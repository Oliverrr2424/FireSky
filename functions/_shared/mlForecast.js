const ML_REQUEST_TIMEOUT_MS = 6500;

function mlEndpoint(env) {
  const value = env.ML_FORECAST_URL || env.FIRESKY_ML_URL;
  return typeof value === 'string' && value.trim() ? value.trim() : null;
}

export async function fetchMlForecast(env, place, bundle) {
  const endpoint = mlEndpoint(env);
  if (!endpoint) {
    return {
      status: 'disabled',
      reason: 'ML_FORECAST_URL is not configured'
    };
  }

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), ML_REQUEST_TIMEOUT_MS);
  try {
    const response = await fetch(endpoint, {
      method: 'POST',
      signal: controller.signal,
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        latitude: place.latitude,
        longitude: place.longitude,
        weather: bundle.weather,
        air: bundle.air,
        events: ['sunrise', 'sunset']
      })
    });

    if (!response.ok) {
      return {
        status: 'error',
        reason: `ML forecast failed (${response.status})`
      };
    }

    const value = await response.json();
    return {
      status: 'ok',
      ...value
    };
  } catch (err) {
    return {
      status: 'error',
      reason: err.name === 'AbortError' ? 'ML forecast timed out' : (err.message || 'ML forecast unavailable')
    };
  } finally {
    clearTimeout(timeout);
  }
}
