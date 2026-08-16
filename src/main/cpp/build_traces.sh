#!/bin/bash
set -e
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}"
DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="$DIR/../resources/libtracesjni.so"
mkdir -p "$(dirname "$OUT")"
bash "$DIR/nauty/build_nauty_lib.sh"
g++ -std=c++17 -shared -fPIC -O3 -march=native \
    -I"${JAVA_HOME}/include" \
    -I"${JAVA_HOME}/include/linux" \
    -I"$DIR" \
    -o "$OUT" \
    "$DIR/nauty_traces.cpp" "$DIR/nauty/libnauty.a" \
    -Wl,--exclude-libs,libnauty.a
echo "Built $OUT"