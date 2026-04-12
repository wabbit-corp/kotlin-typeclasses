// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass.idea

import one.wabbit.ijplugin.common.ConfiguredRefreshIdeSupportAction

class RefreshTypeclassIdeSupportAction :
    ConfiguredRefreshIdeSupportAction(
        "Refresh Typeclass IDE Support",
        "Re-scan Kotlin compiler arguments and enable typeclass IDE support for this project session",
        TypeclassIdeSupportCoordinator,
    ) {
}
