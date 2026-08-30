package dialysis.decomposition

import dialysis.ahu.ColoredAHU
import dialysis.content.Content
import dialysis.graph.Graph
import dialysis.refinement.colorRefine1WL

/**
 * DECOMPOSITION_ORDERING_SPEC.md Parts 3-4: recursive decomposition into pieces, and the position
 * signature built from them. Everything here is a HEURISTIC (see that spec's own opening warning):
 * it changes which query is asked first and which vertex is individualized, never which answers are
 * possible. No step here depends on the decomposition being canonical.
 */
enum class PieceKind { BASE, QUOTIENT }

/** All vertex ids are ORIGINAL graph ids (never the induced subgraph's local ids) -- callers never
 *  need to know [peel] worked internally in local-id space. */
class Piece(
    val vertices: IntArray,
    val kind: PieceKind,
    /** Below are non-null iff [kind] == QUOTIENT. */
    val tree: IntArray? = null,
    val parent: Map<Int, Int>? = null,   // original id -> parent's original id; root is absent as a key
    val depthOf: Map<Int, Int>? = null,  // original id -> BFS depth from this piece's own root
    val orphans: IntArray? = null,
    val root: Int? = null,
)

/**
 * DECOMPOSITION_ORDERING_SPEC.md Part 1.1's DIALYSIS, admitting `v` in `L_{i+1}` iff it has
 * EXACTLY ONE neighbour in `T n L_i` -- already-admitted vertices of the immediately PREVIOUS layer
 * only. Deliberately NOT the same as [dialysis.decomposition.dialysis] (used elsewhere by
 * `initialPhase`), which checks degree within the WHOLE two-layer band `L_i u L_{i+1}` per that
 * function's own doc comment -- the two rules only coincide when there are zero intra-layer edges,
 * guaranteed for a bipartite BFS layering but not for a dense non-bipartite graph. Confirmed
 * directly (not just argued) to matter: on `latin-13` (avg degree 36, admits adjacent vertices
 * share 13 common neighbours -- a strongly-regular graph's constant lambda), EVERY layer-1 vertex
 * has band-degree 14 under the OLD rule (root + 13 layer-1 siblings), so NONE get admitted -- the
 * root's own tree degenerates to size 1. Under THIS rule every layer-1 vertex trivially has exactly
 * one T-neighbour (the root, its only possible layer-0 neighbour by BFS construction), so the whole
 * of layer 1 is admitted unconditionally, as it must be for ANY graph, bipartite or not -- a
 * dialysis tree can never be smaller than 1 + degree(root).
 */
fun dialysisPerSpec(g: Graph, root: Int): Decomposition {
    val n = g.n
    val depth = IntArray(n) { -1 }
    val levels = mutableListOf(mutableListOf(root))
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

    val inTree = BooleanArray(n)
    val parent = IntArray(n) { -1 }
    inTree[root] = true
    for (i in 0 until levels.size - 1) {
        // T n L_i, fixed BEFORE processing L_{i+1} -- admission for a vertex in L_{i+1} only ever
        // looks at the STRICTLY EARLIER layer's already-admitted members, so it can never be
        // confused by an L_{i+1}-to-L_{i+1} edge the way a whole-band degree check can.
        val prevTreeInLayerI = levels[i].filter { inTree[it] }.toHashSet()
        for (v in levels[i + 1]) {
            var count = 0
            var candidate = -1
            for (w in g.adj[v]) if (w in prevTreeInLayerI) { count++; candidate = w }
            if (count == 1) {
                inTree[v] = true
                parent[v] = candidate
            }
        }
    }
    val treeVerts = (0 until n).filter { inTree[it] }.toIntArray()

    val orphans = mutableListOf<Int>()
    val remainder = mutableListOf<Int>()
    for (v in 0 until n) {
        if (inTree[v]) continue
        val hasNonTreeNeighbor = g.adj[v].any { !inTree[it] }
        if (hasNonTreeNeighbor) remainder.add(v) else orphans.add(v)
    }
    val inRemainder = BooleanArray(n)
    for (e in remainder) inRemainder[e] = true

    val connectorEdges = mutableListOf<Pair<Int, Int>>()
    for (v in treeVerts) for (w in g.adj[v]) if (inRemainder[w]) connectorEdges.add(v to w)

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

    return Decomposition(root, treeVerts, parent, depth, orphans.toIntArray(), slices, remainderComps, connectorEdges)
}

