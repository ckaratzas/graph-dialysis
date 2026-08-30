package dialysis.sat.cryptominisat

import dialysis.util.dialysisTempFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * JNI binding to CryptoMiniSat 5.12.1 (Soos et al., MIT-licensed core), vendored together with its
 * real upstream "umbrella" dependencies -- `cadical`/`cadiback` -- under
 * `src/main/cpp/cms-stack/` (see `build_cryptominisat.sh`'s own doc for the exact pinned
 * commits/tags and why each one matters). Binds via a hand-written shim (`cryptominisat_jni.cpp`)
 * reproducing IPASIR's incremental-clause/per-solve-assumption semantics on top of
 * CMSat::SATSolver's own vector-based API -- see that shim's own doc for exactly how. API shape
 * deliberately mirrors [dialysis.sat.cadical.CadicalSolver] as closely as possible.
 *
 * Two independent, separately-verified reasons to reach for this solver over CaDiCaL, both
 * relevant on a parity-structured (CFI) instance:
 * 1. Native XOR detection + Gaussian elimination inside CryptoMiniSat's OWN CDCL engine, via
 *    M4RI (GPLv2, NOT vendored -- see `build_cryptominisat.sh`'s own note; falls back to plain CNF
 *    reasoning, silently, if `libm4ri-dev` isn't installed). Every solver instance this binding
 *    constructs turns this on aggressively (`cryptominisat_jni.cpp`'s `aggressiveGaussConf` --
 *    matches the CLI's `--gauss 1 --autodisablegauss 0 --maxmatrixrows 150000` bundle; OFF by
 *    default in the library otherwise, same as the CLI).
 * 2. [backboneSimplify] -- an EXPLICIT call into cadiback, which hands the whole current CNF to
 *    an embedded CaDiCaL instance to compute the formula's backbone. NOT on CMS's own default
 *    simplify schedule (confirmed by reading solver.cpp directly) -- must be called explicitly, or
 *    this whole umbrella dependency sits there linked but unused.
 *
 * This binding does NOT hand-construct XOR clauses itself (the encoder
 * ([dialysis.sat.cryptominisat.buildCryptoMiniSatEncoding]) emits the exact same plain CNF
 * [dialysis.sat.cadical.CadicalEncoder] does); it relies entirely on CryptoMiniSat's own
 * XOR-recovery preprocessing to find and exploit that structure from the CNF alone.
 *
 * NOT thread-safe: one native solver instance, serialize calls -- same caveat as every other
 * native binding in this codebase.
 */
class CryptoMiniSatSolver : AutoCloseable {
    companion object {
        init {
            val stream = CryptoMiniSatSolver::class.java.getResourceAsStream("/libcryptominisatjni.so")
                ?: error("libcryptominisatjni.so not found in resources -- run src/main/cpp/build_cryptominisat.sh first")
            val tmp = dialysisTempFile("libcryptominisatjni", ".so")
            tmp.toFile().deleteOnExit()
            stream.use { Files.copy(it, tmp, StandardCopyOption.REPLACE_EXISTING) }
            System.load(tmp.toAbsolutePath().toString())
        }
    }

    private external fun nativeInit(): Long
    private external fun nativeRelease(handle: Long)
    private external fun nativeAdd(handle: Long, lit: Int)
    private external fun nativeAssume(handle: Long, lit: Int)
    private external fun nativeAddXorClause(handle: Long, vars: IntArray, rhs: Boolean)
    private external fun nativeSolve(handle: Long, timeoutMs: Long): Int
    private external fun nativeVal(handle: Long, lit: Int): Int
    private external fun nativeSimplify(handle: Long, timeoutMs: Long): Int
    private external fun nativeBackboneSimplify(handle: Long, maxConfl: Long): Int
    private external fun nativeRecoveredXorCount(handle: Long): Int
    private external fun nativeSetVerbosity(handle: Long, verbosity: Int)
    private external fun nativePrintStats(handle: Long)

    private val handle: Long = nativeInit()
    private var released = false

    fun add(lit: Int) = nativeAdd(handle, lit)

    fun addClause(lits: IntArray) {
        for (l in lits) nativeAdd(handle, l)
        nativeAdd(handle, 0)
    }

    fun assume(lit: Int) = nativeAssume(handle, lit)

    /** Native XOR constraint (the DIMACS `x` line extension): [vars] (1-indexed, DIMACS-style --
     *  NOT literals, plain variable numbers) must XOR to [rhs]. This is the one call this binding
     *  has that plain CaDiCaL has no equivalent for -- it's what actually feeds CMS's Gauss/matrix
     *  engine directly, bypassing xorfinder's pattern-matching recovery entirely (see this
     *  project's own empirical finding: xorfinder recovers nothing from a plain permutation-matrix
     *  CNF encoding, on either the real benchmark files or a from-scratch textbook CFI gadget
     *  graph -- native `x` lines are the only way to actually exercise Gauss on this kind of
     *  formula). Only emit a clause here after empirically validating it against REAL computed
     *  automorphisms (see [dialysis.MultipedeFlipParityValidationTest]) -- an incorrect XOR clause
     *  would silently make the encoding unsound. */
    fun addXorClause(vars: IntArray, rhs: Boolean) = nativeAddXorClause(handle, vars, rhs)

    /** Direct, unambiguous evidence of whether Gauss/XOR reasoning has anything to work with AT
     *  ALL: the number of XOR constraints CMS's own xorfinder actually recovered from the plain
     *  CNF, as of whenever this is called (call after [simplify] to see what xor-finding, part of
     *  its "occ-xor" schedule token, found). Zero here means Gauss is configured correctly but
     *  there is nothing for it to eliminate in THIS formula -- a fundamentally different finding
     *  from "we wired it up wrong". See [dialysis.sat.cryptominisat.buildCryptoMiniSatEncoding]'s
     *  own doc: the encoder never emits XOR clauses directly, so whatever xorfinder recovers here
     *  is exactly (and only) what it could reconstruct by pattern-matching the plain bijection/
     *  edge-preservation CNF -- a permutation-matrix encoding is not obviously that pattern. */
    fun recoveredXorCount(): Int = nativeRecoveredXorCount(handle)

    /** 0 = silent (this binding's default elsewhere). Anything higher makes CMS print its own
     *  diagnostic lines (occ-xor/Gauss included) directly to process stdout during [simplify]/
     *  [solve]/[backboneSimplify] -- call before whichever of those you want visibility into. */
    fun setVerbosity(verbosity: Int) = nativeSetVerbosity(handle, verbosity)

    /** Prints CMS's own internal stats (conflicts, propagations, and -- if any ran -- Gauss
     *  matrix/XOR counters) directly to process stdout. Call after solving/simplifying, not
     *  before -- there is nothing to report yet on a fresh solver. */
    fun printStats() = nativePrintStats(handle)

    enum class Result { SAT, UNSAT, UNKNOWN }

    /** [timeoutMs] <= 0 means no deadline (blocks until decided). Returns [Result.UNKNOWN] if the
     *  budget is exhausted before the solver reaches a verdict -- never [Result.UNSAT] on a
     *  timeout, same guard rail as every other solver binding in this codebase. */
    fun solve(timeoutMs: Long): Result {
        return when (nativeSolve(handle, timeoutMs)) {
            10 -> Result.SAT
            20 -> Result.UNSAT
            else -> Result.UNKNOWN
        }
    }

    fun value(lit: Int): Int = nativeVal(handle, lit)

    /** Forces CMS's own simplify pipeline to run NOW -- in particular occ-xor finding and
     *  on-the-fly Gauss elimination (both configured aggressively in [nativeInit] -- see the
     *  native doc), which are otherwise only scheduled on some LATER internal restart, not the
     *  very first call. Call this BEFORE [backboneSimplify]: Gauss can only shrink what cadiback
     *  ends up seeing if it runs first, not after cadiback has already consumed the full
     *  unsimplified formula. [timeoutMs] <= 0 means no deadline. */
    fun simplify(timeoutMs: Long = 0): Result {
        return when (nativeSimplify(handle, timeoutMs)) {
            10 -> Result.SAT
            20 -> Result.UNSAT
            else -> Result.UNKNOWN
        }
    }

    data class BackboneResult(val consistent: Boolean, val finished: Boolean)

    /** The actual "umbrella" step (see `cryptominisat_jni.cpp`'s own doc on
     *  `nativeBackboneSimplify`): hands the CURRENT full CNF to cadiback (an embedded CaDiCaL
     *  instance) to compute the formula's backbone, feeding any forced literals found back in as
     *  new unit clauses. NOT part of this solver's default schedule -- must be called explicitly.
     *  Call [simplify] first (see its own doc on why order matters here).
     *
     *  CAUTION: [maxConfl] is accepted but NOT enforced at the exact cadiback version this binding
     *  is built against (see the native doc) -- this call has no actual internal timeout. Never
     *  call it on a formula you haven't already bounded some other way (instance size, an
     *  external process-level deadline, ...). */
    fun backboneSimplify(maxConfl: Long = 30_000L): BackboneResult {
        val packed = nativeBackboneSimplify(handle, maxConfl)
        return BackboneResult(consistent = (packed and 1) != 0, finished = (packed and 2) != 0)
    }

    override fun close() {
        if (!released) { nativeRelease(handle); released = true }
    }
}
