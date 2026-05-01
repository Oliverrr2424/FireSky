import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { AnimatePresence, motion } from 'framer-motion';
import maplibregl from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import {
  AlertTriangle,
  Aperture,
  Check,
  ChevronDown,
  CloudSun,
  Info,
  Loader2,
  LocateFixed,
  MapPin,
  MoonStar,
  RefreshCw,
  Search,
  SlidersHorizontal,
  Sparkles,
  SunMedium
} from 'lucide-react';
import './styles.css';
import WeatherBackdrop from './WeatherBackdrop.jsx';

// Cache + refresh cadence. Open-Meteo has hourly quotas; we respect them by
// serving localStorage copies within a 90 minute window and fall back to stale
// (< 6h) data whenever the network fails.
const CACHE_VERSION = 'v3';
const CACHE_TTL_MS = 90 * 60 * 1000;
const STALE_TTL_MS = 6 * 60 * 60 * 1000;
const REFRESH_INTERVAL_MS = 90 * 60 * 1000;
const GEOCODE_CACHE_TTL_MS = 24 * 60 * 60 * 1000;
const REQUEST_TIMEOUT_MS = 12000;
const pendingJsonRequests = new Map();

function cacheKey(prefix, place) {
  const lat = Number(place.latitude).toFixed(3);
  const lon = Number(place.longitude).toFixed(3);
  return `firesky:${CACHE_VERSION}:${prefix}:${lat}:${lon}`;
}

function textCacheKey(prefix, value) {
  return `firesky:${CACHE_VERSION}:${prefix}:${String(value).trim().toLowerCase()}`;
}

function cacheGet(key, maxAge = CACHE_TTL_MS) {
  if (typeof window === 'undefined') return null;
  try {
    const raw = window.localStorage.getItem(key);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed.timestamp !== 'number') return null;
    if (Date.now() - parsed.timestamp > maxAge) return null;
    return parsed.value;
  } catch {
    return null;
  }
}

function cacheSet(key, value) {
  if (typeof window === 'undefined') return;
  try {
    window.localStorage.setItem(key, JSON.stringify({ timestamp: Date.now(), value }));
  } catch {
    /* quota exceeded — silently ignore */
  }
}

async function fetchJson(url, errorLabel) {
  if (pendingJsonRequests.has(url)) return pendingJsonRequests.get(url);

  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  const request = fetch(url, { signal: controller.signal })
    .then(async (response) => {
      if (!response.ok) {
        const retryAfter = response.headers.get('Retry-After');
        const suffix = retryAfter ? `; retry after ${retryAfter}s` : '';
        throw new Error(`${errorLabel} request failed (${response.status}${suffix})`);
      }
      return response.json();
    })
    .catch((err) => {
      if (err.name === 'AbortError') {
        throw new Error(`${errorLabel} request timed out`);
      }
      throw err;
    })
    .finally(() => {
      window.clearTimeout(timeout);
      pendingJsonRequests.delete(url);
    });

  pendingJsonRequests.set(url, request);
  return request;
}

const DEFAULT_PLACE = {
  name: 'San Francisco',
  admin1: 'California',
  country_code: 'US',
  latitude: 37.7749,
  longitude: -122.4194
};

const PRESETS = [
  { name: 'San Francisco', admin1: 'California', latitude: 37.7749, longitude: -122.4194, country_code: 'US' },
  { name: 'New York', admin1: 'New York', latitude: 40.7128, longitude: -74.006, country_code: 'US' },
  { name: 'Vancouver', admin1: 'British Columbia', latitude: 49.2827, longitude: -123.1207, country_code: 'CA' },
  { name: 'Mexico City', admin1: 'CDMX', latitude: 19.4326, longitude: -99.1332, country_code: 'MX' },
  { name: 'Toronto', admin1: 'Ontario', latitude: 43.6532, longitude: -79.3832, country_code: 'CA' }
];

const MAP_STYLE_URL = 'https://tiles.openfreemap.org/styles/positron';

function clamp(value, min = 0, max = 100) {
  return Math.max(min, Math.min(max, value));
}

function scoreRange(value, ideal, tolerance, max = 100) {
  if (value == null || Number.isNaN(value)) return 45;
  return clamp(max - (Math.abs(value - ideal) / tolerance) * max);
}

function scoreLowerBetter(value, good, bad) {
  if (value == null || Number.isNaN(value)) return 55;
  return clamp(((bad - value) / (bad - good)) * 100);
}

function scoreHigherBetter(value, good, great) {
  if (value == null || Number.isNaN(value)) return 55;
  return clamp(((value - good) / (great - good)) * 100);
}

function scoreBand(value, outerLow, innerLow, innerHigh, outerHigh) {
  if (value == null || Number.isNaN(value)) return 45;
  if (value <= outerLow || value >= outerHigh) return 0;
  if (value >= innerLow && value <= innerHigh) return 100;
  if (value < innerLow) return clamp(((value - outerLow) / (innerLow - outerLow)) * 100);
  return clamp(((outerHigh - value) / (outerHigh - innerHigh)) * 100);
}

function weightedAverage(pairs) {
  const usable = pairs.filter(([value]) => value != null && !Number.isNaN(value));
  if (!usable.length) return 0;
  const totalWeight = usable.reduce((sum, [, weight]) => sum + weight, 0);
  return usable.reduce((sum, [value, weight]) => sum + value * weight, 0) / totalWeight;
}

function parseLocalDate(value) {
  return value ? new Date(value) : null;
}

function localIsoToUtcDate(value, utcOffsetSeconds = 0) {
  if (!value) return null;
  const [datePart, timePart = '00:00'] = value.split('T');
  const [year, month, day] = datePart.split('-').map(Number);
  const [hour = 0, minute = 0, second = 0] = timePart.split(':').map(Number);
  return new Date(Date.UTC(year, month - 1, day, hour, minute, second) - utcOffsetSeconds * 1000);
}

const RAD = Math.PI / 180;
const DAY_MS = 86400000;
const J1970 = 2440588;
const J2000 = 2451545;

function toJulian(date) {
  return date.valueOf() / DAY_MS - 0.5 + J1970;
}

function toDays(date) {
  return toJulian(date) - J2000;
}

function rightAscension(l, b) {
  const e = 23.4397 * RAD;
  return Math.atan2(Math.sin(l) * Math.cos(e) - Math.tan(b) * Math.sin(e), Math.cos(l));
}

function declination(l, b) {
  const e = 23.4397 * RAD;
  return Math.asin(Math.sin(b) * Math.cos(e) + Math.cos(b) * Math.sin(e) * Math.sin(l));
}

function solarMeanAnomaly(d) {
  return RAD * (357.5291 + 0.98560028 * d);
}

function eclipticLongitude(m) {
  const c = RAD * (1.9148 * Math.sin(m) + 0.02 * Math.sin(2 * m) + 0.0003 * Math.sin(3 * m));
  const p = RAD * 102.9372;
  return m + c + p + Math.PI;
}

function siderealTime(d, lw) {
  return RAD * (280.16 + 360.9856235 * d) - lw;
}

function sunAltitude(date, latitude, longitude) {
  const lw = -longitude * RAD;
  const phi = latitude * RAD;
  const d = toDays(date);
  const m = solarMeanAnomaly(d);
  const l = eclipticLongitude(m);
  const dec = declination(l, 0);
  const ra = rightAscension(l, 0);
  const h = siderealTime(d, lw) - ra;
  return Math.asin(Math.sin(phi) * Math.sin(dec) + Math.cos(phi) * Math.cos(dec) * Math.cos(h)) / RAD;
}

function findSunAltitudeCrossing(startDate, endDate, targetAltitude, latitude, longitude) {
  const start = startDate.getTime();
  const end = endDate.getTime();
  const steps = 72;
  let previousTime = start;
  let previousValue = sunAltitude(new Date(previousTime), latitude, longitude) - targetAltitude;
  for (let i = 1; i <= steps; i += 1) {
    const nextTime = start + ((end - start) * i) / steps;
    const nextValue = sunAltitude(new Date(nextTime), latitude, longitude) - targetAltitude;
    if (previousValue === 0 || previousValue * nextValue <= 0) {
      let low = previousTime;
      let high = nextTime;
      for (let j = 0; j < 28; j += 1) {
        const mid = (low + high) / 2;
        const midValue = sunAltitude(new Date(mid), latitude, longitude) - targetAltitude;
        if (previousValue * midValue <= 0) {
          high = mid;
        } else {
          low = mid;
          previousValue = midValue;
        }
      }
      return new Date((low + high) / 2);
    }
    previousTime = nextTime;
    previousValue = nextValue;
  }
  return null;
}

