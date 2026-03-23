package one.wabbit.typeclass.plugin.integration

import kotlin.test.Ignore
import kotlin.test.Test

/**
 * Supplemental edge-case spec for mode precedence in scoped typeclass-resolution tracing.
 *
 * The main spec keeps the ordinary user story readable. This file isolates the override cases:
 * `INHERIT`, `DISABLED`, nested re-enable, declaration-local mute, bare nested reset behavior, and
 * future interaction with a global trace mode.
 */
@Ignore("Spec-only: active tracing coverage lives in DebugTypeclassResolutionTest")
class DebugTypeclassResolutionPrecedenceSpec : IntegrationTestSupport() {
    @Test
    fun propertyScopeParticipatesInNearestScopePrecedence() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DebugTypeclassResolution
            import one.wabbit.typeclass.TypeclassTraceMode
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            data class User(val id: Int)

            @DebugTypeclassResolution
            class Screen {
                @DebugTypeclassResolution(mode = TypeclassTraceMode.DISABLED)
                val rendered: String
                    get() {
                        @DebugTypeclassResolution(mode = TypeclassTraceMode.FAILURES)
                        val retraced = render(User(1)) // E:TC_NO_CONTEXT_ARGUMENT
                        return retraced
                    }
            }
            """.trimIndent()

        // Intended future trace:
        // - class scope would trace by default
        // - property scope mutes the getter root
        // - the nested local variable explicitly re-enables failure tracing for its initializer
        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedNoContextArgument(phase = DiagnosticPhase.IR)),
        )
    }

    @Test
    fun localVariableScopeParticipatesInNearestScopePrecedence() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DebugTypeclassResolution
            import one.wabbit.typeclass.TypeclassTraceMode
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Eq<A>

            @DebugTypeclassResolution
            fun demo(): String {
                @DebugTypeclassResolution(mode = TypeclassTraceMode.DISABLED)
                val muted = needEqInt() // E:TC_NO_CONTEXT_ARGUMENT

                @DebugTypeclassResolution(mode = TypeclassTraceMode.FAILURES)
                val retraced = needEqString() // E:TC_NO_CONTEXT_ARGUMENT

                return muted + retraced
            }

            context(_: Eq<Int>)
            fun needEqInt(): String = "int"

            context(_: Eq<String>)
            fun needEqString(): String = "string"
            """.trimIndent()

