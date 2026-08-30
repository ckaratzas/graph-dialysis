package dialysis

import dialysis.content.Content
import dialysis.decomposition.DecompositionStore
import dialysis.graph.Graph
import dialysis.refinement.StablePartition
import dialysis.refinement.dispatchColouring
import dialysis.refinement.initialPhase
import dialysis.sat.cadical.drivePerQueryOrbits
import dialysis.util.GraphIO
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.io.File

/**
 * FINAL_MEASUREMENTS_SPEC.md Task 2.2 on `ag2-16` specifically -- the instance the spec itself
 * names as having failed under the GLOBAL formula (333s, 4 timeouts), and one where the per-query
 * filter was separately measured to barely shrink the formula (ratio 0.89, only 3 cells narrowed).
 * Run under an OS-level memory cap, as a deliberate escalation step from the family's easier
 * instances rather than a straight jump to its hardest member.
 *
 * CONFIRMED (2026-08-23, under `systemd-run --scope --user -p MemoryMax=3G`): did not finish within
 * a 280s wall clock / 15s per-query timeout -- killed cleanly by `timeout`, memory flat and low the
 * whole run (no OOM, no system impact). Consistent with, not worse than, the spec's own prior data
 * point for this instance. `@Disabled` so a routine `./gradlew test` doesn't silently hang on this --
 * only re-enable for a deliberate, supervised re-attempt (e.g. after a Task 3 seeding improvement),
 * same OS-level memory cap as before.
 */
@Disabled("ag2-16 does not finish within 280s even with the per-query filter + generator closure -- see class doc; only run deliberately, under an OS-level memory cap")
class PerQueryDriverAg216Test {
    private val instancePath = "graphs/ag/ag2-16"
    private val perQueryTimeoutMs = 15_000L
    private val maxEdgeClauses = 2_000_000L // edge-conflict clauses, not variables -- see drivePerQueryOrbits's own doc

    @Test
    fun measure() {
        val g = GraphIO.loadDimacs(File(instancePath).toPath())
        val dispatch = dispatchColouring(g)
        val base = dispatch.colouring
        val byColour = (0 until g.n).groupBy { base[it] }
        val sharedStore = DecompositionStore.build(g)
        val refine: (Graph, Array<Content>) -> StablePartition = { graph, initial -> initialPhase(graph, initial, false, sharedStore) }

        println("instance=$instancePath n=${g.n} m=${g.m} colouring_used=${dispatch.used} classes=${byColour.size} class_sizes=${byColour.values.map { it.size }}")

        var totalOrbits = 0
        val t0 = System.currentTimeMillis()
        for (members in byColour.values) {
            if (members.size < 2) { totalOrbits += 1; continue }
            val result = drivePerQueryOrbits(g, base, members, refine, perQueryTimeoutMs, maxEdgeClauses)
            totalOrbits += result.orbits.size
            val meanVars = if (result.perQueryVars.isEmpty()) 0.0 else result.perQueryVars.average()
            val maxMs = result.perQueryMs.maxOrNull() ?: 0
            println(
                "  class_size=${members.size} queries_issued=${result.queriesIssued} sat=${result.sat} unsat=${result.unsat} unknown=${result.unknown} " +
                    "skipped_witness=${result.queriesSkippedWitness} skipped_separation=${result.queriesSkippedSeparation} skipped_too_large=${result.queriesSkippedTooLarge} " +
                    "mean_vars=%.0f max_query_ms=$maxMs recovered_orbits=${result.orbits.size}".format(meanVars),
            )
        }
        val totalMs = System.currentTimeMillis() - t0
        println("TOTAL recovered_orbits=$totalOrbits total_ms=$totalMs")
    }
}
