package one.wabbit.typeclass.plugin.integration

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
            expectedMessages = listOf("subtype", "invariant", "contravariant", "string"),
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
}
