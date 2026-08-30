package dialysis.experimental

import dialysis.content.Content
import dialysis.decomposition.Piece
import dialysis.decomposition.PieceKind
import dialysis.decomposition.PositionSignature
import dialysis.decomposition.defaultRootRule
import dialysis.decomposition.peel
import dialysis.decomposition.positionSignatures
import dialysis.graph.Graph
import dialysis.sat.SatQueryResult
import dialysis.sat.SeparatingUnionFind
import dialysis.sat.verifyAutomorphism
import dialysis.sat.cadical.buildCadicalEncoding
import dialysis.sat.cadical.queryOrbitMateCadical

/**
 * MULTIDECOMP_WITNESS_SPEC.md Section 3: multi-decomposition witness hunting.
 *
 * DO NOT MERGE INTO THE MAIN CERTIFICATION PATH (Section 8's own instruction) -- this is a witness
 * GENERATOR, not a decision procedure: the decomposition signature `sig` is not an isomorphism
 * invariant (it depends on an arbitrary root), so an UNSAT under it proves only "no automorphism
 * preserving THIS decomposition's colouring", never "no automorphism". Every SAT witness is decoded
 * and verified against the real graph in O(m) before being trusted -- once verified, it IS a genuine
 * automorphism regardless of how the formula was filtered, so unioning on it is sound
 * unconditionally. UNSAT and UNKNOWN under `sig` are always discarded: never recorded as a
 * separation, never used to mark a pair resolved (Section 2's rule, restated here because Section 2
 * says a future reader will be tempted to record the UNSAT results, and doing so silently corrupts
 * the output).
 */

/** 3.2's ROOT_SCHEDULE: r_1 = an arbitrary member of C (the first, for reproducibility);
 *  r_{t+1} = the member of C maximising its minimum graph distance to every root chosen so far.
 *  An unreachable vertex is treated as maximally far, matching farthest-first's own intent. */
fun rootSchedule(g: Graph, classC: List<Int>, kMax: Int): List<Int> {
    fun bfsDist(root: Int): IntArray {
        val dist = IntArray(g.n) { -1 }
        dist[root] = 0
        val queue = ArrayDeque<Int>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            for (w in g.adj[v]) if (dist[w] == -1) { dist[w] = dist[v] + 1; queue.add(w) }
        }
        return dist
    }
    if (classC.isEmpty()) return emptyList()
    val chosen = mutableListOf(classC[0])
    val dists = mutableListOf(bfsDist(chosen[0]))
    while (chosen.size < kMax && chosen.size < classC.size) {
        var best = -1
        var bestMinDist = -1
        for (v in classC) {
            if (v in chosen) continue
            val minDist = dists.minOf { d -> if (d[v] < 0) Int.MAX_VALUE else d[v] }
            if (minDist > bestMinDist) { bestMinDist = minDist; best = v }
        }
        if (best < 0) break
        chosen.add(best)
        dists.add(bfsDist(best))
    }
    return chosen
}

/**
 * The graph [peel]/[dialysisPerSpec] should actually run on -- same rationale
 * [dialysis.refinement.dispatchColouring] already uses for its own base colouring, and the SAME fix
 * applies here for the SAME reason: on a small-diameter, dense non-bipartite graph (`ag2-16`: avg
 * degree ~16.5, a strongly-regular-like structure), BFS from ANY root reaches almost the whole
 * graph within 1-2 layers, so [dialysisPerSpec]'s tree/layer structure -- and hence the AHU/depth
 * signature built from it -- has almost nothing to discriminate on. That is the actual explanation
 * for 4.1's `ag2-16` failure (distinctSigsInC=2-3, ~95-99% dominance): the decomposition was being
 * run on the dense ORIGINAL graph. Subdividing every edge DOUBLES every distance, giving BFS
 * layering (and therefore the signature) real structure to work with, exactly as
 * [dispatchColouring]'s own doc argues for the base colouring. [Graph.subdivided]'s original `n`
 * vertices keep their own ids unchanged, so no id-translation is needed for anything keyed by an
 * original vertex -- only the extra edge-vertices (ids `>= g.n`) are new, and no caller here ever
 * looks one of those up (every lookup is for a vertex of [classC], which is always `< g.n`).
 * Unconditional, deliberately NOT gated on [Graph.bipartition] the way [dispatchColouring] gates
 * its own subdivision decision: that gate is justified by a DIFFERENT fact entirely (Pi(g) is never
 * coarser than 1-WL(g) on a bipartite graph, a colour-REFINEMENT-strength argument -- see
 * [dispatchColouring]'s own doc), which says nothing about whether [dialysisPerSpec]'s BFS layering
 * benefits from doubled distances. `ag2-16` IS bipartite yet still small-diameter and dense, so it
 * has exactly the pathology this fix targets regardless -- confirmed directly: reusing
 * [dispatchColouring]'s bipartite condition here left `ag2-16` completely untouched (still
 * `[255, 1]`-style splits) because that condition just skips it, which is why the first version of
 * this fix did not actually resolve the case it was written for.
 */
