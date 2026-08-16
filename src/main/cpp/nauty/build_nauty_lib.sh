#!/bin/bash
# Builds the vendored nauty/Traces core (see COPYRIGHT) into a static archive.
# Plain gcc, no autotools/libtool — nauty.h self-configures via the
# preprocessor and the shipped gtools.h is already generated, so the pristine
# source compiles as-is (verified against a clean upstream checkout).
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="$DIR/libnauty.a"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

for f in "$DIR"/*.c; do
    base="$(basename "$f" .c)"
    [ "$base" = "sorttemplates" ] && continue   # template fragment, not a TU
    gcc -O3 -march=native -fPIC -c "$f" -o "$TMP/$base.o"
done

ar rcs "$OUT" "$TMP"/*.o
echo "Built $OUT"