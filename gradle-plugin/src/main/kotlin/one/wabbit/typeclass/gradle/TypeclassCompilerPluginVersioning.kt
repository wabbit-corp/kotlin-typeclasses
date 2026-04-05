package one.wabbit.typeclass.gradle

import org.gradle.api.logging.Logging
import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion

private const val SNAPSHOT_VERSION_SUFFIX = "+dev-SNAPSHOT"

internal fun compilerPluginArtifactVersion(baseVersion: String, kotlinVersion: String): String =
    if (baseVersion.endsWith(SNAPSHOT_VERSION_SUFFIX)) {
        "${baseVersion.removeSuffix(SNAPSHOT_VERSION_SUFFIX)}-kotlin-$kotlinVersion$SNAPSHOT_VERSION_SUFFIX"
    } else {
        "$baseVersion-kotlin-$kotlinVersion"
    }

internal fun currentKotlinGradlePluginVersion(): String =
    getKotlinPluginVersion(Logging.getLogger(TypeclassGradlePlugin::class.java))
