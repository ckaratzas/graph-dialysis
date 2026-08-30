# graph-dialysis

Computing automorphism-group orbits of a graph exactly, via colour-refinement + a SAT encoding,
certified query by query, backed by CaDiCaL (Biere et al.) via a from-scratch JNI binding.

## What this is

Given a graph `G`, we want the orbits of `Aut(G)` — the partition of `V(G)` into sets of vertices
that some automorphism maps onto each other. The approach:

1. **Colour-refine** `G` (1-WL, or a richer "initial phase" refinement — see
   `dialysis.refinement`) into an isomorphism-invariant vertex colouring. Two vertices can only be
   orbit-mates if they share a colour, so this is free pruning before any SAT solver runs.
2. **Encode** "does some `α ∈ Aut(G)` map `u → v`?" as a SAT instance: a permutation matrix
   restricted to colour-admissible entries, plus edge-preservation clauses, plus (optionally)
   *implied distance clauses* — pair-level constraints entailed by edge-preservation + bijectivity
   alone, so adding them changes propagation strength, never which automorphisms are representable
   (`INVARIANT_FILTERED_SAT_SPEC.md`).
3. **Drive** every same-colour pair to a verdict — SAT (a witness, independently re-verified in
   `O(m)`, and its full permutation closes every pair it also connects via union-find) or UNSAT
   (that pair is provably not orbit-mates) — with a two-pass timeout schedule (cheap sweep first,
   long budget only for survivors) and transitivity/separation tracking so most pairs are never
   queried at all (`BENCHMARK_SPEC.md` Part 2).
