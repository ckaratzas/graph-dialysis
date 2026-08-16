package dialysis.graph

/**
 * Immutable simple undirected graph over dense Int vertex ids [0, n).
 * All pipeline structures index into this; original labels live in [names].
 */
class Graph(
    val n: Int,
    val adj: Array<IntArray>,          // sorted neighbor lists
    val names: Array<String>            // original labels (for I/O and reports only)
) {
    val m: Int get() = adj.sumOf { it.size } / 2

    /** CSR form (offsets, neighbors) for native kernels. */
    fun toCsr(): Pair<IntArray, IntArray> {
        val offsets = IntArray(n + 1)
        for (v in 0 until n) offsets[v + 1] = offsets[v] + adj[v].size
        val neighbors = IntArray(offsets[n])
        for (v in 0 until n) adj[v].copyInto(neighbors, offsets[v])
        return offsets to neighbors
    }

    /**
     * Induced subgraph on [verts]; returns the subgraph plus the old->new vertex
     * map (size [n], oldToNew[v] = new id in the subgraph, or -1 if v not in [verts]).
     * New ids follow the order of [verts].
     */
    fun induced(verts: IntArray): Pair<Graph, IntArray> {
        val oldToNew = IntArray(n) { -1 }
        verts.forEachIndexed { newId, old -> oldToNew[old] = newId }
        // Raw IntArray filter+map+sort (no boxed List<Int> intermediates) — this runs once
        // per vertex per remainder component in Phase 2 of initialPhase, so the boxing that
        // a filter/map/sorted chain over IntArray incurs was real, measured overhead there.
        val newAdj = Array(verts.size) { newId ->
            val old = verts[newId]
            val oldNeighbors = adj[old]
            var count = 0
            for (w in oldNeighbors) if (oldToNew[w] >= 0) count++
            val result = IntArray(count)
            var idx = 0
            for (w in oldNeighbors) {
                val nw = oldToNew[w]
                if (nw >= 0) result[idx++] = nw
            }
            result.sort()   // IntArray.sort() is an in-place primitive sort, no boxing
            result
        }
        val newNames = Array(verts.size) { newId -> names[verts[newId]] }
        return Graph(verts.size, newAdj, newNames) to oldToNew
    }

    /**
     * Edge subdivision: one new vertex per edge, u-v becomes u-w-v. Subdivision
     * vertices are assigned ids [n, n+m) in the order their edge is first seen
     * (each unordered edge visited once, via u < v). This is how a non-bipartite
     * graph is turned bipartite for the SAT/CaDiCaL pipeline (see [ensureBipartite]).
     */
    fun subdivided(): Graph {
        val edges = mutableListOf<Pair<Int, Int>>()
        for (u in 0 until n) for (v in adj[u]) if (u < v) edges.add(u to v)

        val newAdj = Array(n + edges.size) { IntArray(0) }
        val bucket = Array(n + edges.size) { mutableListOf<Int>() }
        edges.forEachIndexed { i, (u, v) ->
            val w = n + i
            bucket[u].add(w); bucket[w].add(u)
            bucket[w].add(v); bucket[v].add(w)
        }
        for (v in bucket.indices) newAdj[v] = bucket[v].sorted().toIntArray()

        val newNames = Array(n + edges.size) { if (it < n) names[it] else "#${it - n}" }
        return Graph(n + edges.size, newAdj, newNames)
    }

    /** Apply a permutation (perm[old] = new). Used ONLY by the soundness harness. */
    fun relabeled(perm: IntArray): Graph {
        val newAdj = arrayOfNulls<IntArray>(n)
        val newNames = arrayOfNulls<String>(n)
        for (old in 0 until n) {
            newAdj[perm[old]] = adj[old].map { perm[it] }.sorted().toIntArray()
            newNames[perm[old]] = names[old]
        }
        @Suppress("UNCHECKED_CAST")
        return Graph(n, newAdj as Array<IntArray>, newNames as Array<String>)
    }

    fun isConnected(): Boolean = n == 0 || connectedComponents().size == 1

    fun connectedComponents(): List<IntArray> {
        val visited = BooleanArray(n)
        val components = mutableListOf<IntArray>()
        for (start in 0 until n) {
            if (visited[start]) continue
            val component = mutableListOf<Int>()
            val queue = ArrayDeque<Int>()
            queue.add(start); visited[start] = true
            while (queue.isNotEmpty()) {
                val v = queue.removeFirst()
                component.add(v)
                for (w in adj[v]) if (!visited[w]) {
                    visited[w] = true; queue.add(w)
                }
            }
            components.add(component.sorted().toIntArray())
        }
        return components
    }

    /**
     * Two-coloring of the graph (per connected component independently), or null
     * if any component is non-bipartite. Used by [dialysis.util.randomRelabel]
     * and by tests that need to preserve bipartiteness under relabeling.
     */
    fun bipartition(): Pair<Set<Int>, Set<Int>>? {
        val side = IntArray(n) { -1 }
        val partA = mutableSetOf<Int>()
        val partB = mutableSetOf<Int>()
        for (start in 0 until n) {
            if (side[start] != -1) continue
            side[start] = 0; partA.add(start)
            val queue = ArrayDeque<Int>()
            queue.add(start)
            while (queue.isNotEmpty()) {
                val v = queue.removeFirst()
                for (w in adj[v]) {
                    if (side[w] == -1) {
                        side[w] = 1 - side[v]
                        (if (side[w] == 0) partA else partB).add(w)
                        queue.add(w)
                    } else if (side[w] == side[v]) {
                        return null
                    }
                }
            }
        }
        return partA to partB
    }

    /** The SAT/CaDiCaL orbit-driving pipeline (see `dialysis.sat`) requires bipartite input --
     *  the conversion is the caller's job, not something the encoders do internally. Most graph
     *  families in this repo already are bipartite; this is a no-op for those, and only
     *  subdivides the (rare) non-bipartite ones. */
    fun ensureBipartite(): Graph = if (bipartition() != null) this else subdivided()
}
