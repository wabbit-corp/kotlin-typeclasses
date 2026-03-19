package one.wabbit.typeclass.plugin.integration

import org.junit.Ignore
import kotlin.test.Test

@Ignore("NEW: review before enabling")
class ExistentialTest : IntegrationTestSupport() {
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
                println(render<List<*>>()) // ERROR no Show<List<*>> instance exists; this should fail cleanly
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf(),
            expectedDiagnostics = listOf(expectedErrorContaining("no context argument", "show")),
        )
    }

    @Test fun starProjectedContextsCanBeSatisfiedByConcreteInstances() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Foo<A> {
                fun label(): String
            }

            @Instance
            val wozzle: Foo<Int> =
                object : Foo<Int> {
                    override fun label(): String = "int"
                }

            context(foo: Foo<*>)
            fun baz(): String = foo.label()

            fun main() {
                println(baz())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "int",
        )
    }

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun starProjectedSummonsCanReuseConcreteInstances() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            @Typeclass
            interface Foo<A> {
                fun label(): String
            }

            @Instance
            val wozzle: Foo<Int> =
                object : Foo<Int> {
                    override fun label(): String = "int"
                }

            context(_: Foo<*>)
            fun baz(): String = summon<Foo<*>>().label()

            fun main() {
                println(baz())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "int",
        )
    }

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun oneConcreteInstanceCanSatisfySpecificAndStarProjectedNeedsTogether() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Foo<A>

            @Instance
            val wozzle: Foo<Int> =
                object : Foo<Int> {}

            context(_: Foo<Int>, _: Foo<*>)
            fun ok(): String = "ok"

            fun main() {
                println(ok())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "ok",
        )
    }

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun reportsAmbiguityForStarProjectedContextsWithMultipleConcreteCandidates() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Foo<A> {
                fun label(): String
            }

            @Instance
            val intFoo: Foo<Int> =
                object : Foo<Int> {
                    override fun label(): String = "int"
                }

            @Instance
            val stringFoo: Foo<String> =
                object : Foo<String> {
                    override fun label(): String = "string"
                }

            context(foo: Foo<*>)
            fun baz(): String = foo.label()

            fun main() {
                println(baz()) // ERROR both Foo<Int> and Foo<String> satisfy Foo<*>
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("ambiguous", "foo"),
            expectedDiagnostics = listOf(expectedAmbiguousOrNoContext("foo")),
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
