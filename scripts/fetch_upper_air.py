"""Fetch upper-air and stability variables (cape, 500/700 hPa winds, RH,
geopotential height, freezing level) at the Shanghai city centre for the
sunsetbot reanalysis date range. Cached under data_sources/raw/open_meteo_upper_cache/.
"""

from __future__ import annotations

import json
import time
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / "data_sources" / "raw" / "open_meteo_upper_cache"
CACHE.mkdir(parents=True, exist_ok=True)

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


def fetch(city: str, lat: float, lon: float, start: str, end: str, sleep: float = 0.4) -> Path:
    name = f"upper_{city}_{start}_{end}.json"
    path = CACHE / name
    if path.exists() and path.stat().st_size > 1000:
        return path
    params = {
        "latitude": str(lat),
        "longitude": str(lon),
        "start_date": start,
        "end_date": end,
        "hourly": ",".join(UPPER_VARS),
        "timezone": "auto",
        "wind_speed_unit": "kmh",
    }
    url = "https://archive-api.open-meteo.com/v1/archive"
    for attempt in range(4):
        try:
            r = requests.get(url, params=params, timeout=60, headers={"Accept": "application/json"})
            r.raise_for_status()
            payload = r.json()
            path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
            time.sleep(sleep)
            return path
        except requests.RequestException as exc:
            if attempt == 3:
                raise
            time.sleep(2 ** attempt)
    raise RuntimeError("unreachable")


def main() -> None:
    targets = [
        ("Shanghai", 31.2304, 121.4737, "2022-06-24", "2022-12-31"),
        ("Shanghai", 31.2304, 121.4737, "2023-01-01", "2023-12-30"),
        ("Shanghai", 31.2304, 121.4737, "2024-01-01", "2024-11-18"),
        ("Shanghai", 31.2304, 121.4737, "2025-01-04", "2025-07-05"),
    ]
    for city, lat, lon, s, e in targets:
        p = fetch(city, lat, lon, s, e)
        print(f"  fetched: {p.name}  ({p.stat().st_size / 1024:.0f} KB)")


if __name__ == "__main__":
    main()
