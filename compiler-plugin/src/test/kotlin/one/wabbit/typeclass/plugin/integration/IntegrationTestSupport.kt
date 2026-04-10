// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.typeclass.plugin.integration

import one.wabbit.typeclass.plugin.TYPECLASS_PLUGIN_ID
import one.wabbit.typeclass.plugin.TypeclassDiagnosticIds
import one.wabbit.typeclass.plugin.TypeclassDiagnostic
import one.wabbit.typeclass.plugin.cannotDeriveDiagnostic
import one.wabbit.typeclass.plugin.fix
import one.wabbit.typeclass.plugin.headline
import one.wabbit.typeclass.plugin.invalidEquivDiagnostic
import one.wabbit.typeclass.plugin.invalidInstanceDiagnostic
import one.wabbit.typeclass.plugin.parseTypeclassDiagnostic
import one.wabbit.typeclass.plugin.reason
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.isRegularFile
import org.junit.Rule
import org.junit.rules.TestWatcher
import org.junit.runner.Description

abstract class IntegrationTestSupport {
    private companion object {
        private const val DEFAULT_HARNESS_JVM_TARGET = "1.8"
        private const val KEEP_TEST_TEMP_ENV = "TYPECLASS_KEEP_TEST_TEMP"
        private val locatedSupportJars = ConcurrentHashMap<String, Path>()
        private val locatedDiagnosticRegex = Regex("""^(.+):(\d+):(\d+):\s+(error|warning):\s+(.*)$""")
        private val globalDiagnosticRegex = Regex("""^(e|w|error|warning):\s+(.*)$""")
        private val prefixedDiagnosticIdRegex = Regex("""^\[(TC_[A-Z_]+)]\s*(.*)$""")
        private val sourceDiagnosticMarkerRegex =
            Regex("""\b(?:([A-Za-z_][A-Za-z0-9_]*)@)?([EW])(?::(TC_[A-Z_]+))?\b""")
        private val activeKotlinVersion = KotlinCompilerVersion.VERSION

        private fun maxJvmTarget(left: String, right: String): String =
            if (normalizeJvmTarget(left) >= normalizeJvmTarget(right)) {
                left
            } else {
                right
            }

        private fun normalizeJvmTarget(value: String): Int =
            value.removePrefix("1.").toInt()
    }

    private val tempRootsToCleanup = mutableListOf<Path>()
    private var keepTempRootsForCurrentTest = false

    @get:Rule
    val cleanupHarnessTempRoots =
        object : TestWatcher() {
            override fun failed(
                e: Throwable?,
                description: Description,
            ) {
                keepTempRootsForCurrentTest = true
            }

            override fun finished(description: Description) {
                try {
                    cleanupRegisteredTempRoots()
                } finally {
                    keepTempRootsForCurrentTest = false
                }
            }
        }

    protected fun assertCompiles(
        source: String,
        requiredPlugins: List<CompilerHarnessPlugin> = emptyList(),
        pluginOptions: List<String> = emptyList(),
        dependencies: List<HarnessDependency> = emptyList(),
        useTypeclassPlugin: Boolean = true,
        enableContextParameters: Boolean = true,
        compilerArguments: List<String> = emptyList(),
    ) {
        compileSource(
            source = source,
            requiredPlugins = requiredPlugins,
            pluginOptions = pluginOptions,
            dependencies = dependencies,
            useTypeclassPlugin = useTypeclassPlugin,
            enableContextParameters = enableContextParameters,
            compilerArguments = compilerArguments,
        )
    }

    protected fun compileSourceResult(
        source: String,
        requiredPlugins: List<CompilerHarnessPlugin> = emptyList(),
        pluginOptions: List<String> = emptyList(),
        dependencies: List<HarnessDependency> = emptyList(),
        useTypeclassPlugin: Boolean = true,
        enableContextParameters: Boolean = true,
        compilerArguments: List<String> = emptyList(),
    ): CompilationResult =
        compileSourceInternal(
            sources = mapOf("Sample.kt" to source),
            requiredPlugins = requiredPlugins,
            pluginOptions = pluginOptions,
            dependencies = dependencies,
            useTypeclassPlugin = useTypeclassPlugin,
            enableContextParameters = enableContextParameters,
            compilerArguments = compilerArguments,
        )

    protected fun assertDoesNotCompile(
        source: String,
        expectedDiagnostics: List<ExpectedDiagnostic> = emptyList(),
        unexpectedMessages: List<String> = emptyList(),
        requiredPlugins: List<CompilerHarnessPlugin> = emptyList(),
        pluginOptions: List<String> = emptyList(),
        dependencies: List<HarnessDependency> = emptyList(),
        useTypeclassPlugin: Boolean = true,
        enableContextParameters: Boolean = true,
        compilerArguments: List<String> = emptyList(),
    ) {
        assertDoesNotCompileInternal(
            sources = mapOf("Sample.kt" to source),
            expectedDiagnostics = expectedDiagnostics,
            expectedLabeledDiagnostics = emptyMap(),
            unexpectedMessages = unexpectedMessages,
            requiredPlugins = requiredPlugins,
            pluginOptions = pluginOptions,
            dependencies = dependencies,
            useTypeclassPlugin = useTypeclassPlugin,
            enableContextParameters = enableContextParameters,
            compilerArguments = compilerArguments,
        )
    }

    protected fun assertDoesNotCompile(
        source: String,
        expectedDiagnostics: Map<String, TypeclassDiagnostic>,
        unexpectedMessages: List<String> = emptyList(),
        requiredPlugins: List<CompilerHarnessPlugin> = emptyList(),
        pluginOptions: List<String> = emptyList(),
        dependencies: List<HarnessDependency> = emptyList(),
        useTypeclassPlugin: Boolean = true,
        enableContextParameters: Boolean = true,
        compilerArguments: List<String> = emptyList(),
    ) {
        assertDoesNotCompileInternal(
            sources = mapOf("Sample.kt" to source),
            expectedDiagnostics = emptyList(),
            expectedLabeledDiagnostics = expectedDiagnostics,
            unexpectedMessages = unexpectedMessages,
            requiredPlugins = requiredPlugins,
            pluginOptions = pluginOptions,
            dependencies = dependencies,
            useTypeclassPlugin = useTypeclassPlugin,
            enableContextParameters = enableContextParameters,
            compilerArguments = compilerArguments,
        )
    }

    protected fun assertDoesNotCompile(
        sources: Map<String, String>,
        expectedDiagnostics: List<ExpectedDiagnostic> = emptyList(),
        unexpectedMessages: List<String> = emptyList(),
        requiredPlugins: List<CompilerHarnessPlugin> = emptyList(),
        pluginOptions: List<String> = emptyList(),
        dependencies: List<HarnessDependency> = emptyList(),
        useTypeclassPlugin: Boolean = true,
        enableContextParameters: Boolean = true,
        compilerArguments: List<String> = emptyList(),
    ) {
        assertDoesNotCompileInternal(
            sources = sources,
            expectedDiagnostics = expectedDiagnostics,
            expectedLabeledDiagnostics = emptyMap(),
            unexpectedMessages = unexpectedMessages,
            requiredPlugins = requiredPlugins,
            pluginOptions = pluginOptions,
            dependencies = dependencies,
            useTypeclassPlugin = useTypeclassPlugin,
            enableContextParameters = enableContextParameters,
            compilerArguments = compilerArguments,
        )
    }

