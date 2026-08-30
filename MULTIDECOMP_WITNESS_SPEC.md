# Multi-Decomposition Witness Hunting — spec for Claude Code

Self-contained. Targets the low-colour-class families (`ag`, `latin`, `lattice`, `paley`, `triang`,
`had`, `pg`, `grid-w`) where a single global formula is too large to build — `latin-20` needs ~4.5M
edge-conflict clauses before any query is asked.

---

## 1. The idea

The global colouring leaves one or two classes of size `Theta(n)`, so the shared formula is enormous.
A recursive decomposition rooted at some vertex splits that class into many **small** groups. Encode
against *those* groups instead: many tiny formulas rather than one impossible one. Prove what you can,
then re-run with a **different root**, obtaining a different split, and let union--find merge the
results transitively across runs.

```
class C = {x1..x8}, all genuinely orbit-mates

decomposition rooted at r1:   groups {x1,x2,x3}, {x4,x5,x6}, {x7,x8}
    -> small formulas; witnesses prove x1~x2~x3 and x4~x5~x6 and x7~x8

decomposition rooted at r2:   groups {x1,x2,x6}, {x4,x5,x8}, {x3,x7}
    -> witnesses prove x1~x6 (bridges the first two components)
                       x4~x8 (bridges into the third)
                       x3~x7

union-find over all witnesses: x1..x8 in one component
```

The decompositions do not need to be canonical, invariant, or related to each other. They need only
**overlap** across runs, so the components they prove can be chained.

---

## 2. Soundness — the one rule that matters

The decomposition colouring **is not an isomorphism invariant**: it depends on the arbitrary root. So
it may exclude a genuine automorphism, and an unsatisfiable result under it means only

> *no automorphism preserving this decomposition's colouring*

which is **not** the same as *no automorphism*. Acting on it would create a false separation — the one
error class this pipeline cannot detect, since UNSAT produces no model to verify.

**Therefore:**

```
SAT   -> decode alpha; VERIFY against G in O(m); if it verifies it IS an automorphism of G,
         regardless of how the formula was filtered. Union. Sound unconditionally.

UNSAT -> DISCARD. Conclude nothing. Do NOT add to sep. Do NOT mark the pair resolved.

UNKNOWN -> discard likewise.
```

This costs nothing on the target families, because they are vertex-transitive or near it: essentially
every pair is a genuine orbit-mate pair, so essentially every query is a SAT. The mechanism is a
**witness generator**, not a decision procedure.

**Put this rule in a comment at the top of the module.** A future reader will be tempted to record the
UNSAT results, and doing so silently corrupts the output.

---

## 3. The algorithm

```
WITNESS_HUNT(G, C, k_max):
    # C is a colour class of the global invariant colouring, |C| large
    U <- union-find over C
    roots <- ROOT_SCHEDULE(G, C, k_max)          # see 3.2

    for r in roots:
        (pieces, sig) <- DECOMPOSE(G, r)          # 3.1
        groups <- partition C by sig              # small groups inside C
        for each group g with |g| > 1:
            u <- representative of g
            for v in g \ {u}:
                if U.find(u) == U.find(v): continue
                phi <- ENCODE(G, filter = sig)    # formula admits (i,j) only if sig(i) == sig(j)
                solve phi and x_uv
                if SAT:
                    decode alpha; VERIFY in O(m); abort on failure
                    for every w in V: U.union(w, alpha(w))
                    record alpha as a generator
                # UNSAT / UNKNOWN: discard, per section 2
        record components_remaining(C) after this root
        if components_remaining(C) == 1: break

    return U, generators, components_remaining
```

### 3.1 The decomposition and its signature

Reuse the existing `Peel` / dialysis machinery. For each vertex record

```
sig(v) = ( piece_content_key(piece of v), ahu_position(v) within that piece, depth(v) )
```

`piece_content_key` must be content-addressed — the piece's uncoloured certificate plus its boundary
colour multiset — never an index in a list, or the split varies between runs for reasons unrelated to
the root. **Note:** on non-bipartite input the dialysis tree may contain an intra-layer edge and AHU is
then meaningless; in that case use the piece key alone and set the AHU field to a constant.

The formula is built with `sig` as the admissibility filter, intersected with the global colouring:
`x_ij` exists only if `colour(i) == colour(j)` **and** `sig(i) == sig(j)`.

### 3.2 Root schedule

Roots must produce *different* splits, or the second run repeats the first.

```
ROOT_SCHEDULE(G, C, k_max):
    pick roots from C itself, spread out:
        r_1 <- any vertex of C
        r_{t+1} <- the vertex of C maximising min distance to {r_1..r_t}   (farthest-first)
    return the first k_max
```

