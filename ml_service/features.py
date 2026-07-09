from __future__ import annotations

import math
import sys
from datetime import timedelta
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.train_v2 import (  # noqa: E402
    AIR_HOURLY_VARS,
    GRID_DISTANCES,
    HAS_ASTRAL,
    UPPER_VARS,
    WEATHER_HOURLY_VARS,
    _physical_features,
    _trend_features,
    _upper_features,
    _window_features,
)

if HAS_ASTRAL:
    from astral import Observer
    from astral.sun import azimuth as solar_azimuth


def _hourly_frame(payload: dict[str, Any], variables: list[str], prefix: str = "") -> pd.DataFrame:
    hourly = payload.get("hourly") or {}
    times = hourly.get("time") or []
    frame = pd.DataFrame({"time": pd.to_datetime(times, errors="coerce")})
    for variable in variables:
        values = hourly.get(variable)
        if values is None:
            values = [np.nan] * len(frame)
        frame[f"{prefix}{variable}"] = values
    return frame.dropna(subset=["time"]).sort_values("time").reset_index(drop=True)


def _daily_event_time(weather: dict[str, Any], event: str) -> tuple[str, pd.Timestamp]:
    daily = weather.get("daily") or {}
    dates = daily.get("time") or []
    event_values = daily.get(event) or []
    if not dates or not event_values:
        raise ValueError(f"Weather payload is missing daily {event} time")
    date = str(dates[0])
    event_time = pd.to_datetime(event_values[0], errors="coerce")
    if pd.isna(event_time):
        raise ValueError(f"Weather payload has invalid daily {event} time")
    return date, event_time


def _merge_weather_air(weather: dict[str, Any], air: dict[str, Any] | None) -> pd.DataFrame:
    weather_df = _hourly_frame(weather, WEATHER_HOURLY_VARS)
    if weather_df.empty:
        raise ValueError("Weather payload does not contain hourly data")

    if not air:
        return weather_df

    air_df = _hourly_frame(air, AIR_HOURLY_VARS, prefix="air_")
    if air_df.empty:
        return weather_df

    return (
        pd.merge(weather_df, air_df, on="time", how="left")
        .drop_duplicates("time")
        .sort_values("time")
        .reset_index(drop=True)
    )


def _runtime_path_features(
    event_time: pd.Timestamp,
    event: str,
    latitude: float,
    longitude: float,
    utc_offset_seconds: int,
) -> dict[str, float]:
    if not HAS_ASTRAL:
        return {}

    event_utc = (event_time - timedelta(seconds=utc_offset_seconds)).to_pydatetime()
    try:
        azimuth = float(solar_azimuth(Observer(latitude=latitude, longitude=longitude), event_utc))
    except Exception:
        return {}

    out: dict[str, float] = {
        "solar_azimuth": azimuth,
        "solar_azimuth_sin": math.sin(math.radians(azimuth)),
        "solar_azimuth_cos": math.cos(math.radians(azimuth)),
        "event_is_sunrise_path": int(event == "sunrise"),
    }

    # The first online service version receives center-point forecast payloads
    # from Cloudflare. Directional grid and upper-air features are therefore
    # intentionally left missing; the model consumes them as NaN.
    for distance in GRID_DISTANCES:
        out[f"path_light_score_d{distance}"] = np.nan
        out[f"path_mid_high_minus_low_d{distance}"] = np.nan
        out[f"path_horizon_clear_d{distance}"] = np.nan

    out.update(_upper_features(pd.DataFrame(columns=["time", *UPPER_VARS]), event_time, azimuth))
    return out


def build_features_for_event(
    *,
    weather: dict[str, Any],
    air: dict[str, Any] | None,
    latitude: float,
    longitude: float,
    event: str,
) -> dict[str, float]:
    if event not in {"sunrise", "sunset"}:
        raise ValueError("event must be 'sunrise' or 'sunset'")

    hourly_df = _merge_weather_air(weather, air)
    date, event_time = _daily_event_time(weather, event)
    utc_offset_seconds = int(weather.get("utc_offset_seconds") or 0)

    features = _window_features(hourly_df, event_time)
    features.update(_trend_features(features))
    features.update(_physical_features(features, date))
    features.update(
        _runtime_path_features(
            event_time=event_time,
            event=event,
            latitude=latitude,
            longitude=longitude,
            utc_offset_seconds=utc_offset_seconds,
        )
    )
    features["event_is_sunset"] = int(event == "sunset")
    return features
