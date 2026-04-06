// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.typeclass.plugin.integration

import org.junit.Ignore
import kotlin.test.Test

@Ignore("REVIEWED: don't add, future features - some require magical non-existent syntax")
class FutureFeatureTest : IntegrationTestSupport() {
    // REVIEWED : won't add, too dangerous
    @Test fun capturesLexicallyScopedLocalInstanceDeclarations() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                @Instance
                val localShow: Show<Int> =
                    object : Show<Int> {
                        override fun show(value: Int): String = "local:${'$'}value"
                    }

                val renderLater = { render(1) }
                println(renderLater())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "local:1",
        )
    }

    // REVIEWED : desirable, could use `val foo by lazy { ... }`? Or not...
    @Test fun supportsLazyRecursiveLocalInstances() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            data class Box(val next: Box?)

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                // WONT COMPILE, NEEDS NEW SYNTAX
                @Instance(lazy = true)
                val localBoxShow: Show<Box> =
                    object : Show<Box> {
                        override fun show(value: Box): String =
                            if (value.next == null) "Box(end)" else "Box(" + localBoxShow.show(value.next) + ")"
                    }

                println(render(Box(Box(null))))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "Box(Box(end))",
        )
    }

    // REVIEWED : need to understand the rationale
    @Test fun supportsFunctionalDependencyStyleImprovement() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            // WONT COMPILE, NEEDS NEW SYNTAX
            @FunctionalDependency("A -> B")
            interface Convert<A, B>

            @Instance
            object IntToString : Convert<Int, String>

            context(_: Convert<A, B>)
            fun <A, B> onlyInput(value: A): String = "ok:${'$'}value"

            fun main() {
                println(onlyInput(1))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "ok:1",
        )
    }

    // REVIEWED : won't add
    @Test fun normalizesAssociatedTypesDuringInstanceSearch() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun label(): String
            }

            @Typeclass
            interface ElementType<C> {
                // WONT COMPILE, NEEDS NEW SYNTAX
                associatedtype Element
            }

            @Instance
            object StringShow : Show<String> {
                override fun label(): String = "string"
            }

            @Instance
            object StringsHaveStringElements : ElementType<List<String>> {
                // WONT COMPILE, NEEDS NEW SYNTAX
                typealias Element = String
            }

            // WONT COMPILE, NEEDS NEW SYNTAX
            context(_: ElementType<C>, _: Show<ElementType<C>.Element>)
            fun <C> label(): String = "ok"

            fun main() {
                println(label<List<String>>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "ok",
        )
    }

    // REVIEWED : won't add
    @Test fun supportsQuantifiedConstraintLikeEvidence() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Typeclass

            data class Box<A>(val value: A)

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            // WONT COMPILE, NEEDS NEW SYNTAX
            context(forall<A> { Show<A> implies Show<Box<A>> })
            fun lifted(): String = "lifted"

            fun main() {
                println(lifted())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "lifted",
        )
    }

    @Test fun supportsHigherKindedTypeclasses() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            // WONT COMPILE, NEEDS NEW SYNTAX
            @Typeclass
            interface Functor<F<_>> {
                fun <A, B> map(value: F<A>, f: (A) -> B): F<B>
            }

            @Instance
            object ListFunctor : Functor<List> {
                override fun <A, B> map(value: List<A>, f: (A) -> B): List<B> = value.map(f)
            }

            context(functor: Functor<F>)
            // WONT COMPILE, NEEDS NEW SYNTAX
            fun <F<_>, A, B> liftMap(value: F<A>, f: (A) -> B): F<B> = functor.map(value, f)

            fun main() {
                println(liftMap<List, Int, String>(listOf(1, 2)) { "${'$'}it!" })
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "[1!, 2!]",
        )
    }

    @Test fun supportsTypeLevelNaturalsAndKnownNatStyleEvidence() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Typeclass

            // WONT COMPILE, NEEDS NEW SYNTAX
            @Typeclass
            interface KnownNat<N>

            // WONT COMPILE, NEEDS NEW SYNTAX
            fun <N> natValue(): Int = intrinsicNatValue<N>()

            // WONT COMPILE, NEEDS NEW SYNTAX
            data class Vec<N, A>(val values: List<A>)

            context(_: KnownNat<N>)
            fun <N> size(): Int = natValue<N>()

            fun main() {
                println(size<3>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "3",
        )
    }

    // REVIEWED : maybe
    @Test fun supportsDecidableEqualityProofSearch() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Dec
            import one.wabbit.typeclass.Same

            // WONT COMPILE, NEEDS NEW SYNTAX
            fun <A, B> decideSame(): Dec<Same<A, B>> = auto

            fun main() {
                println(decideSame<Int, Int>().isYes)
                println(decideSame<Int, String>().isNo)
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                true
                true
                """.trimIndent(),
        )
    }

    // REVIEWED : would be lovely
    @Test fun supportsRowPolymorphicFieldEvidence() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Typeclass

            data class User(val id: Int, val name: String)

            // WONT COMPILE, NEEDS NEW SYNTAX
            @Typeclass
            interface HasField<R, Name, A> {
                fun get(receiver: R): A
            }

            // WONT COMPILE, NEEDS NEW SYNTAX
            context(_: HasField<R, "name", String>)
            fun <R> renderName(value: R): String = field<"name">(value)

            fun main() {
                println(renderName(User(1, "Ada")))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "Ada",
        )
    }

    // REVIEWED : would be lovely
    @Test fun supportsTransportAcrossEqualityProofs() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Same

            data class Box<A>(val value: A)

            context(eq: Same<A, B>)
            // WONT COMPILE, NEEDS NEW SYNTAX
            fun <A, B> cast(box: Box<A>): Box<B> = rewrite(eq) { box }

            fun main() {
                println(cast<Int, Int>(Box(1)).value)
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "1",
        )
    }
}
