// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.typeclass.idea

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypeclassIdeSupportDecisionTest {
    @Test
    fun requestsGradleImportWhenOnlyGradleBuildFilesMatch() {
        val decision =
            TypeclassIdeSupportDecision.evaluate(
                scan =
                    TypeclassCompilerPluginScan(
                        projectLevelMatch = null,
                        moduleMatches = emptyList(),
                        gradleBuildFiles = listOf("build.gradle.kts"),
                    ),
                projectTrusted = true,
                registryAllowsOnlyBundledPlugins = true,
            )

        assertTrue(decision.enableExternalCompilerPlugins)
        assertTrue(decision.requestGradleImport)
        assertTrue(decision.restartAnalysis)
    }

    @Test
    fun skipsGradleImportWhenCompilerPluginClasspathIsAlreadyImported() {
        val decision =
            TypeclassIdeSupportDecision.evaluate(
                scan =
                    TypeclassCompilerPluginScan(
                        projectLevelMatch =
                            TypeclassCompilerPluginMatch(
                                ownerName = "demo",
                                classpaths = listOf("/tmp/kotlin-typeclasses-plugin.jar"),
                            ),
                        moduleMatches = emptyList(),
                        gradleBuildFiles = listOf("build.gradle.kts"),
                    ),
                projectTrusted = true,
                registryAllowsOnlyBundledPlugins = false,
            )

        assertFalse(decision.enableExternalCompilerPlugins)
        assertFalse(decision.requestGradleImport)
        assertTrue(decision.restartAnalysis)
    }

    @Test
    fun enabledMessageMentionsRequestedGradleImport() {
        val message =
            TypeclassIdeSupportCoordinator.buildEnabledMessage(
                scan =
                    TypeclassCompilerPluginScan(
                        projectLevelMatch = null,
                        moduleMatches = emptyList(),
                        gradleBuildFiles = listOf("build.gradle.kts"),
                    ),
                registryUpdated = true,
                gradleImportRequested = true,
            )

        assertTrue(message.contains("Requested a Gradle import"))
    }

    @Test
    fun enabledMessageMentionsManualGradleImportWhenAutomaticRefreshWasNotRequested() {
        val message =
            TypeclassIdeSupportCoordinator.buildEnabledMessage(
                scan =
                    TypeclassCompilerPluginScan(
                        projectLevelMatch = null,
                        moduleMatches = emptyList(),
                        gradleBuildFiles = listOf("build.gradle.kts"),
                    ),
                registryUpdated = false,
                gradleImportRequested = false,
            )

        assertTrue(message.contains("Reimport the Gradle project"))
    }
}
