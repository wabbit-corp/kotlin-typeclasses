// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.typeclass.idea

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import one.wabbit.ijplugin.common.CompilerPluginMatch as TypeclassCompilerPluginMatch
import one.wabbit.ijplugin.common.CompilerPluginScan as TypeclassCompilerPluginScan
import one.wabbit.ijplugin.common.GradleBuildFileMatch as TypeclassGradleBuildFileMatch
import one.wabbit.ijplugin.common.IdeSupportActivationState as TypeclassIdeSupportActivationState

class TypeclassCompilerPluginDetectorTest {
    @Test
    fun detectsTypeclassCompilerPluginInMavenLocalPath() {
        assertTrue(
            TypeclassCompilerPluginDetector.isCompilerPluginPath(
                "/Users/example/.m2/repository/one/wabbit/kotlin-typeclasses-plugin/0.0.1/kotlin-typeclasses-plugin-0.0.1.jar"
            )
        )
    }

    @Test
    fun detectsTypeclassCompilerPluginInBuildLibsPath() {
        assertTrue(
            TypeclassCompilerPluginDetector.isCompilerPluginPath(
                "/workspace/kotlin-typeclasses-plugin/build/libs/kotlin-typeclasses-plugin-0.0.1.jar"
            )
        )
    }

    @Test
    fun ignoresUnrelatedCompilerPluginPaths() {
        assertFalse(
            TypeclassCompilerPluginDetector.isCompilerPluginPath(
                "/workspace/kotlin-acyclic-plugin/build/libs/kotlin-acyclic-plugin-0.0.1.jar"
            )
        )
    }

    @Test
    fun matchingClasspathsDeduplicatesMatches() {
        val matches =
            TypeclassCompilerPluginDetector.matchingClasspaths(
                listOf(
                    "/workspace/kotlin-typeclasses-plugin/build/libs/kotlin-typeclasses-plugin-0.0.1.jar",
                    "/workspace/kotlin-typeclasses-plugin/build/libs/kotlin-typeclasses-plugin-0.0.1.jar",
                    "/workspace/other-plugin/build/libs/other-plugin-0.0.1.jar",
                )
            )

        assertEquals(
            listOf(
                "/workspace/kotlin-typeclasses-plugin/build/libs/kotlin-typeclasses-plugin-0.0.1.jar"
            ),
            matches,
        )
    }

    @Test
    fun detectsTypeclassGradlePluginIdInBuildScript() {
        assertTrue(
            TypeclassCompilerPluginDetector.isDirectGradlePluginReference(
                """
                plugins {
                    id("one.wabbit.typeclass")
                }
                """
                    .trimIndent()
            )
        )
    }

    @Test
    fun detectsTypeclassGradlePluginIdInSingleLinePluginsBlock() {
        assertTrue(
            TypeclassCompilerPluginDetector.isDirectGradlePluginReference(
                """plugins { id("one.wabbit.typeclass") }"""
            )
        )
    }

    @Test
    fun detectsTypeclassGradlePluginManagerApply() {
        assertTrue(
            TypeclassCompilerPluginDetector.isDirectGradlePluginReference(
                """pluginManager.apply("one.wabbit.typeclass")"""
            )
        )
    }

    @Test
    fun versionCatalogPluginIdDoesNotCountAsDirectGradleReference() {
        assertFalse(
            TypeclassCompilerPluginDetector.isDirectGradlePluginReference(
                """
                [plugins]
                typeclass = { id = "one.wabbit.typeclass", version = "0.0.1" }
                """
                    .trimIndent()
            )
        )
    }

    @Test
    fun commentedPluginIdDoesNotCountAsGradleReference() {
        assertFalse(
            TypeclassCompilerPluginDetector.isDirectGradlePluginReference(
                """
                // id("one.wabbit.typeclass")
                plugins {
                    kotlin("jvm")
                }
                """
                    .trimIndent()
            )
        )
    }

