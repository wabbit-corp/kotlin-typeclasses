package one.wabbit.typeclass.plugin.integration

import kotlin.test.Test

class DirectRecursionValidationTest : IntegrationTestSupport() {
    @Test
    fun rejectsDirectSelfRecursiveWrapperReturningInstanceRulesAtDeclarationSite() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            data class Box<A>(val value: A)

            @Typeclass
            interface Show<A> {
                fun label(): String
            }

            @Typeclass
            interface WrappedShow<A> : Show<A>

            @Instance
            context(_: Show<Box<A>>)
            fun <A> recursiveWrappedBoxShow(): WrappedShow<Box<A>> = // ERROR expands to Show<Box<A>> => Show<Box<A>>
                object : WrappedShow<Box<A>> {
                    override fun label(): String = "recursive"
                }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("recursive"),
            expectedDiagnostics = listOf(expectedErrorContaining("recursive")),
        )
    }

    @Test
    fun allowsWrapperReturningInstanceRulesWhenExpandedProvidedTypeIsNotRecursive() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            data class Box<A>(val value: A)

            @Typeclass
            interface Show<A> {
                fun label(): String
            }

            @Typeclass
            interface WrappedShow<A> : Show<A>

            @Instance
            object IntShow : Show<Int> {
                override fun label(): String = "int"
            }

            @Instance
            context(_: Show<A>)
            fun <A> wrappedBoxShow(): WrappedShow<Box<A>> =
                object : WrappedShow<Box<A>> {
                    override fun label(): String = "box"
                }

            context(show: Show<A>)
            fun <A> label(): String = show.label()

            fun main() {
                println(label<Box<Int>>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "box",
        )
    }
}
