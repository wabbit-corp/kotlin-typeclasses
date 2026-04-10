// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass

/**
 * Enables scoped typeclass-resolution tracing for the annotated source element.
 *
 * This annotation is source-retained because it only controls compiler diagnostics and tracing
 * behavior; it is not part of the runtime semantics of the compiled program.
 *
 * @property mode trace mode to apply at this scope
 */
@Target(
    AnnotationTarget.FILE,
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.LOCAL_VARIABLE,
)
@Retention(AnnotationRetention.SOURCE)
public annotation class DebugTypeclassResolution(
    val mode: TypeclassTraceMode = TypeclassTraceMode.FAILURES
)

/** Available trace modes for [DebugTypeclassResolution] and the matching compiler-plugin option. */
public enum class TypeclassTraceMode {
    /**
     * Inherit the enclosing scope's trace mode, or the global compiler default when there is no
     * enclosing annotated scope.
     */
    INHERIT,

    /** Disable tracing for this scope. */
    DISABLED,

    /** Emit trace output only for failed resolutions. */
    FAILURES,

    /** Emit trace output for failed resolutions and explain rejected alternatives. */
    FAILURES_AND_ALTERNATIVES,

    /** Emit trace output for successful and failed resolutions. */
    ALL,

    /** Emit trace output for all resolutions and explain rejected alternatives. */
    ALL_AND_ALTERNATIVES,
}
