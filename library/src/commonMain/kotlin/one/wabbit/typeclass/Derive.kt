package one.wabbit.typeclass

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class Derive(vararg val value: KClass<*>)
