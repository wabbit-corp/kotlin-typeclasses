// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.typeclass.gradle

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

class TypeclassGradlePluginFunctionalTest {
    @Test
    fun compilesContextTypeclassCodeThroughGradlePlugin() {
        withManagedTestTempDirectory("typeclass-gradle-plugin-test") { projectDir ->
            val sourceDir = projectDir.resolve("src/main/kotlin/demo").createDirectories()
            val moduleRoot =
                java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
            val repoRoot = moduleRoot.parent
            val kotlinVersion = currentKotlinGradlePluginVersion()
            publishToMavenLocal(repoRoot, ":kotlin-typeclasses:publishToMavenLocal", kotlinVersion)
            publishToMavenLocal(
                repoRoot,
                ":kotlin-typeclasses-plugin:publishToMavenLocal",
                kotlinVersion,
            )

            projectDir
                .resolve("settings.gradle.kts")
                .writeText(
                    """
                rootProject.name = "typeclass-gradle-plugin-smoke"

                pluginManagement {
                    includeBuild("${normalizePath(repoRoot)}")
                    repositories {
                        gradlePluginPortal()
                        mavenCentral()
                    }
                }

                dependencyResolutionManagement {
                    repositories {
                        mavenLocal()
                        mavenCentral()
                    }
                }
                """
                        .trimIndent()
                )

            projectDir
                .resolve("build.gradle.kts")
                .writeText(
                    """
                import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

                plugins {
                    application
                    kotlin("jvm") version "$kotlinVersion"
                    id("one.wabbit.typeclass")
                }

                repositories {
                    mavenLocal()
                    mavenCentral()
                }

                dependencies {
                    implementation("one.wabbit:kotlin-typeclasses:$TYPECLASS_GRADLE_PLUGIN_VERSION")
                }

                kotlin {
                    jvmToolchain(21)
                }

                application {
                    mainClass = "demo.MainKt"
                }

                tasks.register("printCompilerArguments") {
                    val compileKotlin = tasks.named("compileKotlin", KotlinCompile::class.java)
                    dependsOn(compileKotlin)
                    doLast {
                        println("TYPECLASS_ARGS_BEGIN")
                        println(compileKotlin.get().compilerOptions.freeCompilerArgs.get().joinToString("\n"))
                        println("TYPECLASS_ARGS_END")
                    }
                }
                """
                        .trimIndent()
                )

            sourceDir
                .resolve("Main.kt")
                .writeText(
                    """
                    package demo

                    import one.wabbit.typeclass.Instance
                    import one.wabbit.typeclass.Typeclass

                    @Typeclass
                    interface Eq<A> {
                        fun eq(left: A, right: A): Boolean
                    }

                    @Instance
                    object IntEq : Eq<Int> {
                        override fun eq(left: Int, right: Int): Boolean = left == right
                    }

                    @Instance
                    context(_: Eq<A>, _: Eq<B>)
                    fun <A, B> pairEq(): Eq<Pair<A, B>> =
                        object : Eq<Pair<A, B>> {
                            override fun eq(left: Pair<A, B>, right: Pair<A, B>): Boolean =
                                left.first == right.first && left.second == right.second
                        }

                    @Typeclass
                    interface Monoid<A> {
                        fun combine(left: A, right: A): A
                    }

                    @Instance
                    object IntMonoid : Monoid<Int> {
                        override fun combine(left: Int, right: Int): Int = left + right
                    }

                    data class Box<A>(val value: A) {
                        companion object {
                            @Instance
                            context(monoid: Monoid<A>)
                            fun <A> boxMonoid(): Monoid<Box<A>> =
                                object : Monoid<Box<A>> {
                                    override fun combine(left: Box<A>, right: Box<A>): Box<A> =
                                        Box(monoid.combine(left.value, right.value))
                                }
                        }
                    }

                    context(_: Eq<A>)
                    fun <A> foo(a: A): Boolean = true

                    context(_: Eq<A>)
                    fun <A> bar(a: A): Boolean = foo<Pair<A, A>>(a to a)

                    context(monoid: Monoid<A>)
                    operator fun <A> A.plus(other: A): A = monoid.combine(this, other)

                    fun main() {
                        println(bar(1))
                        println((Box(1) + Box(2)).value)
                    }
                    """
                        .trimIndent()
                )

            val result =
                GradleRunner.create()
                    .withProjectDir(projectDir.toFile())
                    .withArguments("build", "run", "printCompilerArguments", "--stacktrace")
                    .build()

            assertTrue(result.output.contains("BUILD SUCCESSFUL"))
            assertTrue(result.task(":build")?.outcome == TaskOutcome.SUCCESS)
            assertEquals(TaskOutcome.SUCCESS, result.task(":run")?.outcome)
            assertTrue(result.output.contains("TYPECLASS_ARGS_BEGIN"))
            assertTrue(result.output.contains("-Xcontext-parameters"))
            assertTrue(result.output.contains("true"))
            assertTrue(result.output.contains("3"))
            assertTrue(projectDir.resolve("build/classes/kotlin/main/demo/MainKt.class").exists())
        }
    }

    private fun normalizePath(path: java.nio.file.Path): String =
        path.toString().replace("\\", "\\\\")

    private inline fun <T> withManagedTestTempDirectory(
        prefix: String,
        block: (java.nio.file.Path) -> T,
    ): T {
        val projectDir = Files.createTempDirectory(prefix)
        var succeeded = false
        try {
            val result = block(projectDir)
            succeeded = true
            return result
        } finally {
            if (succeeded && !keepTestTempDirectories()) {
                deleteRecursivelyOrThrow(projectDir)
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

    private fun publishToMavenLocal(
        moduleRoot: java.nio.file.Path,
        task: String,
        kotlinVersion: String,
    ) {
        val wrapper = moduleRoot.resolve("gradlew")
        require(wrapper.exists()) { "Missing Gradle wrapper at $wrapper" }
        val process =
            ProcessBuilder(
                    wrapper.toAbsolutePath().toString(),
                    "--no-daemon",
                    "-PkotlinVersion=$kotlinVersion",
                    task,
                )
                .directory(moduleRoot.toFile())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        val exitCode = process.waitFor()
        check(exitCode == 0) {
            "publishToMavenLocal failed for $moduleRoot with task $task\n$output"
        }
    }
}
