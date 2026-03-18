package one.wabbit.typeclass.plugin.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WrapperPlannerTest {
    @Test
    fun `local context satisfies a direct requirement`() {
        val a = TcTypeParameter("outer:A", "A")
        val planner = TypeclassResolutionPlanner(emptyList())

        val result =
            planner.resolve(
                desiredType = eq(typeVariable(a)),
                localContextTypes = listOf(eq(typeVariable(a))),
            )

        val success = assertIs<ResolutionSearchResult.Success>(result)
        assertEquals(
            ResolutionPlan.LocalContext(
                index = 0,
                providedType = eq(typeVariable(a)),
            ),
            success.plan,
        )
    }

    @Test
    fun `pair rule derives pair evidence from local evidence`() {
        val a = TcTypeParameter("outer:A", "A")
        val x = TcTypeParameter("rule:X", "X")
        val y = TcTypeParameter("rule:Y", "Y")

        val pairRule =
            InstanceRule(
                id = "pairEq",
                typeParameters = listOf(x, y),
                providedType = eq(pair(typeVariable(x), typeVariable(y))),
                prerequisiteTypes = listOf(eq(typeVariable(x)), eq(typeVariable(y))),
            )

        val result =
            TypeclassResolutionPlanner(listOf(pairRule)).resolve(
                desiredType = eq(pair(typeVariable(a), typeVariable(a))),
                localContextTypes = listOf(eq(typeVariable(a))),
            )

        val success = assertIs<ResolutionSearchResult.Success>(result)
        val applied = assertIs<ResolutionPlan.ApplyRule>(success.plan)
        assertEquals("pairEq", applied.ruleId)
        assertEquals(
            listOf(typeVariable(a), typeVariable(a)),
            applied.appliedTypeArguments,
        )
        assertEquals(
            listOf(
                ResolutionPlan.LocalContext(0, eq(typeVariable(a))),
                ResolutionPlan.LocalContext(0, eq(typeVariable(a))),
            ),
            applied.prerequisitePlans,
        )
    }

    @Test
    fun `nested pair evidence resolves recursively`() {
        val a = TcTypeParameter("outer:A", "A")
        val b = TcTypeParameter("outer:B", "B")
        val c = TcTypeParameter("outer:C", "C")
        val x = TcTypeParameter("rule:X", "X")
        val y = TcTypeParameter("rule:Y", "Y")

        val pairRule =
            InstanceRule(
                id = "pairEq",
                typeParameters = listOf(x, y),
                providedType = eq(pair(typeVariable(x), typeVariable(y))),
                prerequisiteTypes = listOf(eq(typeVariable(x)), eq(typeVariable(y))),
            )

        val result =
            TypeclassResolutionPlanner(listOf(pairRule)).resolve(
                desiredType = eq(pair(pair(typeVariable(a), typeVariable(b)), typeVariable(c))),
                localContextTypes = listOf(eq(typeVariable(a)), eq(typeVariable(b)), eq(typeVariable(c))),
            )

        val success = assertIs<ResolutionSearchResult.Success>(result)
        val outer = assertIs<ResolutionPlan.ApplyRule>(success.plan)
        val inner = assertIs<ResolutionPlan.ApplyRule>(outer.prerequisitePlans[0])

        assertEquals(
            listOf(pair(typeVariable(a), typeVariable(b)), typeVariable(c)),
            outer.appliedTypeArguments,
        )
        assertEquals(
            listOf(typeVariable(a), typeVariable(b)),
            inner.appliedTypeArguments,
        )
    }

    @Test
    fun `multiple local matches are ambiguous`() {
        val a = TcTypeParameter("outer:A", "A")
        val planner = TypeclassResolutionPlanner(emptyList())

        val result =
            planner.resolve(
                desiredType = eq(typeVariable(a)),
                localContextTypes = listOf(eq(typeVariable(a)), eq(typeVariable(a))),
            )

        val ambiguous = assertIs<ResolutionSearchResult.Ambiguous>(result)
        assertEquals(2, ambiguous.matchingPlans.size)
    }

    @Test
    fun `bindable desired variables can match concrete instance rules`() {
        val a = TcTypeParameter("outer:A", "A")
        val concreteRule =
            InstanceRule(
                id = "boxIntEq",
                typeParameters = emptyList(),
                providedType = eq(box(intType())),
                prerequisiteTypes = emptyList(),
            )

        val result =
            TypeclassResolutionPlanner(
                rules = listOf(concreteRule),
                bindableDesiredVariableIds = setOf(a.id),
            ).resolve(
                desiredType = eq(typeVariable(a)),
                localContextTypes = emptyList(),
            )

        val success = assertIs<ResolutionSearchResult.Success>(result)
        val applied = assertIs<ResolutionPlan.ApplyRule>(success.plan)
        assertEquals("boxIntEq", applied.ruleId)
    }

    private fun typeConstructor(
        classifierId: String,
        vararg arguments: TcType,
    ): TcType = TcType.Constructor(classifierId, arguments.toList())

    private fun typeVariable(parameter: TcTypeParameter): TcType = TcType.Variable(parameter.id, parameter.displayName)

    private fun eq(argument: TcType): TcType = typeConstructor("demo.Eq", argument)

    private fun box(argument: TcType): TcType = typeConstructor("demo.Box", argument)

    private fun intType(): TcType = typeConstructor("kotlin.Int")

    private fun pair(
        first: TcType,
        second: TcType,
    ): TcType = typeConstructor("kotlin.Pair", first, second)
}
