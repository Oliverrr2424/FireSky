# Google Play Submission Checklist

## Policy Forms
- Complete **Data Safety** using actual app behavior.
- Complete **Content Rating** questionnaire.
- Declare whether app contains ads.
- Before the first production release, disclose these collected data types when their corresponding features are enabled: precise location, email/account identifier (optional sign-in), saved locations/viewpoints, optional forecast feedback, and usage events tied to a random installation ID.
- The current native build collects no crash diagnostics (no crash-reporting SDK is bundled) and no push token: `ApiClient.registerDevice` exists but is never called and no FCM SDK is included. Amend the Data Safety form if either is added later.
- Mark account creation as optional, provide the published privacy-policy URL, and verify the in-app account deletion flow during closed testing.

## Permissions
- Location permission must be justified in store listing and in-app usage.
- The app requests foreground location only; no `ACCESS_BACKGROUND_LOCATION` permission is declared.
- `POST_NOTIFICATIONS` is requested only after the user enables alerts. Explain that alerts cover forecast probability changes for the selected sunrise/sunset window.
- `SCHEDULE_EXACT_ALARM` backs on-time alert delivery. From Android 12 the user can revoke "Alarms & reminders"; FireSky then falls back to inexact alarms that may arrive up to an hour late and offers a shortcut to that system setting.
- `RECEIVE_BOOT_COMPLETED` re-arms the saved alert plan after a reboot or app update.

## Alerts and Notifications
Alerts are scheduled on the device; there is no server push path yet.

- `SkyAlerts.plan` selects the next sunrise and sunset inside the two forecast days ahead and keeps an event only when the predicted probability reaches the user's threshold.
- Each plan is delivered through `AlarmManager` plus a local notification on the `firesky_sky_alerts` channel. It uses `setExactAndAllowWhileIdle` when exact alarms are allowed and `setAndAllowWhileIdle` otherwise.
- `ForecastRefreshWorker` runs through WorkManager every 3 hours (1-hour flex window, network required) to refresh the forecast and re-plan both alarms while the app is closed.
- `BootReceiver` re-arms the saved plan on boot, package replacement, and changes to the exact-alarm permission.
- If `POST_NOTIFICATIONS` is denied when alerts are enabled, alerts stay off and the app links to the system notification settings.
- FCM is not integrated. `ApiClient.registerDevice` targets `/api/devices` but is never called, so no push token is collected or stored.

## Metadata
- Title: FireSky
- Short description: Track North American sunrise and sunset color chances.
- Full description: include offline/cached behavior, forecast-change timeline, alert thresholds, camera viewpoints, and the limitation that predictions use forecast conditions near the event window.
- Contact email + privacy policy URL
- Choose a public support address before publishing; do not use a personal Google Play login address unless you want it publicly visible.

## Assets
- App icon (512x512)
- Feature graphic (1024x500)
- Phone screenshots (minimum 2)

## Release Track
- Upload AAB to **Internal testing** first.
- Validate crashes/ANR and policy warnings.
- Promote to production only after test feedback is resolved.
- Internal testing supports up to 100 testers and does not require 12 testers. Each tester must accept the invitation using the same Google account as their phone's Play Store.
- For personal accounts subject to production-access testing requirements, run a **closed test** with at least 12 testers opted in continuously for at least 14 days before applying for production access.
- Across supported Android versions, verify Google sign-in, email/password registration, account deletion, location denial, notification permission denial, local alert delivery with exact alarms allowed, inexact-alarm fallback when "Alarms & reminders" is denied, alert re-arming after a reboot, and an expired OAuth session.
