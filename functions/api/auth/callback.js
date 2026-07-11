import { createSession, database } from '../../_shared/account.js';

function base64UrlBytes(value) {
  const padded = value.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - value.length % 4) % 4);
  return Uint8Array.from(atob(padded), (character) => character.charCodeAt(0));
}

function base64UrlJson(value) {
  return JSON.parse(new TextDecoder().decode(base64UrlBytes(value)));
}

async function verifiedAppleClaims(idToken, clientId) {
  const [encodedHeader, encodedPayload, encodedSignature] = String(idToken).split('.');
  if (!encodedHeader || !encodedPayload || !encodedSignature) throw new Error('Apple identity validation failed');
  const header = base64UrlJson(encodedHeader);
  if (header.alg !== 'RS256' || !header.kid) throw new Error('Apple identity validation failed');
  const keys = await fetch('https://appleid.apple.com/auth/keys').then((response) => response.ok ? response.json() : null);
  const jwk = keys?.keys?.find((item) => item.kid === header.kid);
  if (!jwk) throw new Error('Apple identity validation failed');
  const key = await crypto.subtle.importKey('jwk', jwk, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['verify']);
  const valid = await crypto.subtle.verify('RSASSA-PKCS1-v1_5', key, base64UrlBytes(encodedSignature), new TextEncoder().encode(`${encodedHeader}.${encodedPayload}`));
  const claims = base64UrlJson(encodedPayload);
  if (!valid || claims.iss !== 'https://appleid.apple.com' || claims.aud !== clientId || !claims.sub || Number(claims.exp) < Math.floor(Date.now() / 1000)) throw new Error('Apple identity validation failed');
  return claims;
}

async function appleClientSecret(env, redirectUri) {
  const header = btoa(JSON.stringify({ alg: 'ES256', kid: env.APPLE_KEY_ID })).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  const now = Math.floor(Date.now() / 1000);
  const payload = btoa(JSON.stringify({ iss: env.APPLE_TEAM_ID, iat: now, exp: now + 300, aud: 'https://appleid.apple.com', sub: env.APPLE_CLIENT_ID })).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  const pem = String(env.APPLE_PRIVATE_KEY || '').replace(/\\n/g, '\n').replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, '');
  const raw = Uint8Array.from(atob(pem), c => c.charCodeAt(0));
  const key = await crypto.subtle.importKey('pkcs8', raw, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign']);
  const signature = new Uint8Array(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, key, new TextEncoder().encode(`${header}.${payload}`)));
  const encoded = btoa(String.fromCharCode(...signature)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${header}.${payload}.${encoded}`;
}

async function googleProfile(code, redirectUri, env) {
  const token = await fetch('https://oauth2.googleapis.com/token', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ code, client_id: env.GOOGLE_OAUTH_CLIENT_ID, client_secret: env.GOOGLE_OAUTH_CLIENT_SECRET, redirect_uri: redirectUri, grant_type: 'authorization_code' }) });
  if (!token.ok) throw new Error('Google sign-in could not be completed');
  const data = await token.json();
  const profile = await fetch('https://openidconnect.googleapis.com/v1/userinfo', { headers: { Authorization: `Bearer ${data.access_token}` } });
  if (!profile.ok) throw new Error('Google profile could not be read');
  const value = await profile.json();
  if (!value.sub || !value.email_verified) throw new Error('A verified Google email is required');
  return { subject: value.sub, email: value.email, name: value.name || value.email, avatar: value.picture || null };
}

async function appleProfile(code, redirectUri, env) {
  if (!env.APPLE_TEAM_ID || !env.APPLE_KEY_ID || !env.APPLE_PRIVATE_KEY) throw new Error('Apple sign-in is not configured');
  const token = await fetch('https://appleid.apple.com/auth/token', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ code, client_id: env.APPLE_CLIENT_ID, client_secret: await appleClientSecret(env, redirectUri), redirect_uri: redirectUri, grant_type: 'authorization_code' }) });
  if (!token.ok) throw new Error('Apple sign-in could not be completed');
  const value = await token.json();
  const claims = await verifiedAppleClaims(value.id_token, env.APPLE_CLIENT_ID);
  return { subject: claims.sub, email: claims.email || null, name: claims.email || 'Apple user', avatar: null };
}

export async function onRequest({ request, env }) {
  const db = database(env); if (!db) return new Response('Cloud sync is not configured', { status: 503 });
  try {
    const values = request.method === 'POST' ? Object.fromEntries(await request.formData()) : Object.fromEntries(new URL(request.url).searchParams);
    const state = String(values.state || ''); const code = String(values.code || '');
    const record = await db.prepare('SELECT provider, return_to FROM oauth_states WHERE state=? AND expires_at>?').bind(state, Date.now()).first();
    await db.prepare('DELETE FROM oauth_states WHERE state=?').bind(state).run();
    if (!record || !code) throw new Error('This sign-in link has expired. Please try again.');
    const callback = `${new URL(request.url).origin}/api/auth/callback`;
    const identity = record.provider === 'google' ? await googleProfile(code, callback, env) : await appleProfile(code, callback, env);
    const existing = await db.prepare('SELECT user_id FROM oauth_identities WHERE provider=? AND provider_subject=?').bind(record.provider, identity.subject).first();
    const userId = existing?.user_id || crypto.randomUUID(); const now = Date.now();
    if (!existing) {
      await db.batch([
        db.prepare('INSERT INTO users (id,email,display_name,avatar_url,created_at,updated_at) VALUES (?,?,?,?,?,?)').bind(userId, identity.email, identity.name, identity.avatar, now, now),
        db.prepare('INSERT INTO oauth_identities (provider,provider_subject,user_id,email,created_at) VALUES (?,?,?,?,?)').bind(record.provider, identity.subject, userId, identity.email, now),
        db.prepare('INSERT INTO user_settings (user_id,notifications_enabled,alert_lead_minutes,sunrise_alerts,sunset_alerts,alert_threshold,updated_at) VALUES (?,?,?,?,?,?,?)').bind(userId, 1, 60, 1, 1, 70, now)
      ]);
    } else {
      await db.prepare('UPDATE users SET email=COALESCE(?,email),display_name=COALESCE(?,display_name),avatar_url=COALESCE(?,avatar_url),updated_at=? WHERE id=?').bind(identity.email, identity.name, identity.avatar, now, userId).run();
    }
    const destination = new URL(record.return_to); destination.searchParams.set('auth_token', await createSession(db, userId));
    return Response.redirect(destination.toString(), 302);
  } catch (error) {
    return new Response(`FireSky sign-in failed: ${error.message}`, { status: 400, headers: { 'Content-Type': 'text/plain; charset=utf-8' } });
  }
}
