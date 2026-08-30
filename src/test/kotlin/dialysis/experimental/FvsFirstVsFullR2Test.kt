package dialysis.experimental

import dialysis.content.Content
import dialysis.fvs.FeedbackVertexSet
import dialysis.refinement.colorRefine1WL
import dialysis.refinement.dispatchColouring
import dialysis.sat.SatQueryResult
import dialysis.sat.SeparatingUnionFind
import dialysis.sat.cadical.buildCadicalEncoding
import dialysis.sat.cadical.driveToOrbitsCadical
import dialysis.sat.cadical.queryOrbitMateCadical
import dialysis.util.GraphIO
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Direct, same-machine, same-instance, same-process timing of production CaDiCaL
 * (`driveToOrbitsCadical`, unrestricted) against the FVS-seeded 1-WL hybrid, on `cfi-rigid-r2` --
 * the comparison `FVS_SEEDED_1WL_SPEC.md` Part 4.2 never actually built (unlike Part 4.3/4.4's
 * explicit t2/s2/d3 timing tables): it asserted a "genuine end-to-end win" from 2.3's query-count
 * reduction and 4.1's free-certificate rate alone, without a controlled wall-clock measurement.
 *
 * Cross-machine comparison against the cached `results/r2-sat.csv` timings (captured on a
 * different, unrecorded machine) showed a much noisier picture than "genuine win" implies -- some
 * sizes many times faster, several sizes net SLOWER -- which could be a real effect or could be
 * the cross-machine confound Part 4's own intro explicitly avoids for every OTHER family's
 * comparison. This test removes that confound: both approaches, same JVM run, back to back, no
 * any cached results CSV involved at all. Mirrors [FvsFirstVsFullD3Test]'s structure; r2's hybrid needs no
 * "inner residual" completion phase because r2 already reaches full WL discreteness on almost
 * every instance (Part 4.1), so approach B here is exactly [dialysis.fvs.FvsSeededCampaign]'s own
 * pipeline (FVS -> restricted SAT -> seed 1-WL, nothing further).
 */
class FvsFirstVsFullR2Test {

    @Test
    fun compare() {
        for (path in listOf(
            "graphs/cfi-rigid-r2/cfi-rigid-r2-0288-01-1",
            "graphs/cfi-rigid-r2/cfi-rigid-r2-0576-01-1",
            "graphs/cfi-rigid-r2/cfi-rigid-r2-0936-01-1",
            "graphs/cfi-rigid-r2/cfi-rigid-r2-1296-01-1",
            "graphs/cfi-rigid-r2/cfi-rigid-r2-1656-01-1",
            "graphs/cfi-rigid-r2/cfi-rigid-r2-1944-01-1",
            "graphs/cfi-rigid-r2/cfi-rigid-r2-2160-01-1",
            "graphs/cfi-rigid-r2/cfi-rigid-r2-2448-01-1",
        )) {
            val g = GraphIO.loadDimacs(File(path).toPath())
            val dispatch = dispatchColouring(g, allowSubdivision = false)
            val colorOf = { v: Int -> dispatch.colouring[v] }

            // --- Approach A: today's production path, unrestricted, from scratch. ---
            val (fullSolver, fullEncoding) = buildCadicalEncoding(g, colorOf)
            val fullT0 = System.currentTimeMillis()
            val fullResult = try {
                driveToOrbitsCadical(g, fullSolver, fullEncoding, swapPair = null, timeoutMs = 60_000, shortMs = 1_000)
            } finally {
                fullSolver.close()
            }
            val fullMs = System.currentTimeMillis() - fullT0

            // --- Approach B: FVS-seeded 1-WL hybrid (no residual completion needed for r2). ---
            val hybridT0 = System.currentTimeMillis()
            val fvs = FeedbackVertexSet.compute(g)

            val (solver, encoding) = buildCadicalEncoding(g, colorOf)
            val uf = SeparatingUnionFind(g.n)
            val seeded = HashSet<Int>()
            for (members in encoding.groups) if (members.any { it in fvs }) seeded.addAll(members)
            var queriesIssued = 0
            try {
                for (members in encoding.groups) {
                    val inSeeded = members.filter { it in seeded }
                    if (inSeeded.size <= 1) continue
                    for (u in inSeeded) for (v in inSeeded) {
                        if (u == v) continue
                        if (uf.find(u) == uf.find(v) || uf.separated(u, v)) continue
                        queriesIssued++
                        when (val r = queryOrbitMateCadical(solver, encoding, u, v, 60_000)) {
                            is SatQueryResult.Sat -> for (w in 0 until g.n) uf.union(w, r.alpha[w])
                            SatQueryResult.Unsat -> uf.markSeparated(u, v)
                            SatQueryResult.Unknown -> {}
                        }
                    }
                }
            } finally {
                solver.close()
            }

            val initial = Array<Content>(g.n) { v -> if (v in seeded) Content.Str("fvs-orbit-${uf.find(v)}") else Content.Str("non-fvs") }
            val refined = colorRefine1WL(g, initial)
            val hybridMs = System.currentTimeMillis() - hybridT0

            println(
                "$path: n=${g.n}\n" +
                    "  Approach A (production, unrestricted): wall_ms=$fullMs, orbits=${fullResult.orbits.size}, queriesIssued=${fullResult.queriesIssued}\n" +
                    "  Approach B (FVS-seeded hybrid):        wall_ms=$hybridMs, orbits=${refined.cells.size}, queriesIssued=$queriesIssued\n" +
                    "  SAME orbit count? ${fullResult.orbits.size == refined.cells.size}, speedup=${"%.2f".format(fullMs.toDouble() / hybridMs)}x",
            )
        }
    }
}
