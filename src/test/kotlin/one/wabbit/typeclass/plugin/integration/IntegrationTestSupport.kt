package one.wabbit.typeclass.plugin.integration

import one.wabbit.typeclass.plugin.TYPECLASS_PLUGIN_ID
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.isRegularFile

abstract class IntegrationTestSupport {
    private companion object {
        private const val DEFAULT_HARNESS_JVM_TARGET = "1.8"
        private val locatedSupportJars = ConcurrentHashMap<String, Path>()
        private val locatedDiagnosticRegex = Regex("""^(.+):(\d+):(\d+):\s+(error|warning):\s+(.*)$""")
        private val globalDiagnosticRegex = Regex("""^(e|w|error|warning):\s+(.*)$""")

        private fun maxJvmTarget(left: String, right: String): String =
            if (normalizeJvmTarget(left) >= normalizeJvmTarget(right)) {
                left
            } else {
                right
            }

        private fun normalizeJvmTarget(value: String): Int =
            value.removePrefix("1.").toInt()
    }

    protected fun assertCompiles(
        source: String,
        requiredPlugins: List<CompilerHarnessPlugin> = emptyList(),
        pluginOptions: List<String> = emptyList(),
        dependencies: List<HarnessDependency> = emptyList(),
    ) {
        compileSource(
            source = source,
            requiredPlugins = requiredPlugins,
            pluginOptions = pluginOptions,
            dependencies = dependencies,
        )
    }

    protected fun assertDoesNotCompile(
        source: String,
        expectedMessages: List<String>,
        expectedDiagnostics: List<ExpectedDiagnostic> = emptyList(),
        unexpectedMessages: List<String> = emptyList(),
        requiredPlugins: List<CompilerHarnessPlugin> = emptyList(),
        pluginOptions: List<String> = emptyList(),
        dependencies: List<HarnessDependency> = emptyList(),
    ) {
        assertDoesNotCompile(
            sources = mapOf("Sample.kt" to source),
            expectedMessages = expectedMessages,
            expectedDiagnostics = expectedDiagnostics,
            unexpectedMessages = unexpectedMessages,
            requiredPlugins = requiredPlugins,
            pluginOptions = pluginOptions,
            dependencies = dependencies,
        )
    }

    protected fun assertDoesNotCompile(
        sources: Map<String, String>,
        expectedMessages: List<String>,
        expectedDiagnostics: List<ExpectedDiagnostic> = emptyList(),
        unexpectedMessages: List<String> = emptyList(),
        requiredPlugins: List<CompilerHarnessPlugin> = emptyList(),
        pluginOptions: List<String> = emptyList(),
        dependencies: List<HarnessDependency> = emptyList(),
    ) {
        val result = compileSourceInternal(sources, requiredPlugins, pluginOptions, dependencies)
        assertEquals(
            ExitCode.COMPILATION_ERROR,
            result.exitCode,
            result.stdout,
        )
        val lowercaseOutput = result.stdout.lowercase()
        val diagnostics = parseCompilerDiagnostics(result.stdout)
        expectedDiagnostics.forEach { expectedDiagnostic ->
            assertTrue(
                diagnostics.any(expectedDiagnostic::matches),
                buildString {
                    appendLine("Expected diagnostic not found: $expectedDiagnostic")
                    appendLine("Parsed diagnostics:")
                    appendLine(formatDiagnostics(diagnostics))
                    appendLine("Full compiler output:")
                    append(result.stdout)
                },
            )
        }
        expectedMessages.forEach { expectedMessage ->
            assertTrue(lowercaseOutput.contains(expectedMessage.lowercase()), result.stdout)
        }
        unexpectedMessages.forEach { unexpectedMessage ->
            assertTrue(!lowercaseOutput.contains(unexpectedMessage.lowercase()), result.stdout)
        }
    }

