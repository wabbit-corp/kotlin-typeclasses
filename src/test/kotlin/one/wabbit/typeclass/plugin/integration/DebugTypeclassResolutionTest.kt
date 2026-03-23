package one.wabbit.typeclass.plugin.integration

import org.jetbrains.kotlin.cli.common.ExitCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DebugTypeclassResolutionTest : IntegrationTestSupport() {
    private fun String.countOccurrences(fragment: String): Int = split(fragment).size - 1

    @Test
    fun deriveViaFailureTracingUsesDerivationRoots() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DebugTypeclassResolution
            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.TypeclassTraceMode

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            data class Wire(val raw: String)

            @DebugTypeclassResolution(mode = TypeclassTraceMode.FAILURES)
            @DeriveVia(Show::class, Wire::class) // E:TC_CANNOT_DERIVE
            data class UserId(val value: Int)
            """.trimIndent()

        val result = compileSourceResult(source)
        assertEquals(ExitCode.COMPILATION_ERROR, result.exitCode, result.stdout)
        assertOutputContains(
            result.stdout,
            "[TC_CANNOT_DERIVE]",
            "[TC_TRACE] Typeclass derivation trace for demo.Show<demo.UserId>",
            "[TC_TRACE] root kind: derivation",
            "[TC_TRACE] effective mode: FAILURES",
            "reason:",
        )
    }

    @Test
    fun globalAllModeEmitsSuccessTraceWithoutLocalAnnotation() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
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

            fun traced(): String = summon<Show<Int>>().label()

            fun main() {
                println(traced())
            }
            """.trimIndent()

        val result = compileSourceResult(source, pluginOptions = listOf("typeclassTraceMode=all"))
        assertEquals(ExitCode.OK, result.exitCode, result.stdout)
        assertOutputContains(
            result.stdout,
            "[TC_TRACE] Typeclass resolution trace for demo.Show<kotlin.Int>",
            "[TC_TRACE] effective mode: ALL",
            "[TC_TRACE] traced scope: global compiler option",
            "[TC_TRACE] result: success",
        )
        assertEquals("int", runCompiledMain(result.artifacts, "demo.SampleKt"))
    }

    @Test
    fun failureModeWithAlternativesShowsRejectedCandidates() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            @Typeclass
            interface Show<A> {
                fun label(): String
            }

            @Instance
            object AnyShow : Show<Any> {
                override fun label(): String = "any"
            }

            context(first: Show<Int>, second: Show<Int>)
            fun ambiguous(): String = summon<Show<Int>>().label() // E:TC_AMBIGUOUS_INSTANCE

            object FirstIntShow : Show<Int> {
                override fun label(): String = "first"
            }

            object SecondIntShow : Show<Int> {
                override fun label(): String = "second"
            }

            fun main() {
                with(FirstIntShow) {
                    with(SecondIntShow) {
                        println(ambiguous())
                    }
                }
            }
            """.trimIndent()

        val result =
            compileSourceResult(
                source,
                pluginOptions = listOf("typeclassTraceMode=failures-and-alternatives"),
            )
        assertEquals(ExitCode.COMPILATION_ERROR, result.exitCode, result.stdout)
        assertOutputContains(
            result.stdout,
            "[TC_TRACE] Typeclass resolution trace for demo.Show<kotlin.Int>",
            "[TC_TRACE] effective mode: FAILURES_AND_ALTERNATIVES",
            "demo.AnyShow",
            "explained but not searched; not applicable due to head mismatch",
            "[TC_TRACE] result: ambiguity",
        )
    }

    @Test
    fun ambiguityTracingOrdersLocalContextsByBinderName() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            @Typeclass
            interface Show<A> {
                fun label(): String
            }

            object FirstIntShow : Show<Int> {
                override fun label(): String = "first"
            }

            object SecondIntShow : Show<Int> {
                override fun label(): String = "second"
            }

            context(first: Show<Int>, second: Show<Int>)
            fun ambiguous(): String = summon<Show<Int>>().label() // E:TC_AMBIGUOUS_INSTANCE

            fun main() {
                with(FirstIntShow) {
                    with(SecondIntShow) {
                        println(ambiguous())
                    }
                }
            }
            """.trimIndent()

        val result =
            compileSourceResult(
                source,
                pluginOptions = listOf("typeclassTraceMode=failures"),
            )
        assertEquals(ExitCode.COMPILATION_ERROR, result.exitCode, result.stdout)
        val firstIndex = result.stdout.indexOf("first (local context[0])")
        val secondIndex = result.stdout.indexOf("second (local context[1])")
        assertTrue(firstIndex >= 0, result.stdout)
        assertTrue(secondIndex > firstIndex, result.stdout)
        assertOutputContains(
            result.stdout,
            "[TC_AMBIGUOUS_INSTANCE]",
            "[TC_TRACE] Typeclass resolution trace for demo.Show<kotlin.Int>",
            "[TC_TRACE] result: ambiguity",
        )
    }

    @Test
    fun deriveViaSuccessTracingShowsAuthoredAndNormalizedPlan() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DebugTypeclassResolution
            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Iso
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.TypeclassTraceMode

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @JvmInline
            value class Token(val value: Int)

            data class Wire(val raw: String)

            @JvmInline
            value class UserId(val value: Int)

            @JvmInline
            @DebugTypeclassResolution(mode = TypeclassTraceMode.ALL)
            @DeriveVia(Show::class, TokenWireIso::class, Wire::class)
            value class TaggedUserId(val value: UserId)

            object TokenWireIso : Iso<Token, Wire> {
                override fun to(value: Token): Wire = Wire(value.value.toString())
                override fun from(value: Wire): Token = Token(value.raw.toInt())
            }

            @Instance
            object WireShow : Show<Wire> {
                override fun show(value: Wire): String = value.raw
            }
            """.trimIndent()

        val result = compileSourceResult(source)
        assertEquals(ExitCode.OK, result.exitCode, result.stdout)
        assertOutputContains(
            result.stdout,
            "[TC_TRACE] Typeclass derivation trace for demo.Show<demo.TaggedUserId>",
            "authored path: pinned demo.TokenWireIso -> waypoint demo.Wire",
            "normalized plan:",
        )
    }

    @Test
    fun functionScopedAllModeDoesNotLeakToSiblingFunctions() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DebugTypeclassResolution
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.TypeclassTraceMode
            import one.wabbit.typeclass.summon

            @Typeclass
            interface Show<A> {
                fun label(): String
            }

            @Instance
            object IntShow : Show<Int> {
                override fun label(): String = "int"
            }

            @DebugTypeclassResolution(mode = TypeclassTraceMode.ALL)
            fun traced(): String = summon<Show<Int>>().label()

            fun untraced(): String = summon<Show<Int>>().label()

            fun main() {
                println(traced() + "/" + untraced())
            }
            """.trimIndent()

        val result = compileSourceResult(source)
        assertEquals(ExitCode.OK, result.exitCode, result.stdout)
        assertEquals(1, result.stdout.countOccurrences("[TC_TRACE] Typeclass resolution trace for demo.Show<kotlin.Int>"), result.stdout)
        assertOutputContains(result.stdout, "[TC_TRACE] traced scope: function")
        assertEquals("int/int", runCompiledMain(result.artifacts, "demo.SampleKt"))
    }

    @Test
    fun propertyScopedAllModeTracesOnlyThatPropertyRoot() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DebugTypeclassResolution
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.TypeclassTraceMode
            import one.wabbit.typeclass.summon

            @Typeclass
            interface Show<A> {
                fun label(): String
            }

            @Instance
            object IntShow : Show<Int> {
                override fun label(): String = "int"
            }

            class Screen {
                @DebugTypeclassResolution(mode = TypeclassTraceMode.ALL)
                val traced: String = summon<Show<Int>>().label()

                val plain: String = summon<Show<Int>>().label()
            }

            fun main() {
                val screen = Screen()
                println(screen.traced + "/" + screen.plain)
            }
            """.trimIndent()

        val result = compileSourceResult(source)
        assertEquals(ExitCode.OK, result.exitCode, result.stdout)
        assertEquals(1, result.stdout.countOccurrences("[TC_TRACE] Typeclass resolution trace for demo.Show<kotlin.Int>"), result.stdout)
        assertOutputContains(result.stdout, "[TC_TRACE] traced scope: property traced")
        assertEquals("int/int", runCompiledMain(result.artifacts, "demo.SampleKt"))
    }

    @Test
    fun localVariableScopedAllModeTracesOnlyThatInitializer() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DebugTypeclassResolution
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.TypeclassTraceMode
            import one.wabbit.typeclass.summon

            @Typeclass
            interface Show<A> {
                fun label(): String
            }

            @Instance
            object IntShow : Show<Int> {
                override fun label(): String = "int"
            }

            fun tracedOnce(): String {
                @DebugTypeclassResolution(mode = TypeclassTraceMode.ALL)
                val traced = summon<Show<Int>>().label()
                val plain = summon<Show<Int>>().label()
                return traced + "/" + plain
            }

            fun main() {
                println(tracedOnce())
            }
            """.trimIndent()

        val result = compileSourceResult(source)
        assertEquals(ExitCode.OK, result.exitCode, result.stdout)
        assertEquals(1, result.stdout.countOccurrences("[TC_TRACE] Typeclass resolution trace for demo.Show<kotlin.Int>"), result.stdout)
        assertOutputContains(result.stdout, "[TC_TRACE] traced scope: local variable traced")
        assertEquals("int/int", runCompiledMain(result.artifacts, "demo.SampleKt"))
    }

    @Test
    fun inheritDefersToNearestOuterAllMode() {
        val source =
            """
            @file:one.wabbit.typeclass.DebugTypeclassResolution(
                mode = one.wabbit.typeclass.TypeclassTraceMode.ALL
            )

            package demo

            import one.wabbit.typeclass.DebugTypeclassResolution
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.TypeclassTraceMode
            import one.wabbit.typeclass.summon

            @Typeclass
            interface Show<A> {
                fun label(): String
            }

            @Instance
            object IntShow : Show<Int> {
                override fun label(): String = "int"
            }

            @DebugTypeclassResolution(mode = TypeclassTraceMode.INHERIT)
            fun inherited(): String = summon<Show<Int>>().label()

            fun main() {
                println(inherited())
            }
            """.trimIndent()

        val result = compileSourceResult(source)
        assertEquals(ExitCode.OK, result.exitCode, result.stdout)
        assertOutputContains(
            result.stdout,
            "[TC_TRACE] Typeclass resolution trace for demo.Show<kotlin.Int>",
            "[TC_TRACE] effective mode: ALL",
            "[TC_TRACE] traced scope: file Sample.kt",
        )
        assertEquals("int", runCompiledMain(result.artifacts, "demo.SampleKt"))
    }

    @Test
    fun disabledModeSuppressesFailureTraces() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DebugTypeclassResolution
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.TypeclassTraceMode
            import one.wabbit.typeclass.summon

            @Typeclass
            interface Show<A> {
                fun label(): String
            }

            @Instance
            object IntShow : Show<Int> {
                override fun label(): String = "int"
            }

            @DebugTypeclassResolution(mode = TypeclassTraceMode.DISABLED)
            fun hidden(): String = summon<Show<String>>().label() // E:TC_NO_CONTEXT_ARGUMENT
            """.trimIndent()

        val result = compileSourceResult(source)
        assertEquals(ExitCode.COMPILATION_ERROR, result.exitCode, result.stdout)
        assertOutputContains(result.stdout, "no context argument")
        assertOutputNotContains(result.stdout, "[TC_TRACE]")
    }

    @Test
    fun bareNestedAnnotationResetsInheritedAllModeToFailures() {
        val source =
            """
            @file:one.wabbit.typeclass.DebugTypeclassResolution(
                mode = one.wabbit.typeclass.TypeclassTraceMode.ALL
            )

            package demo

            import one.wabbit.typeclass.DebugTypeclassResolution
            import one.wabbit.typeclass.Instance
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

            @DebugTypeclassResolution
            fun inner(): String = summon<Show<Int>>().label()

            fun main() {
                println(inner())
            }
            """.trimIndent()

        val result = compileSourceResult(source)
        assertEquals(ExitCode.OK, result.exitCode, result.stdout)
        assertOutputNotContains(result.stdout, "[TC_TRACE] Typeclass resolution trace for demo.Show<kotlin.Int>")
        assertEquals("int", runCompiledMain(result.artifacts, "demo.SampleKt"))
    }
}
