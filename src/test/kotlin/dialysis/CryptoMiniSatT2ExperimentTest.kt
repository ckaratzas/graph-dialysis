package dialysis

import dialysis.content.Content
import dialysis.refinement.dispatchColouring
import dialysis.sat.cadical.buildCadicalEncoding
import dialysis.sat.cadical.driveToOrbitsCadical
import dialysis.sat.cryptominisat.buildCryptoMiniSatEncoding
import dialysis.sat.cryptominisat.driveToOrbitsCryptoMiniSat
import dialysis.util.GraphIO
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Head-to-head: CaDiCaL (plain CDCL) vs the REAL CryptoMiniSat "umbrella" stack -- its own CDCL
 * engine with native XOR detection + Gaussian elimination (via vendored M4RI), PLUS an explicit
 * [dialysis.sat.cryptominisat.CryptoMiniSatSolver.backboneSimplify] call that hands the whole
 * formula to cadiback (an embedded CaDiCaL instance) once up front -- see build_cryptominisat.sh's
 * own doc on why this needed three separately vendored/pinned repos, not just CryptoMiniSat alone.
 * Same encoding both sides (buildCryptoMiniSatEncoding is a literal copy of
 * buildCadicalEncodingHybrid targeting the other solver -- see its own doc), same short/long
 * two-pass timeout budget. `cfi-rigid-t2` is a non-bipartite CFI family built from parity/twist
 * gadgets -- exactly the structure this stack's XOR-recovery and backbone-simplification are meant
 * to exploit that a plain CDCL solver has to rediscover through unit propagation alone.
 *
 * Deliberately TIGHT timeouts (not this codebase's usual defaults) -- the point is to see whether
 * either solver leaves survivors UNKNOWN at a budget where the other doesn't, not to see both
 * casually resolve everything.
 */
class CryptoMiniSatT2ExperimentTest {

    @Test
    fun compareOnOneT2Instance() {
        val path = "graphs/cfi-rigid-t2/cfi-rigid-t2-0960-01-1"
        val shortMs = 200L
        val longMs = 3_000L

        val g = GraphIO.loadDimacs(File(path).toPath())
        val dispatch = dispatchColouring(g)
        val colorOf: (Int) -> Content = { v -> dispatch.colouring[v] }

        val (cadicalSolver, cadicalEncoding) = buildCadicalEncoding(g, colorOf)
        val cadicalT0 = System.currentTimeMillis()
        val cadicalResult = try {
            driveToOrbitsCadical(g, cadicalSolver, cadicalEncoding, swapPair = null, timeoutMs = longMs, shortMs = shortMs)
        } finally {
            cadicalSolver.close()
        }
        val cadicalMs = System.currentTimeMillis() - cadicalT0

        val (cmsSolver, cmsEncoding) = buildCryptoMiniSatEncoding(g, colorOf)
        val cmsT0 = System.currentTimeMillis()
        val cmsResult = try {
            // Direct, unambiguous check -- before trusting any timing number, confirm Gauss/XOR
            // reasoning actually has something to do at all. Zero here (even after simplify())
            // means the permutation-matrix encoding doesn't expose XOR-recognizable structure to
            // CMS's own pattern-based xorfinder, which is a finding about the ENCODING, not about
            // whether the solver is configured correctly.
            println("  recovered XOR count BEFORE simplify: ${cmsSolver.recoveredXorCount()}")

            // Order matters (see CryptoMiniSatSolver.simplify's own doc): Gauss/occ-xor only gets
            // a chance to shrink the formula BEFORE cadiback consumes it if simplify() runs
            // first. Calling backboneSimplify() first would hand cadiback the full, un-simplified
            // CNF and never let Gauss touch what cadiback already has its own copy of.
            val simplifyT0 = System.currentTimeMillis()
            val simplifyResult = cmsSolver.simplify(longMs)
            println("  simplify (Gauss/occ-xor): result=$simplifyResult took_ms=${System.currentTimeMillis() - simplifyT0}")
            println("  recovered XOR count AFTER simplify: ${cmsSolver.recoveredXorCount()}")
            cmsSolver.printStats()

            // The actual "umbrella" step (see CryptoMiniSatSolver.backboneSimplify's own doc):
            // hands the whole (now Gauss-simplified) formula to cadiback (an embedded CaDiCaL
            // instance) once, before any query -- NOT on CMS's default schedule, so without this
            // explicit call the cadical/cadiback linkage would be present but never actually
            // exercised. No internal timeout at this cadiback version, so this MUST run before
            // anything else on an instance small enough that an unbounded call is still acceptable.
            val backboneT0 = System.currentTimeMillis()
            val backbone = cmsSolver.backboneSimplify()
            println("  backboneSimplify: consistent=${backbone.consistent} finished=${backbone.finished} took_ms=${System.currentTimeMillis() - backboneT0}")
            val driveResult = driveToOrbitsCryptoMiniSat(g, cmsSolver, cmsEncoding, timeoutMs = longMs, shortMs = shortMs)
            println("  recovered XOR count AFTER driving: ${cmsSolver.recoveredXorCount()}")
            cmsSolver.printStats()
            driveResult
        } finally {
            cmsSolver.close()
        }
        val cmsMs = System.currentTimeMillis() - cmsT0

        fun canonical(orbits: List<List<Int>>) = orbits.map { it.sorted() }.sortedBy { it.first() }

        println(
            "instance=$path n=${g.n} colouring_used=${dispatch.used} short_ms=$shortMs long_ms=$longMs\n" +
                "  CaDiCaL:       issued=${cadicalResult.queriesIssued} sat=${cadicalResult.queriesSat} unsat=${cadicalResult.queriesUnsat} " +
                "unknown=${cadicalResult.queriesUnknown} skipped=${cadicalResult.skippedAlreadyConnected} orbits=${cadicalResult.orbits.size} wall_ms=$cadicalMs\n" +
                "  CryptoMiniSat: issued=${cmsResult.queriesIssued} sat=${cmsResult.queriesSat} unsat=${cmsResult.queriesUnsat} " +
                "unknown=${cmsResult.queriesUnknown} skipped=${cmsResult.skippedAlreadyConnected} orbits=${cmsResult.orbits.size} wall_ms=$cmsMs",
        )

        if (cadicalResult.queriesUnknown == 0 && cmsResult.queriesUnknown == 0) {
            check(canonical(cadicalResult.orbits) == canonical(cmsResult.orbits)) {
                "both solvers fully certified but recovered DIFFERENT partitions -- a correctness bug in one of the two encoders/drivers, not a performance difference"
            }
            println("  Both fully certified, partitions identical.")
        } else {
            println("  At least one solver left unknowns at this budget -- partitions not directly comparable class-by-class, only unknown counts are.")
        }
    }
}
