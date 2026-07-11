import { accountError, accountJson, readJson, requireUser } from '../_shared/account.js';

export { onRequestOptions } from './account.js';

export async function onRequestPost({ request, env }) {
  const auth = await requireUser(request, env);
  if (auth.error) return accountError(request, auth.error, auth.status);
  try {
    const body = await readJson(request);
    const token = String(body.token || '');
    if (token.length < 32 || token.length > 4096) return accountError(request, 'Invalid push token');
    const platform = ['android', 'ios'].includes(body.platform) ? body.platform : 'android';
    await auth.db.prepare(`INSERT INTO devices (id,user_id,platform,push_token,enabled,updated_at) VALUES (?,?,?,?,?,?)
      ON CONFLICT(push_token) DO UPDATE SET user_id=excluded.user_id,platform=excluded.platform,enabled=excluded.enabled,updated_at=excluded.updated_at`
    ).bind(crypto.randomUUID(), auth.user.id, platform, token, body.enabled === false ? 0 : 1, Date.now()).run();
    return accountJson(request, { saved: true });
  } catch (error) { return accountError(request, error.message); }
}