    protected fun assertCompilesAndRuns(
        source: String,
        expectedStdout: String,
        mainClass: String = "demo.SampleKt",
        requiredPlugins: List<CompilerHarnessPlugin> = emptyList(),
        pluginOptions: List<String> = emptyList(),
        dependencies: List<HarnessDependency> = emptyList(),
    ) {
        assertCompilesAndRuns(
            sources = mapOf("Sample.kt" to source),
            expectedStdout = expectedStdout,
            mainClass = mainClass,
            requiredPlugins = requiredPlugins,
            pluginOptions = pluginOptions,
            dependencies = dependencies,
        )
    }

    protected fun assertCompilesAndRuns(
        sources: Map<String, String>,
        expectedStdout: String,
        mainClass: String,
        requiredPlugins: List<CompilerHarnessPlugin> = emptyList(),
        pluginOptions: List<String> = emptyList(),
        dependencies: List<HarnessDependency> = emptyList(),
    ) {
        val artifacts = compileSource(sources, requiredPlugins, pluginOptions, dependencies)
        val javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toAbsolutePath().toString()
        val process =
            ProcessBuilder(
                javaExecutable,
                "-cp",
                (listOf(artifacts.outputDir) + artifacts.runtimeClasspathEntries)
                    .joinToString(separator = java.io.File.pathSeparator) { path -> path.toAbsolutePath().toString() },
                mainClass,
            ).redirectErrorStream(true)
                .start()
        val stdout = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        val exitCode = process.waitFor()
        assertEquals(0, exitCode, stdout)
        assertEquals(expectedStdout.trim(), stdout.trim())
    }

    protected fun compileSource(
        sources: Map<String, String>,
        requiredPlugins: List<CompilerHarnessPlugin> = emptyList(),
        pluginOptions: List<String> = emptyList(),
        dependencies: List<HarnessDependency> = emptyList(),
    ): CompilationArtifacts {
        val result = compileSourceInternal(sources, requiredPlugins, pluginOptions, dependencies)
        assertEquals(
            ExitCode.OK,
            result.exitCode,
            result.stdout,
        )
        return result.artifacts
    }

    protected fun compileSource(
        source: String,
        requiredPlugins: List<CompilerHarnessPlugin> = emptyList(),
        pluginOptions: List<String> = emptyList(),
        dependencies: List<HarnessDependency> = emptyList(),
    ): CompilationArtifacts {
        return compileSource(
            sources = mapOf("Sample.kt" to source),
            requiredPlugins = requiredPlugins,
            pluginOptions = pluginOptions,
            dependencies = dependencies,
        )
    }

    protected fun compileSourceInternal(
        source: String,
        requiredPlugins: List<CompilerHarnessPlugin> = emptyList(),
        pluginOptions: List<String> = emptyList(),
        dependencies: List<HarnessDependency> = emptyList(),
    ): CompilationResult {
        return compileSourceInternal(
            sources = mapOf("Sample.kt" to source),
            requiredPlugins = requiredPlugins,
            pluginOptions = pluginOptions,
            dependencies = dependencies,
        )
    }

