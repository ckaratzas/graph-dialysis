package dialysis

import dialysis.cl.TracesJni
import dialysis.gadgetxor.RealFileBypassedGadgetReconstruction
import dialysis.gadgetxor.RealFileBypassedGadgetXor
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Empirical validation (same discipline as [RealFileFlipParityValidationTest] for r2) of the
 * flip-parity invariant against REAL Traces-computed automorphisms, using the SAME
 * [RealFileBypassedGadgetXor.prepare] clustering the production encoder wires in -- deliberately
 * not a parallel reimplementation, so this test is validating the actual code path, not a
 * possibly-drifted copy of it.
 *
 * sideOf here is derived from CLIQUE identity (a vertex's role for one specific port is which of
 * that port's 2 disjoint side-cliques it belongs to, keyed by (vertex, port index) since a single
 * vertex has 3 independent roles), not a physical port-vertex pair (there is no such vertex left
 * post-bypass) -- see the reconstruction class's own doc. Like r2, gadgets are clustered (by
 * sharing >= 2 physical ports) and the invariant is checked as the CLUSTER-generalized XOR
 * (odd-multiplicity ports only), which is what r2's real degenerate case (cfi-rigid-r2-0648-04-2)
 * needed.
 */
class RealFileBypassedFlipParityValidationTest {

    private class Result(val generatorViolations: Int, val generatorChecked: Int, val productViolations: Int, val productChecked: Int)

    private fun measure(path: String): Result {
        println("=== $path ===")
        val recon = RealFileBypassedGadgetReconstruction.loadAndReconstruct(path)
        val sided = RealFileBypassedGadgetXor.prepare(recon)
        val distinctCliques = (0 until recon.g.n).flatMap { v -> (0 until 3).mapNotNull { j -> recon.cliqueOf[v to j] } }.toHashSet().size
        println("Reconstructed ${recon.gadgets.size} gadgets from ${recon.g.n} vertices, $distinctCliques distinct cliques")
        val nontrivialClusters = sided.clusters.values.count { it.size > 1 }
        println("Formed ${sided.clusters.size} cluster(s) from ${recon.gadgets.size} gadgets ($nontrivialClusters non-singleton)")

        fun roleOf(v: Int, side0: Int, side1: Int): Int {
            val cliques = (0 until 3).mapNotNull { j -> recon.cliqueOf[v to j] }
            check(side0 in cliques || side1 in cliques) { "vertex $v's cliques $cliques contain neither expected side-0 clique $side0 nor side-1 clique $side1" }
            return if (side0 in cliques) 0 else 1
        }

        fun clusterFlipXor(alpha: IntArray, clusterRoot: Int): Int =
            sided.clusterOddPorts.getValue(clusterRoot).fold(0) { acc, (p, opp, portIdx) ->
                val side0 = recon.cliqueOf.getValue(p to portIdx); val side1 = recon.cliqueOf.getValue(opp to portIdx)
                acc xor (roleOf(p, side0, side1) xor roleOf(alpha[p], side0, side1))
            }

        val g = recon.g
        val traces = TracesJni()
        val unitPartition = listOf(IntArray(g.n) { it })
        val generators = traces.generators(g, unitPartition)
        println("Found ${generators.size} generator(s) of Aut(G) (|V|=${g.n})")
        assertTrue(generators.isNotEmpty(), "$path: no non-trivial automorphisms found -- can't validate against a rigid graph")

        var checked = 0
        var violations = 0
        for ((gi, alpha) in generators.withIndex()) {
            for (root in sided.clusters.keys) {
                checked++
                if (clusterFlipXor(alpha, root) != 0) {
                    violations++
                    println("VIOLATION: generator $gi, cluster gadgets=${sided.clusters.getValue(root)}")
                }
            }
        }
        println("Checked $checked (generator, cluster) pairs across ${generators.size} generators, $violations violation(s)")

        fun compose(a: IntArray, b: IntArray) = IntArray(g.n) { a[b[it]] }
        var productChecked = 0
        var productViolations = 0
        for (a in generators) for (b in generators) {
            val prod = compose(a, b)
            for (root in sided.clusters.keys) {
                productChecked++
                if (clusterFlipXor(prod, root) != 0) productViolations++
            }
        }
        println("Checked $productChecked (generator-product, cluster) pairs, $productViolations violation(s)")
        return Result(violations, checked, productViolations, productChecked)
    }

    private fun assertClean(path: String) {
        val r = measure(path)
        assertEquals(0, r.generatorViolations, "$path: cluster flip-parity invariant does not hold on generators -- do not implement this as an XOR clause")
        assertEquals(0, r.productViolations, "$path: cluster flip-parity invariant is not closed under composition")
    }

    @Test
    fun checkClusterFlipParityOnSeveralNonRigidT2Files() {
        for (path in listOf(
            "graphs/cfi-rigid-t2/cfi-rigid-t2-0016-04-1",
            "graphs/cfi-rigid-t2/cfi-rigid-t2-0020-01-1",
            "graphs/cfi-rigid-t2/cfi-rigid-t2-0020-02-1",
            "graphs/cfi-rigid-t2/cfi-rigid-t2-0040-04-1",
            "graphs/cfi-rigid-t2/cfi-rigid-t2-0044-01-1",
            "graphs/cfi-rigid-t2/cfi-rigid-t2-0116-02-1",
        )) assertClean(path)
    }
}
