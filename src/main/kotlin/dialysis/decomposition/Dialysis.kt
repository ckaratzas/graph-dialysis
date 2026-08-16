package dialysis.decomposition

import dialysis.graph.Graph

/**
 * Dialysis decomposition D(G, r).
 * Vertex partition: V = V(T) + Orphans + V(R).
 */
class Decomposition(
    val root: Int,
    val treeVerts: IntArray,            // V(T)
    val parent: IntArray,               // parent[v] in T, -1 for root / non-tree
    val depth: IntArray,                // BFS depth for ALL vertices (slices are per-level!)
    val orphans: IntArray,              // isolated vertices of G - V(T)
    val slices: List<Slice>,            // see [Slice] below
    val remainderComps: List<IntArray>, // connected components of R(r)
    val connectorEdges: List<Pair<Int, Int>> // (tree, remainder) — used ONLY for interface colorings
)

/**
 * Slice: at each BFS level, form the bipartite graph between the orphans of that level and
 * the tree vertices they attach to; a slice is each CONNECTED COMPONENT of it.
 * Private attachments give disconnected stars; shared attachments give composite slices of
 * arbitrary bipartite structure. Orphans of different levels never share a slice (attachments
 * are level-adjacent).
 */
class Slice(
    val level: Int,
    val orphans: IntArray,
    val attachments: IntArray           // tree-side vertices of this component
) {
    val isStar: Boolean get() = orphans.size == 1
}

/**
 * O(n+m), deterministic, isomorphism-equivariant.
 * Tree rule: v in L_{i+1} joins T iff deg(v)=1 in G[L_i + L_{i+1}] and its unique
 * neighbor is already in T. Then orphans = isolated vertices of G - V(T).
 */
fun dialysis(g: Graph, root: Int): Decomposition {
    val n = g.n

    // ---- BFS levels + depth for ALL reachable vertices ----
    val depth = IntArray(n) { -1 }
    val levels = mutableListOf<MutableList<Int>>(mutableListOf(root))
    depth[root] = 0
    run {
        val queue = ArrayDeque<Int>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            for (w in g.adj[v]) {
                if (depth[w] == -1) {
                    depth[w] = depth[v] + 1
                    if (levels.size <= depth[w]) levels.add(mutableListOf())
                    levels[depth[w]].add(w)
                    queue.add(w)
                }
            }
        }
    }

    // ---- Tree rule ----
    val inTree = BooleanArray(n)
    val parent = IntArray(n) { -1 }
    inTree[root] = true
    for (i in 0 until levels.size - 1) {
        val band = (levels[i].asSequence() + levels[i + 1].asSequence()).toHashSet()
        for (v in levels[i + 1]) {
            val bandNeighbors = g.adj[v].filter { it in band }
            if (bandNeighbors.size != 1) continue
            val candidate = bandNeighbors[0]
            if (inTree[candidate]) {
                inTree[v] = true
                parent[v] = candidate
            }
        }
    }
    val treeVerts = (0 until n).filter { inTree[it] }.toIntArray()

    // ---- Orphans / remainder classification of V \ T ----
    val orphans = mutableListOf<Int>()
    val remainder = mutableListOf<Int>()
    for (v in 0 until n) {
        if (inTree[v]) continue
        val hasNonTreeNeighbor = g.adj[v].any { !inTree[it] }
        if (hasNonTreeNeighbor) remainder.add(v) else orphans.add(v)
    }
    val inRemainder = BooleanArray(n)
    for (element in remainder) {
        inRemainder[element] = true
    }

    // ---- Connector edges: tree -> remainder ----
    val connectorEdges = mutableListOf<Pair<Int, Int>>()
    for (v in treeVerts) for (w in g.adj[v]) if (inRemainder[w]) connectorEdges.add(v to w)

    // ---- Remainder components: connected components of G[remainder] ----
    val remainderComps = run {
        val visited = BooleanArray(n)
        val comps = mutableListOf<IntArray>()
        for (start in remainder) {
            if (visited[start]) continue
            val comp = mutableListOf<Int>()
            val queue = ArrayDeque<Int>()
            queue.add(start); visited[start] = true
            while (queue.isNotEmpty()) {
                val v = queue.removeFirst()
                comp.add(v)
                for (w in g.adj[v]) if (inRemainder[w] && !visited[w]) { visited[w] = true; queue.add(w) }
            }
            comps.add(comp.sorted().toIntArray())
        }
        comps
    }

    // ---- Slices: per level, connected components of the bipartite
    // orphan<->attachment graph. Orphans have no orphan-orphan edges (they are
    // isolated in G - V(T)), so components are found by alternating BFS through
    // shared tree attachments. ----
    val orphansByLevel = orphans.groupBy { depth[it] }
    val slices = mutableListOf<Slice>()
    for (level in orphansByLevel.keys.sorted()) {
        val levelOrphans = orphansByLevel.getValue(level)
        val attachmentToOrphans = HashMap<Int, MutableList<Int>>()
        for (o in levelOrphans) for (att in g.adj[o]) attachmentToOrphans.getOrPut(att) { mutableListOf() }.add(o)

        val visited = HashSet<Int>()
        for (o in levelOrphans) {
            if (o in visited) continue
            val compOrphans = mutableListOf<Int>()
            val compAttachments = HashSet<Int>()
            val queue = ArrayDeque<Int>()
            queue.add(o); visited.add(o)
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                compOrphans.add(cur)
                for (att in g.adj[cur]) {
                    if (compAttachments.add(att)) {
                        for (sibling in attachmentToOrphans[att].orEmpty()) {
                            if (visited.add(sibling)) queue.add(sibling)
                        }
                    }
                }
            }
            slices.add(Slice(level, compOrphans.sorted().toIntArray(), compAttachments.sorted().toIntArray()))
        }
    }

    return Decomposition(
        root = root,
        treeVerts = treeVerts,
        parent = parent,
        depth = depth,
        orphans = orphans.toIntArray(),
        slices = slices,
        remainderComps = remainderComps,
        connectorEdges = connectorEdges,
    )
}
