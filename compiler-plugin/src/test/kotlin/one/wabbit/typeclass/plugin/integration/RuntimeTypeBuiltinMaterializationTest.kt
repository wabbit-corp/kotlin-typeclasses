// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.typeclass.plugin.integration

import kotlin.test.Test

class RuntimeTypeBuiltinMaterializationTest : IntegrationTestSupport() {
    @Test
    fun nonReifiedNestedKnownTypePrerequisiteFailsAsMissingEvidence() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.KnownType
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Witness<A> {
                fun label(): String
            }

            @Instance
            context(_: KnownType<List<A>>)
            fun <A> knownListWitness(): Witness<A> =
                object : Witness<A> {
                    override fun label(): String = "known"
                }

            context(witness: Witness<A>)
            fun <A> render(): String = witness.label()

            fun <T> generic(): String =
                render<T>() // E:TC_NO_CONTEXT_ARGUMENT KnownType<List<T>> is not materializable for a non-reified T
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedAmbiguousOrNoContext("witness")),
            unexpectedMessages = listOf("exact known ktype"),
        )
    }

    @Test
    fun reifiedNestedKnownTypePrerequisiteStillParticipatesInRuleSearch() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.KnownType
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Witness<A> {
                fun label(): String
            }

            @Instance
            context(_: KnownType<List<A>>)
            fun <A> knownListWitness(): Witness<A> =
                object : Witness<A> {
                    override fun label(): String = "known"
                }

            context(witness: Witness<A>)
            fun <A> render(): String = witness.label()

            inline fun <reified T> generic(): String = render<T>()

            fun main() {
                println(generic<Int>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "known",
        )
    }

    @Test
    fun nonReifiedNestedTypeIdPrerequisiteFailsAsMissingEvidence() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.TypeId
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Witness<A> {
                fun label(): String
            }

            @Instance
            context(_: TypeId<List<A>>)
            fun <A> typeIdListWitness(): Witness<A> =
                object : Witness<A> {
                    override fun label(): String = "typeid"
                }

            context(witness: Witness<A>)
            fun <A> render(): String = witness.label()

            fun <T> generic(): String =
                render<T>() // E:TC_NO_CONTEXT_ARGUMENT TypeId<List<T>> is not materializable for a non-reified T
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedAmbiguousOrNoContext("witness")),
            unexpectedMessages = listOf("exact semantic type"),
        )
    }

    @Test
    fun reifiedNestedTypeIdPrerequisiteStillParticipatesInRuleSearch() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.TypeId
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Witness<A> {
                fun label(): String
            }

            @Instance
            context(_: TypeId<List<A>>)
            fun <A> typeIdListWitness(): Witness<A> =
                object : Witness<A> {
                    override fun label(): String = "typeid"
                }

            context(witness: Witness<A>)
            fun <A> render(): String = witness.label()

            inline fun <reified T> generic(): String = render<T>()

            fun main() {
                println(generic<Int>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "typeid",
        )
    }

    @Test
    fun nonReifiedNestedKSerializerPrerequisiteFailsAsMissingEvidence() {
        val source =
            """
            package demo

            import kotlinx.serialization.KSerializer
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Witness<A> {
                fun label(): String
            }

            @Instance
            context(_: KSerializer<List<A>>)
            fun <A> serializerListWitness(): Witness<A> =
                object : Witness<A> {
                    override fun label(): String = "serializer"
                }

            context(witness: Witness<A>)
            fun <A> render(): String = witness.label()

            fun <T> generic(): String =
                render<T>() // E:TC_NO_CONTEXT_ARGUMENT KSerializer<List<T>> is not materializable for a non-reified T
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedAmbiguousOrNoContext("witness")),
            unexpectedMessages = listOf("builtin kserializer", "reified target type"),
            requiredPlugins = listOf(CompilerHarnessPlugin.Serialization),
            pluginOptions = listOf("builtinKSerializerTypeclass=enabled"),
        )
    }

    @Test
    fun reifiedNestedKSerializerPrerequisiteStillParticipatesInRuleSearch() {
        val source =
            """
            package demo

            import kotlinx.serialization.KSerializer
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Witness<A> {
                fun label(): String
            }

            @Instance
            context(_: KSerializer<List<A>>)
            fun <A> serializerListWitness(): Witness<A> =
                object : Witness<A> {
                    override fun label(): String = "serializer"
                }

            context(witness: Witness<A>)
            fun <A> render(): String = witness.label()

            inline fun <reified T> generic(): String = render<T>()

            fun main() {
                println(generic<Int>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "serializer",
            requiredPlugins = listOf(CompilerHarnessPlugin.Serialization),
            pluginOptions = listOf("builtinKSerializerTypeclass=enabled"),
        )
    }
}
