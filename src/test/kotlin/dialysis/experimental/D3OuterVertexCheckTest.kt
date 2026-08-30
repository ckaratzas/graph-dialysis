package dialysis.experimental

import dialysis.cl.TracesJni
import dialysis.content.Content
import dialysis.fvs.FeedbackVertexSet
import dialysis.refinement.colorRefine1WL
import dialysis.refinement.dispatchColouring
import dialysis.sat.SatQueryResult
import dialysis.sat.SeparatingUnionFind
import dialysis.sat.cadical.buildCadicalEncoding
import dialysis.sat.cadical.queryOrbitMateCadical
import dialysis.util.GraphIO
import org.junit.jupiter.api.Test
import java.io.File

/** Checks whether the FVS/seeded set coincides with the high-degree "outer" (port-hub) vertex
 *  class, and whether the residual merged blocks are exactly the low-degree "inner" (gadget-member)
 *  class -- the Neuen-Schweitzer generalized-group gadget (d3=D3, z3=Z3, per external research)
 *  makes inner-vertex degree = |Gamma| and outer degree = 4*|Gamma|. */
class D3OuterVertexCheckTest {
    @Test
    fun check() {
        val path = "graphs/cfi-rigid-d3/cfi-rigid-d3-0180-01-1"
        val g = GraphIO.loadDimacs(File(path).toPath())
        val degHist = (0 until g.n).groupBy { g.adj[it].size }.mapValues { it.value.size }
        println("degree histogram: $degHist")
        val outer = (0 until g.n).filter { g.adj[it].size == degHist.keys.max() }.toHashSet()
        val inner = (0 until g.n).filter { g.adj[it].size == degHist.keys.min() }.toHashSet()
        println("outer (high-degree) count=${outer.size}, inner (low-degree) count=${inner.size}")

        val fvs = FeedbackVertexSet.compute(g)
        println("|FVS|=${fvs.size}, FVS subset of outer? ${fvs.all { it in outer }}, FVS covers outer fully? ${outer.all { it in fvs }}, FVS∩outer=${fvs.count{it in outer}}, FVS∩inner=${fvs.count{it in inner}}")

        val dispatch = dispatchColouring(g, allowSubdivision = false)
        val colorOf = { v: Int -> dispatch.colouring[v] }
        val (solver, encoding) = buildCadicalEncoding(g, colorOf)
        val uf = SeparatingUnionFind(g.n)
        val seeded = HashSet<Int>()
        for (members in encoding.groups) if (members.any { it in fvs }) seeded.addAll(members)
        println("|seeded|=${seeded.size}, seeded==outer exactly? ${seeded == outer}, seeded∩outer=${seeded.count{it in outer}}, seeded∩inner=${seeded.count{it in inner}}")
        try {
            for (members in encoding.groups) {
                val inSeeded = members.filter { it in seeded }
                if (inSeeded.size <= 1) continue
                for (u in inSeeded) for (v in inSeeded) {
                    if (u == v) continue
                    if (uf.find(u) == uf.find(v) || uf.separated(u, v)) continue
                    when (val r = queryOrbitMateCadical(solver, encoding, u, v, 30_000)) {
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
        println("wlCells=${refined.cells.size}")

        val traces = TracesJni()
        val trueOrbit = traces.orbits(g, listOf(IntArray(g.n) { it }))
        println("trueOrbits=${trueOrbit.toHashSet().size}")

        var mergedVerticesAreAllInner = true
        var mergedCount = 0
        for (cell in refined.cells) {
            val trueOrbitsInCell = cell.map { trueOrbit[it] }.toHashSet()
            if (trueOrbitsInCell.size > 1) {
                mergedCount += cell.size
                if (cell.any { it in outer }) mergedVerticesAreAllInner = false
            }
        }
        println("merged vertices total=$mergedCount, all of them inner (not outer)? $mergedVerticesAreAllInner")

        // Also: is the outer set itself ALREADY fully discrete under true orbits (i.e. does the
        // base graph's own automorphism act trivially/rigidly on outer/hub vertices, or does the
        // ambiguity touch outer vertices too)?
        val outerTrueOrbitCount = outer.map { trueOrbit[it] }.toHashSet().size
        println("outer vertices: ${outer.size} vertices span $outerTrueOrbitCount distinct true orbits (rigid on outer iff equal)")
    }
}
