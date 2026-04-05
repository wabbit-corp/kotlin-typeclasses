package one.wabbit.typeclass

public interface Iso<A, B> {
    public fun to(value: A): B

    public fun from(value: B): A
}