function computeBlueHourWindows({ latitude, longitude, sunrise, sunset }) {
  const threeHours = 3 * 60 * 60 * 1000;
  const morningStart = sunrise ? findSunAltitudeCrossing(new Date(sunrise.getTime() - threeHours), sunrise, -6, latitude, longitude) : null;
  const morningEnd = sunrise ? findSunAltitudeCrossing(new Date(sunrise.getTime() - threeHours), sunrise, -4, latitude, longitude) : null;
  const eveningStart = sunset ? findSunAltitudeCrossing(sunset, new Date(sunset.getTime() + threeHours), -4, latitude, longitude) : null;
  const eveningEnd = sunset ? findSunAltitudeCrossing(sunset, new Date(sunset.getTime() + threeHours), -6, latitude, longitude) : null;
  return {
    sunrise: { start: morningStart, end: morningEnd },
    sunset: { start: eveningStart, end: eveningEnd }
  };
}

function computeAppearanceWindows({ latitude, longitude, sunrise, sunset }) {
  const oneHour = 60 * 60 * 1000;
  const threeHours = 3 * oneHour;
  const sunriseStart = sunrise ? findSunAltitudeCrossing(new Date(sunrise.getTime() - threeHours), sunrise, -6, latitude, longitude) : null;
  const sunriseEnd = sunrise ? findSunAltitudeCrossing(sunrise, new Date(sunrise.getTime() + oneHour), 3, latitude, longitude) : null;
  const sunsetStart = sunset ? findSunAltitudeCrossing(new Date(sunset.getTime() - oneHour), sunset, 3, latitude, longitude) : null;
  const sunsetEnd = sunset ? findSunAltitudeCrossing(sunset, new Date(sunset.getTime() + threeHours), -6, latitude, longitude) : null;
  return {
    sunrise: { start: sunriseStart, end: sunriseEnd },
    sunset: { start: sunsetStart, end: sunsetEnd }
  };
}

function nearestIndex(times, targetDate, utcOffsetSeconds = null) {
  if (!targetDate) return 0;
  let best = 0;
  let bestDiff = Infinity;
  times.forEach((time, index) => {
    const timeDate = utcOffsetSeconds == null ? new Date(time) : localIsoToUtcDate(time, utcOffsetSeconds);
    const diff = Math.abs(timeDate.getTime() - targetDate.getTime());
    if (diff < bestDiff) {
      best = index;
      bestDiff = diff;
    }
  });
  return best;
}

function avg(values) {
  const usable = values.filter((value) => value != null && !Number.isNaN(value));
  if (!usable.length) return null;
  return usable.reduce((sum, value) => sum + value, 0) / usable.length;
}

function valueAt(hourly, key, index) {
  return hourly?.[key]?.[index] ?? null;
}

function pickWindow(hourly, centerDate, mode, utcOffsetSeconds = null) {
  const times = hourly?.time ?? [];
  const center = nearestIndex(times, centerDate, utcOffsetSeconds);
  const indexes = [center - 1, center, center + 1].filter((index) => index >= 0 && index < times.length);
  const cloudTotal = avg(indexes.map((index) => valueAt(hourly, 'cloud_cover', index)));
  const cloudLow = avg(indexes.map((index) => valueAt(hourly, 'cloud_cover_low', index)));
  const cloudMid = avg(indexes.map((index) => valueAt(hourly, 'cloud_cover_mid', index)));
  const cloudHigh = avg(indexes.map((index) => valueAt(hourly, 'cloud_cover_high', index)));
  const humidity = avg(indexes.map((index) => valueAt(hourly, 'relative_humidity_2m', index)));
  const precipProbability = avg(indexes.map((index) => valueAt(hourly, 'precipitation_probability', index)));
  const precipitation = avg(indexes.map((index) => valueAt(hourly, 'precipitation', index)));
  const visibility = avg(indexes.map((index) => valueAt(hourly, 'visibility', index)));
  const wind = avg(indexes.map((index) => valueAt(hourly, 'wind_speed_10m', index)));
  const gust = avg(indexes.map((index) => valueAt(hourly, 'wind_gusts_10m', index)));
  const cape = avg(indexes.map((index) => valueAt(hourly, 'cape', index)));
  const shortwave = avg(indexes.map((index) => valueAt(hourly, 'shortwave_radiation', index)));
  const direct = avg(indexes.map((index) => valueAt(hourly, 'direct_radiation', index)));
  const diffuse = avg(indexes.map((index) => valueAt(hourly, 'diffuse_radiation', index)));
  const directNormal = avg(indexes.map((index) => valueAt(hourly, 'direct_normal_irradiance', index)));
  const sunshineDuration = avg(indexes.map((index) => valueAt(hourly, 'sunshine_duration', index)));
  const vapourPressureDeficit = avg(indexes.map((index) => valueAt(hourly, 'vapour_pressure_deficit', index)));

  return {
    mode,
    center,
    indexes,
    start: times[indexes[0]],
    end: times[indexes[indexes.length - 1]],
    cloudTotal,
    cloudLow,
    cloudMid,
    cloudHigh,
    humidity,
    precipProbability,
    precipitation,
    visibility,
    wind,
    gust,
    cape,
    shortwave,
    direct,
    diffuse,
    directNormal,
    sunshineDuration,
    vapourPressureDeficit
  };
}

function computeWindowScore(window, air, mode, context = {}) {
  const aqi = air?.us_aqi ?? null;
  const pm25 = air?.pm2_5 ?? null;
  const aod = air?.aerosol_optical_depth ?? null;
  const dust = air?.dust ?? null;

  const highCloud = scoreBand(window.cloudHigh, 0, 28, 72, 96);
  const midCloud = scoreBand(window.cloudMid, 0, 18, 62, 90);
  const lowCloudPenalty = scoreLowerBetter(window.cloudLow, 12, 72);
  const totalCloud = scoreBand(window.cloudTotal, 4, 34, 76, 97);
  const humidity = scoreBand(window.humidity, mode === 'sunrise' ? 22 : 18, mode === 'sunrise' ? 48 : 34, mode === 'sunrise' ? 82 : 72, 96);
  const rain = scoreLowerBetter((window.precipProbability ?? 0) + (window.precipitation ?? 0) * 24, 5, 72);
  const visibility = scoreHigherBetter((window.visibility ?? 8000) / 1000, 5, 18);
  const aerosol = clamp(
    scoreBand(aod ?? 0.1, 0, 0.04, 0.24, 0.65) * 0.42 +
      scoreBand(pm25 ?? 8, 0, 3, 14, 42) * 0.3 +
      scoreLowerBetter(aqi ?? 40, 18, 120) * 0.2 +
      scoreLowerBetter(dust ?? 0, 5, 95) * 0.05
  );
  const wind = scoreBand(window.wind, 0, 3, 22, 42);
  const instability = scoreLowerBetter(window.cape ?? 0, 80, 1400);
  const vpd = scoreBand(window.vapourPressureDeficit ?? 0.7, 0, 0.2, 1.4, 2.6);
  const sunAccess = mode === 'sunset'
    ? weightedAverage([
        [scoreHigherBetter(window.directNormal ?? window.direct ?? 0, 45, 430), 0.5],
        [scoreHigherBetter(window.sunshineDuration ?? 0, 240, 2600), 0.25],
        [scoreLowerBetter(window.diffuse ?? 0, 40, 260), 0.25]
      ])
    : weightedAverage([
        [lowCloudPenalty, 0.45],
        [rain, 0.25],
        [visibility, 0.3]
      ]);
  const localTexture = scoreBand(Math.abs((window.cloudHigh ?? 0) - (window.cloudMid ?? 0)) + Math.abs((window.cloudMid ?? 0) - (window.cloudLow ?? 0)) * 0.35, 0, 8, 36, 78);
  const horizonOpening = context.horizonOpening ?? weightedAverage([
    [lowCloudPenalty, 0.48],
    [rain, 0.25],
    [visibility, 0.2],
    [sunAccess, 0.07]
  ]);
  const regionalTexture = context.regionalTexture ?? localTexture;
  const cloudScreen = weightedAverage([
    [highCloud, 0.36],
    [midCloud, 0.23],
    [totalCloud, 0.18],
    [lowCloudPenalty, 0.14],
    [regionalTexture, 0.09]
  ]);
  const blockersClearance = weightedAverage([
    [horizonOpening, 0.36],
    [rain, 0.27],
    [visibility, 0.19],
    [lowCloudPenalty, 0.18]
  ]);
  const colorChemistry = weightedAverage([
    [aerosol, 0.38],
    [humidity, 0.3],
    [vpd, 0.14],
    [wind, 0.1],
    [instability, 0.08]
  ]);

  const probability = clamp(
    cloudScreen * 0.42 +
      blockersClearance * 0.3 +
      colorChemistry * 0.16 +
      sunAccess * 0.07 +
      regionalTexture * 0.05
  );

  const quality = clamp(
    probability * 0.46 +
      highCloud * 0.16 +
      midCloud * 0.1 +
      aerosol * 0.1 +
      visibility * 0.08 +
      horizonOpening * 0.07 +
      regionalTexture * 0.03
  );

  const blockers = [];
  if ((window.cloudLow ?? 0) > 48) blockers.push('Low cloud may block the horizon');
  if ((window.precipProbability ?? 0) > 45) blockers.push('Precipitation risk is elevated');
  if ((window.cloudTotal ?? 0) < 15) blockers.push('Too little cloud texture');
  if ((window.cloudTotal ?? 0) > 88) blockers.push('Cloud deck may be too thick');
  if (((window.visibility ?? 10000) / 1000) < 5) blockers.push('Visibility is limited');
  if (horizonOpening < 35) blockers.push(mode === 'sunset' ? 'Western low-sky opening is weak' : 'Eastern low-sky opening is weak');

  const boosts = [];
  if ((window.cloudHigh ?? 0) >= 24 && (window.cloudHigh ?? 0) <= 70) boosts.push('High cloud screen is favorable');
  if ((window.cloudMid ?? 0) >= 18 && (window.cloudMid ?? 0) <= 64) boosts.push('Mid cloud has room to color');
  if ((aod ?? 0.1) >= 0.05 && (aod ?? 0.1) <= 0.32) boosts.push('Aerosol level is balanced');
  if ((window.precipProbability ?? 0) < 25) boosts.push('Precipitation interference is low');
  if (horizonOpening >= 68) boosts.push(mode === 'sunset' ? 'Western low sky is open' : 'Eastern low sky is open');

  return {
    probability,
    quality,
    factors: {
      highCloud,
      midCloud,
      lowCloudPenalty,
      totalCloud,
      humidity,
      rain,
      visibility,
      aerosol,
      wind,
      instability,
      sunAccess,
      horizonOpening,
      regionalTexture,
      cloudScreen,
      colorChemistry
    },
    blockers,
    boosts,
    confidence: clamp(58 + (window.indexes.length - 1) * 8 + (context.sampleCount ? Math.min(12, context.sampleCount / 10) : 0) - blockers.length * 6)
  };
}

