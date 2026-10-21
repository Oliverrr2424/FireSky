import { accountError, accountJson, createSession, database, readJson, sha256 } from '../../_shared/account.js';

function base64Url(bytes) {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

// Second half of the PKCE flow: trades the one-time code from the OAuth
// redirect for a session, but only for the caller holding the code_verifier.
export async function onRequestPost({ request, env }) {
  const db = database(env);
  if (!db) return accountError(request, 'Cloud sync is not configured yet', 503);
  try {
    const body = await readJson(request, 4 * 1024);
    const code = String(body.code || '');
    const verifier = String(body.code_verifier || '');
    if (!/^[A-Za-z0-9_-]{43,128}$/.test(verifier) || !code) {
      return accountError(request, 'This sign-in link has expired. Please try again.', 400);
    }
    const codeHash = await sha256(code);
    const record = await db.prepare('SELECT user_id, code_challenge FROM oauth_codes WHERE code_hash=? AND expires_at>?').bind(codeHash, Date.now()).first();
    // Single use, whether or not the verifier matches.
    await db.prepare('DELETE FROM oauth_codes WHERE code_hash=? OR expires_at<=?').bind(codeHash, Date.now()).run();
    if (!record) return accountError(request, 'This sign-in link has expired. Please try again.', 400);
    const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier)));
    if (base64Url(digest) !== record.code_challenge) {
      return accountError(request, 'This sign-in link has expired. Please try again.', 400);
    }
    return accountJson(request, { token: await createSession(db, record.user_id) });
  } catch (error) {
    return accountError(request, error.message || 'Sign in failed', 400);
  }
}
