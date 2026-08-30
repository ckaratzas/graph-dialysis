package dialysis.experimental

import dialysis.Utils
import dialysis.fvs.FeedbackVertexSet
import dialysis.graph.Graph
import dialysis.util.GraphIO.loadDimacs
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Sanity check for [FeedbackVertexSet] on small graphs with known-by-hand minimum FVS sizes,
 *  before trusting it on any real `cfi-rigid-*` file. */
class FeedbackVertexSetTest {

    private fun graphOf(n: Int, edges: List<Pair<Int, Int>>): Graph {
        val adj = Array(n) { mutableListOf<Int>() }
        for ((u, v) in edges) { adj[u].add(v); adj[v].add(u) }
        return Graph(n, Array(n) { adj[it].toIntArray().also { a -> a.sort() } }, Array(n) { it.toString() })
    }

    private fun isForest(g: Graph, excluded: Set<Int>): Boolean {
        val visited = BooleanArray(g.n)
        for (start in 0 until g.n) {
            if (start in excluded || visited[start]) continue
            visited[start] = true
            val stack = ArrayDeque<Pair<Int, Int>>()
            stack.addLast(start to -1)
            while (stack.isNotEmpty()) {
                val (v, parent) = stack.removeLast()
                for (w in g.adj[v]) {
                    if (w in excluded) continue
                    if (!visited[w]) { visited[w] = true; stack.addLast(w to v) }
                    else if (w != parent) return false
                }
            }
        }
        return true
    }

    @Test
    fun treeNeedsNoFvs() {
        // star graph: 0 connected to 1..5
        val g = graphOf(6, (1..5).map { 0 to it })
        val fvs = FeedbackVertexSet.compute(g)
        assertEquals(emptySet(), fvs)
    }

    @Test
    fun triangleNeedsOne() {
        val g = graphOf(3, listOf(0 to 1, 1 to 2, 0 to 2))
        val fvs = FeedbackVertexSet.compute(g)
        assertEquals(1, fvs.size)
        assertTrue(isForest(g, fvs))
    }

    @Test
    fun fourCycleNeedsOne() {
        val g = graphOf(4, listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0))
        val fvs = FeedbackVertexSet.compute(g)
        assertEquals(1, fvs.size)
        assertTrue(isForest(g, fvs))
    }

    @Test
    fun twoDisjointTrianglesNeedTwo() {
        val g = graphOf(6, listOf(0 to 1, 1 to 2, 0 to 2, 3 to 4, 4 to 5, 3 to 5))
        val fvs = FeedbackVertexSet.compute(g)
        assertTrue(isForest(g, fvs))
        // optimal is exactly 2 (one per triangle); FEEDBACK's 2x bound allows up to 4, but on
        // something this simple/disjoint it should find the optimum.
        assertEquals(2, fvs.size)
    }

    @Test
    fun k4NeedsTwo() {
        val edges = mutableListOf<Pair<Int, Int>>()
        for (i in 0 until 4) for (j in i + 1 until 4) edges.add(i to j)
        val g = graphOf(4, edges)
        val fvs = FeedbackVertexSet.compute(g)
        assertTrue(isForest(g, fvs))
        assertEquals(2, fvs.size)
    }

    @Test
    fun validOnARealCfiRigidFile() {
        val g = (loadDimacs(java.io.File("graphs/cfi-rigid-t2/cfi-rigid-t2-0504-03-2").toPath()))
        val fvs = FeedbackVertexSet.compute(g)
        assertTrue(isForest(g, fvs), "FVS must actually be a feedback vertex set")
        println("cfi-rigid-t2-0504-03-2: n=${g.n}, m=${g.m}, |FVS|=${fvs.size}")
        println("$fvs")
    }

    @Test
    fun validOnAGraphFamilyFile() {
        val g = Utils.ensureBipartite(loadDimacs(java.io.File("graphs/cfi/cfi-50").toPath()))
        val fvs = FeedbackVertexSet.compute(g)
        assertTrue(isForest(g, fvs), "FVS must actually be a feedback vertex set")
        println("cfi-rigid-t2-0504-03-2: n=${g.n}, m=${g.m}, |FVS|=${fvs.size}")
        println("$fvs")
    }
}
