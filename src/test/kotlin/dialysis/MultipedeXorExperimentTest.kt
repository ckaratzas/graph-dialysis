package dialysis

import dialysis.content.Content
import dialysis.graph.Graph
import dialysis.refinement.dispatchColouring
import dialysis.sat.cryptominisat.buildCryptoMiniSatEncoding
import org.junit.jupiter.api.Test

/**
 * Builds R(B(Gn,sigma)) exactly per Neuen & Schweitzer, "Benchmark Graphs for Practical Graph
 * Isomorphism" (arXiv:1705.03686), Algorithm 1 (base graph) + Algorithm 2 (multipede/CFI gadget
 * construction) -- the UNREDUCED form, so `a(w)`/`b(w)` outer vertices stay separate (unlike the
 * repo's actual cfi-rigid-r2/s2/t2 benchmark files, which apply one or both of the paper's own
 * vertex-reduction tricks -- see Sections 4.1/4.2 -- making their internal gadget structure
 * opaque without the exact generator/seed used to produce them).
 *
 * Purpose: this is the empirical, low-risk alternative to hand-deriving XOR clauses from the
 * gadget's parity theorem (Fig 2.1: an outer-vertex bijection fixing each {a_i,b_i} pair setwise
 * extends to a gadget automorphism iff an EVEN number of pairs are swapped). Hand-deriving that
 * into an explicit `add_xor_clause` call turns out to be genuinely delicate once port
 * PERMUTATION (not just per-port flipping) is accounted for -- getting it wrong risks an unsound
 * encoding, which this codebase treats as the one unacceptable failure mode (see
 * INVARIANT_FILTERED_SAT_SPEC.md). So instead: build the graph so its gadget structure is as
 * close to the textbook form as possible (not bypassed/reduced), encode it with the EXISTING,
 * already-sound plain-CNF encoder (buildCryptoMiniSatEncoding -- a literal copy of the CaDiCaL
 * encoder), and check CMS's own [dialysis.sat.cryptominisat.CryptoMiniSatSolver.recoveredXorCount]
 * to see whether its OWN pattern-based xorfinder succeeds where it failed on the more heavily
 * reduced benchmark files (see the earlier zero-XOR-found result on cfi-rigid-t2).
 */
class MultipedeXorExperimentTest {

    /** Gn: the 2n-cycle with diagonals (Neuen-Schweitzer Section 3.1) -- vertices 0..2n-1, edges
     *  {i, i+1 mod 2n} for all i, plus {i, i+n} for i in 0..n-1 (each diagonal listed once). */
    private fun cycleWithDiagonals(n: Int): Pair<Int, List<Pair<Int, Int>>> {
        val vn = 2 * n
        val edges = mutableListOf<Pair<Int, Int>>()
        for (i in 0 until vn) edges.add(i to (i + 1) % vn)
        for (i in 0 until n) edges.add(i to (i + n))
        return vn to edges
    }

    /** Algorithm 1: V(B) = V(Gn) x {0,1} u E(Gn); (v,0) is adjacent to every edge-vertex
     *  containing v, (v,1) is adjacent to every edge-vertex e with v in sigma(e). Returns
     *  (numVVertices = 2*vn, numWVertices = |E(Gn)|, neighborsOfV: for each V-vertex, its
     *  W-vertex (edge) indices). sigma is given as a permutation of edge indices. */
    private class BaseGraph(val numV: Int, val numW: Int, val neighborsOfV: Array<IntArray>)

