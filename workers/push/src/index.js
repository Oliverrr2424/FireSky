const FIREBASE_SCOPE = 'https://www.googleapis.com/auth/firebase.messaging';

function base64Url(value) { return btoa(typeof value === 'string' ? value : JSON.stringify(value)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, ''); }

async function firebaseAccessToken(env) {
  const service = JSON.parse(env.FIREBASE_SERVICE_ACCOUNT);
  const now = Math.floor(Date.now() / 1000);
  const unsigned = `${base64Url({ alg: 'RS256', typ: 'JWT' })}.${base64Url({ iss: service.client_email, scope: FIREBASE_SCOPE, aud: 'https://oauth2.googleapis.com/token', iat: now, exp: now + 3500 })}`;
  const pem = service.private_key.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, '');
  const key = await crypto.subtle.importKey('pkcs8', Uint8Array.from(atob(pem), (c) => c.charCodeAt(0)), { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']);
  const signature = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(unsigned));
  const assertion = `${unsigned}.${base64Url(String.fromCharCode(...new Uint8Array(signature)))}`;
  const response = await fetch('https://oauth2.googleapis.com/token', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion }) });
  if (!response.ok) throw new Error('Firebase access token request failed');
  return (await response.json()).access_token;
}

async function sendFcm(env, accessToken, token, title, body, data) {
  const service = JSON.parse(env.FIREBASE_SERVICE_ACCOUNT);
  const response = await fetch(`https://fcm.googleapis.com/v1/projects/${service.project_id}/messages:send`, { method: 'POST', headers: { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json' }, body: JSON.stringify({ message: { token, notification: { title, body }, data: Object.fromEntries(Object.entries(data).map(([key, value]) => [key, String(value)])), android: { priority: 'high' } } }) });
  return response.ok || response.status === 404; // An invalid/removed token is harmless; it will be refreshed next login.
}

function forecastTime(value, offsetSeconds = 0) {
  const [date, time = '00:00'] = String(value).split('T'); const [year, month, day] = date.split('-').map(Number); const [hour, minute] = time.split(':').map(Number);
  return Date.UTC(year, month - 1, day, hour || 0, minute || 0) - offsetSeconds * 1000;
}

function nextEvent(weather, mode) {
  const values = weather?.daily?.[mode] || []; const now = Date.now();
  return values.map((value) => forecastTime(value, weather?.utc_offset_seconds || 0)).find((value) => value > now + 15 * 60 * 1000) || null;
}

async function runAlerts(env) {
  if (!env.FIRESKY_DB || !env.FIRESKY_API_ORIGIN || !env.FIREBASE_SERVICE_ACCOUNT) return;
  const rows = await env.FIRESKY_DB.prepare(`SELECT l.user_id,l.name,l.latitude,l.longitude,s.alert_lead_minutes,s.alert_threshold,s.sunrise_alerts,s.sunset_alerts,d.id device_id,d.push_token
    FROM saved_locations l JOIN user_settings s ON s.user_id=l.user_id JOIN devices d ON d.user_id=l.user_id
    WHERE s.notifications_enabled=1 AND d.enabled=1`).all();
  const accessToken = await firebaseAccessToken(env);
  await Promise.allSettled((rows.results || []).map(async (row) => {
    const forecast = await fetch(`${env.FIRESKY_API_ORIGIN}/api/forecast?lat=${row.latitude}&lon=${row.longitude}`).then((response) => response.ok ? response.json() : null);
    for (const mode of ['sunrise', 'sunset']) {
      if ((mode === 'sunrise' && !row.sunrise_alerts) || (mode === 'sunset' && !row.sunset_alerts)) continue;
      const eventAt = nextEvent(forecast?.weather, mode); const rawScore = Number(forecast?.ml?.scores?.[mode]?.probability ?? forecast?.ml?.scores?.[mode]?.score ?? 0); const score = rawScore <= 1 ? rawScore * 100 : rawScore;
      if (!eventAt || score < row.alert_threshold || eventAt - Date.now() > row.alert_lead_minutes * 60 * 1000 || eventAt - Date.now() < 2 * 60 * 1000) continue;
      const already = await env.FIRESKY_DB.prepare('SELECT 1 FROM alert_deliveries WHERE user_id=? AND event_at=? AND mode=? AND device_id=?').bind(row.user_id, eventAt, mode, row.device_id).first();
      if (already) continue;
      const sent = await sendFcm(env, accessToken, row.push_token, `FireSky ${mode === 'sunrise' ? 'Sunrise' : 'Sunset'} Alert`, `${row.name}: ${Math.round(score)}% chance near the target window.`, { mode, eventAt, latitude: row.latitude, longitude: row.longitude });
      if (sent) await env.FIRESKY_DB.prepare('INSERT OR IGNORE INTO alert_deliveries (user_id,event_at,mode,device_id,sent_at) VALUES (?,?,?,?,?)').bind(row.user_id, eventAt, mode, row.device_id, Date.now()).run();
    }
  }));
}

export default { async scheduled(_event, env, ctx) { ctx.waitUntil(runAlerts(env)); } };
