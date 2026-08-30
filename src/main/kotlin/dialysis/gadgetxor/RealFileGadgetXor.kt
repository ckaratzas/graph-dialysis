package dialysis.gadgetxor

import dialysis.sat.cadical.CadicalEncoding
import dialysis.sat.cryptominisat.CryptoMiniSatSolver

/**
 * Production wiring of the CLUSTER-generalized flip-parity invariant (validated in
 * dialysis.RealFileFlipParityValidationTest, test source set) into an actual SAT encoding, for
 * real cfi-rigid-r2 files.
 *
 * Two changes from the from-scratch synthetic version (dialysis.MultipedeGadgetXorSoundnessTest):
 * 1. There is no aBase/bBase global type split to Tseitin-encode against here -- real files carry
 *    no generator metadata. Instead flip(p) is Tseitin-encoded against the GLOBAL `sideOf` labelling
 *    [RealFileGadgetReconstruction] already derives (an arbitrary-but-fixed 0/1 label per outer
 *    vertex, from which side of its own port pair it is): flip(p) <-> OR over admissible targets k
 *    of p with sideOf(k) != sideOf(p). This is well-defined and colour-agnostic -- it does not
 *    assume p's admissible targets are confined to its own port; a target belonging to some OTHER
 *    port (the twin/cluster case) is handled correctly because sideOf is a GLOBAL per-vertex label,
 *    not a per-port-local one.
 * 2. The XOR clause is emitted per CLUSTER (gadgets unioned by sharing >= 2 ports), over only the
 *    ODD-multiplicity ports in that cluster -- not per single gadget. A singleton cluster (the
 *    overwhelmingly common case) reduces this back to exactly the original per-gadget rule.
 */
object RealFileGadgetXor {

    class SidedReconstruction(
        val recon: RealFileGadgetReconstruction.Reconstruction,
        val sideOf: (Int) -> Int,
        val clusters: Map<Int, List<Int>>,
        val clusterOddPorts: Map<Int, List<Pair<Int, Int>>>,
    )

    /** Rebuilds the partner/side labelling and gadget clustering -- pure function of the
     *  reconstruction, no solver/encoding involved yet. Throws if port-pairing is inconsistent. */
    fun prepare(recon: RealFileGadgetReconstruction.Reconstruction): SidedReconstruction {
        val partnerOf = HashMap<Int, Int>()
        for (gadget in recon.gadgets) {
            for ((p, q) in gadget.ports) {
                val existingP = partnerOf[p]; val existingQ = partnerOf[q]
                check(existingP == null || existingP == q) { "vertex $p paired with both $existingP and $q in different gadgets" }
                check(existingQ == null || existingQ == p) { "vertex $q paired with both $existingQ and $p in different gadgets" }
                partnerOf[p] = q; partnerOf[q] = p
            }
        }
        fun sideOf(v: Int): Int = if (v < partnerOf.getValue(v)) 0 else 1
        fun portKey(p: Int, q: Int) = minOf(p, q)

        val n = recon.gadgets.size
        val parent = IntArray(n) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; parent[x] = r; return r }
        fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) parent[ra] = rb }

        val gadgetsByPort = HashMap<Int, MutableList<Int>>()
        for ((gi, gadget) in recon.gadgets.withIndex()) {
            for ((p, q) in gadget.ports) gadgetsByPort.getOrPut(portKey(p, q)) { mutableListOf() }.add(gi)
        }
        val sharedPortCount = HashMap<Long, Int>()
        fun packPair(i: Int, j: Int): Long { val a = minOf(i, j); val b = maxOf(i, j); return (a.toLong() shl 32) or b.toLong() }
        for (owners in gadgetsByPort.values) {
            for (i in owners.indices) for (j in i + 1 until owners.size) {
                val packed = packPair(owners[i], owners[j])
                sharedPortCount[packed] = (sharedPortCount[packed] ?: 0) + 1
            }
        }
        for ((packed, count) in sharedPortCount) if (count >= 2) union((packed shr 32).toInt(), (packed and 0xFFFFFFFFL).toInt())

        val clusters = (0 until n).groupBy { find(it) }
        val clusterOddPorts = clusters.mapValues { (_, members) ->
            val representative = HashMap<Int, Pair<Int, Int>>()
            val count = HashMap<Int, Int>()
            for (gi in members) for ((p, q) in recon.gadgets[gi].ports) {
                val key = portKey(p, q)
                count[key] = (count[key] ?: 0) + 1
                representative.putIfAbsent(key, p to q)
            }
            count.filter { it.value % 2 == 1 }.keys.map { representative.getValue(it) }
        }
        return SidedReconstruction(recon, ::sideOf, clusters, clusterOddPorts)
    }

    /** Adds one Tseitin flip(p) variable per distinct representative port vertex referenced by any
     *  cluster's odd-port list, then one XOR clause per cluster. Returns the number of clusters an
     *  XOR clause was actually added for (clusters with zero odd ports, if any, are skipped). */
    fun addClusterParityXors(solver: CryptoMiniSatSolver, encoding: CadicalEncoding, sided: SidedReconstruction): Int {
        val groupOfVertex = HashMap<Int, Int>()
        for ((gi, members) in encoding.groups.withIndex()) for (v in members) groupOfVertex[v] = gi

        var nextVar = encoding.numVars
        val flipVar = HashMap<Int, Int>() // representative outer vertex -> Tseitin var id
        val flipConst = HashMap<Int, Boolean>() // representative outer vertex -> forced constant, if degenerate

        fun ensureFlip(p: Int) {
            if (p in flipVar || p in flipConst) return
            val group = encoding.groups.getOrElse(groupOfVertex[p] ?: -1) { listOf(p) }
            val sideP = sided.sideOf(p)
            // Identity (k == p) deliberately counts as a "same side" target -- it's the "no flip"
            // possibility, and excluding it would make "no OTHER same-side target" look identical
            // to "same-side is structurally impossible," wrongly forcing flip=true even when the
            // only real option is p mapping to itself (the common case on a RIGID graph, since
            // colour refinement alone can't fully discretize a CFI-hard instance).
            val oppositeTargets = group.filter { k -> sided.sideOf(k) != sideP && encoding.varOf[p][k] >= 0 }
            val sameTargets = group.filter { k -> sided.sideOf(k) == sideP && encoding.varOf[p][k] >= 0 }
            when {
                oppositeTargets.isEmpty() -> flipConst[p] = false
                sameTargets.isEmpty() -> flipConst[p] = true
                else -> {
                    nextVar++
                    val fv = nextVar
                    flipVar[p] = fv
                    val orClause = IntArray(oppositeTargets.size + 1)
                    orClause[0] = -fv
                    for ((idx, k) in oppositeTargets.withIndex()) orClause[idx + 1] = encoding.varOf[p][k]
                    solver.addClause(orClause)
                    for (k in oppositeTargets) solver.addClause(intArrayOf(-encoding.varOf[p][k], fv))
                }
            }
        }

        for (ports in sided.clusterOddPorts.values) for ((p, _) in ports) ensureFlip(p)

        var clustersConstrained = 0
        for (ports in sided.clusterOddPorts.values) {
            if (ports.isEmpty()) continue
            val vars = mutableListOf<Int>()
            var rhs = false
            for ((p, _) in ports) {
                val c = flipConst[p]
                if (c != null) { if (c) rhs = !rhs } else vars.add(flipVar.getValue(p))
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
