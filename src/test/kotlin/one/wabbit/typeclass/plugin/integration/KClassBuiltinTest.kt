package one.wabbit.typeclass.plugin.integration

import kotlin.test.Test

class KClassBuiltinTest : IntegrationTestSupport() {
    @Test fun doesNotTreatKClassAsBuiltinTypeclassWhenFlagDisabled() {
        val source =
            """
            package demo

            import kotlin.reflect.KClass
            import one.wabbit.typeclass.summon

            fun main() {
                println(summon<KClass<Int>>())
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = emptyList(),
            expectedDiagnostics =
                listOf(
                    ExpectedDiagnostic.Error(
                        messageRegex = "(?i)no context argument",
                    ),
                ),
        )
    }

    @Test fun treatsKClassAsBuiltinTypeclassWhenFlagEnabled() {
        val source =
            """
            package demo

            import kotlin.reflect.KClass

            context(kClass: KClass<T>)
            fun <T : Any> matches(expected: KClass<T>): Boolean = kClass == expected

            fun main() {
                println(matches<Int>(Int::class))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "true",
            pluginOptions = listOf("builtinKClassTypeclass=enabled"),
        )
    }

    @Test fun doesNotRecognizeKClassAsTypeclassForIsTypeclassInstanceWhenFlagDisabled() {
        val source =
            """
            package demo

            import kotlin.reflect.KClass
            import one.wabbit.typeclass.IsTypeclassInstance

            context(_: IsTypeclassInstance<KClass<Int>>)
            fun proof(): String = "kclass-typeclass"

            fun main() {
                println(proof())
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = emptyList(),
            expectedDiagnostics =
                listOf(
                    ExpectedDiagnostic.Error(
                        messageRegex = "(?i)(typeclass application|no context argument)",
                    ),
                ),
        )
    }

    @Test fun reifiedSyntheticKClassMatchesClassLiteral() {
        val source =
            """
            package demo

            import kotlin.reflect.KClass
            import one.wabbit.typeclass.summon

            inline fun <reified T : Any> sameKClass(): Boolean =
                summon<KClass<T>>() == T::class

            fun main() {
                println(sameKClass<Int>())
                println(sameKClass<String>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                true
                true
                """.trimIndent(),
            pluginOptions = listOf("builtinKClassTypeclass=enabled"),
        )
    }

    @Test fun reifiedHelpersCanSummonSyntheticKClasses() {
        val source =
            """
            package demo

            import kotlin.reflect.KClass
            import one.wabbit.typeclass.summon

            context(kClass: KClass<T>)
            fun <T : Any> contextualClass(): KClass<T> = kClass

            inline fun <reified T : Any> reifiedClass(): KClass<T> = summon<KClass<T>>()

            fun main() {
                println(contextualClass<Int>() == Int::class)
                println(reifiedClass<String>() == String::class)
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                true
                true
                """.trimIndent(),
            pluginOptions = listOf("builtinKClassTypeclass=enabled"),
        )
    }

    @Test fun explicitLocalKClassEvidenceShadowsSyntheticBuiltin() {
        val source =
            """
            package demo

            import kotlin.reflect.KClass
            import one.wabbit.typeclass.summon

            object FakeIntKClass : KClass<Int> by Int::class {
                override val simpleName: String? get() = "FakeInt"
            }

            context(kClass: KClass<Int>)
            fun chosenSimpleName(): String = summon<KClass<Int>>().simpleName ?: "missing"

            fun main() {
                context(FakeIntKClass) {
                    println(chosenSimpleName())
                }
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "FakeInt",
            pluginOptions = listOf("builtinKClassTypeclass=enabled"),
        )
    }

    @Test fun rejectsNonReifiedGenericKClassMaterializationWithoutExplicitEvidence() {
        val source =
            """
            package demo

            import kotlin.reflect.KClass
            import one.wabbit.typeclass.summon

            fun <T : Any> impossible(): KClass<T> =
                summon<KClass<T>>() // E:TC_NO_CONTEXT_ARGUMENT generic T has no concrete KClass proof here
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("kclass", "concrete"),
            expectedDiagnostics =
                listOf(
                    ExpectedDiagnostic.Error(
                        messageRegex = "(?i)(kclass|no context argument|reified|runtime)",
                    ),
                ),
            pluginOptions = listOf("builtinKClassTypeclass=enabled"),
        )
    }

    @Test fun rejectsNullableKClassMaterializationEvenInsideReifiedHelpers() {
        val source =
            """
            package demo

            import kotlin.reflect.KClass
            import one.wabbit.typeclass.summon

            inline fun <reified T> impossible(): KClass<T> =
                summon<KClass<T>>() // KClass proofs only exist for non-nullable runtime-available types

            fun main() {
                println(impossible<String?>())
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("kclass", "non-null"),
            expectedDiagnostics =
                listOf(
                    ExpectedDiagnostic.Error(
                        messageRegex = "(?i)(kclass|non-null|upper bound|any)",
                    ),
                ),
            pluginOptions = listOf("builtinKClassTypeclass=enabled"),
        )
    }
}
