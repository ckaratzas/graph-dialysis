package dialysis.content

/**
 * Formal role tags: fixed, pairwise-distinct constants
 * used as the first element of a [Content.Tup] so that structurally different
 * "roles" (tree vertex, orphan, remainder component, ...) can never collide
 * even if the rest of their tuples happen to serialize identically. E.g. a
 * tree vertex (T, ahuLabel, chi) and a certified remainder component (R, id,
 * chi) must never compare equal just because ahuLabel == id.
 * Use [Tag.content] to embed one into a [Content] tree.
 */
enum class Tag { T, O, R, I, F, L, X, D }

/** Wraps this tag as a [Content.Str] for embedding in a [Content.Tup]. */
fun Tag.content(): Content.Str = Content.Str(name)

/** Murmur3 finalizer -- a cheap, deterministic bit-mixer used by [Content.MSet.hashCode] to turn
 *  a plain sum-of-children (order-independent, but collision-prone: e.g. {1,4} and {2,3} sum
 *  equal) into one that stays order-independent while spreading collisions apart. Pure function
 *  of its input, so it changes hash bucket distribution only, never equals()/hashCode() agreement. */
private fun avalanche(x0: Int): Int {
    var x = x0
    x = x xor (x ushr 16); x *= -0x7ee3623b
    x = x xor (x ushr 13); x *= -0x3d4d51cb
    x = x xor (x ushr 16)
    return x
}

/**
 * A structural, content-addressed value. This is the currency the whole
 * certification scheme trades in: two [Content] trees must compare / serialize
 * identically iff they are the same value, REGARDLESS of which JVM objects,
 * hash codes, or arena/interning ids happened to produce them. That "no
 * identity leakage" property is not a nice-to-have — a past implementation
 * (Python) picked the designated class by a tie-break that quietly leaked
 * arena ids, and it selected non-corresponding classes on a six-way-tied
 * instance (see the note in Certify.designatedClass). Every operation here
 * (serialize, equals, hashCode, compareTo) is defined purely in terms of the
 * recursive VALUE of the tree — never `.hashCode()`/`.toString()` of a
 * wrapped object, never insertion order, never a counter.
 *
 * - [Str] / [Num]: leaf values.
 * - [Tup]: an ORDERED tuple — position is semantic (e.g. (Tag, id, coloring)).
 * - [MSet]: an UNORDERED multiset — two [MSet]s with the same elements in a
 *   different order must be the same [Content] (this is what lets per-level
 *   multisets M_l(G,r) and folded child-color multisets compare correctly
 *   regardless of CSR/traversal order upstream).
 */
sealed interface Content : Comparable<Content> {
    /** Backing cache for [serialize] — see that function's doc for why this matters. */
    val serializeCache: SerializeCache

    data class Str(val s: String) : Content {
        override val serializeCache = SerializeCache()
    }
    data class Num(val v: Long) : Content {
        override val serializeCache = SerializeCache()
    }

    /**
     * [equals] stays the compiler-generated (structural, recursive over [items], with the
     * usual reference-equality fast path) one. [hashCode] is overridden ONLY to memoize it —
     * same rationale as [SerializeCache] but O(1)-sized (an `Int`, never a flattened string):
     * see [compareTo]'s doc for why nothing here routes through [serialize] anymore.
     */
    data class Tup(val items: List<Content>) : Content {
        override val serializeCache = SerializeCache()

        private var cachedHash: Int? = null
        override fun hashCode(): Int =
            cachedHash ?: items.fold(7) { acc, item -> 31 * acc + item.hashCode() }.also { cachedHash = it }
    }

    /**
     * Multiset: order of [items] is NOT semantic. [List] equality/hashCode
     * (what a data class would generate by default) IS order-sensitive, so
     * equals/hashCode are overridden here to be order-insensitive — otherwise
     * two [MSet]s that are the same value everywhere else in the pipeline
     * could disagree under `==`/`hashCode()` whenever their [items] lists
     * happened to differ only in order.
     *
     * Canonicalization and hashing are structural (recurse into [items] directly), NOT via
     * [serialize] — see [compareTo]'s doc for why: flattening a shared sub-DAG into one string
     * duplicates it once per embedding site, and this scheme nests certificates recursively (a
     * component's own certificate gets embedded into every one of its vertices' colors, one level
     * up), so that duplication compounds ~multiplicatively with recursion depth on deep inputs.
     * Structural comparison instead reuses the same object-level sharing (one shared reference
     * embedded at every site it's needed) rather than re-copying it into a string each time.
     */
    data class MSet(val items: List<Content>) : Content {
        override val serializeCache = SerializeCache()

        // Same memoization rationale as Tup.cachedHash / SerializeCache: equals/hashCode/
        // compareTo are called repeatedly on the same instance (HashMap probing, groupBy,
        // sorting) and canonicalOrder() would otherwise re-sort items.size entries every time.
        private var cachedHash: Int? = null
        private var sortedCache: List<Content>? = null

        override fun equals(other: Any?): Boolean =
            this === other || (other is MSet && canonicalOrder() == other.canonicalOrder())

        // A plain sum over child hashcodes is the textbook weak multiset hash -- {1,4} and {2,3}
        // collide, and on deeply nested MSets that collision compounds at every level, forcing
        // equals() to fall back to full recursive comparison far more often than it should.
        // Avalanching each child hash (murmur3 finalizer) before summing stays commutative/
        // associative (order-independent, as a multiset hash must be) while killing the cheap
        // collisions a raw sum invites -- a pure function of the child hashes, so equal objects
        // still hash equal; this changes bucket distribution only, never correctness.
        override fun hashCode(): Int =
            cachedHash ?: items.sumOf { avalanche(it.hashCode()) }.also { cachedHash = it }

