import {
  cachedJson,
  errorResponse,
  fetchOpenMeteoJson,
  jsonResponse,
  makeGeocodeUrl,
  textCacheKey,
  ttl
} from '../_shared/openMeteo.js';

export async function onRequestGet({ request, env }) {
  try {
    const url = new URL(request.url);
    const query = (url.searchParams.get('q') ?? '').trim();
    if (query.length < 2 || query.length > 80) {
      return errorResponse('Search query must be between 2 and 80 characters', 400);
    }

    const key = textCacheKey('geocode', query);
    const { value, cacheStatus } = await cachedJson(env, key, async () => {
      const data = await fetchOpenMeteoJson(makeGeocodeUrl(query), 'Location search');
      return (data.results ?? []).filter((place) => ['US', 'CA', 'MX'].includes(place.country_code));
    }, {
      freshTtl: ttl.geocodeFresh,
      staleTtl: ttl.geocodeStale
    });

    return jsonResponse(value, { headers: { 'X-FireSky-Cache': cacheStatus } });
  } catch (err) {
    return errorResponse(err.message || 'Unable to search locations', 502);
  }
}
