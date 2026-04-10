// SPDX-License-Identifier: AGPL-3.0-or-later

@file:OptIn(InternalTypeclassApi::class)

package one.wabbit.typeclass

import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.KTypeProjection
import kotlin.reflect.KVariance

/**
 * Proofs about Kotlin types.
 *
 * Semantic meaning:
 * - [Same] witnesses semantic Kotlin type equality.
 * - [Subtype] witnesses semantic Kotlin subtyping.
 * - [StrictSubtype] witnesses proper subtyping: subtype but not equal.
 * - [Nullable] witnesses that `null` is a valid inhabitant of a type.
 * - [NotNullable] witnesses that a type excludes `null`.
 * - [KnownType] carries an exact reflective [KType] for a fully known type.
 * - [TypeId] carries a stable, hashable token for semantic type identity.
 *
 * These notions are intentionally distinct from builtin [kotlin.reflect.KClass]. `KClass` is
 * runtime classifier identity only: it erases type arguments and does not model nullability.
 * [KnownType] and [TypeId] are the exact and semantic identity utilities respectively.
 *
 * In particular, [KnownType] should be read as "the compiler knows the exact instantiated type
 * here", not "the compiler knows something about a source-level type parameter symbol". A reified
 * `T` instantiated as `String` should behave the same as a concrete `String` for [KnownType].
 */

/**
 * Witnesses semantic Kotlin subtyping.
 *
 * A value of `Subtype<Sub, Super>` means that any value of `Sub` may be used where `Super` is
 * expected.
 */
@Typeclass
public interface Subtype<Sub, Super> {
    /** Coerces a value through a proven subtype relationship. */
    @Suppress("UNCHECKED_CAST") public fun coerce(value: Sub): Super = value as Super

    /** Composes two subtype proofs transitively. */
    public fun <Upper> andThen(that: Subtype<Super, Upper>): Subtype<Sub, Upper> =
        unsafeAssertSubtype()

    /** Composes two subtype proofs transitively. */
    public fun <Lower> compose(that: Subtype<Lower, Sub>): Subtype<Lower, Super> =
        unsafeAssertSubtype()

    public companion object {
        /** Subtyping is reflexive. */
        public fun <T> refl(): Subtype<T, T> = unsafeAssertSubtype()

        /** Reifies a statically known Kotlin subtype relationship. */
        public fun <Sub, Super> reify(): Subtype<Sub, Super> where Sub : Super =
            unsafeAssertSubtype()
    }
}

@InternalTypeclassApi
/**
 * Internal singleton carrier for compiler-synthesized [Subtype] evidence.
 *
 * End users normally interact with [Subtype] rather than referencing this object directly.
 */
public object UnsafeAssertSubtype : Subtype<Any?, Any?>

/**
 * Witnesses semantic Kotlin type equality.
 *
 * `Same<A, B>` should line up with the compiler's notion of identical expanded Kotlin types. Type
 * aliases do not create distinct [Same] proofs.
 */
@Typeclass
public interface Same<A, B> : Subtype<A, B> {
    /** Equality is symmetric. */
    public fun flip(): Same<B, A> = unsafeAssertSame()

    /** Equality is transitive. */
    public fun <C> andThen(that: Same<B, C>): Same<A, C> = unsafeAssertSame()

    /** Equality is transitive. */
    public fun <Z> compose(that: Same<Z, A>): Same<Z, B> = unsafeAssertSame()

    /** Equality is also a subtype proof. */
    public fun toSubtype(): Subtype<A, B> = this

    public companion object {
        /** Equality is reflexive. */
        public fun <T> refl(): Same<T, T> = unsafeAssertSame()

        /** Bidirectional subtyping collapses to semantic equality. */
        public fun <A, B> bracket(left: Subtype<A, B>, right: Subtype<B, A>): Same<A, B> =
            unsafeAssertSame()
    }
}

@InternalTypeclassApi
/** Internal singleton carrier for compiler-synthesized [Same] evidence. */
public object UnsafeAssertSame : Same<Any?, Any?>

/**
 * Witnesses that two Kotlin types are provably distinct.
 *
 * This is the lightweight, proof-only notion of inequality. Unlike a future stronger apartness
 * proof, [NotSame] does not carry a canonical identity token.
 */
@Typeclass
public interface NotSame<A, B> {
    /** Inequality is symmetric. */
    public fun flip(): NotSame<B, A> = unsafeAssertNotSame()

    /** Reaching both equality and inequality proofs at the same time is contradictory. */
    public fun contradicts(eq: Same<A, B>): Nothing =
        error("Encountered contradictory Same and NotSame proofs.")

