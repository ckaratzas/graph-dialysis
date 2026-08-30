#!/usr/bin/env bash
# Runs the standalone BenchmarkRunner CLI (dialysis.benchmark) via the self-contained fat jar
# (dependencies + all five vendored native libraries bundled in, see build.gradle.kts's shadowJar
# config) -- meant for a long, unattended campaign run on a benchmark machine. See README.md
# "Running the benchmark CLI" and BENCHMARK_SPEC.md for what the arguments mean.
#
# Usage:
#   scripts/run_benchmark.sh                                    # full campaign, defaults below
#   scripts/run_benchmark.sh --family=cfi-rigid-d3 --maxVertices=1000
#   scripts/run_benchmark.sh --workers=8 --out=results/custom.csv --repeats=3
#   scripts/run_benchmark.sh --preset=r2-xor                    # see below
#
# Any --key=value argument overrides the corresponding default; everything else (--repeats, --seed,
# --timeoutMs, --shortMs, --dmax, --anchorK, --edgeClauseThreshold, --noSubdivision) is passed
# straight through to BenchmarkRunner, which has its own defaults for all of those.
#
# --preset=r2-xor sets --family=cfi-rigid-r2 --solver=CRYPTOMINISAT_XOR --maxVertices=3600
# --out=results/r2-xor.csv -- the one gadget-XOR configuration GADGET_XOR_SPEC.md validated as an
# actual, unqualified win (sound at every size tested, up to 42.6x/140x faster than CaDiCaL at
# n>=2448). This is the ONLY family the preset covers: t2 also runs under --solver=CRYPTOMINISAT_XOR
# (same CLI, GADGET_XOR_SUPPORTED_FAMILIES accepts it) but is NOT a preset here on purpose --
# GADGET_XOR_SPEC.md's Part 4 found it sound yet consistently slower than plain CaDiCaL (plain
# CryptoMiniSat's own CDCL search, independent of the gadget-XOR clause entirely, is ~8x slower than
# CaDiCaL on t2's denser bypassed structure), so there is no size at which recommending it as the
# default t2 choice would be honest. Any explicit --family/--solver/--maxVertices/--out passed
# alongside --preset=r2-xor overrides that preset's own value for just that flag.
#
# --noSubdivision=true forces plain 1-WL on every non-bipartite instance, skipping the subdivision +
# initial-phase comparison dispatchColouring otherwise runs -- faster for a family where that
# comparison always resolves to 1-WL anyway, and puts every family on the same never-subdivided
# basis for an apples-to-apples comparison. See BENCHMARK_SPEC.md and FINAL_MEASUREMENTS_SPEC.md
# Task 1.
#
# Re-running with the same --out RESUMES: instances already present as a row are skipped, so a run
# that dies partway through loses nothing already written.
#
# JAVA_OPTS (env var, not a --flag) is inserted before -jar, for JVM system properties -- e.g.
# `JAVA_OPTS="-Ddialysis.tmpDir=/data/tmp" scripts/run_benchmark.sh ...` if the OS default temp
# directory is too small for a large subdivided graph's decomposition scratch file (see
# dialysis.util.dialysisTempFile's own doc: it's O(n^2) in the subdivided vertex count and can
# exceed a small java.io.tmpdir partition well before the machine's actual disk fills).
#
# This script does NOT build the jar itself -- it picks up whatever already exists under
# build/libs/*-all.jar, so run `./gradlew shadowJar` first (or after changing any source). Deploying
# to a remote VM: copy that one jar over (no repo, no Gradle, no rebuild needed there -- just a JDK)
# and run it directly:
#   java --enable-native-access=ALL-UNNAMED -jar graph-dialysis-1.0-all.jar --family=all --workers=16 --out=results/full.csv
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
WORKDIR="$PWD"
# Resolved to an absolute path up front, in OUR unprivileged shell's PATH -- `sudo` (the last-resort
# containment tier below) resets PATH to its own secure_path by default, which can easily not include
# a user-local JDK install (sdkman/jenv/asdf-managed java isn't under /usr/bin), silently breaking
# `java` lookup inside the sudo'd command with no obvious connection to PATH at all. Hardcoding the
# absolute path here sidesteps that regardless of which containment tier ends up used.
JAVA_BIN="$(command -v java)"

