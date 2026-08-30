# Final report — exact orbit computation for `cfi-rigid-*` and friends

This is the paper-facing synthesis. It states what is proven sound, what is measured fast, what was
tried and abandoned, and exactly how to reproduce every number below. Every claim here traces to one
of the dated spec documents in this repo (`BENCHMARK_SPEC.md`, `INVARIANT_FILTERED_SAT_SPEC.md`,
`FINAL_MEASUREMENTS_SPEC.md`, `DECOMPOSITION_ORDERING_SPEC.md`, `FVS_SEEDED_1WL_SPEC.md`,
`GADGET_XOR_SPEC.md`, `MULTIDECOMP_WITNESS_SPEC.md`) — this document doesn't restate their
derivations, it summarizes their outcomes and tells you where to look for the rest.

## 1. The core method (production, always sound)

Given a graph `G`, compute the orbits of `Aut(G)`:

1. **Colour-refine** `G` into an isomorphism-invariant vertex colouring — the finer of plain 1-WL on
   `G` itself and a richer "initial phase" refinement run on a subdivision and projected back
   (`dialysis.refinement.dispatchColouring`, `FINAL_MEASUREMENTS_SPEC.md` Task 1). Two vertices can
   only be orbit-mates if they share a colour — free pruning before any SAT solver runs.
2. **Encode** "does some `α ∈ Aut(G)` map `u → v`?" as a SAT instance over a colour-admissible
   permutation matrix, plus edge-preservation clauses, plus optional implied distance clauses
   (`INVARIANT_FILTERED_SAT_SPEC.md`). No symmetry-breaking predicates anywhere — they would exclude
   models and could silently turn a genuine SAT instance UNSAT, corrupting certification.
3. **Drive** every same-colour pair to a verdict with a two-pass timeout schedule and
   transitivity/separation tracking so most pairs are never queried at all (`BENCHMARK_SPEC.md`
   Part 2); a SAT witness is independently re-verified in `O(m)` before being trusted.
4. **Certify**: `unknown == 0` means the recovered partition is *provably* exact.

This is the only part of the system every downstream experiment below is checked against — every
"sound" claim means "recovered partition matches this pipeline's own CERTIFIED output" (or, where
noted, an independent Traces/nauty computation).

**Backend:** CaDiCaL (Biere et al.), via a from-scratch JNI binding, replacing an earlier SAT4J
backend — verified to agree with it on 412 pairs (0 disagreements) before the switch, and ~7.6×
faster on a hard instance (39.6s vs 301.5s). (These numbers were originally recorded in a
`CADICAL_MIGRATION_SPEC.md` that does not exist anywhere in this repo or its git history — only this
summary of them survives; treat them as reported, not independently re-verifiable from a document in
this repo.)

**Engineering wins already folded into the production path** (not ablations — these are what
`BenchmarkRunner` actually does):

| Change | Effect | Where |
|---|---|---|
| Colouring dispatch (finer of 1-WL / projected initial-phase) | Solves `s2`/`t2` at original size instead of `n+m`; the paper table of exactly when the initial phase is needed | `FINAL_MEASUREMENTS_SPEC.md` Task 1 |
| Per-query individualization filter | Every low-colour-class family (`ag`, `pg`, `had`, `latin`, `lattice`, `paley`, `triang`, `grid-w`) up to n≤256, sparser ones to n=400, now certifies — the global formula was the binding constraint before this. **Caveat:** the two CSVs this claim traces to no longer exist in the repo, and `had-64` (n=256) specifically has surviving evidence pointing the other way (§3, `MULTIDECOMP_WITNESS_SPEC.md`) — regenerate before citing this row in the paper | `FINAL_MEASUREMENTS_SPEC.md` Task 2 |
| Traces containment (Task 0) | Ground truth no longer risks taking down the VM | `FINAL_MEASUREMENTS_SPEC.md` Task 0 |

