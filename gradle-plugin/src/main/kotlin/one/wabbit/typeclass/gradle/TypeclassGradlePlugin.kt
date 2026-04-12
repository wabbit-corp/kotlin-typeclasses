// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass.gradle

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

private const val TYPECLASS_COMPILER_PLUGIN_ID = "one.wabbit.typeclass"
private const val TYPECLASS_COMPILER_PLUGIN_GROUP = "one.wabbit"
private const val TYPECLASS_COMPILER_PLUGIN_ARTIFACT = "kotlin-typeclasses-plugin"
internal const val CONTEXT_PARAMETERS_FLAG = "-Xcontext-parameters"

class TypeclassGradlePlugin : KotlinCompilerPluginSupportPlugin {
    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true

    override fun applyToCompilation(
        kotlinCompilation: KotlinCompilation<*>
    ): Provider<List<SubpluginOption>> {
        configureContextParametersFlag(kotlinCompilation)
        return kotlinCompilation.target.project.provider { emptyList() }
    }

    override fun getCompilerPluginId(): String = TYPECLASS_COMPILER_PLUGIN_ID

    override fun getPluginArtifact(): SubpluginArtifact =
        SubpluginArtifact(
            groupId = TYPECLASS_COMPILER_PLUGIN_GROUP,
            artifactId = TYPECLASS_COMPILER_PLUGIN_ARTIFACT,
            version =
                compilerPluginArtifactVersion(
                    baseVersion = TYPECLASS_GRADLE_PLUGIN_VERSION,
                    kotlinVersion = currentKotlinGradlePluginVersion(),
                ),
        )

    private fun configureContextParametersFlag(kotlinCompilation: KotlinCompilation<*>) {
        configureRequiredCompilerFlag(
            kotlinCompilation = kotlinCompilation,
            requiredCompilerFlag = CONTEXT_PARAMETERS_FLAG,
            pluginDisplayName = "kotlin-typeclasses",
        )
    }
}

internal fun addContextParametersFlag(freeCompilerArgs: ListProperty<String>, taskPath: String) {
    addRequiredCompilerFlag(
        freeCompilerArgs = freeCompilerArgs,
        requiredCompilerFlag = CONTEXT_PARAMETERS_FLAG,
        taskPath = taskPath,
        pluginDisplayName = "kotlin-typeclasses",
    )
}