        /** Canonical (order-independent) view of [items]: sorted by [Content]'s own structural
         *  [compareTo], never by [serialize] (see the class doc). */
        internal fun canonicalOrder(): List<Content> =
            sortedCache ?: items.sorted().also { sortedCache = it }
    }

    /**
     * A total order over [Content] values, defined structurally/recursively (type rank, then
     * value/items/canonical-multiset-order, recursing via this same [compareTo]) — NOT via
     * [serialize].[String.compareTo]. It only needs to be ONE order used consistently everywhere
     * Content values are compared (designated-class tie-breaks, cell ordering for the canonical
     * labeler, [MSet] canonicalization, ...); nothing depends on it matching [serialize]'s
     * specific byte-lexicographic shape. Going through [serialize] to get there was the OOM bug
     * — see [MSet]'s class doc.
     */
    override fun compareTo(other: Content): Int {
        if (this === other) return 0
        val rankDiff = typeRank() - other.typeRank()
        if (rankDiff != 0) return rankDiff
        return when (this) {
            is Str -> s.compareTo((other as Str).s)
            is Num -> v.compareTo((other as Num).v)
            is Tup -> {
                other as Tup
                for (i in 0 until minOf(items.size, other.items.size)) {
                    val c = items[i].compareTo(other.items[i])
                    if (c != 0) return c
                }
                items.size - other.items.size
            }
            is MSet -> {
                other as MSet
                val a = canonicalOrder()
                val b = other.canonicalOrder()
                for (i in 0 until minOf(a.size, b.size)) {
                    val c = a[i].compareTo(b[i])
                    if (c != 0) return c
                }
                a.size - b.size
            }
        }
    }
}

private fun Content.typeRank(): Int = when (this) {
    is Content.Str -> 0
    is Content.Num -> 1
    is Content.Tup -> 2
    is Content.MSet -> 3
}

/**
 * Canonical, arena-free serialization: depends only on the recursive
 * structure and values of [this], never on object identity. Equal [Content]
 * values (per [Content.equals]) MUST serialize identically, and structurally
 * different values must never collide.
 *
 * Encoding is length-prefixed (netstring-style: `<tag><count>:<payload>`)
 * specifically so that NO escaping is ever needed — an arbitrary [Content.Str]
 * payload may contain any characters, including ones that look like tags,
 * digits, or ':' / ';', with zero ambiguity, because the prefix says exactly
 * how many characters (for [Content.Str]) or child values (for [Content.Tup]
 * / [Content.MSet]) to consume next:
 *
 *  - `Str(s)`    -> `S<s.length>:<s>`                              e.g. `S3:abc`
 *  - `Num(v)`    -> `N<zero-padded unsigned-order 20-digit encoding>;` e.g. `N09223372036854775813;` for v=5
 *  - `Tup(xs)`   -> `T<xs.size>:` ++ xs[0].serialize() ++ ...
 *  - `MSet(xs)`  -> `M<xs.size>:` ++ sorted(xs.map(serialize())) ++ ...
 *
 * [Content.Num] is FIXED-WIDTH (20 digits: enough for any Long), not just
 * digits-until-terminator — a naive `"N$v;"` is self-delimiting but NOT
 * order-preserving under [Content]'s string-based `compareTo`: e.g. "N6;"
 * sorts AFTER "N10;" lexicographically even though 6 < 10 numerically, which
 * would silently break any tie-break over [Content.Num] values with different
 * digit counts. Mapping the signed Long into unsigned order (XOR the sign
 * bit) and zero-padding to a constant width instead makes lexicographic order
 * over the encoded string always agree with numeric order over the original
 * Long — "total order on Content = order on serializations" holds
 * everywhere, not just for equal-length encodings.
 *
 * The multiset sort is the one place order enters: sorting the CHILDREN'S
 * canonical serializations (not e.g. some incidental insertion order) is what
 * makes two [Content.MSet]s with the same elements serialize identically
 * regardless of how their [Content.MSet.items] list was built upstream.
 */
/**
 * Single-slot memoization cell for [Content.serialize]: plain (unsynchronized),
 * by the same single-threaded-per-certification-run convention already
 * documented on [ContentPool] and the native kernels. [Content] values are
 * immutable and [serialize] is a pure function of structure, so caching it is
 * semantically invisible — its only effect is turning repeated calls on the
 * same instance (constant during sorts, grouping, and MSet's own equals/
 * hashCode, all of which walk shared color values over and over) into a
 * single field read after the first.
 */
class SerializeCache {
    var value: String? = null
}

fun Content.serialize(): String {
    serializeCache.value?.let { return it }
    val computed = when (this) {
        is Content.Str -> "S${s.length}:$s"
        is Content.Num -> "N${(v xor Long.MIN_VALUE).toULong().toString().padStart(20, '0')};"
        is Content.Tup -> "T${items.size}:" + items.joinToString("") { it.serialize() }
        is Content.MSet -> "M${items.size}:" + items.map { it.serialize() }.sorted().joinToString("")
    }
    serializeCache.value = computed
    return computed
}

/**
 * Interning is a PERFORMANCE cache keyed by [serialize] — it must never affect
 * semantics: interned or not, two equal [Content] values still compare equal,
 * serialize identically, and order identically. Its only effect is that
 * repeated equal values collapse to one shared instance, which is cheaper to
 * hold in the certification cache and to compare by reference downstream.
 * Not thread-safe (plain [HashMap]); scope one pool per single-threaded
 * certification run rather than sharing it across threads.
 */
class ContentPool {
    private val pool = HashMap<String, Content>()

    /** Returns the canonical shared instance for [c], registering it if new. */
    fun intern(c: Content): Content = pool.getOrPut(c.serialize()) { c }
}