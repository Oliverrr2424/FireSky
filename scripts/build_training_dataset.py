from __future__ import annotations

import argparse
import json
import math
import time
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd
import requests


ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "data_sources"
OUT = DATA / "modeling"
CACHE = OUT / "cache"

WEATHER_VARS = [
    "temperature_2m",
    "dew_point_2m",
    "relative_humidity_2m",
    "precipitation",
    "cloud_cover",
    "cloud_cover_low",
    "cloud_cover_mid",
    "cloud_cover_high",
    "pressure_msl",
    "surface_pressure",
    "visibility",
    "wind_speed_10m",
    "wind_gusts_10m",
    "weather_code",
    "shortwave_radiation",
    "direct_radiation",
    "diffuse_radiation",
    "direct_normal_irradiance",
    "sunshine_duration",
    "vapour_pressure_deficit",
]

AIR_VARS = [
    "us_aqi",
    "pm2_5",
    "pm10",
    "carbon_monoxide",
    "nitrogen_dioxide",
    "sulphur_dioxide",
    "ozone",
    "aerosol_optical_depth",
    "dust",
]

QUALITY_ORDINAL = {
    "基本不烧": 0,
    "不烧": 0,
    "微烧": 1,
    "小烧": 2,
    "小到中烧": 3,
    "中到大烧": 4,
    "大烧": 5,
}

CITY_COORDS = {
    "Boston": (42.3601, -71.0589),
    "Chicago": (41.8781, -87.6298),
    "Los_Angeles": (34.0522, -118.2437),
    "Miami": (25.7617, -80.1918),
    "NYC": (40.7128, -74.0060),
    "Philadelphia": (39.9526, -75.1652),
    "San_Diego": (32.7157, -117.1611),
    "San_Francisco": (37.7749, -122.4194),
    "Seattle": (47.6062, -122.3321),
    "Washington_DC": (38.9072, -77.0369),
    "Shanghai": (31.2304, 121.4737),
}


@dataclass(frozen=True)
class LocationRange:
    name: str
    latitude: float
    longitude: float
    start_date: str
    end_date: str


def request_json(url: str, params: dict[str, Any], cache_name: str, sleep_seconds: float) -> dict[str, Any]:
    CACHE.mkdir(parents=True, exist_ok=True)
    cache_file = CACHE / f"{cache_name}.json"
    if cache_file.exists():
        return json.loads(cache_file.read_text(encoding="utf-8"))

    for attempt in range(4):
        try:
            response = requests.get(url, params=params, timeout=45, headers={"Accept": "application/json"})
            response.raise_for_status()
            payload = response.json()
            cache_file.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
            time.sleep(sleep_seconds)
            return payload
        except requests.RequestException:
            if attempt == 3:
                raise
            time.sleep(2**attempt)

    raise RuntimeError("unreachable")


def load_hourly(payload: dict[str, Any], variables: list[str], prefix: str) -> pd.DataFrame:
    hourly = payload.get("hourly") or {}
    if "time" not in hourly:
        return pd.DataFrame()
    frame = pd.DataFrame({"time": pd.to_datetime(hourly["time"])})
    for var in variables:
        values = hourly.get(var)
        frame[f"{prefix}{var}"] = values if values is not None else np.nan
    return frame


def load_daily(payload: dict[str, Any]) -> pd.DataFrame:
    daily = payload.get("daily") or {}
    if "time" not in daily:
        return pd.DataFrame(columns=["date", "sunrise", "sunset"])
    dates = pd.Series(pd.to_datetime(daily["time"]))
    return pd.DataFrame(
        {
            "date": dates.dt.date.astype(str),
            "sunrise": pd.to_datetime(daily.get("sunrise", []), errors="coerce"),
            "sunset": pd.to_datetime(daily.get("sunset", []), errors="coerce"),
        }
    )