fun decompositionGraphFor(g: Graph): Graph = g.subdivided()

private fun signatureContent(s: PositionSignature): Content =
    Content.Tup(listOf(s.pieceKey, Content.Str(s.ahuOrAttachmentLabel), Content.Num(s.depth.toLong()), Content.Str(s.kind)))

/**
 * DECOMPOSE(G, r) (Section 3/3.1), made GENUINELY recursive "until amenable bases" -- [peel] on its
 * own is not: it recurses on a piece's REMAINDER (by design, DECOMPOSITION_ORDERING_SPEC.md line
 * 115's own termination argument), but treats the QUOTIENT piece it extracts -- the dialysis tree
 * itself -- as terminal no matter how large or how little its one AHU/depth signature actually
 * discriminates. Confirmed directly to matter on `ag2-16`: the FIRST decomposition's tree alone
 * swallowed 273 of 528 original vertices (89.8% of the whole subdivided graph) and [peel] never
 * looked at it again -- that single frozen tree, not diameter collapse (see [decompositionGraphFor]
 * -- subdividing alone did not change `ag2-16`'s numbers at all), is what produced 4.1's 2-3
 * distinct signatures over a 256-272 member class.
 *
 * The fix: after one [peel] call, every BASE piece is final. Every QUOTIENT piece is instead
 * re-peeled on its OWN vertex set, with the signature just computed folded into the colouring the
 * next call sees -- exactly the same shrink-and-recurse structure [peel] already uses for remainder
 * components, just extended to the tree [peel] itself stops at. [root] is forced only for the very
 * first, top-level call (Section 3.2's chosen root); every split below it -- both [peel]'s own
 * remainder recursion AND this function's tree recursion -- uses [defaultRootRule].
 *
 * CHECKED, not assumed, that this recursion does something: on `ag2-16` it does not, and the reason
 * is mechanical, not a bug. `peel`'s `isTreeSub = sub.n <= 1 || sub.m == sub.n - 1` check fires
 * BEFORE the colouring is ever consulted -- re-peeling `ag2-16`'s 4385-vertex extracted tree (with
 * the enriched, signature-carrying colouring from the first pass) returns exactly one BASE piece
 * covering all 4385 vertices unchanged, because that vertex set genuinely induces a tree (by
 * construction of dialysis extraction: `m == n-1`), so `isTreeSub` is true regardless of how much
 * the colouring already discriminates. There is nothing further this framework can split a tree
 * into -- its own colored-AHU labelling (already computed, already used) IS the finest signature
 * PEEL applies to a tree. That it still only produced 2-3 distinct classes over 250+ vertices is
 * therefore a genuine fact about `ag2-16`: the dialysis tree rooted at any of the 8 tried vertices
 * is itself deeply symmetric, not an artifact of stopping recursion too early.
 *
 * Returns a [Content] per vertex of [g] (`< g.n`), never a [PositionSignature] directly, since after
 * more than one level there is no single flat signature left to return -- only the composed key two
 * vertices must match on to be admissible together.
 */
