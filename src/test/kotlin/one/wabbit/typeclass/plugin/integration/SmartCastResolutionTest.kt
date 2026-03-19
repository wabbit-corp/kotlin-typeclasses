package one.wabbit.typeclass.plugin.integration

import kotlin.test.Test

class SmartCastResolutionTest : IntegrationTestSupport() {
    @Test
    fun resolvesUsingSmartCastTypeInsideIsBranch() {
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
                override fun show(value: Int): String = "int:${'$'}value"
            }

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                val value: Any = 1
                if (value is Int) {
                    println(render(value))
                }
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "int:1",
        )
    }

    @Test
    fun resolvesUsingSmartCastTypeInsideWhenBranch() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            data class Foo(val value: Int)

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance
            object FooShow : Show<Foo> {
                override fun show(value: Foo): String = "foo:${'$'}{value.value}"
            }

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                val value: Any = Foo(2)
                when (value) {
                    is Foo -> println(render(value))
                    else -> println("miss")
                }
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "foo:2",
        )
    }

    @Test
    fun resolvesUsingSmartCastTypeInsideNonNullBranch() {
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
            object StringShow : Show<String> {
                override fun show(value: String): String = "string:${'$'}value"
            }

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                val value: String? = "hello"
                if (value != null) {
                    println(render(value))
                }
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "string:hello",
        )
    }
}
