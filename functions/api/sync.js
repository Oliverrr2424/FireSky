import { accountError, accountJson, readJson, requireUser } from '../_shared/account.js';
import { cleanPhotoId } from '../_shared/photos.js';

export { onRequestOptions } from './account.js';

// A photo is uploaded before the viewpoint that names it syncs, but a sync can race the
// upload. Unreferenced photos younger than this are kept so that race never loses the image.
const ORPHAN_GRACE_MS = 60 * 60 * 1000;

function cleanPlace(value) {
  const latitude = Number(value?.latitude); const longitude = Number(value?.longitude);
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude) || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) return null;
  return { id: String(value.id || crypto.randomUUID()).slice(0, 64), name: String(value.name || 'Saved place').slice(0, 80), admin1: String(value.admin1 || '').slice(0, 80), countryCode: String(value.country_code || value.countryCode || '').slice(0, 8), latitude: +latitude.toFixed(4), longitude: +longitude.toFixed(4), label: String(value.label || '').slice(0, 80), photoId: cleanPhotoId(value.photo_id || value.photoId) };
}

export async function onRequestGet({ request, env }) {
  const auth = await requireUser(request, env);
  if (auth.error) return accountError(request, auth.error, auth.status);
  const [locations, viewpoints] = await auth.db.batch([
    auth.db.prepare('SELECT id, name, admin1, country_code, latitude, longitude, label, updated_at FROM saved_locations WHERE user_id = ? ORDER BY updated_at DESC').bind(auth.user.id),
    auth.db.prepare('SELECT id, name, latitude, longitude, note, photo_id, updated_at FROM viewpoints WHERE user_id = ? ORDER BY updated_at DESC').bind(auth.user.id)
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
    // Older clients don't send photo fields. Preserve existing photos for those
    // viewpoints; an explicit null from a new client still removes the photo.
    const existing = await auth.db.prepare('SELECT id, photo_id FROM viewpoints WHERE user_id = ?').bind(auth.user.id).all();
    const previousPhotos = new Map((existing.results || []).map((item) => [item.id, item.photo_id]));
    const suppliedPhotos = new Set((Array.isArray(body.viewpoints) ? body.viewpoints : [])
      .filter((item) => item && (Object.hasOwn(item, 'photo_id') || Object.hasOwn(item, 'photoId')))
      .map((item) => String(item.id || '').slice(0, 64)));
    viewpoints.forEach((item) => {
      if (!suppliedPhotos.has(item.id)) item.photoId = cleanPhotoId(previousPhotos.get(item.id));
    });
    const now = Date.now();
    // A viewpoint may only point at a photo its owner has actually uploaded.
    const named = [...new Set(viewpoints.map((item) => item.photoId).filter(Boolean))];
    let owned = new Set();
    if (named.length) {
      const found = await auth.db.prepare(`SELECT id FROM viewpoint_photos WHERE user_id = ? AND id IN (${named.map(() => '?').join(',')})`).bind(auth.user.id, ...named).all();
      owned = new Set((found.results || []).map((row) => row.id));
    }
    const statements = [auth.db.prepare('DELETE FROM saved_locations WHERE user_id = ?').bind(auth.user.id), auth.db.prepare('DELETE FROM viewpoints WHERE user_id = ?').bind(auth.user.id)];
    locations.forEach((item) => statements.push(auth.db.prepare('INSERT INTO saved_locations (id,user_id,name,admin1,country_code,latitude,longitude,label,updated_at) VALUES (?,?,?,?,?,?,?,?,?)').bind(item.id, auth.user.id, item.name, item.admin1, item.countryCode, item.latitude, item.longitude, item.label, now)));
    viewpoints.forEach((item) => statements.push(auth.db.prepare('INSERT INTO viewpoints (id,user_id,name,latitude,longitude,note,photo_id,updated_at) VALUES (?,?,?,?,?,?,?,?)').bind(item.id, auth.user.id, item.name, item.latitude, item.longitude, item.label, owned.has(item.photoId) ? item.photoId : null, now)));
    statements.push(auth.db.prepare('DELETE FROM viewpoint_photos WHERE user_id = ? AND created_at < ? AND id NOT IN (SELECT photo_id FROM viewpoints WHERE user_id = ? AND photo_id IS NOT NULL)').bind(auth.user.id, now - ORPHAN_GRACE_MS, auth.user.id));
    await auth.db.batch(statements);
    return accountJson(request, { saved: true });
  } catch (error) { return accountError(request, error.message); }
}
