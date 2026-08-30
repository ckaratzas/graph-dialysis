// JNI binding to CryptoMiniSat (Soos et al., MIT-licensed core, vendored under
// src/main/cpp/cms-stack/cryptominisat/ at tag 5.12.1, plus its cadical/cadiback "umbrella"
// dependencies -- see build_cryptominisat.sh's own doc) via its own C++ API
// (cryptominisat.h, class CMSat::SATSolver) -- there is no ready-made IPASIR-conformant C API
// vendored at this tag, so this shim reproduces IPASIR's incremental-clause and per-solve-
// assumption semantics on top of SATSolver's own vector-based API, mirroring cadical_jni.cpp's
// method surface exactly so dialysis.sat.cryptominisat.CryptoMiniSatSolver's Kotlin side is close
// to line-for-line identical to CadicalSolver.
//
// Variables: SATSolver is 0-indexed internally and requires new_vars(n) BEFORE any literal
// references a variable -- ensureVar grows it lazily on first use of each DIMACS (1-indexed) var.
//
// Clause/assumption buffering: SATSolver has no incremental "add one literal, 0 terminates" call
// like IPASIR's ipasir_add -- add_clause takes a whole std::vector<Lit> at once. Handle buffers the
// clause under construction (flushed to add_clause on lit==0) and the pending per-solve assumption
// set (flushed to solve()'s argument and cleared after, same "cleared after each solve" contract
// IPASIR's own ipasir_assume documents).
//
// Timeout: SATSolver::set_max_time(seconds) is CPU time consumed before the NEXT solve() call must
// return -- called fresh before every solve() here, so it acts as a per-call deadline the same way
// CadicalSolver's deadlineEpochMs does (no persistent budget carried across calls). CPU time, not
// wall clock, but single-threaded (never calling set_num_threads) makes the two equivalent enough
// for this binding's purpose.
//
// Gaussian elimination (the actual native-XOR reasoning this solver is being used for at all --
// see CryptoMiniSatSolver's own class doc) is OFF by default in this library, matching the CLI's
// own `--gauss`-family flags being opt-in. There is no single public setter for the whole bundle
// the CLI exposes (`--gauss 1 --autodisablegauss 0 --maxmatrixrows 150000 --matrixfinder 1
// --forcegauss 1 --itergauss 1`) -- the closest public API, SATSolver::set_allow_otf_gauss(), sets
// most of it (doFindXors=true, gaussconf.autodisable=false, allow_elim_xor_vars=true) but pins
// max_matrix_rows to 10000, not 150000. nativeInit below constructs a SolverConf DIRECTLY instead
// (this shim already includes CMS's internal src/ headers, not just the sanitized public API, so
// solverconf.h's fields are all reachable) to get the exact requested matrix-row cap. Two of the
// six requested flags have no equivalent in this CMS version at all: `matrixfinder` maps to
// GaussConf::doMatrixFind, which already defaults to true (nothing to force); `forcegauss` and
// `itergauss` were checked directly against solverconf.h/main.cpp and do not exist as fields or
// CLI flags at 5.12.1 -- they may be from an older/different CMS release's flag set.
//
// Not thread-safe: one native solver per Handle, same caveat as every other native binding in this
// codebase (CadicalSolver, TracesJni, NativeWL1, ColoredAHU).

#include "cryptominisat.h"
#include "solverconf.h"
#include <jni.h>
#include <cstdlib>
#include <vector>

using CMSat::Lit;
using CMSat::SATSolver;
using CMSat::SolverConf;
using CMSat::lbool;

