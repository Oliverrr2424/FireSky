import { accountError, accountJson, database, safeReturnTo } from '../../_shared/account.js';

export async function onRequestGet({ request, env }) {
  const db = database(env);
  if (!db) return accountError(request, 'Cloud sync is not configured yet', 503);
  const requestUrl = new URL(request.url);
  const provider = requestUrl.searchParams.get('provider');
  if (provider !== 'google') return accountError(request, 'Only Google sign-in is enabled', 400);
  const clientId = env.GOOGLE_OAUTH_CLIENT_ID;
  if (!clientId) return accountError(request, 'Google sign-in is not configured yet', 503);
  const state = crypto.randomUUID().replace(/-/g, '') + crypto.randomUUID().replace(/-/g, '');
  const callback = `${requestUrl.origin}/api/auth/callback`;
  const returnTo = safeReturnTo(requestUrl.searchParams.get('return_to'), requestUrl.origin);
  await db.prepare('INSERT INTO oauth_states (state, provider, return_to, expires_at, created_at) VALUES (?, ?, ?, ?, ?)').bind(state, provider, returnTo, Date.now() + 10 * 60 * 1000, Date.now()).run();
  const params = new URLSearchParams({ client_id: clientId, redirect_uri: callback, response_type: 'code', scope: 'openid email profile', state, access_type: 'offline', prompt: 'select_account', response_mode: 'query' });
  const base = 'https://accounts.google.com/o/oauth2/v2/auth';
  return accountJson(request, { url: `${base}?${params}` });
}