    protected fun compileSourceInternal(
        sources: Map<String, String>,
        requiredPlugins: List<CompilerHarnessPlugin> = emptyList(),
        pluginOptions: List<String> = emptyList(),
        dependencies: List<HarnessDependency> = emptyList(),
    ): CompilationResult {
        val dependencyArtifacts =
            dependencies.map { dependency ->
                val dependencyResult =
                    compileSourceInternal(
                        sources = dependency.sources,
                        requiredPlugins = dependency.requiredPlugins,
                        pluginOptions = dependency.pluginOptions,
                        dependencies = dependency.dependencies,
                    )
                assertEquals(
                    ExitCode.OK,
                    dependencyResult.exitCode,
                    dependencyResult.stdout,
                )
                dependencyResult.artifacts
            }
        val workingDir = createTempDirectory(prefix = "typeclass-compile-")
        val outputDir = workingDir.resolve("out")
        outputDir.createDirectories()
        val supportSources =
            buildMap<String, String> {
                requiredPlugins.forEach { plugin ->
                    putAll(plugin.supportSources)
                }
            }
        val effectiveSources = supportSources + sources
        val sourceFiles =
            effectiveSources.map { (relativePath, contents) ->
                workingDir.resolve(relativePath).also { sourceFile ->
                    sourceFile.parent?.createDirectories()
                    sourceFile.writeText(contents)
                }
            }

        val pluginProjectRoot = locateProjectRoot()
        val runtimeProjectRoot = pluginProjectRoot.parent.resolve("kotlin-typeclasses")
        val pluginJar =
            locateBuiltJar(pluginProjectRoot.resolve("build/libs"), "kotlin-typeclasses-plugin")
                .let { builtJar ->
                    val isolatedPluginJar = workingDir.resolve("plugin").resolve(builtJar.fileName.toString())
                    isolatedPluginJar.parent.createDirectories()
                    Files.copy(builtJar, isolatedPluginJar)
                    isolatedPluginJar
                }
        val runtimeClasspathEntry = locateRuntimeClasspathEntry(runtimeProjectRoot)
        val stdlibJar = locateStdlibJar()
        val resolvedSupport = resolveHarnessSupport(requiredPlugins)

        assertTrue(pluginJar.toFile().isFile, "Plugin jar is missing at $pluginJar")
        assertTrue(runtimeClasspathEntry.toFile().exists(), "Runtime classpath entry is missing at $runtimeClasspathEntry")
        assertTrue(stdlibJar.toFile().isFile, "Kotlin stdlib jar is missing at $stdlibJar")
        resolvedSupport.runtimeClasspathEntries.forEach { runtimeJar ->
            assertTrue(runtimeJar.toFile().isFile, "Support runtime jar is missing at $runtimeJar")
        }
        resolvedSupport.compilerPluginJars.forEach { compilerPluginJar ->
            assertTrue(compilerPluginJar.toFile().isFile, "Support compiler plugin jar is missing at $compilerPluginJar")
        }

        val runtimeClasspathEntries =
            linkedSetOf<Path>().apply {
                dependencyArtifacts.forEach { artifacts ->
                    add(artifacts.outputDir)
                }
                add(runtimeClasspathEntry)
                add(stdlibJar)
                addAll(resolvedSupport.runtimeClasspathEntries)
                dependencyArtifacts.forEach { artifacts ->
                    addAll(artifacts.runtimeClasspathEntries)
                }
            }.toList()

        val stdout = ByteArrayOutputStream()
        val jvmTarget =
            requiredPlugins.fold(DEFAULT_HARNESS_JVM_TARGET) { current, plugin ->
                maxJvmTarget(current, plugin.minimumJvmTarget)
            }
        val compilerArguments =
            buildList {
                add("-Xcontext-parameters")
                add("-no-stdlib")
                add("-no-reflect")
                add("-jvm-target")
                add(jvmTarget)
                add("-Xplugin=${pluginJar.toAbsolutePath()}")
                resolvedSupport.compilerPluginJars.forEach { compilerPluginJar ->
                    add("-Xplugin=${compilerPluginJar.toAbsolutePath()}")
                }
                addAll(resolvedSupport.compilerArguments)
                add("-classpath")
                add(runtimeClasspathEntries.joinToString(separator = java.io.File.pathSeparator) { it.toAbsolutePath().toString() })
                pluginOptions.forEach { option ->
                    add("-P")
                    add("plugin:$TYPECLASS_PLUGIN_ID:$option")
                }
                add("-d")
                add(outputDir.toAbsolutePath().toString())
                addAll(sourceFiles.map { it.toAbsolutePath().toString() })
            }
        val exitCode =
            K2JVMCompiler().exec(
                PrintStream(stdout),
                *compilerArguments.toTypedArray(),
            )

        return CompilationResult(
            exitCode = exitCode,
            stdout = stdout.toString(Charsets.UTF_8),
            artifacts =
                CompilationArtifacts(
                    outputDir = outputDir,
                    runtimeClasspathEntries = runtimeClasspathEntries,
                ),
        )
    }