    @Test
    fun unusedLegacyArtifactCoordinateDoesNotCountAsGradleReference() {
        assertFalse(
            TypeclassCompilerPluginDetector.isDirectGradlePluginReference(
                """
                dependencies {
                    implementation("one.wabbit:kotlin-typeclasses-gradle-plugin:0.0.1")
                }
                """
                    .trimIndent()
            )
        )
    }

    @Test
    fun multilineStringContainingPluginSyntaxDoesNotCountAsGradleReference() {
        assertFalse(
            TypeclassCompilerPluginDetector.isDirectGradlePluginReference(
                """
                val docs = ${"\"\"\""}
                    id("one.wabbit.typeclass")
                ${"\"\"\""}
                plugins {
                    kotlin("jvm")
                }
                """
                    .trimIndent()
            )
        )
    }

    @Test
    fun pluginIdWithApplyFalseDoesNotCountAsGradleReference() {
        assertFalse(
            TypeclassCompilerPluginDetector.isDirectGradlePluginReference(
                """
                plugins {
                    id("one.wabbit.typeclass") apply false
                }
                """
                    .trimIndent()
            )
        )
    }

    @Test
    fun applyFalseDoesNotHideLaterSameLineApplication() {
        assertTrue(
            TypeclassCompilerPluginDetector.isDirectGradlePluginReference(
                """plugins { id("one.wabbit.typeclass") apply false; id("one.wabbit.typeclass") }"""
            )
        )
    }

    @Test
    fun matchingGradleBuildFilesFindsPluginReferencesAndSkipsExcludedDirectorySubtrees() {
        withManagedTestTempDirectory("typeclass-idea-detector-test") { projectRoot ->
            projectRoot
                .resolve("build.gradle.kts")
                .writeText(
                    """
                    plugins {
                        id("one.wabbit.typeclass")
                    }
                    """
                        .trimIndent()
                )
            projectRoot.resolve("gradle").createDirectories()
            projectRoot
                .resolve("gradle/libs.versions.toml")
                .writeText(
                    """
                    [plugins]
                    typeclass = { id = "one.wabbit.typeclass", version = "0.0.1" }
                    """
                        .trimIndent()
                )
            listOf(".git", ".gradle", ".idea", "build", "out").forEach { excludedDir ->
                projectRoot.resolve("$excludedDir/generated").createDirectories()
                projectRoot
                    .resolve("$excludedDir/generated/build.gradle.kts")
                    .writeText(
                        """
                        plugins {
                            id("one.wabbit.typeclass")
                        }
                        """
                            .trimIndent()
                    )
            }

            val matches = TypeclassCompilerPluginDetector.matchingGradleBuildFiles(projectRoot)

            assertEquals(listOf("build.gradle.kts"), matches)
        }
    }

    @Test
    fun matchingGradleBuildFilesUsesVersionCatalogAliasesOnlyWhenAppliedInBuildScript() {
        withManagedTestTempDirectory("typeclass-idea-detector-alias-test") { projectRoot ->
            projectRoot
                .resolve("build.gradle.kts")
                .writeText(
                    """
                    plugins { alias(libs.plugins.typeclass) }
                    """
                        .trimIndent()
                )
            projectRoot.resolve("gradle").createDirectories()
            projectRoot
                .resolve("gradle/libs.versions.toml")
                .writeText(
                    """
                    [plugins]
                    typeclass = { id = "one.wabbit.typeclass", version = "0.0.1" }
                    """
                        .trimIndent()
                )

            val matches = TypeclassCompilerPluginDetector.matchingGradleBuildFiles(projectRoot)

            assertEquals(listOf("build.gradle.kts"), matches)
        }
    }

