package one.wabbit.typeclass.plugin.integration

import kotlin.test.Test

class DownstreamNonPluginConsumerTest : IntegrationTestSupport() {
    @Test
    fun nonPluginConsumerCanUseTopLevelInstancesAndExplicitApisFromPluginDependency() {
        val dependency =
            HarnessDependency(
                name = "dep-api",
                sources =
                    mapOf(
                        "dep/Api.kt" to
                            """
                            package dep

                            import one.wabbit.typeclass.Instance
                            import one.wabbit.typeclass.Typeclass

                            @Typeclass
                            interface Show<A> {
                                fun show(value: A): String
                            }

                            @Instance
                            object IntShow : Show<Int> {
                                override fun show(value: Int): String = "dep:${'$'}value"
                            }

                            context(show: Show<Int>)
                            fun render(value: Int): String = show.show(value)

                            fun renderExplicit(show: Show<Int>, value: Int): String = show.show(value)
                            """.trimIndent(),
                    ),
            )
        val source =
            """
            package demo

            import dep.IntShow
            import dep.renderExplicit

            fun main() {
                println(IntShow.show(1))
                println(renderExplicit(IntShow, 2))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                dep:1
                dep:2
                """.trimIndent(),
            dependencies = listOf(dependency),
            useTypeclassPlugin = false,
            enableContextParameters = false,
        )
    }

    @Test
    fun nonPluginConsumerCanUseAssociatedCompanionInstancesFromPluginDependency() {
        val dependency =
            HarnessDependency(
                name = "dep-box",
                sources =
                    mapOf(
                        "dep/Api.kt" to
                            """
                            package dep

                            import one.wabbit.typeclass.Instance
                            import one.wabbit.typeclass.Typeclass

                            @Typeclass
                            interface Show<A> {
                                fun show(value: A): String
                            }

                            data class Box(val value: Int) {
                                companion object {
                                    @Instance
                                    val show =
                                        object : Show<Box> {
                                            override fun show(value: Box): String = "box:${'$'}{value.value}"
                                        }
                                }
                            }

                            context(show: Show<Box>)
                            fun render(value: Box): String = show.show(value)

                            fun renderExplicit(show: Show<Box>, value: Box): String = show.show(value)
                            """.trimIndent(),
                    ),
            )
        val source =
            """
            package demo

            import dep.Box
            import dep.renderExplicit

            fun main() {
                println(Box.show.show(Box(1)))
                println(renderExplicit(Box.show, Box(2)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                box:1
                box:2
                """.trimIndent(),
            dependencies = listOf(dependency),
            useTypeclassPlugin = false,
            enableContextParameters = false,
        )
    }

    @Test
    fun nonPluginConsumerCanExplicitlyCallContextualFunctionsFromPluginDependency() {
        val dependency =
            HarnessDependency(
                name = "dep-wrapper",
                sources =
                    mapOf(
                        "dep/Api.kt" to
                            """
                            package dep

                            import one.wabbit.typeclass.Instance
                            import one.wabbit.typeclass.Typeclass

                            @Typeclass
                            interface Show<A> {
                                fun show(value: A): String
                            }

                            @Instance
                            object IntShow : Show<Int> {
                                override fun show(value: Int): String = "wrapped:${'$'}value"
                            }

                            context(show: Show<Int>)
                            fun render(value: Int): String = show.show(value)
                            """.trimIndent(),
                    ),
            )
        val source =
            """
            package demo

            import dep.IntShow
            import dep.render

            fun main() {
                context(IntShow) {
                    println(render(7))
                }
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "wrapped:7",
            dependencies = listOf(dependency),
            useTypeclassPlugin = false,
            enableContextParameters = true,
        )
    }
}
