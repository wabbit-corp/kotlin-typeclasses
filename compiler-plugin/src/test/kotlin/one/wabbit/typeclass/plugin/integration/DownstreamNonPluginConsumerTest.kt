// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

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

    @Test
    fun downstreamPluginIgnoresRawDependencyDeriveAnnotationsWithoutGeneratedMetadata() {
        val dependency =
            HarnessDependency(
                name = "dep-raw-derive",
                useTypeclassPlugin = false,
                enableContextParameters = false,
                sources =
                    mapOf(
                        "dep/Api.kt" to
                            """
                            package dep

                            import one.wabbit.typeclass.Derive
                            import one.wabbit.typeclass.ProductTypeclassMetadata
                            import one.wabbit.typeclass.SumTypeclassMetadata
                            import one.wabbit.typeclass.Typeclass
                            import one.wabbit.typeclass.TypeclassDeriver

                            @Typeclass
                            interface Show<A> {
                                fun show(value: A): String

                                companion object : TypeclassDeriver {
                                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Show<Any?> =
                                        object : Show<Any?> {
                                            override fun show(value: Any?): String = metadata.typeName.substringAfterLast('.')
                                        }

                                    override fun deriveSum(metadata: SumTypeclassMetadata): Show<Any?> =
                                        object : Show<Any?> {
                                            override fun show(value: Any?): String = metadata.typeName.substringAfterLast('.')
                                        }
                                }
                            }

                            @Derive(Show::class)
                            data class Token(val value: Int)
                            """.trimIndent(),
                    ),
            )
        val source =
            """
            package demo

            import dep.Token
            import dep.Show

            context(_: Show<Token>)
            fun render(value: Token): String = "derived:${'$'}{value.value}"

            fun render(value: Token): String = "plain:${'$'}{value.value}"

            fun main() {
                println(render(Token(1)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "plain:1",
            dependencies = listOf(dependency),
        )
    }

    @Test
    fun downstreamPluginCanDeriveAgainstBinaryAnyReturningProductDerivers() {
        val dependency =
            HarnessDependency(
                name = "dep-binary-any-deriver",
                sources =
                    mapOf(
                        "dep/Api.kt" to
                            """
                            package dep

                            import one.wabbit.typeclass.Instance
                            import one.wabbit.typeclass.ProductTypeclassDeriver
                            import one.wabbit.typeclass.ProductTypeclassMetadata
                            import one.wabbit.typeclass.Typeclass

                            @Typeclass
                            interface Show<A> {
                                fun show(value: A): String

                                companion object : ProductTypeclassDeriver {
                                    private object ExistingShow : Show<Any?> {
                                        override fun show(value: Any?): String = "binary-any"
                                    }

                                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any = ExistingShow
                                }
                            }

                            data class ShownInt(val value: Int) {
                                companion object {
                                    @Instance
                                    val show: Show<ShownInt> =
                                        object : Show<ShownInt> {
                                            override fun show(value: ShownInt): String = value.value.toString()
                                        }
                                }
                            }

                            context(show: Show<A>)
                            fun <A> render(value: A): String = show.show(value)
                            """.trimIndent(),
                    ),
            )
        val source =
            """
            package demo

            import dep.Show
            import dep.ShownInt
            import dep.render
            import one.wabbit.typeclass.Derive

            @Derive(Show::class)
            data class Box(val value: ShownInt)

            fun main() {
                println(render(Box(ShownInt(1))))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "binary-any",
            dependencies = listOf(dependency),
        )
    }

    @Test
    fun downstreamPluginIgnoresUnvalidatedGeneratedDeriveMetadataFromDependency() {
        val dependency =
            HarnessDependency(
                name = "dep-raw-generated-derive",
                useTypeclassPlugin = false,
                enableContextParameters = false,
                sources =
                    mapOf(
                        "dep/Api.kt" to
                            """
                            package dep

                            import one.wabbit.typeclass.GeneratedTypeclassInstance
                            import one.wabbit.typeclass.Instance
                            import one.wabbit.typeclass.ProductTypeclassDeriver
                            import one.wabbit.typeclass.ProductTypeclassMetadata
                            import one.wabbit.typeclass.Typeclass

                            @Typeclass
                            interface Show<A> {
                                fun show(value: A): String

                                companion object : ProductTypeclassDeriver {
                                    private object ExistingShow : Show<Any?> {
                                        override fun show(value: Any?): String = "binary-any"
                                    }

                                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any = ExistingShow
                                }
                            }

                            @Instance
                            object IntShow : Show<Int> {
                                override fun show(value: Int): String = value.toString()
                            }

                            @GeneratedTypeclassInstance(
                                typeclassId = "dep/Show",
                                targetId = "dep/Box",
                                kind = "derive",
                            )
                            data class Box(val value: Int)
                            """.trimIndent(),
                    ),
            )
        val source =
            """
            package demo

            import dep.Box
            import dep.Show

            context(show: Show<Box>)
            fun render(value: Box): String = show.show(value)

            fun main() {
                println(render(Box(1))) // E:TC_NO_CONTEXT_ARGUMENT unvalidated generated derive metadata must not create binary shape-derived evidence
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedNoContextArgument()),
            dependencies = listOf(dependency),
        )
    }

    @Test
    fun downstreamPluginIgnoresRawDependencyDeriveViaAnnotationsWithoutGeneratedMetadata() {
        val dependency =
            HarnessDependency(
                name = "dep-raw-derive-via",
                useTypeclassPlugin = false,
                enableContextParameters = false,
                sources =
                    mapOf(
                        "dep/Api.kt" to
                            """
                            package dep

                            import one.wabbit.typeclass.DeriveVia
                            import one.wabbit.typeclass.Instance
                            import one.wabbit.typeclass.Typeclass

                            @Typeclass
                            interface Show<A> {
                                fun show(value: A): String
                            }

                            data class Wire(val value: Int)

                            @Instance
                            object WireShow : Show<Wire> {
                                override fun show(value: Wire): String = "wire:${'$'}{value.value}"
                            }

                            @JvmInline
                            @DeriveVia(Show::class, Wire::class)
                            value class Token(val value: Int)
                            """.trimIndent(),
                    ),
            )
        val source =
            """
            package demo

            import dep.Token
            import dep.Show

            context(_: Show<Token>)
            fun render(value: Token): String = "derived:${'$'}{value.value}"

            fun render(value: Token): String = "plain:${'$'}{value.value}"

            fun main() {
                println(render(Token(2)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "plain:2",
            dependencies = listOf(dependency),
        )
    }

    @Test
    fun downstreamPluginIgnoresRawDependencyDeriveEquivAnnotationsWithoutGeneratedMetadata() {
        val dependency =
            HarnessDependency(
                name = "dep-raw-derive-equiv",
                useTypeclassPlugin = false,
                enableContextParameters = false,
                sources =
                    mapOf(
                        "dep/Api.kt" to
                            """
                            package dep

                            import one.wabbit.typeclass.DeriveEquiv

                            data class Wire(val value: Int)

                            @DeriveEquiv(Wire::class)
                            data class Token(val value: Int)
                            """.trimIndent(),
                    ),
            )
        val source =
            """
            package demo

            import dep.Token
            import dep.Wire
            import one.wabbit.typeclass.Equiv
            import one.wabbit.typeclass.InternalTypeclassApi

            @OptIn(InternalTypeclassApi::class)
            context(_: Equiv<Token, Wire>)
            fun render(value: Token): String = "derived:${'$'}{value.value}"

            fun render(value: Token): String = "plain:${'$'}{value.value}"

            fun main() {
                println(render(Token(3)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "plain:3",
            dependencies = listOf(dependency),
        )
    }
}
