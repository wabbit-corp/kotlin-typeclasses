package one.wabbit.typeclass

context(value: T)
public fun <T> summon(): T = value
