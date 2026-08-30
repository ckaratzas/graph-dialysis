package dialysis.experimental

import dialysis.Utils
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
import kotlin.math.ceil

/**
 * Same direct, same-machine, same-instance A/B comparison as [FvsFirstVsFullD3Test], now for t2/s2
 * -- where [FvsSeeded1WLScaleTest] already showed the FVS colour-class closure ("seeded") reaches
 * 100% of n. The seeding step can't shrink the QUERY SET there the way it does for r2/z2 (nothing is
 * excluded), but it's an open, separate question whether it still helps by fully resolving the graph
 * via cheap 1-WL after only a partial round of SAT queries -- i.e. does the seeded-WL-reaches-full-
 * discreteness shortcut (proven rigid, zero further verification, see FVS_SEEDED_1WL_SPEC.md) let the
 * hybrid stop EARLY, before production's own admissible-pair sweep would have finished anyway?
 * Measured, not assumed.
 */
class FvsFirstVsFullT2S2Test {

    private fun compareOne(path: String) {
        val g = (GraphIO.loadDimacs(File(path).toPath()))
        val dispatch = dispatchColouring(g, allowSubdivision = false)
        val colorOf = { v: Int -> dispatch.colouring[v] }

        val (fullSolver, fullEncoding) = buildCadicalEncoding(g, colorOf)
        val fullT0 = System.currentTimeMillis()
        val fullResult = try {
            driveToOrbitsCadical(g, fullSolver, fullEncoding, swapPair = null, timeoutMs = 120_000, shortMs = 1_000)
        } finally {
            fullSolver.close()
        }
        val fullMs = System.currentTimeMillis() - fullT0

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

        val innerT0 = System.currentTimeMillis()
        var innerQueries = 0
        for ((i,cell) in refined.cells.withIndex()) {
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
            "$path: n=${g.n} |FVS|=${fvs.size} |seeded|=${seeded.size} (${"%.0f".format(seeded.size * 100.0 / g.n)}% of n)\n" +
                "  Approach A (production, unrestricted): wall_ms=$fullMs, orbits=${fullResult.orbits.size}, queriesIssued=${fullResult.queriesIssued}\n" +
                "  Approach B (FVS-first hybrid): wall_ms=$hybridTotalMs (outer=${outerMs}ms/${outerQueries}q, inner=${innerMs}ms/${innerQueries}q), orbits=$hybridOrbitCount, totalQueries=${outerQueries + innerQueries}\n" +
                "  SAME orbit count? ${fullResult.orbits.size == hybridOrbitCount}, speedup=${"%.2f".format(fullMs.toDouble() / hybridTotalMs)}x",
        )
    }

    @Test
    fun t2() {
        for (path in listOf(
            "graphs/cfi-rigid-t2/cfi-rigid-t2-0048-01-1",
            "graphs/cfi-rigid-t2/cfi-rigid-t2-0192-01-1",
            "graphs/cfi-rigid-t2/cfi-rigid-t2-0504-03-2",
        )) compareOne(path)
    }

    @Test
    fun s2() {
        for (path in listOf(
            "graphs/cfi-rigid-s2/cfi-rigid-s2-0064-01-1",
            "graphs/cfi-rigid-s2/cfi-rigid-s2-0256-01-1",
            "graphs/cfi-rigid-s2/cfi-rigid-s2-0512-01-1",
        )) compareOne(path)
    }
}
