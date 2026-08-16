package dialysis

import dialysis.graph.Graph
import dialysis.util.randomRelabel
import dialysis.util.relabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RelabelTest {

    /** Bipartite test graph: subdivision of Miyazaki (small, well-known structure). */
    private fun testGraph(): Graph = Utils.subdivision(Fixtures.MIYAZAKI_GRAPH)

    private fun edgeSet(g: Graph): Set<Pair<Int, Int>> {
        val edges = mutableSetOf<Pair<Int, Int>>()
        for (u in 0 until g.n) for (v in g.adj[u]) edges.add(minOf(u, v) to maxOf(u, v))
        return edges
    }

    private fun bipartitionSides(g: Graph): Set<Set<Int>> {
        val (a, b) = g.bipartition()!!
        return setOf(a, b)
    }

    @Test fun `relabel maps edges correctly`() {
        val g = testGraph()
        val pi = (0 until g.n).associateWith { g.n - 1 - it }   // reversal: a valid permutation of [0,n)
        val r = relabel(g, pi)
        val expected = edgeSet(g).map { (u, v) -> pi.getValue(u) to pi.getValue(v) }
            .map { (u, v) -> minOf(u, v) to maxOf(u, v) }.toSet()
        assertEquals(expected, edgeSet(r))
    }

    @Test fun `relabel preserves vertex and edge count`() {
        val g = testGraph()
        val pi = (0 until g.n).associateWith { g.n - 1 - it }
        val r = relabel(g, pi)
        assertEquals(g.n, r.n)
        assertEquals(g.m, r.m)
    }

    @Test fun `relabel preserves degree multiset`() {
        val g = testGraph()
        val pi = (0 until g.n).associateWith { g.n - 1 - it }
        val r = relabel(g, pi)
        val degsBefore = g.adj.map { it.size }.sorted()
        val degsAfter = r.adj.map { it.size }.sorted()
        assertEquals(degsBefore, degsAfter)
    }

    @Test fun `relabel with intra-side permutation preserves bipartition`() {
        val g = testGraph()
        val sides = bipartitionSides(g)
        // swap within each side, identity across sides
        val part0 = sides.first().sorted()
        val part1 = sides.last().sorted()
        val pi = (part0.zip(part0.reversed()) + part1.zip(part1.reversed())).toMap()
        val r = relabel(g, pi)
        assertEquals(sides, bipartitionSides(r))
    }

    @Test fun `randomRelabel preserves bipartition class sets`() {
        val g = testGraph()
        val sides = bipartitionSides(g)
        for (seed in 0L..9L) {
            val r = randomRelabel(g, seed)
            assertEquals(sides, bipartitionSides(r), "bipartition changed at seed=$seed")
        }
    }

    @Test fun `randomRelabel is reproducible with same seed`() {
        val g = testGraph()
        assertEquals(edgeSet(randomRelabel(g, 42L)), edgeSet(randomRelabel(g, 42L)))
    }

    @Test fun `subdivision of general graph is bipartite`() {
        val g = Utils.subdivision(Fixtures.MIYAZAKI_GRAPH)
        assertTrue(g.bipartition() != null)
    }
}