    protected fun assertDoesNotCompile(
        sources: Map<String, String>,
        expectedDiagnostics: Map<String, TypeclassDiagnostic>,
        unexpectedMessages: List<String> = emptyList(),
        requiredPlugins: List<CompilerHarnessPlugin> = emptyList(),
        pluginOptions: List<String> = emptyList(),
        dependencies: List<HarnessDependency> = emptyList(),
        useTypeclassPlugin: Boolean = true,
        enableContextParameters: Boolean = true,
        compilerArguments: List<String> = emptyList(),
    ) {
        assertDoesNotCompileInternal(
            sources = sources,
            expectedDiagnostics = emptyList(),
            expectedLabeledDiagnostics = expectedDiagnostics,
            unexpectedMessages = unexpectedMessages,
            requiredPlugins = requiredPlugins,
            pluginOptions = pluginOptions,
            dependencies = dependencies,
            useTypeclassPlugin = useTypeclassPlugin,
            enableContextParameters = enableContextParameters,
            compilerArguments = compilerArguments,
        )
    }

    private fun assertDoesNotCompileInternal(
        sources: Map<String, String>,
        expectedDiagnostics: List<ExpectedDiagnostic>,
        expectedLabeledDiagnostics: Map<String, TypeclassDiagnostic>,
        unexpectedMessages: List<String>,
        requiredPlugins: List<CompilerHarnessPlugin>,
        pluginOptions: List<String>,
        dependencies: List<HarnessDependency>,
        useTypeclassPlugin: Boolean,
        enableContextParameters: Boolean,
        compilerArguments: List<String>,
    ) {
        val result =
            compileSourceInternal(
                sources = sources,
                requiredPlugins = requiredPlugins,
                pluginOptions = pluginOptions,
                dependencies = dependencies,
                useTypeclassPlugin = useTypeclassPlugin,
                enableContextParameters = enableContextParameters,
                compilerArguments = compilerArguments,
            )
        assertEquals(
            ExitCode.COMPILATION_ERROR,
            result.exitCode,
            result.stdout,
        )
        val diagnostics = parseCompilerDiagnostics(result.stdout)
        val sourceMarkers = parseSourceDiagnosticMarkers(sources)
        assertTrue(
            sourceMarkers.any { it.severity == DiagnosticSeverity.ERROR },
            buildString {
                appendLine("assertDoesNotCompile requires at least one inline error marker like // E:TC_... or // label@E:TC_...")
                appendLine("Sources:")
                sources.forEach { (file, source) ->
                    appendLine("== $file ==")
                    appendLine(source)
                }
            },
        )
        val commentDiagnostics =
            expectedDiagnosticsFromSourceMarkers(
                markers = sourceMarkers,
                labeledDiagnostics = expectedLabeledDiagnostics,
            )
        val inlineErrorDiagnostics =
            commentDiagnostics.filterIsInstance<ExpectedDiagnostic.Error>()
        val allExpectedDiagnostics = expectedDiagnostics + commentDiagnostics
        allExpectedDiagnostics.forEach { expectedDiagnostic ->
            assertTrue(
                diagnostics.any(expectedDiagnostic::matches),
                buildString {
                    appendLine("Expected diagnostic not found: $expectedDiagnostic")
                    appendLine("Parsed diagnostics:")
                    appendLine(formatDiagnostics(diagnostics))
                    if (commentDiagnostics.isNotEmpty()) {
                        appendLine("Source marker diagnostics:")
                        appendLine(commentDiagnostics.joinToString(separator = "\n"))
                    }
                    appendLine("Full compiler output:")
                    append(result.stdout)
                },
            )
        }
        val unannotatedErrors =
            diagnostics.filter { diagnostic ->
                diagnostic.severity == DiagnosticSeverity.ERROR &&
                    inlineErrorDiagnostics.none { expected -> expected.matches(diagnostic) }
            }
        assertTrue(
            unannotatedErrors.isEmpty(),
            buildString {
                appendLine("All compiler errors must have an inline source marker annotation.")
                appendLine("Compiler errors without a matching inline marker:")
                appendLine(formatDiagnostics(unannotatedErrors))
                appendLine("Inline marker diagnostics:")
                appendLine(inlineErrorDiagnostics.joinToString(separator = "\n"))
                if (allExpectedDiagnostics.isNotEmpty()) {
                    appendLine("All expected diagnostics:")
                    appendLine(allExpectedDiagnostics.joinToString(separator = "\n"))
                }
                appendLine("Full compiler output:")
                append(result.stdout)
            },
        )
        val unmatchedTypedDiagnostics =
            expectedDiagnostics.filterNot { expected ->
                diagnostics.any(expected::matches)
            }
        assertTrue(
            unmatchedTypedDiagnostics.isEmpty(),
            buildString {
                appendLine("Expected typed diagnostics were not found:")
                appendLine(unmatchedTypedDiagnostics.joinToString(separator = "\n"))
                if (allExpectedDiagnostics.isNotEmpty()) {
                    appendLine("All expected diagnostics:")
                    appendLine(allExpectedDiagnostics.joinToString(separator = "\n"))
                }
                appendLine("Full compiler output:")
                append(result.stdout)
            },
        )
        val lowercaseOutput = result.stdout.lowercase()
        unexpectedMessages.forEach { unexpectedMessage ->
            assertTrue(!lowercaseOutput.contains(unexpectedMessage.lowercase()), result.stdout)
        }
    }

    protected fun expectedErrorContaining(
        vararg fragments: String,
        file: String? = null,
        line: Int? = null,
    ): ExpectedDiagnostic.Error =
        inferDiagnosticIdsFromFragments(fragments.asList()).let { diagnosticIds ->
            val effectiveFragments =
                fragments.toList() + automaticNarrativeFragments(diagnosticIds)
            ExpectedDiagnostic.Error(
                diagnosticIds = diagnosticIds,
                file = file,
                line = line,
                description =
                    buildString {
                        diagnosticIds?.joinToString(prefix = "ids ", separator = " or ")?.let(::append)
                        if (isNotEmpty()) {
                            append(", ")
                        }
                        append("message containing ${effectiveFragments.joinToString()}")
                    },
                messagePredicate = { message ->
                    val normalized = message.lowercase()
                    effectiveFragments.all { normalized.contains(it.lowercase()) }
                },
            )
        }