function formatTime(value, timeZone, utcOffsetSeconds = null) {
  if (!value) return '--:--';
  const date = typeof value === 'string' && utcOffsetSeconds != null ? localIsoToUtcDate(value, utcOffsetSeconds) : new Date(value);
  return new Intl.DateTimeFormat('en-US', { hour: '2-digit', minute: '2-digit', hour12: false, timeZone }).format(date);
}

function formatRange(start, end, timeZone, utcOffsetSeconds = null) {
  return `${formatTime(start, timeZone, utcOffsetSeconds)}-${formatTime(end, timeZone, utcOffsetSeconds)}`;
}

function formatMinutes(totalMinutes) {
  if (totalMinutes == null || Number.isNaN(totalMinutes) || totalMinutes < 0) return '--';
  const hours = Math.floor(totalMinutes / 60);
  const minutes = Math.round(totalMinutes % 60);
  if (!hours) return `${minutes}m`;
  return `${hours}h ${minutes.toString().padStart(2, '0')}m`;
}

function formatDuration(start, end) {
  if (!start || !end) return '--';
  return formatMinutes((new Date(end).getTime() - new Date(start).getTime()) / 60000);
}

function formatTotalDuration(windows) {
  const total = windows.reduce((sum, window) => {
    if (!window?.start || !window?.end) return sum;
    return sum + (new Date(window.end).getTime() - new Date(window.start).getTime()) / 60000;
  }, 0);
  return total ? formatMinutes(total) : '--';
}

function formatTimeBetween(start, end) {
  if (!start || !end) return '--';
  return formatMinutes(Math.abs((new Date(end).getTime() - new Date(start).getTime()) / 60000));
}

function formatPercent(value) {
  return `${Math.round(value ?? 0)}%`;
}

function describeScore(score) {
  if (score >= 78) return 'Exceptional';
  if (score >= 62) return 'Worth waiting';
  if (score >= 42) return 'Possible';
  if (score >= 24) return 'Weak';
  return 'Unlikely';
}

function weatherDescription(code, cloudCover = 0) {
  if ([95, 96, 99].includes(code)) return 'Thunderstorm';
  if ([71, 73, 75, 77, 85, 86].includes(code)) return 'Snow';
  if ([45, 48].includes(code)) return 'Fog';
  if ([51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82].includes(code)) return 'Rain';
  if ([1, 2].includes(code)) return cloudCover > 55 ? 'Partly Cloudy' : 'Mostly Clear';
  if (code === 3 || cloudCover > 80) return 'Cloudy';
  return 'Clear';
}

function weatherTheme(code, cloudCover = 0) {
  if ([95, 96, 99].includes(code)) return 'storm';
  if ([71, 73, 75, 77, 85, 86].includes(code)) return 'snow';
  if ([45, 48].includes(code)) return 'fog';
  if ([51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82].includes(code)) return 'rain';
  if (code === 3 || cloudCover > 72) return 'cloudy';
  return 'clear';
}

function makeForecastUrl(place) {
  const params = new URLSearchParams({
    lat: place.latitude,
    lon: place.longitude
  });
  return `/api/forecast?${params}`;
}

const GRID_STEPS = 15;

function evenlySpacedOffsets(min, max, steps) {
  return Array.from({ length: steps }, (_, index) => +(min + ((max - min) * index) / (steps - 1)).toFixed(4));
}

function createGridPlaces(place) {
  const latSteps = evenlySpacedOffsets(-1.1, 1.1, GRID_STEPS);
  const lonSteps = evenlySpacedOffsets(-1.45, 1.45, GRID_STEPS);
  const offsets = latSteps.flatMap((latOffset) => lonSteps.map((lonOffset) => [latOffset, lonOffset]));
  return offsets.map(([latOffset, lonOffset], index) => ({
    ...place,
    latitude: +(place.latitude + latOffset).toFixed(4),
    longitude: +(place.longitude + lonOffset).toFixed(4),
    sampleId: index
  }));
}

function buildForecast({ weather, air }) {
  const utcOffsetSeconds = weather.utc_offset_seconds ?? 0;
  const timeZone = weather.timezone || undefined;
  const sunrise = localIsoToUtcDate(weather.daily?.sunrise?.[0], utcOffsetSeconds);
  const sunset = localIsoToUtcDate(weather.daily?.sunset?.[0], utcOffsetSeconds);
  const sunriseWindow = pickWindow(weather.hourly, sunrise ? new Date(sunrise.getTime() - 24 * 60 * 1000) : null, 'sunrise', utcOffsetSeconds);
  const sunsetWindow = pickWindow(weather.hourly, sunset ? new Date(sunset.getTime() - 18 * 60 * 1000) : null, 'sunset', utcOffsetSeconds);
  const airIndex = nearestIndex(air.hourly?.time ?? [], sunsetWindow.start ? localIsoToUtcDate(sunsetWindow.start, utcOffsetSeconds) : new Date(), utcOffsetSeconds);
  const airSnapshot = {
    us_aqi: air.current?.us_aqi ?? air.hourly?.us_aqi?.[airIndex],
    pm2_5: air.current?.pm2_5 ?? air.hourly?.pm2_5?.[airIndex],
    pm10: air.current?.pm10 ?? air.hourly?.pm10?.[airIndex],
    aerosol_optical_depth: air.current?.aerosol_optical_depth ?? air.hourly?.aerosol_optical_depth?.[airIndex],
    dust: air.current?.dust ?? air.hourly?.dust?.[airIndex]
  };

  const sunriseScore = computeWindowScore(sunriseWindow, airSnapshot, 'sunrise');
  const sunsetScore = computeWindowScore(sunsetWindow, airSnapshot, 'sunset');
  const blueHour = computeBlueHourWindows({
    latitude: weather.latitude,
    longitude: weather.longitude,
    sunrise,
    sunset
  });
  const appearanceWindow = computeAppearanceWindows({
    latitude: weather.latitude,
    longitude: weather.longitude,
    sunrise,
    sunset
  });
  return {
    weather,
    air,
    timeZone,
    utcOffsetSeconds,
    sunrise,
    sunset,
    blueHour,
    appearanceWindow,
    windows: { sunrise: sunriseWindow, sunset: sunsetWindow },
    scores: { sunrise: sunriseScore, sunset: sunsetScore },
    current: weather.current,
    airSnapshot
  };
}