    private fun locateProjectRoot(): Path {
        val candidates =
            buildList {
                add(Path.of(System.getProperty("user.dir")).toAbsolutePath())
                val classResourcePath = javaClass.name.replace('.', '/') + ".class"
                val classResource = requireNotNull(javaClass.classLoader.getResource(classResourcePath)) {
                    "Could not locate $classResourcePath"
                }
                if (classResource.protocol == "file") {
                    val classPath = Path.of(classResource.toURI()).toAbsolutePath()
                    add(if (classPath.toFile().isFile) classPath.parent else classPath)
                }
            }

        return candidates
            .asSequence()
            .flatMap { start -> generateSequence(start) { current -> current.parent } }
            .firstOrNull { candidate ->
                candidate.resolve("build.gradle.kts").toFile().isFile &&
                    candidate.resolve("settings.gradle.kts").toFile().isFile
            }
            ?: error("Could not locate the kotlin-typeclasses-plugin project root from $candidates")
    }

    private fun locateBuiltJar(
        libsDirectory: Path,
        artifactPrefix: String,
    ): Path =
        selectSingleJar(
            candidates = directJarCandidates(libsDirectory, artifactPrefix),
            description = "built $artifactPrefix jar",
            searchRoot = libsDirectory,
        )

    private fun locateBuiltJvmJar(
        libsDirectory: Path,
        artifactPrefix: String,
    ): Path {
        val jvmJars = directJarCandidates(libsDirectory, "${artifactPrefix}-jvm")
        return if (jvmJars.isNotEmpty()) {
            selectSingleJar(
                candidates = jvmJars,
                description = "built ${artifactPrefix}-jvm jar",
                searchRoot = libsDirectory,
            )
        } else {
            locateBuiltJar(libsDirectory, artifactPrefix)
        }
    }

    private fun locateRuntimeClasspathEntry(runtimeProjectRoot: Path): Path {
        val runtimeJar = locateBuiltJvmJar(runtimeProjectRoot.resolve("build/libs"), "kotlin-typeclasses")
        if (runtimeJarContainsTypeclassApi(runtimeJar)) {
            return runtimeJar
        }

        val compiledClasses = runtimeProjectRoot.resolve("build/classes/kotlin/jvm/main")
        require(compiledClasses.resolve("one/wabbit/typeclass/Typeclass.class").toFile().isFile) {
            "Could not locate compiled runtime classes in $compiledClasses"
        }
        return compiledClasses
    }

    private fun runtimeJarContainsTypeclassApi(runtimeJar: Path): Boolean =
        runCatching {
            java.util.jar.JarFile(runtimeJar.toFile()).use { jar ->
                jar.getEntry("one/wabbit/typeclass/Typeclass.class") != null
            }
        }.getOrDefault(false)

    private fun locateStdlibJar(): Path {
        val resource = requireNotNull(Thread.currentThread().contextClassLoader.getResource("kotlin/Unit.class")) {
            "Could not locate kotlin/Unit.class on the test classpath"
        }
        val externalForm = resource.toExternalForm()
        if (externalForm.startsWith("jar:file:")) {
            val jarUri = URI(externalForm.substringAfter("jar:").substringBefore("!"))
            return Path.of(jarUri)
        }
        val classpathEntry =
            System.getProperty("java.class.path")
                .split(java.io.File.pathSeparator)
                .firstOrNull { entry -> "kotlin-stdlib" in entry }
        return requireNotNull(classpathEntry) {
            "Could not locate kotlin-stdlib on the test classpath"
        }.let(Path::of)
    }

