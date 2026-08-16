package dialysis

import dialysis.ahu.ColoredAHU
import dialysis.graph.Graph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ColoredAHUTest {

    private fun tree(vararg edges: Pair<Int, Int>): Graph {
        val n = (edges.flatMap { listOf(it.first, it.second) }.maxOrNull() ?: 0) + 1
        val bucket = Array(n) { mutableListOf<Int>() }
        for ((u, v) in edges) { bucket[u].add(v); bucket[v].add(u) }
        val adj = Array(n) { bucket[it].distinct().sorted().toIntArray() }
        return Graph(n, adj, Array(n) { it.toString() })
    }

    /** A graph consisting of a single isolated vertex at id [id] (padded with unused lower ids). */
    private fun singleVertex(id: Int): Graph {
        val n = id + 1
        return Graph(n, Array(n) { IntArray(0) }, Array(n) { it.toString() })
    }

    // ── Leaf ──────────────────────────────────────────────────────────────────

    @Test
    fun singleVertexColoredDifferentlyGivesDifferentCanonical() {
        val g = singleVertex(0)
        val rA = ColoredAHU.compute(g, mapOf(0 to "A"), root = 0)
        val rB = ColoredAHU.compute(g, mapOf(0 to "B"), root = 0)
        assertEquals("(A)", rA.treeCanonicalForm)
        assertEquals("(B)", rB.treeCanonicalForm)
        assertNotEquals(rA.treeCanonicalForm, rB.treeCanonicalForm)
    }

    // ── Star: uniform colors ──────────────────────────────────────────────────

    @Test
    fun starUniformColors() {
        val g = tree(0 to 1, 0 to 2, 0 to 3)
        val c = mapOf(0 to "X", 1 to "X", 2 to "X", 3 to "X")
        val r = ColoredAHU.compute(g, c, root = 0)
        assertEquals("(X(X)(X)(X))", r.treeCanonicalForm)
        assertEquals(r.subtreeCanonicalString(1), r.subtreeCanonicalString(2))
        assertEquals(r.subtreeCanonicalString(2), r.subtreeCanonicalString(3))
    }

    // ── Two isomorphic trees → same canonical ─────────────────────────────────

    @Test
    fun isomorphicTreesProduceSameCanonical() {
        val g1 = tree(0 to 1, 0 to 2)
        val g2 = tree(5 to 6, 5 to 7)
        val c1 = mapOf(0 to "ROOT", 1 to "LEAF", 2 to "LEAF")
        val c2 = mapOf(5 to "ROOT", 6 to "LEAF", 7 to "LEAF")
        val r1 = ColoredAHU.compute(g1, c1, root = 0)
        val r2 = ColoredAHU.compute(g2, c2, root = 5)
        assertEquals(r1.treeCanonicalForm, r2.treeCanonicalForm)
    }

    // ── Different colors on leaves → different canonical ──────────────────────

    @Test
    fun differentLeafColorsDifferentCanonical() {
        val g1 = tree(0 to 1, 0 to 2)
        val g2 = tree(0 to 1, 0 to 2)
        val c1 = mapOf(0 to "R", 1 to "A", 2 to "A")
        val c2 = mapOf(0 to "R", 1 to "A", 2 to "B")
        val r1 = ColoredAHU.compute(g1, c1, root = 0)
        val r2 = ColoredAHU.compute(g2, c2, root = 0)
        assertNotEquals(r1.treeCanonicalForm, r2.treeCanonicalForm)
    }

    // ── Per-vertex canonical strings ──────────────────────────────────────────

    @Test
    fun subtreeCanonicalStringsAreCorrect() {
        // Path: 0-1-2-3, colors: P,Q,P,Q
        val g = tree(0 to 1, 1 to 2, 2 to 3)
        val colors = mapOf(0 to "P", 1 to "Q", 2 to "P", 3 to "Q")
        val r = ColoredAHU.compute(g, colors, root = 0)
        assertEquals("(Q)", r.subtreeCanonicalString(3))
        assertEquals("(P(Q))", r.subtreeCanonicalString(2))
        assertEquals("(Q(P(Q)))", r.subtreeCanonicalString(1))
        assertEquals("(P(Q(P(Q))))", r.treeCanonicalForm)
    }

    // ── Labels: isomorphic subtrees share the same label ─────────────────────

    @Test
    fun labelOfIsomorphicSubtreesIsIdentical() {
        val g = tree(0 to 1, 1 to 2, 0 to 3, 3 to 4)
        val c = (0..4).associateWith { "N" }
        val r = ColoredAHU.compute(g, c, root = 0)
        assertEquals(r.label(2), r.label(4))   // both leaves, same color
        assertEquals(r.label(1), r.label(3))   // both "path-1 + leaf"
    }

    // ── Leaf string format ────────────────────────────────────────────────────

    @Test
    fun leafCanonicalStringFormat() {
        val g = singleVertex(42)
        val r = ColoredAHU.compute(g, mapOf(42 to "ATOM"), root = 42)
        assertEquals("(ATOM)", r.treeCanonicalForm)
    }

    // ── String color allows any non-reserved string ───────────────────────────

    @Test
    fun colorStringsCanContainNumbers() {
        val g = singleVertex(0)
        val r = ColoredAHU.compute(g, mapOf(0 to "C6H5"), root = 0)
        assertEquals("(C6H5)", r.treeCanonicalForm)
    }

    // ── Uncolored vertices ────────────────────────────────────────────────────

    @Test
    fun uncoloredLeafDiffersFromStringColoredLeaf() {
        val g = singleVertex(0)
        val rColored   = ColoredAHU.compute(g, mapOf(0 to "A"), root = 0)
        val rUncolored = ColoredAHU.compute(g, emptyMap(),      root = 0)
        assertEquals("(A)", rColored.treeCanonicalForm)
        assertEquals("(*)", rUncolored.treeCanonicalForm)
        assertNotEquals(rColored.treeCanonicalForm, rUncolored.treeCanonicalForm)
    }

    @Test
    fun twoUncoloredLeavesAreIsomorphic() {
        val g = tree(0 to 1, 0 to 2)
        val r = ColoredAHU.compute(g, mapOf(0 to "R"), root = 0)  // root colored, leaves uncolored
        assertEquals("(*)", r.subtreeCanonicalString(1))
        assertEquals("(*)", r.subtreeCanonicalString(2))
        assertEquals(r.label(1), r.label(2))
    }

    @Test
    fun mixedColoredAndUncoloredTree() {
        // Star: root(R), two uncolored leaves, one leaf color B
        val g = tree(0 to 1, 0 to 2, 0 to 3)
        val colors = mapOf(0 to "R", 3 to "B")   // vertices 1 and 2 are uncolored
        val r = ColoredAHU.compute(g, colors, root = 0)
        assertEquals(r.subtreeCanonicalString(1), r.subtreeCanonicalString(2))
        assertNotEquals(r.subtreeCanonicalString(1), r.subtreeCanonicalString(3))
        // Children sorted: "(*)" < "(*)" < "(B)" → "(R(*)(*)(B))"
        assertEquals("(R(*)(*)(B))", r.treeCanonicalForm)
    }

    @Test
    fun fullyUncoloredIsomorphicTrees() {
        val g1 = tree(0 to 1, 0 to 2, 0 to 3)
        val g2 = tree(10 to 11, 10 to 12, 10 to 13)
        val r1 = ColoredAHU.compute(g1, emptyMap(), root = 0)
        val r2 = ColoredAHU.compute(g2, emptyMap(), root = 10)
        assertEquals(r1.treeCanonicalForm, r2.treeCanonicalForm)
        assertEquals("(*(*)(*)(*))".length, r1.treeCanonicalForm.length)
    }

    // ── Isomorphic trees with different CSR neighbor orderings ───────────────
    //
    // Regression test for the string-sort correctness (Change 2 rejection).
    // G1: root's CSR neighbors = [leaf(HIGH), subtree(LOW→X)]
    // G2: root's CSR neighbors = [subtree(LOW→X), leaf(HIGH)] (reversed)
    // Without sorting child canonical strings these would yield different forms.
    // Adjacency is built by hand (not via [tree], which sorts) specifically to
    // feed the native encoder both orderings.
    @Test
    fun isomorphicTreesWithDifferentCSROrderGetSameCanonical() {
        val g1 = Graph(
            4,
            arrayOf(
                intArrayOf(1, 2),   // 0=R: HIGH then LOW
                intArrayOf(0),      // 1=HIGH
                intArrayOf(0, 3),   // 2=LOW
                intArrayOf(2),      // 3=X
            ),
            Array(4) { it.toString() },
        )
        val c1 = mapOf(0 to "R", 1 to "HIGH", 2 to "LOW", 3 to "X")

        val g2adj = Array(14) { IntArray(0) }
        g2adj[10] = intArrayOf(12, 11)   // 10=R: LOW then HIGH (reversed)
        g2adj[11] = intArrayOf(10)       // 11=HIGH
        g2adj[12] = intArrayOf(10, 13)   // 12=LOW
        g2adj[13] = intArrayOf(12)       // 13=X
        val g2 = Graph(14, g2adj, Array(14) { it.toString() })
        val c2 = mapOf(10 to "R", 11 to "HIGH", 12 to "LOW", 13 to "X")

        val r1 = ColoredAHU.compute(g1, c1, root = 0)
        val r2 = ColoredAHU.compute(g2, c2, root = 10)

        assertEquals(
            r1.treeCanonicalForm, r2.treeCanonicalForm,
            "Isomorphic trees with different CSR neighbor order must produce the same canonical form"
        )
        // "(HIGH)" < "(LOW(X))" lex → "(R(HIGH)(LOW(X)))"
        assertEquals("(R(HIGH)(LOW(X)))", r1.treeCanonicalForm)
    }
}