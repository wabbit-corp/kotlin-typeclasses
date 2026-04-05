package one.wabbit.typeclass.plugin.integration.blocked

import org.junit.Ignore
import one.wabbit.typeclass.plugin.integration.IntegrationTestSupport
import kotlin.test.Test

class ContextualPropertyTest : IntegrationTestSupport() {
    @Ignore("Blocked: Kotlin 2.3.10 FIR plugin API has no property-access refinement hook for contextual getter resolution")
    @Test
    fun resolvesContextualPropertyGetter() {
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
            val intLabel: String
                get() = show.show(1)

            fun main() {
                println(intLabel)
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "int:1",
        )
    }

    @Ignore("Blocked: Kotlin 2.3.10 FIR plugin API has no property-access refinement hook for contextual getter resolution")
    @Test
    fun resolvesContextualExtensionPropertyGetter() {
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
            val Int.rendered: String
                get() = show.show(this)

            fun main() {
                println(1.rendered)
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "int:1",
        )
    }

    @Ignore("Blocked: Kotlin 2.3.10 FIR plugin API has no property-access refinement hook for contextual property-reference adaptation")
    @Test
    fun adaptsPropertyReferencesToContextualProperties() {
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
            val intLabel: String
                get() = show.show(1)

            fun main() {
                val getter = ::intLabel
                println(getter.get())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "int:1",
        )
    }
}