        // Intended future trace:
        // - the function enables tracing
        // - the first local variable mutes its initializer
        // - the second local variable re-enables tracing for its own initializer
        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedNoContextArgument(phase = DiagnosticPhase.IR),
                    expectedNoContextArgument(phase = DiagnosticPhase.IR),
                ),
        )
    }

    @Test
    fun explicitModeInheritDefersToTheNearestOuterMode() {
        val source =
            """
            @file:one.wabbit.typeclass.DebugTypeclassResolution(
                mode = one.wabbit.typeclass.TypeclassTraceMode.FAILURES
            )

            package demo

            import one.wabbit.typeclass.DebugTypeclassResolution
            import one.wabbit.typeclass.TypeclassTraceMode
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Eq<A>

            @DebugTypeclassResolution(mode = TypeclassTraceMode.INHERIT)
            fun inheritedTrace(): String = needEqInt() // E:TC_NO_CONTEXT_ARGUMENT

            context(_: Eq<Int>)
            fun needEqInt(): String = "int"
            """.trimIndent()

        // Intended future trace:
        // - `INHERIT` is explicit defer-to-parent
        // - the file-level failure-tracing mode therefore applies here
        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedNoContextArgument(phase = DiagnosticPhase.IR)),
        )
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

        // Intended future trace:
        // - ambient tracing is disabled in this suite unless stated otherwise
        // - `mode = INHERIT` therefore does not force a success trace on
        assertCompilesAndRuns(
            source = source,
            expectedStdout = "int",
        )
    }

    @Test
    fun inheritDoesNotBypassDisabledBarrier() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DebugTypeclassResolution
            import one.wabbit.typeclass.TypeclassTraceMode
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Eq<A>

            @DebugTypeclassResolution(mode = TypeclassTraceMode.DISABLED)
            fun outer(): String {
                @DebugTypeclassResolution(mode = TypeclassTraceMode.INHERIT)
                val stillMuted = needEqInt() // E:TC_NO_CONTEXT_ARGUMENT
                @DebugTypeclassResolution(mode = TypeclassTraceMode.FAILURES)
                val retraced = needEqString() // E:TC_NO_CONTEXT_ARGUMENT
                return stillMuted + retraced
            }

            context(_: Eq<Int>)
            fun needEqInt(): String = "int"

            context(_: Eq<String>)
            fun needEqString(): String = "string"
            """.trimIndent()

        // Intended future trace:
        // - `DISABLED` is a barrier for inherited behavior
        // - the `INHERIT` local variable therefore stays muted
        // - only the explicit `FAILURES` local variable re-enable emits a trace
        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedNoContextArgument(phase = DiagnosticPhase.IR),
                    expectedNoContextArgument(phase = DiagnosticPhase.IR),
                ),
        )
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

        // Intended future trace:
        // - file scope would trace successes because it is in `ALL`
        // - the bare nested annotation is not neutral inheritance
        // - it resets the nested scope to `FAILURES`, so the successful inner root emits no trace
        assertCompilesAndRuns(
            source = source,
            expectedStdout = "int",
        )
    }

    @Test
    fun innerLocalVariableCanDisableTracingInsideAnOtherwiseTracedFunction() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DebugTypeclassResolution
            import one.wabbit.typeclass.TypeclassTraceMode
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A>

            @DebugTypeclassResolution
            fun useBoth(): String {
                @DebugTypeclassResolution(mode = TypeclassTraceMode.DISABLED)
                val muted = missingInt() // E:TC_NO_CONTEXT_ARGUMENT
                val traced = missingString() // E:TC_NO_CONTEXT_ARGUMENT
                return muted + traced
            }

            context(_: Show<Int>)
            fun missingInt(): String = "int"

            context(_: Show<String>)
            fun missingString(): String = "string"
            """.trimIndent()

        // Intended future trace:
        // - the outer function enables failure tracing
        // - the nested local variable mutes only its own initializer
        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedNoContextArgument(phase = DiagnosticPhase.IR),
                    expectedNoContextArgument(phase = DiagnosticPhase.IR),
                ),
        )
    }

    @Test
    fun innerLocalVariableCanReEnableTracingInsideAnOtherwiseMutedFunction() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DebugTypeclassResolution
            import one.wabbit.typeclass.TypeclassTraceMode
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A>

            @DebugTypeclassResolution(mode = TypeclassTraceMode.DISABLED)
            fun useBoth(): String {
                val muted = missingInt() // E:TC_NO_CONTEXT_ARGUMENT
                @DebugTypeclassResolution(mode = TypeclassTraceMode.FAILURES)
                val retraced = missingString() // E:TC_NO_CONTEXT_ARGUMENT
                return muted + retraced
            }

            context(_: Show<Int>)
            fun missingInt(): String = "int"

            context(_: Show<String>)
            fun missingString(): String = "string"
            """.trimIndent()

        // Intended future trace:
        // - the outer function mutes tracing
        // - the nested local variable explicitly re-enables it for its own initializer
        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedNoContextArgument(phase = DiagnosticPhase.IR),
                    expectedNoContextArgument(phase = DiagnosticPhase.IR),
                ),
        )
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

        // Intended future trace:
        // - file scope would ordinarily trace the derivation failure
        // - declaration-local `DISABLED` suppresses that derivation-root trace
        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedCannotDerive(phase = DiagnosticPhase.IR)),
        )
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
                val ok = summon<Show<Int>>().label()
                val boom = hiddenFailure() // E:TC_NO_CONTEXT_ARGUMENT
                return ok + boom
            }

            @DebugTypeclassResolution(mode = TypeclassTraceMode.ALL_AND_ALTERNATIVES)
            fun locallyVerbose(): String {
                val ok = summon<Show<Int>>().label()
                val boom = verboseFailure() // E:TC_NO_CONTEXT_ARGUMENT
                return ok + boom
            }

            context(_: Show<String>)
            fun hiddenFailure(): String = "hidden"

            context(_: Show<String>)
            fun verboseFailure(): String = "verbose"
            """.trimIndent()

        // Intended future trace:
        // - future harness should compile this fixture with global mode `FAILURES`
        // - `locallyMuted` still suppresses both its success and failure traces
        // - `locallyVerbose` proves the upward override too by upgrading from global `FAILURES` to
        //   local `ALL_AND_ALTERNATIVES`
        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedNoContextArgument(phase = DiagnosticPhase.IR),
                    expectedNoContextArgument(phase = DiagnosticPhase.IR),
                ),
        )
    }
}
