// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.typeclass.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import one.wabbit.typeclass.plugin.model.TcType
import org.jetbrains.kotlin.types.Variance

class TypeArgumentInferenceSupportTest {
    @Test
    fun invariantNestedArgumentsStillProduceExactBindings() {
        val bindings =
            inferTypeBindings(
                expected = constructor("demo.Use", constructor("demo.Box", variable("T"))),
                actual =
                    constructor("demo.Use", constructor("demo.Box", constructor("kotlin.String"))),
                bindableVariableIds = setOf("T"),
                variancesForClassifier = ::variancesFor,
                isProvableSubtype = ::isSubtype,
            )

        assertEquals(constructor("kotlin.String"), bindings["T"])
    }

    @Test
    fun covariantNestedArgumentsResolveSingleLowerBounds() {
        val bindings =
            inferTypeBindings(
                expected = constructor("demo.Use", constructor("demo.CovBox", variable("T"))),
                actual =
                    constructor(
                        "demo.Use",
                        constructor("demo.CovBox", constructor("kotlin.String")),
                    ),
                bindableVariableIds = setOf("T"),
                variancesForClassifier = ::variancesFor,
                isProvableSubtype = ::isSubtype,
            )

        assertEquals(constructor("kotlin.String"), bindings["T"])
    }

    @Test
    fun contravariantNestedArgumentsResolveSingleUpperBounds() {
        val bindings =
            inferTypeBindings(
                expected = constructor("demo.Use", constructor("demo.ContraBox", variable("T"))),
                actual =
                    constructor(
                        "demo.Use",
                        constructor("demo.ContraBox", constructor("kotlin.Any")),
                    ),
                bindableVariableIds = setOf("T"),
                variancesForClassifier = ::variancesFor,
                isProvableSubtype = ::isSubtype,
            )

        assertEquals(constructor("kotlin.Any"), bindings["T"])
    }

    @Test
    fun choosesWidestLowerBoundWhenMultipleCovariantCandidatesExist() {
        val constraints = linkedMapOf<String, MutableTypeBindingConstraints<TcType>>()
        recordTypeBindingConstraint(
            constraintsByKey = constraints,
            key = "T",
            candidate =
                TypeBindingBound(
                    value = constructor("kotlin.String"),
                    model = constructor("kotlin.String"),
                ),
            position = ExactTypeArgumentPosition.COVARIANT,
        )
        recordTypeBindingConstraint(
            constraintsByKey = constraints,
            key = "T",
            candidate =
                TypeBindingBound(
                    value = constructor("kotlin.Any"),
                    model = constructor("kotlin.Any"),
                ),
            position = ExactTypeArgumentPosition.COVARIANT,
        )

        val resolved = resolveTypeBindingConstraints(constraints, ::isSubtype)

        assertEquals(constructor("kotlin.Any"), resolved.resolvedByKey["T"])
        assertTrue("T" !in resolved.conflictingKeys)
    }

    @Test
    fun exactConstraintsUseSemanticModelNotRawRepresentative() {
        val constraints = linkedMapOf<String, MutableTypeBindingConstraints<String>>()
        recordTypeBindingConstraint(
            constraintsByKey = constraints,
            key = "T",
            candidate = TypeBindingBound(value = "alias", model = constructor("kotlin.String")),
            position = ExactTypeArgumentPosition.EXACT,
        )
        recordTypeBindingConstraint(
            constraintsByKey = constraints,
            key = "T",
            candidate = TypeBindingBound(value = "expanded", model = constructor("kotlin.String")),
            position = ExactTypeArgumentPosition.EXACT,
        )

        val resolved = resolveTypeBindingConstraints(constraints, ::isSubtype)

        assertEquals("alias", resolved.resolvedByKey["T"])
        assertTrue("T" !in resolved.conflictingKeys)
    }

    @Test
    fun duplicateLowerBoundRepresentativesResolveToCanonicalValue() {
        val resolved =
            resolveTypeBindingFromBounds(
                lowerBounds =
                    listOf(
                        TypeBindingBound(value = "alias", model = constructor("kotlin.Any")),
                        TypeBindingBound(value = "expanded", model = constructor("kotlin.Any")),
                    ),
                upperBounds = emptyList(),
                isProvableSubtype = ::isSubtype,
            )

        assertEquals("alias", resolved)
    }

    @Test
    fun duplicateUpperBoundRepresentativesResolveToCanonicalValue() {
        val resolved =
            resolveTypeBindingFromBounds(
                lowerBounds = emptyList(),
                upperBounds =
                    listOf(
                        TypeBindingBound(value = "alias", model = constructor("kotlin.String")),
                        TypeBindingBound(value = "expanded", model = constructor("kotlin.String")),
                    ),
                isProvableSubtype = ::isSubtype,
            )

        assertEquals("alias", resolved)
    }

    private fun variancesFor(classifierId: String): List<Variance> =
        when (classifierId) {
            "demo.Use" -> listOf(Variance.INVARIANT)
            "demo.Box" -> listOf(Variance.INVARIANT)
            "demo.CovBox" -> listOf(Variance.OUT_VARIANCE)
            "demo.ContraBox" -> listOf(Variance.IN_VARIANCE)
            else -> emptyList()
        }

    private fun isSubtype(sub: TcType, sup: TcType): Boolean =
        when {
            sub == sup -> true
            sub == constructor("kotlin.String") && sup == constructor("kotlin.Any") -> true
            else -> false
        }

    private fun constructor(classifierId: String, vararg arguments: TcType): TcType =
        TcType.Constructor(classifierId, arguments.toList())

    private fun variable(id: String): TcType = TcType.Variable(id = id, displayName = id)
}
