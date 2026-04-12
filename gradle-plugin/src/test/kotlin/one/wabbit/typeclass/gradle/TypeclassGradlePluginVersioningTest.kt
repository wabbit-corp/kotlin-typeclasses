// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.typeclass.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.gradle.api.DefaultTask
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Internal
import org.gradle.testfixtures.ProjectBuilder

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

    @Test
    fun `context parameters flag helper is idempotent`() {
        val project = ProjectBuilder.builder().build()
        val freeCompilerArgs = project.objects.listProperty(String::class.java)
        freeCompilerArgs.add(CONTEXT_PARAMETERS_FLAG)

        addContextParametersFlag(freeCompilerArgs, ":compileKotlin")

        assertEquals(listOf(CONTEXT_PARAMETERS_FLAG), freeCompilerArgs.get())
    }

    @Test
    fun `plugin does not patch non Kotlin tasks with compiler options shaped API`() {
        val project = ProjectBuilder.builder().build()
        val fakeTask =
            project.tasks
                .register("fakeCompilerOptionsTask", FakeCompilerOptionsTask::class.java)
                .get()

        project.pluginManager.apply(TypeclassGradlePlugin::class.java)

        assertFalse(
            fakeTask.compilerOptions.freeCompilerArgs
                .getOrElse(emptyList())
                .contains(CONTEXT_PARAMETERS_FLAG)
        )
    }

    open class FakeCompilerOptionsTask : DefaultTask() {
        @get:Internal
        val compilerOptions: FakeCompilerOptions = FakeCompilerOptions(project.objects)
    }

    class FakeCompilerOptions(objects: ObjectFactory) {
        val freeCompilerArgs: ListProperty<String> = objects.listProperty(String::class.java)
    }
}
