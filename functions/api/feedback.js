import { accountError, accountJson, readJson, requireUser } from '../_shared/account.js';

export { onRequestOptions } from './account.js';

export async function onRequestPost({ request, env }) {
  const auth = await requireUser(request, env);
  if (auth.error) return accountError(request, auth.error, auth.status);
  try {
    const body = await readJson(request);
    const eventAt = Number(body.eventAt); const probability = Number(body.probability);
    if (!Number.isFinite(eventAt) || !Number.isFinite(probability)) return accountError(request, 'A forecast event and probability are required');
    const outcome = ['yes', 'no', 'unknown'].includes(body.outcome) ? body.outcome : 'unknown';
    const sentiment = ['like', 'dislike', null].includes(body.sentiment) ? body.sentiment : null;
    await auth.db.prepare(`INSERT INTO forecast_feedback (id,user_id,event_at,mode,place_name,latitude,longitude,predicted_probability,outcome,sentiment,notes,updated_at)
      VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(user_id,event_at,mode,latitude,longitude) DO UPDATE SET
      predicted_probability=excluded.predicted_probability,outcome=excluded.outcome,sentiment=excluded.sentiment,notes=excluded.notes,updated_at=excluded.updated_at`
    ).bind(crypto.randomUUID(), auth.user.id, eventAt, body.mode === 'sunrise' ? 'sunrise' : 'sunset', String(body.placeName || '').slice(0, 100), +Number(body.latitude).toFixed(4), +Number(body.longitude).toFixed(4), Math.round(Math.max(0, Math.min(100, probability))), outcome, sentiment, String(body.notes || '').slice(0, 500), Date.now()).run();
    return accountJson(request, { saved: true });
  } catch (error) { return accountError(request, error.message); }
}
