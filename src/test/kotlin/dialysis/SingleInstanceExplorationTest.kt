package dialysis

import dialysis.benchmark.Config
import dialysis.content.Content
import dialysis.refinement.colorRefine1WL
import dialysis.refinement.initialPhase
import dialysis.refinement.uniformSeed
import dialysis.sat.cadical.addImpliedDistanceClausesCadical
import dialysis.sat.cadical.buildCadicalEncoding
import dialysis.sat.cadical.buildCadicalEncodingSideSwapped
import dialysis.sat.cadical.driveToOrbitsCadicalParallel
import dialysis.sat.computeAllPairsDistances
import dialysis.util.GraphIO
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Runs ONE hardcoded graph instance through the same colouring/encoding/driving pipeline
 * FullCampaignSatPiTest and BenchmarkRunner use, printing a full stat breakdown to stdout -- for
 * playing with [dmax]/[anchorK]/[workers]/[config] on a single instance before committing to a
 * full campaign sweep. Edit the fields below and re-run; nothing here writes a CSV or touches
 * `results/`.
 *
 * Assumes the instance actually reaches the SAT/CaDiCaL path -- unlike FullCampaignSatPiTest/
 * BenchmarkRunner, there is no PI_ONLY routing here, so don't point this at an instance whose
 * colour classes are known to be huge (e.g. a `latin`/`paley` instance) without expecting a large
 * encoding.
 */
@Disabled("edit-and-rerun manual tool, not a regression test -- some instances trigger a clause-count explosion that has crashed the machine")
class SingleInstanceExplorationTest {
    private val instancePath = "graphs/cfi-rigid-t2/cfi-rigid-t2-0960-04-2"

    /** PI_DIST (initial-phase colouring + implied clauses), WL_DIST (1-WL colouring + implied
     *  clauses), or PI (initial-phase, no implied clauses) -- see BENCHMARK_SPEC.md Part 2. */
    private val config = Config.PI_DIST

    private val workers = 1

    /** Implied-distance-clause parameters -- see INVARIANT_FILTERED_SAT_SPEC.md 1.6. Ignored when
     *  [config] is PI. */
    private val dmax = 6
    private val anchorK = 8

    /** Long-pass / short-pass per-query timeouts (two-pass scheduling, BENCHMARK_SPEC.md Part 2). */
    private val perQueryTimeoutMs = 10_000L
    private val shortMs = 1_000L

    @Test
    fun run() {
        val raw = Utils.ensureBipartite(GraphIO.loadDimacs(File(instancePath).toPath()))
        println("instance=$instancePath n=${raw.n} m=${raw.m} config=$config workers=$workers dmax=$dmax anchorK=$anchorK")

        val chi = uniformSeed(raw.n)
        val piT0 = System.currentTimeMillis()
        val piPartition = initialPhase(raw, chi, false)
        val piMs = System.currentTimeMillis() - piT0
        val wlT0 = System.currentTimeMillis()
        val wlPartition = colorRefine1WL(raw, chi)
        val wlMs = System.currentTimeMillis() - wlT0
        val colouringMs = piMs + wlMs

        check(piPartition.cells.size >= wlPartition.cells.size) {
            "classes_pi (${piPartition.cells.size}) < classes_1wl (${wlPartition.cells.size}) -- invariant violated"
        }
        println("classes_1wl=${wlPartition.cells.size} classes_pi=${piPartition.cells.size} (pi_ms=$piMs wl_ms=$wlMs)")

        val useWl = config == Config.WL_DIST
        val useImplied = config != Config.PI
        val activePartition = if (useWl) wlPartition else piPartition
        val colorOf: (Int) -> Content = { v -> activePartition.color[v] }
        val classSizes = activePartition.cells.map { it.size }
        println("active colouring (${if (useWl) "1-WL" else "initial phase"}): classes=${activePartition.cells.size} class_size_max=${classSizes.max()} class_size_mean=%.2f".format(classSizes.average()))

        val dist = if (useImplied) computeAllPairsDistances(raw) else null

        val encodeT0 = System.currentTimeMillis()
        val (refSolver, refEncoding) = buildCadicalEncoding(raw, colorOf)
        val refSwap = buildCadicalEncodingSideSwapped(raw, colorOf)
        var impliedClauses = 0
        if (useImplied) {
            impliedClauses += addImpliedDistanceClausesCadical(raw, refSolver, refEncoding, dist!!, dmax, anchorK).clausesAdded
            if (refSwap != null) impliedClauses += addImpliedDistanceClausesCadical(raw, refSwap.first, refSwap.second, dist, dmax, anchorK).clausesAdded
        }
        val encodeMs = System.currentTimeMillis() - encodeT0
        refSolver.close()
        refSwap?.first?.close()

        println("variables=${refEncoding.numVars} clauses_bijection=${refEncoding.bijectionConstraints} clauses_edge=${refEncoding.edgeConflictClauses} clauses_implied=$impliedClauses swap_applied=${refSwap != null} (encode_ms=$encodeMs)")

        val solveT0 = System.currentTimeMillis()
        val result = driveToOrbitsCadicalParallel(
            raw, colorOf, workers, perQueryTimeoutMs, shortMs,
            useImpliedDistanceClauses = useImplied, dmax = dmax, anchorK = anchorK, dist = dist,
        )
        val solveMs = System.currentTimeMillis() - solveT0

        check(result.witnessesRejected == 0) { "${result.witnessesRejected} REJECTED witness(es) -- encoding bug, not a timing issue" }
        check(result.sat + result.unsat + result.unknown == result.queriesIssued) {
            "sat+unsat+unknown (${result.sat}+${result.unsat}+${result.unknown}) != queriesIssued (${result.queriesIssued})"
        }
        check(result.orbits.size >= activePartition.cells.size) {
            "recovered_orbits (${result.orbits.size}) < classes (${activePartition.cells.size}) -- orbits must refine colour classes"
        }

        println("queries_issued=${result.queriesIssued} skipped_witness=${result.queriesSkippedWitness} skipped_separation=${result.queriesSkippedSeparation}")
        println("sat=${result.sat} unsat=${result.unsat} unknown=${result.unknown} witnesses_verified=${result.witnessesVerified} witnesses_rejected=${result.witnessesRejected}")
        println("solve_ms_total=$solveMs solve_ms_max=${result.solveMsMax} solve_ms_median=${result.solveMsMedian} straggler_ratio=%.2f".format(result.stragglerRatio))
        println("recovered_orbits=${result.orbits.size}")
        for (w in result.workers) {
            println("  worker[${w.workerIdx}] thread=${w.threadName} unitsProcessed=${w.unitsProcessed} estimatedCost=${w.estimatedCost} encodeMs=${w.encodeMs} solveMs=${w.solveMs} wallMs=${w.wallMs}")
        }

        val status = if (result.unknown == 0) "CERTIFIED" else "PARTIAL"
        val totalMs = colouringMs + encodeMs + solveMs
        println("status=$status total_ms=$totalMs")

        println("=== $status orbits (${result.orbits.size}) ===")
        for (orbit in result.orbits.sortedWith(compareByDescending<List<Int>> { it.size }.thenBy { it.min() })) {
            println("  size=${orbit.size}: ${orbit.sorted()}")
        }
    }
}