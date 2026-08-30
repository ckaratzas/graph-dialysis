package dialysis.experimental

import dialysis.fvs.FeedbackVertexSet
import dialysis.refinement.dispatchColouring
import dialysis.sat.SatQueryResult
import dialysis.sat.SeparatingUnionFind
import dialysis.sat.cadical.buildCadicalEncoding
import dialysis.sat.cadical.queryOrbitMateCadical
import dialysis.util.GraphIO
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Tests the hypothesis: on d3/z3 (where FVS-seeded 1-WL provably under-refines at a scale-invariant
 * ratio -- see FVS_SEEDED_1WL_SPEC.md Part 3 -- does FVS-seeded 2-WL, run via a patched ScaWL
 * (`/tmp/scawl_check/2WL/scawl_seeded.cpp`, built as `scawl_seeded.exe`), close the gap?
 *
 * Pipeline: FVS -> colour-class closure -> restricted SAT orbit queries (same as the 1-WL
 * experiments) -> seed labels -> write graph as Matrix Market + seed labels as a plain int-per-line
 * file -> run the patched ScaWL binary (self-comparison mode, `argc==4`) -> read back
 * `vertex colour` pairs -> compare resulting class count against cached ground truth.
 */
class ScawlSeeded2WLTest {

    private val scawlBinary = File("/tmp/scawl_check/2WL/scawl_seeded.exe")
    private val scratch = File("/tmp/claude-1000/-home-christos-IdeaProjects-graph-dialysis/82a3f245-f7b7-4c30-90a3-c4a5913c1d86/scratchpad")

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

    /** Undirected adjacency as a Matrix Market "coordinate" file -- both (u,v) and (v,u) listed
     *  explicitly (the loader used, initializeFromSparseLinear, does not auto-symmetrize). */
    private fun writeMatrixMarket(g: dialysis.graph.Graph, out: File) {
        out.printWriter().use { w ->
            w.println("%%MatrixMarket matrix coordinate real general")
            w.println("${g.n} ${g.n} ${g.m * 2}")
            for (u in 0 until g.n) for (v in g.adj[u]) w.println("${u + 1} ${v + 1} 1")
        }
    }

    private fun run(family: String, path: String, trueOrbits: Int) {
        assertTrue(scawlBinary.exists(), "scawl_seeded.exe not built yet at $scawlBinary")

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

        // Compact seed labels to small non-negative ints (0 for every non-seeded vertex, since they
        // must all collapse to ONE shared label, matching the 1-WL experiments' "non-fvs" colour).
        val rootToLabel = HashMap<Int, Int>()
        var nextLabel = 1
        val seedLabel = IntArray(g.n)
        for (v in 0 until g.n) {
            seedLabel[v] = if (v in seeded) rootToLabel.getOrPut(uf.find(v)) { nextLabel++ } else 0
        }

        val base = path.substringAfterLast('/')
        val mtxFile = File(scratch, "$base.mtx")
        val seedFile = File(scratch, "$base.seed")
        val outFile = File(scratch, "$base.2wl_out")
        writeMatrixMarket(g, mtxFile)
        seedFile.printWriter().use { w -> for (v in 0 until g.n) w.println(seedLabel[v]) }
        outFile.delete()

        val proc = ProcessBuilder(scawlBinary.absolutePath, mtxFile.absolutePath, seedFile.absolutePath, outFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        val procOutput = proc.inputStream.bufferedReader().readText()
        val exitCode = proc.waitFor()
        println("$family $path: scawl_seeded exit=$exitCode, output tail: ${procOutput.takeLast(500)}")
        assertTrue(outFile.exists(), "$path: scawl_seeded did not produce an output file")

        val vertexColour = IntArray(g.n)
        outFile.forEachLine { line ->
            val (v, c) = line.trim().split(" ").map { it.toInt() }
            vertexColour[v] = c
        }
        val twoWlCellCount = vertexColour.toHashSet().size

        println(
            "$family $path: n=${g.n} |FVS|=${fvs.size} |seeded|=${seeded.size} " +
                "2WLcells=$twoWlCellCount trueOrbits=$trueOrbits ratio=${"%.4f".format(twoWlCellCount.toDouble() / trueOrbits)} " +
                "MATCH=${twoWlCellCount == trueOrbits}",
        )
    }

    @Test
    fun d3() {
        val gt = groundTruthFrom(File("results/gt-d3.csv"), File("results/d3-sat.csv"), File("results/d3-sat-3600-plus.csv"))
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
