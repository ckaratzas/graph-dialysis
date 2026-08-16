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
  refinement/       1-WL (colorRefine1WL) and the richer "initial phase" refinement
  decomposition/    Recursive piece decomposition the initial phase is built on
  ahu/              AHU tree-isomorphism check (used inside the initial phase)
  certify/          Colour-class extraction from a stable colouring
  cl/               CanonicalLabeler interface + the TracesJni binding (ground truth)
  sat/              Solver-agnostic orbit-driving primitives: union-find over the
                     orbit-mate relation, implied distance clauses, query result types
  sat/cadical/      The CaDiCaL backend: JNI solver binding, CNF encoder (no
                     pseudo-boolean support in CaDiCaL, so bijections are plain CNF),
                     the two-pass query driver, and the parallel
                     (colour-class-partitioned) driver
  benchmark/        BenchmarkRunner — the standalone CLI (see below)
  util/             GraphIO (DIMACS parsing), relabelling, bounded thread pools

src/main/cpp/       Native JNI: colored_ahu, paige_tarjan_wl1 (1-WL), nauty_traces
                     (Traces ground truth), cadical + cadical_jni (CaDiCaL backend)
src/test/kotlin/    Unit/acceptance tests, plus the full-campaign harnesses under
                     dialysis/configcomparison/
scripts/            ground_truth.py (dreadnaut-based ground-truth orbit counts, run
                     standalone per family) and merge_ground_truth.py (joins its
                     output into a BenchmarkRunner CSV)
graphs/             The corpus — CFI-rigid families (the hard case: 2-WL-indistinguishable,
                     large near-trivial automorphism groups) plus easier control families
                     (Latin squares, Paley graphs, Hadamard, grids, ...) — see Graph corpus below
