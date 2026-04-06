// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.typeclass.plugin.integration.proofs

import one.wabbit.typeclass.plugin.integration.IntegrationTestSupport
import kotlin.test.Test

class RuntimeTypeProofTest : IntegrationTestSupport() {
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
                summon<KnownType<T>>() // E:TC_NO_CONTEXT_ARGUMENT the compiler does not know the exact KType for an unfixed T
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedErrorContaining("no context argument", "value")),
            unexpectedMessages = listOf("exact known ktype"),
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
                summon<TypeId<T>>() // E:TC_NO_CONTEXT_ARGUMENT TypeId requires an exact known semantic type
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedErrorContaining("no context argument", "value")),
            unexpectedMessages = listOf("exact semantic type"),
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