Farthest-first traversal is cheap and deliberately diversifies. Record the roots used, so a run is
reproducible.

---

## 4. The gate — measure before building the full loop

**This is the decisive measurement and it decides whether the rest is worth writing.**

### 4.1 Split quality (no solver required)

On `ag2-16` (2 classes, ~264 each) and `latin-20` (n = 400):

```
for k = 1..8 roots from ROOT_SCHEDULE:
    decompose, compute sig
    report, per colour class C:
        number of distinct sig values within C
        group size distribution (min / median / max)
        formula size for the largest group: variables, edge clauses
```

**Reading it.**

```
groups of size <= ~30, formula per group in the thousands of clauses
    -> the encoding problem is solved; proceed to 4.2

one group holding ~95% of C (as observed previously: sizes [255, 1])
    -> the decomposition does not split this class; the idea fails here for the same reason
       the global colouring does, and the negative is explained by this number alone. STOP.
```

Prior data point, for calibration: an earlier signature computation on `ag2-16` gave splits
`[255, 1]`, i.e. no useful split; a later corrected computation gave 2 distinct signatures on `ag`
but 7–107 on `had`, `latin`, `lattice`, `triang`, `paley`, `grid-w`. So expect this to **fail on `ag`
and possibly succeed on the others** — which is itself a publishable distinction if it holds.

### 4.2 Coverage (the real question)

Only if 4.1 passes. Still no full-formula solving — this measures whether the *chaining* works.

```
run WITNESS_HUNT with k_max = 8
after each root, record: components_remaining in C, witnesses found, cumulative SAT queries, time
plot components_remaining against k
```

**Reading it.**

```
reaches 1 within a few roots        -> the class is certified connected. This rescues the family.
plateaus at p > 1                   -> p residual components. They are either distinct orbits or
                                       merely unreached; the method cannot tell. See section 5.
never drops much                    -> the witnesses do not chain; the groups from different roots
                                       do not overlap enough. Report and stop.
```

Report `components_remaining` as a function of `k` for every tested instance — this curve is the
result, positive or negative.

---

## 5. Termination and what the result means

Union--find merges are **proofs**: every one rests on a verified automorphism. So the final components
are unions of true orbits, never splits — the same soundness direction as the FVS-seeded refinement
argument.

Two outcomes:

```
components_remaining == 1 for every class
    -> the colour classes are exactly the orbits, PROVEN, with no full-formula query at all.
       This is a certified result for a family previously out of reach.

components_remaining == p > 1
    -> the partition is a refinement of the truth. The residual question is whether those p
       components are distinct orbits. That requires the COMPLETE encoding, but only on the
       C(p,2) representative pairs -- which may be a handful even when |C| is in the hundreds.
```

The second outcome is the honest architecture and should be reported as such: **cheap witness hunting
first, complete certification on the residual only.** Record how many residual pairs remain, since
that number determines whether the family is reachable at all.

If the residual queries are attempted, they must use the **global invariant colouring**, not `sig` —
only then is UNSAT a proof.

---

## 6. Reporting

```
instance, n, colour classes, |C| for the largest class
roots_used (the actual vertex ids), k_max
per root:  distinct_sigs_in_C, group_size_min/median/max,
           formula_vars_max, formula_clauses_max,
           queries_issued, sat, unsat_discarded, unknown_discarded,
           witnesses_verified, components_remaining_after
final:     components_remaining, residual_pairs = C(components,2),
           total_time, total_sat_queries
control:   orbit count from ground truth where available; components_remaining must be
           >= true orbit count within C, never below   <- ASSERT, abort if violated
```

The control assertion is the safety net: union--find components are unions of true orbits, so their
count can never fall below the true orbit count within the class. A violation means a witness was
accepted that is not an automorphism, i.e. the `O(m)` verification is broken.

---

## 6.1 Result of 4.1 (measured, see `dialysis.experimental.MultiDecompWitnessGateTest`)

Reuses the existing `peel`/`pieceKey`/`positionSignatures` machinery unchanged (Section 3.1's `sig`
construction was already built for DECOMPOSITION_ORDERING_SPEC.md); only the root override and the
farthest-first 3.2 schedule are new. 8 roots each, no solver:

```
instance      class(es)      distinct_sigs   largest_group_%_of_C   formula_edge_clauses (largest group)
lattice-20    400            39              9.5%                   38,988   (was 4.56M-scale global)   PASS
latin-20      400            34-38           14.3%                  137,142                             PASS
triang-20     190            19              18.9%                  31,248                              PASS
had-64        256            7               48.4%                  499,720                             WEAK PASS
ag2-16        272 / 256      3 / 2           94.1% / 99.6%          983,040 / 1,036,320                  FAIL
```

