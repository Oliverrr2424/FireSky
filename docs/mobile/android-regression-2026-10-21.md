# Android native regression — 2026-10-21

## Scope and build

- Branch: `feature/android-native`, base commit `20f708e`.
- Tested the current debug APK, including the user's existing `RBF_SIGMA = 0.24` change. Neither that file nor `FireSkySmokeTest.java` was edited or committed during this pass.
- APK SHA-256: `1E967CC1FD2D40FE3A8899C0D1C68556C9B3FF98F5117525827472D6FDEDCC7A`.
- `assembleDebug` succeeded. `testDebugUnitTest --rerun-tasks` ran all seven `ForecastEventsTest` tests: zero failures, errors or skips.
- Device regression used API 36 AVD `firesky_api36`, serial `emulator-5554`, with the host GPU renderer. The other AVD repeatedly exited with software rendering; the cause was not established. Those interrupted attempts are not counted as passes.
- UI checks used 540×1200 at 210 dpi, equivalent in logical dimensions to the original 1080×2400 at 420 dpi. Font scaling was set through the system's `font_scale` setting.

## Results

| Check | Result | Evidence / limits |
| --- | --- | --- |
| Offline city switch | PASS | With San Francisco loaded, disabled Wi-Fi and mobile data and selected the Mexico City preset. Title returned to San Francisco, the existing forecast remained visible, and the message read `Couldn't load Mexico City. Can't reach FireSky. Check your connection and try again.` |
| System font scale 1.3 | PASS for inspected screens | Visually checked the home controls, error message, hero card, solar event times, 7-day strip, sign-in/sign-up fields, alert settings and password-change errors. Text wrapped without overlapping adjacent controls. The 7-day strip remains horizontally scrollable. This is not an exhaustive screen/device matrix. |
| Receiver → notification → forecast | PASS | A real San Francisco plan was generated with a temporary threshold of 0 so the 54% sunrise qualified. Granted notification permission. Stopped the app and changed its saved last place to Vancouver, retaining the San Francisco plan. Root broadcast of ID 7101 posted `Sunrise color chance 54% · San Francisco`, peak color `7:12-7:32 AM`, direction `E 103°`. Tapping the notification cold-opened San Francisco in sunrise mode. Hero and today's 7-day entry both showed 54%. |
| Empty sign-in | PASS | Separate `Enter your email address.` and `Enter your password.` messages. |
| Invalid email | PASS | Entering `bad` and moving focus showed the email-format message. |
| Short login / signup password | PASS | Both modes rejected fewer than 12 characters. Login displayed `FireSky passwords have at least 12 characters.`; signup displayed `Use at least 12 characters.` |
| Password visibility | PASS | Visibility control switched the field between masked and visible text and changed its accessibility label. |
| Password-change validation | PASS | With an offline cached-account fixture, empty submit showed current-password, minimum-length and confirmation-required errors. Different new/confirmation values showed `Passwords don't match.`. No real account was created or password changed. |

Receiver command (root adbd; `--include-stopped-packages` was needed for the deliberately force-stopped test app):

```powershell
adb -s emulator-5554 root
adb -s emulator-5554 shell am broadcast --include-stopped-packages -n com.firesky.app/.notifications.SkyAlertReceiver --ei id 7101
```

Screenshots, UI hierarchies and temporary fixtures are local, outside Git, under `F:\tmp\firesky-regression-20261021`. Useful evidence: `offline-city-a`, `font13-cards`, `signup-short`, `security-empty`, `security-mismatch`, `notification`, `notification-opened`, and `notification-event` (`.png` / `.xml`). Temporary fixture credentials are synthetic.

## Cleanup

- Restored the original native preferences, secure-token preferences and alert plan; verified the original last place, favorites, viewpoints, account state and reminder preferences, and verified the fixture token was removed.
- Restored font scale to 1.0, original display size/density, animation settings, Wi-Fi and mobile data. Revoked the notification permission granted for this test.
- Restored the second AVD's display/network/animation settings as well. Both emulators were shut down after testing; neither was running at the start of this pass.

## Backend pre-deployment checks

- Applied migrations 0001 through 0004 in order to a temporary in-memory SQLite database. Confirmed `oauth_states.code_challenge`, `oauth_codes`, and a clean foreign-key check. This does not verify the remote D1 schema or migration ledger.
- Exercised local URL builders: grid requests use `forecast_days=2`; English geocoding uses `language=en`.
- Installed npm dependencies locally, ran `npm run build`, and compiled Pages Functions with `npx wrangler pages functions build functions --outdir F:/tmp/firesky-regression-20261021/functions-build`; both builds succeeded. Vite reported an existing large client bundle warning (about 1.52 MB before gzip).
- No remote migrations, deployment, account mutation or push was performed.

The npm audit reported three critical advisories in the current JS/Capacitor dependency graph: `@capacitor/android` and `@capacitor/ios` ([GHSA-rvm3-566m-v7fv](https://github.com/advisories/GHSA-rvm3-566m-v7fv)), and `maplibre-gl` ([GHSA-jrc7-96c5-q579](https://github.com/advisories/GHSA-jrc7-96c5-q579)). The audit proposes Capacitor 8.5.3 and MapLibre GL JS 6.15.0; the latter is a major-version change from the declared 5.x range. No dependency upgrade or `audit fix --force` was applied. Audit details are saved in the local evidence folder as `npm-audit.json`. Review these before publishing a new web bundle; the native Android app uses its separate Gradle dependency graph.

Deployment requires user approval. The repository README identifies the active Pages project as `firesky`, serving `fireskychase.pages.dev`; the `wrangler.toml` name refers to the older project, so use the explicit project argument.

```powershell
# Must succeed before publishing the Functions that depend on the new schema.
npx wrangler d1 migrations apply firesky --remote

npm run build
npx wrangler pages deploy dist --project-name firesky --branch master
```

After deployment, verify native Google sign-in through the PKCE exchange, English search results, and two days in the grid response. Previously cached Chinese device search results can remain for up to 24 hours; use an uncached query or a clean test-device search cache to distinguish that from server behavior.

## Still requires physical-device / production validation

- Reverse geocoding, pinch zoom, Doze delivery timing, delivery after granting exact-alarm access, and representative cold-start timing.
- Live Google OAuth and successful authenticated account operations after the migration/deployment.
- R8 release behavior after the separately owned release-hardening work is integrated.

No native code defect requiring a change was found in this initial P0 regression pass. The subsequent [R8 emulator regression](android-r8-regression-2026-10-21.md) found and fixed pinch-focus drift and records the additional release-runtime checks.
