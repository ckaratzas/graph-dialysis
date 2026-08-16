// Exact canonical labeling / automorphism orbits — JNI entry point backed by
// the real Traces() algorithm (Piperno), not nauty's classical
// individualization-refinement (densenauty()). Both live in the same
// vendored library (src/main/cpp/nauty, see COPYRIGHT there) and solve the
// same problem, but Traces generally out-performs nauty on graphs with large
// automorphism groups — exactly the case this scheme's designated-class /
// interface-coloring machinery is built to lean on, since it's the one that
// forces recursion into the expensive exact path in the first place.
//
// Traces operates on nauty's sparsegraph representation (v/d/e arrays — the
// same shape as our own CSR offsets/neighbors, so no format translation is
// needed beyond widening offsets to size_t) rather than the dense bitset
// `graph*` densenauty() uses. That's a second reason to prefer it here: the
// subinstances this scheme certifies are exactly the kind of moderate-size,
// often highly symmetric graphs (post color-refinement, post folding) where
// sparse representation and Traces' search strategy both pay off.
//
// What this shim returns, and why it's this shape (mirrors nauty_wl1.cpp /
// the previous densenauty()-based version):
//   - the canonical labeling itself (`lab`, in/out — Traces overwrites it)
//   - automorphism orbits (needed independently for Lemma 2 dedup upstream)
//   - search stats, packed as doubles. TracesStats has no `numbadleaves`
//     analogue (nauty's leaf-quality metric is specific to nauty's own
//     search strategy) — reported as 0, not a stand-in.
// This deliberately does NOT return canonical adjacency bytes: Kotlin already
// holds the original graph and rebuilds canonical adjacency directly from
// `lab` (canonical position i <-> original vertex lab[i]) without marshaling
// an n x n bit matrix across the JNI boundary. `canong` here is therefore a
// disposable scratch sparsegraph required by Traces()'s contract but never
// read back.
//
// Not thread-safe: Traces' own scratch workspace is static, shared across
// calls — serialize calls from the JVM side (see TracesJni.kt).

#include "nauty/traces.h"
#include <jni.h>
#include <vector>

namespace {

std::vector<int32_t> readIntArray(JNIEnv* env, jintArray arr) {
    jsize len = env->GetArrayLength(arr);
    std::vector<int32_t> buf(len);
    env->GetIntArrayRegion(arr, 0, len, buf.data());
    return buf;
}

// Scratch for Java_dialysis_cl_TracesJni_nativeGenerators: Traces' userautomproc callback has no
// user-data parameter (it's a bare C function pointer, `void(*)(int,int*,int)`), so the only way
// to get a found generator OUT of the callback is a global — safe here because, like the rest of
// this file, calls are already required to be serialized from the JVM side (Traces' own scratch
// workspace is static/shared too, see file doc), so there is never a second call's callback live
// at the same time as this one's.
std::vector<std::vector<int32_t>>* g_generatorSink = nullptr;

void collectGenerator(int /* count */, int* perm, int n) {
    if (g_generatorSink != nullptr) g_generatorSink->emplace_back(perm, perm + n);
}

}  // namespace