fun decomposeWithRoot(g: Graph, dg: Graph, root: Int, colouring: Array<Content>, maxDepth: Int = 12): Map<Int, Content> {
    val finalContent = HashMap<Int, Content>()

    fun recurse(verts: IntArray, forcedRoot: Int?, dgColouring: Array<Content>, depth: Int) {
        if (depth >= maxDepth) {
            for (v in verts) if (v < g.n) finalContent[v] = dgColouring[v]
            return
        }
        val pieces = mutableListOf<Piece>()
        val rootRule: (Graph, Array<Content>) -> Int = { sub, restricted ->
            if (forcedRoot != null && sub.n == verts.size) forcedRoot else defaultRootRule(sub, restricted)
        }
        peel(dg, verts, dgColouring, pieces, rootRule)
        val sig = positionSignatures(dg, pieces, dgColouring)

        for (piece in pieces) {
            if (piece.kind == PieceKind.BASE) {
                for (v in piece.vertices) if (v < g.n) finalContent[v] = Content.Tup(listOf(dgColouring[v], signatureContent(sig.getValue(v))))
                continue
            }
            // QUOTIENT: not amenable yet -- fold this level's signature into the colouring and
            // re-peel the SAME vertex set (the tree [peel] itself would otherwise freeze). Strictly
            // shrinks or terminates: either this recursion hits BASE (a smaller induced subgraph
            // that is now tree-shaped or 1-WL-discrete under the enriched colouring), or it doesn't
            // and [maxDepth] bounds it -- never infinite, since [peel] never returns a QUOTIENT piece
            // covering MORE vertices than it was given.
            val enriched = dgColouring.copyOf()
            for (v in piece.vertices) enriched[v] = Content.Tup(listOf(dgColouring[v], signatureContent(sig.getValue(v))))
            recurse(piece.vertices, null, enriched, depth + 1)
        }
    }

    val dgColouring = Array<Content>(dg.n) { v -> if (v < g.n) colouring[v] else Content.Str("SUBDIV_EDGE") }
    recurse(IntArray(dg.n) { it }, root, dgColouring, 0)
    return finalContent
}

class WitnessHuntStepReport(
    val root: Int,
    val distinctSigsInC: Int,
    val queriesIssued: Int,
    val sat: Int,
    val unsatDiscarded: Int,
    val unknownDiscarded: Int,
    val witnessesVerified: Int,
    val componentsRemainingAfter: Int,
    /** True iff this round's `(colour, sig)` formula was skipped WITHOUT ever calling
     *  [buildCadicalEncoding] because [estimateGlobalEncodingSize] flagged it too large --
     *  see [MAX_COMBINED_EDGE_CLAUSES]'s own doc for why this check exists at all. */
    val skippedTooLarge: Boolean = false,
)

/**
 * Safety gate before EVERY [buildCadicalEncoding] call in this file -- `sig` is not guaranteed to
 * discriminate a colour class at all (see [decomposeWithRoot]'s own finding: on `ag2-16`, `sig`
 * leaves a 255-member group essentially intact). Without this check, [witnessHunt] and
 * [witnessHuntWithClearing] would walk straight into the EXACT SAME O(k^3) bijection-clause
 * catastrophe `BenchmarkRunner`'s own `globalTooBig` gate exists to prevent (see
 * `CadicalEncoder.kt`'s `bijectionClauses` doc and tonight's whole campaign-crash investigation) --
 * confirmed directly: the first version of this file's `witnessHuntWithClearing`, run on `ag2-16`
 * with no such check, was SIGKILLed by the OS within 49 seconds. `2_000_000` matches
 * `BenchmarkRunner`'s own `--edgeClauseThreshold` default -- not re-derived, reused on purpose so
 * this experimental path is at least as conservative as the validated production one.
 */
const val MAX_COMBINED_EDGE_CLAUSES = 2_000_000L

private fun tooLargeToEncode(g: Graph, colorOf: (Int) -> Content): Boolean {
    val est = dialysis.sat.cadical.estimateGlobalEncodingSize(g, colorOf)
    return est.edgeConflictClauses > MAX_COMBINED_EDGE_CLAUSES || est.bijectionClauses > MAX_COMBINED_EDGE_CLAUSES
}

class WitnessHuntResult(
    val rootsUsed: List<Int>,
    val steps: List<WitnessHuntStepReport>,
    val finalComponentsRemaining: Int,
    val totalQueries: Int,
    val totalTimeMs: Long,
)

/**
 * Section 3's WITNESS_HUNT(G, C, k_max). [globalColorOf] is the graph-wide invariant colouring `C`
 * was drawn from; the formula built each round admits `x_ij` only if BOTH `globalColorOf(i) ==
 * globalColorOf(j)` AND `sig(i) == sig(j)` (Section 3.1), via one combined [Content] key fed
 * straight into the existing [buildCadicalEncoding] -- no new encoder needed.
 */
