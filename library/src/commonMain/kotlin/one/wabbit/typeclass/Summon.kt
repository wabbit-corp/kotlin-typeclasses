// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass

/**
 * Returns the context value of type [T].
 *
 * On its own this is just a small context-parameter helper. In compilations that use the
 * `kotlin-typeclasses` compiler plugin, `summon<Typeclass<...>>()` also becomes the user-facing way
 * to request typeclass evidence from the current resolution context.
 */
context(value: T)
public fun <T> summon(): T = value
