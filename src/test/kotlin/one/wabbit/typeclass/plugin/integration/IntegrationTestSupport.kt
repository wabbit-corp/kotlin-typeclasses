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

abstract class IntegrationTestSupport {
    internal open fun supportBundles(): List<CompilerHarnessSupportBundle> = defaultHarnessSupportBundles()

    protected fun assertCompiles(source: String) {
        compileSource(source)
    }

    protected fun assertDoesNotCompile(
        source: String,
        expectedMessages: List<String>,
        pluginOptions: List<String> = emptyList(),
    ) {
        assertDoesNotCompile(
            sources = mapOf("Sample.kt" to source),
            expectedMessages = expectedMessages,
            pluginOptions = pluginOptions,
        )
    }

    protected fun assertDoesNotCompile(
        sources: Map<String, String>,
        expectedMessages: List<String>,
        pluginOptions: List<String> = emptyList(),
    ) {
        val result = compileSourceInternal(sources, pluginOptions)
        assertEquals(
            ExitCode.COMPILATION_ERROR,
            result.exitCode,
            result.stdout,
        )
        val lowercaseOutput = result.stdout.lowercase()
        expectedMessages.forEach { expectedMessage ->
            assertTrue(lowercaseOutput.contains(expectedMessage.lowercase()), result.stdout)
        }
    }

    protected fun assertCompilesAndRuns(
        source: String,
        expectedStdout: String,
        mainClass: String = "demo.SampleKt",
        pluginOptions: List<String> = emptyList(),
    ) {
        assertCompilesAndRuns(
            sources = mapOf("Sample.kt" to source),
            expectedStdout = expectedStdout,
            mainClass = mainClass,
            pluginOptions = pluginOptions,
        )
    }

    protected fun assertCompilesAndRuns(
        sources: Map<String, String>,
        expectedStdout: String,
        mainClass: String,
        pluginOptions: List<String> = emptyList(),
    ) {
        val artifacts = compileSource(sources, pluginOptions)
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
        pluginOptions: List<String> = emptyList(),
    ): CompilationArtifacts {
        val result = compileSourceInternal(sources, pluginOptions)
        assertEquals(
            ExitCode.OK,
            result.exitCode,
            result.stdout,
        )
        return result.artifacts
    }

    protected fun compileSource(
        source: String,
        pluginOptions: List<String> = emptyList(),
    ): CompilationArtifacts {
        return compileSource(mapOf("Sample.kt" to source), pluginOptions)
    }

    protected fun compileSourceInternal(
        source: String,
        pluginOptions: List<String> = emptyList(),
    ): CompilationResult {
        return compileSourceInternal(mapOf("Sample.kt" to source), pluginOptions)
    }

