// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.typeclass.gradle

import kotlin.test.Test
import kotlin.test.assertEquals

class TypeclassGradlePluginVersioningTest {
    @Test
    fun `plugin exposes the expected compiler plugin metadata`() {
        val plugin = TypeclassGradlePlugin()

        assertEquals("one.wabbit.typeclass", plugin.getCompilerPluginId())

        val artifact = plugin.getPluginArtifact()
        assertEquals("one.wabbit", artifact.groupId)
        assertEquals("kotlin-typeclasses-plugin", artifact.artifactId)
        assertEquals(
            compilerPluginArtifactVersion(
                baseVersion = TYPECLASS_GRADLE_PLUGIN_VERSION,
                kotlinVersion = currentKotlinGradlePluginVersion(),
            ),
            artifact.version,
        )
    }

    @Test
    fun `compiler plugin artifact version appends the Kotlin version for releases`() {
        assertEquals(
            "1.2.3-kotlin-2.4.0",
            compilerPluginArtifactVersion(baseVersion = "1.2.3", kotlinVersion = "2.4.0"),
        )
    }

    @Test
    fun `compiler plugin artifact version keeps snapshot suffix at the end`() {
        assertEquals(
            "1.2.3-kotlin-2.4.0+dev-SNAPSHOT",
            compilerPluginArtifactVersion(
                baseVersion = "1.2.3+dev-SNAPSHOT",
                kotlinVersion = "2.4.0",
            ),
        )
    }
}
