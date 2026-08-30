package dialysis

import dialysis.cl.TracesJni
import dialysis.graph.Graph
import dialysis.refinement.colorRefine1WL
import dialysis.refinement.perQueryAdmissible
import dialysis.refinement.perQueryColouring
import dialysis.refinement.perQueryFilterStats
import dialysis.refinement.uniformSeed
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct, in-process verification of FINAL_MEASUREMENTS_SPEC.md Task 2.1's per-query filter --
 * not just that it's plausible, but that for a REAL automorphism `alpha` with `alpha(u) = v`,
 * `c_v(alpha(i)) = c_u(i)` holds for every `i`, exactly as the soundness argument in
 * [dialysis.refinement.PerQueryFilter]'s own doc claims. Small, hand-built graph -- no SAT solver
 * anywhere in this file, safe to run unconditionally.
 */
class PerQueryFilterSoundnessTest {
    private fun graphOf(n: Int, edges: List<Pair<Int, Int>>): Graph {
        val buckets = Array(n) { mutableListOf<Int>() }
        for ((u, v) in edges) { buckets[u].add(v); buckets[v].add(u) }
        return Graph(n, Array(n) { buckets[it].distinct().sorted().toIntArray() }, Array(n) { it.toString() })
    }

    private fun unitPartition(n: Int) = listOf(IntArray(n) { it })

    @Test
    fun individualizedColouringsCorrespondUnderARealAutomorphism() {
        // "Paw" graph: triangle {0,1,2} + pendant edge {2,3}. |Aut(G)| = 2, single non-trivial
        // generator swaps 0<->1 and fixes 2,3 (same instance as ColouringDispatchSoundnessTest).
        val edges = listOf(0 to 1, 1 to 2, 0 to 2, 2 to 3)
        val g = graphOf(4, edges)

        val traces = TracesJni()
        val generators = traces.generators(g, unitPartition(g.n))
        assertEquals(1, generators.size, "expected exactly one non-trivial generator for the paw graph")
        val alpha = generators[0]
        assertEquals(1, alpha[0], "generator must map 0 -> 1 for this test to exercise u=0, v=1")

        val base = uniformSeed(g.n) // 0 and 1 are orbit-mates, so they share a base colour class
        val refine: (Graph, Array<dialysis.content.Content>) -> dialysis.refinement.StablePartition = { graph, initial -> colorRefine1WL(graph, initial) }

        val cU = perQueryColouring(g, base, 0, refine)
        val cV = perQueryColouring(g, base, 1, refine)

        for (i in 0 until g.n) {
            assertEquals(cU[i], cV[alpha[i]], "c_v(alpha($i)=${alpha[i]}) must equal c_u($i) for the real automorphism alpha=(0 1)")
        }

        // The filter's own admissibility check must accept every (i, alpha(i)) pair -- the exact
        // pairs a real automorphism with alpha(0)=1 actually uses -- and it must NOT admit every
        // (i,j) pair indiscriminately (i.e. individualizing actually discriminates on this graph).
        for (i in 0 until g.n) {
            assertTrue(perQueryAdmissible(cU, cV, i, alpha[i]), "filter must admit the pair a real automorphism uses: ($i, ${alpha[i]})")
        }
        val stats = perQueryFilterStats(g, base, 0, 1, refine)
        assertTrue(stats.perQueryAdmissiblePairs < stats.globalClassSize.toLong() * stats.globalClassSize, "individualizing must strictly shrink the admissible-pair count on this graph")
    }
}
