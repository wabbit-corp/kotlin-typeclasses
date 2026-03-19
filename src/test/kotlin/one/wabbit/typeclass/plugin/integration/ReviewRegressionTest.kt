package one.wabbit.typeclass.plugin.integration

import org.junit.Ignore
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

    @Test
    fun illegalMemberInstanceFunctionsDoNotCreateSpuriousCallSiteAmbiguity() {
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
                override fun show(value: Int): String = "good:${'$'}value"
            }

            class BadScope {
                @Instance
                fun badShow(): Show<Int> =
                    object : Show<Int> {
                        override fun show(value: Int): String = "bad:${'$'}value"
                    }
            }

            context(show: Show<Int>)
            fun render(): String = show.show(1)

            fun main() {
                println(render())
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("companion"),
            unexpectedMessages = listOf("ambiguous"),
        )
    }

    @Test
    fun illegalNestedInstanceObjectsDoNotCreateSpuriousCallSiteAmbiguity() {
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
                override fun show(value: Int): String = "good:${'$'}value"
            }

            class BadScope {
                @Instance
                object BadShow : Show<Int> {
                    override fun show(value: Int): String = "bad:${'$'}value"
                }
            }

            context(show: Show<Int>)
            fun render(): String = show.show(1)

            fun main() {
                println(render())
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("companion"),
            unexpectedMessages = listOf("ambiguous"),
        )
    }

    @Test
    fun genericSealedSubclassesAreRejectedForNonGenericDerivedRoots() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
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
                            override fun show(value: Any?): String = metadata.typeName
                        }

                    override fun deriveSum(metadata: SumTypeclassMetadata): Any =
                        object : Show<Any?> {
                            override fun show(value: Any?): String = metadata.typeName
                        }
                }
            }

            @Derive(Show::class)
            sealed interface Expr

            data class Lit<T>(val value: T) : Expr

            fun main() {
                println("unreachable")
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("cannot derive", "lit"),
        )
    }

    @Test
    fun impossibleBuiltinPrerequisitesDoNotCreateSpuriousAmbiguity() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.NotSame
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            @Typeclass
            interface Show<A> {
                fun label(): String
            }

            @Instance
            object IntShow : Show<Int> {
                override fun label(): String = "int"
            }

            @Instance
            context(_: NotSame<A, A>)
            fun <A> impossibleShow(): Show<A> =
                object : Show<A> {
                    override fun label(): String = "impossible"
                }

            context(_: Show<Int>)
            fun render(): String = summon<Show<Int>>().label()

            fun main() {
                println(render())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "int",
        )
    }

    @Test
    fun impossibleSubtypePrerequisitesDoNotCreateSpuriousAmbiguity() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Subtype
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            class Box<T>

            @Typeclass
            interface Show<A> {
                fun label(): String
            }

            @Instance
            object IntShow : Show<Int> {
                override fun label(): String = "int"
            }

            @Instance
            context(_: Subtype<Box<String>, Box<Int>>)
            fun impossibleShow(): Show<Int> =
                object : Show<Int> {
                    override fun label(): String = "impossible"
                }

            context(_: Show<Int>)
            fun render(): String = summon<Show<Int>>().label()

            fun main() {
                println(render())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "int",
        )
    }

    @Test
    fun impossibleStrictSubtypePrerequisitesDoNotCreateSpuriousAmbiguity() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.StrictSubtype
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            class Marker

            @Typeclass
            interface Show<A> {
                fun label(): String
            }

            @Instance
            object IntShow : Show<Int> {
                override fun label(): String = "int"
            }

            @Instance
            context(_: StrictSubtype<Marker, Marker>)
            fun impossibleShow(): Show<Int> =
                object : Show<Int> {
                    override fun label(): String = "impossible"
                }

            context(_: Show<Int>)
            fun render(): String = summon<Show<Int>>().label()

            fun main() {
                println(render())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "int",
        )
    }

    @Test
    fun resolvesDependencyModuleInstancesInIrAsWellAsFir() {
        val dependency =
            HarnessDependency(
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
                                companion object {
                                    @Instance
                                    val show =
                                        object : Show<Box> {
                                            override fun show(value: Box): String = "dep:${'$'}{value.value}"
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
            expectedStdout = "dep:1",
            dependencies = listOf(dependency),
        )
    }

    @Ignore("Pending explicit context-argument preservation coverage")
    @Test
    fun explicitWrongTypeclassArgumentsAreNotReboundToDifferentGoals() {
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
            object IntShow : Show<Int> {
                override fun label(): String = "int"
            }

            @Instance
            object NullableIntShow : Show<Int?> {
                override fun label(): String = "nullable"
            }

            context(show: Show<A>)
            fun <A> render(): String = show.label()

            fun main() {
                context(NullableIntShow) {
                    println(render<Int>()) // ERROR explicit type arguments must not let the wrong explicit context be rebound
                }
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("show", "int"),
        )
    }

    @Ignore("Pending builtin admissibility coverage")
    @Test
    fun inapplicableBuiltinCandidatesDoNotCreateFalseAmbiguity() {
        val source =
            """
            package demo

            import kotlin.reflect.KClass
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            @Typeclass
            interface Show<A> {
                fun label(): String
            }

            @Instance
            object IntShow : Show<Int> {
                override fun label(): String = "instance"
            }

            @Instance
            context(_: KClass<A>)
            fun <A> builtinBackedShow(): Show<A> =
                object : Show<A> {
                    override fun label(): String = "builtin"
                }

            context(_: Show<Int>)
            fun render(): String = summon<Show<Int>>().label()

            fun main() {
                println(render())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "instance",
            pluginOptions = listOf("builtinKClassTypeclass=enabled"),
        )
    }

    @Ignore("Pending derivation admissibility work")
    @Test
    fun derivesOnlyAdmissibleSumCasesForRequestedTypeclasses() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.SumTypeclassMetadata
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.TypeclassDeriver

            @Typeclass
            interface Codec<A> {
                fun encode(value: A): String
                fun decode(value: String): A

                companion object : TypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                        error("placeholder")

                    override fun deriveSum(metadata: SumTypeclassMetadata): Any =
                        error("placeholder")
                }
            }

            @Derive(Codec::class)
            sealed interface Expr<A>

            data class Lit(val value: Int) : Expr<Int>

            data class Name(val value: String) : Expr<String>
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("derive", "expr"),
        )
    }

    @Ignore("Pending type-argument and explicit-context preservation coverage")
    @Test
    fun preservesExplicitContextArgumentsAndTypeArgumentsThroughRewrites() {
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

            @Instance
            object NullableIntShow : Show<Int?> {
                override fun show(value: Int?): String = "nullable:${'$'}value"
            }

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                context(NullableIntShow) {
                    println(render<Int?>(1))
                }
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "nullable:1",
        )
    }
}