    @Test
    fun matchingGradleBuildFileMatchesRetainOriginatingRootPath() {
        withManagedTestTempDirectory("typeclass-idea-detector-root-path-test") { projectRoot ->
            val appRoot = projectRoot.resolve("app")
            appRoot.createDirectories()
            appRoot
                .resolve("build.gradle.kts")
                .writeText(
                    """
                    plugins {
                        id("one.wabbit.typeclass")
                    }
                    """
                        .trimIndent()
                )

            val matches =
                TypeclassCompilerPluginDetector.matchingGradleBuildFileMatches(listOf(appRoot))

            assertEquals(
                listOf(
                    TypeclassGradleBuildFileMatch(
                        relativePath = "build.gradle.kts",
                        rootPath =
                            appRoot.toAbsolutePath().normalize().toString().replace('\\', '/'),
                    )
                ),
                matches,
            )
        }
    }

    @Test
    fun matchingGradleBuildFilesDoesNotLeakVersionCatalogAliasesAcrossSelectedRoots() {
        withManagedTestTempDirectory("typeclass-idea-detector-root-set-alias-test") { projectRoot ->
            val appRoot = projectRoot.resolve("app")
            appRoot.createDirectories()
            appRoot
                .resolve("build.gradle.kts")
                .writeText(
                    """
                    plugins {
                        alias(libs.plugins.kotlin.typeclasses)
                    }
                    """
                        .trimIndent()
                )
            projectRoot.resolve("gradle").createDirectories()
            projectRoot
                .resolve("gradle/libs.versions.toml")
                .writeText(
                    """
                    [plugins]
                    kotlin-typeclasses = { id = "one.wabbit.typeclass", version = "0.0.1" }
                    """
                        .trimIndent()
                )

            val matches =
                TypeclassCompilerPluginDetector.matchingGradleBuildFiles(
                    listOf(projectRoot, appRoot)
                )

            assertEquals(emptyList(), matches)
        }
    }

    @Test
    fun matchingGradleBuildFilesIgnoresVersionCatalogAliasesThatAreNotApplied() {
        withManagedTestTempDirectory("typeclass-idea-detector-catalog-only-test") { projectRoot ->
            projectRoot
                .resolve("build.gradle.kts")
                .writeText(
                    """
                    plugins {
                        kotlin("jvm")
                    }
                    """
                        .trimIndent()
                )
            projectRoot.resolve("gradle").createDirectories()
            projectRoot
                .resolve("gradle/libs.versions.toml")
                .writeText(
                    """
                    [plugins]
                    typeclass = { id = "one.wabbit.typeclass", version = "0.0.1" }
                    """
                        .trimIndent()
                )

            val matches = TypeclassCompilerPluginDetector.matchingGradleBuildFiles(projectRoot)

            assertEquals(emptyList(), matches)
        }
    }

    @Test
    fun matchingGradleBuildFilesIgnoresNestedFixtureBuildFiles() {
        withManagedTestTempDirectory("typeclass-idea-detector-fixture-test") { projectRoot ->
            projectRoot.resolve("docs/examples").createDirectories()
            projectRoot
                .resolve("docs/examples/build.gradle.kts")
                .writeText(
                    """
                    plugins {
                        id("one.wabbit.typeclass")
                    }
                    """
                        .trimIndent()
                )

            val matches = TypeclassCompilerPluginDetector.matchingGradleBuildFiles(projectRoot)

            assertEquals(emptyList(), matches)
        }
    }

    @Test
    fun matchingGradleBuildFilesIgnoresSettingsScriptsThatOnlyMentionPluginId() {
        withManagedTestTempDirectory("typeclass-idea-detector-settings-test") { projectRoot ->
            projectRoot
                .resolve("settings.gradle.kts")
                .writeText(
                    """
                    pluginManagement {
                        plugins {
                            id("one.wabbit.typeclass") version "0.0.1"
                        }
                    }
                    """
                        .trimIndent()
                )
            projectRoot
                .resolve("build.gradle.kts")
                .writeText(
                    """
                    plugins {
                        kotlin("jvm")
                    }
                    """
                        .trimIndent()
                )

            val matches = TypeclassCompilerPluginDetector.matchingGradleBuildFiles(projectRoot)

            assertEquals(emptyList(), matches)
        }
    }

