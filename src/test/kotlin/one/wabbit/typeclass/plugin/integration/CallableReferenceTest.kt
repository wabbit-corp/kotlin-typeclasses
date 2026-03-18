package one.wabbit.typeclass.plugin.integration

import org.junit.Ignore
import kotlin.test.Test

class CallableReferenceTest : IntegrationTestSupport() {
    @Ignore("NEW: contextual callable-reference adaptation is not implemented yet")
    @Test
    fun adaptsCallableReferencesToContextualTopLevelFunctions() {
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

            context(show: Show<Int>)
            fun renderInt(value: Int): String = show.show(value)

            fun consume(f: (Int) -> String): String = f(1)

            fun main() {
                println(consume(::renderInt))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "int:1",
        )
    }

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun adaptsBoundCallableReferencesToContextualMemberFunctions() {
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

            class Items {
                context(show: Show<Int>)
                fun renderInt(value: Int): String = show.show(value)
            }

            fun consume(f: (Int) -> String): String = f(1)

            fun main() {
                println(consume(Items()::renderInt))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "int:1",
        )
    }

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun preservesExtensionFunctionReferenceInterchangeability() {
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

            context(show: Show<Int>)
            fun Int.rendered(suffix: String): String = show.show(this) + suffix

            fun consumeAsExtension(f: Int.(String) -> String): String = 1.f("!")

            fun consumeAsPlain(f: (Int, String) -> String): String = f(1, "?")

            fun main() {
                println(consumeAsExtension(Int::rendered))
                println(consumeAsPlain(Int::rendered))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                int:1!
                int:1?
                """.trimIndent(),
        )
    }
}
