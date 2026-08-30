// 1-WL (color refinement) — JNI entry points backed by a hand-rolled
// Paige-Tarjan style partition refinement (ported from an older gi.NativePhases
// prototype), replacing the previous nauty refine()-based implementation.
//
// Maintains color classes cc[c] and a work-queue of "splitter" colors. Popping
// a splitter, every neighboring color with vertices of varying in-splitter
// degree is split: the residue at that color's own min degree keeps its id,
// every other distinct degree value gets a fresh id. New ids — and, per
// Hopcroft's rule, the smaller side of any split — are re-queued, so the loop
// reaches the coarsest equitable partition (the 1-WL / color-refinement fixed
// point) in O(m log n).
//
// Output convention (unchanged from the nauty version): an IntArray of size
// maxVertex+1 where result[v] = compact 0..k-1 stable color class of v. Color
// ids are assigned at creation and never reassigned, and a created id is never
// fully vacated by a later split (the split that targets it always keeps its
// own min-degree residue in place), so ids stay dense over 0..lastColor with
// no compaction pass needed.

#include <jni.h>
#include <algorithm>
#include <deque>
#include <set>
#include <vector>

namespace {

std::vector<int32_t> run1WL(const int* offsets, const int* neighbors, int n,
                             const std::vector<int32_t>& vertexColors) {
    if (n == 0) return {};
    if (n == 1) return {0};

    // Normalize initial colors to a dense 0..k-1 range (all-0 if uniform).
    std::vector<int> initColor(n, 0);
    int numInit = 1;
    if (!vertexColors.empty()) {
        std::vector<int> distinct(vertexColors.begin(), vertexColors.end());
        std::sort(distinct.begin(), distinct.end());
        distinct.erase(std::unique(distinct.begin(), distinct.end()), distinct.end());
        numInit = (int)distinct.size();
        for (int v = 0; v < n; v++) {
            initColor[v] = (int)(std::lower_bound(distinct.begin(), distinct.end(), vertexColors[v]) - distinct.begin());
        }
    }

    int MAX = n + 2;
    std::vector<int> coloring(n), cd(n, 0);
    std::vector<std::vector<int>> cc(MAX), pdcc(MAX);
    for (int v = 0; v < n; v++) { coloring[v] = initColor[v]; cc[initColor[v]].push_back(v); }

    std::vector<int> maxCD(MAX, 0), minCD(MAX, 0);
    std::vector<bool> inStack(MAX, false);

    int lastColor = numInit - 1;
    std::deque<int> stk;
    for (int c = 0; c < numInit; c++) { stk.push_back(c); inStack[c] = true; }

    while (!stk.empty()) {
        int cur = stk.front(); stk.pop_front(); inStack[cur] = false;

        // calculateColorDegrees
        std::set<int> adj;
        for (int v : cc[cur]) {
            for (int i = offsets[v]; i < offsets[v + 1]; i++) {
                int w = neighbors[i];
                if (++cd[w] == 1) pdcc[coloring[w]].push_back(w);
                adj.insert(coloring[w]);
                if (cd[w] > maxCD[coloring[w]]) maxCD[coloring[w]] = cd[w];
            }
        }
        for (int c : adj) {
            if (cc[c].size() != pdcc[c].size()) { minCD[c] = 0; }
            else {
                minCD[c] = maxCD[c];
                for (int v : pdcc[c]) if (cd[v] < minCD[c]) minCD[c] = cd[v];
            }
        }

        // splitUpColor for adjacent colors with varying degree, in ascending order
        std::vector<int> toSplit;
        for (int c : adj) if (minCD[c] < maxCD[c]) toSplit.push_back(c);
        std::sort(toSplit.begin(), toSplit.end());

        for (int color : toSplit) {
            int maxDeg = maxCD[color];
            std::vector<int> num(maxDeg + 1, 0);
            num[0] = (int)cc[color].size() - (int)pdcc[color].size();
            for (int v : pdcc[color]) num[cd[v]]++;

            int maxIdx = 0;
            for (int i = 1; i <= maxDeg; i++) if (num[i] > num[maxIdx]) maxIdx = i;

            std::vector<int> nm(maxDeg + 1, -1);
            bool curInStk = inStack[color];
            for (int i = 0; i <= maxDeg; i++) {
                if (!num[i]) continue;
                if (i == minCD[color]) {
                    nm[i] = color;
                    if (!curInStk && maxIdx != i) { stk.push_back(color); inStack[color] = true; }
                } else {
                    ++lastColor;
                    if (lastColor >= (int)cc.size()) {
                        cc.resize(lastColor + 1); pdcc.resize(lastColor + 1);
                        maxCD.resize(lastColor + 1, 0); minCD.resize(lastColor + 1, 0);
                        inStack.resize(lastColor + 1, false);
                    }
                    nm[i] = lastColor;
                    if (curInStk || i != maxIdx) { stk.push_back(lastColor); inStack[lastColor] = true; }
                }
            }
            for (int v : pdcc[color]) {
                int nc = nm[cd[v]];
                if (nc != color) {
                    auto& cls = cc[color];
                    auto it = std::find(cls.begin(), cls.end(), v);
                    if (it != cls.end()) { *it = cls.back(); cls.pop_back(); }
                    cc[nc].push_back(v);
                    coloring[v] = nc;
                }
            }
        }

        // cleanup
        for (int c : adj) { for (int v : pdcc[c]) cd[v] = 0; maxCD[c] = 0; pdcc[c].clear(); }
    }

    std::vector<int32_t> result(coloring.begin(), coloring.end());
    return result;
}

std::vector<int32_t> readIntArray(JNIEnv* env, jintArray arr) {
    jsize len = env->GetArrayLength(arr);
    std::vector<int32_t> buf(len);
    env->GetIntArrayRegion(arr, 0, len, buf.data());
    return buf;
}

jintArray newIntArray(JNIEnv* env, const std::vector<int32_t>& v) {
    jintArray arr = env->NewIntArray((jsize)v.size());
    // NewIntArray returns null (with a pending OutOfMemoryError already thrown on env) when the
    // JVM heap is exhausted -- confirmed as a real, reachable case, not theoretical: a 2026-08-29
    // campaign run hit exactly this under memory pressure and, without this check, the null `arr`
    // was passed straight into SetIntArrayRegion, which dereferences it -- a native SIGSEGV instead
    // of the clean, already-handled-elsewhere-in-this-campaign Java OutOfMemoryError this should
    // have been. Returning null here lets that pending exception propagate normally.
    if (arr == nullptr) return nullptr;
    env->SetIntArrayRegion(arr, 0, (jsize)v.size(), v.data());
    return arr;
}

}  // namespace

extern "C" {

JNIEXPORT jintArray JNICALL Java_dialysis_refinement_NativeWL1_compute1WLColors(
    JNIEnv* env, jclass, jintArray j_offsets, jintArray j_neighbors, jint maxVertex) {
    int n = (int)maxVertex + 1;
    auto offsets = readIntArray(env, j_offsets);
    auto neighbors = readIntArray(env, j_neighbors);
    auto result = run1WL(offsets.data(), neighbors.data(), n, {});
    return newIntArray(env, result);
}

JNIEXPORT jintArray JNICALL Java_dialysis_refinement_NativeWL1_compute1WLColorsFrom(
    JNIEnv* env, jclass, jintArray j_offsets, jintArray j_neighbors, jint maxVertex, jintArray j_colors) {
    int n = (int)maxVertex + 1;
    auto offsets = readIntArray(env, j_offsets);
    auto neighbors = readIntArray(env, j_neighbors);
    auto colors = readIntArray(env, j_colors);
    auto result = run1WL(offsets.data(), neighbors.data(), n, colors);
    return newIntArray(env, result);
}

}  // extern "C"