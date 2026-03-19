package one.wabbit.typeclass.plugin.integration

import kotlin.test.Test

class ImportVisibilityTest : IntegrationTestSupport() {
    @Test fun topLevelInstancesDeclaredBesideTypeclassHeadsRemainUsable() {
        val sources =
            mapOf(
                "shared/Api.kt" to
                    """
                    package shared

                    import one.wabbit.typeclass.Instance
                    import one.wabbit.typeclass.Typeclass

                    @Typeclass
                    interface Show<A> {
                        fun show(value: A): String
                    }

                    @Instance
                    object IntShow : Show<Int> {
                        override fun show(value: Int): String = "head:${'$'}value"
                    }

                    context(show: Show<A>)
                    fun <A> render(value: A): String = show.show(value)
                    """.trimIndent(),
                "demo/Main.kt" to
                    """
                    package demo

                    import shared.render

                    fun main() {
                        println(render(1))
                    }
                    """.trimIndent(),
            )

        assertCompilesAndRuns(
            sources = sources,
            expectedStdout = "head:1",
            mainClass = "demo.MainKt",
        )
    }

    @Test fun topLevelInstancesDeclaredBesideConcreteProvidedClassifiersRemainUsable() {
        val sources =
            mapOf(
                "shared/Api.kt" to
                    """
                    package shared

                    import one.wabbit.typeclass.Typeclass

                    @Typeclass
                    interface Show<A> {
                        fun show(value: A): String
                    }

                    context(show: Show<A>)
                    fun <A> render(value: A): String = show.show(value)
                    """.trimIndent(),
                "alpha/AlphaId.kt" to
                    """
                    package alpha

                    import one.wabbit.typeclass.Instance
                    import shared.Show

                    data class AlphaId(val value: Int)

                    @Instance
                    object AlphaIdShow : Show<AlphaId> {
                        override fun show(value: AlphaId): String = "alpha-id:${'$'}{value.value}"
                    }
                    """.trimIndent(),
                "demo/Main.kt" to
                    """
                    package demo

                    import alpha.AlphaId
                    import shared.render

                    fun main() {
                        println(render(AlphaId(1)))
                    }
                    """.trimIndent(),
            )

        assertCompilesAndRuns(
            sources = sources,
            expectedStdout = "alpha-id:1",
            mainClass = "demo.MainKt",
        )
    }

    @Test fun rejectsTopLevelOrphanInstancesDeclaredOutsideRelevantFiles() {
        val sources =
            mapOf(
                "shared/Api.kt" to
                    """
                    package shared

                    import one.wabbit.typeclass.Typeclass

                    @Typeclass
                    interface Show<A> {
                        fun show(value: A): String
                    }
                    """.trimIndent(),
                "alpha/AlphaId.kt" to
                    """
                    package alpha

                    data class AlphaId(val value: Int)
                    """.trimIndent(),
                "beta/Instances.kt" to
                    """
                    package beta

                    import alpha.AlphaId
                    import one.wabbit.typeclass.Instance
                    import shared.Show

                    @Instance
                    object AlphaIdShow : Show<AlphaId> { // E:TC_INVALID_INSTANCE_DECL orphan instances must live with Show or AlphaId
                        override fun show(value: AlphaId): String = "beta:${'$'}{value.value}"
                    }
                    """.trimIndent(),
            )

        assertDoesNotCompile(
            sources = sources,
            expectedMessages = listOf("invalid @instance declaration", "same file", "show", "alphaid"),
            expectedDiagnostics = listOf(expectedInvalidInstanceDecl("same file", "show", "alphaid")),
        )
    }

