// Colored AHU tree canonicalization — JNI entry points.
//
// Each vertex carries an optional string color.  Absent color is "uncolored"
// (canonical prefix *).  The AHU label of a vertex v is the unique integer
// assigned to the triple (colored, colorString, sorted child labels).
//
// Post-order invariant: every child label is numerically smaller than its
// parent's, so ensure_strings_up_to(L) always finds all dependencies built.
//
// Reserved: the color string "*" is reserved for the uncolored sentinel.
// Passing "*" as a color is undefined behaviour — validate on the Kotlin side.

#include <jni.h>
#include <vector>
#include <unordered_map>
#include <string>
#include <algorithm>
#include <functional>
#include <cstdint>

// ── Registry ──────────────────────────────────────────────────────────────────

struct Key {
    bool        colored;        // false = uncolored (canonical prefix *)
    std::string color;          // empty when !colored
    std::vector<int> children;  // sorted ascending AHU child labels

    bool operator==(const Key& o) const {
        return colored == o.colored && color == o.color && children == o.children;
    }
};

struct KeyHash {
    size_t operator()(const Key& k) const noexcept {
        uint64_t h = k.colored
            ? std::hash<std::string>{}(k.color)
            : UINT64_C(0xdeadbeefcafebabe);   // fixed hash for uncolored
        h ^= (uint64_t)k.colored + UINT64_C(0x9e3779b9) + (h << 6) + (h >> 2);
        for (int x : k.children)
            h ^= (uint64_t)(uint32_t)x + UINT64_C(0x9e3779b9) + (h << 6) + (h >> 2);
        return (size_t)h;
    }
};

static std::unordered_map<Key, int, KeyHash> g_key_to_label;
static std::vector<Key>                      g_label_to_key;
static std::unordered_map<int, std::string>  g_label_to_str;
static int                                   g_counter = 0;

static void reset_registry() {
    g_key_to_label.clear();
    g_label_to_key.clear();
    g_label_to_str.clear();
    g_counter = 0;
}

static int intern(bool colored, const std::string& color, std::vector<int>& sorted_children) {
    Key k{colored, colored ? color : std::string{}, sorted_children};
    auto it = g_key_to_label.find(k);
    if (it != g_key_to_label.end()) return it->second;

    int label = g_counter++;
    g_key_to_label.emplace(k, label);
    g_label_to_key.push_back(std::move(k));
    return label;
}

// ── Canonical strings ─────────────────────────────────────────────────────────
//
// Format: "(*...)"        for uncolored vertices
//         "(colorStr...)" for colored vertices
// Children are sorted lexicographically so the result is CSR-order independent.

static void ensure_strings_up_to(int max_label) {
    for (int l = 0; l <= max_label && l < (int)g_label_to_key.size(); ++l) {
        if (g_label_to_str.count(l)) continue;
        const Key& k = g_label_to_key[l];
        std::string s = k.colored ? ("(" + k.color) : "(*";
        if (!k.children.empty()) {
            std::vector<std::string> cs;
            cs.reserve(k.children.size());
            for (int c : k.children) cs.push_back(g_label_to_str.at(c));
            std::sort(cs.begin(), cs.end());
            for (const auto& x : cs) s += x;
        }
        s += ")";
        g_label_to_str.emplace(l, std::move(s));
    }
}

// ── Tree encoding ─────────────────────────────────────────────────────────────

static void encode_tree(
    const int* offsets, const int* neighbors,
    const std::vector<bool>&        colored,
    const std::vector<std::string>& colors,
    int root, int N,
    int* labels)
{
    struct Frame { int node, parent, pos; };
    std::vector<Frame> stk;
    stk.reserve(N);
    stk.push_back({root, -1, offsets[root]});

    while (!stk.empty()) {
        int  top  = (int)stk.size() - 1;
        int  node = stk[top].node;
        int  par  = stk[top].parent;
        int& pos  = stk[top].pos;   // reference into reserved storage — no realloc

        while (pos < offsets[node + 1] && neighbors[pos] == par)
            ++pos;

        if (pos < offsets[node + 1]) {
            int child = neighbors[pos++];
            stk.push_back({child, node, offsets[child]});
        } else {
            stk.pop_back();
            std::vector<int> ch;
            ch.reserve((size_t)(offsets[node + 1] - offsets[node]));
            for (int i = offsets[node]; i < offsets[node + 1]; ++i) {
                int nb = neighbors[i];
                if (nb != par) ch.push_back(labels[nb]);
            }
            std::sort(ch.begin(), ch.end());
            labels[node] = intern(colored[node], colors[node], ch);
        }
    }
}

// ── JNI ───────────────────────────────────────────────────────────────────────

extern "C" {

// computeLabels(csrOffsets, csrNeighbors, maxVertex, initialColors: Array<String?>, root)
//   -> IntArray of size maxVertex+1; result[v] = AHU label (-1 if unreachable).
// initialColors[v] = null means vertex v is uncolored.
JNIEXPORT jintArray JNICALL Java_dialysis_ahu_ColoredAHU_computeLabels(
    JNIEnv* env, jclass,
    jintArray j_offsets, jintArray j_neighbors,
    jint maxVertex,
    jobjectArray j_colors,
    jint root)
{
    int N = (int)maxVertex + 1;
    jint* offsets   = env->GetIntArrayElements(j_offsets,   nullptr);
    jint* neighbors = env->GetIntArrayElements(j_neighbors, nullptr);

    std::vector<bool>        colored(N, false);
    std::vector<std::string> colors(N);
    for (int v = 0; v < N; ++v) {
        auto elem = (jstring)env->GetObjectArrayElement(j_colors, v);
        if (elem != nullptr) {
            const char* utf = env->GetStringUTFChars(elem, nullptr);
            colors[v]  = utf;
            colored[v] = true;
            env->ReleaseStringUTFChars(elem, utf);
        }
        env->DeleteLocalRef(elem);
    }

    std::vector<jint> result(N, -1);
    encode_tree(offsets, neighbors, colored, colors, (int)root, N, result.data());

    env->ReleaseIntArrayElements(j_offsets,   offsets,   JNI_ABORT);
    env->ReleaseIntArrayElements(j_neighbors, neighbors, JNI_ABORT);

    jintArray out = env->NewIntArray(N);
    env->SetIntArrayRegion(out, 0, N, result.data());
    return out;
}

// canonicalString(label) -> String
JNIEXPORT jstring JNICALL Java_dialysis_ahu_ColoredAHU_canonicalString(
    JNIEnv* env, jclass, jint label)
{
    if (label < 0 || label >= g_counter)
        return env->NewStringUTF("");
    ensure_strings_up_to(label);
    return env->NewStringUTF(g_label_to_str.at(label).c_str());
}

// reset() — clears the registry.  Called automatically inside ColoredAHU.compute().
JNIEXPORT void JNICALL Java_dialysis_ahu_ColoredAHU_reset(
    JNIEnv*, jclass)
{
    reset_registry();
}

} // extern "C"
