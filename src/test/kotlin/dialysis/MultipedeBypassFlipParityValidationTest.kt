package dialysis

import dialysis.cl.TracesJni
import dialysis.graph.Graph
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Ground-truth validation of the flip-parity invariant on a from-scratch OUTER-VERTEX-BYPASSED
 * multipede -- i.e. R*(B(Gn,sigma)), matching the cfi-rigid-s2 family's construction (bypass
 * without base-graph reduction; cfi-rigid-t2 additionally reduces the base graph, see
 * [MultipedeFlipParityValidationTest] / [RealFileFlipParityValidationTest] for that half).
 *
 * Per the paper (Section 4.2, "Bypassing the outer vertices", fetched verbatim since this is too
 * easy to get wrong from memory): a(w)/b(w) are removed entirely, and "inner vertices mi(v) [get
 * connected] to mj(w) if both are connected to either a(v,w) or b(v,w)" -- i.e. each outer vertex's
 * neighbour set becomes a CLIQUE. Consequence used below: within a single gadget, every pair of its
 * 4 even-weight-pattern members agrees on EXACTLY ONE of the 3 port bits (Hamming distance 2 in a
 * length-3 code), so bypass turns every gadget into an internal K4, with additional external edges
 * to whichever OTHER gadgets' members share a given port's side-clique.
 *
 * Ground truth (patternOf) is used directly here -- no structural reconstruction yet, matching how
 * [MultipedeFlipParityValidationTest] validated the un-bypassed case before
 * [RealFileGadgetReconstruction] took on reconstructing it from a real file with no generator
 * access.
 */
class MultipedeBypassFlipParityValidationTest {

    private fun cycleWithDiagonals(n: Int): Pair<Int, List<Pair<Int, Int>>> {
        val vn = 2 * n
        val edges = mutableListOf<Pair<Int, Int>>()
        for (i in 0 until vn) edges.add(i to (i + 1) % vn)
        for (i in 0 until n) edges.add(i to (i + n))
        return vn to edges
    }

    private class BaseGraph(val numV: Int, val numW: Int, val neighborsOfV: Array<IntArray>)

    private fun buildB(vn: Int, edges: List<Pair<Int, Int>>, sigma: IntArray): BaseGraph {
        val m = edges.size
        val numV = 2 * vn
        val incidentEdgesOf = Array(vn) { mutableListOf<Int>() }
        for ((idx, e) in edges.withIndex()) {
            incidentEdgesOf[e.first].add(idx)
            incidentEdgesOf[e.second].add(idx)
        }
        val sigmaIncidentEdgesOf = Array(vn) { mutableListOf<Int>() }
        for (e in 0 until m) {
            val (a, b) = edges[sigma[e]]
            sigmaIncidentEdgesOf[a].add(e)
            sigmaIncidentEdgesOf[b].add(e)
        }
        val neighborsOfV = Array(numV) { IntArray(0) }
        for (v in 0 until vn) {
            neighborsOfV[2 * v] = incidentEdgesOf[v].sorted().toIntArray()
            neighborsOfV[2 * v + 1] = sigmaIncidentEdgesOf[v].sorted().toIntArray()
        }
        for (row in neighborsOfV) check(row.size == 3)
        return BaseGraph(numV, m, neighborsOfV)
    }

    private val evenWeightPatterns = listOf(
        intArrayOf(0, 0, 0), intArrayOf(0, 1, 1), intArrayOf(1, 0, 1), intArrayOf(1, 1, 0),
    )

    /** [patternOf] and [portsOf] are ground truth: patternOf(m) = the 3-bit pattern of inner
     *  vertex m's gadget membership; portsOf(v) = base-vertex v's 3 ports in fixed order. */
    private class BypassedMultipede(val g: Graph, val numV: Int, val numW: Int, val portsOf: Array<IntArray>, val patternOf: (Int) -> IntArray, val gadgetOf: (Int) -> Int)

