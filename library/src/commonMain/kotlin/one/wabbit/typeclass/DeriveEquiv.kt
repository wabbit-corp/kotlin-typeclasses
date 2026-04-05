package one.wabbit.typeclass

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@Repeatable
public annotation class DeriveEquiv(
    val otherClass: KClass<*>,
)
