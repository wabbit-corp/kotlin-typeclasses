// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.typeclass.idea

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypeclassCompilerPluginDetectorTest {
    @Test
    fun detectsTypeclassCompilerPluginInMavenLocalPath() {
        assertTrue(
            TypeclassCompilerPluginDetector.isTypeclassesCompilerPluginPath(
                "/Users/example/.m2/repository/one/wabbit/kotlin-typeclasses-plugin/0.0.1/kotlin-typeclasses-plugin-0.0.1.jar",
            ),
        )
    }

    @Test
    fun detectsTypeclassCompilerPluginInBuildLibsPath() {
        assertTrue(
            TypeclassCompilerPluginDetector.isTypeclassesCompilerPluginPath(
                "/workspace/kotlin-typeclasses-plugin/build/libs/kotlin-typeclasses-plugin-0.0.1.jar",
            ),
        )
    }

    @Test
    fun ignoresUnrelatedCompilerPluginPaths() {
        assertFalse(
            TypeclassCompilerPluginDetector.isTypeclassesCompilerPluginPath(
                "/workspace/kotlin-acyclic-plugin/build/libs/kotlin-acyclic-plugin-0.0.1.jar",
            ),
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
                ),
            )

        assertEquals(
            listOf("/workspace/kotlin-typeclasses-plugin/build/libs/kotlin-typeclasses-plugin-0.0.1.jar"),
            matches,
        )
    }

    @Test
    fun detectsTypeclassGradlePluginIdInBuildScript() {
        assertTrue(
            TypeclassCompilerPluginDetector.isTypeclassesGradlePluginReference(
                """
                plugins {
                    id("one.wabbit.typeclass")
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun detectsTypeclassGradlePluginIdInVersionCatalog() {
        assertTrue(
            TypeclassCompilerPluginDetector.isTypeclassesGradlePluginReference(
                """
                [plugins]
                typeclass = { id = "one.wabbit.typeclass", version = "0.0.1" }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun matchingGradleBuildFilesFindsPluginReferencesAndIgnoresBuildOutputs() {
        withManagedTestTempDirectory("typeclass-idea-detector-test") { projectRoot ->
            projectRoot.resolve("build.gradle.kts").writeText(
                """
                plugins {
                    id("one.wabbit.typeclass")
                }
                """.trimIndent(),
            )
            projectRoot.resolve("gradle").createDirectories()
            projectRoot.resolve("gradle/libs.versions.toml").writeText(
                """
                [plugins]
                typeclass = { id = "one.wabbit.typeclass", version = "0.0.1" }
                """.trimIndent(),
            )
            projectRoot.resolve("build/generated").createDirectories()
            projectRoot.resolve("build/generated/build.gradle.kts").writeText(
                """
                plugins {
                    id("one.wabbit.typeclass")
                }
                """.trimIndent(),
            )

            val matches = TypeclassCompilerPluginDetector.matchingGradleBuildFiles(projectRoot)

            assertEquals(
                listOf("build.gradle.kts", "gradle/libs.versions.toml"),
                matches,
            )
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
                                ),
                            ),
                        gradleBuildFiles = listOf("build.gradle.kts"),
                ),
                registryUpdated = true,
                gradleImportRequested = false,
            )

        assertTrue(message.contains("project settings"))
        assertTrue(message.contains("module app"))
        assertTrue(message.contains("Gradle build files"))
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
        System.getenv("TYPECLASS_KEEP_TEST_TEMP")
            ?.trim()
            ?.lowercase()
            ?.let { value -> value.isNotEmpty() && value != "0" && value != "false" && value != "no" }
            ?: false

    private fun deleteRecursivelyOrThrow(path: java.nio.file.Path) {
        val file = path.toFile()
        if (!file.exists()) {
            return
        }
        check(file.deleteRecursively()) {
            "Failed to delete temporary test directory $path"
        }
    }
}
