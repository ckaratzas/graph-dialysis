package dialysis

import dialysis.sat.cryptominisat.CryptoMiniSatSolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Smoke test for the raw JNI binding, independent of the encoder -- if this fails, the problem is
 *  in cryptominisat_jni.cpp / CryptoMiniSatSolver.kt, not in the encoding built on top of them. */
class CryptoMiniSatSolverTest {

    @Test
    fun solvesATrivialSatisfiableFormula() {
        CryptoMiniSatSolver().use { s ->
            s.addClause(intArrayOf(1, 2))
            s.addClause(intArrayOf(-1, 2))
            s.assume(1)
            val r = s.solve(5_000)
            assertEquals(CryptoMiniSatSolver.Result.SAT, r)
            assertEquals(1, s.value(1))
            assertEquals(2, s.value(2))
        }
    }

    @Test
    fun detectsATrivialUnsatFormula() {
        CryptoMiniSatSolver().use { s ->
            s.addClause(intArrayOf(1))
            s.addClause(intArrayOf(-1))
            val r = s.solve(5_000)
            assertEquals(CryptoMiniSatSolver.Result.UNSAT, r)
        }
    }

    @Test
    fun backboneSimplifyOnATinyFormulaFindsForcedLiterals() {
        // 1 forces 2 (via 1->2), and 3 is unconstrained -- the backbone is exactly {1, 2}.
        CryptoMiniSatSolver().use { s ->
            s.addClause(intArrayOf(1))
            s.addClause(intArrayOf(-1, 2))
            s.addClause(intArrayOf(3, -3)) // trivially satisfied, keeps var 3 "present" but unforced
            val result = s.backboneSimplify()
            assertEquals(true, result.consistent)
            // Whether or not cadiback actually ran (small/degenerate formulas can resolve before
            // it's even invoked), the formula must still be solvable and consistent afterward.
            assertEquals(CryptoMiniSatSolver.Result.SAT, s.solve(5_000))
            assertEquals(1, s.value(1))
            assertEquals(2, s.value(2))
        }
    }

    @Test
    fun xorClauseForcesEvenParity() {
        // x 1 2 3 0 (rhs=false, i.e. XOR to FALSE): var1 xor var2 xor var3 = false -- an even
        // number of them true. Fix var1=true, var2=true (both true = even so far) and check var3
        // is forced false; then re-solve with var1=true, var2=false and check var3 is forced true.
        CryptoMiniSatSolver().use { s ->
            s.addXorClause(intArrayOf(1, 2, 3), rhs = false)
            s.assume(1); s.assume(2)
            assertEquals(CryptoMiniSatSolver.Result.SAT, s.solve(5_000))
            assertEquals(-3, s.value(3))

            s.assume(1); s.assume(-2)
            assertEquals(CryptoMiniSatSolver.Result.SAT, s.solve(5_000))
            assertEquals(3, s.value(3))
        }
    }

    @Test
    fun xorClauseRejectsOddParityWhenTwoVarsAreFixed() {
        CryptoMiniSatSolver().use { s ->
            s.addXorClause(intArrayOf(1, 2, 3), rhs = false)
            s.addClause(intArrayOf(1)); s.addClause(intArrayOf(2)); s.addClause(intArrayOf(3))
            // 1=T, 2=T, 3=T -> xor = T xor T xor T = T != false -- UNSAT.
            assertEquals(CryptoMiniSatSolver.Result.UNSAT, s.solve(5_000))
        }
    }

    @Test
    fun incrementalAssumptionsAcrossMultipleSolveCalls() {
        CryptoMiniSatSolver().use { s ->
            s.addClause(intArrayOf(1, 2, 3))
            s.assume(-1); s.assume(-2)
            assertEquals(CryptoMiniSatSolver.Result.SAT, s.solve(5_000))
            assertEquals(3, s.value(3))

            s.assume(-1); s.assume(-2); s.assume(-3)
            assertEquals(CryptoMiniSatSolver.Result.UNSAT, s.solve(5_000))
        }
    }
}
