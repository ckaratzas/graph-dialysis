package dialysis

import dialysis.graph.Graph
import dialysis.util.GraphIO
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphIOTest {

    // K_{2,3}: left={0,1}, right={2,3,4}
    private fun k23(): Graph {
        val edges = listOf(0 to 2, 0 to 3, 0 to 4, 1 to 2, 1 to 3, 1 to 4)
        val bucket = Array(5) { mutableListOf<Int>() }
        for ((u, v) in edges) { bucket[u].add(v); bucket[v].add(u) }
        val adj = Array(5) { bucket[it].sorted().toIntArray() }
        return Graph(5, adj, Array(5) { it.toString() })
    }

    private fun edgeSet(g: Graph): Set<Pair<Int, Int>> {
        val edges = mutableSetOf<Pair<Int, Int>>()
        for (u in 0 until g.n) for (v in g.adj[u]) edges.add(minOf(u, v) to maxOf(u, v))
        return edges
    }

    @Test fun `round-trip edge list preserves vertices and edges`() {
        val original = k23()
        val tmp = Files.createTempFile("k23", ".txt")
        try {
            GraphIO.save(original, tmp)
            val loaded = GraphIO.loadEdgeList(tmp)
            assertEquals(original.n, loaded.n)
            assertEquals(edgeSet(original), edgeSet(loaded))
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test fun `loadDimacs reads ag2-2 with correct vertex and edge count`() {
        val path = Paths.get("graphs/ag/ag2-2")
        if (!Files.exists(path)) return   // skip if not present in CI
        val g = GraphIO.loadDimacs(path)
        assertEquals(10, g.n)
        assertEquals(12, g.m)
    }

    @Test fun `loadDimacs result is bipartite`() {
        val path = Paths.get("graphs/ag/ag2-2")
        if (!Files.exists(path)) return
        val g = GraphIO.loadDimacs(path)
        assertTrue(g.bipartition() != null)
    }

    @Test fun `loadEdgeList parses inline fixture`() {
        val tmp = Files.createTempFile("fixture", ".txt")
        try {
            // P_4: 0-1-2-3 (bipartite, sides {0,2} and {1,3})
            Files.writeString(tmp, "0 1\n1 2\n2 3\n")
            val g = GraphIO.loadEdgeList(tmp)
            assertEquals(4, g.n)
            assertEquals(3, g.m)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}