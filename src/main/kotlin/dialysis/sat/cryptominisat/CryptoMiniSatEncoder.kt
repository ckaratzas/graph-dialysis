package dialysis.sat.cryptominisat

import dialysis.content.Content
import dialysis.graph.Graph
import dialysis.sat.cadical.CadicalEncoding
import dialysis.sat.cadical.DEFAULT_EDGE_THRESHOLD
import dialysis.sat.cadical.SparseVarRow
import dialysis.sat.cadical.computePreserveGroups

/**
 * Identical encoding to [dialysis.sat.cadical.buildCadicalEncodingHybrid] -- same variables, same
 * bijection/edge-preservation clauses, same conflict-vs-support-form threshold -- targeting
 * [CryptoMiniSatSolver] instead of [dialysis.sat.cadical.CadicalSolver]. Deliberately NOT
 * refactored into one shared implementation parameterized over the solver type: [CadicalEncoding]
 * itself is already solver-agnostic (just `varOf`/`numVars`/`groups`), so the only thing duplicated
 * here is the ~30-line clause-emission loop, and keeping it a literal copy makes it trivial to
 * confirm by inspection that this experiment is solving the EXACT SAME formula CaDiCaL would (see
 * INVARIANT_FILTERED_SAT_SPEC.md -- soundness/completeness depend on that formula, not on which
 * solver decides it). Only the preserve-side (non-bipartite) encoding -- no side-swapped variant --
 * since this exists to compare against CaDiCaL on `cfi-rigid-t2`/`s2`, both non-bipartite families.
 */
private fun exactlyOne(solver: CryptoMiniSatSolver, lits: List<Int>): Int {
    solver.addClause(lits.toIntArray())
    var clauses = 1
    for (i in lits.indices) for (j in i + 1 until lits.size) {
        solver.addClause(intArrayOf(-lits[i], -lits[j]))
        clauses++
    }
    return clauses
}

fun buildCryptoMiniSatEncoding(g: Graph, colorOf: (Int) -> Content, edgeThreshold: Long = DEFAULT_EDGE_THRESHOLD): Pair<CryptoMiniSatSolver, CadicalEncoding> {
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

    val solver = CryptoMiniSatSolver()

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
