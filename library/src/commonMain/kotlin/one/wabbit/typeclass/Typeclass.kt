// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass

/**
 * Marks a supported typeclass head as participating in typeclass resolution.
 *
 * Ordinary application and library typeclasses should be interfaces annotated with [Typeclass]. The
 * compiler also supports a narrow advanced path for subclassable class heads with accessible
 * zero-argument constructors, mainly for compiler-owned surfaces such as [Equiv] and generated
 * `DeriveVia` adapters.
 *
 * The annotation uses binary retention so the compiler plugin can observe the marker across module
 * boundaries.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class Typeclass
