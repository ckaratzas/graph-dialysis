package dialysis.fvs

import dialysis.graph.Graph

/**
 * Bafna, Berman & Fujito, "A 2-Approximation Algorithm for the Undirected Feedback Vertex Set
 * Problem" (SIAM J. Discrete Math. 12(3), 1999) -- algorithm FEEDBACK, Figure 3.1. Unweighted
 * specialization (every vertex weight = 1, matching the paper's own unweighted corollary) --
 * this project has no natural per-vertex weighting for the `cfi-rigid-*` graphs it targets.
 *
 * A feedback vertex set (FVS) is a vertex set hitting every cycle -- removing it leaves a forest.
 * FEEDBACK repeatedly identifies a "local structure" (a semidisjoint cycle -- one where every
 * vertex has degree 2 except at most one -- or, failing that, the whole remaining clean graph,
 * weighted degree-proportionally) and subtracts a uniform slice of its weight from the running
 * total, pushing every vertex whose weight hits zero onto a stack and into the FVS; a final reverse
 * pass discards any now-redundant vertex. See the paper for the performance-ratio proof (<= 2).
 */
object FeedbackVertexSet {

    /** Returns a feedback vertex set F -- G with F removed is a forest -- with total size at most
     *  2x the true minimum (Theorem 4.5's <=2 ratio, up to the paper's own -2/(|E|-3) slack). */
    fun compute(g: Graph): Set<Int> {
        val n = g.n
        val weight = DoubleArray(n) { 1.0 }
        val alive = BooleanArray(n) { true }
        val adj = Array(n) { g.adj[it].toHashSet() }
        fun degree(v: Int) = adj[v].size

        fun cleanup() {
            val queue = ArrayDeque<Int>()
            for (v in 0 until n) if (alive[v] && degree(v) <= 1) queue.add(v)
            while (queue.isNotEmpty()) {
                val v = queue.removeFirst()
                if (!alive[v] || degree(v) > 1) continue
                alive[v] = false
                for (w in adj[v].toList()) {
                    adj[w].remove(v)
                    if (alive[w] && degree(w) <= 1) queue.add(w)
                }
                adj[v].clear()
            }
        }

        // A semidisjoint cycle: every vertex has degree 2 except at most one ("the hub"). After
        // cleanup(), every alive vertex has degree >= 2, so tracing a maximal chain of degree-2
        // vertices in both directions from any degree-2 start either closes on itself (a pure
        // degree-2 cycle) or hits a hub on each side -- the same hub on both sides means the chain
        // plus that one hub IS a semidisjoint cycle.
        fun findSemidisjointCycle(): List<Int>? {
            val visited = BooleanArray(n)
            fun trace(start: Int, firstStep: Int): Pair<MutableList<Int>, Int> {
                val path = mutableListOf(start)
                var prev = start
                var cur = firstStep
                while (alive[cur] && degree(cur) == 2 && cur != start) {
                    path.add(cur)
                    val next = adj[cur].first { it != prev }
                    prev = cur
                    cur = next
                }
                return path to cur
            }
            for (start in 0 until n) {
                if (!alive[start] || visited[start] || degree(start) != 2) continue
                val nbrs = adj[start].toList()
                val (path1, end1) = trace(start, nbrs[0])
                if (end1 == start) {
                    for (v in path1) visited[v] = true
                    return path1
                }
                val (path2, end2) = trace(start, nbrs[1])
                for (v in path1) visited[v] = true
                for (v in path2) visited[v] = true
                if (end1 == end2) {
                    val cycle = mutableListOf<Int>()
                    cycle.addAll(path1)
                    cycle.add(end1)
                    cycle.addAll(path2.asReversed())
                    return cycle
                }
            }
            return null
        }

        val fvsOrder = mutableListOf<Int>() // doubles as the paper's STACK (append == push)
        cleanup()
        while ((0 until n).any { alive[it] }) {
            val cycle = findSemidisjointCycle()
            if (cycle != null) {
                val gamma = cycle.minOf { weight[it] }
                for (v in cycle) weight[v] -= gamma
            } else {
                val aliveVerts = (0 until n).filter { alive[it] }
                val gamma = aliveVerts.minOf { weight[it] / (degree(it) - 1) }
                for (v in aliveVerts) weight[v] -= gamma * (degree(v) - 1)
            }
            var madeProgress = false
            for (v in 0 until n) {
                if (alive[v] && weight[v] <= 1e-9) {
                    alive[v] = false
                    for (w in adj[v].toList()) adj[w].remove(v)
                    adj[v].clear()
                    fvsOrder.add(v)
                    madeProgress = true
                }
            }
            cleanup()
            check(madeProgress) { "FEEDBACK made no progress in one iteration -- algorithm invariant violated" }
        }

        val fvs = fvsOrder.toMutableSet()
        for (v in fvsOrder.asReversed()) {
            fvs.remove(v)
            if (!isForest(g, fvs)) fvs.add(v) // v was actually load-bearing, keep it
        }
        return fvs
    }

    /** True iff [g] restricted to (all vertices EXCEPT [excluded]) is acyclic. Plain iterative
     *  DFS with parent-tracking -- a visited non-parent neighbour is a back edge, i.e. a cycle. */
    private fun isForest(g: Graph, excluded: Set<Int>): Boolean {
        val visited = BooleanArray(g.n)
        for (start in 0 until g.n) {
            if (start in excluded || visited[start]) continue
            visited[start] = true
            val stack = ArrayDeque<Pair<Int, Int>>()
            stack.addLast(start to -1)
            while (stack.isNotEmpty()) {
                val (v, parent) = stack.removeLast()
                for (w in g.adj[v]) {
                    if (w in excluded) continue
                    if (!visited[w]) {
                        visited[w] = true
                        stack.addLast(w to v)
                    } else if (w != parent) {
                        return false
                    }
                }
            }
        }
        return true
    }
}
