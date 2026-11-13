#!/usr/bin/env bash
# Batch CIA CCI Decryptor - Linux port of "Batch CIA CCI Decryptor.bat"
#
# Usage: ./decrypt.sh [--cia] [--debug] [DIRECTORY]
#   DIRECTORY  folder containing .3ds/.cci/.cia files (default: the folder this script is in)
#   --cia      save decrypted game CIAs as .cia instead of .cci (Azahar's game list only
#              shows .cci/.3ds files; CIAs have to be installed with File > Install CIA)
#   --debug    print commands and tool output, write ctrdecrypt output to log.txt,
#              and don't skip files that already look decrypted

set -u
shopt -s nullglob nocaseglob

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BIN="$SCRIPT_DIR/bin"

DEBUG=0
GAME_FORMAT=cci
WORKDIR="$SCRIPT_DIR"
for arg in "$@"; do
    case "$arg" in
        --debug) DEBUG=1 ;;
        --cia) GAME_FORMAT=cia ;;
        --cci) GAME_FORMAT=cci ;;
        -h|--help) sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
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

# Decrypt a ROM into .ncch files (ctrdecrypt looks for seeddb.bin next to itself).
# Returns ctrdecrypt's exit status; it fails e.g. when a seed-encrypted title has no seed.
decrypt_rom() {
    local status
    if (( DEBUG )); then
        echo "----------------------------------------------------" >>log.txt
        "$CTRDECRYPT" "$1" >>log.txt 2>&1
        status=$?
        printf '%s\n\n' "------------------------End-------------------------" >>log.txt
    else
        "$CTRDECRYPT" "$1" --no-verbose >/dev/null 2>&1
        status=$?
    fi
    return $status
}

# Outputs written in this run (lowercase name -> source ROM), so two ROMs that map to the
# same output name (e.g. Game.3ds and Game.cia) can't silently overwrite each other
declare -A WRITTEN=()

# build OUTPUT ROM MAKEROM-ARGS...: run makerom unless OUTPUT was already written this run
build() {
    local out="$1" src="$2"
    shift 2
    if [[ -n "${WRITTEN[${out,,}]:-}" ]]; then
        echo "Skipping \"$src\": \"$out\" was already written from \"${WRITTEN[${out,,}]}\". Rename one of them and run again."
        echo
        return 1
    fi
    run "$MAKEROM" "$@" -ignoresign -target p -o "$out" && WRITTEN[${out,,}]="$src"
}

# Report a failed decrypt and remove whatever ctrdecrypt left behind
decrypt_failed() {
    echo "Failed to decrypt \"$1\" (run with --debug and see log.txt for details)"
    echo
    rm -f -- "${1%.*}".*.ncch
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

    if ! decrypt_rom "$rom"; then
        decrypt_failed "$rom"
        continue
    fi

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

    build "$name-decrypted.cci" "$rom" -f cci "${args[@]}"
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

    if ! decrypt_rom "$rom"; then
        decrypt_failed "$rom"
        continue
    fi

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
        if [[ $GAME_FORMAT == cci ]]; then
            # The content index is the CCI partition (0 game, 1 manual, 2 download play child)
            build "$name-decrypted.cci" "$rom" -f cci "${args[@]}"
        else
            build "$name-decrypted.cia" "$rom" -f cia "${args[@]}" -ver "$ver"
        fi
    fi

    if grep -Eq '^T.*d.*0004000e' content.txt; then
        build "$name (Update)-decrypted.cia" "$rom" -f cia "${dlc[@]}" -ver "$ver"
    fi

    if grep -Eq '^T.*d.*0004008c' content.txt; then
        run "$DLCHELPER" "$name" "$ver"
    fi
done

# Clean up temporary files
rm -f content.txt -- *.ncch

echo "Done."