    private fun resolveHarnessSupport(requiredPlugins: List<CompilerHarnessPlugin>): ResolvedHarnessSupport {
        val runtimeClasspathEntries = linkedMapOf<String, Path>()
        val compilerPluginJars = linkedMapOf<String, Path>()
        val compilerArguments = mutableListOf<String>()
        requiredPlugins.forEach { plugin ->
            plugin.runtimeClasspathJarMarkers.forEach { marker ->
                runtimeClasspathEntries.putIfAbsent(marker, locateSupportJar(marker))
            }
            plugin.compilerPluginJarMarkers.forEach { marker ->
                compilerPluginJars.putIfAbsent(marker, locateSupportJar(marker))
            }
            if (plugin.compilerPluginId != null) {
                plugin.compilerPluginOptions.forEach { option ->
                    compilerArguments += "-P"
                    compilerArguments += "plugin:${plugin.compilerPluginId}:$option"
                }
            }
            compilerArguments += plugin.compilerArguments
        }
        return ResolvedHarnessSupport(
            runtimeClasspathEntries = runtimeClasspathEntries.values.toList(),
            compilerPluginJars = compilerPluginJars.values.toList(),
            compilerArguments = compilerArguments,
        )
    }

    private fun locateSupportJar(jarNamePrefix: String): Path =
        locatedSupportJars.computeIfAbsent(jarNamePrefix) { prefix ->
            locateClasspathJar(prefix)
                ?: locateGradleCacheJar(prefix)
                ?: error("Could not locate $prefix on the test classpath or in the Gradle artifact cache")
        }

    private fun locateClasspathJar(jarNamePrefix: String): Path? =
        selectSingleJarOrNull(
            candidates =
                System.getProperty("java.class.path")
                    .split(java.io.File.pathSeparator)
                    .asSequence()
                    .filter { entry -> entry.endsWith(".jar") }
                    .map(Path::of)
                    .filter { candidate -> isMatchingJar(candidate, jarNamePrefix) }
                    .sortedBy { candidate -> candidate.toAbsolutePath().toString() }
                    .toList(),
            description = "$jarNamePrefix on the test classpath",
        )

    private fun locateGradleCacheJar(jarNamePrefix: String): Path? {
        val gradleCacheRoot = Path.of(System.getProperty("user.home"), ".gradle", "caches", "modules-2", "files-2.1")
        if (!Files.isDirectory(gradleCacheRoot)) {
            return null
        }
        return selectSingleJarOrNull(
            candidates =
                Files.walk(gradleCacheRoot).use { paths ->
                    paths.iterator()
                        .asSequence()
                        .filter { candidate -> candidate.isRegularFile() && isMatchingJar(candidate, jarNamePrefix) }
                        .sortedBy { candidate -> candidate.toAbsolutePath().toString() }
                        .toList()
                },
            description = "$jarNamePrefix in the Gradle artifact cache",
        )
    }

    private fun parseCompilerDiagnostics(output: String): List<ReportedDiagnostic> =
        output.lineSequence().mapNotNull(::parseCompilerDiagnostic).toList()

    private fun parseCompilerDiagnostic(line: String): ReportedDiagnostic? =
        parseLocatedDiagnostic(line) ?: parseGlobalDiagnostic(line)

    private fun parseLocatedDiagnostic(line: String): ReportedDiagnostic? {
        val match = locatedDiagnosticRegex.matchEntire(line) ?: return null
        return ReportedDiagnostic(
            file = match.groupValues[1],
            line = match.groupValues[2].toInt(),
            column = match.groupValues[3].toInt(),
            severity = parseDiagnosticSeverity(match.groupValues[4]),
            message = match.groupValues[5],
            rawLine = line,
        )
    }

    private fun parseGlobalDiagnostic(line: String): ReportedDiagnostic? {
        val match = globalDiagnosticRegex.matchEntire(line) ?: return null
        return ReportedDiagnostic(
            file = null,
            line = null,
            column = null,
            severity = parseDiagnosticSeverity(match.groupValues[1]),
            message = match.groupValues[2],
            rawLine = line,
        )
    }

