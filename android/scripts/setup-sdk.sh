#!/usr/bin/env bash
# One-time toolchain install under ~/Android/Sdk. Re-runnable. Accepts the Android SDK licenses
# (running this script is the explicit acceptance step).
set -euo pipefail
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
CMDLINE_ZIP_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  tmp="$(mktemp -d)"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  curl -fL --retry 3 -o "$tmp/cmdtools.zip" "$CMDLINE_ZIP_URL"
  unzip -q "$tmp/cmdtools.zip" -d "$tmp/x"
  mv "$tmp/x/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$tmp"
fi

# shellcheck disable=SC1091
source "$(dirname "$0")/env.sh"
yes | sdkmanager --licenses >/dev/null || true
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0" "emulator" \
  "system-images;android-34;google_apis;x86_64"

if ! avdmanager list avd | grep -q "Name: sentinel_pixel"; then
  echo no | avdmanager create avd -n sentinel_pixel -d pixel_6 \
    -k "system-images;android-34;google_apis;x86_64"
fi
echo "SDK ready at $ANDROID_HOME"
