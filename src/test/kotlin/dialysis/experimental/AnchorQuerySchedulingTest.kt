package dialysis.experimental

import dialysis.graph.Graph
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
 * Tests the proposed "ask for a generating set directly" scheduling: for a colour class C, repeatedly
 * pick one unresolved vertex as an ANCHOR, query it against every other still-unresolved member
 * (skipping anything a prior witness's union-find closure already resolved), then drop the anchor's
 * whole now-known orbit and pick a fresh anchor from what's left -- rather than production's current
 * "for u in members: for v in members" scan.
 *
 * Hypothesis check BEFORE running anything: production's own loop, read directly
 * (CadicalOrbitDriver.kt:57-68), already does exactly this by construction -- `members` is scanned in
 * a fixed order, so the first not-yet-resolved vertex in that order plays the anchor role
 * automatically (every one of its pairs gets tried "cold" since nothing has touched it yet, and any
 * LATER vertex already unioned into an earlier anchor's orbit has EVERY one of its own pairs skipped
 * for free via `uf.find(u)==uf.find(v)`). So an explicit anchor scheduler should produce the IDENTICAL
 * query count, not fewer -- unless something about query ORDER within a still-ambiguous class matters
 * beyond that. Measuring directly instead of trusting the read.
 */
class AnchorQuerySchedulingTest {

    private fun anchorSchedule(g: Graph, colorOf: (Int) -> dialysis.content.Content): Triple<Int, Int, Int> {
        val (solver, encoding) = buildCadicalEncoding(g, colorOf)
        val uf = SeparatingUnionFind(g.n)
        var queriesIssued = 0
        var anchorsUsed = 0
        try {
            for (members in encoding.groups) {
                if (members.size <= 1) continue
                var unresolved = members.toMutableList()
                while (unresolved.isNotEmpty()) {
                    val anchor = unresolved.first()
                    anchorsUsed++
                    for (v in unresolved) {
                        if (v == anchor) continue
                        if (uf.find(anchor) == uf.find(v) || uf.separated(anchor, v)) continue
                        queriesIssued++
                        when (val r = queryOrbitMateCadical(solver, encoding, anchor, v, 60_000)) {
                            is SatQueryResult.Sat -> for (w in 0 until g.n) uf.union(w, r.alpha[w])
                            SatQueryResult.Unsat -> uf.markSeparated(anchor, v)
                            SatQueryResult.Unknown -> {}
                        }
                    }
                    val anchorRoot = uf.find(anchor)
                    unresolved = unresolved.filter { uf.find(it) != anchorRoot }.toMutableList()
                }
            }
        } finally {
            solver.close()
        }
        val orbitCount = (0 until g.n).map { uf.find(it) }.toHashSet().size
        return Triple(queriesIssued, anchorsUsed, orbitCount)
    }

    private fun compareOne(path: String) {
        val g = GraphIO.loadDimacs(File(path).toPath())
        val dispatch = dispatchColouring(g, allowSubdivision = false)
        val colorOf = { v: Int -> dispatch.colouring[v] }

        val (prodSolver, prodEncoding) = buildCadicalEncoding(g, colorOf)
        val prodT0 = System.currentTimeMillis()
        val prodResult = try {
            driveToOrbitsCadical(g, prodSolver, prodEncoding, swapPair = null, timeoutMs = 120_000, shortMs = 1_000)
        } finally {
            prodSolver.close()
        }
        val prodMs = System.currentTimeMillis() - prodT0

        val anchorT0 = System.currentTimeMillis()
        val (anchorQueries, anchorsUsed, anchorOrbits) = anchorSchedule(g, colorOf)
        val anchorMs = System.currentTimeMillis() - anchorT0

        println(
            "$path: n=${g.n}\n" +
                "  production (for-u-for-v + UF): queries=${prodResult.queriesIssued} orbits=${prodResult.orbits.size} wall_ms=$prodMs\n" +
                "  explicit anchor schedule:       queries=$anchorQueries anchorsUsed=$anchorsUsed orbits=$anchorOrbits wall_ms=$anchorMs\n" +
                "  SAME query count? ${prodResult.queriesIssued == anchorQueries}, same orbits? ${prodResult.orbits.size == anchorOrbits}",
        )
    }

    @Test
    fun r2() {
        for (path in listOf(
            "graphs/cfi-rigid-r2/cfi-rigid-r2-0216-01-1",
            "graphs/cfi-rigid-r2/cfi-rigid-r2-0864-01-1",
        )) compareOne(path)
    }

    @Test
    fun t2() {
        for (path in listOf(
            "graphs/cfi-rigid-t2/cfi-rigid-t2-0192-01-1",
            "graphs/cfi-rigid-t2/cfi-rigid-t2-0384-01-1",
        )) compareOne(path)
    }

    @Test
    fun d3() {
        for (path in listOf(
            "graphs/cfi-rigid-d3/cfi-rigid-d3-0360-01-1",
            "graphs/cfi-rigid-d3/cfi-rigid-d3-0720-01-1",
        )) compareOne(path)
    }
}
