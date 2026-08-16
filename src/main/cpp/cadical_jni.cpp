// JNI binding to CaDiCaL (Biere et al., MIT license, vendored under
// src/main/cpp/cadical/ -- see COPYRIGHT/LICENSE there) via CaDiCaL's own
// IPASIR-conformant C API (cadical/src/ccadical.h) -- see CADICAL_MIGRATION_SPEC.md.
//
// One opaque handle (a jlong-boxed pointer to Handle below) = one persistent
// incremental solver instance, mirroring dialysis.sat's SAT4J usage: build the
// clause set once, then call solve() many times with different assumptions.
// IPASIR itself has no notion of a solve timeout, so the deadline is entirely
// our own addition: ccadical_set_terminate registers a callback ONCE at init
// time; that callback just compares against Handle::deadlineMs, which
// nativeSolve updates (and clears) around each call. No JVM upcall per check --
// CaDiCaL polls this callback purely in native code, so there is no JNI
// round-trip cost on the hot path.
//
// Not thread-safe: one native solver per Handle, but CaDiCaL itself is not
// safe for concurrent calls into the SAME instance -- serialize calls from the
// JVM side, same caveat as every other native binding in this codebase
// (TracesJni, NativeWL1, ColoredAHU).

#include "cadical/src/ccadical.h"
#include <jni.h>
#include <chrono>
#include <cstdint>

namespace {

struct Handle {
    CCaDiCaL* solver;
    volatile int64_t deadlineMs; // 0 = no deadline, i.e. never terminate early
};

int64_t nowMillis() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(system_clock::now().time_since_epoch()).count();
}

extern "C" int terminateCallback(void* state) {
    auto* h = static_cast<Handle*>(state);
    if (h->deadlineMs <= 0) return 0;
    return nowMillis() >= h->deadlineMs ? 1 : 0;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_dialysis_sat_cadical_CadicalSolver_nativeInit(JNIEnv*, jobject) {
    auto* h = new Handle();
    h->solver = ccadical_init();
    h->deadlineMs = 0;
    ccadical_set_terminate(h->solver, h, terminateCallback);
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL Java_dialysis_sat_cadical_CadicalSolver_nativeRelease(JNIEnv*, jobject, jlong handlePtr) {
    auto* h = reinterpret_cast<Handle*>(handlePtr);
    ccadical_release(h->solver);
    delete h;
}

JNIEXPORT void JNICALL Java_dialysis_sat_cadical_CadicalSolver_nativeAdd(JNIEnv*, jobject, jlong handlePtr, jint lit) {
    ccadical_add(reinterpret_cast<Handle*>(handlePtr)->solver, lit);
}

JNIEXPORT void JNICALL Java_dialysis_sat_cadical_CadicalSolver_nativeAssume(JNIEnv*, jobject, jlong handlePtr, jint lit) {
    ccadical_assume(reinterpret_cast<Handle*>(handlePtr)->solver, lit);
}

// deadlineEpochMs <= 0 disables the deadline for this call (solve() blocks until decided).
JNIEXPORT jint JNICALL Java_dialysis_sat_cadical_CadicalSolver_nativeSolve(JNIEnv*, jobject, jlong handlePtr, jlong deadlineEpochMs) {
    auto* h = reinterpret_cast<Handle*>(handlePtr);
    h->deadlineMs = deadlineEpochMs;
    int result = ccadical_solve(h->solver); // IPASIR convention: 10=SAT, 20=UNSAT, 0=UNKNOWN/terminated
    h->deadlineMs = 0;
    return result;
}

JNIEXPORT jint JNICALL Java_dialysis_sat_cadical_CadicalSolver_nativeVal(JNIEnv*, jobject, jlong handlePtr, jint lit) {
    return ccadical_val(reinterpret_cast<Handle*>(handlePtr)->solver, lit);
}

}  // extern "C"