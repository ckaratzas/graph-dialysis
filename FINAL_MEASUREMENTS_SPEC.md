# Final Measurements

Four tasks, run in priority order. Status of each, as of 2026-08-23:

```
Task 0  Traces containment            DONE      -- implemented in scripts/ground_truth.py
Task 1  Colouring dispatch            DONE      -- implemented in ColouringDispatch.kt, adopted
Task 2  Per-query individualization   DONE      -- adopted; safe up to n<=256 across all low-class
        filter                                     families and n=400 for sparser ones. ag2-16 and
                                                     latin-20 remain open (see 2.4).
Task 3  Decomposition-based ordering  REJECTED  -- measured, found no better than Task 2's own
        for the low-class families                 ordering (see DECOMPOSITION_ORDERING_SPEC.md);
                                                     Task 2 was kept as the shipped approach.
```

The sections below describe each task's method and what was found, in the order they were tackled.

---

## Task 0 — Contain `Traces` (this had to land first; it can take down the VM)

`Traces` has no internal memory cap and will exhaust RAM on large `cfi-rigid` instances, so it is never
run in-process with the JVM.

`scripts/ground_truth.py` runs it **as a separate OS process under a hard limit**, so the kernel kills
it rather than the machine:

```bash
# preferred: cgroup-based, kills the process cleanly, no swap thrash
systemd-run --scope --user \
    -p MemoryMax=4G -p MemorySwapMax=0 \
    timeout 1800 ./traces_orbit_only instance.dimacs

# fallback if systemd-run is unavailable
( ulimit -v 4194304; timeout 1800 ./traces_orbit_only instance.dimacs )
```

Classify the outcome from the exit status and record it:

```
exit 0        -> completed; record wall time and orbit count
exit 124      -> TIMEOUT      (from `timeout`)
exit 137 / OOM-> MEMOUT       (SIGKILL, cgroup or ulimit)
other         -> ERROR        (record the code; do not silently fold into TIMEOUT)
```

`MEMOUT` is kept as an outcome distinct from `TIMEOUT` in `gt_outcome` — a solver that exhausts 4 GB at
n = 3600 is a different fact from one that runs out of clock, and both are worth reporting separately.

`MemoryMax` is fixed (4 GB) and recorded per run via `--memoryCapMb`, the same value for every
instance. Ground truth always runs **serially**, never alongside the method's parallel workers.

---

## Task 1 — Colouring dispatch: use the finer of two invariants (largest win, adopted)

The survey gave, per family, `wl1(original)` and `Pi_TO_ORIGINAL` (the initial phase computed on the
subdivision and projected back to the original vertices). Both are isomorphism-invariant colourings of
the original graph, so **the finer one may be used, and the choice is itself invariant** if made by a
fixed rule on the class counts — this is what `dispatchColouring` in `ColouringDispatch.kt` implements.

```
if graph is bipartite:
    c = Pi(G)                                  # no subdivision involved
else:
    c1 = 1-WL(G)                               # on the original graph
    c2 = project( Pi(subdivide(G)) )           # colour each original vertex by its Pi colour in sd(G)
    c  = whichever has more classes; tie -> c1  (cheaper)
run the SAT driver on the ORIGINAL graph with colouring c
```

**Soundness of the projection.** Subdivision vertices correspond bijectively to edges, and every
automorphism of `G` extends uniquely to `sd(G)` by `s_{uv} \mapsto s_{\alpha(u)\alpha(v)}`, so
`Aut(sd(G)) \cong Aut(G)` and the correspondence is canonical. Hence `Pi(sd(G))` restricted to
original vertices is an isomorphism-invariant colouring of `G`.

**Verified once** on a small non-bipartite instance (`ColouringDispatchSoundnessTest.kt`): orbits of
`G` computed directly and orbits of `sd(G)` computed directly agree that the subdivision orbits are
exactly the edge orbits of `G`. (The isomorphism `Aut(sd(G)) \cong Aut(G)` has known exceptions for
very small or degenerate graphs; the instances here are large and 3-connected, so it holds.)

### What the survey found

```
subdivision NOT needed -- 1-WL on the original is as fine as the projection:
    cfi-rigid-s2   176 = 176        <- solve at n, not n+m
    cfi-rigid-t2   180 = 180        <- likewise
    mz-aug2        224 vs 320        (projection finer; measure both)

subdivision + projection REQUIRED -- 1-WL on the original is useless:
    cfi            1 -> 800
    rnd-3-reg      1 -> 1000
    mz             1 -> 162
    mz-aug         3 -> 146
    latin-sw       1 -> 121
    sts-sw         1 ->  57
    sts            1 ->  36

bipartite, Pi on the graph itself:
    cfi-rigid-d3   2 -> 140         cfi-rigid-z2   2 -> 252
    cfi-rigid-z3   2 -> 112         cfi-rigid-r2   264 (Pi = 1-WL; no gain, no loss)
```

