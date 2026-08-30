# FVS-seeded 1-WL: does knowing a feedback vertex set's orbits solve the rest for free?

## The idea

`cfi-rigid-*` graphs are hard for 1-WL specifically because of their gadget cycle structure. A
**feedback vertex set (FVS)** — a vertex set hitting every cycle in the graph — is, by definition,
exactly the set whose removal turns the graph into a forest. Colour refinement (1-WL) is well known
to correctly compute the automorphism partition of a forest. So: if the TRUE automorphism orbits of
just the FVS (small relative to n) are known, seeding 1-WL with that information alone might already
be enough to refine the WHOLE graph down to its true orbit partition — without ever issuing an
orbit-mate SAT query on any non-FVS vertex. If it works, this cuts SAT calls from the current
practice (colour-admissible pairs across the *entire* graph) down to roughly `O(|FVS|^2)`.

This document records everything that was actually tried, found, and measured while chasing that
idea — including the parts that didn't pan out, and a follow-up investigation into *why* two
families resist it. Theorem or not, honestly.

---

## Part 1 — implementation

### 1.1 `dialysis.fvs.FeedbackVertexSet` (`src/main/kotlin/dialysis/fvs/`)

Implements Bafna, Berman & Fujito's `FEEDBACK` algorithm (SIAM J. Discrete Math. 12(3), 1999,
Figure 3.1) — a 2-approximation for minimum FVS, unweighted specialization (every vertex weight 1;
this project has no natural per-vertex weighting for `cfi-rigid-*` graphs). Repeatedly identifies a
semidisjoint cycle (every vertex degree 2 except at most one) or, failing that, the whole remaining
clean graph degree-proportionally weighted; subtracts a uniform slice, pushes zero-weight vertices
onto a stack and into F; a final reverse pass discards redundant vertices. Validated
(`FeedbackVertexSetTest`) against hand-known answers (triangle→1, 4-cycle→1, tree→0, two disjoint
triangles→2, K4→2) and directly confirmed to always leave a genuine forest on a real r2 file.

### 1.2 FVS orbit computation — the SAME SAT machinery already in production, restricted

`buildCadicalEncoding` + `queryOrbitMateCadical` (unmodified, exactly what `driveToOrbitsCadical`
uses) — but the query set only covers a **seeded set**, not the raw FVS (see 1.3).

### 1.3 The bug that had to be found and fixed first

The first attempt seeded 1-WL with "FVS vertices get their SAT-derived orbit label, everyone else
gets one shared colour" — and on `cfi-rigid-r2-0068-03-1` this produced 68 WL classes when the true
orbit count is 43. That's not just wrong, it's *impossible* for a correctly-seeded refinement:
colour refinement seeded with a colouring that is constant on every true orbit can only ever produce
a result that is a UNION of complete true orbits (`wlCells <= trueOrbits`, always — a basic,
certain theorem, since automorphisms preserve neighbour-colour multisets at every refinement round).
Getting *more* classes than true orbits is a direct proof the seed itself wasn't orbit-consistent.

Diagnosed directly: vertex 21 (in the computed FVS) and vertex 66 (not in it) are the SAME true
orbit, yet got different seed colours. **FVS membership is not itself an automorphism-invariant
property** — an approximate FVS has no reason to be a union of complete orbits; a real automorphism
can map an FVS vertex onto a non-FVS one just fine.

**The fix**: seed the *colour-class closure* of the FVS, not the raw FVS. Since same true orbit
implies same base colour (the base colouring itself is automorphism-invariant), extending the seeded
set to every vertex sharing an `encoding.groups` colour-admissible class with *any* FVS vertex is
guaranteed to capture the WHOLE of any true orbit that touches the FVS at all — closing the gap
exactly. Still far smaller than the full graph whenever the FVS only touches a modest number of
colour classes (r2/z2 — confirmed below); can swallow the entire graph when it doesn't (t2/s2 —
also confirmed below).

### 1.4 Verification

Small-scale runs used Traces' own `orbits()` call directly (nauty/Traces, an entirely separate
computation from the FVS/SAT/WL pipeline — not circular). The larger scale sweep switched to this
repo's own **already-CERTIFIED** `results/*.csv` files (CaDiCaL's `recovered_orbits` where
`status=CERTIFIED`, or `gt-d3.csv`'s dedicated `true_orbits`) instead of re-running Traces — same
trust level (CERTIFIED means independently verified sound at the time it was produced), much faster,
and avoids Traces' own well-known blow-up risk on large/symmetric instances.

---

## Part 2 — does it work? (r2/t2/s2/z2 vs d3/z3)

### 2.1 Small-scale validation (17 instances, 6 families)

| Family | Instances | Exact match | When it fails |
|---|---|---|---|
| r2 | 5 | **5/5** | — |
| t2 | 5 | **5/5** | — |
| s2 | 3 | **3/3** | — |
| z2 | 2 | **2/2** | — |
| d3 | 2 | 0/2 | under-refines |
| z3 | 2 | 0/2 | under-refines |

15/17 matched EXACTLY — verified vertex-pair by vertex-pair against Traces, not just a class-count
coincidence. Every d3/z3 failure was **under-refinement**, never over-refinement, consistent with
the theorem in 1.3 (a correctly-seeded refinement can fall short when 1-WL itself isn't powerful
enough, but can never exceed the true partition).

