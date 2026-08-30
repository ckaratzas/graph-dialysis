package dialysis.gadgetxor

import dialysis.graph.Graph
import dialysis.util.GraphIO
import java.io.File

/**
 * Reconstructs gadget structure for the OUTER-VERTEX-BYPASSED families (t2 = R*(B*(Gn,sigma)),
 * and s2 = R*(B(Gn,sigma)) though only t2 is wired into production) purely from topology -- no
 * generator/seed access, same constraint as [RealFileGadgetReconstruction] (the non-bypassed r2
 * case).
 *
 * Structural facts (see MultipedeBypassFlipParityValidationTest, test source set, for the paper
 * citation on the bypass construction and its ground-truth validation):
 * - Every vertex is a gadget member (bypass removes the separate outer-vertex class entirely) --
 *   there is no degree-3 signature to key off like r2 has.
 * - A gadget's 4 members pairwise agree on EXACTLY one of 3 port bits, so they form an internal
 *   K4, with each of the 3 ports corresponding to one of the K4's 3 disjoint perfect-matching
 *   edge-pairs.
 * - For a genuine port pairing {p,q} (agreeing on some port), the OTHER gadgets sharing that
 *   exact (port, side) are exactly N(p) ∩ N(q) MINUS this gadget's own 4 members -- BUT that raw
 *   intersection is contaminated by {p,q}'s own true OTHER-2 gadget-mates (who are also mutually
 *   adjacent to both p and q, via being fellow K4 members, regardless of which port is being
 *   tested) UNLESS the candidate group already correctly contains them. This makes "is it a K4"
 *   alone insufficient: a coincidental K4 can form from 4 vertices belonging to 2 DIFFERENT real
 *   gadgets that each independently share an unrelated port with one member of the other.
 * - The key discriminator: every vertex's FULL degree must be EXACTLY accounted for by 3 internal
 *   K4 edges plus the summed size of its 3 external port-pools (found via max-clique cleaning of
 *   the noisy raw intersections) -- a coincidental (non-gadget) K4 leaves some of a member's real
 *   degree unexplained. This alone (no other test) achieves zero false positives empirically.
 * - A small residual fraction of gadgets (a rare, structural "twin" degeneracy -- residual
 *   base-graph symmetry pairing up 2 gadgets that share MULTIPLE ports, mirroring
 *   cfi-rigid-r2-0648-04-2's flip-parity cluster case) can't be resolved this way alone, since
 *   each twin's own accounting looks "odd"/incomplete in isolation. These show up as leftover,
 *   uncovered vertices after the main pass; they're resolved in a second pass by finding
 *   connected components among the leftovers and picking any same-degree, consistent K4 pair
 *   within each (multiple such splits can be equally valid -- see this class's own test doc for
 *   why that's fine: soundness is established empirically against real automorphisms afterward,
 *   not by matching one specific ground-truth labelling).
 */
object RealFileBypassedGadgetReconstruction {

    /** ports: 3 pairs, each (m1, m2) being the 2 gadget members who agree on that port -- the
     *  OTHER 2 members (the complementary pair, at the same index via [oppositePairs]) form that
     *  same port's opposite side. A single vertex sits in 3 INDEPENDENT cliques (one per port it
     *  participates in) -- unlike r2, where each physical outer vertex belongs to exactly one
     *  port, post-bypass every vertex plays a role in all 3 of its own gadget's ports at once. So
     *  [cliqueOf] is keyed by (vertex, port index in 0..2), not by vertex alone: cliqueOf[v to j]
     *  gives the id of every vertex (possibly beyond this gadget) sharing v's exact port-j side,
     *  including v itself. The port index for a given (member, partner) pair is always the index
     *  at which that pair appears in [ports]/[oppositePairs] for v's own gadget. */
    data class Gadget(val members: List<Int>)
    class Reconstruction(
        val g: Graph,
        val gadgets: List<Gadget>,
        val cliqueOf: Map<Pair<Int, Int>, Int>,
        /** Indices into [gadgets] whose reconstructed clique/port structure failed the
         *  same-side-is-actually-a-clique sanity check -- see the safety net at the end of
         *  [reconstruct]. Downstream code must exclude these gadgets' ports from any XOR clause
         *  construction rather than risk emitting an unsound constraint. */
        val unreliableGadgetIndex: Set<Int> = emptySet(),
    )

