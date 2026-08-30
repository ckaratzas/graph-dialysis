package dialysis

import dialysis.content.Content
import dialysis.graph.Graph
import dialysis.refinement.dispatchColouring
import dialysis.sat.cadical.buildCadicalEncoding
import dialysis.sat.cadical.driveToOrbitsCadical
import dialysis.sat.cadical.CadicalEncoding
import dialysis.sat.cryptominisat.CryptoMiniSatSolver
import dialysis.sat.cryptominisat.buildCryptoMiniSatEncoding
import dialysis.sat.cryptominisat.driveToOrbitsCryptoMiniSat
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The real test: does adding the flip-parity XOR clause -- validated against real automorphisms
 * in [MultipedeFlipParityValidationTest] -- (a) keep the encoding sound (identical recovered
 * orbits to the existing plain-CNF encoders) and (b) actually change anything performance-wise,
 * now that it's fed to CMS as a NATIVE `x` line instead of left for xorfinder to (fail to)
 * recover.
 *
 * flip(w) is Tseitin-encoded as a fresh variable: flip(w) <-> OR over b-type targets k of
 * x[a(w)][k] (a(w)'s own admissible targets, split by target type -- colour refinement already
 * guarantees a(w) can only map within its own colour class, which may or may not mix a/b types).
 * Degenerate cases (a(w) has ONLY a-type or ONLY b-type admissible targets) fix flip(w) to a
 * constant via a unit clause instead of needing an OR at all.
 */
class MultipedeGadgetXorSoundnessTest {

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

    /** Tseitin-encodes flip(w) for every w, then adds the per-gadget parity XOR clause. Returns
     *  the number of gadgets an XOR clause was actually added for (== [numV] unless something is
     *  degenerate). */
    private fun addGadgetParityXors(solver: CryptoMiniSatSolver, encoding: CadicalEncoding, mp: Multipede): Int {
        var nextVar = encoding.numVars
        // -1 = not yet computed; otherwise a positive DIMACS var id for a REAL Tseitin variable.
        // Degenerate (constant) cases are handled separately since add_xor_clause needs a
        // variable, not a boolean constant -- a constant flip(w) is folded into the XOR by
        // omitting it from the clause and flipping the target rhs instead (a XOR true-constant is
        // the same as inverting rhs; a XOR false-constant just drops out of the equation).
        val flipVar = IntArray(mp.numW) { -1 }
        val flipConst = arrayOfNulls<Boolean>(mp.numW) // non-null iff flip(w) is a forced constant

        for (w in 0 until mp.numW) {
            val aw = mp.aBase + w
            val bTargets = (mp.bBase until mp.g.n).filter { k -> encoding.varOf[aw][k] >= 0 }
            val aTargets = (mp.aBase until mp.bBase).filter { k -> encoding.varOf[aw][k] >= 0 }
            when {
                bTargets.isEmpty() -> flipConst[w] = false
                aTargets.isEmpty() -> flipConst[w] = true
                else -> {
                    nextVar++
                    val fv = nextVar
                    flipVar[w] = fv
                    // fv -> OR(bTargets):  (-fv, t1, t2, ..., tk)
                    val orClause = IntArray(bTargets.size + 1)
                    orClause[0] = -fv
                    for ((idx, k) in bTargets.withIndex()) orClause[idx + 1] = encoding.varOf[aw][k]
                    solver.addClause(orClause)
                    // each ti -> fv:  (-ti, fv)
                    for (k in bTargets) solver.addClause(intArrayOf(-encoding.varOf[aw][k], fv))
                }
            }
        }

        var gadgetsConstrained = 0
        for (v in 0 until mp.numV) {
            val ports = mp.portsOf[v]
            val vars = mutableListOf<Int>()
            var rhs = false // start at "even" (xor to false); each forced-true constant flips rhs
            for (w in ports) {
                val c = flipConst[w]
                if (c != null) { if (c) rhs = !rhs } else vars.add(flipVar[w])
            }
            if (vars.isEmpty()) {
                check(!rhs) { "gadget $v has an all-constant port-flip pattern that violates the parity invariant -- the invariant itself is wrong, do not proceed" }
                continue
            }
            solver.addXorClause(vars.toIntArray(), rhs)
            gadgetsConstrained++
        }
        return gadgetsConstrained
    }