**Control**: plain 1-WL, uniform seed, no FVS help, on the same files — matched only 1/17. Confirms
the FVS seeding is doing real work, not riding on the graph already being trivial for 1-WL.

### 2.2 Scale sweep (100+ instances, real files up to n≈3600–4000, ground truth from cached CSVs)

| family | instances | matches | avg FVS % of n | avg seeded % of n | avg WL-orbits/true-orbits | max n tested |
|---|---|---|---|---|---|---|
| **r2** | 37 | **37/37 (100%)** | 20.5% | 24.2% | **1.0000** | 2592 |
| **t2** | 46 | **46/46 (100%)** | 75.7% | 100.0% | **1.0000** | 1488 |
| **s2** | 10 | **10/10 (100%)** | 81.9% | 100.0% | **1.0000** | 640 (full available range) |
| **z2** | 45 | **45/45 (100%)** | 20.2% | 23.5% | **1.0000** | 3960 (nearly full range) |
| **d3** | 18 | 0/18 (0%) | 18.5% | 20.0% | **0.6364** (constant) | 3240 |
| **z3** | 18 | 0/18 (0%) | 15.4% | 16.9% | **0.6364** (constant) | 3240 |

**The pattern holds exactly at scale, no degradation with size.** r2/t2/s2/z2 are perfect at every
size tested. d3/z3 fail at every size too, but the failure is *exactly* constant —
WL-orbits/true-orbits = 0.6364 (= 28/44 = 7/11) at every single size in both families, min=max=avg.
Not noise: a fixed structural gap independent of n (see Part 3 for why).

**"seeded % of n" explained** — not the raw FVS, but its *colour-class closure* (1.3): every vertex
sharing a base 1-WL colour class with any FVS vertex. Whether that stays close to the FVS or balloons
to the whole graph depends on how coarse the base colouring is:
- **r2/z2**: plain 1-WL from a uniform seed already splits the graph into *many, small* classes
  (e.g. `cfi-rigid-r2-0216-01-1`, n=216: 72 classes, avg size 3). The FVS (20%) only touches a
  fraction of them, so the closure stays small.
- **t2/s2**: the classic "CFI defeats 1-WL" phenomenon, hitting these two hardest — bypass makes t2
  ~4x denser than r2 (`GADGET_XOR_SPEC.md`), so plain 1-WL barely discriminates anything (e.g.
  `cfi-rigid-t2-0048-01-1`, n=48: only 12 classes total). With that few, large classes, an
  already-large FVS (76-82%) is virtually guaranteed to touch *every* class that exists, so the
  closure jumps straight to 100% — a threshold effect, not a gradual one.

**Reliability check — was Traces the bottleneck, not the technique?** Confirmed yes for the small
runs (ground truth via Traces took up to 117s at n=576 while the FVS+SAT+WL pipeline itself stayed
under a few seconds); switching the scale sweep to cached CERTIFIED CSVs removed that bottleneck
entirely and let the sweep reach n≈3600-4000 comfortably.

### 2.3 SAT-call reduction, precisely quantified

Comparing the restricted (seeded-set-only) query count against the *full admissible-pair count* the
unrestricted approach would need to consider, summed across every swept instance:

| Family | Total restricted queries | Total full admissible pairs | Reduction |
|---|---|---|---|
| r2 (37 instances) | 5,904 | 112,044 | **94.7%** |
| z2 (45 instances) | 10,720 | 223,560 | **95.2%** |

On t2/s2 there is no reduction to report — the seeded set already reaches 100% of n on essentially
every instance (2.2), so the restricted query set is the same as the unrestricted one.

---

## Part 3 — where the hardness in d3/z3 actually comes from

### 3.1 It's not the FVS size, and it's not the cycles

d3/z3's FVS% is *lower* than r2's (18.5%/15.4% vs 20.5%), so a bigger FVS isn't the issue. Direct
diagnosis on `cfi-rigid-d3-0180-01-1` (n=180, 44 true orbits, 28 WL cells), comparing per-vertex
against real Traces orbits: **16 WL cells are "merged"** (span more than one true orbit) — every
single one is *exactly* 9 vertices, all degree 6, spanning exactly 2 true orbits, and **zero** of
those 9 vertices are in the FVS or even in the seeded closure. 16×9 = 144 of 180 vertices (80% of
the graph) sit in these repeating blocks; the other 36 (the seeded set) are perfectly discrete.

So the ambiguity lives entirely in the "forest part" — as 16 identical, repeating local blocks that
are structurally indistinguishable to 1-WL (same degree, same neighbour-colour signature) but are
genuinely *not* the same orbit under the true automorphism group. Classic CFI/WL-hardness — two
locally-identical substructures differing only via a global constraint invisible to local
refinement — just showing up in a tree-shaped region instead of around a cycle. The block count
scales exactly linearly with n (confirmed: the constant 0.6364 ratio at every size), consistent with
this being a fixed-size, repeating gadget-level phenomenon, not a diffuse scaling effect.

### 3.2 Trying the project's own stronger refinement (`initialPhase`)

