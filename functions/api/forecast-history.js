import { accountError, accountJson, readJson, requireUser } from '../_shared/account.js';

export { onRequestOptions } from './account.js';

export async function onRequestGet({ request, env }) {
  const auth = await requireUser(request, env);
  if (auth.error) return accountError(request, auth.error, auth.status);
  const url = new URL(request.url); const eventAt = Number(url.searchParams.get('eventAt')); const mode = url.searchParams.get('mode') === 'sunrise' ? 'sunrise' : 'sunset';
  if (!Number.isFinite(eventAt)) return accountError(request, 'eventAt is required');
  const rows = await auth.db.prepare('SELECT calculated_at, probability, quality, model_version FROM forecast_snapshots WHERE user_id=? AND event_at=? AND mode=? ORDER BY calculated_at ASC LIMIT 96').bind(auth.user.id, eventAt, mode).all();
  // Keep the API shape aligned with the client/local snapshot store. D1 uses
  // snake_case columns, while the application persists JavaScript snapshots in
  // camelCase; returning the raw row left timeline labels without a timestamp.
  const snapshots = (rows.results || []).map((row) => ({
    calculatedAt: Number(row.calculated_at),
    probability: Number(row.probability),
    quality: Number(row.quality),
    modelVersion: row.model_version
  }));
  return accountJson(request, { snapshots });
}

export async function onRequestPost({ request, env }) {
  const auth = await requireUser(request, env);
  if (auth.error) return accountError(request, auth.error, auth.status);
  try {
    const body = await readJson(request); const eventAt = Number(body.eventAt); const calculatedAt = Number(body.calculatedAt) || Date.now();
    if (!Number.isFinite(eventAt) || !Number.isFinite(Number(body.probability))) return accountError(request, 'Forecast snapshot is incomplete');
    await auth.db.prepare(`INSERT INTO forecast_snapshots (id,user_id,event_at,mode,latitude,longitude,calculated_at,probability,quality,model_version)
      VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT(user_id,event_at,mode,latitude,longitude,calculated_at) DO UPDATE SET probability=excluded.probability,quality=excluded.quality,model_version=excluded.model_version`
    ).bind(crypto.randomUUID(), auth.user.id, eventAt, body.mode === 'sunrise' ? 'sunrise' : 'sunset', +Number(body.latitude).toFixed(4), +Number(body.longitude).toFixed(4), calculatedAt, Math.round(Math.max(0, Math.min(100, Number(body.probability)))), Math.round(Math.max(0, Math.min(100, Number(body.quality) || 0))), String(body.modelVersion || 'rules').slice(0, 48)).run();
    return accountJson(request, { saved: true });
  } catch (error) { return accountError(request, error.message); }
}
