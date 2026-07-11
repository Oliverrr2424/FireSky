const SESSION_DAYS = 30;

export function corsHeaders(request, extra = {}) {
  const origin = request.headers.get('Origin') || '';
  // The packaged Capacitor app is served from https://localhost. Keep this
  // allow-list explicit because authenticated requests use Bearer tokens.
  const allowed = new Set([
    'https://fireskychase.pages.dev',
    'https://localhost',
    'http://localhost:5173',
    'http://127.0.0.1:5173'
  ]);
  return {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    ...(allowed.has(origin) ? { 'Access-Control-Allow-Origin': origin, Vary: 'Origin' } : {}),
    'Access-Control-Allow-Headers': 'Authorization, Content-Type',
    'Access-Control-Allow-Methods': 'GET, POST, PUT, DELETE, OPTIONS',
    ...extra
  };
}

export function accountJson(request, value, status = 200) {
  return new Response(JSON.stringify(value), { status, headers: corsHeaders(request) });
}

export function accountError(request, message, status = 400) {
  return accountJson(request, { error: message }, status);
}

export function database(env) {
  return env.FIRESKY_DB || null;
}

export async function sha256(value) {
  const bytes = new TextEncoder().encode(value);
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('');
}

export function normalEmail(value) {
  const email = String(value || '').trim().toLowerCase();
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) && email.length <= 254 ? email : null;
}

function base64(bytes) {
  return btoa(String.fromCharCode(...new Uint8Array(bytes)));
}

function base64Bytes(value) {
  return Uint8Array.from(atob(value), (character) => character.charCodeAt(0));
}

export async function passwordHash(password, salt, iterations = 180000) {
  const material = await crypto.subtle.importKey('raw', new TextEncoder().encode(password), 'PBKDF2', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt: base64Bytes(salt), iterations }, material, 256);
  return base64(bits);
}

export function newSalt() {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return base64(bytes);
}

export function bearerToken(request) {
  const match = /^Bearer\s+(.+)$/i.exec(request.headers.get('Authorization') || '');
  return match?.[1]?.trim() || null;
}

export async function requireUser(request, env) {
  const db = database(env);
  if (!db) return { error: 'Cloud sync is not configured yet', status: 503 };
  const token = bearerToken(request);
  if (!token || token.length < 32) return { error: 'Sign in is required', status: 401 };
  const tokenHash = await sha256(token);
  const now = Date.now();
  const row = await db.prepare(`
    SELECT u.id, u.email, u.display_name, u.avatar_url, u.created_at
    FROM sessions s JOIN users u ON u.id = s.user_id
    WHERE s.token_hash = ? AND s.expires_at > ?
  `).bind(tokenHash, now).first();
  if (!row) return { error: 'Your session has expired. Please sign in again.', status: 401 };
  await db.prepare('UPDATE sessions SET last_seen_at = ? WHERE token_hash = ?').bind(now, tokenHash).run();
  return { db, tokenHash, user: row };
}

export async function readJson(request, maxBytes = 24 * 1024) {
  const text = await request.text();
  if (text.length > maxBytes) throw new Error('Request is too large');
  try { return JSON.parse(text || '{}'); } catch { throw new Error('Invalid JSON'); }
}

export async function createSession(db, userId) {
  const raw = `${crypto.randomUUID()}${crypto.randomUUID()}`.replace(/-/g, '');
  const now = Date.now();
  await db.prepare(
    'INSERT INTO sessions (id, user_id, token_hash, created_at, expires_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?)'
  ).bind(crypto.randomUUID(), userId, await sha256(raw), now, now + SESSION_DAYS * 86400000, now).run();
  return raw;
}

export function safeReturnTo(value, fallback) {
  if (value === 'com.firesky.app://auth') return value;
  try {
    const url = new URL(value || fallback);
    if (url.origin === new URL(fallback).origin) return url.toString();
  } catch { /* use fallback */ }
  return fallback;
}
