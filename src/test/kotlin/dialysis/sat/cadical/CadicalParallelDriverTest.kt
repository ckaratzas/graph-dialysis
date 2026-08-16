package dialysis.sat.cadical

import dialysis.Utils
import dialysis.refinement.uniformSeed
import dialysis.refinement.initialPhase
import dialysis.sat.computeAllPairsDistances
import dialysis.util.GraphIO
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Parallel driver correctness: for workers in {1, 4}, the recovered partition must be IDENTICAL
 * to [driveToOrbitsCadical]'s (sequential) -- partitioning colour classes across workers must
 * never change the answer (BENCHMARK_SPEC.md Part 3's own correctness requirement), only how the
 * work is scheduled. Also checks the invariant `sat + unsat + unknown == queries_issued` and
 * `witnesses_rejected == 0` (BENCHMARK_SPEC.md 2.3).
 */
class CadicalParallelDriverTest {
    private fun load(path: String) = Utils.ensureBipartite(GraphIO.loadDimacs(File(path).toPath()))

    @Test
    fun matchesSequentialAcrossWorkerCounts() {
        val path = "graphs/cfi-rigid-d3/cfi-rigid-d3-0180-01-1"
        val g = load(path)
        val p = initialPhase(g, uniformSeed(g.n))
        val dist = computeAllPairsDistances(g)
        val timeoutMs = 10_000L
        val shortMs = 1_000L

        val (seqSolver, seqEncoding) = buildCadicalEncoding(g) { v -> p.color[v] }
        val seqSwap = buildCadicalEncodingSideSwapped(g) { v -> p.color[v] }
        addImpliedDistanceClausesCadical(g, seqSolver, seqEncoding, dist, 6, 8)
        if (seqSwap != null) addImpliedDistanceClausesCadical(g, seqSwap.first, seqSwap.second, dist, 6, 8)
        val seq = driveToOrbitsCadical(g, seqSolver, seqEncoding, seqSwap, timeoutMs, shortMs)
        seqSolver.close(); seqSwap?.first?.close()
        val seqPartition = seq.orbits.map { it.toHashSet() }.toHashSet()

        for (workers in listOf(1, 4)) {
            val par = driveToOrbitsCadicalParallel(
                g, { v -> p.color[v] }, workers, timeoutMs, shortMs,
                useImpliedDistanceClauses = true, dmax = 6, anchorK = 8, dist = dist,
            )
            val parPartition = par.orbits.map { it.toHashSet() }.toHashSet()
            assertEquals(seqPartition, parPartition, "workers=$workers: parallel partition differs from sequential")
            assertEquals(0, par.witnessesRejected, "workers=$workers: a rejected witness aborts the run")
            assertEquals(
                par.queriesIssued, par.sat + par.unsat + par.unknown,
                "workers=$workers: sat+unsat+unknown must equal queriesIssued",
            )
            println(
                "workers=$workers issued=${par.queriesIssued} skippedWitness=${par.queriesSkippedWitness} " +
                    "skippedSeparation=${par.queriesSkippedSeparation} unknown=${par.unknown} " +
                    "stragglerRatio=%.2f workerCount=${par.workers.size}".format(par.stragglerRatio)
            )
        }
    }
}