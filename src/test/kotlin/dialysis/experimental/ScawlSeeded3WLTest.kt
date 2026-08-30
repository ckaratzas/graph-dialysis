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

/** Same pipeline as [ScawlSeeded2WLTest], now against the patched ScaWL 3WL variant
 *  (`/tmp/scawl_check/3WL/scawl_seeded.exe`) -- the natural escalation once 2WL was confirmed
 *  (Part 7.3) to give the theoretically-guaranteed-identical result to 1-WL (2-WL vs 1-WL are
 *  provably equivalent in discriminating power; the WL hierarchy only gains real power at 3-WL). */
@org.junit.jupiter.api.Disabled("3-WL's O(n^3) state space crashed the machine at these sizes -- do not re-enable without explicit authorization")
class ScawlSeeded3WLTest {

    private val scawlBinary = File("/tmp/scawl_check/3WL/scawl_seeded.exe")
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

    private fun writeMatrixMarket(g: dialysis.graph.Graph, out: File) {
        out.printWriter().use { w ->
            w.println("%%MatrixMarket matrix coordinate real general")
            w.println("${g.n} ${g.n} ${g.m * 2}")
            for (u in 0 until g.n) for (v in g.adj[u]) w.println("${u + 1} ${v + 1} 1")
        }
    }

    private fun run(family: String, path: String, trueOrbits: Int) {
        assertTrue(scawlBinary.exists(), "3WL scawl_seeded.exe not built yet at $scawlBinary")

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

        val rootToLabel = HashMap<Int, Int>()
        var nextLabel = 1
        val seedLabel = IntArray(g.n)
        for (v in 0 until g.n) {
            seedLabel[v] = if (v in seeded) rootToLabel.getOrPut(uf.find(v)) { nextLabel++ } else 0
        }

        val base = path.substringAfterLast('/')
        val mtxFile = File(scratch, "$base.mtx")
        val seedFile = File(scratch, "$base.seed")
        val outFile = File(scratch, "$base.3wl_out")
        writeMatrixMarket(g, mtxFile)
        seedFile.printWriter().use { w -> for (v in 0 until g.n) w.println(seedLabel[v]) }
        outFile.delete()

        val t0 = System.currentTimeMillis()
        val proc = ProcessBuilder(scawlBinary.absolutePath, mtxFile.absolutePath, seedFile.absolutePath, outFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        val procOutput = proc.inputStream.bufferedReader().readText()
        val exitCode = proc.waitFor()
        val wallMs = System.currentTimeMillis() - t0
        println("$family $path: scawl_seeded(3WL) exit=$exitCode wall_ms=$wallMs, output tail: ${procOutput.takeLast(300)}")
        assertTrue(outFile.exists(), "$path: scawl_seeded(3WL) did not produce an output file")

        val vertexColour = IntArray(g.n)
        outFile.forEachLine { line ->
            val (v, c) = line.trim().split(" ").map { it.toInt() }
            vertexColour[v] = c
        }
        val threeWlCellCount = vertexColour.toHashSet().size

        println(
            "$family $path: n=${g.n} |FVS|=${fvs.size} |seeded|=${seeded.size} " +
                "3WLcells=$threeWlCellCount trueOrbits=$trueOrbits ratio=${"%.4f".format(threeWlCellCount.toDouble() / trueOrbits)} " +
                "MATCH=${threeWlCellCount == trueOrbits}",
        )
    }

    @Test
    fun d3() {
        val gt = groundTruthFrom(File("results/gt-d3.csv"), File("results/d3-sat.csv"), File("results/d3-sat-3600-plus.csv"))
        for (path in listOf(
            "graphs/cfi-rigid-d3/cfi-rigid-d3-0180-01-1",
            "graphs/cfi-rigid-d3/cfi-rigid-d3-0360-01-1",
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
        )) {
            val trueOrbits = gt[path] ?: continue
            run("cfi-rigid-z3", path, trueOrbits)
        }
    }
}
