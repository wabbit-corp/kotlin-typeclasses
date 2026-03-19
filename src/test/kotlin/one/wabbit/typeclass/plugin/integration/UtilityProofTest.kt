package one.wabbit.typeclass.plugin.integration

import kotlin.test.Ignore
import kotlin.test.Test

class UtilityProofTest : IntegrationTestSupport() {
    private val serializationPlugins = listOf(CompilerHarnessPlugin.Serialization)

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
                println(provenSame<Int, String>()) // ERROR Int and String are not the same type
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("same", "int", "string"),
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
                println(provenDifferent<Int, Age>()) // ERROR Age is exactly Int
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("notsame", "int"),
            expectedDiagnostics = listOf(expectedErrorContaining("no context argument", "notsame")),
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
                println(provenDifferent<Int, Int>())
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("notsame", "int"),
            expectedDiagnostics = listOf(expectedErrorContaining("no context argument", "notsame")),
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
                provenDifferent<A, B>() // ERROR the compiler cannot prove that A and B differ
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("notsame", "prove"),
            expectedDiagnostics = listOf(expectedErrorContaining("notsame")),
        )
    }

    @Test fun materializesSubtypeProofForBoundedTypeParameters() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Subtype
            import one.wabbit.typeclass.summon

            fun <A, B : A> proveBound(): Subtype<B, A> = summon<Subtype<B, A>>()

            open class Animal
            class Dog : Animal()

            fun main() {
                println(proveBound<Animal, Dog>() != null)
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "true",
        )
    }

    @Test fun materializesSubtypeProofForVarianceNullabilityAndStarProjections() {
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
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                true
                true
                true
                """.trimIndent(),
        )
    }

    @Test fun rejectsSubtypeProofForInvariantOrUnrelatedTypes() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Subtype

            context(_: Subtype<A, B>)
            fun <A, B> provenSubtype(): String = "subtype"

            class Invariant<A>(val value: A)
            class Contravariant<in A>

            fun main() {
                println(provenSubtype<Invariant<String>, Invariant<Any>>()) // ERROR Invariant is invariant
                println(provenSubtype<Contravariant<Int>, Contravariant<Any>>()) // ERROR contravariance reverses the direction
                println(provenSubtype<String, Int>()) // ERROR unrelated types
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("no context argument", "Subtype"),
            expectedDiagnostics = listOf(expectedErrorContaining("no context argument", "subtype")),
        )
    }

    @Test fun isTypeclassInstanceRejectsOrdinaryAppliedTypes() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.IsTypeclassInstance

            context(_: IsTypeclassInstance<TC>)
            fun <TC> proof(): String = "typeclass"

            fun main() {
                println(proof<List<Int>>()) // ERROR List<Int> is not a typeclass application
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("typeclass", "list"),
            expectedDiagnostics = listOf(ExpectedDiagnostic.Error(messageRegex = "(?i)typeclass application")),
        )
    }

    @Test fun isTypeclassInstanceRecognizesFlagBackedKSerializerTypeclasses() {
        val source =
            """
            package demo

            import kotlinx.serialization.KSerializer
            import kotlinx.serialization.Serializable
            import one.wabbit.typeclass.IsTypeclassInstance

            @Serializable
            data class User(val name: String)

            context(_: IsTypeclassInstance<KSerializer<User>>)
            fun serializerProof(): String = "serializer-typeclass"

            fun main() {
                println(serializerProof())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "serializer-typeclass",
            requiredPlugins = serializationPlugins,
            pluginOptions = listOf("builtinKSerializerTypeclass=enabled"),
        )
    }

    @Test fun isTypeclassInstanceRecognizesFlagBackedKClassTypeclasses() {
        val source =
            """
            package demo

            import kotlin.reflect.KClass
            import one.wabbit.typeclass.IsTypeclassInstance

            context(_: IsTypeclassInstance<KClass<Int>>)
            fun kclassProof(): String = "kclass-typeclass"

            fun main() {
                println(kclassProof())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "kclass-typeclass",
            pluginOptions = listOf("builtinKClassTypeclass=enabled"),
        )
    }

    @Test fun knownTypeMatchesTypeOfInsideReifiedHelpers() {
        val source =
            """
            package demo

            import kotlin.reflect.KType
            import kotlin.reflect.typeOf
            import one.wabbit.typeclass.KnownType
            import one.wabbit.typeclass.summon

            @OptIn(ExperimentalStdlibApi::class)
            inline fun <reified T> sameType(): Boolean =
                summon<KnownType<T>>().kType == typeOf<T>()

            @OptIn(ExperimentalStdlibApi::class)
            fun main() {
                println(sameType<List<String?>>())
                println(sameType<Map<Int, List<String?>>?>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                true
                true
                """.trimIndent(),
        )
    }

    @Test fun rejectsKnownTypeMaterializationForUnfixedGenericTypeParameters() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.KnownType
            import one.wabbit.typeclass.summon

            fun <T> impossible(): KnownType<T> =
                summon<KnownType<T>>() // ERROR the compiler does not know the exact KType for an unfixed T
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("knowntype", "exact", "ktype"),
            expectedDiagnostics = listOf(expectedErrorContaining("knowntype")),
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
                println(sameOuter<List<Int>, Set<Int>>()) // ERROR List and Set have different outer constructors
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("sametypeconstructor", "list", "set"),
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

    @Test fun subtypeProofCanActAsPrerequisiteForOrdinaryRuleSearch() {
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
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "subtype-witness",
        )
    }

    @Test fun knownTypeProofCanActAsPrerequisiteForOrdinaryRuleSearch() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.KnownType
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface TypeWitness<A> {
                fun rendered(): String
            }

            @Instance
            context(known: KnownType<A>)
            fun <A> knownTypeWitness(): TypeWitness<A> =
                object : TypeWitness<A> {
                    override fun rendered(): String {
                        val rendered = known.kType.toString()
                        return if ("List" in rendered && "String" in rendered) "list-of-string" else rendered
                    }
                }

            context(witness: TypeWitness<A>)
            fun <A> render(): String = witness.rendered()

            fun main() {
                println(render<List<String?>>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "list-of-string",
        )
    }

    @Test fun isTypeclassInstanceProofCanActAsPrerequisiteForOrdinaryRuleSearch() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.IsTypeclassInstance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A>

            @Typeclass
            interface TypeclassWitness<TC> {
                fun verdict(): String
            }

            @Instance
            context(_: IsTypeclassInstance<TC>)
            fun <TC> typeclassWitness(): TypeclassWitness<TC> =
                object : TypeclassWitness<TC> {
                    override fun verdict(): String = "typeclass-witness"
                }

            context(witness: TypeclassWitness<TC>)
            fun <TC> render(): String = witness.verdict()

            fun main() {
                println(render<Show<Int>>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "typeclass-witness",
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

    @Test fun subtypeProofSupportsCoercionAndComposition() {
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
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                true
                true
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

    @Test fun materializesStrictSubtypeProofForProperSubtypes() {
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
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                true
                true
                """.trimIndent(),
        )
    }

    @Test fun rejectsStrictSubtypeProofForEqualAliasAndUnrelatedTypes() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.StrictSubtype

            typealias Age = Int

            context(_: StrictSubtype<A, B>)
            fun <A, B> provenStrictSubtype(): String = "strict-subtype"

            fun main() {
                println(provenStrictSubtype<Int, Age>()) // ERROR aliases are equal, not a proper subtype
                println(provenStrictSubtype<String, Int>()) // ERROR unrelated types
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("no context argument", "StrictSubtype"),
            expectedDiagnostics = listOf(expectedErrorContaining("no context argument", "strictsubtype")),
        )
    }

    @Test fun strictSubtypeSupportsCoercionAndDecompositionToSubtypeAndNotSame() {
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
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                true
                true
                true
                """.trimIndent(),
        )
    }

    @Test fun strictSubtypeComposesWithStrictSubtypeSubtypeAndSame() {
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
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                true
                true
                true
                true
                """.trimIndent(),
        )
    }

    @Test fun strictSubtypeProofCanActAsPrerequisiteForOrdinaryRuleSearch() {
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
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "strict-subtype-witness",
        )
    }

    @Test fun materializesNullableAndNotNullableProofsForProvableCases() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.NotNullable
            import one.wabbit.typeclass.Nullable
            import one.wabbit.typeclass.summon

            typealias MaybeName = String?

            fun main() {
                println(summon<Nullable<String?>>() != null)
                println(summon<Nullable<MaybeName>>() != null)
                println(summon<NotNullable<String>>() != null)
                println(summon<NotNullable<List<String?>>>() != null)
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                true
                true
                true
                true
                """.trimIndent(),
        )
    }

    @Test fun rejectsNullableAndNotNullableProofsWhenNullabilityIsUnknownOrWrong() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.NotNullable
            import one.wabbit.typeclass.Nullable

            context(_: Nullable<T>)
            fun <T> needsNullable(): String = "nullable"

            context(_: NotNullable<T>)
            fun <T> needsNotNullable(): String = "not-nullable"

            fun <T> impossibleNullable(): String =
                needsNullable<T>() // ERROR the compiler cannot prove that T admits null

            fun <T> impossibleNotNullable(): String =
                needsNotNullable<T>() // ERROR the compiler cannot prove that T excludes null

            fun main() {
                println(needsNullable<String>()) // ERROR String is not nullable
                println(needsNotNullable<String?>()) // ERROR String? is nullable
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("nullable", "notnullable", "string"),
            expectedDiagnostics =
                listOf(
                    expectedErrorContaining("nullable"),
                    expectedErrorContaining("notnullable"),
                ),
        )
    }

    @Test fun nullableProofSupportsNullValueContradictionAndTransportAcrossSameAndSubtype() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.NotNullable
            import one.wabbit.typeclass.Nullable
            import one.wabbit.typeclass.Same
            import one.wabbit.typeclass.Subtype
            import one.wabbit.typeclass.summon

            open class Box
            class FancyBox : Box()

            fun impossible(nullable: Nullable<String?>, notNullable: NotNullable<String?>): Nothing =
                nullable.contradicts(notNullable)

            fun main() {
                val nullableString = summon<Nullable<String?>>()
                val sameAlias = summon<Same<String?, String?>>()
                val widened = summon<Subtype<String?, Any?>>()
                val aliasNullable: Nullable<String?> = nullableString.andThen(sameAlias)
                val anyNullable: Nullable<Any?> = nullableString.andThen(widened)
                val nullValue: String? = nullableString.nullValue()

                println(aliasNullable != null)
                println(anyNullable != null)
                println(nullValue == null)
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                true
                true
                true
                """.trimIndent(),
        )
    }

    @Test fun notNullableProofSupportsContradictionAndTransportAcrossSameAndSubtype() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.NotNullable
            import one.wabbit.typeclass.Nullable
            import one.wabbit.typeclass.Same
            import one.wabbit.typeclass.Subtype
            import one.wabbit.typeclass.summon

            open class Animal
            class Dog : Animal()
            typealias Hound = Dog

            fun impossible(notNullable: NotNullable<String>, nullable: Nullable<String>): Nothing =
                notNullable.contradicts(nullable)

            fun main() {
                val dogNotNull = summon<NotNullable<Dog>>()
                val houndDog = summon<Same<Hound, Dog>>()
                val dogAnimal = summon<Subtype<Dog, Animal>>()
                val houndNotNull: NotNullable<Hound> = dogNotNull.compose(houndDog)
                val dogStillNotNull: NotNullable<Dog> =
                    summon<NotNullable<Animal>>().compose(dogAnimal)

                println(houndNotNull != null)
                println(dogStillNotNull != null)
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                true
                true
                """.trimIndent(),
        )
    }

    @Test fun nullableAndNotNullableProofsCanActAsPrerequisitesForOrdinaryRuleSearch() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.NotNullable
            import one.wabbit.typeclass.Nullable
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface NullabilityWitness<A> {
                fun verdict(): String
            }

            @Instance
            context(_: Nullable<A>)
            fun <A> nullableWitness(): NullabilityWitness<A> =
                object : NullabilityWitness<A> {
                    override fun verdict(): String = "nullable"
                }

            @Instance
            context(_: NotNullable<A>)
            fun <A> notNullableWitness(): NullabilityWitness<A> =
                object : NullabilityWitness<A> {
                    override fun verdict(): String = "not-nullable"
                }

            context(witness: NullabilityWitness<A>)
            fun <A> render(): String = witness.verdict()

            fun main() {
                println(render<String?>())
                println(render<String>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                nullable
                not-nullable
                """.trimIndent(),
        )
    }

    @Test fun reifiedKnownTypeMatchesConcreteKnownType() {
        val source =
            """
            package demo

            import kotlin.reflect.typeOf
            import one.wabbit.typeclass.KnownType
            import one.wabbit.typeclass.knownType
            import one.wabbit.typeclass.summon

            @OptIn(ExperimentalStdlibApi::class)
            inline fun <reified T> sameAsConcrete(): Boolean =
                summon<KnownType<T>>().sameAs(knownType(typeOf<String>()))

            fun main() {
                println(sameAsConcrete<String>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "true",
        )
    }

    @Test fun kClassErasesTypeArgumentsWhileKnownTypeDistinguishesThem() {
        val source =
            """
            package demo

            import kotlin.reflect.KClass
            import one.wabbit.typeclass.KnownType
            import one.wabbit.typeclass.summon

            fun main() {
                val stringListClass = summon<KClass<List<String>>>()
                val intListClass = summon<KClass<List<Int>>>()
                val stringListType = summon<KnownType<List<String>>>()
                val intListType = summon<KnownType<List<Int>>>()

                println(stringListClass == intListClass)
                println(stringListType.sameAs(intListType))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                true
                false
                """.trimIndent(),
            pluginOptions = listOf("builtinKClassTypeclass=enabled"),
        )
    }

    @Test fun typeIdTreatsAliasesAsSemanticallyEqual() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.TypeId
            import one.wabbit.typeclass.summon

            typealias Age = Int

            fun main() {
                val intId = summon<TypeId<Int>>()
                val ageId = summon<TypeId<Age>>()
                println(intId.sameAs(ageId))
                println(intId.stableHash == ageId.stableHash)
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                true
                true
                """.trimIndent(),
        )
    }

    @Test fun typeIdDistinguishesArgumentsAndNullability() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.TypeId
            import one.wabbit.typeclass.summon

            fun main() {
                println(summon<TypeId<List<String>>>().sameAs(summon<TypeId<List<Int>>>()))
                println(summon<TypeId<Int>>().sameAs(summon<TypeId<Int?>>()))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                false
                false
                """.trimIndent(),
        )
    }

    @Test fun knownTypeAndTypeIdCanActAsPrerequisitesForOrdinaryRuleSearch() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.KnownType
            import one.wabbit.typeclass.TypeId
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface TypeWitness<A> {
                fun verdict(): String
            }

            @Instance
            context(known: KnownType<A>, id: TypeId<A>)
            fun <A> reflectiveWitness(): TypeWitness<A> =
                object : TypeWitness<A> {
                    override fun verdict(): String =
                        "${'$'}{"List" in known.kType.toString() && "String" in known.kType.toString()} | ${'$'}{id.canonicalName}"
                }

            context(witness: TypeWitness<A>)
            fun <A> render(): String = witness.verdict()

            fun main() {
                println(render<List<String?>>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "true | kotlin.collections.List<kotlin.String?>",
        )
    }

    @Test fun typeIdSupportsHashMapLookupAcrossRepeatedSummonsAndAliases() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.TypeId
            import one.wabbit.typeclass.summon

            typealias Age = Int

            fun main() {
                val values = hashMapOf<TypeId<*>, String>()
                values[summon<TypeId<Int>>()] = "int"
                println(values[summon<TypeId<Int>>()])
                println(values[summon<TypeId<Age>>()])
                println(values.size)
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                int
                int
                1
                """.trimIndent(),
        )
    }

    @Test fun typeIdCompareProducesEqualityProofForSemanticAliases() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Same
            import one.wabbit.typeclass.TypeId
            import one.wabbit.typeclass.TypeIdComparison
            import one.wabbit.typeclass.summon

            typealias Age = Int

            fun main() {
                val cmp = summon<TypeId<Int>>().compare(summon<TypeId<Age>>())
                when (cmp) {
                    is TypeIdComparison.Equal -> {
                        val eq: Same<Int, Age> = cmp.proof
                        println(eq.coerce(41))
                    }
                    is TypeIdComparison.Different -> println("different")
                }
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "41",
        )
    }

    @Test fun typeIdCompareProducesInequalityForDistinctSemanticTypes() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.TypeId
            import one.wabbit.typeclass.TypeIdComparison
            import one.wabbit.typeclass.summon

            fun main() {
                val cmp = summon<TypeId<Int>>().compare(summon<TypeId<String>>())
                when (cmp) {
                    is TypeIdComparison.Equal -> println("equal")
                    is TypeIdComparison.Different -> {
                        println(cmp.proof != null)
                        println(cmp.proof.flip() != null)
                    }
                }
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                true
                true
                """.trimIndent(),
        )
    }

    @Test fun typeIdDistinguishesStarProjectionsFromConcreteAndNullableArguments() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.TypeId
            import one.wabbit.typeclass.summon

            fun main() {
                println(summon<TypeId<List<Int>>>().sameAs(summon<TypeId<List<*>>>()))
                println(summon<TypeId<List<*>>>().sameAs(summon<TypeId<List<Any?>>>()))
                println(summon<TypeId<List<Int?>>>().sameAs(summon<TypeId<List<Int>>>()))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                false
                false
                false
                """.trimIndent(),
        )
    }

    @Test fun typeIdPreservesUseSiteVariance() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.TypeId
            import one.wabbit.typeclass.summon

            fun main() {
                println(summon<TypeId<Array<Int>>>().sameAs(summon<TypeId<Array<out Int>>>()))
                println(summon<TypeId<Array<in String>>>().sameAs(summon<TypeId<Array<Any?>>>()))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                false
                false
                """.trimIndent(),
        )
    }

    @Test fun typeIdTreatsNestedAliasesAsSemanticallyEqualInsideGenericArguments() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.TypeId
            import one.wabbit.typeclass.summon

            typealias Age = Int
            typealias Ages = List<Age>
            typealias Ints = List<Int>

            fun main() {
                val ages = summon<TypeId<Ages>>()
                val ints = summon<TypeId<Ints>>()
                println(ages.sameAs(ints))
                println(ages == ints)
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                true
                true
                """.trimIndent(),
        )
    }

    @Test fun typeIdDistinguishesValueClassesFromTheirUnderlyingTypes() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.TypeId
            import one.wabbit.typeclass.summon

            @JvmInline
            value class UserId(val value: Int)

            fun main() {
                val userId = summon<TypeId<UserId>>()
                val intId = summon<TypeId<Int>>()
                val values = hashMapOf<TypeId<*>, String>()
                values[userId] = "user-id"
                values[intId] = "int"
                println(userId.sameAs(intId))
                println(values[userId])
                println(values[intId])
                println(values.size)
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                false
                user-id
                int
                2
                """.trimIndent(),
        )
    }

    @Test fun rejectsTypeIdMaterializationForUnfixedGenericTypeParameters() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.TypeId
            import one.wabbit.typeclass.summon

            fun <T> impossible(): TypeId<T> =
                summon<TypeId<T>>() // ERROR TypeId requires an exact known semantic type
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("TypeId"),
            expectedDiagnostics = listOf(expectedErrorContaining("typeid")),
        )
    }

    @Test fun reifiedTypeIdMatchesConcreteInstantiationAndAliases() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.TypeId
            import one.wabbit.typeclass.summon

            typealias Age = Int

            inline fun <reified T> sameAsConcreteInt(): Boolean =
                summon<TypeId<T>>() == summon<TypeId<Int>>()

            fun main() {
                println(sameAsConcreteInt<Int>())
                println(sameAsConcreteInt<Age>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                true
                true
                """.trimIndent(),
        )
    }

    @Test fun typeIdDistinguishesFunctionTypeArgumentsAndNullability() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.TypeId
            import one.wabbit.typeclass.summon

            typealias Age = Int

            fun main() {
                println(summon<TypeId<(Int) -> String>>().sameAs(summon<TypeId<(Age) -> String>>()))
                println(summon<TypeId<(Int) -> String>>().sameAs(summon<TypeId<(Int?) -> String>>()))
                println(summon<TypeId<(Int) -> String>>().sameAs(summon<TypeId<(Int) -> String?>>()))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                true
                false
                false
                """.trimIndent(),
        )
    }
}
