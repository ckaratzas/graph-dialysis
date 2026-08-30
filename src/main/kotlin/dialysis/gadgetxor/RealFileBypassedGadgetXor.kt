package dialysis.gadgetxor

import dialysis.sat.cadical.CadicalEncoding
import dialysis.sat.cryptominisat.CryptoMiniSatSolver

/**
 * Production wiring of the cluster-generalized flip-parity invariant (validated in
 * dialysis.RealFileBypassedFlipParityValidationTest, test source set) into an actual SAT encoding,
 * for real cfi-rigid-t2 files. The bypass counterpart of [RealFileGadgetXor] -- see that class's
 * own doc for the shared design (Tseitin flip(p) variables, cluster-generalized XOR clauses over
 * odd-multiplicity ports); the one substantive difference is how `sideOf`/role is derived: there is
 * no physical port vertex left after bypass, so a vertex's role for one specific port is which of
 * that port's 2 disjoint CLIQUES (see [RealFileBypassedGadgetReconstruction.cliqueOf]) it belongs
 * to, rather than a fixed global per-vertex label. A single vertex has 3 INDEPENDENT roles (one per
 * port it participates in) -- `cliqueOf` is keyed by (vertex, port index), not by vertex alone, and
 * checking whether some OTHER vertex `k` shares a specific clique means checking ALL 3 of k's own
 * port indices (k's own gadget may order its ports differently than p's does), not just one.
 */
object RealFileBypassedGadgetXor {

    /** Every clique id vertex k participates in, across its (up to) 3 ports. */
    private fun cliquesOf(recon: RealFileBypassedGadgetReconstruction.Reconstruction, k: Int): List<Int> =
        (0 until 3).mapNotNull { idx -> recon.cliqueOf[k to idx] }

    /** representative (p, oppositeMember, portIdx) per odd port -- portIdx is p's (and opp's) own
     *  index into [RealFileBypassedGadgetReconstruction.ports]/[oppositePairs] for the gadget that
     *  contributed this representative, needed to look `cliqueOf` up correctly later (it is NOT
     *  re-derivable from p/opp alone once other gadgets' cliques are also in play). */
    class SidedReconstruction(
        val recon: RealFileBypassedGadgetReconstruction.Reconstruction,
        val clusters: Map<Int, List<Int>>,
        val clusterOddPorts: Map<Int, List<Triple<Int, Int, Int>>>,
    )

