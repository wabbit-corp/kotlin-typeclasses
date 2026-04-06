// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass

import kotlin.reflect.KClass

/**
 * Requests derivation by transporting a typeclass instance through an
 * equivalence path.
 *
 * Conceptually, `@DeriveVia(TC::class, Foo::class)` asks the compiler to solve a
 * suitable equivalence between the annotated type and `Foo`, obtain `TC<Foo>`,
 * and transport that evidence back to the annotated type.
 *
 * Entries in [path] name intermediate via-types or pinned [Iso]-backed
 * singleton segments that the compiler may use while constructing that path.
 * Empty paths are rejected.
 *
 * The current implementation is intentionally conservative and focuses on
 * monomorphic target types.
 *
 * @property typeclass the typeclass to derive through transport
 * @property path via-types or pinned [Iso] singleton classes describing the
 *   transport path
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@Repeatable
public annotation class DeriveVia(
    val typeclass: KClass<*>,
    vararg val path: KClass<*>,
)
