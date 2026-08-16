package dialysis

import dialysis.content.Content
import dialysis.graph.Graph
import dialysis.refinement.StablePartition
import dialysis.refinement.colorRefine1WL
import dialysis.util.GraphIO
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the Content <-> native-kernel glue (colorRefine1WL) against the
 * real benchmark graphs in graphs/, cross-checked against ground truth
 * already established directly on the native kernels (1WLTest, TracesJniTest)
 * rather than asserted blind. initialPhase (Sec. 3), built on top of
 * colorRefine1WL, has its own tests (InitialPhaseTest).
 */
class RefinementTest {

    private fun load(path: String): Graph = GraphIO.loadDimacs(File(path).toPath())
    private fun uniform(g: Graph): Array<Content> = Array(g.n) { Content.Str("X") }

    private fun assertPartitionsAllVertices(g: Graph, p: StablePartition) {
        val union = p.cells.flatMap { it.toList() }
        assertEquals((0 until g.n).toSet(), union.toSet())
        assertEquals(g.n, union.size)   // no vertex appears in more than one cell
    }

    private fun assertCellsInCanonicalOrder(p: StablePartition) {
        val cellColors = p.cells.map { cell -> p.color[cell[0]] }
        assertEquals(cellColors.sorted(), cellColors)
    }

    // ── StablePartition.pi()/isDiscrete(): no native call needed ──

    @Test
    fun piCountsOnlyNonSingletonCells() {
        val color = Array<Content>(5) { Content.Num(if (it < 3) 0 else it.toLong()) }
        val p = StablePartition(color, listOf(intArrayOf(0, 1, 2), intArrayOf(3), intArrayOf(4)))
        assertEquals(3, p.pi())
        assertTrue(!p.isDiscrete())
    }

    @Test
    fun discretePartitionHasZeroPi() {
        val color = Array<Content>(3) { Content.Num(it.toLong()) }
        val p = StablePartition(color, listOf(intArrayOf(0), intArrayOf(1), intArrayOf(2)))
        assertEquals(0, p.pi())
        assertTrue(p.isDiscrete())
    }

    // ── colorRefine1WL on real graphs, cross-checked against known ground
    // truth from NativeWL1 (1WLTest: ag2-2 -> 2 classes of size 4 and 6). ────

    @Test
    fun ag22UniformColoringMatchesKnownOneWLPartition() {
        val g = load("graphs/ag/ag2-2")
        val p = colorRefine1WL(g, uniform(g))
        assertPartitionsAllVertices(g, p)
        assertCellsInCanonicalOrder(p)
        assertEquals(listOf(4, 6), p.cells.map { it.size }.sorted())
        assertEquals(10, p.pi())   // no singleton cells at all
        assertTrue(!p.isDiscrete())
    }

    @Test
    fun miyazakiUniformColoringIsCompletelyNonDiscriminating() {
        // Miyazaki is the classic instance built to defeat naive
        // individualization-refinement (see Certify.designatedClass); plain
        // 1-WL from a uniform start doesn't even split it into more than one
        // cell.
        val g = Fixtures.MIYAZAKI_GRAPH
        val p = colorRefine1WL(g, uniform(g))
        assertEquals(1, p.cells.size)
        assertEquals(g.n, p.pi())
    }

    @Test
    fun individualizingAVertexRefinesThePartition() {
        val g = load("graphs/ag/ag2-2")
        val colors = Array<Content>(g.n) { if (it == 0) Content.Str("SEED") else Content.Str("X") }
        val p = colorRefine1WL(g, colors)
        assertPartitionsAllVertices(g, p)
        assertTrue(p.cells.any { it.size == 1 && it[0] == 0 })   // vertex 0 now a singleton cell
        assertTrue(p.cells.size > colorRefine1WL(g, uniform(g)).cells.size)
    }

    // ── Equivariance: colorRefine1WL depends only on graph + coloring
    // structure, never on which specific vertex ids/Content instances were used. ──

    @Test
    fun colorRefine1WLIsInvariantUnderRelabeling() {
        val g = load("graphs/cfi-k5/cfi_k5_G0.dimacs")
        val perm = IntArray(g.n) { (it + 17) % g.n }
        val relabeled = g.relabeled(perm)
        val p = colorRefine1WL(g, uniform(g))
        val pRelabeled = colorRefine1WL(relabeled, uniform(relabeled))
        assertEquals(p.cells.map { it.size }.sorted(), pRelabeled.cells.map { it.size }.sorted())
        assertEquals(p.pi(), pRelabeled.pi())
    }
}