    fun prepare(recon: RealFileBypassedGadgetReconstruction.Reconstruction): SidedReconstruction {
        fun portCliqueKey(p: Int, opp: Int, portIdx: Int): Pair<Int, Int> {
            val a = recon.cliqueOf.getValue(p to portIdx); val b = recon.cliqueOf.getValue(opp to portIdx)
            return minOf(a, b) to maxOf(a, b)
        }

        val n = recon.gadgets.size
        val parent = IntArray(n) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; parent[x] = r; return r }
        fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) parent[ra] = rb }

        // Gadgets whose reconstruction failed the same-side-is-a-clique sanity check are excluded
        // entirely from XOR clause construction -- their port/clique identity is not trustworthy,
        // and emitting a clause built on it risks unsoundness (see [RealFileBypassedGadgetReconstruction]
        // safety-net doc). They still exist as gadgets for the encoding itself; they simply never
        // contribute a port to any cluster below.
        val reliableGadgets = (0 until n).filter { it !in recon.unreliableGadgetIndex }

        val gadgetsByPortKey = HashMap<Pair<Int, Int>, MutableList<Int>>()
        for (gi in reliableGadgets) {
            val gadget = recon.gadgets[gi]
            val ports = RealFileBypassedGadgetReconstruction.ports(gadget.members)
            val opposites = RealFileBypassedGadgetReconstruction.oppositePairs(gadget.members)
            for (j in 0 until 3) {
                val (p, _) = ports[j]; val (op, _) = opposites[j]
                gadgetsByPortKey.getOrPut(portCliqueKey(p, op, j)) { mutableListOf() }.add(gi)
            }
        }
        val sharedPortCount = HashMap<Long, Int>()
        fun packPair(i: Int, j: Int): Long { val a = minOf(i, j); val b = maxOf(i, j); return (a.toLong() shl 32) or b.toLong() }
        for (owners in gadgetsByPortKey.values) {
            for (i in owners.indices) for (j in i + 1 until owners.size) {
                val packed = packPair(owners[i], owners[j])
                sharedPortCount[packed] = (sharedPortCount[packed] ?: 0) + 1
            }
        }
        for ((packed, count) in sharedPortCount) if (count >= 2) union((packed shr 32).toInt(), (packed and 0xFFFFFFFFL).toInt())

        val clusters = reliableGadgets.groupBy { find(it) }
        val clusterOddPorts = clusters.mapValues { (_, members) ->
            val representative = HashMap<Pair<Int, Int>, Triple<Int, Int, Int>>()
            val count = HashMap<Pair<Int, Int>, Int>()
            for (gi in members) {
                val gadget = recon.gadgets[gi]
                val ports = RealFileBypassedGadgetReconstruction.ports(gadget.members)
                val opposites = RealFileBypassedGadgetReconstruction.oppositePairs(gadget.members)
                for (j in 0 until 3) {
                    val (p, _) = ports[j]; val (op, _) = opposites[j]
                    val key = portCliqueKey(p, op, j)
                    count[key] = (count[key] ?: 0) + 1
                    representative.putIfAbsent(key, Triple(p, op, j))
                }
            }
            count.filter { it.value % 2 == 1 }.keys.map { representative.getValue(it) }
        }
        return SidedReconstruction(recon, clusters, clusterOddPorts)
    }

    /** Adds one Tseitin flip(p, side0, side1) variable per distinct (representative vertex, its
     *  port's clique pair) referenced by any cluster's odd-port list, then one XOR clause per
     *  cluster. Returns the number of clusters an XOR clause was actually added for. */
    fun addClusterParityXors(solver: CryptoMiniSatSolver, encoding: CadicalEncoding, sided: SidedReconstruction): Int {
        val groupOfVertex = HashMap<Int, Int>()
        for ((gi, members) in encoding.groups.withIndex()) for (v in members) groupOfVertex[v] = gi

        var nextVar = encoding.numVars
        // Keyed by (vertex, port-clique-key) since one vertex can have a different flip variable
        // per port it participates in (unlike r2, where sideOf is one global label per vertex).
        val flipVar = HashMap<Pair<Int, Pair<Int, Int>>, Int>()
        val flipConst = HashMap<Pair<Int, Pair<Int, Int>>, Boolean>()

        fun ensureFlip(p: Int, side0: Int, side1: Int) {
            val key = p to (minOf(side0, side1) to maxOf(side0, side1))
            if (key in flipVar || key in flipConst) return
            val group = encoding.groups.getOrElse(groupOfVertex[p] ?: -1) { listOf(p) }
            // k (a colour-admissible candidate target for p) may belong to a DIFFERENT gadget with
            // its own port ordering, so "does k share this clique" means checking ALL of k's (up
            // to 3) port-clique ids, not looking k up at p's own port index. Identity (k == p)
            // counts as "same side" automatically here, since p's own clique list always contains
            // side0 (that's how side0 was derived) -- the "no flip" possibility, common on
            // rigid/near-rigid instances (see RealFileGadgetXor's own doc for why that matters).
            val sameTargets = group.filter { k -> side0 in cliquesOf(sided.recon, k) && encoding.varOf[p][k] >= 0 }
            val oppositeTargets = group.filter { k -> side1 in cliquesOf(sided.recon, k) && encoding.varOf[p][k] >= 0 }
            when {
                oppositeTargets.isEmpty() -> flipConst[key] = false
                sameTargets.isEmpty() -> flipConst[key] = true
                else -> {
                    nextVar++
                    val fv = nextVar
                    flipVar[key] = fv
                    val orClause = IntArray(oppositeTargets.size + 1)
                    orClause[0] = -fv
                    for ((idx, k) in oppositeTargets.withIndex()) orClause[idx + 1] = encoding.varOf[p][k]
                    solver.addClause(orClause)
                    for (k in oppositeTargets) solver.addClause(intArrayOf(-encoding.varOf[p][k], fv))
                }
            }
        }

        fun sides(p: Int, opp: Int, portIdx: Int): Pair<Int, Int> =
            sided.recon.cliqueOf.getValue(p to portIdx) to sided.recon.cliqueOf.getValue(opp to portIdx)

        for (ports in sided.clusterOddPorts.values) for ((p, opp, portIdx) in ports) {
            val (side0, side1) = sides(p, opp, portIdx)
            ensureFlip(p, side0, side1)
        }

        var clustersConstrained = 0
        for (ports in sided.clusterOddPorts.values) {
            if (ports.isEmpty()) continue
            val vars = mutableListOf<Int>()
            var rhs = false
            for ((p, opp, portIdx) in ports) {
                val (side0, side1) = sides(p, opp, portIdx)
                val key = p to (minOf(side0, side1) to maxOf(side0, side1))
                val c = flipConst[key]
                if (c != null) { if (c) rhs = !rhs } else vars.add(flipVar.getValue(key))
            }
            if (vars.isEmpty()) {
                check(!rhs) { "cluster with all-constant port-flip pattern violates the parity invariant -- do not proceed" }
                continue
            }
            solver.addXorClause(vars.toIntArray(), rhs)
            clustersConstrained++
        }
        return clustersConstrained
    }
}
