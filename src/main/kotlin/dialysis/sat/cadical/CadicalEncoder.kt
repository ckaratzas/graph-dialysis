package dialysis.sat.cadical

import dialysis.content.Content
import dialysis.graph.Graph

/** Colour classes larger than this fraction of the class-product threshold switch an edge's
 *  encoding from the short conflict clauses to the longer support-clause form -- see
 *  [buildCadicalEncodingHybrid]'s own doc for why both forms exist. */
const val DEFAULT_EDGE_THRESHOLD = 1024L

/**
 * The colour-filtered permutation encoding for "does some automorphism map `u` to `v`?": a
 * permutation matrix restricted to colour-admissible entries (`varOf`), plus edge-preservation
 * clauses. Building this is a pure function of the graph and its colouring -- no solver state is
 * shared across calls, so building fresh encodings per worker (see [CadicalParallelDriver]) only
 * costs recomputation, never correctness.
 */
class CadicalEncoding(
    val g: Graph,
    val varOf: Array<IntArray>, // varOf[i][j] = 1-indexed sat var id if j admissible for i, else -1
    val numVars: Int,
    val groups: List<List<Int>>,
    val bijectionConstraints: Int = 0,
    val edgeConflictClauses: Int = 0,
)

/**
 * "Exactly one of [lits] is true" as plain CNF: at-least-one is one clause; at-most-one is naive
 * pairwise (`¬lits[i] ∨ ¬lits[j]` for every pair) -- O(k^2) clauses per constraint, not a
 * sequential/commander encoding, because it doesn't need to be: every caller of this (bijection
 * rows/columns within one colour-degree-side group) is already bounded to a small fraction of the
 * graph's own vertex count before the SAT path is even used (see `dialysis.benchmark.BenchmarkRunner`'s
 * colour-class-size guard), so the worst case is a few hundred literals, not thousands -- pairwise
 * stays cheap and is far easier to audit than an encoding with auxiliary variables.
 */
private fun exactlyOne(solver: CadicalSolver, lits: List<Int>): Int {
    solver.addClause(lits.toIntArray())
    var clauses = 1
    for (i in lits.indices) for (j in i + 1 until lits.size) {
        solver.addClause(intArrayOf(-lits[i], -lits[j]))
        clauses++
    }
    return clauses
}

fun buildCadicalEncoding(g: Graph, colorOf: (Int) -> Content): Pair<CadicalSolver, CadicalEncoding> =
    buildCadicalEncodingHybrid(g, DEFAULT_EDGE_THRESHOLD, colorOf)

fun buildCadicalEncodingHybrid(g: Graph, edgeThreshold: Long, colorOf: (Int) -> Content): Pair<CadicalSolver, CadicalEncoding> {
    val n = g.n
    val bipartition = g.bipartition()
    fun side(v: Int): Int = if (bipartition == null) 0 else if (v in bipartition.first) 0 else 1
    val degree = IntArray(n) { g.adj[it].size }

    val groups: List<List<Int>> = (0 until n)
        .groupBy { v -> Triple(colorOf(v), degree[v], side(v)) }
        .values.toList()
    val groupOfVertex = IntArray(n)
    for ((gi, members) in groups.withIndex()) for (v in members) groupOfVertex[v] = gi

    val varOf = Array(n) { IntArray(n) { -1 } }
    var nextVar = 0
    for (members in groups) {
        for (i in members) for (j in members) {
            nextVar++
            varOf[i][j] = nextVar
        }
    }

    val solver = CadicalSolver()

    var bijectionConstraints = 0
    for (members in groups) {
        for (i in members) {
            exactlyOne(solver, members.map { j -> varOf[i][j] })
            bijectionConstraints++
        }
        for (j in members) {
            exactlyOne(solver, members.map { i -> varOf[i][j] })
            bijectionConstraints++
        }
    }

    var edgeConflictClauses = 0
    for (i in 0 until n) {
        for (k in g.adj[i]) {
            if (k <= i) continue
            val membersI = groups[groupOfVertex[i]]
            val membersK = groups[groupOfVertex[k]]
            val classProduct = membersI.size.toLong() * membersK.size.toLong()
            if (classProduct <= edgeThreshold) {
                for (j in membersI) {
                    val adjJ = g.adj[j].toHashSet()
                    for (l in membersK) {
                        if (l !in adjJ) {
                            solver.addClause(intArrayOf(-varOf[i][j], -varOf[k][l]))
                            edgeConflictClauses++
                        }
                    }
                }
            } else {
                val membersKSet = membersK.toHashSet()
                for (j in membersI) {
                    val support = g.adj[j].filter { it in membersKSet }
                    if (support.isEmpty()) {
                        solver.addClause(intArrayOf(-varOf[i][j]))
                    } else {
                        val lits = IntArray(support.size + 1)
                        lits[0] = -varOf[i][j]
                        for ((idx, l) in support.withIndex()) lits[idx + 1] = varOf[k][l]
                        solver.addClause(lits)
                    }
                    edgeConflictClauses++
                }
            }
        }
    }

    return solver to CadicalEncoding(g, varOf, nextVar, groups, bijectionConstraints, edgeConflictClauses)
}

