#!/usr/bin/env bash
# Batch CIA CCI Decryptor - Linux port of "Batch CIA CCI Decryptor.bat"
#
# Usage: ./decrypt.sh [--debug] [DIRECTORY]
#   DIRECTORY  folder containing .3ds/.cci/.cia files (default: the folder this script is in)
#   --debug    print commands and tool output, write ctrdecrypt output to log.txt,
#              and don't skip files that already look decrypted

set -u
shopt -s nullglob nocaseglob

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BIN="$SCRIPT_DIR/bin"

DEBUG=0
WORKDIR="$SCRIPT_DIR"
for arg in "$@"; do
    case "$arg" in
        --debug) DEBUG=1 ;;
        -h|--help) sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) WORKDIR="$arg" ;;
    esac
done

# Prefer tools in bin/, fall back to PATH
find_tool() {
    if [[ -x "$BIN/$1" ]]; then echo "$BIN/$1"
    elif command -v "$1" >/dev/null 2>&1; then command -v "$1"
    else echo "Error: '$1' not found in $BIN or PATH" >&2; return 1
    fi
}
CTRTOOL=$(find_tool ctrtool) || exit 1
MAKEROM=$(find_tool makerom) || exit 1
CTRDECRYPT=$(find_tool ctrdecrypt) || exit 1
DLCHELPER=$(find_tool dlchelper) || exit 1
SEEDDB="$BIN/seeddb.bin"

cd "$WORKDIR" || exit 1

# Run a command; in debug mode echo it and show its output, otherwise silence it
run() {
    if (( DEBUG )); then
        echo "$*"
        "$@"
        echo
    else
        "$@" >/dev/null 2>&1
    fi
}

# Decrypt a ROM into .ncch files (ctrdecrypt looks for seeddb.bin next to itself)
decrypt_rom() {
    if (( DEBUG )); then
        {
            echo "----------------------------------------------------"
            "$CTRDECRYPT" "$1"
            echo "------------------------End-------------------------"
            echo
        } >>log.txt 2>&1
    else
        "$CTRDECRYPT" "$1" --no-verbose >/dev/null 2>&1
    fi
}

# Returns 0 if ctrtool reports the content as unencrypted
already_decrypted() {
    (( DEBUG )) && return 1
    grep -Eq 'Crypto.*None' content.txt
}

echo "Decrypting & building..."
echo
(( DEBUG )) && date >log.txt

# Clean up leftovers
rm -f -- *.ncch

# ---- .3ds / .cci ----
for rom in *.3ds *.cci; do
    name="${rom%.*}"
    [[ "${name,,}" == *decrypted* ]] && continue

    "$CTRTOOL" --seeddb="$SEEDDB" "$rom" >content.txt 2>/dev/null

    if already_decrypted; then
        echo "File \"$rom\" is already decrypted, skipping"
        echo
        continue
    fi

    decrypt_rom "$rom"

    # Files are named <name>.<Partition>.<index as 8 hex digits>.ncch
    args=()
    for f in "$name".*.ncch; do
        stem="${f%.ncch}"
        index=$((16#${stem##*.}))
        args+=(-i "$f:$index:$index")
    done

    if (( ${#args[@]} == 0 )); then
        echo "Failed to decrypt \"$rom\""
        echo
        continue
    fi

    run "$MAKEROM" -f cci -ignoresign -target p -o "$name-decrypted.cci" "${args[@]}"
done

# ---- .cia ----
for rom in *.cia; do
    name="${rom%.*}"
    [[ "${name,,}" == *decrypted* ]] && continue

    "$CTRTOOL" --seeddb="$SEEDDB" "$rom" >content.txt 2>/dev/null

    if already_decrypted; then
        echo "File \"$rom\" is already decrypted, skipping"
        echo
        continue
    fi

    decrypt_rom "$rom"

    # Files are named <name>.<content index>.<content id as 8 hex digits>.ncch
    args=()
    dlc=()
    for f in "$name".*.ncch; do
        stem="${f%.ncch}"
        id=$((16#${stem##*.}))
        stem="${stem%.*}"
        index="${stem##*.}"
        args+=(-i "$f:$index:$index")
        dlc+=(-i "$f:$index:$id")
    done

    if (( ${#args[@]} == 0 )); then
        echo "Failed to decrypt \"$rom\""
        echo
        continue
    fi

    # Title version, e.g. "TitleVersion:  1.2.0 (1056)" -> 1056 (last match wins, like the .bat)
    ver=$(grep 'TitleVersion' content.txt | tail -n1 | sed -n 's/.*(v\{0,1\}\([0-9]*\)).*/\1/p')
    ver=${ver:-0}

    # Pick output type based on the TitleID category
    if grep -Eq '^T.*d.*00040000' content.txt; then
        run "$MAKEROM" -f cia -ignoresign -target p -o "$name-decrypted.cia" "${args[@]}" -ver "$ver"
    fi

    if grep -Eq '^T.*d.*0004000e' content.txt; then
        run "$MAKEROM" -f cia -ignoresign -target p -o "$name (Update)-decrypted.cia" "${dlc[@]}" -ver "$ver"
    fi

    if grep -Eq '^T.*d.*0004008c' content.txt; then
        run "$DLCHELPER" "$name" "$ver"
    fi
done

# Clean up temporary files
rm -f content.txt -- *.ncch

echo "Done."
