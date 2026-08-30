package dialysis.experimental

import dialysis.cl.TracesJni
import dialysis.content.Content
import dialysis.fvs.FeedbackVertexSet
import dialysis.graph.Graph
import dialysis.refinement.colorRefine1WL
import dialysis.refinement.dispatchColouring
import dialysis.sat.SatQueryResult
import dialysis.sat.SeparatingUnionFind
import dialysis.sat.cadical.buildCadicalEncoding
import dialysis.sat.cadical.queryOrbitMateCadical
import dialysis.util.GraphIO
import org.junit.jupiter.api.Test
import java.io.File

/**
 * EXPERIMENTAL -- tests a conjecture, not a validated technique. The idea (not mine, the user's):
 * `cfi-rigid-*` hardness for 1-WL comes from cyclic gadget structure; a feedback vertex set (FVS)
 * hits every cycle, so G minus an FVS is a forest, and 1-WL/colour-refinement is exactly the AHU
 * tree-isomorphism algorithm on a forest -- provably COMPLETE there. So: if the true automorphism
 * ORBITS of just the (small) FVS are known, seeding 1-WL with them and nothing else might already
 * be enough to refine the WHOLE graph down to its true orbit partition, without ever running an
 * orbit-mate SAT query on any non-FVS vertex. If true, this would cut SAT calls from O(n^2)
 * (colour-admissible pairs across the whole graph) down to O(|FVS|^2) -- and on a real r2 file
 * (n=68) |FVS| was already measured at only 14, an order of magnitude fewer candidate pairs.
 *
 * Pipeline per instance:
 * 1. Compute an approximate FVS (Bafna-Berman-Fujito, [FeedbackVertexSet], <=2x optimal).
 * 2. Compute the FVS's own orbit partition via the EXACT same orbit-mate SAT machinery already
 *    used in production (`buildCadicalEncoding` + `queryOrbitMateCadical`), but restricted to
 *    pairs where BOTH vertices are in the FVS -- never touching a non-FVS vertex.
 * 3. Seed colour refinement: every FVS vertex gets a colour keyed to its SAT-derived orbit id;
 *    every OTHER vertex gets one single uniform colour (deliberately throwing away everything else
 *    -- degree, base colour, anything -- to test the cleanest, strongest form of the hypothesis).
 *    Run plain 1-WL from there.
 * 4. Compare the result against ground truth: Traces' own `orbits()` call (an independent
 *    computation, not reusing anything from steps 1-3, so this is a real check, not circular).
 *
 * This is NOT wired into any production path -- it either produces a theorem-shaped empirical
 * result or it doesn't, and either way the finding gets written up, not assumed.
 */
class FvsSeeded1WLExperimentTest {

    private class Result(
        val path: String, val n: Int, val fvsSize: Int, val seededSize: Int, val fvsQueriesIssued: Int,
        val wlCellCount: Int, val plainWlCellCount: Int, val trueOrbitCount: Int, val partitionsMatch: Boolean,
        val fullAdmissiblePairs: Long,
    )

    private fun run(path: String): Result {
        val g = GraphIO.loadDimacs(File(path).toPath())

        val fvs = FeedbackVertexSet.compute(g)

        // Step 2: FVS orbits via the SAME SAT machinery as production, restricted to a SEEDED SET
        // -- NOT the raw FVS alone. colorOf matches production's own --noSubdivision practice
        // (dispatchColouring with subdivision off) -- this is "as we do now", just with the query
        // set restricted.
        //
        // The raw-FVS version of this experiment (first attempt, see FvsOrbitLabelDebugTest for
        // the diagnosis) is UNSOUND: FVS membership is not itself an automorphism-invariant
        // property -- an approximate FVS has no reason to be a union of complete true orbits, so a
        // real automorphism can map an FVS vertex onto a non-FVS one. Confirmed directly on
        // cfi-rigid-r2-0068-03-1: vertex 21 (in F) and vertex 66 (not in F) are the SAME true
        // orbit, yet the raw approach coloured them differently -- breaking the one property this
        // whole technique depends on (a seed colouring constant on every true orbit can only ever
        // be COARSENED-OR-PRESERVED by 1-WL refinement, never split further; that's what
        // guarantees the seeded refinement can't beat the true partition). The fix: the SEEDED SET
        // is the colour-class CLOSURE of the FVS -- every vertex that shares an `encoding.groups`
        // colour-admissible class with some FVS vertex, not just the FVS itself. Since same true
        // orbit implies same base colour (colorOf is itself automorphism-invariant), this closure
        // is guaranteed to contain the WHOLE of any true orbit that intersects the FVS at all, not
        // just the arbitrary fraction FEEDBACK happened to select -- closing exactly the gap above.
        // Still far smaller than querying every admissible pair in the whole graph whenever the FVS
        // only touches a modest number of colour classes.
        val dispatch = dispatchColouring(g, allowSubdivision = false)
        val colorOf = { v: Int -> dispatch.colouring[v] }
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

        // Step 3: seed 1-WL. Seeded-set vertices keyed by their SAT-derived orbit root; every
        // other vertex gets ONE shared, uninformative colour.
        val initial = Array<Content>(g.n) { v ->
            if (v in seeded) Content.Str("fvs-orbit-${uf.find(v)}") else Content.Str("non-fvs")
        }
        val refined = colorRefine1WL(g, initial)

        // Control: plain 1-WL from a fully uniform seed, NO FVS augmentation at all -- isolates
        // whether the FVS seeding is doing anything, versus the graph just being trivial for 1-WL
        // regardless (most real cfi-rigid-r2 files ARE rigid with no non-trivial automorphism at
        // all, per GADGET_XOR_SPEC.md -- 1-WL alone might already fully discretize those).
        val plainRefined = colorRefine1WL(g, Array(g.n) { Content.Str("u") })

        // Step 4: ground truth, fully independent of steps 1-3.
        val traces = TracesJni()
        val trueOrbitLabel = traces.orbits(g, listOf(IntArray(g.n) { it }))
        val trueOrbitCount = trueOrbitLabel.toHashSet().size

        val wlCellOf = IntArray(g.n)
        for ((cellId, cell) in refined.cells.withIndex()) for (v in cell) wlCellOf[v] = cellId
        var partitionsMatch = refined.cells.size == trueOrbitCount
        if (partitionsMatch) {
            outer@ for (u in 0 until g.n) for (v in 0 until g.n) {
                val sameWl = wlCellOf[u] == wlCellOf[v]
                val sameTrue = trueOrbitLabel[u] == trueOrbitLabel[v]
                if (sameWl != sameTrue) { partitionsMatch = false; break@outer }
            }
        }

        val fullAdmissiblePairs = encoding.groups.sumOf { it.size.toLong() * (it.size - 1) }

        return Result(
            path, g.n, fvs.size, seeded.size, queriesIssued,
            refined.cells.size, plainRefined.cells.size, trueOrbitCount, partitionsMatch, fullAdmissiblePairs,
        )
    }

