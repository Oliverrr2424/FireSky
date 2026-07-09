#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
if [[ -z "${ANDROID_HOME:-}" && -d /opt/homebrew/share/android-commandlinetools ]]; then
  export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
fi
if [[ -z "${ANDROID_HOME:-}" ]]; then
  echo "ANDROID_HOME is required to run the Android smoke test." >&2
  exit 1
fi
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
[[ -d node_modules ]] || npm install
npm run build
npx cap sync android
( cd android && ./gradlew :app:connectedDebugAndroidTest )
