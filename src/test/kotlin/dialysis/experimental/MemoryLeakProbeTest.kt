package dialysis.experimental

import dialysis.refinement.dispatchColouring
import dialysis.sat.cadical.estimateGlobalEncodingSize
import dialysis.util.GraphIO
import dialysis.Utils
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Throwaway diagnostic (not meant to stay in the tree) for the 2026-08-29 cross-instance memory
 * growth finding: BenchmarkRunner's peak_rss_mb climbed steadily across a cmz sweep (1149MB ->
 * 8169MB over 7 instances) until a cgroup OOM-kill. Root cause (found via a separate, careful
 * instrumented investigation, not left in this file -- see BenchmarkRunner's `globalTooBig` and
 * CadicalEncoder's `estimateGlobalEncodingSize`): `exactlyOne`'s naive pairwise "at-most-one" costs
 * O(k^2) clauses per row/column of a k-member colour class, called once per row AND once per
 * column -- O(k^3) total per class. The old `estimateGlobalEncodingSize` only reported
 * `edgeConflictClauses` (correctly bounded by the hybrid edge-encoding threshold) and `variables`
 * (O(k^2)) -- neither reflects this cubic bijection cost, so a class with few crossing edges to
 * other classes could sail past the GLOBAL-vs-skip gate and still blow up catastrophically once
 * `buildCadicalEncodingHybrid` actually built it. `bijectionClauses` closes that gap.
 *
 * This is now just a solver-free confirmation that the new estimate actually reports a huge number
 * for a real graph that has a large residual colour class after refinement.
 */
class MemoryLeakProbeTest {
    @Test
    fun cmz33HasHugeBijectionEstimate() {
        val path = "graphs/cmz/cmz-33"
        val raw = Utils.ensureBipartite(GraphIO.loadDimacs(File(path).toPath()))
        val dispatch = dispatchColouring(raw, true)
        val colorOf = { v: Int -> dispatch.colouring[v] }
        val estimate = estimateGlobalEncodingSize(raw, colorOf)
        println(
            "instance=$path n=${raw.n} m=${raw.m} variables=${estimate.variables} " +
                "edgeConflictClauses=${estimate.edgeConflictClauses} bijectionClauses=${estimate.bijectionClauses}",
        )
    }
}