**Measured and rejected** (recorded, not adopted): recursive-decomposition query ordering/seeding —
on `cfi-rigid-d3` it changed nothing (523/529 pairs fell in the same similarity tier); on the
low-class families its position signature discriminates no better than the per-query filter already
does (`DECOMPOSITION_ORDERING_SPEC.md`, `FINAL_MEASUREMENTS_SPEC.md` Task 3). Kept in the repo
(`dialysis.decomposition.Peel`) because the mechanism is worth recording — same tool, two regimes,
both measured, neither won.

**Still open under the core method + every adopted improvement:** `ag2-16` (per-query filter barely
narrows its colouring — ratio 0.89 — and doesn't finish in 280s under a 3GB memory cap, cleanly
killed, no OOM) and `latin-20` (dense, n=400, unresolved under both global and per-query filters).
Both remain open after the decomposition-heuristic attempt above too.

## 2. Two real, measured speedups beyond the core method

Both of the following are **sound everywhere tested** and **fast specifically on the families named**
— neither is a universal replacement for the core method above.

### 2.1 FVS-seeded 1-WL — `r2`, `z2`

Seed 1-WL with the SAT-derived orbits of a feedback vertex set (`dialysis.fvs.FeedbackVertexSet`, a
2-approximation, Bafna-Berman-Fujito) restricted to its colour-class closure, instead of driving every
colour-admissible pair. A correctly-seeded colour refinement can only ever produce a *union* of true
orbits, never a split — so a mismatch is only ever under-refinement, and full discreteness is by
itself an airtight rigidity proof needing zero SAT verification.

