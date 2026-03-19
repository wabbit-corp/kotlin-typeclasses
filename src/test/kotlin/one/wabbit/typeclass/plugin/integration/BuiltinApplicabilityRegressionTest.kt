package one.wabbit.typeclass.plugin.integration

import kotlin.test.Test

class BuiltinApplicabilityRegressionTest : IntegrationTestSupport() {
    @Test
    fun impossibleIsTypeclassInstancePrerequisiteDoesNotCreateFakeAmbiguity() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.IsTypeclassInstance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(value: Int): String = "exact:${'$'}value"
            }

            @Instance
            context(_: IsTypeclassInstance<List<Int>>)
            fun impossibleShow(): Show<Int> =
                object : Show<Int> {
                    override fun show(value: Int): String = "impossible:${'$'}value"
                }

            context(show: Show<Int>)
            fun render(): String = show.show(1)

            fun main() {
                println(render())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "exact:1",
        )
    }

    @Test
    fun validIsTypeclassInstancePrerequisiteStillParticipatesInOrdinaryRuleSearch() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.IsTypeclassInstance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance
            context(_: IsTypeclassInstance<Show<Int>>)
            fun derivedShow(): Show<Int> =
                object : Show<Int> {
                    override fun show(value: Int): String = "derived:${'$'}value"
                }

            context(show: Show<Int>)
            fun render(): String = show.show(2)

            fun main() {
                println(render())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "derived:2",
        )
    }

    @Test
    fun impossibleBuiltinKSerializerPrerequisiteDoesNotCreateFakeAmbiguity() {
        val source =
            """
            package demo

            import kotlinx.serialization.KSerializer
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            class NotSerializable(val value: Int)

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(value: Int): String = "exact:${'$'}value"
            }

            @Instance
            context(_: KSerializer<NotSerializable>)
            fun impossibleShow(): Show<Int> =
                object : Show<Int> {
                    override fun show(value: Int): String = "impossible:${'$'}value"
                }

            context(show: Show<Int>)
            fun render(): String = show.show(4)

            fun main() {
                println(render())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "exact:4",
            requiredPlugins = listOf(CompilerHarnessPlugin.Serialization),
            pluginOptions = listOf("builtinKSerializerTypeclass=enabled"),
        )
    }
}
