# R8 emulator release regression — 2026-10-21

## Integrated changes and candidate

Fast-forwarded `feature/android-native` from `20f708e` to the release-hardening commit `94f489b`. Preserved the user's uncommitted `HeatRenderer.kt` and `FireSkySmokeTest.java` changes. Added one runtime fix in `MapCard.kt` after reproducing pinch drift during this pass.

The tested candidate is the **release variant with R8 enabled**, signed with the local debug key solely for emulator installation. A Gradle init script outside the repository selects that test signing configuration; no production signing configuration or version number was changed. This APK is not a store-upload artifact.

- Device: API 36 `firesky_api36`, `emulator-5554`, host GPU.
- Checked maps at both 540×1200 / 210 dpi and 1080×2400 / 420 dpi.
- Candidate: `android/app/build/outputs/apk/release/app-release.apk`.
- A copy of this exact test APK is preserved outside the build directory at `F:\tmp\firesky-r8-20261021\firesky-r8-emulator.apk`.
- SHA-256: `D824B1285A16F3735DE12581AD281718DF8CEF63D6A5529CCB825A7F513BE74D`.
- Version remains `versionCode 3`, `versionName 1.0.2`.
- Local evidence: `F:\tmp\firesky-r8-20261021` (screenshots, UI dumps, scheduler/alarm/notification dumps, database snapshots, and input fixture scripts; outside Git).

## Results

| Check | Result | What was established |
| --- | --- | --- |
| Native startup / MapLibre JNI | PASS | Minified release starts and renders both basemap and heat overlays, legends, labels and scores. No app fatal exception or JNI/class-loading error observed during these checks. |
| Pinch zoom | Fixed, then PASS | Injected two simultaneous touch slots through the emulator's touchscreen input device. Original code repeatedly recentered the camera on the finger focus, drifting from San Francisco toward Redding. The fix adjusts the camera center by the actual clamped zoom scale to preserve the geographic point under the fingers. Replayed the gesture successfully at both tested densities; San Francisco remains in the zoomed view. |
| Single-finger map drag | PASS | A swipe beginning over the map scrolls the page; it does not trap the page in the map. |
| WorkManager instantiation and execution | PASS with elapsed-time fixture | OS job triggering before the due time was correctly deferred by WorkManager. With only the test database's enqueue timestamp moved back by 3 hours, the minified app instantiated `ForecastRefreshWorker`, returned SUCCESS, advanced `period_count` from 0 to 1, and re-planned alerts. Stored interval/flex remain 10,800,000 / 3,600,000 ms. This did not wait three real hours or validate OEM scheduling. |
| Reboot recovery | PASS | Rebooted the emulator, then unlocked it without opening FireSky. Event logs show the process started for `BootReceiver`; AlarmManager contained both saved sunrise and sunset alarms again. |
| Exact-alarm denial / grant | PASS | Denied `SCHEDULE_EXACT_ALARM` app-op: the alarm had a nonzero window and inexact flags. Allowed it and sent the permission-change broadcast: the same saved event was re-armed with `window=0`, `exactAllowReason=permission`, flags `0x5`. |
| Actual alarm in forced Doze | PASS on emulator | Turned the screen off, unplugged the simulated battery, and forced deep IDLE. A real AlarmManager callback (not a manual receiver broadcast) posted notification 7101 while still IDLE. Scheduled time 12:06:59.025 UTC; `notification_enqueue` at 12:06:59.208 UTC, about 183 ms later. A temporary threshold of 0 and a near-term trigger time made the existing 54% sunrise plan testable. |
| Notification content and tap | PASS | The minified app posted San Francisco / sunrise / 54%, peak window `7:12-7:32 AM`, bearing `E 103°`; tapping opened the matching forecast. |
| Google Custom Tab | PASS to sign-in screen | `Continue with Google` opened a Custom Tab at `accounts.google.com`, showing the FireSky site's Google sign-in form. No Google credentials were entered. |
| OAuth URI dispatch / invalid-link handling | PASS | Opening `com.firesky.app://auth?auth_code=emulator-regression-invalid` returned to the account screen with `This sign-in link has expired. Please try again.`. Successful authenticated PKCE exchange still requires the backend deployment and a test account. |
| Coil image decoding / display | PASS with local image fixture | An offline cached-account fixture with a local PNG avatar displayed the image in the account screen. This checks the minified Coil display path, not authenticated avatar downloads. |
| Location fix / reverse geocoding | PASS on emulator | Injected Boston coordinates through emulator GPS, granted foreground precise location, and used the locate button. The app displayed Boston and persisted `Boston`, `Massachusetts`, `US`, latitude 42.3601 / longitude -71.0589, then loaded its forecast. Physical-device behavior still needs validation. |
| Emulator cold startup | Recorded | Three force-stopped starts at original display size reported `am start -W` TotalTime of 718, 371, 593 ms (median 593 ms). These measure initial activity display with existing caches, not fresh-install loading or physical-device startup. |

