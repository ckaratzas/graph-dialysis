package dialysis

import dialysis.content.Content
import dialysis.graph.Graph
import dialysis.refinement.StablePartition
import dialysis.refinement.colorRefine1WL
import dialysis.refinement.initialPhase
import dialysis.util.GraphIO
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises initialPhase, the Dialysis-enriched refinement now used everywhere a stable
 * partition is needed. Only isomorphism-invariance is a correctness requirement of this
 * function — its discriminating power is a heuristic property, checked here against real
 * measured behavior rather than asserted blind.
 */
class InitialPhaseTest {

    private fun load(path: String): Graph = GraphIO.loadDimacs(File(path).toPath())
    private fun uniform(g: Graph): Array<Content> = Array(g.n) { Content.Str("X") }

    private fun assertPartitionsAllVertices(g: Graph, p: StablePartition) {
        val union = p.cells.flatMap { it.toList() }
        assertEquals((0 until g.n).toSet(), union.toSet())
        assertEquals(g.n, union.size)
    }

    private fun assertCellsInCanonicalOrder(p: StablePartition) {
        val cellColors = p.cells.map { cell -> p.color[cell[0]] }
        assertEquals(cellColors.sorted(), cellColors)
    }

    @Test
    fun neverCoarserThanPlain1WL() {
        for (path in listOf("graphs/ag/ag2-2", "graphs/cfi-k5/cfi_k5_G0.dimacs")) {
            val g = load(path)
            val p1 = colorRefine1WL(g, uniform(g))
            val pInit = initialPhase(g, uniform(g))
            assertPartitionsAllVertices(g, pInit)
            assertCellsInCanonicalOrder(pInit)
            assertTrue(pInit.cells.size >= p1.cells.size, "$path: initialPhase must not coarsen plain 1-WL")
        }
    }

    // ── Miyazaki is the classic instance built to defeat naive
    // individualization-refinement: plain 1-WL from a uniform start on the
    // unsubdivided graph doesn't even split it into more than one cell (see
    // RefinementTest) — subdividing it for bipartiteness alone already gives
    // 1-WL a second cell (subdivision vertices are degree-2, structurally
    // distinct), but nowhere near what a real refinement should find. The
    // whole point of Phase 1's tree/orphan anchoring is to do much better. ──

    @Test
    fun discriminatesMiyazakiFarBeyondPlain1WL() {
        val g = Utils.ensureBipartite(Fixtures.MIYAZAKI_GRAPH)
        val p1 = colorRefine1WL(g, uniform(g))

        val pInit = initialPhase(g, uniform(g))
        assertPartitionsAllVertices(g, pInit)
        assertCellsInCanonicalOrder(pInit)
        assertTrue(pInit.cells.size > p1.cells.size, "expected initialPhase (${pInit.cells.size} cells) to discriminate beyond plain 1WL (${p1.cells.size} cells)")
    }

    @Test
    fun discretePartitionShortCircuitsAtPhase0() {
        // Every vertex individualized -> already discrete after Phase 0 (plain
        // 1-WL) -> initialPhase must return exactly that, not run Phases 1-3.
        val g = load("graphs/ag/ag2-2")
        val chi = Array<Content>(g.n) { Content.Num(it.toLong()) }
        val p1 = colorRefine1WL(g, chi)
        assertTrue(p1.isDiscrete())
        val pInit = initialPhase(g, chi)
        assertEquals(p1.cells.map { it.toList() }, pInit.cells.map { it.toList() })
    }

    // ── Equivariance: the load-bearing property (Prop. "Invariance of the
    // initial phase") — corresponding vertices of isomorphic inputs must end
    // up in corresponding (equally-colored) classes, regardless of relabeling. ──

    @Test
    fun isInvariantUnderRelabelingOnAg22() {
        val g = load("graphs/ag/ag2-2")
        val perm = IntArray(g.n) { (it + 17) % g.n }
        val relabeled = g.relabeled(perm)
        val p = initialPhase(g, uniform(g))
        val pRelabeled = initialPhase(relabeled, uniform(relabeled))
        assertEquals(p.cells.map { it.size }.sorted(), pRelabeled.cells.map { it.size }.sorted())
        assertEquals(p.pi(), pRelabeled.pi())
    }

    @Test
    fun isInvariantUnderRelabelingOnMiyazaki() {
        // The instance this whole refinement exists to do better on — checked
        // for equivariance specifically, not just plain-1WL fixtures.
        val g = Utils.ensureBipartite(Fixtures.MIYAZAKI_GRAPH)
        val perm = IntArray(g.n) { (it + 7) % g.n }
        val relabeled = g.relabeled(perm)
        val p = initialPhase(g, uniform(g))
        val pRelabeled = initialPhase(relabeled, uniform(relabeled))
        assertEquals(p.cells.map { it.size }.sorted(), pRelabeled.cells.map { it.size }.sorted())
        assertEquals(p.pi(), pRelabeled.pi())
    }

    @Test
    fun isInvariantUnderRelabelingWithNonUniformColoring() {
        val g = load("graphs/ag/ag2-2")
        val chi = Array<Content>(g.n) { if (it == 0) Content.Str("SEED") else Content.Str("X") }
        val perm = IntArray(g.n) { (it + 3) % g.n }
        val relabeled = g.relabeled(perm)
        val chiRelabeled = Array(g.n) { chi[perm.indexOf(it)] }
        val p = initialPhase(g, chi)
        val pRelabeled = initialPhase(relabeled, chiRelabeled)
        assertEquals(p.cells.map { it.size }.sorted(), pRelabeled.cells.map { it.size }.sorted())
        assertEquals(p.pi(), pRelabeled.pi())
    }

    // ── Miyazaki vs. its twist: known non-isomorphic pair with identical
    // low-order invariants — plain 1-WL can't tell them apart either (both
    // collapse to 1 cell); initialPhase should at least agree on CELL-SIZE
    // PROFILE not being a proof either way (correctness is never asserted from
    // this heuristic alone — MatcherTest/CertifyTest cover the real verdict). ──

    @Test
    fun runsOnMiyazakiTwistWithoutError() {
        val g = Utils.ensureBipartite(Fixtures.MIYAZAKI_TWISTED_GRAPH)
        val p = initialPhase(g, uniform(g))
        assertPartitionsAllVertices(g, p)
        assertCellsInCanonicalOrder(p)
    }
}
