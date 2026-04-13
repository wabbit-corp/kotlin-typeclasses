// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.typeclass.plugin.integration.interop

import java.nio.file.Files
import kotlin.test.Test
import one.wabbit.typeclass.plugin.integration.CompilerHarnessPlugin
import one.wabbit.typeclass.plugin.integration.IntegrationTestSupport

class ComposeHarnessResolutionTest : IntegrationTestSupport() {
    @Test
    fun composeHarnessDoesNotRequireGradleCacheOnlyArtifacts() {
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
                check(renderInt(1) == "int:1")
            }
            """
                .trimIndent()

        withEmptyGradleCacheView {
            assertCompiles(source = source, requiredPlugins = listOf(CompilerHarnessPlugin.Compose))
        }
    }

    private fun withEmptyGradleCacheView(block: () -> Unit) {
        val originalUserHome = System.getProperty("user.home")
        val tempUserHome = Files.createTempDirectory("typeclass-compose-home-")
        try {
            clearLocatedSupportJars()
            System.setProperty("user.home", tempUserHome.toString())
            block()
        } finally {
            System.setProperty("user.home", originalUserHome)
            clearLocatedSupportJars()
            tempUserHome.toFile().deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun clearLocatedSupportJars() {
        val field = IntegrationTestSupport::class.java.getDeclaredField("locatedSupportJars")
        field.isAccessible = true
        (field.get(null) as MutableMap<String, *>).clear()
    }
}
