package dialysis

import dialysis.cl.TracesJni
import dialysis.graph.Graph
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Before trusting ANY hand-derived XOR clause, check the candidate invariant against REAL,
 * independently-computed automorphisms (via Traces) of the multipede graph
 * [MultipedeXorExperimentTest] builds -- not just the textbook theorem's restricted special case
 * (a bijection fixing each {a_i,b_i} pair setwise), which doesn't obviously survive port
 * permutation once the gadget is embedded in a larger graph and colour classes don't pin down
 * which port maps to which.
 *
 * Candidate invariant: for w with outer vertices a(w)/b(w), define flip(w, alpha) = true iff
 * alpha(a(w)) is a b-type vertex (alpha may send a(w) to EITHER an a(w') or a b(w') -- whichever
 * colour-admissible target the automorphism actually picks). Claim: for every gadget v with ports
 * (w1,w2,w3) and every alpha in Aut(G), flip(w1,alpha) XOR flip(w2,alpha) XOR flip(w3,alpha) = 0.
 */
class MultipedeFlipParityValidationTest {

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

    private class Multipede(val g: Graph, val numV: Int, val numW: Int, val portsOf: Array<IntArray>, val aBase: Int, val bBase: Int)

    private fun buildR(b: BaseGraph): Multipede {
        val aBase = b.numV * 4
        val bBase = aBase + b.numW
        val n = bBase + b.numW
        fun inner(v: Int, patternIdx: Int) = v * 4 + patternIdx
        fun a(w: Int) = aBase + w
        fun bb(w: Int) = bBase + w
        val adjSets = Array(n) { mutableSetOf<Int>() }
        fun connect(x: Int, y: Int) { adjSets[x].add(y); adjSets[y].add(x) }
        for (v in 0 until b.numV) {
            val ports = b.neighborsOfV[v]
            for ((patternIdx, pattern) in evenWeightPatterns.withIndex()) {
                val mi = inner(v, patternIdx)
                for (j in 0..2) {
                    val w = ports[j]
                    if (pattern[j] == 0) connect(mi, a(w)) else connect(mi, bb(w))
                }
            }
        }
        val adj = Array(n) { adjSets[it].toSortedSet().toIntArray() }
        val names = Array(n) { "v$it" }
        return Multipede(Graph(n, adj, names), b.numV, b.numW, b.neighborsOfV, aBase, bBase)
    }

    @Test
    fun checkFlipParityInvariantAgainstRealAutomorphisms() {
        val n = 3
        val (vn, edges) = cycleWithDiagonals(n)
        val m = edges.size
        // A rotation sigma (the original choice here) turns out to give the BASE GRAPH B itself a
        // nontrivial automorphism group (confirmed by direct search over random sigmas, checking
        // Aut(B) via Traces on B alone) -- meaning every automorphism of R(B) can permute WHOLE
        // gadgets onto other, structurally-unrelated gadgets (confirmed: 24/216 (generator, port)
        // checks landed on some a(w')/b(w') for a DIFFERENT edge w' entirely, which this test's OLD
        // isBType-only formula silently mis-scored as "not flipped" rather than recognizing the
        // per-gadget invariant doesn't even apply there). This sigma is verified (by the same
        // search) to make B rigid -- with a rigid base, gadgets can only ever map to themselves,
        // which is the actual precondition this invariant's derivation assumes.
        val sigma = intArrayOf(3, 5, 6, 1, 8, 0, 4, 7, 2)
        val base = buildB(vn, edges, sigma)
        val mp = buildR(base)
        val g = mp.g

        val traces = TracesJni()
        val unitPartition = listOf(IntArray(g.n) { it })
        val generators = traces.generators(g, unitPartition)
        println("Found ${generators.size} generator(s) of Aut(G) (|V|=${g.n})")
        assertTrue(generators.isNotEmpty(), "test is vacuous without at least one non-trivial automorphism -- adjust sigma/n")

        fun isBType(v: Int) = v >= mp.bBase

        // Defensive: fail loudly, not silently, if any port's image lands outside {a(w), b(w)} --
        // that's the gadget/port-permuting case this simple per-gadget invariant does NOT cover
        // (see cfi-rigid-r2-0648-04-2's cluster generalization for what handling it properly looks
        // like). Checking isBType alone can't tell "genuinely fixed/swapped" from "permuted to an
        // unrelated port" apart, so check explicitly rather than trust it.
        fun checkStaysWithinOwnPort(alpha: IntArray) {
            for (w in 0 until mp.numW) {
                val a = mp.aBase + w; val b = mp.bBase + w
                val image = alpha[a]
                check(image == a || image == b) {
                    "port $w's a(w)=$a maps to $image, neither a(w) nor b(w) -- this automorphism permutes gadgets/ports, " +
                        "which the simple per-gadget invariant assumes does NOT happen; do not trust isBType here"
                }
            }
        }

        var checked = 0
        var violations = 0
        for ((gi, alpha) in generators.withIndex()) {
            checkStaysWithinOwnPort(alpha)
            for (v in 0 until mp.numV) {
                val ports = mp.portsOf[v]
                val flips = ports.map { w -> isBType(alpha[mp.aBase + w]) }
                val xor = flips.fold(false) { acc, f -> acc xor f }
                checked++
                if (xor) {
                    violations++
                    println("VIOLATION: generator $gi, gadget v=$v, ports=${ports.toList()}, flips=$flips")
                }
            }
        }
        println("Checked $checked (generator, gadget) pairs across ${generators.size} generators, $violations violation(s)")
        assertTrue(violations == 0, "flip-parity invariant does NOT hold for $violations/$checked real automorphism/gadget pairs -- do not implement this as an XOR clause")

        // Generators alone aren't the whole group -- if the invariant weren't closed under
        // composition, some non-generator element of Aut(G) could still violate it, which would
        // make an XOR clause encoding it UNSOUND (it would exclude a genuine automorphism). Check
        // products of generator pairs too, as a cheap extra check that this is really a subgroup
        // property, not an accident of this specific generating set.
        fun compose(a: IntArray, b: IntArray) = IntArray(g.n) { a[b[it]] }
        var productChecked = 0
        var productViolations = 0
        for (a in generators) for (b in generators) {
            val prod = compose(a, b)
            checkStaysWithinOwnPort(prod)
            for (v in 0 until mp.numV) {
                val ports = mp.portsOf[v]
                val flips = ports.map { w -> isBType(prod[mp.aBase + w]) }
                val xor = flips.fold(false) { acc, f -> acc xor f }
                productChecked++
                if (xor) productViolations++
            }
        }
        println("Checked $productChecked (generator-product, gadget) pairs, $productViolations violation(s)")
        assertTrue(productViolations == 0, "flip-parity invariant is not closed under composition -- $productViolations/$productChecked violations in generator products")
    }
}
