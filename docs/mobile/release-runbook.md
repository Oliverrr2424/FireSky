# FireSky Mobile Release Runbook

## 1) Preflight
1. `npm install`
2. `npm run mobile:preflight`
3. Confirm no blocking errors in build output.

## 2) Android Build
1. `npm run mobile:android:bundle`
2. Output file: `android/app/build/outputs/bundle/release/app-release.aab`
3. Upload AAB to Play Console internal testing.

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
