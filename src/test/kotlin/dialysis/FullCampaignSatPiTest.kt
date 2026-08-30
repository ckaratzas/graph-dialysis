package dialysis

import dialysis.content.Content
import dialysis.graph.Graph
import dialysis.refinement.initialPhase
import dialysis.refinement.uniformSeed
import dialysis.sat.cadical.buildCadicalEncoding
import dialysis.sat.cadical.buildCadicalEncodingSideSwapped
import dialysis.sat.cadical.driveToOrbitsCadicalParallel
import dialysis.sat.computeAllPairsDistances
import dialysis.util.GraphIO
import org.junit.jupiter.api.Test
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter

/**
 * Sweeps every instance in [familiesToProcess] through the initial-phase colouring + CaDiCaL
 * orbit driver, writing one CSV row per instance (resumable: re-running skips instances already
 * present in [resultsFile]).
 *
 * Does not call Traces -- ground truth is computed separately via `scripts/ground_truth.py` and
 * joined in with `scripts/merge_ground_truth.py`, so this sweep only ever reports `CERTIFIED`/
 * `PARTIAL`, never `EXACT`.
 */
class FullCampaignSatPiTest {
    /** Instances whose effective (post-bipartite-subdivision) vertex count exceeds this are skipped. */
    private val maxEffectiveVertices = 1000

    /** Which `graphs/` subdirectories to sweep -- edit this set to run a subset instead of everything. */
    private val familiesToProcess = setOf("cfi-rigid-d3")

    private val resultsFile = File("results/d3-cadical.csv")

    /**
     * Worker count passed to [dialysis.sat.cadical.driveToOrbitsCadicalParallel]: colour classes are partitioned across
     * this many threads (longest-processing-time-first, see CadicalParallelDriver.kt), each with its
     * own CaDiCaL instance. `1` runs everything on a single worker -- functionally identical to no
     * parallelism, just going through the same code path.
     */
    private val cadicalParallelism = 2

    /** Colour classes larger than this fraction of `n` route to a PI_ONLY row (report the colouring,
     *  skip SAT entirely) rather than risk a pathological encoding. */
    private val maxClassSizeRatioGuard = 0.10

    /** Implied-distance-clause parameters (see INVARIANT_FILTERED_SAT_SPEC.md 1.6): `dmax` buckets
     *  distances beyond it into a single ">dmax" value, `anchorK` is how many anchor vertices
     *  (smallest colour classes first) each vertex is compared against. */
    private val dmax = 6
    private val anchorK = 8

    /** Long-pass per-query timeout; the short first pass is fixed at 1s (see
     *  [dialysis.sat.cadical.driveToOrbitsCadicalParallel]'s own doc on two-pass scheduling). */
    private val perQueryTimeoutMs = 10_000L
    private val shortMs = 1_000L

    private val knownPiOnlyFamilies = setOf("ag", "had", "triang", "latin", "paley", "pg", "pp")

    private val familyDirs = (File("graphs").listFiles()?.filter { it.isDirectory } ?: emptyList())
        .map { it.name }.sorted()

    private val headerColumns = listOf(
        "family", "instance", "n", "m", "mode", "workers",
        "initial_phase_classes", "initial_phase_ms",
        "variables", "clauses_bijection", "clauses_edge", "swap_applied",
        "queries_issued", "sat", "unsat", "unknown",
        "solve_ms_total", "recovered_orbits", "true_orbits", "gt_source", "status", "total_ms",
    )
    private val header = headerColumns.joinToString(",")

    /** Column 1 ("instance") is the actual graph path [run] checks each candidate against below --
     *  NOT column 0 ("family"): `it.substringBefore(',')` would grab the family name instead, which
     *  never matches a path, so the skip check below would silently never skip anything. */
    private fun alreadyDone(): Set<String> {
        if (!resultsFile.exists()) return emptySet()
        return resultsFile.readLines().drop(1).mapNotNull { it.split(',').getOrNull(1)?.takeIf(String::isNotBlank) }.toSet()
    }

    private fun listInstances(dir: String): List<String> =
        File("graphs/$dir").listFiles()?.filter { it.isFile }?.map { it.path }?.sorted() ?: emptyList()