    private fun parseDiagnosticSeverity(token: String): DiagnosticSeverity =
        when (token.lowercase()) {
            "e", "error" -> DiagnosticSeverity.ERROR
            "w", "warning" -> DiagnosticSeverity.WARNING
            else -> error("Unsupported diagnostic severity token: $token")
        }

    private fun formatDiagnostics(diagnostics: List<ReportedDiagnostic>): String =
        if (diagnostics.isEmpty()) {
            "<none>"
        } else {
            diagnostics.joinToString(separator = "\n") { diagnostic -> diagnostic.toString() }
        }

    private fun directJarCandidates(
        libsDirectory: Path,
        jarNamePrefix: String,
    ): List<Path> =
        libsDirectory.toFile().listFiles()
            ?.asSequence()
            ?.filter { file -> file.isFile && isMatchingJar(file.toPath(), jarNamePrefix) }
            ?.map { file -> file.toPath() }
            ?.sortedBy { candidate -> candidate.fileName.toString() }
            ?.toList()
            ?: emptyList()

    private fun isMatchingJar(
        candidate: Path,
        jarNamePrefix: String,
    ): Boolean {
        val fileName = candidate.fileName.toString()
        return fileName.endsWith(".jar") &&
            !fileName.endsWith("-sources.jar") &&
            !fileName.endsWith("-javadoc.jar") &&
            (fileName == "$jarNamePrefix.jar" || fileName.startsWith("$jarNamePrefix-"))
    }

    private fun selectSingleJar(
        candidates: List<Path>,
        description: String,
        searchRoot: Path,
    ): Path =
        selectSingleJarOrNull(candidates, description)
            ?: error("Could not locate $description in $searchRoot")

    private fun selectSingleJarOrNull(
        candidates: List<Path>,
        description: String,
    ): Path? =
        when (candidates.size) {
            0 -> null
            1 -> candidates.single()
            else ->
                error(
                    buildString {
                        appendLine("Found multiple candidates for $description:")
                        candidates.forEach { candidate ->
                            appendLine(candidate.toAbsolutePath().toString())
                        }
                    },
                )
        }
}

data class CompilationArtifacts(
    val outputDir: Path,
    val runtimeClasspathEntries: List<Path>,
)

data class HarnessDependency(
    val sources: Map<String, String>,
    val requiredPlugins: List<CompilerHarnessPlugin> = emptyList(),
    val pluginOptions: List<String> = emptyList(),
    val dependencies: List<HarnessDependency> = emptyList(),
)

data class CompilationResult(
    val exitCode: ExitCode,
    val stdout: String,
    val artifacts: CompilationArtifacts,
)

enum class DiagnosticSeverity {
    ERROR,
    WARNING,
}

data class ReportedDiagnostic(
    val file: String?,
    val line: Int?,
    val column: Int?,
    val severity: DiagnosticSeverity,
    val message: String,
    val rawLine: String,
) {
    override fun toString(): String {
        val location =
            buildString {
                if (file != null) {
                    append(file)
                }
                if (line != null) {
                    if (isNotEmpty()) {
                        append(':')
                    }
                    append(line)
                }
                if (column != null) {
                    append(':')
                    append(column)
                }
            }
        return buildString {
            if (location.isNotEmpty()) {
                append(location)
                append(": ")
            }
            append(severity.name.lowercase())
            append(": ")
            append(message)
        }
    }
}