fun witnessHunt(
    g: Graph,
    classC: List<Int>,
    globalColorOf: (Int) -> Content,
    kMax: Int,
    perQueryTimeoutMs: Long = 10_000L,
): WitnessHuntResult {
    val uf = SeparatingUnionFind(g.n)
    val roots = rootSchedule(g, classC, kMax)
    val dg = decompositionGraphFor(g)
    val steps = mutableListOf<WitnessHuntStepReport>()
    val t0 = System.currentTimeMillis()
    var totalQueries = 0

    for (root in roots) {
        val sig = decomposeWithRoot(g, dg, root, Array(g.n) { globalColorOf(it) })
        val combinedColorOf = { v: Int -> Content.Tup(listOf(globalColorOf(v), sig.getValue(v))) }

        if (tooLargeToEncode(g, combinedColorOf)) {
            val distinctSigs = classC.groupBy { sig.getValue(it) }.size
            val remaining = classC.map { uf.find(it) }.toHashSet().size
            steps.add(WitnessHuntStepReport(root, distinctSigs, 0, 0, 0, 0, 0, remaining, skippedTooLarge = true))
            continue
        }

        val (solver, encoding) = buildCadicalEncoding(g, combinedColorOf)
        var issued = 0
        var sat = 0
        var unsat = 0
        var unknown = 0
        var verified = 0
        try {
            val groups = classC.groupBy { sig.getValue(it) }.values
            for (group in groups) {
                if (group.size <= 1) continue
                val u = group[0]
                for (v in group.drop(1)) {
                    if (uf.find(u) == uf.find(v)) continue
                    issued++
                    totalQueries++
                    when (val r = queryOrbitMateCadical(solver, encoding, u, v, perQueryTimeoutMs)) {
                        is SatQueryResult.Sat -> {
                            sat++
                            check(verifyAutomorphism(g, r.alpha)) {
                                "witness for ($u,$v) at root=$root failed O(m) verification -- " +
                                    "this is the one error class this pipeline cannot recover from, aborting"
                            }
                            verified++
                            for (w in 0 until g.n) uf.union(w, r.alpha[w])
                        }
                        // Section 2/8: UNSAT and UNKNOWN under `sig` are discarded unconditionally --
                        // never recorded, never fed to a separation structure, never treated as
                        // resolving this pair.
                        SatQueryResult.Unsat -> unsat++
                        SatQueryResult.Unknown -> unknown++
                    }
                }
            }
        } finally {
            solver.close()
        }
        val remaining = classC.map { uf.find(it) }.toHashSet().size
        val distinctSigs = classC.groupBy { sig.getValue(it) }.size
        steps.add(WitnessHuntStepReport(root, distinctSigs, issued, sat, unsat, unknown, verified, remaining))
        if (remaining == 1) break
    }

    return WitnessHuntResult(
        rootsUsed = roots,
        steps = steps,
        finalComponentsRemaining = steps.last().componentsRemainingAfter,
        totalQueries = totalQueries,
        totalTimeMs = System.currentTimeMillis() - t0,
    )
}

/** Quotient of [g] by the CURRENT state of [uf]: one vertex per union-find component, an edge
 *  between two quotient vertices iff ANY pair of their members is adjacent in [g]. Returns the
 *  quotient graph plus a map from ORIGINAL vertex id -> quotient vertex id. Self-loops from
 *  intra-component edges are dropped ([Graph] has no notion of them, and BFS/dialysis has no use
 *  for one); multi-edges collapse to one (a plain [Graph] is a simple graph). */
fun quotientGraph(g: Graph, uf: SeparatingUnionFind): Pair<Graph, IntArray> {
    val root = IntArray(g.n) { uf.find(it) }
    val distinctRoots = root.toHashSet().sorted()
    val rootToQid = HashMap<Int, Int>()
    for ((i, r) in distinctRoots.withIndex()) rootToQid[r] = i
    val quotientOf = IntArray(g.n) { rootToQid.getValue(root[it]) }
    val qn = distinctRoots.size
    val adjSets = Array(qn) { HashSet<Int>() }
    for (v in 0 until g.n) {
        val qv = quotientOf[v]
        for (w in g.adj[v]) {
            val qw = quotientOf[w]
            if (qv != qw) { adjSets[qv].add(qw); adjSets[qw].add(qv) }
        }
    }
    val adj = Array(qn) { adjSets[it].sorted().toIntArray() }
    val names = Array(qn) { it.toString() }
    return Graph(qn, adj, names) to quotientOf
}

