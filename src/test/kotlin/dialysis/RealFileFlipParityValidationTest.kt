package dialysis

import dialysis.cl.TracesJni
import dialysis.gadgetxor.RealFileGadgetReconstruction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Same empirical validation as [MultipedeFlipParityValidationTest], but against REAL cfi-rigid-r2
 * benchmark files, using [RealFileGadgetReconstruction]'s purely-structural gadget recovery
 * instead of ground-truth vertex semantics from a from-scratch generator.
 *
 * cfi-rigid-r2 is, as the family name says, essentially ALWAYS rigid by construction -- a sweep of
 * every file in graphs/cfi-rigid-r2 found a non-trivial Aut(G) for only 2 of ~66 files:
 * cfi-rigid-r2-0068-03-1 (n=68) and cfi-rigid-r2-0648-04-2 (n=648). Those are the only two files
 * this invariant is actually testable against.
 *
 * The naive per-gadget invariant ("this gadget's own 3 ports XOR to 0") is what
 * [MultipedeFlipParityValidationTest] validates, and it holds whenever every automorphism maps
 * each gadget to ITSELF (pure per-edge a/b flips, no gadget permutation) -- true for the
 * intended/generic construction. cfi-rigid-r2-0648-04-2 breaks that assumption: its lone generator
 * maps gadget [53,134,277,572] onto the DIFFERENT gadget [164,219,282,581] wholesale, because that
 * seed's reduced base graph B* happens to have its own residual automorphism swapping those two
 * "gadget slots" (which share 2 of their 3 ports -- (290,477) and (425,270) -- differing only in
 * one port each, (163,141) vs (292,247)). The naive per-gadget check gives 1 (not 0) for BOTH
 * gadgets individually here, but their SUM is 0 -- and algebraically, summing two gadgets that
 * share ports cancels every DOUBLY-referenced port (even multiplicity XORs to 0), leaving only the
 * ports referenced an ODD number of times across the pair. For this twin pair that reduces to:
 * flip(163,141) == flip(292,247) -- the two gadgets' own unique ports must flip together, while the
 * 2 shared ports are free to do whatever they do independently. This generalizes cleanly: group
 * gadgets into clusters by the "shares >= 2 ports" relation (ordinary base-graph adjacency shares
 * exactly 1 port and must NOT be merged), then XOR only the ODD-multiplicity ports within each
 * cluster. A singleton cluster (the overwhelmingly common case) reduces this back to exactly the
 * original single-gadget rule, since all 3 of its ports have multiplicity 1 (odd).
 */
class RealFileFlipParityValidationTest {

    private class Result(val generatorViolations: Int, val generatorChecked: Int, val productViolations: Int, val productChecked: Int)