Matches the spec's own prior calibration almost exactly: `ag2-16`'s 256-class reproduces the cited
`[255, 1]` split verbatim. **Verdict: proceed to 4.2** on `lattice-20`, `latin-20`, `triang-20`,
`had-64`; `ag2-16` is excluded (the predicted negative — itself worth reporting as the
family-dependent boundary the spec called out).

**Correction, checked directly:** the numbers above were first measured with `peel`/`dialysisPerSpec`
run on the ORIGINAL graph. That is wrong for a small-diameter, dense graph (`ag2-16`: avg degree
~16.5) -- BFS from any root reaches almost the whole graph in 1-2 layers regardless of colour, so
the tree/AHU signature has almost nothing to discriminate on, the same diameter-collapse
[dialysis.refinement.dispatchColouring] already subdivides to avoid for its own base colouring.
Fixed: `peel` now always runs on `g.subdivided()` (original vertex ids are unchanged by
subdivision, so no id-translation is needed anywhere a signature is looked up).

Re-measured with the fix: `lattice-20`/`triang-20`/`had-64` are byte-identical (they were already
non-bipartite and happened to already route through a subdivision-equivalent structure in the
original conditional version of this fix). `latin-20`'s signature count rose (34 -> 72-79 distinct
sigs) with no change to its largest group. **`ag2-16` is also byte-identical to its
pre-fix numbers** (confirmed subdivided: decomposition graph grew from 528 to 4880 vertices, same
3/2 distinct sigs, same 94.1%/99.6% dominance, same formula sizes to the exact integer). Diameter
collapse on the original graph is therefore NOT the explanation for `ag2-16`'s failure -- something
else about this family defeats the piece/AHU signature regardless of which graph it's computed on.

**Second correction, checked directly:** `peel` itself is also not fully recursive "until amenable
bases" in the sense Section 3 implies -- it recurses on a piece's REMAINDER
(`DECOMPOSITION_ORDERING_SPEC.md` line 115's own termination argument, correct for that earlier
task) but treats the QUOTIENT piece it extracts -- the dialysis tree itself -- as terminal
regardless of size or how little its one AHU/depth signature discriminates. On `ag2-16` the first
decomposition's tree alone held 273 of 528 vertices (89.8% of the subdivided graph) and was never
looked at again. Fixed in `decomposeWithRoot` (`WitnessHunt.kt`): every QUOTIENT piece is now
re-peeled on its own vertex set, with the signature just computed folded into the next level's
colouring, exactly the same shrink-and-recurse `peel` already does for remainder components, now
extended to the tree it stops at.

Re-measured with THIS fix, on all five instances: **no change anywhere, not just `ag2-16`** --
confirmed mechanically, not just empirically: re-peeling the extracted tree's own vertex set (with
the enriched colouring) returns exactly one BASE piece unchanged, because `peel`'s
`isTreeSub = sub.n <= 1 || sub.m == sub.n - 1` check fires BEFORE the colouring is ever consulted,
and a dialysis-extracted tree's vertex set, by construction of the admission rule (exactly one
tree-neighbour in the previous layer -- no vertex can pick up a "shortcut" edge to another tree
vertex without violating that), always induces a genuine tree in the graph. So this recursion path
is structurally guaranteed to terminate at depth 0 for every tree piece, on every instance tested --
it can only ever help the REMAINDER, which `peel` already recursed into on its own before this fix.

**Conclusion:** the piece/AHU signature's discriminating power is bottlenecked by the colored-AHU
labelling of ONE tree per root, not by insufficient recursion depth -- there is no further
structural decomposition available within this framework once a piece is confirmed tree-shaped.
`ag2-16`'s failure means its dialysis tree, from every one of the 8 tried roots, is itself deeply
symmetric. This is now a settled, mechanically-verified negative, not an open implementation gap.

## 6.3 "Clearing": quotienting by proven orbits between rounds (measured, see `WitnessHunt.kt`)

