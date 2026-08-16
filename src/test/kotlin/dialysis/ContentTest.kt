package dialysis

import dialysis.content.Content
import dialysis.content.ContentPool
import dialysis.content.Tag
import dialysis.content.content
import dialysis.content.serialize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ContentTest {

    // ── serialize: determinism + no cross-type / cross-shape collisions ──────

    @Test
    fun sameStructureSerializesIdentically() {
        val a = Content.Tup(listOf(Content.Str("x"), Content.Num(3)))
        val b = Content.Tup(listOf(Content.Str("x"), Content.Num(3)))
        assertEquals(a.serialize(), b.serialize())
    }

    @Test
    fun stringPayloadNeedsNoEscaping() {
        // Payloads containing digits, colons, semicolons, and tag-like letters
        // must not be confused with the length-prefix grammar.
        val tricky = Content.Str("S3:abcN1;T2:xxM0:")
        val plain = Content.Str("hello")
        assertNotEquals(tricky.serialize(), plain.serialize())
        // Round-trips to a distinct, self-consistent value (not corrupted into
        // looking like some other shape).
        assertEquals(tricky.serialize(), Content.Str("S3:abcN1;T2:xxM0:").serialize())
    }

    @Test
    fun concatenationAmbiguityIsAvoidedByLengthPrefix() {
        // Str("ab") + Str("c") must serialize differently from Str("a") + Str("bc"),
        // even though naive concatenation without length-prefixing would collide.
        val g1 = Content.Tup(listOf(Content.Str("ab"), Content.Str("c")))
        val g2 = Content.Tup(listOf(Content.Str("a"), Content.Str("bc")))
        assertNotEquals(g1.serialize(), g2.serialize())
    }

    @Test
    fun differentContentShapesNeverCollide() {
        val asStr = Content.Str("N1;")          // looks like a serialized Num
        val asNum = Content.Num(1)
        assertNotEquals(asStr.serialize(), asNum.serialize())

        val emptyTup = Content.Tup(emptyList())
        val emptyMSet = Content.MSet(emptyList())
        assertNotEquals(emptyTup.serialize(), emptyMSet.serialize())
    }

    @Test
    fun negativeNumbersSerializeUnambiguously() {
        assertNotEquals(Content.Num(-1).serialize(), Content.Num(1).serialize())
        val tup = Content.Tup(listOf(Content.Num(-12), Content.Num(3)))
        // Must not be confused with a single Num(-123), e.g. via naive concatenation.
        assertNotEquals(tup.serialize(), Content.Tup(listOf(Content.Num(-123))).serialize())
    }

    // ── Num ordering MUST be numeric, not the lexicographic order of the naive
    // decimal string — regression for a real bug: a bare "N$v;" encoding sorts
    // "N6;" AFTER "N10;" (compares '6' vs '1'), even though 6 < 10 numerically.
    // This silently broke Certifier.designatedClass's tie-break whenever two
    // tied classes' native-kernel ids happened to have different digit counts
    // (caught on Miyazaki vs. its twist: the same literal vertex class got
    // native ids 14 and 6 in the two graphs — its rank against OTHER tied
    // classes' ids flipped between the two, picking a non-corresponding
    // designated class). ─────────────────────────────────────────────────────

    @Test
    fun numOrderingIsNumericAcrossDigitCountBoundaries() {
        assertTrue(Content.Num(6) < Content.Num(10))
        assertTrue(Content.Num(9) < Content.Num(10))
        assertTrue(Content.Num(99) < Content.Num(100))
        assertTrue(Content.Num(6).serialize() < Content.Num(10).serialize())
    }

    @Test
    fun numOrderingHandlesNegativesAndZeroCorrectly() {
        assertTrue(Content.Num(-100) < Content.Num(-5))
        assertTrue(Content.Num(-1) < Content.Num(0))
        assertTrue(Content.Num(0) < Content.Num(1))
        assertTrue(Content.Num(Long.MIN_VALUE) < Content.Num(Long.MAX_VALUE))
        assertTrue(Content.Num(-1) < Content.Num(100))
    }

    @Test
    fun numOrderingIsConsistentAcrossManyValuesRegardlessOfDigitCount() {
        val values = listOf(-1000L, -17L, -1L, 0L, 1L, 6L, 9L, 10L, 15L, 16L, 17L, 99L, 100L, 12345L)
        val byNumericValue = values.sorted()
        val byContentOrder = values.map { Content.Num(it) }.sorted().map { it.v }
        assertEquals(byNumericValue, byContentOrder)
    }

    // ── Tup: ORDER is semantic ────────────────────────────────────────────────

    @Test
    fun tupOrderMatters() {
        val a = Content.Tup(listOf(Content.Num(1), Content.Num(2)))
        val b = Content.Tup(listOf(Content.Num(2), Content.Num(1)))
        assertNotEquals(a, b)
        assertNotEquals(a.serialize(), b.serialize())
    }

    // ── MSet: order is NOT semantic, for equals/hashCode/serialize alike ─────

    @Test
    fun mSetOrderDoesNotMatterForEquality() {
        val a = Content.MSet(listOf(Content.Num(1), Content.Num(2), Content.Str("x")))
        val b = Content.MSet(listOf(Content.Str("x"), Content.Num(2), Content.Num(1)))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(a.serialize(), b.serialize())
    }

    @Test
    fun mSetDistinguishesActuallyDifferentMultisets() {
        // Same elements, different multiplicity -> different multiset.
        val a = Content.MSet(listOf(Content.Num(1), Content.Num(1), Content.Num(2)))
        val b = Content.MSet(listOf(Content.Num(1), Content.Num(2), Content.Num(2)))
        assertNotEquals(a, b)
        assertNotEquals(a.serialize(), b.serialize())
    }

    @Test
    fun mSetInsideMapOrSetWorksByValue() {
        val a = Content.MSet(listOf(Content.Num(1), Content.Num(2)))
        val b = Content.MSet(listOf(Content.Num(2), Content.Num(1)))
        val seen = HashSet<Content>()
        seen.add(a)
        assertTrue(b in seen)   // order-insensitive hashCode/equals must make this true
    }

    // ── compareTo: a consistent total order, structural (NOT via serialize()) ──
    // See Content.kt's compareTo/MSet doc: routing comparison through serialize()
    // was the lattice-13 OOM bug (flattening a shared sub-DAG into a string
    // duplicates it once per embedding site). compareTo no longer needs to
    // match serialize()'s byte order, just be one order used consistently.

    @Test
    fun compareToIsAValidTotalOrderConsistentWithEquals() {
        val values = listOf(
            Content.Num(2),
            Content.Num(-5),
            Content.Str("a"),
            Content.Str("b"),
            Content.Tup(listOf(Content.Num(1))),
            Content.MSet(listOf(Content.Num(1), Content.Num(2))),
            Content.MSet(listOf(Content.Num(2), Content.Num(1))),  // same value, different input order
        )
        val sorted = values.sorted()
        for (i in 0 until sorted.size - 1) assertTrue(sorted[i] <= sorted[i + 1])
        for (a in values) for (b in values) assertEquals(a == b, a.compareTo(b) == 0)
    }

    @Test
    fun compareToIsStableAcrossRepeatedSorts() {
        val values = listOf(
            Content.MSet(listOf(Content.Num(3), Content.Str("z"), Content.Num(1))),
            Content.Tup(listOf(Content.Str("x"), Content.Num(1))),
            Content.Num(42),
        )
        assertEquals(values.sorted().map { it.serialize() }, values.sorted().map { it.serialize() })
    }

    @Test
    fun equalContentComparesEqual() {
        val a = Content.Tup(listOf(Content.Str("x"), Content.Num(1)))
        val b = Content.Tup(listOf(Content.Str("x"), Content.Num(1)))
        assertEquals(0, a.compareTo(b))
    }

    // ── Tag: pairwise-distinct role markers, embeddable in a Tup ─────────────

    @Test
    fun distinctTagsNeverCollideOnceEmbedded() {
        val serializedTags = Tag.entries.map { it.content().serialize() }.toSet()
        assertEquals(Tag.entries.size, serializedTags.size)
    }

    @Test
    fun tagDisambiguatesOtherwiseIdenticalTuples() {
        // A tree vertex (T, 7, chi) and a certified remainder component (R, 7, chi)
        // must never collide just because their id/coloring fields match.
        val chi = Content.Str("chi")
        val treeVertex = Content.Tup(listOf(Tag.T.content(), Content.Num(7), chi))
        val remainderComp = Content.Tup(listOf(Tag.R.content(), Content.Num(7), chi))
        assertNotEquals(treeVertex, remainderComp)
        assertNotEquals(treeVertex.serialize(), remainderComp.serialize())
    }

    // ── ContentPool: memoization only, never changes semantics ───────────────

    @Test
    fun internReturnsValueEqualContent() {
        val pool = ContentPool()
        val a = Content.Tup(listOf(Content.Str("x"), Content.Num(1)))
        val b = Content.Tup(listOf(Content.Str("x"), Content.Num(1)))
        val internedA = pool.intern(a)
        val internedB = pool.intern(b)
        assertEquals(a, internedA)
        assertEquals(internedA, internedB)
        assertSame(internedA, internedB)   // second intern reuses the first instance
    }

    @Test
    fun internDoesNotCollapseDistinctContent() {
        val pool = ContentPool()
        val a = pool.intern(Content.Num(1))
        val b = pool.intern(Content.Num(2))
        assertNotEquals(a, b)
    }
}