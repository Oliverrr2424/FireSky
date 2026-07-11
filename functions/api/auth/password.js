import { accountError, accountJson, createSession, database, newSalt, normalEmail, passwordHash, readJson, requireUser } from '../../_shared/account.js';

const ITERATIONS = 180000;

export async function onRequestPost({ request, env }) {
  const db = database(env);
  if (!db) return accountError(request, 'Cloud sync is not configured yet', 503);
  try {
    const body = await readJson(request, 8 * 1024);
    if (body.action === 'change') {
      const auth = await requireUser(request, env);
      if (auth.error) return accountError(request, auth.error, auth.status);
      const currentPassword = String(body.currentPassword || '');
      const nextPassword = String(body.nextPassword || '');
      if (nextPassword.length < 12 || nextPassword.length > 256) return accountError(request, 'New password must be 12–256 characters');
      const credential = await auth.db.prepare('SELECT password_hash,salt,iterations FROM password_credentials WHERE user_id=?').bind(auth.user.id).first();
      if (!credential) return accountError(request, 'This Google account does not have an email password', 400);
      if (await passwordHash(currentPassword, credential.salt, credential.iterations) !== credential.password_hash) return accountError(request, 'Current password is incorrect', 401);
      const salt = newSalt(); const hash = await passwordHash(nextPassword, salt, ITERATIONS);
      await auth.db.prepare('UPDATE password_credentials SET password_hash=?,salt=?,iterations=?,updated_at=? WHERE user_id=?').bind(hash, salt, ITERATIONS, Date.now(), auth.user.id).run();
      return accountJson(request, { changed: true });
    }
    const action = body.action === 'signup' ? 'signup' : 'login';
    const email = normalEmail(body.email);
    const password = String(body.password || '');
    if (!email || password.length < 12 || password.length > 256) return accountError(request, 'Use a valid email and a password of 12–256 characters');
    const now = Date.now();
    let user;
    if (action === 'signup') {
      user = await db.prepare('SELECT id FROM users WHERE email = ?').bind(email).first();
      if (user) return accountError(request, 'An account already exists for this email. Sign in instead.', 409);
      const id = crypto.randomUUID(); const salt = newSalt(); const hash = await passwordHash(password, salt, ITERATIONS);
      const displayName = String(body.displayName || email.split('@')[0]).trim().slice(0, 80) || 'FireSky user';
      await db.batch([
        db.prepare('INSERT INTO users (id,email,display_name,avatar_url,created_at,updated_at) VALUES (?,?,?,?,?,?)').bind(id, email, displayName, null, now, now),
        db.prepare('INSERT INTO password_credentials (user_id,password_hash,salt,iterations,created_at,updated_at) VALUES (?,?,?,?,?,?)').bind(id, hash, salt, ITERATIONS, now, now),
        db.prepare('INSERT INTO user_settings (user_id,notifications_enabled,alert_lead_minutes,sunrise_alerts,sunset_alerts,alert_threshold,updated_at) VALUES (?,?,?,?,?,?,?)').bind(id, 1, 60, 1, 1, 70, now)
      ]);
      user = { id, email, display_name: displayName, avatar_url: null, created_at: now };
    } else {
      const record = await db.prepare(`SELECT u.id,u.email,u.display_name,u.avatar_url,u.created_at,p.password_hash,p.salt,p.iterations
        FROM users u JOIN password_credentials p ON p.user_id=u.id WHERE u.email=?`).bind(email).first();
      if (!record || await passwordHash(password, record.salt, record.iterations) !== record.password_hash) return accountError(request, 'Email or password is incorrect', 401);
      user = record;
    }
    const token = await createSession(db, user.id);
    return accountJson(request, { token, user: { id: user.id, email: user.email, displayName: user.display_name, avatarUrl: user.avatar_url, createdAt: user.created_at } });
  } catch (error) {
    if (String(error.message).includes('UNIQUE constraint failed')) return accountError(request, 'An account already exists for this email. Sign in instead.', 409);
    return accountError(request, 'Unable to complete account sign-in', 400);
  }
}

export { onRequestOptions } from '../account.js';