    @Test
    fun run() {
        resultsFile.parentFile.mkdirs()
        val done = alreadyDone()
        val resuming = done.isNotEmpty()
        if (resuming) {
            println("Resuming: ${done.size} instance(s) already in ${resultsFile.path}, skipping those.")
            val bytes = resultsFile.readBytes()
            if (bytes.isNotEmpty() && bytes.last() != '\n'.code.toByte()) resultsFile.appendText("\n")
        }
        PrintWriter(FileWriter(resultsFile, resuming)).use { writer ->
            if (!resuming) writer.println(header)
            for (family in familyDirs) {
                if (!familiesToProcess.contains(family)) {
                    continue
                }
                for (path in listInstances(family)) {
                    if (path in done) { println("SKIP (already done): $path"); continue }
                    try {
                        val raw = Utils.ensureBipartite(GraphIO.loadDimacs(File(path).toPath()))
                        if (raw.n > maxEffectiveVertices) {
                            println("SKIP (effective vertices limit): $path (n_effective=${raw.n})")
                            continue
                        }
                        println("STARTING: [$family] $path n=${raw.n} m=${raw.m} workers=$cadicalParallelism")
                        System.out.flush()
                        val row = measureOne(family, path, raw)
                        writer.println(row)
                        writer.flush()
                        println("  -> $row")
                    } catch (e: Throwable) {
                        println("FAILED: $path -- ${e::class.simpleName}: ${e.message}")
                        val errorRow = MutableList(headerColumns.size) { "" }
                        errorRow[headerColumns.indexOf("family")] = family
                        errorRow[headerColumns.indexOf("instance")] = path
                        errorRow[headerColumns.indexOf("mode")] = "ERROR"
                        errorRow[headerColumns.indexOf("status")] = "${e::class.simpleName}: ${e.message}".replace(',', ';').replace('\n', ' ')
                        writer.println(errorRow.joinToString(","))
                        writer.flush()
                    }
                }
            }
        }
        println("Results written to ${resultsFile.path}")
    }

    private fun measureOne(family: String, path: String, g: Graph): String {
        val t0 = System.currentTimeMillis()
        val p = initialPhase(g, uniformSeed(g.n))
        val colouringMs = System.currentTimeMillis() - t0
        val classes = p.cells.size
        val maxClassSize = p.cells.maxOf { it.size }
        val maxClassSizeRatio = maxClassSize.toDouble() / g.n

        val forcePiOnly = family in knownPiOnlyFamilies || maxClassSizeRatio > maxClassSizeRatioGuard
        if (forcePiOnly) {
            val reason = if (family in knownPiOnlyFamilies) "known family" else "guard maxClassSizeRatio=%.3f".format(maxClassSizeRatio)
            println("  PI-ONLY ($reason): classes=$classes")
            return listOf(
                family, path, g.n, g.m, "PI_ONLY", "",
                classes, colouringMs,
                "", "", "", "",
                "", "", "", "",
                "", classes, "", "", "PI_ONLY", colouringMs,
            ).joinToString(",")
        }

        val colorOf: (Int) -> Content = { v -> p.color[v] }
        val dist = computeAllPairsDistances(g)

        // Reference encoding, built only to report variables/clauses/swap_applied -- the actual
        // driven encodings are each worker's own (see driveToOrbitsCadicalParallel's class doc).
        val (refSolver, refEncoding) = buildCadicalEncoding(g, colorOf)
        val refSwap = buildCadicalEncodingSideSwapped(g, colorOf)
        refSolver.close()
        refSwap?.first?.close()

        // solveMs covers both encoding and solving -- each worker builds and drives its own
        // encoding inside this call, so the two aren't separately observable from here.
        val solveT0 = System.currentTimeMillis()
        val result = driveToOrbitsCadicalParallel(
            g, colorOf, cadicalParallelism, perQueryTimeoutMs, shortMs,
            useImpliedDistanceClauses = true, dmax = dmax, anchorK = anchorK, dist = dist,
        )
        val solveMs = System.currentTimeMillis() - solveT0

        val recovered = result.orbits.size
        val totalMs = colouringMs + solveMs
        val status = if (result.unknown == 0) "CERTIFIED" else "PARTIAL"
        return listOf(
            family, path, g.n, g.m, "SAT", cadicalParallelism,
            classes, colouringMs,
            refEncoding.numVars, refEncoding.bijectionConstraints, refEncoding.edgeConflictClauses, (refSwap != null),
            result.queriesIssued, result.sat, result.unsat, result.unknown,
            solveMs, recovered, "", "", status, totalMs,
        ).joinToString(",")
    }
}