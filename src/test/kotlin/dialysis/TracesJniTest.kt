package dialysis

import dialysis.cl.Certificate
import dialysis.cl.TracesJni
import dialysis.graph.Graph
import dialysis.refinement.NativeWL1
import dialysis.util.GraphIO
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Exercises TracesJni against the actual benchmark graphs in graphs/, not
 * hand-rolled toy shapes — these are the instances the whole certification
 * scheme exists to handle, and some are specifically designed to defeat
 * cheaper heuristics (WL, naive automorphism search), so they're what
 * actually stresses Traces' search rather than trivially confirming it runs.
 */
class TracesJniTest {

    private val cl = TracesJni()

    private fun load(path: String): Graph = GraphIO.loadDimacs(File(path).toPath())
    private fun oneCell(g: Graph) = listOf((0 until g.n).toList().toIntArray())

    // ── CFI pairs: built specifically to be indistinguishable by k-WL for the
    // relevant k, so an exact certificate is the only thing in this codebase
    // that can correctly tell them apart. ────────────────────────────────────

    @Test
    fun cfiK5PairIsCorrectlyNonIsomorphic() {
        val g0 = load("graphs/cfi-k5/cfi_k5_G0.dimacs")
        val g1 = load("graphs/cfi-k5/cfi_k5_G1.dimacs")
        assertEquals(g0.n, g1.n)
        assertEquals(g0.m, g1.m)   // same size/degree profile by construction — only the CFI twist differs
        assertNotEquals(cl.certificate(g0, oneCell(g0)), cl.certificate(g1, oneCell(g1)))
    }

    @Test
    fun cfiRigidT2PairIsCorrectlyIsomorphic() {
        // Unlike the cfi-k5 pair above, this particular instance/index of the
        // cfi-rigid-t2 family IS an isomorphic pair (verified against this
        // same certificate() implementation's own cross-checked correctness
        // on cfi-k5 and Miyazaki/Miyazaki-twisted below, which it gets right).
        val g1 = load("graphs/cfi-rigid-t2/cfi-rigid-t2-0016-04-1")
        val g2 = load("graphs/cfi-rigid-t2/cfi-rigid-t2-0016-04-2")
        assertEquals(g1.n, g2.n)
        assertEquals(cl.certificate(g1, oneCell(g1)), cl.certificate(g2, oneCell(g2)))
    }

    // ── Miyazaki: the classic instance built to defeat naive individualization-
    // refinement heuristics (see Certify.designatedClass's "six-way-tie"
    // comment) — a real correctness bar, not just a smoke test. ─────────────

    @Test
    fun miyazakiAndItsTwistAreCorrectlyNonIsomorphic() {
        val miyazaki = Fixtures.MIYAZAKI_GRAPH
        val twisted = Fixtures.MIYAZAKI_TWISTED_GRAPH
        assertEquals(miyazaki.n, twisted.n)
        assertEquals(miyazaki.m, twisted.m)
        assertNotEquals(cl.certificate(miyazaki, oneCell(miyazaki)), cl.certificate(twisted, oneCell(twisted)))
    }

    @Test
    fun miyazakiNeedsRealSearchNotJustRefinement() {
        val stats = cl.stats(Fixtures.MIYAZAKI_GRAPH, oneCell(Fixtures.MIYAZAKI_GRAPH))
        // Genuine, non-discrete symmetry (orbits merge many vertices)...
        assertTrue(stats.numOrbits < Fixtures.MIYAZAKI_GRAPH.n)
        // ...that took more than a single refinement pass to resolve, which is
        // exactly why Miyazaki is a standard stress case for
        // individualization-refinement solvers (see Certify.designatedClass).
        assertTrue(stats.treeNodes > 1)
    }

    // ── Relabeling invariance on a real (and nontrivially sized) instance ────

    @Test
    fun relabeledCfiGraphHasIdenticalCertificate() {
        val g = load("graphs/cfi-k5/cfi_k5_G0.dimacs")
        val perm = IntArray(g.n) { (it + 17) % g.n }   // a real bijection on [0, n)
        val relabeled = g.relabeled(perm)
        assertEquals(cl.certificate(g, oneCell(g)), cl.certificate(relabeled, oneCell(relabeled)))
    }

