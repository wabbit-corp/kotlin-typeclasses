// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.typeclass.plugin.integration.proofs

import one.wabbit.typeclass.plugin.integration.CompilerHarnessPlugin
import one.wabbit.typeclass.plugin.integration.IntegrationTestSupport
import kotlin.test.Test

class TypeclassMetaProofTest : IntegrationTestSupport() {
    private val serializationPlugins = listOf(CompilerHarnessPlugin.Serialization)

    @Test fun isTypeclassInstanceRejectsOrdinaryAppliedTypes() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.IsTypeclassInstance

            context(_: IsTypeclassInstance<TC>)
            fun <TC> proof(): String = "typeclass"

            fun main() {
                println(proof<List<Int>>()) // E:TC_NO_CONTEXT_ARGUMENT List<Int> is not a typeclass application
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedErrorContaining("no context argument", "istypeclassinstance")),
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
}
