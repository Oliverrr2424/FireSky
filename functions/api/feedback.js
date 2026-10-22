import { accountError, accountJson, bearerToken, database, readJson, requireUser } from '../_shared/account.js';

export { onRequestOptions } from './account.js';

const REASONS =['too_high', 'too_low', 'wrong_time', 'other'];
// Nearby places see slightly different sunrise/sunset minutes, so one "event" is
// a window in time and a cell in space rather than an exact match.
const EVENT_WINDOW_MS = 20 * 60 * 1000;
const CELL_DEGREES = 0.25;

// Number(null) and Number('') are 0, which would pass for a real timestamp or coordinate.
const present = (value) => (value === null || value === undefined || value === '' ? NaN : Number(value));

function eventFrom(source) {
  const eventAt = present(source.eventAt); const latitude = present(source.latitude); const longitude = present(source.longitude);
  if (!Number.isFinite(eventAt) || !Number.isFinite(latitude) || !Number.isFinite(longitude)) return null;
  if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) return null;
  return { eventAt, mode: source.mode === 'sunrise' ? 'sunrise' : 'sunset', latitude, longitude };
}

const WINDOW_SQL = `mode = ? AND event_at BETWEEN ? AND ? AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ?`;
function windowArgs(event) {
  return [event.mode, event.eventAt - EVENT_WINDOW_MS, event.eventAt + EVENT_WINDOW_MS,
    event.latitude - CELL_DEGREES, event.latitude + CELL_DEGREES, event.longitude - CELL_DEGREES, event.longitude + CELL_DEGREES];
}

async function summary(db, event, userId) {
  const counted = await db.prepare(`SELECT sentiment, COUNT(*) AS n FROM forecast_feedback WHERE ${WINDOW_SQL} AND sentiment IS NOT NULL GROUP BY sentiment`)
    .bind(...windowArgs(event)).all();
  const counts = { like: 0, dislike: 0 };
  for (const row of counted.results || []) if (row.sentiment in counts) counts[row.sentiment] = Number(row.n);
  let mine = null;
  if (userId) {
    const own = await db.prepare(`SELECT sentiment, reason FROM forecast_feedback WHERE user_id = ? AND ${WINDOW_SQL} AND sentiment IS NOT NULL ORDER BY updated_at DESC LIMIT 1`)
      .bind(userId, ...windowArgs(event)).all();
    const row = own.results?.[0];
    if (row) mine = { sentiment: row.sentiment, reason: row.reason || null };
  }
  return { counts, mine };
}

// Reading is open: anyone can see how many people found a forecast helpful.
// Only a valid session also gets its own reaction back, so a reinstall or a
// second device shows the same state.
export async function onRequestGet({ request, env }) {
  const db = database(env);
  if (!db) return accountError(request, 'Cloud sync is not configured yet', 503);
  const params = new URL(request.url).searchParams;
  const event = eventFrom({ eventAt: params.get('eventAt'), mode: params.get('mode'), latitude: params.get('lat'), longitude: params.get('lon') });
  if (!event) return accountError(request, 'A forecast event and location are required');
  let userId = null;
  if (bearerToken(request)) {
    const auth = await requireUser(request, env);
    if (!auth.error) userId = auth.user.id;
  }
  return accountJson(request, { ...(await summary(db, event, userId)), signedIn: Boolean(userId) });
}

// One reaction per person per forecast event. Sending no sentiment withdraws it.
export async function onRequestPost({ request, env }) {
  const auth = await requireUser(request, env);
  if (auth.error) return accountError(request, auth.error, auth.status);
  try {
    const body = await readJson(request);
    const event = eventFrom(body);
    if (!event) return accountError(request, 'A forecast event and location are required');
    const latitude = +event.latitude.toFixed(4); const longitude = +event.longitude.toFixed(4);
    const sentiment = body.sentiment === 'like' || body.sentiment === 'dislike' ? body.sentiment : null;
    if (!sentiment) {
      await auth.db.prepare('DELETE FROM forecast_feedback WHERE user_id = ? AND event_at = ? AND mode = ? AND latitude = ? AND longitude = ?')
        .bind(auth.user.id, event.eventAt, event.mode, latitude, longitude).run();
    } else {
      const probability = Number(body.probability);
      if (!Number.isFinite(probability)) return accountError(request, 'A forecast probability is required');
      const reason = sentiment === 'dislike' && REASONS.includes(body.reason) ? body.reason : null;
      await auth.db.prepare(`INSERT INTO forecast_feedback (id,user_id,event_at,mode,place_name,latitude,longitude,predicted_probability,outcome,sentiment,reason,notes,updated_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(user_id,event_at,mode,latitude,longitude) DO UPDATE SET
        place_name=excluded.place_name,predicted_probability=excluded.predicted_probability,sentiment=excluded.sentiment,reason=excluded.reason,updated_at=excluded.updated_at`
      ).bind(crypto.randomUUID(), auth.user.id, event.eventAt, event.mode, String(body.placeName || '').slice(0, 100), latitude, longitude,
        Math.round(Math.max(0, Math.min(100, probability))), 'unknown', sentiment, reason, '', Date.now()).run();
    }
    return accountJson(request, { saved: true, ...(await summary(auth.db, event, auth.user.id)) });
  } catch (error) { return accountError(request, error.message); }
}