    @Test fun rejectsConstructedTopLevelOrphanInstancesDeclaredOutsideRelevantFiles() {
        val sources =
            mapOf(
                "shared/Api.kt" to
                    """
                    package shared

                    import one.wabbit.typeclass.Instance
                    import one.wabbit.typeclass.Typeclass
                    import one.wabbit.typeclass.summon

                    data class Box<A>(val value: A)

                    @Typeclass
                    interface Show<A> {
                        fun label(): String
                    }

                    @Instance
                    context(show: Show<A>)
                    fun <A> boxShow(): Show<Box<A>> =
                        object : Show<Box<A>> {
                            override fun label(): String = "box-" + show.label()
                        }

                    context(_: Show<A>)
                    fun <A> which(): String = summon<Show<A>>().label()
                    """.trimIndent(),
                "alpha/AlphaId.kt" to
                    """
                    package alpha

                    import one.wabbit.typeclass.Instance
                    import shared.Show

                    data class AlphaId(val value: Int) {
                        companion object {
                            @Instance
                            val show =
                                object : Show<AlphaId> {
                                    override fun label(): String = "alpha-id"
                                }
                        }
                    }
                    """.trimIndent(),
                "beta/Instances.kt" to
                    """
                    package beta

                    import alpha.AlphaId
                    import one.wabbit.typeclass.Instance
                    import shared.Box
                    import shared.Show

                    @Instance
                    object BetaBoxAlphaIdShow : Show<Box<AlphaId>> { // E:TC_INVALID_INSTANCE_DECL orphan instances must live with Box or AlphaId
                        override fun label(): String = "beta-box"
                    }
                    """.trimIndent(),
                "demo/Main.kt" to
                    """
                    package demo

                    import alpha.AlphaId
                    import shared.Box
                    import shared.which

                    fun main() {
                        println(which<Box<AlphaId>>())
                    }
                    """.trimIndent(),
            )

        assertDoesNotCompile(
            sources = sources,
            expectedMessages = listOf("invalid @instance declaration", "same file", "show", "box", "alphaid"),
            expectedDiagnostics = listOf(expectedInvalidInstanceDecl("same file", "show", "box", "alphaid")),
        )
    }

    @Test fun internalDependencyInstancesDeclaredBesideTypeclassHeadsDoNotLeakAcrossModuleBoundaries() {
        val dependency =
            HarnessDependency(
                name = "dep-internal-instance",
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
                            internal object IntShow : Show<Int> {
                                override fun show(value: Int): String = "internal:${'$'}value"
                            }

                            context(show: Show<A>)
                            fun <A> render(value: A): String = show.show(value)
                            """.trimIndent(),
                    ),
            )
        val source =
            """
            package demo

            import dep.render

            fun main() {
                println(render(1)) // E:TC_NO_CONTEXT_ARGUMENT internal instance from dependency should not be visible here
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("no context argument", "show"),
            expectedDiagnostics = listOf(expectedErrorContaining("no context argument", "show")),
            dependencies = listOf(dependency),
        )
    }

    @Test fun publicAssociatedCompanionInstancesFromDependenciesParticipateInResolution() {
        val dependency =
            HarnessDependency(
                name = "dep-public-companion",
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
                            """.trimIndent(),
                    ),
            )
        val source =
            """
            package demo

            import dep.Box
            import dep.render

            fun main() {
                println(render(Box(1)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "box:1",
            dependencies = listOf(dependency),
        )
    }

    @Test fun privateAssociatedCompanionInstancesDoNotLeakAcrossDependencyBoundaries() {
        val dependency =
            HarnessDependency(
                name = "dep-private-companion",
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
                                    private val show: Show<Box> =
                                        object : Show<Box> {
                                            override fun show(value: Box): String = "hidden:${'$'}{value.value}"
                                        }
                                }
                            }

                            context(show: Show<Box>)
                            fun render(value: Box): String = show.show(value)
                            """.trimIndent(),
                    ),
            )
        val source =
            """
            package demo

            import dep.Box
            import dep.render

            fun main() {
                println(render(Box(1))) // E:TC_NO_CONTEXT_ARGUMENT private companion instance from dependency should not leak
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("no context argument", "show"),
            expectedDiagnostics = listOf(expectedErrorContaining("no context argument", "show")),
            dependencies = listOf(dependency),
        )
    }
}
