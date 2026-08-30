package dialysis.gadgetxor

import dialysis.graph.Graph
import dialysis.util.GraphIO
import java.io.File

/**
 * Reconstructs Neuen-Schweitzer multipede gadget structure (inner vertices grouped into gadgets
 * of 4, each gadget's 3 ports identified as complementary outer-vertex pairs) purely from an
 * unlabelled graph's topology -- no generator/seed access, since the real cfi-rigid-r2 benchmark
 * files carry no such metadata.
 *
 * Works for the NON-bypassed r2 family (R(B*(Gn,sigma))) where inner vertices (always degree 3,
 * regardless of the base-graph reduction) and outer vertices (a(w)/b(w), degree 2*(number of
 * surviving adjacent gadgets)) remain structurally distinct. Does NOT handle s2/t2's outer-vertex
 * bypass (which removes the a/b layer entirely and connects inner vertices directly) -- that
 * reconstruction problem was attempted and found to be substantially harder (see the project's own
 * notes); this module is scoped to r2 only.
 *
 * Algorithm (validated on cfi-rigid-r2-0068-03-1 by hand before trusting it further -- see
 * dialysis.RealFileFlipParityValidationTest in the test source set):
 * 1. Inner = degree-3 vertices. A candidate gadget is a 4-set where every pair shares EXACTLY one
 *    neighbour (true for any 2 members of a real gadget -- their patterns differ in exactly 2 of 3
 *    bits) AND the union of all 4 neighbour-sets has size exactly 6 (rules out chains of
 *    coincidental single-neighbour overlaps through a shared high-degree outer "hub" vertex, which
 *    pairwise checks alone cannot distinguish from genuine gadget membership).
 * 2. The full vertex set need not decompose uniquely from one starting vertex's local search (hub
 *    sharing creates real ambiguity) -- solved globally via exact cover over ALL candidate 4-sets
 *    found from every inner vertex's perspective. Locally-valid candidate blocks are NOT enough on
 *    their own, though: multiple candidate blocks can each individually pass the pairwise+union-6
 *    test and have internally-consistent ports, yet disagree with EACH OTHER about which outer
 *    vertex is which port-partner (a shared hub vertex's true partner differs depending on which
 *    of two overlapping candidate groupings is picked). So partner-consistency is enforced AS A
 *    CONSTRAINT DURING the exact-cover search itself (a block that would assign a hub vertex a
 *    different partner than one already committed by an earlier-chosen block is rejected outright),
 *    not just checked after the fact -- checking after the fact can only detect the problem, not
 *    avoid picking an inconsistent solution when a consistent one also exists.
 * 3. Per confirmed gadget, its 6 outer neighbours are paired into 3 ports: two outer vertices are
 *    port-partners (the a(w)/b(w) pair) iff, restricted to that gadget's 4 members, their
 *    adjacent-member sets are exact complements of one another.
 */
object RealFileGadgetReconstruction {

    data class Gadget(val inner: List<Int>, val ports: List<Pair<Int, Int>>) // ports: (a(w), b(w)) pairs, order arbitrary but fixed

    class Reconstruction(val g: Graph, val gadgets: List<Gadget>, val numInner: Int, val numOuter: Int)

    private class CandidateBlock(val members: List<Int>, val ports: List<Pair<Int, Int>>)

    /** Computes the 3 port-pairs for a candidate 4-member block, or null if this block can't form
     *  valid ports at all (some outer neighbour has no exact-complement partner within it) -- such
     *  a block is structurally invalid regardless of what else is chosen and is dropped before the
     *  search even starts. */
    private fun computePorts(members: List<Int>, adjSets: Array<HashSet<Int>>): List<Pair<Int, Int>>? {
        val memberSet = members.toHashSet()
        val outerNeighbours = members.flatMap { adjSets[it] }.toHashSet().toList()
        if (outerNeighbours.size != 6) return null
        fun membersOf(o: Int) = memberSet.filter { o in adjSets[it] }.toHashSet()
        val used = BooleanArray(outerNeighbours.size)
        val ports = mutableListOf<Pair<Int, Int>>()
        for (i in outerNeighbours.indices) {
            if (used[i]) continue
            val oi = outerNeighbours[i]
            val mi = membersOf(oi)
            var partner = -1
            for (j in i + 1 until outerNeighbours.size) {
                if (used[j]) continue
                val oj = outerNeighbours[j]
                if (membersOf(oj) == memberSet - mi) { partner = j; break }
            }
            if (partner < 0) return null
            ports.add(oi to outerNeighbours[partner])
            used[i] = true; used[partner] = true
        }
        if (ports.size != 3) return null
        return ports
    }