    private fun buildRStar(b: BaseGraph): BypassedMultipede {
        fun inner(v: Int, patternIdx: Int) = v * 4 + patternIdx
        val n = b.numV * 4
        val adjSets = Array(n) { mutableSetOf<Int>() }
        fun connect(x: Int, y: Int) { if (x != y) { adjSets[x].add(y); adjSets[y].add(x) } }

        // side(w, s) = every inner vertex attached to port w on side s (0=a, 1=b) -- these become
        // one clique each.
        val sideMembers = HashMap<Pair<Int, Int>, MutableList<Int>>()
        for (v in 0 until b.numV) {
            val ports = b.neighborsOfV[v]
            for ((patternIdx, pattern) in evenWeightPatterns.withIndex()) {
                val mi = inner(v, patternIdx)
                for (j in 0..2) {
                    val w = ports[j]
                    sideMembers.getOrPut(w to pattern[j]) { mutableListOf() }.add(mi)
                }
            }
        }
        for (members in sideMembers.values) {
            for (i in members.indices) for (j in i + 1 until members.size) connect(members[i], members[j])
        }

        val adj = Array(n) { adjSets[it].toSortedSet().toIntArray() }
        val names = Array(n) { "v$it" }
        val patternOf = { m: Int -> evenWeightPatterns[m % 4] }
        val gadgetOf = { m: Int -> m / 4 }
        return BypassedMultipede(Graph(n, adj, names), b.numV, b.numW, b.neighborsOfV, patternOf, gadgetOf)
    }

    @Test
    fun checkFlipParityInvariantAgainstRealAutomorphismsOnBypassedGraph() {
        val n = 3
        val (vn, edges) = cycleWithDiagonals(n)
        val m = edges.size
        // A rotation sigma gives the BASE GRAPH B itself a nontrivial automorphism group (see
        // NonBypassGadgetPermutationCheck) -- meaning the rotation choice never actually tested
        // the intended generic/rigid case. Use a sigma verified (by direct search) to make B rigid.
        val sigma = intArrayOf(3, 5, 6, 1, 8, 0, 4, 7, 2)
        val base = buildB(vn, edges, sigma)
        val mp = buildRStar(base)
        val g = mp.g
        println("Built R*(B(G_$n, sigma)): n_vertices=${g.n} (expected 4*${base.numV}=${4 * base.numV})")

        val traces = TracesJni()
        val unitPartition = listOf(IntArray(g.n) { it })
        val generators = traces.generators(g, unitPartition)
        println("Found ${generators.size} generator(s) of Aut(G) (|V|=${g.n})")
        assertTrue(generators.isNotEmpty(), "test is vacuous without at least one non-trivial automorphism -- adjust sigma/n")

        // flip(v, j, alpha) = 1 iff alpha maps gadget v's port-j representative to a vertex whose
        // OWN pattern bit at the SAME port w is the OPPOSITE of v's own bit -- this assumes alpha
        // keeps port w "fixed" (maps its clique to itself, not to some other port's clique).
        // Defensive: fail loudly (not silently) if that assumption is ever violated -- exactly the
        // gadget-permuting case found for cfi-rigid-r2-0648-04-2, which needs the cluster
        // generalization, not this simple per-gadget check.
        fun patternBitOfImage(image: Int, w: Int): Int {
            val gv = mp.gadgetOf(image)
            val ports = mp.portsOf[gv]
            val j = ports.indexOf(w)
            check(j >= 0) { "port $w's representative maps to gadget $gv (ports=${ports.toList()}), which does not even reference port $w -- this automorphism permutes gadgets/ports; the simple per-gadget invariant does not apply" }
            return mp.patternOf(image)[j]
        }

        fun gadgetFlipXor(alpha: IntArray, v: Int): Int {
            val ports = mp.portsOf[v]
            val pattern0 = mp.patternOf(v * 4) // representative: the 000 member of gadget v
            val image = alpha[v * 4]
            return ports.mapIndexed { j, w -> pattern0[j] xor patternBitOfImage(image, w) }.fold(0) { acc, f -> acc xor f }
        }

        var checked = 0
        var violations = 0
        for ((gi, alpha) in generators.withIndex()) {
            for (v in 0 until mp.numV) {
                checked++
                if (gadgetFlipXor(alpha, v) != 0) {
                    violations++
                    println("VIOLATION: generator $gi, gadget v=$v, ports=${mp.portsOf[v].toList()}")
                }
            }
        }
        println("Checked $checked (generator, gadget) pairs, $violations violation(s)")
        assertTrue(violations == 0, "flip-parity invariant does NOT hold on the bypassed graph -- do not trust it for s2/t2")

        fun compose(a: IntArray, b: IntArray) = IntArray(g.n) { a[b[it]] }
        var productChecked = 0
        var productViolations = 0
        for (a in generators) for (b in generators) {
            val prod = compose(a, b)
            for (v in 0 until mp.numV) {
                productChecked++
                if (gadgetFlipXor(prod, v) != 0) productViolations++
            }
        }
        println("Checked $productChecked (generator-product, gadget) pairs, $productViolations violation(s)")
        assertTrue(productViolations == 0, "flip-parity invariant is not closed under composition on the bypassed graph")
    }
}