    protected fun compileSourceInternal(
        sources: Map<String, String>,
        pluginOptions: List<String> = emptyList(),
    ): CompilationResult {
        val workingDir = createTempDirectory(prefix = "typeclass-compile-")
        val outputDir = workingDir.resolve("out")
        outputDir.createDirectories()
        val sourceFiles =
            sources.map { (relativePath, contents) ->
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
        val resolvedSupport = resolveHarnessSupport(sources.values)

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
            buildList {
                add(runtimeClasspathEntry)
                add(stdlibJar)
                addAll(resolvedSupport.runtimeClasspathEntries)
            }

        val stdout = ByteArrayOutputStream()
        val compilerArguments =
            buildList {
                add("-Xcontext-parameters")
                add("-no-stdlib")
                add("-no-reflect")
                add("-Xplugin=${pluginJar.toAbsolutePath()}")
                resolvedSupport.compilerPluginJars.forEach { compilerPluginJar ->
                    add("-Xplugin=${compilerPluginJar.toAbsolutePath()}")
                }
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
    ): Path {
        val jar =
            libsDirectory.toFile().listFiles()
                ?.asSequence()
                ?.filter { file ->
                    file.isFile &&
                        file.extension == "jar" &&
                        file.name.startsWith("$artifactPrefix-") &&
                        !file.name.endsWith("-sources.jar") &&
                        !file.name.endsWith("-javadoc.jar")
                }
                ?.maxByOrNull { file -> file.lastModified() }
                ?.toPath()
        return requireNotNull(jar) {
            "Could not locate a built $artifactPrefix jar in $libsDirectory"
        }
    }

    private fun locateBuiltJvmJar(
        libsDirectory: Path,
        artifactPrefix: String,
    ): Path {
        val jvmJar =
            libsDirectory.toFile().listFiles()
                ?.asSequence()
                ?.filter { file ->
                    file.isFile &&
                        file.extension == "jar" &&
                        file.name.startsWith("${artifactPrefix}-jvm-") &&
                        !file.name.endsWith("-sources.jar") &&
                        !file.name.endsWith("-javadoc.jar")
                }
                ?.maxByOrNull { file -> file.lastModified() }
                ?.toPath()
        return jvmJar ?: locateBuiltJar(libsDirectory, artifactPrefix)
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

    private fun resolveHarnessSupport(sourceContents: Collection<String>): ResolvedHarnessSupport {
        val combinedSource = sourceContents.joinToString(separator = "\n")
        val runtimeClasspathEntries = linkedMapOf<String, Path>()
        val compilerPluginJars = linkedMapOf<String, Path>()
        supportBundles().forEach { bundle ->
            val runtimeRequired = bundle.requiresRuntime(combinedSource)
            val compilerPluginRequired = bundle.requiresCompilerPlugin(combinedSource)
            if (runtimeRequired || compilerPluginRequired) {
                bundle.runtimeClasspathJarMarkers.forEach { marker ->
                    runtimeClasspathEntries.putIfAbsent(marker, locateClasspathJar(containing = marker))
                }
            }
            if (compilerPluginRequired) {
                bundle.compilerPluginJarMarkers.forEach { marker ->
                    compilerPluginJars.putIfAbsent(marker, locateClasspathJar(containing = marker))
                }
            }
        }
        return ResolvedHarnessSupport(
            runtimeClasspathEntries = runtimeClasspathEntries.values.toList(),
            compilerPluginJars = compilerPluginJars.values.toList(),
        )
    }

    private fun locateClasspathJar(containing: String): Path =
        System.getProperty("java.class.path")
            .split(java.io.File.pathSeparator)
            .firstOrNull { entry ->
                entry.contains(containing) && entry.endsWith(".jar")
            }
            ?.let(Path::of)
            ?: error("Could not locate $containing on the test classpath")
}

data class CompilationArtifacts(
    val outputDir: Path,
    val runtimeClasspathEntries: List<Path>,
)

data class CompilationResult(
    val exitCode: ExitCode,
    val stdout: String,
    val artifacts: CompilationArtifacts,
)

internal data class CompilerHarnessSupportBundle(
    val name: String,
    val runtimeClasspathJarMarkers: List<String>,
    val compilerPluginJarMarkers: List<String> = emptyList(),
    val requiresRuntime: (String) -> Boolean,
    val requiresCompilerPlugin: (String) -> Boolean = { false },
)

private data class ResolvedHarnessSupport(
    val runtimeClasspathEntries: List<Path>,
    val compilerPluginJars: List<Path>,
)

private fun defaultHarnessSupportBundles(): List<CompilerHarnessSupportBundle> =
    listOf(
        CompilerHarnessSupportBundle(
            name = "kotlinx-serialization",
            runtimeClasspathJarMarkers = listOf("kotlinx-serialization-core-jvm"),
            compilerPluginJarMarkers = listOf("kotlin-serialization-compiler-plugin-embeddable"),
            requiresRuntime = { source ->
                "kotlinx.serialization" in source ||
                    "@Serializable" in source ||
                    "KSerializer<" in source ||
                    "serializer<" in source
            },
            requiresCompilerPlugin = { source ->
                "@Serializable" in source ||
                    "serializer<" in source ||
                    "serializer(" in source
            },
        ),
    )
