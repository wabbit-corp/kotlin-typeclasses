// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass

/**
 * Opt-in marker for low-level runtime carriers that exist to support compiler- synthesized evidence
 * and library internals.
 *
 * These declarations are public so generated code can reference them across module boundaries, but
 * they are not intended to be the primary user-facing API surface.
 */
@RequiresOptIn(message = "This API is internal.")
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
)
public annotation class InternalTypeclassApi
