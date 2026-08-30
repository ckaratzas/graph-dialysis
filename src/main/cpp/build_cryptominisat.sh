#!/bin/bash
set -e
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}"
DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="$DIR/../resources/libcryptominisatjni.so"
mkdir -p "$(dirname "$OUT")"

STACK_DIR="$DIR/cms-stack"
CADICAL_DIR="$STACK_DIR/cadical"
CADIBACK_DIR="$STACK_DIR/cadiback"
CMS_DIR="$STACK_DIR/cryptominisat"
CMS_BUILD_DIR="$CMS_DIR/build"

# This is the REAL upstream architecture, not a standalone CryptoMiniSat: as of CMS 5.12,
# `backbone_simpl()` (an inprocessing step -- see solver.cpp's simplify-schedule tokens
# "backbone"/"oracle-sparsify") hands the current CNF to `cadiback`, a tool built on top of an
# ACTUAL CaDiCaL instance, to compute the formula's backbone (literals forced to one value in
# every satisfying assignment); the result feeds back into CMS's own CDCL search. This is why
# building the plain `cryptominisat5` library target now unconditionally requires prebuilt
# `cadical`+`cadiback` libraries at specific sibling-relative paths (CMS's own CMakeLists.txt:
# `find_library(cadical PATHS <cryptominisat>/../cadical/build/ ...)`, similarly for cadiback) --
# unlike the rest of this codebase's vendored deps (nauty, CaDiCaL for OUR OWN JNI binding), this
# one genuinely cannot be built standalone at this version.
#
# THREE separate checkouts, matching CMS's own CI recipe (.github/workflows/binary-build.yml) --
# `master`/`main` on any of the three drifts the API incompatibly (confirmed directly: CMS 5.12.1's
# backbone.cpp calls a 3-argument CadiBack::doit(), current cadiback `main` has grown to 8 arguments
# -- picking the wrong commit fails to compile, not silently misbehaves, so getting this pin right
# matters):
#   cms-stack/cadical/       meelgroup/cadical, tag mate-only-libraries-1.8.0 (a fork maintained
#                             for cadiback/CMS's own consumption -- NOT the arminbiere/cadical this
#                             repo's OWN CaDiCaL JNI binding vendors separately under
#                             src/main/cpp/cadical/; deliberately kept in an isolated stack dir so
#                             the two never collide despite both being named "cadical")
#   cms-stack/cadiback/      meelgroup/cadiback @ 5610143 ("Now verbosity can be controlled") --
#                             the last commit whose CadiBack::doit() signature matches what CMS
#                             5.12.1 actually calls; every later commit adds more out-params
#   cms-stack/cryptominisat/ CryptoMiniSat 5.12.1 itself
#
# Rebuild from scratch:
#   rm -rf src/main/cpp/cms-stack && (re-run the clone+checkout+build sequence this comment
#   documents -- see git history of this file for the exact commands, since re-deriving the
#   cadiback commit pin requires bisecting its history against CMS's backbone.cpp call site again)
if [ ! -f "$CADICAL_DIR/build/libcadical.a" ] || [ ! -f "$CADIBACK_DIR/libcadiback.a" ] || [ ! -f "$CMS_BUILD_DIR/lib/libcryptominisat5.a" ]; then
    echo "src/main/cpp/cms-stack/ is missing a prebuilt piece (cadical/cadiback/cryptominisat5)." >&2
    echo "This stack's three-repo version pin isn't a one-liner to reproduce -- see the comment" >&2
    echo "at the top of this script for exactly which checkout+build sequence produced it." >&2
    exit 1
fi

CMS_LIB="$CMS_BUILD_DIR/lib/libcryptominisat5.a"
CADICAL_LIB="$CADICAL_DIR/build/libcadical.a"
CADIBACK_LIB="$CADIBACK_DIR/libcadiback.a"

# M4RI (Method of Four Russians Inversion, GPLv2 -- NOT vendored, must already be on the system as
# `libm4ri-dev`) is what gives CryptoMiniSat's OWN CDCL engine native XOR detection + Gaussian
# elimination, independent of the cadiback/CaDiCaL backbone step above -- both are real, separate
# reasons this solver can behave differently from plain CaDiCaL on a parity-structured (CFI)
# instance. Linked only if actually present (checked at CMS's own configure time, not here) --
# `cms-stack/cryptominisat/build`'s CMake cache records whether it took; this script doesn't
# reconfigure, only links whatever was already built into libcryptominisat5.a.
M4RI_LINK=()
if ldconfig -p 2>/dev/null | grep -q 'libm4ri'; then
    M4RI_LINK=(-lm4ri)
fi

g++ -std=c++20 -shared -fPIC -O3 -march=native \
    -I"${JAVA_HOME}/include" \
    -I"${JAVA_HOME}/include/linux" \
    -I"$CMS_DIR/src" \
    -I"$CMS_BUILD_DIR/cmsat5-src" \
    -I"$CADIBACK_DIR" \
    -o "$OUT" \
    "$DIR/cryptominisat_jni.cpp" \
    "$CMS_LIB" "$CADIBACK_LIB" "$CADICAL_LIB" "${M4RI_LINK[@]}" -lgmp -lgmpxx -lpthread
echo "Built $OUT (statically linked against cryptominisat5 + cadiback + cadical)"
