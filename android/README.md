# Android app

An Android port of the decryptor for arm64 devices (built and laid out with the AYN Thor in mind).
It runs the same pipeline as `decrypt.sh` on the device:

* `ctrdecrypt` (Rust, from this repo) and `makerom` (C, [Project_CTR](https://github.com/3DSGuy/Project_CTR) v0.18.4)
  are cross-compiled for `arm64-v8a` and shipped inside the APK
* Title ID, version and encryption status are read directly from the ROM headers (replaces `ctrtool`)
* DLC/update CIA handling replaces `dlchelper`

## Features
* Choose a ROM folder on internal storage or an SD card (remembered between launches)
* Decrypts every `.3ds`, `.cci` and `.cia` in the folder, skipping ones that are already decrypted or already have a `-decrypted` copy
* Shows the current file, its stage, and per-file and overall progress bars, plus a log
* Keeps running in the background with a progress notification (can be cancelled)

## Requirements
* Android 11 or newer, arm64
* The app asks for **All files access** so the native tools can read and write ROMs by path

## Building
Needs the Android SDK + NDK (`~/Android/Sdk` or `$ANDROID_HOME`), Rust/cargo, `git`, `make`, `curl` and `unzip`.
```
android/build.sh            # builds native tools + app-debug.apk
android/build.sh --install  # also installs it on the connected device via adb
android/build.sh --release  # app-release.apk instead
```
`build-native.sh` can be run on its own to rebuild just the native tools.

## Proof of concept (no app)
`poc.sh` runs the Android builds of the tools directly on a connected device over adb,
which is useful for checking the native side on new hardware:
```
android/build-native.sh
android/poc.sh "/path/to/Game.cia"
```
