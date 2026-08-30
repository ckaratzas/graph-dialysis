package dialysis.experimental

import dialysis.content.Content
import dialysis.decomposition.Piece
import dialysis.decomposition.PieceKind
import dialysis.decomposition.peel
import dialysis.fvs.FeedbackVertexSet
import dialysis.refinement.colorRefine1WL
import dialysis.refinement.dispatchColouring
import dialysis.refinement.uniformSeed
import dialysis.sat.SatQueryResult
import dialysis.sat.SeparatingUnionFind
import dialysis.sat.cadical.buildCadicalEncoding
import dialysis.sat.cadical.queryOrbitMateCadical
import dialysis.util.GraphIO
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Second attempt at d3/z3 (the pure-FVS seed left a scale-invariant gap -- 16 identical, entirely
 * orphan-of-the-FVS 9-vertex/degree-6 blocks per D3MergeDiagnosticTest, all OUTSIDE both the FVS and
 * its colour-class closure). The blocks live purely in the "forest part" 1-WL alone can't resolve --
 * so seed with MORE than just the FVS: also gather every ORPHAN (DECOMPOSITION_ORDERING_SPEC.md
 * Part 3's [peel] -- "isolated vertices of G - V(T)", i.e. vertices the recursive BFS-tree
 * decomposition can't attach to any tree at all) from every piece the recursive decomposition
 * produces, and seed with FVS UNION orphans instead of FVS alone. Orphans are exactly the vertices
 * [peel]'s own tree machinery already flags as structurally exceptional -- worth testing whether they
 * overlap with (or explain) the merged blocks before concluding 1-WL's ceiling is unrelated to them.
 */
class FvsPlusOrphansD3Z3Test {

    private fun groundTruthFrom(vararg files: File): Map<String, Int> {
        val map = LinkedHashMap<String, Int>()
        for (file in files) {
            if (!file.exists()) continue
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
        }
        return map
    }

    private fun seededOrbitsAndWl(g: dialysis.graph.Graph, seedVertices: Set<Int>): Pair<Int, Int> {
        val dispatch = dispatchColouring(g, allowSubdivision = false)
        val colorOf = { v: Int -> dispatch.colouring[v] }
        val (solver, encoding) = buildCadicalEncoding(g, colorOf)
        val uf = SeparatingUnionFind(g.n)
        val seeded = HashSet<Int>()
        for (members in encoding.groups) if (members.any { it in seedVertices }) seeded.addAll(members)
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
        val initial = Array<Content>(g.n) { v -> if (v in seeded) Content.Str("orbit-${uf.find(v)}") else Content.Str("un-seeded") }
        val refined = colorRefine1WL(g, initial)
        return seeded.size to refined.cells.size
    }

    private fun run(family: String, path: String, trueOrbits: Int) {
        val g = GraphIO.loadDimacs(File(path).toPath())
        val fvs = FeedbackVertexSet.compute(g)

        val pieces = mutableListOf<Piece>()
        peel(g, IntArray(g.n) { it }, uniformSeed(g.n), pieces)
        val allOrphans = pieces.filter { it.kind == PieceKind.QUOTIENT }.flatMap { it.orphans!!.toList() }.toHashSet()
        val basePieces = pieces.count { it.kind == PieceKind.BASE }
        val quotientPieces = pieces.count { it.kind == PieceKind.QUOTIENT }

        val (fvsSeededSize, fvsWl) = seededOrbitsAndWl(g, fvs)
        val combined = fvs + allOrphans
        val (combinedSeededSize, combinedWl) = seededOrbitsAndWl(g, combined)

        println(
            "$family $path: n=${g.n} |FVS|=${fvs.size} |orphans|=${allOrphans.size} |FVS∪orphans|=${combined.size} " +
                "pieces(base=$basePieces,quotient=$quotientPieces) " +
                "fvsSeeded=$fvsSeededSize fvsWl=$fvsWl combinedSeeded=$combinedSeededSize combinedWl=$combinedWl trueOrbits=$trueOrbits " +
                "fvs/true=${"%.4f".format(fvsWl.toDouble() / trueOrbits)} combined/true=${"%.4f".format(combinedWl.toDouble() / trueOrbits)} " +
                "COMBINED_MATCH=${combinedWl == trueOrbits}",
        )
    }

    @Test
    fun d3() {
        val gt = groundTruthFrom(File("results/gt-d3.csv"), File("results/d3-sat.csv"))
        for (path in listOf(
            "graphs/cfi-rigid-d3/cfi-rigid-d3-0180-01-1",
            "graphs/cfi-rigid-d3/cfi-rigid-d3-0360-01-1",
            "graphs/cfi-rigid-d3/cfi-rigid-d3-0720-01-1",
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
        )) {
            val trueOrbits = gt[path] ?: continue
            run("cfi-rigid-z3", path, trueOrbits)
        }
    }
}
