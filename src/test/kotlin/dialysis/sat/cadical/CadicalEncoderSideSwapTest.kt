package dialysis.sat.cadical

import dialysis.content.Content
import dialysis.graph.Graph
import dialysis.sat.SatQueryResult
import dialysis.sat.verifyAutomorphism
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guard rail for side-swapping automorphisms. C4 (4-cycle: 0-1-2-3-0) has bipartition
 * A={0,2}, B={1,3} -- equal size, and the rotation 0->1->2->3->0 is a genuine automorphism that
 * SWAPS the parts (0 in A lands on 1 in B). [buildCadicalEncoding] alone (side-preserving only)
 * cannot express this; if its UNSAT on (0,1) were trusted as unconditional, that would be an
 * unsound gap -- an equal-sized bipartition can always hide a side-swapping automorphism the
 * preserving encoding alone is blind to.
 */
class CadicalEncoderSideSwapTest {
    private fun c4(): Graph = Graph(4, arrayOf(intArrayOf(1, 3), intArrayOf(0, 2), intArrayOf(1, 3), intArrayOf(0, 2)), arrayOf("0", "1", "2", "3"))
    private val uniform: (Int) -> Content = { Content.Str("u") }

    @Test
    fun swapEncoderFindsTheRotation() {
        val g = c4()
        val bp = g.bipartition()!!
        assertEquals(bp.first.size, bp.second.size, "C4's two parts must be equal size for this test to be meaningful")

        val (solver, encoding) = buildCadicalEncodingSideSwapped(g, uniform)!!
        try {
            val r = queryOrbitMateCadical(solver, encoding, 0, 1, 60_000L)
            assertTrue(r is SatQueryResult.Sat, "the side-swapping rotation 0->1->2->3->0 must be found -- got $r")
            val alpha = (r as SatQueryResult.Sat).alpha
            assertTrue(verifyAutomorphism(g, alpha), "witness must independently verify in O(m)")
            assertEquals(1, alpha[0])
        } finally {
            solver.close()
        }
    }

    @Test
    fun preserveEncoderAloneMissesIt() {
        val g = c4()
        val (solver, encoding) = buildCadicalEncoding(g, uniform)
        try {
            val r = queryOrbitMateCadical(solver, encoding, 0, 1, 60_000L)
            assertTrue(r is SatQueryResult.Unsat, "0 and 1 are on different (equal-size) sides -- the side-PRESERVING encoder alone must say UNSAT here, demonstrating the gap the swap encoding exists to close")
        } finally {
            solver.close()
        }
    }

    @Test
    fun combinedDriveRecoversTheSingleOrbit() {
        val g = c4()
        val (preserveSolver, preserveEncoding) = buildCadicalEncoding(g, uniform)
        val swapPair = buildCadicalEncodingSideSwapped(g, uniform)
        assertNotNull(swapPair, "C4 has equal-sized parts -- a swap encoding must be built, not null")
        try {
            val result = driveToOrbitsCadical(g, preserveSolver, preserveEncoding, swapPair)
            assertEquals(0, result.witnessesRejected)
            assertEquals(1, result.orbits.size, "C4 is vertex-transitive -- all 4 vertices in ONE orbit")
            assertEquals(4, result.orbits.single().size)
        } finally {
            preserveSolver.close()
            swapPair?.first?.close()
        }
    }

    @Test
    fun swapEncoderIsNullWhenPartsAreUnequal() {
        // A path P3 (0-1-2): bipartition {0,2} vs {1} -- sizes 2 and 1, unequal.
        val g = Graph(3, arrayOf(intArrayOf(1), intArrayOf(0, 2), intArrayOf(1)), arrayOf("0", "1", "2"))
        val bp = g.bipartition()!!
        assertTrue(bp.first.size != bp.second.size, "P3's two parts must be unequal for this test to be meaningful")
        assertNull(buildCadicalEncodingSideSwapped(g, uniform), "swap cannot be a bijection when |A| != |B| -- must return null, not an unsound partial encoding")
    }
}