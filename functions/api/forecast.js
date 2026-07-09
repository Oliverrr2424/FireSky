import {
  cachedJson,
  cacheCoordinates,
  cacheKey,
  errorResponse,
  fetchOpenMeteoJson,
  isUsableForecastBundle,
  isUsableWeatherPayload,
  jsonResponse,
  makeAirUrl,
  makeForecastUrl,
  readLatLon
} from '../_shared/openMeteo.js';
import { fetchMlForecast } from '../_shared/mlForecast.js';

export async function onRequestGet({ request, env }) {
  try {
    const place = readLatLon(request);
    const cachePoint = cacheCoordinates(place, 'forecast');
    const key = cacheKey('forecast', cachePoint.latitude, cachePoint.longitude);
    const { value, cacheStatus } = await cachedJson(env, key, async () => {
      const weather = await fetchOpenMeteoJson(makeForecastUrl(place), 'Forecast', undefined, {
        validate: isUsableWeatherPayload,
        invalidMessage: 'Forecast response did not include usable weather data'
      });
      const airResult = await Promise.allSettled([
        fetchOpenMeteoJson(makeAirUrl(place), 'Air quality')
      ]);
      const air = airResult[0].status === 'fulfilled' ? airResult[0].value : {};
      const warnings = airResult[0].status === 'rejected'
        ? [`Air quality unavailable: ${airResult[0].reason?.message || 'request failed'}`]
        : undefined;
      return { weather, air, warnings };
    }, {
      validate: isUsableForecastBundle,
      invalidMessage: 'Forecast bundle did not include usable weather data'
    });
    const ml = await fetchMlForecast(env, place, value);
    console.info('[FireSky forecast]', {
      latitude: place.latitude,
      longitude: place.longitude,
      weatherCache: cacheStatus,
      mlStatus: ml.status,
      mlModel: ml.modelVersion,
      mlReason: ml.reason,
      sunriseMlScore: ml.scores?.sunrise?.probability,
      sunsetMlScore: ml.scores?.sunset?.probability
    });

    return jsonResponse({ ...value, ml }, {
      headers: {
        'Cache-Control': 'public, max-age=300, stale-while-revalidate=5400',
        'X-FireSky-Cache': cacheStatus,
        'X-FireSky-ML': ml.status
      }
    });
  } catch (err) {
    if (err instanceof Response) return err;
    return errorResponse(err.message || 'Weather data is temporarily unavailable', 502);
  }
}
