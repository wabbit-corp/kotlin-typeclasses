// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass.idea

import one.wabbit.ijplugin.common.CompilerPluginIdeSupportDescriptor
import one.wabbit.ijplugin.common.ConfiguredCompilerPluginDetectorSupport
import one.wabbit.ijplugin.common.ConfiguredCompilerPluginIdeSupport

internal const val TYPECLASSES_COMPILER_PLUGIN_MARKER = "kotlin-typeclasses-plugin"
internal const val TYPECLASSES_GRADLE_PLUGIN_ID = "one.wabbit.typeclass"

internal val TYPECLASS_PLUGIN_SUPPORT =
    ConfiguredCompilerPluginIdeSupport(
        descriptor =
            CompilerPluginIdeSupportDescriptor(
                loggerCategory = TypeclassIdeSupportCoordinator::class.java,
                notificationGroupId = "TypeclassIdeSupport",
                supportDisplayName = "Typeclass",
                supportDisplayNameLowercase = "typeclass",
                compilerPluginMarker = TYPECLASSES_COMPILER_PLUGIN_MARKER,
                compilerPluginDisplayName = "kotlin-typeclasses-plugin",
                gradlePluginId = TYPECLASSES_GRADLE_PLUGIN_ID,
                externalPluginDisplayName = "kotlin-typeclasses",
                analysisRestartReason = "Typeclass IDE support activation",
                enablementLogMessage = { project ->
                    "Temporarily enabling non-bundled K2 compiler plugins for project ${project.name}"
                },
                gradleImportDetectedName = "kotlin-typeclasses Gradle plugin",
            )
    )

internal object TypeclassCompilerPluginDetector :
    ConfiguredCompilerPluginDetectorSupport(TYPECLASS_PLUGIN_SUPPORT)
