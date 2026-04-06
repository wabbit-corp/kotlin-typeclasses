package one.wabbit.typeclass

/**
 * Marks an interface as participating in typeclass resolution.
 *
 * Only interfaces annotated with [Typeclass] are considered by the compiler
 * plugin when it searches for implicit evidence to satisfy typeclass-shaped
 * context parameters.
 *
 * The annotation uses binary retention so the compiler plugin can observe the
 * marker across module boundaries.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class Typeclass