```

## Software stack

- **Kotlin** 2.2.20 (JVM) — the orbit-computation pipeline, benchmark CLI, and test suite.
- **JDK 17+** — Gradle 9.5.1's own minimum supported JVM, and the runtime for everything above.
- **Gradle** 9.5.1 (via the wrapper, `./gradlew`) — build orchestration, including compiling the
  four vendored native libraries as part of `processResources`.
- **JGraphT** 1.5.2 — DIMACS parsing (`dialysis.util.GraphIO`) only; no algorithmic use.
- **CaDiCaL** (Biere et al., MIT license) — the SAT backend. Vendored source under
  `src/main/cpp/cadical/`, bound via a hand-written JNI shim (`src/main/cpp/cadical_jni.cpp`); see
  `src/main/cpp/cadical/LICENSE`.
- **nauty & Traces** (Brendan McKay, Adolfo Piperno, et al. — Apache License 2.0) — ground truth.
  `dialysis.cl.TracesJni` binds it in-process for unit tests; `scripts/ground_truth.py` instead
  drives the standalone `dreadnaut` binary as a subprocess for campaign-scale ground truth
  (`BENCHMARK_SPEC.md` Part 1). Vendored source under `src/main/cpp/nauty/`; see
  `src/main/cpp/nauty/COPYRIGHT` for the full license and copyright holders. Distribution site:
  https://pallini.di.uniroma1.it/index.html
- **Python 3** — `scripts/ground_truth.py` and `scripts/merge_ground_truth.py`; standalone, no
  dependency on the JVM build.
- **g++ / make** — building the vendored native libraries (nauty/Traces, CaDiCaL) and the two
  hand-written JNI shims (`colored_ahu.cpp`, `paige_tarjan_wl1.cpp`).

## Building

Requires a JDK, `g++`, and `make` (for the vendored native libraries — nauty/Traces and CaDiCaL).
Everything else is fetched by Gradle.

```
./gradlew build
```

This compiles four native libraries as part of `processResources` (see `build.gradle.kts`):
`libcoloredahu.so`, `libwl1jni.so` (1-WL), `libtracesjni.so` (Traces), and `libcadicaljni.so`
(CaDiCaL — vendored under `src/main/cpp/cadical/`, built via its own `./configure --shared &&
make`, not hand-replicated compiler flags). All four rebuild automatically whenever their sources
change; each is a normal Gradle `Exec` task, so `--info`/`--debug` show the underlying commands if
something goes wrong.

## Running the tests

```
./gradlew test
```

Runs everything except `FullCampaignSatPiTest` — it's unbounded by design (sweeps an entire graph
family) and meant to be run deliberately, not on every `test` invocation. Run it directly if you
want it:

```
./gradlew test --tests "dialysis.configcomparison.FullCampaignSatPiTest"
```

## Running the benchmark CLI

`BenchmarkRunner` (`dialysis.benchmark`) is the standalone, configurable form of what
`FullCampaignSatPiTest` does as a JUnit test — built for running unattended on a benchmark
machine, per `BENCHMARK_SPEC.md`.

```
./gradlew run --args="--family=cfi-rigid-d3 --maxVertices=1000 --workers=4 --config=PI_DIST --repeats=1 --out=results/d3-1000v.csv"
```

or, to avoid paying Gradle's startup cost on every invocation (useful for a long unattended run):

```
./gradlew installDist
build/install/graph-dialysis/bin/graph-dialysis --family=all --maxVertices=3780 --workers=16 --config=PI_DIST --out=results/full.csv
```

**Arguments:**

| Flag | Default | Meaning |
|---|---|---|
| `--family` | *(required)* | A family name (matches a `graphs/` subdirectory), a comma-separated list, or `all` |
| `--maxVertices` | `3000` | Skip any instance whose effective (post-bipartite-subdivision) vertex count exceeds this |
| `--workers` | `1` | Parallelism — colour classes are partitioned across workers (longest-processing-time-first, by `\|C\|²`), each with its own CaDiCaL instance; see `BENCHMARK_SPEC.md` Part 3 |
| `--config` | `PI_DIST` | `PI_DIST` (initial-phase colouring + implied distance clauses — the proposed method), `WL_DIST` (1-WL colouring + implied clauses — ablation: is the richer colouring needed?), or `PI` (initial-phase, no implied clauses — ablation: are the implied clauses needed?) |
| `--repeats` | `1` | Repeats per instance |
| `--seed` | `42` | Seed for the initial (uniform) colouring |
| `--timeoutMs` | `10000` | Long-pass per-query timeout |
| `--shortMs` | `1000` | Short-pass per-query timeout (the cheap first sweep) |
| `--dmax`, `--anchorK` | `6`, `8` | Implied-distance-clause parameters |
| `--maxClassSizeRatio` | `0.10` | Colour classes larger than this fraction of `n` route to a `PI_ONLY` row (report the colouring, skip SAT entirely) rather than risk a pathological encoding |
| `--out` | *(required)* | Output CSV path |

Re-running with the same `--out` **resumes**: instances already present as a row are skipped, so a
run that dies partway through loses nothing already written.

Output columns follow `BENCHMARK_SPEC.md` Part 2.1 (identity / colouring / encoding / solving /
witnesses / result), including the `queries_skipped_witness` vs `queries_skipped_separation` split
and the `CERTIFIED`/`PARTIAL` status. `EXACT` requires ground truth, computed separately with
`scripts/ground_truth.py` (a standalone `dreadnaut`/Traces subprocess per instance) and joined in
with `scripts/merge_ground_truth.py` — not part of this CLI's own pass.

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
or `ERROR` rows. `paley`/`latin` route entirely to `PI_ONLY` (their colour classes exceed the
`maxClassSizeRatio` guard) — these are exactly the "easy family, all configurations must recover
known orbits" controls `BENCHMARK_SPEC.md` Part 4 calls for, not a gap in coverage. `cfi-rigid-s2`
has far fewer instances under the cap than the others because it's edge-dense enough that
bipartite subdivision grows it past 1000 vertices starting at its very next size class (n=128
subdivides past 1400) — the cap is doing its job, not silently skipping something it shouldn't.

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

## License

MIT — see `LICENSE`.