import {
  cachedJson,
  cacheCoordinates,
  cacheKey,
  errorResponse,
  fetchOpenMeteoJson,
  jsonResponse,
  makeGridUrl,
  readLatLon
} from '../_shared/openMeteo.js';

const GRID_REQUEST_TIMEOUT_MS = 45000;

export async function onRequestGet({ request, env }) {
  try {
    const place = readLatLon(request);
    const cachePoint = cacheCoordinates(place, 'grid');
    const key = cacheKey('grid', cachePoint.latitude, cachePoint.longitude);
    const { value, cacheStatus } = await cachedJson(env, key, () => (
      fetchOpenMeteoJson(makeGridUrl(place), 'Regional grid', GRID_REQUEST_TIMEOUT_MS)
    ));

    return jsonResponse(value, {
      headers: {
        'Cache-Control': 'public, max-age=300, stale-while-revalidate=5400',
        'X-FireSky-Cache': cacheStatus
      }
    });
  } catch (err) {
    if (err instanceof Response) return err;
    return errorResponse(err.message || 'Regional map data is temporarily unavailable', 502);
  }
}