    private fun runFamily(name: String, paths: List<String>) {
        println("=== $name ===")
        var allMatch = true
        for (path in paths) {
            val r = run(path)
            val vsPlain = if (r.plainWlCellCount == r.trueOrbitCount) "plain-1WL-ALREADY-SUFFICIENT" else "plain-1WL-insufficient-FVS-helped"
            println(
                "  $path: n=${r.n} |FVS|=${r.fvsSize} |seeded|=${r.seededSize} fvsQueries=${r.fvsQueriesIssued} " +
                    "(full-admissible-pairs=${r.fullAdmissiblePairs}) " +
                    "wlCells=${r.wlCellCount} plainWlCells=${r.plainWlCellCount} trueOrbits=${r.trueOrbitCount} " +
                    "MATCH=${r.partitionsMatch} ($vsPlain)",
            )
            if (!r.partitionsMatch) allMatch = false
        }
        println("$name: ALL MATCH = $allMatch")
    }

    @Test
    fun r2() {
        runFamily(
            "cfi-rigid-r2",
            listOf(
                "graphs/cfi-rigid-r2/cfi-rigid-r2-0068-03-1",
                "graphs/cfi-rigid-r2/cfi-rigid-r2-0072-01-1",
                "graphs/cfi-rigid-r2/cfi-rigid-r2-0072-01-2",
                "graphs/cfi-rigid-r2/cfi-rigid-r2-0144-01-1",
                "graphs/cfi-rigid-r2/cfi-rigid-r2-0216-01-1",
            ),
        )
    }

    @Test
    fun t2() {
        runFamily(
            "cfi-rigid-t2",
            listOf(
                "graphs/cfi-rigid-t2/cfi-rigid-t2-0016-04-1",
                "graphs/cfi-rigid-t2/cfi-rigid-t2-0020-01-1",
                "graphs/cfi-rigid-t2/cfi-rigid-t2-0040-04-1",
                "graphs/cfi-rigid-t2/cfi-rigid-t2-0044-01-1",
                "graphs/cfi-rigid-t2/cfi-rigid-t2-0048-01-1",
            ),
        )
    }

    @Test
    fun s2() {
        runFamily(
            "cfi-rigid-s2",
            listOf(
                "graphs/cfi-rigid-s2/cfi-rigid-s2-0064-01-1",
                "graphs/cfi-rigid-s2/cfi-rigid-s2-0064-02-1",
                "graphs/cfi-rigid-s2/cfi-rigid-s2-0128-01-1",
            ),
        )
    }

    @Test
    fun z2() {
        runFamily(
            "cfi-rigid-z2",
            listOf(
                "graphs/cfi-rigid-z2/cfi-rigid-z2-0088-01-1",
                "graphs/cfi-rigid-z2/cfi-rigid-z2-0088-02-1",
            ),
        )
    }

    @Test
    fun z3() {
        runFamily(
            "cfi-rigid-z3",
            listOf(
                "graphs/cfi-rigid-z3/cfi-rigid-z3-0180-01-1",
                "graphs/cfi-rigid-z3/cfi-rigid-z3-0180-02-1",
            ),
        )
    }

    @Test
    fun d3() {
        runFamily(
            "cfi-rigid-d3",
            listOf(
                "graphs/cfi-rigid-d3/cfi-rigid-d3-0180-01-1",
                "graphs/cfi-rigid-d3/cfi-rigid-d3-0180-02-1",
            ),
        )
    }
}