async function fetchForecast(place, { force = false } = {}) {
  const key = cacheKey('forecast', place);
  if (!force) {
    const cached = cacheGet(key);
    if (cached) return buildForecast(cached);
  }
  try {
    const bundle = await fetchJson(makeForecastUrl(place), 'Forecast');
    cacheSet(key, bundle);
    return buildForecast(bundle);
  } catch (err) {
    const stale = cacheGet(key, STALE_TTL_MS);
    if (stale) return buildForecast(stale);
    throw new Error('Weather data is temporarily unavailable');
  }
}

function distanceDegrees(a, b) {
  const latScale = Math.cos((((a.latitude + b.latitude) / 2) * Math.PI) / 180);
  const dx = (a.longitude - b.longitude) * latScale;
  const dy = a.latitude - b.latitude;
  return Math.sqrt(dx * dx + dy * dy);
}

function standardDeviation(values) {
  const usable = values.filter((value) => value != null && !Number.isNaN(value));
  if (!usable.length) return 0;
  const mean = avg(usable);
  return Math.sqrt(avg(usable.map((value) => (value - mean) ** 2)));
}

function regionalContextForPoint(point, allPoints, mode) {
  const direction = mode === 'sunset' ? -1 : 1;
  const near = allPoints.filter((sample) => sample.sampleId !== point.sampleId && distanceDegrees(point, sample) <= 1.2);
  const horizon = allPoints.filter((sample) => {
    const lonDelta = sample.longitude - point.longitude;
    return lonDelta * direction > 0.18 && Math.abs(sample.latitude - point.latitude) < 0.55 && distanceDegrees(point, sample) <= 1.65;
  });
  const horizonSet = horizon.length ? horizon : near;
  const horizonLow = avg(horizonSet.map((sample) => sample.window.cloudLow)) ?? point.window.cloudLow;
  const horizonTotal = avg(horizonSet.map((sample) => sample.window.cloudTotal)) ?? point.window.cloudTotal;
  const horizonPrecip = avg(horizonSet.map((sample) => sample.window.precipProbability)) ?? point.window.precipProbability;
  const horizonVisibility = avg(horizonSet.map((sample) => sample.window.visibility)) ?? point.window.visibility;
  const regionalTextureRaw = standardDeviation(near.flatMap((sample) => [sample.window.cloudHigh, sample.window.cloudMid]));
  return {
    sampleCount: allPoints.length,
    horizonOpening: weightedAverage([
      [scoreLowerBetter(horizonLow * 0.68 + horizonTotal * 0.22 + (horizonPrecip ?? 0) * 0.1, 20, 82), 0.56],
      [scoreLowerBetter(horizonPrecip ?? 0, 12, 70), 0.22],
      [scoreHigherBetter((horizonVisibility ?? 8000) / 1000, 6, 18), 0.22]
    ]),
    regionalTexture: scoreBand(regionalTextureRaw, 0, 4, 22, 46)
  };
}

function enhanceRegionalScores(points, mode) {
  return points.map((point) => {
    const context = regionalContextForPoint(point, points, mode);
    const score = computeWindowScore(point.window, {}, mode, context);
    return {
      ...point,
      probability: score.probability,
      quality: score.quality,
      factors: score.factors,
      confidence: score.confidence
    };
  });
}

function buildGrid(payload, place, mode) {
  const samples = createGridPlaces(place);
  const rows = Array.isArray(payload) ? payload : [payload];
  const points = rows.slice(0, samples.length).map((item, index) => {
    const utcOffsetSeconds = item.utc_offset_seconds ?? 0;
    const target = mode === 'sunrise'
      ? localIsoToUtcDate(item.daily?.sunrise?.[0], utcOffsetSeconds)
      : localIsoToUtcDate(item.daily?.sunset?.[0], utcOffsetSeconds);
    const adjusted = target
      ? new Date(target.getTime() + (mode === 'sunrise' ? -24 : -18) * 60 * 1000)
      : new Date();
    const window = pickWindow(item.hourly, adjusted, mode, utcOffsetSeconds);
    const score = computeWindowScore(window, {}, mode);
    return { ...samples[index], window, probability: score.probability, quality: score.quality };
  });
  return enhanceRegionalScores(points, mode);
}

async function fetchGrid(place, mode, { force = false } = {}) {
  const key = cacheKey('grid', place);
  if (!force) {
    const cached = cacheGet(key);
    if (cached) return buildGrid(cached, place, mode);
  }
  try {
    const params = new URLSearchParams({
      lat: place.latitude,
      lon: place.longitude
    });
    const payload = await fetchJson(`/api/grid?${params}`, 'Regional grid');
    cacheSet(key, payload);
    return buildGrid(payload, place, mode);
  } catch (err) {
    const stale = cacheGet(key, STALE_TTL_MS);
    if (stale) return buildGrid(stale, place, mode);
    return null;
  }
}

async function geocodeCity(query) {
  const normalizedQuery = query.trim();
  if (normalizedQuery.length < 2) return [];
  const key = textCacheKey('geocode', normalizedQuery);
  const cached = cacheGet(key, GEOCODE_CACHE_TTL_MS);
  if (cached) return cached;
  const params = new URLSearchParams({ q: normalizedQuery });
  const results = await fetchJson(`/api/geocode?${params}`, 'Location search');
  cacheSet(key, results);
  return results;
}

function GlassCard({ children, className = '', delay = 0 }) {
  return (
    <motion.section
      initial={{ opacity: 0, y: 18 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.58, delay, ease: [0.22, 1, 0.36, 1] }}
      className={`glass ${className}`}
    >
      {children}
    </motion.section>
  );
}

function ScoreRing({ value, label, tone = 'sunset' }) {
  const rounded = Math.round(value ?? 0);
  const normalized = clamp(rounded, 0, 100);
  const radius = 44;
  const circumference = 2 * Math.PI * radius;
  const dashOffset = circumference * (1 - normalized / 100);
  const gradientId = `score-gradient-${tone}`;
  return (
    <motion.div
      className={`score-ring ${tone}`}
      initial={{ opacity: 0, scale: 0.92, rotate: -6 }}
      animate={{ opacity: 1, scale: 1, rotate: 0 }}
      transition={{ duration: 0.7, ease: [0.22, 1, 0.36, 1] }}
    >
      <div className="score-orb-glow" />
      <svg className="score-orb-ring" viewBox="0 0 120 120" aria-hidden="true">
        <defs>
          <linearGradient id={gradientId} x1="16" y1="16" x2="104" y2="104" gradientUnits="userSpaceOnUse">
            <stop offset="0%" stopColor={tone === 'sunrise' ? '#fff0b8' : '#d9c2ff'} />
            <stop offset="52%" stopColor={tone === 'sunrise' ? '#ffb86b' : '#8fd8ff'} />
            <stop offset="100%" stopColor={tone === 'sunrise' ? '#ff6f61' : '#5f8cff'} />
          </linearGradient>
        </defs>
        <circle className="score-track" cx="60" cy="60" r={radius} />
        <motion.circle
          className="score-meter"
          cx="60"
          cy="60"
          r={radius}
          stroke={`url(#${gradientId})`}
          strokeDasharray={circumference}
          initial={{ strokeDashoffset: circumference }}
          animate={{ strokeDashoffset: dashOffset }}
          transition={{ duration: 1.05, ease: [0.22, 1, 0.36, 1] }}
        />
      </svg>
      <div className="score-orb-core">
        <span>Sky Score</span>
        <strong>{rounded}</strong>
        <small>{label}</small>
      </div>
    </motion.div>
  );
}

