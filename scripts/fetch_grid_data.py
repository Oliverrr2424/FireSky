"""Fetch Open-Meteo historical archive for a directional grid of points
surrounding the Shanghai sunsetbot observation site. The grid is used to
build solar-light-path features (clear west horizon, mid/high cloud along
the sun azimuth, etc.).

Grid layout
-----------
- 12 bearings (every 30 degrees from 0=N, clockwise)
- 2 distances: 60 km and 150 km
- 24 grid points total per city

Each point is fetched per year-chunk and cached under
`data_sources/raw/open_meteo_grid_cache/`.

The list of grid points is small (24), so we run sequentially with a small
delay between requests. Re-running uses on-disk cache and skips network.
"""

from __future__ import annotations

import json
import math
import sys
import time
from dataclasses import dataclass
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / "data_sources" / "raw" / "open_meteo_grid_cache"
CACHE.mkdir(parents=True, exist_ok=True)

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

# Earth radius in km for offsetting along a bearing.
EARTH_KM = 6371.0


def offset(lat: float, lon: float, bearing_deg: float, distance_km: float) -> tuple[float, float]:
    """Return (lat, lon) of a point distance_km away from (lat, lon) along the
    compass bearing (degrees clockwise from north)."""
    bearing = math.radians(bearing_deg)
    lat1 = math.radians(lat)
    lon1 = math.radians(lon)
    angular = distance_km / EARTH_KM
    lat2 = math.asin(math.sin(lat1) * math.cos(angular) + math.cos(lat1) * math.sin(angular) * math.cos(bearing))
    lon2 = lon1 + math.atan2(
        math.sin(bearing) * math.sin(angular) * math.cos(lat1),
        math.cos(angular) - math.sin(lat1) * math.sin(lat2),
    )
    return math.degrees(lat2), math.degrees(lon2)


@dataclass(frozen=True)
class GridPoint:
    city: str
    bearing: int
    distance_km: int
    latitude: float
    longitude: float


def make_grid(city: str, lat: float, lon: float, bearings: list[int], distances: list[int]) -> list[GridPoint]:
    points = []
    for b in bearings:
        for d in distances:
            plat, plon = offset(lat, lon, float(b), float(d))
            points.append(GridPoint(city, b, d, round(plat, 4), round(plon, 4)))
    return points


def fetch_point(point: GridPoint, start: str, end: str, sleep: float = 0.4) -> Path:
    name = f"weather_{point.city}_b{point.bearing:03d}_d{point.distance_km:03d}_{start}_{end}.json"
    path = CACHE / name
    if path.exists() and path.stat().st_size > 1000:
        return path
    params = {
        "latitude": str(point.latitude),
        "longitude": str(point.longitude),
        "start_date": start,
        "end_date": end,
        "hourly": ",".join(GRID_VARS),
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
            wait = 2 ** attempt
            print(f"    retry {attempt + 1}/3 after {exc} (sleeping {wait}s)")
            time.sleep(wait)
    raise RuntimeError("unreachable")


def main() -> None:
    bearings = list(range(0, 360, 30))
    distances = [60, 150, 300]
    city = "Shanghai"
    lat, lon = 31.2304, 121.4737
    points = make_grid(city, lat, lon, bearings, distances)
    chunks = [
        ("2022-06-24", "2022-12-31"),
        ("2023-01-01", "2023-12-30"),
        ("2024-01-01", "2024-11-18"),
        ("2025-01-04", "2025-07-05"),
    ]

    print(f"Fetching {len(points)} grid points x {len(chunks)} chunks = {len(points) * len(chunks)} JSON files")
    done = 0
    for chunk_start, chunk_end in chunks:
        for point in points:
            path = fetch_point(point, chunk_start, chunk_end)
            done += 1
            size_kb = path.stat().st_size / 1024
            print(f"  [{done:>3}/{len(points)*len(chunks)}] {path.name}  ({size_kb:.0f} KB)", flush=True)

    print(f"\nDone. {done} files in {CACHE}")


if __name__ == "__main__":
    main()
