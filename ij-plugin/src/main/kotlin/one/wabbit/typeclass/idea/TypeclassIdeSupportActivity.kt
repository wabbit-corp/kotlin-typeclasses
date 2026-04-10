// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass.idea

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class TypeclassIdeSupportActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        TypeclassIdeSupportCoordinator.enableIfNeeded(project = project, userInitiated = false)
    }
}
