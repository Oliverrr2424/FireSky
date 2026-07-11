import { accountError, accountJson, readJson, requireUser } from '../_shared/account.js';

export { onRequestOptions } from './account.js';

function cleanPlace(value) {
  const latitude = Number(value?.latitude); const longitude = Number(value?.longitude);
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude) || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) return null;
  return { id: String(value.id || crypto.randomUUID()).slice(0, 64), name: String(value.name || 'Saved place').slice(0, 80), admin1: String(value.admin1 || '').slice(0, 80), countryCode: String(value.country_code || value.countryCode || '').slice(0, 8), latitude: +latitude.toFixed(4), longitude: +longitude.toFixed(4), label: String(value.label || '').slice(0, 80) };
}

export async function onRequestGet({ request, env }) {
  const auth = await requireUser(request, env);
  if (auth.error) return accountError(request, auth.error, auth.status);
  const [locations, viewpoints] = await auth.db.batch([
    auth.db.prepare('SELECT id, name, admin1, country_code, latitude, longitude, label, updated_at FROM saved_locations WHERE user_id = ? ORDER BY updated_at DESC').bind(auth.user.id),
    auth.db.prepare('SELECT id, name, latitude, longitude, note, updated_at FROM viewpoints WHERE user_id = ? ORDER BY updated_at DESC').bind(auth.user.id)
  ]);
  return accountJson(request, { locations: locations.results || [], viewpoints: viewpoints.results || [] });
}

export async function onRequestPut({ request, env }) {
  const auth = await requireUser(request, env);
  if (auth.error) return accountError(request, auth.error, auth.status);
  try {
    const body = await readJson(request);
    const locations = Array.isArray(body.locations) ? body.locations.map(cleanPlace).filter(Boolean).slice(0, 20) : [];
    const viewpoints = Array.isArray(body.viewpoints) ? body.viewpoints.map(cleanPlace).filter(Boolean).slice(0, 30) : [];
    const now = Date.now();
    const statements = [auth.db.prepare('DELETE FROM saved_locations WHERE user_id = ?').bind(auth.user.id), auth.db.prepare('DELETE FROM viewpoints WHERE user_id = ?').bind(auth.user.id)];
    locations.forEach((item) => statements.push(auth.db.prepare('INSERT INTO saved_locations (id,user_id,name,admin1,country_code,latitude,longitude,label,updated_at) VALUES (?,?,?,?,?,?,?,?,?)').bind(item.id, auth.user.id, item.name, item.admin1, item.countryCode, item.latitude, item.longitude, item.label, now)));
    viewpoints.forEach((item) => statements.push(auth.db.prepare('INSERT INTO viewpoints (id,user_id,name,latitude,longitude,note,updated_at) VALUES (?,?,?,?,?,?,?)').bind(item.id, auth.user.id, item.name, item.latitude, item.longitude, item.label, now)));
    await auth.db.batch(statements);
    return accountJson(request, { saved: true });
  } catch (error) { return accountError(request, error.message); }
}