# Memory containment -- this invocation runs a raw `java` process (no Gradle daemon in the way, so
# unlike `./gradlew` it CAN actually be wrapped by an external cgroup, see
# feedback-avoid-singleinstanceexploration-test's "Critical correction" for why that's not true of
# `./gradlew`-invoked JVMs). There is otherwise NO memory bound on this process at all: no -Xmx
# (unlike tasks.test/tasks.run in build.gradle.kts), and the native CaDiCaL/CryptoMiniSat JNI
# libraries can allocate unbounded off-heap memory on a clause-count explosion (had-20 style, see
# feedback-avoid-singleinstanceexploration-test) that no JVM heap flag would catch anyway. That
# combination is what "crashed the laptop" running this script directly -- a runaway here had no
# ceiling and could consume all RAM+swap, freezing the whole desktop session (IDE, browser,
# everything), not just this job. Reusing the same systemd-run cgroup pattern scripts/ground_truth.py
# already validated (generous cap + MemorySwapMax=0 so a runaway gets a clean SIGKILL inside its own
# scope instead of thrashing swap to death) so only THIS job's scope gets killed if it blows past the
# cap -- the rest of the session is untouched.
#
# BENCHMARK_MEMORY_CAP_MB overrides the default cap; BENCHMARK_NO_MEMCAP=1 disables containment
# entirely (e.g. deliberately running on a real benchmark machine, not this laptop -- see this
# script's own top-of-file comment on intended usage).
#
# No `ulimit -v` (RLIMIT_AS) fallback here, unlike ground_truth.py's dreadnaut wrapper -- confirmed
# unusable for a JVM: RLIMIT_AS caps reserved VIRTUAL address space, not physical (RSS+swap) usage,
# and a JVM reserves address space for things it never commits (a bare `java -jar` reserves 1GiB for
# compressed class space alone, plus heap/metaspace/thread-stack reservations on top) regardless of
# actual need -- a 2048M cap failed with "Could not allocate compressed class space: 1073741824
# bytes" before the JVM even started running any dialysis code. Even a looser cap that let it start
# would still not bound actual physical memory the way a cgroup does, which is the entire point --
# native CaDiCaL/CryptoMiniSat allocations aren't JVM heap and RLIMIT_AS wouldn't catch a physical
# blowup there either. So when systemd-run --user isn't available, this refuses to run rather than
# pretend a broken mechanism is containing anything.
# `systemd-run --user` needs a D-Bus SESSION bus (needs dbus-user-session installed + socket
# activated), not just the delegated cgroup itself -- a headless benchmark box commonly gets
# `user@<uid>.service` (and its delegated cgroup) from a plain SSH login via pam_systemd, but NOT a
# session bus, since that's a separate piece nothing here requires otherwise. So this tries
# systemd-run first (works on a normal desktop session, e.g. this laptop) and falls back to writing
# the delegated cgroup v2 hierarchy directly (no D-Bus involved at all) when that's unavailable.
# Both mechanisms were verified end-to-end: a JVM starts fine under either (unlike a `ulimit -v`
# RLIMIT_AS cap, which fails before the JVM even finishes booting -- see the removed fallback this
# replaced, further down in git history) and a genuine runaway gets a clean SIGKILL confined to the
# cgroup, with zero effect on anything else running on the machine.
#
# The cgroup cap alone isn't enough, though: modern JDKs are cgroup-aware and default MaxHeapSize to
# `-XX:MaxRAMPercentage=25.0` OF THE CGROUP LIMIT, not physical RAM (confirmed directly:
# `-XX:+PrintFlagsFinal` under a 4096M-capped scope reports MaxHeapSize=1073741824, i.e. exactly
# 1024M/25% -- matches "OutOfMemoryError: Java heap space" with `top` never showing past ~1.3G against
# a 4G cap exactly). No `-Xmx` here previously (unlike tasks.test/tasks.run in build.gradle.kts, which
# both set maxHeapSize explicitly) meant the cgroup cap silently starved the JVM's OWN heap far below
# what was actually intended, on top of leaving room for native CaDiCaL/CryptoMiniSat memory. Sizing
# -Xmx as a fraction of the cgroup cap (not all of it) explicitly, leaving the rest for native
# allocations, metaspace, and thread stacks, which don't count against -Xmx but DO count against the
# cgroup's memory.max.
#
# 4096M is a deliberate fixed budget (matches what he gives Traces elsewhere, for an apples-to-apples
# comparison) -- not something to raise speculatively when an instance OOMs. The 75% split WAS too
# conservative though: rnd-3-reg-10000-1 died with `top` showing ~3.5G total RSS against a 3072M
# (75%) heap ceiling -- since this crashed with a genuine Java heap OOM before any native
# CaDiCaL/CryptoMiniSat solving even started (STARTING: -> immediate FAILED), that ~400-500M gap is
# pure JVM overhead (metaspace/threads/GC/code cache), well under the 1024M (25%) that was reserved.
# 85% leaves ~600M of headroom instead -- more realistic breathing room above what was actually
# observed, while still leaving room for native solver memory if an instance gets far enough to reach
# one. If an instance still OOMs at 85%, that's a real signal it doesn't fit in this budget even
# maximally tuned, not something to keep chasing with a bigger split.
MEMORY_CAP_MB="${BENCHMARK_MEMORY_CAP_MB:-4096}"
JVM_HEAP_MB="${BENCHMARK_JVM_HEAP_MB:-$((MEMORY_CAP_MB * 85 / 100))}"
MEMCAP_WRAPPER=()
# Left empty (JVM falls back to its own ergonomic default, i.e. 25% of PHYSICAL RAM) when
# BENCHMARK_NO_MEMCAP=1 -- an explicit opt-out of the cgroup cap should also opt out of a heap size
# that was only ever meant to fit inside it.
JVM_XMX_ARGS=()
if [ "${BENCHMARK_NO_MEMCAP:-0}" != "1" ]; then
    JVM_XMX_ARGS=("-Xmx${JVM_HEAP_MB}m")
    # Some terminals (a tmux/screen shell started before the graphical session's env was set, an
    # su/sudo shell, etc.) don't inherit XDG_RUNTIME_DIR/DBUS_SESSION_BUS_ADDRESS even though a
    # perfectly good user systemd + D-Bus session is running -- `systemd-run --user` then fails with
    # "$DBUS_SESSION_BUS_ADDRESS and $XDG_RUNTIME_DIR not defined" even though nothing is actually
    # broken. Both are deterministic for a standard systemd user session (verified against the real
    # socket here), so default them instead of failing on an environment gap that has nothing to do
    # with whether containment is actually available. Harmless to set even when there really is no
    # session bus (e.g. the headless case) -- the probe below just fails cleanly instead.
    : "${XDG_RUNTIME_DIR:=/run/user/$(id -u)}"
    : "${DBUS_SESSION_BUS_ADDRESS:=unix:path=${XDG_RUNTIME_DIR}/bus}"
    export XDG_RUNTIME_DIR DBUS_SESSION_BUS_ADDRESS

    SYSTEMD_RUN_OK=0
    if command -v systemd-run >/dev/null 2>&1; then
        if probe_err="$(systemd-run --scope --user --quiet true 2>&1 >/dev/null)"; then
            SYSTEMD_RUN_OK=1
        else
            echo "systemd-run --user unavailable (${probe_err:-no output}) -- trying a delegated cgroup v2 directly." >&2
        fi
    fi

    if [ "$SYSTEMD_RUN_OK" = 1 ]; then
        MEMCAP_WRAPPER=(systemd-run --scope --user --quiet \
            "--unit=dialysis-bench-$$-$(date +%s)" \
            -p "MemoryMax=${MEMORY_CAP_MB}M" -p "MemorySwapMax=0" \
            "--working-directory=${WORKDIR}" --)
    else
        # A hardcoded /sys/fs/cgroup/user.slice/user-<uid>.slice/user@<uid>.service guess only holds
        # for a normal systemd login session -- it doesn't exist for root (no login session, hence
        # `sudo`ing this script makes things WORSE, not better: don't run this under sudo) and doesn't
        # exist at all on a container/cloud box with no systemd session manager. Instead, discover
        # whatever cgroup THIS shell is actually already in (from /proc/self/cgroup) and walk upward
        # looking for the deepest ancestor that's actually writable with the memory controller enabled
        # for children -- e.g. on this laptop /proc/self/cgroup's own leaf (.../app.slice/dbus.service)
        # rejects a child's memory.max with Permission denied even though 2 levels up
        # (user@1000.service) works, confirmed directly. This makes no assumption about systemd,
        # containers, or uid at all -- it just uses wherever we're actually allowed to write.
        CGROUP_BASE=""
        if [ -f /sys/fs/cgroup/cgroup.controllers ]; then
            cg_rel="$(awk -F: '$1=="0"{print $3}' /proc/self/cgroup 2>/dev/null)"
            cg_path="/sys/fs/cgroup${cg_rel}"
            while [ -n "$cg_rel" ] && [ "$cg_path" != "/sys/fs/cgroup" ] && [ "$cg_path" != "/" ]; do
                if [ -d "$cg_path" ]; then
                    cg_probe="$cg_path/.dialysis-cgroup-probe-$$"
                    if mkdir "$cg_probe" 2>/dev/null; then
                        if (exec 2>/dev/null; echo max > "$cg_probe/memory.max"); then
                            rmdir "$cg_probe" 2>/dev/null
                            CGROUP_BASE="$cg_path"
                            break
                        fi
                        rmdir "$cg_probe" 2>/dev/null
                    fi
                fi
                cg_path="$(dirname "$cg_path")"
            done
        fi
        CG="${CGROUP_BASE:+$CGROUP_BASE/dialysis-bench-$$}"
        if [ -n "$CGROUP_BASE" ] && \
           mkdir "$CG" 2>/dev/null && \
           echo "${MEMORY_CAP_MB}M" > "$CG/memory.max" 2>/dev/null && \
           echo 0 > "$CG/memory.swap.max" 2>/dev/null && \
           echo $$ > "$CG/cgroup.procs" 2>/dev/null; then
            echo "No D-Bus session -- contained via delegated cgroup v2 at $CG instead (cap ${MEMORY_CAP_MB}M)." >&2
        elif command -v sudo >/dev/null 2>&1 && { sudo -n true 2>/dev/null || sudo -v; }; then
            # Last resort: some boxes (this one, per its shell's own cgroup being
            # /system.slice/ssh.service -- sshd never creates a per-login session, so there's no
            # delegated cgroup ANYWHERE in an unprivileged user's process tree, root included) give an
            # unprivileged user no cgroup delegation at all. `sudo systemd-run` in SYSTEM mode (no
            # --user) sidesteps that entirely -- it talks to the always-present system D-Bus, needs no
            # login session or delegation, and --uid=/--gid= run the actual java process back as YOU,
            # not root, so output files stay yours. This is the *correct* way to bring sudo into this,
            # unlike sudo-ing the whole script (tried earlier): that runs AS root throughout, and root
            # has no session-based delegation either -- confirmed by the earlier
            # user-0.slice/user@0.service failure.
            [ -n "$CG" ] && rmdir "$CG" 2>/dev/null
            MEMCAP_WRAPPER=(sudo systemd-run --scope --quiet \
                "--unit=dialysis-bench-$$-$(date +%s)" \
                -p "MemoryMax=${MEMORY_CAP_MB}M" -p "MemorySwapMax=0" \
                "--working-directory=${WORKDIR}" \
                --uid="$(id -u)" --gid="$(id -g)" --)
            echo "No delegated cgroup -- contained via 'sudo systemd-run' (system mode) instead (cap ${MEMORY_CAP_MB}M)." >&2
        else
            [ -n "$CG" ] && rmdir "$CG" 2>/dev/null || true
            echo "Neither systemd-run --user, any writable delegated cgroup ancestor of $(awk -F: '$1=="0"{print $3}' /proc/self/cgroup 2>/dev/null || echo '(unknown)'), nor sudo is available" >&2
            echo "-- refusing to run without memory containment." >&2
            echo "Set BENCHMARK_NO_MEMCAP=1 to run uncontained anyway (only on a real benchmark machine)." >&2
            exit 1
        fi
    fi
