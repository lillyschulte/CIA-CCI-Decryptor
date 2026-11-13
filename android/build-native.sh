#!/usr/bin/env bash
# Cross-compiles ctrdecrypt (Rust) and makerom (C, Project_CTR) for Android arm64
# and places them in app/src/main/jniLibs/arm64-v8a/ as lib*.so so the APK ships them
# as executables in the app's native library directory.
set -euo pipefail

MAKEROM_TAG=makerom-v0.18.4   # v0.19.0 drops the title version, see ../setup.sh
API=29

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$HERE")"
OUT="$HERE/app/src/main/jniLibs/arm64-v8a"
WORK="$HERE/build/native"
mkdir -p "$OUT" "$WORK"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
NDK="${ANDROID_NDK_HOME:-$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -n1)}"
[[ -d "$NDK" ]] || { echo "Android NDK not found (looked in $SDK/ndk)" >&2; exit 1; }
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CC="$TC/aarch64-linux-android$API-clang"
CXX="$TC/aarch64-linux-android$API-clang++"
echo "Using NDK: $NDK"

# ---- ctrdecrypt ----
CARGO=$(command -v cargo || echo "$HOME/.cargo/bin/cargo")
RUSTUP=$(command -v rustup || echo "$HOME/.cargo/bin/rustup")
"$RUSTUP" target add aarch64-linux-android >/dev/null
echo "Building ctrdecrypt..."
CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CC" \
CC_aarch64_linux_android="$CC" \
AR_aarch64_linux_android="$TC/llvm-ar" \
    "$CARGO" build --release --target aarch64-linux-android \
    --manifest-path "$ROOT/ctrdecrypt/Cargo.toml"
install -m 755 "$ROOT/ctrdecrypt/target/aarch64-linux-android/release/ctrdecrypt" "$OUT/libctrdecrypt.so"

# ---- makerom ----
if [[ ! -d "$WORK/Project_CTR" ]]; then
    git clone --depth 1 --branch "$MAKEROM_TAG" https://github.com/3DSGuy/Project_CTR "$WORK/Project_CTR"
fi
# The makefiles call plain `ar`, so put llvm-ar first on PATH
mkdir -p "$WORK/toolbin"
ln -sf "$TC/llvm-ar" "$WORK/toolbin/ar"
echo "Building makerom..."
(
    cd "$WORK/Project_CTR/makerom"
    export PATH="$WORK/toolbin:$PATH"
    # ARFLAGS: the makefiles use "cr -o", which GNU ar tolerates but llvm-ar rejects
    MAKEARGS=(PROJECT_PLATFORM=GNU PROJECT_PLATFORM_ARCH=arm64 CC="$CC" CXX="$CXX"
              ARFLAGS=rcs
              "ARCHFLAGS=-static-libstdc++ -Wno-unused-command-line-argument")
    make -j"$(nproc)" deps "${MAKEARGS[@]}"
    make -j"$(nproc)" program "${MAKEARGS[@]}"
)
install -m 755 "$WORK/Project_CTR/makerom/bin/makerom" "$OUT/libmakerom.so"

# ---- seeddb.bin ----
mkdir -p "$HERE/app/src/main/assets"
cp "$ROOT/bin/seeddb.bin" "$HERE/app/src/main/assets/seeddb.bin"

file "$OUT"/*.so
echo "Native tools ready in $OUT"
