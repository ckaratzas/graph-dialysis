package dialysis.benchmark

import dialysis.ahu.ColoredAHU
import dialysis.content.Content
import dialysis.decomposition.DecompositionStore
import dialysis.gadgetxor.RealFileBypassedGadgetReconstruction
import dialysis.gadgetxor.RealFileBypassedGadgetXor
import dialysis.gadgetxor.RealFileGadgetReconstruction
import dialysis.gadgetxor.RealFileGadgetXor
import dialysis.graph.Graph
import dialysis.refinement.StablePartition
import dialysis.refinement.dispatchColouring
import dialysis.refinement.initialPhase
import dialysis.refinement.perQueryColouring
import dialysis.refinement.uniformSeed
import dialysis.refinement.colorRefine1WL
import dialysis.sat.OrbitDriveResult
import dialysis.sat.cadical.PerQueryDriveResult
import dialysis.sat.cadical.ParallelDriveResult
import dialysis.sat.cadical.addImpliedDistanceClausesCadical
import dialysis.sat.cadical.buildCadicalEncoding
import dialysis.sat.cadical.buildCadicalEncodingSideSwapped
import dialysis.sat.cadical.driveToOrbitsCadicalParallel
import dialysis.sat.cadical.drivePerQueryOrbits
import dialysis.sat.cadical.estimateGlobalEncodingSize
import dialysis.sat.cadical.estimatePerQueryEncodingSize
import dialysis.sat.computeAllPairsDistances
import dialysis.sat.cryptominisat.buildCryptoMiniSatEncoding
import dialysis.sat.cryptominisat.driveToOrbitsCryptoMiniSat
import dialysis.util.GraphIO
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.lang.management.ManagementFactory
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Standalone CLI benchmark runner, BENCHMARK_SPEC.md Part 2 (method comparison) + Part 3
 * (parallelism) -- configurable from the command line (family, vertex cap, parallelism, colouring
 * mode, implied clauses), since this is meant to run unattended on a booked benchmark VM, not
 * through Gradle's test runner.
 *
 * One invocation = one (family selection, config, worker count) combination, run over every
 * matching instance with [repeats] repeats each -- BENCHMARK_SPEC.md's "3 configs x 3 repeats"
 * table is built by invoking this three times (once per [Config]), not by this tool looping over
 * configs internally; that keeps each run composable and independently resumable.
 *
 * Ground truth is NOT computed here -- it's a separate pass, `scripts/ground_truth.py` (dreadnaut
 * subprocess per instance) joined in afterwards with `scripts/merge_ground_truth.py`. This pass
 * leaves `true_orbits`/`gt_source` blank/pending, so `status` can only ever be CERTIFIED or
 * PARTIAL from this tool, never EXACT.
 *
 * Usage:`
 * ```
 * ./gradlew run --args="--family=cfi-rigid-d3 --maxVertices=1000 --workers=4 --config=PI_DIST --repeats=1 --out=results/d3-1000v.csv"
 * ```
 * or, after `./gradlew installDist`, directly via `build/install/graph-dialysis/bin/graph-dialysis <args>`.
 */

/** BENCHMARK_SPEC.md Part 2's three configurations. */
enum class Config { PI_DIST, WL_DIST, PI }

/**
 * Which SAT backend GLOBAL mode drives orbits with. CADICAL is the default, unchanged path.
 * CRYPTOMINISAT drives the identical plain-CNF encoding through CryptoMiniSat instead (a soundness
 * cross-check, no gadget-XOR clause). CRYPTOMINISAT_XOR additionally reconstructs the gadget
 * structure from topology alone and adds the cluster-generalized flip-parity XOR clause -- for
 * cfi-rigid-r2 via dialysis.gadgetxor.RealFileGadgetReconstruction/RealFileGadgetXor (the
 * non-bypassed case), and for cfi-rigid-t2 via
 * dialysis.gadgetxor.RealFileBypassedGadgetReconstruction/RealFileBypassedGadgetXor (t2 bypasses
 * the outer a(w)/b(w) vertices entirely -- every vertex is a gadget member, and "sideOf" becomes
 * per-port clique membership rather than a single global per-vertex label; see that class's own
 * doc). Both are validated sound against real Traces-computed automorphisms on rigid and
 * non-rigid real files of their own family (dialysis.RealFileGadgetXorSoundnessTest and
 * dialysis.RealFileBypassedGadgetXorSoundnessTest, test source set). ONLY these two families are
 * supported -- s2 uses the same bypass as t2 but WITHOUT r2's base-graph reduction, and its
 * reconstruction hits a materially harder residual-symmetry degeneracy that was investigated and
 * explicitly not solved (see the project's own notes); requesting it (or any other family) with
 * this solver fails fast. Neither CryptoMiniSat mode parallelizes across queries (unlike CADICAL,
 * which honours --workers via driveToOrbitsCadicalParallel) -- --workers is ignored (forced to 1)
 * under either.
 */
enum class Solver { CADICAL, CRYPTOMINISAT, CRYPTOMINISAT_XOR }

private val GADGET_XOR_SUPPORTED_FAMILIES = setOf("cfi-rigid-r2", "cfi-rigid-t2")

data class CliArgs(
    val families: List<String>,          // "all" or explicit comma-separated family names
    val minVertices: Int,
    val maxVertices: Int,
    val workers: Int,
    val config: Config,
    val repeats: Int,
    val seed: Long,
    val timeoutMs: Long,
    val shortMs: Long,
    val dmax: Int,
    val anchorK: Int,
    val edgeClauseThreshold: Long,
    val maxInstanceSolveMs: Long,
    val out: File,
    // Off by default (matches every existing run's behaviour). --noSubdivision=true forces
    // dispatchColouring to skip sd(g) and the initial phase on it, using plain 1-WL(g) for every
    // non-bipartite instance regardless of family -- for a family where the comparison in
    // dispatchColouring's own doc always resolves to 1-WL anyway, this is strictly faster and
    // avoids DecompositionStore's O(n^2) scratch file on the subdivided vertex count entirely (see
    // that class's own doc on why that can exceed a small java.io.tmpdir partition). Also lets a
    // campaign compare configs on the SAME (never-subdivided) basis across every family, instead of
    // some families implicitly subdividing and others not depending on dispatchColouring's own
    // per-instance decision.
    val allowSubdivision: Boolean,
    val solver: Solver,
)

private fun parseArgs(args: Array<String>): CliArgs {
    val map = args.associate { arg ->
        require(arg.startsWith("--") && arg.contains('=')) { "bad argument '$arg', expected --key=value" }
        val kv = arg.removePrefix("--").split('=', limit = 2)
        kv[0] to kv[1]
    }
    fun str(key: String, default: String? = null): String = map[key] ?: default ?: error("--$key is required")
    return CliArgs(
        families = str("family").split(',').map { it.trim() },
        // Lets a campaign resume past an older, schema-incompatible CSV (e.g. one predating the
        // Task 1/2 diagnostic columns) without re-running the instances it already covers: point a
        // NEW --out at this floor instead of trying to append into the old file. Gates on the same
        // quantity --maxVertices does (original.n, not n+m -- see that check's own comment), so the
        // two form a single closed [minVertices, maxVertices] band with no gap or overlap.
        minVertices = str("minVertices", "0").toInt(),
        maxVertices = str("maxVertices", "3000").toInt(),
        workers = str("workers", "1").toInt(),
        config = Config.valueOf(str("config", "PI_DIST")),
        repeats = str("repeats", "1").toInt(),
        seed = str("seed", "42").toLong(),
        timeoutMs = str("timeoutMs", "10000").toLong(),
        shortMs = str("shortMs", "1000").toLong(),
        dmax = str("dmax", "6").toInt(),
        anchorK = str("anchorK", "8").toInt(),
        // project_dialysis_final_measurements_task2 memory: certification held up to ~1M measured
        // edge-conflict clauses (had-64) and failed at ~3.28M (latin-20); 2,000,000 sits between the
        // two with margin on both sides. Not hunted for precisely -- this campaign's own
        // global_edge_clause_estimate/per_query_edge_clause_estimate columns are what should decide
        // whether this default is right, not a targeted search (see that memory's own reasoning).
        edgeClauseThreshold = str("edgeClauseThreshold", "2000000").toLong(),
        // Hard wall-clock backstop on ONE instance's solve phase, independent of --timeoutMs (which
        // only bounds a SINGLE query). A class with many members can need many queries before
        // generator closure catches up (measured directly: had-20, 80 members, took 26-62s despite
        // every individual query resolving in ~1s -- see project_dialysis_final_measurements_task2
        // memory), and nothing before this bounded the TOTAL. Default 2 minutes: generous for any
        // instance that's actually making progress, short enough that one bad instance can't stall
        // a campaign of hundreds.
        maxInstanceSolveMs = str("maxInstanceSolveMs", "120000").toLong(),
        out = File(str("out")),
        allowSubdivision = str("noSubdivision", "false").toBoolean().not(),
        solver = Solver.valueOf(str("solver", "CADICAL").uppercase()),
    )
}

/** Daemon threads so a still-running (e.g. deadline-abandoned GLOBAL) solve never blocks JVM exit;
 *  cached so a long campaign doesn't pile up one thread per instance that hit the deadline. */
private val solveExecutor = Executors.newCachedThreadPool { r -> Thread(r, "benchmark-solve").apply { isDaemon = true } }

private val headerColumns = listOf(
    "family", "instance", "n", "m", "config", "repeat", "seed",
    "classes_1wl", "classes_pi", "class_size_max", "class_size_mean", "colouring_ms",
    "variables", "clauses_bijection", "clauses_edge", "clauses_implied", "anchors_k", "dmax", "encode_ms", "formula_peak_rss_mb",
    "queries_issued", "queries_skipped_witness", "queries_skipped_separation", "sat", "unsat", "unknown",
    "solve_ms_total", "solve_ms_max", "solve_ms_median", "per_query_timeout_ms", "workers", "straggler_ratio",
    "witnesses_verified", "witnesses_rejected",
    "recovered_orbits", "true_orbits", "gt_source", "status", "total_ms", "peak_rss_mb",
    // FINAL_MEASUREMENTS_SPEC.md Task 1: solving now always happens on the ORIGINAL graph (n, not
    // n+m) -- classes_1wl/classes_pi above are computed w.r.t. that same original graph too, so
    // these columns record HOW that colouring was chosen, not the colouring itself (already in
    // classes_1wl/classes_pi).
    "wl1_original", "pi_subdivision", "pi_to_original", "colouring_used", "subdivided", "subdivision_mode", "n_solved", "filter_mode",
    // Task 2 integration: Sigma_edges |C(i)|*|C(k)|, computed in one pass from the colouring alone,
    // BEFORE any solving is attempted -- see estimateGlobalEncodingSize/estimatePerQueryEncodingSize
    // and project_dialysis_final_measurements_task2 memory for why this predicts feasibility. Always
    // populated (when the instance has a class of size >= 2 to measure), regardless of which mode
    // was actually used, so the whole campaign's data can be bucketed by either number afterward.
    "global_edge_clause_estimate", "per_query_edge_clause_estimate",
    // --solver: which SAT backend GLOBAL mode used (always CADICAL for PER_QUERY/SKIPPED_TOO_LARGE
    // rows -- gadget-XOR is GLOBAL-only). gadget_xor_clusters is only populated for
    // CRYPTOMINISAT_XOR rows: how many clusters (see RealFileGadgetXor's own doc) actually got a
    // constraining XOR clause added.
    "solver", "gadget_xor_clusters",
)
private val header = headerColumns.joinToString(",")

/** A row with the right column count even on failure -- a hand-typed comma string is too easy to
 *  miscount against [headerColumns], and the exception message could itself contain commas or
 *  newlines that would otherwise corrupt this (unquoted) CSV. */
private fun errorRow(family: String, path: String, cli: CliArgs, e: Throwable): String {
    val row = MutableList(headerColumns.size) { "" }
    row[headerColumns.indexOf("family")] = family
    row[headerColumns.indexOf("instance")] = path
    row[headerColumns.indexOf("config")] = cli.config.toString()
    row[headerColumns.indexOf("status")] = "ERROR: ${e::class.simpleName}: ${e.message}".replace(',', ';').replace('\n', ' ')
    return row.joinToString(",")
}

/** Whole-process (not per-worker -- see CadicalParallelDriver.kt's own doc on why a true
 *  per-worker breakdown isn't meaningfully separable for threads sharing one JVM) resident set
 *  size in MB, via /proc/self/status on Linux; falls back to the JVM heap estimate elsewhere
 *  (documented as less accurate -- it misses native CaDiCaL memory entirely). */
private fun peakRssMb(): Double {
    val status = File("/proc/self/status")
    if (status.exists()) {
        val line = status.readLines().firstOrNull { it.startsWith("VmHWM:") }
        if (line != null) {
            val kb = line.trim().split(Regex("\\s+"))[1].toLongOrNull()
            if (kb != null) return kb / 1024.0
        }
    }
    val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
    return heap.used / (1024.0 * 1024.0)
}

private fun listInstances(dir: String): List<String> =
    File("graphs/$dir").listFiles()?.filter { it.isFile }?.map { it.path }?.sorted() ?: emptyList()

private fun alreadyDone(out: File): Set<String> {
    if (!out.exists()) return emptySet()
    return out.readLines().drop(1).mapNotNull { it.split(',').getOrNull(1)?.takeIf(String::isNotBlank) }.toSet()
}

fun main(args: Array<String>) {
    val cli = parseArgs(args)
    cli.out.parentFile?.mkdirs()
    val done = alreadyDone(cli.out)
    val resuming = done.isNotEmpty()
    if (resuming) {
        println("Resuming: ${done.size} instance(s) already in ${cli.out.path}, skipping those.")
        val bytes = cli.out.readBytes()
        if (bytes.isNotEmpty() && bytes.last() != '\n'.code.toByte()) cli.out.appendText("\n")
    }

    val familyDirs = if (cli.families.singleOrNull() == "all") {
        (File("graphs").listFiles()?.filter { it.isDirectory } ?: emptyList()).map { it.name }.sorted()
    } else {
        cli.families
    }
    if (cli.solver == Solver.CRYPTOMINISAT_XOR) {
        val invalid = familyDirs.filterNot { it in GADGET_XOR_SUPPORTED_FAMILIES }
        require(invalid.isEmpty()) {
            "--solver=CRYPTOMINISAT_XOR only supports $GADGET_XOR_SUPPORTED_FAMILIES -- remove ${invalid.joinToString()} from --family"
        }
    }

    PrintWriter(FileWriter(cli.out, resuming)).use { writer ->
        if (!resuming) writer.println(header)
        for (family in familyDirs) {
            for (path in listInstances(family)) {
                if (path in done) { println("SKIP (already done): $path"); continue }
                try {
                    // FINAL_MEASUREMENTS_SPEC.md Task 1: NEVER eagerly subdivide here -- solving
                    // happens on this ORIGINAL graph directly regardless of config (see
                    // measureOne/dispatchColouring); a subdivision, when needed at all, is built
                    // internally and only for the colouring phase. The vertex cap therefore gates
                    // on n (what's actually encoded/solved), not n+m.
                    val original = GraphIO.loadDimacs(File(path).toPath())
                    if (original.n < cli.minVertices) {
                        println("SKIP (below vertex floor): $path (n=${original.n})")
                        continue
                    }
                    if (original.n > cli.maxVertices) {
                        println("SKIP (vertex cap): $path (n=${original.n})")
                        continue
                    }
                    println("STARTING: [$family] $path n=${original.n} m=${original.m} config=${cli.config} workers=${cli.workers}")
                    System.out.flush()
                    for (repeat in 1..cli.repeats) {
                        val row = measureOne(family, path, original, cli, repeat)
                        writer.println(row)
                        writer.flush()
                        println("  -> $row")
                        if (",INSTANCE_TIMEOUT," in row) {
                            // A timeout's Future.cancel(true) is best-effort ONLY (see the timeout
                            // handlers' own comments) -- it can't actually stop a thread blocked
                            // inside a native JNI solve() call. Confirmed directly: continuing to
                            // the NEXT instance in this same process after a CryptoMiniSat timeout
                            // reliably SIGABRTs (native "corrupted" crash) shortly after, because the
                            // orphaned solve() thread is still running/freeing shared native state
                            // out from under the next instance's own native calls. Exiting here (a
                            // clean, expected exit -- NOT a crash) hands off to the external
                            // resume/retry wrapper, which restarts in a fresh, uncorrupted process
                            // and resumes via this same CSV, skipping everything already written.
                            println("Exiting after INSTANCE_TIMEOUT to avoid reusing a possibly-corrupted native solver state -- re-run the same command to resume.")
                            // A bare `return` here only returns from main() -- it does NOT guarantee
                            // the JVM actually terminates. The orphaned worker thread this comment
                            // already worries about (Future.cancel(true) is best-effort against a
                            // thread stuck in a native JNI call or an uninterruptible per-query loop)
                            // runs on Executors.newFixedThreadPool's NON-daemon threads, and
                            // pool.shutdown() (called in that driver's own `finally`) does not stop
                            // an already-running task -- it only stops accepting new ones. Confirmed
                            // directly: a 2026-08-30 campaign run left the JVM alive and burning a
                            // full CPU core for 30+ minutes after printing this exact message, because
                            // main() had returned but that orphaned thread had not. exitProcess forces
                            // the JVM down regardless, matching what this comment already documents as
                            // the intended, expected behavior here.
                            kotlin.system.exitProcess(0)
                        }
                    }
                } catch (e: Throwable) {
                    println("FAILED: $path -- ${e::class.simpleName}: ${e.message}")
                    writer.println(errorRow(family, path, cli, e))
                    writer.flush()
                }
            }
        }
    }
    println("Results written to ${cli.out.path}")
}

/** Groups vertex indices by colour value -- for a raw [Array]<[Content]> (e.g.
 *  [dialysis.refinement.ColouringDispatch.colouring]) that isn't already wrapped in a
 *  [dialysis.refinement.StablePartition]. Returns [List]<[IntArray]>, matching
 *  [dialysis.refinement.StablePartition.cells]'s own type, so callers can use either
 *  interchangeably. */
private fun cellsOf(colours: Array<Content>): List<IntArray> =
    colours.indices.groupBy { colours[it] }.values.map { it.toIntArray() }

private fun measureOne(family: String, path: String, g: Graph, cli: CliArgs, repeat: Int): String {
    // Bounds ColoredAHU's (thread-local) intern pool to this one instance -- see
    // ColoredAHU.clearInternPool's own doc for why this is safe under --workers > 1 and why it
    // matters: left uncleared, a family whose instances share no structure with each other (e.g.
    // rnd-3-reg) accumulates every distinct subtree shape ever seen for the life of the process,
    // eventually OOMing a long campaign even though any single instance's own formula is trivial.
    ColoredAHU.clearInternPool()

    // FINAL_MEASUREMENTS_SPEC.md Task 1: colouring is now ALWAYS computed w.r.t. this ORIGINAL
    // graph's own n vertices, whether or not a subdivision was needed along the way -- see
    // dispatchColouring's own doc for the soundness argument. 1-WL never needed subdivision either
    // (unlike the initial phase, which requires bipartite input for its tree decomposition), so it
    // runs on [g] directly regardless of config or bipartite-ness.
    val colouringT0 = System.currentTimeMillis()
    val dispatch = dispatchColouring(g, cli.allowSubdivision)
    // dispatchColouring's own non-bipartite branch already computes plain 1-WL internally (to
    // compare against project(Pi(sd(g)))) -- reuse that instead of paying for a second, identical
    // colorRefine1WL(g, uniformSeed(g.n)) call. Only the bipartite branch (wl1Colouring == null)
    // never computed it, so only that branch actually pays for it here.
    val wl1Colour = dispatch.wl1Colouring ?: colorRefine1WL(g, uniformSeed(g.n)).color
    val wl1Cells = cellsOf(wl1Colour)
    val colouringMs = System.currentTimeMillis() - colouringT0

    // classes_pi/classes_1wl retain their historical column meaning (the PI-config colouring's
    // class count, and 1-WL's), just computed over g's own n vertices now instead of n+m. The
    // invariant holds by construction of dispatchColouring's own selection rule (it picks whichever
    // of {1-WL(g), project(Pi(sd(g)))} has MORE classes, tie -> 1-WL), not because Pi unconditionally
    // dominates 1-WL globally (only true when both are computed on the identical graph, i.e. the
    // bipartite branch).
    val classesPi = dispatch.colouring.toHashSet().size
    check(classesPi >= wl1Cells.size) {
        "INVARIANT VIOLATED on $path: classes_pi ($classesPi) < classes_1wl (${wl1Cells.size})"
    }

    val useWl = cli.config == Config.WL_DIST
    val useImplied = cli.config != Config.PI
    val activeColouring = if (useWl) wl1Colour else dispatch.colouring
    val colorOf: (Int) -> Content = { v -> activeColouring[v] }
    val activeCells = if (useWl) wl1Cells else cellsOf(dispatch.colouring)
    val classSizes = activeCells.map { it.size }
    val classSizeMax = classSizes.max()
    val classSizeMean = classSizes.average()

    // Task 2 integration: Sigma_edges |C(i)|*|C(k)| under the ACTIVE colouring, computed in one pass
    // from the colouring alone -- no CadicalSolver constructed, safe regardless of how large the
    // real encoding would be. See estimateGlobalEncodingSize's own doc and
    // project_dialysis_final_measurements_task2 memory for why this is the gate, not a class-size
    // ratio: it is what actually predicts the cost that crashes a run, not a proxy for it.
    val globalEstimate = estimateGlobalEncodingSize(g, colorOf)

    // A representative per-query estimate: individualize the first two members of the LARGEST
    // class (the one the global formula would spend the most on) and measure what that query's own
    // formula would need -- also solver-free, also always safe to compute. LAZY: only computed when
    // the global estimate alone doesn't already clear the threshold -- building this requires a
    // full DecompositionStore (a parallel BFS decomposition of the WHOLE graph), which is real work
    // on a large instance, and the vast majority of instances (most cfi-rigid ones especially, with
    // many small classes) resolve to GLOBAL from the global estimate alone. Paying this cost
    // unconditionally on every instance -- including ones that were always going to be GLOBAL -- was
    // measured as a real contributor to "the campaign takes ages" on a full run. Built and shared
    // once so the real PER_QUERY solve below (if it happens) reuses this exact store rather than
    // rebuilding it per query -- see InitialPhase.kt's own doc on why that redundant rebuild is
    // expensive.
    val biggestCell = activeCells.maxByOrNull { it.size }!!
    // globalEstimate.bijectionClauses (O(k^3) in the largest class's size -- see
    // estimateGlobalEncodingSize's own doc) can blow the GLOBAL formula up even when
    // edgeConflictClauses stays small (a class with few crossing edges to other classes), a gap
    // that let a real cmz-family campaign OOM past this exact gate -- so the "is GLOBAL too big"
    // check below must fail on EITHER estimate being over threshold, not edgeConflictClauses alone.
    val globalTooBig = globalEstimate.edgeConflictClauses > cli.edgeClauseThreshold ||
        globalEstimate.bijectionClauses > cli.edgeClauseThreshold
    var perQueryEstimateClauses: Long? = null
    var perQueryTooBig: Boolean? = null
    var refine: ((Graph, Array<Content>) -> StablePartition)? = null
    if (globalTooBig && biggestCell.size >= 2) {
        val store = DecompositionStore.build(g)
        val r: (Graph, Array<Content>) -> StablePartition = { graph, initial -> initialPhase(graph, initial, false, store) }
        refine = r
        val cU = perQueryColouring(g, activeColouring, biggestCell[0], r)
        val cV = perQueryColouring(g, activeColouring, biggestCell[1], r)
        val perQueryEstimate = estimatePerQueryEncodingSize(g, cU, cV)
        perQueryEstimateClauses = perQueryEstimate.edgeConflictClauses
        // Same gap as globalTooBig above -- PerQueryCadicalEncoder's own exactlyOneVars is the
        // identical O(k^3)-per-bucket cost (see estimatePerQueryEncodingSize's own doc), and this
        // is what actually blew up `ag2-16` on 2026-08-29: its edgeConflictClauses estimate alone
        // was comfortably under threshold and would have routed PER_QUERY here unprotected.
        perQueryTooBig = perQueryEstimateClauses > cli.edgeClauseThreshold ||
            perQueryEstimate.bijectionClauses > cli.edgeClauseThreshold
    }

    val filterMode = when {
        !globalTooBig -> "GLOBAL"
        perQueryTooBig == false -> "PER_QUERY"
        else -> "NONE"
    }
    val estimateColumns = listOf(globalEstimate.edgeConflictClauses.toString(), perQueryEstimateClauses?.toString() ?: "")

    // New Task 1 diagnostic columns -- same for every row of this instance, whichever branch below returns.
    val dispatchColumns = listOf(
        dispatch.wl1OriginalClasses?.toString() ?: "",
        dispatch.piSubdivisionClasses?.toString() ?: "",
        dispatch.piToOriginalClasses?.toString() ?: "",
        dispatch.used, dispatch.subdivided, if (cli.allowSubdivision) "AUTO" else "OFF", g.n,
        filterMode,
    )

    if (filterMode == "NONE") {
        println(
            "  SKIPPED_TOO_LARGE (global_edge_clause_estimate=${globalEstimate.edgeConflictClauses} " +
                "global_bijection_clause_estimate=${globalEstimate.bijectionClauses} " +
                "per_query_edge_clause_estimate=${perQueryEstimateClauses ?: "n/a"} > threshold=${cli.edgeClauseThreshold})",
        )
        return (
            listOf(
                family, path, g.n, g.m, cli.config, repeat, cli.seed,
                wl1Cells.size, classesPi, classSizeMax, "%.2f".format(classSizeMean), colouringMs,
                "", "", "", "", "", "", "", "",
                "", "", "", "", "", "",
                "", "", "", "", cli.workers, "",
                "", "",
                activeCells.size, "", "", "SKIPPED_TOO_LARGE", colouringMs, "%.1f".format(peakRssMb()),
            ) + dispatchColumns + estimateColumns + listOf(cli.solver.toString(), "")
        ).joinToString(",")
    }

    if (filterMode == "PER_QUERY") {
        val refineFn = checkNotNull(refine) { "PER_QUERY chosen but no class had >= 2 members -- should be unreachable" }
        println("  PER_QUERY (global_edge_clause_estimate=${globalEstimate.edgeConflictClauses} per_query_edge_clause_estimate=$perQueryEstimateClauses)")

        // Same quantity the edgeClauseThreshold gate above used, not a smaller/unrelated cap: that
        // gate only ever measured ONE representative pair from the largest class, so a query
        // elsewhere in this instance can legitimately need a bigger formula -- a safety margin above
        // the threshold that already justified trying PER_QUERY at all, not an arbitrary constant.
        val maxEdgeClauses = cli.edgeClauseThreshold * 2
        var totalIssued = 0; var totalSkippedWitness = 0; var totalSkippedSeparation = 0
        var totalSat = 0; var totalUnsat = 0; var totalUnknown = 0
        var totalVerified = 0; var totalRejected = 0
        var totalOrbits = 0
        val allPerQueryMs = mutableListOf<Long>()

        val solveT0 = System.currentTimeMillis()
        val instanceDeadline = solveT0 + cli.maxInstanceSolveMs
        // The cooperative deadline checks inside/between classes handle the common case, but they
        // can only fire BETWEEN queries -- a single pathologically slow encoding build or solve call
        // has nothing forcing it to stop early. Wrapping the whole loop in the same daemon executor
        // GLOBAL mode uses (future.get with a hard timeout) closes that gap: if it fires, this
        // instance's row is reported as INSTANCE_TIMEOUT and the campaign moves on regardless of
        // whether the abandoned background computation ever actually stops.
        val perQueryFuture = solveExecutor.submit(
            Callable {
                for (cell in activeCells) {
                    if (cell.size < 2) { totalOrbits += 1; continue }
                    if (System.currentTimeMillis() >= instanceDeadline) {
                        // Same treatment as a mid-class deadline cutoff (see drivePerQueryOrbits's
                        // own doc): every pair in this not-yet-started class is unresolved, counted
                        // as both issued and unknown so invariants downstream still hold, never
                        // silently dropped.
                        val pairs = cell.size * (cell.size - 1)
                        totalIssued += pairs; totalUnknown += pairs; totalOrbits += cell.size
                        continue
                    }
                    val r: PerQueryDriveResult = drivePerQueryOrbits(g, activeColouring, cell.toList(), refineFn, cli.timeoutMs, maxEdgeClauses, instanceDeadline)
                    totalIssued += r.queriesIssued
                    totalSkippedWitness += r.queriesSkippedWitness
                    totalSkippedSeparation += r.queriesSkippedSeparation
                    totalSat += r.sat; totalUnsat += r.unsat; totalUnknown += r.unknown
                    totalVerified += r.witnessesVerified; totalRejected += r.witnessesRejected
                    totalOrbits += r.orbits.size
                    allPerQueryMs += r.perQueryMs
                }
            },
        )
        try {
            perQueryFuture.get(cli.maxInstanceSolveMs + 5_000L, TimeUnit.MILLISECONDS) // small grace margin over the cooperative deadline itself
        } catch (e: TimeoutException) {
            perQueryFuture.cancel(true) // best-effort only, same caveat as the GLOBAL path below
            val solveMs = System.currentTimeMillis() - solveT0
            val totalMs = colouringMs + solveMs
            println("  INSTANCE_TIMEOUT: $path exceeded maxInstanceSolveMs=${cli.maxInstanceSolveMs} (PER_QUERY, hard executor timeout)")
            return (
                listOf(
                    family, path, g.n, g.m, cli.config, repeat, cli.seed,
                    wl1Cells.size, classesPi, classSizeMax, "%.2f".format(classSizeMean), colouringMs,
                    "", "", "", "", "", "", "", "",
                    "", "", "", "", "", "",
                    solveMs, "", "", cli.timeoutMs, 1, "",
                    "", "",
                    "", "", "", "INSTANCE_TIMEOUT", totalMs, "%.1f".format(peakRssMb()),
                ) + dispatchColumns + estimateColumns + listOf(cli.solver.toString(), "")
            ).joinToString(",")
        }
        val solveMs = System.currentTimeMillis() - solveT0

        check(totalRejected == 0) { "$path: $totalRejected REJECTED witness(es) -- aborting per BENCHMARK_SPEC.md 2.3" }
        check(totalIssued == totalSat + totalUnsat + totalUnknown) {
            "$path: sat+unsat+unknown ($totalSat+$totalUnsat+$totalUnknown) != queriesIssued ($totalIssued)"
        }
        check(totalOrbits >= activeCells.size) {
            "$path: recovered_orbits ($totalOrbits) < classes (${activeCells.size}) -- orbits must refine colour classes"
        }

        val sortedMs = allPerQueryMs.sorted()
        val solveMsMax = sortedMs.lastOrNull() ?: 0
        val solveMsMedian = if (sortedMs.isEmpty()) 0 else sortedMs[sortedMs.size / 2]
        val status = if (totalUnknown == 0) "CERTIFIED" else "PARTIAL"
        val totalMs = colouringMs + solveMs
        return (
            listOf(
                family, path, g.n, g.m, cli.config, repeat, cli.seed,
                wl1Cells.size, classesPi, classSizeMax, "%.2f".format(classSizeMean), colouringMs,
                // No single reference encoding exists under PER_QUERY (every query rebuilds its own,
                // by design -- see PerQueryCadicalEncoder.kt's own doc); estimateColumns below carry
                // the size information for this mode instead. Implied-distance clauses are not
                // supported in PER_QUERY mode (orthogonal ablation, not yet wired through).
                "", "", "", "", "", "", "", "",
                totalIssued, totalSkippedWitness, totalSkippedSeparation, totalSat, totalUnsat, totalUnknown,
                solveMs, solveMsMax, solveMsMedian, cli.timeoutMs, 1, "", // workers=1 -- PER_QUERY does not parallelize across classes (yet)
                totalVerified, totalRejected,
                totalOrbits, "", "", status, totalMs, "%.1f".format(peakRssMb()),
            ) + dispatchColumns + estimateColumns + listOf(cli.solver.toString(), "")
        ).joinToString(",")
    }

    // filterMode == "GLOBAL". --solver selects the backend: CADICAL (default, unchanged) below, or
    // one of the CryptoMiniSat modes, handled and returned from entirely separately (see
    // measureOneGlobalCryptoMiniSat) since OrbitDriveResult's shape/available diagnostics differ
    // enough from ParallelDriveResult's that force-fitting one branch to cover both would obscure
    // more than it'd share.
    if (cli.solver != Solver.CADICAL) {
        return measureOneGlobalCryptoMiniSat(
            family, path, g, cli, repeat, colorOf,
            wl1Cells, classesPi, classSizeMax, classSizeMean, colouringMs, dispatchColumns, estimateColumns,
        )
    }

    val dist = if (useImplied) computeAllPairsDistances(g) else null

    val encodeT0 = System.currentTimeMillis()
    val (refSolver, refEncoding) = buildCadicalEncoding(g, colorOf)
    val refSwap = buildCadicalEncodingSideSwapped(g, colorOf)
    var impliedClauses = 0
    if (useImplied) {
        impliedClauses += addImpliedDistanceClausesCadical(g, refSolver, refEncoding, dist!!, cli.dmax, cli.anchorK).clausesAdded
        if (refSwap != null) impliedClauses += addImpliedDistanceClausesCadical(g, refSwap.first, refSwap.second, dist, cli.dmax, cli.anchorK).clausesAdded
    }
    val encodeMs = System.currentTimeMillis() - encodeT0
    val formulaPeakRssMb = peakRssMb()
    refSolver.close()
    refSwap?.first?.close()

    // driveToOrbitsCadicalParallel's own timeoutMs/shortMs only bound a SINGLE query's cost -- a
    // class with many members can need many queries before generator closure catches up (measured
    // directly: had-20 took 26-62s despite every individual query resolving in ~1s). Run it on a
    // daemon executor with a hard WALL-CLOCK deadline so one pathological instance can never stall
    // the whole campaign; this function is shared/untouched code (not modified for this), so an
    // external deadline is the safe way to bound it without risking its own validated behaviour.
    val solveT0 = System.currentTimeMillis()
    val solveFuture = solveExecutor.submit(
        Callable {
            driveToOrbitsCadicalParallel(
                g, colorOf, cli.workers, cli.timeoutMs, cli.shortMs,
                useImpliedDistanceClauses = useImplied, dmax = cli.dmax, anchorK = cli.anchorK, dist = dist,
            )
        },
    )
    val result: ParallelDriveResult = try {
        solveFuture.get(cli.maxInstanceSolveMs, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        solveFuture.cancel(true) // best-effort only -- CaDiCaL's native call won't actually stop, but this instance's row is reported now regardless
        val solveMs = System.currentTimeMillis() - solveT0
        val totalMs = colouringMs + encodeMs + solveMs
        println("  INSTANCE_TIMEOUT: $path exceeded maxInstanceSolveMs=${cli.maxInstanceSolveMs}")
        return (
            listOf(
                family, path, g.n, g.m, cli.config, repeat, cli.seed,
                wl1Cells.size, classesPi, classSizeMax, "%.2f".format(classSizeMean), colouringMs,
                refEncoding.numVars, refEncoding.bijectionConstraints, refEncoding.edgeConflictClauses, impliedClauses, cli.anchorK, cli.dmax, encodeMs, "%.1f".format(formulaPeakRssMb),
                "", "", "", "", "", "",
                solveMs, "", "", cli.timeoutMs, cli.workers, "",
                "", "",
                "", "", "", "INSTANCE_TIMEOUT", totalMs, "%.1f".format(peakRssMb()),
            ) + dispatchColumns + estimateColumns + listOf(cli.solver.toString(), "")
        ).joinToString(",")
    }
    val solveMs = System.currentTimeMillis() - solveT0

    check(result.witnessesRejected == 0) { "$path: ${result.witnessesRejected} REJECTED witness(es) -- aborting per BENCHMARK_SPEC.md 2.3" }
    check(result.queriesIssued == result.sat + result.unsat + result.unknown) {
        "$path: sat+unsat+unknown (${result.sat}+${result.unsat}+${result.unknown}) != queriesIssued (${result.queriesIssued})"
    }
    // Orbits REFINE the colour partition (every orbit sits inside exactly one colour class, and a
    // class can split into several orbits -- an automorphism can never move a vertex to a
    // differently-coloured one), so recovered_orbits >= classes always. BENCHMARK_SPEC.md 2.3
    // states this inequality backwards ("recovered_orbits <= classes_pi") -- confirmed a typo
    // there, not a bug here.
    check(result.orbits.size >= activeCells.size) {
        "$path: recovered_orbits (${result.orbits.size}) < classes (${activeCells.size}) -- orbits must refine colour classes"
    }

    val status = if (result.unknown == 0) "CERTIFIED" else "PARTIAL"
    val totalMs = colouringMs + encodeMs + solveMs
    return (
        listOf(
            family, path, g.n, g.m, cli.config, repeat, cli.seed,
            wl1Cells.size, classesPi, classSizeMax, "%.2f".format(classSizeMean), colouringMs,
            refEncoding.numVars, refEncoding.bijectionConstraints, refEncoding.edgeConflictClauses, impliedClauses, cli.anchorK, cli.dmax, encodeMs, "%.1f".format(formulaPeakRssMb),
            result.queriesIssued, result.queriesSkippedWitness, result.queriesSkippedSeparation, result.sat, result.unsat, result.unknown,
            solveMs, result.solveMsMax, result.solveMsMedian, cli.timeoutMs, cli.workers, "%.2f".format(result.stragglerRatio),
            result.witnessesVerified, result.witnessesRejected,
            result.orbits.size, "", "", status, totalMs, "%.1f".format(peakRssMb()),
        ) + dispatchColumns + estimateColumns + listOf(cli.solver.toString(), "")
    ).joinToString(",")
}

/**
 * GLOBAL mode, --solver=CRYPTOMINISAT or CRYPTOMINISAT_XOR. Separate from [measureOne]'s CaDiCaL
 * path (see that call site's own comment on why): [driveToOrbitsCryptoMiniSat] returns
 * [OrbitDriveResult], which -- unlike CaDiCaL's [ParallelDriveResult] -- doesn't track
 * per-query timings (no solve_ms_max/median) or separate "already-connected" from "already
 * separated" skip reasons (one combined [OrbitDriveResult.skippedAlreadyConnected] counter), and
 * never parallelizes (--workers is ignored, always reported as 1). Implied-distance clauses are
 * also not wired through this path (CADICAL-only ablation) -- impliedClauses is always 0 here.
 */
private fun measureOneGlobalCryptoMiniSat(
    family: String,
    path: String,
    g: Graph,
    cli: CliArgs,
    repeat: Int,
    colorOf: (Int) -> Content,
    wl1Cells: List<IntArray>,
    classesPi: Int,
    classSizeMax: Int,
    classSizeMean: Double,
    colouringMs: Long,
    dispatchColumns: List<Any>,
    estimateColumns: List<String>,
): String {
    if (cli.solver == Solver.CRYPTOMINISAT_XOR) {
        require(family in GADGET_XOR_SUPPORTED_FAMILIES) {
            "--solver=CRYPTOMINISAT_XOR only supports $GADGET_XOR_SUPPORTED_FAMILIES -- gadget reconstruction is " +
                "unsound/inapplicable on any other family (got family=$family)"
        }
    }

    val encodeT0 = System.currentTimeMillis()
    val (solver, encoding) = buildCryptoMiniSatEncoding(g, colorOf)
    var gadgetXorClusters = ""
    if (cli.solver == Solver.CRYPTOMINISAT_XOR) {
        gadgetXorClusters = if (family == "cfi-rigid-r2") {
            val recon = RealFileGadgetReconstruction.reconstruct(g, path)
            val sided = RealFileGadgetXor.prepare(recon)
            RealFileGadgetXor.addClusterParityXors(solver, encoding, sided).toString()
        } else {
            val recon = RealFileBypassedGadgetReconstruction.reconstruct(g, path)
            val sided = RealFileBypassedGadgetXor.prepare(recon)
            RealFileBypassedGadgetXor.addClusterParityXors(solver, encoding, sided).toString()
        }
    }
    val encodeMs = System.currentTimeMillis() - encodeT0
    val formulaPeakRssMb = peakRssMb()

    val solveT0 = System.currentTimeMillis()
    val solveFuture = solveExecutor.submit(Callable { driveToOrbitsCryptoMiniSat(g, solver, encoding, cli.timeoutMs, cli.shortMs) })
    val result: OrbitDriveResult = try {
        solveFuture.get(cli.maxInstanceSolveMs, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        solveFuture.cancel(true) // best-effort only, same caveat as the CaDiCaL path
        solver.close()
        val solveMs = System.currentTimeMillis() - solveT0
        val totalMs = colouringMs + encodeMs + solveMs
        println("  INSTANCE_TIMEOUT: $path exceeded maxInstanceSolveMs=${cli.maxInstanceSolveMs} (solver=${cli.solver})")
        return (
            listOf(
                family, path, g.n, g.m, cli.config, repeat, cli.seed,
                wl1Cells.size, classesPi, classSizeMax, "%.2f".format(classSizeMean), colouringMs,
                encoding.numVars, encoding.bijectionConstraints, encoding.edgeConflictClauses, 0, cli.anchorK, cli.dmax, encodeMs, "%.1f".format(formulaPeakRssMb),
                "", "", "", "", "", "",
                solveMs, "", "", cli.timeoutMs, 1, "",
                "", "",
                "", "", "", "INSTANCE_TIMEOUT", totalMs, "%.1f".format(peakRssMb()),
            ) + dispatchColumns + estimateColumns + listOf(cli.solver.toString(), gadgetXorClusters)
        ).joinToString(",")
    }
    solver.close()
    val solveMs = System.currentTimeMillis() - solveT0

    check(result.witnessesRejected == 0) { "$path: ${result.witnessesRejected} REJECTED witness(es) -- aborting per BENCHMARK_SPEC.md 2.3" }
    check(result.queriesIssued == result.queriesSat + result.queriesUnsat + result.queriesUnknown) {
        "$path: sat+unsat+unknown (${result.queriesSat}+${result.queriesUnsat}+${result.queriesUnknown}) != queriesIssued (${result.queriesIssued})"
    }
    check(result.orbits.size >= wl1Cells.size) {
        "$path: recovered_orbits (${result.orbits.size}) < classes -- orbits must refine colour classes"
    }

    val status = if (result.queriesUnknown == 0) "CERTIFIED" else "PARTIAL"
    val totalMs = colouringMs + encodeMs + solveMs
    return (
        listOf(
            family, path, g.n, g.m, cli.config, repeat, cli.seed,
            wl1Cells.size, classesPi, classSizeMax, "%.2f".format(classSizeMean), colouringMs,
            encoding.numVars, encoding.bijectionConstraints, encoding.edgeConflictClauses, 0, cli.anchorK, cli.dmax, encodeMs, "%.1f".format(formulaPeakRssMb),
            result.queriesIssued, result.skippedAlreadyConnected, "", result.queriesSat, result.queriesUnsat, result.queriesUnknown,
            solveMs, "", "", cli.timeoutMs, 1, "",
            result.witnessesVerified, result.witnessesRejected,
            result.orbits.size, "", "", status, totalMs, "%.1f".format(peakRssMb()),
        ) + dispatchColumns + estimateColumns + listOf(cli.solver.toString(), gadgetXorClusters)
    ).joinToString(",")
}
