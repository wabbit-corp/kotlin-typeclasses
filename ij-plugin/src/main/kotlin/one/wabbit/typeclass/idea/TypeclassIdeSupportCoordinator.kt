// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass.idea

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import org.jetbrains.plugins.gradle.util.GradleConstants

internal data class TypeclassIdeSupportResult(
    val scan: TypeclassCompilerPluginScan,
    val projectTrusted: Boolean,
    val registryAlreadyEnabledForExternalPlugins: Boolean,
    val registryUpdated: Boolean,
    val gradleImportRequested: Boolean,
)

internal object TypeclassIdeSupportCoordinator {
    private val logger = Logger.getInstance(TypeclassIdeSupportCoordinator::class.java)
    internal var gradleImportRequester: TypeclassGradleImportRequester =
        DefaultTypeclassGradleImportRequester
    internal var analysisRestarter: TypeclassAnalysisRestarter = DefaultTypeclassAnalysisRestarter

    fun enableIfNeeded(project: Project, userInitiated: Boolean): TypeclassIdeSupportResult {
        val scan = TypeclassCompilerPluginDetector.scan(project)
        val trusted = TrustedProjects.isProjectTrusted(project)
        val registryValue = Registry.get(EXTERNAL_K2_COMPILER_PLUGINS_REGISTRY_KEY)
        val registryAllowsOnlyBundledPlugins = registryValue.asBoolean()
        val enablePlan =
            TypeclassIdeSupportDecision.evaluate(
                scan = scan,
                projectTrusted = trusted,
                registryAllowsOnlyBundledPlugins = registryAllowsOnlyBundledPlugins,
            )
        if (!scan.hasMatches) {
            if (userInitiated) {
                notify(
                    project = project,
                    type = NotificationType.INFORMATION,
                    title = "Typeclass IDE support",
                    content =
                        "No imported Kotlin compiler arguments or Gradle build files reference kotlin-typeclasses-plugin or one.wabbit.typeclass.",
                )
            }
            return TypeclassIdeSupportResult(
                scan = scan,
                projectTrusted = trusted,
                registryAlreadyEnabledForExternalPlugins = !registryAllowsOnlyBundledPlugins,
                registryUpdated = false,
                gradleImportRequested = false,
            )
        }

        if (!trusted) {
            notify(
                project = project,
                type = NotificationType.WARNING,
                title = "Typeclass IDE support is waiting for trust",
                content =
                    "kotlin-typeclasses-plugin was detected, but IntelliJ will not load external compiler plugins until the project is trusted.",
            )
            return TypeclassIdeSupportResult(
                scan = scan,
                projectTrusted = false,
                registryAlreadyEnabledForExternalPlugins = !registryAllowsOnlyBundledPlugins,
                registryUpdated = false,
                gradleImportRequested = false,
            )
        }

        var registryUpdated = false
        if (enablePlan.enableExternalCompilerPlugins) {
            logger.info(
                "Temporarily enabling non-bundled K2 compiler plugins for project ${project.name}"
            )
            registryValue.setValue(false, project)
            registryUpdated = true
        }
        val gradleImportRequested =
            if (enablePlan.requestGradleImport) {
                gradleImportRequester.requestImport(project)
            } else {
                false
            }

        if (enablePlan.restartAnalysis) {
            analysisRestarter.restart(project)
        }

        if (registryUpdated || gradleImportRequested || userInitiated) {
            notify(
                project = project,
                type = NotificationType.INFORMATION,
                title = "Typeclass IDE support is active",
                content =
                    buildEnabledMessage(
                        scan = scan,
                        registryUpdated = registryUpdated,
                        gradleImportRequested = gradleImportRequested,
                    ),
            )
        }

        return TypeclassIdeSupportResult(
            scan = scan,
            projectTrusted = true,
            registryAlreadyEnabledForExternalPlugins = !registryAllowsOnlyBundledPlugins,
            registryUpdated = registryUpdated,
            gradleImportRequested = gradleImportRequested,
        )
    }

    internal fun buildEnabledMessage(
        scan: TypeclassCompilerPluginScan,
        registryUpdated: Boolean,
        gradleImportRequested: Boolean,
    ): String {
        val owners = buildList {
            scan.projectLevelMatch?.let { add("project settings") }
            addAll(scan.moduleMatches.map { match -> "module ${match.ownerName}" })
            if (scan.gradleBuildFiles.isNotEmpty()) {
                add("Gradle build files")
            }
        }
        val prefix =
            if (registryUpdated) {
                "Enabled non-bundled K2 compiler plugins for this project session."
            } else {
                "Non-bundled K2 compiler plugins were already enabled for this project session."
            }
        val ownerSummary = owners.joinToString(", ")
        val refreshMessage =
            when {
                scan.requiresGradleImport && gradleImportRequested ->
                    " Requested a Gradle import because the compiler plugin classpath has not been imported yet."

                scan.requiresGradleImport ->
                    " The Gradle plugin was detected, but the compiler plugin classpath is not imported yet. Reimport the Gradle project."

                else -> ""
            }
        return "$prefix Detected kotlin-typeclasses-plugin in $ownerSummary.$refreshMessage"
    }

    private fun notify(project: Project, type: NotificationType, title: String, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("TypeclassIdeSupport")
            .createNotification(title, content, type)
            .notify(project)
    }
}

internal data class TypeclassIdeSupportDecision(
    val enableExternalCompilerPlugins: Boolean,
    val requestGradleImport: Boolean,
    val restartAnalysis: Boolean,
) {
    companion object {
        fun evaluate(
            scan: TypeclassCompilerPluginScan,
            projectTrusted: Boolean,
            registryAllowsOnlyBundledPlugins: Boolean,
        ): TypeclassIdeSupportDecision {
            if (!scan.hasMatches || !projectTrusted) {
                return TypeclassIdeSupportDecision(
                    enableExternalCompilerPlugins = false,
                    requestGradleImport = false,
                    restartAnalysis = false,
                )
            }
            return TypeclassIdeSupportDecision(
                enableExternalCompilerPlugins = registryAllowsOnlyBundledPlugins,
                requestGradleImport = scan.requiresGradleImport,
                restartAnalysis = true,
            )
        }
    }
}

internal interface TypeclassGradleImportRequester {
    fun requestImport(project: Project): Boolean
}

internal object DefaultTypeclassGradleImportRequester : TypeclassGradleImportRequester {
    override fun requestImport(project: Project): Boolean {
        val basePath = project.basePath ?: return false
        return runCatching {
                ExternalSystemUtil.requestImport(project, basePath, GradleConstants.SYSTEM_ID)
            }
            .onFailure { throwable ->
                Logger.getInstance(TypeclassIdeSupportCoordinator::class.java)
                    .warn("Could not request Gradle import for ${project.name}", throwable)
            }
            .isSuccess
    }
}

internal interface TypeclassAnalysisRestarter {
    fun restart(project: Project)
}

internal object DefaultTypeclassAnalysisRestarter : TypeclassAnalysisRestarter {
    override fun restart(project: Project) {
        DaemonCodeAnalyzer.getInstance(project).restart()
    }
}
