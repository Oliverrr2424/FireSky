"""Firesky v2 training pipeline.

Builds features from the raw Open-Meteo cache + raw labels (Stanford "Good
Sunset" residual + sunsetbot ERA-5 reanalysis), and trains a LightGBM
classifier for predicting 火烧云 (vivid sunrise/sunset).

The script is fully self-contained: it only reads from `data_sources/raw/`
(weather/air JSON caches + label files) and writes artifacts to
`data_sources/modeling_v2/`.

Target metric: recall >= 0.90 with accuracy as high as possible
(threshold-tuned on a validation split).
"""

from __future__ import annotations

import json
import math
import sys
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd
from lightgbm import LGBMClassifier, LGBMRegressor
from xgboost import XGBClassifier
from sklearn.metrics import (
    accuracy_score,
    average_precision_score,
    classification_report,
    confusion_matrix,
    f1_score,
    precision_score,
    recall_score,
    roc_auc_score,
)
from sklearn.model_selection import StratifiedKFold

try:
    from astral import Observer
    from astral.sun import azimuth as solar_azimuth
    HAS_ASTRAL = True
except ImportError:
    HAS_ASTRAL = False

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / "data_sources" / "raw"
GRID_CACHE = RAW / "open_meteo_grid_cache"
UPPER_CACHE = RAW / "open_meteo_upper_cache"
OUT = ROOT / "data_sources" / "modeling_v2"
OUT.mkdir(parents=True, exist_ok=True)

UPPER_VARS = [
    "cape",
    "freezing_level_height",
    "wind_direction_500hPa",
    "wind_speed_500hPa",
    "wind_direction_700hPa",
    "wind_speed_700hPa",
    "wind_direction_850hPa",
    "wind_speed_850hPa",
    "relative_humidity_500hPa",
    "relative_humidity_700hPa",
    "relative_humidity_850hPa",
    "geopotential_height_500hPa",
    "geopotential_height_700hPa",
    "temperature_500hPa",
    "temperature_700hPa",
]

GRID_BEARINGS = list(range(0, 360, 30))
GRID_DISTANCES = [60, 150, 300]
GRID_VARS = [
    "cloud_cover",
    "cloud_cover_low",
    "cloud_cover_mid",
    "cloud_cover_high",
    "visibility",
    "relative_humidity_2m",
    "precipitation",
    "wind_direction_10m",
    "wind_speed_10m",
]

# Approximate UTC offset for each city center, used to convert local
# event_time -> UTC when calling astral. (Open-Meteo daily sunrise/sunset
# are reported in local time with `timezone=auto`.)
CITY_UTC_OFFSET_HOURS = {
    "Shanghai": 8.0,
    "Boston": -5.0,
    "Chicago": -6.0,
    "Los_Angeles": -8.0,
    "Miami": -5.0,
    "NYC": -5.0,
    "Philadelphia": -5.0,
    "San_Diego": -8.0,
    "San_Francisco": -8.0,
    "Seattle": -8.0,
    "Washington_DC": -5.0,
}