Both d3/z3 are bipartite (confirmed directly), so `initialPhase` (Phase 0 = 1-WL, then Phases 1-3:
per-vertex decomposition + AHU + remainder colouring — the same machinery `PI_DIST` uses in
production) runs on the graph as-is, seeded the same way.

| Family | n | plain-1-WL/true | initialPhase/true | Matches? |
|---|---|---|---|---|
| d3 | 180 | 0.6364 | **1.0000** | yes |
| d3 | 360 | 0.6364 | 0.6364 | no |
| d3 | 720 | 0.6364 | 0.6364 | no |
| d3 | 1080 | 0.6364 | 0.6364 | no |
| z3 | 180 | 0.6364 | 0.9318 | no (closer, not exact) |
| z3 | 360/720/1080 | 0.6364 | 0.6364 | no |

Helped at the very smallest size (fully solved d3 at n=180, got much closer on z3), but at every
larger size `initialPhase`'s extra phases contributed **literally nothing** beyond Phase 0 (plain
1-WL) — identical cell counts. So this isn't just "1-WL is too weak"; the project's stronger,
decomposition-based refinement also can't see past it once the instance is even moderately sized.

### 3.3 Trying to seed with more than just the FVS ("orphans")

Hypothesis: the 16 merged blocks are exactly the vertices `dialysis.decomposition.peel`'s recursive
BFS-tree decomposition (`DECOMPOSITION_ORDERING_SPEC.md` Part 3) flags as **orphans** — "isolated
vertices of G − V(T)", vertices the tree can't attach at all. Ran `peel` recursively, gathered every
orphan from every piece, and seeded with FVS ∪ orphans instead of FVS alone.

| Family | n | FVS-only ratio | FVS+orphans ratio |
|---|---|---|---|
| d3 | 180 | 0.6364 | 0.7500 |
| d3 | 360 | 0.6364 | 0.7841 |
| d3 | 720 | 0.6364 | 0.8636 |
| z3 | 180 | 0.6364 | 0.7727 |
| z3 | 360 | 0.6364 | 0.6818 |
| z3 | 720 | 0.6364 | 0.6364 (zero effect) |

Real but partial and inconsistent: d3 improves *and* the improvement grows with scale (unlike the
FVS-only case, which stays exactly flat); z3 barely improves and the effect vanishes by n=720. Cost
is real too — the seeded set roughly doubled-to-quadrupled for a partial win. Two families sharing
the identical 0.6364 constant do NOT share the same failure mechanism once probed further.

### 3.4 The actual root cause: d3/z3 use a different, more general CFI gadget

`cfi-rigid-d3`/`cfi-rigid-z3` are **not defined anywhere in this repository** — no generator script,
no spec. Traced to the source paper itself (Neuen & Schweitzer, "Benchmark Graphs for Practical
Graph Isomorphism", arXiv:1705.03686 / ESA 2017), Section 5, "CFI gadgets for other groups": the
standard `X₃` gadget (used by r2/t2/s2/z2) realizes `Δ ≤ (ℤ₂)³`, i.e. group `Γ = ℤ₂`. Section 5
generalizes to an arbitrary group Γ:

- **z3** = the cyclic-group construction with **Γ = ℤ₃**.
- **d3** = the dihedral-group construction with **Γ = D₃** (order 6, triangle symmetries).

Confirmed directly against the DIMACS files, not just the paper: inner-vertex degree = |Γ| (3 for
z3, 6 for d3), outer-vertex degree = 4|Γ| (12 for z3, 24 for d3) — and d3's whole degree histogram is
exactly 2× z3's in every bucket, matching |D₃| = 2|ℤ₃|. The "degree=6" finding in 3.1 is literally
`|D₃|`, not a coincidence.

**Direct check that the "outer vertices" ARE exactly what FVS+closure already computes** —
`cfi-rigid-d3-0180-01-1`:

```
degree histogram: {6: 144, 24: 36}          <- inner (144) vs outer/hub (36)
FVS ⊆ outer exactly (33 of 36, 0 inner)
seeded (FVS colour-closure) == outer, EXACTLY (all 36, only outer, 0 inner)
merged/ambiguous vertices = 144, ALL of them inner (0 outer)
outer vertices: 36 vertices -> 12 distinct true orbits, ALL correctly resolved
```

So the technique already gets the outer/hub vertices exactly right — the FVS and its closure
coincide precisely with the base graph's outer vertex class, and their true orbit structure (12
orbits over 36 vertices) is fully recovered. It just doesn't propagate from there: the 144 inner
vertices stay stuck in the same 16-block pattern regardless.

**Why**: for the ℤ₂ gadget, the consistency relation tying inner vertices to their gadget's outer
ports is a simple GF(2) *parity* condition — exactly the flip-XOR relationship `GADGET_XOR_SPEC.md`
encodes by hand, and simple enough that 1-WL can propagate it once outer identity is fixed. For
Γ=ℤ₃/D₃, the analogous condition is "the group elements around the gadget multiply to the identity
in Γ" — a genuinely richer algebraic constraint, and for D₃ specifically **non-abelian**. 1-WL's
purely local, multiset-based colour propagation has no mechanism to represent that at all. This is
the same underlying reason CFI constructions defeat WL in general — it's just showing up one level
deeper (inside the gadget) because the group carrying the hard part changed from ℤ₂ to something WL
genuinely cannot encode. A real fix would need a hand-derived, group-specific constraint analogous to
the ℤ₂ gadget-XOR clause, generalized to ℤ₃/D₃ — for D₃, a non-abelian generalization, a materially
different (and harder) piece of work than a tweak of the existing one.

