// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass.gradle

import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.provider.ListProperty
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion

private const val SNAPSHOT_VERSION_SUFFIX = "+dev-SNAPSHOT"

internal fun compilerPluginArtifactVersion(baseVersion: String, kotlinVersion: String): String =
    if (baseVersion.endsWith(SNAPSHOT_VERSION_SUFFIX)) {
        "${baseVersion.removeSuffix(SNAPSHOT_VERSION_SUFFIX)}-kotlin-$kotlinVersion$SNAPSHOT_VERSION_SUFFIX"
    } else {
        "$baseVersion-kotlin-$kotlinVersion"
    }

internal fun currentKotlinGradlePluginVersion(): String =
    currentKotlinGradlePluginVersion(Logging.getLogger(TypeclassGradlePlugin::class.java))

internal fun currentKotlinGradlePluginVersion(logger: Logger): String = getKotlinPluginVersion(logger)

internal fun configureRequiredCompilerFlag(
    kotlinCompilation: KotlinCompilation<*>,
    requiredCompilerFlag: String,
    pluginDisplayName: String,
) {
    val compileTaskProvider =
        runCatching { kotlinCompilation.compileTaskProvider }
            .getOrElse { error ->
                throw GradleException(
                    "Could not locate the Kotlin compile task for ${kotlinCompilation.target.project.path}:${kotlinCompilation.compilationName}; $pluginDisplayName requires $requiredCompilerFlag.",
                    error,
                )
            }
    compileTaskProvider.configure { task ->
        addRequiredCompilerFlag(
            freeCompilerArgs =
                runCatching { task.compilerOptions.freeCompilerArgs }
                    .getOrElse { error ->
                        throw GradleException(
                            "Could not access Kotlin compiler options for ${task.path}; $pluginDisplayName requires $requiredCompilerFlag.",
                            error,
                        )
                    },
            requiredCompilerFlag = requiredCompilerFlag,
            taskPath = task.path,
            pluginDisplayName = pluginDisplayName,
        )
    }
}

internal fun addRequiredCompilerFlag(
    freeCompilerArgs: ListProperty<String>,
    requiredCompilerFlag: String,
    taskPath: String,
    pluginDisplayName: String,
) {
    runCatching {
            if (requiredCompilerFlag !in freeCompilerArgs.getOrElse(emptyList())) {
                freeCompilerArgs.add(requiredCompilerFlag)
            }
        }
        .getOrElse { error ->
            throw GradleException(
                "Could not add $requiredCompilerFlag to Kotlin compilation task $taskPath for $pluginDisplayName.",
                error,
            )
        }
}
