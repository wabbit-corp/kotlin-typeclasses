// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.typeclass.plugin.integration

import org.jetbrains.kotlin.cli.common.ExitCode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Supplemental edge-case spec for mode precedence in scoped typeclass-resolution tracing.
 *
 * The main spec keeps the ordinary user story readable. This file isolates the override cases:
 * `INHERIT`, `DISABLED`, nested re-enable, declaration-local mute, bare nested reset behavior, and
 * future interaction with a global trace mode.
 */
class DebugTypeclassResolutionPrecedenceSpec : IntegrationTestSupport() {
    private fun String.countOccurrences(fragment: String): Int = split(fragment).size - 1

    @Test
    fun propertyScopeParticipatesInNearestScopePrecedence() {
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
            class Screen {
                @DebugTypeclassResolution(mode = TypeclassTraceMode.DISABLED)
                val rendered: String
                    get() {
                        @DebugTypeclassResolution(mode = TypeclassTraceMode.ALL)
                        val retraced = summon<Show<Int>>().label()
                        return retraced
                    }
            }

            fun main() {
                println(Screen().rendered)
            }
            """.trimIndent()

        val result = compileSourceResult(source)
        assertEquals(ExitCode.OK, result.exitCode, result.stdout)
        assertEquals(1, result.stdout.countOccurrences("[TC_TRACE] Typeclass resolution trace for demo.Show<kotlin.Int>"), result.stdout)
        assertOutputContains(
            result.stdout,
            "[TC_TRACE] traced scope: local variable retraced",
        )
        assertOutputNotContains(result.stdout, "[TC_TRACE] traced scope: property rendered")
        assertEquals("int", runCompiledMain(result.artifacts, "demo.SampleKt"))
    }

    @Test
    fun localVariableScopeParticipatesInNearestScopePrecedence() {
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
            fun demo(): String {
                @DebugTypeclassResolution(mode = TypeclassTraceMode.DISABLED)
                val muted = summon<Show<Int>>().label()

                @DebugTypeclassResolution(mode = TypeclassTraceMode.ALL)
                val retraced = summon<Show<Int>>().label()

                return muted + retraced
            }

            fun main() {
                println(demo())
            }
            """.trimIndent()

        val result = compileSourceResult(source)
        assertEquals(ExitCode.OK, result.exitCode, result.stdout)
        assertEquals(1, result.stdout.countOccurrences("[TC_TRACE] Typeclass resolution trace for demo.Show<kotlin.Int>"), result.stdout)
        assertOutputContains(
            result.stdout,
            "[TC_TRACE] traced scope: local variable retraced",
        )
        assertEquals("intint", runCompiledMain(result.artifacts, "demo.SampleKt"))
    }