namespace {

struct Handle {
    SATSolver* solver;
    std::vector<Lit> currentClause;
    std::vector<Lit> pendingAssumptions;
};

// See this file's own top-of-file doc. Constructed fresh per solver (SolverConf is copied BY
// VALUE into Searcher/Solver at construction time -- see solver.cpp's Solver(const SolverConf*,
// ...) constructor -- so this can safely be a local that goes out of scope right after `new
// SATSolver(&conf)` returns).
SolverConf aggressiveGaussConf() {
    SolverConf conf;
    conf.doFindXors = true;                        // --gauss 1
    conf.gaussconf.autodisable = false;             // --autodisablegauss 0
    conf.gaussconf.max_matrix_rows = 150000;        // --maxmatrixrows 150000
    conf.gaussconf.max_matrix_columns = 10000000;   // wide enough that max_matrix_rows is the
                                                     // binding constraint, not this
    conf.gaussconf.doMatrixFind = true;             // --matrixfinder 1 (already CMS's own default)
    conf.gaussconf.max_num_matrices = 10;
    conf.allow_elim_xor_vars = true;
    // Gauss/XOR-finding only actually RUNS as part of CMS's own simplify pipeline (the "occ-xor"
    // token in simplify_schedule_startup/nonstartup -- see solverconf.cpp), which is OFF by
    // default on the very first call (simplify_at_startup defaults to false) -- so without this,
    // the very first thing this binding did with a fresh formula (an explicit simplify() call, see
    // CryptoMiniSatSolver.simplify's own doc) would still run before Gauss ever got a chance to
    // simplify anything, not after. Force it on so simplify() actually exercises occ-xor/Gauss
    // immediately, not just on some later internal restart.
    conf.simplify_at_startup = true;
    conf.full_simplify_at_startup = true;
    return conf;
}

// DIMACS convention: a positive int is variable v (1-indexed), a negative int is its negation.
Lit toLit(jint dimacsLit) {
    uint32_t var0 = static_cast<uint32_t>(std::abs(static_cast<int>(dimacsLit)) - 1);
    return Lit(var0, dimacsLit < 0);
}

void ensureVar(Handle* h, uint32_t var0) {
    if (var0 >= h->solver->nVars()) {
        h->solver->new_vars(var0 + 1 - h->solver->nVars());
    }
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_dialysis_sat_cryptominisat_CryptoMiniSatSolver_nativeInit(JNIEnv*, jobject) {
    auto* h = new Handle();
    SolverConf conf = aggressiveGaussConf();
    h->solver = new SATSolver(&conf);
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL Java_dialysis_sat_cryptominisat_CryptoMiniSatSolver_nativeRelease(JNIEnv*, jobject, jlong handlePtr) {
    auto* h = reinterpret_cast<Handle*>(handlePtr);
    delete h->solver;
    delete h;
}

JNIEXPORT void JNICALL Java_dialysis_sat_cryptominisat_CryptoMiniSatSolver_nativeAdd(JNIEnv*, jobject, jlong handlePtr, jint lit) {
    auto* h = reinterpret_cast<Handle*>(handlePtr);
    if (lit == 0) {
        h->solver->add_clause(h->currentClause);
        h->currentClause.clear();
        return;
    }
    ensureVar(h, static_cast<uint32_t>(std::abs(static_cast<int>(lit)) - 1));
    h->currentClause.push_back(toLit(lit));
}

// vars are 1-indexed DIMACS-style variable numbers (matching every other call in this shim), NOT
// yet-0-indexed CMSat::Lit -- ensureVar is called per variable exactly like nativeAdd/nativeAssume
// do, since add_xor_clause bypasses the currentClause buffer entirely and could otherwise reference
// a variable the solver hasn't grown to cover yet. rhs=true means the literals XOR to TRUE (odd
// parity); false means they XOR to FALSE (even parity) -- e.g. the CFI gadget's own "even number
// of ports flipped" constraint is add_xor_clause(vars, rhs=false).
JNIEXPORT void JNICALL Java_dialysis_sat_cryptominisat_CryptoMiniSatSolver_nativeAddXorClause(JNIEnv* env, jobject, jlong handlePtr, jintArray vars, jboolean rhs) {
    auto* h = reinterpret_cast<Handle*>(handlePtr);
    jsize len = env->GetArrayLength(vars);
    jint* elems = env->GetIntArrayElements(vars, nullptr);
    std::vector<unsigned> cmsVars;
    cmsVars.reserve(len);
    for (jsize k = 0; k < len; ++k) {
        uint32_t var0 = static_cast<uint32_t>(elems[k] - 1);
        ensureVar(h, var0);
        cmsVars.push_back(var0);
    }
    env->ReleaseIntArrayElements(vars, elems, JNI_ABORT);
    h->solver->add_xor_clause(cmsVars, rhs == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_dialysis_sat_cryptominisat_CryptoMiniSatSolver_nativeAssume(JNIEnv*, jobject, jlong handlePtr, jint lit) {
    auto* h = reinterpret_cast<Handle*>(handlePtr);
    ensureVar(h, static_cast<uint32_t>(std::abs(static_cast<int>(lit)) - 1));
    h->pendingAssumptions.push_back(toLit(lit));
}

// timeoutMs <= 0 means no deadline (blocks until decided) -- set_max_time is simply skipped, same
// convention as CadicalSolver.nativeSolve's deadlineEpochMs <= 0.
JNIEXPORT jint JNICALL Java_dialysis_sat_cryptominisat_CryptoMiniSatSolver_nativeSolve(JNIEnv*, jobject, jlong handlePtr, jlong timeoutMs) {
    auto* h = reinterpret_cast<Handle*>(handlePtr);
    if (timeoutMs > 0) {
        h->solver->set_max_time(static_cast<double>(timeoutMs) / 1000.0);
    }
    lbool result = h->solver->solve(&h->pendingAssumptions);
    h->pendingAssumptions.clear();
    if (result == CMSat::l_True) return 10;   // IPASIR/DIMACS convention: 10=SAT, 20=UNSAT, 0=UNKNOWN
    if (result == CMSat::l_False) return 20;
    return 0;
}

// Direct, unambiguous evidence of whether Gauss/XOR reasoning has anything to work with AT ALL --
// get_recovered_xors() returns the actual XOR constraints CMS's own xorfinder recovered from the
// plain CNF (our encoder never emits XOR clauses directly -- see buildCryptoMiniSatEncoding's own
// doc -- so this is the only way to know whether xorfinder found any latent XOR structure in a
// permutation-matrix encoding at all, as opposed to "configured but nothing to find"). Returns just
// the COUNT (not the XORs themselves) -- this is a yes/no-plus-how-many verification probe, not a
// data-extraction API.
JNIEXPORT jint JNICALL Java_dialysis_sat_cryptominisat_CryptoMiniSatSolver_nativeRecoveredXorCount(JNIEnv*, jobject, jlong handlePtr) {
    auto* h = reinterpret_cast<Handle*>(handlePtr);
    return static_cast<jint>(h->solver->get_recovered_xors(false).size());
}

JNIEXPORT void JNICALL Java_dialysis_sat_cryptominisat_CryptoMiniSatSolver_nativeSetVerbosity(JNIEnv*, jobject, jlong handlePtr, jint verbosity) {
    auto* h = reinterpret_cast<Handle*>(handlePtr);
    h->solver->set_verbosity(static_cast<unsigned>(verbosity));
}

JNIEXPORT void JNICALL Java_dialysis_sat_cryptominisat_CryptoMiniSatSolver_nativePrintStats(JNIEnv*, jobject, jlong handlePtr) {
    auto* h = reinterpret_cast<Handle*>(handlePtr);
    h->solver->print_stats();
}

// Exposes SATSolver::simplify() directly, so a caller can force CMS's own simplify pipeline --
// including occ-xor finding and on-the-fly Gauss elimination (see aggressiveGaussConf's own doc on
// why simplify_at_startup/full_simplify_at_startup are forced on) -- to run BEFORE handing the
// formula to backbone_simpl/cadiback. Order matters: Gauss can only shrink/simplify a formula
// cadiback hasn't seen yet if it runs FIRST -- calling backboneSimplify before this would hand
// cadiback the full, un-simplified formula and never let Gauss touch what cadiback already
// consumed. Same IPASIR-style outcome convention as nativeSolve (10=SAT, 20=UNSAT, 0=UNKNOWN).
JNIEXPORT jint JNICALL Java_dialysis_sat_cryptominisat_CryptoMiniSatSolver_nativeSimplify(JNIEnv*, jobject, jlong handlePtr, jlong timeoutMs) {
    auto* h = reinterpret_cast<Handle*>(handlePtr);
    if (timeoutMs > 0) {
        h->solver->set_max_time(static_cast<double>(timeoutMs) / 1000.0);
    }
    lbool result = h->solver->simplify();
    if (result == CMSat::l_True) return 10;
    if (result == CMSat::l_False) return 20;
    return 0;
}

// Exposes SATSolver::backbone_simpl -- the actual "umbrella" inprocessing step (see
// build_cryptominisat.sh's own doc): hands the CURRENT full CNF to cadiback, which runs an
// embedded CaDiCaL instance to compute the formula's backbone (literals forced to one value in
// every satisfying assignment) and returns them as new unit clauses. NOT on CMS's default
// simplify schedule -- there is no public API to add it to that schedule, so this is the only way
// to actually exercise this path from outside the solver.
//
// CAUTION: at the exact cadiback commit this stack is pinned to (see build_cryptominisat.sh),
// CadiBack::doit() ignores the conflict-budget parameter entirely -- backbone_simpl's own
// max_confl argument is accepted but unused in THIS version, so this call runs cadiback/CaDiCaL to
// completion with NO internal timeout. Callers must bound it externally (e.g. don't call this on
// a large formula without a wrapping deadline of their own).
//
// Returns a packed int: bit 0 = solver still consistent (backbone_simpl's own bool return, false
// only if the backbone computation itself derived UNSAT); bit 1 = finished (a backbone was found
// and its forced literals were added as new unit clauses -- see backbone.cpp's own `finished =
// true` only on that path). 0 typically means cadiback returned neither SAT(10) nor UNSAT(20) to
// backbone_simpl (its own "unknown" outcome), i.e. nothing was learned.
JNIEXPORT jint JNICALL Java_dialysis_sat_cryptominisat_CryptoMiniSatSolver_nativeBackboneSimplify(JNIEnv*, jobject, jlong handlePtr, jlong maxConfl) {
    auto* h = reinterpret_cast<Handle*>(handlePtr);
    bool finished = false;
    bool ok = h->solver->backbone_simpl(maxConfl, finished);
    return (ok ? 1 : 0) | (finished ? 2 : 0);
}

JNIEXPORT jint JNICALL Java_dialysis_sat_cryptominisat_CryptoMiniSatSolver_nativeVal(JNIEnv*, jobject, jlong handlePtr, jint lit) {
    auto* h = reinterpret_cast<Handle*>(handlePtr);
    uint32_t var0 = static_cast<uint32_t>(std::abs(static_cast<int>(lit)) - 1);
    const auto& model = h->solver->get_model();
    if (var0 >= model.size()) return 0;
    lbool v = model[var0];
    if (v == CMSat::l_Undef) return 0;
    bool litIsTrue = (v == CMSat::l_True) == (lit > 0);
    return litIsTrue ? lit : -lit;
}

}  // extern "C"