WEATHER_HOURLY_VARS = [
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

AIR_HOURLY_VARS = [
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

# 火烧云 ordinal levels (中文 sunsetbot 等级)
QUALITY_ORDINAL = {
    "基本不烧": 0,
    "不烧": 0,
    "微烧": 1,
    "小烧": 2,
    "小到中烧": 3,
    "中到大烧": 4,
    "大烧": 5,
}

# Definition of positive class: 小到中烧 and above (ord >= 3) by default.
# Tunable via environment variable FIRESKY_POSITIVE_ORDINAL.
import os as _os
POSITIVE_ORDINAL = int(_os.environ.get("FIRESKY_POSITIVE_ORDINAL", "3"))

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


# ---------------------------------------------------------------------------
# Raw label loaders
# ---------------------------------------------------------------------------


def load_stanford_labels() -> pd.DataFrame:
    """Stanford supplementary - daily Instagram-residual based good sunset."""
    csv = RAW / "stanford_supplementary" / "sunset_quality_scores.csv"
    frame = pd.read_csv(csv)
    frame = frame.rename(columns={frame.columns[0]: "row_id"})
    frame["source"] = "stanford"
    frame["city"] = frame["City"]
    frame["event"] = "sunset"
    frame["date"] = pd.to_datetime(frame["Date"]).dt.strftime("%Y-%m-%d")
    frame["quality_ordinal"] = np.where(frame["Good Sunset"].astype(float).ge(0.5), 4, 0)
    frame["label_good"] = frame["quality_ordinal"].ge(POSITIVE_ORDINAL).astype(int)
    frame["residual"] = frame["Residual After Controlling for Time"].astype(float)
    return frame[
        ["source", "city", "date", "event", "label_good", "quality_ordinal", "residual"]
    ]


def load_sunsetbot_record_curated() -> pd.DataFrame:
    """Sunsetbot curated case-study records (selection-biased, mostly
    positive). Columns of the raw list:
    [date, event, observed_cloud, color, quality, sky_condition, case_url]."""
    path = RAW / "sunsetbot_scrape" / "record_custom_all.json"
    payload = json.loads(path.read_text(encoding="utf-8"))
    rows = payload.get("table_content", [])
    if not rows:
        return pd.DataFrame()
    frame = pd.DataFrame(rows, columns=["date", "event", "observed_cloud", "color", "quality", "sky_condition", "case_url"])
    frame["source"] = "sunsetbot_record"
    frame["city"] = "Shanghai"
    frame["event"] = frame["event"].map({"日出": "sunrise", "日落": "sunset"}).fillna("sunset")
    frame["quality_ordinal"] = frame["quality"].map(QUALITY_ORDINAL).fillna(0).astype(int)
    frame["label_good"] = frame["quality_ordinal"].ge(POSITIVE_ORDINAL).astype(int)
    frame["residual"] = np.nan
    return frame[
        ["source", "city", "date", "event", "label_good", "quality_ordinal", "residual"]
    ]


def load_sunsetbot_reanalysis() -> pd.DataFrame:
    """Sunsetbot ERA-5 reanalysis labels for Shanghai (cleanest labels)."""
    path = RAW / "sunsetbot_scrape" / "reanalysis_all_chunked.json"
    payload = json.loads(path.read_text(encoding="utf-8"))
    rows = payload["table_content"]
    frame = pd.DataFrame(rows)
    frame["source"] = "sunsetbot_reanalysis"
    frame["city"] = "Shanghai"
    frame["event"] = frame["event"].map({"日出": "sunrise", "日落": "sunset"}).fillna("sunset")
    frame["quality_ordinal"] = frame["actual_quality"].map(QUALITY_ORDINAL).fillna(0).astype(int)
    frame["label_good"] = frame["quality_ordinal"].ge(POSITIVE_ORDINAL).astype(int)
    frame["residual"] = np.nan
    return frame[
        ["source", "city", "date", "event", "label_good", "quality_ordinal", "residual"]
    ]


# ---------------------------------------------------------------------------
# Weather / air loading and feature engineering
# ---------------------------------------------------------------------------


def _load_open_meteo_cache_for_city(city: str) -> tuple[pd.DataFrame, pd.DataFrame]:
    """Concat all cache chunks for a city and return (hourly_df, daily_df)."""
    cache_dir = RAW / "open_meteo_api_cache"
    weather_files = sorted(cache_dir.glob(f"weather_{city}_*.json"))
    air_files = sorted(cache_dir.glob(f"air_{city}_*.json"))
    if not weather_files:
        raise FileNotFoundError(f"No weather cache for {city}")

    hourly_chunks = []
    daily_chunks = []
    for f in weather_files:
        payload = json.loads(f.read_text(encoding="utf-8"))
        hourly = payload.get("hourly", {})
        if not hourly:
            continue
        df = pd.DataFrame({"time": pd.to_datetime(hourly["time"])})
        for var in WEATHER_HOURLY_VARS:
            df[var] = hourly.get(var, [np.nan] * len(df))
        hourly_chunks.append(df)
        daily = payload.get("daily", {})
        if daily and "time" in daily:
            ddf = pd.DataFrame(
                {
                    "date": pd.to_datetime(daily["time"]).strftime("%Y-%m-%d"),
                    "sunrise": pd.to_datetime(daily.get("sunrise", []), errors="coerce"),
                    "sunset": pd.to_datetime(daily.get("sunset", []), errors="coerce"),
                }
            )
            daily_chunks.append(ddf)

    # Air quality merge
    air_chunks = []
    for f in air_files:
        payload = json.loads(f.read_text(encoding="utf-8"))
        hourly = payload.get("hourly", {})
        if not hourly:
            continue
        df = pd.DataFrame({"time": pd.to_datetime(hourly["time"])})
        for var in AIR_HOURLY_VARS:
            df[f"air_{var}"] = hourly.get(var, [np.nan] * len(df))
        air_chunks.append(df)

    weather_df = pd.concat(hourly_chunks, ignore_index=True).drop_duplicates("time").sort_values("time")
    if air_chunks:
        air_df = pd.concat(air_chunks, ignore_index=True).drop_duplicates("time").sort_values("time")
        hourly_df = pd.merge(weather_df, air_df, on="time", how="left")
    else:
        hourly_df = weather_df
    hourly_df = hourly_df.reset_index(drop=True)
    daily_df = (
        pd.concat(daily_chunks, ignore_index=True).drop_duplicates("date").sort_values("date").reset_index(drop=True)
        if daily_chunks
        else pd.DataFrame(columns=["date", "sunrise", "sunset"])
    )
    return hourly_df, daily_df


def _safe_stats(values: pd.Series) -> dict[str, float]:
    arr = pd.to_numeric(values, errors="coerce").dropna()
    if arr.empty:
        return {"mean": np.nan, "min": np.nan, "max": np.nan, "std": np.nan}
    return {
        "mean": float(arr.mean()),
        "min": float(arr.min()),
        "max": float(arr.max()),
        "std": float(arr.std()) if len(arr) > 1 else 0.0,
    }


def _window_features(hourly_df: pd.DataFrame, event_time: pd.Timestamp) -> dict[str, float]:
    feats: dict[str, float] = {}
    if pd.isna(event_time):
        return feats

    all_vars = WEATHER_HOURLY_VARS + [f"air_{v}" for v in AIR_HOURLY_VARS]

    # Pick nearest hour for event-time value.
    diffs = (hourly_df["time"] - event_time).abs()
    if diffs.notna().any():
        idx = diffs.idxmin()
        for var in all_vars:
            if var in hourly_df:
                value = hourly_df.at[idx, var]
                feats[f"{var}_at_event"] = float(value) if pd.notna(value) else np.nan

    # Three windows around the event.
    for label, start_h, end_h in [
        ("pre3h", -3, 0),
        ("near", -1, 1),
        ("post2h", 0, 2),
        ("pre6h", -6, -3),
    ]:
        window = hourly_df[
            (hourly_df["time"] >= event_time + timedelta(hours=start_h))
            & (hourly_df["time"] <= event_time + timedelta(hours=end_h))
        ]
        for var in all_vars:
            if var not in window:
                continue
            stats = _safe_stats(window[var])
            for stat_name, value in stats.items():
                feats[f"{var}_{label}_{stat_name}"] = value
    return feats


# ---------------------------------------------------------------------------
# Directional grid (solar light-path) features
# ---------------------------------------------------------------------------


def load_grid_for_city(city: str) -> dict[tuple[int, int], pd.DataFrame]:
    """Return {(bearing, distance_km): hourly DataFrame} or {} if none cached."""
    grid: dict[tuple[int, int], pd.DataFrame] = {}
    for b in GRID_BEARINGS:
        for d in GRID_DISTANCES:
            files = sorted(GRID_CACHE.glob(f"weather_{city}_b{b:03d}_d{d:03d}_*.json"))
            if not files:
                continue
            chunks = []
            for f in files:
                payload = json.loads(f.read_text(encoding="utf-8"))
                hourly = payload.get("hourly", {})
                if not hourly:
                    continue
                df = pd.DataFrame({"time": pd.to_datetime(hourly["time"])})
                for v in GRID_VARS:
                    df[v] = hourly.get(v, [np.nan] * len(df))
                chunks.append(df)
            if chunks:
                grid[(b, d)] = (
                    pd.concat(chunks, ignore_index=True)
                    .drop_duplicates("time")
                    .sort_values("time")
                    .reset_index(drop=True)
                )
    return grid


def load_upper_for_city(city: str) -> pd.DataFrame:
    files = sorted(UPPER_CACHE.glob(f"upper_{city}_*.json"))
    if not files:
        return pd.DataFrame()
    chunks = []
    for f in files:
        payload = json.loads(f.read_text(encoding="utf-8"))
        hourly = payload.get("hourly", {})
        if not hourly:
            continue
        df = pd.DataFrame({"time": pd.to_datetime(hourly["time"])})
        for v in UPPER_VARS:
            df[v] = hourly.get(v, [np.nan] * len(df))
        chunks.append(df)
    if not chunks:
        return pd.DataFrame()
    return pd.concat(chunks, ignore_index=True).drop_duplicates("time").sort_values("time").reset_index(drop=True)


def _upper_features(upper_df: pd.DataFrame, event_time: pd.Timestamp, azimuth_deg: float) -> dict[str, float]:
    out: dict[str, float] = {}
    if upper_df.empty:
        return out
    diffs = (upper_df["time"] - event_time).abs()
    if not diffs.notna().any():
        return out
    idx = diffs.idxmin()
    for v in UPPER_VARS:
        value = upper_df.at[idx, v]
        out[f"upper_{v}_at_event"] = float(value) if pd.notna(value) else np.nan

    # 6-hour mean before event for stability vars.
    mask = (upper_df["time"] >= event_time - timedelta(hours=6)) & (upper_df["time"] <= event_time)
    window = upper_df.loc[mask]
    for v in ["cape", "freezing_level_height", "geopotential_height_500hPa", "relative_humidity_500hPa", "relative_humidity_700hPa"]:
        vals = pd.to_numeric(window[v], errors="coerce").dropna() if v in window else []
        out[f"upper_{v}_pre6h_mean"] = float(np.mean(vals)) if len(vals) else np.nan

    # Wind direction relative to sun azimuth at 500/700/850 hPa.
    for level in ["500hPa", "700hPa", "850hPa"]:
        wd = out.get(f"upper_wind_direction_{level}_at_event")
        ws = out.get(f"upper_wind_speed_{level}_at_event")
        if wd is not None and not (isinstance(wd, float) and math.isnan(wd)):
            rel = ((wd - azimuth_deg + 180) % 360) - 180
            out[f"upper_wind_rel_sun_{level}"] = rel
            out[f"upper_wind_rel_sun_cos_{level}"] = math.cos(math.radians(rel))
            if ws is not None and not (isinstance(ws, float) and math.isnan(ws)):
                out[f"upper_wind_advection_{level}"] = ws * math.cos(math.radians(rel))
    return out


def _angular_distance(a: float, b: float) -> float:
    d = abs(a - b) % 360
    return min(d, 360 - d)


def _interp_two_nearest(grid: dict[tuple[int, int], pd.DataFrame], azimuth_deg: float, distance: int, event_time: pd.Timestamp, var: str) -> float:
    bearings_available = sorted({b for (b, d) in grid if d == distance})
    if not bearings_available:
        return np.nan
    ordered = sorted(bearings_available, key=lambda x: _angular_distance(x, azimuth_deg))
    b1, b2 = ordered[0], ordered[1] if len(ordered) > 1 else ordered[0]
    d1 = _angular_distance(b1, azimuth_deg)
    d2 = _angular_distance(b2, azimuth_deg)
    total = d1 + d2
    if total == 0:
        w1, w2 = 1.0, 0.0
    else:
        w1, w2 = d2 / total, d1 / total

    def value_at(b: int) -> float:
        df = grid[(b, distance)]
        diffs = (df["time"] - event_time).abs()
        if diffs.notna().any():
            idx = diffs.idxmin()
            v = df.at[idx, var]
            return float(v) if pd.notna(v) else np.nan
        return np.nan

    v1 = value_at(b1)
    v2 = value_at(b2)
    if math.isnan(v1) and math.isnan(v2):
        return np.nan
    if math.isnan(v1):
        return v2
    if math.isnan(v2):
        return v1
    return w1 * v1 + w2 * v2


def _grid_window_mean(grid: dict[tuple[int, int], pd.DataFrame], azimuth_deg: float, distance: int, event_time: pd.Timestamp, var: str, start_h: int, end_h: int) -> float:
    bearings_available = sorted({b for (b, d) in grid if d == distance})
    if not bearings_available:
        return np.nan
    ordered = sorted(bearings_available, key=lambda x: _angular_distance(x, azimuth_deg))
    b1, b2 = ordered[0], ordered[1] if len(ordered) > 1 else ordered[0]
    d1 = _angular_distance(b1, azimuth_deg)
    d2 = _angular_distance(b2, azimuth_deg)
    total = d1 + d2
    w1, w2 = (1.0, 0.0) if total == 0 else (d2 / total, d1 / total)

    def window_mean(b: int) -> float:
        df = grid[(b, distance)]
        mask = (df["time"] >= event_time + timedelta(hours=start_h)) & (
            df["time"] <= event_time + timedelta(hours=end_h)
        )
        vals = pd.to_numeric(df.loc[mask, var], errors="coerce").dropna()
        return float(vals.mean()) if not vals.empty else np.nan

    v1 = window_mean(b1)
    v2 = window_mean(b2)
    if math.isnan(v1) and math.isnan(v2):
        return np.nan
    if math.isnan(v1):
        return v2
    if math.isnan(v2):
        return v1
    return w1 * v1 + w2 * v2


def _path_features(
    grid: dict[tuple[int, int], pd.DataFrame],
    upper_df: pd.DataFrame,
    city: str,
    event_time: pd.Timestamp,
    event: str,
    lat: float,
    lon: float,
) -> dict[str, float]:
    out: dict[str, float] = {}
    if not HAS_ASTRAL:
        return out
    offset_hours = CITY_UTC_OFFSET_HOURS.get(city, 0.0)
    event_utc = (event_time - timedelta(hours=offset_hours)).to_pydatetime()
    try:
        obs = Observer(latitude=lat, longitude=lon)
        az = float(solar_azimuth(obs, event_utc))
    except Exception:
        return out
    if not grid:
        # Still emit upper-air features (do not return early).
        out.update(_upper_features(upper_df, event_time, az))
        return out

    out["solar_azimuth"] = az
    out["solar_azimuth_sin"] = math.sin(math.radians(az))
    out["solar_azimuth_cos"] = math.cos(math.radians(az))

    for distance in GRID_DISTANCES:
        for var in GRID_VARS:
            tag = f"path_{var}_d{distance}_at_event"
            out[tag] = _interp_two_nearest(grid, az, distance, event_time, var)
            tag2 = f"path_{var}_d{distance}_pre3h_mean"
            out[tag2] = _grid_window_mean(grid, az, distance, event_time, var, -3, 0)

    # Anti-azimuth reference (opposite side of sky) to expose asymmetries.
    anti = (az + 180.0) % 360
    for distance in GRID_DISTANCES:
        for var in ["cloud_cover_low", "cloud_cover_mid", "cloud_cover_high", "visibility"]:
            out[f"anti_{var}_d{distance}_at_event"] = _interp_two_nearest(grid, anti, distance, event_time, var)

    # Derived light-path scores.
    def g(key: str) -> float:
        v = out.get(key)
        return float(v) if v is not None and not (isinstance(v, float) and math.isnan(v)) else np.nan

    for distance in GRID_DISTANCES:
        low = g(f"path_cloud_cover_low_d{distance}_at_event")
        mid = g(f"path_cloud_cover_mid_d{distance}_at_event")
        high = g(f"path_cloud_cover_high_d{distance}_at_event")
        vis = g(f"path_visibility_d{distance}_at_event")
        if not (math.isnan(low) or math.isnan(mid) or math.isnan(high)):
            out[f"path_light_score_d{distance}"] = 0.5 * high + 0.35 * mid - 0.55 * low
            out[f"path_mid_high_minus_low_d{distance}"] = (high + mid) / 2.0 - 0.5 * low
        if not math.isnan(low) and not math.isnan(vis):
            out[f"path_horizon_clear_d{distance}"] = (max(0.0, 100.0 - low) / 100.0) * vis

    # Far vs near light-path differential (positive => far horizon clearer
    # than near sky, often a good firesky setup).
    for var in ["cloud_cover_low", "cloud_cover_mid", "cloud_cover_high", "visibility"]:
        near = g(f"path_{var}_d60_at_event")
        far = g(f"path_{var}_d300_at_event")
        if not math.isnan(near) and not math.isnan(far):
            out[f"path_{var}_far_minus_near"] = far - near

    # Wind direction relative to sun azimuth -- captures whether clouds are
    # being advected toward (negative) or away from (positive) the sun
    # direction along the light path.
    for distance in GRID_DISTANCES:
        wd = g(f"path_wind_direction_10m_d{distance}_at_event")
        ws = g(f"path_wind_speed_10m_d{distance}_at_event")
        if not math.isnan(wd):
            rel = ((wd - az + 180) % 360) - 180
            out[f"path_wind_rel_sun_d{distance}"] = rel
            out[f"path_wind_rel_sun_cos_d{distance}"] = math.cos(math.radians(rel))
            if not math.isnan(ws):
                out[f"path_wind_advection_d{distance}"] = ws * math.cos(math.radians(rel))

    # Upper-air / stability features at city centre.
    out.update(_upper_features(upper_df, event_time, az))
    return out


def _trend_features(feats: dict[str, float]) -> dict[str, float]:
    """Cloud / humidity / radiation trends -- difference between earlier and
    later windows. These often capture 'clearing in the west' style signal."""
    extra: dict[str, float] = {}
    for var in [
        "cloud_cover",
        "cloud_cover_low",
        "cloud_cover_mid",
        "cloud_cover_high",
        "relative_humidity_2m",
        "visibility",
        "direct_radiation",
        "diffuse_radiation",
        "shortwave_radiation",
        "wind_speed_10m",
        "air_pm2_5",
        "air_aerosol_optical_depth",
    ]:
        early = feats.get(f"{var}_pre6h_mean")
        late = feats.get(f"{var}_near_mean")
        if early is not None and late is not None and not (
            isinstance(early, float) and math.isnan(early)
        ) and not (isinstance(late, float) and math.isnan(late)):
            extra[f"{var}_trend_6to_near"] = float(late) - float(early)
    return extra


def _physical_features(feats: dict[str, float], date: str) -> dict[str, float]:
    def g(name: str, default: float = np.nan) -> float:
        v = feats.get(name, default)
        try:
            return float(v) if v is not None and not (isinstance(v, float) and math.isnan(v)) else default
        except (TypeError, ValueError):
            return default

    extra: dict[str, float] = {}
    low = g("cloud_cover_low_pre3h_mean")
    mid = g("cloud_cover_mid_pre3h_mean")
    high = g("cloud_cover_high_pre3h_mean")
    total = g("cloud_cover_pre3h_mean")
    rh = g("relative_humidity_2m_pre3h_mean")
    vis = g("visibility_pre3h_mean")
    aod = g("air_aerosol_optical_depth_pre3h_mean")
    pm25 = g("air_pm2_5_pre3h_mean")
    pm10 = g("air_pm10_pre3h_mean")
    cape = g("cape_pre3h_mean")  # may be missing; harmless
    direct = g("direct_radiation_pre3h_mean")
    diffuse = g("diffuse_radiation_pre3h_mean")
    sunshine = g("sunshine_duration_pre3h_mean")
    precip_prob = g("precipitation_probability_pre3h_mean")
    precip = g("precipitation_pre3h_mean")

    if not math.isnan(low) and not math.isnan(mid) and not math.isnan(high):
        extra["mid_high_cloud_mean"] = float(np.nanmean([mid, high]))
        extra["cloud_screen"] = (mid + high) / 2.0 - 0.45 * low
        extra["light_path_score"] = (
            0.45 * (high if not math.isnan(high) else 0)
            + 0.35 * (mid if not math.isnan(mid) else 0)
            - 0.35 * (low if not math.isnan(low) else 0)
            - 0.18 * (rh if not math.isnan(rh) else 0)
        )
    if not math.isnan(direct) and not math.isnan(diffuse):
        extra["direct_diffuse_ratio"] = direct / (diffuse + 1e-3)
    if not math.isnan(aod) and not math.isnan(pm25):
        extra["aerosol_combo"] = (
            (pm25 if not math.isnan(pm25) else 0)
            + (pm10 if not math.isnan(pm10) else 0)
            + 100 * (aod if not math.isnan(aod) else 0)
        )
    doy = pd.to_datetime(date).dayofyear
    extra["season_sin"] = math.sin(2 * math.pi * doy / 365.25)
    extra["season_cos"] = math.cos(2 * math.pi * doy / 365.25)
    return extra


def build_dataset(labels: pd.DataFrame) -> pd.DataFrame:
    cities_in_labels = labels["city"].unique().tolist()
    cache: dict[str, tuple[pd.DataFrame, pd.DataFrame]] = {}
    grids: dict[str, dict[tuple[int, int], pd.DataFrame]] = {}
    uppers: dict[str, pd.DataFrame] = {}
    for city in cities_in_labels:
        if city not in CITY_COORDS:
            continue
        print(f"  loading raw cache: {city}", flush=True)
        cache[city] = _load_open_meteo_cache_for_city(city)
        grids[city] = load_grid_for_city(city)
        uppers[city] = load_upper_for_city(city)
        if grids[city]:
            print(f"    + directional grid ({len(grids[city])} bearings x distances)")
        if not uppers[city].empty:
            print(f"    + upper-air vars ({len(uppers[city])} hours)")

    rows: list[dict[str, Any]] = []
    for _, label in labels.iterrows():
        city = label["city"]
        if city not in cache:
            continue
        hourly_df, daily_df = cache[city]
        date = label["date"]
        match = daily_df[daily_df["date"] == date]
        if match.empty:
            continue
        event_col = "sunrise" if label["event"] == "sunrise" else "sunset"
        event_time = match[event_col].iloc[0]
        if pd.isna(event_time):
            continue

        feats = _window_features(hourly_df, event_time)
        feats.update(_trend_features(feats))
        feats.update(_physical_features(feats, date))
        lat, lon = CITY_COORDS[city]
        feats.update(_path_features(grids.get(city, {}), uppers.get(city, pd.DataFrame()), city, event_time, label["event"], lat, lon))
        feats["source"] = label["source"]
        feats["city"] = city
        feats["date"] = date
        feats["event"] = label["event"]
        feats["event_is_sunset"] = int(label["event"] == "sunset")
        feats["label_good"] = int(label["label_good"])
        feats["quality_ordinal"] = int(label["quality_ordinal"])
        rows.append(feats)

    dataset = pd.DataFrame(rows)
    return dataset


# ---------------------------------------------------------------------------
# Training
# ---------------------------------------------------------------------------


def tune_threshold(y_true: np.ndarray, proba: np.ndarray, target_recall: float) -> tuple[float, dict[str, float]]:
    """Find lowest threshold whose recall >= target. Among such, prefer
    highest accuracy. Falls back to threshold maximizing F1 if target
    recall is unattainable."""
    thresholds = np.linspace(0.01, 0.99, 99)
    best: tuple[float, float, float] | None = None  # (threshold, accuracy, recall)
    for t in thresholds:
        pred = (proba >= t).astype(int)
        rec = recall_score(y_true, pred, zero_division=0)
        if rec >= target_recall:
            acc = accuracy_score(y_true, pred)
            if best is None or acc > best[1]:
                best = (float(t), float(acc), float(rec))
    if best is None:
        # Recall target unattainable -> fall back to max F1 threshold.
        f1_best = (0.5, -1.0)
        for t in thresholds:
            pred = (proba >= t).astype(int)
            f1 = f1_score(y_true, pred, zero_division=0)
            if f1 > f1_best[1]:
                f1_best = (float(t), float(f1))
        t = f1_best[0]
        pred = (proba >= t).astype(int)
        return t, {
            "threshold": t,
            "fallback": "max_f1",
            "accuracy": float(accuracy_score(y_true, pred)),
            "precision": float(precision_score(y_true, pred, zero_division=0)),
            "recall": float(recall_score(y_true, pred, zero_division=0)),
            "f1": float(f1_score(y_true, pred, zero_division=0)),
        }
    t, acc, rec = best
    pred = (proba >= t).astype(int)
    return t, {
        "threshold": t,
        "accuracy": acc,
        "precision": float(precision_score(y_true, pred, zero_division=0)),
        "recall": rec,
        "f1": float(f1_score(y_true, pred, zero_division=0)),
    }


def train_lgbm(X: pd.DataFrame, y: np.ndarray, scale_pos_weight: float, sample_weight: np.ndarray | None = None) -> LGBMClassifier:
    model = LGBMClassifier(
        n_estimators=2000,
        learning_rate=0.03,
        num_leaves=63,
        max_depth=-1,
        min_child_samples=15,
        reg_alpha=0.1,
        reg_lambda=0.2,
        subsample=0.9,
        colsample_bytree=0.85,
        objective="binary",
        scale_pos_weight=scale_pos_weight,
        random_state=42,
        n_jobs=-1,
        verbosity=-1,
    )
    model.fit(X, y, sample_weight=sample_weight)
    return model


def train_xgb(X: pd.DataFrame, y: np.ndarray, scale_pos_weight: float, sample_weight: np.ndarray | None = None) -> XGBClassifier:
    model = XGBClassifier(
        n_estimators=2000,
        learning_rate=0.03,
        max_depth=6,
        min_child_weight=5,
        subsample=0.9,
        colsample_bytree=0.85,
        reg_alpha=0.1,
        reg_lambda=0.5,
        objective="binary:logistic",
        tree_method="hist",
        scale_pos_weight=scale_pos_weight,
        random_state=42,
        n_jobs=-1,
        verbosity=0,
        eval_metric="auc",
    )
    model.fit(X, y, sample_weight=sample_weight)
    return model


def train_lgbm_ordinal(X: pd.DataFrame, ord_target: np.ndarray, sample_weight: np.ndarray | None = None) -> LGBMRegressor:
    """Regress on the 0-5 ordinal quality scale; downstream code thresholds the
    raw regression output to obtain the firesky probability score."""
    model = LGBMRegressor(
        n_estimators=2500,
        learning_rate=0.025,
        num_leaves=63,
        min_child_samples=12,
        reg_alpha=0.1,
        reg_lambda=0.3,
        subsample=0.9,
        colsample_bytree=0.85,
        objective="regression",
        random_state=42,
        n_jobs=-1,
        verbosity=-1,
    )
    model.fit(X, ord_target, sample_weight=sample_weight)
    return model


def main() -> None:
    print("Loading raw labels...", flush=True)
    use_sources = sys.argv[1] if len(sys.argv) > 1 else "sunsetbot"
    stanford = load_stanford_labels()
    sunsetbot = load_sunsetbot_reanalysis()
    record = load_sunsetbot_record_curated()
    if use_sources == "sunsetbot":
        labels = sunsetbot.copy()
    elif use_sources == "stanford":
        labels = stanford.copy()
    elif use_sources == "sunsetbot_plus":
        # Reanalysis (clean) + curated case studies (selection-biased
        # positives). Deduplicate by (date, event), keeping reanalysis.
        merged = pd.concat([sunsetbot, record], ignore_index=True, sort=False)
        merged = merged.drop_duplicates(subset=["date", "event"], keep="first").reset_index(drop=True)
        labels = merged
    else:
        labels = pd.concat([stanford, sunsetbot, record], ignore_index=True, sort=False)
    labels = labels.dropna(subset=["city", "date", "event"]).reset_index(drop=True)
    print(f"  using sources  : {use_sources}")
    print(f"  stanford rows  : {len(stanford)}")
    print(f"  sunsetbot rows : {len(sunsetbot)}")
    print(f"  record rows    : {len(record)}")
    print(f"  total labels   : {len(labels)}")
    print(f"  positives     : {int(labels['label_good'].sum())} ({labels['label_good'].mean():.2%})")

    print("\nBuilding features from raw Open-Meteo cache...", flush=True)
    dataset = build_dataset(labels)
    dataset.to_csv(OUT / "firesky_v2_features.csv", index=False, encoding="utf-8")
    print(f"  feature matrix shape: {dataset.shape}")

    meta_cols = {"source", "city", "date", "event", "label_good", "quality_ordinal"}
    feature_cols = [c for c in dataset.columns if c not in meta_cols]
    # Drop columns with >98% missing.
    keep = [c for c in feature_cols if dataset[c].isna().mean() < 0.98]
    print(f"  retained {len(keep)} / {len(feature_cols)} numeric features after missing-rate filter")

    X = dataset[keep].astype(float)
    y = dataset["label_good"].astype(int).to_numpy()
    ord_target = dataset["quality_ordinal"].astype(float).to_numpy()
    source = dataset["source"].to_numpy()
    # Sample weight scheme: reanalysis is clean → 1.0; curated case studies
    # are selection-biased toward positives → 0.5.
    sample_weight = np.where(source == "sunsetbot_record", 0.5, 1.0)
    # Clean-evaluation mask: only score on reanalysis rows for OOF metrics
    # (Stanford and record sources are biased / different).
    eval_mask = source == "sunsetbot_reanalysis"
    print(f"  rows: {len(y)} ({eval_mask.sum()} reanalysis used for OOF eval)")

    pos = max(int(y.sum()), 1)
    neg = len(y) - pos
    scale_pos_weight = neg / pos

    print(f"\nTraining LightGBM (cls + ordinal) + XGBoost  5-fold stratified CV  pos={pos}, neg={neg}", flush=True)
    skf = StratifiedKFold(n_splits=5, shuffle=True, random_state=42)
    oof_lgb = np.zeros(len(y), dtype=float)
    oof_ord = np.zeros(len(y), dtype=float)
    oof_xgb = np.zeros(len(y), dtype=float)
    fold_metrics: list[dict[str, float]] = []
    for fold, (tr, va) in enumerate(skf.split(X, y), 1):
        cls = train_lgbm(X.iloc[tr], y[tr], scale_pos_weight, sample_weight=sample_weight[tr])
        oof_lgb[va] = cls.predict_proba(X.iloc[va])[:, 1]
        reg = train_lgbm_ordinal(X.iloc[tr], ord_target[tr], sample_weight=sample_weight[tr])
        oof_ord[va] = reg.predict(X.iloc[va])
        xgb = train_xgb(X.iloc[tr], y[tr], scale_pos_weight, sample_weight=sample_weight[tr])
        oof_xgb[va] = xgb.predict_proba(X.iloc[va])[:, 1]
        # Evaluate fold AUC on the clean reanalysis subset only.
        va_clean = va[eval_mask[va]]
        if len(va_clean) > 0 and len(np.unique(y[va_clean])) == 2:
            fold_metrics.append(
                {
                    "fold": fold,
                    "lgb_auc": float(roc_auc_score(y[va_clean], oof_lgb[va_clean])),
                    "ord_auc": float(roc_auc_score(y[va_clean], oof_ord[va_clean])),
                    "xgb_auc": float(roc_auc_score(y[va_clean], oof_xgb[va_clean])),
                }
            )
            print(
                f"  fold {fold}: lgb={fold_metrics[-1]['lgb_auc']:.4f}  "
                f"ord={fold_metrics[-1]['ord_auc']:.4f}  "
                f"xgb={fold_metrics[-1]['xgb_auc']:.4f}  "
                f"(n_clean_va={len(va_clean)})"
            )

    def _rank_norm(x):
        order = np.argsort(np.argsort(x))
        return order / (len(x) - 1 + 1e-9)

    # Compute ranks on the clean-eval subset, since that's the population we
    # threshold-tune for.
    y_eval = y[eval_mask]
    rl = _rank_norm(oof_lgb[eval_mask])
    ro = _rank_norm(oof_ord[eval_mask])
    rx = _rank_norm(oof_xgb[eval_mask])
    # Also keep full versions for storing OOF metrics on all rows.
    rl_full = _rank_norm(oof_lgb)
    ro_full = _rank_norm(oof_ord)
    rx_full = _rank_norm(oof_xgb)

    # Search blend weights maximising min(accuracy, recall) over (weights,
    # threshold) jointly on the clean-eval OOF subset.
    best = {"min_ar": -1.0}
    grid_w = [(a, b, c) for a in np.arange(0, 1.01, 0.1) for b in np.arange(0, 1.01 - a, 0.1) for c in [round(1 - a - b, 2)] if c >= 0]
    for wa, wb, wc in grid_w:
        blend = wa * rl + wb * ro + wc * rx
        for t in np.linspace(0.20, 0.95, 76):
            pred = (blend >= t).astype(int)
            rec = recall_score(y_eval, pred, zero_division=0)
            acc = accuracy_score(y_eval, pred)
            score = min(acc, rec)
            if score > best["min_ar"]:
                best = {
                    "min_ar": float(score),
                    "weights": (float(wa), float(wb), float(wc)),
                    "threshold": float(t),
                    "accuracy": float(acc),
                    "recall": float(rec),
                    "precision": float(precision_score(y_eval, pred, zero_division=0)),
                    "f1": float(f1_score(y_eval, pred, zero_division=0)),
                }
    print(
        f"\nBest blend (max min(acc, recall) on reanalysis OOF):\n"
        f"  weights (lgb, ord, xgb) = {best['weights']}\n"
        f"  threshold = {best['threshold']:.3f}\n"
        f"  acc = {best['accuracy']:.3f}  rec = {best['recall']:.3f}  prec = {best['precision']:.3f}  f1 = {best['f1']:.3f}"
    )

    wa, wb, wc = best["weights"]
    oof = wa * rl + wb * ro + wc * rx
    oof_full = wa * rl_full + wb * ro_full + wc * rx_full
    y_full = y  # keep original for final-fit
    y = y_eval  # downstream sweep / report uses the clean eval set
    blend_weights = (wa, wb, wc)

    roc_auc = float(roc_auc_score(y, oof))
    ap = float(average_precision_score(y, oof))
    print(f"\nOOF  ROC-AUC = {roc_auc:.4f}   Average-Precision = {ap:.4f}")

    # Report a sweep so user can see the recall/accuracy curve.
    print("\nRecall / accuracy sweep on OOF:")
    print(f"  {'threshold':>10}  {'acc':>6}  {'prec':>6}  {'rec':>6}  {'f1':>6}")
    for t in np.linspace(0.05, 0.95, 19):
        pred = (oof >= t).astype(int)
        print(
            f"  {t:>10.3f}  "
            f"{accuracy_score(y, pred):>6.3f}  "
            f"{precision_score(y, pred, zero_division=0):>6.3f}  "
            f"{recall_score(y, pred, zero_division=0):>6.3f}  "
            f"{f1_score(y, pred, zero_division=0):>6.3f}"
        )

    threshold, tuned = tune_threshold(y, oof, target_recall=0.90)
    print(
        f"Tuned threshold (target recall>=0.90): t={threshold:.3f}  "
        f"acc={tuned['accuracy']:.3f}  prec={tuned['precision']:.3f}  "
        f"rec={tuned['recall']:.3f}  f1={tuned['f1']:.3f}"
    )

    threshold_acc, tuned_acc = tune_threshold(y, oof, target_recall=0.0)
    # tune_threshold falls back to max-F1 when target=0 always passes; we
    # additionally search the "balanced" point where recall and accuracy are
    # both high.
    def best_balanced(y_true, proba):
        thresholds = np.linspace(0.01, 0.99, 99)
        best = (0.5, -1.0, {})
        for t in thresholds:
            pred = (proba >= t).astype(int)
            acc = accuracy_score(y_true, pred)
            rec = recall_score(y_true, pred, zero_division=0)
            score = min(acc, rec)
            if score > best[1]:
                best = (
                    float(t),
                    float(score),
                    {
                        "threshold": float(t),
                        "accuracy": float(acc),
                        "precision": float(precision_score(y_true, pred, zero_division=0)),
                        "recall": float(rec),
                        "f1": float(f1_score(y_true, pred, zero_division=0)),
                    },
                )
        return best[0], best[2]

    bal_t, bal_metrics = best_balanced(y, oof)
    print(
        f"Balanced threshold (max min(acc,recall)): t={bal_t:.3f}  "
        f"acc={bal_metrics['accuracy']:.3f}  prec={bal_metrics['precision']:.3f}  "
        f"rec={bal_metrics['recall']:.3f}  f1={bal_metrics['f1']:.3f}"
    )

    # Train final models on full data.
    print("\nFitting final LightGBM (cls + ordinal) + XGBoost on full data...", flush=True)
    final_cls = train_lgbm(X, y_full, scale_pos_weight, sample_weight=sample_weight)
    final_ord = train_lgbm_ordinal(X, ord_target, sample_weight=sample_weight)
    final_xgb = train_xgb(X, y_full, scale_pos_weight, sample_weight=sample_weight)
    import joblib

    joblib.dump(
        {
            "classifier_lgbm": final_cls,
            "ordinal_regressor_lgbm": final_ord,
            "classifier_xgb": final_xgb,
            "features": keep,
            "blend_weights_lgb_ord_xgb": blend_weights,
            "threshold_recall90": threshold,
            "threshold_balanced": bal_t,
            "threshold_best_min_ar": best["threshold"],
            "scale_pos_weight": scale_pos_weight,
        },
        OUT / "firesky_v2_model.joblib",
    )
    best_blend_metrics = best

    feat_importance = (
        pd.DataFrame({"feature": keep, "importance": final_cls.feature_importances_})
        .sort_values("importance", ascending=False)
    )
    feat_importance.to_csv(OUT / "firesky_v2_feature_importance.csv", index=False, encoding="utf-8")

    # Per-source breakdown at recall-90 threshold (uses full OOF blend).
    pred_full = (oof_full >= threshold).astype(int)
    breakdown: dict[str, Any] = {}
    for source, group in dataset.groupby("source"):
        mask = (dataset["source"] == source).to_numpy()
        gy = y_full[mask]
        gp = pred_full[mask]
        if len(gy) == 0:
            continue
        breakdown[source] = {
            "n": int(mask.sum()),
            "positives": int(gy.sum()),
            "accuracy": float(accuracy_score(gy, gp)),
            "precision": float(precision_score(gy, gp, zero_division=0)),
            "recall": float(recall_score(gy, gp, zero_division=0)),
            "confusion": confusion_matrix(gy, gp).tolist(),
        }

    report = {
        "best_blend_min_acc_recall": best_blend_metrics,
        "label_definition": f"label_good = (sunsetbot ordinal >= {POSITIVE_ORDINAL}) | (Stanford good_sunset==1)",
        "rows": int(len(dataset)),
        "positives": int(y.sum()),
        "positive_rate": float(y.mean()),
        "feature_count": len(keep),
        "model": "LightGBM (LGBMClassifier, scale_pos_weight balanced)",
        "cv_folds": fold_metrics,
        "oof_roc_auc": roc_auc,
        "oof_average_precision": ap,
        "threshold_recall90": tuned,
        "threshold_balanced": bal_metrics,
        "classification_report_at_recall90": classification_report(
            y, (oof >= threshold).astype(int), output_dict=True, zero_division=0
        ),
        "breakdown_by_source_at_recall90": breakdown,
    }
    (OUT / "firesky_v2_report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print("\nReport written to:", OUT / "firesky_v2_report.json")


if __name__ == "__main__":
    main()