fi

FAMILY="all"
MAX_VERTICES="2000"
WORKERS="$(nproc)"
CONFIG="PI_DIST"
OUT="results/full-campaign.csv"
SOLVER=""

# Presets apply their defaults FIRST so an explicit flag later in "$@" (handled by the loop below)
# still overrides them -- order in the case statement below doesn't matter, only that this scan
# runs before the main loop.
for arg in "$@"; do
    case "$arg" in
        --preset=r2-xor)
            FAMILY="cfi-rigid-r2"
            SOLVER="CRYPTOMINISAT_XOR"
            MAX_VERTICES="3600"
            OUT="results/r2-xor.csv"
            ;;
        --preset=*)
            echo "Unknown --preset=${arg#*=} -- only r2-xor is defined" >&2
            exit 1
            ;;
    esac
done

# Pull the flags above out of the caller's arguments so they override the defaults instead of
# being passed twice (BenchmarkRunner would reject a repeated --family/--workers/etc.). --preset
# itself is consumed here too (BenchmarkRunner has no such flag) rather than falling into
# PASSTHROUGH.
PASSTHROUGH=()
for arg in "$@"; do
    case "$arg" in
        --family=*)      FAMILY="${arg#*=}" ;;
        --maxVertices=*) MAX_VERTICES="${arg#*=}" ;;
        --workers=*)     WORKERS="${arg#*=}" ;;
        --config=*)      CONFIG="${arg#*=}" ;;
        --out=*)         OUT="${arg#*=}" ;;
        --solver=*)      SOLVER="${arg#*=}" ;;
        --preset=*)      ;; # already applied above
        *)               PASSTHROUGH+=("$arg") ;;
    esac
done

mkdir -p "$(dirname "$OUT")"

JAR="$(ls build/libs/*-all.jar | head -1)"

SOLVER_ARGS=()
[ -n "$SOLVER" ] && SOLVER_ARGS=(--solver="$SOLVER")

echo "Running: ${MEMCAP_WRAPPER[*]:-} $JAVA_BIN ${JVM_XMX_ARGS[*]:-} ${JAVA_OPTS:-} -jar $JAR --family=$FAMILY --maxVertices=$MAX_VERTICES --workers=$WORKERS --config=$CONFIG --out=$OUT ${SOLVER_ARGS[*]:-} ${PASSTHROUGH[*]}"
exec "${MEMCAP_WRAPPER[@]}" "$JAVA_BIN" --enable-native-access=ALL-UNNAMED "${JVM_XMX_ARGS[@]}" ${JAVA_OPTS:-} -jar "$JAR" \
    --family="$FAMILY" \
    --maxVertices="$MAX_VERTICES" \
    --workers="$WORKERS" \
    --config="$CONFIG" \
    --out="$OUT" \
    "${SOLVER_ARGS[@]}" \
    "${PASSTHROUGH[@]}"