    /** Reports violation counts rather than asserting -- callers decide what a given count means. */
    private fun measure(path: String): Result {
        println("=== $path ===")
        val recon = RealFileGadgetReconstruction.loadAndReconstruct(path)
        println("Reconstructed ${recon.gadgets.size} gadgets (${recon.numInner} inner, ${recon.numOuter} outer vertices)")

        val partnerOf = HashMap<Int, Int>()
        for (gadget in recon.gadgets) {
            for ((p, q) in gadget.ports) {
                val existingP = partnerOf[p]; val existingQ = partnerOf[q]
                check(existingP == null || existingP == q) { "$path: vertex $p paired with both $existingP and $q in different gadgets -- port pairing is inconsistent" }
                check(existingQ == null || existingQ == p) { "$path: vertex $q paired with both $existingQ and $p in different gadgets -- port pairing is inconsistent" }
                partnerOf[p] = q; partnerOf[q] = p
            }
        }
        println("Port-partner matching is globally consistent across all gadgets (${partnerOf.size / 2} distinct ports)")

        // Arbitrary but fixed per-pair bit: the smaller-id member is "side 0", the other "side 1".
        fun sideOf(v: Int): Int = if (v < partnerOf.getValue(v)) 0 else 1
        fun portKey(p: Int, q: Int) = minOf(p, q)

        // --- Cluster gadgets that share >= 2 ports (twin/degenerate case) via union-find. ---
        val n = recon.gadgets.size
        val parent = IntArray(n) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; parent[x] = r; return r }
        fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) parent[ra] = rb }

        val gadgetsByPort = HashMap<Int, MutableList<Int>>()
        for ((gi, gadget) in recon.gadgets.withIndex()) {
            for ((p, q) in gadget.ports) gadgetsByPort.getOrPut(portKey(p, q)) { mutableListOf() }.add(gi)
        }
        val sharedPortCount = HashMap<Long, Int>()
        fun packPair(i: Int, j: Int): Long { val a = minOf(i, j); val b = maxOf(i, j); return (a.toLong() shl 32) or b.toLong() }
        for (owners in gadgetsByPort.values) {
            for (i in owners.indices) for (j in i + 1 until owners.size) {
                val packed = packPair(owners[i], owners[j])
                sharedPortCount[packed] = (sharedPortCount[packed] ?: 0) + 1
            }
        }
        for ((packed, count) in sharedPortCount) if (count >= 2) union((packed shr 32).toInt(), (packed and 0xFFFFFFFFL).toInt())

        val clusters = (0 until n).groupBy { find(it) }
        val nontrivialClusters = clusters.values.count { it.size > 1 }
        println("Formed ${clusters.size} cluster(s) from ${n} gadgets ($nontrivialClusters non-singleton)")

        // For each cluster, the port multiplicity across its member gadgets; only odd-multiplicity
        // ports actually participate in that cluster's joint invariant.
        val clusterOddPorts: Map<Int, List<Pair<Int, Int>>> = clusters.mapValues { (_, members) ->
            val portMultiplicity = HashMap<Int, Pair<Int, Int>>() // key -> representative (p, q)
            val count = HashMap<Int, Int>()
            for (gi in members) for ((p, q) in recon.gadgets[gi].ports) {
                val key = portKey(p, q)
                count[key] = (count[key] ?: 0) + 1
                portMultiplicity.putIfAbsent(key, p to q)
            }
            count.filter { it.value % 2 == 1 }.keys.map { portMultiplicity.getValue(it) }
        }

        fun clusterFlipXor(alpha: IntArray, clusterRoot: Int): Int =
            clusterOddPorts.getValue(clusterRoot).fold(0) { acc, (p, _) -> acc xor sideOf(p) xor sideOf(alpha[p]) }

        val g = recon.g
        val traces = TracesJni()
        val unitPartition = listOf(IntArray(g.n) { it })
        val generators = traces.generators(g, unitPartition)
        println("Found ${generators.size} generator(s) of Aut(G) (|V|=${g.n})")
        assertTrue(generators.isNotEmpty(), "$path: no non-trivial automorphisms found -- can't validate against a rigid graph")

        var checked = 0
        var violations = 0
        for ((gi, alpha) in generators.withIndex()) {
            for (root in clusters.keys) {
                checked++
                if (clusterFlipXor(alpha, root) != 0) {
                    violations++
                    println("VIOLATION: generator $gi, cluster gadgets=${clusters.getValue(root)}, oddPorts=${clusterOddPorts.getValue(root)}")
                }
            }
        }
        println("Checked $checked (generator, cluster) pairs across ${generators.size} generators, $violations violation(s)")

        fun compose(a: IntArray, b: IntArray) = IntArray(g.n) { a[b[it]] }
        var productChecked = 0
        var productViolations = 0
        for (a in generators) for (b in generators) {
            val prod = compose(a, b)
            for (root in clusters.keys) {
                productChecked++
                if (clusterFlipXor(prod, root) != 0) productViolations++
            }
        }
        println("Checked $productChecked (generator-product, cluster) pairs, $productViolations violation(s)")
        return Result(violations, checked, productViolations, productChecked)
    }

    @Test
    fun checkClusterFlipParityOnSmallestNonRigidR2File() {
        val r = measure("graphs/cfi-rigid-r2/cfi-rigid-r2-0068-03-1")
        assertEquals(0, r.generatorViolations, "cluster flip-parity invariant does not hold on generators -- do not implement this as an XOR clause")
        assertEquals(0, r.productViolations, "cluster flip-parity invariant is not closed under composition")
    }

    /** The file that broke the naive per-gadget-only invariant -- see class doc. The generalized
     *  cluster invariant (union twin gadgets sharing >= 2 ports, XOR only odd-multiplicity ports)
     *  must resolve it to 0 violations, both for the invariant itself AND for closure under
     *  composition, or the generalization is wrong and must not be trusted for the SAT encoder. */
    @Test
    fun checkClusterFlipParityResolvesTheGadgetPermutingCase() {
        val r = measure("graphs/cfi-rigid-r2/cfi-rigid-r2-0648-04-2")
        assertEquals(0, r.generatorViolations, "cluster generalization did not fix the gadget-permuting violation -- do not trust it")
        assertEquals(0, r.productViolations, "cluster flip-parity invariant is not closed under composition")
    }
}