    public companion object {
        /**
         * Builds an inequality proof from a contradiction that would arise if equality were ever
         * provided.
         */
        public fun <A, B> fromContradiction(contradiction: (Same<A, B>) -> Nothing): NotSame<A, B> =
            object : NotSame<A, B> {
                override fun contradicts(eq: Same<A, B>): Nothing = contradiction(eq)
            }

        /** Inequality is irreflexive. */
        public fun <A> irreflexive(proof: NotSame<A, A>): Nothing = proof.contradicts(Same.refl())
    }
}

@InternalTypeclassApi
/** Internal singleton carrier for compiler-synthesized [NotSame] evidence. */
public object UnsafeAssertNotSame : NotSame<Any?, Any?>

/** Witnesses proper subtyping: `Sub` is a subtype of `Super`, but the two are not equal. */
@Typeclass
public interface StrictSubtype<Sub, Super> : Subtype<Sub, Super>, NotSame<Sub, Super> {
    /** Proper subtyping composes transitively with ordinary subtyping. */
    override fun <Upper> andThen(that: Subtype<Super, Upper>): StrictSubtype<Sub, Upper> =
        unsafeAssertStrictSubtype()

    /** Proper subtyping composes transitively with ordinary subtyping. */
    override fun <Lower> compose(that: Subtype<Lower, Sub>): StrictSubtype<Lower, Super> =
        unsafeAssertStrictSubtype()

    /** Forgets the strictness information and keeps only subtyping. */
    public fun toSubtype(): Subtype<Sub, Super> = this

    /** Forgets the subtyping information and keeps only inequality. */
    public fun toNotSame(): NotSame<Sub, Super> = this

    /** Proper subtyping cannot also hold in the reverse direction. */
    public fun contradicts(reverse: Subtype<Super, Sub>): Nothing =
        toNotSame().contradicts(Same.bracket(this, reverse))
}

@InternalTypeclassApi
/** Internal singleton carrier for compiler-synthesized [StrictSubtype] evidence. */
public object UnsafeAssertStrictSubtype : StrictSubtype<Any?, Any?>

/**
 * Witnesses that `null` is a valid inhabitant of `T`.
 *
 * This is stronger than "the type is nullable syntax-wise". The intended semantics are "the
 * compiler can prove that `null` is a valid value of `T`". For an unconstrained type parameter,
 * neither [Nullable] nor [NotNullable] should materialize.
 */
@Typeclass
public interface Nullable<T> {
    /** Produces the null inhabitant. */
    @Suppress("UNCHECKED_CAST") public fun nullValue(): T = null as T

    /** Equality preserves nullability. */
    public fun <U> andThen(that: Same<T, U>): Nullable<U> = unsafeAssertNullable()

    /** If `T` admits null and `T` is a subtype of `U`, then `U` also admits null. */
    public fun <U> andThen(that: Subtype<T, U>): Nullable<U> = unsafeAssertNullable()

    /** Equality preserves nullability in the reverse direction as well. */
    public fun <Z> compose(that: Same<Z, T>): Nullable<Z> = unsafeAssertNullable()

    /** Reaching both nullable and non-nullable proofs at the same time is contradictory. */
    public fun contradicts(notNullable: NotNullable<T>): Nothing =
        error("Encountered contradictory Nullable and NotNullable proofs.")
}

@InternalTypeclassApi
/** Internal singleton carrier for compiler-synthesized [Nullable] evidence. */
public object UnsafeAssertNullable : Nullable<Any?>

/**
 * Witnesses that `T` excludes `null`.
 *
 * This should be read as "the compiler can prove `T <: Any`", not merely "the type was written
 * without a trailing `?`".
 */
@Typeclass
public interface NotNullable<T> {
    /** Equality preserves non-nullability. */
    public fun <U> andThen(that: Same<T, U>): NotNullable<U> = unsafeAssertNotNullable()

    /** Equality preserves non-nullability in the reverse direction as well. */
    public fun <Z> compose(that: Same<Z, T>): NotNullable<Z> = unsafeAssertNotNullable()

    /** If `Lower` is a subtype of `T` and `T` excludes null, then `Lower` also excludes null. */
    public fun <Lower> compose(that: Subtype<Lower, T>): NotNullable<Lower> =
        unsafeAssertNotNullable()

    /** Reaching both nullable and non-nullable proofs at the same time is contradictory. */
    public fun contradicts(nullable: Nullable<T>): Nothing =
        error("Encountered contradictory Nullable and NotNullable proofs.")
}

@InternalTypeclassApi
/** Internal singleton carrier for compiler-synthesized [NotNullable] evidence. */
public object UnsafeAssertNotNullable : NotNullable<Any?>

/** Witnesses that a type application is headed by a typeclass constructor. */
@Typeclass public interface IsTypeclassInstance<TC>

@InternalTypeclassApi
/** Internal singleton carrier for compiler-synthesized [IsTypeclassInstance] evidence. */
public object UnsafeAssertIsTypeclassInstance : IsTypeclassInstance<Any?>