sealed class ExpectedDiagnostic private constructor(
    private val severity: DiagnosticSeverity,
    private val file: String?,
    private val line: Int?,
    private val description: String,
    private val messagePredicate: (String) -> Boolean,
) {
    fun matches(actual: ReportedDiagnostic): Boolean =
        actual.severity == severity &&
            matchesFile(actual.file) &&
            (line == null || actual.line == line) &&
            messagePredicate(actual.message)

    private fun matchesFile(actualFile: String?): Boolean {
        if (file == null) {
            return true
        }
        if (actualFile == null) {
            return false
        }
        val normalizedExpected = file.replace('\\', '/')
        val normalizedActual = actualFile.replace('\\', '/')
        return normalizedActual == normalizedExpected || normalizedActual.endsWith("/$normalizedExpected")
    }

    override fun toString(): String =
        buildString {
            append(severity.name.lowercase())
            append('(')
            append(file ?: "*")
            append(':')
            append(line ?: "*")
            append(", ")
            append(description)
            append(')')
        }

    class Error(
        file: String? = null,
        line: Int? = null,
        messageRegex: String? = null,
        description: String? = null,
        messagePredicate: ((String) -> Boolean)? = null,
    ) : ExpectedDiagnostic(
            severity = DiagnosticSeverity.ERROR,
            file = file,
            line = line,
            description = diagnosticDescription(messageRegex, description, messagePredicate),
            messagePredicate = diagnosticMessagePredicate(messageRegex, messagePredicate),
        )

    class Warning(
        file: String? = null,
        line: Int? = null,
        messageRegex: String? = null,
        description: String? = null,
        messagePredicate: ((String) -> Boolean)? = null,
    ) : ExpectedDiagnostic(
            severity = DiagnosticSeverity.WARNING,
            file = file,
            line = line,
            description = diagnosticDescription(messageRegex, description, messagePredicate),
            messagePredicate = diagnosticMessagePredicate(messageRegex, messagePredicate),
        )

    private companion object {
        private fun diagnosticDescription(
            messageRegex: String?,
            description: String?,
            messagePredicate: ((String) -> Boolean)?,
        ): String =
            when {
                description != null -> description
                messageRegex != null -> "message matching /$messageRegex/"
                messagePredicate != null -> "custom predicate"
                else -> "any message"
            }

        private fun diagnosticMessagePredicate(
            messageRegex: String?,
            messagePredicate: ((String) -> Boolean)?,
        ): (String) -> Boolean {
            val regex = messageRegex?.let(::Regex)
            return { message ->
                val regexMatches = regex?.containsMatchIn(message) ?: true
                val predicateMatches = messagePredicate?.invoke(message) ?: true
                regexMatches && predicateMatches
            }
        }
    }
}

sealed interface CompilerHarnessPlugin {
    val supportSources: Map<String, String>
        get() = emptyMap()
    val runtimeClasspathJarMarkers: List<String>
    val compilerPluginJarMarkers: List<String>
        get() = emptyList()
    val compilerPluginId: String?
        get() = null
    val compilerPluginOptions: List<String>
        get() = emptyList()
    val compilerArguments: List<String>
        get() = emptyList()
    val minimumJvmTarget: String
        get() = "1.8"

    data object Serialization : CompilerHarnessPlugin {
        override val runtimeClasspathJarMarkers: List<String> =
            listOf(
                "kotlinx-serialization-core-jvm",
            )
        override val compilerPluginJarMarkers: List<String> =
            listOf("kotlin-serialization-compiler-plugin-embeddable")
    }

    data object Compose : CompilerHarnessPlugin {
        override val runtimeClasspathJarMarkers: List<String> =
            listOf(
                "runtime-metadata",
                "runtime-desktop",
                "runtime-annotation-jvm",
                "collection-jvm",
                "kotlinx-coroutines-core-jvm",
            )
        override val compilerPluginJarMarkers: List<String> =
            listOf("kotlin-compose-compiler-plugin-embeddable")
        override val minimumJvmTarget: String = "11"
    }