def fetch_location_range(location: LocationRange, sleep_seconds: float) -> tuple[pd.DataFrame, pd.DataFrame]:
    base_params = {
        "latitude": str(location.latitude),
        "longitude": str(location.longitude),
        "start_date": location.start_date,
        "end_date": location.end_date,
        "timezone": "auto",
    }
    weather = request_json(
        "https://archive-api.open-meteo.com/v1/archive",
        {
            **base_params,
            "hourly": ",".join(WEATHER_VARS),
            "daily": "sunrise,sunset",
            "wind_speed_unit": "kmh",
        },
        f"weather_{location.name}_{location.start_date}_{location.end_date}",
        sleep_seconds,
    )
    air = request_json(
        "https://air-quality-api.open-meteo.com/v1/air-quality",
        {**base_params, "hourly": ",".join(AIR_VARS)},
        f"air_{location.name}_{location.start_date}_{location.end_date}",
        sleep_seconds,
    )

    weather_hourly = load_hourly(weather, WEATHER_VARS, "")
    air_hourly = load_hourly(air, AIR_VARS, "air_")
    hourly = pd.merge(weather_hourly, air_hourly, on="time", how="outer").sort_values("time")
    daily = load_daily(weather)
    return hourly, daily


def clean_stanford_labels() -> pd.DataFrame:
    path = DATA / "stanford_sunsets" / "sunset_quality_scores.csv"
    frame = pd.read_csv(path)
    first_col = frame.columns[0]
    frame = frame.rename(columns={first_col: "row_id"})
    frame["source"] = "stanford"
    frame["city"] = frame["City"]
    frame["event"] = "sunset"
    frame["label_good"] = frame["Good Sunset"].astype(float).astype(int)
    frame["quality_ordinal"] = np.where(frame["label_good"].eq(1), 3, 0)
    frame["quality_source_value"] = frame["Residual After Controlling for Time"].astype(float)
    frame["raw_posts"] = frame["Raw Number of Posts"].astype(float)
    frame["is_primary_train"] = True
    frame["is_selection_biased"] = False
    rows = frame[
        [
            "source",
            "city",
            "Date",
            "event",
            "label_good",
            "quality_ordinal",
            "quality_source_value",
            "raw_posts",
            "is_primary_train",
            "is_selection_biased",
        ]
    ].rename(columns={"Date": "date"})
    rows["latitude"] = rows["city"].map(lambda value: CITY_COORDS[value][0])
    rows["longitude"] = rows["city"].map(lambda value: CITY_COORDS[value][1])
    return rows


def clean_sunsetbot_reanalysis() -> pd.DataFrame:
    path = DATA / "sunsetbot" / "sunsetbot_reanalysis_events.csv"
    frame = pd.read_csv(path)
    frame["source"] = "sunsetbot_reanalysis"
    frame["city"] = "Shanghai"
    frame["event"] = frame["event"].map({"日出": "sunrise", "日落": "sunset"}).fillna(frame["event"])
    frame["quality_ordinal"] = frame["actual_quality"].map(QUALITY_ORDINAL)
    frame["label_good"] = frame["quality_ordinal"].ge(3).astype(int)
    frame["quality_source_value"] = frame["quality_ordinal"].astype(float)
    frame["raw_posts"] = np.nan
    frame["is_primary_train"] = True
    frame["is_selection_biased"] = False
    frame["latitude"] = CITY_COORDS["Shanghai"][0]
    frame["longitude"] = CITY_COORDS["Shanghai"][1]
    return frame[
        [
            "source",
            "city",
            "date",
            "event",
            "label_good",
            "quality_ordinal",
            "quality_source_value",
            "raw_posts",
            "is_primary_train",
            "is_selection_biased",
            "latitude",
            "longitude",
            "observed_cloud",
            "color",
            "actual_quality",
            "predicted_quality",
            "consistency",
            "sky_condition",
            "case_url",
        ]
    ]


