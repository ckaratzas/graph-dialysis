package dialysis.sat.cadical

import dialysis.content.Content
import dialysis.graph.Graph
import dialysis.sat.SatQueryResult

/**
 * FINAL_MEASUREMENTS_SPEC.md Task 2.1's per-query encoding: variable `x_ij` exists iff
 * `cU[i] = cV[j]`, where `cU`/`cV` come from individualizing `u`/`v` (see
 * [dialysis.refinement.perQueryColouring]) -- an ASYMMETRIC admissibility test, unlike
 * [buildCadicalEncodingHybrid]'s single shared `colorOf`, so rows ([rowGroups], grouped by `cU`)
 * and columns ([colGroups], grouped by the SAME colour value under `cV`) are tracked separately;
 * `rowGroups[k]`/`colGroups[k]` are the two sides of the k-th matching colour bucket. Because both
 * `u` and `v` are marked with the identical sentinel colour (see [dialysis.refinement.individualize]),
 * `u`'s row group and `v`'s column group are that bucket, so the bijection constraints alone force
 * `x_uv` -- no separate assumption is strictly needed, though [queryPerQueryCadical] still asserts
 * it explicitly as a defensive check against an unexpected colour collision.
 *
 * Deliberately NOT reusing [CadicalEncoding]/[decodeModel]: those assume symmetric groups (the
 * same vertex list on both the row and column side), which per-query rows/cols are not.
 */
class PerQueryCadicalEncoding(
    val g: Graph,
    val varOf: Array<SparseVarRow>,
    val numVars: Int,
    val rowGroups: List<List<Int>>,
    val colGroups: List<List<Int>>,
    val bijectionConstraints: Int,
    val edgeConflictClauses: Int,
)