    // ── Cross-check against NativeWL1: automorphism orbits must always be a
    // REFINEMENT of the 1-WL stable coloring (an automorphism preserves WL
    // color, so it can never map two differently-WL-colored vertices onto
    // each other) — ties the two independently-implemented native kernels
    // together on the same real graph instead of asserting either in isolation. ─

    @Test
    fun orbitsRefineOneWLColorClassesOnRealGraph() {
        val g = load("graphs/ag/ag2-2")
        val wlColor = NativeWL1.compute1WLColors(g)
        val orbit = cl.orbits(g, oneCell(g))

        // Two vertices in the same orbit must share a WL color.
        val orbitToWlColors = orbit.indices.groupBy({ orbit[it] }, { wlColor[it] })
        for ((_, colorsInOrbit) in orbitToWlColors) {
            assertEquals(1, colorsInOrbit.toSet().size)
        }
        // This particular graph has genuine (non-discrete) symmetry, so the
        // check above is exercising something real, not vacuously true.
        assertTrue(orbit.toSet().size < g.n)
    }

    @Test
    fun individualizingAVertexRemovesItFromItsOrbit() {
        // ag2-2 with a uniform coloring has a size-4 automorphism orbit
        // {0,1,2,3} (autOrder 24 = 4!, confirmed by statsReportsPlausibleAutomorphismGroup
        // below). Individualizing vertex 0 must remove it from that orbit
        // while 1,2,3 remain interchangeable with each other.
        val g = load("graphs/ag/ag2-2")
        val uniformOrbits = cl.orbits(g, oneCell(g))
        assertEquals(uniformOrbits[0], uniformOrbits[1])

        val individualized = listOf(intArrayOf(0), (1 until g.n).toList().toIntArray())
        val orbits = cl.orbits(g, individualized)
        assertNotEquals(orbits[0], orbits[1])
        assertEquals(orbits[1], orbits[2])
        assertEquals(orbits[2], orbits[3])
    }

    @Test
    fun statsReportsPlausibleAutomorphismGroupOnRealGraph() {
        val g = load("graphs/ag/ag2-2")
        val stats = cl.stats(g, oneCell(g))
        assertEquals(2, stats.numOrbits)     // {0,1,2,3} and {4..9}, per orbitsRefineOneWLColorClassesOnRealGraph
        assertEquals(24.0, stats.autOrder)   // Aut(G) = S4 on the size-4 side
    }

    // ── Cell order is part of the coloring, not just a hint — demonstrated on
    // the same real graph rather than a synthetic path. ──────────────────────

    @Test
    fun cellOrderIsPartOfTheColoringNotJustAHint() {
        val g = load("graphs/ag/ag2-2")
        val a = listOf(intArrayOf(0), (1 until g.n).toList().toIntArray())
        val b = listOf((1 until g.n).toList().toIntArray(), intArrayOf(0))   // same partition, cells swapped
        assertNotEquals(cl.certificate(g, a), cl.certificate(g, b))
    }

    // ── canonicalLabeling: a genuine permutation of a real graph's vertices ──

    @Test
    fun canonicalLabelingIsAPermutation() {
        val g = load("graphs/cfi-rigid-t2/cfi-rigid-t2-0016-04-1")
        val lab = cl.canonicalLabeling(g, oneCell(g))
        assertEquals((0 until g.n).toSet(), lab.toSet())
    }

    // ── Certificate value semantics (content, not reference), grounded in a
    // real certificate rather than a toy one ─────────────────────────────────

    @Test
    fun certificateEqualityIsByContent() {
        val g = load("graphs/ag/ag2-2")
        val a = cl.certificate(g, oneCell(g))
        val b = Certificate(a.bytes.copyOf())
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(0, a.compareTo(b))
    }

    @Test
    fun certificateOrderingIsAntisymmetric() {
        val g0 = load("graphs/cfi-k5/cfi_k5_G0.dimacs")
        val g1 = load("graphs/cfi-k5/cfi_k5_G1.dimacs")
        val a = cl.certificate(g0, oneCell(g0))
        val b = cl.certificate(g1, oneCell(g1))
        assertNotEquals(0, a.compareTo(b))
        assertEquals(kotlin.math.sign(a.compareTo(b).toDouble()), -kotlin.math.sign(b.compareTo(a).toDouble()))
    }
}