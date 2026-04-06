// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.typeclass.plugin.integration.interop

import one.wabbit.typeclass.plugin.integration.CompilerHarnessPlugin
import one.wabbit.typeclass.plugin.integration.ExpectedDiagnostic
import one.wabbit.typeclass.plugin.integration.IntegrationTestSupport
import kotlin.test.Test

/**
 * Compose compiler compatibility design tests.
 *
 * These are intentionally compile-oriented. The main risk is whether our plugin's call rewriting
 * coexists with Compose's hidden parameter/default/lambda lowering, not whether a UI renders.
 */
class ComposeInteropTest : IntegrationTestSupport() {
    private val composePlugins = listOf(CompilerHarnessPlugin.Compose)

    @Test
    fun composableBodiesCanUseTypeclassResolution() {
        val source =
            """
            package demo

            import androidx.compose.runtime.Composable
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

            context(show: Show<Int>)
            fun renderInt(value: Int): String = show.show(value)

            @Composable
            fun Screen() {
                val rendered = renderInt(1)
                check(rendered == "int:1")
            }
            """.trimIndent()

        assertCompiles(
            source = source,
            requiredPlugins = composePlugins,
        )
    }

    @Test
    fun composableLambdaBodiesCanUseTypeclassResolution() {
        val source =
            """
            package demo

            import androidx.compose.runtime.Composable
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

            context(show: Show<Int>)
            fun renderInt(value: Int): String = show.show(value)

            @Composable
            fun Host(content: @Composable () -> Unit) {
                content()
            }

            @Composable
            fun Screen() {
                Host {
                    val rendered = renderInt(1)
                    check(rendered == "int:1")
                }
            }
            """.trimIndent()

        assertCompiles(
            source = source,
            requiredPlugins = composePlugins,
        )
    }

    @Test
    fun composableCallsContextualHelpersWithDefaultArguments() {
        val source =
            """
            package demo

            import androidx.compose.runtime.Composable
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

            context(show: Show<Int>)
            fun renderInt(prefix: String = "value="): String = prefix + show.show(1)

            @Composable
            fun Screen() {
                val rendered = renderInt()
                check(rendered == "value=int:1")
            }
            """.trimIndent()

        assertCompiles(
            source = source,
            requiredPlugins = composePlugins,
        )
    }

    @Test
    fun composableDefaultParameterExpressionsCanUseTypeclassResolution() {
        val source =
            """
            package demo

            import androidx.compose.runtime.Composable
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

            context(show: Show<Int>)
            fun renderInt(value: Int): String = show.show(value)

            @Composable
            fun Label(text: String = renderInt(1)) {
                check(text == "int:1")
            }

            @Composable
            fun Screen() {
                Label()
            }
            """.trimIndent()

        assertCompiles(
            source = source,
            requiredPlugins = composePlugins,
        )
    }

    @Test
    fun rememberLambdasCanCallContextualHelpers() {
        val source =
            """
            package demo

            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.remember
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

            context(show: Show<Int>)
            fun renderInt(value: Int): String = show.show(value)

            @Composable
            fun Screen() {
                val rendered = remember { renderInt(1) }
                check(rendered == "int:1")
            }
            """.trimIndent()

        assertCompiles(
            source = source,
            requiredPlugins = composePlugins,
        )
    }

    @Test
    fun rememberedComposableLambdasCanCallContextualHelpers() {
        val source =
            """
            package demo

            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.remember
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

            context(show: Show<Int>)
            fun renderInt(value: Int): String = show.show(value)

            @Composable
            fun Screen() {
                val formatter = remember(1) { { value: Int -> renderInt(value) } }
                check(formatter(2) == "int:2")
            }
            """.trimIndent()

        assertCompiles(
            source = source,
            requiredPlugins = composePlugins,
        )
    }

    @Test
    fun effectScopesCanCallContextualHelpers() {
        val source =
            """
            package demo

            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.DisposableEffect
            import androidx.compose.runtime.LaunchedEffect
            import androidx.compose.runtime.SideEffect
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

            context(show: Show<Int>)
            fun renderInt(value: Int): String = show.show(value)

            @Composable
            fun Screen() {
                SideEffect {
                    check(renderInt(1) == "int:1")
                }

                DisposableEffect(Unit) {
                    check(renderInt(2) == "int:2")
                    onDispose {
                        check(renderInt(3) == "int:3")
                    }
                }

                LaunchedEffect(Unit) {
                    check(renderInt(4) == "int:4")
                }
            }
            """.trimIndent()

        assertCompiles(
            source = source,
            requiredPlugins = composePlugins,
        )
    }

    @Test
    fun memberComposableFunctionsCanRewriteContextualCalls() {
        val source =
            """
            package demo

            import androidx.compose.runtime.Composable
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

            context(show: Show<Int>)
            fun renderInt(value: Int): String = show.show(value)

            class Presenter {
                @Composable
                fun Screen() {
                    val rendered = renderInt(1)
                    check(rendered == "int:1")
                }
            }
            """.trimIndent()

        assertCompiles(
            source = source,
            requiredPlugins = composePlugins,
        )
    }

    @Test
    fun composableExtensionFunctionsCanUseTypeclassResolution() {
        val source =
            """
            package demo

            import androidx.compose.runtime.Composable
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

            context(show: Show<Int>)
            @Composable
            fun Int.Render() {
                val rendered = show.show(this)
                check(rendered == "int:1")
            }

            @Composable
            fun Screen() {
                1.Render()
            }
            """.trimIndent()

        assertCompiles(
            source = source,
            requiredPlugins = composePlugins,
        )
    }

    @Test
    fun missingTypeclassInsideComposableReportsNormalDiagnostic() {
        val source =
            """
            package demo

            import androidx.compose.runtime.Composable
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            context(show: Show<Int>)
            fun renderInt(value: Int): String = show.show(value)

            @Composable
            fun Screen() {
                renderInt(1) // E:TC_NO_CONTEXT_ARGUMENT missing Show<Int> should be reported normally inside composable bodies
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    ExpectedDiagnostic.Error(
                        file = "Sample.kt",
                        line = 16,
                        messageRegex = "(?i)no context argument.*show: Show<Int>.*found",
                    ),
                ),
            requiredPlugins = composePlugins,
        )
    }
}