extern "C" {

JNIEXPORT jdoubleArray JNICALL Java_dialysis_cl_TracesJni_nativeCanon(
    JNIEnv* env, jobject,
    jintArray j_offsets, jintArray j_neighbors, jint maxVertex,
    jintArray j_lab0, jintArray j_ptn0,
    jintArray j_labOut, jintArray j_orbitsOut,
    jboolean j_getcanon) {
    int n = (int)maxVertex + 1;
    auto offsets = readIntArray(env, j_offsets);     // size n+1, offsets[n] == nde
    auto neighbors = readIntArray(env, j_neighbors);  // size nde
    auto lab = readIntArray(env, j_lab0);    // consumed as input, overwritten with the canonical labeling
    auto ptn = readIntArray(env, j_ptn0);    // consumed as scratch by Traces; never read back

    size_t nde = neighbors.size();

    SG_DECL(g);
    SG_ALLOC(g, n, nde, "nauty_traces malloc");
    g.nv = n;
    g.nde = nde;
    for (int v = 0; v < n; v++) {
        g.v[v] = (size_t)offsets[v];
        g.d[v] = offsets[v + 1] - offsets[v];
    }
    for (size_t i = 0; i < nde; i++) g.e[i] = neighbors[i];

    SG_DECL(canong);   // mandatory scratch for getcanon=TRUE; unread

    std::vector<int32_t> orbits(n);

    // Detect the trivial single-cell partition: lab == identity and ptn has no cell
    // boundary before the last position (exactly one cell spanning all n vertices — the
    // "uncolored" case, e.g. true-orbit measurement). Traces has a dedicated fast path for
    // exactly this case, gated by options.defaultptn (see traces.c: the `ti->regular` /
    // defaultptn branch skips the general partition-processing loop entirely) — the same
    // path dreadnaut uses whenever no partition command is given. Manually encoding an
    // equivalent one-cell partition and passing defaultptn=FALSE (the previous behavior)
    // takes the slow general-purpose path instead: measured ~12x slower (26.7s vs 2.2s)
    // on an uncolored 2808-vertex rigid-CFI instance. A genuinely colored (multi-cell)
    // partition must still set defaultptn=FALSE — that flag means "ignore whatever
    // partition you were given," which is only correct when it WAS the trivial one.
    bool isTrivialPartition = true;
    for (int i = 0; i < n && isTrivialPartition; i++) {
        if (lab[i] != i) isTrivialPartition = false;
        if (i < n - 1 && ptn[i] == 0) isTrivialPartition = false;
    }

    DEFAULTOPTIONS_TRACES(options);
    // getcanon=TRUE asks Traces to additionally PROVE canonicity (no other search branch
    // produces a smaller labeling) — real, often substantial extra search cost on top of
    // just finding the automorphism group/orbits. A caller that only wants orbits (e.g.
    // true-orbit measurement, never CanonicalLabeler.certificate/canonicalLabeling) should
    // pass getcanon=false: dreadnaut's own default (DEFAULTOPTIONS_TRACES has getcanon=FALSE)
    // is exactly this, which is why plain orbit/group queries there are so much cheaper than
    // this wrapper's previous always-TRUE behavior.
    options.getcanon = j_getcanon;
    options.defaultptn = isTrivialPartition ? TRUE : FALSE;

    TracesStats stats;
    Traces(&g, lab.data(), ptn.data(), orbits.data(), &options, &stats, &canong);

    env->SetIntArrayRegion(j_labOut, 0, n, lab.data());
    env->SetIntArrayRegion(j_orbitsOut, 0, n, orbits.data());

    SG_FREE(g);
    SG_FREE(canong);

    double statsOut[6] = {
        (double)stats.numnodes, 0.0 /* no badLeaves analogue in Traces */, (double)stats.numorbits,
        (double)stats.numgenerators, stats.grpsize1, (double)stats.grpsize2,
    };
    jdoubleArray result = env->NewDoubleArray(6);
    env->SetDoubleArrayRegion(result, 0, 6, statsOut);
    return result;
}

// Aut(colored graph)'s GENERATORS (paper "piece-coset" scheme's `autgrp` — see PIECE_COSET_SPEC.md
// 0.4): unlike nativeCanon above (which only ever surfaced group ORDER via TracesStats, never the
// actual permutations), this collects every generator Traces finds via its own userautomproc
// callback (traces.h: the vendored library already supports this, just not previously wired to
// JNI) and returns them as one jintArray per generator, each of length n (a full permutation of
// [0,n), not just its support). getcanon is always FALSE here: this call only wants the
// automorphism group, never a proof of canonicity — same rationale as nativeCanon's own
// getcanon=false path (measured ~12x cheaper on an uncolored rigid-CFI instance).
JNIEXPORT jobjectArray JNICALL Java_dialysis_cl_TracesJni_nativeGenerators(
    JNIEnv* env, jobject,
    jintArray j_offsets, jintArray j_neighbors, jint maxVertex,
    jintArray j_lab0, jintArray j_ptn0) {
    int n = (int)maxVertex + 1;
    auto offsets = readIntArray(env, j_offsets);
    auto neighbors = readIntArray(env, j_neighbors);
    auto lab = readIntArray(env, j_lab0);
    auto ptn = readIntArray(env, j_ptn0);

    size_t nde = neighbors.size();

    SG_DECL(g);
    SG_ALLOC(g, n, nde, "nauty_traces malloc");
    g.nv = n;
    g.nde = nde;
    for (int v = 0; v < n; v++) {
        g.v[v] = (size_t)offsets[v];
        g.d[v] = offsets[v + 1] - offsets[v];
    }
    for (size_t i = 0; i < nde; i++) g.e[i] = neighbors[i];

    SG_DECL(canong);   // mandatory scratch even with getcanon=FALSE; unread

    bool isTrivialPartition = true;
    for (int i = 0; i < n && isTrivialPartition; i++) {
        if (lab[i] != i) isTrivialPartition = false;
        if (i < n - 1 && ptn[i] == 0) isTrivialPartition = false;
    }

    std::vector<int32_t> orbits(n);   // Traces requires this buffer; not returned here

    DEFAULTOPTIONS_TRACES(options);
    options.getcanon = FALSE;
    options.defaultptn = isTrivialPartition ? TRUE : FALSE;
    options.userautomproc = &collectGenerator;

    std::vector<std::vector<int32_t>> collected;
    g_generatorSink = &collected;

    TracesStats stats;
    Traces(&g, lab.data(), ptn.data(), orbits.data(), &options, &stats, &canong);

    g_generatorSink = nullptr;

    SG_FREE(g);
    SG_FREE(canong);

    jclass intArrayClass = env->FindClass("[I");
    jobjectArray result = env->NewObjectArray((jsize)collected.size(), intArrayClass, nullptr);
    for (size_t i = 0; i < collected.size(); i++) {
        jintArray perm = env->NewIntArray(n);
        env->SetIntArrayRegion(perm, 0, n, collected[i].data());
        env->SetObjectArrayElement(result, (jsize)i, perm);
        env->DeleteLocalRef(perm);
    }
    return result;
}

}  // extern "C"