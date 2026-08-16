#!/bin/bash
set -e
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}"
DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="$DIR/../resources/libcadicaljni.so"
mkdir -p "$(dirname "$OUT")"

# CaDiCaL's own configure/make (not hand-replicated compiler flags) -- it self-detects
# platform features (unlocked IO, closefrom, etc.); ./configure regenerates build/ fresh
# each time (see cadical/.gitignore -- build/ and makefile are never vendored/committed).
# --shared adds -fpic: CaDiCaL's default build targets its own static 'cadical' binary,
# not position-independent code, so linking libcadical.a into OUR .so fails without it.
CADICAL_DIR="$DIR/cadical"
( cd "$CADICAL_DIR" && ./configure --shared && make -C build -j"$(nproc)" )

g++ -std=c++17 -shared -fPIC -O3 -march=native \
    -I"${JAVA_HOME}/include" \
    -I"${JAVA_HOME}/include/linux" \
    -I"$DIR" \
    -o "$OUT" \
    "$DIR/cadical_jni.cpp" "$CADICAL_DIR/build/libcadical.a"
echo "Built $OUT"