    protected fun expectedDiagnosticId(
        diagnosticId: String,
        vararg fragments: String,
        file: String? = null,
        line: Int? = null,
        phase: DiagnosticPhase? = null,
    ): ExpectedDiagnostic.Error =
        ExpectedDiagnostic.Error(
            diagnosticIds = setOf(diagnosticId),
            phase = phase,
            file = file,
            line = line,
            description = "$diagnosticId containing ${fragments.joinToString()}",
            messagePredicate = { message ->
                val normalized = message.lowercase()
                fragments.all { normalized.contains(it.lowercase()) }
            },
        )

    protected fun expectedNoContextArgument(
        vararg headlineFragments: String,
        whyFragments: List<String> = emptyList(),
        fixFragments: List<String> = emptyList(),
        file: String? = null,
        line: Int? = null,
        phase: DiagnosticPhase? = DiagnosticPhase.FIR,
    ): ExpectedDiagnostic.Error =
        expectedStructuredDiagnosticId(
            diagnosticId = TypeclassDiagnosticIds.NO_CONTEXT_ARGUMENT,
            headlineFragments =
                listOf("no context argument") +
                    if (phase == DiagnosticPhase.IR) listOf("required instance") else emptyList<String>() +
                    headlineFragments.toList(),
            whyFragments =
                if (phase == DiagnosticPhase.IR) {
                    if (whyFragments.isEmpty()) {
                        listOf("no matching local context value or @instance rule could produce that goal")
                    } else {
                        whyFragments
                    }
                } else {
                    whyFragments
                },
            fixFragments = fixFragments,
            requireNarrative = phase == DiagnosticPhase.IR,
            file = file,
            line = line,
            phase = phase,
        )

    protected fun expectedAmbiguousInstance(
        vararg headlineFragments: String,
        whyFragments: List<String> = listOf("multiple candidates matched"),
        fixFragments: List<String> = listOf("pass the intended context argument explicitly"),
        file: String? = null,
        line: Int? = null,
        phase: DiagnosticPhase? = DiagnosticPhase.IR,
    ): ExpectedDiagnostic.Error =
        expectedStructuredDiagnosticId(
            diagnosticId = TypeclassDiagnosticIds.AMBIGUOUS_INSTANCE,
            headlineFragments = listOf("ambiguous typeclass instance") + headlineFragments.toList(),
            whyFragments = whyFragments,
            fixFragments = fixFragments,
            requireNarrative = true,
            file = file,
            line = line,
            phase = phase,
        )

    protected fun expectedInvalidInstanceDecl(
        vararg whyFragments: String,
        fixFragments: List<String> = emptyList(),
        file: String? = null,
        line: Int? = null,
        phase: DiagnosticPhase? = DiagnosticPhase.FIR,
    ): ExpectedDiagnostic.Error =
        expectedStructuredDiagnosticId(
            diagnosticId = TypeclassDiagnosticIds.INVALID_INSTANCE_DECL,
            headlineFragments = emptyList(),
            whyFragments = whyFragments.toList(),
            fixFragments = fixFragments,
            requireNarrative = true,
            file = file,
            line = line,
            phase = phase,
        )

    protected fun expectedInvalidEquivDecl(
        vararg whyFragments: String,
        fixFragments: List<String> = emptyList(),
        file: String? = null,
        line: Int? = null,
        phase: DiagnosticPhase? = DiagnosticPhase.FIR,
    ): ExpectedDiagnostic.Error =
        expectedStructuredDiagnosticId(
            diagnosticId = TypeclassDiagnosticIds.INVALID_EQUIV_DECL,
            headlineFragments = emptyList(),
            whyFragments = whyFragments.toList(),
            fixFragments = fixFragments,
            requireNarrative = true,
            file = file,
            line = line,
            phase = phase,
        )

    protected fun expectedCannotDerive(
        vararg whyFragments: String,
        fixFragments: List<String> = emptyList(),
        file: String? = null,
        line: Int? = null,
        phase: DiagnosticPhase? = DiagnosticPhase.IR,
    ): ExpectedDiagnostic.Error =
        expectedStructuredDiagnosticId(
            diagnosticId = TypeclassDiagnosticIds.CANNOT_DERIVE,
            headlineFragments = emptyList(),
            whyFragments = whyFragments.toList(),
            fixFragments = fixFragments,
            requireNarrative = true,
            file = file,
            line = line,
            phase = phase,
        )

    protected fun expectedAmbiguousOrNoContext(vararg fragments: String): ExpectedDiagnostic.Error =
        ExpectedDiagnostic.Error(
            diagnosticIds =
                setOf(
                    TypeclassDiagnosticIds.AMBIGUOUS_INSTANCE,
                    TypeclassDiagnosticIds.NO_CONTEXT_ARGUMENT,
                ),
            description = "ambiguous or missing-context diagnostic containing ${fragments.joinToString()}",
            messagePredicate = { message ->
                val normalized = message.lowercase()
                (normalized.contains("ambiguous") || normalized.contains("no context argument")) &&
                    fragments.all { normalized.contains(it.lowercase()) }
            },
        )

    protected fun expectedTypeclassDiagnostic(
        expected: TypeclassDiagnostic,
        file: String? = null,
        line: Int? = null,
        phase: DiagnosticPhase? = null,
    ): ExpectedDiagnostic.Error =
        ExpectedDiagnostic.Error(
            diagnosticIds = setOf(expected.diagnosticId),
            phase = phase,
            file = file,
            line = line,
            description = "${expected.diagnosticId} exact narrative=$expected",
            messagePredicate = { message ->
                parseTypeclassDiagnostic(message, expected.diagnosticId) == expected
            },
        )

    protected fun expectedExactInvalidInstanceDecl(
        why: String,
        file: String? = null,
        line: Int? = null,
        phase: DiagnosticPhase? = DiagnosticPhase.FIR,
    ): ExpectedDiagnostic.Error =
        expectedTypeclassDiagnostic(
            expected = invalidInstanceDiagnostic(why),
            file = file,
            line = line,
            phase = phase,
        )

    protected fun expectedExactInvalidEquivDecl(
        why: String,
        file: String? = null,
        line: Int? = null,
        phase: DiagnosticPhase? = DiagnosticPhase.FIR,
    ): ExpectedDiagnostic.Error =
        expectedTypeclassDiagnostic(
            expected = invalidEquivDiagnostic(why),
            file = file,
            line = line,
            phase = phase,
        )

    protected fun expectedExactCannotDerive(
        why: String,
        file: String? = null,
        line: Int? = null,
        phase: DiagnosticPhase? = DiagnosticPhase.IR,
    ): ExpectedDiagnostic.Error =
        expectedTypeclassDiagnostic(
            expected = cannotDeriveDiagnostic(why),
            file = file,
            line = line,
            phase = phase,
        )

