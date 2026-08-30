# Gadget-derived XOR clauses for CFI benchmark families

## Motivation

The orbit-mate SAT encoding (`INVARIANT_FILTERED_SAT_SPEC.md`) treats every automorphism candidate
as an unconstrained bijection subject only to colour and edge-preservation invariants. On
`cfi-rigid-*` instances this is provably complete, but it gives the solver no help exploiting the
*specific* algebraic structure of the Cai-Fürer-Immerman gadget construction those families are
built from (Neuen & Schweitzer, "Benchmark Graphs for Practical Graph Isomorphism," arXiv:1705.03686).
That structure is a genuine GF(2) parity condition (Section 2, Fig. 2.1 of the paper): a candidate
automorphism's action on one gadget's ports must flip an *even* number of them, or it cannot extend
to a real automorphism. This document records how that fact was turned into a hand-derived native
XOR clause, fed to CryptoMiniSat's Gaussian-elimination engine, and validated sound on real
benchmark files without ever assuming correctness in advance.

The standing rule throughout this work: never trust a derived invariant because it looks right on
paper. Every version of it was checked against real, independently-computed automorphisms (via
Traces) before being used for anything, and the actual SAT-encoding-level output (recovered orbit
partitions) was cross-checked against CaDiCaL's answer on every real file tested.

---

## Part 0 — what CryptoMiniSat actually contributes, and what it doesn't

CryptoMiniSat's own CDCL engine (MiniSat lineage) is not CaDiCaL-derived. It also vendors CaDiCaL
as a genuine dependency, but only reachable through an explicit `backboneSimplify()` call
(`cryptominisat_jni.cpp`, `CryptoMiniSatSolver.kt`) that hands the whole current formula to an
embedded CaDiCaL instance via `cadiback` for backbone computation — this is *not* on CMS's default
solve schedule. None of the driving code in this repository (`CryptoMiniSatOrbitDriver.kt`) calls
it, so on every result reported below, CaDiCaL sits linked-but-idle inside the CryptoMiniSat binary;
all solving is CMS's own engine.

Gaussian elimination is configured aggressively at solver construction
(`aggressiveGaussConf()`: `doFindXors=true`, `autodisablegauss=0`, `maxmatrixrows=150000`,
`simplify_at_startup=true`, `full_simplify_at_startup=true` — matching the CLI flag bundle
`--gauss 1 --autodisablegauss 0 --maxmatrixrows 150000 --matrixfinder 1`), so the occ-xor/Gauss
pipeline *does* run automatically on every query's first `solve()` call. But it only has anything
to do if there is XOR structure to find. Empirically confirmed twice — once on a from-scratch
synthetic CFI/multipede graph, once on a real `cfi-rigid-r2` file, both driven to full orbit
closure — that CMS's own `xorfinder` recovers **zero** XOR structure from the permutation-matrix
CNF encoding this project uses (`recoveredXorCount()` == 0 after driving). This is not a
configuration bug; it is a fundamental shape mismatch between "which vertex maps to which" bijection
clauses and the pattern `xorfinder` looks for. Native (`add_xor_clause`) XOR clauses, derived by
hand from the actual gadget mathematics rather than recovered automatically, are the only way to
give Gauss anything to act on for this class of encoding.

---

## Part 1 — the theorem, and why deriving it correctly is delicate

The Cai-Fürer-Immerman gadget `X₃` used by the multipede construction (Gurevich-Shelah) has 4
"inner" vertices realising the even-weight code `{000, 011, 101, 110}` over 3 bits, and, in the
un-bypassed construction, 6 "outer" port vertices `a(w)`/`b(w)` — two per port `w`, one per side —
each inner vertex connecting to exactly one side of each of its 3 ports according to its own bit
pattern. The textbook theorem: a bijection on `{a(w), b(w) : w}` that fixes each `{a(w), b(w)}` pair
*setwise* extends to a gadget automorphism iff an even number of the 3 pairs are actually swapped.