def clean_sunsetbot_record() -> pd.DataFrame:
    path = DATA / "sunsetbot" / "sunsetbot_record_events.csv"
    frame = pd.read_csv(path)
    frame["source"] = "sunsetbot_record"
    frame["city"] = "Shanghai"
    frame["event"] = frame["event"].map({"日出": "sunrise", "日落": "sunset"}).fillna(frame["event"])
    frame["quality_ordinal"] = frame["quality"].map(QUALITY_ORDINAL)
    frame["label_good"] = frame["quality_ordinal"].ge(3).astype(int)
    frame["quality_source_value"] = frame["quality_ordinal"].astype(float)
    frame["raw_posts"] = np.nan
    frame["is_primary_train"] = False
    frame["is_selection_biased"] = True
    frame["latitude"] = CITY_COORDS["Shanghai"][0]
    frame["longitude"] = CITY_COORDS["Shanghai"][1]
    frame["actual_quality"] = frame["quality"]
    frame["predicted_quality"] = np.nan
    frame["consistency"] = np.nan
    return frame[
        [
            "source",
            "city",
            "date",
            "event",
            "label_good",
            "quality_ordinal",
            "quality_source_value",
            "raw_posts",
            "is_primary_train",
            "is_selection_biased",
            "latitude",
            "longitude",
            "observed_cloud",
            "color",
            "actual_quality",
            "predicted_quality",
            "consistency",
            "sky_condition",
            "case_url",
        ]
    ]


def make_location_ranges(labels: pd.DataFrame) -> list[LocationRange]:
    ranges = []
    for city, group in labels.groupby("city"):
        latitude, longitude = CITY_COORDS[city]
        dates = pd.to_datetime(group["date"])
        start = dates.min().strftime("%Y-%m-%d")
        end = dates.max().strftime("%Y-%m-%d")
        if city == "Shanghai" and pd.to_datetime(end) > pd.to_datetime("2024-12-31"):
            # Keep cache chunks manageable; Open-Meteo supports longer ranges, but small chunks retry better.
            year_groups = group.assign(year=dates.dt.year).groupby("year")
            for year, year_group in year_groups:
                year_dates = pd.to_datetime(year_group["date"])
                ranges.append(
                    LocationRange(
                        f"{city}_{year}",
                        latitude,
                        longitude,
                        year_dates.min().strftime("%Y-%m-%d"),
                        year_dates.max().strftime("%Y-%m-%d"),
                    )
                )
        else:
            ranges.append(LocationRange(city, latitude, longitude, start, end))
    return ranges


def nearest_value(frame: pd.DataFrame, event_time: pd.Timestamp, column: str) -> float:
    if frame.empty or column not in frame:
        return math.nan
    distances = (frame["time"] - event_time).abs()
    if distances.isna().all():
        return math.nan
    index = distances.idxmin()
    value = frame.loc[index, column]
    return float(value) if pd.notna(value) else math.nan


def window_stats(frame: pd.DataFrame, event_time: pd.Timestamp, column: str, start_hours: int, end_hours: int) -> dict[str, float]:
    start = event_time + timedelta(hours=start_hours)
    end = event_time + timedelta(hours=end_hours)
    window = frame[(frame["time"] >= start) & (frame["time"] <= end)]
    if window.empty or column not in window:
        return {"mean": math.nan, "min": math.nan, "max": math.nan}
    values = pd.to_numeric(window[column], errors="coerce")
    return {"mean": values.mean(), "min": values.min(), "max": values.max()}


def number_or(value: Any, fallback: float = 0.0) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return fallback
    return number if math.isfinite(number) else fallback


