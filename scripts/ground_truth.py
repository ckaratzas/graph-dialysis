#!/usr/bin/env python3
"""Ground-truth automorphism orbit counts via nauty's dreadnaut, run standalone (no JNI, no JVM)
so it's fully isolated from the benchmark process -- one dreadnaut subprocess per graph, killed
outright on timeout OR on exceeding --memoryCapMb (FINAL_MEASUREMENTS_SPEC.md Task 0: Traces has
no internal memory cap and can exhaust RAM on large cfi-rigid instances) -- no risk to anything
else running, and no risk to the machine either.

--subdivide controls whether the SAME bipartite-subdivision transform BenchmarkRunner's
dispatchColouring can build (dialysis.graph.Graph.ensureBipartite: one new vertex per edge iff the
graph isn't already bipartite) is applied before computing orbits:

    auto    (default) subdivide iff the graph isn't already bipartite -- matches
            dispatchColouring's OWN colouring computation, but NOT what it solves on.
    never   never subdivide; orbits are always over the graph's own n vertices.
    always  always subdivide, even if the graph is already bipartite.

IMPORTANT: since FINAL_MEASUREMENTS_SPEC.md Task 1, BenchmarkRunner always SOLVES on the ORIGINAL
graph (subdivision, when dispatchColouring uses one, is confined to choosing a colouring) --
`recovered_orbits` in the benchmark CSV therefore counts orbits over the original n vertices only,
never the subdivision's. Because Aut(sd(G)) === Aut(G), orbits(sd(G)) = orbits_on_V(G) +
orbits_on_E(G) -- strictly MORE than orbits_on_V(G) alone -- so a `true_orbits` computed with
--subdivide=auto or --subdivide=always on a graph that actually gets subdivided is NOT the same
count as `recovered_orbits` and will register as a spurious DISAGREEMENT in
scripts/merge_ground_truth.py even when the method is correct. Use --subdivide=never to match
`recovered_orbits`'s own domain; `gt_subdivided` in the output records which happened per row so
this is visible after the fact regardless of which mode produced a given CSV.

Usage:
    python3 scripts/ground_truth.py --family=cfi-rigid-d3 --maxVertices=1000 --out=results/gt-d3.csv
    python3 scripts/ground_truth.py --family=all --timeoutSec=300 --memoryCapMb=4096 --out=results/gt-all.csv
    python3 scripts/ground_truth.py --family=all --subdivide=never --out=results/gt-all-nosub.csv

Run this SERIALLY, never alongside the method's own parallel SAT workers (FINAL_MEASUREMENTS_SPEC.md
Task 0) -- it's isolated from THAT process, not from competing with it for the same machine's RAM.

Same conventions as the benchmark CLI: resumable (skips instances already present as a row in
--out), one row flushed per instance so a killed run loses nothing already written.
"""
import argparse
import csv
import itertools
import os
import resource
import shutil
import signal
import subprocess
import sys
import time
from pathlib import Path
from types import SimpleNamespace

REPO_ROOT = Path(__file__).resolve().parent.parent
# gt_timed_out is kept (not replaced by gt_outcome) so scripts/merge_ground_truth.py's existing
# `gt["gt_timed_out"] != "false"` check keeps working unmodified -- FINAL_MEASUREMENTS_SPEC.md Task 0
# adds gt_outcome/gt_tool/gt_memory_cap_mb/gt_timeout_s alongside it, not instead of it.
# gt_subdivided records whether THIS instance was actually subdivided before Traces ran --
# BenchmarkRunner's own `recovered_orbits` (FINAL_MEASUREMENTS_SPEC.md Task 1) is always over the
# ORIGINAL graph's vertices only, never the subdivision, so a row where gt_subdivided is true is
# comparing recovered_orbits against an orbit count that also includes the subdivision's edge
# orbits -- NOT the same count. Matching --subdivide=never on both sides of a comparison keeps them
# apples-to-apples; see this script's own module doc and --subdivide's help text.
HEADER = [
    "family", "instance", "n", "m", "n_effective", "true_orbits", "gt_ms", "gt_timed_out",
    "gt_outcome", "gt_tool", "gt_memory_cap_mb", "gt_timeout_s", "gt_subdivided",
]