    @Test
    fun explicitModeInheritDefersToTheNearestOuterMode() {
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
            fun inheritedTrace(): String = summon<Show<Int>>().label()

            fun main() {
                println(inheritedTrace())
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
    fun explicitModeInheritDoesNotForceTracingWhenAmbientModeIsDisabled() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DebugTypeclassResolution
            import one.wabbit.typeclass.TypeclassTraceMode
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

            @DebugTypeclassResolution(mode = TypeclassTraceMode.INHERIT)
            fun maybeTraced(): String = summon<Show<Int>>().label()

            fun main() {
                println(maybeTraced())
            }
            """.trimIndent()

        val result = compileSourceResult(source)
        assertEquals(ExitCode.OK, result.exitCode, result.stdout)
        assertOutputNotContains(result.stdout, "[TC_TRACE]")
        assertEquals("int", runCompiledMain(result.artifacts, "demo.SampleKt"))
    }

    @Test
    fun inheritDoesNotBypassDisabledBarrier() {
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
            fun outer(): String {
                @DebugTypeclassResolution(mode = TypeclassTraceMode.INHERIT)
                val stillMuted = summon<Show<Int>>().label()
                @DebugTypeclassResolution(mode = TypeclassTraceMode.ALL)
                val retraced = summon<Show<Int>>().label()
                return stillMuted + retraced
            }

            fun main() {
                println(outer())
            }
            """.trimIndent()

        val result = compileSourceResult(source)
        assertEquals(ExitCode.OK, result.exitCode, result.stdout)
        assertEquals(1, result.stdout.countOccurrences("[TC_TRACE] Typeclass resolution trace for demo.Show<kotlin.Int>"), result.stdout)
        assertOutputContains(
            result.stdout,
            "[TC_TRACE] traced scope: local variable retraced",
        )
        assertEquals("intint", runCompiledMain(result.artifacts, "demo.SampleKt"))
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

    @Test
    fun innerLocalVariableCanDisableTracingInsideAnOtherwiseTracedFunction() {
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
            fun useBoth(): String {
                @DebugTypeclassResolution(mode = TypeclassTraceMode.DISABLED)
                val muted = summon<Show<Int>>().label()
                val traced = summon<Show<Int>>().label()
                return muted + traced
            }

            fun main() {
                println(useBoth())
            }
            """.trimIndent()

        val result = compileSourceResult(source)
        assertEquals(ExitCode.OK, result.exitCode, result.stdout)
        assertEquals(1, result.stdout.countOccurrences("[TC_TRACE] Typeclass resolution trace for demo.Show<kotlin.Int>"), result.stdout)
        assertOutputContains(
            result.stdout,
            "[TC_TRACE] traced scope: function",
        )
        assertEquals("intint", runCompiledMain(result.artifacts, "demo.SampleKt"))
    }

    @Test
    fun innerLocalVariableCanReEnableTracingInsideAnOtherwiseMutedFunction() {
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
            fun useBoth(): String {
                val muted = summon<Show<Int>>().label()
                @DebugTypeclassResolution(mode = TypeclassTraceMode.ALL)
                val retraced = summon<Show<Int>>().label()
                return muted + retraced
            }

            fun main() {
                println(useBoth())
            }
            """.trimIndent()

        val result = compileSourceResult(source)
        assertEquals(ExitCode.OK, result.exitCode, result.stdout)
        assertEquals(1, result.stdout.countOccurrences("[TC_TRACE] Typeclass resolution trace for demo.Show<kotlin.Int>"), result.stdout)
        assertOutputContains(
            result.stdout,
            "[TC_TRACE] traced scope: local variable retraced",
        )
        assertEquals("intint", runCompiledMain(result.artifacts, "demo.SampleKt"))
    }

    @Test
    fun fileScopedTracingCanBeDisabledForASpecificDerivedDeclaration() {
        val source =
            """
            @file:one.wabbit.typeclass.DebugTypeclassResolution

            package demo

            import one.wabbit.typeclass.DebugTypeclassResolution
            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.SumTypeclassMetadata
            import one.wabbit.typeclass.TypeclassTraceMode
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

            @Instance
            object IntShow : Show<Int> {
                override fun show(value: Int): String = value.toString()
            }

            @DebugTypeclassResolution(mode = TypeclassTraceMode.DISABLED)
            @Derive(Show::class) // E:TC_CANNOT_DERIVE
            sealed interface Expr

            data class Lit<T>(val value: T) : Expr
            """.trimIndent()

        val result = compileSourceResult(source)
        assertEquals(ExitCode.COMPILATION_ERROR, result.exitCode, result.stdout)
        assertOutputContains(result.stdout, "[TC_CANNOT_DERIVE]")
        assertOutputNotContains(result.stdout, "[TC_TRACE]")
    }

    @Test
    fun annotationScopeOverridesGlobalTraceFlagConfiguration() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DebugTypeclassResolution
            import one.wabbit.typeclass.TypeclassTraceMode
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

            @Instance
            object AnyShow : Show<Any> {
                override fun label(): String = "any"
            }

            @DebugTypeclassResolution(mode = TypeclassTraceMode.DISABLED)
            fun locallyMuted(): String {
                return summon<Show<Int>>().label()
            }

            @DebugTypeclassResolution(mode = TypeclassTraceMode.ALL_AND_ALTERNATIVES)
            fun locallyVerbose(): String {
                return summon<Show<Int>>().label()
            }

            fun main() {
                println(locallyMuted() + "/" + locallyVerbose())
            }
            """.trimIndent()

        val result = compileSourceResult(source, pluginOptions = listOf("typeclassTraceMode=failures"))
        assertEquals(ExitCode.OK, result.exitCode, result.stdout)
        assertEquals(1, result.stdout.countOccurrences("[TC_TRACE] Typeclass resolution trace for demo.Show<kotlin.Int>"), result.stdout)
        assertOutputContains(result.stdout, "[TC_TRACE] effective mode: ALL_AND_ALTERNATIVES")
        assertEquals("int/int", runCompiledMain(result.artifacts, "demo.SampleKt"))
    }
}
