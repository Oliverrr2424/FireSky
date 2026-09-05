# FireSky internal release — 2026-09-05

- Source: `master` at `2ce2d0e`, plus the local Android launcher fix and version bump.
- Package: `com.firesky.app`.
- Release: `1.0.2 internal`, version name `1.0.2`, version code `3`.
- Track: Internal testing (`4701618212420516910`).
- Console verification: **Active**, **Available to internal testers**, released September 5, 2026.
- AAB: `android/app/build/outputs/bundle/release/app-release.aab`.
- SHA-256: `94a94a3c16eecc16db3312b4f83e58ce4f974e2508376b862afdb535792d86fb`.

## Diagnosis and changes

The earlier release was already available to internal testers. The listed tester had not accepted the invitation. After accepting, Google Play displayed **You're a tester**, **Install**, and **This app is available for your device**.

The npm Android tasks used `./gradlew`, which failed under Windows npm's command shell. `scripts/android-gradle.mjs` now selects the platform-specific Gradle wrapper. Version 1.0.1 (code 2) was superseded by this release.

On phones, a later responsive CSS rule was moving the account sheet back beside the avatar. It now remains a bottom sheet below 720px. Native Google OAuth now registers its URL listener before opening the browser and also consumes the launch URL, so a callback that starts or resumes the app is handled.

## Validation

- Vite production build: passed.
- Capacitor Android sync and `npx cap doctor android`: passed.
- Signed Gradle release bundle, including release lint: passed.
- Google Play upload and release validation: passed with two non-blocking diagnostic warnings (no deobfuscation mapping and no native debug symbols). R8 minification is disabled.
- Supported device counts did not decrease in the release preview.
- The all-platform preflight reports missing Xcode on Windows; Android-specific checks passed.
- Physical installation and launch must be confirmed on the phone; no ADB device was connected during preparation.
- The store listing updated to September 5 and offered installation on `Xiaomi 2201123C` (last used today). The remote install flow requested Google account reauthentication before completion; no successful installation confirmation has been received yet.
- The Cloudflare password endpoint and D1 password table both responded normally; a failed email registration should now display the server-provided reason in the corrected mobile sheet.

## Installation

Use the phone's approved Google Play account to open the [internal test invitation](https://play.google.com/apps/internaltest/4701618212420516910), then **Download test app**. The temporary `com.firesky.app (unreviewed)` name is expected until app setup and review are complete.
