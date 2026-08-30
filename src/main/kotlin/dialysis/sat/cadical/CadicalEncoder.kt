package dialysis.sat.cadical

import dialysis.content.Content
import dialysis.graph.Graph

/** Colour classes larger than this fraction of the class-product threshold switch an edge's
 *  encoding from the short conflict clauses to the longer support-clause form -- see
 *  [buildCadicalEncodingHybrid]'s own doc for why both forms exist. */
const val DEFAULT_EDGE_THRESHOLD = 1024L

/**
 * One row of [CadicalEncoding.varOf]: only admissible (colour/side-compatible) columns are ever
 * stored, backed by a lazily-allocated map instead of an `n`-length array. A dense `IntArray(n)`
 * per row (`n^2` total) was measured as the actual OOM cause on a ~16700-effective-vertex instance
 * (~1GiB per full varOf matrix, several live at once across a test's own stats print, the driver's
 * former ref-encoding build, and each worker's real encoding) -- almost all of those `n^2` entries
 * are `-1` (colour classes are typically a small fraction of `n`, which is the entire point of
 * refining the colouring before the SAT path). `get`/`set` operators keep every existing
 * `varOf[i][j]` / `varOf[i][j] = x` call site unchanged; only this row's backing storage differs
 * from before.
 */
class SparseVarRow {
    private var entries: HashMap<Int, Int>? = null
    operator fun get(j: Int): Int = entries?.get(j) ?: -1
    operator fun set(j: Int, value: Int) {
        (entries ?: HashMap<Int, Int>().also { entries = it })[j] = value
    }
}

/**
 * The colour-filtered permutation encoding for "does some automorphism map `u` to `v`?": a
 * permutation matrix restricted to colour-admissible entries (`varOf`), plus edge-preservation
 * clauses. Building this is a pure function of the graph and its colouring -- no solver state is
 * shared across calls, so building fresh encodings per worker (see [CadicalParallelDriver]) only
 * costs recomputation, never correctness.
 */
