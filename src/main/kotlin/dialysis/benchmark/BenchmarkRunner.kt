package dialysis.benchmark

import dialysis.graph.Graph
import dialysis.refinement.uniformSeed
import dialysis.refinement.colorRefine1WL
import dialysis.refinement.initialPhase
import dialysis.sat.cadical.ParallelDriveResult
import dialysis.sat.cadical.addImpliedDistanceClausesCadical
import dialysis.sat.cadical.buildCadicalEncoding
import dialysis.sat.cadical.buildCadicalEncodingSideSwapped
import dialysis.sat.cadical.driveToOrbitsCadicalParallel
import dialysis.sat.computeAllPairsDistances
import dialysis.util.GraphIO
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.lang.management.ManagementFactory

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

data class CliArgs(
    val families: List<String>,          // "all" or explicit comma-separated family names
    val maxVertices: Int,
    val workers: Int,
    val config: Config,
    val repeats: Int,
    val seed: Long,
    val timeoutMs: Long,
    val shortMs: Long,
    val dmax: Int,
    val anchorK: Int,
    val maxClassSizeRatio: Double,
    val out: File,
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
        maxVertices = str("maxVertices", "3000").toInt(),
        workers = str("workers", "1").toInt(),
        config = Config.valueOf(str("config", "PI_DIST")),
        repeats = str("repeats", "1").toInt(),
        seed = str("seed", "42").toLong(),
        timeoutMs = str("timeoutMs", "10000").toLong(),
        shortMs = str("shortMs", "1000").toLong(),
        dmax = str("dmax", "6").toInt(),
        anchorK = str("anchorK", "8").toInt(),
        maxClassSizeRatio = str("maxClassSizeRatio", "0.10").toDouble(),
        out = File(str("out")),
    )
}

private val headerColumns = listOf(
    "family", "instance", "n", "m", "config", "repeat", "seed",
    "classes_1wl", "classes_pi", "class_size_max", "class_size_mean", "colouring_ms",
    "variables", "clauses_bijection", "clauses_edge", "clauses_implied", "anchors_k", "dmax", "encode_ms", "formula_peak_rss_mb",
    "queries_issued", "queries_skipped_witness", "queries_skipped_separation", "sat", "unsat", "unknown",
    "solve_ms_total", "solve_ms_max", "solve_ms_median", "per_query_timeout_ms", "workers", "straggler_ratio",
    "witnesses_verified", "witnesses_rejected",
    "recovered_orbits", "true_orbits", "gt_source", "status", "total_ms", "peak_rss_mb",
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

    PrintWriter(FileWriter(cli.out, resuming)).use { writer ->
        if (!resuming) writer.println(header)
        for (family in familyDirs) {
            for (path in listInstances(family)) {
                if (path in done) { println("SKIP (already done): $path"); continue }
                try {
                    val raw = GraphIO.loadDimacs(File(path).toPath()).ensureBipartite()
                    if (raw.n > cli.maxVertices) {
                        println("SKIP (vertex cap): $path (n_effective=${raw.n})")
                        continue
                    }
                    println("STARTING: [$family] $path n=${raw.n} m=${raw.m} config=${cli.config} workers=${cli.workers}")
                    System.out.flush()
                    for (repeat in 1..cli.repeats) {
                        val row = measureOne(family, path, raw, cli, repeat)
                        writer.println(row)
                        writer.flush()
                        println("  -> $row")
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

private fun measureOne(family: String, path: String, g: Graph, cli: CliArgs, repeat: Int): String {
    val t0 = System.currentTimeMillis()
    val chi = uniformSeed(g.n)
    val piPartition = initialPhase(g, chi)
    val piMs = System.currentTimeMillis() - t0
    val wlT0 = System.currentTimeMillis()
    val wlPartition = colorRefine1WL(g, chi)
    val wlMs = System.currentTimeMillis() - wlT0
    val colouringMs = piMs + wlMs // both computed regardless of config -- needed for the classes_pi >= classes_1wl invariant

    check(piPartition.cells.size >= wlPartition.cells.size) {
        "INVARIANT VIOLATED on $path: classes_pi (${piPartition.cells.size}) < classes_1wl (${wlPartition.cells.size})"
    }

    val useWl = cli.config == Config.WL_DIST
    val useImplied = cli.config != Config.PI
    val colorOf: (Int) -> dialysis.content.Content = if (useWl) { v -> wlPartition.color[v] } else { v -> piPartition.color[v] }
    val activePartition = if (useWl) wlPartition else piPartition
    val classSizes = activePartition.cells.map { it.size }
    val classSizeMax = classSizes.max()
    val classSizeMean = classSizes.average()
    val maxClassSizeRatio = classSizeMax.toDouble() / g.n

    if (maxClassSizeRatio > cli.maxClassSizeRatio) {
        val reason = "guard maxClassSizeRatio=%.3f".format(maxClassSizeRatio)
        println("  PI-ONLY ($reason): classes=${activePartition.cells.size}")
        return listOf(
            family, path, g.n, g.m, cli.config, repeat, cli.seed,
            wlPartition.cells.size, piPartition.cells.size, classSizeMax, "%.2f".format(classSizeMean), colouringMs,
            "", "", "", "", "", "", "", "",
            "", "", "", "", "", "",
            "", "", "", "", cli.workers, "",
            "", "",
            activePartition.cells.size, "", "", "PI_ONLY", colouringMs, "%.1f".format(peakRssMb()),
        ).joinToString(",")
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

    val solveT0 = System.currentTimeMillis()
    val result: ParallelDriveResult = driveToOrbitsCadicalParallel(
        g, colorOf, cli.workers, cli.timeoutMs, cli.shortMs,
        useImpliedDistanceClauses = useImplied, dmax = cli.dmax, anchorK = cli.anchorK, dist = dist,
    )
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
    check(result.orbits.size >= activePartition.cells.size) {
        "$path: recovered_orbits (${result.orbits.size}) < classes (${activePartition.cells.size}) -- orbits must refine colour classes"
    }

    val status = if (result.unknown == 0) "CERTIFIED" else "PARTIAL"
    val totalMs = colouringMs + encodeMs + solveMs
    return listOf(
        family, path, g.n, g.m, cli.config, repeat, cli.seed,
        wlPartition.cells.size, piPartition.cells.size, classSizeMax, "%.2f".format(classSizeMean), colouringMs,
        refEncoding.numVars, refEncoding.bijectionConstraints, refEncoding.edgeConflictClauses, impliedClauses, cli.anchorK, cli.dmax, encodeMs, "%.1f".format(formulaPeakRssMb),
        result.queriesIssued, result.queriesSkippedWitness, result.queriesSkippedSeparation, result.sat, result.unsat, result.unknown,
        solveMs, result.solveMsMax, result.solveMsMedian, cli.timeoutMs, cli.workers, "%.2f".format(result.stragglerRatio),
        result.witnessesVerified, result.witnessesRejected,
        result.orbits.size, "", "", status, totalMs, "%.1f".format(peakRssMb()),
    ).joinToString(",")
}