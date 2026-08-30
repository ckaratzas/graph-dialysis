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
 * Direct, same-machine, same-instance test of the hypothesis: does stabilizing the OUTER vertices
 * first (via FVS -> restricted SAT, proven to exactly cover the outer/hub class -- see
 * D3OuterVertexCheckTest) make the WHOLE orbit computation faster than today's production path
 * (driveToOrbitsCadical, unrestricted admissible pairs across outer AND inner together), by only
 * needing full SAT search on the residual ambiguous INNER cells afterward? Not reasoned about --
 * measured, on the identical instance, in the identical process, back to back.
 *
 * FVS-first path: FVS -> restricted SAT (closure=outer) -> seed 1-WL -> for every NON-singleton WL
 * cell (the still-ambiguous inner blocks), run REAL same-orbit/different-orbit SAT queries within
 * just that cell (same mechanism driveToOrbitsCadical itself uses, just scoped down) to actually
 * PROVE the residual partition, not just guess it from cached ground truth.
 */
class FvsFirstVsFullD3Test {

    @Test
    fun compare() {
        for (path in listOf(
            "graphs/cfi-rigid-d3/cfi-rigid-d3-0180-01-1",
            "graphs/cfi-rigid-d3/cfi-rigid-d3-0360-01-1",
            "graphs/cfi-rigid-d3/cfi-rigid-d3-0720-01-1",
        )) {
            val g = GraphIO.loadDimacs(File(path).toPath())
            val dispatch = dispatchColouring(g, allowSubdivision = false)
            val colorOf = { v: Int -> dispatch.colouring[v] }

            // --- Approach A: today's production path, unrestricted, from scratch. ---
            val (fullSolver, fullEncoding) = buildCadicalEncoding(g, colorOf)
            val fullT0 = System.currentTimeMillis()
            val fullResult = try {
                driveToOrbitsCadical(g, fullSolver, fullEncoding, swapPair = null, timeoutMs = 120_000, shortMs = 1_000)
            } finally {
                fullSolver.close()
            }
            val fullMs = System.currentTimeMillis() - fullT0

            // --- Approach B: FVS-first hybrid. ---
            val hybridT0 = System.currentTimeMillis()
            val fvs = FeedbackVertexSet.compute(g)

            val (solver, encoding) = buildCadicalEncoding(g, colorOf)
            val uf = SeparatingUnionFind(g.n)
            val seeded = HashSet<Int>()
            for (members in encoding.groups) if (members.any { it in fvs }) seeded.addAll(members)
            var outerQueries = 0
            for (members in encoding.groups) {
                val inSeeded = members.filter { it in seeded }
                if (inSeeded.size <= 1) continue
                for (u in inSeeded) for (v in inSeeded) {
                    if (u == v) continue
                    if (uf.find(u) == uf.find(v) || uf.separated(u, v)) continue
                    outerQueries++
                    when (val r = queryOrbitMateCadical(solver, encoding, u, v, 60_000)) {
                        is SatQueryResult.Sat -> for (w in 0 until g.n) uf.union(w, r.alpha[w])
                        SatQueryResult.Unsat -> uf.markSeparated(u, v)
                        SatQueryResult.Unknown -> {}
                    }
                }
            }
            val outerMs = System.currentTimeMillis() - hybridT0

            val initial = Array<Content>(g.n) { v -> if (v in seeded) Content.Str("fvs-orbit-${uf.find(v)}") else Content.Str("non-fvs") }
            val refined = colorRefine1WL(g, initial)

            // Complete the residual: real SAT queries within each non-singleton WL cell, reusing
            // the SAME solver/encoding (learned clauses carry over, same as production would get).
            val innerT0 = System.currentTimeMillis()
            var innerQueries = 0
            for (cell in refined.cells) {
                if (cell.size <= 1) continue
                for (u in cell) for (v in cell) {
                    if (u == v) continue
                    if (uf.find(u) == uf.find(v) || uf.separated(u, v)) continue
                    if (encoding.varOf[u][v] < 0) continue
                    innerQueries++
                    when (val r = queryOrbitMateCadical(solver, encoding, u, v, 60_000)) {
                        is SatQueryResult.Sat -> for (w in 0 until g.n) uf.union(w, r.alpha[w])
                        SatQueryResult.Unsat -> uf.markSeparated(u, v)
                        SatQueryResult.Unknown -> {}
                    }
                }
            }
            val innerMs = System.currentTimeMillis() - innerT0
            solver.close()
            val hybridTotalMs = System.currentTimeMillis() - hybridT0

            val hybridOrbitCount = (0 until g.n).map { uf.find(it) }.toHashSet().size

            println(
                "$path: n=${g.n}\n" +
                    "  Approach A (production, unrestricted): wall_ms=$fullMs, orbits=${fullResult.orbits.size}, queriesIssued=${fullResult.queriesIssued}\n" +
                    "  Approach B (FVS-first hybrid): wall_ms=$hybridTotalMs (outer=${outerMs}ms/${outerQueries}q, inner=${innerMs}ms/${innerQueries}q), orbits=$hybridOrbitCount\n" +
                    "  SAME orbit count? ${fullResult.orbits.size == hybridOrbitCount}, speedup=${"%.2f".format(fullMs.toDouble() / hybridTotalMs)}x",
            )
        }
    }
}