    /** For gadget members (m,x,y,z) in that fixed order, port j's two sides are (m,x)/(y,z),
     *  (m,y)/(x,z), (m,z)/(x,y) respectively (the 3 disjoint perfect matchings of a K4). */
    fun oppositePairs(members: List<Int>): List<Pair<Int, Int>> {
        val (m, x, y, z) = members
        return listOf(y to z, x to z, x to y)
    }

    fun ports(members: List<Int>): List<Pair<Int, Int>> {
        val (m, x, y, z) = members
        return listOf(m to x, m to y, m to z)
    }

    /** Greedy degree-ordered clique growth: fast (O(pool^2)), not guaranteed to find the true
     *  MAXIMUM clique in an adversarial pool, but exactness here isn't what establishes
     *  correctness -- the caller's exact degree-accounting check (every member's real degree must
     *  be EXACTLY explained by 3 internal + the summed cleaned-pool sizes) is what rejects a bad
     *  candidate outright; a slightly-wrong (too-small) greedy clique just fails that check like
     *  any other invalid candidate would, rather than silently producing a wrong answer. This
     *  matters because the exact (branch-and-bound) version is worst-case exponential in pool
     *  size, and t2's combined base-reduction + bypass grows pool sizes with instance size --
     *  confirmed to make the exact version intractable well before real instance sizes. */
    private fun greedyCliqueContaining(pool: Set<Int>, adjSets: Array<HashSet<Int>>): Set<Int> {
        if (pool.size <= 24) return exactMaxCliqueContaining(pool, adjSets) // cheap enough, prefer exactness
        val ordered = pool.sortedByDescending { v -> pool.count { it != v && it in adjSets[v] } }
        val clique = mutableListOf<Int>()
        for (v in ordered) if (clique.all { it in adjSets[v] }) clique.add(v)
        return clique.toHashSet()
    }

    private fun exactMaxCliqueContaining(pool: Set<Int>, adjSets: Array<HashSet<Int>>): Set<Int> {
        var best = emptyList<Int>()
        fun bk(candidates: List<Int>, current: MutableList<Int>) {
            if (candidates.isEmpty()) {
                if (current.size > best.size) best = current.toList()
                return
            }
            if (current.size + candidates.size <= best.size) return
            for (i in candidates.indices) {
                val v = candidates[i]
                val next = candidates.subList(i + 1, candidates.size).filter { it in adjSets[v] }
                current.add(v)
                bk(next, current)
                current.removeAt(current.size - 1)
            }
        }
        bk(pool.toList(), mutableListOf())
        return best.toHashSet()
    }

    fun loadAndReconstruct(path: String): Reconstruction {
        val g = GraphIO.loadDimacs(File(path).toPath())
        return reconstruct(g, path)
    }

