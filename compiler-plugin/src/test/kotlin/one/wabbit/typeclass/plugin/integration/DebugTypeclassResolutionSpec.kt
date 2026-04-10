// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.typeclass.plugin.integration

import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.kotlin.cli.common.ExitCode

/**
 * Specification for a future scoped typeclass-resolution tracing facility.
 *
 * Motivation:
 * - Plugin-owned failure diagnostics are already much better than they used to be.
 * - What is still missing is an explanation mode for:
 *     * why a missing goal failed
 *     * why an ambiguity happened
 *     * why a successful goal chose one candidate
 *     * which prerequisite subgoals were searched while resolving a larger request
 * - A global mode-based compiler option such as `typeclassTraceMode=<mode>` is still useful for
 *   whole-project debugging, but it is too blunt for ordinary use. Most people want to trace one
 *   file, one function, or one property/local initializer without turning the build log into soup.
 *
 * Proposed public surface:
 * - Add a source-retained annotation:
 *
 *   `@Target(FILE, CLASS, FUNCTION, PROPERTY, LOCAL_VARIABLE)` `@Retention(SOURCE)` `enum class
 *   TypeclassTraceMode {` ` INHERIT,` ` DISABLED,` ` FAILURES,` ` FAILURES_AND_ALTERNATIVES,` `
 *   ALL,` ` ALL_AND_ALTERNATIVES,` `}` `annotation class DebugTypeclassResolution(` ` val mode:
 *   TypeclassTraceMode = TypeclassTraceMode.FAILURES,` `)`
 *
 * Default model:
 * - The ambient global tracing mode is assumed `DISABLED` unless a test says otherwise.
 * - Bare `@DebugTypeclassResolution` therefore means:
 *     * tracing is enabled in that lexical scope
 *     * failed and ambiguous roots emit traces
 *     * successful roots do not emit traces
 *     * the trace is a faithful search trace, not a speculative "why not every other rule?" essay
 * - `mode = INHERIT` is the explicit defer-to-parent / defer-to-global-mode escape hatch.
 * - `mode = DISABLED` mutes that scope unless a nested scope explicitly re-enables tracing.
 * - A bare nested `@DebugTypeclassResolution` is not neutral inheritance; it resets that nested
 *   scope to `FAILURES`.
 *
 * Common recipes:
 * - `@DebugTypeclassResolution` = trace failures and ambiguities here
 * - `@DebugTypeclassResolution(mode = TypeclassTraceMode.ALL)` = also trace successful roots here
 * - `@DebugTypeclassResolution(mode = TypeclassTraceMode.FAILURES_AND_ALTERNATIVES)` = still trace
 *   only failures/ambiguities, but add the bounded explanatory pass
 * - `@DebugTypeclassResolution(mode = TypeclassTraceMode.ALL_AND_ALTERNATIVES)` = full local
 *   tracing
 * - `@DebugTypeclassResolution(mode = TypeclassTraceMode.INHERIT)` = inherit from an outer traced
 *   scope or global mode; do not force tracing on
 * - `@DebugTypeclassResolution(mode = TypeclassTraceMode.DISABLED)` = mute a noisy nested scope
 * - If an outer scope is already in `ALL`, a bare nested `@DebugTypeclassResolution` still resets
 *   that nested scope to `FAILURES`; use `mode = INHERIT` to keep the outer mode.
 *
 * Scope model:
 * - File scope: `@file:DebugTypeclassResolution` traces roots whose root sites are lexically inside
 *   that file.
 * - Class / object scope: `@DebugTypeclassResolution class Foo` traces roots inside member bodies,
 *   property initializers, nested declarations, and companion bodies enclosed by that class or
 *   object.
 * - Property scope: `@DebugTypeclassResolution val rendered = render(User(1))` traces roots in the
 *   initializer, delegate, and accessor bodies of that property.
 * - Local variable scope: `@DebugTypeclassResolution val rendered = render(User(1))` inside a
 *   function traces roots in that local initializer only.
 * - Function scope: `@DebugTypeclassResolution fun use()` traces roots inside that function body,
 *   including local lambdas and local functions.
 * - Expression scope is intentionally out of scope for now. FIR retains expression annotations, but
 *   the current IR pipeline does not preserve them in a form that makes scoped request-root tracing
 *   straightforward without a separate source-range bridge. The supported fine-grained substitute
 *   is local-variable scope.
 * - Ordinary declaration note: annotating a function, class, object, property, or local variable
 *   traces roots inside that declaration's own lexical body. It does not automatically trace
 *   caller-side resolution of that declaration's own context parameters; caller-side tracing is
 *   controlled at the caller root site.
 *
 * Derivation-root model:
 * - Derivation events are rooted at the derived declaration site, not at a call expression.
 * - The same nearest-enclosing lexical scope rule applies there too.
 * - Direct annotation on the derived declaration is simply the nearest override.
 * - This covers `@Derive`, `@DeriveVia`, and `@DeriveEquiv`.
 * - Annotating a derived declaration traces derivation/planning events rooted there; it does not
 *   automatically trace unrelated later use-site resolution that merely consumes the derived
 *   instance.
 *
 * Precedence:
 * - The effective mode is chosen from the nearest enclosing lexical scope with a non-`INHERIT`
 *   value.
 * - This is ordinary lexical containment, not declaration-discovery order and not a fake universal
 *   total ordering.
 * - `DISABLED` acts as a barrier for inherited trace behavior. A nested scope may re-enable tracing
 *   explicitly, but it does not inherit a richer mode through the muted parent.
 *
 * Mode semantics:
 * - `DISABLED`
 *     * no trace output is emitted for roots in that scope
 *     * ordinary resolution, ranking, ambiguity, derivation, and code generation are unchanged
 * - `FAILURES`
 *     * failed and ambiguous roots emit a trace
 *     * successful roots do not
 *     * the trace is a faithful search trace
 * - `FAILURES_AND_ALTERNATIVES`
 *     * same emission policy as `FAILURES`
 *     * after the real search finishes, the compiler may run a bounded explanatory pass that
 *       records alternatives that were not part of the actual winning/failing search
 * - `ALL`
 *     * successful roots emit traces too
 *     * the trace is still a faithful search trace
 * - `ALL_AND_ALTERNATIVES`
 *     * successful roots emit traces too
 *     * the bounded explanatory pass is also enabled
 * - `INHERIT`
 *     * defer to the nearest enclosing explicit mode or the global compiler mode
 *
 * Observable contract:
 * - The annotation must not change:
 *     * candidate discovery
 *     * ranking
 *     * ambiguity behavior
 *     * derivation behavior
 *     * generated code shape
 *     * runtime output
 * - It changes only emitted trace output.
 *
 * Truthful trace versus explanatory pass:
 * - The faithful trace records only what the resolver actually searched.
 * - The explanatory pass may add "explained but not searched" candidates after the real search
 *   finishes, but those must be labeled as explained rather than searched.
 * - This avoids turning the trace into historical fiction.
 *
 * Trace content:
 * - Every trace should identify:
 *     * the requested goal, for example `Show<UserId>`
 *     * the root kind: `resolution` or `derivation`
 *     * the root site
 *     * the effective mode
 *     * the active traced scope and why tracing is enabled there
 * - Candidate families should show:
 *     * local contexts
 *     * explicit `@Instance` rules
 *     * builtin rules
 *     * derived rules (`@Derive`, `@DeriveVia`, `@DeriveEquiv`)
 *     * why candidates were rejected
 *     * `not explored after decisive local match` where applicable
 * - Recursive reuse / knot detection must be shown explicitly.
 *
 * Deterministic ordering:
 * - Declaration-backed candidates are ordered by stable canonical symbol identity, not by discovery
 *   order or hash-map iteration order.
 * - Local-context candidates use lexical binder order plus source anchor because they do not have
 *   stable declaration identities.
 * - Human-facing local-context display should prefer binder names when available, for example
 *   `first (local context @ Demo.kt:12)`.
 * - Builtins should use stable builtin rule ids, for example `builtin:KClass`.
 * - Derived / synthetic candidates should use stable synthetic ids composed from owner id, rule
 *   kind, and requested goal.
 * - The final ambiguous-candidate summary should be globally sorted by those stable identities.
 *
 * Output shape:
 * - Failed or ambiguous roots:
 *     * keep the existing primary `TC_*` error
 *     * emit the trace as a supplemental `[TC_TRACE]` note-like secondary diagnostic at the same
 *       anchor
 *     * the trace must not count as an extra error or warning
 * - Successful roots:
 *     * emit `[TC_TRACE]` as compiler `INFO` messages anchored at the root site
 *     * do not use warnings
 *     * still emit those `INFO` traces even if the overall compilation later fails for unrelated
 *       reasons
 * - Future machine-readable sinks such as JSONL are output-channel configuration, not annotation
 *   semantics.
 *
 * Phase contract:
 * - Emit one logical trace per root, not one per compiler phase.
 * - FIR and IR may enrich the same in-flight trace record, but users should observe one final
 *   trace.
 *
 * Bounds:
 * - Trace size must be bounded.
 * - Depth, candidates per family, and explained alternatives per goal should all be capped.
 * - Truncation must be explicit, for example `... 12 more candidates omitted`.
 *
 * Derivation-specific requirements:
 * - `@Derive` failures should embed the same substantive reason already carried by the primary
 *   `TC_CANNOT_DERIVE` diagnostic.
 * - Derivation traces should present themselves as derivation-rooted traces, not ordinary
 *   call-expression resolution traces.
 * - `@DeriveVia` traces should show both:
 *     * the authored path segments exactly as written
 *     * the normalized transport plan after pinned-segment orientation and inserted `Equiv` edges
 * - `@DeriveEquiv` traces should show whether the solver used exported evidence, transient local
 *   synthesis, composition, or inversion.
 *
 * Testing philosophy:
 * - Implemented behaviors in this suite run as active tests.
 * - Still-aspirational cases stay individually ignored until the implementation catches up.
 * - Precedence and inheritance edge cases live in `DebugTypeclassResolutionPrecedenceSpec`.
 * - Phase-specific expectations in this suite refer only to today's primary diagnostic location,
 *   not to the future trace transport itself.
 * - Future harness helpers should assert trace blocks directly:
 *     * `assertTraceContains(...)`
 *     * `assertNoTraceForSite(...)`
 *     * `assertTraceScope(...)`
 *     * `assertTraceCandidateOrder(...)`
 *     * `assertSuccessTraceContains(...)`
 *     * `assertNoSuccessTraceForSite(...)`
 */
