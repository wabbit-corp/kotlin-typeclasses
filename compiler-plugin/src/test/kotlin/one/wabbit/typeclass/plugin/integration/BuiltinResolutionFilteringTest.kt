// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.typeclass.plugin.integration

import kotlin.test.Test

class BuiltinResolutionFilteringTest : IntegrationTestSupport() {
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
    fun speculativeSubtypeGoalFailsInFirInsteadOfIr() {
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
            expectedDiagnostics = listOf(expectedNoContextArgument("subtype")),
            unexpectedMessages = listOf("invalid builtin evidence"),
        )
    }

    @Test
    fun provableSubtypeGoalFromBoundsStillParticipatesInRefinement() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Subtype

            context(_: Subtype<B, A>)
            fun <A, B : A> render(): String = "context"

            open class Animal
            class Dog : Animal()

            fun <A, B : A> choose(): String = render<A, B>()

            fun main() {
                println(choose<Animal, Dog>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "context",
        )
    }

    @Test
    fun speculativeSubtypePrerequisiteFailsInFirInsteadOfIr() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Subtype
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun label(): String
            }

            @Instance
            context(_: Subtype<T, Number>)
            fun <T> speculativeShow(): Show<T> =
                object : Show<T> {
                    override fun label(): String = "context"
                }

            context(show: Show<T>)
            fun <T> render(): String = show.label()

            fun <T> choose(): String = render<T>() // E:TC_NO_CONTEXT_ARGUMENT speculative prerequisite should fail in FIR

            fun main() {
                println(choose<String>())
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedNoContextArgument("show")),
            unexpectedMessages = listOf("invalid builtin evidence"),
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
    fun speculativeStrictSubtypeGoalFailsInFirInsteadOfIr() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.StrictSubtype

            context(_: StrictSubtype<T, Number>)
            fun <T> render(value: T): String = "context"

            fun <T> choose(value: T): String = render(value) // E:TC_NO_CONTEXT_ARGUMENT speculative strict subtype goal should fail in FIR

            fun main() {
                println(choose("not-a-number"))
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedNoContextArgument("strictsubtype")),
            unexpectedMessages = listOf("invalid builtin evidence"),
        )
    }

    @Test
    fun impossibleIsTypeclassInstancePrerequisiteDoesNotCreateFakeAmbiguity() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.IsTypeclassInstance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(value: Int): String = "exact:${'$'}value"
            }

            @Instance
            context(_: IsTypeclassInstance<List<Int>>)
            fun impossibleShow(): Show<Int> =
                object : Show<Int> {
                    override fun show(value: Int): String = "impossible:${'$'}value"
                }

            context(show: Show<Int>)
            fun render(): String = show.show(1)

            fun main() {
                println(render())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "exact:1",
        )
    }

    @Test
    fun speculativeIsTypeclassInstanceGoalFailsInFirInsteadOfIr() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.IsTypeclassInstance

            context(_: IsTypeclassInstance<TC>)
            fun <TC> render(value: Int): String = "context"

            fun <TC> choose(): String = render<TC>(1) // E:TC_NO_CONTEXT_ARGUMENT speculative is-typeclass-instance goal should fail in FIR

            fun main() {
                println(choose<List<Int>>())
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedNoContextArgument("istypeclassinstance")),
            unexpectedMessages = listOf("invalid builtin evidence"),
        )
    }

    @Test
    fun validIsTypeclassInstancePrerequisiteStillParticipatesInOrdinaryRuleSearch() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.IsTypeclassInstance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance
            context(_: IsTypeclassInstance<Show<Int>>)
            fun derivedShow(): Show<Int> =
                object : Show<Int> {
                    override fun show(value: Int): String = "derived:${'$'}value"
                }

            context(show: Show<Int>)
            fun render(): String = show.show(2)

            fun main() {
                println(render())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "derived:2",
        )
    }

    @Test
    fun impossibleBuiltinKSerializerPrerequisiteDoesNotCreateFakeAmbiguity() {
        val source =
            """
            package demo

            import kotlinx.serialization.KSerializer
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            class NotSerializable(val value: Int)

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(value: Int): String = "exact:${'$'}value"
            }

            @Instance
            context(_: KSerializer<NotSerializable>)
            fun impossibleShow(): Show<Int> =
                object : Show<Int> {
                    override fun show(value: Int): String = "impossible:${'$'}value"
                }

            context(show: Show<Int>)
            fun render(): String = show.show(4)

            fun main() {
                println(render())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "exact:4",
            requiredPlugins = listOf(CompilerHarnessPlugin.Serialization),
            pluginOptions = listOf("builtinKSerializerTypeclass=enabled"),
        )
    }

    @Test
    fun nonMaterializableKnownTypeDoesNotHidePlainOverload() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.KnownType

            context(_: KnownType<T>)
            fun <T> choose(): String = "builtin"

            fun choose(label: String = "plain"): String = label

            fun main() {
                println(choose())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "plain",
        )
    }

    @Test
    fun nonMaterializableTypeIdDoesNotHidePlainOverload() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.TypeId

            context(_: TypeId<T>)
            fun <T> choose(): String = "builtin"

            fun choose(label: String = "plain"): String = label

            fun main() {
                println(choose())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "plain",
        )
    }

    @Test
    fun nonMaterializableBuiltinKClassDoesNotHidePlainOverload() {
        val source =
            """
            package demo

            import kotlin.reflect.KClass

            context(_: KClass<T>)
            fun <T : Any> choose(): String = "builtin"

            fun choose(label: String = "plain"): String = label

            fun main() {
                println(choose())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "plain",
            pluginOptions = listOf("builtinKClassTypeclass=enabled"),
        )
    }

    @Test
    fun nonMaterializableBuiltinKSerializerDoesNotHidePlainOverload() {
        val source =
            """
            package demo

            import kotlinx.serialization.KSerializer

            context(_: KSerializer<T>)
            fun <T> choose(): String = "builtin"

            fun choose(label: String = "plain"): String = label

            fun main() {
                println(choose())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "plain",
            requiredPlugins = listOf(CompilerHarnessPlugin.Serialization),
            pluginOptions = listOf("builtinKSerializerTypeclass=enabled"),
        )
    }

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
            object NullableIntListShow : Show<List<Int?>> {
                override fun label(): String = "instance"
            }

            @Instance
            context(_: KClass<A>)
            fun <A : Any> builtinBackedShow(): Show<List<A>> =
                object : Show<List<A>> {
                    override fun label(): String = "builtin"
                }

            context(_: Show<List<Int?>>)
            fun render(): String = summon<Show<List<Int?>>>().label()

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
}