# Signals a memory-starved process plausibly dies from: SIGKILL (9, the cgroup/systemd-run
# enforcement path -- confirmed empirically to surface as subprocess returncode -9) and SIGSEGV/
# SIGABRT/SIGBUS (11/6/7, how an RLIMIT_AS-induced malloc failure typically surfaces in a C program
# that doesn't check malloc's return value, which is nauty/Traces' style -- confirmed empirically
# with a throwaway unchecked-malloc reproduction: RLIMIT_AS produced returncode -11). Not a proof
# any given crash was memory-caused, but the best available signal-level classification -- see
# Task 0's own exit-code table in FINAL_MEASUREMENTS_SPEC.md.
OOM_SIGNALS = {9, 11, 6, 7}


def load_dimacs(path):
    n = 0
    edges = []
    with open(REPO_ROOT / path) as f:
        for line in f:
            parts = line.split()
            if not parts:
                continue
            if parts[0] == "p":
                n = int(parts[2])
            elif parts[0] == "e":
                edges.append((int(parts[1]) - 1, int(parts[2]) - 1))
    return n, edges


def build_adjacency(n, edges):
    adj = [set() for _ in range(n)]
    for u, v in edges:
        adj[u].add(v)
        adj[v].add(u)
    return [sorted(s) for s in adj]


def is_bipartite(adj):
    n = len(adj)
    side = [-1] * n
    for start in range(n):
        if side[start] != -1:
            continue
        side[start] = 0
        queue = [start]
        while queue:
            v = queue.pop()
            for w in adj[v]:
                if side[w] == -1:
                    side[w] = 1 - side[v]
                    queue.append(w)
                elif side[w] == side[v]:
                    return False
    return True


def subdivide(n, edges):
    """One new vertex per edge (u < v), in ascending (u, v) order -- matches
    dialysis.graph.Graph.subdivided() exactly, so vertex ids correspond 1:1."""
    ordered_edges = sorted((u, v) if u < v else (v, u) for u, v in edges)
    new_n = n + len(ordered_edges)
    new_edges = []
    for i, (u, v) in enumerate(ordered_edges):
        w = n + i
        new_edges.append((u, w))
        new_edges.append((w, v))
    return new_n, new_edges


def apply_subdivide_mode(n, edges, mode):
    """Returns (n_effective, edges_effective, subdivided) per --subdivide's mode -- see this
    module's own doc for why `never` is what matches `recovered_orbits`'s domain."""
    if mode == "never":
        return n, edges, False
    if mode == "always":
        return subdivide(n, edges) + (True,)
    adj = build_adjacency(n, edges)
    if is_bipartite(adj):
        return n, edges, False
    return subdivide(n, edges) + (True,)


def dreadnaut_input(n, adj):
    """dreadnaut's `g` command: bare numbers add an edge from the CURRENT vertex; `;` advances to
    the next vertex; `N:` would JUMP to vertex N instead (not what we want here) -- so each
    vertex's line lists only neighbours greater than itself, letting symmetric closure cover the
    rest, and plain `;` to advance. `At` switches to Traces (this graph family is specifically
    built to defeat plain dense nauty refinement -- Traces is the algorithm this project's own
    ground-truth path actually uses). Verified against known automorphism group orders (triangle=6,
    path4=2, two disjoint triangles=72) before trusting this format.

    IMPORTANT: `;` after the LAST vertex would advance the current vertex past n-1, which itself
    ends `g` mode ("too high for a vertex label, stop") -- a trailing `.` after that lands outside
    `g` mode entirely and is rejected as an illegal top-level command. So `;` goes BETWEEN vertices
    only; the final vertex's list is followed directly by `.`.
    """
    parts = ["At", f"n={n} g"]
    for v in range(n):
        forward = [w for w in adj[v] if w > v]
        parts.append(" ".join(str(w) for w in forward))
        if v < n - 1:
            parts.append(";")
    parts.append(". x q")
    return " ".join(parts) + "\n"


