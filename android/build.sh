#!/usr/bin/env bash
# Builds the Android APK (native tools + app).
# Usage: android/build.sh [--release] [--install]
#   --release  build app-release.apk (signed with the debug key) instead of app-debug.apk
#   --install  install the APK on the connected device with adb
set -euo pipefail

GRADLE_VER=9.9.1

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

VARIANT=debug
INSTALL=0
for arg in "$@"; do
    case "$arg" in
        --release) VARIANT=release ;;
        --install) INSTALL=1 ;;
        *) echo "Unknown option: $arg" >&2; exit 1 ;;
    esac
done

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
[[ -d "$SDK" ]] || { echo "Android SDK not found at $SDK (set ANDROID_HOME)" >&2; exit 1; }
[[ -f local.properties ]] || echo "sdk.dir=$SDK" > local.properties

./build-native.sh

# Bootstrap the Gradle wrapper on first build
if [[ ! -x gradlew ]]; then
    DIST="$HERE/build/gradle-dist"
    if [[ ! -x "$DIST/gradle-$GRADLE_VER/bin/gradle" ]]; then
        mkdir -p "$DIST"
        echo "Downloading Gradle $GRADLE_VER..."
        curl -fsSL -o "$DIST/gradle.zip" "https://services.gradle.org/distributions/gradle-$GRADLE_VER-bin.zip"
        unzip -q -o "$DIST/gradle.zip" -d "$DIST"
    fi
    "$DIST/gradle-$GRADLE_VER/bin/gradle" wrapper --gradle-version "$GRADLE_VER"
fi

TASK="assemble${VARIANT^}"
./gradlew "$TASK"

APK="$HERE/app/build/outputs/apk/$VARIANT/app-$VARIANT.apk"
echo "APK: $APK"
if (( INSTALL )); then
    adb install -r "$APK"
fi