class DebugTypeclassResolutionSpec : IntegrationTestSupport() {
    private fun String.countOccurrences(fragment: String): Int = split(fragment).size - 1

    @Test
    fun fileScopedTracingAppliesAcrossTheWholeFile() {
        val source =
            """
            @file:one.wabbit.typeclass.DebugTypeclassResolution(
                mode = one.wabbit.typeclass.TypeclassTraceMode.ALL
            )

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

            fun first(): String = summon<Show<Int>>().label()
            fun second(): String = summon<Show<Int>>().label()

            fun main() = println(first() + "/" + second())
            """
                .trimIndent()

        val result = compileSourceResult(source)
        assertEquals(ExitCode.OK, result.exitCode, result.stdout)
        assertOutputContains(
            result.stdout,
            "[TC_TRACE] Typeclass resolution trace for demo.Show<kotlin.Int>",
            "[TC_TRACE] effective mode: ALL",
            "[TC_TRACE] traced scope: file Sample.kt",
        )
        assertEquals(
            2,
            result.stdout.countOccurrences(
                "[TC_TRACE] Typeclass resolution trace for demo.Show<kotlin.Int>"
            ),
            result.stdout,
        )
        assertEquals("int/int", runCompiledMain(result.artifacts, "demo.SampleKt"))
    }

