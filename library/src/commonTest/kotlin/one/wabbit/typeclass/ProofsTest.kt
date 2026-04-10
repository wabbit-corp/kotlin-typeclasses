// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.typeclass

import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(InternalTypeclassApi::class)
class ProofsTest {
    @Test
    fun sameSubtypeAndBracketSurfaceCompose() {
        val same = Same.refl<Int>()
        val subtype = Subtype.reify<Int, Number>()
        val id = Same.bracket(Subtype.refl<Int>(), Subtype.refl<Int>())

        assertEquals(1, same.coerce(1))
        assertEquals(2, same.flip().coerce(2))
        assertEquals(3, subtype.andThen(Subtype.reify<Number, Any>()).coerce(3))
        assertEquals(4, id.coerce(4))
    }

    @Test
    fun notSameStrictSubtypeAndSameTypeConstructorSurfaceCompose() {
        val notSame =
            NotSame.fromContradiction<Int, String> {
                throw IllegalStateException("custom-not-same")
            }
        val strict = strictSubtype<String, Any>()
        val outer = sameTypeConstructor<List<Int>, List<String>>()
        val flipped: NotSame<String, Int> = notSame.flip()
        val widened: Any = strict.toSubtype().coerce("x")
        val apart: NotSame<Any, String> = strict.toNotSame().flip()
        val composed: SameTypeConstructor<List<Int>, List<Double>> =
            outer.andThen(sameTypeConstructor<List<String>, List<Double>>())

        assertFailsWith<IllegalStateException> { flipped.contradicts(impossibleSame()) }
        assertEquals("x", widened)
        assertFailsWith<IllegalStateException> { apart.contradicts(impossibleSame()) }
        assertEquals(composed::class, composed.flip().flip()::class)
        assertFailsWith<IllegalStateException> { notSame.contradicts(impossibleSame()) }
        assertFailsWith<IllegalStateException> { strict.contradicts(impossibleSubtype()) }
        assertFailsWith<IllegalStateException> {
            NotSame.irreflexive(
                NotSame.fromContradiction<Int, Int> { throw IllegalStateException("irreflexive") }
            )
        }
    }

    @Test
    fun nullableAndNotNullableSurfaceSupportTransportAndContradiction() {
        val nullable = nullable<String?>()
        val same = Same.refl<String?>()
        val widened = Subtype.reify<String?, Any?>()
        val notNullable = notNullable<Any>()
        val sameTransported: Nullable<String?> = nullable.andThen(same)
        val widenedTransported: Nullable<Any?> = nullable.andThen(widened)
        val narrowedNotNull: NotNullable<String> = notNullable.compose(Subtype.reify<String, Any>())

        assertEquals(null, nullable.nullValue())
        assertTrue(sameTransported.contradictsCatches(notNullable<String?>()))
        assertTrue(widenedTransported.contradictsCatches(notNullable<Any?>()))
        assertTrue(narrowedNotNull.contradictsCatches(nullable<String>()))
        assertFailsWith<IllegalStateException> { nullable.contradicts(notNullable<String?>()) }
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun knownTypeAndTypeIdFactoriesPreserveExactVsSemanticIdentity() {
        val listString = knownType(typeOf<List<String?>>())
        val listStringAgain = knownType(typeOf<List<String?>>())
        val listInt = knownType(typeOf<List<Int>>())

        val age = typeId("kotlin.Int")
        val intId = TypeId.fromCanonicalName<Int>("kotlin.Int")
        val nullableInt = typeId("kotlin.Int?")

        assertTrue(listString.sameAs(listStringAgain))
        assertFalse(listString.sameAs(listInt))

        assertTrue(age.sameAs(intId))
        assertEquals(age.stableHash, intId.stableHash)
        assertFalse(age.sameAs(nullableInt))
        assertTrue(age == intId)
        assertEquals(age.hashCode(), intId.hashCode())
    }

    @Test
    fun typeIdUsesCanonicalSemanticNameAndSupportsComparison() {
        val left = typeId("kotlin.collections.List<kotlin.String?>")
        val right = typeId("kotlin.collections.List<kotlin.String?>")
        val different = typeId("kotlin.collections.List<kotlin.Int>")
        val map = hashMapOf(left to "value")

        assertTrue(left.sameAs(right))
        assertFalse(left.sameAs(different))
        assertEquals(left.stableHash, right.stableHash)
        assertEquals("value", map[right])
        assertFalse(map.containsKey(different))

        val equal = TypeId.compare(left, right)
        val differentResult = left.compare(different)

        assertTrue(equal is TypeIdComparison.Equal<*, *>)
        assertTrue(differentResult is TypeIdComparison.Different<*, *>)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <A, B> impossibleSame(): Same<A, B> = UnsafeAssertSame as Same<A, B>

    @Suppress("UNCHECKED_CAST")
    private fun <Sub, Super> impossibleSubtype(): Subtype<Sub, Super> =
        UnsafeAssertSubtype as Subtype<Sub, Super>

    @Suppress("UNCHECKED_CAST")
    private fun <Sub, Super> strictSubtype(): StrictSubtype<Sub, Super> =
        UnsafeAssertStrictSubtype as StrictSubtype<Sub, Super>

    @Suppress("UNCHECKED_CAST")
    private fun <T> nullable(): Nullable<T> = UnsafeAssertNullable as Nullable<T>

    @Suppress("UNCHECKED_CAST")
    private fun <T> notNullable(): NotNullable<T> = UnsafeAssertNotNullable as NotNullable<T>

    @Suppress("UNCHECKED_CAST")
    private fun <A, B> sameTypeConstructor(): SameTypeConstructor<A, B> =
        UnsafeAssertSameTypeConstructor as SameTypeConstructor<A, B>

    private fun <T> Nullable<T>.contradictsCatches(notNullable: NotNullable<T>): Boolean =
        runCatching { contradicts(notNullable) }.isFailure

    private fun <T> NotNullable<T>.contradictsCatches(nullable: Nullable<T>): Boolean =
        runCatching { contradicts(nullable) }.isFailure
}
