package dialysis

import dialysis.gadgetxor.RealFileBypassedGadgetReconstruction
import dialysis.gadgetxor.RealFileBypassedGadgetXor
import dialysis.refinement.dispatchColouring
import dialysis.sat.cadical.buildCadicalEncoding
import dialysis.sat.cadical.driveToOrbitsCadical
import dialysis.sat.cryptominisat.buildCryptoMiniSatEncoding
import dialysis.sat.cryptominisat.driveToOrbitsCryptoMiniSat
import dialysis.util.GraphIO
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * The t2 counterpart of [RealFileGadgetXorSoundnessTest]: does adding the cluster-generalized
 * flip-parity XOR clause -- validated against real automorphisms in
 * [RealFileBypassedFlipParityValidationTest] -- keep the encoding sound on ACTUAL cfi-rigid-t2
 * files, checked on both a non-rigid file (orbits are non-trivial, something for the clause to
 * actually interact with) and a rigid one (the overwhelmingly common case in this family).
 */
class RealFileBypassedGadgetXorSoundnessTest {

    private fun canonical(orbits: List<List<Int>>) = orbits.map { it.sorted() }.sortedBy { it.first() }

    private fun compare(path: String) {
        val g = GraphIO.loadDimacs(File(path).toPath())
        val recon = RealFileBypassedGadgetReconstruction.loadAndReconstruct(path)
        val sided = RealFileBypassedGadgetXor.prepare(recon)
        val dispatch = dispatchColouring(g)
        val colorOf = { v: Int -> dispatch.colouring[v] }

        val (cadicalSolver, cadicalEncoding) = buildCadicalEncoding(g, colorOf)
        val cadicalT0 = System.currentTimeMillis()
        val cadicalResult = try {
            driveToOrbitsCadical(g, cadicalSolver, cadicalEncoding, swapPair = null, timeoutMs = 30_000, shortMs = 2_000)
        } finally {
            cadicalSolver.close()
        }
        val cadicalMs = System.currentTimeMillis() - cadicalT0

        val (plainCmsSolver, plainCmsEncoding) = buildCryptoMiniSatEncoding(g, colorOf)
        val plainT0 = System.currentTimeMillis()
        val plainResult = try {
            driveToOrbitsCryptoMiniSat(g, plainCmsSolver, plainCmsEncoding, timeoutMs = 30_000, shortMs = 2_000)
        } finally {
            plainCmsSolver.close()
        }
        val plainMs = System.currentTimeMillis() - plainT0

        val (xorCmsSolver, xorCmsEncoding) = buildCryptoMiniSatEncoding(g, colorOf)
        val xorT0 = System.currentTimeMillis()
        val xorResult = try {
            val clustersConstrained = RealFileBypassedGadgetXor.addClusterParityXors(xorCmsSolver, xorCmsEncoding, sided)
            println("$path: added XOR clauses for $clustersConstrained/${sided.clusters.size} clusters")
            driveToOrbitsCryptoMiniSat(g, xorCmsSolver, xorCmsEncoding, timeoutMs = 30_000, shortMs = 2_000)
        } finally {
            xorCmsSolver.close()
        }
        val xorMs = System.currentTimeMillis() - xorT0

        println(
            "$path: n=${g.n}\n" +
                "  CaDiCaL (plain):        issued=${cadicalResult.queriesIssued} unknown=${cadicalResult.queriesUnknown} orbits=${cadicalResult.orbits.size} wall_ms=$cadicalMs\n" +
                "  CryptoMiniSat (plain):  issued=${plainResult.queriesIssued} unknown=${plainResult.queriesUnknown} orbits=${plainResult.orbits.size} wall_ms=$plainMs\n" +
                "  CryptoMiniSat (+ cluster XOR): issued=${xorResult.queriesIssued} unknown=${xorResult.queriesUnknown} orbits=${xorResult.orbits.size} wall_ms=$xorMs",
        )

        assertEquals(0, cadicalResult.queriesUnknown)
        assertEquals(0, plainResult.queriesUnknown)
        assertEquals(0, xorResult.queriesUnknown)

        val canonicalCadical = canonical(cadicalResult.orbits)
        assertEquals(canonicalCadical, canonical(plainResult.orbits), "$path: plain CryptoMiniSat disagrees with CaDiCaL -- unrelated to the XOR clause")
        assertEquals(
            canonicalCadical, canonical(xorResult.orbits),
            "$path: XOR-augmented CryptoMiniSat recovered a DIFFERENT partition than plain CaDiCaL -- the cluster gadget XOR clause is UNSOUND on this real file, do not use it",
        )
        println("$path: all three agree on the recovered partition.")
    }

    @Test
    fun soundOnANonRigidT2File() {
        compare("graphs/cfi-rigid-t2/cfi-rigid-t2-0044-01-1")
    }

    @Test
    fun soundOnARigidT2File() {
        compare("graphs/cfi-rigid-t2/cfi-rigid-t2-0048-01-1")
    }
}
