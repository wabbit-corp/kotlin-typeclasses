// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.SpecialNames

internal fun <T> directOrNestedCompanion(
    owner: ClassId,
    directCompanion: T?,
    nestedLookup: (ClassId) -> T?,
): T? = directCompanion ?: nestedLookup(owner.createNestedClassId(SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT))
