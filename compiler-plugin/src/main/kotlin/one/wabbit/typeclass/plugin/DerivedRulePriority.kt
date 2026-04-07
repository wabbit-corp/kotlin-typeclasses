// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass.plugin

import one.wabbit.typeclass.plugin.model.InstanceRule
import one.wabbit.typeclass.plugin.model.TcType
import one.wabbit.typeclass.plugin.model.normalizedKey

internal fun buildShapeDerivedRefinementRule(
    goal: TcType.Constructor,
    targetOwnerId: String,
    directTypeclassId: String,
    targetType: TcType.Constructor,
    prerequisiteTypes: List<TcType>,
    priority: Int,
): InstanceRule =
    InstanceRule(
        id =
            directRuleId(
                prefix = "derived",
                declarationKey = "$directTypeclassId:$targetOwnerId:${targetType.normalizedKey()}",
                providedType = goal,
                prerequisiteTypes = prerequisiteTypes,
            ),
        typeParameters = emptyList(),
        providedType = goal,
        prerequisiteTypes = prerequisiteTypes,
        supportsRecursiveResolution = true,
        priority = priority,
    )

internal fun TcType.gadtSpecificityScore(): Int =
    when (this) {
        TcType.StarProjection -> 0
        is TcType.Projected -> 1 + type.gadtSpecificityScore()
        is TcType.Variable -> 0
        is TcType.Constructor -> 1 + arguments.sumOf(TcType::gadtSpecificityScore)
    }