/**
 * Witnesses that two applied types share the same outer type constructor.
 *
 * Example: `SameTypeConstructor<List<Int>, List<String>>` should hold, while
 * `SameTypeConstructor<List<Int>, Set<Int>>` should not.
 */
@Typeclass
public interface SameTypeConstructor<A, B> {
    /** Outer-constructor equality is symmetric. */
    public fun flip(): SameTypeConstructor<B, A> = unsafeAssertSameTypeConstructor()

    /** Outer-constructor equality is transitive. */
    public fun <C> andThen(that: SameTypeConstructor<B, C>): SameTypeConstructor<A, C> =
        unsafeAssertSameTypeConstructor()

    /** Outer-constructor equality is transitive. */
    public fun <Z> compose(that: SameTypeConstructor<Z, A>): SameTypeConstructor<Z, B> =
        unsafeAssertSameTypeConstructor()
}

@InternalTypeclassApi
/** Internal singleton carrier for compiler-synthesized [SameTypeConstructor] evidence. */
public object UnsafeAssertSameTypeConstructor : SameTypeConstructor<Any?, Any?>

/**
 * Carries an exact reflective [KType] for a fully known Kotlin type.
 *
 * Identity meaning:
 * - preserves classifier, type arguments, and nullability
 * - should collapse reified type parameters to their instantiated types
 * - should not preserve source-only alias names or declaration-site type parameter identity as part
 *   of its public semantics
 *
 * [KnownType] is intentionally richer than builtin [kotlin.reflect.KClass], which only captures
 * runtime classifier identity.
 */
@Typeclass
public interface KnownType<T> {
    /** Exact reflective type for the witnessed type. */
    public val kType: KType

    /** Reflective equality over the exact carried [KType]. */
    public fun sameAs(other: KnownType<*>): Boolean = kType == other.kType
}

private data class KnownTypeImpl(override val kType: KType) : KnownType<Any?>

/**
 * Wraps an exact reflective [KType] into a [KnownType].
 *
 * This factory is primarily used by compiler-synthesized evidence.
 */
public fun knownType(kType: KType): KnownType<Any?> = KnownTypeImpl(kType)

/**
 * The result of comparing two canonical [TypeId] values.
 *
 * Equal ids yield a [Same] proof. Unequal ids yield a [NotSame] proof.
 */
public sealed interface TypeIdComparison<A, B> {
    /** Left-hand identifier that participated in the comparison. */
    public val left: TypeId<A>

    /** Right-hand identifier that participated in the comparison. */
    public val right: TypeId<B>

    /** Result shape for two equal canonical type identifiers. */
    public data class Equal<A, B>(
        override val left: TypeId<A>,
        override val right: TypeId<B>,
        /** Equality witness justified by the two matching identifiers. */
        public val proof: Same<A, B>,
    ) : TypeIdComparison<A, B>

    /** Result shape for two distinct canonical type identifiers. */
    public data class Different<A, B>(
        override val left: TypeId<A>,
        override val right: TypeId<B>,
        /** Inequality witness justified by the two distinct identifiers. */
        public val proof: NotSame<A, B>,
    ) : TypeIdComparison<A, B>
}

/**
 * A stable, hashable token for semantic type identity.
 *
 * Public semantic contract:
 * - `left.sameAs(right)` should hold exactly when the compiler could prove `Same<A, B>` for the
 *   corresponding type arguments
 * - `left == right` and `left.hashCode() == right.hashCode()` follow the same semantic identity
 *   relation
 * - it preserves classifier, type arguments, and nullability
 * - compiler-synthesized ids should not treat source alias names as semantically meaningful
 *   distinctions
 * - reflective fallback ids may still preserve source type-parameter names when the runtime [KType]
 *   is not fully concrete
 *
 * Unlike [KnownType], [TypeId] is meant for fast equality checks, hashing, and durable map/set
 * keys. A [TypeId] should therefore be safe to use as a key in a `HashMap` or member of a `HashSet`
 * without risking mismatches between semantic comparison and runtime equality. Unlike builtin
 * [kotlin.reflect.KClass], it is not erased.
 */