/**
 * [witnessHunt], but "clearing" already-proven orbits between rounds by QUOTIENTING [g] on the
 * current union-find state before each round's decomposition, instead of re-decomposing the same
 * static [g] with only the root varied. This is a strictly stronger source of diversity than root
 * variation alone: [decomposeWithRoot]'s own doc shows a case (`ag2-16`) where a dialysis tree's
 * colored-AHU signature is genuinely uniform regardless of which root is tried or how deep the
 * recursion goes, on the STATIC graph -- but contracting every already-known orbit into one vertex
 * changes degrees and BFS distances outright, handing the decomposition a graph that is
 * STRUCTURALLY different, not just differently rooted, which a fixed-graph strategy can never
 * produce no matter how many roots it tries.
 *
 * Every SAT witness is still decoded and verified against the REAL, uncontracted [g] in O(m) before
 * any union -- the quotient is used ONLY to compute `sig` (a cheaper, differently-shaped filter);
 * the formula built and solved (`buildCadicalEncoding`) and the verification
 * (`verifyAutomorphism`) are always against [g] itself, so soundness is identical to [witnessHunt]:
 * UNSAT/UNKNOWN under `sig` are still discarded unconditionally, never recorded, per Section 2/8.
 * Automorphisms preserve colour, and every non-trivial union here came from one already-verified
 * automorphism, so every member of one quotient component is guaranteed the same [globalColorOf]
 * value -- reading any single representative's colour for the whole quotient vertex is exact, not
 * an approximation.
 */
fun witnessHuntWithClearing(
    g: Graph,
    classC: List<Int>,
    globalColorOf: (Int) -> Content,
    kMax: Int,
    perQueryTimeoutMs: Long = 10_000L,
): WitnessHuntResult {
    val uf = SeparatingUnionFind(g.n)
    val roots = rootSchedule(g, classC, kMax)
    val steps = mutableListOf<WitnessHuntStepReport>()
    val t0 = System.currentTimeMillis()
    var totalQueries = 0

    for (root in roots) {
        if (classC.map { uf.find(it) }.toHashSet().size == 1) break

        val (qg, quotientOf) = quotientGraph(g, uf)
        val originalOf = IntArray(qg.n) { -1 }
        for (v in 0 until g.n) if (originalOf[quotientOf[v]] < 0) originalOf[quotientOf[v]] = v
        val qColouring = Array(qg.n) { qv -> globalColorOf(originalOf[qv]) }
        val quotientRoot = quotientOf[root]

        val dqg = decompositionGraphFor(qg)
        val sigQ = decomposeWithRoot(qg, dqg, quotientRoot, qColouring)
        val liftedSig = { v: Int -> sigQ.getValue(quotientOf[v]) }
        val combinedColorOf = { v: Int -> Content.Tup(listOf(globalColorOf(v), liftedSig(v))) }

        if (tooLargeToEncode(g, combinedColorOf)) {
            val distinctSigs = classC.groupBy { liftedSig(it) }.size
            val remaining = classC.map { uf.find(it) }.toHashSet().size
            steps.add(WitnessHuntStepReport(root, distinctSigs, 0, 0, 0, 0, 0, remaining, skippedTooLarge = true))
            continue
        }

        val (solver, encoding) = buildCadicalEncoding(g, combinedColorOf)
        var issued = 0
        var sat = 0
        var unsat = 0
        var unknown = 0
        var verified = 0
        try {
            val groups = classC.groupBy { liftedSig(it) }.values
            for (group in groups) {
                if (group.size <= 1) continue
                val u = group[0]
                for (v in group.drop(1)) {
                    if (uf.find(u) == uf.find(v)) continue
                    issued++
                    totalQueries++
                    when (val r = queryOrbitMateCadical(solver, encoding, u, v, perQueryTimeoutMs)) {
                        is SatQueryResult.Sat -> {
                            sat++
                            check(verifyAutomorphism(g, r.alpha)) {
                                "witness for ($u,$v) at root=$root failed O(m) verification -- " +
                                    "this is the one error class this pipeline cannot recover from, aborting"
                            }
                            verified++
                            for (w in 0 until g.n) uf.union(w, r.alpha[w])
                        }
                        SatQueryResult.Unsat -> unsat++
                        SatQueryResult.Unknown -> unknown++
                    }
                }
            }
        } finally {
            solver.close()
        }
        val remaining = classC.map { uf.find(it) }.toHashSet().size
        val distinctSigs = classC.groupBy { liftedSig(it) }.size
        steps.add(WitnessHuntStepReport(root, distinctSigs, issued, sat, unsat, unknown, verified, remaining))
        if (remaining == 1) break
    }

    return WitnessHuntResult(
        rootsUsed = roots,
        steps = steps,
        finalComponentsRemaining = steps.lastOrNull()?.componentsRemainingAfter ?: classC.map { uf.find(it) }.toHashSet().size,
        totalQueries = totalQueries,
        totalTimeMs = System.currentTimeMillis() - t0,
    )
}