class CadicalEncoding(
    val g: Graph,
    val varOf: Array<SparseVarRow>, // varOf[i][j] = 1-indexed sat var id if j admissible for i, else -1
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

/**
 * Colour+degree+side grouping [buildCadicalEncodingHybrid] partitions vertices into, WITHOUT
 * building its O(n^2) `varOf` matrix, a [CadicalSolver], or any SAT clauses -- for callers (e.g.
 * [dialysis.sat.cadical.driveToOrbitsCadicalParallel]'s work-queue setup) that only need group
 * membership/sizes. Building a full [CadicalEncoding] just to read `.groups` was measured as pure
 * waste: a discarded solver plus a discarded n-by-n matrix (~1GiB at n≈16700) for zero use beyond
 * `.groups.size`.
 */
fun computePreserveGroups(g: Graph, colorOf: (Int) -> Content): List<List<Int>> {
    val n = g.n
    val bipartition = g.bipartition()
    fun side(v: Int): Int = if (bipartition == null) 0 else if (v in bipartition.first) 0 else 1
    val degree = IntArray(n) { g.adj[it].size }
    return (0 until n).groupBy { v -> Triple(colorOf(v), degree[v], side(v)) }.values.toList()
}

/** Same rationale as [computePreserveGroups], for [buildCadicalEncodingSideSwapped]'s grouping.
 *  Null exactly when [buildCadicalEncodingSideSwapped] itself would return null (non-bipartite,
 *  or unequal-sized parts). */
fun computeSwapGroups(g: Graph, colorOf: (Int) -> Content): List<List<Int>>? {
    val n = g.n
    val bipartition = g.bipartition() ?: return null
    val (partA, partB) = bipartition
    if (partA.size != partB.size) return null
    return (0 until n).groupBy { v -> Pair(colorOf(v), g.adj[v].size) }.values.toList()
}

fun buildCadicalEncoding(g: Graph, colorOf: (Int) -> Content): Pair<CadicalSolver, CadicalEncoding> =
    buildCadicalEncodingHybrid(g, DEFAULT_EDGE_THRESHOLD, colorOf)

/**
 * Exact variable/edge-conflict-clause counts [buildCadicalEncodingHybrid] would produce, computed
 * WITHOUT ever constructing a [CadicalSolver] or calling `addClause` -- the GLOBAL-formula
 * counterpart of [estimatePerQueryEncodingSize] (see `PerQueryCadicalEncoder.kt`).
 * `edgeConflictClauses` is exactly `sum over edges (i,k) of |C(i)| * |C(k)|` (the predictor
 * `project_dialysis_final_measurements_task2` memory settled on for when per-query filtering, or
 * the plain global formula, is even worth attempting) -- computable in one pass over [g]'s edges
 * from [colorOf] alone, before any solving, so this is safe to call on every campaign instance
 * regardless of how large the real encoding would turn out to be.
 */
class GlobalEncodingSizeEstimate(val variables: Long, val edgeConflictClauses: Long, val bijectionClauses: Long)

fun estimateGlobalEncodingSize(g: Graph, colorOf: (Int) -> Content, edgeThreshold: Long = DEFAULT_EDGE_THRESHOLD): GlobalEncodingSizeEstimate {
    val groups = computePreserveGroups(g, colorOf)
    val groupOfVertex = IntArray(g.n)
    for ((gi, members) in groups.withIndex()) for (v in members) groupOfVertex[v] = gi

    var variables = 0L
    // exactlyOne's naive pairwise "at-most-one" is 1 + k*(k-1)/2 clauses per row/column of a
    // k-member class, and buildCadicalEncodingHybrid calls it once per row AND once per column of
    // EVERY class (see that function) -- i.e. O(k^3) total per class, cubic in class size, unlike
    // `variables` (O(k^2)) or edgeConflictClauses (bounded by edgeThreshold's hybrid switch).
    // 2026-08-29: this was the actual, previously unestimated driver of a real BenchmarkRunner
    // cmz-family OOM crash (peak_rss_mb 1149MB -> 8169MB over a 7-instance sweep) -- a class with
    // few crossing edges (keeping edgeConflictClauses low, passing the GLOBAL gate below) can still
    // have a catastrophic bijection-clause cost the old estimate never saw at all.
    var bijectionClauses = 0L
    for (members in groups) {
        val k = members.size.toLong()
        variables += k * k
        bijectionClauses += 2 * k * (1 + k * (k - 1) / 2)
    }

    var edgeConflictClauses = 0L
    for (i in 0 until g.n) {
        for (k in g.adj[i]) {
            if (k <= i) continue
            val membersI = groups[groupOfVertex[i]]
            val membersK = groups[groupOfVertex[k]]
            val classProduct = membersI.size.toLong() * membersK.size.toLong()
            if (classProduct <= edgeThreshold) {
                for (j in membersI) {
                    val adjSet = g.adj[j].toHashSet()
                    for (l in membersK) if (l !in adjSet) edgeConflictClauses++
                }
            } else {
                edgeConflictClauses += membersI.size // one clause per j, regardless of support size -- matches the real encoder
            }
        }
    }
    return GlobalEncodingSizeEstimate(variables, edgeConflictClauses, bijectionClauses)
}

fun buildCadicalEncodingHybrid(g: Graph, edgeThreshold: Long, colorOf: (Int) -> Content): Pair<CadicalSolver, CadicalEncoding> {
    val n = g.n
    val groups: List<List<Int>> = computePreserveGroups(g, colorOf)
    val groupOfVertex = IntArray(n)
    for ((gi, members) in groups.withIndex()) for (v in members) groupOfVertex[v] = gi

    val varOf = Array(n) { SparseVarRow() }
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

    // Safe to force-unwrap: this function already returned null above for exactly the two
    // conditions (non-bipartite, unequal-sized parts) that would make computeSwapGroups null too.
    val groups: List<List<Int>> = computeSwapGroups(g, colorOf)!!

    val varOf = Array(n) { SparseVarRow() }
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