function MetricRail({ activeMode, data }) {
  const score = data.scores[activeMode];
  const window = data.windows[activeMode];
  const weather = weatherDescription(data.current?.weather_code, data.current?.cloud_cover);
  const items = [
    ['Weather', weather, CloudSun],
    ['Sunset', `${Math.round(data.scores.sunset.probability)}-${Math.min(99, Math.round(data.scores.sunset.quality + 8))}%`, SunMedium],
    ['Sunrise', `${Math.round(data.scores.sunrise.probability)}-${Math.min(99, Math.round(data.scores.sunrise.quality + 8))}%`, MoonStar],
    ['Cloud Sea', `${Math.round(score.factors.totalCloud * 0.5)}%`, Sparkles],
    ['Rainbow', `${Math.round((100 - (window.precipProbability ?? 0)) * 0.05)}`, Aperture],
    ['Haze', data.airSnapshot.us_aqi != null ? `AQI ${Math.round(data.airSnapshot.us_aqi)}` : 'None']
  ];
  return (
    <div className="metric-rail">
      {items.map(([title, value, Icon], index) => (
        <motion.button
          key={title}
          whileHover={{ y: -3 }}
          whileTap={{ scale: 0.96 }}
          className={index === (activeMode === 'sunset' ? 1 : 2) ? 'rail-item active' : 'rail-item'}
        >
          {Icon ? <Icon size={18} /> : <span className="rail-icon-spacer" />}
          <span className="rail-label">{title}</span>
          <strong>{value}</strong>
        </motion.button>
      ))}
    </div>
  );
}

function heatColor(value, type) {
  const v = clamp(value);
  if (type === 'quality') {
    if (v >= 78) return '#efb8ff';
    if (v >= 58) return '#b46cff';
    if (v >= 38) return '#7657e8';
    return '#42507d';
  }
  if (v >= 78) return '#ff4038';
  if (v >= 58) return '#ff8f42';
  if (v >= 38) return '#ffd05e';
  return '#8da0bd';
}

function colorStop(value, type) {
  const stops = [
    [1, [36, 96, 151]],
    [24, [58, 156, 184]],
    [44, [108, 184, 129]],
    [62, [244, 207, 73]],
    [82, [237, 112, 62]],
    [100, [194, 54, 70]]
  ];
  const v = clamp(value, 1, 100);
  for (let i = 0; i < stops.length - 1; i += 1) {
    const [aValue, aColor] = stops[i];
    const [bValue, bColor] = stops[i + 1];
    if (v >= aValue && v <= bValue) {
      const t = (v - aValue) / (bValue - aValue || 1);
      return aColor.map((channel, index) => Math.round(channel + (bColor[index] - channel) * t));
    }
  }
  return stops[stops.length - 1][1];
}

// Gaussian RBF interpolation keeps the field continuous, while a modest sigma
// preserves regional boundaries instead of washing everything into one color.
const RBF_SIGMA = 0.18;
const RBF_SIG2_INV = 1 / (2 * RBF_SIGMA * RBF_SIGMA);

function interpolateSample(lat, lon, samples, type) {
  let weighted = 0;
  let weights = 0;
  const latScale = Math.cos((lat * Math.PI) / 180);
  for (let i = 0; i < samples.length; i += 1) {
    const sample = samples[i];
    const value = type === 'probability' ? sample.probability : sample.quality;
    if (value == null || Number.isNaN(value)) continue;
    const dx = (lon - sample.longitude) * latScale;
    const dy = lat - sample.latitude;
    const weight = Math.exp(-(dx * dx + dy * dy) * RBF_SIG2_INV);
    weighted += value * weight;
    weights += weight;
  }
  return weights ? weighted / weights : 0;
}

function isReferenceLineLayer(layer) {
  return layer.type === 'line' && (
    layer.id.includes('boundary') ||
    layer.id.includes('highway_major') ||
    layer.id.includes('highway_motorway') ||
    layer.id.includes('highway_minor') ||
    layer.id.includes('road') ||
    layer.id.includes('waterway')
  );
}

function mapIsUsable(map) {
  return Boolean(map && !map._removed && map.style);
}

function safeGetStyle(map) {
  if (!mapIsUsable(map)) return null;
  try {
    return map.getStyle();
  } catch {
    return null;
  }
}

function safeGetLayer(map, id) {
  if (!mapIsUsable(map)) return null;
  try {
    return map.getLayer(id);
  } catch {
    return null;
  }
}

function safeRemoveLayer(map, id) {
  if (!safeGetLayer(map, id)) return;
  try {
    map.removeLayer(id);
  } catch {
    /* MapLibre may already be tearing down the style during unmount. */
  }
}

function firstSymbolLayerId(map) {
  const layers = safeGetStyle(map)?.layers ?? [];
  return layers.find((layer) => layer.type === 'symbol')?.id;
}

function addReferenceLineOverlays(map, beforeId) {
  if (!mapIsUsable(map)) return;
  const layers = safeGetStyle(map)?.layers ?? [];
  layers.filter(isReferenceLineLayer).forEach((layer) => {
    const layerId = `fire-sky-reference-${layer.id}`;
    if (safeGetLayer(map, layerId) || !layer.source) return;
    const isBoundary = layer.id.includes('boundary');
    try {
      map.addLayer(
        {
          ...layer,
          id: layerId,
          paint: {
            ...(layer.paint ?? {}),
            'line-color': isBoundary ? '#213a55' : 'rgba(255, 255, 255, 0.82)',
            'line-opacity': isBoundary ? 0.72 : 0.62,
            'line-width': isBoundary
              ? ['interpolate', ['linear'], ['zoom'], 4, 0.45, 7, 1.15, 10, 1.8]
              : ['interpolate', ['linear'], ['zoom'], 4, 0.35, 7, 1.05, 10, 2.2],
            'line-blur': isBoundary ? 0 : 0.15
          }
        },
        beforeId
      );
    } catch {
      /* Optional reference overlays should never break the map. */
    }
  });
}

function removeReferenceLineOverlays(map) {
  if (!mapIsUsable(map)) return;
  const layers = safeGetStyle(map)?.layers ?? [];
  layers
    .filter((layer) => layer.id.startsWith('fire-sky-reference-'))
    .reverse()
    .forEach((layer) => {
      safeRemoveLayer(map, layer.id);
    });
}

function enhanceMapReferenceStyle(map) {
  if (!mapIsUsable(map)) return;
  const layers = safeGetStyle(map)?.layers ?? [];
  layers.forEach((layer) => {
    try {
      if (layer.type === 'symbol' && layer.layout?.['text-field'] != null) {
        map.setPaintProperty(layer.id, 'text-color', '#1c344f');
        map.setPaintProperty(layer.id, 'text-halo-color', 'rgba(255, 255, 255, 0.88)');
        map.setPaintProperty(layer.id, 'text-halo-width', 1.15);
        map.setPaintProperty(layer.id, 'text-halo-blur', 0.15);
      }
      if (layer.type === 'line' && layer.id.includes('boundary')) {
        map.setPaintProperty(layer.id, 'line-color', '#273f5c');
        map.setPaintProperty(layer.id, 'line-opacity', 0.68);
        map.setPaintProperty(layer.id, 'line-width', 1);
      }
    } catch {
      /* Some imported styles lock paint properties behind expressions. */
    }
  });
}

