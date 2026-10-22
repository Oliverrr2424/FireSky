import { accountError, accountJson, requireUser } from '../../_shared/account.js';
import { MAX_PHOTOS_PER_USER, MAX_PHOTO_BYTES, MAX_PHOTO_BYTES_PER_USER, cleanPhotoId, matchesSignature, photoType } from '../../_shared/photos.js';

export { onRequestOptions } from '../account.js';

// POST /api/viewpoint-photos?id=<client id>&width=&height= with the image as the raw body.
// The client picks the id so a viewpoint can name its photo before the upload finishes;
// re-sending the same id replaces the image, and one account can never take over another's id.
export async function onRequestPost({ request, env }) {
  const auth = await requireUser(request, env);
  if (auth.error) return accountError(request, auth.error, auth.status);
  try {
    const params = new URL(request.url).searchParams;
    const id = cleanPhotoId(params.get('id')) || crypto.randomUUID();
    const type = photoType(request.headers.get('Content-Type'));
    if (!type) return accountError(request, 'Photos must be JPEG, PNG or WebP', 415);
    const bytes = await request.arrayBuffer();
    if (!bytes.byteLength) return accountError(request, 'The photo is empty');
    if (bytes.byteLength > MAX_PHOTO_BYTES) return accountError(request, 'The photo is too large', 413);
    if (!matchesSignature(type, new Uint8Array(bytes, 0, Math.min(bytes.byteLength, 16)))) return accountError(request, 'That file is not a valid image', 415);

    const usage = await auth.db.prepare('SELECT COUNT(*) AS n, COALESCE(SUM(byte_size), 0) AS total FROM viewpoint_photos WHERE user_id = ? AND id <> ?').bind(auth.user.id, id).all();
    const used = usage.results?.[0] || { n: 0, total: 0 };
    if (Number(used.n) >= MAX_PHOTOS_PER_USER || Number(used.total) + bytes.byteLength > MAX_PHOTO_BYTES_PER_USER) {
      return accountError(request, 'Photo storage is full. Remove a saved viewpoint photo first.', 413);
    }

    const dimension = (name) => { const value = Math.round(Number(params.get(name))); return value > 0 && value <= 20000 ? value : null; };
    const result = await auth.db.prepare(`INSERT INTO viewpoint_photos (id,user_id,content_type,width,height,byte_size,data,created_at)
      VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET content_type=excluded.content_type,width=excluded.width,height=excluded.height,
      byte_size=excluded.byte_size,data=excluded.data,created_at=excluded.created_at WHERE viewpoint_photos.user_id = excluded.user_id`
    ).bind(id, auth.user.id, type, dimension('width'), dimension('height'), bytes.byteLength, bytes, Date.now()).run();
    if (result.meta?.changes === 0) return accountError(request, 'That photo id is already taken', 409);
    return accountJson(request, { id, byteSize: bytes.byteLength });
  } catch (error) { return accountError(request, error.message); }
}
