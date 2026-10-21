# FireSky accounts, cloud sync, and background alerts

FireSky uses **Cloudflare D1** for account-scoped relational data: OAuth identities, hashed sessions, settings, saved places/viewpoints, feedback, and forecast-change history. The schema also has a devices table for future push integration; the native app currently uploads no push token. D1 fits this workload better than KV because writes need uniqueness, account deletion, and event-history queries. KV remains the shared forecast cache.

## 1. Create D1 and apply the schema

```bash
npx wrangler d1 create firesky
npx wrangler d1 migrations apply firesky --remote
```

Copy the returned database id into the `[[d1_databases]]` block in `wrangler.toml` (or add the same `FIRESKY_DB` binding in Cloudflare Pages → Settings → Bindings). Deploy the migration in `migrations/0001_accounts.sql` before turning on sign-in.

For local Pages development, use:

```bash
npx wrangler d1 migrations apply firesky --local
npx wrangler pages dev dist --d1 FIRESKY_DB=firesky
```

## 2. Google OAuth

In Google Cloud Console, create an OAuth **Web application** client. Add this authorized redirect URI:

```
https://fireskychase.pages.dev/api/auth/callback
```

Add the following as encrypted Pages secrets (never Vite variables):

```bash
npx wrangler pages secret put GOOGLE_OAUTH_CLIENT_ID --project-name firesky
npx wrangler pages secret put GOOGLE_OAUTH_CLIENT_SECRET --project-name firesky
```

## 3. Email/password accounts

Email/password accounts are stored in D1 alongside the rest of the account data. Passwords are never stored directly: the Pages Function derives a salted PBKDF2-SHA-256 hash before writing a `password_credentials` record. Apply migration `0003_password_credentials.sql` before releasing this flow.

Native Google sign-in uses PKCE. The Android app sends a `code_challenge` to `/api/auth/start`; the callback then redirects to `com.firesky.app://auth?auth_code=…` with a five-minute, single-use code instead of a session token, and the app trades that code plus its verifier at `POST /api/auth/exchange`. Another app that intercepts the custom-scheme redirect therefore gets nothing usable. Apply migration `0004_oauth_pkce.sql` **before** deploying these Functions: until the `code_challenge` column exists, native Google sign-in fails (the browser flow is unaffected). Builds released before PKCE keep working against the new server, because requests without a challenge still receive `auth_token`.

The Android app receives Google OAuth through `com.firesky.app://auth`; Google sign-in deliberately opens in the system browser rather than the embedded WebView. Apple Sign in is not enabled in this release.

## 4. Current local alerts and future server push

The native Android app currently schedules sunrise/sunset reminders with `AlarmManager` and posts local notifications. `ForecastRefreshWorker` refreshes the plan through WorkManager every 3 hours with a 1-hour flex window and a network constraint. `BootReceiver` re-arms saved alarms after boot, app replacement, or an exact-alarm permission change. Exact alarms use `setExactAndAllowWhileIdle`; without access, scheduling falls back to `setAndAllowWhileIdle`.

FCM is not integrated: no Firebase messaging SDK is bundled, and `ApiClient.registerDevice` is never called. The separate push worker and `/api/devices` endpoint are groundwork for a future push path. The following setup is a future integration checklist, not a description of the shipped app:

1. Create a Firebase project for `com.firesky.app`; download `google-services.json` to `android/app/` (it is intentionally not committed).
2. Add the Firebase Android setup and deploy the separate scheduled notification worker with a service-account secret:

```bash
cd workers/push
npx wrangler secret put FIREBASE_SERVICE_ACCOUNT # full Firebase service-account JSON
npx wrangler secret put FIRESKY_API_ORIGIN       # https://fireskychase.pages.dev
npx wrangler deploy
```

Bind the same D1 database in `workers/push/wrangler.toml` before deploying. The Worker runs every 15 minutes and deduplicates by user, device, target event, and mode.
3. Add native FCM token acquisition, token refresh handling, and registration in D1's `devices` table after sign-in. This client registration does not exist yet.
4. A Cloudflare Worker Cron runs every 15 minutes, recalculates each opted-in saved place using the **target sunrise/sunset window**, and sends a message only when the configured threshold is crossed. Store a per-event delivery key to prevent duplicates.

Do not run this cron inside a Pages Function: Pages has no Cron Trigger. It should be a small dedicated Cloudflare Worker bound to the same D1 database. Its service-account JSON and Firebase project id must be Cloudflare secrets.

## 5. Data controls and Play Console

The account screen supports local sign-out and irreversible account deletion; local sign-out does not currently revoke the server session. Update the privacy policy before release to disclose optional Google/email identity data, location and sync data, feedback, usage events tied to a random installation ID, retention, and the deletion route. The current native build bundles no crash-reporting or FCM SDK and collects no crash diagnostics or push token. Reassess these disclosures when those integrations are added. Keep the Play Data Safety form aligned with `google-play-submission.md` and actual production behavior.
