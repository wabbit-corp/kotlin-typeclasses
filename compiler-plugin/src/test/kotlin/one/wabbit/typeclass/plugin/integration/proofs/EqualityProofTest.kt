// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.typeclass.plugin.integration.proofs

import one.wabbit.typeclass.plugin.integration.IntegrationTestSupport
import kotlin.test.Test

class EqualityProofTest : IntegrationTestSupport() {
    @Test fun materializesSameProofForIdenticalTypesAliasesAndReflexiveTypeParameters() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Same

            typealias Age = Int

            context(_: Same<A, B>)
            fun <A, B> provenSame(): String = "same"

            fun <A> reflexive(): String = provenSame<A, A>()

            fun main() {
                println(provenSame<Int, Int>())
                println(provenSame<Int, Age>())
                println(reflexive<String>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                same
                same
                same
                """.trimIndent(),
        )
    }

    @Test fun rejectsSameProofForDistinctTypes() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Same

            context(_: Same<A, B>)
            fun <A, B> provenSame(): String = "same"

            fun main() {
                println(provenSame<Int, String>()) // E:TC_NO_CONTEXT_ARGUMENT Int and String are not the same type
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedErrorContaining("no context argument", "same")),
        )
    }

    @Test fun materializesNotSameProofForProvablyDistinctTypes() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.NotSame

            @JvmInline
            value class UserId(val value: Int)

            context(_: NotSame<A, B>)
            fun <A, B> provenDifferent(): String = "different"

            fun main() {
                println(provenDifferent<Int, String>())
                println(provenDifferent<UserId, Int>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                different
                different
                """.trimIndent(),
        )
    }

    @Test fun rejectsNotSameProofForAliasesToTheSameType() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.NotSame

            typealias Age = Int

            context(_: NotSame<A, B>)
            fun <A, B> provenDifferent(): String = "different"