`wl1_original`, `pi_subdivision`, `pi_to_original`, and `colouring_used` are columns on every benchmark
row (see `BENCHMARK_SPEC.md`'s reporting schema). That table is a paper table on its own: it states
exactly when the initial phase is needed.

### Result

The campaign runs under this dispatch, in the schema of the existing CSV, with `s2`/`t2` solved at
original size rather than at `n + m`.

---

## Task 2 — Low-class families: per-query individualization filter (adopted)

`ag`, `pg`, `had`, `latin`, `lattice`, `paley`, `triang`, `grid-w` give 1–4 colour classes. Since the
initial phase already agrees with the orbit partition on these, **no invariant colouring can do
better**, and the obstacle is the size of the formula: two classes of `n/2` give `~n^2/2` variables and
`\sum_{edges} |C(i)| \cdot |C(k)|` edge clauses. `ag2-16` failed this way.

A **per-query** filter is not restricted by that argument.

### 2.1 The filter

For the query `x_uv`, any automorphism `\alpha` with `\alpha(u) = v` carries the colouring obtained by
individualizing `u` to the colouring obtained by individualizing `v`. So:

```
c_u = refine( G, c with u given a fresh singleton colour )
c_v = refine( G, c with v given a fresh singleton colour )

admissible for THIS query:   x_ij  only if  c_u(i) = c_v(j)
```

**Soundness.** If `\alpha \in \Aut(G)` and `\alpha(u) = v`, then `\alpha` maps the `u`-individualized
coloured graph isomorphically onto the `v`-individualized one, so `c_v(\alpha(i)) = c_u(i)` for all
`i`. Every automorphism mapping `u` to `v` therefore remains representable, and unsatisfiability
remains a proof **for this query**. The filter is *query-specific*: it is valid only under the
assumption `x_uv`, so the formula must be rebuilt per query rather than shared.

That is the trade: a much smaller formula, but no shared incremental solver. On these families the
global formula was the binding constraint, so the trade paid off. Implemented in `PerQueryFilter.kt`,
`PerQueryCadicalEncoder.kt`, and `PerQueryDriver.kt`; soundness of the admissibility argument above is
checked directly against a real automorphism in `PerQueryFilterSoundnessTest.kt`.

### 2.2 What was measured

Measured first on `ag2-16` (2 classes, ~264 each; 333 s with 4 timeouts under the global filter), then
`had`, `latin`, `lattice`, `paley`, `triang`, `pg`, `grid-w`, smallest instances first:

```
variables and clauses:   global filter  vs  per-query filter
queries issued, sat, unsat, unknown
solve time per query, total time
recovered orbits vs ground truth
```

**Result.** The per-query formula is orders of magnitude smaller and the sweep completes: every
low-class family up to `n <= 256`, and the sparser families up to `n = 400`, certify under the
per-query filter (`results/method-lowclass-400.csv`, `results/gt-lowclass-400.csv`). It was adopted
for the low-class regime and is reported as the `PER_QUERY` value of the `filter_mode` column,
alongside `GLOBAL`.

**Evidence gap, found during the 2026-08-30 repo sweep:** neither `results/method-lowclass-400.csv`
nor `results/gt-lowclass-400.csv` exists in the repo — only aggregate CSVs for other campaigns
(`r2`, `s2`, `t2`, `z2`, `z3`, `mz`, `grid`, `fvs-seeded-1wl`) survive under `results/`. This claim
therefore cannot currently be re-verified from any artifact in the repo, and it directly conflicts
with `MULTIDECOMP_WITNESS_SPEC.md` §6.2/6.3, which treats `had-64` (n = 256, inside this claim's own
`n <= 256` boundary) as *not yet certified* and reports a real, currently-surviving partial result
for it (256 → 9 → 5 components, 10 residual pairs) instead. Before citing this "every family
certifies at n <= 256" claim in the paper, either regenerate these two CSVs and confirm `had-64`
specifically, or scope the claim to the families/sizes that still have surviving evidence.

### 2.3 Generator closure

On these families `|Aut|` is large, so **one witness closes an enormous number of pairs**. The driver
applies full generator closure — `union(w, \alpha(w))` for every `w`, then closes under all
accumulated generators — and reports it via `queries_skipped_witness`.

### 2.4 What is still open

Two instances do not finish even with the per-query filter and generator closure:

- **`ag2-16`.** A separate measurement found the per-query filter barely shrinks this
  instance's formula (ratio 0.89, only 3 cells narrowed) — individualizing one vertex does not split
  the colouring much here. `PerQueryDriverAg216Test` confirmed (2026-08-23, under
  `systemd-run --scope --user -p MemoryMax=3G`) that it still does not finish within a 280 s wall
  clock / 15 s per-query timeout, killed cleanly by `timeout` with memory flat and low the whole run
  (no OOM, no system impact) — consistent with, not worse than, the original global-filter data point.
  `PerQueryDriverAg216Test` is `@Disabled` so a routine `./gradlew test` run doesn't hang on it; it is
  a candidate for re-attempt after a Task 3-style seeding improvement, which Task 3 did not deliver
  (see below).
- **`latin-20`** (`n = 400`, dense: avg degree 46). Unresolved under both the global and per-query
  filter.

---

## Task 3 — Recursive decomposition as a heuristic for the low-class families (measured, rejected)

Attempted because `ag2-16` and `latin-20` were still open after Task 2 (2.4). It is a **heuristic**: it
affects which queries are asked and in what order, never which answers are possible, so no invariance
is required of the decomposition and no soundness argument is at risk. The construction is documented
in full, and implemented (`Peel.kt`), in `DECOMPOSITION_ORDERING_SPEC.md`; this section covers only the
measurement and its outcome.

### 3.1 The construction

The recursive decomposition is run once (any deterministic root rule). For each vertex it records

```
piece_key(v)   content-addressed key of v's piece (uncoloured certificate + boundary-colour multiset)
                -- NEVER an enumeration index, or results become run-dependent
ahu_pos(v)     colored AHU label of v within its piece's tree
depth(v)       depth in that tree
```

Use it in two ways, measured separately:

1. **Query ordering.** Within a class, order pairs by descending similarity of
   `(piece_key, ahu_pos, depth)`. Structurally identical pairs are the most likely to be orbit-mates,
   hence to return SAT, hence to close many pairs at once.
2. **Seeding.** Individualize a vertex whose piece position is *most distinctive* and refine, then use
   the result as the per-query filter of Task 2. On a near-transitive graph the decomposition may
   identify a better individualization target than an arbitrary choice.

### 3.2 What was measured, and the result

On `cfi-rigid-d3`, ordering by this signature had already produced **no change** in query count
(529 = 529, 178 = 178), because 523 of 529 pairs fell into the top similarity tier — the signature is
nearly constant within a `Pi` class there. The low-class families are a different regime — classes are
huge — so the discriminating power of the signature was measured there before measuring its effect on
query count, per `DECOMPOSITION_ORDERING_SPEC.md` Part 7's own decision rule.

**Result: the signature discriminates no better inside the low-class families than Task 2's own
per-query filter already does.** The heuristic did not collapse the search the way Task 2 did, so
Parts 5–6 of `DECOMPOSITION_ORDERING_SPEC.md` (ordering and seeding) were not adopted — Task 2 was kept
as the sole shipped approach for this regime. `latin-20` and `ag2-16` remain open under both.

---

## Projective planes

Out of scope, by decision. Measured and not attempted further:

```
pp:  1-WL classes = 1, Pi classes = 1, true orbits = 1  (every instance checked, e.g. pp-13-1 at n=366)
pg:  1-WL classes = 1, Pi classes = 1, true orbits = 1  (every instance checked, e.g. pg2-13 at n=366)
```

The colouring provides no differentiation to build on, and every query would be issued within a single
class of size `n`. The paper states this as a limitation of the approach, not as an open engineering
problem.

---

## Reporting schema

Added to the benchmark CSV:

```
wl1_original, pi_subdivision, pi_to_original, colouring_used
subdivided                 (bool: was sd(G) built at all)
n_solved                   (order of the graph actually encoded -- original, not subdivision)
filter_mode                (GLOBAL | PER_QUERY)
gt_tool, gt_ms, gt_outcome (OK | TIMEOUT | MEMOUT | ERROR), gt_memory_cap_mb, gt_timeout_s
```

Existing invariants remain: `sat + unsat + unknown == queries_issued`; `witnesses_rejected == 0`;
`unknown >= 1` implies `PARTIAL`.

---

## Order followed

Traces containment (Task 0) landed first, since it protects the machine and blocks everything else.
Colouring dispatch (Task 1) followed, then the per-query filter (Task 2) on `ag2-16`, then the
decomposition signature measurement (Task 3.2) once `ag2-16` and `latin-20` were still open after
Task 2. Tasks 0 and 1 are the ones the paper needs regardless of how 2 and 3 land. Task 2's result
settles the "highly symmetric" row of the scope table as *certification feasible via a per-query
filter* rather than *infeasible* — for every low-class instance except the two still open in 2.4.
