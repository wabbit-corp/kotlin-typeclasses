// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.typeclass.plugin.integration.proofs

import kotlin.test.Test
import one.wabbit.typeclass.plugin.integration.ExpectedDiagnostic
import one.wabbit.typeclass.plugin.integration.HarnessDependency
import one.wabbit.typeclass.plugin.integration.IntegrationTestSupport

class SubtypeProofTest : IntegrationTestSupport() {
    @Test
    fun materializesSubtypeProofForBoundedTypeParameters() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Subtype
            import one.wabbit.typeclass.summon

            fun <A, B : A> proveBound(): Subtype<B, A> = summon<Subtype<B, A>>()
            fun <A : B, B : C, C> proveTransitiveBound(): Subtype<A, C> = summon<Subtype<A, C>>()

            open class Creature
            open class Animal : Creature()
            class Dog : Animal()

            fun main() {
                println(proveBound<Animal, Dog>() != null)
                println(proveTransitiveBound<Dog, Animal, Creature>() != null)
            }
            """
                .trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                true
                true
                """
                    .trimIndent(),
        )
    }

    @Test
    fun materializesSubtypeProofForVarianceNullabilityAndStarProjections() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Subtype
            import one.wabbit.typeclass.summon

            class Contravariant<in A>
            class Covariant<out A>

            fun main() {
                println(summon<Subtype<Contravariant<Any>, Contravariant<Int>>>() != null)
                println(summon<Subtype<Any, Any?>>() != null)
                println(summon<Subtype<Covariant<Int>, Covariant<*>>>() != null)
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

    @Test
    fun rejectsSubtypeProofForInvariantOrUnrelatedTypes() {
        fun assertSubtypeFailure(callSite: String, expectedDiagnostic: ExpectedDiagnostic) {
            val source =
                """
                package demo

                import one.wabbit.typeclass.Subtype

                context(_: Subtype<A, B>)
                fun <A, B> provenSubtype(): String = "subtype"

                class Invariant<A>(val value: A)
                class Contravariant<in A>

                fun fail() {
                    $callSite
                }
                """
                    .trimIndent()

            assertDoesNotCompile(source = source, expectedDiagnostics = listOf(expectedDiagnostic))
        }

        assertSubtypeFailure(
            """println(provenSubtype<Invariant<String>, Invariant<Any>>()) // E:TC_NO_CONTEXT_ARGUMENT Invariant is invariant""",
            expectedDiagnostic = expectedErrorContaining("no context argument", "subtype"),
        )
        assertSubtypeFailure(
            """println(provenSubtype<Contravariant<Int>, Contravariant<Any>>()) // E:TC_NO_CONTEXT_ARGUMENT contravariance reverses the direction""",
            expectedDiagnostic = expectedNoContextArgument("subtype"),
        )
        assertSubtypeFailure(
            """println(provenSubtype<String, Int>()) // E:TC_NO_CONTEXT_ARGUMENT unrelated types""",
            expectedDiagnostic = expectedNoContextArgument("subtype"),
        )
    }

    @Test
    fun subtypeProofCanActAsPrerequisiteForOrdinaryRuleSearch() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Subtype
            import one.wabbit.typeclass.Typeclass

            open class Animal
            class Dog : Animal()

            @Typeclass
            interface UpcastWitness<A, B> {
                fun verdict(): String
            }

            @Instance
            context(_: Subtype<A, B>)
            fun <A, B> subtypeWitness(): UpcastWitness<A, B> =
                object : UpcastWitness<A, B> {
                    override fun verdict(): String = "subtype-witness"
                }

            context(witness: UpcastWitness<A, B>)
            fun <A, B> render(): String = witness.verdict()

            fun main() {
                println(render<Dog, Animal>())
            }
            """
                .trimIndent()

        assertCompilesAndRuns(source = source, expectedStdout = "subtype-witness")
    }

    @Test
    fun subtypeProofSupportsCoercionAndComposition() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Subtype
            import one.wabbit.typeclass.summon

            open class Animal
            open class Dog : Animal()
            class Puppy : Dog()

            fun main() {
                val puppyDog = summon<Subtype<Puppy, Dog>>()
                val dogAnimal = summon<Subtype<Dog, Animal>>()
                val chained: Subtype<Puppy, Animal> = puppyDog.andThen(dogAnimal)
                val composed: Subtype<Puppy, Animal> = dogAnimal.compose(puppyDog)

                println(chained.coerce(Puppy()) is Animal)
                println(composed.coerce(Puppy()) is Animal)
            }
            """
                .trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                true
                true
                """
                    .trimIndent(),
        )
    }

    @Test
    fun materializesSubtypeProofForDependencyHierarchy() {
        val dependency =
            HarnessDependency(
                name = "dep-hierarchy",
                sources =
                    mapOf(
                        "dep/Model.kt" to
                            """
                            package dep

                            open class Animal
                            class Dog : Animal()
                            """
                                .trimIndent()
                    ),
            )
        val source =
            """
            package demo

            import dep.Animal
            import dep.Dog
            import one.wabbit.typeclass.Subtype
            import one.wabbit.typeclass.summon

            fun main() {
                println(summon<Subtype<Dog, Animal>>() != null)
            }
            """
                .trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "true",
            dependencies = listOf(dependency),
        )
    }

    @Test
    fun materializesSubtypeAndStrictSubtypeProofsForGenericDeclaredSupertypes() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.StrictSubtype
            import one.wabbit.typeclass.Subtype
            import one.wabbit.typeclass.summon

            open class Base<A>
            class Sub<A> : Base<A>()

            fun main() {
                println(summon<Subtype<Sub<String>, Base<String>>>() != null)
                println(summon<StrictSubtype<Sub<String>, Base<String>>>() != null)
            }
            """
                .trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                true
                true
                """
                    .trimIndent(),
        )
    }

    @Test
    fun materializesStrictSubtypeProofForProperSubtypes() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.StrictSubtype
            import one.wabbit.typeclass.summon

            open class Animal
            open class Dog : Animal()
            class Puppy : Dog()

            fun main() {
                println(summon<StrictSubtype<Dog, Animal>>() != null)
                println(summon<StrictSubtype<Puppy, Dog>>() != null)
            }
            """
                .trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                true
                true
                """
                    .trimIndent(),
        )
    }

    @Test
    fun materializesStrictSubtypeProofForStarProjectedAndFunctionTypes() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.StrictSubtype
            import one.wabbit.typeclass.summon

            fun <R> proveProjectedStrictSubtype(): StrictSubtype<Function1<Int, R>, Function1<*, R>> =
                summon<StrictSubtype<Function1<Int, R>, Function1<*, R>>>()

            fun main() {
                println(summon<StrictSubtype<List<String>, List<*>>>() != null)
                println(summon<StrictSubtype<Function1<Int, String>, Function1<*, String>>>() != null)
                println(summon<StrictSubtype<Function1<Int, String>, Function1<*, *>>>() != null)
                println(summon<StrictSubtype<(List<*>) -> Int, (List<String>) -> Int>>() != null)
                println(proveProjectedStrictSubtype<String>() != null)
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
                true
                true
                """
                    .trimIndent(),
        )
    }

    @Test
    fun materializesStrictSubtypeProofForTypeParametersWithStrictUpperBounds() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.StrictSubtype
            import one.wabbit.typeclass.summon

            open class Animal
            open class Dog : Animal()

            fun <T : Dog> proveStrict(): StrictSubtype<T, Animal> = summon<StrictSubtype<T, Animal>>()

            fun main() {
                println(proveStrict<Dog>() != null)
            }
            """
                .trimIndent()

        assertCompilesAndRuns(source = source, expectedStdout = "true")
    }

    @Test
    fun rejectsStrictSubtypeProofWhenUpperBoundStillAllowsEquality() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.StrictSubtype
            import one.wabbit.typeclass.summon

            fun <T : Any> proveStrict(): StrictSubtype<T, Any> = summon<StrictSubtype<T, Any>>() // E:TC_NO_CONTEXT_ARGUMENT T may still be exactly Any
            """
                .trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedErrorContaining("no context argument")),
        )
    }

    @Test
    fun rejectsStrictSubtypeProofForEqualAliasAndUnrelatedTypes() {
        fun assertStrictSubtypeFailure(callSite: String, expectedDiagnostic: ExpectedDiagnostic) {
            val source =
                """
                package demo

                import one.wabbit.typeclass.StrictSubtype

                typealias Age = Int

                context(_: StrictSubtype<A, B>)
                fun <A, B> provenStrictSubtype(): String = "strict-subtype"

                fun fail() {
                    $callSite
                }
                """
                    .trimIndent()

            assertDoesNotCompile(source = source, expectedDiagnostics = listOf(expectedDiagnostic))
        }

        assertStrictSubtypeFailure(
            """println(provenStrictSubtype<Int, Age>()) // E:TC_NO_CONTEXT_ARGUMENT aliases are equal, not a proper subtype""",
            expectedDiagnostic = expectedErrorContaining("no context argument", "strictsubtype"),
        )
        assertStrictSubtypeFailure(
            """println(provenStrictSubtype<String, Int>()) // E:TC_NO_CONTEXT_ARGUMENT unrelated types""",
            expectedDiagnostic = expectedNoContextArgument("strictsubtype"),
        )
    }

    @Test
    fun strictSubtypeSupportsCoercionAndDecompositionToSubtypeAndNotSame() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.NotSame
            import one.wabbit.typeclass.Same
            import one.wabbit.typeclass.StrictSubtype
            import one.wabbit.typeclass.Subtype
            import one.wabbit.typeclass.summon

            open class Animal
            class Dog : Animal()

            fun impossible(strict: StrictSubtype<Dog, Animal>, eq: Same<Dog, Animal>): Nothing =
                strict.toNotSame().contradicts(eq)

            fun main() {
                val strict = summon<StrictSubtype<Dog, Animal>>()
                val widened: Animal = strict.coerce(Dog())
                val weak: Subtype<Dog, Animal> = strict.toSubtype()
                val apart: NotSame<Dog, Animal> = strict.toNotSame()

                println(widened is Animal)
                println(weak.coerce(Dog()) is Animal)
                println(apart != null)
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

    @Test
    fun strictSubtypeComposesWithStrictSubtypeSubtypeAndSame() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Same
            import one.wabbit.typeclass.StrictSubtype
            import one.wabbit.typeclass.Subtype
            import one.wabbit.typeclass.summon

            open class Animal
            open class Mammal : Animal()
            open class Dog : Mammal()
            class Puppy : Dog()

            typealias Hound = Dog
            typealias Creature = Animal

            fun main() {
                val puppyDog = summon<StrictSubtype<Puppy, Dog>>()
                val dogMammal = summon<StrictSubtype<Dog, Mammal>>()
                val mammalCreature = summon<Subtype<Mammal, Creature>>()
                val houndDog = summon<Same<Hound, Dog>>()
                val animalCreature = summon<Same<Animal, Creature>>()

                val puppyMammal: StrictSubtype<Puppy, Mammal> = puppyDog.andThen(dogMammal)
                val puppyCreature: StrictSubtype<Puppy, Creature> = puppyMammal.andThen(mammalCreature)
                val houndMammal: StrictSubtype<Hound, Mammal> = dogMammal.compose(houndDog)
                val dogCreature: StrictSubtype<Dog, Creature> =
                    summon<StrictSubtype<Dog, Animal>>().andThen(animalCreature)

                println(puppyMammal.coerce(Puppy()) is Mammal)
                println(puppyCreature.coerce(Puppy()) is Creature)
                println(houndMammal.coerce(Dog()) is Mammal)
                println(dogCreature.coerce(Dog()) is Creature)
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
                true
                """
                    .trimIndent(),
        )
    }

    @Test
    fun strictSubtypeProofCanActAsPrerequisiteForOrdinaryRuleSearch() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.StrictSubtype
            import one.wabbit.typeclass.Typeclass

            open class Animal
            class Dog : Animal()

            @Typeclass
            interface ProperUpcastWitness<A, B> {
                fun verdict(): String
            }

            @Instance
            context(_: StrictSubtype<A, B>)
            fun <A, B> strictSubtypeWitness(): ProperUpcastWitness<A, B> =
                object : ProperUpcastWitness<A, B> {
                    override fun verdict(): String = "strict-subtype-witness"
                }

            context(witness: ProperUpcastWitness<A, B>)
            fun <A, B> render(): String = witness.verdict()

            fun main() {
                println(render<Dog, Animal>())
            }
            """
                .trimIndent()

        assertCompilesAndRuns(source = source, expectedStdout = "strict-subtype-witness")
    }
}