/** `internal`, not `private`: MULTIDECOMP_WITNESS_SPEC.md's 4.1 gate reuses this as the fallback
 *  rule for every [peel] recursion level below its own top-level root override -- see
 *  `MultiDecompWitnessGateTest`. */
internal fun defaultRootRule(sub: Graph, restrictedColouring: Array<Content>): Int {
    val bySize = (0 until sub.n).groupBy { restrictedColouring[it] }
    val smallest = bySize.values.minByOrNull { it.size }!!
    return smallest.min()
}

/**
 * Part 3's `PEEL`. [x] is a set of ORIGINAL vertex ids (call initially with `0 until g.n`).
 * [rootRule] operates on the induced subgraph and ITS OWN restricted colouring (local ids) -- see
 * [defaultRootRule] for the spec's suggested default ("least-indexed vertex of the smallest colour
 * class"), which does not need to be invariant since nothing downstream depends on canonicity.
 */
fun peel(
    g: Graph,
    x: IntArray,
    colouring: Array<Content>,
    out: MutableList<Piece>,
    rootRule: (Graph, Array<Content>) -> Int = ::defaultRootRule,
) {
    val (sub, oldToNew) = g.induced(x)
    val newToOld = x // induced()'s new ids follow x's own order, so x[newId] == old id

    val restrictedColouring = Array(sub.n) { localId -> colouring[newToOld[localId]] }
    val isTreeSub = sub.n <= 1 || sub.m == sub.n - 1
    val discrete = colorRefine1WL(sub, restrictedColouring).isDiscrete()

    if (isTreeSub || discrete) {
        out.add(Piece(vertices = x.copyOf(), kind = PieceKind.BASE))
        return
    }

    val localRoot = rootRule(sub, restrictedColouring)
    val dec = dialysisPerSpec(sub, localRoot)

    val originalTree = IntArray(dec.treeVerts.size) { newToOld[dec.treeVerts[it]] }
    val originalParent = HashMap<Int, Int>()
    for (v in dec.treeVerts) {
        val p = dec.parent[v]
        if (p >= 0) originalParent[newToOld[v]] = newToOld[p]
    }
    val originalDepth = HashMap<Int, Int>()
    for (v in dec.treeVerts) originalDepth[newToOld[v]] = dec.depth[v]
    val originalOrphans = IntArray(dec.orphans.size) { newToOld[dec.orphans[it]] }
    val pieceVerticesOriginal = originalTree + originalOrphans

    out.add(
        Piece(
            vertices = pieceVerticesOriginal,
            kind = PieceKind.QUOTIENT,
            tree = originalTree,
            parent = originalParent,
            depthOf = originalDepth,
            orphans = originalOrphans,
            root = newToOld[localRoot],
        ),
    )

    if (dec.remainderComps.isEmpty()) return
    for (comp in dec.remainderComps) {
        val compOriginal = IntArray(comp.size) { newToOld[comp[it]] }
        peel(g, compOriginal, colouring, out, rootRule)
    }
}

/**
 * Part 4.1's cheap, content-addressed piece key: `(|P|, |E(P)|, sorted degree sequence of P, sorted
 * boundary colours)` -- the spec's own sanctioned substitute for a full uncoloured certificate.
 * Degrees/edges are WITHIN the induced subgraph on [pieceVertices]; "boundary" means having a
 * neighbour (in [g]) outside [pieceVertices].
 */
