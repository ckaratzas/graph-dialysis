#!/usr/bin/env bash
# Runs the FVS-seeded 1-WL scale campaign (FVS_SEEDED_1WL_SPEC.md) -- one JUnit test per family,
# NOT the BenchmarkRunner CLI (this experiment is single-threaded, restricted-SAT-query-driven, and
# was never wired into that CLI -- see FVS_SEEDED_1WL_SPEC.md's own "Status" section). Meant for
# running on a machine stronger than a laptop: this is single-threaded (no --workers knob to size),
# so what actually helps is a faster single core and enough free RAM that CaDiCaL's search never
# swaps -- the slow tail observed on r2 above n~2500 lined up with the laptop's available memory
# dropping under a few GB (visible via `free -h` while it ran), which is exactly the kind of
# page-fault slowdown a bigger machine avoids, not a sign of runaway/unbounded cost (each restricted
# SAT query is still capped at 60s in FvsSeeded1WLScaleTest.kt).
#
# Usage:
#   scripts/run_fvs_campaign.sh                # r2 only (the default campaign)
#   scripts/run_fvs_campaign.sh r2 z2          # both, one after another
#   scripts/run_fvs_campaign.sh r2 z2 t2 s2 d3 z3   # every family the test supports
#
# RESUMING FROM A PARTIAL RUN (e.g. one started on a laptop and moved here): copy the existing
# results/fvs-seeded-1wl.csv into this checkout BEFORE running -- FvsSeeded1WLScaleTest's own
# alreadyDone() check skips any instance path already present as a row, so it picks up exactly
# where the old run left off, same file, no flags needed. Safe to interrupt (Ctrl-C, machine
# reboot, etc.) at any point -- every row is flushed to disk as soon as that one instance finishes,
# so nothing already written is lost; just run this script again to continue.
#
# Requirements: a JDK 17+, g++, and make (for the vendored CaDiCaL native library -- see README.md
# "Building"; this campaign does NOT need the CryptoMiniSat stack, only CaDiCaL). No network access
# needed once the repo + graphs/ corpus are on the machine -- `./gradlew test` builds everything it
# needs (including the native libraries) as a normal dependency of the test task, so there's no
# separate build step to run first.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

FAMILIES=("$@")
if [ ${#FAMILIES[@]} -eq 0 ]; then
    FAMILIES=("r2")
fi

for family in "${FAMILIES[@]}"; do
    echo "=== FVS-seeded 1-WL campaign: cfi-rigid-$family ==="
    ./gradlew test --tests "dialysis.experimental.FvsSeeded1WLScaleTest.$family" -i \
        2>&1 | tee -a "results/fvs-campaign-$family.log"
done

echo
echo "=== Summary (results/fvs-seeded-1wl.csv) ==="
awk -F, 'NR==1{next} {rows[$1]++; if ($13=="true") matched[$1]++} END{for (f in rows) printf "%-16s %d/%d matched\n", f, matched[f]+0, rows[f]}' results/fvs-seeded-1wl.csv