@Typeclass
public class TypeId<T>
private constructor(
    /**
     * A canonical semantic rendering used for equality and diagnostics.
     *
     * This is part of the public contract for tests and debugging, but callers should prefer
     * [sameAs] or [compare] for identity checks.
     */
    public val canonicalName: String
) {
    /** A stable hash derived from [canonicalName]. */
    public val stableHash: Long
        get() = hashCode().toLong()

    /** Semantic identity comparison. */
    public fun sameAs(other: TypeId<*>): Boolean = canonicalName == other.canonicalName

    /** Compares two semantic type ids and returns either equality or inequality evidence. */
    public fun <Other> compare(other: TypeId<Other>): TypeIdComparison<T, Other> =
        if (sameAs(other)) {
            TypeIdComparison.Equal(this, other, unsafeAssertSame())
        } else {
            TypeIdComparison.Different(
                this,
                other,
                NotSame.fromContradiction { _ ->
                    error(
                        "Encountered contradictory TypeId comparison between " +
                            "$canonicalName and ${other.canonicalName}."
                    )
                },
            )
        }

    override fun equals(other: Any?): Boolean =
        other is TypeId<*> && canonicalName == other.canonicalName

    override fun hashCode(): Int = canonicalName.hashCode()

    override fun toString(): String = canonicalName

    public companion object {
        /**
         * Wraps a canonical semantic name into a [TypeId].
         *
         * Compiler-synthesized [TypeId] values should use a canonical rendering whose equality
         * matches [Same]. Reflective fallback ids preserve the semantics of [typeId(kType)] below
         * and may therefore retain reflective type-parameter names for non-concrete [KType] values.
         */
        public fun <T> fromCanonicalName(canonicalName: String): TypeId<T> = TypeId(canonicalName)

        /** Compares two semantic type identifiers. */
        public fun <A, B> compare(left: TypeId<A>, right: TypeId<B>): TypeIdComparison<A, B> =
            left.compare(right)
    }
}

/**
 * Wraps a canonical semantic name into a [TypeId].
 *
 * Compiler-synthesized [TypeId] values should use a canonical rendering whose equality matches
 * [Same]. Reflective fallback ids preserve the semantics of [typeId(kType)] below and may therefore
 * retain reflective type-parameter names for non-concrete [KType] values.
 */
public fun typeId(canonicalName: String): TypeId<Any?> = TypeId.fromCanonicalName(canonicalName)

/**
 * Wraps an exact [KType] into a semantic [TypeId].
 *
 * This is primarily used by compiler-synthesized evidence for reified types. For non-concrete
 * reflective [KType] values, the resulting canonical name may still retain reflective
 * type-parameter names.
 */
public fun typeId(kType: KType): TypeId<Any?> =
    TypeId.fromCanonicalName(kType.toCanonicalTypeIdName())

@InternalTypeclassApi
@Suppress("UNCHECKED_CAST")
private fun <A, B> unsafeAssertSame(): Same<A, B> = UnsafeAssertSame as Same<A, B>

@InternalTypeclassApi
@Suppress("UNCHECKED_CAST")
private fun <A, B> unsafeAssertNotSame(): NotSame<A, B> = UnsafeAssertNotSame as NotSame<A, B>

@InternalTypeclassApi
@Suppress("UNCHECKED_CAST")
private fun <Sub, Super> unsafeAssertSubtype(): Subtype<Sub, Super> =
    UnsafeAssertSubtype as Subtype<Sub, Super>

@InternalTypeclassApi
@Suppress("UNCHECKED_CAST")
private fun <Sub, Super> unsafeAssertStrictSubtype(): StrictSubtype<Sub, Super> =
    UnsafeAssertStrictSubtype as StrictSubtype<Sub, Super>

@InternalTypeclassApi
@Suppress("UNCHECKED_CAST")
private fun <T> unsafeAssertNullable(): Nullable<T> = UnsafeAssertNullable as Nullable<T>

@InternalTypeclassApi
@Suppress("UNCHECKED_CAST")
private fun <T> unsafeAssertNotNullable(): NotNullable<T> =
    UnsafeAssertNotNullable as NotNullable<T>

@InternalTypeclassApi
@Suppress("UNCHECKED_CAST")
private fun <A, B> unsafeAssertSameTypeConstructor(): SameTypeConstructor<A, B> =
    UnsafeAssertSameTypeConstructor as SameTypeConstructor<A, B>

private fun KType.toCanonicalTypeIdName(): String = buildString {
    append(classifierCanonicalName())
    if (arguments.isNotEmpty()) {
        append(
            arguments.joinToString(prefix = "<", postfix = ">", separator = ",") { argument ->
                argument.toCanonicalTypeIdName()
            }
        )
    }
    if (isMarkedNullable) {
        append('?')
    }
}

private fun KType.classifierCanonicalName(): String =
    when (val current = classifier) {
        is KClass<*> -> current.qualifiedName ?: current.toString()
        is kotlin.reflect.KTypeParameter -> current.name
        else -> toString().substringBefore('<').removeSuffix("?")
    }

private fun KTypeProjection.toCanonicalTypeIdName(): String =
    when {
        type == null -> "*"
        variance == KVariance.INVARIANT || variance == null -> type!!.toCanonicalTypeIdName()
        variance == KVariance.IN -> "in ${type!!.toCanonicalTypeIdName()}"
        variance == KVariance.OUT -> "out ${type!!.toCanonicalTypeIdName()}"
        else -> type!!.toCanonicalTypeIdName()
    }
