// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass

/**
 * Marks a declaration as a source of typeclass evidence.
 *
 * Supported declaration shapes are:
 * - objects
 * - parameterless functions whose context parameters describe prerequisites
 * - immutable properties
 *
 * The compiler plugin indexes these declarations and treats them as candidates during typeclass
 * resolution. Binary retention is required so those candidates remain visible across module
 * boundaries.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
public annotation class Instance
