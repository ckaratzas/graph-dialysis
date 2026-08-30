#!/usr/bin/env bash
# Builds a SELF-CONTAINED bundle for running the FVS-seeded 1-WL campaign (FVS_SEEDED_1WL_SPEC.md)
# on another machine WITHOUT cloning this repo there -- just the shadow jar (dependencies + native
# CaDiCaL library bundled in, same as scripts/run_benchmark.sh's own jar), the requested families'
# graph files (17-20 MB each, not the full multi-GB corpus), the small `results/` CSV directory
# (ground truth + any existing partial `results/fvs-seeded-1wl.csv` to resume from), and a `run.sh`
# that invokes the standalone CLI (`dialysis.fvs.FvsCampaignRunner`) directly -- no Gradle, no
# source, just a JDK 17+ on the target machine.
#
# Usage (run on THIS machine, where the repo is checked out):
#   scripts/package_fvs_campaign.sh r2
#   scripts/package_fvs_campaign.sh r2 z2
#
# Produces fvs-campaign-<families>.tar.gz in the current directory. Copy it to the target machine
# (scp/rsync/whatever), then there:
#   tar xzf fvs-campaign-*.tar.gz && cd fvs-campaign && ./run.sh
#
# RESUMING a partial campaign (e.g. one already partway through on this laptop): just run this
# script again before copying -- it picks up whatever is currently in results/fvs-seeded-1wl.csv,
# so the bundle already resumes from exactly where this machine's run left off. run.sh's own
# FvsCampaignRunner call skips any instance already present as a row, same as every other
# resumable campaign in this repo.
#
# The bundle is one-way: bring the resulting results/fvs-seeded-1wl.csv (or the whole bundle
# directory, which accumulates it in place) back to this repo's own results/ directory afterward --
# this script does not push results anywhere itself.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

FAMILIES=("$@")
if [ ${#FAMILIES[@]} -eq 0 ]; then
    echo "Usage: $0 <family> [family...]   e.g. $0 r2 z2" >&2
    exit 1
fi

echo "Building shadow jar (dependencies + native CaDiCaL library bundled in)..."
./gradlew shadowJar -q
JAR="$(ls build/libs/*-all.jar | head -1)"

STAGE="$(mktemp -d)/fvs-campaign"
mkdir -p "$STAGE/graphs" "$STAGE/results"
cp "$JAR" "$STAGE/fvs-campaign.jar"
cp -r results/. "$STAGE/results/"

RUN_LINES=()
for family in "${FAMILIES[@]}"; do
    full="cfi-rigid-$family"
    src="graphs/$full"
    if [ ! -d "$src" ]; then
        echo "No such graph family directory: $src" >&2
        exit 1
    fi
    echo "Copying $src ($(du -sh "$src" | cut -f1))..."
    cp -r "$src" "$STAGE/graphs/"
    RUN_LINES+=("java --enable-native-access=ALL-UNNAMED -cp fvs-campaign.jar dialysis.fvs.FvsCampaignRunnerKt --family=$full --graphsDir=graphs/$full --out=results/fvs-seeded-1wl.csv")
done

{
    echo '#!/usr/bin/env bash'
    echo '# Runs the FVS-seeded 1-WL campaign for each family this bundle was packaged with.'
    echo '# Needs only a JDK 17+ -- no Gradle, no source, no network access.'
    echo 'set -euo pipefail'
    echo 'cd "$(dirname "${BASH_SOURCE[0]}")"'
    for line in "${RUN_LINES[@]}"; do
        echo "$line"
    done
} > "$STAGE/run.sh"
chmod +x "$STAGE/run.sh"

OUT_TAR="fvs-campaign-$(IFS=-; echo "${FAMILIES[*]}").tar.gz"
tar czf "$OUT_TAR" -C "$(dirname "$STAGE")" "$(basename "$STAGE")"
rm -rf "$(dirname "$STAGE")"

echo
echo "Wrote $OUT_TAR ($(du -sh "$OUT_TAR" | cut -f1))."
echo "Copy it to the target machine, then: tar xzf $(basename "$OUT_TAR") && cd fvs-campaign && ./run.sh"
