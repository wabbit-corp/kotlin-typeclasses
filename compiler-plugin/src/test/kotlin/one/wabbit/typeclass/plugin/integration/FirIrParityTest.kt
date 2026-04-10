// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.typeclass.plugin.integration

import kotlin.test.Test

/**
 * Locks high-risk semantic seams where FIR and IR have historically drifted.
 *
 * Positive cases compile and run, proving both phases agree on the accepted behavior.
 * Negative cases require an explicit FIR diagnostic so backend-only failures cannot regress back in.
 */
class FirIrParityTest : IntegrationTestSupport() {
    @Test
    fun namedTypeclassCompanionDeriversRemainVisibleAcrossFirAndIr() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.ProductTypeclassDeriver
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String

                companion object NamedFactory : ProductTypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                        object : Show<Any?> {
                            override fun show(value: Any?): String = metadata.typeName
                        }
                }
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(value: Int): String = "int:${'$'}value"
            }

            @Derive(Show::class)
            data class Box(val value: Int)

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(Box(1)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "demo.Box",
        )
    }

    @Test
    fun namedDependencyCompanionInstancesRemainVisibleAcrossFirAndIr() {
        val dependency =
            HarnessDependency(
                name = "dep-fir-ir-parity-named-companion",
                sources =
                    mapOf(
                        "dep/Api.kt" to
                            """
                            package dep

                            import one.wabbit.typeclass.Instance
                            import one.wabbit.typeclass.Typeclass

                            @Typeclass
                            interface Show<A> {
                                fun show(value: A): String
                            }

                            data class Box(val value: Int) {
                                companion object NamedEvidence {
                                    @Instance
                                    val show =
                                        object : Show<Box> {
                                            override fun show(value: Box): String = "named-box:${'$'}{value.value}"
                                        }
                                }
                            }

                            context(show: Show<Box>)
                            fun render(value: Box): String = show.show(value)
                            """.trimIndent(),
                    ),
            )
        val source =
            """
            package demo

            import dep.Box
            import dep.render

            fun main() {
                println(render(Box(1)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "named-box:1",
            dependencies = listOf(dependency),
        )
    }

    @Test
    fun deriveViaValidationRejectsUntransportableForwardedSurfaceAcrossFirAndIr() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Fancy<A> {
                fun render(value: A): String

                context(dep: A)
                fun helper(): String = dep.toString()
            }

            @JvmInline
            value class Foo(val value: Int)

            @Instance
            object FooFancy : Fancy<Foo> {
                override fun render(value: Foo): String = "foo:${'$'}{value.value}"
            }

            @JvmInline
            @DeriveVia(Fancy::class, Foo::class) // E:TC_CANNOT_DERIVE concrete forwarded context members must be transportable
            value class UserId(val value: Int)
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedCannotDerive(
                        "context parameters",
                        "transported type parameter",
                        phase = null,
                    ),
                ),
            unexpectedMessages =
                listOf(
                    "no context argument",
                    "required instance",
                    "overload resolution ambiguity",
                ),
        )
    }

    @Test
    fun unsupportedInheritedDeriveViaSurfaceIsRejectedConsistently() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface BaseFancy<A> {
                fun <T : A> narrow(value: T): A
            }

            @Typeclass
            interface Fancy<A> : BaseFancy<A>

            @JvmInline
            value class Foo(val value: Int)

            @Instance
            object FooFancy : Fancy<Foo> {
                override fun <T : Foo> narrow(value: T): Foo = value
            }

            @JvmInline
            @DeriveVia(Fancy::class, Foo::class) // E:TC_CANNOT_DERIVE inherited method bounds mentioning the transported type parameter are unsupported
            value class UserId(val value: Int)
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedCannotDerive(
                        "type-parameter bounds",
                        "transported type parameter",
                        phase = null,
                    ),
                ),
            unexpectedMessages =
                listOf(
                    "no context argument",
                    "required instance",
                    "overload resolution ambiguity",
                ),
        )
    }

    @Test
    fun builtinFeasibilityRulesStayAlignedAcrossFirAndIrForProvableGoals() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.NotNullable
            import one.wabbit.typeclass.Subtype

            context(_: NotNullable<T>)
            fun <T : Any> notNull(): String = "not-nullable"

            context(_: Subtype<B, A>)
            fun <A, B : A> subtype(): String = "subtype"

            open class Animal
            class Dog : Animal()

            fun main() {
                println(notNull<String>())
                println(subtype<Animal, Dog>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                not-nullable
                subtype
                """.trimIndent(),
        )
    }

    @Test
    fun speculativeBuiltinGoalsFailInFirBeforeIrCanDrift() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Subtype

            context(_: Subtype<T, Number>)
            fun <T> render(value: T): String = "context"

            fun <T> choose(value: T): String = render(value) // E:TC_NO_CONTEXT_ARGUMENT speculative subtype goal should fail in FIR

            fun main() {
                println(choose("not-a-number"))
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedNoContextArgument("subtype", phase = DiagnosticPhase.FIR),
                ),
            unexpectedMessages = listOf("invalid builtin evidence"),
        )
    }

    @Test
    fun structuralProductTransportRemainsAlignedAcrossFirAndIr() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveEquiv
            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            data class A(val a: Int, val b: String)

            @Instance
            object AShow : Show<A> {
                override fun show(value: A): String = "${'$'}{value.a}|${'$'}{value.b}"
            }

            @DeriveEquiv(A::class)
            @DeriveVia(Show::class, A::class)
            data class B(val x: String, val y: Int)

            context(show: Show<T>)
            fun <T> render(value: T): String = show.show(value)

            fun main() {
                println(render(B("x", 1)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "1|x",
        )
    }

    @Test
    fun structuralSumTransportRemainsAlignedAcrossFirAndIr() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveEquiv
            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            sealed interface A {
                data object Empty : A
                data class NonEmpty(val a: Int, val b: String) : A
            }

            @Instance
            object AShow : Show<A> {
                override fun show(value: A): String =
                    when (value) {
                        A.Empty -> "empty"
                        is A.NonEmpty -> "${'$'}{value.a}|${'$'}{value.b}"
                    }
            }

            @DeriveEquiv(A::class)
            @DeriveVia(Show::class, A::class)
            sealed interface B {
                data object Wuzzle : B
                data class Wozzle(val a: Int, val b: String) : B
            }

            context(show: Show<T>)
            fun <T> render(value: T): String = show.show(value)

            fun main() {
                val empty: B = B.Wuzzle
                val nonEmpty: B = B.Wozzle(1, "x")
                println(render(empty))
                println(render(nonEmpty))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                empty
                1|x
                """.trimIndent(),
        )
    }

    @Test
    fun ambiguousStructuralProductTransportIsRejectedConsistently() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveEquiv
            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            data class RightShape(val left: String, val count: Int, val right: String)

            @Instance
            object RightShapeShow : Show<RightShape> {
                override fun show(value: RightShape): String = "${'$'}{value.left}|${'$'}{value.count}|${'$'}{value.right}"
            }

            @DeriveEquiv(RightShape::class) // E:TC_CANNOT_DERIVE repeated field types keep the product transport ambiguous
            @DeriveVia(Show::class, RightShape::class) // E:TC_CANNOT_DERIVE repeated field types keep the transport path ambiguous
            data class LeftShape(val first: String, val amount: Int, val second: String)
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedCannotDerive("equiv", "leftshape", "rightshape"),
                ),
            unexpectedMessages =
                listOf(
                    "no context argument",
                    "required instance",
                    "overload resolution ambiguity",
                ),
        )
    }
}
