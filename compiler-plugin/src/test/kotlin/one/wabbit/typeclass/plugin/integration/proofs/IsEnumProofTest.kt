// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.typeclass.plugin.integration.proofs

import kotlin.test.Test
import one.wabbit.typeclass.plugin.integration.IntegrationTestSupport

class IsEnumProofTest : IntegrationTestSupport() {
    @Test
    fun materializesEnumProofForConcreteEnumIncludingEntriesValuesAndValueOf() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.IsEnum
            import one.wabbit.typeclass.summon

            enum class Color {
                RED,
                BLUE,
            }

            fun main() {
                val proof = summon<IsEnum<Color>>()

                println(proof.entries.joinToString("|"))
                println(proof.values().joinToString("|"))
                println(proof.valueOf("BLUE") == Color.BLUE)
            }
            """
                .trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                RED|BLUE
                RED|BLUE
                true
                """
                    .trimIndent(),
        )
    }

    @Test
    fun proofCanActAsPrerequisiteForOrdinaryRuleSearch() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.IsEnum
            import one.wabbit.typeclass.Typeclass

            enum class Mode {
                OFF,
                ON,
            }

            @Typeclass
            interface EnumWitness<A> {
                fun verdict(): String
            }

            @Instance
            context(isEnum: IsEnum<A>)
            fun <A> enumWitness(): EnumWitness<A> =
                object : EnumWitness<A> {
                    override fun verdict(): String = isEnum.entries.joinToString(",")
                }

            context(witness: EnumWitness<A>)
            fun <A> render(): String = witness.verdict()

            fun main() {
                println(render<Mode>())
            }
            """
                .trimIndent()

        assertCompilesAndRuns(source = source, expectedStdout = "OFF,ON")
    }

    @Test
    fun rejectsProofForNonEnumTypes() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.IsEnum
            import one.wabbit.typeclass.summon

            class NotAnEnum

            fun main() {
                println(summon<IsEnum<NotAnEnum>>()) // E:TC_NO_CONTEXT_ARGUMENT NotAnEnum is not an enum class
            }
            """
                .trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedNoContextArgument()),
        )
    }

    @Test
    fun supportsEmptyEnums() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.IsEnum
            import one.wabbit.typeclass.summon

            enum class Empty {
                ;
            }

            fun main() {
                val proof = summon<IsEnum<Empty>>()
                println(proof.entries.isEmpty())
                println(proof.values().isEmpty())
                try {
                    proof.valueOf("MISSING")
                    println("unexpected")
                } catch (error: IllegalArgumentException) {
                    println(error.message?.contains("MISSING") == true)
                }
            }
            """
                .trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                true
                true
                true
                """
                    .trimIndent(),
        )
    }
}