    fun loadAndReconstruct(path: String): Reconstruction {
        val g = GraphIO.loadDimacs(File(path).toPath())
        return reconstruct(g, path)
    }

    fun reconstruct(g: Graph, path: String = "<in-memory>"): Reconstruction {
        val adjSets = Array(g.n) { g.adj[it].toHashSet() }
        val inner = (0 until g.n).filter { g.adj[it].size == 3 }
        val outerCount = g.n - inner.size

        fun shareExactlyOne(a: Int, b: Int) = (adjSets[a] intersect adjSets[b]).size == 1

        fun candidatesFor(m: Int, pool: Set<Int>): List<List<Int>> {
            val (n1, n2, n3) = adjSets[m].sorted().let { Triple(it[0], it[1], it[2]) }
            fun sharing(nk: Int) = pool.filter { it != m && nk in adjSets[it] && shareExactlyOne(m, it) }
            val c1 = sharing(n1); val c2 = sharing(n2); val c3 = sharing(n3)
            val found = mutableSetOf<List<Int>>()
            for (m1 in c1) for (m2 in c2) {
                if (m2 == m1) continue
                for (m3 in c3) {
                    if (m3 == m1 || m3 == m2) continue
                    val group = listOf(m, m1, m2, m3)
                    val pairsOk = group.indices.all { i -> (i + 1 until 4).all { j -> shareExactlyOne(group[i], group[j]) } }
                    val union = group.flatMap { adjSets[it] }.toHashSet()
                    if (pairsOk && union.size == 6) found.add(group.sorted())
                }
            }
            return found.toList()
        }

        val allCandidates = mutableSetOf<List<Int>>()
        val poolSet = inner.toHashSet()
        for (m in inner) allCandidates.addAll(candidatesFor(m, poolSet))

        val candidateBlocks = allCandidates.mapNotNull { members ->
            computePorts(members, adjSets)?.let { CandidateBlock(members, it) }
        }

        val blockOfElem = HashMap<Int, MutableList<CandidateBlock>>()
        for (b in candidateBlocks) for (e in b.members) blockOfElem.getOrPut(e) { mutableListOf() }.add(b)

        val partnerOf = HashMap<Int, Int>()

        // Returns the list of (key, value) pairs THIS call newly inserted into partnerOf (so they
        // can be removed again on backtrack), or null if the block's ports conflict with an
        // already-committed partner assignment.
        fun tryAdd(block: CandidateBlock): List<Pair<Int, Int>>? {
            for ((p, q) in block.ports) {
                val ep = partnerOf[p]; val eq = partnerOf[q]
                if (ep != null && ep != q) return null
                if (eq != null && eq != p) return null
            }
            val added = mutableListOf<Pair<Int, Int>>()
            for ((p, q) in block.ports) {
                if (p !in partnerOf) { partnerOf[p] = q; added.add(p to q) }
                if (q !in partnerOf) { partnerOf[q] = p; added.add(q to p) }
            }
            return added
        }

        fun undoAdd(added: List<Pair<Int, Int>>) {
            for ((k, _) in added) partnerOf.remove(k)
        }

        val solution = mutableListOf<CandidateBlock>()
        fun backtrack(remaining: Set<Int>): Boolean {
            if (remaining.isEmpty()) return true
            val e = remaining.minByOrNull { blockOfElem[it]?.size ?: 0 } ?: return false
            for (block in blockOfElem[e].orEmpty()) {
                if (block.members.all { it in remaining }) {
                    val added = tryAdd(block) ?: continue
                    solution.add(block)
                    if (backtrack(remaining - block.members.toSet())) return true
                    solution.removeAt(solution.size - 1)
                    undoAdd(added)
                }
            }
            return false
        }
        check(backtrack(inner.toHashSet())) { "exact cover (with partner-consistency) failed to partition $path's ${inner.size} inner vertices into gadgets of 4 -- reconstruction algorithm does not apply to this file (bypassed family? different reduction?)" }

        val gadgets = solution.map { Gadget(it.members, it.ports) }
        return Reconstruction(g, gadgets, inner.size, outerCount)
    }
}