A real gap, distinct from everything above: `witnessHunt` (Section 3 as literally written) only
varies the ROOT across rounds -- it always decomposes the same static, uncollapsed `G`. It never
QUOTIENTS already-proven orbits out of the graph before the next round's decomposition, even though
Section 3's own "transitively merge orbits" framing implies exactly that. Implemented as
`witnessHuntWithClearing` (`WitnessHunt.kt`): before each round, contract every current
union-find component into one vertex (`quotientGraph`), decompose THAT (structurally different --
not just differently rooted -- since contraction changes degrees and BFS distances outright), lift
the resulting signature back to original vertex ids, and encode/verify against the REAL,
uncontracted `G` as always (soundness unchanged: Section 2/8's rules apply identically).

**Safety gap found and fixed first**: neither `witnessHunt` nor `witnessHuntWithClearing` had
`BenchmarkRunner`'s own `estimateGlobalEncodingSize` gate before calling `buildCadicalEncoding` --
running the (colour, sig) formula ungated on `ag2-16` (whose `sig` barely discriminates its class,
see 6.1/6.2) reproduced the EXACT SAME O(k^3) bijection-clause catastrophe from tonight's campaign
crash, SIGKILLing the JVM within 49 seconds. Fixed: `tooLargeToEncode` checks both `edgeConflictClauses`
and `bijectionClauses` against `BenchmarkRunner`'s own 2,000,000 default before ANY solver is built,
reusing the validated threshold rather than re-deriving a new one.

**Result, re-measured under the safety gate, one instance per process (running several together in
one JVM risks the same native-memory accumulation `malloc_trim` was added for earlier tonight):**

```
instance      NO_CLEARING final       CLEARING final              verdict
ag2-16        skipped every round (edge/bijection clauses over threshold from round 1) -- clearing
              never gets a chance: it only helps AFTER a first witness seeds the union-find.
had-64        skipped every round (~2.17M summed bijection clauses, just over the 2M cutoff --
              user's call: stay conservative, exclude rather than raise the threshold)
lattice-20    skipped every round (~2.45M summed EDGE clauses this time, not bijection -- many
              small-to-modest groups with many cross-group edges in a fairly dense graph, m=7600)
latin-20      untested for clearing -- SIGKILLed independent of the encoding gate, in the repeated
              decomposition step itself (16 `decomposeWithRoot` calls across both variants on an
              11,800-vertex subdivided graph); not pursued further given the effort already spent
triang-20     190 -> 56 (k=1) -> 31 -> 19 -> 17 -> ... -> 16, CONTINUOUS progress across multiple
              roots, vs. plateauing at 56 forever under NO_CLEARING            REAL POSITIVE
```

**`triang-20` is decisive, positive evidence for the idea.** Under the static-graph approach it
plateaus at 56 components after the very first root and never improves across 7 more roots (the
exact "witnesses do not chain" negative recorded in 6.2). Under clearing, EVERY one of several
subsequent rounds finds new witnesses the static approach could not, because each round decomposes a
genuinely different (contracted) graph -- ending at 16 residual components (120 pairs needing full
certification) instead of 56 (1540 pairs). This is the mechanism the static approach was missing,
confirmed on the one instance small enough to test end-to-end without hitting either safety
boundary.

**Standing limitation, not yet resolved:** the size gate and the native-memory cost of repeated
decomposition together mean this can currently only be VALIDATED on instances small/sparse enough to
clear both -- `triang-20` here. Whether clearing would also rescue `lattice-20`/`had-64` (both close
to, not wildly over, the size cutoff) is an open question this session did not answer; scaling this
up (raising limits deliberately with memory watched, or reducing per-round cost) is the natural next
step if this is pursued further.

---

## 6.2 Result of 4.2 (measured, see `dialysis.experimental.MultiDecompWitnessHuntCoverageTest`)

Real SAT solving this time (`buildCadicalEncoding` against the combined `(colour, sig)` key,
`queryOrbitMateCadical` per pair, every SAT witness verified in O(m) before any union -- no UNSAT
or UNKNOWN ever recorded, per Section 2/8), run on all four families that passed 4.1, `k_max=8`:

```
instance      class  k=1 witnesses   components after k=1   final components   residual pairs   total queries
latin-20      400    0               400 (no change)         400               79,800           2,921
lattice-20    400    1               210                      210               21,945           2,737
triang-20     190    9               56                       56                1,540            1,161
had-64        256    7               9                        5 (k=3 added 2 more)   10           521
```

`latin-20` and `lattice-20`: essentially every query comes back UNSAT under `sig` -- the opposite of
the spec's own assumption ("essentially every query is a SAT" on these near-vertex-transitive
families). `triang-20` collapses once at the first root, then flatlines for the other 7 (zero new
witnesses) -- 4.2's own "never drops much" failure mode, despite passing 4.1 cleanly. **4.1 split
quality does not predict 4.2 chaining success** -- worth stating on its own.

