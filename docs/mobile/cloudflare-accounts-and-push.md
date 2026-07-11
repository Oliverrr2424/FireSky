# FireSky accounts, cloud sync, and background alerts

FireSky uses **Cloudflare D1** for account-scoped relational data: OAuth identities, hashed sessions, settings, saved places/viewpoints, feedback, forecast-change history, and device push tokens. D1 fits this workload better than KV because writes need uniqueness, account deletion, and event-history queries. KV remains the shared forecast cache.

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

The Android app receives Google OAuth through `com.firesky.app://auth`; Google sign-in deliberately opens in the system browser rather than the embedded WebView. Apple Sign in is not enabled in this release.

## 4. Reliable background push

Local notifications are kept as an immediate fallback. For delivery when the app has not been opened, use Firebase Cloud Messaging (FCM) on Android and APNs through Firebase on iOS:

1. Create a Firebase project for `com.firesky.app`; download `google-services.json` to `android/app/` (it is intentionally not committed).
2. Add the Firebase Android setup and deploy the separate scheduled notification worker with a service-account secret:

```bash
cd workers/push
npx wrangler secret put FIREBASE_SERVICE_ACCOUNT # full Firebase service-account JSON
npx wrangler secret put FIRESKY_API_ORIGIN       # https://fireskychase.pages.dev
npx wrangler deploy
```

Bind the same D1 database in `workers/push/wrangler.toml` before deploying. The Worker runs every 15 minutes and deduplicates by user, device, target event, and mode.
3. The app registers each FCM token in D1's `devices` table after a user signs in.
4. A Cloudflare Worker Cron runs every 15 minutes, recalculates each opted-in saved place using the **target sunrise/sunset window**, and sends a message only when the configured threshold is crossed. Store a per-event delivery key to prevent duplicates.

Do not run this cron inside a Pages Function: Pages has no Cron Trigger. It should be a small dedicated Cloudflare Worker bound to the same D1 database. Its service-account JSON and Firebase project id must be Cloudflare secrets.

## 5. Data controls and Play Console

The account screen supports sign-out and irreversible account deletion. Update the privacy policy before release to disclose Google/Apple identity data, sync data, push tokens, feedback, retention, and the deletion route. In Play Console, declare precise location, account identifiers, optional user feedback, diagnostics, and notification use according to the final production behavior.
