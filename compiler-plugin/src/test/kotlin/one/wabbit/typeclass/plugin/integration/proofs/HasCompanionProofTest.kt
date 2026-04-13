// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.typeclass.plugin.integration.proofs

import kotlin.test.Test
import one.wabbit.typeclass.plugin.integration.IntegrationTestSupport

class HasCompanionProofTest : IntegrationTestSupport() {
    @Test
    fun materializesCompanionProofForRequestedSupertypeAndAny() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.HasCompanion
            import one.wabbit.typeclass.summon

            interface Boo {
                fun label(): String
            }

            class Foo {
                companion object : Boo {
                    override fun label(): String = "boo"
                }
            }

            fun main() {
                val booProof = summon<HasCompanion<Foo, Boo>>()
                val anyProof = summon<HasCompanion<Foo, Any>>()

                println(booProof.companion.label())
                println(booProof.companion === Foo.Companion)
                println(anyProof.companion === Foo.Companion)
            }
            """
                .trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                boo
                true
                true
                """
                    .trimIndent(),
        )
    }

    @Test
    fun rejectsProofWhenClassHasNoCompanion() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.HasCompanion
            import one.wabbit.typeclass.summon

            interface Boo

            class NoCompanion

            fun main() {
                println(summon<HasCompanion<NoCompanion, Boo>>()) // E:TC_NO_CONTEXT_ARGUMENT NoCompanion has no companion
            }
            """
                .trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedNoContextArgument()),
        )
    }

    @Test
    fun rejectsProofWhenCompanionDoesNotSatisfyRequestedType() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.HasCompanion
            import one.wabbit.typeclass.summon

            interface Boo
            interface Bar

            class Foo {
                companion object : Boo
            }

            fun main() {
                println(summon<HasCompanion<Foo, Bar>>()) // E:TC_NO_CONTEXT_ARGUMENT Foo.Companion is not a Bar
            }
            """
                .trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedNoContextArgument()),
        )
    }

    @Test
    fun companionProofCanActAsPrerequisiteForOrdinaryRuleSearch() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.HasCompanion
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            interface Boo {
                fun label(): String
            }

            class Foo {
                companion object : Boo {
                    override fun label(): String = "boo"
                }
            }

            @Typeclass
            interface CompanionWitness<A, C> {
                fun verdict(): String
            }

            @Instance
            context(hasCompanion: HasCompanion<A, C>)
            fun <A, C> companionWitness(): CompanionWitness<A, C> =
                object : CompanionWitness<A, C> {
                    override fun verdict(): String = hasCompanion.companion.toString()
                }

            context(witness: CompanionWitness<A, C>)
            fun <A, C> render(): String = witness.verdict()

            fun main() {
                println(render<Foo, Boo>().contains("Companion"))
            }
            """
                .trimIndent()

        assertCompilesAndRuns(source = source, expectedStdout = "true")
    }
}
