// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.typeclass.plugin.integration

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.streams.asStream
import kotlin.test.Test
import kotlin.test.assertTrue

class DocumentationConsistencyTest : IntegrationTestSupport() {
    @Test
    fun readmeQuickStartSourceCompilesAndRuns() {
        val source = readMarkedKotlinBlock("README.md", "quickstart-source")

        assertCompilesAndRuns(
            sources = mapOf("Main.kt" to source),
            expectedStdout = "(1, 2)",
            mainClass = "demo.MainKt",
        )
    }

    @Test
    fun derivationGuideExampleCompilesAndRuns() {
        val source = readMarkedKotlinBlock("docs/derivation.md", "derive-example")

        assertCompilesAndRuns(
            sources = mapOf("DerivationExample.kt" to source),
            expectedStdout =
                """
                Box(value=1)
                Some(value=1)
                None()
                Warm
                """
                    .trimIndent(),
            mainClass = "demo.DerivationExampleKt",
        )
    }

    @Test
    fun readmeLocalLinksPointAtExistingFiles() {
        val root = repositoryRoot()
        val readme = root.resolve("README.md")
        val offenders =
            markdownLinks(readme.readText()).mapIndexedNotNull { index, link ->
                val localTarget = localMarkdownTarget(link) ?: return@mapIndexedNotNull null
                val target =
                    if (localTarget.isAbsolute) {
                        localTarget
                    } else {
                        readme.parent.resolve(localTarget).normalize()
                    }
                if (Files.exists(target)) {
                    null
                } else {
                    "README.md:${index + 1}: ${link.raw}"
                }
            }

        assertTrue(offenders.isEmpty(), offenders.joinToString(separator = "\n"))
    }

    @Test
    fun markdownUsesActualGradleProjectPaths() {
        val root = repositoryRoot()
        val forbiddenProjectPaths = listOf(":compiler-plugin", ":gradle-plugin", ":ij-plugin")
        val offenders =
            Files.walk(root).use { paths ->
                paths
                    .filter { path ->
                        Files.isRegularFile(path) &&
                            path.fileName.toString().endsWith(".md") &&
                            !path.toString().contains("${Path.of("build")}") &&
                            !path.toString().contains("${Path.of(".gradle")}")
                    }
                    .flatMap { path ->
                        path
                            .readText()
                            .lineSequence()
                            .mapIndexedNotNull { index, line ->
                                val forbidden = forbiddenProjectPaths.firstOrNull { it in line }
                                if (forbidden == null) {
                                    null
                                } else {
                                    "${root.relativize(path)}:${index + 1}: $forbidden"
                                }
                            }
                            .asStream()
                    }
                    .toList()
            }

        assertTrue(offenders.isEmpty(), offenders.joinToString(separator = "\n"))
    }

    private fun readMarkedKotlinBlock(relativePath: String, marker: String): String {
        val file = repositoryRoot().resolve(relativePath)
        val match =
            Regex(
                    pattern =
                        """<!-- ${Regex.escape(marker)}:start -->\s*```kotlin\s*(.*?)\s*```\s*<!-- ${Regex.escape(marker)}:end -->""",
                    options = setOf(RegexOption.DOT_MATCHES_ALL),
                )
                .find(file.readText())
                ?: error("$relativePath is missing the $marker Kotlin block marker")
        return match.groupValues[1].trim() + "\n"
    }

    private data class MarkdownLink(val raw: String, val destination: String)

    private fun markdownLinks(markdown: String): List<MarkdownLink> =
        Regex("""\[[^\]]+]\(([^)]+)\)""")
            .findAll(markdown)
            .map { match -> MarkdownLink(raw = match.value, destination = match.groupValues[1]) }
            .toList()

    private fun localMarkdownTarget(link: MarkdownLink): Path? {
        val destination = link.destination.substringBefore('#').trim()
        if (
            destination.isEmpty() ||
                destination.startsWith("http://") ||
                destination.startsWith("https://") ||
                destination.startsWith("mailto:")
        ) {
            return null
        }
        return Path.of(destination)
    }

    private fun repositoryRoot(): Path {
        var current: Path? = Path.of("").toAbsolutePath()
        while (current != null) {
            if (
                Files.isRegularFile(current.resolve("settings.gradle.kts")) &&
                    Files.isRegularFile(current.resolve("README.md"))
            ) {
                return current
            }
            current = current.parent
        }
        error("Could not locate repository root from ${Path.of("").toAbsolutePath()}")
    }
}
