@file:OptIn(InternalTypeclassApi::class)

package one.wabbit.typeclass

@Typeclass
@InternalTypeclassApi
public abstract class Equiv<A, B> protected constructor() {
    public abstract fun to(value: A): B

    public abstract fun from(value: B): A

    public fun inverse(): Equiv<B, A> =
        unsafeEquiv(
            to = ::from,
            from = ::to,
        )

    public fun <C> compose(that: Equiv<B, C>): Equiv<A, C> =
        unsafeEquiv(
            to = { value -> that.to(to(value)) },
            from = { value -> from(that.from(value)) },
        )
}

@InternalTypeclassApi
public fun <A, B> unsafeEquiv(
    to: (A) -> B,
    from: (B) -> A,
): Equiv<A, B> =
    object : Equiv<A, B>() {
        override fun to(value: A): B = to.invoke(value)

        override fun from(value: B): A = from.invoke(value)
    }
