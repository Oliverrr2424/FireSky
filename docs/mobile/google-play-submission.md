# Google Play Submission Checklist

## Policy Forms
- Complete **Data Safety** using actual app behavior.
- Complete **Content Rating** questionnaire.
- Declare whether app contains ads.
- Before the first production release, disclose these collected data types when their corresponding features are enabled: precise location, email/account identifier, saved locations/viewpoints, optional forecast feedback, crash diagnostics, and push token/device identifier.
- Mark account creation as optional, provide the published privacy-policy URL, and verify the in-app account deletion flow during closed testing.

## Permissions
- Location permission must be justified in store listing and in-app usage.
- If background location is not used, do not request it.
- `POST_NOTIFICATIONS` is requested only after the user enables alerts. Explain that alerts cover forecast probability changes for the selected sunrise/sunset window.

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
- Across supported Android versions, verify Google sign-in, email/password registration, account deletion, location denial, notification permission denial, FCM delivery, local-notification fallback, and an expired OAuth session.