function createHeatTextureLayer(id, canvas) {
  return {
    id,
    type: 'custom',
    renderingMode: '2d',
    onAdd(map, gl) {
      const vertexSource = `
        attribute vec2 a_pos;
        attribute vec2 a_tex;
        varying vec2 v_tex;
        void main() {
          v_tex = a_tex;
          gl_Position = vec4(a_pos, 0.0, 1.0);
        }
      `;
      const fragmentSource = `
        precision mediump float;
        uniform sampler2D u_image;
        varying vec2 v_tex;
        void main() {
          gl_FragColor = texture2D(u_image, v_tex);
        }
      `;
      const compile = (type, source) => {
        const shader = gl.createShader(type);
        gl.shaderSource(shader, source);
        gl.compileShader(shader);
        return shader;
      };
      const program = gl.createProgram();
      gl.attachShader(program, compile(gl.VERTEX_SHADER, vertexSource));
      gl.attachShader(program, compile(gl.FRAGMENT_SHADER, fragmentSource));
      gl.linkProgram(program);

      this.program = program;
      this.posLoc = gl.getAttribLocation(program, 'a_pos');
      this.texLoc = gl.getAttribLocation(program, 'a_tex');
      this.imageLoc = gl.getUniformLocation(program, 'u_image');
      this.posBuffer = gl.createBuffer();
      this.texBuffer = gl.createBuffer();
      this.texture = gl.createTexture();

      gl.bindBuffer(gl.ARRAY_BUFFER, this.posBuffer);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
      gl.bindBuffer(gl.ARRAY_BUFFER, this.texBuffer);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([0, 1, 1, 1, 0, 0, 1, 0]), gl.STATIC_DRAW);
      gl.bindTexture(gl.TEXTURE_2D, this.texture);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    },
    render(gl) {
      if (!canvas.width || !canvas.height || !this.program) return;
      const depthWasEnabled = gl.isEnabled(gl.DEPTH_TEST);
      const blendWasEnabled = gl.isEnabled(gl.BLEND);
      gl.useProgram(this.program);
      gl.activeTexture(gl.TEXTURE0);
      gl.bindTexture(gl.TEXTURE_2D, this.texture);
      gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, true);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, canvas);
      gl.uniform1i(this.imageLoc, 0);

      gl.bindBuffer(gl.ARRAY_BUFFER, this.posBuffer);
      gl.enableVertexAttribArray(this.posLoc);
      gl.vertexAttribPointer(this.posLoc, 2, gl.FLOAT, false, 0, 0);
      gl.bindBuffer(gl.ARRAY_BUFFER, this.texBuffer);
      gl.enableVertexAttribArray(this.texLoc);
      gl.vertexAttribPointer(this.texLoc, 2, gl.FLOAT, false, 0, 0);

      gl.disable(gl.DEPTH_TEST);
      gl.enable(gl.BLEND);
      gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
      gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
      gl.disableVertexAttribArray(this.posLoc);
      gl.disableVertexAttribArray(this.texLoc);
      if (depthWasEnabled) gl.enable(gl.DEPTH_TEST);
      if (!blendWasEnabled) gl.disable(gl.BLEND);
    }
  };
}

function MapLibreHeatCanvasLayer({ map, canvasRef, samples, type, onReady }) {
  const samplesRef = useRef(samples);
  const typeRef = useRef(type);
  const redrawRef = useRef(null);
  const readyRef = useRef(false);

  useEffect(() => {
    samplesRef.current = samples;
    typeRef.current = type;
    readyRef.current = false;
    redrawRef.current?.();
    const redrawAfterMove = window.setTimeout(() => redrawRef.current?.(), 900);
    const redrawAfterTiles = window.setTimeout(() => redrawRef.current?.(), 1700);
    return () => {
      window.clearTimeout(redrawAfterMove);
      window.clearTimeout(redrawAfterTiles);
    };
  }, [samples, type]);

  useEffect(() => {
    if (!mapIsUsable(map)) return undefined;
    const canvas = canvasRef.current;
    if (!canvas) return undefined;
    const ctx = canvas.getContext('2d', { willReadFrequently: true });
    const layerId = 'fire-sky-heat';
    let frame = 0;

    function ensureLayer() {
      if (!mapIsUsable(map) || !map.isStyleLoaded()) return false;
      const firstSymbolId = firstSymbolLayerId(map);
      if (!safeGetLayer(map, layerId)) {
        try {
          map.addLayer(createHeatTextureLayer(layerId, canvas), firstSymbolId);
        } catch {
          return false;
        }
      }
      addReferenceLineOverlays(map, firstSymbolId);
      return true;
    }

    function draw() {
      frame = 0;
      if (!ctx || !mapIsUsable(map)) return;
      const activeSamples = samplesRef.current ?? [];
      if (!activeSamples.length) return;
      let mapCanvas;
      try {
        mapCanvas = map.getCanvas();
      } catch {
        return;
      }
      const cssWidth = mapCanvas.clientWidth;
      const cssHeight = mapCanvas.clientHeight;
      if (!cssWidth || !cssHeight) return;
      const resolution = cssWidth > 1400 ? 1.45 : 1.15;
      const width = Math.max(1, Math.round(cssWidth / resolution));
      const height = Math.max(1, Math.round(cssHeight / resolution));
      canvas.width = width;
      canvas.height = height;

      const image = ctx.createImageData(width, height);
      const widthScale = Math.max(1, width - 1);
      const heightScale = Math.max(1, height - 1);
      for (let y = 0; y < height; y += 1) {
        for (let x = 0; x < width; x += 1) {
          const point = [
            (x / widthScale) * cssWidth,
            (y / heightScale) * cssHeight
          ];
          const lngLat = map.unproject(point);
          const value = interpolateSample(lngLat.lat, lngLat.lng, activeSamples, typeRef.current);
          const [r, g, b] = colorStop(value, typeRef.current);
          const alpha = clamp(0.3 + value / 190, 0.3, 0.78);
          const index = (y * width + x) * 4;
          image.data[index] = r;
          image.data[index + 1] = g;
          image.data[index + 2] = b;
          image.data[index + 3] = Math.round(alpha * 255);
        }
      }
      ctx.putImageData(image, 0, 0);

      if (!ensureLayer()) return;
      try {
        map.triggerRepaint();
        if (!readyRef.current) {
          readyRef.current = true;
          requestAnimationFrame(() => onReady?.());
        }
      } catch {
        /* The map can be removed between draw scheduling and repaint. */
      }
    }

    function scheduleDraw() {
      if (!mapIsUsable(map)) return;
      if (frame) cancelAnimationFrame(frame);
      frame = requestAnimationFrame(draw);
    }

    redrawRef.current = scheduleDraw;
    scheduleDraw();
    map.on('load', scheduleDraw);
    map.on('moveend', scheduleDraw);
    map.on('zoomend', scheduleDraw);
    map.on('resize', scheduleDraw);

    return () => {
      if (frame) cancelAnimationFrame(frame);
      map.off('load', scheduleDraw);
      map.off('moveend', scheduleDraw);
      map.off('zoomend', scheduleDraw);
      map.off('resize', scheduleDraw);
      redrawRef.current = null;
      removeReferenceLineOverlays(map);
      safeRemoveLayer(map, layerId);
    };
  }, [canvasRef, map, onReady]);

  return null;
}

function MapScoreLabels({ map, samples, type }) {
  const [labels, setLabels] = useState([]);

  useEffect(() => {
    if (!map || !samples?.length) {
      setLabels([]);
      return undefined;
    }

    function updateLabels() {
      if (!mapIsUsable(map)) {
        setLabels([]);
        return;
      }
      const nextLabels = samples
        .slice()
        .sort((a, b) => (type === 'probability' ? b.probability - a.probability : b.quality - a.quality))
        .slice(0, 5)
        .map((sample) => {
          const point = map.project([sample.longitude, sample.latitude]);
          return {
            key: `${sample.latitude}-${sample.longitude}`,
            x: point.x,
            y: point.y,
            value: type === 'probability' ? sample.probability : sample.quality
          };
        });
      setLabels(nextLabels);
    }

    updateLabels();
    map.on('move', updateLabels);
    map.on('zoom', updateLabels);
    map.on('resize', updateLabels);
    return () => {
      map.off('move', updateLabels);
      map.off('zoom', updateLabels);
      map.off('resize', updateLabels);
    };
  }, [map, samples, type]);

  return (
    <div className="map-score-labels" aria-hidden="true">
      {labels.map((label) => (
        <strong
          key={label.key}
          style={{ transform: `translate(${label.x}px, ${label.y}px) translate(-50%, -50%)` }}
        >
          {label.value.toFixed(1)}%
        </strong>
      ))}
    </div>
  );
}