    data class PowerAssert(
        val functions: List<String> = listOf("kotlin.assert"),
    ) : CompilerHarnessPlugin {
        override val runtimeClasspathJarMarkers: List<String> = emptyList()
        override val compilerPluginJarMarkers: List<String> =
            listOf("kotlin-power-assert-compiler-plugin-embeddable")
        override val compilerPluginId: String = "org.jetbrains.kotlin.powerassert"
        override val compilerPluginOptions: List<String> =
            functions.map { functionFqName -> "function=$functionFqName" }
    }

    data object Parcelize : CompilerHarnessPlugin {
        override val supportSources: Map<String, String> =
            mapOf(
                "android/os/Parcel.kt" to
                    """
                    package android.os

                    public open class Parcel {
                        public fun writeInt(value: Int): Unit = Unit

                        public fun readInt(): Int = 0
                    }
                    """.trimIndent(),
                "android/os/Parcelable.kt" to
                    """
                    package android.os

                    public interface Parcelable {
                        public fun describeContents(): Int = 0

                        public fun writeToParcel(parcel: Parcel, flags: Int): Unit = Unit

                        public interface Creator<T> {
                            public fun createFromParcel(parcel: Parcel): T

                            public fun newArray(size: Int): Array<T?>
                        }
                    }
                    """.trimIndent(),
            )
        override val runtimeClasspathJarMarkers: List<String> =
            listOf("kotlin-parcelize-runtime")
        override val compilerPluginJarMarkers: List<String> =
            listOf("kotlin-parcelize-compiler")
        override val compilerPluginId: String = "org.jetbrains.kotlin.parcelize"
    }

    data object AtomicFu : CompilerHarnessPlugin {
        override val runtimeClasspathJarMarkers: List<String> =
            listOf("atomicfu-jvm")
        override val compilerPluginJarMarkers: List<String> =
            listOf("kotlin-atomicfu-compiler-plugin-embeddable")
    }

    data class AllOpen(
        val annotations: List<String>,
        val presets: List<String> = emptyList(),
    ) : CompilerHarnessPlugin {
        override val runtimeClasspathJarMarkers: List<String> = emptyList()
        override val compilerPluginJarMarkers: List<String> =
            listOf("kotlin-allopen-compiler-plugin-embeddable")
        override val compilerPluginId: String = "org.jetbrains.kotlin.allopen"
        override val compilerPluginOptions: List<String> =
            buildList {
                annotations.forEach { annotationFqName ->
                    add("annotation=$annotationFqName")
                }
                presets.forEach { presetName ->
                    add("preset=$presetName")
                }
            }
    }

    data class NoArg(
        val annotations: List<String>,
        val presets: List<String> = emptyList(),
        val invokeInitializers: Boolean = false,
    ) : CompilerHarnessPlugin {
        override val runtimeClasspathJarMarkers: List<String> = emptyList()
        override val compilerPluginJarMarkers: List<String> =
            listOf("kotlin-noarg-compiler-plugin-embeddable")
        override val compilerPluginId: String = "org.jetbrains.kotlin.noarg"
        override val compilerPluginOptions: List<String> =
            buildList {
                annotations.forEach { annotationFqName ->
                    add("annotation=$annotationFqName")
                }
                presets.forEach { presetName ->
                    add("preset=$presetName")
                }
                if (invokeInitializers) {
                    add("invokeInitializers=true")
                }
            }
    }

    data class External(
        override val supportSources: Map<String, String> = emptyMap(),
        override val runtimeClasspathJarMarkers: List<String> = emptyList(),
        override val compilerPluginJarMarkers: List<String> = emptyList(),
        override val compilerPluginId: String? = null,
        override val compilerPluginOptions: List<String> = emptyList(),
        override val compilerArguments: List<String> = emptyList(),
        override val minimumJvmTarget: String = "1.8",
    ) : CompilerHarnessPlugin
}

private data class ResolvedHarnessSupport(
    val runtimeClasspathEntries: List<Path>,
    val compilerPluginJars: List<Path>,
    val compilerArguments: List<String>,
)
