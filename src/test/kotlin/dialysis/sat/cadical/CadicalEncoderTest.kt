package dialysis.sat.cadical

import dialysis.Fixtures
import dialysis.Utils
import dialysis.cl.TracesJni
import dialysis.content.Content
import dialysis.graph.Graph
import dialysis.sat.SatQueryResult
import dialysis.sat.verifyAutomorphism
import dialysis.util.GraphIO
import dialysis.util.randomRelabelPermutation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.io.File

/**
 * Correctness guard rail for the CaDiCaL encoder: drives full orbit computation purely from
 * repeated [queryOrbitMateCadical] calls (union-find over discovered orbit-mate pairs) and checks
 * the result against Traces' own ground-truth orbits -- the encoder's OWN correctness, independent
 * of anything else in the pipeline.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CadicalEncoderTest {

    private fun load(path: String): Graph = Utils.ensureBipartite(GraphIO.loadDimacs(File(path).toPath()))

    private fun unionFind(n: Int, pairs: List<Pair<Int, Int>>): IntArray {
        val uf = IntArray(n) { it }
        fun find(x: Int): Int {
            var r = x
            while (uf[r] != r) r = uf[r]
            var c = x
            while (uf[c] != r) { val next = uf[c]; uf[c] = r; c = next }
            return r
        }
        for ((a, b) in pairs) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) uf[ra] = rb
        }
        return IntArray(n) { find(it) }
    }

    private fun driveOrbitsAndCheck(g: Graph, label: String) {
        val cl = TracesJni()
        val trueOrbits = cl.certifyAll(g, listOf(IntArray(g.n) { it }), getcanon = false).orbits
        val trueOrbitCount = trueOrbits.toSet().size

        val uniform: (Int) -> Content = { Content.Str("u") }
        val (solver, encoding) = buildCadicalEncoding(g, uniform)
        try {
            val discoveredPairs = mutableListOf<Pair<Int, Int>>()
            var satCount = 0
            var unsatCount = 0
            var unknownCount = 0
            var rejects = 0
            for (u in 0 until g.n) {
                for (v in u + 1 until g.n) {
                    when (val result = queryOrbitMateCadical(solver, encoding, u, v, 60_000L)) {
                        is SatQueryResult.Sat -> {
                            satCount++
                            if (!verifyAutomorphism(g, result.alpha)) rejects++
                            else if (result.alpha[u] != v) rejects++ // model must actually witness the query
                            else discoveredPairs.add(u to v)
                        }
                        SatQueryResult.Unsat -> unsatCount++
                        SatQueryResult.Unknown -> unknownCount++
                    }
                }
            }
            val orbitOf = unionFind(g.n, discoveredPairs)
            val discoveredOrbitCount = orbitOf.toHashSet().size

            println(
                "$label: n=${g.n}  trueOrbitCount=$trueOrbitCount  discoveredOrbitCount=$discoveredOrbitCount  " +
                    "satQueries=$satCount  unsatQueries=$unsatCount  unknown=$unknownCount  rejects=$rejects"
            )
            assertEquals(0, rejects, "$label: a SAT model must ALWAYS verify in O(m) and witness its own query -- $rejects did not")
            assertEquals(trueOrbitCount, discoveredOrbitCount, "$label: encoder-driven orbits must match Traces ground truth exactly")

            // Cross-check: every discovered orbit-mate pair must be within one TRUE orbit, and vice
            // versa every true-orbit pair must be SAT (soundness AND completeness of the encoder).
            for (u in 0 until g.n) for (v in u + 1 until g.n) {
                val sameTrueOrbit = trueOrbits[u] == trueOrbits[v]
                val sameDiscovered = orbitOf[u] == orbitOf[v]
                assertEquals(sameTrueOrbit, sameDiscovered, "$label: mismatch at u=$u v=$v")
            }
        } finally {
            solver.close()
        }
    }

    @Test
    @Order(1)
    fun frucht_trivialAutomorphismGroup() {
        // Famous property: Aut(Frucht) is trivial -- every non-identity query must be UNSAT.
        driveOrbitsAndCheck(Utils.ensureBipartite(Fixtures.FRUCTH_GRAPH), "Frucht")
    }

    @Test
    @Order(2)
    fun cfiRigidR2_0072_nonTrivialOrbits() {
        driveOrbitsAndCheck(load("graphs/cfi-rigid-r2/cfi-rigid-r2-0072-01-1"), "cfi-rigid-r2-0072-01-1")
    }

    @Test
    @Order(3)
    fun z3_0180_01_2_nonTrivialAutomorphismGroup() {
        driveOrbitsAndCheck(load("graphs/cfi-rigid-z3/cfi-rigid-z3-0180-01-2"), "cfi-rigid-z3-0180-01-2")
    }

    @Test
    @Order(4)
    fun nameFreeOnRandomRelabeling() {
        // Relabel randomly, rebuild, re-drive -- the discovered PARTITION (not vertex ids) must be
        // identical, or something in the encoding is leaking vertex identity.
        val g = load("graphs/cfi-rigid-r2/cfi-rigid-r2-0072-01-1")
        val perm = IntArray(g.n) { randomRelabelPermutation(g, seed = 11L).getValue(it) }
        val relabeled = g.relabeled(perm)

        fun discoveredPartition(graph: Graph): IntArray {
            val uniform: (Int) -> Content = { Content.Str("u") }
            val (solver, encoding) = buildCadicalEncoding(graph, uniform)
            try {
                val pairs = mutableListOf<Pair<Int, Int>>()
                for (u in 0 until graph.n) for (v in u + 1 until graph.n) {
                    val r = queryOrbitMateCadical(solver, encoding, u, v, 60_000L)
                    if (r is SatQueryResult.Sat) pairs.add(u to v)
                }
                return unionFind(graph.n, pairs)
            } finally {
                solver.close()
            }
        }

        val orig = discoveredPartition(g)
        val relab = discoveredPartition(relabeled)
        for (u in 0 until g.n) for (w in u + 1 until g.n) {
            val sameOriginal = orig[u] == orig[w]
            val sameRelabeled = relab[perm[u]] == relab[perm[w]]
            assertTrue(sameOriginal == sameRelabeled, "name-freeness violated at u=$u w=$w")
        }
        println("nameFreeOnRandomRelabeling: PASS (${orig.toHashSet().size} orbits, invariant under relabeling)")
    }
}