4. **Certify**: `unknown == 0` means the recovered partition is *provably* exact, not just
   plausible — never claimed otherwise (see `BENCHMARK_SPEC.md`'s status definitions).

No symmetry-breaking predicates anywhere in this pipeline — they would exclude models and could
silently turn a genuine SAT instance into UNSAT, corrupting certification.

## Layout

```
src/main/kotlin/dialysis/
  graph/            Graph type (adjacency lists, bipartition, subdivision)
  content/          Content — the isomorphism-invariant colour value type
  refinement/       1-WL (colorRefine1WL), the richer "initial phase" refinement,
                     ColouringDispatch (finer-of-two-invariants choice, Task 1) and
                     PerQueryFilter (per-query individualization filter, Task 2)
  decomposition/    Recursive piece decomposition the initial phase is built on
                     (Dialysis.kt, DecompositionStore.kt), plus Peel.kt — the
                     decomposition-into-pieces/position-signature construction
                     (DECOMPOSITION_ORDERING_SPEC.md; measured, not adopted)
  ahu/              AHU tree-isomorphism check (used inside the initial phase)
  certify/          Colour-class extraction from a stable colouring
  cl/               CanonicalLabeler interface + the TracesJni binding (ground truth)
  fvs/              FeedbackVertexSet (2-approx FVS) and the FVS-seeded 1-WL scale
                     campaign (FvsSeededCampaign + its standalone CLI,
                     FvsCampaignRunner — FVS_SEEDED_1WL_SPEC.md)
  gadgetxor/        Gadget structure reconstruction from topology alone, and the
                     hand-derived cluster-generalized GF(2) parity XOR clause built
                     from it, for r2 (non-bypassed) and t2 (bypassed) — GADGET_XOR_SPEC.md
  sat/              Solver-agnostic orbit-driving primitives: union-find over the
                     orbit-mate relation, implied distance clauses, query result types
  sat/cadical/      The CaDiCaL backend: JNI solver binding, CNF encoder (no
                     pseudo-boolean support in CaDiCaL, so bijections are plain CNF),
                     the two-pass query driver, the parallel (colour-class-partitioned)
                     driver, and the per-query encoder/driver (Task 2.1)
  sat/cryptominisat/ The CryptoMiniSat backend (JNI binding, encoder, query, driver) —
                     native XOR/Gaussian-elimination reasoning gadgetxor's clauses need,
                     which CaDiCaL doesn't support
  benchmark/        BenchmarkRunner — the standalone CLI (see below)
  util/             GraphIO (DIMACS parsing), relabelling, bounded thread pools,
                     TempFiles (redirects native-lib extraction + scratch files off a
                     possibly-too-small OS default temp dir)

src/main/cpp/       Native JNI: colored_ahu, paige_tarjan_wl1 (1-WL), nauty_traces
                     (Traces ground truth), cadical + cadical_jni (CaDiCaL backend),
                     cryptominisat_jni (CryptoMiniSat backend, built against the
                     vendored cms-stack/ — cryptominisat + its cadical/cadiback
                     "umbrella" dependencies, see build_cryptominisat.sh)
external/           Third-party source patched for this project's own experiments,
                     kept separate from src/main/cpp's own vendored libraries —
                     scawl-patch/ (a patched ScaWL k-WL implementation, for the
                     FVS-seeded 2-WL/3-WL checks in FVS_SEEDED_1WL_SPEC.md Part 7;
                     3-WL is banned from re-running, see that spec's own note)
src/test/kotlin/    Unit/acceptance tests, the full-campaign harnesses, and
                     experimental/ (one-off measurement harnesses backing the specs —
                     FVS-seeded WL, decomposition ordering, per-query filter, etc.)
scripts/            ground_truth.py (dreadnaut-based ground-truth orbit counts, run
                     standalone per family) and merge_ground_truth.py (joins its
                     output into a BenchmarkRunner CSV); run_benchmark.sh (the
                     BenchmarkRunner CLI, presets, resumable campaigns);
                     run_fvs_campaign.sh / package_fvs_campaign.sh (the FVS-seeded
                     1-WL campaign, and a no-repo-clone-needed bundle for running it
                     on another machine)
tools/nauty/        A standalone `dreadnaut` binary — what scripts/ground_truth.py
                     shells out to (distinct from dialysis.cl.TracesJni's in-process
                     binding, used by unit tests instead)
graphs/             The corpus — CFI-rigid families (the hard case: 2-WL-indistinguishable,
                     large near-trivial automorphism groups) plus easier control families
                     (Latin squares, Paley graphs, Hadamard, grids, ...) — see Graph corpus below
results/            Campaign output CSVs (ground truth + BenchmarkRunner/FVS-campaign
                     results) — committed as artifacts, not scratch output
```

## Software stack

- **Kotlin** 2.2.20 (JVM) — the orbit-computation pipeline, benchmark CLI, and test suite.
- **JDK 17+** — Gradle 9.5.1's own minimum supported JVM, and the runtime for everything above.
- **Gradle** 9.5.1 (via the wrapper, `./gradlew`) — build orchestration, including compiling the
  five vendored native libraries as part of `processResources`.
- **JGraphT** 1.5.2 — DIMACS parsing (`dialysis.util.GraphIO`) only; no algorithmic use.
- **CaDiCaL** (Biere et al., MIT license) — the production SAT backend. Vendored source under
  `src/main/cpp/cadical/`, bound via a hand-written JNI shim (`src/main/cpp/cadical_jni.cpp`); see
  `src/main/cpp/cadical/LICENSE`.
- **CryptoMiniSat** (Soos et al., MIT-licensed core), plus its real upstream "umbrella"
  dependencies **cadical** (a fork pinned separately from this project's own CaDiCaL vendoring,
  kept in its own `cms-stack/` subdirectory so the two never collide) and **cadiback** — the
  backend `GADGET_XOR_SPEC.md`'s hand-derived native XOR clauses need (native Gaussian
  elimination/XOR detection, which CaDiCaL doesn't support). Vendored under
  `src/main/cpp/cms-stack/`, bound via `src/main/cpp/cryptominisat_jni.cpp`; built by
  `src/main/cpp/build_cryptominisat.sh`, which fails fast with instructions rather than attempting
  a from-scratch checkout if the pinned pieces aren't already prebuilt there (three separately
  vendored repos at specific commits, not a one-liner to reproduce — see that script's own doc).
  Optionally uses **M4RI** (GPLv2, system `libm4ri-dev`, not vendored) for its own native XOR
  detection, independent of the cadiback link.
- **nauty & Traces** (Brendan McKay, Adolfo Piperno, et al. — Apache License 2.0) — ground truth.
  `dialysis.cl.TracesJni` binds it in-process for unit tests; `scripts/ground_truth.py` instead
  drives the standalone `dreadnaut` binary (`tools/nauty/dreadnaut`) as a subprocess for
  campaign-scale ground truth (`BENCHMARK_SPEC.md` Part 1). Vendored source under
  `src/main/cpp/nauty/`; see `src/main/cpp/nauty/COPYRIGHT` for the full license and copyright
  holders. Distribution site: https://pallini.di.uniroma1.it/index.html
- **ScaWL** (github.com/CobySoss/ScaWL, MIT licensed) — a third-party k-WL implementation, patched
  (`external/scawl-patch/`, not part of the routine build) for the FVS-seeded 2-WL check in
  `FVS_SEEDED_1WL_SPEC.md` Part 7; see that directory's own README for the exact build command.
- **Python 3** — `scripts/ground_truth.py` and `scripts/merge_ground_truth.py`; standalone, no
  dependency on the JVM build.
- **g++ / make** — building the vendored native libraries (nauty/Traces, CaDiCaL, CryptoMiniSat)
  and the three hand-written JNI shims (`colored_ahu.cpp`, `paige_tarjan_wl1.cpp`,
  `cryptominisat_jni.cpp`).

## Building

Requires a JDK, `g++`, and `make` (for the vendored native libraries — nauty/Traces, CaDiCaL, and
CryptoMiniSat). Everything else is fetched by Gradle.

```
./gradlew assemble
```

This compiles five native libraries as part of `processResources` (see `build.gradle.kts`):
`libcoloredahu.so`, `libwl1jni.so` (1-WL), `libtracesjni.so` (Traces), `libcadicaljni.so` (CaDiCaL —
vendored under `src/main/cpp/cadical/`, built via its own `./configure --shared && make`, not
hand-replicated compiler flags), and `libcryptominisatjni.so` (CryptoMiniSat, see below). All five
are normal Gradle `Exec` tasks that rebuild automatically whenever their declared inputs change
(`--info`/`--debug` show the underlying commands if something goes wrong).

**CryptoMiniSat is a hard prerequisite for every build, not an opt-in** — `processResources`
depends unconditionally on `buildNativeCryptoMiniSat`, so `./gradlew assemble`/`test`/`run`/
`shadowJar` all fail at that task on a from-scratch checkout, even if you never touch the
gadget-XOR campaign or CryptoMiniSat cross-checks. `buildNativeCryptoMiniSat` fails fast with setup
instructions rather than attempting a from-scratch checkout itself if `src/main/cpp/cms-stack/`
doesn't already have its three pinned pieces (cryptominisat, plus its own cadical/cadiback
"umbrella" dependencies) prebuilt — see `src/main/cpp/build_cryptominisat.sh`'s own doc for the
exact checkout/build sequence that has to be run once, by hand, before the first `./gradlew`
invocation on a new checkout.

