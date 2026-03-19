package one.wabbit.typeclass.plugin.integration

import kotlin.test.Test

class DerivationBoundaryTest : IntegrationTestSupport() {
    @Test
    fun consumerModuleCanUseDependencyCompanionInstancesForContextualFunctions() {
        val dependency =
            HarnessDependency(
                name = "dep-companion-show",
                sources =
                    mapOf(
                        "dep/Show.kt" to showTypeclassSource(packageName = "dep"),
                        "dep/ShownString.kt" to shownStringSource("dep"),
                    ),
            )
        val source =
            """
            package demo

            import dep.ShownString
            import dep.render

            fun main() {
                println(render(ShownString("dep")))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "dep",
            dependencies = listOf(dependency),
        )
    }

    @Test
    fun derivesSealedHierarchiesSplitAcrossFiles() {
        val sources =
            mapOf(
                "shared/Show.kt" to showTypeclassSource(packageName = "shared"),
                "shared/ShownString.kt" to shownStringSource("shared"),
                "shared/Token.kt" to
                    """
                    package shared

                    import one.wabbit.typeclass.Derive

                    @Derive(Show::class)
                    sealed interface Token
                    """.trimIndent(),
                "shared/Word.kt" to
                    """
                    package shared

                    data class Word(val value: ShownString) : Token
                    """.trimIndent(),
                "shared/End.kt" to
                    """
                    package shared

                    object End : Token
                    """.trimIndent(),
                "demo/Main.kt" to
                    """
                    package demo

                    import shared.End
                    import shared.ShownString
                    import shared.Token
                    import shared.Word
                    import shared.render

                    fun main() {
                        val word: Token = Word(ShownString("hi"))
                        val end: Token = End
                        println(render(word))
                        println(render(end))
                    }
                    """.trimIndent(),
            )

        assertCompilesAndRuns(
            sources = sources,
            expectedStdout =
                """
                Word(value=hi)
                End()
                """.trimIndent(),
            mainClass = "demo.MainKt",
        )
    }

    @Test
    fun derivesGenericSealedHierarchiesSplitAcrossFiles() {
        val sources =
            mapOf(
                "shared/Show.kt" to showTypeclassSource(packageName = "shared"),
                "shared/ShownInt.kt" to shownIntSource("shared"),
                "shared/Envelope.kt" to
                    """
                    package shared

                    import one.wabbit.typeclass.Derive

                    @Derive(Show::class)
                    sealed class Envelope<out A>
                    """.trimIndent(),
                "shared/Value.kt" to
                    """
                    package shared

                    data class Value<A>(val value: A) : Envelope<A>()
                    """.trimIndent(),
                "shared/Missing.kt" to
                    """
                    package shared

                    object Missing : Envelope<Nothing>()
                    """.trimIndent(),
                "demo/Main.kt" to
                    """
                    package demo

                    import shared.Envelope
                    import shared.Missing
                    import shared.ShownInt
                    import shared.Value
                    import shared.render

                    fun main() {
                        val missing: Envelope<ShownInt> = Missing
                        println(render(Value(ShownInt(1))))
                        println(render(missing))
                    }
                    """.trimIndent(),
            )

        assertCompilesAndRuns(
            sources = sources,
            expectedStdout =
                """
                Value(value=1)
                Missing()
                """.trimIndent(),
            mainClass = "demo.MainKt",
        )
    }

    @Test
    fun consumerModuleCanUseDerivedSealedInstancesFromDependencyModules() {
        val dependency =
            HarnessDependency(
                name = "dep-derived-sealed",
                sources =
                    mapOf(
                        "dep/Show.kt" to showTypeclassSource(packageName = "dep"),
                        "dep/ShownString.kt" to shownStringSource("dep"),
                        "dep/Token.kt" to
                            """
                            package dep

                            import one.wabbit.typeclass.Derive

                            @Derive(Show::class)
                            sealed interface Token
                            """.trimIndent(),
                        "dep/Word.kt" to
                            """
                            package dep

                            data class Word(val value: ShownString) : Token
                            """.trimIndent(),
                        "dep/End.kt" to
                            """
                            package dep

                            object End : Token
                            """.trimIndent(),
                    ),
            )
        val source =
            """
            package demo

            import dep.End
            import dep.ShownString
            import dep.Token
            import dep.Word
            import dep.render

            fun main() {
                val word: Token = Word(ShownString("dep"))
                val end: Token = End
                println(render(word))
                println(render(end))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                Word(value=dep)
                End()
                """.trimIndent(),
            dependencies = listOf(dependency),
        )
    }

    @Test
    fun consumerModuleCanUseDerivedGenericSealedInstancesFromDependencyModules() {
        val dependency =
            HarnessDependency(
                name = "dep-derived-generic-sealed",
                sources =
                    mapOf(
                        "dep/Show.kt" to showTypeclassSource(packageName = "dep"),
                        "dep/ShownInt.kt" to shownIntSource("dep"),
                        "dep/Envelope.kt" to
                            """
                            package dep

                            import one.wabbit.typeclass.Derive

                            @Derive(Show::class)
                            sealed class Envelope<out A>
                            """.trimIndent(),
                        "dep/Value.kt" to
                            """
                            package dep

                            data class Value<A>(val value: A) : Envelope<A>()
                            """.trimIndent(),
                        "dep/Missing.kt" to
                            """
                            package dep

                            object Missing : Envelope<Nothing>()
                            """.trimIndent(),
                    ),
            )
        val source =
            """
            package demo

            import dep.Envelope
            import dep.Missing
            import dep.ShownInt
            import dep.Value
            import dep.render

            fun main() {
                val missing: Envelope<ShownInt> = Missing
                println(render(Value(ShownInt(2))))
                println(render(missing))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                Value(value=2)
                Missing()
                """.trimIndent(),
            dependencies = listOf(dependency),
        )
    }

    @Test
    fun consumerModuleCanUseDeriversDefinedInAnUpstreamDependencyModule() {
        val typeclassDependency =
            HarnessDependency(
                name = "dep-shared-show",
                sources =
                    mapOf(
                        "dep/Show.kt" to showTypeclassSource(packageName = "dep"),
                    ),
            )
        val modelDependency =
            HarnessDependency(
                name = "dep-split-derived-sealed",
                dependencies = listOf(typeclassDependency),
                sources =
                    mapOf(
                        "model/ShownString.kt" to
                            """
                            package model

                            import dep.Show
                            import one.wabbit.typeclass.Instance

                            data class ShownString(val value: String) {
                                companion object {
                                    @Instance
                                    val show: Show<ShownString> =
                                        object : Show<ShownString> {
                                            override fun show(value: ShownString): String = value.value
                                        }
                                }
                            }
                            """.trimIndent(),
                        "model/Token.kt" to
                            """
                            package model

                            import dep.Show
                            import one.wabbit.typeclass.Derive

                            @Derive(Show::class)
                            sealed interface Token
                            """.trimIndent(),
                        "model/Word.kt" to
                            """
                            package model

                            data class Word(val value: ShownString) : Token
                            """.trimIndent(),
                        "model/End.kt" to
                            """
                            package model

                            object End : Token
                            """.trimIndent(),
                    ),
            )
        val source =
            """
            package demo

            import dep.render
            import model.End
            import model.ShownString
            import model.Token
            import model.Word

            fun main() {
                val word: Token = Word(ShownString("split"))
                val end: Token = End
                println(render(word))
                println(render(end))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                Word(value=split)
                End()
                """.trimIndent(),
            dependencies = listOf(modelDependency),
        )
    }

    @Test
    fun consumerModuleGetsUsefulFailureWhenDependencySealedSubclassLacksEvidence() {
        val typeclassDependency =
            HarnessDependency(
                name = "dep-show-missing-case",
                sources = mapOf("dep/Show.kt" to showTypeclassSource(packageName = "dep")),
            )
        val modelDependency =
            HarnessDependency(
                name = "model-missing-case",
                dependencies = listOf(typeclassDependency),
                sources =
                    mapOf(
                        "model/NoShow.kt" to
                            """
                            package model

                            data class NoShow(val value: String)
                            """.trimIndent(),
                        "model/Token.kt" to
                            """
                            package model

                            import dep.Show
                            import one.wabbit.typeclass.Derive

                            @Derive(Show::class)
                            sealed interface Token
                            """.trimIndent(),
                        "model/Word.kt" to
                            """
                            package model

                            data class Word(val value: NoShow) : Token
                            """.trimIndent(),
                        "model/End.kt" to
                            """
                            package model

                            object End : Token
                            """.trimIndent(),
                    ),
            )
        val source =
            """
            package demo

            import dep.render
            import model.NoShow
            import model.Token
            import model.Word

            fun main() {
                val token: Token = Word(NoShow("missing"))
                println(render(token)) // E:TC_NO_CONTEXT_ARGUMENT missing dependency field evidence
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("missing typeclass instance", "show"),
            expectedDiagnostics = listOf(expectedNoContextArgument("show", phase = DiagnosticPhase.IR)),
            dependencies = listOf(modelDependency),
            unexpectedMessages = listOf("internal compiler error"),
        )
    }

    @Test
    fun consumerModuleGetsAmbiguityWhenDependencyExportsManualAndDerivedSealedInstances() {
        val typeclassDependency =
            HarnessDependency(
                name = "dep-show-conflict",
                sources = mapOf("dep/Show.kt" to showTypeclassSource(packageName = "dep")),
            )
        val modelDependency =
            HarnessDependency(
                name = "model-derived-conflict",
                dependencies = listOf(typeclassDependency),
                sources =
                    mapOf(
                        "model/ShownString.kt" to shownStringSource("model", showPackage = "dep"),
                        "model/Token.kt" to
                            """
                            package model

                            import dep.Show
                            import one.wabbit.typeclass.Derive
                            import one.wabbit.typeclass.Instance

                            @Derive(Show::class)
                            sealed interface Token {
                                companion object {
                                    @Instance
                                    val show: Show<Token> =
                                        object : Show<Token> {
                                            override fun show(value: Token): String = "manual"
                                        }
                                }
                            }
                            """.trimIndent(),
                        "model/Word.kt" to
                            """
                            package model

                            data class Word(val value: ShownString) : Token
                            """.trimIndent(),
                        "model/End.kt" to
                            """
                            package model

                            object End : Token
                            """.trimIndent(),
                    ),
            )
        val source =
            """
            package demo

            import dep.render
            import model.ShownString
            import model.Token
            import model.Word

            fun main() {
                val token: Token = Word(ShownString("clash"))
                println(render(token))
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("show"),
            expectedDiagnostics = listOf(expectedAmbiguousOrNoContext("show")),
            dependencies = listOf(modelDependency),
            unexpectedMessages = listOf("internal compiler error"),
        )
    }

    @Test
    fun missingSecondDerivedHeadAcrossDependenciesDoesNotBreakTheWorkingHead() {
        val typeclassDependency =
            HarnessDependency(
                name = "dep-show-eq",
                sources =
                    mapOf(
                        "dep/Show.kt" to showTypeclassSource(packageName = "dep"),
                        "dep/Eq.kt" to eqTypeclassSource(packageName = "dep"),
                    ),
            )
        val modelDependency =
            HarnessDependency(
                name = "model-multi-derive",
                dependencies = listOf(typeclassDependency),
                sources =
                    mapOf(
                        "model/ShownString.kt" to shownStringSource("model", showPackage = "dep"),
                        "model/Token.kt" to
                            """
                            package model

                            import dep.Eq
                            import dep.Show
                            import one.wabbit.typeclass.Derive

                            @Derive(Show::class, Eq::class)
                            sealed interface Token
                            """.trimIndent(),
                        "model/Word.kt" to
                            """
                            package model

                            data class Word(val value: ShownString) : Token
                            """.trimIndent(),
                        "model/End.kt" to
                            """
                            package model

                            object End : Token
                            """.trimIndent(),
                    ),
            )
        val source =
            """
            package demo

            import dep.render
            import dep.same
            import model.End
            import model.ShownString
            import model.Token
            import model.Word

            fun main() {
                val word: Token = Word(ShownString("ok"))
                println(render(word))
                println(render(End))
                println(same(word, word)) // E:TC_NO_CONTEXT_ARGUMENT missing second derived head
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("no context argument", "eq"),
            expectedDiagnostics = listOf(expectedNoContextArgument("eq")),
            dependencies = listOf(modelDependency),
            unexpectedMessages = listOf("internal compiler error"),
        )
    }
}

private fun showTypeclassSource(
    packageName: String,
): String =
    """
    package $packageName

    import one.wabbit.typeclass.Instance
    import one.wabbit.typeclass.ProductTypeclassMetadata
    import one.wabbit.typeclass.SumTypeclassMetadata
    import one.wabbit.typeclass.Typeclass
    import one.wabbit.typeclass.TypeclassDeriver
    import one.wabbit.typeclass.get
    import one.wabbit.typeclass.matches

    @Typeclass
    interface Show<A> {
        fun show(value: A): String

        companion object : TypeclassDeriver {
            override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                object : Show<Any?> {
                    override fun show(value: Any?): String {
                        require(value != null)
                        val renderedFields =
                            metadata.fields.joinToString(", ") { field ->
                                val fieldValue = field.get(value)
                                val fieldShow = field.instance as Show<Any?>
                                "${'$'}{field.name}=${'$'}{fieldShow.show(fieldValue)}"
                            }
                        val typeName = metadata.typeName.substringAfterLast('.')
                        return "${'$'}typeName(${'$'}renderedFields)"
                    }
                }

            override fun deriveSum(metadata: SumTypeclassMetadata): Any =
                object : Show<Any?> {
                    override fun show(value: Any?): String {
                        require(value != null)
                        val matchingCase = metadata.cases.single { candidate -> candidate.matches(value) }
                        val caseShow = matchingCase.instance as Show<Any?>
                        return caseShow.show(value)
                    }
                }
        }
    }

    context(show: Show<A>)
    fun <A> render(value: A): String = show.show(value)
    """.trimIndent()

private fun shownStringSource(packageName: String): String =
    """
    package $packageName

    import one.wabbit.typeclass.Instance

    data class ShownString(val value: String) {
        companion object {
            @Instance
            val show: Show<ShownString> =
                object : Show<ShownString> {
                    override fun show(value: ShownString): String = value.value
                }
        }
    }
    """.trimIndent()

private fun shownStringSource(
    packageName: String,
    showPackage: String,
): String =
    """
    package $packageName

    import one.wabbit.typeclass.Instance

    data class ShownString(val value: String) {
        companion object {
            @Instance
            val show: $showPackage.Show<ShownString> =
                object : $showPackage.Show<ShownString> {
                    override fun show(value: ShownString): String = value.value
                }
        }
    }
    """.trimIndent()

private fun shownIntSource(packageName: String): String =
    """
    package $packageName

    import one.wabbit.typeclass.Instance

    data class ShownInt(val value: Int) {
        companion object {
            @Instance
            val show: Show<ShownInt> =
                object : Show<ShownInt> {
                    override fun show(value: ShownInt): String = value.value.toString()
                }
        }
    }
    """.trimIndent()

private fun eqTypeclassSource(
    packageName: String,
): String =
    """
    package $packageName

    import one.wabbit.typeclass.ProductTypeclassMetadata
    import one.wabbit.typeclass.SumTypeclassMetadata
    import one.wabbit.typeclass.Typeclass
    import one.wabbit.typeclass.TypeclassDeriver
    import one.wabbit.typeclass.get
    import one.wabbit.typeclass.matches

    @Typeclass
    interface Eq<A> {
        fun equal(left: A, right: A): Boolean

        companion object : TypeclassDeriver {
            override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                object : Eq<Any?> {
                    override fun equal(left: Any?, right: Any?): Boolean {
                        if (left == null || right == null) {
                            return left == right
                        }
                        return metadata.fields.all { field ->
                            val fieldEq = field.instance as Eq<Any?>
                            fieldEq.equal(field.get(left), field.get(right))
                        }
                    }
                }

            override fun deriveSum(metadata: SumTypeclassMetadata): Any =
                object : Eq<Any?> {
                    override fun equal(left: Any?, right: Any?): Boolean {
                        if (left == null || right == null) {
                            return left == right
                        }
                        val leftCase = metadata.cases.singleOrNull { candidate -> candidate.matches(left) } ?: return false
                        val rightCase = metadata.cases.singleOrNull { candidate -> candidate.matches(right) } ?: return false
                        if (leftCase.name != rightCase.name) {
                            return false
                        }
                        val caseEq = leftCase.instance as Eq<Any?>
                        return caseEq.equal(left, right)
                    }
                }
        }
    }

    context(eq: Eq<A>)
    fun <A> same(left: A, right: A): Boolean = eq.equal(left, right)
    """.trimIndent()
