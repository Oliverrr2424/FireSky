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
  Loader2,
  Bell,
  BellOff,
  LocateFixed,
  MapPin,
  MoonStar,
  Minus,
  Plus,
  RefreshCw,
  Search,
  Sparkles,
  SunMedium,
  ThumbsDown,
  ThumbsUp
} from 'lucide-react';
import './styles.css';
import WeatherBackdrop from './WeatherBackdrop.jsx';

// Cache + refresh cadence. Open-Meteo has hourly quotas; we respect them by
// serving localStorage copies within a 90 minute window and fall back to stale
// (< 6h) data whenever the network fails.
const CACHE_VERSION = 'v7';
const CACHE_TTL_MS = 90 * 60 * 1000;
const STALE_TTL_MS = 6 * 60 * 60 * 1000;
const REFRESH_INTERVAL_MS = 90 * 60 * 1000;
const GEOCODE_CACHE_TTL_MS = 24 * 60 * 60 * 1000;
const REQUEST_TIMEOUT_MS = 12000;
const GRID_REQUEST_TIMEOUT_MS = 45000;
const FORECAST_CACHE_STEP = 0.05;
const GRID_CACHE_STEP = 0.1;
const pendingJsonRequests = new Map();
const IS_LOW_POWER_DEVICE = typeof navigator !== 'undefined' && (
  (Number.isFinite(navigator.deviceMemory) && navigator.deviceMemory <= 4) ||
  (Number.isFinite(navigator.hardwareConcurrency) && navigator.hardwareConcurrency <= 4)
);
const RETRYABLE_STATUS_CODES = new Set([408, 425, 429, 500, 502, 503, 504]);
const FEEDBACK_STORAGE_KEY = 'firesky:daily-feedback:v1';
const SETTINGS_STORAGE_KEY = 'firesky:mobile-settings:v1';
const SUNSET_ALERT_IDS = [7101, 7102];
const SUNSET_ALERT_THRESHOLD = 70;

function roundedCoordinate(value, step) {
  const rounded = Math.round(Number(value) / step) * step;
  return rounded.toFixed(step >= 0.1 ? 1 : 2);
}

