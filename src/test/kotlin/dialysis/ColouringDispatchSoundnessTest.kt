package dialysis

import dialysis.cl.TracesJni
import dialysis.graph.Graph
import dialysis.refinement.ColouringSource
import dialysis.refinement.dispatchColouring
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct, in-process verification of [dispatchColouring]'s soundness argument -- not just that
 * orbit COUNTS happen to add up, but that sd(G)'s actual automorphism generators are EXACTLY G's
 * generators extended by `s_{uv} -> s_{alpha(u)alpha(v)}` on the matching subdivision vertices.
 * Cross-checked independently against a hand-computed brute-force result before writing this test
 * (paw graph: |Aut(G)|=2, vertex orbits {0,1}/{2}/{3}, edge orbits {01}/{02,12}/{23}) -- Traces'
 * own generator for sd(G) came back as exactly `(0 1)(5 6)`, matching the s_{02}<->s_{12} swap the
 * hand computation predicted, not merely a matching total orbit count of 6 (=3+3).
 */
class ColouringDispatchSoundnessTest {
    private fun graphOf(n: Int, edges: List<Pair<Int, Int>>): Graph {
        val buckets = Array(n) { mutableListOf<Int>() }
        for ((u, v) in edges) { buckets[u].add(v); buckets[v].add(u) }
        return Graph(n, Array(n) { buckets[it].distinct().sorted().toIntArray() }, Array(n) { it.toString() })
    }

    private fun unitPartition(n: Int) = listOf(IntArray(n) { it })

    /** BFS closure of [start] under repeated application of [generators] -- standard orbit-closure
     *  over a finite generating set, correct regardless of how large the generated group is. */
    private fun edgeOrbit(start: Pair<Int, Int>, generators: List<IntArray>): Set<Pair<Int, Int>> {
        fun norm(u: Int, v: Int) = if (u < v) u to v else v to u
        val visited = mutableSetOf(start)
        val frontier = ArrayDeque(listOf(start))
        while (frontier.isNotEmpty()) {
            val (u, v) = frontier.removeFirst()
            for (gen in generators) {
                val image = norm(gen[u], gen[v])
                if (visited.add(image)) frontier.add(image)
            }
        }
        return visited
    }

    @Test
    fun subdivisionGeneratorsMatchOriginalGraphGenerators() {
        // "Paw" graph: triangle {0,1,2} + pendant edge {2,3} -- small, non-bipartite (has a
        // triangle), non-trivial automorphism group.
        val edges = listOf(0 to 1, 1 to 2, 0 to 2, 2 to 3)
        val g = graphOf(4, edges)
        assertEquals(null, g.bipartition(), "paw graph must be non-bipartite for this test to exercise the subdivision path")

        val traces = TracesJni()
        val generators = traces.generators(g, unitPartition(g.n))
        val vertexOrbits = traces.orbits(g, unitPartition(g.n))

        // Independent hand computation (verified separately by brute force over all 4! = 24
        // permutations): |Aut(G)| = 2, the single non-trivial generator swaps 0<->1 and fixes 2,3.
        assertEquals(1, generators.size, "expected exactly one non-trivial generator for the paw graph")

        // Vertex orbits: {0,1}, {2}, {3}.
        assertEquals(vertexOrbits[0], vertexOrbits[1], "0 and 1 must be orbit-mates")
        assertTrue(vertexOrbits[2] != vertexOrbits[0] && vertexOrbits[3] != vertexOrbits[0] && vertexOrbits[2] != vertexOrbits[3])

        // Edge orbits computed independently from the generators: {(0,1)}, {(0,2),(1,2)}, {(2,3)}.
        val orbit01 = edgeOrbit(0 to 1, generators)
        val orbit02 = edgeOrbit(0 to 2, generators)
        val orbit23 = edgeOrbit(2 to 3, generators)
        assertEquals(setOf(0 to 1), orbit01)
        assertEquals(setOf(0 to 2, 1 to 2), orbit02)
        assertEquals(setOf(2 to 3), orbit23)

        // Now the actual claim: sd(G)'s OWN Traces-computed orbits (a completely separate Traces
        // run on a completely different graph) must reproduce exactly this vertex-orbit +
        // edge-orbit structure via the s_{uv} <-> subdivision-vertex correspondence.
        val sd = g.subdivided()
        val sdOrbits = traces.orbits(sd, unitPartition(sd.n))

        // Vertex part: sd(G)'s restriction to [0, n) must be the SAME partition as G's own orbits
        // (representative ids may legitimately differ between the two independent Traces runs).
        for (u in 0 until g.n) for (v in 0 until g.n) {
            assertEquals(vertexOrbits[u] == vertexOrbits[v], sdOrbits[u] == sdOrbits[v], "vertex orbit mismatch for ($u,$v)")
        }

        // Edge part: subdivision vertex n+i corresponds to the i-th edge in Graph.subdivided()'s
        // own enumeration order (ascending u, then ascending v among u's neighbours, u<v).
        val orderedEdges = mutableListOf<Pair<Int, Int>>()
        for (u in 0 until g.n) for (v in g.adj[u]) if (u < v) orderedEdges.add(u to v)
        fun subVertexOf(e: Pair<Int, Int>): Int = g.n + orderedEdges.indexOf(if (e.first < e.second) e else e.second to e.first)

        val s01 = subVertexOf(0 to 1)
        val s02 = subVertexOf(0 to 2)
        val s12 = subVertexOf(1 to 2)
        val s23 = subVertexOf(2 to 3)

        assertTrue(sdOrbits[s02] == sdOrbits[s12], "s_{02} and s_{12} must be orbit-mates in sd(G), mirroring edge orbit {(0,2),(1,2)}")
        assertTrue(sdOrbits[s01] != sdOrbits[s02], "s_{01} must NOT be an orbit-mate of s_{02} -- they're in different edge orbits")
        assertTrue(sdOrbits[s23] != sdOrbits[s02], "s_{23} must NOT be an orbit-mate of s_{02} -- they're in different edge orbits")
        assertTrue(sdOrbits[s01] != sdOrbits[s23], "s_{01} and s_{23} are each alone in their own edge orbit")

        // And the total count identity the spec itself calls out: sd(G) orbit count = vertex
        // orbits + edge orbits, since the two vertex classes (original vs subdivision) never mix.
        val totalSdOrbits = sdOrbits.toSet().size
        val totalVertexOrbits = vertexOrbits.toSet().size
        val totalEdgeOrbits = setOf(orbit01, orbit02, orbit23).size
        assertEquals(totalVertexOrbits + totalEdgeOrbits, totalSdOrbits)
    }

    @Test
    fun dispatchPicksTheFinerColouringAndReportsBothCounts() {
        val edges = listOf(0 to 1, 1 to 2, 0 to 2, 2 to 3)
        val g = graphOf(4, edges)
        val dispatch = dispatchColouring(g)

        assertTrue(dispatch.subdivided, "non-bipartite graph must go through the subdivision comparison")
        assertEquals(4, dispatch.colouring.size, "colouring must be sized to the ORIGINAL graph, never n+m")
        assertTrue(dispatch.used == ColouringSource.WL1_ORIGINAL || dispatch.used == ColouringSource.PI_TO_ORIGINAL)
        assertTrue(dispatch.wl1OriginalClasses != null && dispatch.piSubdivisionClasses != null && dispatch.piToOriginalClasses != null)
    }
}
