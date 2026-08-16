package dialysis

import dialysis.graph.Graph
import java.util.TreeSet

object Utils {

    /**
     * Build a [Graph] from a set of edges represented as 2-element lists.
     * Vertex count is inferred from the max endpoint + 1.
     */
    fun fromSet(edges: TreeSet<MutableList<Int>>): Graph {
        var maxVertex = -1
        for (e in edges) maxVertex = maxOf(maxVertex, e[0], e[1])
        val n = maxVertex + 1
        val bucket = Array(n) { mutableListOf<Int>() }
        for (e in edges) { bucket[e[0]].add(e[1]); bucket[e[1]].add(e[0]) }
        val adj = Array(n) { bucket[it].distinct().sorted().toIntArray() }
        return Graph(n, adj, Array(n) { it.toString() })
    }

    /**
     * Parse a string of the form `"[[x1,y1],[x2,y2],…]"` into a sorted set of
     * integer pairs.  Each pair becomes a 2-element [MutableList].
     */
    fun toSet(s: String): TreeSet<MutableList<Int>> {
        val result = TreeSet<MutableList<Int>>(compareBy({ it[0] }, { it[1] }))
        val inner = s.trim().removePrefix("[").removeSuffix("]")
        if (inner.isBlank()) return result
        Regex("""\[(-?\d+),\s*(-?\d+)]""").findAll(inner).forEach { m ->
            result.add(mutableListOf(m.groupValues[1].toInt(), m.groupValues[2].toInt()))
        }
        return result
    }

    /**
     * Returns the subdivision of [g]: each edge {u,v} is replaced by a path
     * u — w — v where w is a fresh vertex.
     */
    fun subdivision(g: Graph): Graph = g.subdivided()

    /**
     * The SAT/CaDiCaL pipeline requires bipartite input — the conversion is the caller's job, not
     * something the encoders do internally. Not every test fixture is bipartite, so tests that run
     * a non-bipartite fixture through that pipeline must convert at the point of use via this helper.
     */
    fun ensureBipartite(g: Graph): Graph = if (g.bipartition() != null) g else g.subdivided()
}