_unit_counter = itertools.count()

# Extra seconds added on top of --timeoutSec for the systemd scope's OWN RuntimeMaxSec -- a
# backstop, not the primary timeout mechanism (see run_dreadnaut's doc for why: killing
# systemd-run's own PID via Python's subprocess timeout does NOT reliably kill everything in its
# cgroup, confirmed empirically with a throwaway multi-process reproduction, though today's
# single-process dreadnaut is unaffected by that specific gap). Large enough that this should
# never actually fire in normal operation -- Python's own `timeout=` always elapses first.
RUNTIME_MAX_BUFFER_SEC = 30


def _rlimit_as_preexec(mem_bytes):
    """RLIMIT_AS fallback (same mechanism as `ulimit -v`, in KB) for when systemd-run isn't on
    PATH. Applied via preexec_fn, so it takes effect in the CHILD only, after fork and before
    exec -- never touches this script's own process."""
    def _set():
        resource.setrlimit(resource.RLIMIT_AS, (mem_bytes, mem_bytes))
    return _set


def systemd_user_available():
    """`shutil.which("systemd-run")` only proves the BINARY is on PATH, not that `--user` actually
    works -- that additionally needs a running user systemd instance + D-Bus session (XDG_RUNTIME_DIR
    etc.), which a headless/minimal VM commonly does not have even with systemd itself installed.
    Trusting `which` alone there means EVERY run_dreadnaut call fails identically and immediately
    with systemd-run's own error (observed: `ERROR:rc=1`, dreadnaut never actually invoked) instead
    of running at all -- confirmed by probing once with a trivial no-op scope up front, here, so a
    VM without a working --user session falls back to RLIMIT_AS instead of failing every instance."""
    if shutil.which("systemd-run") is None:
        return False
    probe = subprocess.run(
        ["systemd-run", "--scope", "--user", "--quiet", "true"],
        capture_output=True, text=True,
    )
    if probe.returncode != 0:
        detail = (probe.stderr or probe.stdout or "").strip().splitlines()
        print(
            "systemd-run --user probe failed (rc={}): {} -- falling back to RLIMIT_AS (ulimit -v) "
            "for memory containment this run.".format(
                probe.returncode, detail[0] if detail else "(no output)",
            ),
        )
        return False
    return True


