#!/usr/bin/env bash
# Builds ctrdecrypt + dlchelper and downloads Linux builds of ctrtool + makerom into bin/
set -euo pipefail

CTRTOOL_VER=1.3.0
MAKEROM_VER=0.19.0
BASE_URL=https://github.com/3DSGuy/Project_CTR/releases/download

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BIN="$ROOT/bin"
mkdir -p "$BIN"

CARGO=$(command -v cargo || echo "$HOME/.cargo/bin/cargo")
if [[ ! -x "$CARGO" ]]; then
    echo "cargo not found. Install Rust (e.g. 'sudo dnf install cargo' or https://rustup.rs)" >&2
    exit 1
fi

for tool in ctrdecrypt dlchelper; do
    echo "Building $tool..."
    "$CARGO" build --release --manifest-path "$ROOT/$tool/Cargo.toml"
    install -m 755 "$ROOT/$tool/target/release/$tool" "$BIN/$tool"
done

# ctrdecrypt looks for seeddb.bin next to itself, which is bin/seeddb.bin

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
fetch() { # name version
    local zip="$1-v$2-ubuntu_x86_64.zip"
    echo "Downloading $zip..."
    curl -fsSL -o "$TMP/$zip" "$BASE_URL/$1-v$2/$zip"
    unzip -o -q "$TMP/$zip" -d "$TMP/$1"
    install -m 755 "$(find "$TMP/$1" -type f -name "$1" | head -n1)" "$BIN/$1"
}
[[ -x "$BIN/ctrtool" ]] || fetch ctrtool "$CTRTOOL_VER"
[[ -x "$BIN/makerom" ]] || fetch makerom "$MAKEROM_VER"

echo "Done. Put your .3ds/.cci/.cia files next to decrypt.sh and run ./decrypt.sh"
