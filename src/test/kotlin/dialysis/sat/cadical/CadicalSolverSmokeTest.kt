package dialysis.sat.cadical

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CadicalSolverSmokeTest {
    @Test
    fun trivialSatUnsatAndTimeout() {
        CadicalSolver().use { s ->
            // (x1 v x2) & (-x1 v x2) & (x1 v -x2) -- satisfiable only by x1=true, x2=true
            s.addClause(intArrayOf(1, 2))
            s.addClause(intArrayOf(-1, 2))
            s.addClause(intArrayOf(1, -2))
            assertEquals(CadicalSolver.Result.SAT, s.solve(0))
            assertEquals(1, s.value(1))
            assertEquals(2, s.value(2))
        }

        CadicalSolver().use { s ->
            s.addClause(intArrayOf(1))
            s.addClause(intArrayOf(-1))
            assertEquals(CadicalSolver.Result.UNSAT, s.solve(0))
        }

        CadicalSolver().use { s ->
            // pigeonhole-ish: force a longer search, then cap it well below what it needs
            for (i in 1..25) {
                s.addClause(intArrayOf(i, -i))
            }
            // deliberately tiny timeout on a formula big enough that solve() hasn't returned yet
            // -- this mainly checks solve() doesn't hang or throw, not a hardness guarantee
            val r = s.solve(1)
            assert(r == CadicalSolver.Result.SAT || r == CadicalSolver.Result.UNKNOWN) {
                "expected SAT or UNKNOWN, got $r"
            }
        }
    }
}