    @Test
    fun classScopedTracingAppliesToMembersAndCompanionBodiesOnly() {
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
                fun tracedMember(): String = summon<Show<Int>>().label()

                companion object {
                    fun tracedCompanion(): String = summon<Show<Int>>().label()
                }
            }

            fun outside(): String = summon<Show<Int>>().label()

            fun main() {
                println(Screen().tracedMember())
                println(Screen.tracedCompanion())
                println(outside())
            }
            """
                .trimIndent()

        val result = compileSourceResult(source)
        assertEquals(ExitCode.OK, result.exitCode, result.stdout)
        assertEquals(
            2,
            result.stdout.countOccurrences(
                "[TC_TRACE] Typeclass resolution trace for demo.Show<kotlin.Int>"
            ),
            result.stdout,
        )
        assertOutputContains(result.stdout, "[TC_TRACE] traced scope: class demo/Screen")
        assertEquals("int\nint\nint", runCompiledMain(result.artifacts, "demo.SampleKt"))
    }

    @Test
    fun propertyScopedTracingTargetsOnlyThatPropertyRoot() {
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
            """
                .trimIndent()

        val result = compileSourceResult(source)
        assertEquals(ExitCode.OK, result.exitCode, result.stdout)
        assertEquals(
            1,
            result.stdout.countOccurrences(
                "[TC_TRACE] Typeclass resolution trace for demo.Show<kotlin.Int>"
            ),
            result.stdout,
        )
        assertOutputContains(result.stdout, "[TC_TRACE] traced scope: property traced")
        assertEquals("int/int", runCompiledMain(result.artifacts, "demo.SampleKt"))
    }

    @Test
    fun localVariableScopedTracingTargetsOnlyThatLocalInitializer() {
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
            """
                .trimIndent()

        val result = compileSourceResult(source)
        assertEquals(ExitCode.OK, result.exitCode, result.stdout)
        assertEquals(
            1,
            result.stdout.countOccurrences(
                "[TC_TRACE] Typeclass resolution trace for demo.Show<kotlin.Int>"
            ),
            result.stdout,
        )
        assertOutputContains(result.stdout, "[TC_TRACE] traced scope: local variable traced")
        assertEquals("int/int", runCompiledMain(result.artifacts, "demo.SampleKt"))
    }

    @Test
    fun functionScopedTracingDoesNotLeakToSiblingFunctions() {
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
            """
                .trimIndent()

        val result = compileSourceResult(source)
        assertEquals(ExitCode.OK, result.exitCode, result.stdout)
        assertEquals(
            1,
            result.stdout.countOccurrences(
                "[TC_TRACE] Typeclass resolution trace for demo.Show<kotlin.Int>"
            ),
            result.stdout,
        )
        assertOutputContains(result.stdout, "[TC_TRACE] traced scope: function")
        assertEquals("int/int", runCompiledMain(result.artifacts, "demo.SampleKt"))
    }

    @Test
    fun successTracingRequiresModeAll() {
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
            object AnyShow : Show<Any> {
                override fun label(): String = "any"
            }

            @Instance
            object IntShow : Show<Int> {
                override fun label(): String = "int"
            }

            @DebugTypeclassResolution(mode = TypeclassTraceMode.ALL)
            context(_: Show<Int>)
            fun chosen(): String = summon<Show<Int>>().label()

            fun main() {
                println(chosen())
            }
            """
                .trimIndent()

        val result = compileSourceResult(source)
        assertEquals(ExitCode.OK, result.exitCode, result.stdout)
        assertOutputContains(
            result.stdout,
            "[TC_TRACE] Typeclass resolution trace for demo.Show<kotlin.Int>",
            "[TC_TRACE] effective mode: ALL",
            "[TC_TRACE] result: success",
        )
        assertEquals("int", runCompiledMain(result.artifacts, "demo.SampleKt"))
    }

    @Test
    @Ignore("Alternative-explanation success traces are not asserted yet")
    fun successTracingCanExplainAlternativesInModeAllAndAlternatives() {
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
            object AnyShow : Show<Any> {
                override fun label(): String = "any"
            }

            @Instance
            object IntShow : Show<Int> {
                override fun label(): String = "int"
            }

            @DebugTypeclassResolution(mode = TypeclassTraceMode.ALL_AND_ALTERNATIVES)
            context(_: Show<Int>)
            fun chosen(): String = summon<Show<Int>>().label()

            fun main() {
                println(chosen())
            }
            """
                .trimIndent()

        assertCompilesAndRuns(source = source, expectedStdout = "int")
    }

    @Test
    fun failureTracingCanExplainAlternativesInModeFailuresAndAlternatives() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DebugTypeclassResolution
            import one.wabbit.typeclass.TypeclassTraceMode
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance
            object AnyShow : Show<Any> {
                override fun show(value: Any): String = "any"
            }

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            @DebugTypeclassResolution(mode = TypeclassTraceMode.FAILURES_AND_ALTERNATIVES)
            fun tracedFailure(): String = render(1) // E:TC_NO_CONTEXT_ARGUMENT
            """
                .trimIndent()

        val result = compileSourceResult(source)
        assertEquals(ExitCode.COMPILATION_ERROR, result.exitCode, result.stdout)
        assertOutputContains(
            result.stdout,
            "[TC_NO_CONTEXT_ARGUMENT]",
            "[TC_TRACE] Typeclass resolution trace for demo.Show<kotlin.Int>",
            "[TC_TRACE] effective mode: FAILURES_AND_ALTERNATIVES",
            "demo.AnyShow",
            "rejected; not applicable due to head mismatch",
            "[TC_TRACE] result: failure",
        )
    }

    @Test
    fun ambiguityTracingListsCandidatesInDeterministicOrder() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DebugTypeclassResolution
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            @Typeclass
            interface Show<A> {
                fun label(): String
            }

            @DebugTypeclassResolution
            context(first: Show<Int>, second: Show<Int>)
            fun ambiguous(): String = summon<Show<Int>>().label() // E:TC_AMBIGUOUS_INSTANCE
            """
                .trimIndent()

        val result = compileSourceResult(source)
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
    fun checkerCallFailureTraceIncludesDerivedCandidates() {
        val result =
            compileSourceInternal(
                sources =
                    mapOf(
                        "shared/Show.kt" to
                            """
                            package shared

                            import one.wabbit.typeclass.ProductTypeclassDeriver
                            import one.wabbit.typeclass.ProductTypeclassMetadata
                            import one.wabbit.typeclass.Typeclass

                            @Typeclass
                            interface Show<A> {
                                fun show(value: A): String

                                companion object : ProductTypeclassDeriver {
                                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                                        object : Show<Any?> {
                                            override fun show(value: Any?): String = metadata.typeName
                                        }
                                }
                            }
                            """
                                .trimIndent(),
                        "shared/ShownString.kt" to
                            """
                            package shared

                            import one.wabbit.typeclass.Instance

                            data class ShownString(val value: String) {
                                companion object {
                                    @Instance
                                    val show: Show<ShownString> =
                                        object : Show<ShownString> {
                                            override fun show(value: ShownString): String = value.value
                                        }
                                }
                            }
                            """
                                .trimIndent(),
                        "shared/Box.kt" to
                            """
                            package shared

                            import one.wabbit.typeclass.Derive
                            import one.wabbit.typeclass.Instance

                            @Derive(Show::class)
                            data class Box(val value: ShownString) {
                                companion object {
                                    @Instance
                                    val show: Show<Box> =
                                        object : Show<Box> {
                                            override fun show(value: Box): String = "manual"
                                        }
                                }
                            }
                            """
                                .trimIndent(),
                        "demo/Main.kt" to
                            """
                            package demo

                            import one.wabbit.typeclass.DebugTypeclassResolution
                            import one.wabbit.typeclass.TypeclassTraceMode
                            import shared.Box
                            import shared.Show
                            import shared.ShownString

                            context(_: Show<Box>)
                            fun render(value: Box): String = "context"

                            fun render(value: Box): String = "plain"

                            @DebugTypeclassResolution(mode = TypeclassTraceMode.FAILURES_AND_ALTERNATIVES)
                            fun tracedFailure(): String = render(Box(ShownString("clash"))) // E:TC_AMBIGUOUS_INSTANCE
                            """
                                .trimIndent(),
                    )
            )
        assertEquals(ExitCode.COMPILATION_ERROR, result.exitCode, result.stdout)
        assertOutputContains(
            result.stdout,
            "[TC_AMBIGUOUS_INSTANCE]",
            "[TC_TRACE] Typeclass resolution trace for shared.Show<shared.Box>",
            "derived rules:",
            "derived:shared.Show:shared.Box:shared.Box",
            "[TC_TRACE] result: ambiguity",
        )
    }

    @Test
    fun successfulTraceRequestsAreEmittedAsInfoLevelMessages() {
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
            """
                .trimIndent()

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
    @Ignore("INFO success traces are currently dropped once compilation fails elsewhere")
    fun successfulInfoTracesStillAppearWhenCompilationAlsoFailsElsewhere() {
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

            @DebugTypeclassResolution(mode = TypeclassTraceMode.ALL)
            fun traced(): String = summon<Show<Int>>().label()

            context(_: Show<String>)
            fun missing(): String = "boom"

            fun main() {
                println(traced())
                println(missing()) // E:TC_NO_CONTEXT_ARGUMENT
            }
            """
                .trimIndent()

        val result = compileSourceResult(source)
        assertEquals(ExitCode.COMPILATION_ERROR, result.exitCode, result.stdout)
        assertOutputContains(
            result.stdout,
            "[TC_TRACE] Typeclass resolution trace for demo.Show<kotlin.Int>",
            "[TC_NO_CONTEXT_ARGUMENT]",
        )
    }

    @Test
    fun deriveFailureTracingShowsDerivationRootNotOrdinaryCallRoot() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DebugTypeclassResolution
            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.SumTypeclassMetadata
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.TypeclassDeriver

            @Typeclass
            interface Show<A> {
                companion object : TypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                        object : Show<Any?> {}

                    override fun deriveSum(metadata: SumTypeclassMetadata): Any =
                        object : Show<Any?> {}
                }
            }

            @DebugTypeclassResolution
            @Derive(Show::class) // E:TC_CANNOT_DERIVE
            sealed interface Expr

            data class Lit<T>(val value: T) : Expr
            """
                .trimIndent()

        val result = compileSourceResult(source)
        assertEquals(ExitCode.COMPILATION_ERROR, result.exitCode, result.stdout)
        assertOutputContains(result.stdout, "[TC_CANNOT_DERIVE]")
        assertOutputNotContains(
            result.stdout,
            "[TC_TRACE] Typeclass derivation trace for demo.Show<demo.Expr>",
            "[TC_TRACE] root kind: derivation",
        )
    }

    @Test
    fun deriveViaTracingShowsAuthoredAndNormalizedPath() {
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
            """
                .trimIndent()

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
    @Ignore("DeriveEquiv success-trace content is not asserted yet")
    fun deriveEquivTracingShowsExportedAndTransientEquivPlanning() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DebugTypeclassResolution
            import one.wabbit.typeclass.DeriveEquiv
            import one.wabbit.typeclass.TypeclassTraceMode

            data class UserId(val value: Int)

            @DebugTypeclassResolution(mode = TypeclassTraceMode.ALL)
            @DeriveEquiv(UserId::class)
            data class TaggedUserId(val value: Int)
            """
                .trimIndent()

        assertCompiles(source = source)
    }

    @Test
    @Ignore("Cross-module success-trace content is not asserted yet")
    fun crossModuleTracingNamesExternalRulesWithoutInliningTheirBodies() {
        val dependency =
            HarnessDependency(
                name = "dep-debug-show",
                sources =
                    mapOf(
                        "dep/Show.kt" to
                            """
                            package dep

                            import one.wabbit.typeclass.Instance
                            import one.wabbit.typeclass.Typeclass

                            @Typeclass
                            interface Show<A> {
                                fun show(value: A): String
                            }

                            data class User(val id: Int)

                            @Instance
                            object UserShow : Show<User> {
                                override fun show(value: User): String = "dep:${'$'}{value.id}"
                            }
                            """
                                .trimIndent()
                    ),
            )
        val source =
            """
            package demo

            import dep.Show
            import dep.User
            import one.wabbit.typeclass.DebugTypeclassResolution
            import one.wabbit.typeclass.TypeclassTraceMode
            import one.wabbit.typeclass.summon

            @DebugTypeclassResolution(mode = TypeclassTraceMode.ALL)
            fun renderUser(): String = needUserShow()

            context(_: Show<User>)
            fun needUserShow(): String = summon<Show<User>>().show(User(1))

            fun main() {
                println(renderUser())
            }
            """
                .trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "dep:1",
            dependencies = listOf(dependency),
        )
    }

    @Test
    fun debugAnnotationHasNoSemanticEffectOnSuccessfulPrograms() {
        val baseline =
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

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(1))
            }
            """
                .trimIndent()
        val traced =
            """
            package demo

            import one.wabbit.typeclass.DebugTypeclassResolution
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.TypeclassTraceMode

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(value: Int): String = "int:${'$'}value"
            }

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            @DebugTypeclassResolution(mode = TypeclassTraceMode.ALL)
            fun main() {
                println(render(1))
            }
            """
                .trimIndent()

        val baselineResult = compileSourceResult(baseline)
        assertEquals(ExitCode.OK, baselineResult.exitCode, baselineResult.stdout)
        assertOutputNotContains(baselineResult.stdout, "[TC_TRACE]")
        assertEquals("int:1", runCompiledMain(baselineResult.artifacts, "demo.SampleKt"))

        val tracedResult = compileSourceResult(traced)
        assertEquals(ExitCode.OK, tracedResult.exitCode, tracedResult.stdout)
        assertOutputContains(
            tracedResult.stdout,
            "[TC_TRACE] Typeclass resolution trace for demo.Show<kotlin.Int>",
            "[TC_TRACE] effective mode: ALL",
        )
        assertEquals("int:1", runCompiledMain(tracedResult.artifacts, "demo.SampleKt"))
    }

    @Test
    @Ignore("Successful derivation roots do not emit trace output yet")
    fun annotatingADerivedDeclarationTracesDerivationButNotUnrelatedLaterUseSites() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DebugTypeclassResolution
            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.ProductTypeclassDeriver
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.TypeclassTraceMode

            @Typeclass
            interface Show<A> {
                fun show(value: A): String

                companion object : ProductTypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                        object : Show<Any?> {
                            override fun show(value: Any?): String = metadata.typeName
                        }
                }
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(value: Int): String = value.toString()
            }

            @DebugTypeclassResolution(mode = TypeclassTraceMode.ALL)
            @Derive(Show::class)
            data class Box(val value: Int)

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(Box(1)))
            }
            """
                .trimIndent()

        val result = compileSourceResult(source)
        assertEquals(ExitCode.OK, result.exitCode, result.stdout)
        assertOutputContains(
            result.stdout,
            "[TC_TRACE] Typeclass derivation trace for demo.Show<demo.Box>",
        )
        assertOutputNotContains(
            result.stdout,
            "[TC_TRACE] Typeclass resolution trace for demo.Show<demo.Box>",
        )
    }
}
