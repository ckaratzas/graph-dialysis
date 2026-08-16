# Benchmark Specification — 16 vCPU / 8 GB / Xeon

The run that produces the paper's experimental section. Two things must come out of it: a head-to-head
against `Traces` under identical conditions, and a parallel scaling curve.

**Hardware constraint that shapes the design.** 8 GB across 16 vCPUs is 512 MB per worker if all are
busy. The formula `Φ(G,c)` is built once and shared read-only; each worker needs its own solver
instance, and CaDiCaL's memory grows with learned clauses. **Measure peak RSS per worker at the
largest n before committing to 16-way parallelism** — if a single solver at n = 3780 uses more than
~400 MB, cap the worker count accordingly and report the cap.

---

## Part 1 — Ground truth

Ground truth is a separate pass from the benchmark run itself, computed with `scripts/ground_truth.py`
and joined into a benchmark CSV afterwards with `scripts/merge_ground_truth.py`.

### 1.1 Protocol

```
tool         nauty's dreadnaut, in Traces mode (`At`) -- orbit count only, no canonical form needed
timeout      per-instance, recorded; a timeout produces no true_orbits for that row, not a guess
input        the same bipartite-subdivided graph the benchmark CLI analyzes (ensureBipartite is
             applied identically on both sides so recovered_orbits and true_orbits are comparable)
machine      one dreadnaut subprocess per instance, single-threaded, fully isolated from the JVM
             benchmark process (no shared state, a hung/killed instance can't affect another)
```

Traces mode is required, not optional — plain dense nauty refinement is specifically defeated by
some of the graph families in this corpus, where Traces still returns quickly.

### 1.2 Reporting

`scripts/ground_truth.py`'s own CSV: `family, instance, n, m, n_effective, true_orbits, gt_ms,
gt_timed_out`. `scripts/merge_ground_truth.py` joins this onto a benchmark CSV by `instance`,
filling `true_orbits`/`gt_source` and recomputing `status` (EXACT when `recovered_orbits ==
true_orbits` and `unknown == 0`, PARTIAL otherwise) — printing a `DISAGREEMENT` line for any
certified-but-mismatched row rather than silently overwriting it.

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

**Query scheduling.** Every query gets the full per-query timeout is the naive approach, but a small
number of genuinely hard queries then dominate wall-clock time even though most queries resolve in
milliseconds. Two-pass scheduling fixes this: a cheap short-budget sweep over every pending pair
first (a SAT witness closes many pairs at once via union-find over the whole permutation, so this
sweep shrinks the survivor list fast), then the full long budget only for whatever's left. This is
why `--timeoutMs`/`--shortMs` are separate CLI parameters rather than one timeout.

### 2.2 Status

```
CERTIFIED   unknown == 0                              (the queries prove the partition)
EXACT       unknown == 0 AND recovered == true_orbits (certified and independently confirmed)
PARTIAL     unknown >= 1                              (regardless of any agreement)
```

**A row with `unknown >= 1` is never `EXACT`.** Agreement with ground truth on a partial run is a
coincidence, not a result.

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
build Phi(G,c) ONCE                       -- read-only, shared
partition the colour classes across W workers
each worker:
    its own CaDiCaL instance, loaded with the shared formula
    processes its assigned classes sequentially
    maintains union-find / separation state LOCAL to those classes
join: the local partitions are disjoint by construction; concatenate
```

No locking is required on the shared formula and no state is exchanged. **Do not share a solver
between workers** — the incremental learned-clause state is what makes queries fast, and interleaving
unrelated classes would destroy it.

### 3.2 Load balancing

Class sizes are uneven and cost is quadratic in orbits per class, so round-robin will straggle.
Assign classes to workers by **longest-processing-time-first** on the estimate `|C|²`, recomputed as a
static schedule before starting. Report the straggler ratio:

```
straggler_ratio = max_worker_ms / mean_worker_ms
```

A ratio near 1 means the schedule is good; a large ratio means one class dominates and the speedup is
capped by it — which is itself worth reporting.

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