def run_dreadnaut(dreadnaut_path, n, adj, timeout_sec, memory_cap_mb, use_systemd):
    """Runs dreadnaut (which switches into Traces via the `At` command -- see dreadnaut_input)
    under a hard memory cap: Traces has no internal memory cap and can exhaust RAM on large
    cfi-rigid instances (FINAL_MEASUREMENTS_SPEC.md Task 0) -- this makes the kernel/cgroup kill
    the subprocess instead of letting it take down the machine. Prefers systemd-run's cgroup
    enforcement (clean SIGKILL, no swap thrash); falls back to RLIMIT_AS when [use_systemd] is
    False (determined once, up front, by [systemd_user_available] -- not re-probed per call).
    Both were verified directly (a throwaway unchecked-malloc C reproduction) to surface to Python
    as a NEGATIVE `subprocess` returncode (-9 under systemd-run, -11 under RLIMIT_AS) -- NOT the
    shell's 128+signal convention (that's what a shell wrapping the same exit sees, not what
    Python's subprocess module reports directly).

    On timeout, the WHOLE process tree is torn down, not just the one pid `subprocess` tracks --
    confirmed empirically that plain `Popen.kill()` (what `subprocess.run(timeout=...)` uses
    internally) only reaches the ONE pid it was called on: a throwaway multi-process reproduction
    (a shell script forking a child) left the child running indefinitely after Python's timeout
    "fired and returned". Today's dreadnaut is a single process so that gap wouldn't bite it
    directly, but Task 0 exists specifically to make this path trustworthy against processes we
    don't fully control, so it doesn't lean on that staying true. Two independent mechanisms cover
    it: `start_new_session=True` puts the child in its own process GROUP so `os.killpg` reaches any
    children it forks (covers the RLIMIT_AS path), and the systemd-run path additionally gets an
    explicit --unit name so its whole cgroup can be torn down via `systemctl ... kill` (covers a
    child that escapes the process group, e.g. by calling setsid() itself).

    Returns (orbits_or_None, ms, outcome) where outcome is one of "OK", "TIMEOUT", "MEMOUT", "ERROR".
    """
    mem_bytes = memory_cap_mb * 1024 * 1024
    stdin_data = dreadnaut_input(n, adj)
    unit_name = None

    if use_systemd:
        unit_name = f"dialysis-gt-{os.getpid()}-{next(_unit_counter)}"
        cmd = [
            "systemd-run", "--scope", "--user", "--quiet", f"--unit={unit_name}",
            "-p", f"MemoryMax={memory_cap_mb}M", "-p", "MemorySwapMax=0",
            "-p", f"RuntimeMaxSec={timeout_sec + RUNTIME_MAX_BUFFER_SEC}",
            dreadnaut_path,
        ]
        popen_kwargs = {}
    else:
        cmd = [dreadnaut_path]
        popen_kwargs = {"preexec_fn": _rlimit_as_preexec(mem_bytes)}

    t0 = time.time()
    proc = subprocess.Popen(
        cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        start_new_session=True, **popen_kwargs,
    )
    try:
        stdout, stderr = proc.communicate(input=stdin_data, timeout=timeout_sec)
        returncode = proc.returncode
    except subprocess.TimeoutExpired:
        if unit_name is not None:
            # Best-effort immediate teardown of the WHOLE cgroup -- ignore failures (e.g. it
            # already exited on its own between the timeout firing and this call).
            subprocess.run(
                ["systemctl", "--user", "kill", "--signal=SIGKILL", f"{unit_name}.scope"],
                capture_output=True,
            )
        else:
            try:
                os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
            except ProcessLookupError:
                pass  # already gone
        proc.wait()
        return None, (time.time() - t0) * 1000, "TIMEOUT"
    ms = (time.time() - t0) * 1000
    result = SimpleNamespace(returncode=returncode, stdout=stdout)

    if result.returncode != 0:
        if result.returncode < 0 and -result.returncode in OOM_SIGNALS:
            return None, ms, "MEMOUT"
        # stderr isn't put in the CSV (outcome stays a short fixed-ish string other tooling greps
        # for) but IS printed here -- an ERROR with no visible cause is undiagnosable, which is
        # exactly what happened with the untrapped systemd-run --user failure this replaced.
        detail = (stderr or "").strip().splitlines()
        if detail:
            print(f"  dreadnaut/systemd-run stderr: {detail[0]}")
        return None, ms, f"ERROR:rc={result.returncode}"

    for line in reversed(result.stdout.splitlines()):
        if "grpsize=" in line:
            orbits = int(line.strip().split(" orbit")[0])
            return orbits, ms, "OK"
    return None, ms, "ERROR:no_grpsize_line"


def already_done(out_path):
    if not os.path.exists(out_path):
        return set()
    with open(out_path) as f:
        reader = csv.DictReader(f)
        existing_header = reader.fieldnames
        if existing_header is not None and existing_header != HEADER:
            sys.exit(
                f"{out_path} has an old/different column schema {existing_header} -- this version's "
                f"HEADER is {HEADER} (adds gt_outcome/gt_tool/gt_memory_cap_mb/gt_timeout_s for "
                f"FINAL_MEASUREMENTS_SPEC.md Task 0, and gt_subdivided for --subdivide). Appending "
                f"would produce a ragged CSV -- start a fresh --out path instead of resuming this one."
            )
        return {row["instance"] for row in reader}