The exact-alarm/Doze test used the original forecast event time but moved the test trigger into the next 100 seconds. Device settings and synthetic account/alert fixtures are restored after testing. Forced Doze, root app-op changes and scheduler database fixtures are diagnostic tests, not claims about unattended device behavior.

## Build and automated gates

- `assembleDebug`, `testDebugUnitTest`, `lintDebug`: successful after the pinch fix; all 7 unit tests pass. Existing lint warnings remain.
- `connectedDebugAndroidTest`: successful after the pinch fix (1 test). The user's smoke test checks visible Compose content; the other agent's report about a WebView assertion applied to the earlier committed baseline. After testing, the user authorized committing this change together with the existing `RBF_SIGMA = 0.24` adjustment; both were already included in the tested candidate.
- `assembleRelease` with R8 and the external test-signing init script: successful. APK signature verified as `Android Debug`.
- GitHub Actions is integrated but has not run on a GitHub runner; no push was made.

Reproduction commands for the local test build:

```powershell
node scripts/android-gradle.mjs assembleDebug testDebugUnitTest lintDebug
$env:ANDROID_SERIAL = 'emulator-5554'
node scripts/android-gradle.mjs connectedDebugAndroidTest
node scripts/android-gradle.mjs assembleRelease --init-script F:/tmp/firesky-r8-test-signing.gradle
adb -s emulator-5554 install -r android/app/build/outputs/apk/release/app-release.apk
```

The external init script sets `release.signingConfig` to `signingConfigs.debug` in `gradle.afterProject`. Without production keystore properties, a normal signed release remains unavailable.

## Cleanup

Restored the backed-up account/token/OAuth preferences, original place, installation ID, favorites, viewpoints and alert preferences; assertions confirmed restoration. Restored precise/coarse location permission to granted, notifications to denied, exact-alarm app-op to default, original display size/density, font scale, network and simulated power/idle settings. Removed the test avatar and input helpers from the device and reset GPS to the original San Francisco coordinates. The final WorkManager database has the normal 3-hour interval / 1-hour flex, a current enqueue timestamp and period count 0; the backdated test fixture is gone. The device retains the debug-key-signed R8 candidate for later testing. The emulator is shut down after verification to free host resources.

## Documentation cleanup

Updated `cloudflare-accounts-and-push.md` to describe current local alarms/WorkManager, mark FCM as future infrastructure, and align collection disclosures with the Play checklist. Also corrected the Play permission wording: current notifications cover upcoming windows meeting the threshold; forecast-improvement push is not implemented.

## Remaining release gates

- Successful Google login/PKCE exchange after applying remote migration 0004 **before** deploying Functions; deployment still requires the user's approval.
- Physical-device reverse geocoding, multitouch feel, naturally entered Doze/OEM power management, genuine 3-hour background cadence, and representative cold-start timings.
- Production signing, Play version-code selection, a real GitHub Actions run, and closed testing.
- Previously recorded web/Capacitor npm critical advisories, crash reporting, telemetry opt-out, in-app legal pages, and the other product/compliance roadmap items remain separate work. This pass does not declare the app ready for a production store release.
