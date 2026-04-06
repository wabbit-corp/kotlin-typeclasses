// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass

/**
 * Marks a compiler-generated wrapper callable used to present a source-friendly
 * resolution surface before IR rewriting restores the original contextual call.
 *
 * This annotation exists for compiler bookkeeping and generated-code discovery;
 * it is not intended to be applied manually in user code.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class GeneratedTypeclassWrapper

/**
 * Marks a compiler-generated declaration that publishes derived or synthesized
 * typeclass evidence.
 *
 * The encoded identifiers are used by the compiler plugin to rediscover the
 * generated declaration across compilation stages and module boundaries.
 *
 * @property typeclassId encoded class identifier for the typeclass head, when
 *   relevant to the generated declaration
 * @property targetId encoded class identifier for the target type the generated
 *   declaration applies to
 * @property kind encoded generation kind such as ordinary derivation,
 *   `@DeriveVia`, or `@DeriveEquiv`
 * @property payload additional encoded generation metadata when the selected
 *   [kind] needs more than the two class identifiers
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@Repeatable
public annotation class GeneratedTypeclassInstance(
    val typeclassId: String,
    val targetId: String,
    val kind: String,
    val payload: String = "",
)