    private fun buildB(vn: Int, edges: List<Pair<Int, Int>>, sigma: IntArray): BaseGraph {
        val m = edges.size
        val numV = 2 * vn
        val incidentEdgesOf = Array(vn) { mutableListOf<Int>() }
        for ((idx, e) in edges.withIndex()) {
            incidentEdgesOf[e.first].add(idx)
            incidentEdgesOf[e.second].add(idx)
        }
        // sigmaIncidentEdgesOf[v] = edges e such that v in sigma(e) -- i.e. e such that
        // sigma[e] is incident to v, per Algorithm 1's "e in E(Gn) with v in sigma(e)".
        val sigmaIncidentEdgesOf = Array(vn) { mutableListOf<Int>() }
        for (e in 0 until m) {
            val (a, b) = edges[sigma[e]]
            sigmaIncidentEdgesOf[a].add(e)
            sigmaIncidentEdgesOf[b].add(e)
        }
        val neighborsOfV = Array(numV) { IntArray(0) }
        for (v in 0 until vn) {
            neighborsOfV[2 * v] = incidentEdgesOf[v].sorted().toIntArray()      // (v,0)
            neighborsOfV[2 * v + 1] = sigmaIncidentEdgesOf[v].sorted().toIntArray() // (v,1)
        }
        for (row in neighborsOfV) check(row.size == 3) { "expected degree 3, got ${row.size}" }
        return BaseGraph(numV, m, neighborsOfV)
    }

    /** The 4 even-weight (number of 1s even) vectors in {0,1}^3, in a fixed order -- these index
     *  the 4 inner vertices m_i(v) of each gadget (Algorithm 2). */
    private val evenWeightPatterns = listOf(
        intArrayOf(0, 0, 0), intArrayOf(0, 1, 1), intArrayOf(1, 0, 1), intArrayOf(1, 1, 0),
    )

    private class Multipede(val g: Graph, val innerOf: (v: Int, patternIdx: Int) -> Int, val outerA: (w: Int) -> Int, val outerB: (w: Int) -> Int)

    /** Algorithm 2. Vertex id layout: inner vertices first (numV * 4 of them, 4 per V-vertex, in
     *  [evenWeightPatterns] order), then a(w) for w in 0..numW-1, then b(w) for w in 0..numW-1. */
    private fun buildR(b: BaseGraph): Multipede {
        val innerBase = 0
        val aBase = b.numV * 4
        val bBase = aBase + b.numW
        val n = bBase + b.numW
        fun inner(v: Int, patternIdx: Int) = innerBase + v * 4 + patternIdx
        fun a(w: Int) = aBase + w
        fun bb(w: Int) = bBase + w

        val adjSets = Array(n) { mutableSetOf<Int>() }
        fun connect(x: Int, y: Int) { adjSets[x].add(y); adjSets[y].add(x) }

        for (v in 0 until b.numV) {
            val ports = b.neighborsOfV[v] // w1, w2, w3 in fixed (sorted) order
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
        return Multipede(Graph(n, adj, names), ::inner, ::a, ::bb)
    }

    @Test
    fun checkWhetherXorfinderRecoversStructureFromAnUnreducedMultipedeGraph() {
        val n = 3 // Gn has 2n=6 vertices, 3n=9 edges -> R(B) has 22n = 66 vertices
        val (vn, edges) = cycleWithDiagonals(n)
        // Deterministic "random" permutation of edge indices -- a fixed rotation, not the
        // identity (which would make sigma trivial and the base graph degenerate/symmetric).
        val m = edges.size
        val sigma = IntArray(m) { (it + 1) % m }
        val base = buildB(vn, edges, sigma)
        val multipede = buildR(base)
        val g = multipede.g

        println("Built R(B(G_$n, sigma)): n_vertices=${g.n} n_edges=${g.m} (expected 22*$n=${22 * n} vertices)")

        val dispatch = dispatchColouring(g)
        val colorOf: (Int) -> Content = { v -> dispatch.colouring[v] }
        val classSizes = (0 until g.n).groupBy { colorOf(it) }.values.map { it.size }
        println("colouring_used=${dispatch.used} classes=${classSizes.size} class_size_distribution=${classSizes.groupingBy { it }.eachCount()}")

        val (cmsSolver, _) = buildCryptoMiniSatEncoding(g, colorOf)
        try {
            println("recovered XOR count BEFORE simplify: ${cmsSolver.recoveredXorCount()}")
            val simplifyResult = cmsSolver.simplify(10_000)
            println("simplify result=$simplifyResult")
            println("recovered XOR count AFTER simplify: ${cmsSolver.recoveredXorCount()}")
            cmsSolver.printStats()
        } finally {
            cmsSolver.close()
        }
    }
}
