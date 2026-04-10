// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.typeclass.plugin.integration.interop

import kotlin.test.Test
import one.wabbit.typeclass.plugin.integration.CompilerHarnessPlugin
import one.wabbit.typeclass.plugin.integration.ExpectedDiagnostic
import one.wabbit.typeclass.plugin.integration.IntegrationTestSupport

/**
 * Power-assert compatibility design tests.
 *
 * These focus on coexistence with the power-assert compiler plugin.
 */
class PowerAssertInteropTest : IntegrationTestSupport() {
    private val powerAssertPlugins = listOf(CompilerHarnessPlugin.PowerAssert())

    @Test
    fun contextualCallsInsideAssertExpressionsCompile() {
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

            context(show: Show<Int>)
            fun renderInt(value: Int): String = show.show(value)

            fun main() {
                assert(renderInt(1) == "int:1")
            }
            """
                .trimIndent()

        assertCompiles(source, requiredPlugins = powerAssertPlugins)
    }

    @Test
    fun contextualCallsInsideNestedAssertLambdasCompile() {
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

            context(show: Show<Int>)
            fun renderInt(value: Int): String = show.show(value)

            fun main() {
                assert(listOf(1, 2).map { renderInt(it) } == listOf("int:1", "int:2"))
            }
            """
                .trimIndent()

        assertCompiles(source, requiredPlugins = powerAssertPlugins)
    }

    @Test
    fun missingTypeclassInsideAssertReportsNormalDiagnostic() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            context(show: Show<Int>)
            fun renderInt(value: Int): String = show.show(value)

            fun main() {
                assert(renderInt(1) == "int:1") // E:TC_NO_CONTEXT_ARGUMENT missing Show<Int> should be reported normally inside assert
            }
            """
                .trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    ExpectedDiagnostic.Error(file = "Sample.kt", line = 14) { message ->
                        message.contains("no context argument", ignoreCase = true) &&
                            message.contains("Show<Int>")
                    }
                ),
            requiredPlugins = powerAssertPlugins,
        )
    }
}