| Family | Sound? | Speedup | Why |
|---|---|---|---|
| **r2** | Yes, always | **Real, but only above n≈600-900** — a wash or net slower below that (production is already sub-100ms there, so ~88-95% fewer SAT queries doesn't recoup the FVS/1-WL overhead); up to 3.32× measured above it | Plain 1-WL from a uniform seed already splits into many small classes; the FVS only touches a fraction of them |
| **z2** | Yes, always | ~88-95% fewer SAT queries, same mechanism as r2 — same-machine speedup curve not separately measured (see §2.1 note) | Same mechanism as r2 |
| t2, s2 | Yes, always | None — net *slower* (1.5-3×) | Seeded set already reaches 100% of n; pure overhead, no query reduction possible |
| d3, z3 | **No** — provably under-refines at a constant 0.6364 ratio, every size tested | — | Different, richer CFI gadget (group Γ=ℤ₃/D₃ — Neuen-Schweitzer §5 — not the ℤ₂ gadget r2/t2/s2/z2 use); the group-consistency constraint is invisible to *any* WL-style local refinement, confirmed by also trying FVS-seeded **2-WL** (identical result to 1-WL on every instance) and the project's own stronger `initialPhase` refinement (helps only at the smallest size, n=180) |

Reproduce: `scripts/run_fvs_campaign.sh r2 z2` → `results/fvs-seeded-1wl.csv`. See
`FVS_SEEDED_1WL_SPEC.md` for the full derivation, the d3/z3 root-cause diagnosis (Part 3), and the
2-WL check (Part 7).

**Timing, done right (same machine, same instance, back to back, no cached CSV involved —
`FvsFirstVsFullR2Test`, `FVS_SEEDED_1WL_SPEC.md` Part 4.2):**

| n | production | hybrid | speedup |
|---|---|---|---|
| 288 | 11ms / 336 queries | 25ms / 34 queries | 0.44× (slower) |
| 576 | 73ms / 672 | 80ms / 69 | 0.91× (slower) |
| 936 | 753ms / 1092 | 744ms / 120 | 1.01× (wash) |
| 1296 | 1399ms / 1512 | 1420ms / 159 | 0.99× (wash) |
| 1656 | 3482ms / 1932 | 2509ms / 206 | 1.39× |
| 1944 | 4903ms / 2268 | 3894ms / 240 | 1.26× |
| 2160 | 33271ms / 2520 | 10007ms / 268 | **3.32×** |
| 2448 | 23091ms / 2856 | 12966ms / 296 | 1.78× |

Sound at every size (identical orbit count both ways). A first attempt at this comparison joined
this campaign's own timings against the cached `results/r2-sat.csv` production numbers and got a
much noisier, partly-net-slower-at-large-n picture — that turned out to be a cross-machine
measurement artifact (those two CSVs were never produced on the same machine), not a real effect;
discarded in favor of the controlled table above once the confound was identified.

**Confirmed this session** (`results/fvs-seeded-1wl.csv`, both families run to completion): `z2` —
**47/47 size buckets, 100% match**, up to n=4136 (beyond the spec's originally-documented n=3960
ceiling). `r2` — **43/43 size buckets, 100% match**, up to n=3024 (beyond the spec's
originally-documented n=2592 ceiling), zero `ERROR` rows. The first 37 r2 buckets (to n=2592) ran on
a laptop; the remaining 6 (n=2664–3024) were finished on a stronger machine via the self-contained
bundle `scripts/package_fvs_campaign.sh r2` produces (jar + r2's graph files + `results/` including
the partial CSV, no repo clone needed there — see README.md), resuming rather than restarting. Those
6 buckets took noticeably longer per instance (up to ~30 minutes at n=3024) — individual restricted
SAT queries hitting the same kind of occasional pathological case `GADGET_XOR_SPEC.md` documents for
r2's production CaDiCaL path; real, not a soundness issue, but not something raw machine strength
fully absorbs either, since the pipeline is single-threaded (no `--workers` knob, unlike
`BenchmarkRunner`) — a stronger CPU and more free RAM help, but a single hard query still runs to
its own conclusion serially.

**3-WL was the natural next escalation and was not completed** — it crashed the machine running it
(O(n³) state space). Banned from re-running without explicit new authorization; see
`src/test/kotlin/dialysis/experimental/ScawlSeeded3WLTest.kt` and `Scawl3WLControlTest.kt`, both now
`@Disabled` with that reason stated directly in the annotation. The next real option for closing the
d3/z3 gap is a hand-derived ℤ₃/D₃ algebraic constraint analogous to §2.2's ℤ₂ construction below —
for D₃ specifically, a non-abelian generalization, materially harder than a tweak of the existing one.

**Does this solve anything, beyond `cfi-rigid-*`, that normal SAT doesn't already solve? No.**
Tested small-scale (27 instances, 15 non-`cfi-rigid` families, full per-vertex partition verified
against Traces, ~10 seconds total) — `FVS_SEEDED_1WL_SPEC.md` Part 8. FVS-seeded 1-WL alone matches
Traces on 25/27 instances (`mz`'s 2 instances genuinely under-refine, same shape as d3/z3). But the
real question is the baseline: running the SAME 27 instances through today's unrestricted
*production* SAT path (`driveToOrbitsCadical`, both preserve and side-swapped encodings —
`NormalSatOtherFamiliesControlTest`) **certifies and matches Traces on all 27/27, every one in under
15ms.** So plainly: outside `r2`/`z2` (and only above their own size crossover, §2.1's own table),
FVS-seeded 1-WL adds nothing — these families were never hard for the baseline, and on the one
family it actually gets wrong (`mz`), production gets it right, fast, for free. Same conclusion as
t2/s2's own result inside `cfi-rigid-*` (sound is not the same as useful), now confirmed to hold
past `cfi-rigid-*` too.

Along the way, found and fixed a real gap in the experimental harness itself: three families (`pg`,
`had`, `grid-w`, all self-dual bipartite structures) initially came back OVER-refining relative to
Traces — impossible for a correctly-seeded refinement per this section's own theorem — traced to the
harness never trying production's side-swapped encoding, which `cfi-rigid-*`'s own bipartite
families never needed (their two sides always differ in size/role). Fixed by using both encodings,
matching production; not a bug in `dispatchColouring`/`initialPhase` (both confirmed correct before
looking anywhere else) — this fix is what got FVS-seeded 1-WL itself to 25/27 above, before the
control test showed that number doesn't actually matter.

### 2.2 Gadget-XOR CryptoMiniSat — `r2`

The Cai-Fürer-Immerman gadget's port-consistency condition is a genuine GF(2) parity constraint. Hand
derive it as a native XOR clause (cluster-generalized to handle real files' merged/twinned ports, no
generator metadata available), feed it to CryptoMiniSat's Gaussian-elimination engine
(`dialysis.gadgetxor`, `dialysis.sat.cryptominisat`) instead of solving with plain CDCL.

| Family | Sound? | Fast? | Verdict |
|---|---|---|---|
| **r2** | Yes — 0 mismatches, every size, 398-instance full sweep n=68–3600 | **Yes** — up to 42.6× faster than CaDiCaL at n≥2448 (140× on CaDiCaL's single worst straggler); no straggler variance of its own | **Solved, recommended** (`--preset=r2-xor`) |
| t2 | Yes — 0 mismatches, after a safety-net fix | No — plain CryptoMiniSat (no XOR) is already ~8× *slower* than CaDiCaL on t2's denser bypassed structure | Not recommended; CaDiCaL remains right for t2 |
| s2 | N/A | N/A | Not attempted at scale — cluster reconstruction's port/clique union step over-merges on ~100% of gadgets tested |

Reproduce: `./gradlew shadowJar && scripts/run_benchmark.sh --preset=r2-xor` → `results/r2-xor.csv`.
See `GADGET_XOR_SPEC.md` for the derivation, the two real upstream bugs found and fixed before
trusting any CryptoMiniSat output, and the t2/s2 negative-result detail.

**Confirmed this session** (`results/r2-xor.csv`): full 398/398-instance sweep, n=68–3600, every row
`CERTIFIED` (`unknown == 0`), completed in about 18 minutes single-run wall clock. Cross-checked
against independently-computed Traces ground truth (`results/gt-r2.csv`, via
`scripts/merge_ground_truth.py`): 94/398 rows had a comparable ground-truth entry, **0
disagreements**.

## 3. What's sound but not a speed win, and why that's still worth recording

Three separate lines of work in this repo land on the same shape of result: **sound everywhere,
fast nowhere new**. That's not a wasted effort — each one rules out a plausible-looking idea with a
measured number instead of an assumption, which is exactly the discipline this project holds itself
to (see `feedback_empirical_verification` in this project's own working notes: build an isolated
diagnostic, don't explain away a surprising result).

- Recursive-decomposition query ordering/seeding (§1) — sound, no effect on either regime tested.
- FVS-seeded 1-WL/2-WL on t2/s2 (§2.1) — sound, pure overhead once the seeded set reaches 100% of n.
- Plain (non-XOR) CryptoMiniSat and gadget-XOR CryptoMiniSat on t2 (§2.2) — sound, slower than
  CaDiCaL on t2's denser structure either way.

**Multi-decomposition witness hunting** (`MULTIDECOMP_WITNESS_SPEC.md`) is a fourth line in this same
shape, but with one genuine narrow exception. The idea: recursively decompose a low-colour-class
family from a chosen root, encode against the small groups the decomposition's own signature
produces (instead of the whole class), prove pairs inside those small groups, then repeat from a
*different* root — or, in the stronger "clearing" variant, on the graph with already-proven orbits
quotiented away — and let union-find chain the results transitively. Every accepted witness is
verified in `O(m)` exactly as in the core method, so this can never produce a false result; it can
only fail to fully connect a class. Measured on the families the core method's per-query filter
struggles with:

- `latin-20`, `lattice-20` — negative; essentially every query under the decomposition's signature
  comes back UNSAT (the opposite of the near-vertex-transitive assumption the idea relies on).
- `ag2-16` — excluded outright: its decomposition signature barely splits the class at all (one group
  holds 94-99% of it, mechanically confirmed to be a property of the family, not an implementation
  gap — see spec §6.1).
- `triang-20` — **real positive under clearing**: plateaus at 56 residual components under a static
  graph, but the clearing variant keeps making progress across rounds down to 16 (120 pairs needing
  full certification, instead of 1,540).
- `had-64` — **real positive**: 256 → 9 → 5 components, only 10 residual pairs needing full
  certification, from cross-root chaining alone (no clearing needed).
- Swept across the whole `had` family (`had-1`–`had-76`) to see if this generalizes: it does not. Time
  per instance compounds sharply from `had-24` onward (roughly 25× wall-clock for under 2× the
  vertex count) and hits a hard wall at `had-44` even under a 600s per-instance timeout — see spec
  §6.4. Dropped as a family-wide strategy; the individual `had-64`/`triang-20` results above stand on
  their own.

Verdict matches this section's theme exactly: sound everywhere tried, a real but narrow win on two
specific instances, not adopted into `BenchmarkRunner` (the spec's own gate requires success on more
than one *family*, not two instances within one).

## 4. Open problems, ranked by what would actually move the needle

1. **d3/z3** — the highest-value remaining gap. WL-style refinement (1-WL, 2-WL, the project's own
   `initialPhase`) provably cannot close it; a hand-derived ℤ₃/D₃ algebraic gadget constraint
   (§2.1's 3-WL escalation is banned — see above) is the only avenue identified so far, and is
   materially harder than the ℤ₂ case already solved in §2.2 because D₃ is non-abelian.
2. **`ag2-16`, `latin-20`** — still open under every adopted method (global filter, per-query filter,
   decomposition heuristic) and under the newer multi-decomposition witness-hunting idea too (§3,
   `MULTIDECOMP_WITNESS_SPEC.md`) — `ag2-16`'s decomposition signature doesn't split its class enough
   to even try, and `latin-20` returns essentially all-UNSAT under it. `had-64` and `triang-20`
   responded well to that same idea, so it is not a dead end in general, just on these two instances
   specifically.
3. **s2 gadget reconstruction** — the port/clique union step needs a different merge rule before
   gadget-XOR can even be attempted at scale here; not investigated further.

## 5. How to reproduce every table above

```
./gradlew test                                                          # everything except the unbounded full-campaign test
./gradlew test --tests "dialysis.FullCampaignSatPiTest"                 # full-corpus core-method sweep
scripts/run_fvs_campaign.sh r2 z2                                       # -> results/fvs-seeded-1wl.csv
./gradlew shadowJar && scripts/run_benchmark.sh --preset=r2-xor         # -> results/r2-xor.csv
./gradlew test --tests "dialysis.experimental.MultiDecompWitnessGateTest"       # §3's split-quality gate, no solver
./gradlew test --tests "dialysis.experimental.MultiDecompWitnessHuntCoverageTest"  # §3's coverage curve, one instance per invocation
```

See README.md's "Running the benchmark CLI", "Running the FVS-seeded 1-WL campaigns", and "Running
the CryptoMiniSat gadget-XOR campaign" sections for the full flag reference, resume semantics, and
machine-sizing notes (`--workers`, `JAVA_OPTS`/`-Ddialysis.tmpDir`).

**Do not run:** `SingleInstanceExplorationTest` (had-20 clause explosion, crashed the machine
repeatedly), `ScawlSeeded3WLTest`/`Scawl3WLControlTest` (3-WL, crashed the machine), and
`MultiDecompWitnessHuntCoverageTest.latin20` (SIGKILLs in its own repeated decomposition step,
independent of the encoding-size gate) — all four are `@Disabled` with that reason stated in the
annotation, left in the repo for their code, not for routine execution.