    @Test
    fun addingTheValidatedXorClauseKeepsTheEncodingSoundAndMeasuresThePayoff() {
        val n = 3
        val (vn, edges) = cycleWithDiagonals(n)
        val m = edges.size
        // A rotation sigma gives the BASE GRAPH B itself a nontrivial automorphism group -- see
        // MultipedeFlipParityValidationTest's doc. Use a sigma verified (by direct search) to make
        // B rigid, so gadgets can only ever map to themselves, matching what the flip-parity
        // invariant this test relies on actually assumes.
        val sigma = intArrayOf(3, 5, 6, 1, 8, 0, 4, 7, 2)
        val base = buildB(vn, edges, sigma)
        val mp = buildR(base)
        val g = mp.g
        val dispatch = dispatchColouring(g)
        val colorOf: (Int) -> Content = { v -> dispatch.colouring[v] }

        val (cadicalSolver, cadicalEncoding) = buildCadicalEncoding(g, colorOf)
        val cadicalT0 = System.currentTimeMillis()
        val cadicalResult = try {
            driveToOrbitsCadical(g, cadicalSolver, cadicalEncoding, swapPair = null, timeoutMs = 30_000, shortMs = 2_000)
        } finally {
            cadicalSolver.close()
        }
        val cadicalMs = System.currentTimeMillis() - cadicalT0

        val (plainCmsSolver, plainCmsEncoding) = buildCryptoMiniSatEncoding(g, colorOf)
        val plainT0 = System.currentTimeMillis()
        val plainResult = try {
            driveToOrbitsCryptoMiniSat(g, plainCmsSolver, plainCmsEncoding, timeoutMs = 30_000, shortMs = 2_000)
        } finally {
            plainCmsSolver.close()
        }
        val plainMs = System.currentTimeMillis() - plainT0

        val (xorCmsSolver, xorCmsEncoding) = buildCryptoMiniSatEncoding(g, colorOf)
        val xorT0 = System.currentTimeMillis()
        val xorResult = try {
            val gadgetsConstrained = addGadgetParityXors(xorCmsSolver, xorCmsEncoding, mp)
            println("Added XOR clauses for $gadgetsConstrained/${mp.numV} gadgets")
            xorCmsSolver.printStats()
            driveToOrbitsCryptoMiniSat(g, xorCmsSolver, xorCmsEncoding, timeoutMs = 30_000, shortMs = 2_000)
        } finally {
            xorCmsSolver.close()
        }
        val xorMs = System.currentTimeMillis() - xorT0

        fun canonical(orbits: List<List<Int>>) = orbits.map { it.sorted() }.sortedBy { it.first() }

        println(
            "n_vertices=${g.n}\n" +
                "  CaDiCaL (plain):        issued=${cadicalResult.queriesIssued} unknown=${cadicalResult.queriesUnknown} orbits=${cadicalResult.orbits.size} wall_ms=$cadicalMs\n" +
                "  CryptoMiniSat (plain):  issued=${plainResult.queriesIssued} unknown=${plainResult.queriesUnknown} orbits=${plainResult.orbits.size} wall_ms=$plainMs\n" +
                "  CryptoMiniSat (+ gadget XOR): issued=${xorResult.queriesIssued} unknown=${xorResult.queriesUnknown} orbits=${xorResult.orbits.size} wall_ms=$xorMs",
        )

        assertEquals(0, cadicalResult.queriesUnknown)
        assertEquals(0, plainResult.queriesUnknown)
        assertEquals(0, xorResult.queriesUnknown)

        val canonicalCadical = canonical(cadicalResult.orbits)
        assertEquals(canonicalCadical, canonical(plainResult.orbits), "plain CryptoMiniSat disagrees with CaDiCaL -- unrelated to the XOR clause, a pre-existing encoder bug")
        assertEquals(
            canonicalCadical, canonical(xorResult.orbits),
            "XOR-augmented CryptoMiniSat recovered a DIFFERENT partition than plain CaDiCaL -- the gadget XOR clause is UNSOUND despite passing the generator/product validation, do not use it",
        )
        println("All three agree on the recovered partition -- the XOR clause is sound on this instance.")
    }
}
