import {
  cachedJson,
  cacheKey,
  errorResponse,
  fetchOpenMeteoJson,
  jsonResponse,
  makeAirUrl,
  makeForecastUrl,
  readLatLon
} from '../_shared/openMeteo.js';

export async function onRequestGet({ request, env }) {
  try {
    const place = readLatLon(request);
    const key = cacheKey('forecast', place.cacheLatitude, place.cacheLongitude);
    const { value, cacheStatus } = await cachedJson(env, key, async () => {
      const [weather, air] = await Promise.all([
        fetchOpenMeteoJson(makeForecastUrl(place), 'Forecast'),
        fetchOpenMeteoJson(makeAirUrl(place), 'Air quality')
      ]);
      return { weather, air };
    });

    return jsonResponse(value, { headers: { 'X-FireSky-Cache': cacheStatus } });
  } catch (err) {
    if (err instanceof Response) return err;
    return errorResponse(err.message || 'Weather data is temporarily unavailable', 502);
  }
}
