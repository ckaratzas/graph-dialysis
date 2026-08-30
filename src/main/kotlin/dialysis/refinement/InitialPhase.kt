package dialysis.refinement

import dialysis.ahu.ColoredAHU
import dialysis.content.Content
import dialysis.content.serialize
import dialysis.decomposition.DecompositionStore
import dialysis.decomposition.dialysis
import dialysis.graph.Graph
import dialysis.util.boundedPool

/** A single-colour initial coloring — the standard starting point for [initialPhase]/
 *  [colorRefine1WL] when no external invariant colouring is available. */
fun uniformSeed(n: Int): Array<Content> = Array(n) { Content.Str("u") }

/**
 * The initial phase: a colour refinement stronger than plain 1-WL, used everywhere a stable
 * partition is needed. It is a discriminating heuristic, not a canonical or complete procedure —
 * only isomorphism-invariance is required of it; a weaker or stronger refinement here changes
 * discriminating power and speed, never correctness of anything built on top of it.
 *
 * Three phases beyond the 1-WL baseline, each anchoring colour classes by an invariant key and
 * re-propagating:
 *  - Phase 1: tree/orphan anchoring — key(v) = (AHU(T(v)), orphan count of v).
 *  - Phase 2: remainder anchoring, incremental — key(v) = the sorted multiset, over v's
 *    remainder's own connected components, of each component's own 1-WL stable colouring (seeded
 *    by whatever colours earlier classes in this same sweep have already produced).
 *  - Phase 3: final propagation, only if Phase 2 introduced a new colour.
 *
 * [runPhases23] (default true) gates Phase 2/3 off entirely: false returns right after Phase 1's
 * own 1-WL propagation, whether or not that made the partition discrete — useful for measuring
 * how much of the initial phase's discriminating power comes from tree/orphan anchoring alone,
 * isolated from the more expensive remainder-component work.
 *
 * [precomputedStore], if given, is used INSTEAD OF building a fresh [DecompositionStore] -- valid
 * because `dialysis(g, v)` (what the store actually holds) is a pure function of the graph alone,
 * never of [chi]: a caller that invokes this repeatedly on the SAME [g] with only [chi] varying
 * (e.g. FINAL_MEASUREMENTS_SPEC.md Task 2's per-query filter, individualizing a different vertex
 * each call) can build ONE store up front (`DecompositionStore.build(g)`, no `needed` mask so every
 * vertex is covered regardless of which query's `needsWork` would have asked for it) and reuse it
 * across every call instead of repeating a graph-wide decomposition per call. Measured as the actual
 * cause of a `latin-20` (n=400) per-query run exhausting memory: up to 400 redundant whole-graph
 * decompositions (one per individualized vertex) where exactly one was needed.
 */
