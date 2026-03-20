package one.wabbit.typeclass.plugin.integration.derivation

import one.wabbit.typeclass.plugin.integration.IntegrationTestSupport
import one.wabbit.typeclass.plugin.integration.DiagnosticPhase
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
                mainBody = """println(requiresEq())""",
            )

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("eq", "user"),
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
                mainBody = """println(render<User?>(null))""",
            )

        assertDoesNotCompile(
            source = source,
            expectedMessages = emptyList(),
            expectedDiagnostics = listOf(expectedNoContextArgument("show", phase = DiagnosticPhase.IR)),
        )
    }

    @Test
    fun nonUnaryTypeclassesCannotBeDerived() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
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
            expectedMessages = emptyList(),
            expectedDiagnostics = listOf(expectedCannotDerive("exactly one type parameter")),
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
            expectedMessages = listOf("deriveProduct", "Show", "Eq"),
            expectedDiagnostics = listOf(expectedCannotDerive("deriveProduct", "Show", "Eq", phase = null)),
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
            expectedMessages = emptyList(),
            expectedDiagnostics = listOf(expectedCannotDerive("deriveProduct", "Show")),
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
            expectedMessages = listOf("deriveSum", "Show", "Eq"),
            expectedDiagnostics = listOf(expectedCannotDerive("deriveSum", "Show", "Eq", phase = null)),
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
            expectedMessages = emptyList(),
            expectedDiagnostics = listOf(expectedCannotDerive("deriveSum", "Show")),
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
            expectedMessages = listOf("deriveEnum", "Show", "Eq"),
            expectedDiagnostics = listOf(expectedCannotDerive("deriveEnum", "Show", "Eq", phase = null)),
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
            expectedMessages = emptyList(),
            expectedDiagnostics = listOf(expectedCannotDerive("deriveEnum", "Show")),
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
            expectedMessages = listOf("deriveEnum", "enum classes"),
            expectedDiagnostics = listOf(expectedCannotDerive("deriveEnum", "enum classes", phase = null)),
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
            expectedMessages = listOf("ProductTypeclassDeriver", "sealed sums"),
            expectedDiagnostics = listOf(expectedCannotDerive("ProductTypeclassDeriver", "sealed sums", phase = null)),
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
            expectedMessages = listOf("sealed or final classes and objects"),
            expectedDiagnostics = listOf(expectedCannotDerive("sealed or final classes and objects", phase = null)),
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
            expectedMessages = listOf("constructor parameters", "stored properties"),
            expectedDiagnostics =
                listOf(expectedCannotDerive("constructor parameters", "stored properties", phase = null)),
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
            expectedMessages = listOf("primary constructor"),
            expectedDiagnostics = listOf(expectedCannotDerive("primary constructor", phase = null)),
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
