// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.typeclass.plugin.integration.interop

import one.wabbit.typeclass.plugin.integration.CompilerHarnessPlugin
import one.wabbit.typeclass.plugin.integration.IntegrationTestSupport
import kotlin.test.Test

/**
 * AtomicFU compatibility design tests.
 *
 * These focus on coexistence with the AtomicFU compiler plugin.
 */
class AtomicFuInteropTest : IntegrationTestSupport() {
    private val atomicFuPlugins = listOf(CompilerHarnessPlugin.AtomicFu)

    @Test
    fun contextualCallsCanReadAtomicfuManagedState() {
        val source =
            """
            package demo

            import kotlinx.atomicfu.atomic
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

            class Counter {
                private val value = atomic(1)

                context(show: Show<Int>)
                fun render(): String = show.show(value.value)
            }

            fun main() {
                println(Counter().render())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "int:1",
            requiredPlugins = atomicFuPlugins,
        )
    }

    @Test
    fun contextualCallsInsideAtomicfuUpdateLambdasCompile() {
        val source =
            """
            package demo

            import kotlinx.atomicfu.atomic
            import kotlinx.atomicfu.updateAndGet
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

            class Counter {
                private val value = atomic(1)

                context(show: Show<Int>)
                fun bump(): Int =
                    value.updateAndGet { current ->
                        check(show.show(current).startsWith("int:"))
                        current + 1
                    }
            }

            fun main() {
                println(Counter().bump())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "2",
            requiredPlugins = atomicFuPlugins,
        )
    }

    @Test
    fun typesContainingAtomicfuManagedStateDoNotCrashCompilerInterplay() {
        val source =
            """
            package demo

            import kotlinx.atomicfu.atomic
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

            class CounterState(initial: Int) {
                private val value = atomic(initial)

                context(show: Show<Int>)
                fun render(): String = show.show(value.value)
            }

            fun main() {
                println(CounterState(1).render())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "int:1",
            requiredPlugins = atomicFuPlugins,
        )
    }
}