---

## Part 4 — does any of this make the production pipeline faster?

Measured directly, same machine, same instance, back to back — not projected from query counts alone
(cached `results/*.csv` timings are from a different, faster machine and were deliberately NOT used
for any of these comparisons).

### 4.1 The certification asymmetry

A correctly-seeded WL result can only ever be a *union* of true orbits, never a split (Part 1.3's
theorem). So: two vertices in **different** WL cells are provably different true orbits — no
verification ever needed. Two vertices in the **same** WL cell are *not* provably the same orbit
(exactly what over-refinement would look like, though the fix in 1.3 prevents it) — unless the WL
result is **fully discrete** (every vertex its own cell), in which case that alone is a mathematically
airtight proof the graph is rigid, with zero further SAT calls needed at all.

Checked how often full discreteness actually happens in the 2.2 sweep:

| Family | Reached full discreteness |
|---|---|
| r2 | 36/37 |
| z2 | 44/45 |
| t2 | 41/46 |
| s2 | 9/10 |

So on the large majority of real instances in every one of these four families, FVS-seeded 1-WL
doesn't just get close — it produces a *free, self-certifying* proof of rigidity, no SAT verification
of the WL result required at all.

### 4.2 r2/z2: real, measured speedup — but only above a real crossover point

Originally asserted here from 2.3's query-count reduction and 4.1's free-certificate rate alone,
without ever actually building the same-machine, same-instance, back-to-back timing table 4.3/4.4
built for t2/s2/d3 below. A first attempt at that comparison, done by joining this campaign's own
timings against the cached `results/r2-sat.csv` production numbers, showed a much noisier picture
than "genuine win" implies — some sizes many times faster, several sizes net *slower* — which turned
out to be exactly the cross-machine confound this Part's own intro warns about: `r2-sat.csv`'s
timings and this campaign's were never captured on the same machine. Redone properly
(`FvsFirstVsFullR2Test`, mirroring 4.3/4.4's own methodology — both approaches, same JVM run, same
instance, back to back, no cached CSV involved):

```
n=288:  production=11ms/336q    | hybrid=25ms/34q    -> 0.44x (slower)
n=576:  production=73ms/672q    | hybrid=80ms/69q    -> 0.91x (slower)
n=936:  production=753ms/1092q  | hybrid=744ms/120q  -> 1.01x (wash)
n=1296: production=1399ms/1512q | hybrid=1420ms/159q -> 0.99x (wash)
n=1656: production=3482ms/1932q | hybrid=2509ms/206q -> 1.39x
n=1944: production=4903ms/2268q | hybrid=3894ms/240q -> 1.26x
n=2160: production=33271ms/2520q| hybrid=10007ms/268q-> 3.32x
n=2448: production=23091ms/2856q| hybrid=12966ms/296q-> 1.78x
```

Sound at every size (identical orbit count both ways). The real shape: **below roughly n≈600-900,
the hybrid is a wash or net slower** — production is already sub-100ms there, and the hybrid pays
FVS computation plus an extra 1-WL pass that a query-count reduction alone can't recoup. **Above
that crossover, it's a genuine and growing win** — up to 3.32× at n=2160 in this sample — because
that's where production's own CDCL search starts to occasionally struggle (matching this Part's own
d3 finding: the technique's edge is real specifically where CaDiCaL's plain search has room to be
beaten, not a uniform per-query constant). Query-count reduction is real throughout (~88-90% fewer
issued queries at every size tested here, in line with 2.3's separately-computed ~95% on the full
corpus) but does not translate 1:1 into wall-clock savings at small sizes where the query count was
already cheap to pay for outright.

**z2 was not separately re-measured this way** (same mechanism as r2 per 2.2's identical
percentage-reduction numbers; a same-machine z2 table would be the natural next check before citing
a z2 speedup with this same level of confidence).

### 4.3 t2/s2: no speedup — same work, plus overhead

Same direct comparison, on instances where 2.2 already showed seeded coverage hits 100% of n:

```
t2-0048: production=4ms/72q    | hybrid=11ms/72q   -> 0.36x (slower)
t2-0192: production=11ms/288q  | hybrid=29ms/288q  -> 0.38x (slower)
t2-0384: production=72ms/576q  | hybrid=105ms/576q -> 0.69x (slower)
s2-0064: production=5ms/94q    | hybrid=15ms/94q   -> 0.33x (slower)
s2-0256: production=10ms/384q  | hybrid=26ms/384q  -> 0.38x (slower)
s2-0512: production=22ms/768q  | hybrid=69ms/768q  -> 0.32x (slower)
```

Query counts are *identical* (seeded = 100% of n, so nothing is actually restricted), and the hybrid
is consistently 1.5-3x slower — pure overhead (computing the FVS, running 1-WL) with no compensating
saving.

