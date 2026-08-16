package dialysis.sat

import dialysis.graph.Graph
import java.util.ArrayDeque

/**
 * Implied distance clauses. An automorphism preserves distance (`dist(alpha(u), alpha(w)) =
 * dist(u, w)`), so for admissible images `v` of `u` and `a'` of anchor `a`, `x[u][v] AND
 * x[a][a']` is impossible whenever their distance buckets disagree. This is ENTAILED by the
 * edge-preservation and bijection constraints an encoding already has -- adding it excludes no
 * automorphism, so completeness is untouched and UNSAT stays an unconditional proof. Its only
 * role is propagation strength: a pair-level constraint that per-vertex colour filtering alone
 * cannot express.
 *
 * These clauses are never symmetry-breaking, precisely because they are entailed, not additional.
 */

/** All-pairs shortest-path distances via one BFS per vertex, O(n(n+m)) total. */
fun computeAllPairsDistances(g: Graph): Array<IntArray> {
    val n = g.n
    return Array(n) { src ->
        val dist = IntArray(n) { -1 }
        dist[src] = 0
        val queue = ArrayDeque<Int>()
        queue.add(src)
        while (queue.isNotEmpty()) {
            val u = queue.poll()
            for (w in g.adj[u]) {
                if (dist[w] < 0) {
                    dist[w] = dist[u] + 1
                    queue.add(w)
                }
            }
        }
        dist
    }
}

/** Buckets a distance: exact values `0..dmax`, everything larger collapses to one `>DMAX` bucket
 *  (`dmax + 1`) -- unreachable (`-1`, disconnected graphs only) gets its OWN bucket, never
 *  confused with ">DMAX", since "no path" and "a long path" are not the same invariant. */
fun distanceBucket(d: Int, dmax: Int): Int = when {
    d < 0 -> Int.MAX_VALUE
    d > dmax -> dmax + 1
    else -> d
}

/**
 * Picks `k` anchor vertices from the smallest colour classes first (most constrained, hence most
 * informative), ties broken by vertex id for determinism -- never by insertion/hash order.
 */
fun selectAnchors(groups: List<List<Int>>, k: Int): List<Int> {
    val ordered = groups.sortedWith(compareBy({ it.size }, { it.min() }))
    val anchors = mutableListOf<Int>()
    for (group in ordered) {
        for (v in group.sorted()) {
            if (anchors.size >= k) return anchors
            anchors.add(v)
        }
    }
    return anchors
}

data class ImpliedClauseStats(val dmax: Int, val anchorK: Int, val anchors: List<Int>, val clausesAdded: Int)
