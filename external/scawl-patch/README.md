# Patched ScaWL (2-WL) for FVS-seeded 2-WL experiments

`scawl_seeded.cpp` is a patched copy of ScaWL's `2WL/scawl.cpp`
(github.com/CobySoss/ScaWL, MIT licensed) — see `FVS_SEEDED_1WL_SPEC.md` Part 7 for the full story
(what was changed, why, and two real upstream bugs found and fixed along the way).

Changes from upstream, all additive (search `dialysis project` in the file for exact locations):
- A seed-colour loader + an offset applied once at load time, so `RowsEqual`'s row/column comparison
  respects an externally-supplied per-vertex colouring instead of only raw adjacency.
- A new `argc==4` CLI mode (`<graph.mtx> <seedColourFile> <outputFile>`) that self-compares one graph
  against itself and dumps the final per-vertex stable colour classes to `outputFile`.
- A one-line fix to a loop-termination bug the seed patch itself exposed (forces the pre-loop colour
  count baseline to 0 when seeded, since the pre-existing `maxColor()`-on-raw-values heuristic is only
  valid for unseeded 0/1 adjacency).
- `omp_set_num_threads(1)` in `main()` — works around a confirmed, reproducible upstream
  memory-corruption bug (present in unmodified `scawl.cpp` too) inside `ComputeBestTeam`'s
  multi-threaded team-building logic.

## Build

No real MPI needed — this project only ever runs one process. `dialysis_mpi_stub.h` provides a
single-process-correct `mpi.h` replacement (real semantics for the 2 calls not behind a
`worldSize > 1` guard; safe no-ops for everything else).

```
g++ -std=c++11 -I. -O0 -g -fsanitize=address -static-libasan -pthread -fopenmp \
    scawl_seeded.cpp -o scawl_seeded.exe
```

`-fsanitize=address -O0` is REQUIRED, not optional — the plain (non-ASan) build reproduces a
pre-existing upstream double-free that crashes on real inputs; ASan's allocation pattern happens to
avoid triggering it. `-static-libasan` is needed so the binary works when launched as a JVM
subprocess (`ProcessBuilder`), not just from a plain shell.

Also needs ScaWL's own `parallel_hashmap/` (header-only, vendored in the upstream repo) on the
include path — not copied here; clone github.com/CobySoss/ScaWL and build from within its `2WL/`
directory with this file swapped in, or copy `parallel_hashmap/` alongside this file.

## Usage

```
./scawl_seeded.exe <graph.mtx> <seedColourFile> <outputFile>
```

- `graph.mtx`: Matrix Market coordinate format, BOTH `(u,v)` and `(v,u)` listed explicitly for each
  undirected edge (the loader does not auto-symmetrize).
- `seedColourFile`: one non-negative integer per line, n lines, vertex i's seed colour on line i.
  Vertices meant to share no seed information should share a value that is otherwise unused for
  meaningful distinctions (0 in the dialysis project's own usage).
- `outputFile`: written as `<vertex> <colour>` pairs, one per line, on success.

See `dialysis.experimental.ScawlSeeded2WLTest` in the main project for the Kotlin-side caller
(exports a `dialysis.graph.Graph` + FVS-derived seed labels, invokes this binary, parses the result
back).
