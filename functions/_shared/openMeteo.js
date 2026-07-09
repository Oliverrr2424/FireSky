const CACHE_VERSION = 'v6';
const FRESH_TTL_SECONDS = 90 * 60;
const STALE_TTL_SECONDS = 6 * 60 * 60;
const GEOCODE_FRESH_TTL_SECONDS = 7 * 24 * 60 * 60;
const GEOCODE_STALE_TTL_SECONDS = 30 * 24 * 60 * 60;
const REQUEST_TIMEOUT_MS = 12000;
const CACHE_TIMEOUT_MS = 1200;
const RETRYABLE_STATUS_CODES = new Set([408, 425, 429, 500, 502, 503, 504]);
const GRID_STEPS = 15;
const GRID_LAT_SPAN_DEG = 2.2;
const GRID_LON_SPAN_DEG = 4.4;
const FORECAST_CACHE_STEP = 0.05;
const GRID_CACHE_STEP = 0.1;

const WEATHER_VARS = [
  'cloud_cover',
  'cloud_cover_low',
  'cloud_cover_mid',
  'cloud_cover_high',
  'dew_point_2m',
  'relative_humidity_2m',
  'precipitation_probability',
  'precipitation',
  'pressure_msl',
  'surface_pressure',
  'visibility',
  'wind_speed_10m',
  'wind_gusts_10m',
  'weather_code',
  'cape',
  'shortwave_radiation',
  'direct_radiation',
  'diffuse_radiation',
  'direct_normal_irradiance',
  'sunshine_duration',
  'vapour_pressure_deficit'
].join(',');

const DAILY_VARS = 'sunrise,sunset,uv_index_max,precipitation_probability_max';
const AIR_VARS = 'us_aqi,pm2_5,pm10,carbon_monoxide,nitrogen_dioxide,sulphur_dioxide,ozone,aerosol_optical_depth,dust';

export function jsonResponse(value, init = {}) {
  return new Response(JSON.stringify(value), {
    ...init,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'public, max-age=60',
      // Public, unauthenticated read-only weather data: allow cross-origin
      // access so the packaged mobile apps (Capacitor WebView origin is
      // https://localhost, not this Pages domain) can call it directly.
      'Access-Control-Allow-Origin': '*',
      ...(init.headers ?? {})
    }
  });
}

export function errorResponse(message, status = 500) {
  return jsonResponse({ error: message }, { status, headers: { 'Cache-Control': 'no-store' } });
}

export function getCache(env) {
  return env.FIRESKY_CACHE ?? null;
}

export function readLatLon(request) {
  const url = new URL(request.url);
  const latitude = Number(url.searchParams.get('lat'));
  const longitude = Number(url.searchParams.get('lon'));

  if (!Number.isFinite(latitude) || latitude < -90 || latitude > 90) {
    throw new Response('Invalid latitude', { status: 400 });
  }
  if (!Number.isFinite(longitude) || longitude < -180 || longitude > 180) {
    throw new Response('Invalid longitude', { status: 400 });
  }

  return {
    latitude: +latitude.toFixed(4),
    longitude: +longitude.toFixed(4)
  };
}

export function cacheKey(prefix, latitude, longitude) {
  return `firesky:${CACHE_VERSION}:${prefix}:${latitude}:${longitude}`;
}

function roundedCoordinate(value, step) {
  const rounded = Math.round(Number(value) / step) * step;
  return rounded.toFixed(step >= 0.1 ? 1 : 2);
}

export function cacheCoordinates(place, prefix) {
  const step = prefix === 'grid' ? GRID_CACHE_STEP : FORECAST_CACHE_STEP;
  return {
    latitude: roundedCoordinate(place.latitude, step),
    longitude: roundedCoordinate(place.longitude, step)
  };
}

export function textCacheKey(prefix, value) {
  return `firesky:${CACHE_VERSION}:${prefix}:${String(value).trim().toLowerCase()}`;
}

