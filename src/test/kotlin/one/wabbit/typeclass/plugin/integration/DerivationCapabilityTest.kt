package one.wabbit.typeclass.plugin.integration

import kotlin.test.Test

class DerivationCapabilityTest : IntegrationTestSupport() {
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
}