fun initialPhase(g: Graph, chi: Array<Content>, runPhases23: Boolean = true, precomputedStore: DecompositionStore? = null): StablePartition {
    // Phase 0: baseline.
    val p0 = colorRefine1WL(g, chi)
    if (p0.isDiscrete()) return p0

    // Anchoring is a no-op on an already-singleton cell -- vertices already unique after Phase 0
    // stay that way through every later phase, so skip the per-vertex decomposition/AHU/remainder
    // work for them entirely.
    val needsWork = BooleanArray(g.n)
    for (cell in p0.cells) if (cell.size > 1) for (v in cell) needsWork[v] = true

    // One decomposition per vertex that still needs it, computed once and kept off-heap; Phase 1
    // and Phase 2 both read from it below, and a later caller that needs dialysis(g, r) for some
    // root r can fetch an already-computed decomposition through the partition's
    // [StablePartition.decompositionOf] instead of recomputing it. A [precomputedStore] must cover
    // every vertex [needsWork] could ask for -- true whenever it was itself built with no mask,
    // checked here (not just documented) so a future caller passing a partially-masked store fails
    // loudly with a clear message instead of silently reading a zero-length record later.
    if (precomputedStore != null) {
        require(precomputedStore.coversEveryVertex) {
            "precomputedStore must cover every vertex (built with a null/all-true `needed` mask) -- " +
                "this call's own needsWork mask can differ from whatever built the store"
        }
    }
    val store = precomputedStore ?: DecompositionStore.build(g, needsWork)

    // Phase 1: tree/orphan anchoring.
    val treeOrphanKey = arrayOfNulls<Content>(g.n)
    for (v in 0 until g.n) {
        if (!needsWork[v]) continue
        val (treeVerts, orphanCount) = store.treeAndOrphanCount(v)
        val (treeGraph, treeOldToNew) = g.induced(treeVerts)
        val ahuColors = treeVerts.associate { origId -> treeOldToNew[origId] to chi[origId].serialize() }
        val ahuResult = ColoredAHU.compute(treeGraph, ahuColors, root = treeOldToNew[v])
        // The AHU root id's raw magnitude is intern-pool-order-dependent (not a portable, canonical
        // value on its own), but it's used here only as an ANCHORING KEY within one process's own
        // run, where Phase 2's incremental ordering is deliberately sensitive to exactly this
        // key's tie-break behaviour -- measured directly: swapping it for a canonical-string token
        // changes anchor()'s tie-break order, which changes Phase 2's incremental result (fewer,
        // not more, discriminated classes on at least one measured instance). Any future fix needs
        // to preserve Phase 2's processing order, not just swap this representation.
        treeOrphanKey[v] = Content.Tup(listOf(Content.Num(ahuResult.rootId.toLong()), Content.Num(orphanCount.toLong())))
    }
    val chi1 = p0.color.copyOf()
    // Safe to force-unwrap: anchor() only evaluates `key(v)` for cells of size > 1, and every such
    // vertex has needsWork[v] == true (that's exactly how needsWork was built).
    for (cell in p0.cells) anchor(chi1, cell) { v -> treeOrphanKey[v]!!.serialize() }
    val p1 = colorRefine1WL(g, chi1)
    if (p1.isDiscrete() || !runPhases23) return p1.withDecompositions(g, store)

    // Phase 2: remainder anchoring, incremental -- classes in canonical order, each reading
    // whatever chi2 has become by the time it's its turn.
    val chi2 = p1.color.copyOf()
    var refined = false
    for (cell in p1.cells) {
        if (cell.size <= 1) continue   // anchor() is a no-op here too; skip building keys nobody needs
        // For each remainder component: seed 1-WL WITH chi2 (restricted to the component) and let
        // it refine from there, rather than running 1-WL unseeded first and mapping chi2 on
        // afterward -- measured more discriminating on the corpus this was tuned against.
        // Returns the SERIALIZED key, not the live Content tree -- see anchor()'s own doc for why
        // that's both correct (Content's own invariants guarantee string equality/order match
        // structural equality/order exactly) and the actual fix here: the live tree this builds is
        // transient (this function's own stack frame), collectible right after serialize() runs,
        // instead of retained for every member of the cell simultaneously via precomputedKeys below
        // -- confirmed as the fix for the 15MB->5663MB blowup this replaced (measured on sts-51).
        fun remainderKeyOf(v: Int): String {
            val components = store.remainderComponents(v).map { comp ->
                // inducedGraphOnly, not induced: this call's old->new map would be discarded
                // immediately below, and induced()'s O(n) map allocation/init was measured as the
                // dominant per-call cost here (thousands of these calls per instance).
                val compGraph = g.inducedGraphOnly(comp)
                val compChi = Array(compGraph.n) { localId -> chi2[comp[localId]] }
                Content.MSet(colorRefine1WLColors(compGraph, compChi).toList())
            }
            return Content.MSet(components).serialize()
        }
        // Every v in this cell reads the SAME (pre-anchor) chi2 snapshot -- none of these calls
        // mutate chi2 or depend on each other, only on the read-only DecompositionStore and plain
        // colorRefine1WLColors -- safe to compute concurrently. anchor() itself (the actual chi2
        // mutation) still runs afterward, sequentially; only the per-vertex key computation is
        // parallelized here, up to boundedPool's cap.
        val precomputedKeys = boundedPool.submit(java.util.concurrent.Callable {
            cell.toList().parallelStream()
                .map { v -> v to remainderKeyOf(v) }
                .collect(java.util.stream.Collectors.toMap({ it.first }, { it.second }))
        }).get()
        if (anchor(chi2, cell) { v -> precomputedKeys.getValue(v) } > 0) refined = true
    }
    if (!refined) return p1.withDecompositions(g, store)
    // Phase 3: final propagation.
    return colorRefine1WL(g, chi2).withDecompositions(g, store)
}

