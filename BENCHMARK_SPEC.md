# Benchmark Specification — 16 vCPU / 8 GB / Xeon

The run that produces the paper's experimental section. Two things must come out of it: a head-to-head
against `Traces` under identical conditions, and a parallel scaling curve.

**Hardware constraint that shapes the design.** 8 GB across 16 vCPUs is 512 MB per worker if all are
busy. The formula `Φ(G,c)` is built once and shared read-only; each worker needs its own solver
instance, and CaDiCaL's memory grows with learned clauses. **Measure peak RSS per worker at the
largest n before committing to 16-way parallelism** — if a single solver at n = 3780 uses more than
~400 MB, cap the worker count accordingly and report the cap.

**Disk, not just RAM, is a per-instance constraint on a large subdivided graph.**
`DecompositionStore` (built whenever `dispatchColouring` compares 1-WL against the subdivision, see
Part 2's `--noSubdivision`) spills an O(n^2) scratch file to `java.io.tmpdir`, `n` being the
(possibly subdivided) vertex count it was built for — confirmed to exceed a small `java.io.tmpdir`
partition (e.g. a VM's tmpfs `/tmp`, distinct from the machine's main disk) on a subdivided instance
upward of ~8000 vertices, surfacing as "No space left on device" with the actual disk showing free.
Point `java.io.tmpdir` at the main disk with `JAVA_OPTS="-Ddialysis.tmpDir=/path/on/big/disk"
scripts/run_benchmark.sh ...`, or avoid building the store at all for a family where it doesn't pay
off with `--noSubdivision=true` (see Part 2).

---

## Part 1 — Ground truth

Ground truth is a separate pass from the benchmark run itself, computed with `scripts/ground_truth.py`
and joined into a benchmark CSV afterwards with `scripts/merge_ground_truth.py`.

### 1.1 Protocol

```
tool         nauty's dreadnaut, in Traces mode (`At`) -- orbit count only, no canonical form needed
timeout      per-instance, recorded; a timeout produces no true_orbits for that row, not a guess
input        `--subdivide` controls this (default `auto`: subdivide iff not bipartite). Since Task 1
             (FINAL_MEASUREMENTS_SPEC.md), the benchmark CLI always SOLVES on the ORIGINAL graph, so
             `recovered_orbits` counts orbits over the original n vertices only, never a
             subdivision's -- use `--subdivide=never` to make `true_orbits` comparable to it (`auto`/
             `always` count MORE orbits whenever they do subdivide, since Aut(sd(G)) === Aut(G) means
             orbits(sd(G)) = orbits_on_V(G) + orbits_on_E(G), not just the first term). Every row
             records which happened in `gt_subdivided`, and `scripts/merge_ground_truth.py` skips the
             recovered/true comparison rather than flag a spurious disagreement when it's `true`.
machine      one dreadnaut subprocess per instance, single-threaded, fully isolated from the JVM
             benchmark process (no shared state, a hung/killed instance can't affect another)
```

Traces mode is required, not optional — plain dense nauty refinement is specifically defeated by
some of the graph families in this corpus, where Traces still returns quickly.

### 1.2 Reporting

`scripts/ground_truth.py`'s own CSV: `family, instance, n, m, n_effective, true_orbits, gt_ms,
gt_timed_out, gt_outcome, gt_tool, gt_memory_cap_mb, gt_timeout_s, gt_subdivided` (the last five added
by FINAL_MEASUREMENTS_SPEC.md Task 0 and this section's `--subdivide` support). `scripts/
merge_ground_truth.py` joins this onto a benchmark CSV by `instance`, filling `true_orbits`/
`gt_source` and recomputing `status` (EXACT when `recovered_orbits == true_orbits` and `unknown ==
0`, PARTIAL otherwise; skipped entirely, as if no ground truth existed, when `gt_subdivided` is
`true`) — printing a `DISAGREEMENT` line for any certified-but-mismatched row rather than silently
overwriting it.

### 1.3 The threshold

Report, per family, the largest `n` at which the ground-truth pass still completed and the smallest
at which it started timing out. Runtime on a graph family engineered to defeat canonical-form tools
need not be monotone in `n` — report what's actually observed rather than smoothing it.

---

## Part 2 — The method: reporting schema

One row per (instance, configuration, repeat). Configurations:

```
PI_DIST         initial-phase colouring + implied distance clauses     [the proposed method]
WL_DIST         1-WL colouring + implied distance clauses              [ablation: is Pi needed?]
PI              initial-phase colouring, no implied clauses            [ablation: are (D) needed?]
```

Three configurations, three repeats, so nine runs per instance. That is affordable given the times
below and it makes both ablations defensible.

### 2.1 Columns

```
IDENTITY
  instance, family, n, m, config, repeat, seed

COLOURING
  classes_1wl                  colour classes under plain refinement
  classes_pi                   colour classes under the initial phase
  class_size_max, class_size_mean
  colouring_ms

ENCODING
  variables
  clauses_bijection, clauses_edge, clauses_implied
  anchors_K, dmax              parameters used for (D)
  encode_ms
  formula_peak_rss_mb

SOLVING
  queries_issued
  queries_skipped_witness      skipped because find(u) == find(v)
  queries_skipped_separation   skipped because (a,b) in sep
  sat, unsat, unknown
  solve_ms_total, solve_ms_max, solve_ms_median
  conflicts_total, conflicts_max
  per_query_timeout_ms

WITNESSES
  witnesses_verified, witnesses_rejected      <-- rejected MUST be 0; nonzero aborts the run

RESULT
  recovered_orbits
  true_orbits, gt_source        (dreadnaut | none — see Part 1)
  status                        CERTIFIED | EXACT | PARTIAL
  total_ms                      colouring_ms + encode_ms + solve_ms_total
  peak_rss_mb
```

FINAL_MEASUREMENTS_SPEC.md Task 1 added further diagnostic columns not repeated here (`wl1_original`,
`pi_subdivision`, `pi_to_original`, `colouring_used`, `subdivided`, `subdivision_mode`, `n_solved`,
`filter_mode`, plus Task 2's `global_edge_clause_estimate`/`per_query_edge_clause_estimate`) — see
that spec's own reporting-schema section for what each means. `subdivision_mode` records `AUTO` or
`OFF` for the whole run (from `--noSubdivision`), not a per-instance decision — `subdivided` is the
per-instance one.

**`--noSubdivision=true`** forces `dispatchColouring` to skip the subdivision + initial-phase
comparison entirely and use plain 1-WL on every non-bipartite instance, for a family where that
comparison (see FINAL_MEASUREMENTS_SPEC.md Task 1's own survey table) always resolves to 1-WL
anyway. Two reasons to use it: it's strictly faster (no `sd(g)`, no `DecompositionStore` on it — see
the disk-space note above), and it puts every family in a run on the same never-subdivided basis, so
configs/families are being compared on identical terms rather than some silently subdividing and
others not depending on `dispatchColouring`'s own per-instance choice. Pair with
`scripts/ground_truth.py --subdivide=never` when checking such a run against ground truth — see Part
1's own note on why `true_orbits` and `recovered_orbits` are only the same count when neither side
subdivides.

**`--minVertices`** (default `0`) is `--maxVertices`'s floor: skip any instance whose original `n` is
below it, gating on the same quantity. Exists to resume a campaign past an older CSV whose column
schema predates a later addition (Task 1/2's diagnostic columns, `subdivision_mode`, etc.) without
re-running everything that CSV already covers — point a fresh `--out` at `--minVertices=<one past the
old run's largest n>` rather than trying to append rows of a different shape into the old file.

**Query scheduling.** Every query gets the full per-query timeout is the naive approach, but a small
number of genuinely hard queries then dominate wall-clock time even though most queries resolve in
milliseconds. Two-pass scheduling fixes this: a cheap short-budget sweep over every pending pair
first (a SAT witness closes many pairs at once via union-find over the whole permutation, so this
sweep shrinks the survivor list fast), then the full long budget only for whatever's left. This is
why `--timeoutMs`/`--shortMs` are separate CLI parameters rather than one timeout.

### 2.2 Status

```
CERTIFIED         unknown == 0                              (the queries prove the partition)
EXACT             unknown == 0 AND recovered == true_orbits (certified and independently confirmed)
PARTIAL           unknown >= 1                              (regardless of any agreement)
PI_ONLY           class_size_max/n exceeded a size guard    (colouring reported, SAT never attempted)
SKIPPED_TOO_LARGE both edge-clause estimates exceeded --edgeClauseThreshold (see Task 2 below)
INSTANCE_TIMEOUT  solve phase exceeded --maxInstanceSolveMs (encoding was built; solve abandoned)
```

**A row with `unknown >= 1` is never `EXACT`.** Agreement with ground truth on a partial run is a
coincidence, not a result.

**`SKIPPED_TOO_LARGE` (FINAL_MEASUREMENTS_SPEC.md Task 2).** `global_edge_clause_estimate` and
`per_query_edge_clause_estimate` are `Sigma_edges |C(i)|*|C(k)|` for the GLOBAL colouring and for a
representative per-query individualization of the largest class, respectively -- both computed in
one pass from the colouring alone, no `CadicalSolver` ever constructed, so always safe regardless of
instance size. Below `--edgeClauseThreshold` on the global estimate: solve GLOBAL as before. Above
it but below threshold on the per-query estimate: solve via the per-query filter (`filter_mode =
PER_QUERY`). Above threshold on both: `SKIPPED_TOO_LARGE`, with both numbers recorded rather than
silently dropping the row -- the default threshold sits between a measured-safe ~1M and a
measured-failing ~3.28M, not a hunted-for exact boundary, and the boundary is expected to be a
smooth degradation, not a per-family cliff.
`estimateGlobalEncodingSize`/`estimatePerQueryEncodingSize` are computed lazily where possible (the
per-query estimate only when the global one alone doesn't already clear the threshold) -- computing
it unconditionally required a full `DecompositionStore` build (a parallel BFS decomposition of the
WHOLE graph) even on instances that were always going to be `GLOBAL`, which was a real, measured
contributor to campaign wall-clock time before this was fixed.

**`INSTANCE_TIMEOUT`.** `--maxInstanceSolveMs` (default 120000) is a hard WALL-CLOCK backstop on
ONE instance's entire solve phase, independent of `--timeoutMs` (which only bounds a single query).
A colour class with many members can need many queries before generator closure catches up --
measured directly, `had-20` (80 members) took 26-62s despite every individual query resolving in
~1s, because before the first witness closes anything, every candidate pair gets a real solve
attempt with nothing to skip. Without this backstop a single pathological instance could stall an
entire campaign indefinitely; with it, that instance's row is `INSTANCE_TIMEOUT` and the campaign
moves on to the next one. The GLOBAL path enforces this via an external daemon-thread deadline
(`driveToOrbitsCadicalParallel` itself is shared, validated code and isn't modified for this --
CaDiCaL's native call won't actually stop on cancellation, so the abandoned solve keeps running in
the background, but the campaign's own forward progress is what this backstop protects); the
PER_QUERY path checks the same deadline between and within colour classes directly.

### 2.3 Invariants to assert per row

```
sat + unsat + unknown == queries_issued
witnesses_rejected == 0
classes_pi >= classes_1wl
recovered_orbits >= classes_pi        (orbits refine colour classes -- a class can split into
                                        several orbits, never merge with another class)
```

Fail the row and report rather than writing a row that violates any of these.

---

## Part 3 — Parallelism

Queries in distinct colour classes are independent: no union crosses a colour class,
so the union--find and separation state of one class never influences another. Within a class the
queries are sequential.

### 3.1 Design

```
build Phi(G,c) ONCE per worker             -- CaDiCaL has no formula-sharing/solver-cloning API,
                                               so this is recomputed per worker, not literally shared
each worker:
    its own CaDiCaL instance
    pulls classes one at a time from a SHARED atomic index into a queue ordered largest-first,
        until the queue is exhausted -- no class is pre-assigned to any particular worker
    maintains union-find / separation state LOCAL to whichever classes it drew
join: the local partitions are disjoint by construction; concatenate
```

No locking is required beyond the shared index, and no state is exchanged between workers. **Do not
share a solver between workers** — the incremental learned-clause state is what makes queries fast,
and interleaving unrelated classes would destroy it.

**Two-pass scheduling is per-worker-global, not per-class.** Each worker short-passes every pair in
every class it claims off the shared queue first, accumulating survivors across its WHOLE share, and
only then long-passes those survivors — mirroring the single-threaded driver's graph-wide
short-then-long structure, scoped to one worker's own solver. Doing short-then-long inside the claim
loop (one class at a time) was tried and was a real bug, not a style choice: a worker's solver only
warms up from the pairs it has actually queried, so resetting that warm-up on every class starves
later classes' hard queries of the accumulated learned clauses that make them resolve cheaply.
Confirmed directly: `cfi-rigid-d3-3600-02-1` at workers=1 (one solver — this MUST reduce to the
single-threaded driver's exact behavior) went from 15 unknowns with per-class two-pass to 0 with
per-worker-global two-pass, same instance, same timeout — every query resolved in the short pass
once warm-up wasn't being discarded every class.

### 3.2 Load balancing — dynamic, not estimated

A static schedule (longest-processing-time-first on the estimate `|C|²`, computed once before
starting) was tried and dropped. **Finding, worth a sentence in the paper:** on families with
near-uniform colour classes (e.g. `cfi-rigid-d3` at n=2160: every class has 6-9 members), wall-clock
cost is not driven by query count — it's driven by a handful of individually hard queries near the
per-query timeout, and which classes contain those hard queries has no correlation with `|C|`.
Measured directly: 4 workers given IDENTICAL estimated cost under the static schedule finished in
75s-114s, a 40%+ spread, because the estimator carried no real signal for this family. SAT hardness
isn't a function of any cheap structural feature, so the fix is not a better estimator — it's not
predicting at all. A worker that draws a slow class simply ends up processing fewer classes overall;
the straggler is bounded by at most one class's duration, not one worker's entire pre-assigned share.
Confirmed on the same instance: after switching to the dynamic queue, `straggler_ratio` went from
1.18-1.21 to 1.00 and total solve time improved, with per-worker class counts varying wildly (one
worker can legitimately process 10x more classes than another) while all workers finish within a
few hundred ms of each other.

Classes are still queued largest-first — not because size predicts cost well (it doesn't, per
above), but to avoid the tail case where the LAST class claimed off the queue is also the biggest
one; standard practice for dynamic scheduling under unknown durations.

A shared warm-up prefix (every worker priming on the same small set of classes before racing for
the queue) was tried and dropped: it reintroduces the exact imbalance the dynamic queue exists to
prevent, since a worker that finishes its priming pass even slightly faster gets a head start and
can claim most of the queue before the others start competing. Measured directly: one worker
claimed 521 of 560 classes while three others got 12-14 each, and `straggler_ratio` rose to 1.35
from the ~1.00 baseline without it. The fair-start assumption behind the dynamic queue (workers
begin racing for the shared index at the same time) doesn't survive adding any variable-duration
step before that race begins.

```
straggler_ratio = max_worker_ms / mean_worker_ms
```

`straggler_ratio` is still the reported metric, but it now measures how well the **queue** balanced
itself, not how good a cost estimate was — there is no cost estimate driving scheduling anymore.
Expect it near 1; a large ratio now means the queue drained unevenly (e.g. too few classes relative
to worker count), not an estimator failure.

### 3.3 The scaling measurement

For a fixed set of instances near the top of the range (n = 2700, 3240, 3780):

```
W in {1, 2, 4, 8, 16}
report per W:  wall_ms, speedup = wall_ms(1)/wall_ms(W), efficiency = speedup/W,
               straggler_ratio, peak_rss_mb_total
```

Three repeats per (instance, W). **Report efficiency, not just speedup** — a referee will ask, and
sub-linear scaling with a stated cause (stragglers, memory, or the sequential colouring phase) is a
better result than an unexplained curve.

### 3.4 Amdahl accounting

The colouring phase is sequential and is included in `total_ms`. Report it separately so the
parallel fraction is visible:

```
serial_fraction = colouring_ms / total_ms(W=1)
```

At n = 3780 the colouring was a few seconds against solve times of hundreds, so the serial fraction
should be small — but state it rather than letting the reader assume.

---

## Part 4 — Instance set

```
cfi-rigid-d3    all sizes to n = 3780        [the headline family; already run single-threaded]
cfi-rigid-z3    all sizes to the same range
cfi-rigid-r2    all sizes to the same range  [the family where Pi = 1-WL; the WL_DIST ablation
                                              should show no gap here, and that is the point]
cfi-rigid-z2, t2, s2   as far as time allows
```

Plus, as controls where the answer is known and must be reproduced:

```
one easy family (latin or paley, small n)     -- all configurations must recover known orbits
one instance run twice in separate JVM launches -- identical partitions (reproducibility)
one instance under random relabelling          -- identical partition as a partition
```

---

## Part 5 — Time budget

Single-threaded d3 times: ~600 s at n = 3780, with high variance (571 s to 2674 s at the same size).
Rough estimate for the full plan:

```
Part 1 ground truth   3 repeats x 2 tools x all sizes, most timing out at 1800 s -> dominated by
                      timeouts; budget generously, and run it FIRST since everything depends on it
Part 2 method         3 configs x 3 repeats; PI_DIST is the expensive one, WL_DIST and PI cheaper
Part 3 parallel       3 instances x 5 values of W x 3 repeats = 45 runs, the W=1 runs being the
                      most expensive
```

Run Part 1 first. If the machine time is insufficient for everything, drop `z2/t2/s2` from Part 2
before dropping anything from Parts 1 or 3.

---

## Standing rules

- **Emit rows incrementally**, flushed to disk as each completes. A run that dies at hour six must
  not lose hours one to five.
- **Record the exact solver version and build flags** for CaDiCaL, Traces and nauty, and the CPU
  model and memory. The paper needs them.
- **Fix and record the random seed** for anything stochastic; report it per row.
- **Timeouts are `unknown`**, never `unsat`.
- **No symmetry-breaking predicates**; a run that adds any is invalid.
- **Verify every witness in `O(m)`**; a rejection aborts the run rather than being logged and skipped.