function cacheKey(prefix, place) {
  const step = prefix === 'grid' ? GRID_CACHE_STEP : FORECAST_CACHE_STEP;
  const lat = roundedCoordinate(place.latitude, step);
  const lon = roundedCoordinate(place.longitude, step);
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

function sleep(ms) {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}

function isRetryableNetworkError(err) {
  if (!err) return false;
  if (err.retryable) return true;
  const message = String(err.message ?? '').toLowerCase();
  return (
    message.includes('timed out') ||
    message.includes('networkerror') ||
    message.includes('failed to fetch') ||
    message.includes('network request')
  );
}

async function fetchJson(url, errorLabel, timeoutMs = REQUEST_TIMEOUT_MS) {
  if (pendingJsonRequests.has(url)) return pendingJsonRequests.get(url);

  const maxAttempts = 2;
  const request = (async () => {
    for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
      const controller = new AbortController();
      const timeout = window.setTimeout(() => controller.abort(), timeoutMs);
      try {
        const response = await fetch(url, { signal: controller.signal });
        if (!response.ok) {
          const retryAfter = response.headers.get('Retry-After');
          const suffix = retryAfter ? `; retry after ${retryAfter}s` : '';
          const err = new Error(`${errorLabel} request failed (${response.status}${suffix})`);
          err.retryable = RETRYABLE_STATUS_CODES.has(response.status);
          throw err;
        }
        return await response.json();
      } catch (err) {
        if (err.name === 'AbortError') {
          const timeoutErr = new Error(`${errorLabel} request timed out`);
          timeoutErr.retryable = true;
          if (attempt < maxAttempts) {
            await sleep(300 * attempt);
            continue;
          }
          throw timeoutErr;
        }
        if (attempt < maxAttempts && isRetryableNetworkError(err)) {
          await sleep(300 * attempt);
          continue;
        }
        throw err;
      } finally {
        window.clearTimeout(timeout);
      }
    }
    throw new Error(`${errorLabel} request failed`);
  })().finally(() => {
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

function weightedProductScore(pairs, floor = 0.03) {
  const usable = pairs.filter(([value]) => value != null && !Number.isNaN(value));
  if (!usable.length) return 0;
  const totalWeight = usable.reduce((sum, [, weight]) => sum + weight, 0);
  const product = usable.reduce((nextProduct, [value, weight]) => {
    const normalized = Math.max(floor, clamp(value) / 100);
    return nextProduct * normalized ** (weight / totalWeight);
  }, 1);
  return clamp(product * 100);
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
const DEG = 180 / Math.PI;
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

function sunAzimuth(date, latitude, longitude) {
  const lw = -longitude * RAD;
  const phi = latitude * RAD;
  const d = toDays(date);
  const m = solarMeanAnomaly(d);
  const l = eclipticLongitude(m);
  const dec = declination(l, 0);
  const ra = rightAscension(l, 0);
  const h = siderealTime(d, lw) - ra;
  const azimuthFromSouth = Math.atan2(
    Math.sin(h),
    Math.cos(h) * Math.sin(phi) - Math.tan(dec) * Math.cos(phi)
  );
  return (azimuthFromSouth * DEG + 180 + 360) % 360;
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

function findEventAltitudeTime(eventDate, targetAltitude, latitude, longitude, mode) {
  if (!eventDate) return null;
  const oneHour = 60 * 60 * 1000;
  const before = new Date(eventDate.getTime() - 3 * oneHour);
  const after = new Date(eventDate.getTime() + 3 * oneHour);
  return findSunAltitudeCrossing(before, after, targetAltitude, latitude, longitude);
}

function peakColorAltitudeBand(window, air = {}, mode) {
  const highCloud = scoreBand(window?.cloudHigh, 0, 26, 72, 96) / 100;
  const midCloud = scoreBand(window?.cloudMid, 0, 18, 62, 90) / 100;
  const lowCloudClearance = scoreLowerBetter(window?.cloudLow, 14, 72) / 100;
  const totalCloud = scoreBand(window?.cloudTotal, 4, 34, 76, 97) / 100;
  const visibilityKm = (window?.visibility ?? 10000) / 1000;
  const visibility = scoreHigherBetter(visibilityKm, 5, 18) / 100;
  const aerosol = weightedAverage([
    [scoreBand(air?.aerosol_optical_depth ?? 0.1, 0, 0.04, 0.24, 0.65), 0.45],
    [scoreLowerBetter(air?.us_aqi ?? 40, 18, 120), 0.3],
    [scoreBand(air?.pm2_5 ?? 8, 0, 3, 14, 42), 0.25]
  ]) / 100;
  const cloudScreen = weightedAverage([
    [highCloud * 100, 0.46],
    [midCloud * 100, 0.28],
    [totalCloud * 100, 0.18],
    [lowCloudClearance * 100, 0.08]
  ]) / 100;
  const clarity = weightedAverage([
    [visibility * 100, 0.46],
    [lowCloudClearance * 100, 0.26],
    [aerosol * 100, 0.18],
    [scoreLowerBetter(window?.precipProbability ?? 0, 8, 68), 0.1]
  ]) / 100;
  const highCloudAfterglow = highCloud * 1.55 + midCloud * 0.42;
  const hazeCompression = clamp((0.56 - clarity) * 2.2, 0, 1.1);
  const weakScreenCompression = cloudScreen < 0.42 ? 0.75 : 0;
  const blockedHorizonCompression = lowCloudClearance < 0.45 ? 0.65 : 0;
  const deepTwilightAltitude = clamp(
    -3.7 - highCloudAfterglow + hazeCompression + weakScreenCompression,
    -7.2,
    -2.35
  );
  const nearHorizonAltitude = clamp(
    0.75 + midCloud * 0.65 - blockedHorizonCompression - hazeCompression * 0.28,
    -0.45,
    2.1
  );

  return mode === 'sunrise'
    ? { startAltitude: deepTwilightAltitude, endAltitude: nearHorizonAltitude }
    : { startAltitude: nearHorizonAltitude, endAltitude: deepTwilightAltitude };
}

function computeAppearanceWindows({ latitude, longitude, sunrise, sunset, windows, airSnapshot, airSnapshots }) {
  const sunriseBand = peakColorAltitudeBand(windows?.sunrise, airSnapshots?.sunrise ?? airSnapshot, 'sunrise');
  const sunsetBand = peakColorAltitudeBand(windows?.sunset, airSnapshots?.sunset ?? airSnapshot, 'sunset');
  const sunriseStart = findEventAltitudeTime(sunrise, sunriseBand.startAltitude, latitude, longitude, 'sunrise');
  const sunriseEnd = findEventAltitudeTime(sunrise, sunriseBand.endAltitude, latitude, longitude, 'sunrise');
  const sunsetStart = findEventAltitudeTime(sunset, sunsetBand.startAltitude, latitude, longitude, 'sunset');
  const sunsetEnd = findEventAltitudeTime(sunset, sunsetBand.endAltitude, latitude, longitude, 'sunset');
  return {
    sunrise: { start: sunriseStart, end: sunriseEnd, ...sunriseBand },
    sunset: { start: sunsetStart, end: sunsetEnd, ...sunsetBand }
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
    start: times[indexes[0]] ?? null,
    end: times[indexes[indexes.length - 1]] ?? null,
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
  const directBeam = window.directNormal ?? window.direct;
  const beamPurity = directBeam != null && window.diffuse != null && directBeam > 0
    ? scoreHigherBetter(directBeam / (window.diffuse + 1), 0.45, 3.2)
    : 55;
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
  const solarCorridor = context.solarCorridor ?? weightedAverage([
    [horizonOpening, 0.58],
    [lowCloudPenalty, 0.18],
    [rain, 0.14],
    [sunAccess, 0.1]
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
  const cloudCanvas = weightedAverage([
    [highCloud, 0.46],
    [midCloud, 0.29],
    [totalCloud, 0.14],
    [regionalTexture, 0.11]
  ]);
  const intensityScreen = weightedAverage([
    [highCloud, 0.42],
    [midCloud, 0.23],
    [regionalTexture, 0.22],
    [totalCloud, 0.13]
  ]);
  const opticalColor = weightedAverage([
    [scoreBand(aod ?? 0.1, 0, 0.04, 0.2, 0.55), 0.34],
    [scoreBand(pm25 ?? 8, 0, 3, 12, 35), 0.22],
    [scoreLowerBetter(aqi ?? 40, 18, 110), 0.18],
    [scoreLowerBetter(dust ?? 0, 5, 75), 0.1],
    [humidity, 0.1],
    [vpd, 0.06]
  ]);
  const contrast = weightedAverage([
    [visibility, 0.38],
    [beamPurity, 0.32],
    [scoreBand(window.humidity, mode === 'sunrise' ? 18 : 14, mode === 'sunrise' ? 36 : 28, mode === 'sunrise' ? 76 : 68, 92), 0.3]
  ]);

  const probability = weightedProductScore([
    [horizonOpening, 0.23],
    [solarCorridor, 0.18],
    [lowCloudPenalty, 0.17],
    [rain, 0.15],
    [visibility, 0.09],
    [cloudCanvas, 0.18]
  ]);

  const quality = weightedAverage([
    [intensityScreen, 0.34],
    [opticalColor, 0.24],
    [contrast, 0.16],
    [beamPurity, 0.1],
    [regionalTexture, 0.1],
    [wind, 0.06]
  ]);

  const blockers = [];
  if ((window.cloudLow ?? 0) > 48) blockers.push('Low cloud may block the horizon');
  if ((window.precipProbability ?? 0) > 45) blockers.push('Precipitation risk is elevated');
  if ((window.cloudTotal ?? 0) < 15) blockers.push('Too little cloud texture');
  if ((window.cloudTotal ?? 0) > 88) blockers.push('Cloud deck may be too thick');
  if (((window.visibility ?? 10000) / 1000) < 5) blockers.push('Visibility is limited');
  if (horizonOpening < 35) blockers.push(mode === 'sunset' ? 'Western low-sky opening is weak' : 'Eastern low-sky opening is weak');
  if (solarCorridor < 38) blockers.push(mode === 'sunset' ? 'Sunset light may be blocked upstream' : 'Sunrise light may be blocked upstream');

  const boosts = [];
  if ((window.cloudHigh ?? 0) >= 24 && (window.cloudHigh ?? 0) <= 70) boosts.push('High cloud screen is favorable');
  if ((window.cloudMid ?? 0) >= 18 && (window.cloudMid ?? 0) <= 64) boosts.push('Mid cloud has room to color');
  if ((aod ?? 0.1) >= 0.05 && (aod ?? 0.1) <= 0.32) boosts.push('Aerosol level is balanced');
  if ((window.precipProbability ?? 0) < 25) boosts.push('Precipitation interference is low');
  if (horizonOpening >= 68) boosts.push(mode === 'sunset' ? 'Western low sky is open' : 'Eastern low sky is open');
  if (solarCorridor >= 68) boosts.push(mode === 'sunset' ? 'Sunset light corridor is open' : 'Sunrise light corridor is open');

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
      beamPurity,
      sunAccess,
      horizonOpening,
      solarCorridor,
      regionalTexture,
      cloudScreen,
      blockersClearance,
      colorChemistry,
      cloudCanvas,
      intensityScreen,
      opticalColor,
      contrast
    },
    blockers,
    boosts,
    confidence: clamp(58 + (window.indexes.length - 1) * 8 + (context.sampleCount ? Math.min(12, context.sampleCount / 10) : 0) - blockers.length * 6)
  };
}

function formatTime(value, timeZone, utcOffsetSeconds = null) {
  if (!value) return '--:--';
  const date = typeof value === 'string' && utcOffsetSeconds != null ? localIsoToUtcDate(value, utcOffsetSeconds) : new Date(value);
  if (Number.isNaN(date.getTime())) return '--:--';
  return new Intl.DateTimeFormat('en-US', { hour: '2-digit', minute: '2-digit', hour12: false, timeZone }).format(date);
}

function formatRange(start, end, timeZone, utcOffsetSeconds = null) {
  if (!start || !end) return '--';
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
  const startTime = new Date(start).getTime();
  const endTime = new Date(end).getTime();
  if (!Number.isFinite(startTime) || !Number.isFinite(endTime)) return '--';
  return formatMinutes((endTime - startTime) / 60000);
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

function preferredForecastMode(forecast) {
  const hour = 60 * 60 * 1000;
  const now = Date.now();
  const sunriseEnd = forecast?.appearanceWindow?.sunrise?.end?.getTime?.();
  const sunsetEnd = forecast?.appearanceWindow?.sunset?.end?.getTime?.();
  if (sunriseEnd && sunsetEnd && now >= sunriseEnd + hour && now <= sunsetEnd + hour) return 'sunset';
  return 'sunrise';
}

function formatPercent(value) {
  return `${Math.round(value ?? 0)}%`;
}

function formatScoreRange(a, b) {
  const values = [a, b].filter((value) => Number.isFinite(value)).map((value) => Math.round(value));
  if (!values.length) return '--';
  const low = Math.min(...values);
  const high = Math.max(...values);
  return low === high ? `${low}%` : `${low}-${high}%`;
}

function describeScore(score) {
  if (score >= 85) return 'Excellent';
  if (score >= 72) return 'High chance';
  if (score >= 63) return 'Worth watching';
  if (score >= 42) return 'Possible';
  if (score >= 24) return 'Weak';
  return 'Unlikely';
}

function normalizeMlPercent(score) {
  const value = score?.score ?? score?.probability;
  if (!Number.isFinite(value)) return null;
  return clamp(value <= 1 ? value * 100 : value, 0, 100);
}

function mergeMlScore(ruleScore, mlScore) {
  const mlPercent = normalizeMlPercent(mlScore);
  if (mlPercent == null) return ruleScore;
  return {
    ...ruleScore,
    probability: mlPercent,
    ml: {
      ...mlScore,
      probability: mlPercent,
      fallbackProbability: ruleScore.probability
    }
  };
}

function scoreModelLabel(score) {
  return score?.ml ? 'ML v2' : 'Rules';
}

function ruleFallbackScore(score) {
  return score?.ml?.fallbackProbability ?? score?.probability ?? 0;
}

function debugScore(score) {
  if (!score) return null;
  return {
    displaySource: scoreModelLabel(score),
    displayedProbability: Math.round(score.probability ?? 0),
    ruleFallbackProbability: score.ml?.fallbackProbability != null ? Math.round(score.ml.fallbackProbability) : null,
    mlProbability: score.ml?.probability != null ? Math.round(score.ml.probability) : null,
    mlLevel: score.ml?.level,
    mlConfidence: score.ml?.confidence,
    featureCoverage: score.ml?.featureCoverage,
    thresholds: score.ml?.thresholds,
    components: score.ml?.components,
    ruleFactors: {
      cloudScreen: Math.round(score.factors?.cloudScreen ?? 0),
      solarCorridor: Math.round(score.factors?.solarCorridor ?? 0),
      blockersClearance: Math.round(score.factors?.blockersClearance ?? 0),
      colorChemistry: Math.round(score.factors?.colorChemistry ?? 0)
    }
  };
}

function logForecastScoring(forecast, place) {
  if (typeof console === 'undefined' || !forecast?.scores) return;
  console.info('[FireSky] forecast scoring', {
    place: place ? {
      name: place.name,
      latitude: place.latitude,
      longitude: place.longitude
    } : null,
    cloudflare: {
      mlStatus: forecast.ml?.status,
      mlModelVersion: forecast.ml?.modelVersion,
      mlCalibration: forecast.ml?.calibration,
      mlReason: forecast.ml?.reason,
      oofRocAuc: forecast.ml?.metrics?.oofRocAuc,
      oofAveragePrecision: forecast.ml?.metrics?.oofAveragePrecision
    },
    sunrise: debugScore(forecast.scores.sunrise),
    sunset: debugScore(forecast.scores.sunset)
  });
}

const SCORE_COLOR_STOPS = [
  [0, '#26376f'],
  [24, '#4b2f7f'],
  [42, '#c05a78'],
  [62, '#ee7a45'],
  [78, '#f6c85a'],
  [100, '#ff6a3d']
];

function hexToRgb(hex) {
  const value = Number.parseInt(hex.slice(1), 16);
  return [(value >> 16) & 255, (value >> 8) & 255, value & 255];
}

function scoreSpectrumColor(value) {
  const score = clamp(value ?? 0);
  const upperIndex = SCORE_COLOR_STOPS.findIndex(([stop]) => score <= stop);
  if (upperIndex <= 0) return SCORE_COLOR_STOPS[0][1];
  const [lowStop, lowColor] = SCORE_COLOR_STOPS[upperIndex - 1];
  const [highStop, highColor] = SCORE_COLOR_STOPS[upperIndex];
  const t = (score - lowStop) / (highStop - lowStop);
  const low = hexToRgb(lowColor);
  const high = hexToRgb(highColor);
  const [r, g, b] = low.map((channel, index) => Math.round(channel + (high[index] - channel) * t));
  return `rgb(${r}, ${g}, ${b})`;
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

const GRID_STEPS = IS_LOW_POWER_DEVICE ? 11 : 15;
const GRID_LAT_SPAN_DEG = 2.2;
const GRID_LON_SPAN_DEG = 4.4;
const SOLAR_CORRIDOR_MAX_KM = 430;
const SOLAR_CORRIDOR_MIN_KM = 25;

function evenlySpacedOffsets(min, max, steps) {
  return Array.from({ length: steps }, (_, index) => +(min + ((max - min) * index) / (steps - 1)).toFixed(4));
}

function createGridPlaces(place) {
  const latSteps = evenlySpacedOffsets(-GRID_LAT_SPAN_DEG, GRID_LAT_SPAN_DEG, GRID_STEPS);
  const lonSteps = evenlySpacedOffsets(-GRID_LON_SPAN_DEG, GRID_LON_SPAN_DEG, GRID_STEPS);
  const offsets = latSteps.flatMap((latOffset) => lonSteps.map((lonOffset) => [latOffset, lonOffset]));
  return offsets.map(([latOffset, lonOffset], index) => ({
    ...place,
    latitude: +(place.latitude + latOffset).toFixed(4),
    longitude: +(place.longitude + lonOffset).toFixed(4),
    sampleId: index
  }));
}

function airSnapshotAt(air, targetDate, utcOffsetSeconds = 0) {
  const index = nearestIndex(air?.hourly?.time ?? [], targetDate ?? new Date(), utcOffsetSeconds);
  return {
    us_aqi: air?.hourly?.us_aqi?.[index] ?? air?.current?.us_aqi,
    pm2_5: air?.hourly?.pm2_5?.[index] ?? air?.current?.pm2_5,
    pm10: air?.hourly?.pm10?.[index] ?? air?.current?.pm10,
    aerosol_optical_depth: air?.hourly?.aerosol_optical_depth?.[index] ?? air?.current?.aerosol_optical_depth,
    dust: air?.hourly?.dust?.[index] ?? air?.current?.dust
  };
}

function currentAirSnapshot(air) {
  return {
    us_aqi: air?.current?.us_aqi,
    pm2_5: air?.current?.pm2_5,
    pm10: air?.current?.pm10,
    aerosol_optical_depth: air?.current?.aerosol_optical_depth,
    dust: air?.current?.dust
  };
}

function isUsableWeatherPayload(weather) {
  return Boolean(
    weather &&
      Array.isArray(weather.hourly?.time) &&
      weather.hourly.time.length > 0 &&
      weather.daily?.sunrise?.[0] &&
      weather.daily?.sunset?.[0] &&
      Number.isFinite(Number(weather.latitude)) &&
      Number.isFinite(Number(weather.longitude))
  );
}

function isUsableForecastBundle(bundle) {
  return isUsableWeatherPayload(bundle?.weather);
}

function buildForecast({ weather, air = {}, ml }) {
  if (!isUsableWeatherPayload(weather)) throw new Error('Weather data is incomplete');
  const utcOffsetSeconds = weather.utc_offset_seconds ?? 0;
  const timeZone = weather.timezone || undefined;
  const sunrise = localIsoToUtcDate(weather.daily?.sunrise?.[0], utcOffsetSeconds);
  const sunset = localIsoToUtcDate(weather.daily?.sunset?.[0], utcOffsetSeconds);
  const sunriseWindow = pickWindow(weather.hourly, sunrise, 'sunrise', utcOffsetSeconds);
  const sunsetWindow = pickWindow(weather.hourly, sunset, 'sunset', utcOffsetSeconds);
  const airSnapshot = currentAirSnapshot(air);
  const airSnapshots = {
    sunrise: airSnapshotAt(air, sunrise, utcOffsetSeconds),
    sunset: airSnapshotAt(air, sunset, utcOffsetSeconds)
  };

  const sunriseScore = mergeMlScore(
    computeWindowScore(sunriseWindow, airSnapshots.sunrise, 'sunrise'),
    ml?.status === 'ok' ? ml?.scores?.sunrise : null
  );
  const sunsetScore = mergeMlScore(
    computeWindowScore(sunsetWindow, airSnapshots.sunset, 'sunset'),
    ml?.status === 'ok' ? ml?.scores?.sunset : null
  );
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
    sunset,
    windows: { sunrise: sunriseWindow, sunset: sunsetWindow },
    airSnapshot,
    airSnapshots
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
    ml,
    current: weather.current,
    airSnapshot,
    airSnapshots
  };
}

async function fetchForecast(place, { force = false } = {}) {
  const key = cacheKey('forecast', place);
  if (!force) {
    const cached = cacheGet(key);
    if (isUsableForecastBundle(cached)) return buildForecast(cached);
  }
  try {
    const bundle = await fetchJson(makeForecastUrl(place), 'Forecast');
    if (!isUsableForecastBundle(bundle)) throw new Error('Forecast response was incomplete');
    cacheSet(key, bundle);
    return buildForecast(bundle);
  } catch (err) {
    const stale = cacheGet(key, STALE_TTL_MS);
    if (isUsableForecastBundle(stale)) return buildForecast(stale);
    throw new Error('Weather data is temporarily unavailable');
  }
}

function distanceDegrees(a, b) {
  const latScale = Math.cos((((a.latitude + b.latitude) / 2) * Math.PI) / 180);
  const dx = (a.longitude - b.longitude) * latScale;
  const dy = a.latitude - b.latitude;
  return Math.sqrt(dx * dx + dy * dy);
}

function distanceKm(a, b) {
  const lat1 = a.latitude * RAD;
  const lat2 = b.latitude * RAD;
  const dLat = (b.latitude - a.latitude) * RAD;
  const dLon = (b.longitude - a.longitude) * RAD;
  const sinLat = Math.sin(dLat / 2);
  const sinLon = Math.sin(dLon / 2);
  const h = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLon * sinLon;
  return 6371 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
}

function bearingDegrees(a, b) {
  const lat1 = a.latitude * RAD;
  const lat2 = b.latitude * RAD;
  const dLon = (b.longitude - a.longitude) * RAD;
  const y = Math.sin(dLon) * Math.cos(lat2);
  const x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
  return (Math.atan2(y, x) * DEG + 360) % 360;
}

function angleDeltaDegrees(a, b) {
  return Math.abs((((a - b + 540) % 360) - 180));
}

function weightedMean(values) {
  const usable = values.filter(({ value, weight }) => value != null && !Number.isNaN(value) && weight > 0);
  if (!usable.length) return null;
  const totalWeight = usable.reduce((sum, item) => sum + item.weight, 0);
  return usable.reduce((sum, item) => sum + item.value * item.weight, 0) / totalWeight;
}

function standardDeviation(values) {
  const usable = values.filter((value) => value != null && !Number.isNaN(value));
  if (!usable.length) return 0;
  const mean = avg(usable);
  return Math.sqrt(avg(usable.map((value) => (value - mean) ** 2)));
}

function regionalContextForPoint(point, allPoints, mode) {
  const fallbackBearing = mode === 'sunset' ? 270 : 90;
  const sunBearing = Number.isFinite(point.sunBearing) ? point.sunBearing : fallbackBearing;
  const near = allPoints.filter((sample) => sample.sampleId !== point.sampleId && distanceKm(point, sample) <= 150);
  const sunward = allPoints
    .filter((sample) => sample.sampleId !== point.sampleId)
    .map((sample) => {
      const distance = distanceKm(point, sample);
      const alignment = angleDeltaDegrees(bearingDegrees(point, sample), sunBearing);
      const width = distance < 180 ? 30 : 20;
      const weight = Math.max(0, 1 - alignment / width) * (1 / (1 + distance / 160));
      return { sample, distance, alignment, weight };
    })
    .filter(({ distance, weight }) => (
      distance >= SOLAR_CORRIDOR_MIN_KM &&
      distance <= SOLAR_CORRIDOR_MAX_KM &&
      weight > 0
    ));
  const horizon = sunward.filter(({ distance }) => distance <= 190);
  const horizonSet = horizon.length ? horizon : near;
  const horizonValues = horizon.length
    ? horizonSet.map(({ sample, weight }) => ({ sample, weight }))
    : horizonSet.map((sample) => ({ sample, weight: 1 }));
  const corridorValues = sunward.length ? sunward.map(({ sample, weight }) => ({ sample, weight })) : horizonValues;
  const horizonLow = weightedMean(horizonValues.map(({ sample, weight }) => ({ value: sample.window.cloudLow, weight }))) ?? point.window.cloudLow;
  const horizonMid = weightedMean(horizonValues.map(({ sample, weight }) => ({ value: sample.window.cloudMid, weight }))) ?? point.window.cloudMid;
  const horizonTotal = weightedMean(horizonValues.map(({ sample, weight }) => ({ value: sample.window.cloudTotal, weight }))) ?? point.window.cloudTotal;
  const horizonPrecip = weightedMean(horizonValues.map(({ sample, weight }) => ({ value: sample.window.precipProbability, weight }))) ?? point.window.precipProbability;
  const horizonVisibility = weightedMean(horizonValues.map(({ sample, weight }) => ({ value: sample.window.visibility, weight }))) ?? point.window.visibility;
  const corridorLow = weightedMean(corridorValues.map(({ sample, weight }) => ({ value: sample.window.cloudLow, weight }))) ?? horizonLow;
  const corridorMid = weightedMean(corridorValues.map(({ sample, weight }) => ({ value: sample.window.cloudMid, weight }))) ?? horizonMid;
  const corridorTotal = weightedMean(corridorValues.map(({ sample, weight }) => ({ value: sample.window.cloudTotal, weight }))) ?? horizonTotal;
  const corridorPrecip = weightedMean(corridorValues.map(({ sample, weight }) => ({ value: sample.window.precipProbability, weight }))) ?? horizonPrecip;
  const corridorVisibility = weightedMean(corridorValues.map(({ sample, weight }) => ({ value: sample.window.visibility, weight }))) ?? horizonVisibility;
  const corridorOpticalBlock = corridorLow * 0.42 + corridorMid * 0.24 + corridorTotal * 0.2 + (corridorPrecip ?? 0) * 0.14;
  const regionalTextureRaw = standardDeviation(near.flatMap((sample) => [sample.window.cloudHigh, sample.window.cloudMid]));
  const horizonOpticalBlock = horizonLow * 0.52 + horizonMid * 0.18 + horizonTotal * 0.2 + (horizonPrecip ?? 0) * 0.1;
  return {
    sampleCount: allPoints.length,
    horizonOpening: weightedAverage([
      [scoreLowerBetter(horizonOpticalBlock, 20, 82), 0.56],
      [scoreLowerBetter(horizonPrecip ?? 0, 12, 70), 0.22],
      [scoreHigherBetter((horizonVisibility ?? 8000) / 1000, 6, 18), 0.22]
    ]),
    solarCorridor: weightedAverage([
      [scoreLowerBetter(corridorOpticalBlock, 18, 78), 0.64],
      [scoreLowerBetter(corridorPrecip ?? 0, 10, 68), 0.18],
      [scoreHigherBetter((corridorVisibility ?? 8000) / 1000, 6, 20), 0.18]
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

function applyRegionalContextToForecast(forecast, regionalPoints, place) {
  if (!forecast || !regionalPoints?.length) return forecast;
  const nextScores = { ...forecast.scores };
  ['sunrise', 'sunset'].forEach((mode) => {
    const target = mode === 'sunrise' ? forecast.sunrise : forecast.sunset;
    const point = {
      ...place,
      sampleId: -1,
      window: forecast.windows[mode],
      sunBearing: target ? sunAzimuth(target, place.latitude, place.longitude) : (mode === 'sunset' ? 270 : 90)
    };
    const context = regionalContextForPoint(point, regionalPoints, mode);
    nextScores[mode] = mergeMlScore(
      computeWindowScore(forecast.windows[mode], forecast.airSnapshots?.[mode] ?? forecast.airSnapshot, mode, context),
      forecast.ml?.status === 'ok' ? forecast.ml?.scores?.[mode] : forecast.scores?.[mode]?.ml
    );
  });
  return { ...forecast, scores: nextScores };
}

function buildGrid(payload, place, mode) {
  const samples = createGridPlaces(place);
  const rows = Array.isArray(payload) ? payload : [payload];
  const points = rows.slice(0, samples.length).map((item, index) => {
    const utcOffsetSeconds = item.utc_offset_seconds ?? 0;
    const target = mode === 'sunrise'
      ? localIsoToUtcDate(item.daily?.sunrise?.[0], utcOffsetSeconds)
      : localIsoToUtcDate(item.daily?.sunset?.[0], utcOffsetSeconds);
    const window = pickWindow(item.hourly, target ?? new Date(), mode, utcOffsetSeconds);
    const score = computeWindowScore(window, {}, mode);
    return {
      ...samples[index],
      window,
      sunBearing: target ? sunAzimuth(target, samples[index].latitude, samples[index].longitude) : (mode === 'sunset' ? 270 : 90),
      probability: score.probability,
      quality: score.quality
    };
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
    const payload = await fetchJson(`/api/grid?${params}`, 'Regional grid', GRID_REQUEST_TIMEOUT_MS);
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

function loadFeedbackStore() {
  if (typeof window === 'undefined') return {};
  try {
    const raw = window.localStorage.getItem(FEEDBACK_STORAGE_KEY);
    if (!raw) return {};
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch {
    return {};
  }
}

function saveFeedbackStore(store) {
  if (typeof window === 'undefined') return;
  try {
    window.localStorage.setItem(FEEDBACK_STORAGE_KEY, JSON.stringify(store));
  } catch {
    /* ignore feedback persistence issues */
  }
}

function loadMobileSettings() {
  if (typeof window === 'undefined') return { notificationsEnabled: true };
  try {
    const raw = window.localStorage.getItem(SETTINGS_STORAGE_KEY);
    if (!raw) return { notificationsEnabled: true };
    const parsed = JSON.parse(raw);
    return {
      notificationsEnabled: parsed?.notificationsEnabled !== false
    };
  } catch {
    return { notificationsEnabled: true };
  }
}

function saveMobileSettings(settings) {
  if (typeof window === 'undefined') return;
  try {
    window.localStorage.setItem(SETTINGS_STORAGE_KEY, JSON.stringify(settings));
  } catch {
    /* ignore settings persistence issues */
  }
}

function forecastDayKey(forecast) {
  if (!forecast?.sunset) return null;
  const formatter = new Intl.DateTimeFormat('en-CA', {
    timeZone: forecast.timeZone || 'UTC',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  });
  return formatter.format(forecast.sunset);
}

async function scheduleSunsetAlerts({ enabled, forecast, placeName }) {
  try {
    const [{ Capacitor }, { LocalNotifications }] = await Promise.all([
      import('@capacitor/core'),
      import('@capacitor/local-notifications')
    ]);
    if (!Capacitor.isNativePlatform()) return;

    await LocalNotifications.cancel({
      notifications: SUNSET_ALERT_IDS.map((id) => ({ id }))
    });

    if (!enabled || !forecast?.sunset || !forecast?.scores?.sunset) return;
    const probability = Math.round(forecast.scores.sunset.probability ?? 0);
    if (probability < SUNSET_ALERT_THRESHOLD) return;

    const permission = await LocalNotifications.checkPermissions();
    if (permission.display !== 'granted') {
      const requested = await LocalNotifications.requestPermissions();
      if (requested.display !== 'granted') return;
    }

    const sunsetTs = forecast.sunset.getTime();
    const reminders = [
      { id: SUNSET_ALERT_IDS[0], offsetMs: 2 * 60 * 60 * 1000, label: '2h' },
      { id: SUNSET_ALERT_IDS[1], offsetMs: 1 * 60 * 60 * 1000, label: '1h' }
    ]
      .map(({ id, offsetMs, label }) => ({
        id,
        label,
        at: new Date(sunsetTs - offsetMs)
      }))
      .filter((entry) => entry.at.getTime() > Date.now() + 15000);

    if (!reminders.length) return;

    await LocalNotifications.schedule({
      notifications: reminders.map((entry) => ({
        id: entry.id,
        title: 'FireSky Sunset Alert',
        body: `${placeName}: sunset chance ${probability}% (${entry.label} before sunset).`,
        schedule: { at: entry.at },
        smallIcon: 'ic_stat_icon_config_sample',
        sound: undefined
      }))
    });
  } catch {
    /* notification scheduling should not block forecast loading */
  }
}

async function requestNativeLocation() {
  try {
    const [{ Capacitor }, { Geolocation }] = await Promise.all([
      import('@capacitor/core'),
      import('@capacitor/geolocation')
    ]);
    if (!Capacitor.isNativePlatform()) return null;
    const permission = await Geolocation.checkPermissions();
    if (permission.location !== 'granted') {
      const asked = await Geolocation.requestPermissions();
      if (asked.location !== 'granted') return null;
    }
    return Geolocation.getCurrentPosition({
      enableHighAccuracy: true,
      timeout: 15000,
      maximumAge: 60000
    });
  } catch {
    return null;
  }
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

const SCORE_PARTICLES = [
  { id: 1, progress: 12, lane: 0.22, size: 1.2, opacity: 0.48 },
  { id: 2, progress: 18, lane: 0.78, size: 1.6, opacity: 0.62 },
  { id: 3, progress: 26, lane: 0.44, size: 1.1, opacity: 0.52 },
  { id: 4, progress: 34, lane: 0.7, size: 1.4, opacity: 0.46 },
  { id: 5, progress: 43, lane: 0.3, size: 1.7, opacity: 0.58 },
  { id: 6, progress: 51, lane: 0.62, size: 1.2, opacity: 0.5 },
  { id: 7, progress: 59, lane: 0.18, size: 1.5, opacity: 0.66 },
  { id: 8, progress: 67, lane: 0.84, size: 1.1, opacity: 0.5 },
  { id: 9, progress: 76, lane: 0.5, size: 1.7, opacity: 0.7 },
  { id: 10, progress: 84, lane: 0.28, size: 1.3, opacity: 0.56 },
  { id: 11, progress: 91, lane: 0.72, size: 1.6, opacity: 0.64 }
];

function ScoreRing({ value, label, tone = 'sunset' }) {
  const rounded = Math.round(value ?? 0);
  const targetValue = clamp(rounded, 0, 100);
  const [animatedValue, setAnimatedValue] = useState(0);
  const normalized = clamp(animatedValue, 0, 100);
  const svgWidth = 320;
  const svgHeight = 206;
  const cx = svgWidth / 2;
  const cy = 172;
  const radius = 128;
  const strokeWidth = 34;
  const angle = 180 - normalized * 1.8;
  const angleRad = (angle * Math.PI) / 180;
  const trackStartX = cx - radius;
  const trackEndX = cx + radius;
  const trackY = cy;
  const markerOuter = {
    x: cx + Math.cos(angleRad) * (radius + strokeWidth / 2 + 11),
    y: cy - Math.sin(angleRad) * (radius + strokeWidth / 2 + 11)
  };
  const markerInner = {
    x: cx + Math.cos(angleRad) * (radius - strokeWidth / 2 - 9),
    y: cy - Math.sin(angleRad) * (radius - strokeWidth / 2 - 9)
  };
  const accentColor = scoreSpectrumColor(normalized);

  useEffect(() => {
    let frameId;
    const duration = 950;
    const startTime = performance.now();
    const startValue = 0;
    const endValue = targetValue;

    function tick(now) {
      const progress = clamp((now - startTime) / duration, 0, 1);
      const eased = 1 - Math.pow(1 - progress, 3);
      setAnimatedValue(startValue + (endValue - startValue) * eased);
      if (progress < 1) frameId = requestAnimationFrame(tick);
    }

    setAnimatedValue(0);
    frameId = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frameId);
  }, [targetValue]);

  return (
    <motion.div
      className={`score-ring ${tone}`}
      style={{ '--score-value': normalized, '--score-color': accentColor }}
      initial={{ opacity: 0, scale: 0.92, rotate: -6 }}
      animate={{ opacity: 1, scale: 1, rotate: 0 }}
      transition={{ duration: 0.7, ease: [0.22, 1, 0.36, 1] }}
    >
      <div className="score-gauge-shell">
        <svg className="score-gauge" viewBox={`0 0 ${svgWidth} ${svgHeight}`} aria-hidden="true">
          <defs>
            <linearGradient id={`score-gauge-gradient-${tone}`} x1="0%" y1="0%" x2="100%" y2="0%">
              <stop offset="0%" stopColor="#4c1d95" />
              <stop offset="45%" stopColor="#e11d48" />
              <stop offset="85%" stopColor="#f97316" />
              <stop offset="100%" stopColor="#facc15" />
            </linearGradient>
            <filter id={`score-gauge-glow-${tone}`} x="-20%" y="-35%" width="140%" height="170%">
              <feGaussianBlur stdDeviation="10" result="blur" />
              <feComposite in="SourceGraphic" in2="blur" operator="over" />
            </filter>
          </defs>

          <path className="score-track" d={`M ${trackStartX} ${trackY} A ${radius} ${radius} 0 0 1 ${trackEndX} ${trackY}`} pathLength="100" />
          <motion.path
            className="score-meter score-meter-glow"
            d={`M ${trackStartX} ${trackY} A ${radius} ${radius} 0 0 1 ${trackEndX} ${trackY}`}
            pathLength="100"
            stroke={`url(#score-gauge-gradient-${tone})`}
            filter={`url(#score-gauge-glow-${tone})`}
            initial={{ strokeDashoffset: 100 }}
            animate={{ strokeDashoffset: 100 - normalized }}
            transition={{ duration: 0.95, ease: [0.22, 1, 0.36, 1] }}
          />
          <motion.path
            className="score-meter"
            d={`M ${trackStartX} ${trackY} A ${radius} ${radius} 0 0 1 ${trackEndX} ${trackY}`}
            pathLength="100"
            stroke={`url(#score-gauge-gradient-${tone})`}
            initial={{ strokeDashoffset: 100 }}
            animate={{ strokeDashoffset: 100 - normalized }}
            transition={{ duration: 0.95, ease: [0.22, 1, 0.36, 1] }}
          />

          {SCORE_PARTICLES.map((particle) => {
            const particleAngle = 180 - particle.progress * 1.8;
            const particleRad = (particleAngle * Math.PI) / 180;
            const particleRadius = radius - strokeWidth / 2 + particle.lane * strokeWidth;
            return (
              <circle
                key={particle.id}
                cx={cx + particleRadius * Math.cos(particleRad)}
                cy={cy - particleRadius * Math.sin(particleRad)}
                r={particle.size}
                fill="#ffffff"
                opacity={normalized >= particle.progress ? particle.opacity : 0}
              />
            );
          })}

          <motion.line
            className="score-needle"
            x1={markerInner.x}
            y1={markerInner.y}
            x2={markerOuter.x}
            y2={markerOuter.y}
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            transition={{ duration: 0.45 }}
          />
        </svg>

        <div className="score-orb-core">
          <strong>
            {Math.round(animatedValue)}
            <span>%</span>
          </strong>
          <small>{label}</small>
        </div>
      </div>
    </motion.div>
  );
}

function MetricRail({ activeMode, data }) {
  const score = data.scores[activeMode];
  const weather = weatherDescription(data.current?.weather_code, data.current?.cloud_cover);
  const activeAir = data.airSnapshots?.[activeMode] ?? data.airSnapshot;
  const items = [
    ['Now', weather, CloudSun],
    ['Sunset', formatScoreRange(data.scores.sunset.probability, data.scores.sunset.quality + 8), SunMedium],
    ['Sunrise', formatScoreRange(data.scores.sunrise.probability, data.scores.sunrise.quality + 8), MoonStar],
    ['Cloud Screen', `${Math.round(score.factors.cloudScreen ?? 0)}%`, Sparkles],
    ['Corridor', `${Math.round(score.factors.solarCorridor ?? 0)}%`, Aperture],
    ['Window AQI', activeAir?.us_aqi != null ? `${Math.round(activeAir.us_aqi)}` : '--']
  ];
  return (
    <div className="metric-rail">
      {items.map(([title, value, Icon], index) => (
        <motion.button
          key={title}
          whileHover={{ y: -3 }}
          whileTap={{ scale: 0.96 }}
          className="rail-item"
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
  const refineRef = useRef({ idle: 0, timer: 0 });

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
    const ctx = canvas.getContext('2d');
    const layerId = 'fire-sky-heat';
    let frame = 0;

    function clearRefine() {
      if (refineRef.current.idle && 'cancelIdleCallback' in window) {
        window.cancelIdleCallback(refineRef.current.idle);
      }
      if (refineRef.current.timer) {
        window.clearTimeout(refineRef.current.timer);
      }
      refineRef.current = { idle: 0, timer: 0 };
    }

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

    function scheduleRefinedDraw() {
      clearRefine();
      const run = () => scheduleDraw('refined');
      if ('requestIdleCallback' in window) {
        refineRef.current.idle = window.requestIdleCallback(run, { timeout: 1400 });
      } else {
        refineRef.current.timer = window.setTimeout(run, 850);
      }
    }

    function draw(quality = 'fast') {
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
      const lowPowerMultiplier = IS_LOW_POWER_DEVICE ? 1.35 : 1;
      const resolution = quality === 'fast'
        ? (cssWidth > 1400 ? 3 : 2.35) * lowPowerMultiplier
        : (cssWidth > 1400 ? 1.65 : 1.35) * lowPowerMultiplier;
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
        if (quality === 'fast') scheduleRefinedDraw();
      } catch {
        /* The map can be removed between draw scheduling and repaint. */
      }
    }

    function scheduleDraw(quality = 'fast') {
      if (!mapIsUsable(map)) return;
      if (frame) cancelAnimationFrame(frame);
      frame = requestAnimationFrame(() => draw(quality));
    }

    redrawRef.current = () => scheduleDraw('fast');
    scheduleDraw('fast');
    const scheduleFastDraw = () => scheduleDraw('fast');
    map.on('load', scheduleFastDraw);
    map.on('moveend', scheduleFastDraw);
    map.on('zoomend', scheduleFastDraw);
    map.on('resize', scheduleFastDraw);

    return () => {
      clearRefine();
      if (frame) cancelAnimationFrame(frame);
      map.off('load', scheduleFastDraw);
      map.off('moveend', scheduleFastDraw);
      map.off('zoomend', scheduleFastDraw);
      map.off('resize', scheduleFastDraw);
      redrawRef.current = null;
      removeReferenceLineOverlays(map);
      safeRemoveLayer(map, layerId);
    };
  }, [canvasRef, map, onReady]);

  return null;
}

function mapLabelValue(sample, type) {
  return type === 'probability' ? sample.probability : sample.quality;
}

function representativeMapSamples(samples, type) {
  if (!samples?.length) return [];
  const ranked = samples
    .slice()
    .filter((sample) => Number.isFinite(mapLabelValue(sample, type)))
    .sort((a, b) => mapLabelValue(b, type) - mapLabelValue(a, type));
  if (!ranked.length) return [];
  const indexes = [0.16, 0.5, 0.84].map((ratio) => Math.min(ranked.length - 1, Math.round((ranked.length - 1) * ratio)));
  const picked = [];
  indexes.forEach((index) => {
    const candidate = ranked[index];
    if (!picked.some((sample) => sample.sampleId === candidate.sampleId)) picked.push(candidate);
  });
  return picked.slice(0, 3);
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
      const nextLabels = representativeMapSamples(samples, type)
        .map((sample) => {
          const point = map.project([sample.longitude, sample.latitude]);
          return {
            key: `${sample.latitude}-${sample.longitude}`,
            x: point.x,
            y: point.y,
            value: mapLabelValue(sample, type)
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
          style={{ left: `${label.x}px`, top: `${label.y}px` }}
        >
          {Math.round(label.value)}%
        </strong>
      ))}
    </div>
  );
}

function sampleBounds(samples, fallbackCenter) {
  const base = samples?.length
    ? samples
    : [{ latitude: fallbackCenter[0], longitude: fallbackCenter[1] }];
  return base.reduce(
    (nextBounds, sample) => nextBounds.extend([sample.longitude, sample.latitude]),
    new maplibregl.LngLatBounds()
  );
}

function paddedBounds(bounds, ratio = 0.08) {
  const west = bounds.getWest();
  const east = bounds.getEast();
  const south = bounds.getSouth();
  const north = bounds.getNorth();
  const lonPad = Math.max(0.08, (east - west) * ratio);
  const latPad = Math.max(0.06, (north - south) * ratio);
  return new maplibregl.LngLatBounds(
    [west - lonPad, south - latPad],
    [east + lonPad, north + latPad]
  );
}

function MapHeatPanel({ place, samples, type }) {
  const nodeRef = useRef(null);
  const heatCanvasRef = useRef(null);
  const mapRef = useRef(null);
  const [map, setMap] = useState(null);
  const [mapSettled, setMapSettled] = useState(false);
  const [heatReady, setHeatReady] = useState(false);
  const [zoomLevel, setZoomLevel] = useState(7);
  const [zoomLimits, setZoomLimits] = useState({ min: 4, max: 8.75 });
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
      pitchWithRotate: false,
      maxZoom: 8.75
    });
    nextMap.addControl(new maplibregl.AttributionControl({ compact: true }), 'bottom-right');
    const onLoad = () => {
      if (!disposed) enhanceMapReferenceStyle(nextMap);
    };
    nextMap.on('load', onLoad);
    mapRef.current = nextMap;
    setMap(nextMap);
    const updateZoom = () => setZoomLevel(nextMap.getZoom());
    nextMap.on('zoom', updateZoom);
    nextMap.on('zoomend', updateZoom);

    return () => {
      disposed = true;
      nextMap.off('load', onLoad);
      nextMap.off('zoom', updateZoom);
      nextMap.off('zoomend', updateZoom);
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
      const bounds = sampleBounds(samples, center);
      const constrainedBounds = paddedBounds(bounds);
      const camera = map.cameraForBounds(bounds, { padding: [30, 30] });
      const fitZoom = camera?.zoom == null ? 7 : Math.min(camera.zoom, 7);
      const minZoom = Math.max(2, fitZoom - 0.03);
      const maxZoom = Math.max(minZoom + 0.5, 8.75);
      map.setMaxBounds(constrainedBounds);
      map.setMinZoom(minZoom);
      map.setMaxZoom(maxZoom);
      setZoomLimits({ min: minZoom, max: maxZoom });
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

  const zoomIn = useCallback(() => {
    if (mapIsUsable(map)) map.zoomIn({ duration: 220 });
  }, [map]);

  const zoomOut = useCallback(() => {
    if (mapIsUsable(map)) map.zoomOut({ duration: 220 });
  }, [map]);

  return (
    <div className={`real-map ${type} ${isReady ? 'is-ready' : 'is-loading'}`}>
      <div ref={nodeRef} className="maplibre-node" />
      <canvas ref={heatCanvasRef} className="maplibre-heat-source" />
      <MapLibreHeatCanvasLayer map={map} canvasRef={heatCanvasRef} samples={samples} type={type} onReady={handleHeatReady} />
      {isReady ? <MapScoreLabels map={map} samples={samples} type={type} /> : null}
      <div className="map-zoom-controls" aria-label="Map zoom controls">
        <button type="button" onClick={zoomIn} disabled={!map || zoomLevel >= zoomLimits.max - 0.05} title="Zoom in">
          <Plus size={16} />
        </button>
        <button type="button" onClick={zoomOut} disabled={!map || zoomLevel <= zoomLimits.min + 0.05} title="Zoom out">
          <Minus size={16} />
        </button>
      </div>
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
    ['Cloud Screen', score.factors.cloudScreen],
    ['Blocker Clearance', score.factors.blockersClearance],
    ['Solar Corridor', score.factors.solarCorridor],
    ['Color Chemistry', score.factors.colorChemistry],
    ['Sun Access', score.factors.sunAccess],
    ['Regional Texture', score.factors.regionalTexture],
    ['Rain Clearance', score.factors.rain],
    ['Visibility', score.factors.visibility]
  ];
  return (
    <div className="factor-bars">
      {labels.map(([label, value]) => (
        <div className="factor" key={label}>
          <div>
            <span>{label}</span>
            <strong>{Math.round(value ?? 0)}</strong>
          </div>
          <i>
            <motion.b
              initial={{ width: 0 }}
              animate={{ width: `${clamp(value ?? 0)}%` }}
              transition={{ duration: 0.8 }}
              style={{
                '--spectrum-size': `${clamp(value ?? 0) > 0 ? 10000 / clamp(value ?? 0) : 100}% 100%`
              }}
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
  const savedSettings = useMemo(() => loadMobileSettings(), []);
  const [place, setPlace] = useState(DEFAULT_PLACE);
  const [activeMode, setActiveMode] = useState('sunset');
  const [data, setData] = useState(null);
  const [grid, setGrid] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [isOffline, setIsOffline] = useState(typeof navigator !== 'undefined' ? !navigator.onLine : false);
  const [notificationsEnabled, setNotificationsEnabled] = useState(savedSettings.notificationsEnabled);
  const [feedbackStore, setFeedbackStore] = useState(() => loadFeedbackStore());
  const [showSearch, setShowSearch] = useState(false);
  const loadSeq = useRef(0);
  const userSelectedModeRef = useRef(false);

  const title = `${place.name}${place.admin1 ? ` · ${place.admin1}` : ''}`;

  async function load(nextPlace = place, nextMode = activeMode, { force = false, silent = false } = {}) {
    const seq = loadSeq.current + 1;
    loadSeq.current = seq;
    if (!silent) setLoading(true);
    setError('');
    try {
      const forecast = await fetchForecast(nextPlace, { force });
      logForecastScoring(forecast, nextPlace);
      const modeForGrid = userSelectedModeRef.current ? nextMode : preferredForecastMode(forecast);
      if (seq !== loadSeq.current) return;
      if (!userSelectedModeRef.current && modeForGrid !== activeMode) setActiveMode(modeForGrid);
      setData(forecast);
      setGrid(null);
      if (!silent) setLoading(false);
      try {
        const nextGrid = await fetchGrid(nextPlace, modeForGrid, { force });
        if (seq !== loadSeq.current) return;
        setGrid(nextGrid);
        setData(applyRegionalContextToForecast(forecast, nextGrid, nextPlace));
      } catch {
        if (seq === loadSeq.current) setError('Regional map is temporarily unavailable; local weather is still current.');
      }
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
    if (data) {
      fetchGrid(place, activeMode)
        .then((nextGrid) => {
          setGrid(nextGrid);
          setData((current) => applyRegionalContextToForecast(current, nextGrid, place));
        })
        .catch(() => setGrid(null));
    }
  }, [activeMode]);

  // Auto-refresh every 90 minutes to stay within Open-Meteo hourly quotas.
  // The refresh runs silently (no loading spinner) and force-bypasses cache.
  useEffect(() => {
    const id = window.setInterval(() => {
      load(place, activeMode, { force: true, silent: true });
    }, REFRESH_INTERVAL_MS);
    return () => window.clearInterval(id);
  }, [place, activeMode]);

  useEffect(() => {
    function markOnline() {
      setIsOffline(false);
    }
    function markOffline() {
      setIsOffline(true);
    }
    window.addEventListener('online', markOnline);
    window.addEventListener('offline', markOffline);
    return () => {
      window.removeEventListener('online', markOnline);
      window.removeEventListener('offline', markOffline);
    };
  }, []);

  useEffect(() => {
    saveMobileSettings({ notificationsEnabled });
  }, [notificationsEnabled]);

  function selectPlace(nextPlace) {
    setPlace(nextPlace);
    setShowSearch(false);
    load(nextPlace, activeMode);
  }

  function selectMode(mode) {
    userSelectedModeRef.current = true;
    setActiveMode(mode);
  }

  async function locateMe() {
    setError('');
    const nativePosition = await requestNativeLocation();
    if (nativePosition) {
      const nextPlace = {
        name: 'Current Location',
        admin1: 'North America',
        country_code: 'US',
        latitude: +nativePosition.coords.latitude.toFixed(4),
        longitude: +nativePosition.coords.longitude.toFixed(4)
      };
      selectPlace(nextPlace);
      return;
    }
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
      (geoError) => {
        if (geoError?.code === 1) {
          setError('Location permission was denied. Enable it in settings or search a city instead.');
          return;
        }
        if (geoError?.code === 2) {
          setError('Unable to determine your location right now. Please try again.');
          return;
        }
        setError('Location request timed out. Search a city instead.');
      },
      {
        enableHighAccuracy: true,
        timeout: 15000,
        maximumAge: 60000
      }
    );
  }

  const active = data?.scores?.[activeMode];
  const activeWindow = data?.windows?.[activeMode];
  const activeAppearanceWindow = data?.appearanceWindow?.[activeMode];
  const activeAirSnapshot = data?.airSnapshots?.[activeMode] ?? data?.airSnapshot;
  const theme = data ? weatherTheme(data.current?.weather_code, data.current?.cloud_cover) : 'clear';
  const currentWeather = data ? weatherDescription(data.current?.weather_code, data.current?.cloud_cover) : 'Loading';
  const isNight = useMemo(() => {
    if (!data?.sunrise || !data?.sunset) return false;
    const now = Date.now();
    return now < data.sunrise.getTime() || now > data.sunset.getTime();
  }, [data]);
  const dominant = useMemo(() => {
    if (!data) return null;
    return data.scores.sunset.probability >= data.scores.sunrise.probability ? 'sunset' : 'sunrise';
  }, [data]);
  const sunsetDay = useMemo(() => forecastDayKey(data), [data]);
  const sunsetFeedback = sunsetDay ? feedbackStore[sunsetDay] : null;
  const recentSunsetFeedback = useMemo(
    () => Object.values(feedbackStore)
      .filter((item) => item?.day)
      .sort((a, b) => (b.updatedAt ?? 0) - (a.updatedAt ?? 0))
      .slice(0, 7),
    [feedbackStore]
  );

  useEffect(() => {
    if (!data?.scores?.sunset || !sunsetDay) return;
    setFeedbackStore((current) => {
      const existing = current[sunsetDay] ?? {};
      const next = {
        ...current,
        [sunsetDay]: {
          ...existing,
          probability: Math.round(data.scores.sunset.probability ?? 0),
          day: sunsetDay,
          place: title,
          updatedAt: Date.now()
        }
      };
      saveFeedbackStore(next);
      return next;
    });
  }, [data?.scores?.sunset?.probability, sunsetDay, title]);

  useEffect(() => {
    scheduleSunsetAlerts({
      enabled: notificationsEnabled,
      forecast: data,
      placeName: title
    });
  }, [notificationsEnabled, data?.sunset?.getTime?.(), data?.scores?.sunset?.probability, title]);

  function updateSunsetFeedback(patch) {
    if (!sunsetDay || !data?.scores?.sunset) return;
    setFeedbackStore((current) => {
      const next = {
        ...current,
        [sunsetDay]: {
          ...(current[sunsetDay] ?? {}),
          day: sunsetDay,
          place: title,
          probability: Math.round(data.scores.sunset.probability ?? 0),
          ...patch,
          updatedAt: Date.now()
        }
      };
      saveFeedbackStore(next);
      return next;
    });
  }

  function toggleSunsetReaction(sentiment) {
    const currentSentiment = sunsetFeedback?.sentiment ?? null;
    updateSunsetFeedback({ sentiment: currentSentiment === sentiment ? null : sentiment });
  }

  function toggleSunsetAccuracy(accurate) {
    const currentAccuracy = sunsetFeedback?.accurate;
    updateSunsetFeedback({ accurate: currentAccuracy === accurate ? null : accurate });
  }

  function toggleNotifications() {
    setNotificationsEnabled((value) => !value);
  }

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
          <div className="location-controls">
            <div className="mode-toggle" aria-label="Forecast mode">
              <button className={activeMode === 'sunrise' ? 'selected' : ''} onClick={() => selectMode('sunrise')}>Sunrise</button>
              <button className={activeMode === 'sunset' ? 'selected' : ''} onClick={() => selectMode('sunset')}>Sunset</button>
            </div>
            <div className="quick-actions">
              <button onClick={locateMe} title="Locate"><LocateFixed size={18} /></button>
              <button onClick={() => load(place, activeMode, { force: true })} title="Refresh"><RefreshCw size={18} /></button>
              <button onClick={toggleNotifications} title={notificationsEnabled ? 'Disable sunset alerts' : 'Enable sunset alerts'} className={notificationsEnabled ? 'selected' : ''}>
                {notificationsEnabled ? <Bell size={18} /> : <BellOff size={18} />}
              </button>
            </div>
          </div>
        </div>

        <AnimatePresence>
          {showSearch ? (
            <motion.div initial={{ opacity: 0, y: -12 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }}>
              <SearchPanel onSelect={selectPlace} />
            </motion.div>
          ) : null}
        </AnimatePresence>

        {isOffline ? (
          <GlassCard className="notice network-notice">
            <AlertTriangle size={20} />
            <span>You are offline. Cached forecasts are shown when available.</span>
          </GlassCard>
        ) : null}

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
                    <span>ML Chance</span>
                    <span>{activeMode === 'sunset' ? 'Sunset' : 'Sunrise'}</span>
                  </h2>
                  <HeatMap place={place} samples={grid} type="probability" />
                </GlassCard>
                <GlassCard delay={0.08}>
                  <h2>
                    <span>Intensity</span>
                    <span>{activeMode === 'sunset' ? 'Sunset' : 'Sunrise'}</span>
                  </h2>
                  <HeatMap place={place} samples={grid} type="quality" />
                </GlassCard>
              </div>

              <GlassCard className="hero" delay={0.12}>
                <div className="hero-copy">
                  <div className="eyebrow">
                    <Sparkles size={14} />
                    <span>Today's Fire Sky Forecast</span>
                  </div>
                  <div className="current-weather-line">
                    <strong>{Math.round(data.current?.temperature_2m ?? 0)}°</strong>
                    <span>{currentWeather}</span>
                  </div>
                  <h1>{dominant === 'sunset' ? 'Sunset is the better bet' : 'Sunrise is the better bet'}</h1>
                  <p>{active?.ml ? 'ML v2 chance with rule-based weather explanations and automatic fallback.' : 'Rule-based fallback from cloud layers, precipitation, visibility, humidity, aerosols, and PM2.5.'}</p>
                </div>
                <ScoreRing value={active.probability} label={describeScore(active.probability)} tone={activeMode} />
              </GlassCard>

              <GlassCard className="astro-card" delay={0.14}>
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
                        <span>Best Duration</span>
                        <b>{formatDuration(activeAppearanceWindow?.start, activeAppearanceWindow?.end)}</b>
                      </div>
                    </div>
                  </div>
                </div>
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
              </GlassCard>

              <div className="controls-panel">
                <MetricRail activeMode={activeMode} data={data} />
              </div>

              <GlassCard className="local-data" delay={0.2}>
                <div className="section-title">
                  <span>Window Forecast · Solar Corridor</span>
                  <strong className="score-pair">
                    <span><small>Rule Score</small>{formatPercent(ruleFallbackScore(active))}</span>
                    <i />
                    <span><small>Intensity</small>{formatPercent(active.quality)}</span>
                  </strong>
                </div>
                <div className="data-pills">
                  <div><strong>{formatRange(activeAppearanceWindow?.start, activeAppearanceWindow?.end, data.timeZone)}</strong><span>Peak Window</span></div>
                  <div><strong>{formatDuration(activeAppearanceWindow?.start, activeAppearanceWindow?.end)}</strong><span>Best Duration</span></div>
                  <div><strong>{activeAirSnapshot?.us_aqi != null ? Math.round(activeAirSnapshot.us_aqi) : '--'}</strong><span>Window AQI</span></div>
                  <div><strong>{((activeWindow.visibility ?? 0) / 1000).toFixed(1)}km</strong><span>Window Visibility</span></div>
                </div>
                <FactorBars score={active} />
                <div className="feedback-panel">
                  <div className="feedback-head">
                    <span>Daily Sunset Feedback</span>
                    <b>{sunsetDay || '--'}</b>
                  </div>
                  <div className="feedback-meta">
                    <span>Predicted chance</span>
                    <strong>{data?.scores?.sunset ? `${Math.round(data.scores.sunset.probability)}%` : '--'}</strong>
                  </div>
                  <div className="feedback-actions">
                    <button
                      type="button"
                      className={sunsetFeedback?.sentiment === 'like' ? 'selected' : ''}
                      onClick={() => toggleSunsetReaction('like')}
                    >
                      <ThumbsUp size={15} />
                      <span>Like</span>
                    </button>
                    <button
                      type="button"
                      className={sunsetFeedback?.sentiment === 'dislike' ? 'selected' : ''}
                      onClick={() => toggleSunsetReaction('dislike')}
                    >
                      <ThumbsDown size={15} />
                      <span>Dislike</span>
                    </button>
                    <button
                      type="button"
                      className={sunsetFeedback?.accurate === true ? 'selected' : ''}
                      onClick={() => toggleSunsetAccuracy(true)}
                    >
                      Accurate
                    </button>
                    <button
                      type="button"
                      className={sunsetFeedback?.accurate === false ? 'selected' : ''}
                      onClick={() => toggleSunsetAccuracy(false)}
                    >
                      Not Accurate
                    </button>
                  </div>
                  <div className="feedback-history">
                    {recentSunsetFeedback.length ? recentSunsetFeedback.map((item) => (
                      <div key={item.day}>
                        <span>{item.day}</span>
                        <strong>{Number.isFinite(item.probability) ? `${item.probability}%` : '--'}</strong>
                        <small>{item.accurate == null ? 'Pending' : (item.accurate ? 'Accurate' : 'Not Accurate')}</small>
                      </div>
                    )) : <p>No daily sunset feedback yet.</p>}
                  </div>
                </div>
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
          <span>{data?.ml?.status === 'ok' ? 'FireSky ML v2 + Open-Meteo Forecast + Air Quality · Today only.' : 'Open-Meteo Forecast + Air Quality · Rule fallback · Today only.'}</span>
        </footer>
      </section>
    </main>
  );
}

if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/service-worker.js')
      .then((registration) => {
        registration.addEventListener('updatefound', () => {
          const installing = registration.installing;
          if (!installing) return;
          installing.addEventListener('statechange', () => {
            if (installing.state === 'installed' && navigator.serviceWorker.controller) {
              const shouldReload = window.confirm('A new FireSky version is ready. Reload now?');
              if (shouldReload) window.location.reload();
            }
          });
        });
      })
      .catch(() => {
        /* service worker registration should not block app startup */
      });
  });
}

createRoot(document.getElementById('root')).render(<App />);