fun buildPerQueryCadicalEncoding(
    g: Graph,
    cU: Array<Content>,
    cV: Array<Content>,
    edgeThreshold: Long = DEFAULT_EDGE_THRESHOLD,
): Pair<CadicalSolver, PerQueryCadicalEncoding> {
    val n = g.n
    val rowsByColour = LinkedHashMap<Content, MutableList<Int>>()
    for (i in 0 until n) rowsByColour.getOrPut(cU[i]) { mutableListOf() }.add(i)
    val colsByColour = HashMap<Content, MutableList<Int>>()
    for (j in 0 until n) colsByColour.getOrPut(cV[j]) { mutableListOf() }.add(j)

    val rowGroups = mutableListOf<List<Int>>()
    val colGroups = mutableListOf<List<Int>>()
    val varOf = Array(n) { SparseVarRow() }
    var nextVar = 0
    for ((colour, rows) in rowsByColour) {
        val cols = colsByColour[colour] ?: emptyList()
        rowGroups.add(rows)
        colGroups.add(cols)
        for (i in rows) for (j in cols) {
            nextVar++
            varOf[i][j] = nextVar
        }
    }

    val solver = CadicalSolver()

    var bijectionConstraints = 0
    for (gi in rowGroups.indices) {
        val rows = rowGroups[gi]
        val cols = colGroups[gi]
        for (i in rows) {
            exactlyOneVars(solver, cols.map { j -> varOf[i][j] })
            bijectionConstraints++
        }
        for (j in cols) {
            exactlyOneVars(solver, rows.map { i -> varOf[i][j] })
            bijectionConstraints++
        }
    }

    val rowGroupOfVertex = IntArray(n) { -1 }
    for ((gi, rows) in rowGroups.withIndex()) for (i in rows) rowGroupOfVertex[i] = gi

    var edgeConflictClauses = 0
    for (i in 0 until n) {
        val gi = rowGroupOfVertex[i]
        if (gi < 0) continue // i is never a row (no vertex shares cU[i] under cV) -- x_i* is unsatisfiable, no clauses needed
        for (k in g.adj[i]) {
            if (k <= i) continue
            val gk = rowGroupOfVertex[k]
            if (gk < 0) continue
            val colsI = colGroups[gi]
            val colsK = colGroups[gk]
            val classProduct = colsI.size.toLong() * colsK.size.toLong()
            if (classProduct <= edgeThreshold) {
                for (j in colsI) {
                    val adjJ = g.adj[j].toHashSet()
                    for (l in colsK) {
                        if (l !in adjJ) {
                            solver.addClause(intArrayOf(-varOf[i][j], -varOf[k][l]))
                            edgeConflictClauses++
                        }
                    }
                }
            } else {
                val colsKSet = colsK.toHashSet()
                for (j in colsI) {
                    val support = g.adj[j].filter { it in colsKSet }
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

    return solver to PerQueryCadicalEncoding(g, varOf, nextVar, rowGroups, colGroups, bijectionConstraints, edgeConflictClauses)
}

/**
 * Exact variable/clause counts [buildPerQueryCadicalEncoding] would produce for `cU`/`cV`, computed
 * WITHOUT ever constructing a [CadicalSolver] or calling `addClause` -- every branch below mirrors
 * that function's own loop structure exactly (same hybrid short/long clause threshold, same
 * non-adjacency check), just replacing each `solver.addClause(...)` with a counter increment. This
 * is the "measure before you build" step FINAL_MEASUREMENTS_SPEC.md Task 2.2 and
 * DECOMPOSITION_ORDERING_SPEC.md Part 7 both prescribe, applied to the ENCODING SIZE itself rather
 * than just the admissible-pair count [dialysis.refinement.perQueryFilterStats] reports -- pair
 * count alone does not predict edge-conflict clause count, which is what actually dominates a dense
 * graph's encoding cost (see `project_dialysis_final_measurements_task2` memory on `latin-20`).
 */
class PerQueryEncodingSizeEstimate(val variables: Long, val edgeConflictClauses: Long, val bijectionClauses: Long)

fun estimatePerQueryEncodingSize(
    g: Graph,
    cU: Array<Content>,
    cV: Array<Content>,
    edgeThreshold: Long = DEFAULT_EDGE_THRESHOLD,
): PerQueryEncodingSizeEstimate {
    val n = g.n
    val rowsByColour = HashMap<Content, MutableList<Int>>()
    for (i in 0 until n) rowsByColour.getOrPut(cU[i]) { mutableListOf() }.add(i)
    val colsByColour = HashMap<Content, MutableList<Int>>()
    for (j in 0 until n) colsByColour.getOrPut(cV[j]) { mutableListOf() }.add(j)

    var variables = 0L
    // Same O(k^3)-per-bucket blind spot as estimateGlobalEncodingSize's `bijectionClauses` (see
    // that field's own doc) -- buildPerQueryCadicalEncoding's exactlyOneVars is naive pairwise,
    // called once per row (cost ~ c^2, c = that bucket's column count) and once per column (cost ~
    // r^2), for EVERY row/column in a bucket -- r*(1+c*(c-1)/2) + c*(1+r*(r-1)/2) total, asymmetric
    // because PER_QUERY buckets are (unlike GLOBAL's symmetric classes). This is what actually blew
    // up `ag2-16` on 2026-08-29 even though its edgeConflictClauses estimate (983,296) was
    // comfortably under threshold and correctly routed PER_QUERY -- the missing half of the same
    // bug this whole investigation was validating a fix for.
    var bijectionClauses = 0L
    val colsOfColour = HashMap<Content, List<Int>>()
    for ((colour, rows) in rowsByColour) {
        val cols = colsByColour[colour] ?: emptyList()
        val r = rows.size.toLong()
        val c = cols.size.toLong()
        variables += r * c
        bijectionClauses += r * (1 + c * (c - 1) / 2) + c * (1 + r * (r - 1) / 2)
        colsOfColour[colour] = cols
    }

    var edgeConflictClauses = 0L
    for (i in 0 until n) {
        val colsI = colsOfColour[cU[i]] ?: continue
        if (colsI.isEmpty()) continue
        for (k in g.adj[i]) {
            if (k <= i) continue
            val colsK = colsOfColour[cU[k]] ?: continue
            if (colsK.isEmpty()) continue
            val classProduct = colsI.size.toLong() * colsK.size.toLong()
            if (classProduct <= edgeThreshold) {
                for (j in colsI) {
                    val adjJ = g.adj[j].toHashSet()
                    for (l in colsK) if (l !in adjJ) edgeConflictClauses++
                }
            } else {
                edgeConflictClauses += colsI.size // one clause per j, regardless of support size -- matches the real encoder
            }
        }
    }
    return PerQueryEncodingSizeEstimate(variables, edgeConflictClauses, bijectionClauses)
}

/** Same "exactly one" CNF as [CadicalEncoder.kt]'s private `exactlyOne` -- duplicated rather than
 *  exported because that one is `private` to keep its own file's grouping invariants local; this
 *  encoder's rows/cols come from a structurally different (asymmetric) grouping. */
private fun exactlyOneVars(solver: CadicalSolver, lits: List<Int>) {
    solver.addClause(lits.toIntArray())
    for (i in lits.indices) for (j in i + 1 until lits.size) solver.addClause(intArrayOf(-lits[i], -lits[j]))
}

fun decodePerQueryModel(encoding: PerQueryCadicalEncoding, solver: CadicalSolver): IntArray {
    val n = encoding.g.n
    val alpha = IntArray(n) { -1 }
    for (gi in encoding.rowGroups.indices) {
        for (i in encoding.rowGroups[gi]) for (j in encoding.colGroups[gi]) {
            val vid = encoding.varOf[i][j]
            if (vid > 0 && solver.value(vid) > 0) alpha[i] = j
        }
    }
    return alpha
}

/**
 * One query `x_uv`, built as a FRESH [CadicalSolver]/[PerQueryCadicalEncoding] from [cU]/[cV] --
 * per Task 2.1's soundness argument this formula is only valid under the assumption `x_uv`, so it
 * is rebuilt per query rather than shared the way [buildCadicalEncoding]'s formula is (see that
 * function's own doc, and [PerQueryCadicalEncoding]'s doc for why `x_uv` is already forced by
 * construction). The solver is always closed before returning -- no state survives across queries.
 */
fun queryPerQueryCadical(g: Graph, cU: Array<Content>, cV: Array<Content>, u: Int, v: Int, timeoutMs: Long): Pair<SatQueryResult, PerQueryCadicalEncoding> {
    val (solver, encoding) = buildPerQueryCadicalEncoding(g, cU, cV)
    try {
        val varUV = encoding.varOf[u][v]
        if (varUV < 0) return SatQueryResult.Unsat to encoding
        solver.assume(varUV)
        val result = when (solver.solve(timeoutMs)) {
            CadicalSolver.Result.SAT -> SatQueryResult.Sat(decodePerQueryModel(encoding, solver))
            CadicalSolver.Result.UNSAT -> SatQueryResult.Unsat
            CadicalSolver.Result.UNKNOWN -> SatQueryResult.Unknown
        }
        return result to encoding
    } finally {
        solver.close()
    }
}