/** Attaches [store] as the partition's decomposition lookup (see
 *  [StablePartition.decompositionOf]). Falls back to computing dialysis(g, v) fresh for a vertex
 *  the store's `needsWork` mask skipped -- an already-singleton-at-Phase-0 vertex can still end up
 *  as a chosen root later, so "not stored" must mean "compute now", never "unreachable". */
private fun StablePartition.withDecompositions(g: Graph, store: DecompositionStore): StablePartition =
    StablePartition(color, cells, decompositionOf = { v -> if (store.has(v)) store.get(v) else dialysis(g, v) })

/**
 * Anchoring: groups [cls] (all sharing one colour) by [key], orders the groups by the canonical
 * order of their keys, leaves the first group's colour unchanged, and assigns freshly derived
 * colours to the rest. Equivariant: the groups and their canonical order are determined by
 * invariant keys, so corresponding classes of isomorphic inputs split identically. Returns the
 * number of new colours created.
 *
 * New colours are `Content.Tup(originalColor, groupRank)` rather than a literal global integer
 * counter: distinctness and canonical order both fall out of [Content]'s own total order
 * (different original colours never collide; within one class, group rank breaks the tie).
 */
/**
 * [key] returns [Content.serialize]'s STRING form, not a live [Content] -- grouping/sorting only
 * ever needs a comparable, equality-preserving proxy for the key, never the emitted colour (which
 * is built below from [originalColor]/[rank] alone, independent of the key's own representation).
 * Correctness of using the string instead of the live tree follows directly from two of
 * [Content]'s own documented invariants: equal values MUST serialize identically (so string
 * equality is exactly [Content] equality here), and "total order on Content = order on
 * serializations... holds everywhere" (so [String]'s natural ordering below matches [Content]'s
 * own structural order). This matters because a caller's [key] can be expensive to keep alive as a
 * live object tree -- see [dialysis.refinement.initialPhase]'s Phase 2, whose per-vertex remainder
 * key is a `Content.MSet` potentially as large as the vertex's own remainder component, retained
 * for every member of a cell simultaneously (confirmed directly: one `sts-51` cell of 12444
 * members drove heap from 15MB to 5663MB building that map of live [Content] trees before this
 * fix) -- a flat string has far less JVM per-node overhead than the equivalent tree of `Content.Tup`
 * /`Content.MSet`/`Content.Num` wrapper objects (each carrying its own object header plus a
 * [dialysis.content.SerializeCache] instance), and the LIVE tree itself only needs to exist
 * transiently per key computation (long enough to call [Content.serialize] once), not for the
 * whole cell's duration -- unlike going through `serialize()` for [Content.MSet]'s OWN
 * canonicalization/hashing (a documented, DIFFERENT past OOM bug, see that class's doc), there is
 * no cross-call structural SHARING here to lose: each vertex's key tree is built fresh,
 * independent of every other vertex's, so flattening it costs nothing semantically.
 */
private fun anchor(chi: Array<Content>, cls: IntArray, key: (Int) -> String): Int {
    if (cls.size <= 1) return 0   // a singleton class can never be split further -- skip `key` entirely
    val groups = cls.groupBy(key).entries.sortedBy { it.key }
    if (groups.size <= 1) return 0
    val originalColor = chi[cls[0]]
    groups.forEachIndexed { rank, (_, members) ->
        if (rank == 0) return@forEachIndexed
        val newColor = Content.Tup(listOf(originalColor, Content.Num(rank.toLong())))
        for (v in members) chi[v] = newColor
    }
    return groups.size - 1
}