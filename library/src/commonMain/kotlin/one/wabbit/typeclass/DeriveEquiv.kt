package one.wabbit.typeclass

import kotlin.reflect.KClass

/**
 * Requests an exported compiler-synthesized [Equiv] between the annotated class
 * and [otherClass].
 *
 * This is intended for transparent shapes where the compiler can conservatively
 * justify a reversible structural equivalence. Explicit user-authored reversible
 * conversions should normally be modeled as [Iso], not by manually constructing
 * [Equiv].
 *
 * The current implementation is intentionally conservative and focuses on
 * monomorphic target types.
 *
 * @property otherClass the class to expose as equivalent to the annotated class
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@Repeatable
public annotation class DeriveEquiv(
    val otherClass: KClass<*>,
)
