#!/usr/bin/env bash
# Proof of concept: run the Android builds of ctrdecrypt + makerom on a connected device over adb.
# Usage: android/poc.sh <rom.cia|rom.3ds|rom.cci>
# Uses the desktop bin/ctrtool only to read the title type/version (the app does this itself).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$HERE")"
LIBS="$HERE/app/src/main/jniLibs/arm64-v8a"
DEV=/data/local/tmp/decryptor

ROM="${1:?usage: $0 <rom>}"
FILE="$(basename "$ROM")"
NAME="${FILE%.*}"
EXT="${FILE##*.}"; EXT="${EXT,,}"

[[ -f "$LIBS/libctrdecrypt.so" && -f "$LIBS/libmakerom.so" ]] || { echo "Run android/build-native.sh first" >&2; exit 1; }
adb get-state >/dev/null || { echo "No device connected" >&2; exit 1; }

echo "Device: $(adb shell getprop ro.product.model) / Android $(adb shell getprop ro.build.version.release) / $(adb shell getprop ro.product.cpu.abi)"
adb shell "rm -rf $DEV && mkdir -p $DEV"
adb push "$LIBS/libctrdecrypt.so" $DEV/ctrdecrypt >/dev/null
adb push "$LIBS/libmakerom.so" $DEV/makerom >/dev/null
adb push "$ROOT/bin/seeddb.bin" $DEV/ >/dev/null
echo "Pushing $FILE..."
adb push "$ROM" "$DEV/$FILE" >/dev/null
adb shell "chmod 755 $DEV/ctrdecrypt $DEV/makerom"

echo "== ctrdecrypt"
time adb shell "cd $DEV && ./ctrdecrypt '$FILE'"

mapfile -t NCCH < <(adb shell "cd $DEV && ls -1 '$NAME'.*.ncch" | tr -d '\r')
(( ${#NCCH[@]} )) || { echo "ctrdecrypt produced no .ncch files" >&2; exit 1; }
printf '  %s\n' "${NCCH[@]}"

INFO=$("$ROOT/bin/ctrtool" --seeddb="$ROOT/bin/seeddb.bin" "$ROM" 2>/dev/null || true)
VER=$(grep 'TitleVersion' <<<"$INFO" | tail -n1 | sed -n 's/.*(v\{0,1\}\([0-9]*\)).*/\1/p'); VER=${VER:-0}

ARGS=()
if [[ "$EXT" != cia ]]; then
    for f in "${NCCH[@]}"; do s="${f%.ncch}"; i=$((16#${s##*.})); ARGS+=(-i "'$f:$i:$i'"); done
    OUTF="$NAME-decrypted.cci"; MR="-f cci"
else
    if   grep -Eq '^T.*d.*0004000e' <<<"$INFO"; then KIND=update; OUTF="$NAME (Update)-decrypted.cia"; MR="-f cia"
    elif grep -Eq '^T.*d.*0004008c' <<<"$INFO"; then KIND=dlc;    OUTF="$NAME (DLC)-decrypted.cia";    MR="-f cia -dlc"
    else                                               KIND=game;   OUTF="$NAME-decrypted.cia";          MR="-f cia"; fi
    for f in "${NCCH[@]}"; do
        s="${f%.ncch}"; id=$((16#${s##*.})); s="${s%.*}"; i="${s##*.}"
        [[ $KIND == game ]] && ARGS+=(-i "'$f:$i:$i'") || ARGS+=(-i "'$f:$i:$id'")
    done
    MR="$MR -ver $VER"
fi

echo "== makerom"
time adb shell "cd $DEV && ./makerom $MR -ignoresign -target p -o '$OUTF' ${ARGS[*]}"

mkdir -p "$HERE/build/poc"
adb pull "$DEV/$OUTF" "$HERE/build/poc/" >/dev/null
adb shell "rm -rf $DEV"
echo "== Result: $HERE/build/poc/$OUTF"
"$ROOT/bin/ctrtool" "$HERE/build/poc/$OUTF" 2>/dev/null | grep -E 'Title id|TitleVersion|Crypto Key' | sort -u
