import {
  cachedJson,
  cacheKey,
  errorResponse,
  fetchOpenMeteoJson,
  jsonResponse,
  makeGridUrl,
  readLatLon
} from '../_shared/openMeteo.js';

export async function onRequestGet({ request, env }) {
  try {
    const place = readLatLon(request);
    const key = cacheKey('grid', place.cacheLatitude, place.cacheLongitude);
    const { value, cacheStatus } = await cachedJson(env, key, () => (
      fetchOpenMeteoJson(makeGridUrl(place), 'Regional grid')
    ));

    return jsonResponse(value, { headers: { 'X-FireSky-Cache': cacheStatus } });
  } catch (err) {
    if (err instanceof Response) return err;
    return errorResponse(err.message || 'Regional map data is temporarily unavailable', 502);
  }
}
