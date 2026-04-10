// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.typeclass.plugin.integration.proofs

import one.wabbit.typeclass.plugin.integration.ExpectedDiagnostic
import one.wabbit.typeclass.plugin.integration.IntegrationTestSupport
import kotlin.test.Test

class NullabilityProofTest : IntegrationTestSupport() {
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

    @Test fun materializesNullableAndNotNullableProofsForExactGenericCases() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.NotNullable
            import one.wabbit.typeclass.Nullable
            import one.wabbit.typeclass.summon

            fun <T> proveNullable(): Nullable<T?> = summon<Nullable<T?>>()

            fun <T : Any> proveNotNullable(): NotNullable<T> = summon<NotNullable<T>>()

            fun main() {
                println(proveNullable<String>() != null)
                println(proveNotNullable<String>() != null)
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

    @Test fun rejectsNullableAndNotNullableProofsWhenNullabilityIsUnknownOrWrong() {
        fun assertNullabilityFailure(
            declaration: String,
        ) {
            val source =
                """
                package demo

                import one.wabbit.typeclass.NotNullable
                import one.wabbit.typeclass.Nullable

                context(_: Nullable<T>)
                fun <T> needsNullable(): String = "nullable"

                context(_: NotNullable<T>)
                fun <T> needsNotNullable(): String = "not-nullable"

                $declaration
                """.trimIndent()

            assertDoesNotCompile(
                source = source,
                expectedDiagnostics =
                    listOf(
                        ExpectedDiagnostic.Error(messageRegex = "(?i)(nullable|notnullable)"),
                    ),
            )
        }

        assertNullabilityFailure(
            """
            fun <T> impossibleNullable(): String =
                needsNullable<T>() // E:TC_NO_CONTEXT_ARGUMENT the compiler cannot prove that T admits null
            """.trimIndent(),
        )
        assertNullabilityFailure(
            """
            fun <T> impossibleNotNullable(): String =
                needsNotNullable<T>() // E:TC_NO_CONTEXT_ARGUMENT the compiler cannot prove that T excludes null
            """.trimIndent(),
        )
        assertNullabilityFailure(
            """
            fun wrongNullableCall() {
                println(needsNullable<String>()) // E:TC_NO_CONTEXT_ARGUMENT String is not nullable
            }
            """.trimIndent(),
        )
        assertNullabilityFailure(
            """
            fun wrongNotNullableCall() {
                println(needsNotNullable<String?>()) // E:TC_NO_CONTEXT_ARGUMENT String? is nullable
            }
            """.trimIndent(),
        )
    }

    @Test fun rejectsNotNullableProofForJavaPlatformType() {
        val sources =
            mapOf(
                "demo/JavaApi.java" to
                    """
                    package demo;

                    public final class JavaApi {
                        private JavaApi() {}

                        public static String platformString() {
                            return null;
                        }
                    }
                    """.trimIndent(),
                "Sample.kt" to
                    """
                    package demo

                    import one.wabbit.typeclass.NotNullable

                    context(_: NotNullable<T>)
                    fun <T> requiresNotNullable(value: T): String = "not-nullable"

                    fun fail(): String =
                        requiresNotNullable(JavaApi.platformString()) // E:TC_NO_CONTEXT_ARGUMENT platform nullability is unknown
                    """.trimIndent(),
            )

        assertDoesNotCompile(
            sources = sources,
            expectedDiagnostics = listOf(expectedNoContextArgument("notnullable")),
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
}