### 4.4 d3/z3: no speedup either, for a different reason

```
d3-0180: production=6ms/2q    | hybrid=15ms/2q   -> 0.40x
d3-0360: production=84ms/36q  | hybrid=99ms/34q  -> 0.85x
d3-0720: production=653ms/66q | hybrid=671ms/66q -> 0.97x (~parity)
```

Here the query counts nearly match too, but for a different reason than t2/s2: `driveToOrbitsCadical`
already isolates the outer vertex class as its own colour class (by degree alone) and resolves it
cheaply via its own union-find pruning — restricting to FVS+closure doesn't unlock any pruning that
wasn't already happening for free. The genuinely expensive part (the 144-vertex inner class) needs
the same amount of real SAT work either way, restricted or not.

**Net conclusion**: the FVS-seeding trick is a genuine computational win specifically where it shrinks
the *set of colour classes worth querying at all* (r2/z2 only). Everywhere else tested it is either
redundant with CaDiCaL's own incremental union-find pruning, or costs more than it saves.

---

## Part 5 — "ask for a generating set, not pairs" (query scheduling)

Proposed optimization: for a colour class C, don't query all pairs — query one anchor vertex against
the residual, close via the witnesses found (generating sets for transitive groups are usually small,
often 2 generators), and only continue on what closure left unresolved.

**Already implemented — just not under that name.** `driveToOrbitsCadical`'s actual query loop
(`CadicalOrbitDriver.kt:57-68`) builds its queue as `for u in members: for v in members`, skipping
any pair already resolved via `SeparatingUnionFind`. This is mechanically identical to the proposed
scheme: the first not-yet-resolved vertex in each class plays the anchor role automatically (every
one of its queries is tried cold, since nothing has touched it yet), and the moment a witness unions
its whole discovered orbit, every later vertex that landed in that orbit has its *entire* row skipped
for free when the loop reaches it.

Verified, not just argued: built an independent, explicit anchor-scheduler (own union-find, own
loop — pick an anchor, exhaust the residual against it, drop its whole resolved orbit, repeat) and
compared query counts against production directly:

```
d3-0360:  production=36 queries  | anchor=36 queries  (identical)
d3-0720:  production=66 queries  | anchor=66 queries  (identical)
r2-0216:  production=252 queries | anchor=252 queries (identical)
r2-0864:  production=1008 queries| anchor=1008 queries (identical)
t2-0192:  production=288 queries | anchor=288 queries (identical)
t2-0384:  production=576 queries | anchor=576 queries (identical)
```

Identical on every instance tested, no exceptions. No query-count win available here — production is
already at (or very near) the group-theoretic minimum this idea points at.

---

## Part 7 — does FVS-seeded 2-WL close the d3/z3 gap?

Motivating hypothesis: 2-WL is strictly more expressive than 1-WL, so seeding it with the same FVS
orbits might succeed where 1-WL provably falls short. Tested using ScaWL
(github.com/CobySoss/ScaWL), an existing C++/MPI/OpenMP k-WL implementation, patched to accept a
seed colouring and expose the final per-vertex partition (upstream has neither — see below).

### 7.1 The patch

ScaWL's `2WL/scawl.cpp` (~4,800 lines) is built entirely around comparing two DIFFERENT graphs
(every struct field and function is a `rows1/cols1` vs `rows2/cols2` pair) and only ever returns a
boolean + a colour count, never the partition itself. Two additions, both at the boundary of the
algorithm — no changes to the internal multi-threaded hash-merging/bucket-sorting machinery
(`hash1`, `ThreadWorker`, `ComputeBestTeam`, `MergeSplitTables`):

1. **Self-comparison trick**: feed the SAME graph as both "graph1" and "graph2" — a mode the
   codebase already exercises itself (its own no-argument debug `main()` branch does this for a
   correctness self-check), so it's a proven-working path, not a novel combination.
2. **Seed injection**: add `seed(v) * OFFSET` to every entry of vertex v's row and column once, at
   load time, before the refinement loop starts. `RowsEqual` compares whole rows position-by-
   position, so two vertices with different seeds can never compare equal at round 1 regardless of
   adjacency, and — since round-1 colour ids are what every later round's signature is built from —
   the distinction propagates through every subsequent round for free, matching how
   `colorRefine1WL`'s own `initial` seed array is used exactly once.
3. **Output extraction**: after the loop converges, `graphData->allRowColors1[v]` holds vertex v's
   stable colour (confirmed by reading the code: it's exactly what the next round's bucket
   assignment reads as "vertex v's current colour", and nothing mutates it again before the loop
   exits) — dumped to a file.

No real MPI installation was available or needed: since this project only ever runs one process, a
~70-line stub `mpi.h` (real single-process semantics for `MPI_Barrier`/`MPI_Allgather`, since those
two are not behind a `worldSize > 1` guard; safe no-ops for everything else, which is behind that
guard) let the file build with plain `g++`.

### 7.2 Two real upstream bugs found and fixed before trusting any result

Never trusted an output from a modified third-party tool without first confirming the modification
itself, and the tool's own baseline, are correct:

