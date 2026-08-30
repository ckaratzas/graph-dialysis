package dialysis.experimental

import dialysis.content.Content
import dialysis.fvs.FeedbackVertexSet
import dialysis.refinement.colorRefine1WL
import dialysis.refinement.dispatchColouring
import dialysis.refinement.initialPhase
import dialysis.sat.SatQueryResult
import dialysis.sat.SeparatingUnionFind
import dialysis.sat.cadical.buildCadicalEncoding
import dialysis.sat.cadical.queryOrbitMateCadical
import dialysis.util.GraphIO
import org.junit.jupiter.api.Test
import java.io.File

/**
 * [FvsSeeded1WLScaleTest] found a CONSISTENT, scale-invariant gap on d3/z3: FVS-seeded plain 1-WL
 * always lands at exactly wl_cells/true_orbits = 0.6364 (=28/44=7/11), never closer, regardless of
 * n. This tests whether the project's OWN stronger refinement -- [initialPhase] (Phase 0 = 1-WL,
 * then Phases 1-3: per-vertex decomposition + AHU + remainder colouring -- the same machinery
 * `PI_DIST` config uses in production) closes that gap when seeded the SAME way (FVS colour-class
 * closure -> restricted SAT orbit queries -> orbit labels as the initial colouring), or whether the
 * shortfall is more fundamental than plain 1-WL's own limits. d3/z3 are both bipartite (confirmed
 * directly), so [initialPhase] runs on the graph AS-IS, no subdivision translation needed.
 */
class FvsSeededInitialPhaseD3Z3Test {

    private fun run(family: String, path: String, trueOrbits: Int) {
        val g = GraphIO.loadDimacs(File(path).toPath())
        val fvs = FeedbackVertexSet.compute(g)

        val dispatch = dispatchColouring(g, allowSubdivision = false)
        val colorOf = { v: Int -> dispatch.colouring[v] }
        val (solver, encoding) = buildCadicalEncoding(g, colorOf)
        val uf = SeparatingUnionFind(g.n)
        val seeded = HashSet<Int>()
        for (members in encoding.groups) if (members.any { it in fvs }) seeded.addAll(members)
        try {
            for (members in encoding.groups) {
                val inSeeded = members.filter { it in seeded }
                if (inSeeded.size <= 1) continue
                for (u in inSeeded) for (v in inSeeded) {
                    if (u == v) continue
                    if (uf.find(u) == uf.find(v) || uf.separated(u, v)) continue
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

        val wlT0 = System.currentTimeMillis()
        val wlRefined = colorRefine1WL(g, initial)
        val wlMs = System.currentTimeMillis() - wlT0

        val ipT0 = System.currentTimeMillis()
        val ipRefined = initialPhase(g, initial)
        val ipMs = System.currentTimeMillis() - ipT0

        println(
            "$family $path: n=${g.n} |FVS|=${fvs.size} |seeded|=${seeded.size} " +
                "wlCells=${wlRefined.cells.size} initialPhaseCells=${ipRefined.cells.size} trueOrbits=$trueOrbits " +
                "wl/true=${"%.4f".format(wlRefined.cells.size.toDouble() / trueOrbits)} " +
                "ip/true=${"%.4f".format(ipRefined.cells.size.toDouble() / trueOrbits)} " +
                "wl_ms=$wlMs ip_ms=$ipMs " +
                "IP_MATCH=${ipRefined.cells.size == trueOrbits}",
        )
    }

    private fun groundTruthFrom(file: File): Map<String, Int> {
        val map = LinkedHashMap<String, Int>()
        if (!file.exists()) return map
        val lines = file.readLines()
        val cols = lines[0].split(",")
        val instanceIdx = cols.indexOf("instance")
        val trueOrbitsIdx = cols.indexOf("true_orbits")
        val recoveredIdx = cols.indexOf("recovered_orbits")
        val statusIdx = cols.indexOf("status")
        for (line in lines.drop(1)) {
            val parts = line.split(",")
            val instance = parts.getOrNull(instanceIdx) ?: continue
            if (instance.isBlank() || instance in map) continue
            val trueOrbits = trueOrbitsIdx.takeIf { it >= 0 }?.let { parts.getOrNull(it) }?.takeIf { it.isNotBlank() }?.toIntOrNull()
            if (trueOrbits != null) { map[instance] = trueOrbits; continue }
            if (statusIdx >= 0 && parts.getOrNull(statusIdx) == "CERTIFIED") {
                recoveredIdx.takeIf { it >= 0 }?.let { parts.getOrNull(it) }?.takeIf { it.isNotBlank() }?.toIntOrNull()?.let { map[instance] = it }
            }
        }
        return map
    }

    @Test
    fun d3() {
        val gt = groundTruthFrom(File("results/gt-d3.csv")) + groundTruthFrom(File("results/d3-sat.csv"))
        for (path in listOf(
            "graphs/cfi-rigid-d3/cfi-rigid-d3-0180-01-1",
            "graphs/cfi-rigid-d3/cfi-rigid-d3-0360-01-1",
            "graphs/cfi-rigid-d3/cfi-rigid-d3-0720-01-1",
            "graphs/cfi-rigid-d3/cfi-rigid-d3-1080-01-1",
        )) {
            val trueOrbits = gt[path] ?: continue
            run("cfi-rigid-d3", path, trueOrbits)
        }
    }

    @Test
    fun z3() {
        val gt = groundTruthFrom(File("results/z3-sat.csv"))
        for (path in listOf(
            "graphs/cfi-rigid-z3/cfi-rigid-z3-0180-01-1",
            "graphs/cfi-rigid-z3/cfi-rigid-z3-0360-01-1",
            "graphs/cfi-rigid-z3/cfi-rigid-z3-0720-01-1",
            "graphs/cfi-rigid-z3/cfi-rigid-z3-1080-01-1",
        )) {
            val trueOrbits = gt[path] ?: continue
            run("cfi-rigid-z3", path, trueOrbits)
        }
    }
}
