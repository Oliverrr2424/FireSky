# FireSky Mobile Release Runbook

## 1) Preflight
1. `npm install`
2. `npm run mobile:preflight`
3. Confirm no blocking errors in build output.

## 2) Android Build
1. On Windows, use `npm install`, `npm run build`, `npx cap sync android`, and `npx cap doctor android`. The all-platform preflight also checks Xcode, which is unavailable on Windows.
2. Check the highest uploaded version code in Play Console, then increase `versionCode` in `android/app/build.gradle` and update `versionName`. A version code cannot be reused after upload.
3. Keep the existing upload key and ignored `android/keystore.properties`. Never commit signing credentials.
4. Run `npm run mobile:android:bundle`. The launcher selects `gradlew.bat` on Windows and `./gradlew` on macOS/Linux.
5. Output file: `android/app/build/outputs/bundle/release/app-release.aab`
6. Upload AAB to Play Console **Internal testing**, enter release notes, preview the release, resolve blocking errors, and publish to internal testers.

### Install from Google Play
- Verify that the track is **Active** and the release says **Available to internal testers**. The overall app can still say **Draft**, with a temporary `com.firesky.app (unreviewed)` name.
- Add the Google account used by the phone's Play Store to an enabled tester email list.
- Open the [FireSky internal test invitation](https://play.google.com/apps/internaltest/4701618212420516910) with that account and select **Accept invite**. Being on the email list alone does not enroll the tester.
- Confirm **You're a tester**, then select **Download test app** and install through Google Play. Do not rely on searching the store for an unpublished internal test.
- If a fresh release has not propagated, allow a few minutes and reopen the download link with the same account.
- The 12 testers / 14 days requirement applies to the qualifying **closed test** for production access, not installation through internal testing.
- Reference: [Google Play testing setup](https://support.google.com/googleplay/android-developer/answer/9845334).

## 3) iOS Build (macOS required)
1. `npm run mobile:build`
2. `npm run mobile:open:ios`
3. In Xcode, set signing/team and archive build.
4. Upload archive to App Store Connect (TestFlight first).

## 4) Smoke Test Matrix
- Cold start under good network / weak network / offline.
- Location grant / deny / timeout path.
- City search and refresh.
- Sunrise/sunset mode switching.

## 5) Release Decision
- No critical crash, blank screen, or blocked permission flow.
- Store metadata matches actual app behavior.
- Privacy policy URL is reachable.