    protected fun expectedExactNoContextArgument(
        goal: String,
        scope: String = "",
        recursive: Boolean = false,
        file: String? = null,
        line: Int? = null,
        phase: DiagnosticPhase? = DiagnosticPhase.IR,
    ): ExpectedDiagnostic.Error =
        expectedTypeclassDiagnostic(
            expected =
                TypeclassDiagnostic.NoContextArgument(
                    goal = goal,
                    scope = scope,
                    recursive = recursive,
                ),
            file = file,
            line = line,
            phase = phase,
        )

    protected fun expectedExactAmbiguousInstance(
        goal: String,
        scope: String = "",
        candidates: List<String>,
        file: String? = null,
        line: Int? = null,
        phase: DiagnosticPhase? = DiagnosticPhase.IR,
    ): ExpectedDiagnostic.Error =
        expectedTypeclassDiagnostic(
            expected =
                TypeclassDiagnostic.AmbiguousInstance(
                    goal = goal,
                    scope = scope,
                    candidates = candidates,
                ),
            file = file,
            line = line,
            phase = phase,
        )

    protected fun assertCompilesAndRuns(
        source: String,
        expectedStdout: String,
        mainClass: String = "demo.SampleKt",
        requiredPlugins: List<CompilerHarnessPlugin> = emptyList(),
        pluginOptions: List<String> = emptyList(),
        dependencies: List<HarnessDependency> = emptyList(),
        useTypeclassPlugin: Boolean = true,
        enableContextParameters: Boolean = true,
        compilerArguments: List<String> = emptyList(),
    ) {
        assertCompilesAndRuns(
            sources = mapOf("Sample.kt" to source),
            expectedStdout = expectedStdout,
            mainClass = mainClass,
            requiredPlugins = requiredPlugins,
            pluginOptions = pluginOptions,
            dependencies = dependencies,
            useTypeclassPlugin = useTypeclassPlugin,
            enableContextParameters = enableContextParameters,
            compilerArguments = compilerArguments,
        )
    }

