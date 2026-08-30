package dialysis.sat.cadical

import dialysis.util.dialysisTempFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * JNI binding to CaDiCaL (Biere et al., MIT license, vendored under `src/main/cpp/cadical/`) via
 * its own IPASIR-conformant C API (`ccadical.h`).
 *
 * One instance = one persistent incremental solver: build the clause set once via [add]/
 * [addClause], then call [solve] many times with a fresh [assume] each time to test a different
 * assumption against the same fixed clause set. IPASIR has no native notion of a solve timeout;
 * [solve]'s [timeoutMs] is entirely our own addition, backed by a native deadline check
 * (`cadical_jni.cpp`'s `terminateCallback`) -- no JVM upcall per check, so there is no JNI
 * round-trip cost added to the hot search loop.
 *
 * NOT thread-safe: one native solver instance, serialize calls -- same caveat as every other
 * native binding in this codebase (TracesJni, NativeWL1, ColoredAHU).
 */
class CadicalSolver : AutoCloseable {
    companion object {
        init {
            val stream = CadicalSolver::class.java.getResourceAsStream("/libcadicaljni.so")
                ?: error("libcadicaljni.so not found in resources -- run src/main/cpp/build_cadical.sh first")
            val tmp = dialysisTempFile("libcadicaljni", ".so")
            tmp.toFile().deleteOnExit()
            stream.use { Files.copy(it, tmp, StandardCopyOption.REPLACE_EXISTING) }
            System.load(tmp.toAbsolutePath().toString())
        }
    }

    private external fun nativeInit(): Long
    private external fun nativeRelease(handle: Long)
    private external fun nativeAdd(handle: Long, lit: Int)
    private external fun nativeAssume(handle: Long, lit: Int)
    private external fun nativeSolve(handle: Long, deadlineEpochMs: Long): Int
    private external fun nativeVal(handle: Long, lit: Int): Int

    private val handle: Long = nativeInit()
    private var released = false

    /** IPASIR `add()`: a non-zero literal (DIMACS convention) extends the clause under
     *  construction; 0 terminates and submits it. Prefer [addClause] at call sites -- this is
     *  exposed mainly so [addClause] itself can be a one-liner. */
    fun add(lit: Int) = nativeAdd(handle, lit)

    fun addClause(lits: IntArray) {
        for (l in lits) nativeAdd(handle, l)
        nativeAdd(handle, 0)
    }

    /** IPASIR `assume()`: a per-[solve]-call assumption, cleared automatically after that call --
     *  not a permanent clause. */
    fun assume(lit: Int) = nativeAssume(handle, lit)

    enum class Result { SAT, UNSAT, UNKNOWN }

    /** [timeoutMs] <= 0 means no deadline (blocks until decided). Returns [Result.UNKNOWN] if the
     *  deadline is hit before the solver reaches a verdict -- never [Result.UNSAT] on a timeout,
     *  same guard rail as [dialysis.sat.queryOrbitMate]. */
    fun solve(timeoutMs: Long): Result {
        val deadline = if (timeoutMs <= 0) 0L else System.currentTimeMillis() + timeoutMs
        return when (nativeSolve(handle, deadline)) {
            10 -> Result.SAT
            20 -> Result.UNSAT
            else -> Result.UNKNOWN
        }
    }

    /** IPASIR `val()`: the value of [lit] under the last SAT model -- sign convention matches
     *  DIMACS (positive = true), only meaningful right after a [solve] that returned [Result.SAT]. */
    fun value(lit: Int): Int = nativeVal(handle, lit)

    override fun close() {
        if (!released) { nativeRelease(handle); released = true }
    }
}
