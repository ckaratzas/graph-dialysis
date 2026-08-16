#!/usr/bin/env python3
"""Ground-truth automorphism orbit counts via nauty's dreadnaut, run standalone (no JNI, no JVM)
so it's fully isolated from the benchmark process -- one dreadnaut subprocess per graph, killed
outright on timeout with no risk to anything else running.

Applies the SAME bipartite-subdivision transform BenchmarkRunner does (dialysis.graph.Graph.
ensureBipartite: subdivide one new vertex per edge iff the graph isn't already bipartite) before
computing orbits, so the vertex count and orbit count here correspond to the exact same graph the
SAT/CaDiCaL pipeline analyzed -- ground truth on the original, unsubdivided graph would not be
comparable to `recovered_orbits` in the benchmark CSV.

Usage:
    python3 scripts/ground_truth.py --family=cfi-rigid-d3 --maxVertices=1000 --out=results/gt-d3.csv
    python3 scripts/ground_truth.py --family=all --timeoutSec=300 --out=results/gt-all.csv

Same conventions as the benchmark CLI: resumable (skips instances already present as a row in
--out), one row flushed per instance so a killed run loses nothing already written.
"""
import argparse
import csv
import os
import subprocess
import sys
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
HEADER = ["family", "instance", "n", "m", "n_effective", "true_orbits", "gt_ms", "gt_timed_out"]


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


def ensure_bipartite(n, edges):
    adj = build_adjacency(n, edges)
    if is_bipartite(adj):
        return n, edges
    return subdivide(n, edges)


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


def run_dreadnaut(dreadnaut_path, n, adj, timeout_sec):
    t0 = time.time()
    try:
        result = subprocess.run(
            [dreadnaut_path], input=dreadnaut_input(n, adj), capture_output=True, text=True, timeout=timeout_sec,
        )
    except subprocess.TimeoutExpired:
        return None, (time.time() - t0) * 1000
    ms = (time.time() - t0) * 1000
    for line in reversed(result.stdout.splitlines()):
        if "grpsize=" in line:
            orbits = int(line.strip().split(" orbit")[0])
            return orbits, ms
    return None, ms


def already_done(out_path):
    if not os.path.exists(out_path):
        return set()
    with open(out_path) as f:
        reader = csv.DictReader(f)
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
    ap.add_argument("--dreadnaut", default=str(REPO_ROOT / "tools" / "nauty" / "dreadnaut"))
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

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
                    n_eff, edges_eff = ensure_bipartite(n, edges)
                    if n_eff > args.maxVertices:
                        print(f"SKIP (vertex cap): {path} (n_effective={n_eff})")
                        continue
                    adj = build_adjacency(n_eff, edges_eff)
                    print(f"STARTING: [{family}] {path} n={n} n_effective={n_eff}")
                    orbits, ms = run_dreadnaut(args.dreadnaut, n_eff, adj, args.timeoutSec)
                    if orbits is None:
                        row = [family, path, n, len(edges), n_eff, "", f"{ms:.0f}", "true"]
                        print(f"  TIMED OUT at {args.timeoutSec}s")
                    else:
                        row = [family, path, n, len(edges), n_eff, orbits, f"{ms:.0f}", "false"]
                        print(f"  -> true_orbits={orbits} ms={ms:.0f}")
                    writer.writerow(row)
                    f.flush()
                except Exception as e:
                    writer.writerow([family, path, "", "", "", "", "", f"ERROR: {e}"])
                    f.flush()
                    print(f"FAILED: {path} -- {e}")

    print(f"Results written to {out_path}")


if __name__ == "__main__":
    main()