package one.wabbit.typeclass

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@Repeatable
public annotation class DeriveVia(
    val typeclass: KClass<*>,
    vararg val path: KClass<*>,
)