function MapHeatPanel({ place, samples, type }) {
  const nodeRef = useRef(null);
  const heatCanvasRef = useRef(null);
  const mapRef = useRef(null);
  const [map, setMap] = useState(null);
  const [mapSettled, setMapSettled] = useState(false);
  const [heatReady, setHeatReady] = useState(false);
  const center = useMemo(() => [place.latitude, place.longitude], [place.latitude, place.longitude]);
  const isReady = mapSettled && heatReady;
  const handleHeatReady = useCallback(() => setHeatReady(true), []);
  const sampleSignature = useMemo(
    () => `${type}:${samples?.length ?? 0}:${samples?.[0]?.latitude ?? center[0]}:${samples?.[0]?.longitude ?? center[1]}`,
    [center, samples, type]
  );

  useEffect(() => {
    if (!nodeRef.current || mapRef.current) return undefined;
    let disposed = false;
    const nextMap = new maplibregl.Map({
      container: nodeRef.current,
      style: MAP_STYLE_URL,
      center: [place.longitude, place.latitude],
      zoom: 7,
      attributionControl: false,
      scrollZoom: false,
      boxZoom: false,
      keyboard: false,
      dragRotate: false,
      pitchWithRotate: false
    });
    nextMap.addControl(new maplibregl.AttributionControl({ compact: true }), 'bottom-right');
    const onLoad = () => {
      if (!disposed) enhanceMapReferenceStyle(nextMap);
    };
    nextMap.on('load', onLoad);
    mapRef.current = nextMap;
    setMap(nextMap);

    return () => {
      disposed = true;
      nextMap.off('load', onLoad);
      setMap(null);
      try {
        if (!nextMap._removed) nextMap.remove();
      } catch {
        /* Ignore teardown races from MapLibre internals. */
      }
      mapRef.current = null;
    };
  }, []);

  useEffect(() => {
    if (!map) return;
    if (!mapIsUsable(map)) return;
    setMapSettled(false);
    setHeatReady(false);
    let finished = false;
    let fallback = 0;

    const markSettled = () => {
      if (finished || !mapIsUsable(map)) return;
      finished = true;
      window.clearTimeout(fallback);
      setMapSettled(true);
    };
    const markAfterMove = () => {
      window.setTimeout(markSettled, 120);
    };

    const scheduleSettled = () => {
      map.once('idle', markSettled);
      map.once('moveend', markAfterMove);
      fallback = window.setTimeout(markSettled, 2200);
    };

    if (samples?.length) {
      const bounds = samples.reduce(
        (nextBounds, sample) => nextBounds.extend([sample.longitude, sample.latitude]),
        new maplibregl.LngLatBounds()
      );
      map.fitBounds(bounds, {
        padding: [30, 30],
        maxZoom: 7,
        duration: 650
      });
    } else {
      map.flyTo({ center: [center[1], center[0]], zoom: map.getZoom(), duration: 650 });
    }
    scheduleSettled();

    return () => {
      finished = true;
      window.clearTimeout(fallback);
      if (mapIsUsable(map)) {
        map.off('idle', markSettled);
        map.off('moveend', markAfterMove);
      }
    };
  }, [center, map, samples, sampleSignature]);

  return (
    <div className={`real-map ${type} ${isReady ? 'is-ready' : 'is-loading'}`}>
      <div ref={nodeRef} className="maplibre-node" />
      <canvas ref={heatCanvasRef} className="maplibre-heat-source" />
      <MapLibreHeatCanvasLayer map={map} canvasRef={heatCanvasRef} samples={samples} type={type} onReady={handleHeatReady} />
      {isReady ? <MapScoreLabels map={map} samples={samples} type={type} /> : null}
      <div className="map-shade" />
      <div className={`map-legend ${type}`}>
        <span>1%</span>
        <i />
        <span>100%</span>
      </div>
      <div className="map-loading-surface" aria-hidden="true">
        <div className="map-loading-grid" />
        <div className="map-loading-contours">
          <i />
          <i />
          <i />
        </div>
        <div className="map-loading-pulse" />
      </div>
    </div>
  );
}

function HeatMap({ place, samples, type }) {
  const data = samples?.length ? samples : createGridPlaces(place).map((sample, index) => ({
    ...sample,
    probability: 34 + (index % GRID_STEPS) * 1.4 + Math.floor(index / GRID_STEPS) * 0.7,
    quality: 42 + (index % GRID_STEPS) * 1 + Math.floor(index / GRID_STEPS) * 0.55
  }));
  return (
    <motion.div initial={{ opacity: 0, scale: 0.985 }} animate={{ opacity: 1, scale: 1 }} transition={{ duration: 0.45 }}>
      <MapHeatPanel place={place} samples={data} type={type} />
    </motion.div>
  );
}

function FactorBars({ score }) {
  const labels = [
    ['Cloud Screen', score.factors.cloudScreen ?? score.factors.highCloud],
    ['Horizon', score.factors.horizonOpening ?? score.factors.lowCloudPenalty],
    ['High Cloud', score.factors.highCloud],
    ['Precipitation', score.factors.rain],
    ['Visibility', score.factors.visibility],
    ['Aerosol', score.factors.aerosol]
  ];
  return (
    <div className="factor-bars">
      {labels.map(([label, value]) => (
        <div className="factor" key={label}>
          <div>
            <span>{label}</span>
            <strong>{Math.round(value)}</strong>
          </div>
          <i>
            <motion.b
              initial={{ width: 0 }}
              animate={{ width: `${value}%` }}
              transition={{ duration: 0.8 }}
              style={{ '--value': `${Math.round(value)}%` }}
            />
          </i>
        </div>
      ))}
    </div>
  );
}

function SearchPanel({ onSelect }) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState([]);
  const [searching, setSearching] = useState(false);
  const [searchError, setSearchError] = useState('');

  async function submit(event) {
    event.preventDefault();
    if (!query.trim()) return;
    setSearching(true);
    setSearchError('');
    try {
      setResults(await geocodeCity(query.trim()));
    } catch (err) {
      setResults([]);
      setSearchError(err.message || 'Unable to search locations');
    } finally {
      setSearching(false);
    }
  }

  return (
    <div className="search-panel">
      <form onSubmit={submit}>
        <Search size={18} />
        <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search North American cities" />
        <button type="submit">{searching ? <Loader2 className="spin" size={18} /> : 'Search'}</button>
      </form>
      <div className="preset-row">
        {PRESETS.map((place) => (
          <button key={place.name} onClick={() => onSelect(place)}>{place.name}</button>
        ))}
      </div>
      {searchError ? <div className="search-error">{searchError}</div> : null}
      <AnimatePresence>
        {results.length ? (
          <motion.div initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }} className="results">
            {results.map((place) => (
              <button key={place.id} onClick={() => onSelect(place)}>
                <MapPin size={16} />
                <span>{place.name}, {place.admin1 || place.country}</span>
              </button>
            ))}
          </motion.div>
        ) : null}
      </AnimatePresence>
    </div>
  );
}

