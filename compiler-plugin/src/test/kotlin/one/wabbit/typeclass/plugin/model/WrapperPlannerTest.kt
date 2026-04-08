// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.typeclass.plugin.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

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
    fun `local exact evidence wins over an applicable rule`() {
        val a = TcTypeParameter("outer:A", "A")
        val b = TcTypeParameter("outer:B", "B")
        val x = TcTypeParameter("rule:X", "X")
        val y = TcTypeParameter("rule:Y", "Y")

        val pairRule =
            InstanceRule(
                id = "pairEq",
                typeParameters = listOf(x, y),
                providedType = eq(pair(typeVariable(x), typeVariable(y))),
                prerequisiteTypes = listOf(eq(typeVariable(x)), eq(typeVariable(y))),
            )

        val desired = eq(pair(typeVariable(a), typeVariable(b)))
        val result =
            TypeclassResolutionPlanner(listOf(pairRule)).resolve(
                desiredType = desired,
                localContextTypes = listOf(desired, eq(typeVariable(a)), eq(typeVariable(b))),
            )

        val success = assertIs<ResolutionSearchResult.Success>(result)
        assertEquals(
            ResolutionPlan.LocalContext(index = 0, providedType = desired),
            success.plan,
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
    fun `mutual recursion surfaces recursive failure instead of missing`() {
        val a = TcTypeParameter("outer:A", "A")
        val x = TcTypeParameter("rule:X", "X")
        val fooRule =
            InstanceRule(
                id = "fooFromBar",
                typeParameters = listOf(x),
                providedType = foo(typeVariable(x)),
                prerequisiteTypes = listOf(bar(typeVariable(x))),
            )
        val barRule =
            InstanceRule(
                id = "barFromFoo",
                typeParameters = listOf(x),
                providedType = bar(typeVariable(x)),
                prerequisiteTypes = listOf(foo(typeVariable(x))),
            )

        val result =
            TypeclassResolutionPlanner(listOf(fooRule, barRule)).resolve(
                desiredType = foo(typeVariable(a)),
                localContextTypes = emptyList(),
            )

        assertIs<ResolutionSearchResult.Recursive>(result)
    }

    @Test
    fun `recursive candidates are ignored when a non-recursive rule succeeds`() {
        val a = TcTypeParameter("outer:A", "A")
        val x = TcTypeParameter("rule:X", "X")
        val fooFromBar =
            InstanceRule(
                id = "fooFromBar",
                typeParameters = listOf(x),
                providedType = foo(typeVariable(x)),
                prerequisiteTypes = listOf(bar(typeVariable(x))),
            )
        val barFromFoo =
            InstanceRule(
                id = "barFromFoo",
                typeParameters = listOf(x),
                providedType = bar(typeVariable(x)),
                prerequisiteTypes = listOf(foo(typeVariable(x))),
            )
        val directFoo =
            InstanceRule(
                id = "directFoo",
                typeParameters = listOf(x),
                providedType = foo(typeVariable(x)),
                prerequisiteTypes = emptyList(),
            )

        val result =
            TypeclassResolutionPlanner(listOf(fooFromBar, barFromFoo, directFoo)).resolve(
                desiredType = foo(typeVariable(a)),
                localContextTypes = emptyList(),
            )

        val success = assertIs<ResolutionSearchResult.Success>(result)
        val applied = assertIs<ResolutionPlan.ApplyRule>(success.plan)
        assertEquals("directFoo", applied.ruleId)
        assertEquals(emptyList(), applied.prerequisitePlans)
    }

    @Test
    fun `recursive admissibility is scoped to the active candidate rule`() {
        val a = TcTypeParameter("outer:A", "A")
        val x = TcTypeParameter("rule:X", "X")
        val poisonRecursiveRule =
            InstanceRule(
                id = "derived:poisonRecursiveFoo",
                typeParameters = listOf(x),
                providedType = foo(pair(typeVariable(x), typeVariable(x))),
                prerequisiteTypes = listOf(foo(pair(typeVariable(x), typeVariable(x)))),
                supportsRecursiveResolution = true,
            )
        val fooFromBar =
            InstanceRule(
                id = "customFooFromBar",
                typeParameters = listOf(x),
                providedType = foo(typeVariable(x)),
                prerequisiteTypes = listOf(bar(typeVariable(x))),
            )
        val barFromFoo =
            InstanceRule(
                id = "barFromFoo",
                typeParameters = listOf(x),
                providedType = bar(typeVariable(x)),
                prerequisiteTypes = listOf(foo(typeVariable(x))),
            )

        val result =
            TypeclassResolutionPlanner(listOf(poisonRecursiveRule, fooFromBar, barFromFoo)).resolve(
                desiredType = foo(typeVariable(a)),
                localContextTypes = emptyList(),
            )

        assertIs<ResolutionSearchResult.Recursive>(result)
    }

    @Test
    fun `missing candidates outrank unrelated recursive candidates`() {
        val a = TcTypeParameter("outer:A", "A")
        val x = TcTypeParameter("rule:X", "X")
        val fooFromBar =
            InstanceRule(
                id = "fooFromBar",
                typeParameters = listOf(x),
                providedType = foo(typeVariable(x)),
                prerequisiteTypes = listOf(bar(typeVariable(x))),
            )
        val barFromFoo =
            InstanceRule(
                id = "barFromFoo",
                typeParameters = listOf(x),
                providedType = bar(typeVariable(x)),
                prerequisiteTypes = listOf(foo(typeVariable(x))),
            )
        val fooFromBaz =
            InstanceRule(
                id = "fooFromBaz",
                typeParameters = listOf(x),
                providedType = foo(typeVariable(x)),
                prerequisiteTypes = listOf(baz(typeVariable(x))),
            )

        val result =
            TypeclassResolutionPlanner(listOf(fooFromBar, barFromFoo, fooFromBaz)).resolve(
                desiredType = foo(typeVariable(a)),
                localContextTypes = emptyList(),
            )

        assertIs<ResolutionSearchResult.Missing>(result)
    }

    @Test
    fun `rule ordering does not change the surviving successful plan`() {
        val a = TcTypeParameter("outer:A", "A")
        val x = TcTypeParameter("rule:X", "X")
        val fooFromBar =
            InstanceRule(
                id = "fooFromBar",
                typeParameters = listOf(x),
                providedType = foo(typeVariable(x)),
                prerequisiteTypes = listOf(bar(typeVariable(x))),
            )
        val barFromFoo =
            InstanceRule(
                id = "barFromFoo",
                typeParameters = listOf(x),
                providedType = bar(typeVariable(x)),
                prerequisiteTypes = listOf(foo(typeVariable(x))),
            )
        val directFoo =
            InstanceRule(
                id = "directFoo",
                typeParameters = listOf(x),
                providedType = foo(typeVariable(x)),
                prerequisiteTypes = emptyList(),
            )

        val leftToRight =
            TypeclassResolutionPlanner(listOf(fooFromBar, barFromFoo, directFoo)).resolve(
                desiredType = foo(typeVariable(a)),
                localContextTypes = emptyList(),
            )
        val rightToLeft =
            TypeclassResolutionPlanner(listOf(directFoo, barFromFoo, fooFromBar)).resolve(
                desiredType = foo(typeVariable(a)),
                localContextTypes = emptyList(),
            )

        assertEquals(leftToRight, rightToLeft)
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
    fun `ambiguous rule matches keep both competing plans`() {
        val a = TcTypeParameter("outer:A", "A")
        val x = TcTypeParameter("rule:X", "X")
        val fooFromBar =
            InstanceRule(
                id = "fooFromBar",
                typeParameters = listOf(x),
                providedType = foo(typeVariable(x)),
                prerequisiteTypes = listOf(bar(typeVariable(x))),
            )
        val fooFromBaz =
            InstanceRule(
                id = "fooFromBaz",
                typeParameters = listOf(x),
                providedType = foo(typeVariable(x)),
                prerequisiteTypes = listOf(baz(typeVariable(x))),
            )

        val result =
            TypeclassResolutionPlanner(listOf(fooFromBar, fooFromBaz)).resolve(
                desiredType = foo(typeVariable(a)),
                localContextTypes = listOf(bar(typeVariable(a)), baz(typeVariable(a))),
            )

        val ambiguous = assertIs<ResolutionSearchResult.Ambiguous>(result)
        val matchingRuleIds =
            ambiguous.matchingPlans.map { plan ->
                assertIs<ResolutionPlan.ApplyRule>(plan).ruleId
            }.toSet()
        assertEquals(setOf("fooFromBar", "fooFromBaz"), matchingRuleIds)
    }

    @Test
    fun `alternative explanation labels head-only matches when prerequisites are unchecked`() {
        val a = TcTypeParameter("outer:A", "A")
        val x = TcTypeParameter("rule:X", "X")
        val fooFromBar =
            InstanceRule(
                id = "fooFromBar",
                typeParameters = listOf(x),
                providedType = foo(typeVariable(x)),
                prerequisiteTypes = listOf(bar(typeVariable(x))),
            )

        val traced =
            TypeclassResolutionPlanner(listOf(fooFromBar)).resolveWithTrace(
                desiredType = foo(typeVariable(a)),
                localContextTypes = listOf(foo(typeVariable(a))),
                explainAlternatives = true,
            )

        val success = assertIs<ResolutionSearchResult.Success>(traced.result)
        assertEquals(
            ResolutionPlan.LocalContext(index = 0, providedType = foo(typeVariable(a))),
            success.plan,
        )
        val candidate = traced.trace.ruleCandidates.singleOrNull { it.identity == "fooFromBar" }
        assertNotNull(candidate)
        assertEquals(ResolutionTraceCandidateState.EXPLAINED_NOT_SEARCHED, candidate.state)
        assertEquals("head matches the requested goal; prerequisites not checked", candidate.reason)
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

    @Test
    fun `bindable desired variables can match concrete local context`() {
        val a = TcTypeParameter("outer:A", "A")

        val result =
            TypeclassResolutionPlanner(
                rules = emptyList(),
                bindableDesiredVariableIds = setOf(a.id),
            ).resolve(
                desiredType = eq(typeVariable(a)),
                localContextTypes = listOf(eq(box(intType()))),
            )

        val success = assertIs<ResolutionSearchResult.Success>(result)
        assertEquals(
            ResolutionPlan.LocalContext(
                index = 0,
                providedType = eq(box(intType())),
            ),
            success.plan,
        )
    }

    private fun typeConstructor(
        classifierId: String,
        vararg arguments: TcType,
    ): TcType = TcType.Constructor(classifierId, arguments.toList())

    private fun typeVariable(parameter: TcTypeParameter): TcType = TcType.Variable(parameter.id, parameter.displayName)

    private fun eq(argument: TcType): TcType = typeConstructor("demo.Eq", argument)

    private fun foo(argument: TcType): TcType = typeConstructor("demo.Foo", argument)

    private fun bar(argument: TcType): TcType = typeConstructor("demo.Bar", argument)

    private fun baz(argument: TcType): TcType = typeConstructor("demo.Baz", argument)

    private fun box(argument: TcType): TcType = typeConstructor("demo.Box", argument)

    private fun intType(): TcType = typeConstructor("kotlin.Int")

    private fun pair(
        first: TcType,
        second: TcType,
    ): TcType = typeConstructor("kotlin.Pair", first, second)
}
