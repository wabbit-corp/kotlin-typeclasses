// SPDX-License-Identifier: AGPL-3.0-or-later

@file:OptIn(InternalTypeclassApi::class)

package one.wabbit.typeclass

/**
 * Compiler-owned canonical evidence that [A] and [B] are reversibly equivalent.
 *
 * Unlike [Iso], [Equiv] is itself a typeclass and is intended to be synthesized
 * by the compiler rather than authored directly by user code. Exported `Equiv`
 * evidence participates in typeclass search; internal proof trees that only
 * exist while completing one derivation do not necessarily become globally
 * summonable.
 *
 * User-authored reversible conversions should normally be expressed as [Iso].
 */
@Typeclass
@InternalTypeclassApi
public abstract class Equiv<A, B> protected constructor() {
    /**
     * Converts a value from [A] to [B].
     */
    public abstract fun to(value: A): B

    /**
     * Converts a value from [B] back to [A].
     */
    public abstract fun from(value: B): A

    /**
     * Reverses this equivalence.
     */
    public fun inverse(): Equiv<B, A> =
        unsafeEquiv(
            to = ::from,
            from = ::to,
        )

    /**
     * Composes this equivalence with [that].
     */
    public fun <C> compose(that: Equiv<B, C>): Equiv<A, C> =
        unsafeEquiv(
            to = { value -> that.to(to(value)) },
            from = { value -> from(that.from(value)) },
        )
}

/**
 * Low-level factory for [Equiv] values.
 *
 * This exists primarily for compiler-synthesized evidence and library internals.
 * Callers are responsible for ensuring that [to] and [from] really form a
 * reversible equivalence.
 */
@InternalTypeclassApi
public fun <A, B> unsafeEquiv(
    to: (A) -> B,
    from: (B) -> A,
): Equiv<A, B> =
    object : Equiv<A, B>() {
        override fun to(value: A): B = to.invoke(value)

        override fun from(value: B): A = from.invoke(value)
    }
