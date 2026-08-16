package dialysis.sat

/** The outcome of driving an encoding's colour classes to actual automorphism orbits: how many
 *  queries were needed, how they resolved, and the recovered partition itself. */
data class OrbitDriveResult(
    val orbits: List<List<Int>>,
    val queriesIssued: Int,
    val queriesSat: Int,
    val queriesUnsat: Int,
    val queriesUnknown: Int,
    val skippedAlreadyConnected: Int,
    val generatorsFound: Int,
    val witnessesVerified: Int,
    val witnessesRejected: Int,
)

/**
 * Union-find over the "orbit-mate" equivalence relation, extended with its OTHER half: not just
 * "have we already proven `u` and `v` are orbit-mates" ([find]), but "have we already proven they
 * are NOT" ([separated]). Both halves are transitive, so both are worth tracking: a verified SAT
 * closes a whole component via [union] (every vertex it also connects, via the witness's full
 * permutation, not just the queried pair); a verified UNSAT closes every pair BETWEEN two
 * components via [markSeparated] -- not just the one pair queried, since a component pair proven
 * separated stays separated forever, even as either component later absorbs more vertices.
 *
 * Together these two economies mean most same-colour pairs are never queried at all: a driver
 * checks `find(u) == find(v) || separated(u, v)` before issuing a query, and skips it if either
 * already holds.
 */
class SeparatingUnionFind(n: Int) {
    private val parent = IntArray(n) { it }
    private val separatedWith = HashMap<Int, MutableSet<Int>>()

    fun find(x: Int): Int {
        var r = x
        while (parent[r] != r) r = parent[r]
        var c = x
        while (parent[c] != r) {
            val next = parent[c]
            parent[c] = r
            c = next
        }
        return r
    }

    /** True iff [a] and [b]'s components were already proven to be DIFFERENT orbits (a verified
     *  UNSAT between some past representative of each) -- skip this pair without querying it. */
    fun separated(a: Int, b: Int): Boolean {
        val ra = find(a)
        val rb = find(b)
        return separatedWith[ra]?.contains(rb) == true
    }

    /** Record a verified UNSAT: [a]'s and [b]'s components are provably different orbits. Recorded
     *  symmetrically (both roots point at each other) since separation is symmetric. */
    fun markSeparated(a: Int, b: Int) {
        val ra = find(a)
        val rb = find(b)
        check(ra != rb) { "attempted to mark ($a,$b) separated but they are already in the same component -- contradiction in the driver, not this class" }
        separatedWith.getOrPut(ra) { mutableSetOf() }.add(rb)
        separatedWith.getOrPut(rb) { mutableSetOf() }.add(ra)
    }

    /** Record a verified SAT: [a] and [b] are orbit-mates. [b]'s root is absorbed into [a]'s root
     *  (arbitrary but consistent choice); every OTHER component previously proven separated from
     *  [b]'s root is re-pointed at [a]'s root instead of being silently dropped. */
    fun union(a: Int, b: Int) {
        val ra = find(a)
        val rb = find(b)
        if (ra == rb) return
        check(separatedWith[ra]?.contains(rb) != true) {
            "attempted to union components $ra and $rb that were already proven separated -- contradiction in the driver, not this class"
        }
        val rbSeparated = separatedWith.remove(rb)
        if (rbSeparated != null) {
            for (x in rbSeparated) {
                separatedWith[x]?.remove(rb)
                separatedWith[x]?.add(ra)
            }
            separatedWith.getOrPut(ra) { mutableSetOf() }.addAll(rbSeparated)
        }
        parent[rb] = ra
    }
}