## Running the tests

```
./gradlew test
```

Runs everything except `FullCampaignSatPiTest` — it's unbounded by design (sweeps an entire graph
family) and meant to be run deliberately, not on every `test` invocation. Run it directly if you
want it:

```
./gradlew test --tests "dialysis.FullCampaignSatPiTest"
```

## Running the benchmark CLI

`BenchmarkRunner` (`dialysis.benchmark`) is the standalone, configurable form of what
`FullCampaignSatPiTest` does as a JUnit test — built for running unattended on a benchmark
machine, per `BENCHMARK_SPEC.md`.

```
./gradlew run --args="--family=cfi-rigid-d3 --maxVertices=1000 --workers=2 --config=PI_DIST --repeats=1 --out=results/d3-1000v.csv"
```

or, to avoid paying Gradle's startup cost on every invocation (useful for a long unattended run):

```
./gradlew installDist
build/install/graph-dialysis/bin/graph-dialysis --family=all --maxVertices=3780 --workers=4 --config=PI_DIST --out=results/full.csv
```

**Arguments:**

| Flag | Default | Meaning |
|---|---|---|
| `--family` | *(required)* | A family name (matches a `graphs/` subdirectory), a comma-separated list, or `all` |
| `--minVertices` | `0` | Skip any instance whose original vertex count (`n`, not a subdivision's — same quantity `--maxVertices` gates on) is below this. Lets a run resume past an older, schema-incompatible CSV without re-running the instances it already covers: point a fresh `--out` at `--minVertices=<one past the old run's largest n>` instead of trying to append into the old file |
| `--maxVertices` | `3000` | Skip any instance whose original vertex count (`n`, not a subdivision's — FINAL_MEASUREMENTS_SPEC.md Task 1 solves on the original graph regardless of colouring) exceeds this |
| `--workers` | `1` | Parallelism — colour classes are drawn from a shared dynamic work queue (largest-first order, no static pre-assignment), each worker with its own CaDiCaL instance; see `BENCHMARK_SPEC.md` Part 3 |
| `--config` | `PI_DIST` | `PI_DIST` (initial-phase colouring + implied distance clauses — the proposed method), `WL_DIST` (1-WL colouring + implied clauses — ablation: is the richer colouring needed?), or `PI` (initial-phase, no implied clauses — ablation: are the implied clauses needed?) |
| `--repeats` | `1` | Repeats per instance |
| `--seed` | `42` | Seed for the initial (uniform) colouring |
| `--timeoutMs` | `10000` | Long-pass per-query timeout |
| `--shortMs` | `1000` | Short-pass per-query timeout (the cheap first sweep) |
| `--dmax`, `--anchorK` | `6`, `8` | Implied-distance-clause parameters |
| `--edgeClauseThreshold` | `2000000` | Gate on `Sigma_edges \|C(i)\|*\|C(k)\|` (edge-conflict clause count), computed from the colouring alone before any solving — below it, the GLOBAL formula is built directly; above it, the per-query filter is tried instead (FINAL_MEASUREMENTS_SPEC.md Task 2); if even the per-query estimate exceeds it, the row is `SKIPPED_TOO_LARGE` with both estimates recorded. The default sits between a measured-safe ~1M and a measured-failing ~3.28M, not a hunted-for exact boundary; the campaign's own `global_edge_clause_estimate`/`per_query_edge_clause_estimate` columns are what should refine it |
| `--maxInstanceSolveMs` | `120000` | Hard WALL-CLOCK backstop on ONE instance's entire solve phase, independent of `--timeoutMs` (which only bounds a single query). A colour class can need many queries before generator closure catches up, so nothing else bounds the total; exceeding it yields `status=INSTANCE_TIMEOUT` and the campaign moves on to the next instance rather than stalling |
| `--out` | *(required)* | Output CSV path |
| `--noSubdivision` | `false` | `true` forces plain 1-WL colouring on every non-bipartite instance, skipping `dispatchColouring`'s subdivision + initial-phase comparison entirely (FINAL_MEASUREMENTS_SPEC.md Task 1). Faster for a family where that comparison always resolves to 1-WL anyway, and keeps every family in a run on the same never-subdivided basis for an apples-to-apples comparison. Also sidesteps `DecompositionStore`'s disk use on the subdivided vertex count — see `JAVA_OPTS` below |
| `--solver` | `CADICAL` | GLOBAL-mode SAT backend. `CADICAL` (default, unchanged). `CRYPTOMINISAT` drives the identical plain-CNF encoding through CryptoMiniSat instead — a soundness cross-check, no gadget-XOR clause. `CRYPTOMINISAT_XOR` additionally reconstructs `cfi-rigid-r2`'s gadget structure from topology alone and adds a hand-derived, cluster-generalized flip-parity XOR clause (`dialysis.gadgetxor.RealFileGadgetXor`) — validated sound against real Traces-computed automorphisms on both a rigid and a non-rigid real r2 file, but **`cfi-rigid-r2` only**: the reconstruction assumes r2's non-bypassed gadget structure and the run refuses to start (fails fast, before any instance runs) if `--family` includes anything else. Neither CryptoMiniSat mode parallelizes across queries — `--workers` is ignored (always reported as `1`) under either. Adds two CSV columns: `solver` and `gadget_xor_clusters` (how many gadget clusters actually got a constraining XOR clause; blank outside `CRYPTOMINISAT_XOR`) |

Set `JAVA_OPTS` (an environment variable, not a `--flag`) for JVM system properties, e.g.
`-Ddialysis.tmpDir=/path` if the OS default temp directory is too small: `DecompositionStore`'s
scratch file is O(n²) in the (possibly subdivided) vertex count it was built for, which can exceed a
small `java.io.tmpdir` partition (a VM's tmpfs `/tmp`, distinct from the machine's main disk) well
before the disk itself fills — confirmed on a subdivided instance upward of ~8000 vertices, surfacing
as "No space left on device" with `df` on the main disk showing plenty free. `scripts/run_benchmark.sh`
inserts `JAVA_OPTS` before `-jar`:
```
JAVA_OPTS="-Ddialysis.tmpDir=/data/tmp" scripts/run_benchmark.sh --family=all --noSubdivision=true --out=results/full.csv
```

Re-running with the same `--out` **resumes**: instances already present as a row are skipped, so a
run that dies partway through loses nothing already written.

Output columns follow `BENCHMARK_SPEC.md` Part 2.1 (identity / colouring / encoding / solving /
witnesses / result), including the `queries_skipped_witness` vs `queries_skipped_separation` split
and the `CERTIFIED`/`PARTIAL` status. `EXACT` requires ground truth, computed separately with
`scripts/ground_truth.py` (a standalone `dreadnaut`/Traces subprocess per instance) and joined in
with `scripts/merge_ground_truth.py` — not part of this CLI's own pass.

If you ran the benchmark with `--noSubdivision=true`, pass `--subdivide=never` to
`scripts/ground_truth.py` too — `recovered_orbits` and `true_orbits` are only counting orbits over
the same graph when neither side subdivides (see `BENCHMARK_SPEC.md` Part 1 and
`scripts/ground_truth.py`'s own module doc). `scripts/merge_ground_truth.py` skips the comparison
rather than flag a false disagreement when they don't match on this.

### Running the "mini500" campaign

A full-corpus run capped at 500 effective vertices, via the self-contained jar rather than Gradle
(build it once with `./gradlew shadowJar`, then re-run the jar directly on every subsequent
invocation):

```
scripts/run_benchmark.sh --family=all --maxVertices=500 --workers=8 --config=PI_DIST --out=results/mini500.csv
```

Re-running this exact command **resumes**: instances already present as a row in `results/mini500.csv`
are skipped, so if the process is killed or the machine runs out of memory partway through, nothing
already written is lost — just run the same command again.

`--workers=8` here is not the `nproc` default — size it to the RAM actually available on the machine
you're running on, not to core count; see the hardware-constraint note at the top of
`BENCHMARK_SPEC.md` for how a peak-RSS-per-worker budget was measured and how to re-measure it. On a
different machine (e.g. a VM with a small `/tmp`), also see that same note on `JAVA_OPTS`/
`-Ddialysis.tmpDir` and `--noSubdivision` above — a large subdivided graph can fill a small temp
partition well before RAM or the main disk are the binding constraint.

### Running the FVS-seeded 1-WL campaigns (`r2`/`z2`)

`FVS_SEEDED_1WL_SPEC.md`'s validated speedup — seed 1-WL with the SAT-derived orbits of a feedback
vertex set's colour-class closure, instead of driving the full colour-admissible pair set. Sound on
every family tested; a genuine, growing win specifically on `r2`/`z2` above a real crossover point
(same-machine measurement: a wash or net slower below n≈600-900, up to 3.32× faster above it — see
`FVS_SEEDED_1WL_SPEC.md` Part 4.2), and most instances resolve to a free, self-certifying rigidity
proof with zero further SAT calls regardless of size. Not
wired into `BenchmarkRunner` — run directly as a JUnit test, one size bucket per distinct `n` found
in `graphs/<family>/` that already has a cached ground-truth answer in `results/`:

```
scripts/run_fvs_campaign.sh r2 z2
```

or, equivalently, one family at a time via Gradle directly:

```
./gradlew test --tests "dialysis.experimental.FvsSeeded1WLScaleTest.r2"
./gradlew test --tests "dialysis.experimental.FvsSeeded1WLScaleTest.z2"
```

Writes/resumes `results/fvs-seeded-1wl.csv` (same resume-by-instance convention as `BenchmarkRunner`
— re-running skips rows already present, so a killed run loses nothing already written; copy an
existing `results/fvs-seeded-1wl.csv` into a fresh checkout on another machine to pick up exactly
where a run left off there). `t2`/`s2` (sound, no speedup) and `d3`/`z3` (provably under-refines —
see spec Part 3) run the same way (`scripts/run_fvs_campaign.sh t2 s2 d3 z3`) if you want those
numbers too.

This is single-threaded (no `--workers` knob) and growth is real but not explosive (1-WL and
restricted SAT queries only, no k-WL for k≥2) — but a handful of individual queries above n≈2500 on
`r2` can land on the same kind of occasional pathological case `GADGET_XOR_SPEC.md` documents for
r2's production CaDiCaL path, each capped at 60s but still adding up. On a memory-constrained
machine this shows up as the sweep visibly slowing once free RAM drops to a few GB (CaDiCaL's search
starts paying for page faults, not doing more work) — `scripts/run_fvs_campaign.sh`'s own header
comment has the detail; running on a machine with more headroom avoids it.

Both full sweeps have been run to completion: `z2` — 47/47 size buckets, 100% match, up to n=4136;
`r2` — 43/43 size buckets, 100% match, up to n=3024, zero `ERROR` rows (the last few `r2` buckets
finished on a second, stronger machine via the no-clone-needed bundle below, resuming the same CSV).

**Running it on another machine without cloning this repo.** The campaign logic lives in
`dialysis.fvs.FvsSeededCampaign` (main source set), reachable as a standalone CLI
(`dialysis.fvs.FvsCampaignRunner`) that only needs a JDK, not Gradle or the source tree.
`scripts/package_fvs_campaign.sh` builds exactly that bundle — the shadow jar (native CaDiCaL
library included), the requested families' graph files (17-20 MB each, not the whole multi-GB
corpus), the small `results/` CSV directory (ground truth + any existing partial
`results/fvs-seeded-1wl.csv`, so the bundle resumes from wherever this machine's run left off), and
a `run.sh`:

```
scripts/package_fvs_campaign.sh r2          # -> fvs-campaign-r2.tar.gz, ~10 MB
```

On the other machine: `tar xzf fvs-campaign-r2.tar.gz && cd fvs-campaign && ./run.sh` — no clone, no
build, no network access needed there. Bring the resulting `results/fvs-seeded-1wl.csv` back into
this repo's own `results/` directory afterward; the packaging script doesn't push results anywhere
itself.

### Running the CryptoMiniSat gadget-XOR campaign (`r2`)

`GADGET_XOR_SPEC.md`'s validated, real speedup — a hand-derived cluster-generalized GF(2) parity XOR
clause for the CFI gadget, solved via CryptoMiniSat's native Gaussian-elimination engine instead of
CaDiCaL's plain CDCL search. Sound at every size tested (zero mismatches against CaDiCaL), and up to
42.6× faster than CaDiCaL at n≥2448 (140× on CaDiCaL's worst individual straggler). **Only supports
`cfi-rigid-r2`** — the gadget reconstruction assumes r2's non-bypassed structure and the run refuses
to start on any other family:

```
./gradlew shadowJar
scripts/run_benchmark.sh --preset=r2-xor
```

Expands to `--family=cfi-rigid-r2 --solver=CRYPTOMINISAT_XOR --maxVertices=3600
--out=results/r2-xor.csv` (see `scripts/run_benchmark.sh`'s own header comment for what the preset
covers and why `t2` deliberately isn't one — sound there too, but consistently slower than CaDiCaL).
Resumable exactly like every other `BenchmarkRunner` run. Real, not projected: the full 398-instance
`r2` sweep (n=68 to 3600) completed in about 18 minutes wall clock on this machine, every row
`CERTIFIED` and 0 disagreements against independent Traces ground truth, with no straggler variance
(every seed within a couple of seconds of every other at a given size) — contrast CaDiCaL's own
38.7s–477s spread on 8 seeds of the same size at n=2448 (`GADGET_XOR_SPEC.md`'s own table).

## Sample results

Small per-family campaigns (`--maxVertices=1000`, `--config=PI_DIST`, single-threaded), one row
per instance, generated with the CLI above — enough to see the shape of the data, not a claim
about the full corpus (`BENCHMARK_SPEC.md`'s actual campaign runs on a dedicated 16 vCPU machine,
up to n = 3780).

| Family | Instances | n range | Status | Avg `total_ms` | Max `total_ms` | Avg queries issued |
|---|---|---|---|---|---|---|
| `cfi-rigid-d3` | 40 | 180–900 | 40 `CERTIFIED` | 887 | 3,107 | 97.1 |
| `cfi-rigid-r2` | 102 | 68–936 | 102 `CERTIFIED` | 416 | 1,684 | 597.1 |
| `cfi-rigid-s2` | 8 | 696–708 | 8 `CERTIFIED` | 129 | 170 | 1.1 |
| `paley` | 11 | 10–976 | 11 `PI_ONLY` | 99 | 348 | — |
| `latin` | 7 | 10–736 | 7 `PI_ONLY` | 69 | 186 | — |

Every instance that reached the SAT/CaDiCaL path fully certified (`unknown == 0`) — zero `PARTIAL`
or `ERROR` rows. `cfi-rigid-s2` has far fewer instances under the cap than the others because it's
edge-dense enough that bipartite subdivision grows it past 1000 vertices starting at its very next
size class (n=128 subdivides past 1400) — the cap is doing its job, not silently skipping something
it shouldn't.

**Stale note:** the `paley`/`latin` `PI_ONLY` rows above predate the Task 2 per-query filter
integration (they were routed there by the old `--maxClassSizeRatio` guard, since removed). Under
the current `--edgeClauseThreshold` gate, small `paley`/`latin` instances like these route to
`PER_QUERY` and actually certify instead (`FINAL_MEASUREMENTS_SPEC.md` Task 2) — this table needs
regenerating; left as-is for now rather than silently edited without a real re-run.

## Graph corpus

The `graphs/` corpus is sourced from two public benchmark collections, not generated by this
project:

- https://pallini.di.uniroma1.it/Graphs.html — nauty & Traces' own benchmark graph collection.
- https://www.lics.rwth-aachen.de/cms/lics/forschung/publikationen/~rtok/benchmark-graphs/?lidx=1
  — RWTH Aachen's benchmark graph collection (the CFI-rigid families).

## Documentation

- `BENCHMARK_SPEC.md` — the full benchmark protocol (ground truth, method/ablation comparison,
  parallelism, reporting schema, two-pass query scheduling) the CLI above implements.
- `INVARIANT_FILTERED_SAT_SPEC.md` — the SAT encoding itself: why it's sound and complete, the
  conflict-form/support-form edge-clause choice that avoids an OOM on large near-uniform colour
  classes, implied distance clauses, and how a driven colour class becomes verified orbits.
- `FINAL_MEASUREMENTS_SPEC.md` — the paper's remaining scope questions: Traces memory containment
  (Task 0, `scripts/ground_truth.py`), colouring dispatch (Task 1, `dialysis.refinement.ColouringDispatch`),
  the per-query individualization filter for low-colour-class families (Task 2, `filter_mode=PER_QUERY`
  in the benchmark CSV), and recursive-decomposition query ordering/seeding (Task 3).
- `DECOMPOSITION_ORDERING_SPEC.md` — Task 3's decomposition-into-pieces and position-signature
  construction (`dialysis.decomposition.peel`/`positionSignatures`) — a measured negative result for
  seeding/ordering on the low-colour-class families, kept because the *mechanism* is worth recording
  alongside the positive `cfi-rigid` result: same tool, two regimes, both measured.
- `FVS_SEEDED_1WL_SPEC.md` — seeding 1-WL with a feedback-vertex-set's SAT-derived orbits
  (`dialysis.fvs.FeedbackVertexSet`): a real, measured win on `r2`/`z2` above a size crossover
  (n≈600-900; up to 3.32× above it, a wash or slower below), a free rigidity certificate on most
  instances regardless of size, no win on `t2`/`s2` (sound, just no speedup), and a
  precisely-characterized negative result on `d3`/`z3` that survives even FVS-seeded 2-WL (Part 7).
- `GADGET_XOR_SPEC.md` — a hand-derived, cluster-generalized GF(2) parity XOR clause for the
  Cai-Fürer-Immerman gadget, fed to CryptoMiniSat's native Gaussian-elimination engine
  (`dialysis.gadgetxor`, `dialysis.sat.cryptominisat`) — a substantial, measured win on `r2`
  (up to 42.6× faster than CaDiCaL at n≥2448), sound but not competitive on `t2`, not attempted at
  scale on `s2` (reconstruction over-merges).
- `MULTIDECOMP_WITNESS_SPEC.md` — multi-decomposition witness hunting for low-colour-class families:
  recursively decompose from a root, encode against the small groups the decomposition's own
  signature produces instead of the whole class, prove pairs inside them, repeat from a different
  root (or, "clearing", on the graph with proven orbits quotiented away), union-find chains the
  results transitively. A real but narrow win on `had-64` and `triang-20` (under clearing); negative
  on `latin-20`/`lattice-20`/`ag2-16`; does not generalize across the whole `had` family (time
  compounds sharply, hits a wall by `had-44`). Not folded into `BenchmarkRunner`.
- `FINAL_REPORT.md` — the paper-facing synthesis of every result above: what's sound, what's fast,
  what was tried and abandoned, and why.

## License

MIT — see `LICENSE`.