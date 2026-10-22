import { accountError, accountJson, corsHeaders, requireUser } from '../../_shared/account.js';
import { blobBytes, cleanPhotoId } from '../../_shared/photos.js';

export { onRequestOptions } from '../account.js';

// Photos are private to their owner, so the bearer token is required for reads too.
export async function onRequestGet({ request, env, params }) {
  const auth = await requireUser(request, env);
  if (auth.error) return accountError(request, auth.error, auth.status);
  const id = cleanPhotoId(params.id);
  const row = id && await auth.db.prepare('SELECT content_type, data FROM viewpoint_photos WHERE id = ? AND user_id = ?').bind(id, auth.user.id).first();
  if (!row) return accountError(request, 'Photo not found', 404);
  // A photo id is never reused for different pixels, so the bytes can be cached for good.
  return new Response(blobBytes(row.data), { headers: corsHeaders(request, { 'Content-Type': row.content_type, 'Cache-Control': 'private, max-age=31536000, immutable' }) });
}

export async function onRequestDelete({ request, env, params }) {
  const auth = await requireUser(request, env);
  if (auth.error) return accountError(request, auth.error, auth.status);
  const id = cleanPhotoId(params.id);
  if (!id) return accountError(request, 'Photo not found', 404);
  await auth.db.batch([
    auth.db.prepare('UPDATE viewpoints SET photo_id = NULL WHERE user_id = ? AND photo_id = ?').bind(auth.user.id, id),
    auth.db.prepare('DELETE FROM viewpoint_photos WHERE id = ? AND user_id = ?').bind(id, auth.user.id)
  ]);
  return accountJson(request, { deleted: true });
}
