package one.wabbit.typeclass.plugin.integration

import kotlin.test.Test

class DerivationCapabilityTest : IntegrationTestSupport() {
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
        )
    }
}