`had-64` is the one real positive: k=3 (a DIFFERENT root than k=1) found 2 more witnesses k=1 had
missed -- genuine cross-root chaining, not just one root's own lucky split. Final state: 5 residual
components, only 10 pairs needing full-formula certification (Section 5's second, honest outcome)
-- a family that was previously out of reach now needs at most 10 complete-encoding queries instead
of a ~500K-edge-clause global formula.

**Verdict:** do not fold into `BenchmarkRunner` (Step 5 requires success "on more than one family";
only `had` qualifies here). Recorded as a mixed result: negative on `latin`/`lattice`/`triang`,
a genuine (if narrow) positive on `had` -- worth finishing (the 10 residual pairs, full encoding)
if `had`'s certification is wanted, but not worth generalizing the mechanism further on this
evidence.

Re-verified after 6.1's subdivision fix (Section 3.1 decomposition now runs on `g.subdivided()`,
not the original graph): the verdict is unchanged. `had-64` is still the only success (256 -> 9 ->
5, identical); `latin-20`/`lattice-20`/`triang-20` are still negative (`latin-20` still finds zero
witnesses across all 2,606 queries). The fix mattered for explaining 4.1's numbers correctly, not
for this outcome.

---

## 6.4 Family-wide sweep on `had` (measured, then dropped — 2026-08-30)

Ran `witnessHuntWithClearing` across every `had-*` instance (`had-1` through `had-76`), one JVM
process per instance, memory-watchdog-guarded, `kMax = 5`, cross-checked inline against
`TracesJni`'s true orbit count per class (`control_ok` below is that assertion, not a self-report).
Results for the instances that completed, in `results/multidecomp-had.csv`:

```
instance   n     components_remaining  true_orbits  total_time_ms
had-1      4     2                     1            26
had-2      8     1                     1            40
had-4      16    4                     1            41
had-8      32    1                     1            127
had-12     48    1                     1            257
had-16     64    1                     1            696
had-20     80    16                    1            1,340
had-24     96    1                     1            20,759
had-28     112   32                    1            11,036
had-32     128   16                    1            67,902
had-36     144   32                    1            214,421
had-40     160   20                    1            513,788
had-44     176   —                     —             >600,000 (timed out)
```

Every completed instance passed the control assertion (`components_remaining >= true_orbits_in_C`,
in fact always `== true_orbits_in_C` where fully connected). But `total_time_ms` is not just slow, it
is **compounding**: `had-24` to `had-40` is roughly a 25x increase in wall-clock for a less-than-2x
increase in `n`. Doubling the per-instance timeout from 120s to 600s only bought two more instances
(`had-36`, `had-40`) before hitting the same wall at `had-44` — evidence that this is a real
super-polynomial blowup in this regime, not an artifact of an arbitrary cutoff, and that further
timeout increases would not be a productive way to spend wall-clock.

**Verdict: dropped as a family-wide solving strategy.** `had-44` through `had-76` were never
attempted; `HadFamilyWitnessHuntTest.kt` (the driver for this sweep) was removed during the
2026-08-30 repo cleanup — running all `had-*` instances as one routine `./gradlew test` class would
either hang for many minutes per un-gated instance or require the exact external
memory-watchdog/one-process-per-instance harness this sweep was run under, neither of which belongs
in a routine test run. The individual-instance data above (`had-1` through `had-40`) remains in
`results/multidecomp-had.csv` as evidence of where the mechanism does and does not scale; §6.2/6.3's
`had-64` result (a single hand-run instance, not part of this automated sweep) stands on its own and
is unaffected by this conclusion.

---

## 7. Order of work

```
1  4.1 on ag2-16 and latin-20                  -- split quality, no solver. May stop here.
2  4.1 on had-64, lattice-20, triang-20        -- the families where signatures were diverse
3  4.2 on whichever passed                     -- the coverage curve; this is the result
4  full loop + residual certification          -- only if 4.2 reaches or approaches 1
5  fold into BenchmarkRunner as a preset       -- only after 4 succeeds on more than one family
```

---

## 8. Guard rails

- **UNSAT under `sig` is never recorded.** Stated twice deliberately; it is the only way this
  mechanism can produce a wrong answer.
- **Verify every witness in `O(m)`** before any union. A rejection aborts the run.
- **Content-addressed piece keys only.**
- **Assert** `components_remaining >= true_orbits_in_C` wherever ground truth exists.
- **Record the roots used**, so any reported run can be reproduced exactly.
- **Do not merge this into the main certification path** until 4.2 succeeds; it is a witness
  generator, and the certified result still comes from the complete encoding on whatever remains.