fun pieceKey(g: Graph, pieceVertices: IntArray, colouring: Array<Content>): Content {
    val members = pieceVertices.toHashSet()
    val degrees = IntArray(pieceVertices.size)
    val boundaryColours = mutableListOf<Content>()
    for ((idx, v) in pieceVertices.withIndex()) {
        var inner = 0
        var hasOutside = false
        for (w in g.adj[v]) {
            if (w in members) inner++ else hasOutside = true
        }
        degrees[idx] = inner
        if (hasOutside) boundaryColours.add(colouring[v])
    }
    val edgeCount = degrees.sum() / 2
    degrees.sort()
    boundaryColours.sort()
    return Content.Tup(
        listOf(
            Content.Num(pieceVertices.size.toLong()),
            Content.Num(edgeCount.toLong()),
            Content.MSet(degrees.map { Content.Num(it.toLong()) }),
            Content.MSet(boundaryColours),
        ),
    )
}

/**
 * Builds a plain undirected tree graph from ONLY [parent]'s edges (never from [g]'s own induced
 * edges among [treeVertices]) -- this is what makes the result always genuinely acyclic and safe to
 * feed to [ColoredAHU], regardless of whether [g] has extra edges among these vertices (the spec's
 * "T may contain a cycle on non-bipartite input" warning is about running AHU on the INDUCED
 * subgraph; building strictly from parent pointers sidesteps that by construction, at the cost of a
 * coarser signature that ignores those extra edges -- acceptable since this is only a heuristic).
 */
private fun buildTreeOnlyGraph(treeVertices: IntArray, parent: Map<Int, Int>): Pair<Graph, Map<Int, Int>> {
    val oldToNew = treeVertices.withIndex().associate { (i, v) -> v to i }
    val buckets = Array(treeVertices.size) { mutableListOf<Int>() }
    for (v in treeVertices) {
        val p = parent[v] ?: continue
        val lv = oldToNew.getValue(v)
        val lp = oldToNew.getValue(p)
        buckets[lv].add(lp)
        buckets[lp].add(lv)
    }
    val adj = Array(treeVertices.size) { buckets[it].distinct().sorted().toIntArray() }
    val names = Array(treeVertices.size) { it.toString() }
    return Graph(treeVertices.size, adj, names) to oldToNew
}

/** One vertex's Part 4 position signature. Equality/hashCode are what Part 7 needs (counting
 *  distinct signatures per colour class) -- [ahuOrAttachmentLabel] is a canonical STRING (never a
 *  raw AHU int id), per the spec's own warning against comparing/sorting global-intern-pool ids by
 *  magnitude across different [ColoredAHU.compute] calls; string equality is always safe. */
data class PositionSignature(val pieceKey: Content, val ahuOrAttachmentLabel: String, val depth: Int, val kind: String)

/**
 * Part 4's `POSITION_SIGNATURES`. [pieces] must partition every vertex referenced by [colouring]
 * exactly once (the same assumption the spec's own "assert the pieces partition V" guard rail
 * requires of [peel]'s own output).
 */
fun positionSignatures(g: Graph, pieces: List<Piece>, colouring: Array<Content>): Map<Int, PositionSignature> {
    val sig = HashMap<Int, PositionSignature>()
    for (piece in pieces) {
        val key = pieceKey(g, piece.vertices, colouring)
        when (piece.kind) {
            PieceKind.BASE -> for (v in piece.vertices) sig[v] = PositionSignature(key, "", -1, "BASE")
            PieceKind.QUOTIENT -> {
                val tree = piece.tree!!
                val parent = piece.parent!!
                val root = piece.root!!
                val depthOf = piece.depthOf!!
                val (treeGraph, oldToNewTree) = buildTreeOnlyGraph(tree, parent)
                val uniformColours = tree.associate { oldToNewTree.getValue(it) to "u" }
                val ahu = ColoredAHU.compute(treeGraph, uniformColours, oldToNewTree.getValue(root))
                for (v in tree) {
                    sig[v] = PositionSignature(key, ahu.subtreeCanonicalString(oldToNewTree.getValue(v)), depthOf.getValue(v), "TREE")
                }
                val treeSet = tree.toHashSet()
                for (o in piece.orphans!!) {
                    val attachLabels = g.adj[o].filter { it in treeSet }
                        .map { t -> ahu.subtreeCanonicalString(oldToNewTree.getValue(t)) }
                        .sorted()
                    sig[o] = PositionSignature(key, attachLabels.toString(), -1, "ORPHAN")
                }
            }
        }
    }
    return sig
}