    protected fun assertCompilesAndRuns(
        sources: Map<String, String>,
        expectedStdout: String,
        mainClass: String,
        requiredPlugins: List<CompilerHarnessPlugin> = emptyList(),
        pluginOptions: List<String> = emptyList(),
        dependencies: List<HarnessDependency> = emptyList(),
        useTypeclassPlugin: Boolean = true,
        enableContextParameters: Boolean = true,
        compilerArguments: List<String> = emptyList(),
    ) {
        val artifacts =
            compileSource(
                sources = sources,
                requiredPlugins = requiredPlugins,
                pluginOptions = pluginOptions,
                dependencies = dependencies,
                useTypeclassPlugin = useTypeclassPlugin,
                enableContextParameters = enableContextParameters,
                compilerArguments = compilerArguments,
            )
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

    protected fun runCompiledMain(
        artifacts: CompilationArtifacts,
        mainClass: String,
    ): String {
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
        return stdout.trim()
    }

    protected fun assertOutputContains(
        output: String,
        vararg fragments: String,
    ) {
        fragments.forEach { fragment ->
            assertTrue(
                output.contains(fragment),
                buildString {
                    appendLine("Expected compiler output to contain:")
                    appendLine(fragment)
                    appendLine("Full output:")
                    append(output)
                },
            )
        }
    }

    protected fun assertOutputNotContains(
        output: String,
        vararg fragments: String,
    ) {
        fragments.forEach { fragment ->
            assertFalse(
                output.contains(fragment),
                buildString {
                    appendLine("Expected compiler output not to contain:")
                    appendLine(fragment)
                    appendLine("Full output:")
                    append(output)
                },
            )
        }
    }

    protected fun compileSource(
        sources: Map<String, String>,
        requiredPlugins: List<CompilerHarnessPlugin> = emptyList(),
        pluginOptions: List<String> = emptyList(),
        dependencies: List<HarnessDependency> = emptyList(),
        useTypeclassPlugin: Boolean = true,
        enableContextParameters: Boolean = true,
        compilerArguments: List<String> = emptyList(),
    ): CompilationArtifacts {
        val result =
            compileSourceInternal(
                sources = sources,
                requiredPlugins = requiredPlugins,
                pluginOptions = pluginOptions,
                dependencies = dependencies,
                useTypeclassPlugin = useTypeclassPlugin,
                enableContextParameters = enableContextParameters,
                compilerArguments = compilerArguments,
            )
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
        useTypeclassPlugin: Boolean = true,
        enableContextParameters: Boolean = true,
        compilerArguments: List<String> = emptyList(),
    ): CompilationArtifacts {
        return compileSource(
            sources = mapOf("Sample.kt" to source),
            requiredPlugins = requiredPlugins,
            pluginOptions = pluginOptions,
            dependencies = dependencies,
            useTypeclassPlugin = useTypeclassPlugin,
            enableContextParameters = enableContextParameters,
            compilerArguments = compilerArguments,
        )
    }

    protected fun compileSourceInternal(
        source: String,
        requiredPlugins: List<CompilerHarnessPlugin> = emptyList(),
        pluginOptions: List<String> = emptyList(),
        dependencies: List<HarnessDependency> = emptyList(),
        useTypeclassPlugin: Boolean = true,
        enableContextParameters: Boolean = true,
        compilerArguments: List<String> = emptyList(),
    ): CompilationResult {
        return compileSourceInternal(
            sources = mapOf("Sample.kt" to source),
            requiredPlugins = requiredPlugins,
            pluginOptions = pluginOptions,
            dependencies = dependencies,
            useTypeclassPlugin = useTypeclassPlugin,
            enableContextParameters = enableContextParameters,
            compilerArguments = compilerArguments,
        )
    }

    protected fun compileSourceInternal(
        sources: Map<String, String>,
        requiredPlugins: List<CompilerHarnessPlugin> = emptyList(),
        pluginOptions: List<String> = emptyList(),
        dependencies: List<HarnessDependency> = emptyList(),
        useTypeclassPlugin: Boolean = true,
        enableContextParameters: Boolean = true,
        compilerArguments: List<String> = emptyList(),
    ): CompilationResult {
        val plan =
            buildCompilationPlan(
                sources = sources,
                settings =
                    HarnessCompilationSettings(
                        requiredPlugins = requiredPlugins,
                        pluginOptions = pluginOptions,
                        useTypeclassPlugin = useTypeclassPlugin,
                        enableContextParameters = enableContextParameters,
                        compilerArguments = compilerArguments,
                    ),
                dependencies = dependencies,
            )
        return executeCompilationPlan(plan)
    }

    private fun buildCompilationPlan(
        sources: Map<String, String>,
        settings: HarnessCompilationSettings,
        dependencies: List<HarnessDependency>,
    ): HarnessCompilationPlan {
        var nextStepId = 0

        fun createStep(
            preferredName: String?,
            stepSources: Map<String, String>,
            stepSettings: HarnessCompilationSettings,
            stepDependencies: List<HarnessDependency>,
        ): HarnessCompilationStep {
            val stepId = nextStepId++
            val stepName = preferredName ?: if (stepId == 0) "main" else "step-$stepId"
            return HarnessCompilationStep(
                id = stepId,
                name = stepName,
                sources = stepSources,
                settings = stepSettings,
                dependencies =
                    stepDependencies.map { dependency ->
                        createStep(
                            preferredName = dependency.name,
                            stepSources = dependency.sources,
                            stepSettings = dependency.toSettings(),
                            stepDependencies = dependency.dependencies,
                        )
                    },
            )
        }

        val root =
            createStep(
                preferredName = "main",
                stepSources = sources,
                stepSettings = settings,
                stepDependencies = dependencies,
            )
        return HarnessCompilationPlan(
            root = root,
            orderedSteps = buildCompilationOrder(root),
        )
    }

    private fun buildCompilationOrder(root: HarnessCompilationStep): List<HarnessCompilationStep> {
        val ordered = mutableListOf<HarnessCompilationStep>()

        fun visit(step: HarnessCompilationStep) {
            step.dependencies.forEach(::visit)
            ordered += step
        }

        visit(root)
        return ordered
    }

    private fun executeCompilationPlan(plan: HarnessCompilationPlan): CompilationResult {
        val resultsByStepId = linkedMapOf<Int, CompilationResult>()
        plan.orderedSteps.forEach { step ->
            val dependencyArtifacts =
                step.dependencies.map { dependency ->
                    val dependencyResult =
                        requireNotNull(resultsByStepId[dependency.id]) {
                            "Missing compilation result for dependency step ${dependency.name}"
                        }
                    assertEquals(
                        ExitCode.OK,
                        dependencyResult.exitCode,
                        dependencyResult.stdout,
                    )
                    dependencyResult.artifacts
                }
            resultsByStepId[step.id] = compileSingleStep(step, dependencyArtifacts)
        }
        return requireNotNull(resultsByStepId[plan.root.id]) {
            "Missing compilation result for root step ${plan.root.name}"
        }
    }

    private fun compileSingleStep(
        step: HarnessCompilationStep,
        dependencyArtifacts: List<CompilationArtifacts>,
    ): CompilationResult {
        require(step.settings.useTypeclassPlugin || step.settings.pluginOptions.isEmpty()) {
            "Typeclass plugin options require the typeclass plugin for step ${step.name}"
        }
        val workingDir = registerTempRootForCurrentTest(createTempDirectory(prefix = "typeclass-${step.id}-${sanitizeStepName(step.name)}-"))
        val outputDir = workingDir.resolve("out")
        outputDir.createDirectories()
        val supportSources =
            buildMap<String, String> {
                step.settings.requiredPlugins.forEach { plugin ->
                    putAll(plugin.supportSources)
                }
            }
        val effectiveSources = supportSources + step.sources
        val sourceFiles =
            effectiveSources.map { (relativePath, contents) ->
                workingDir.resolve(relativePath).also { sourceFile ->
                    sourceFile.parent?.createDirectories()
                    sourceFile.writeText(contents)
                }
            }

        val repositoryRoot = locateRepositoryRoot()
        val pluginProjectRoot = locateCompilerPluginProjectRoot(repositoryRoot)
        val runtimeProjectRoot = locateRuntimeProjectRoot(repositoryRoot)
        val pluginJar =
            if (step.settings.useTypeclassPlugin) {
                locateBuiltJar(pluginProjectRoot.resolve("build/libs"), "kotlin-typeclasses-plugin")
                    .let { builtJar ->
                        val isolatedPluginJar = workingDir.resolve("plugin").resolve(builtJar.fileName.toString())
                        isolatedPluginJar.parent.createDirectories()
                        Files.copy(builtJar, isolatedPluginJar)
                        isolatedPluginJar
                    }
            } else {
                null
            }
        val runtimeClasspathEntry = locateRuntimeClasspathEntry(runtimeProjectRoot)
        val stdlibJar = locateStdlibJar()
        val resolvedSupport = resolveHarnessSupport(step.settings.requiredPlugins)

        pluginJar?.let { isolatedPluginJar ->
            assertTrue(isolatedPluginJar.toFile().isFile, "Plugin jar is missing at $isolatedPluginJar")
        }
        assertTrue(runtimeClasspathEntry.toFile().exists(), "Runtime classpath entry is missing at $runtimeClasspathEntry")
        assertTrue(stdlibJar.toFile().isFile, "Kotlin stdlib jar is missing at $stdlibJar")
        resolvedSupport.runtimeClasspathEntries.forEach { runtimeJar ->
            assertTrue(runtimeJar.toFile().isFile, "Support runtime jar is missing at $runtimeJar")
        }
        resolvedSupport.compilerPluginJars.forEach { compilerPluginJar ->
            assertTrue(compilerPluginJar.toFile().isFile, "Support compiler plugin jar is missing at $compilerPluginJar")
        }

        val compilerPluginClasspathEntries =
            linkedSetOf<Path>().apply {
                pluginJar?.let(::add)
                addAll(resolvedSupport.compilerPluginJars)
                if (isNotEmpty() && activeKotlinVersion.startsWith("2.4")) {
                    add(stdlibJar)
                }
            }.toList()

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
            step.settings.requiredPlugins.fold(DEFAULT_HARNESS_JVM_TARGET) { current, plugin ->
                maxJvmTarget(current, plugin.minimumJvmTarget)
            }
        val compilerArguments =
            buildList {
                if (step.settings.enableContextParameters) {
                    add("-Xcontext-parameters")
                }
                add("-no-stdlib")
                add("-no-reflect")
                add("-jvm-target")
                add(jvmTarget)
                compilerPluginClasspathEntries.forEach { compilerPluginEntry ->
                    add("-Xplugin=${compilerPluginEntry.toAbsolutePath()}")
                }
                addAll(resolvedSupport.compilerArguments)
                addAll(step.settings.compilerArguments)
                add("-classpath")
                add(runtimeClasspathEntries.joinToString(separator = java.io.File.pathSeparator) { it.toAbsolutePath().toString() })
                step.settings.pluginOptions.forEach { option ->
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

    private fun sanitizeStepName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').ifEmpty { "step" }

    private fun registerTempRootForCurrentTest(path: Path): Path =
        path.also { candidate ->
            synchronized(tempRootsToCleanup) {
                tempRootsToCleanup.add(candidate)
            }
        }

    private fun cleanupRegisteredTempRoots() {
        val roots =
            synchronized(tempRootsToCleanup) {
                tempRootsToCleanup.toList().also { tempRootsToCleanup.clear() }
            }
        if (keepTempRootsForCurrentTest || keepTestTempDirectories()) {
            return
        }
        roots
            .distinct()
            .sortedByDescending { path -> path.nameCount }
            .forEach(::deleteRecursivelyOrThrow)
    }

    private fun keepTestTempDirectories(): Boolean =
        System.getenv(KEEP_TEST_TEMP_ENV)
            ?.trim()
            ?.lowercase()
            ?.let { value -> value.isNotEmpty() && value != "0" && value != "false" && value != "no" }
            ?: false

    private fun locateRepositoryRoot(): Path {
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
                candidate.resolve("settings.gradle.kts").toFile().isFile &&
                    (
                        candidate.resolve("compiler-plugin/build.gradle.kts").toFile().isFile ||
                            candidate.resolve("src/main/kotlin/one/wabbit/typeclass/plugin/TypeclassCompilerPluginRegistrar.kt").toFile().isFile
                    )
            }
            ?: error("Could not locate the kotlin-typeclasses repository root from $candidates")
    }

    private fun locateCompilerPluginProjectRoot(repositoryRoot: Path): Path {
        val multiModuleRoot = repositoryRoot.resolve("compiler-plugin")
        return if (multiModuleRoot.resolve("build.gradle.kts").toFile().isFile) {
            multiModuleRoot
        } else {
            repositoryRoot
        }
    }

    private fun locateRuntimeProjectRoot(repositoryRoot: Path): Path {
        val multiModuleRuntimeRoot = repositoryRoot.resolve("library")
        if (multiModuleRuntimeRoot.resolve("build.gradle.kts").toFile().isFile) {
            return multiModuleRuntimeRoot
        }

        val legacyRuntimeRoot = repositoryRoot.resolve("kotlin-typeclasses")
        if (legacyRuntimeRoot.resolve("build.gradle.kts").toFile().isFile) {
            return legacyRuntimeRoot
        }

        error("Could not locate the kotlin-typeclasses runtime project from $repositoryRoot")
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
        val parsedMessage = parseDiagnosticMessage(match.groupValues[5])
        return ReportedDiagnostic(
            file = match.groupValues[1],
            line = match.groupValues[2].toInt(),
            column = match.groupValues[3].toInt(),
            severity = parseDiagnosticSeverity(match.groupValues[4]),
            message = parsedMessage.message,
            diagnosticId = parsedMessage.diagnosticId,
            phase = inferDiagnosticPhase(parsedMessage, hasLocation = true),
            rawLine = line,
        )
    }

    private fun parseGlobalDiagnostic(line: String): ReportedDiagnostic? {
        val match = globalDiagnosticRegex.matchEntire(line) ?: return null
        val parsedMessage = parseDiagnosticMessage(match.groupValues[2])
        return ReportedDiagnostic(
            file = null,
            line = null,
            column = null,
            severity = parseDiagnosticSeverity(match.groupValues[1]),
            message = parsedMessage.message,
            diagnosticId = parsedMessage.diagnosticId,
            phase = inferDiagnosticPhase(parsedMessage, hasLocation = false),
            rawLine = line,
        )
    }

    private fun parseDiagnosticMessage(message: String): ParsedDiagnosticMessage {
        val trimmed = message.trim()
        val explicitMatch = prefixedDiagnosticIdRegex.matchEntire(trimmed)
        if (explicitMatch != null) {
            return ParsedDiagnosticMessage(
                message = explicitMatch.groupValues[2],
                diagnosticId = explicitMatch.groupValues[1],
                explicitDiagnosticId = true,
            )
        }
        return ParsedDiagnosticMessage(
            message = trimmed,
            diagnosticId = inferDiagnosticIdFromMessage(trimmed),
            explicitDiagnosticId = false,
        )
    }

    private fun inferDiagnosticIdsFromFragments(fragments: List<String>): Set<String>? {
        val normalizedFragments = fragments.map(String::lowercase)
        return when {
            normalizedFragments.any { it == "no context argument" } ->
                setOf(TypeclassDiagnosticIds.NO_CONTEXT_ARGUMENT)
            normalizedFragments.any { it == "ambiguous typeclass instance" } ->
                setOf(TypeclassDiagnosticIds.AMBIGUOUS_INSTANCE)
            normalizedFragments.any { it == "invalid @instance declaration" } ->
                setOf(TypeclassDiagnosticIds.INVALID_INSTANCE_DECL)
            normalizedFragments.any { it == "cannot derive" } ->
                setOf(TypeclassDiagnosticIds.CANNOT_DERIVE)
            else -> null
        }
    }

    private fun inferDiagnosticIdFromMessage(message: String): String? =
        parseTypeclassDiagnostic(message)?.diagnosticId?.takeIf { it.startsWith("TC_") }
            ?: run {
                val normalized = message.lowercase()
                when {
                    normalized.startsWith("no context argument for ") ->
                        TypeclassDiagnosticIds.NO_CONTEXT_ARGUMENT
                    normalized.startsWith("ambiguous typeclass instance for ") ->
                        TypeclassDiagnosticIds.AMBIGUOUS_INSTANCE
                    normalized.startsWith("invalid @instance declaration:") ->
                        TypeclassDiagnosticIds.INVALID_INSTANCE_DECL
                    normalized.startsWith("invalid equiv declaration:") ->
                        TypeclassDiagnosticIds.INVALID_EQUIV_DECL
                    normalized.startsWith("cannot derive") ->
                        TypeclassDiagnosticIds.CANNOT_DERIVE
                    else -> null
                }
            }

    private fun inferDiagnosticPhase(
        parsedMessage: ParsedDiagnosticMessage,
        hasLocation: Boolean,
    ): DiagnosticPhase =
        when (parsedMessage.diagnosticId) {
            TypeclassDiagnosticIds.INVALID_INSTANCE_DECL -> DiagnosticPhase.FIR
            TypeclassDiagnosticIds.INVALID_EQUIV_DECL -> DiagnosticPhase.FIR
            TypeclassDiagnosticIds.AMBIGUOUS_INSTANCE -> DiagnosticPhase.IR
            TypeclassDiagnosticIds.CANNOT_DERIVE -> DiagnosticPhase.IR
            TypeclassDiagnosticIds.NO_CONTEXT_ARGUMENT ->
                when {
                    parsedMessage.explicitDiagnosticId -> DiagnosticPhase.IR
                    parsedMessage.message.lowercase().startsWith("missing typeclass instance for ") ||
                        parsedMessage.message.lowercase().startsWith("recursive typeclass resolution for ") -> DiagnosticPhase.IR
                    else -> DiagnosticPhase.FIR
                }
            TypeclassDiagnosticIds.INVALID_BUILTIN_EVIDENCE -> DiagnosticPhase.IR
            else ->
                when {
                    !hasLocation &&
                        (
                            parsedMessage.message.lowercase().startsWith("missing typeclass instance for ") ||
                                parsedMessage.message.lowercase().startsWith("recursive typeclass resolution for ")
                        ) -> DiagnosticPhase.IR
                    hasLocation -> DiagnosticPhase.FIR
                    else -> DiagnosticPhase.UNKNOWN
                }
        }

    private fun expectedDiagnosticsFromSourceMarkers(
        markers: List<SourceDiagnosticMarker>,
        labeledDiagnostics: Map<String, TypeclassDiagnostic> = emptyMap(),
    ): List<ExpectedDiagnostic> {
        val labeledMarkers =
            buildMap<String, SourceDiagnosticMarker> {
                markers.filter { it.label != null }.forEach { marker ->
                    val label = marker.label ?: error("unreachable")
                    val previous = put(label, marker)
                    check(previous == null) {
                        "Duplicate source diagnostic marker label '$label' at ${marker.file}:${marker.line}"
                    }
                }
            }
        val missingExpectedLabels = labeledMarkers.keys - labeledDiagnostics.keys
        check(missingExpectedLabels.isEmpty()) {
            "Missing expected typed diagnostics for source labels: ${missingExpectedLabels.sorted().joinToString()}"
        }
        val unusedExpectedLabels = labeledDiagnostics.keys - labeledMarkers.keys
        check(unusedExpectedLabels.isEmpty()) {
            "Expected typed diagnostics reference missing source labels: ${unusedExpectedLabels.sorted().joinToString()}"
        }
        return markers.map { marker ->
            val labeledDiagnostic = marker.label?.let(labeledDiagnostics::getValue)
            if (labeledDiagnostic != null) {
                check(marker.severity == DiagnosticSeverity.ERROR) {
                    "Typed source diagnostic labels currently support only errors: ${marker.render()}"
                }
                check(marker.diagnosticId != null) {
                    "Typed source diagnostic label '${marker.label}' must declare a TC_* diagnostic id."
                }
                check(labeledDiagnostic.diagnosticId == marker.diagnosticId) {
                    "Typed source diagnostic label '${marker.label}' expects ${labeledDiagnostic.diagnosticId}, but source marker declares ${marker.diagnosticId}"
                }
                expectedTypeclassDiagnostic(
                    expected = labeledDiagnostic,
                    file = marker.file,
                    line = marker.line,
                )
            } else {
                when (marker.severity) {
                    DiagnosticSeverity.ERROR ->
                        ExpectedDiagnostic.Error(
                            file = marker.file,
                            line = marker.line,
                            diagnosticIds = marker.diagnosticId?.let(::setOf),
                            description = "source marker ${marker.render()}",
                        )

                    DiagnosticSeverity.WARNING ->
                        ExpectedDiagnostic.Warning(
                            file = marker.file,
                            line = marker.line,
                            diagnosticIds = marker.diagnosticId?.let(::setOf),
                            description = "source marker ${marker.render()}",
                        )
                }
            }
        }
    }

    private fun parseSourceDiagnosticMarkers(sources: Map<String, String>): List<SourceDiagnosticMarker> =
        sources.entries.flatMap { (file, source) ->
            source.lineSequence().mapIndexedNotNull { index, line ->
                val commentStart = line.indexOf("//")
                if (commentStart < 0) {
                    return@mapIndexedNotNull null
                }
                val comment = line.substring(commentStart + 2)
                val markers = sourceDiagnosticMarkerRegex.findAll(comment).toList()
                if (markers.isEmpty()) {
                    return@mapIndexedNotNull null
                }
                markers.map { marker ->
                    SourceDiagnosticMarker(
                        label = marker.groupValues[1].ifEmpty { null },
                        severity = parseDiagnosticSeverity(marker.groupValues[2]),
                        diagnosticId = marker.groupValues[3].ifEmpty { null },
                        file = file,
                        line = index + 1,
                    )
                }
            }.flatten().toList()
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

    private fun automaticNarrativeFragments(diagnosticIds: Set<String>?): List<String> =
        when {
            diagnosticIds == null -> emptyList()
            TypeclassDiagnosticIds.INVALID_INSTANCE_DECL in diagnosticIds -> invalidInstanceNarrativeFragments()
            TypeclassDiagnosticIds.INVALID_EQUIV_DECL in diagnosticIds -> invalidEquivNarrativeFragments()
            TypeclassDiagnosticIds.CANNOT_DERIVE in diagnosticIds -> cannotDeriveNarrativeFragments()
            TypeclassDiagnosticIds.AMBIGUOUS_INSTANCE in diagnosticIds -> ambiguousNarrativeFragments()
            else -> emptyList()
        }

    private fun invalidInstanceNarrativeFragments(): List<String> = listOf("why it failed", "how to fix")

    private fun invalidEquivNarrativeFragments(): List<String> = listOf("why it failed", "how to fix")

    private fun cannotDeriveNarrativeFragments(): List<String> = listOf("why it failed", "how to fix")

    private fun ambiguousNarrativeFragments(): List<String> = listOf("why it failed", "how to fix", "candidates")

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
                preferredJarCandidate(candidates, description)
                    ?: error(
                        buildString {
                            appendLine("Found multiple candidates for $description:")
                            candidates.forEach { candidate ->
                                appendLine(candidate.toAbsolutePath().toString())
                            }
                        },
                    )
        }

    private fun preferredJarCandidate(
        candidates: List<Path>,
        description: String,
    ): Path? {
        if (description == "built kotlin-typeclasses-plugin jar") {
            candidates.firstOrNull { candidate ->
                candidate.fileName.toString().contains("-kotlin-$activeKotlinVersion")
            }?.let { return it }
            return candidates.firstOrNull { candidate ->
                candidate.fileName.toString().contains("-kotlin-")
            }
        }
        if (description.startsWith("runtime-desktop ")) {
            return candidates.firstOrNull { candidate ->
                "androidx.compose.runtime" in candidate.toString()
            }
        }
        return null
    }

    protected fun expectedStructuredDiagnosticId(
        diagnosticId: String,
        headlineFragments: List<String> = emptyList(),
        whyFragments: List<String> = emptyList(),
        fixFragments: List<String> = emptyList(),
        file: String? = null,
        line: Int? = null,
        phase: DiagnosticPhase? = null,
        requireNarrative: Boolean = true,
    ): ExpectedDiagnostic.Error =
        ExpectedDiagnostic.Error(
            diagnosticIds = setOf(diagnosticId),
            phase = phase,
            file = file,
            line = line,
            description =
                buildString {
                    append(diagnosticId)
                    append(" headline=")
                    append(headlineFragments)
                    append(" why=")
                    append(whyFragments)
                    append(" fix=")
                    append(fixFragments)
                },
            messagePredicate = { message ->
                val narrative = parseTypeclassDiagnostic(message, diagnosticId)
                if (requireNarrative && narrative == null) {
                    false
                } else {
                    val headline = narrative?.headline?.lowercase() ?: message.lowercase()
                    val why = narrative?.reason?.lowercase()
                    val fix = narrative?.fix?.lowercase()
                    headlineFragments.all { headline.contains(it.lowercase()) } &&
                        whyFragments.all { fragment -> why?.contains(fragment.lowercase()) == true } &&
                        fixFragments.all { fragment -> fix?.contains(fragment.lowercase()) == true }
                }
            },
        )
}

data class CompilationArtifacts(
    val outputDir: Path,
    val runtimeClasspathEntries: List<Path>,
)

data class HarnessDependency(
    val name: String? = null,
    val sources: Map<String, String>,
    val requiredPlugins: List<CompilerHarnessPlugin> = emptyList(),
    val pluginOptions: List<String> = emptyList(),
    val dependencies: List<HarnessDependency> = emptyList(),
    val useTypeclassPlugin: Boolean = true,
    val enableContextParameters: Boolean = true,
    val compilerArguments: List<String> = emptyList(),
)

data class CompilationResult(
    val exitCode: ExitCode,
    val stdout: String,
    val artifacts: CompilationArtifacts,
)

private data class HarnessCompilationSettings(
    val requiredPlugins: List<CompilerHarnessPlugin> = emptyList(),
    val pluginOptions: List<String> = emptyList(),
    val useTypeclassPlugin: Boolean = true,
    val enableContextParameters: Boolean = true,
    val compilerArguments: List<String> = emptyList(),
)

private data class HarnessCompilationStep(
    val id: Int,
    val name: String,
    val sources: Map<String, String>,
    val settings: HarnessCompilationSettings,
    val dependencies: List<HarnessCompilationStep>,
)

private data class HarnessCompilationPlan(
    val root: HarnessCompilationStep,
    val orderedSteps: List<HarnessCompilationStep>,
)

private fun HarnessDependency.toSettings(): HarnessCompilationSettings =
    HarnessCompilationSettings(
        requiredPlugins = requiredPlugins,
        pluginOptions = pluginOptions,
        useTypeclassPlugin = useTypeclassPlugin,
        enableContextParameters = enableContextParameters,
        compilerArguments = compilerArguments,
    )

private fun deleteRecursivelyOrThrow(path: Path) {
    val file = path.toFile()
    if (!file.exists()) {
        return
    }
    check(file.deleteRecursively()) {
        "Failed to delete temporary test directory $path"
    }
}

enum class DiagnosticSeverity {
    ERROR,
    WARNING,
}

enum class DiagnosticPhase {
    FIR,
    IR,
    UNKNOWN,
}

data class ReportedDiagnostic(
    val file: String?,
    val line: Int?,
    val column: Int?,
    val severity: DiagnosticSeverity,
    val message: String,
    val diagnosticId: String?,
    val phase: DiagnosticPhase,
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
            diagnosticId?.let { id ->
                append(TypeclassDiagnosticIds.prefix(id))
                append(' ')
            }
            if (phase != DiagnosticPhase.UNKNOWN) {
                append('(')
                append(phase.name)
                append(") ")
            }
            append(message)
        }
    }
}

private data class ParsedDiagnosticMessage(
    val message: String,
    val diagnosticId: String?,
    val explicitDiagnosticId: Boolean,
)

private data class SourceDiagnosticMarker(
    val label: String?,
    val severity: DiagnosticSeverity,
    val diagnosticId: String?,
    val file: String,
    val line: Int,
) {
    fun render(): String =
        buildString {
            label?.let {
                append(it)
                append('@')
            }
            append(
                when (severity) {
                    DiagnosticSeverity.ERROR -> 'E'
                    DiagnosticSeverity.WARNING -> 'W'
                },
            )
            diagnosticId?.let {
                append(':')
                append(it)
            }
        }
}

sealed class ExpectedDiagnostic private constructor(
    private val severity: DiagnosticSeverity,
    private val diagnosticIds: Set<String>?,
    private val phase: DiagnosticPhase?,
    private val file: String?,
    private val line: Int?,
    private val description: String,
    private val messagePredicate: (String) -> Boolean,
) {
    fun matches(actual: ReportedDiagnostic): Boolean =
        actual.severity == severity &&
            matchesDiagnosticId(actual.diagnosticId) &&
            matchesPhase(actual.phase) &&
            matchesLocation(actual) &&
            messagePredicate(actual.message)

    private fun matchesDiagnosticId(actualDiagnosticId: String?): Boolean =
        diagnosticIds == null || actualDiagnosticId in diagnosticIds

    private fun matchesPhase(actualPhase: DiagnosticPhase): Boolean =
        phase == null || phase == actualPhase

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

    private fun matchesLocation(actual: ReportedDiagnostic): Boolean {
        // Some Kotlin-owned FIR diagnostics are emitted without a file/line even
        // though the source test still points at the intended failing call site with
        // an inline marker. Keep the locationless fallback only for those non-plugin
        // errors; plugin-owned TC_* diagnostics are expected to carry a real source
        // location and should be fixed at the emitter if they do not.
        if (actual.file == null && actual.line == null) {
            return actual.diagnosticId == null
        }
        return matchesFile(actual.file) && (line == null || actual.line == line)
    }

    override fun toString(): String =
        buildString {
            append(severity.name.lowercase())
            append('(')
            append(file ?: "*")
            append(':')
            append(line ?: "*")
            append(", ")
            diagnosticIds?.joinToString(prefix = "[", postfix = "] ")?.let(::append)
            phase?.let { expectedPhase ->
                append('(')
                append(expectedPhase.name)
                append(") ")
            }
            append(description)
            append(')')
        }

    class Error(
        file: String? = null,
        line: Int? = null,
        diagnosticIds: Set<String>? = null,
        phase: DiagnosticPhase? = null,
        messageRegex: String? = null,
        description: String? = null,
        messagePredicate: ((String) -> Boolean)? = null,
    ) : ExpectedDiagnostic(
            severity = DiagnosticSeverity.ERROR,
            diagnosticIds = diagnosticIds,
            phase = phase,
            file = file,
            line = line,
            description = diagnosticDescription(messageRegex, description, messagePredicate),
            messagePredicate = diagnosticMessagePredicate(messageRegex, messagePredicate),
        )

    class Warning(
        file: String? = null,
        line: Int? = null,
        diagnosticIds: Set<String>? = null,
        phase: DiagnosticPhase? = null,
        messageRegex: String? = null,
        description: String? = null,
        messagePredicate: ((String) -> Boolean)? = null,
    ) : ExpectedDiagnostic(
            severity = DiagnosticSeverity.WARNING,
            diagnosticIds = diagnosticIds,
            phase = phase,
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
                "kotlinx-serialization-json-jvm",
            )
        override val compilerPluginJarMarkers: List<String> =
            listOf("kotlin-serialization-compiler-plugin-embeddable")
    }

    data object SerializationRuntime : CompilerHarnessPlugin {
        override val runtimeClasspathJarMarkers: List<String> =
            listOf(
                "kotlinx-serialization-core-jvm",
                "kotlinx-serialization-json-jvm",
            )
    }

    data object CoroutinesRuntime : CompilerHarnessPlugin {
        override val runtimeClasspathJarMarkers: List<String> =
            listOf("kotlinx-coroutines-core-jvm")
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
