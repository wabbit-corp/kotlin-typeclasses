package one.wabbit.typeclass.plugin.integration

import kotlin.test.Test

class RuleIdentityRegressionTest : IntegrationTestSupport() {
    @Test
    fun overloadedInstanceRulesWithSameNameAndProvidedTypeDoNotCollapseById() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Alpha<A>

            @Typeclass
            interface Beta<A>

            @Typeclass
            interface Show<A> {
                fun label(): String
            }

            @Instance
            object AlphaInt : Alpha<Int>

            @Instance
            context(_: Beta<Int>)
            fun witness(): Show<Int> =
                object : Show<Int> {
                    override fun label(): String = "beta"
                }

            @Instance
            context(_: Alpha<Int>)
            fun witness(): Show<Int> =
                object : Show<Int> {
                    override fun label(): String = "alpha"
                }

            context(show: Show<Int>)
            fun render(): String = show.label()

            context(_: Alpha<Int>)
            fun useAlphaOnly(): String = render()

            fun main() {
                println(useAlphaOnly())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "alpha",
        )
    }

    @Test
    fun overloadedInstanceRulesWithSameNameAndProvidedTypeStillReportAmbiguity() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Alpha<A>

            @Typeclass
            interface Beta<A>

            @Typeclass
            interface Show<A> {
                fun label(): String
            }

            @Instance
            object AlphaInt : Alpha<Int>

            @Instance
            object BetaInt : Beta<Int>

            @Instance
            context(_: Beta<Int>)
            fun witness(): Show<Int> =
                object : Show<Int> {
                    override fun label(): String = "beta"
                }

            @Instance
            context(_: Alpha<Int>)
            fun witness(): Show<Int> =
                object : Show<Int> {
                    override fun label(): String = "alpha"
                }

            context(show: Show<Int>)
            fun render(): String = show.label()

            context(_: Alpha<Int>, _: Beta<Int>)
            fun useBoth(): String = render() // E:TC_NO_CONTEXT_ARGUMENT ambiguous Show<Int> resolution
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("ambiguous", "show"),
            expectedDiagnostics = listOf(expectedAmbiguousOrNoContext("show")),
        )
    }
}
