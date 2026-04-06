package one.wabbit.typeclass

/**
 * An explicit reversible conversion between [A] and [B].
 *
 * Unlike [Equiv], [Iso] is not a typeclass and is intended for direct
 * user-authored values. It models a specific conversion object rather than
 * compiler-owned canonical equivalence evidence.
 */
public interface Iso<A, B> {
    /**
     * Converts a value from [A] to [B].
     */
    public fun to(value: A): B

    /**
     * Converts a value from [B] back to [A].
     */
    public fun from(value: B): A
}