That theorem, as stated, assumes the automorphism keeps each port's identity fixed (maps `a(w)`
to either `a(w)` or `b(w)` — never to some other port's vertex). This assumption fails whenever the
underlying *base graph* itself has a non-trivial automorphism (a residual symmetry the paper's
"rigid" construction is not guaranteed to eliminate for every seed) — in that case a real
automorphism can map an entire gadget onto a *different* gadget wholesale, and a naive
per-gadget/per-port XOR clause built on the textbook theorem becomes **unsound**: it would exclude
a genuine automorphism. Two independent confirmations of this exact failure mode surfaced during
this work:

1. **The original synthetic validation itself was silently wrong.** The from-scratch multipede test
   graph used a "rotation" σ that, it turned out, gives the *base graph* its own non-trivial
   automorphism group. The validation formula in use at the time (`isBType(alpha(a(w)))`, a global
   a/b-pool-membership check) could not distinguish "genuinely fixed/swapped within its own port"
   from "silently permuted to an unrelated port" — 24/216 (generator, port) checks were mis-scored
   as non-violations. Fixed by (a) verifying against a σ confirmed (by direct search over the base
   graph's own automorphism group) to make the base graph rigid, and (b) adding a defensive
   same-port check that fails loudly instead of silently mis-scoring if this recurs.
2. **`cfi-rigid-r2-0648-04-2`**, one of only 2 (of ~66 sampled) real r2 files with any non-trivial
   automorphism at all, has this exact degeneracy: its one generator maps gadget `[53,134,277,572]`
   onto the *different* gadget `[164,219,282,581]` wholesale, fixing only the 2 physical outer
   vertices those two gadgets happen to share and swapping their two distinct third ports with each
   other.

### The generalized (cluster) invariant

Case 2 above was resolved algebraically, not by loosening the check. Summing the naive
per-gadget XOR value over *both* halves of a swapped pair, every port that both gadgets share gets
counted twice — and cancels mod 2 — leaving only the ports unique to one side or the other. That
generalizes cleanly:

1. Union-find gadgets into **clusters**: two gadgets sharing ≥ 2 physical ports (edges) get merged
   into the same cluster (r2's twins never share exactly 1 — that is ordinary base-graph adjacency,
   not degeneracy).
2. Within each cluster, count how many times each port is referenced across the cluster's gadgets.
   Only **odd-multiplicity** ports actually participate in that cluster's joint XOR clause.
3. A singleton cluster (the overwhelming common case) has all 3 of its own ports at multiplicity 1
   (odd), so this reduces to exactly the textbook per-gadget rule.

Validated (`RealFileFlipParityValidationTest`, `MultipedeFlipParityValidationTest`,
`MultipedeGadgetXorSoundnessTest`) against real Traces-computed automorphism *generators and their
pairwise products* (composition closure — generators alone are not the whole group, and an
invariant that isn't closed under composition would make an XOR clause built from it unsound) on
both the corrected synthetic construction and both known non-rigid real r2 files: **0 violations**.

---

## Part 2 — reconstructing gadget structure from real files

Real `cfi-rigid-*` files carry no generator/seed metadata — the port structure has to be recovered
purely from topology before any of the above can be applied to them.

### r2 (`R(B*(Gn,σ))` — base-graph-reduced, not bypassed)

Inner vertices are always degree 3 regardless of reduction; outer vertices (`a(w)`/`b(w)`, degree
`2×(gadgets attached)`) remain structurally distinct. `RealFileGadgetReconstruction.kt`:

1. Candidate gadget = a 4-set where every pair shares exactly one neighbour (patterns differ in
   exactly 2 of 3 bits) *and* the union of all 4 neighbour-sets has size exactly 6 — the second
   condition is necessary: pairwise-share-one alone cannot distinguish genuine gadget membership
   from a chain of coincidental single-neighbour overlaps through a shared high-degree outer "hub".
2. Global exact cover over all such candidates, with **partner-consistency enforced as a
   constraint during the search itself** (not checked after the fact) — multiple candidate blocks
   can each individually pass the local test yet disagree with each other about a shared hub
   vertex's true port-partner; checking after the fact can only detect that, not avoid picking an
   inconsistent solution when a consistent one also exists.
3. Per confirmed gadget, port-partners are identified as the exact set-complement of adjacent-member
   sets within that gadget.

Validated end-to-end: reconstruction → cluster-generalized flip-parity check against real Traces
automorphisms (0 violations, both files with any non-trivial automorphism) → wired into the actual
`CryptoMiniSatSolver` encoding (`RealFileGadgetXor.kt`) → confirmed to recover the **identical**
orbit partition CaDiCaL does, on both a rigid and the non-rigid real file.

### t2 (`R*(B*(Gn,σ))` — base-graph-reduced *and* bypassed)

Bypass (paper Section 4.2: "removing all `a`/`b` vertices ... and connecting inner vertices `mᵢ(v)`
to `mⱼ(w)` if both are connected to either `a(v,w)` or `b(v,w)`") removes the separate outer-vertex
class entirely — every vertex is a gadget member, with no degree-3 signature to key off. Confirmed
by direct construction (bypassing a real, already-reconstructed r2 file's known outer vertices by
hand and comparing to a same-parameter real z2/s2 pair) that "bypass = clique substitution per
removed outer vertex" is exactly correct: the derived graph matched the real file's degree
histogram and edge count exactly.

Reconstruction (`RealFileBypassedGadgetReconstruction.kt`) is a two-pass algorithm, arrived at after
several approaches that looked plausible but failed on closer empirical inspection:

- **Global maximal-clique enumeration was tried and rejected.** A true port-side clique can be a
  *strict subset* of a larger, coincidental maximal clique (proven directly: forcing an exact
  edge+vertex cover using only graph-maximal cliques as candidates found no solution at all on a
  real t2 file), invalidating that whole candidate space.
- **Local K4-search with the naive "external neighbour sets are equal" test was tried and
  rejected.** A gadget-mate pair's raw pairwise neighbour intersection is contaminated by that
  *same pair's own other 2 true gadget-mates* (who are mutually adjacent to both, being fellow K4
  members, for a completely unrelated reason) whenever the candidate group being tested is wrong —
  this produces convincing-looking "K4s" built from 2 unrelated real gadgets' members.

The algorithm that works:

1. **Pass 1** (handles the large majority of gadgets, zero false positives): for each vertex, find
   K4 candidates among its neighbours; for each of the 3 possible port-pairings, clean the raw
   pairwise-intersection pool via clique search (exact for pools ≤ 24, degree-ordered-greedy above
   that — correctness doesn't depend on the greedy step finding the true *maximum* clique, since the
   downstream degree-accounting check below rejects a wrong candidate outright, same as any other
   invalid one); require the 2 pools of each split to be disjoint. **The discriminator that achieves
   zero false positives on its own**: every member's real graph degree must be *exactly* accounted
   for by 3 internal K4 edges plus the summed sizes of its 3 cleaned external pools. A coincidental
   (non-gadget) K4 always leaves some real degree unexplained.
2. **Pass 2** (a rare residual "twin" degeneracy — the *same* base-graph-symmetry phenomenon as
   `cfi-rigid-r2-0648-04-2`, but at the reconstruction level rather than the automorphism level):
   vertices pass 1 can't resolve unambiguously form connected components (via leftover-restricted
   adjacency); within each, find same-degree K4 candidates via the same local per-vertex search
   (not a brute-force O(size⁴) scan over the whole component — that scan is fine for r2's rare
   8-vertex twin pairs but intractable on t2's denser, more frequently-ambiguous residual clusters),
   then exact-cover them. Multiple internally-consistent partitions of a twin cluster can be equally
   valid — this reflects genuine local graph symmetry the topology itself doesn't pin down further,
   not an algorithm defect — so any one found is accepted; final soundness is established
   empirically afterward (below), not by matching one arbitrary ground-truth labelling.

Port/clique identity recovery (needed for the flip invariant's role-labelling, since there is no
physical port vertex left to key a global label off): union-find, keyed by **(vertex, port index)**
pairs — a single vertex has 3 *independent* clique memberships, one per port it participates in, so
a plain `Map<vertex, cliqueId>` is the wrong data model (it collapses a gadget's own 3 ports into one
clique, since every gadget's own `ports()` derivation shares its first member across all 3 of its own
port pairs — confirmed as the root cause of an early, severe bug below). Two side-pairs from possibly
different gadgets are merged when an external vertex is adjacent to both members of one side and
neither member of the other (r2's own exact-complement principle, generalized).

**A confirmed, real soundness bug was found and fixed in this union step, both worth recording
because the fix generalizes.** Diagnosing why t2's Gauss/XOR machinery reported zero constrainable
clusters on every real file surfaced two distinct problems, in order:

1. **The "distinct ports=1" collapse.** The original `Map<vertex, cliqueId>` model (see above)
   silently merged an ENTIRE gadget's 3 ports into a single clique regardless of density — confirmed
   directly (a diagnostic dump showed every single vertex in a 216-vertex file resolving to the same
   one clique id). Fixed by the (vertex, port index) keying above.
2. **Two independent unsoundness modes survived that first fix**, both confirmed on real small,
   highly-symmetric (many-generator) files — `cfi-rigid-t2-0016-04-1` (CaDiCaL found 5 orbits,
   XOR-augmented CryptoMiniSat found 6 — wrong) and `cfi-rigid-t2-0020-01-1` (7 vs 20 — badly wrong):
   - A stray cross-gadget union can collapse a gadget's own two *opposite* port sides into the SAME
     clique id. Root cause: a single external K4 (another gadget's own 4 mutually-adjacent members)
     can, at t2's density, be coincidentally adjacent to *both* sides of an unrelated port — since a
     gadget's own members are mutually adjacent regardless of port, this slips past a "is the merged
     group actually a clique" check if the contaminating vertex happens to be universally adjacent
     to all 4 of the other gadget's members.
   - A genuine cross-gadget port-share can be missed entirely — a "twin-like" relationship the local
     adjacent/non-adjacent exclusion test doesn't detect at all, structurally the same residual-
     symmetry phenomenon as `cfi-rigid-r2-0648-04-2` and pass 2's twin clusters above, just one level
     harder to spot here.

   Neither failure mode is detectable by re-checking the FINAL clique's internal consistency alone —
   the first one produces a clique that still looks locally plausible (all-adjacent), and the second
   produces no clique at all (no collapse to catch). The fix actually adopted is a **conservative
   safety net, not a smarter discriminator**: three independently-checked conditions mark a gadget
   `unreliable`, at which point every one of its ports is excluded from XOR clause generation entirely
   (its 4 vertices remain in the encoding as ordinary bijection variables, just contribute no
   constraint) rather than risk emitting a wrong clause —
   1. same-gadget opposite-port-sides resolving to the same clique id (directly catches failure mode 1),
   2. a "same side" clique's members turning out NOT to be pairwise adjacent after all (a second,
      independent check against failure mode 1's more roundabout manifestations),
   3. a pool vertex (candidate cross-gadget port-mate) whose own 3 relations don't include a partner
      back in the same pool — an unexplained/dropped relation, which is exactly what a missed
      port-share (failure mode 2) looks like structurally, so this is checked and flagged directly at
      the point of construction rather than inferred after the fact.

   Re-running the encoder-level orbit comparison after adding this safety net: both previously-wrong
   files now match CaDiCaL exactly (`cfi-rigid-t2-0016-04-1`: 5 vs 5; `cfi-rigid-t2-0020-01-1`: 7 vs
   7, achieved by excluding every one of that file's gadgets — a fully degenerate but SOUND outcome,
   equivalent to plain CryptoMiniSat on that particular file).

Validated the same way as r2: ground-truth check (bypass a known-correct r2 reconstruction by hand,
confirm the algorithm recovers every gadget and covers every vertex exactly once, on a 672-vertex
instance) → cluster-generalized flip-parity check against real Traces automorphisms on 6 real,
non-rigid t2 files spanning n=16 to n=116, **including the 2 files the safety net above was built to
fix** (0 violations, both generators and products, on every file, after the fix) → wired into
`CryptoMiniSatSolver` (`RealFileBypassedGadgetXor.kt`) → confirmed identical recovered orbit
partition to CaDiCaL on both a non-rigid and a rigid real t2 file, and on the 2 previously-failing
small files above.

**Despite being sound, t2 is a negative result for a completely different reason — see Part 4.**

### s2 — explicitly not solved

s2 (`R*(B(Gn,σ))` — bypassed, *not* base-graph-reduced) uses the same bypass mechanism as t2 but
hits a materially harder residual-symmetry degeneracy. `RealFileBypassedGadgetReconstruction`/
`RealFileBypassedGadgetXor` are family-agnostic (they operate on bypassed-CFI graph structure alone,
not a family name), so after t2's safety net above was built, re-running that same, unmodified
production code directly against real s2 files was a cheap way to ask "does the safety net alone
make s2 usable, even in a degraded/partial way?" It does not:

- **Gadget discovery itself (pass 1/2) is perfect on every s2 file tried**: every K4 found is a real
  K4, every vertex covered exactly once, on files from n=64 to n=128. The base problem this part of
  the algorithm solves is not s2-specific.
- **The port/clique-identity union step over-merges far more severely than on t2.** On
  `cfi-rigid-s2-0064-01-1` (16 gadgets, 64 vertices, 192 total port-references), the resulting
  "same-side clique" size histogram was `{45, 16, 10, 7, 6, 6, 4, 4, 4, 2×38}` — instead of the
  clean size-2 pairs expected almost everywhere (which 38 of them are), one single group swallowed
  **45 of the 64 vertices**. This is not a rare, localized tangle the way `cfi-rigid-r2-0648-04-2`
  or t2's `-0016`/`-0020` cases were — it is the dominant behaviour.
- **The safety net correctly refuses to trust any of it**: every one of the 16 gadgets on that file
  (and every gadget on every other s2 file tried: n=64 ×4 seeds, n=128 ×1) gets marked unreliable and
  excluded. The result is sound (verified: 0 clusters formed → CryptoMiniSat + gadget-XOR degrades
  to exactly plain CryptoMiniSat, no wrong constraint ever gets emitted) but useless — 100% exclusion
  leaves nothing for the XOR clause to do, on every file tested, not just the one investigated in the
  original pass.

The conclusion is the same as before but now resting on much stronger evidence: s2's lack of
base-graph reduction leaves enough residual symmetry that the *entire* pairwise-adjacency
exclusion test this technique's port-identity recovery depends on is unreliable, not just one
tangled corner of it — there is no realistic "detect and exclude just the bad part" fix here, because
on this family the bad part is most of the graph. Investigated and explicitly stopped rather than
shipped half-working. `--solver=CRYPTOMINISAT_XOR` refuses to run on s2 (or any family besides r2/t2)
— fails fast, before any instance runs, rather than producing a silently-wrong result.

---

## Part 3 — production wiring

`--solver=CRYPTOMINISAT_XOR` on `BenchmarkRunner` (GLOBAL mode only):

```
./gradlew run --args="--family=cfi-rigid-r2 --maxVertices=3600 --solver=CRYPTOMINISAT_XOR --out=results/r2-xor.csv"
./gradlew run --args="--family=cfi-rigid-t2 --maxVertices=3648 --solver=CRYPTOMINISAT_XOR --noSubdivision=true --out=results/t2-xor.csv"
```

Or, for the one configuration actually worth recommending (see Part 4 — r2, not t2):

```
scripts/run_benchmark.sh --preset=r2-xor
```

which expands to `--family=cfi-rigid-r2 --solver=CRYPTOMINISAT_XOR --maxVertices=3600
--out=results/r2-xor.csv` (any of those four flags can still be overridden by also passing it
explicitly). Deliberately not a t2 preset too — see Part 4's t2 finding for why recommending it as a
default would be dishonest.

`--solver=CRYPTOMINISAT` (no XOR clause) is also available as an isolated soundness/performance
cross-check of CryptoMiniSat's own engine against CaDiCaL, independent of the gadget-derived clause.
Neither CryptoMiniSat mode parallelizes across queries (`--workers` is ignored, always reported as
1 — `driveToOrbitsCryptoMiniSat` has no parallel counterpart to CaDiCaL's
`driveToOrbitsCadicalParallel`).

`--noSubdivision=true` matters specifically for t2: t2's bypassed structure is dense enough that
`dispatchColouring`'s subdivision-vs-1-WL comparison (which always resolves to 1-WL for this family
anyway) becomes a real cost on its own — confirmed directly (n=336, 8 instances): 54.6s with the
comparison running, 7.8s with it skipped, for *identical* solve results. This is pre-existing
behaviour of the colouring dispatch, unrelated to the gadget-XOR work, but it needs to be turned off
to reach t2's real per-instance solve time.

Two CSV columns record what happened: `solver` (which backend ran) and `gadget_xor_clusters` (how
many clusters actually received a constraining XOR clause under `CRYPTOMINISAT_XOR` — 0 is a valid,
sound outcome, not a bug: it means every cluster's odd-multiplicity port set was empty, or every
contributing gadget was excluded by the safety net above, so the invariant holds vacuously and no
clause was needed).

**A timeout must exit the process, not continue reusing the same solver.** `Future.cancel(true)` on
a query that exceeded `maxInstanceSolveMs` is best-effort only — it cannot actually stop a thread
blocked inside a native JNI `solve()` call, confirmed directly: continuing to the NEXT instance in
the SAME process after a CryptoMiniSat timeout intermittently SIGABRTs (native "corrupted"
double-free-shaped crash) shortly after, because the orphaned, still-running `solve()` thread frees
or mutates shared native state out from under the next instance's own native calls. Confirmed
non-deterministic, not a guaranteed crash (some timeouts were followed by a clean next instance,
others by 6 consecutive crashes retrying the same next instance) — consistent with a genuine race,
not a reproducible logic bug. `BenchmarkRunner.main()` now exits cleanly (not a crash — a deliberate,
logged exit) immediately after writing any `INSTANCE_TIMEOUT` row, handing off to the external
resume/retry wrapper (any re-run of the same `--out` file resumes via the CSV, skipping everything
already written) to restart in a fresh, uncorrupted process rather than gamble on the same one.

---

## Part 4 — benchmark results

### Summary: what worked, what didn't, and why

| Family | Sound? | Fast? | Verdict | Root cause of the verdict |
|---|---|---|---|---|
| **r2** | Yes (0 mismatches, every size) | Yes — up to 42.6× (140× on the worst individual seed) faster than CaDiCaL at n≥2448 | **Solved, recommended** (`--preset=r2-xor`) | Plain CryptoMiniSat is already faster than CaDiCaL on r2's sparser structure; the gadget-XOR clause adds a further, large win specifically where CaDiCaL's own search starts to struggle (large classes, occasional pathological queries) |
| **t2** | Yes (0 mismatches, every file tested, after the safety-net fix in Part 2) | **No** — plain CryptoMiniSat (no XOR at all) is already ~8× *slower* than CaDiCaL on t2; the XOR clause doesn't reliably close that gap | **Not recommended** — CaDiCaL remains the right choice for t2 | Not an encoding or wiring defect: CryptoMiniSat's own CDCL search is fundamentally worse than CaDiCaL's at proving these specific UNSAT orbit-mate queries on t2's much denser (bypass-cliqued) structure — see below |
| **s2** | N/A — refuses to run | N/A | **Not solved, not attempted at scale** | Reconstruction's port/clique-identity union step over-merges on ~100% of gadgets tested (not a rare tangle) — see Part 2 |

### r2, full real-file sweep (n = 68 to 3600, every file that exists, 398 instances)

Sound throughout: **zero `unknown`s, zero orbit-count mismatches against CaDiCaL** at every size
tested.

| n range | CaDiCaL total | CryptoMiniSat (plain) | CryptoMiniSat + gadget-XOR |
|---|---|---|---|
| 68–1440 (158 instances) | 169.4 s | 77.0 s (2.2× faster) | 99.9 s (30% *slower* than plain — pure Tseitin overhead, nothing to gain, see below) |
| n=2448 (8 instances, seeds matching `results/r2-sat.csv`'s existing CaDiCaL numbers, `--workers=4`) | 1544.5 s (single-thread-equivalent stragglers up to 477s on individual seeds) | — | 36.3 s (**42.6× faster**; the largest relative wins land exactly on CaDiCaL's worst stragglers — 140× on the single slowest seed) |
| n=3024–3600 (72 instances, the complete remaining real r2 file set) | not re-run (CryptoMiniSat-only sweep) | — | 530.2 s total, 7.4 s/instance average, smooth linear-ish scaling from 6.5s (n=3024) to 8.5s (n=3600), **no straggler variance** (every seed within ~2-3s of every other at a given size — contrast CaDiCaL's 38.7s–477s spread on 8 seeds of the same size) |

The gadget-XOR clause is a genuine, substantial win at the sizes where CaDiCaL's own solving starts
to struggle (large classes, occasional pathological queries) — but it is *not* a free lunch below
that: at small-to-medium sizes, where CaDiCaL never has trouble in the first place, the clause's
Tseitin-variable bookkeeping is pure overhead, and *plain* CryptoMiniSat (no gadget structure needed
at all) is already the better choice purely on its own engine's merits.

### t2 — sound, but not competitive with CaDiCaL (negative result)

A full real-file sweep (n=68 to 3648) was started under `--solver=CRYPTOMINISAT_XOR
--noSubdivision=true`; per-instance wall time climbed from tens of milliseconds at n≤456 into
tens of seconds by n=816-1008, with the first `INSTANCE_TIMEOUT` (60s cap) at n=960. This alone —
without yet knowing *why* — was already the wrong shape (r2's own sweep never exceeded ~8.5s/instance
all the way to n=3600), so the sweep was stopped rather than pushed further or explained away.

**Isolating the cause** (`t2-sat.csv`'s existing CaDiCaL numbers as the reference, same real files):
a controlled 3-way comparison — CaDiCaL (plain) vs CryptoMiniSat (plain, zero XOR clauses) vs
CryptoMiniSat (+ cluster XOR) — on 2 real instances, each solver capped at 90s:

| Instance | CaDiCaL (plain) | CryptoMiniSat (plain, no XOR) | CryptoMiniSat + cluster XOR |
|---|---|---|---|
| `cfi-rigid-t2-0816-03-1` (n=816) | 6.6 s | 55.2 s | 39.6 s |
| `cfi-rigid-t2-0912-02-1` (n=912) | 7.5 s | 55.6 s | 66.4 s |

**Plain CryptoMiniSat — with zero XOR clauses involved at all — is already ~8× slower than CaDiCaL.**
Adding the cluster XOR clause does not reliably close that gap: better at n=816, *worse* at n=912.
This rules out "Gauss isn't firing" as the explanation before it's even asked, since the plain
baseline (no XOR clause exists yet to fire Gauss on) is already the slow one.

Isolating further: a per-query timing probe (bypassing the union-find dedup, calling
`assume()+solve()` directly and timing each call individually) on the same n=816 instance, plain
CryptoMiniSat, zero XOR clauses:

```
query 0 (u=0,v=22):   UNSAT  14394 ms   <- pays CMS's one-time simplify_at_startup pass too
query 1 (u=0,v=740):  UNSAT   5848 ms
query 2 (u=0,v=772):  UNSAT   6211 ms
query 3-11 (same colour-class, different u): UNSAT   0-1 ms   <- free, reuses learned clauses
query 12 (u=1,v=291): UNSAT   2384 ms   <- new colour-class, pays real search cost again
query 13-14:                              UNSAT  ~2100 ms each
query 15-23 (same colour-class):          UNSAT   0 ms        <- free again
```

`simplify_at_startup`/`full_simplify_at_startup` (the Gauss/occ-xor pass) is confirmed, by reading
`solver.cpp:1396-1406` directly, to run only ONCE per solver instance (gated on
`solveStats.num_simplify == 0`) — so it cannot explain queries 1, 2, 12, 13, 14 also costing seconds
each, long after that one-time pass is done. The real pattern: proving each *new* UNSAT orbit-mate
claim from scratch costs CryptoMiniSat real CDCL search time (seconds) on t2's structure, while a
later query against an already-explored vertex comes back free (reusing learned clauses from the
first). CaDiCaL proves the identical UNSAT queries, on the identical CNF, in a small fraction of that
time.

**Conclusion: this is a genuine core-CDCL-search mismatch between CryptoMiniSat's own branching/
restart heuristics and t2's CNF shape — not an encoding bug, not a wiring bug, and not fixable by
tuning Gauss settings**, since Gauss is not involved in the slow plain-mode queries at all. t2 is
roughly 4× denser than r2 at a comparable n (e.g. n=720: r2 has m=1440, t2 has m=5860 — bypass turns
every removed outer vertex's neighbourhood into a clique, and t2 has no base-graph reduction to thin
that back out the way r2 does), and CryptoMiniSat's heuristics apparently lose to CaDiCaL's on that
denser shape specifically, the mirror image of r2 where CryptoMiniSat already wins before the XOR
clause is even added. The gadget-XOR technique itself is validated sound on t2 (Part 2) — it simply
has no uncompetitive baseline left to rescue.

---

## Guard rails (same discipline as `INVARIANT_FILTERED_SAT_SPEC.md`)

- **Never trust a derived invariant without checking it against real automorphisms first** —
  generators *and* their pairwise products, not generators alone.
- **A "found no violations" result on an insufficiently-rigid test construction is not validation**
  — the base graph the invariant is checked against must itself be confirmed rigid (or the
  degeneracy explicitly modelled, as the cluster generalization does), or a real unsoundness can
  hide behind a coincidentally-passing test.
- **A reconstruction algorithm's job is to produce topology that is internally self-consistent and
  empirically sound, not to match one arbitrary ground-truth labelling** — real residual symmetry
  in the underlying graph can make multiple gadget partitions equally valid; picking any one of them
  and validating the result against real automorphisms is the correct process, not a compromise.
  Only reconstruction bugs (partner-inconsistency, incomplete degree accounting) are actual defects.
- **A family this technique has not been validated on refuses to run**, rather than silently
  producing output that has not been checked.
- **A slower baseline is not evidence the added technique is broken** — always separate "is the new
  clause sound" from "is the underlying solver competitive here" before concluding anything about the
  clause itself. t2's slowdown looked like a Gauss/XOR problem until a plain-CryptoMiniSat-vs-CaDiCaL
  baseline (zero XOR clauses involved) isolated it as a pre-existing solver-engine mismatch instead.
- **`Future.cancel(true)` on a thread blocked in a native call is best-effort, not a guarantee** — a
  timed-out native solve can keep running invisibly and corrupt shared state out from under whatever
  runs next in the same process. Exit the process after a timeout and resume via CSV in a fresh one,
  rather than trust the cancellation actually stopped anything.
- **A safety net that excludes rather than guesses is the right response to an unreliable
  discriminator, but it isn't a substitute for the discriminator working** — it converts "silently
  wrong" into "silently useless," which is always the correct trade, but on s2 the excluded fraction
  turned out to be ~100%, not a rare edge case, which is itself the actual finding.
