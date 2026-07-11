import { accountError, accountJson, corsHeaders, readJson, requireUser } from '../_shared/account.js';

function profile(user) {
  return { id: user.id, email: user.email, displayName: user.display_name, avatarUrl: user.avatar_url, createdAt: user.created_at, hasPassword: Boolean(user.has_password) };
}

export async function onRequestGet({ request, env }) {
  const auth = await requireUser(request, env);
  if (auth.error) return accountError(request, auth.error, auth.status);
  const [settings, credential] = await auth.db.batch([
    auth.db.prepare('SELECT notifications_enabled, alert_lead_minutes, sunrise_alerts, sunset_alerts, alert_threshold FROM user_settings WHERE user_id = ?').bind(auth.user.id),
    auth.db.prepare('SELECT 1 AS has_password FROM password_credentials WHERE user_id = ?').bind(auth.user.id)
  ]);
  return accountJson(request, { user: profile({ ...auth.user, has_password: credential.results?.[0]?.has_password }), settings: settings.results?.[0] || null });
}

export async function onRequestPut({ request, env }) {
  const auth = await requireUser(request, env);
  if (auth.error) return accountError(request, auth.error, auth.status);
  try {
    const body = await readJson(request);
    const notificationsEnabled = body.notificationsEnabled !== false ? 1 : 0;
    const lead = [30, 60, 120].includes(Number(body.alertLeadMinutes)) ? Number(body.alertLeadMinutes) : 60;
    const threshold = Math.max(40, Math.min(95, Number(body.alertThreshold) || 70));
    const sunrise = body.sunriseAlerts !== false ? 1 : 0;
    const sunset = body.sunsetAlerts !== false ? 1 : 0;
    const displayName = body.displayName == null ? null : String(body.displayName).trim().slice(0, 80);
    if (displayName != null && !displayName) return accountError(request, 'Display name cannot be empty');
    if (displayName != null) await auth.db.prepare('UPDATE users SET display_name = ?, updated_at = ? WHERE id = ?').bind(displayName, Date.now(), auth.user.id).run();
    await auth.db.prepare(`INSERT INTO user_settings
      (user_id, notifications_enabled, alert_lead_minutes, sunrise_alerts, sunset_alerts, alert_threshold, updated_at)
      VALUES (?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(user_id) DO UPDATE SET notifications_enabled=excluded.notifications_enabled,
        alert_lead_minutes=excluded.alert_lead_minutes, sunrise_alerts=excluded.sunrise_alerts,
        sunset_alerts=excluded.sunset_alerts, alert_threshold=excluded.alert_threshold, updated_at=excluded.updated_at`
    ).bind(auth.user.id, notificationsEnabled, lead, sunrise, sunset, threshold, Date.now()).run();
    return accountJson(request, { saved: true, user: profile({ ...auth.user, display_name: displayName ?? auth.user.display_name }) });
  } catch (error) { return accountError(request, error.message); }
}

export async function onRequestDelete({ request, env }) {
  const auth = await requireUser(request, env);
  if (auth.error) return accountError(request, auth.error, auth.status);
  await auth.db.batch([
    auth.db.prepare('DELETE FROM sessions WHERE user_id = ?').bind(auth.user.id),
    auth.db.prepare('DELETE FROM users WHERE id = ?').bind(auth.user.id)
  ]);
  return accountJson(request, { deleted: true });
}

export function onRequestOptions({ request }) { return new Response(null, { status: 204, headers: corsHeaders(request) }); }
