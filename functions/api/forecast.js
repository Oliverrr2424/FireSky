import {
  cachedJson,
  cacheCoordinates,
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
    const cachePoint = cacheCoordinates(place, 'forecast');
    const key = cacheKey('forecast', cachePoint.latitude, cachePoint.longitude);
    const { value, cacheStatus } = await cachedJson(env, key, async () => {
      const [weather, air] = await Promise.all([
        fetchOpenMeteoJson(makeForecastUrl(place), 'Forecast'),
        fetchOpenMeteoJson(makeAirUrl(place), 'Air quality')
      ]);
      return { weather, air };
    });

    return jsonResponse(value, {
      headers: {
        'Cache-Control': 'public, max-age=300, stale-while-revalidate=5400',
        'X-FireSky-Cache': cacheStatus
      }
    });
  } catch (err) {
    if (err instanceof Response) return err;
    return errorResponse(err.message || 'Weather data is temporarily unavailable', 502);
  }
}
