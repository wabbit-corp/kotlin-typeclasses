// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass

import kotlin.reflect.KClass

/**
 * Requests compiler-synthesized derivation for one or more typeclasses.
 *
 * Each entry in [value] names a typeclass whose companion is expected to provide the appropriate
 * derivation hook through [ProductTypeclassDeriver] or [TypeclassDeriver].
 *
 * The compiler plugin validates the target shape, synthesizes metadata, and publishes the generated
 * evidence into normal typeclass search.
 *
 * @property value typeclasses to derive for the annotated class
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class Derive(vararg val value: KClass<*>)
