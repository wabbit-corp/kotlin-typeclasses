// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.typeclass.plugin.integration.derivation

import one.wabbit.typeclass.plugin.cannotDeriveMissingEnumOverride
import one.wabbit.typeclass.plugin.cannotDeriveOnlyUnaryTypeclasses
import one.wabbit.typeclass.plugin.cannotDeriveConstructiveProductStoredPropertyMismatch
import one.wabbit.typeclass.plugin.cannotDeriveRequiresPrimaryConstructor
import one.wabbit.typeclass.plugin.cannotDeriveMissingRequiredDeriver
import one.wabbit.typeclass.plugin.cannotDeriveUnsupportedShape
import one.wabbit.typeclass.plugin.cannotDeriveWrongDeriverReturnType
import one.wabbit.typeclass.plugin.integration.IntegrationTestSupport
import one.wabbit.typeclass.plugin.integration.DiagnosticPhase
import one.wabbit.typeclass.plugin.integration.HarnessDependency
import kotlin.test.Test

class DerivationCapabilityTest : IntegrationTestSupport() {
    @Test
    fun deriveAnnotationsSatisfyTheRequestedTypeclass() {
        val source =
            requestedHeadOnlySource(
                extraDeclarations =
                    """
                    context(show: Show<A>)
                    fun <A> render(value: A): String = show.show(value)
                    """,
                mainBody = """println(render(User("ada")))""",
            )

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "User",
        )
    }

    @Test
    fun deriveAnnotationsDoNotSatisfyUnrequestedTypeclasses() {
        val source =
            requestedHeadOnlySource(
                extraDeclarations =
                    """
        context(_: Eq<User>)
        fun requiresEq(): String = "eq"
        """,
                mainBody = """println(requiresEq()) // E:TC_NO_CONTEXT_ARGUMENT only Show<User> is derived here, not Eq<User>""",
            )

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedNoContextArgument("eq")),
        )
    }

    @Test
    fun deriveAnnotationsDoNotSatisfyNullableInstantiationsOfTheDerivedHead() {
        val source =
            requestedHeadOnlySource(
                extraDeclarations =
                    """
        context(show: Show<A>)
        fun <A> render(value: A): String = show.show(value)
        """,
                mainBody = """println(render<User?>(null)) // E:TC_NO_CONTEXT_ARGUMENT @Derive(Show::class) for User does not imply Show<User?>""",
            )

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedNoContextArgument("show", phase = DiagnosticPhase.IR)),
        )
    }

    @Test
    fun nonUnaryTypeclassesCannotBeDerived() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.ProductTypeclassDeriver
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Decoder<E, A> {
                fun decode(env: E): A

                companion object : ProductTypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                        object : Decoder<String, Any?> {
                            override fun decode(env: String): Any? = metadata.typeName
                        }
                }
            }

            @Derive(Decoder::class) // E:TC_CANNOT_DERIVE non-unary typeclasses are not derivable with @Derive
            data class Box(val value: Int)
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedTypeclassDiagnostic(cannotDeriveOnlyUnaryTypeclasses()),
                ),
        )
    }

    @Test
    fun helperMethodsNamedLikeDeriversDoNotTriggerValidationOnPlainTypeclassCompanions() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String

                companion object {
                    fun deriveProduct(debug: String): String = "debug:${'$'}debug"
                }
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(value: Int): String = "int:${'$'}value"
            }

            context(show: Show<Int>)
            fun renderInt(): String = show.show(1)

            fun main() {
                println(renderInt())
                println(Show.deriveProduct("ok"))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                int:1
                debug:ok
                """.trimIndent(),
        )
    }

    @Test
    fun helperOverloadsNamedLikeDeriversDoNotBreakActualDerivation() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.ProductTypeclassDeriver
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String

                companion object : ProductTypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                        object : Show<Any?> {
                            override fun show(value: Any?): String = metadata.typeName
                        }

                    fun deriveProduct(debug: String): String = "debug:${'$'}debug"
                }
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(value: Int): String = "int:${'$'}value"
            }

            @Derive(Show::class)
            data class Box(val value: Int)

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(Box(1)))
                println(Show.deriveProduct("ok"))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                demo.Box
                debug:ok
                """.trimIndent(),
        )
    }

    @Test
    fun deriveProductMayUseInheritedDefaultImplementation() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.ProductTypeclassDeriver
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.Typeclass

            interface SharedShowDeriver : ProductTypeclassDeriver {
                override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                    object : Show<Any?> {
                        override fun show(value: Any?): String = metadata.typeName
                    }
            }

            @Typeclass
            interface Show<A> {
                fun show(value: A): String

                companion object : SharedShowDeriver
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(value: Int): String = "int:${'$'}value"
            }

            @Derive(Show::class)
            data class Box(val value: Int)

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(Box(1)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "demo.Box",
        )
    }

    @Test
    fun deriveProductWorksWithNamedTypeclassCompanions() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.ProductTypeclassDeriver
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String

                companion object NamedFactory : ProductTypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                        object : Show<Any?> {
                            override fun show(value: Any?): String = metadata.typeName
                        }
                }
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(value: Int): String = "int:${'$'}value"
            }

            @Derive(Show::class)
            data class Box(val value: Int)

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(Box(1)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "demo.Box",
        )
    }

    @Test
    fun deriveSumMayUseInheritedDefaultImplementation() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.SumTypeclassMetadata
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.TypeclassDeriver
            import one.wabbit.typeclass.matches

            interface SharedShowDeriver : TypeclassDeriver {
                override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                    object : Show<Any?> {
                        override fun show(value: Any?): String = metadata.typeName
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

            @Typeclass
            interface Show<A> {
                fun show(value: A): String

                companion object : SharedShowDeriver
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(value: Int): String = value.toString()
            }

            @Derive(Show::class)
            sealed interface Token

            @Derive(Show::class)
            data class Count(val value: Int) : Token

            @Derive(Show::class)
            data object End : Token

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(Count(1) as Token))
                println(render(End as Token))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                demo.Count
                demo.End
                """.trimIndent(),
        )
    }

    @Test
    fun deriveEnumMayUseInheritedDefaultImplementation() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.EnumTypeclassMetadata
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.SumTypeclassMetadata
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.TypeclassDeriver

            interface SharedShowDeriver : TypeclassDeriver {
                override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                    object : Show<Any?> {
                        override fun show(value: Any?): String = metadata.typeName
                    }

                override fun deriveSum(metadata: SumTypeclassMetadata): Any =
                    object : Show<Any?> {
                        override fun show(value: Any?): String = metadata.typeName
                    }

                override fun deriveEnum(metadata: EnumTypeclassMetadata): Any =
                    object : Show<Any?> {
                        override fun show(value: Any?): String = metadata.typeName
                    }
            }

            @Typeclass
            interface Show<A> {
                fun show(value: A): String

                companion object : SharedShowDeriver
            }

            @Derive(Show::class)
            enum class Priority {
                LOW,
                HIGH,
            }

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(Priority.LOW))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "demo.Priority",
        )
    }

    @Test
    fun helperDeriveEnumOverloadsDoNotCountAsTheRequiredEnumOverride() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.SumTypeclassMetadata
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.TypeclassDeriver

            @Typeclass
            interface Show<A> {
                fun show(value: A): String

                companion object : TypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                        object : Show<Any?> {
                            override fun show(value: Any?): String = metadata.typeName
                        }

                    override fun deriveSum(metadata: SumTypeclassMetadata): Any =
                        object : Show<Any?> {
                            override fun show(value: Any?): String = metadata.typeName
                        }

                    fun deriveEnum(debug: String): String = "debug:${'$'}debug"
                }
            }

            @Derive(Show::class) // E:TC_CANNOT_DERIVE helper deriveEnum overloads do not satisfy the enum override contract
            enum class Mode {
                ON,
                OFF,
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedTypeclassDiagnostic(cannotDeriveMissingEnumOverride("Show")),
                ),
        )
    }

    @Test
    fun deriveProductMustReturnTheRequestedTypeclassConstructorWhenStaticallyKnown() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.ProductTypeclassDeriver
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Eq<A> {
                fun eqv(left: A, right: A): Boolean
            }

            @Typeclass
            interface Show<A> {
                fun show(value: A): String

                companion object : ProductTypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any { // E:TC_CANNOT_DERIVE
                        return object : Eq<Any?> {
                            override fun eqv(left: Any?, right: Any?): Boolean = true
                        }
                    }
                }
            }

            @Derive(Show::class)
            data class Box(val value: Int)
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedTypeclassDiagnostic(
                        cannotDeriveWrongDeriverReturnType("deriveProduct", "Show", "Eq"),
                        phase = null,
                    ),
                ),
        )
    }

    @Test
    fun deriveProductDeclaredReturnTypeMustExpandToTheOwningTypeclass() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.ProductTypeclassDeriver
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String

                companion object : ProductTypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any = 42 // E:TC_CANNOT_DERIVE
                }
            }

            @Derive(Show::class)
            data class Box(val value: Int)
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedTypeclassDiagnostic(cannotDeriveWrongDeriverReturnType("deriveProduct", "Show")),
                ),
        )
    }

    @Test
    fun deriveProductAnonymousObjectReturnMustStillProveTheOwningTypeclass() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.ProductTypeclassDeriver
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String

                companion object : ProductTypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any = object {} // E:TC_CANNOT_DERIVE
                }
            }

            @Derive(Show::class)
            data class Box(val value: Int)
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedTypeclassDiagnostic(cannotDeriveWrongDeriverReturnType("deriveProduct", "Show")),
                ),
        )
    }

    @Test
    fun deriveProductAnonymousObjectReturnRecognizesDependencyTypeclassHeads() {
        val dependency =
            HarnessDependency(
                name = "dep-eq-head",
                sources =
                    mapOf(
                        "dep/Eq.kt" to
                            """
                            package dep

                            import one.wabbit.typeclass.Typeclass

                            @Typeclass
                            interface Eq<A> {
                                fun eqv(left: A, right: A): Boolean
                            }
                            """.trimIndent(),
                    ),
            )
        val source =
            """
            package demo

            import dep.Eq
            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.ProductTypeclassDeriver
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String

                companion object : ProductTypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any { // E:TC_CANNOT_DERIVE
                        return object : Eq<Any?> {
                            override fun eqv(left: Any?, right: Any?): Boolean = true
                        }
                    }
                }
            }

            @Derive(Show::class)
            data class Box(val value: Int)
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            dependencies = listOf(dependency),
            expectedDiagnostics =
                listOf(
                    expectedTypeclassDiagnostic(
                        cannotDeriveWrongDeriverReturnType("deriveProduct", "Show", "Eq"),
                        phase = null,
                    ),
                ),
        )
    }

    @Test
    fun deriveProductMayReturnAnonymousObjectsThroughInheritedTypeclassBases() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.ProductTypeclassDeriver
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.Typeclass

            abstract class SharedShowBase : Show<Any?> {
                override fun show(value: Any?): String = "shared"
            }

            @Typeclass
            interface Show<A> {
                fun show(value: A): String

                companion object : ProductTypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                        object : SharedShowBase() {}
                }
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(value: Int): String = "int:${'$'}value"
            }

            @Derive(Show::class)
            data class Box(val value: Int)

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(Box(1)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "shared",
        )
    }

    @Test
    fun deriveProductMayReturnAnExistingInstanceObjectThroughAny() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.ProductTypeclassDeriver
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String

                companion object : ProductTypeclassDeriver {
                    private object ExistingShow : Show<Any?> {
                        override fun show(value: Any?): String = "existing"
                    }

                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any = ExistingShow
                }
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(value: Int): String = "int:${'$'}value"
            }

            @Derive(Show::class)
            data class Box(val value: Int)

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(Box(1)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "existing",
        )
    }

    @Test
    fun deriveProductMayReturnAConcreteConstructorCallThroughAny() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.ProductTypeclassDeriver
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.Typeclass

            class ShowImpl(private val typeName: String) : Show<Any?> {
                override fun show(value: Any?): String = typeName
            }

            @Typeclass
            interface Show<A> {
                fun show(value: A): String

                companion object : ProductTypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                        ShowImpl(metadata.typeName)
                }
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(value: Int): String = "int:${'$'}value"
            }

            @Derive(Show::class)
            data class Box(val value: Int)

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(Box(1)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "demo.Box",
        )
    }

    @Test
    fun deriveProductMayReturnAnAnyTypedLocalBindingWhoseConcreteValueImplementsTheOwningTypeclass() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.ProductTypeclassDeriver
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String

                companion object : ProductTypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any {
                        val instance: Any =
                            object : Show<Any?> {
                                override fun show(value: Any?): String = "local"
                            }
                        return instance
                    }
                }
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(value: Int): String = "int:${'$'}value"
            }

            @Derive(Show::class)
            data class Box(val value: Int)

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(Box(1)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "local",
        )
    }

    @Test
    fun deriveProductMayReturnThroughAnyTypedHelpersThatConcretelyBuildTheOwningTypeclass() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.ProductTypeclassDeriver
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String

                companion object : ProductTypeclassDeriver {
                    private fun helper(metadata: ProductTypeclassMetadata): Any =
                        object : Show<Any?> {
                            override fun show(value: Any?): String = metadata.typeName
                        }

                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                        helper(metadata)
                }
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(value: Int): String = "int:${'$'}value"
            }

            @Derive(Show::class)
            data class Box(val value: Int)

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(Box(1)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "demo.Box",
        )
    }

    @Test
    fun deriveProductMayReturnThroughAnyTypedHelperPropertiesWithCustomGetters() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.ProductTypeclassDeriver
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String

                companion object : ProductTypeclassDeriver {
                    private val helper: Any
                        get() =
                            object : Show<Any?> {
                                override fun show(value: Any?): String = "getter"
                            }

                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                        helper
                }
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(value: Int): String = "int:${'$'}value"
            }

            @Derive(Show::class)
            data class Box(val value: Int)

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(Box(1)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "getter",
        )
    }

    @Test
    fun deriveSumMustReturnTheRequestedTypeclassConstructorWhenStaticallyKnown() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.SumTypeclassMetadata
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.TypeclassDeriver

            @Typeclass
            interface Eq<A> {
                fun eqv(left: A, right: A): Boolean
            }

            @Typeclass
            interface Show<A> {
                fun show(value: A): String

                companion object : TypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                        object : Show<Any?> {
                            override fun show(value: Any?): String = metadata.typeName
                        }

                    override fun deriveSum(metadata: SumTypeclassMetadata): Any { // E:TC_CANNOT_DERIVE
                        return object : Eq<Any?> {
                            override fun eqv(left: Any?, right: Any?): Boolean = true
                        }
                    }
                }
            }

            @Derive(Show::class)
            sealed interface Token

            data class Word(val value: String) : Token
            object End : Token
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedTypeclassDiagnostic(
                        cannotDeriveWrongDeriverReturnType("deriveSum", "Show", "Eq"),
                        phase = null,
                    ),
                ),
        )
    }

    @Test
    fun deriveSumDeclaredReturnTypeMustExpandToTheOwningTypeclass() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.SumTypeclassMetadata
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.TypeclassDeriver

            @Typeclass
            interface Show<A> {
                fun show(value: A): String

                companion object : TypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                        object : Show<Any?> {
                            override fun show(value: Any?): String = metadata.typeName
                        }

                    override fun deriveSum(metadata: SumTypeclassMetadata): Any = 42 // E:TC_CANNOT_DERIVE
                }
            }

            @Derive(Show::class)
            sealed interface Token

            data class Word(val value: String) : Token
            object End : Token
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedTypeclassDiagnostic(cannotDeriveWrongDeriverReturnType("deriveSum", "Show")),
                ),
        )
    }

    @Test
    fun deriveSumAnonymousObjectReturnMustStillProveTheOwningTypeclass() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.SumTypeclassMetadata
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.TypeclassDeriver

            @Typeclass
            interface Show<A> {
                fun show(value: A): String

                companion object : TypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                        object : Show<Any?> {
                            override fun show(value: Any?): String = metadata.typeName
                        }

                    override fun deriveSum(metadata: SumTypeclassMetadata): Any = object {} // E:TC_CANNOT_DERIVE
                }
            }

            @Derive(Show::class)
            sealed interface Token

            data class Word(val value: String) : Token
            object End : Token
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedTypeclassDiagnostic(cannotDeriveWrongDeriverReturnType("deriveSum", "Show")),
                ),
        )
    }

    @Test
    fun deriveEnumMustReturnTheRequestedTypeclassConstructorWhenStaticallyKnown() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.EnumTypeclassMetadata
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.TypeclassDeriver

            @Typeclass
            interface Eq<A> {
                fun eqv(left: A, right: A): Boolean
            }

            @Typeclass
            interface Show<A> {
                fun show(value: A): String

                companion object : TypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                        object : Show<Any?> {
                            override fun show(value: Any?): String = metadata.typeName
                        }

                    override fun deriveSum(metadata: one.wabbit.typeclass.SumTypeclassMetadata): Any =
                        object : Show<Any?> {
                            override fun show(value: Any?): String = metadata.typeName
                        }

                    override fun deriveEnum(metadata: EnumTypeclassMetadata): Any { // E:TC_CANNOT_DERIVE
                        return object : Eq<Any?> {
                            override fun eqv(left: Any?, right: Any?): Boolean = true
                        }
                    }
                }
            }

            @Derive(Show::class)
            enum class Priority {
                LOW,
                HIGH,
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedTypeclassDiagnostic(
                        cannotDeriveWrongDeriverReturnType("deriveEnum", "Show", "Eq"),
                        phase = null,
                    ),
                ),
        )
    }

    @Test
    fun deriveEnumAnonymousObjectReturnMustStillProveTheOwningTypeclass() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.EnumTypeclassMetadata
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.SumTypeclassMetadata
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.TypeclassDeriver

            @Typeclass
            interface Show<A> {
                fun show(value: A): String

                companion object : TypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                        object : Show<Any?> {
                            override fun show(value: Any?): String = metadata.typeName
                        }

                    override fun deriveSum(metadata: SumTypeclassMetadata): Any =
                        object : Show<Any?> {
                            override fun show(value: Any?): String = metadata.typeName
                        }

                    override fun deriveEnum(metadata: EnumTypeclassMetadata): Any = object {} // E:TC_CANNOT_DERIVE
                }
            }

            @Derive(Show::class)
            enum class Priority {
                LOW,
                HIGH,
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedTypeclassDiagnostic(cannotDeriveWrongDeriverReturnType("deriveEnum", "Show")),
                ),
        )
    }

    @Test
    fun deriveEnumDeclaredReturnTypeMustExpandToTheOwningTypeclass() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.EnumTypeclassMetadata
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.SumTypeclassMetadata
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.TypeclassDeriver

            @Typeclass
            interface Show<A> {
                fun show(value: A): String

                companion object : TypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                        object : Show<Any?> {
                            override fun show(value: Any?): String = metadata.typeName
                        }

                    override fun deriveSum(metadata: SumTypeclassMetadata): Any =
                        object : Show<Any?> {
                            override fun show(value: Any?): String = metadata.typeName
                        }

                    override fun deriveEnum(metadata: EnumTypeclassMetadata): Any = 42 // E:TC_CANNOT_DERIVE
                }
            }

            @Derive(Show::class)
            enum class Priority {
                LOW,
                HIGH,
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedTypeclassDiagnostic(cannotDeriveWrongDeriverReturnType("deriveEnum", "Show")),
                ),
        )
    }

    @Test
    fun enumDerivationRequiresExplicitDeriveEnumOverride() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.SumTypeclassMetadata
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.TypeclassDeriver

            @Typeclass
            interface Show<A> {
                fun show(value: A): String

                companion object : TypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                        object : Show<Any?> {
                            override fun show(value: Any?): String = metadata.typeName
                        }

                    override fun deriveSum(metadata: SumTypeclassMetadata): Any =
                        object : Show<Any?> {
                            override fun show(value: Any?): String = metadata.typeName
                        }
                }
            }

            @Derive(Show::class) // E:TC_CANNOT_DERIVE
            enum class Priority {
                LOW,
                HIGH,
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedTypeclassDiagnostic(cannotDeriveMissingEnumOverride("Show"), phase = null),
                ),
        )
    }

    @Test
    fun productOnlyDeriversCannotDeriveSealedSums() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.ProductTypeclassDeriver
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String

                companion object : ProductTypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                        object : Show<Any?> {
                            override fun show(value: Any?): String = metadata.typeName
                        }
                }
            }

            @Derive(Show::class) // E:TC_CANNOT_DERIVE
            sealed interface Token

            data class Word(val value: String) : Token
            object End : Token
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedTypeclassDiagnostic(
                        cannotDeriveMissingRequiredDeriver(
                            typeclassName = "Show",
                            requiredName = "TypeclassDeriver",
                            detail = "ProductTypeclassDeriver only supports products, not sealed sums",
                        ),
                        phase = null,
                    ),
                ),
        )
    }

    @Test
    fun openClassesCannotBeDerived() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.ProductTypeclassDeriver
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String

                companion object : ProductTypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                        object : Show<Any?> {
                            override fun show(value: Any?): String = metadata.typeName
                        }
                }
            }

            @Derive(Show::class) // E:TC_CANNOT_DERIVE
            open class Box(val value: Int)
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedTypeclassDiagnostic(cannotDeriveUnsupportedShape(), phase = null),
                ),
        )
    }

    @Test
    fun constructiveProductDerivationRequiresConstructorParametersToMatchStoredProperties() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.ProductTypeclassDeriver
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String

                companion object : ProductTypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                        object : Show<Any?> {
                            override fun show(value: Any?): String = metadata.typeName
                        }
                }
            }

            @Derive(Show::class) // E:TC_CANNOT_DERIVE
            class Stats(count: Int) {
                val total: Int = count
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedTypeclassDiagnostic(
                        cannotDeriveConstructiveProductStoredPropertyMismatch("demo.Stats"),
                        phase = null,
                    ),
                ),
        )
    }

    @Test
    fun constructiveProductDerivationRequiresPrimaryConstructor() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.ProductTypeclassDeriver
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String

                companion object : ProductTypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                        object : Show<Any?> {
                            override fun show(value: Any?): String = metadata.typeName
                        }
                }
            }

            @Derive(Show::class) // E:TC_CANNOT_DERIVE
            class Stats {
                val total: Int

                constructor(total: Int) {
                    this.total = total
                }
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedTypeclassDiagnostic(cannotDeriveRequiresPrimaryConstructor(), phase = null),
                ),
        )
    }

    @Test
    fun constructiveProductDerivationRejectsPrivateStoredProperties() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.ProductTypeclassDeriver
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String

                companion object : ProductTypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                        object : Show<Any?> {
                            override fun show(value: Any?): String = metadata.typeName
                        }
                }
            }

            @Derive(Show::class) // E:TC_CANNOT_DERIVE
            data class Secret(private val value: Int)
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedExactCannotDerive(
                        "constructive product derivation requires public stored properties; demo.Secret.value is not public.",
                        phase = null,
                    ),
                ),
        )
    }

    @Test
    fun constructiveProductDerivationRejectsPrivatePrimaryConstructors() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.ProductTypeclassDeriver
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String

                companion object : ProductTypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                        object : Show<Any?> {
                            override fun show(value: Any?): String = metadata.typeName
                        }
                }
            }

            @Derive(Show::class) // E:TC_CANNOT_DERIVE
            data class Secret private constructor(val value: Int)
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedExactCannotDerive(
                        "constructive product derivation requires a public primary constructor for demo.Secret.",
                        phase = null,
                    ),
                ),
        )
    }

    @Test
    fun sealedRootDerivationRejectsCasesThatCannotDeriveProducts() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.SumTypeclassMetadata
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.TypeclassDeriver

            @Typeclass
            interface Show<A> {
                fun show(value: A): String

                companion object : TypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                        object : Show<Any?> {
                            override fun show(value: Any?): String = metadata.typeName
                        }

                    override fun deriveSum(metadata: SumTypeclassMetadata): Any =
                        object : Show<Any?> {
                            override fun show(value: Any?): String = metadata.typeName
                        }
                }
            }

            @Derive(Show::class) // E:TC_CANNOT_DERIVE
            sealed interface Token

            data class Public(val value: Int) : Token

            data class Secret private constructor(val value: Int) : Token
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedExactCannotDerive(
                        "Cannot derive demo.Token because sealed subclass demo.Secret is not itself derivable: constructive product derivation requires a public primary constructor for demo.Secret.",
                        phase = null,
                    ),
                ),
        )
    }

    private fun requestedHeadOnlySource(
        extraDeclarations: String,
        mainBody: String,
    ): String =
        """
        package demo

        import one.wabbit.typeclass.Derive
        import one.wabbit.typeclass.Instance
        import one.wabbit.typeclass.ProductTypeclassMetadata
        import one.wabbit.typeclass.SumTypeclassMetadata
        import one.wabbit.typeclass.Typeclass
        import one.wabbit.typeclass.TypeclassDeriver

        @Typeclass
        interface Show<A> {
            fun show(value: A): String

            companion object : TypeclassDeriver {
                override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                    object : Show<Any?> {
                        override fun show(value: Any?): String = metadata.typeName.substringAfterLast('.')
                    }

                override fun deriveSum(metadata: SumTypeclassMetadata): Any =
                    object : Show<Any?> {
                        override fun show(value: Any?): String = metadata.typeName.substringAfterLast('.')
                    }
            }
        }

        @Typeclass
        interface Eq<A> {
            fun same(left: A, right: A): Boolean
        }

        @Instance
        object StringShow : Show<String> {
            override fun show(value: String): String = value
        }

        @Derive(Show::class)
        data class User(val name: String)

        ${extraDeclarations.trimIndent()}

        fun main() {
            $mainBody
        }
        """.trimIndent()
}