1. **A memory-corruption crash (double-free), confirmed present in completely unmodified upstream
   `scawl.cpp`, unrelated to this patch.** Reproduced on a 3-vertex triangle graph with plain
   glibc malloc; the official build (which always links `jemalloc`) apparently tolerates it rather
   than fixing it — not something to build a result on. Building with `-fsanitize=address -O0`
   avoided the crash entirely (ASan's allocation patterns don't trigger whatever undefined behaviour
   causes it) and gave sane, verifiably-correct output on hand-checked synthetic graphs (a triangle:
   1 class; a 4-cycle: 1 class; a 4-cycle seeded with 2 different labels on alternating vertices: 2
   classes, exactly the expected split) — used for every real run from that point on. `-static-libasan`
   was needed to survive being launched via a JVM `ProcessBuilder` subprocess.
2. **A loop-termination bug, introduced by the seed patch itself, caught by a monotonicity check
   that should never fail.** First real-data run gave 13 classes on a d3 instance where 1-WL alone
   already achieves 28 — theoretically *impossible* (2-WL can never be less powerful than 1-WL) and
   therefore treated as a bug, not a result, before it was ever reported. Root cause: the pre-loop
   baseline colour count (`maxColor()`, called once before the refinement loop) reads the RAW,
   pre-hash array values directly as if they were already colour ids — valid only for vanilla 0/1
   adjacency, the only case upstream ever exercised. The seed offset makes those raw values large by
   design (that's what makes `RowsEqual` respect the seed), so the baseline became artificially huge
   (12,000,001), making the first REAL refinement round's small, properly-hashed colour count look
   like a decrease — terminating the `while(prev < current)` loop after exactly one round, before any
   genuine multi-round WL refinement happened. Fixed by forcing the pre-loop baseline to 0 whenever a
   seed is supplied, so continuation is governed entirely by `hash1`'s own returned counts each
   round, exactly as in the unseeded case. Confirmed via the same debug build
   (`-DMAX_COLOR_VALUES_AND_ITER`, an existing upstream flag): before the fix, 1 iteration; after,
   3 iterations with genuinely increasing colour counts (0 → 193 → 880 → converged).

### 7.3 Result, after both fixes

| Family | n | FVS-seeded 1-WL ratio | FVS-seeded 2-WL ratio | Same? |
|---|---|---|---|---|
| d3 | 180 | 0.6364 | 0.6364 | yes |
| d3 | 360 | 0.6364 | 0.6364 | yes |
| d3 | 720 | 0.6364 | 0.6364 | yes |
| z3 | 180 | 0.6364 | 0.6364 | yes |
| z3 | 360 | 0.6364 | 0.6364 | yes |
| z3 | 720 | 0.6364 | 0.6364 | yes |

**2-WL gives the IDENTICAL result to 1-WL on every instance tested — the hypothesis is refuted, not
confirmed.** This is consistent with, and further sharpens, Part 3.4's finding: 2-WL is strictly more
expressive than 1-WL *in general*, but the specific obstruction in d3/z3 (a non-abelian D₃, or
cyclic-but-non-ℤ₂, group-consistency constraint baked into the gadget itself) apparently isn't the
kind of local-aggregation blindness 2-WL's extra expressiveness addresses. Consistent with these
graphs being engineered (Neuen & Schweitzer, Section 5) specifically as harder benchmark instances
than the ℤ₂ construction — plausibly requiring higher k, or not solvable by any fixed k as these
families scale, which is exactly the kind of resistance a benchmark paper would aim to demonstrate.

---

## Part 6 — overall verdict

| Family | Sound? | Faster? | Why |
|---|---|---|---|
| **r2, z2** | Yes, always | **Yes, above n≈600-900 — real, measured (4.2)** | ~88-95% fewer SAT queries throughout; below the crossover the hybrid is a wash or net slower (query savings don't offset FVS/1-WL overhead when production is already sub-100ms); above it, up to 3.32× measured; free rigidity certificate on ~97% of instances |
| **t2, s2** | Yes, always | No — net slower | Seeded set reaches 100% of n; no query reduction possible, pure overhead added |
| **d3, z3** | No — provably under-refines, even seeded 2-WL doesn't help (Part 7) | No | Genuinely different, group-Γ (ℤ₃/D₃) CFI gadget; hardness lives inside the gadget's inner vertices, invisible to any WL-style local refinement (1-WL OR 2-WL); production's own pruning already isolates the cheap outer part for free |

**Not a universal theorem.** A real, substantial, measured win specifically on r2 and z2 above their
own size-dependent crossover point (4.2) — not a uniform win at every size, corrected from an
earlier, under-substantiated claim here. Confirmed
correct (never unsound) on every family tested, including a useful negative result on s2 — the one
family the gadget-XOR reconstruction technique (`GADGET_XOR_SPEC.md`) couldn't handle at all, FVS
seeding *is* sound there, it's just not a speed win. The d3/z3 failure is now precisely
characterized (Part 3) and confirmed to survive strictly more powerful refinement (Part 7's 2-WL,
Part 3.2's `initialPhase`, Part 3.3's orphan-augmented seeding all land short): it traces to a
different, richer CFI gadget group that would need its own hand-derived algebraic constraint (like
the ℤ₂ gadget-XOR clause, but for ℤ₃ or non-abelian D₃) to close — a materially different, harder
piece of future work, not a tuning knob on the current technique.

---

## Part 8 — past `cfi-rigid-*`: does it solve anything normal SAT doesn't? No (8.4).

Every result above is specific to the six `cfi-rigid-*` families. Motivating question: on families
that AREN'T engineered to defeat WL, does FVS-seeded 1-WL still recover the true orbit partition —
and, since these are generally much smaller instances, does it do so fast? Short answer, established
in full in 8.4: it recovers the right answer on 25/27 instances (8.2), but production's own
unrestricted SAT search *already* solves all 27/27, trivially and in single-digit milliseconds — so
even where FVS-seeded 1-WL is correct here, it isn't adding anything. Tested small-scale,
full-per-vertex-partition-verified against Traces directly (same discipline as Part 1's original
validation, not just an orbit-count check), across 15 families, 27 instances total, via
`FvsSeededOtherFamiliesTest`: `cfi`, `mz`, `mz-aug`, `mz-aug2`, `sts`, `sts-sw`, `rnd-3-reg` (the
families `FINAL_MEASUREMENTS_SPEC.md` Task 1 found NEED subdivision — 1-WL(original) alone is
useless, e.g. `cfi` is 1-vs-800 classes), plus `ag`, `pg`, `had`, `latin`, `paley`, `lattice`,
`triang`, `grid-w` (the low-colour-class families `FINAL_MEASUREMENTS_SPEC.md` Task 2 targets). Runs
in ~10 seconds total — confirms the "fast" expectation outright.

Because these families are NOT all in Task 1's "subdivision not needed" bucket the way every
`cfi-rigid-*` family is, this experiment uses `dispatchColouring(g, allowSubdivision = true)` (the
real production dispatch) rather than the `allowSubdivision = false` `FvsSeeded1WLExperimentTest`
hardcodes for `cfi-rigid-*` — forcing subdivision off here would collapse `cfi`/`mz`/`sts` etc.'s
base colouring to one giant class before the experiment even starts.

### 8.1 A real bug found and fixed before trusting any result: the side-swap gap

First pass, preserve-only encoding (exactly what `FvsSeeded1WLExperimentTest` uses): `pg`, `had`,
`grid-w` all came back with MORE WL cells than true orbits (e.g. `pg2-2`: 2 cells vs 1 true orbit) —
which `FVS_SEEDED_1WL_SPEC.md` Part 1.3's own theorem says is IMPOSSIBLE for a correctly-seeded
refinement. Treated as a bug, not a result, and diagnosed directly rather than assumed:

1. Checked the base colouring's own invariance first (cheapest thing that could be wrong): every
   vertex in all three graphs got the IDENTICAL colour, exactly matching each graph's true single
   orbit. Not the bug.
2. That meant the bug had to be in what "colour-admissible" means for the restricted SAT step.
   `computePreserveGroups` (`CadicalEncoder.kt`) groups by `(colorOf, degree, SIDE)` — so even a
   globally-constant colouring still splits one group per bipartition side. Confirmed directly: all
   three graphs have EQUAL-SIZED bipartitions (7/7, 2/2, 4/4) — self-dual incidence-style structures
   with a genuine side-swapping automorphism (`computeSwapGroups` merges both sides into one group;
   `buildCadicalEncodingSideSwapped` builds successfully for all three).
3. So the preserve-only restricted search was provably INCOMPLETE there: it can never find a witness
   that swaps sides, so it wrongly concludes two genuine orbit-mates on opposite sides are separated
   — which breaks the seed colouring's required precondition (same true orbit -> same seed colour)
   before 1-WL ever runs. Not a violation of the theorem, not a bug in `dispatchColouring`/
   `initialPhase` (both confirmed correct) — a gap in the harness itself: it never tried the
   side-swapped encoding production's own `driveToOrbitsCadical` already uses via its `swapPair`
   parameter. `FvsSeeded1WLExperimentTest` has the identical gap but never hits it, because every
   `cfi-rigid-*` bipartite family's two sides differ in size/role, so no swap is even possible there.

**Fix**: seed-closure and the restricted query loop both now also use
[`buildCadicalEncodingSideSwapped`] whenever the graph has an equal-sized bipartition (returns
`null` otherwise, so this is a no-op everywhere it doesn't apply) — closure is taken to a fixpoint
over BOTH the preserve groups and the swap groups, and queries are issued against both encodings,
exactly mirroring production's own two-encoding drive.

### 8.2 Result, after the fix

| Family | Instances | Match | Notes |
|---|---|---|---|
| `cfi` | 2 | 2/2 | subdivision-required family; `PI_TO_ORIGINAL` used |
| `mz` | 2 | **0/2** | genuine under-refinement (6/10, 12/20 classes) — see 8.3 |
| `mz-aug` | 2 | 2/2 | |
| `mz-aug2` | 2 | 2/2 | |
| `sts` | 3 | 3/3 | |
| `sts-sw` | 2 | 2/2 | seeded set already 100% of n (like t2/s2) — no restriction, but sound |
| `rnd-3-reg` | 1 | 1/1 | n=1000, seeded set already 100% of n |
| `ag`, `pg`, `had`, `latin`, `paley`, `lattice`, `triang`, `grid-w` | 12 | 12/12 | `pg`/`had`/`grid-w` needed the 8.1 fix; the rest already had trivial (size-1) true orbit counts or matched on the first pass |

**25/27 instances (14/15 families) match exactly, full per-vertex partition verified against
Traces.** `mz` is the one clean exception (8.3) — not a bug, a genuine capability limit, same shape
as the `d3`/`z3` result: 1-WL, even FVS-seeded, isn't always enough.

### 8.3 `mz`: a genuine, non-CFI negative result

`mz-2` (n=40): FVS-seeded 1-WL gives 6 classes; Traces gives 10. `mz-4` (n=80): 12 vs 20. Not a
side-swap issue (`mz` is non-bipartite here — `sideSwap=false`, `buildCadicalEncodingSideSwapped`
returns `null`). Plain 1-WL alone gives just 1 class on both (fully useless, matching Task 1's own
finding that `mz` needs subdivision) — so FVS seeding is doing real work (1 -> 6, 1 -> 12), just not
enough. Root cause not investigated further here (out of scope for a "does it generalize" sweep) —
would need the same kind of structural diagnosis Part 3 did for `d3`/`z3` to characterize precisely.

### 8.4 The actual question: does this solve anything normal SAT doesn't? No.

The real point of a speedup/capability technique is to help where the baseline struggles. Checked
directly, not assumed: ran the SAME 27 instances through TODAY'S UNRESTRICTED PRODUCTION PATH
(`driveToOrbitsCadical`, both the preserve and side-swapped encodings — exactly what `BenchmarkRunner`
runs, `NormalSatOtherFamiliesControlTest`).

**Production certifies AND matches Traces on all 27/27 instances — including both `mz` instances,
where FVS-seeded 1-WL itself failed.** Every wall-clock time is under 15ms.

So, plainly: **no, FVS-seeded 1-WL does not solve anything on these families that production's
normal, unrestricted SAT search doesn't already solve — trivially and fast.** These families were
never hard for the baseline in the first place (that's the whole reason `cfi-rigid-*` was engineered
as a separate, adversarial benchmark set); running FVS-seeded 1-WL on them either matches an answer
production already had for free (25/27 instances), or actively gets it WRONG where production
doesn't (`mz`, 2/27). This is the same conclusion Part 4.3 already reached for `t2`/`s2` inside
`cfi-rigid-*` itself — sound is not the same as useful — now confirmed to generalize past
`cfi-rigid-*` too: **the technique's entire measured value is confined to `r2`/`z2`, and only above
the size crossover Part 4.2 already established (`FINAL_REPORT.md` §2.1's own table).**

---

## Status

Experimental only (`src/test/kotlin/dialysis/experimental/`), not wired into `BenchmarkRunner` or any
production path. `FeedbackVertexSet` itself (`src/main/kotlin/dialysis/fvs/`) is genuinely reusable
graph-algorithm code, independently sanity-tested, and safe to depend on if r2/z2's win is pursued
into production. Key files:

- `dialysis.fvs.FeedbackVertexSet` — the FVS algorithm (main source, reusable).
- `FeedbackVertexSetTest` — hand-checked correctness (main regression suite).
- `FvsSeeded1WLExperimentTest` — small-scale, full-partition-verified validation across 6 families.
- `FvsSeeded1WLScaleTest` — the 100+-instance scale sweep, ground truth from cached CERTIFIED CSVs,
  resumable (writes to `results/fvs-seeded-1wl.csv`, same resume-by-instance convention as
  `BenchmarkRunner`); also reachable as a standalone CLI, `dialysis.fvs.FvsCampaignRunner`, for
  running on a machine that only has the shadow jar plus one family's files (see
  `scripts/package_fvs_campaign.sh`).
- `FvsSeededOtherFamiliesTest` — Part 8's 15-family, 27-instance generalization check beyond
  `cfi-rigid-*`, including the side-swap-aware encoding fix (8.1).
- `NormalSatOtherFamiliesControlTest` — Part 8.4's control: production's own unrestricted SAT path
  on the same 27 instances, showing it already solves all of them (the actual answer to "does this
  help").
- `FvsSeededInitialPhaseD3Z3Test`, `FvsPlusOrphansD3Z3Test`, `D3MergeDiagnosticTest`,
  `D3OuterVertexCheckTest` — the d3/z3 root-cause investigation (Part 3).
- `FvsFirstVsFullD3Test`, `FvsFirstVsFullT2S2Test`, `FvsFirstVsFullR2Test` — the direct,
  same-machine speed comparisons (Part 4).
- `AnchorQuerySchedulingTest` — the query-scheduling check (Part 5).
- `ScawlSeeded2WLTest` — the FVS-seeded 2-WL check (Part 7). Depends on a compiled
  `scawl_seeded.exe`, built from `external/scawl-patch/scawl_seeded.cpp` (see that directory's own
  README for the exact build command) — not built automatically by `./gradlew`, and not something to
  wire into CI; a one-off binary this test shells out to via `ProcessBuilder`.