def enrich_row(row: pd.Series, weather_by_city: dict[str, tuple[pd.DataFrame, pd.DataFrame]]) -> dict[str, Any]:
    city_key = row["city"]
    if city_key == "Shanghai":
        city_key = f"Shanghai_{pd.to_datetime(row['date']).year}"
    hourly, daily = weather_by_city[city_key]
    date = pd.to_datetime(row["date"]).date().isoformat()
    daily_row = daily[daily["date"] == date]
    event_col = "sunrise" if row["event"] == "sunrise" else "sunset"
    event_time = daily_row[event_col].iloc[0] if not daily_row.empty else pd.NaT
    result: dict[str, Any] = {"event_time": event_time.isoformat() if pd.notna(event_time) else ""}
    if pd.isna(event_time):
        return result

    all_vars = WEATHER_VARS + [f"air_{var}" for var in AIR_VARS]
    for var in all_vars:
        result[f"{var}_event"] = nearest_value(hourly, event_time, var)
        for label, start, end in [
            ("pre3h", -3, 0),
            ("near", -1, 1),
            ("post2h", 0, 2),
        ]:
            stats = window_stats(hourly, event_time, var, start, end)
            for stat_name, value in stats.items():
                result[f"{var}_{label}_{stat_name}"] = value

    result["mid_high_cloud_event"] = np.nanmean(
        [result.get("cloud_cover_mid_event", np.nan), result.get("cloud_cover_high_event", np.nan)]
    )
    result["cloud_screen_event"] = result.get("mid_high_cloud_event", np.nan) - 0.45 * result.get("cloud_cover_low_event", np.nan)
    result["light_path_score_event"] = (
        0.45 * number_or(result.get("cloud_cover_high_event"))
        + 0.35 * number_or(result.get("cloud_cover_mid_event"))
        - 0.35 * number_or(result.get("cloud_cover_low_event"))
        - 0.18 * number_or(result.get("relative_humidity_2m_event"))
    )
    haze_values = [
        result.get("air_pm2_5_event", np.nan),
        result.get("air_pm10_event", np.nan),
        100 * number_or(result.get("air_aerosol_optical_depth_event"), np.nan),
    ]
    result["aerosol_haze_event"] = float(np.nanmean(haze_values)) if not np.isnan(haze_values).all() else np.nan
    result["clean_air_proxy_event"] = (
        -0.03 * number_or(result.get("air_pm2_5_event"))
        - 0.02 * number_or(result.get("air_pm10_event"))
        - 2.0 * number_or(result.get("air_aerosol_optical_depth_event"))
    )
    result["season_sin"] = math.sin(2 * math.pi * pd.to_datetime(row["date"]).dayofyear / 365.25)
    result["season_cos"] = math.cos(2 * math.pi * pd.to_datetime(row["date"]).dayofyear / 365.25)
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sleep", type=float, default=0.25, help="Delay after uncached Open-Meteo requests.")
    args = parser.parse_args()

    OUT.mkdir(parents=True, exist_ok=True)
    labels = pd.concat(
        [clean_stanford_labels(), clean_sunsetbot_reanalysis(), clean_sunsetbot_record()],
        ignore_index=True,
        sort=False,
    )
    labels["date"] = pd.to_datetime(labels["date"]).dt.strftime("%Y-%m-%d")
    labels.to_csv(OUT / "labels_unified.csv", index=False, encoding="utf-8")

    weather_by_city: dict[str, tuple[pd.DataFrame, pd.DataFrame]] = {}
    for location in make_location_ranges(labels):
        print(f"Fetching/enriching {location.name} {location.start_date}..{location.end_date}")
        weather_by_city[location.name] = fetch_location_range(location, args.sleep)

    feature_rows = []
    for _, row in labels.iterrows():
        feature_rows.append(enrich_row(row, weather_by_city))
    features = pd.DataFrame(feature_rows)
    dataset = pd.concat([labels.reset_index(drop=True), features.reset_index(drop=True)], axis=1)
    dataset.to_csv(OUT / "firesky_training_dataset.csv", index=False, encoding="utf-8")

    summary = {
        "rows": int(len(dataset)),
        "primary_train_rows": int(dataset["is_primary_train"].sum()),
        "supplemental_rows": int((~dataset["is_primary_train"]).sum()),
        "sources": dataset["source"].value_counts(dropna=False).to_dict(),
        "primary_label_distribution": dataset[dataset["is_primary_train"]]["label_good"].value_counts(dropna=False).to_dict(),
        "output": str(OUT / "firesky_training_dataset.csv"),
    }
    (OUT / "dataset_summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