    @Test
    fun enabledMessageListsProjectAndModuleOwners() {
        val message =
            TypeclassIdeSupportCoordinator.buildEnabledMessage(
                scan =
                    TypeclassCompilerPluginScan(
                        projectLevelMatch =
                            TypeclassCompilerPluginMatch(
                                ownerName = "demo",
                                classpaths = listOf("/tmp/kotlin-typeclasses-plugin.jar"),
                            ),
                        moduleMatches =
                            listOf(
                                TypeclassCompilerPluginMatch(
                                    ownerName = "app",
                                    classpaths = listOf("/tmp/kotlin-typeclasses-plugin.jar"),
                                )
                            ),
                        gradleBuildFiles = listOf("build.gradle.kts"),
                    ),
                registryUpdated = true,
                gradleImportRequired = false,
                gradleImportRequested = false,
            )

        assertTrue(message.contains("project settings"))
        assertTrue(message.contains("module app"))
        assertTrue(message.contains("Gradle build files"))
    }

    @Test
    fun gradleImportRequiredMessageUsesGradlePluginName() {
        val message =
            TypeclassIdeSupportCoordinator.buildGradleImportRequiredMessage(
                scan =
                    TypeclassCompilerPluginScan(
                        projectLevelMatch = null,
                        moduleMatches = emptyList(),
                        gradleBuildFiles = listOf("build.gradle.kts"),
                    ),
                activationState = TypeclassIdeSupportActivationState.ALREADY_ENABLED,
                gradleImportRequested = false,
            )

        assertTrue(message.contains("kotlin-typeclasses Gradle plugin"))
    }

    @Test
    fun enabledMessageTreatsRequiredAndRequestedGradleImportAsSeparateStates() {
        val message =
            TypeclassIdeSupportCoordinator.buildEnabledMessage(
                scan =
                    TypeclassCompilerPluginScan(
                        projectLevelMatch = null,
                        moduleMatches = emptyList(),
                        gradleBuildFiles = listOf("build.gradle.kts"),
                    ),
                registryUpdated = false,
                gradleImportRequired = true,
                gradleImportRequested = false,
            )

        assertTrue(message.contains("kotlin-typeclasses Gradle plugin"))
        assertTrue(message.contains("Reimport the Gradle project."))
        assertFalse(message.contains("Requested a Gradle import"))
    }

    @Test
    fun coordinatorUsesMatchedGradleImportPathsBeforeProjectBasePathFallback() {
        val paths =
            TypeclassIdeSupportCoordinator.gradleImportPaths(
                scan =
                    TypeclassCompilerPluginScan(
                        projectLevelMatch = null,
                        moduleMatches = emptyList(),
                        gradleBuildFiles = listOf("app/build.gradle.kts"),
                        gradleImportPaths = listOf("/repo/app"),
                    ),
                projectBasePath = "/repo",
            )

        assertEquals(listOf("/repo/app"), paths)
    }

    private inline fun <T> withManagedTestTempDirectory(
        prefix: String,
        block: (java.nio.file.Path) -> T,
    ): T {
        val projectRoot = Files.createTempDirectory(prefix)
        var succeeded = false
        try {
            val result = block(projectRoot)
            succeeded = true
            return result
        } finally {
            if (succeeded && !keepTestTempDirectories()) {
                deleteRecursivelyOrThrow(projectRoot)
            }
        }
    }

    private fun keepTestTempDirectories(): Boolean =
        System.getenv("TYPECLASS_KEEP_TEST_TEMP")?.trim()?.lowercase()?.let { value ->
            value.isNotEmpty() && value != "0" && value != "false" && value != "no"
        } ?: false

    private fun deleteRecursivelyOrThrow(path: java.nio.file.Path) {
        val file = path.toFile()
        if (!file.exists()) {
            return
        }
        check(file.deleteRecursively()) { "Failed to delete temporary test directory $path" }
    }
}