function App() {
  const [place, setPlace] = useState(DEFAULT_PLACE);
  const [activeMode, setActiveMode] = useState('sunset');
  const [data, setData] = useState(null);
  const [grid, setGrid] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [showSearch, setShowSearch] = useState(false);
  const loadSeq = useRef(0);

  const title = `${place.name}${place.admin1 ? ` · ${place.admin1}` : ''}`;

  async function load(nextPlace = place, nextMode = activeMode, { force = false, silent = false } = {}) {
    const seq = loadSeq.current + 1;
    loadSeq.current = seq;
    if (!silent) setLoading(true);
    setError('');
    try {
      const forecast = await fetchForecast(nextPlace, { force });
      const nextGrid = await fetchGrid(nextPlace, nextMode, { force });
      if (seq !== loadSeq.current) return;
      setData(forecast);
      setGrid(nextGrid);
    } catch (err) {
      if (seq === loadSeq.current) setError(err.message || 'Loading failed');
    } finally {
      if (seq === loadSeq.current && !silent) setLoading(false);
    }
  }

  useEffect(() => {
    load(place, activeMode);
  }, []);

  useEffect(() => {
    if (data) fetchGrid(place, activeMode).then(setGrid).catch(() => setGrid(null));
  }, [activeMode]);

  // Auto-refresh every 90 minutes to stay within Open-Meteo hourly quotas.
  // The refresh runs silently (no loading spinner) and force-bypasses cache.
  useEffect(() => {
    const id = window.setInterval(() => {
      load(place, activeMode, { force: true, silent: true });
    }, REFRESH_INTERVAL_MS);
    return () => window.clearInterval(id);
  }, [place, activeMode]);

  function selectPlace(nextPlace) {
    setPlace(nextPlace);
    setShowSearch(false);
    load(nextPlace, activeMode);
  }

  function locateMe() {
    if (!navigator.geolocation) {
      setError('Geolocation is not supported by this browser');
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (position) => {
        const nextPlace = {
          name: 'Current Location',
          admin1: 'North America',
          country_code: 'US',
          latitude: +position.coords.latitude.toFixed(4),
          longitude: +position.coords.longitude.toFixed(4)
        };
        selectPlace(nextPlace);
      },
      () => setError('Location permission was denied. Search a city instead.')
    );
  }

  const active = data?.scores?.[activeMode];
  const activeWindow = data?.windows?.[activeMode];
  const activeAppearanceWindow = data?.appearanceWindow?.[activeMode];
  const theme = data ? weatherTheme(data.current?.weather_code, data.current?.cloud_cover) : 'clear';
  const currentWeather = data ? weatherDescription(data.current?.weather_code, data.current?.cloud_cover) : 'Loading';
  const isNight = useMemo(() => {
    if (!data?.sunrise || !data?.sunset) return false;
    const now = Date.now();
    return now < data.sunrise.getTime() || now > data.sunset.getTime();
  }, [data]);
  const solarCountdown = useMemo(() => {
    if (!data?.sunrise || !data?.sunset) return { label: 'Solar Gap', value: '--' };
    const now = new Date();
    if (now < data.sunrise) return { label: 'To Sunrise', value: formatTimeBetween(now, data.sunrise) };
    if (now < data.sunset) return { label: 'To Sunset', value: formatTimeBetween(now, data.sunset) };
    return { label: 'Since Sunset', value: formatTimeBetween(now, data.sunset) };
  }, [data]);
  const dominant = useMemo(() => {
    if (!data) return null;
    return data.scores.sunset.probability >= data.scores.sunrise.probability ? 'sunset' : 'sunrise';
  }, [data]);

  return (
    <main className={`weather-${theme} ${isNight ? 'is-night' : 'is-day'}`}>
      <WeatherBackdrop theme={theme} isNight={isNight} />
      <section className="app-shell">
        <header className="topbar">
          <div className="brand-mark">
            <SunMedium size={22} />
            <strong>FireSky Now</strong>
          </div>
          <div className="header-meta">
            <span>North America Today</span>
            <b>{new Intl.DateTimeFormat('en-US', { hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date())}</b>
          </div>
        </header>

        <div className="location-row">
          <button onClick={() => setShowSearch((value) => !value)} className="location-button">
            <span>{title}</span>
            <ChevronDown size={20} />
          </button>
          <div className="quick-actions">
            <button onClick={locateMe} title="Locate"><LocateFixed size={18} /></button>
            <button onClick={() => load(place, activeMode, { force: true })} title="Refresh"><RefreshCw size={18} /></button>
          </div>
        </div>

        <AnimatePresence>
          {showSearch ? (
            <motion.div initial={{ opacity: 0, y: -12 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }}>
              <SearchPanel onSelect={selectPlace} />
            </motion.div>
          ) : null}
        </AnimatePresence>

        {error ? (
          <GlassCard className="notice">
            <AlertTriangle size={20} />
            <span>{error}</span>
          </GlassCard>
        ) : null}

        <AnimatePresence mode="wait">
          {loading || !data ? (
            <motion.div key="loading" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} className="loading">
              <Loader2 className="spin" />
              <span>Loading today's cloud field, air quality, and solar windows...</span>
            </motion.div>
          ) : (
            <motion.div key="content" className="dashboard-grid" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
              <div className="map-grid">
                <GlassCard delay={0.04}>
                  <h2>
                    <span>Probability</span>
                    <span>{activeMode === 'sunset' ? 'Sunset' : 'Sunrise'}</span>
                  </h2>
                  <HeatMap place={place} samples={grid} type="probability" />
                </GlassCard>
                <GlassCard delay={0.08}>
                  <h2>
                    <span>Quality</span>
                    <span>{activeMode === 'sunset' ? 'Sunset' : 'Sunrise'}</span>
                  </h2>
                  <HeatMap place={place} samples={grid} type="quality" />
                </GlassCard>
              </div>

              <GlassCard className="hero" delay={0.12}>
                <div className="hero-copy">
                  <div className="eyebrow">
                    <Sparkles size={14} />
                    <span>Today's Fire Sky Forecast · North America</span>
                  </div>
                  <div className="current-weather-line">
                    <strong>{Math.round(data.current?.temperature_2m ?? 0)}°</strong>
                    <span>{currentWeather}</span>
                  </div>
                  <h1>{dominant === 'sunset' ? 'Sunset is the better bet' : 'Sunrise is the better bet'}</h1>
                  <p>Explainable scoring from cloud layers, precipitation, visibility, humidity, aerosols, and PM2.5.</p>
                </div>
                <ScoreRing value={active.probability} label={describeScore(active.probability)} tone={activeMode} />
              </GlassCard>

              <GlassCard className="astro-card" delay={0.14}>
                <div className="astro-column blue">
                  <i />
                  <div className="astro-content">
                    <span className="astro-kicker">Blue Hour</span>
                    <div className="astro-row">
                      <small>Morning</small>
                      <strong>{formatRange(data.blueHour.sunrise.start, data.blueHour.sunrise.end, data.timeZone)}</strong>
                    </div>
                    <div className="astro-row">
                      <small>Evening</small>
                      <strong>{formatRange(data.blueHour.sunset.start, data.blueHour.sunset.end, data.timeZone)}</strong>
                    </div>
                    <div className="astro-note">
                      <span>Total</span>
                      <b>{formatTotalDuration([data.blueHour.sunrise, data.blueHour.sunset])}</b>
                    </div>
                    <div className="astro-mini-grid">
                      <div>
                        <span>Morning Length</span>
                        <b>{formatDuration(data.blueHour.sunrise.start, data.blueHour.sunrise.end)}</b>
                      </div>
                      <div>
                        <span>Evening Length</span>
                        <b>{formatDuration(data.blueHour.sunset.start, data.blueHour.sunset.end)}</b>
                      </div>
                    </div>
                  </div>
                </div>
                <div className="astro-column violet">
                  <i />
                  <div className="astro-content">
                    <span className="astro-kicker">Solar Events</span>
                    <div className="astro-row">
                      <small>Sunrise</small>
                      <strong>{formatTime(data.sunrise, data.timeZone)}</strong>
                    </div>
                    <div className="astro-row">
                      <small>Sunset</small>
                      <strong>{formatTime(data.sunset, data.timeZone)}</strong>
                    </div>
                    <div className="astro-note">
                      <span>Day Length</span>
                      <b>{formatDuration(data.sunrise, data.sunset)}</b>
                    </div>
                    <div className="astro-mini-grid">
                      <div>
                        <span>Peak Color</span>
                        <b>{formatRange(activeAppearanceWindow?.start, activeAppearanceWindow?.end, data.timeZone)}</b>
                      </div>
                      <div>
                        <span>{solarCountdown.label}</span>
                        <b>{solarCountdown.value}</b>
                      </div>
                    </div>
                  </div>
                </div>
              </GlassCard>

              <div className="controls-panel">
                <MetricRail activeMode={activeMode} data={data} />

                <div className="segmented">
                  <button className={activeMode === 'sunrise' ? 'selected' : ''} onClick={() => setActiveMode('sunrise')}>Sunrise</button>
                  <button className={activeMode === 'sunset' ? 'selected' : ''} onClick={() => setActiveMode('sunset')}>Sunset</button>
                  <button onClick={() => setShowSearch(true)}><SlidersHorizontal size={17} /> Change City</button>
                  <button title="Algorithm notes"><Info size={18} /></button>
                </div>
              </div>

              <GlassCard className="local-data" delay={0.2}>
                <div className="section-title">
                  <span>Local Data · 50km Radius</span>
                  <strong>{formatPercent(active.probability)} · {formatPercent(active.quality)}</strong>
                </div>
                <div className="data-pills">
                  <div><strong>{formatRange(activeAppearanceWindow?.start, activeAppearanceWindow?.end, data.timeZone)}</strong><span>Peak Window</span></div>
                  <div><strong>{Math.round(data.airSnapshot.us_aqi ?? 0)}</strong><span>Local AQI</span></div>
                  <div><strong>{((activeWindow.visibility ?? 0) / 1000).toFixed(1)}km</strong><span>Visibility</span></div>
                </div>
                <FactorBars score={active} />
                <div className="verdict">
                  <AlertTriangle size={18} />
                  <span>
                    {active.blockers.length
                      ? `Local constraints: ${active.blockers.join('; ')}. `
                      : `Local advantages: ${active.boosts.join('; ')}. `}
                    Confidence is about {Math.round(active.confidence)}%.
                  </span>
                </div>
              </GlassCard>
            </motion.div>
          )}
        </AnimatePresence>

        <footer className="source-line">
          <Check size={14} />
          <span>Open-Meteo Forecast + Air Quality · Today only.</span>
        </footer>
      </section>
    </main>
  );
}

createRoot(document.getElementById('root')).render(<App />);