async function withTimeout(promise, timeoutMs, fallbackValue = undefined) {
  let timer;
  try {
    return await Promise.race([
      promise,
      new Promise((resolve) => {
        timer = setTimeout(() => resolve(fallbackValue), timeoutMs);
      })
    ]);
  } finally {
    clearTimeout(timer);
  }
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function isRetryableFetchError(err) {
  const message = String(err?.message ?? '').toLowerCase();
  return err?.retryable ||
    message.includes('timed out') ||
    message.includes('network') ||
    message.includes('fetch failed');
}

export async function cachedJson(env, key, fetcher, options = {}) {
  const cache = getCache(env);
  const freshTtl = options.freshTtl ?? FRESH_TTL_SECONDS;
  const staleTtl = options.staleTtl ?? STALE_TTL_SECONDS;
  const now = Date.now();

  const cached = cache ? await withTimeout(cache.get(key, 'json'), CACHE_TIMEOUT_MS, null) : null;
  if (cached?.timestamp && now - cached.timestamp <= freshTtl * 1000) {
    return { value: cached.value, cacheStatus: 'hit' };
  }

  try {
    const value = await fetcher();
    if (options.validate && !options.validate(value)) {
      throw new Error(options.invalidMessage || 'Cached payload failed validation');
    }
    if (cache) {
      await withTimeout(
        cache.put(key, JSON.stringify({ timestamp: now, value }), {
          expirationTtl: staleTtl
        }),
        CACHE_TIMEOUT_MS
      );
    }
    return { value, cacheStatus: cached ? 'refresh' : 'miss' };
  } catch (err) {
    if (cached?.value && now - cached.timestamp <= staleTtl * 1000) {
      return { value: cached.value, cacheStatus: 'stale' };
    }
    throw err;
  }
}

export async function fetchOpenMeteoJson(url, label, timeoutMs = REQUEST_TIMEOUT_MS, options = {}) {
  const maxAttempts = options.maxAttempts ?? 3;

  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), timeoutMs);

    try {
      const response = await fetch(url, {
        signal: controller.signal,
        headers: { Accept: 'application/json' }
      });
      if (!response.ok) {
        const retryAfter = response.headers.get('Retry-After');
        const suffix = retryAfter ? `; retry after ${retryAfter}s` : '';
        const err = new Error(`${label} request failed (${response.status}${suffix})`);
        err.retryable = RETRYABLE_STATUS_CODES.has(response.status);
        throw err;
      }
      const value = await response.json();
      if (options.validate && !options.validate(value)) {
        const err = new Error(options.invalidMessage || `${label} response was incomplete`);
        err.retryable = true;
        throw err;
      }
      return value;
    } catch (err) {
      const error = err.name === 'AbortError' ? new Error(`${label} request timed out`) : err;
      if (err.name === 'AbortError') error.retryable = true;
      if (attempt < maxAttempts && isRetryableFetchError(error)) {
        await sleep(250 * attempt);
        continue;
      }
      throw error;
    } finally {
      clearTimeout(timeout);
    }
  }

  throw new Error(`${label} request failed`);
}

export function isUsableWeatherPayload(value) {
  return Boolean(
    value &&
      Array.isArray(value.hourly?.time) &&
      value.hourly.time.length > 0 &&
      Array.isArray(value.daily?.sunrise) &&
      Array.isArray(value.daily?.sunset) &&
      value.daily.sunrise[0] &&
      value.daily.sunset[0] &&
      Number.isFinite(Number(value.latitude)) &&
      Number.isFinite(Number(value.longitude))
  );
}

export function isUsableForecastBundle(value) {
  return Boolean(value?.weather && isUsableWeatherPayload(value.weather));
}

export function makeForecastUrl(place) {
  const params = new URLSearchParams({
    latitude: String(place.latitude),
    longitude: String(place.longitude),
    hourly: WEATHER_VARS,
    daily: DAILY_VARS,
    current: 'temperature_2m,relative_humidity_2m,cloud_cover,weather_code,wind_speed_10m',
    forecast_days: '7',
    timezone: 'auto',
    wind_speed_unit: 'kmh'
  });
  return `https://api.open-meteo.com/v1/forecast?${params}`;
}

export function makeAirUrl(place) {
  const params = new URLSearchParams({
    latitude: String(place.latitude),
    longitude: String(place.longitude),
    hourly: AIR_VARS,
    current: 'us_aqi,pm2_5,pm10,aerosol_optical_depth,dust',
    forecast_days: '7',
    timezone: 'auto'
  });
  return `https://air-quality-api.open-meteo.com/v1/air-quality?${params}`;
}

function evenlySpacedOffsets(min, max, steps) {
  return Array.from({ length: steps }, (_, index) => +(min + ((max - min) * index) / (steps - 1)).toFixed(4));
}

export function createGridPlaces(place) {
  const latSteps = evenlySpacedOffsets(-GRID_LAT_SPAN_DEG, GRID_LAT_SPAN_DEG, GRID_STEPS);
  const lonSteps = evenlySpacedOffsets(-GRID_LON_SPAN_DEG, GRID_LON_SPAN_DEG, GRID_STEPS);
  return latSteps.flatMap((latOffset) => lonSteps.map((lonOffset) => ({
    latitude: +(place.latitude + latOffset).toFixed(4),
    longitude: +(place.longitude + lonOffset).toFixed(4)
  })));
}

export function makeGridUrl(place, samples = createGridPlaces(place)) {
  const params = new URLSearchParams({
    latitude: samples.map((sample) => sample.latitude).join(','),
    longitude: samples.map((sample) => sample.longitude).join(','),
    hourly: WEATHER_VARS,
    daily: DAILY_VARS,
    forecast_days: '1',
    timezone: 'auto',
    wind_speed_unit: 'kmh'
  });
  return `https://api.open-meteo.com/v1/forecast?${params}`;
}

export function makeGridUrls(place, batchSize = 75) {
  const samples = createGridPlaces(place);
  const urls = [];
  for (let index = 0; index < samples.length; index += batchSize) {
    urls.push(makeGridUrl(place, samples.slice(index, index + batchSize)));
  }
  return urls;
}

export function makeGeocodeUrl(query) {
  const params = new URLSearchParams({
    name: query,
    count: '6',
    language: 'zh',
    format: 'json'
  });
  return `https://geocoding-api.open-meteo.com/v1/search?${params}`;
}

export const ttl = {
  fresh: FRESH_TTL_SECONDS,
  stale: STALE_TTL_SECONDS,
  geocodeFresh: GEOCODE_FRESH_TTL_SECONDS,
  geocodeStale: GEOCODE_STALE_TTL_SECONDS
};
