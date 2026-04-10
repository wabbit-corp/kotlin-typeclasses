// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.typeclass.plugin.integration

import kotlin.test.Test

class HarnessDeterminismTest : IntegrationTestSupport() {
    @Test
    fun failingCompilationDoesNotPoisonLaterSuccessfulCompilationInSameJvm() {
        val failingSource =
            """
            package demo

            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(1)) // E:TC_NO_CONTEXT_ARGUMENT first compile should fail without leaking into the next one
            }
            """
                .trimIndent()

        assertDoesNotCompile(
            source = failingSource,
            expectedDiagnostics = listOf(expectedNoContextArgument("show")),
        )

        val succeedingSource =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(value: Int): String = "ok:${'$'}value"
            }

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(1))
            }
            """
                .trimIndent()

        assertCompilesAndRuns(source = succeedingSource, expectedStdout = "ok:1")
    }

    @Test
    fun earlierSuccessfulCompilationDoesNotLeakInstancesIntoLaterCompilation() {
        val successfulSource =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(value: Int): String = "first:${'$'}value"
            }

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(1))
            }
            """
                .trimIndent()

        assertCompilesAndRuns(source = successfulSource, expectedStdout = "first:1")

        val laterSource =
            """
            package demo

            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(1)) // E:TC_NO_CONTEXT_ARGUMENT earlier instances must not leak into this compilation
            }
            """
                .trimIndent()

        assertDoesNotCompile(
            source = laterSource,
            expectedDiagnostics = listOf(expectedNoContextArgument("show")),
        )
    }

    @Test
    fun repeatedSuccessfulCompilesRemainStableInSameJvm() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(value: Int): String = "repeat:${'$'}value"
            }

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(7))
            }
            """
                .trimIndent()

        assertCompilesAndRuns(source = source, expectedStdout = "repeat:7")
        assertCompilesAndRuns(source = source, expectedStdout = "repeat:7")
    }

    @Test
    fun multiFileCompilationOrderDoesNotChangeOutcome() {
        val sourcesA =
            linkedMapOf(
                "demo/Api.kt" to
                    """
                    package demo

                    import one.wabbit.typeclass.Instance
                    import one.wabbit.typeclass.Typeclass

                    @Typeclass
                    interface Show<A> {
                        fun show(value: A): String
                    }

                    @Instance
                    object IntShow : Show<Int> {
                        override fun show(value: Int): String = "order:${'$'}value"
                    }

                    context(show: Show<A>)
                    fun <A> render(value: A): String = show.show(value)
                    """
                        .trimIndent(),
                "demo/Main.kt" to
                    """
                    package demo

                    fun main() {
                        println(render(9))
                    }
                    """
                        .trimIndent(),
            )
        val sourcesB =
            linkedMapOf(
                "demo/Main.kt" to sourcesA.getValue("demo/Main.kt"),
                "demo/Api.kt" to sourcesA.getValue("demo/Api.kt"),
            )

        assertCompilesAndRuns(
            sources = sourcesA,
            expectedStdout = "order:9",
            mainClass = "demo.MainKt",
        )
        assertCompilesAndRuns(
            sources = sourcesB,
            expectedStdout = "order:9",
            mainClass = "demo.MainKt",
        )
    }

    @Test
    fun dependencyCompilationResultsDoNotBleedAcrossRuns() {
        val dependency =
            HarnessDependency(
                name = "dep-show",
                sources =
                    mapOf(
                        "dep/Api.kt" to
                            """
                            package dep

                            import one.wabbit.typeclass.Typeclass
                            import one.wabbit.typeclass.Instance

                            @Typeclass
                            interface Show<A> {
                                fun show(value: A): String
                            }

                            data class Token(val value: Int) {
                                companion object {
                                    @Instance
                                    val show: Show<Token> =
                                        object : Show<Token> {
                                            override fun show(value: Token): String = "dep:${'$'}{value.value}"
                                        }
                                }
                            }

                            context(show: Show<A>)
                            fun <A> render(value: A): String = show.show(value)
                            """
                                .trimIndent()
                    ),
            )
        val consumerSource =
            """
            package demo

            import dep.Token // E
            import dep.render // E

            fun main() {
                println(render(Token(11))) // E
            }
            """
                .trimIndent()

        assertCompilesAndRuns(
            source = consumerSource,
            expectedStdout = "dep:11",
            dependencies = listOf(dependency),
        )

        assertDoesNotCompile(
            source = consumerSource,
            expectedDiagnostics =
                listOf(
                    expectedErrorContaining(
                        "unresolved reference",
                        "dep",
                        file = "Sample.kt",
                        line = 3,
                    ),
                    expectedErrorContaining(
                        "unresolved reference",
                        "dep",
                        file = "Sample.kt",
                        line = 4,
                    ),
                    expectedErrorContaining(
                        "unresolved reference",
                        "render",
                        file = "Sample.kt",
                        line = 7,
                    ),
                    expectedErrorContaining(
                        "unresolved reference",
                        "token",
                        file = "Sample.kt",
                        line = 7,
                    ),
                ),
            dependencies = emptyList(),
        )
    }
}
