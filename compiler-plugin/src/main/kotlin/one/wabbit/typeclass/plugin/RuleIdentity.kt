// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass.plugin

import one.wabbit.typeclass.plugin.model.InstanceRule
import one.wabbit.typeclass.plugin.model.TcType
import one.wabbit.typeclass.plugin.model.TcTypeParameter
import one.wabbit.typeclass.plugin.model.normalizedKey

internal fun directRuleId(
    prefix: String,
    declarationKey: String,
    providedType: TcType,
    prerequisiteTypes: List<TcType> = emptyList(),
    typeParameters: List<TcTypeParameter> = emptyList(),
): String =
    buildString {
        append(prefix)
        append(':')
        append(declarationKey)
        append(":provided=")
        append(providedType.normalizedKey())
        append(":prereqs=")
        append(prerequisiteTypes.joinToString(separator = ",") { prerequisite -> prerequisite.normalizedKey() })
        append(":tparams=")
        append(typeParameters.size)
    }

internal fun InstanceRule.directIdentityKey(): String = id
