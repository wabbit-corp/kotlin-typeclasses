// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass.idea

import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCommonCompilerArgumentsHolder
import org.jetbrains.kotlin.idea.facet.KotlinFacet

internal const val EXTERNAL_K2_COMPILER_PLUGINS_REGISTRY_KEY =
    "kotlin.k2.only.bundled.compiler.plugins.enabled"
internal const val TYPECLASSES_COMPILER_PLUGIN_MARKER = "kotlin-typeclasses-plugin"
internal const val TYPECLASSES_GRADLE_PLUGIN_ID = "one.wabbit.typeclass"
private const val TYPECLASSES_GRADLE_PLUGIN_ARTIFACT_MARKER = "kotlin-typeclasses-gradle-plugin"
private const val MAX_GRADLE_BUILD_SCAN_DEPTH = 6

internal data class TypeclassCompilerPluginMatch(
    val ownerName: String,
    val classpaths: List<String>,
)

internal data class TypeclassCompilerPluginScan(
    val projectLevelMatch: TypeclassCompilerPluginMatch?,
    val moduleMatches: List<TypeclassCompilerPluginMatch>,
    val gradleBuildFiles: List<String>,
) {
    val hasImportedCompilerPluginMatches: Boolean
        get() = projectLevelMatch != null || moduleMatches.isNotEmpty()

    val hasMatches: Boolean
        get() = hasImportedCompilerPluginMatches || gradleBuildFiles.isNotEmpty()

    val requiresGradleImport: Boolean
        get() = gradleBuildFiles.isNotEmpty() && !hasImportedCompilerPluginMatches
}

internal object TypeclassCompilerPluginDetector {
    fun scan(project: Project): TypeclassCompilerPluginScan {
        val gradleBuildFiles =
            project.basePath
                ?.let { basePath -> matchingGradleBuildFiles(Path.of(basePath)) }
                .orEmpty()
        val projectClasspaths =
            matchingClasspaths(
                KotlinCommonCompilerArgumentsHolder.getInstance(project)
                    .settings
                    .pluginClasspaths
                    .orEmpty()
                    .asList()
            )
        val projectMatch =
            projectClasspaths
                .takeIf { it.isNotEmpty() }
                ?.let { classpaths ->
                    TypeclassCompilerPluginMatch(ownerName = project.name, classpaths = classpaths)
                }
        val moduleMatches =
            ModuleManager.getInstance(project).modules.mapNotNull { module -> scanModule(module) }
        return TypeclassCompilerPluginScan(
            projectLevelMatch = projectMatch,
            moduleMatches = moduleMatches,
            gradleBuildFiles = gradleBuildFiles,
        )
    }

    fun matchingClasspaths(classpaths: Iterable<String>): List<String> =
        classpaths.filter(::isTypeclassesCompilerPluginPath).distinct()

    fun isTypeclassesCompilerPluginPath(classpath: String): Boolean {
        val normalized = classpath.replace('\\', '/').lowercase()
        return normalized.contains(TYPECLASSES_COMPILER_PLUGIN_MARKER)
    }

    fun matchingGradleBuildFiles(projectRoot: Path): List<String> {
        if (!Files.isDirectory(projectRoot)) {
            return emptyList()
        }
        Files.walk(projectRoot, MAX_GRADLE_BUILD_SCAN_DEPTH).use { paths ->
            return paths
                .filter(Files::isRegularFile)
                .filter(::isGradleBuildFileCandidate)
                .map { path -> projectRoot.relativize(path).normalize() }
                .filter { relativePath ->
                    runCatching {
                            isTypeclassesGradlePluginReference(
                                Files.readString(projectRoot.resolve(relativePath))
                            )
                        }
                        .getOrDefault(false)
                }
                .map { relativePath -> relativePath.toString().replace('\\', '/') }
                .distinct()
                .sorted()
                .collect(Collectors.toList())
        }
    }

    fun isTypeclassesGradlePluginReference(content: String): Boolean {
        val normalized = content.lowercase()
        return normalized.contains(TYPECLASSES_GRADLE_PLUGIN_ID) ||
            normalized.contains(TYPECLASSES_GRADLE_PLUGIN_ARTIFACT_MARKER)
    }

    private fun scanModule(module: Module): TypeclassCompilerPluginMatch? {
        val facet = KotlinFacet.Companion.get(module) ?: return null
        val classpaths =
            matchingClasspaths(
                facet.configuration.settings.mergedCompilerArguments
                    ?.pluginClasspaths
                    .orEmpty()
                    .asList()
            )
        if (classpaths.isEmpty()) {
            return null
        }
        return TypeclassCompilerPluginMatch(ownerName = module.name, classpaths = classpaths)
    }

    private fun isGradleBuildFileCandidate(path: Path): Boolean {
        if (
            path.any { segment ->
                segment.toString() in setOf(".git", ".gradle", ".idea", "build", "out")
            }
        ) {
            return false
        }
        val fileName = path.fileName?.toString() ?: return false
        return fileName.endsWith(".gradle") ||
            fileName.endsWith(".gradle.kts") ||
            fileName.endsWith(".versions.toml")
    }
}
