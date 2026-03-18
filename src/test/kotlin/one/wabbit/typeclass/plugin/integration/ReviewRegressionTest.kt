package one.wabbit.typeclass.plugin.integration

import kotlin.test.Test

class ReviewRegressionTest : IntegrationTestSupport() {
    @Test
    fun deriveAnnotationsOnlySatisfyTheRequestedTypeclass() {
        val source =
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

            context(_: Eq<User>)
            fun requiresEq(): String = "eq"

            fun main() {
                println(requiresEq())
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("eq", "user"),
        )
    }

    @Test
    fun ambiguousImplicitContextsRemainVisibleToTheFrontend() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun label(): String
            }

            @Instance
            object IntShowOne : Show<Int> {
                override fun label(): String = "one"
            }

            @Instance
            object IntShowTwo : Show<Int> {
                override fun label(): String = "two"
            }

            context(_: Show<Int>)
            fun render(): String = "value"

            fun main() {
                println(render())
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("no context argument", "show"),
        )
    }

    @Test
    fun recursiveImplicitContextsRemainVisibleToTheFrontend() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Foo<A> {
                fun label(): String
            }

            @Typeclass
            interface Bar<A> {
                fun label(): String
            }

            @Instance
            context(_: Bar<A>)
            fun <A> fooFromBar(): Foo<A> =
                object : Foo<A> {
                    override fun label(): String = "foo"
                }

            @Instance
            context(_: Foo<A>)
            fun <A> barFromFoo(): Bar<A> =
                object : Bar<A> {
                    override fun label(): String = "bar"
                }

            context(_: Foo<Int>)
            fun use(): String = "ok"

            fun main() {
                println(use())
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("no context argument", "foo"),
        )
    }
}