            fun main() {
                println(provenDifferent<Int, Age>()) // E:TC_NO_CONTEXT_ARGUMENT Age is exactly Int
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedErrorContaining("no context argument")),
        )
    }

    @Test fun rejectsNotSameProofForIdenticalTypes() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.NotSame

            context(_: NotSame<A, B>)
            fun <A, B> provenDifferent(): String = "different"

            fun main() {
                println(provenDifferent<Int, Int>()) // E:TC_NO_CONTEXT_ARGUMENT Int is not provably different from Int
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedErrorContaining("no context argument")),
        )
    }

    @Test fun rejectsNotSameProofForUnconstrainedTypeParameters() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.NotSame

            context(_: NotSame<A, B>)
            fun <A, B> provenDifferent(): String = "different"

            fun <A, B> impossible(): String =
                provenDifferent<A, B>() // E:TC_NO_CONTEXT_ARGUMENT the compiler cannot prove that A and B differ
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedErrorContaining("notsame")),
        )
    }

    @Test fun materializesNotSameProofForTypeParametersWithStrictUpperBounds() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.NotSame
            import one.wabbit.typeclass.summon

            open class Animal
            open class Dog : Animal()

            fun <T : Dog> proveDifferent(): NotSame<T, Animal> = summon<NotSame<T, Animal>>()

            fun main() {
                println(proveDifferent<Dog>() != null)
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "true",
        )
    }

    @Test fun rejectsNotSameProofWhenUpperBoundStillAllowsEquality() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.NotSame
            import one.wabbit.typeclass.summon

            fun <T : Any> proveDifferent(): NotSame<T, Any> = summon<NotSame<T, Any>>() // E:TC_NO_CONTEXT_ARGUMENT T may still be exactly Any
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedErrorContaining("no context argument")),
        )
    }

    @Test fun materializesNotSameProofForGenericDeclaredSupertypes() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.NotSame
            import one.wabbit.typeclass.summon

            open class Base<A>
            class Sub<A> : Base<A>()

            fun main() {
                println(summon<NotSame<Sub<String>, Base<String>>>() != null)
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "true",
        )
    }

    @Test fun sameTypeConstructorRecognizesMatchingOuterConstructors() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.SameTypeConstructor

            context(_: SameTypeConstructor<A, B>)
            fun <A, B> sameOuter(): String = "same-outer"

            fun main() {
                println(sameOuter<List<Int>, List<String>>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "same-outer",
        )
    }

    @Test fun sameTypeConstructorRejectsDifferentOuterConstructors() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.SameTypeConstructor

            context(_: SameTypeConstructor<A, B>)
            fun <A, B> sameOuter(): String = "same-outer"

            fun main() {
                println(sameOuter<List<Int>, Set<Int>>()) // E:TC_NO_CONTEXT_ARGUMENT List and Set have different outer constructors
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(expectedErrorContaining("no context argument", "sametypeconstructor")),
        )
    }

    @Test fun sameProofCanActAsPrerequisiteForOrdinaryRuleSearch() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Same
            import one.wabbit.typeclass.Typeclass

            typealias Age = Int

            @Typeclass
            interface PairWitness<A, B> {
                fun verdict(): String
            }

            @Instance
            context(_: Same<A, B>)
            fun <A, B> samePairWitness(): PairWitness<A, B> =
                object : PairWitness<A, B> {
                    override fun verdict(): String = "same-pair"
                }

            context(witness: PairWitness<A, B>)
            fun <A, B> render(): String = witness.verdict()

            fun main() {
                println(render<Int, Int>())
                println(render<Int, Age>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                same-pair
                same-pair
                """.trimIndent(),
        )
    }

    @Test fun notSameProofCanActAsPrerequisiteForOrdinaryRuleSearch() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.NotSame
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface DistinctPair<A, B> {
                fun verdict(): String
            }

            @Instance
            context(_: NotSame<A, B>)
            fun <A, B> distinctPairWitness(): DistinctPair<A, B> =
                object : DistinctPair<A, B> {
                    override fun verdict(): String = "distinct-pair"
                }

            context(witness: DistinctPair<A, B>)
            fun <A, B> render(): String = witness.verdict()

            fun main() {
                println(render<Int, String>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "distinct-pair",
        )
    }

    @Test fun sameTypeConstructorProofCanActAsPrerequisiteForOrdinaryRuleSearch() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.SameTypeConstructor
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface OuterWitness<A, B> {
                fun verdict(): String
            }

            @Instance
            context(_: SameTypeConstructor<A, B>)
            fun <A, B> sameOuterWitness(): OuterWitness<A, B> =
                object : OuterWitness<A, B> {
                    override fun verdict(): String = "same-outer-witness"
                }

            context(witness: OuterWitness<A, B>)
            fun <A, B> render(): String = witness.verdict()

            fun main() {
                println(render<List<Int>, List<String>>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "same-outer-witness",
        )
    }

    @Test fun sameProofSupportsCoercionFlipCompositionAndSubtypeConversion() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Same
            import one.wabbit.typeclass.summon

            typealias Age = Int

            fun main() {
                val eq = summon<Same<Int, Age>>()
                val age: Age = eq.coerce(41)
                val back: Int = eq.flip().coerce(age)
                val idInt: Same<Int, Int> = eq.andThen(eq.flip())
                val idAge: Same<Age, Age> = eq.compose(eq.flip())
                val widened: Age = eq.toSubtype().coerce(42)

                println(age)
                println(back)
                println(idInt.coerce(7))
                println(idAge.coerce(8))
                println(widened)
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                41
                41
                7
                8
                42
                """.trimIndent(),
        )
    }

    @Test fun notSameProofSupportsFlipAndContradictionSurface() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.NotSame
            import one.wabbit.typeclass.Same
            import one.wabbit.typeclass.summon

            fun impossible(neq: NotSame<Int, String>, eq: Same<Int, String>): Nothing =
                neq.contradicts(eq)

            fun main() {
                val neq = summon<NotSame<Int, String>>()
                val flipped: NotSame<String, Int> = neq.flip()
                println(flipped != null)
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "true",
        )
    }

    @Test fun bracketTurnsBidirectionalSubtypeIntoEquality() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Same
            import one.wabbit.typeclass.Subtype
            import one.wabbit.typeclass.summon

            typealias Age = Int

            fun main() {
                val eq: Same<Int, Age> = Same.bracket(
                    summon<Subtype<Int, Age>>(),
                    summon<Subtype<Age, Int>>(),
                )
                println(eq.coerce(5))
                println(eq.flip().coerce(6))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                5
                6
                """.trimIndent(),
        )
    }
}
