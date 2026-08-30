package dialysis.experimental

import dialysis.util.GraphIO
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * CONTROL for [ScawlSeeded3WLTest]: does PLAIN 3-WL (uniform seed, zero FVS information) already
 * solve d3-0180-01-1 on its own? 3-WL is a genuinely powerful mechanism in general -- if it solves
 * this instance standalone, the earlier "FVS-seeded 3-WL gives 44/44" result proves nothing about
 * the FVS trick specifically; it would just mean 3-WL alone was already enough at this size.
 */
@org.junit.jupiter.api.Disabled("3-WL's O(n^3) state space crashed the machine at these sizes -- do not re-enable without explicit authorization")
class Scawl3WLControlTest {

    private val scawlBinary = File("/tmp/scawl_check/3WL/scawl_seeded.exe")
    private val scratch = File("/tmp/claude-1000/-home-christos-IdeaProjects-graph-dialysis/82a3f245-f7b7-4c30-90a3-c4a5913c1d86/scratchpad")

    private fun writeMatrixMarket(g: dialysis.graph.Graph, out: File) {
        out.printWriter().use { w ->
            w.println("%%MatrixMarket matrix coordinate real general")
            w.println("${g.n} ${g.n} ${g.m * 2}")
            for (u in 0 until g.n) for (v in g.adj[u]) w.println("${u + 1} ${v + 1} 1")
        }
    }

    private fun runPlain(path: String, trueOrbits: Int) {
        assertTrue(scawlBinary.exists())
        val g = GraphIO.loadDimacs(File(path).toPath())
        val base = path.substringAfterLast('/')

        val mtxFile = File(scratch, "control_$base.mtx")
        val seedFile = File(scratch, "control_$base.seed")
        val outFile = File(scratch, "control_$base.3wl_out")
        writeMatrixMarket(g, mtxFile)
        seedFile.printWriter().use { w -> for (v in 0 until g.n) w.println(0) } // UNIFORM, no FVS info at all
        outFile.delete()

        val t0 = System.currentTimeMillis()
        val proc = ProcessBuilder(scawlBinary.absolutePath, mtxFile.absolutePath, seedFile.absolutePath, outFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        val procOutput = proc.inputStream.bufferedReader().readText()
        val exitCode = proc.waitFor()
        val wallMs = System.currentTimeMillis() - t0
        println("$path (PLAIN, no seed): exit=$exitCode wall_ms=$wallMs, output tail: ${procOutput.takeLast(300)}")
        assertTrue(outFile.exists(), "did not produce an output file")

        val vertexColour = IntArray(g.n)
        outFile.forEachLine { line ->
            val (v, c) = line.trim().split(" ").map { it.toInt() }
            vertexColour[v] = c
        }
        val threeWlCellCount = vertexColour.toHashSet().size
        println(
            "$path PLAIN 3WL (no FVS seed): n=${g.n} 3WLcells=$threeWlCellCount trueOrbits=$trueOrbits " +
                "ratio=${"%.4f".format(threeWlCellCount.toDouble() / trueOrbits)} MATCH=${threeWlCellCount == trueOrbits}",
        )
    }

    @Test
    fun plain3WLOnD3_180() {
        runPlain("graphs/cfi-rigid-d3/cfi-rigid-d3-0180-01-1", 44)
    }

    @Test
    fun plain3WLOnD3_360() {
        // trueOrbits=88 from results/gt-d3.csv, same instance as ScawlSeeded3WLTest.d3()
        runPlain("graphs/cfi-rigid-d3/cfi-rigid-d3-0360-01-1", 88)
    }
}