    fun reconstruct(g: Graph, path: String = "<in-memory>"): Reconstruction {
        val adjSets = Array(g.n) { g.adj[it].toHashSet() }
        check(g.n % 4 == 0) { "$path: n=${g.n} is not a multiple of 4 -- not a gadget-based CFI construction" }

        // --- Pass 1: precise K4 + degree-accounting candidates (handles the overwhelming
        // majority of gadgets with zero false positives). ---
        data class Pass1Candidate(val members: List<Int>)

        fun pass1CandidatesFor(m: Int): List<Pass1Candidate> {
            val neighbours = adjSets[m].toList()
            val found = mutableListOf<Pass1Candidate>()
            for (i in neighbours.indices) for (j in i + 1 until neighbours.size) for (k in j + 1 until neighbours.size) {
                val x = neighbours[i]; val y = neighbours[j]; val z = neighbours[k]
                if (z !in adjSets[x] || y !in adjSets[x] || z !in adjSets[y]) continue
                val group = listOf(m, x, y, z)
                val groupSet = group.toHashSet()
                val pairs = ports(group) to oppositePairs(group)
                val poolSizes = HashMap<Int, Int>()
                for (v in group) poolSizes[v] = 0
                var ok = true
                for (idx in 0 until 3) {
                    val (p, q) = pairs.first[idx]; val (r, s) = pairs.second[idx]
                    val poolPq = (adjSets[p] intersect adjSets[q]) - groupSet
                    val poolRs = (adjSets[r] intersect adjSets[s]) - groupSet
                    val cleanPq = greedyCliqueContaining(poolPq, adjSets)
                    val cleanRs = greedyCliqueContaining(poolRs, adjSets)
                    if (cleanPq.any { it in cleanRs }) { ok = false; break }
                    poolSizes[p] = poolSizes.getValue(p) + cleanPq.size
                    poolSizes[q] = poolSizes.getValue(q) + cleanPq.size
                    poolSizes[r] = poolSizes.getValue(r) + cleanRs.size
                    poolSizes[s] = poolSizes.getValue(s) + cleanRs.size
                }
                if (ok && group.all { adjSets[it].size == 3 + poolSizes.getValue(it) }) {
                    found.add(Pass1Candidate(group.sorted()))
                }
            }
            return found
        }

        val pass1Candidates = HashSet<List<Int>>()
        for (m in 0 until g.n) for (c in pass1CandidatesFor(m)) pass1Candidates.add(c.members)

        val coverCount = HashMap<Int, Int>()
        for (block in pass1Candidates) for (v in block) coverCount[v] = (coverCount[v] ?: 0) + 1
        val unambiguous = pass1Candidates.filter { block -> block.all { coverCount.getValue(it) == 1 } }
        val covered = unambiguous.flatten().toHashSet()
        val leftover = (0 until g.n).filterNot { it in covered }.toHashSet()

        // --- Pass 2: leftover vertices (rare "twin"/residual-symmetry clusters, or bigger
        // tangles of them on smaller/denser bypassed+reduced files like t2) -- connected
        // components among leftover-restricted adjacency, then same-degree-filtered exact cover
        // (vertex-disjoint K4s) within each component. Multiple valid partitions of a component
        // can be equally consistent (this IS the residual local symmetry, not an algorithm bug --
        // see class doc); any one found is accepted, since soundness is established afterward by
        // empirical validation against real automorphisms, not by matching one arbitrary
        // ground-truth labelling that the graph's own structure doesn't actually pin down. ---
        val pass2Gadgets = mutableListOf<List<Int>>()
        val visited = HashSet<Int>()
        for (start in leftover) {
            if (start in visited) continue
            val comp = mutableSetOf<Int>()
            val stack = ArrayDeque<Int>()
            stack.add(start)
            while (stack.isNotEmpty()) {
                val v = stack.removeLast()
                if (!comp.add(v)) continue
                visited.add(v)
                for (u in adjSets[v]) if (u in leftover && u !in comp) stack.add(u)
            }
            // Local, per-vertex K4 search (same shape as pass 1's, restricted to this component's
            // neighbours) instead of an O(size^4) brute-force scan over the whole component --
            // that scan is fine for a handful of vertices (e.g. r2's rare 8-vertex twin pairs) but
            // t2's denser bypass+reduction combination can leave MOST of the graph "ambiguous"
            // after pass 1, making a global O(size^4) enumeration intractable well before real
            // instance sizes.
            val k4s = mutableSetOf<List<Int>>()
            for (m in comp) {
                val neighboursInComp = adjSets[m].filter { it in comp }
                for (i in neighboursInComp.indices) for (j in i + 1 until neighboursInComp.size) for (k in j + 1 until neighboursInComp.size) {
                    val x = neighboursInComp[i]; val y = neighboursInComp[j]; val z = neighboursInComp[k]
                    if (z !in adjSets[x] || y !in adjSets[x] || z !in adjSets[y]) continue
                    k4s.add(listOf(m, x, y, z).sorted())
                }
            }
            val sameDegree = k4s.filter { it.map { v -> adjSets[v].size }.toHashSet().size == 1 }

            val blockOfElem = HashMap<Int, MutableList<List<Int>>>()
            for (b in sameDegree) for (e in b) blockOfElem.getOrPut(e) { mutableListOf() }.add(b)
            val solution = mutableListOf<List<Int>>()
            fun backtrack(remaining: Set<Int>): Boolean {
                if (remaining.isEmpty()) return true
                val e = remaining.minByOrNull { blockOfElem[it]?.size ?: 0 } ?: return false
                for (block in blockOfElem[e].orEmpty()) {
                    if (block.all { it in remaining }) {
                        solution.add(block)
                        if (backtrack(remaining - block.toSet())) return true
                        solution.removeAt(solution.size - 1)
                    }
                }
                return false
            }
            check(backtrack(comp)) { "$path: no consistent gadget partition found for leftover cluster ${comp.sorted()} -- reconstruction does not apply" }
            pass2Gadgets.addAll(solution)
        }

        val allGadgetMembers = unambiguous + pass2Gadgets
        check(allGadgetMembers.flatten().toHashSet().size == g.n) { "$path: gadget reconstruction did not cover every vertex exactly once" }

        // --- Recover port-clique identities for every gadget (needed for the flip invariant's
        // sideOf labelling). A vertex sits in 3 INDEPENDENT cliques (one per port), so this is a
        // union-find over (vertex, portIdx) pairs, NOT over bare vertices -- an earlier version
        // keyed by vertex alone was a real bug: since `ports(members)` always lists the gadget's
        // first member as the shared element across ALL 3 of its own ports, unioning "p with q"
        // per port collapsed a gadget's own 4 members into ONE clique regardless of which port was
        // being processed (confirmed directly: this collapsed an entire real file's every port
        // into a single ID). A second attempt (reusing pass 1's raw-pool-with-opposite-exclusion
        // test, still keyed by bare vertex) had the same root problem for a different reason: even
        // a CORRECT test for "is this vertex on the right side" can't fix a union-find that has no
        // way to represent one vertex's 3 separate memberships in the first place.
        //
        // The fix: encode each node as (vertex, portIdx). Within one gadget, port idx's own two
        // sides (p,q) and (r,s) trivially union under that SAME idx (they're the same physical
        // port by construction). For an external vertex y found via the exclusion test, we still
        // need to know which of y's OWN port-idx values this relationship corresponds to -- found
        // by checking y's 3 candidate (partner, idx) pairs and picking whichever one has its
        // partner ALSO in the same pool (every gadget contributes its OWN 2-member pair to a
        // shared side, never a lone vertex, so exactly one of y's 3 relationships qualifies).
        data class Node(val v: Int, val idx: Int)
        val parent = HashMap<Node, Node>()
        fun find(x: Node): Node { var r = x; while (parent.getValue(r) != r) r = parent.getValue(r); parent[x] = r; return r }
        fun union(a: Node, b: Node) { val ra = find(a); val rb = find(b); if (ra != rb) parent[ra] = rb }

        val gadgets = allGadgetMembers.map { Gadget(it) }
        val gadgetOfMember = HashMap<Int, List<Int>>()
        val gadgetIndexOfMember = HashMap<Int, Int>()
        for ((gi, gadget) in gadgets.withIndex()) for (v in gadget.members) { gadgetOfMember[v] = gadget.members; gadgetIndexOfMember[v] = gi }
        for (gadget in gadgets) for (v in gadget.members) for (idx in 0 until 3) parent[Node(v, idx)] = Node(v, idx)

        /** For vertex v (any member of some gadget), its 3 (partner, portIdx) relationships. */
        fun relationsOf(v: Int): List<Pair<Int, Int>> {
            val members = gadgetOfMember.getValue(v)
            val ps = ports(members); val ops = oppositePairs(members)
            val rels = mutableListOf<Pair<Int, Int>>()
            for (idx in 0 until 3) {
                val (a, b) = ps[idx]
                if (a == v) rels.add(b to idx) else if (b == v) rels.add(a to idx)
                val (c, d) = ops[idx]
                if (c == v) rels.add(d to idx) else if (d == v) rels.add(c to idx)
            }
            return rels
        }

        // Gadget indices where some pool vertex's presence could NOT be explained by a clean
        // partner relationship within the same pool -- see the safety-net doc below for why this
        // signals a genuinely missed cross-gadget union (case 2), not just noise to ignore.
        val unresolvedPoolGadgetIndex = HashSet<Int>()

        for ((gi, gadget) in gadgets.withIndex()) {
            val groupSet = gadget.members.toHashSet()
            val portPairs = ports(gadget.members)
            val oppositePairsList = oppositePairs(gadget.members)
            for (idx in 0 until 3) {
                val (p, q) = portPairs[idx]; val (r, s) = oppositePairsList[idx]
                union(Node(p, idx), Node(q, idx)); union(Node(r, idx), Node(s, idx))
                // Cheap, exact (no clique search) membership test: an external vertex y genuinely
                // shares THIS port's (p,q) side iff it's adjacent to BOTH p and q AND adjacent to
                // NEITHER r nor s (the opposite side) -- being merely adjacent to p and q is not
                // enough at t2's density (confirmed directly: without the opposite-side exclusion,
                // many unrelated vertices are coincidentally adjacent to any given pair). This is
                // exactly r2's own port-pairing principle (exact complement of adjacent-member
                // sets), generalized to a possibly-larger-than-one-gadget side.
                val poolPq = (adjSets[p] intersect adjSets[q]) - groupSet - adjSets[r] - adjSets[s]
                val poolRs = (adjSets[r] intersect adjSets[s]) - groupSet - adjSets[p] - adjSets[q]
                for (y in poolPq) {
                    val rel = relationsOf(y).firstOrNull { (partner, _) -> partner in poolPq }
                    if (rel == null) { unresolvedPoolGadgetIndex.add(gi); unresolvedPoolGadgetIndex.add(gadgetIndexOfMember.getValue(y)); continue }
                    union(Node(p, idx), Node(y, rel.second))
                }
                for (y in poolRs) {
                    val rel = relationsOf(y).firstOrNull { (partner, _) -> partner in poolRs }
                    if (rel == null) { unresolvedPoolGadgetIndex.add(gi); unresolvedPoolGadgetIndex.add(gadgetIndexOfMember.getValue(y)); continue }
                    union(Node(r, idx), Node(y, rel.second))
                }
            }
        }

        var nextId = 0
        val rootId = HashMap<Node, Int>()
        val cliqueOf = HashMap<Pair<Int, Int>, Int>()
        for (gadget in gadgets) for (v in gadget.members) for (idx in 0 until 3) {
            val root = find(Node(v, idx))
            val id = rootId.getOrPut(root) { nextId++ }
            cliqueOf[v to idx] = id
        }

        // --- Safety net: the port/clique identification above is a LOCAL heuristic (pairwise
        // adjacency + opposite-side exclusion) and can genuinely fail on rare, highly-symmetric
        // instances -- confirmed directly on small real t2 files with unusually rich automorphism
        // groups, via 2 distinct failure modes:
        //  (1) a stray cross-gadget union collapses a gadget's own two OPPOSITE port sides into the
        //      SAME clique id (confirmed: cfi-rigid-t2-0016-04-1, where an unrelated gadget's K4
        //      members ended up spanning both (p,q) and (r,s) of another gadget's port -- since a
        //      gadget's own 4 members are mutually adjacent regardless of port, a single contaminating
        //      vertex adjacent to all 4 can slip past the "is this a clique" check below undetected,
        //      so this is checked FIRST and directly: for every port of every gadget, side and
        //      opposite side must resolve to distinct clique ids).
        //  (2) a genuine cross-gadget port-share is missed entirely (confirmed: cfi-rigid-t2-0020-01-1,
        //      a "twin-like" relationship the local exclusion test doesn't detect at all): the pool
        //      construction loop above already flags this directly -- a pool vertex y whose own
        //      relations don't include a partner also in the pool is exactly an unexplained/dropped
        //      cross-gadget relation, tracked as [unresolvedPoolGadgetIndex] -- rather than the
        //      cliques staying internally self-consistent but semantically incomplete (which the
        //      is-a-clique check below cannot see), that gadget is marked unreliable directly.
        // On all three fronts: mark any gadget touching a failing check as unreliable; downstream
        // (RealFileBypassedGadgetXor) excludes such gadgets' ports from XOR clause construction
        // entirely rather than emit a clause that might be wrong.
        val cliqueMembers = HashMap<Int, MutableSet<Int>>()
        for ((vIdx, id) in cliqueOf) cliqueMembers.getOrPut(id) { mutableSetOf() }.add(vIdx.first)
        val unreliableGadgetIndex = HashSet<Int>(unresolvedPoolGadgetIndex)

        for ((gi, gadget) in gadgets.withIndex()) {
            val portPairs = ports(gadget.members)
            val oppositePairsList = oppositePairs(gadget.members)
            for (idx in 0 until 3) {
                val (p, _) = portPairs[idx]; val (r, _) = oppositePairsList[idx]
                if (cliqueOf.getValue(p to idx) == cliqueOf.getValue(r to idx)) unreliableGadgetIndex.add(gi)
            }
        }
        for ((gi, gadget) in gadgets.withIndex()) {
            for (v in gadget.members) for (idx in 0 until 3) {
                val members = cliqueMembers.getValue(cliqueOf.getValue(v to idx))
                val isClique = members.all { a -> members.all { b -> a == b || b in adjSets[a] } }
                if (!isClique) unreliableGadgetIndex.add(gi)
            }
        }

        return Reconstruction(g, gadgets, cliqueOf, unreliableGadgetIndex)
    }
}
