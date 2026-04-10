// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass.idea

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

class RefreshTypeclassIdeSupportAction :
    DumbAwareAction(
        "Refresh Typeclass IDE Support",
        "Re-scan Kotlin compiler arguments and enable typeclass IDE support for this project session",
        null,
    ) {
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        TypeclassIdeSupportCoordinator.enableIfNeeded(project = project, userInitiated = true)
    }
}
