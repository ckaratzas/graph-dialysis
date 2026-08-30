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

/** Digs into WHY d3 has a constant, scale-invariant wl/true ratio of 0.6364 -- one, small (n=180)
 *  instance, comparing FVS-seeded WL cells directly against real Traces orbits (fast/safe at this
 *  size, unlike the earlier n>=576 slowdowns) to find exactly which true-orbit-distinct vertices
 *  get wrongly merged, and what they have in common structurally. */
class D3MergeDiagnosticTest {

    @Test
    fun diagnose() {
        val path = "graphs/cfi-rigid-d3/cfi-rigid-d3-0180-01-1"
        val g = GraphIO.loadDimacs(File(path).toPath())
        val fvs = FeedbackVertexSet.compute(g)
        println("n=${g.n}, |FVS|=${fvs.size}")

        val dispatch = dispatchColouring(g, allowSubdivision = false)
        val colorOf = { v: Int -> dispatch.colouring[v] }
        val (solver, encoding) = buildCadicalEncoding(g, colorOf)
        val uf = SeparatingUnionFind(g.n)
        val seeded = HashSet<Int>()
        for (members in encoding.groups) if (members.any { it in fvs }) seeded.addAll(members)
        println("|seeded|=${seeded.size}, base colour classes touching FVS: ${encoding.groups.count { m -> m.any { it in fvs } }} / ${encoding.groups.size} total")
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
        val trueOrbitCount = trueOrbit.toHashSet().size
        println("trueOrbits=$trueOrbitCount")

        val wlCellOf = IntArray(g.n)
        for ((cellId, cell) in refined.cells.withIndex()) for (v in cell) wlCellOf[v] = cellId

        // Find WL cells that actually span MULTIPLE true orbits -- these are exactly the
        // over-merges causing the gap.
        var mergedCellCount = 0
        var mergedVertexCount = 0
        for (cell in refined.cells) {
            val trueOrbitsInCell = cell.map { trueOrbit[it] }.toHashSet()
            if (trueOrbitsInCell.size > 1) {
                mergedCellCount++
                mergedVertexCount += cell.size
                val inFvs = cell.count { it in fvs }
                val inSeeded = cell.count { it in seeded }
                println(
                    "MERGED WL cell size=${cell.size} spans ${trueOrbitsInCell.size} true orbits " +
                        "(inFVS=$inFvs, inSeeded=$inSeeded, degrees=${cell.map { g.adj[it].size }.toSortedSet()}) " +
                        "vertices=${cell.sorted().take(20)}",
                )
            }
        }
        println("merged WL cells: $mergedCellCount, vertices inside them: $mergedVertexCount / ${g.n}")
    }
}