/**
 * If `G` is connected and bipartite with equal-sized parts `A`, `B`, an automorphism may SWAP the
 * parts instead of preserving them -- [buildCadicalEncoding] only ever encodes the preserving case
 * (`side(i)==side(j)` baked into its own grouping), so on an equal-sized bipartition its UNSAT
 * alone is not an unconditional proof: a side-swapping automorphism moving `u` to `v` would be
 * invisible to it. This function encodes ONLY the swapped case (`side(i) != side(j)`) -- callers
 * combine the two (SAT if either) for the actual unconditional guarantee. Returns null when
 * swapping cannot apply at all (non-bipartite, or `|A| != |B|`), so a null result itself is the
 * "parts preserved, nothing else to check" signal.
 */
fun buildCadicalEncodingSideSwapped(g: Graph, colorOf: (Int) -> Content): Pair<CadicalSolver, CadicalEncoding>? {
    val n = g.n
    val bipartition = g.bipartition() ?: return null
    val (partA, partB) = bipartition
    if (partA.size != partB.size) return null
    val sideOfA = partA.toHashSet()
    fun side(v: Int): Int = if (v in sideOfA) 0 else 1

    val groups: List<List<Int>> = (0 until n).groupBy { v -> Pair(colorOf(v), g.adj[v].size) }.values.toList()

    val varOf = Array(n) { IntArray(n) { -1 } }
    var nextVar = 0
    val sideAOf = HashMap<Int, List<Int>>()
    val sideBOf = HashMap<Int, List<Int>>()
    for ((gi, members) in groups.withIndex()) {
        val a = members.filter { side(it) == 0 }
        val b = members.filter { side(it) == 1 }
        sideAOf[gi] = a
        sideBOf[gi] = b
        for (i in a) for (j in b) {
            nextVar++; varOf[i][j] = nextVar
            nextVar++; varOf[j][i] = nextVar
        }
    }

    val solver = CadicalSolver()

    for (gi in groups.indices) {
        val a = sideAOf.getValue(gi)
        val b = sideBOf.getValue(gi)
        for (i in a) exactlyOne(solver, b.map { j -> varOf[i][j] })
        for (j in b) exactlyOne(solver, a.map { i -> varOf[i][j] })
        for (j in b) exactlyOne(solver, a.map { i -> varOf[j][i] })
        for (i in a) exactlyOne(solver, b.map { j -> varOf[j][i] })
    }

    val groupOfVertex = IntArray(n)
    for ((gi, members) in groups.withIndex()) for (v in members) groupOfVertex[v] = gi

    for (i in 0 until n) {
        for (k in g.adj[i]) {
            if (k <= i) continue
            val gi = groupOfVertex[i]
            val gk = groupOfVertex[k]
            val imagesI = (sideAOf.getValue(gi) + sideBOf.getValue(gi)).filter { varOf[i][it] >= 0 }
            val imagesK = (sideAOf.getValue(gk) + sideBOf.getValue(gk)).filter { varOf[k][it] >= 0 }
            for (j in imagesI) {
                val adjJ = g.adj[j].toHashSet()
                for (l in imagesK) {
                    if (l !in adjJ) {
                        solver.addClause(intArrayOf(-varOf[i][j], -varOf[k][l]))
                    }
                }
            }
        }
    }

    return solver to CadicalEncoding(g, varOf, nextVar, groups)
}