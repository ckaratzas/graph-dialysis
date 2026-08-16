#!/bin/bash
set -e
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}"
DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="$DIR/../resources/libwl1jni.so"
mkdir -p "$(dirname "$OUT")"
g++ -std=c++17 -shared -fPIC -O3 -march=native \
    -I"${JAVA_HOME}/include" \
    -I"${JAVA_HOME}/include/linux" \
    -o "$OUT" \
    "$DIR/paige_tarjan_wl1.cpp"
echo "Built $OUT"