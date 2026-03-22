package one.wabbit.typeclass.plugin.integration

import org.junit.Ignore
import kotlin.test.Test

class StarProjectionResolutionTest : IntegrationTestSupport() {
    @Test fun reportsMissingInstanceForStarProjectedGoalsWithoutCrashing() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A>

            context(_: Show<A>)
            fun <A> render(): String = "ok"

            fun main() {
                println(render<List<*>>()) // E:TC_NO_CONTEXT_ARGUMENT no Show<List<*>> instance exists; this should fail cleanly
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedErrorContaining("no context argument", "show")),
        )
    }

    @Test
    @Ignore("Pending runtime/library support for Subtype proof surface")
    fun materializesSubtypeProofForOrdinaryAndStarProjectedSubtyping() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Subtype

            @JvmInline
            value class UserId(val value: Int)

            context(_: Subtype<A, B>)
            fun <A, B> provenSubtype(): String = "subtype"

            fun main() {
                println(provenSubtype<String, CharSequence>())
                println(provenSubtype<List<String>, Collection<String>>())
                println(provenSubtype<UserId, Any>())
                println(provenSubtype<List<String>, List<*>>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                subtype
                subtype
                subtype
                subtype
                """.trimIndent(),
        )
    }

    @Test
    @Ignore("Pending runtime/library support for IsTypeclassInstance proof surface")
    fun isTypeclassInstanceRecognizesAnnotatedTypeclassesNullaryTypeclassesAndStarProjectedApplications() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.IsTypeclassInstance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A>

            @Typeclass
            interface FeatureFlag

            @Typeclass
            interface Foo<A>

            context(_: IsTypeclassInstance<TC>)
            fun <TC> proof(): String = "typeclass"

            fun main() {
                println(proof<Show<Int>>())
                println(proof<FeatureFlag>())
                println(proof<Foo<*>>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                typeclass
                typeclass
                typeclass
                """.trimIndent(),
        )
    }
}