def list_instances(family):
    """Relative paths ("graphs/<family>/<file>"), matching BenchmarkRunner's CSV `instance`
    column exactly -- the merge script joins on this, so the formats must agree."""
    d = REPO_ROOT / "graphs" / family
    return sorted(f"graphs/{family}/{p.name}" for p in d.iterdir() if p.is_file())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--family", required=True, help="family name (graphs/ subdirectory), comma list, or 'all'")
    ap.add_argument("--maxVertices", type=int, default=3000, help="skip instances whose effective (post-subdivision) vertex count exceeds this")
    ap.add_argument("--timeoutSec", type=int, default=300)
    ap.add_argument("--memoryCapMb", type=int, default=4096, help="hard memory cap per dreadnaut/Traces subprocess -- FINAL_MEASUREMENTS_SPEC.md Task 0")
    ap.add_argument("--dreadnaut", default=str(REPO_ROOT / "tools" / "nauty" / "dreadnaut"))
    ap.add_argument("--out", required=True)
    ap.add_argument(
        "--subdivide", choices=("auto", "never", "always"), default="auto",
        help="auto (default): subdivide iff not bipartite, matching dispatchColouring's colouring "
             "computation. never: always use the graph as-is -- matches what recovered_orbits "
             "actually counts since FINAL_MEASUREMENTS_SPEC.md Task 1 (see this module's own doc). "
             "always: force subdivision even on an already-bipartite graph.",
    )
    args = ap.parse_args()

    use_systemd = systemd_user_available()
    containment = "systemd-run" if use_systemd else "RLIMIT_AS (ulimit -v fallback)"
    print(f"Memory containment: {containment}, cap={args.memoryCapMb}MB")
    print(f"Subdivide mode: {args.subdivide}")

    if not os.path.isfile(args.dreadnaut):
        sys.exit(f"dreadnaut binary not found at {args.dreadnaut} -- build nauty and place it there first")

    families = sorted(p.name for p in (REPO_ROOT / "graphs").iterdir() if p.is_dir()) if args.family == "all" else args.family.split(",")

    out_path = args.out
    os.makedirs(os.path.dirname(out_path) or ".", exist_ok=True)
    done = already_done(out_path)
    resuming = bool(done)
    if resuming:
        print(f"Resuming: {len(done)} instance(s) already in {out_path}, skipping those.")

    mode = "a" if resuming else "w"
    with open(out_path, mode, newline="") as f:
        writer = csv.writer(f)
        if not resuming:
            writer.writerow(HEADER)
        for family in families:
            for path in list_instances(family):
                if path in done:
                    print(f"SKIP (already done): {path}")
                    continue
                try:
                    n, edges = load_dimacs(path)
                    n_eff, edges_eff, subdivided = apply_subdivide_mode(n, edges, args.subdivide)
                    if n_eff > args.maxVertices:
                        print(f"SKIP (vertex cap): {path} (n_effective={n_eff})")
                        continue
                    adj = build_adjacency(n_eff, edges_eff)
                    print(f"STARTING: [{family}] {path} n={n} n_effective={n_eff} subdivided={subdivided}")
                    orbits, ms, outcome = run_dreadnaut(args.dreadnaut, n_eff, adj, args.timeoutSec, args.memoryCapMb, use_systemd)
                    # gt_timed_out kept boolean and TRUE ONLY for actual timeouts (not MEMOUT/ERROR)
                    # -- see HEADER's comment on why this legacy column stays exactly as before.
                    gt_timed_out = "true" if outcome == "TIMEOUT" else "false"
                    row = [
                        family, path, n, len(edges), n_eff, orbits if orbits is not None else "", f"{ms:.0f}", gt_timed_out,
                        outcome, "traces", args.memoryCapMb, args.timeoutSec, "true" if subdivided else "false",
                    ]
                    if orbits is not None:
                        print(f"  -> true_orbits={orbits} ms={ms:.0f}")
                    else:
                        print(f"  {outcome} at {ms:.0f}ms")
                    writer.writerow(row)
                    f.flush()
                except Exception as e:
                    writer.writerow([family, path, "", "", "", "", "", "false", f"ERROR:{e}", "traces", args.memoryCapMb, args.timeoutSec, ""])
                    f.flush()
                    print(f"FAILED: {path} -- {e}")

    print(f"Results written to {out_path}")


if __name__ == "__main__":
    main()