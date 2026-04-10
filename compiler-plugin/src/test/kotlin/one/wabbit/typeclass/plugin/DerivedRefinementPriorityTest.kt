// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.typeclass.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import one.wabbit.typeclass.plugin.model.ResolutionPlan
import one.wabbit.typeclass.plugin.model.ResolutionSearchResult
import one.wabbit.typeclass.plugin.model.TcType
import one.wabbit.typeclass.plugin.model.TypeclassResolutionPlanner

class DerivedRefinementPriorityTest {
    @Test
    fun specificGadtTargetsWinWhenFirDerivedRefinementRulesKeepTheirPriority() {
        val goal = constructor("demo/Show", specificExprTarget)
        val genericRule =
            buildShapeDerivedRefinementRule(
                goal = goal,
                targetOwnerId = "demo/Expr",
                directTypeclassId = "demo/Show",
                targetType = genericExprTarget,
                prerequisiteTypes = emptyList(),
                priority = genericExprTarget.gadtSpecificityScore(),
            )
        val specificRule =
            buildShapeDerivedRefinementRule(
                goal = goal,
                targetOwnerId = "demo/Expr",
                directTypeclassId = "demo/Show",
                targetType = specificExprTarget,
                prerequisiteTypes = emptyList(),
                priority = specificExprTarget.gadtSpecificityScore(),
            )

        val result =
            TypeclassResolutionPlanner(listOf(genericRule, specificRule)).resolve(goal, emptyList())

        val success = assertIs<ResolutionSearchResult.Success>(result)
        val appliedRule = assertIs<ResolutionPlan.ApplyRule>(success.plan)
        assertEquals(specificRule.id, appliedRule.ruleId)
    }

    @Test
    fun equalPriorityFirDerivedRefinementRulesStayAmbiguousForTheSameGoal() {
        val goal = constructor("demo/Show", specificExprTarget)
        val genericRule =
            buildShapeDerivedRefinementRule(
                goal = goal,
                targetOwnerId = "demo/Expr",
                directTypeclassId = "demo/Show",
                targetType = genericExprTarget,
                prerequisiteTypes = emptyList(),
                priority = 0,
            )
        val specificRule =
            buildShapeDerivedRefinementRule(
                goal = goal,
                targetOwnerId = "demo/Expr",
                directTypeclassId = "demo/Show",
                targetType = specificExprTarget,
                prerequisiteTypes = emptyList(),
                priority = 0,
            )

        val result =
            TypeclassResolutionPlanner(listOf(genericRule, specificRule)).resolve(goal, emptyList())

        assertIs<ResolutionSearchResult.Ambiguous>(result)
    }

    private companion object {
        val genericExprTarget = constructor("demo/Expr", variable("A"))
        val specificExprTarget = constructor("demo/Expr", constructor("kotlin/Int"))

        fun constructor(classifierId: String, vararg arguments: TcType) =
            TcType.Constructor(classifierId = classifierId, arguments = arguments.toList())

        fun variable(name: String) = TcType.Variable(id = "test:$name", displayName = name)
    }
}
