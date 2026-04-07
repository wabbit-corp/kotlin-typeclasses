// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass.plugin

import one.wabbit.typeclass.plugin.model.TcType
import org.jetbrains.kotlin.types.Variance

internal enum class ExactTypeArgumentPosition {
    EXACT,
    COVARIANT,
    CONTRAVARIANT,
    ;

    fun compose(variance: Variance): ExactTypeArgumentPosition =
        when (variance) {
            Variance.INVARIANT -> this
            Variance.OUT_VARIANCE ->
                when (this) {
                    EXACT,
                    COVARIANT,
                    ->
                        COVARIANT

                    CONTRAVARIANT -> CONTRAVARIANT
                }

            Variance.IN_VARIANCE ->
                when (this) {
                    EXACT,
                    COVARIANT,
                    ->
                        CONTRAVARIANT

                    CONTRAVARIANT -> COVARIANT
                }
    }
}

internal data class TypeBindingBound<T>(
    val value: T,
    val model: TcType,
)

internal class MutableTypeBindingConstraints<T> {
    var exact: TypeBindingBound<T>? = null
    val lowerBounds: MutableList<TypeBindingBound<T>> = mutableListOf()
    val upperBounds: MutableList<TypeBindingBound<T>> = mutableListOf()
    var conflicting: Boolean = false
}

internal data class ResolvedTypeBindingConstraints<K, T>(
    val resolvedByKey: Map<K, T>,
    val conflictingKeys: Set<K>,
)

internal fun <K, T> recordTypeBindingConstraint(
    constraintsByKey: MutableMap<K, MutableTypeBindingConstraints<T>>,
    key: K,
    candidate: TypeBindingBound<T>,
    position: ExactTypeArgumentPosition,
) {
    val constraints = constraintsByKey.getOrPut(key, ::MutableTypeBindingConstraints)
    when (position) {
        ExactTypeArgumentPosition.EXACT -> {
            val existing = constraints.exact
            if (existing == null) {
                constraints.exact = candidate
            } else if (existing.model != candidate.model || existing.value != candidate.value) {
                constraints.exact = null
                constraints.conflicting = true
            }
        }

        ExactTypeArgumentPosition.COVARIANT -> {
            if (constraints.lowerBounds.none { bound -> bound.model == candidate.model && bound.value == candidate.value }) {
                constraints.lowerBounds += candidate
            }
        }

        ExactTypeArgumentPosition.CONTRAVARIANT -> {
            if (constraints.upperBounds.none { bound -> bound.model == candidate.model && bound.value == candidate.value }) {
                constraints.upperBounds += candidate
            }
        }
    }
}

internal fun <K, T> resolveTypeBindingConstraints(
    constraintsByKey: Map<K, MutableTypeBindingConstraints<T>>,
    isProvableSubtype: (TcType, TcType) -> Boolean,
): ResolvedTypeBindingConstraints<K, T> {
    val resolved = linkedMapOf<K, T>()
    val conflicting = linkedSetOf<K>()
    constraintsByKey.forEach { (key, constraints) ->
        if (constraints.conflicting) {
            conflicting += key
            return@forEach
        }
        val exact = constraints.exact
        if (exact != null) {
            val lowerCompatible = constraints.lowerBounds.all { lower -> isProvableSubtype(lower.model, exact.model) }
            val upperCompatible = constraints.upperBounds.all { upper -> isProvableSubtype(exact.model, upper.model) }
            if (lowerCompatible && upperCompatible) {
                resolved[key] = exact.value
            } else {
                conflicting += key
            }
            return@forEach
        }
        val resolvedValue =
            resolveTypeBindingFromBounds(
                lowerBounds = constraints.lowerBounds,
                upperBounds = constraints.upperBounds,
                isProvableSubtype = isProvableSubtype,
            )
        if (resolvedValue != null) {
            resolved[key] = resolvedValue
        } else if (constraints.lowerBounds.isNotEmpty() || constraints.upperBounds.isNotEmpty()) {
            conflicting += key
        }
    }
    return ResolvedTypeBindingConstraints(
        resolvedByKey = resolved,
        conflictingKeys = conflicting,
    )
}

internal fun inferTypeBindings(
    expected: TcType,
    actual: TcType,
    bindableVariableIds: Set<String>,
    variancesForClassifier: (String) -> List<Variance>,
    isProvableSubtype: (TcType, TcType) -> Boolean,
): Map<String, TcType> {
    val constraints = linkedMapOf<String, MutableTypeBindingConstraints<TcType>>()
    collectTypeBindingConstraints(
        expected = expected,
        actual = actual,
        bindableVariableIds = bindableVariableIds,
        variancesForClassifier = variancesForClassifier,
        position = ExactTypeArgumentPosition.EXACT,
        constraintsByVariableId = constraints,
    )
    return resolveTypeBindingConstraints(
        constraintsByKey = constraints,
        isProvableSubtype = isProvableSubtype,
    ).resolvedByKey
}

internal fun inferExactTypeBindings(
    expected: TcType,
    actual: TcType,
    bindableVariableIds: Set<String>,
    variancesForClassifier: (String) -> List<Variance>,
): Map<String, TcType> {
    return inferTypeBindings(
        expected = expected,
        actual = actual,
        bindableVariableIds = bindableVariableIds,
        variancesForClassifier = variancesForClassifier,
        isProvableSubtype = { left, right -> left == right },
    )
}

private fun collectTypeBindingConstraints(
    expected: TcType,
    actual: TcType,
    bindableVariableIds: Set<String>,
    variancesForClassifier: (String) -> List<Variance>,
    position: ExactTypeArgumentPosition,
    constraintsByVariableId: MutableMap<String, MutableTypeBindingConstraints<TcType>>,
) {
    when (expected) {
        TcType.StarProjection -> return

        is TcType.Variable -> {
            if (expected.id !in bindableVariableIds) {
                return
            }
            recordTypeBindingConstraint(
                constraintsByKey = constraintsByVariableId,
                key = expected.id,
                candidate = TypeBindingBound(value = actual, model = actual),
                position = position,
            )
            return
        }

        is TcType.Projected -> {
            val nestedActual =
                when (actual) {
                    TcType.StarProjection -> return
                    is TcType.Projected -> {
                        if (actual.variance != expected.variance) {
                            return
                        }
                        actual.type
                    }

                    else -> actual
                }
            collectTypeBindingConstraints(
                expected = expected.type,
                actual = nestedActual,
                bindableVariableIds = bindableVariableIds,
                variancesForClassifier = variancesForClassifier,
                position = position.compose(expected.variance),
                constraintsByVariableId = constraintsByVariableId,
            )
            return
        }

        is TcType.Constructor -> Unit
    }

    val expectedConstructor = expected as? TcType.Constructor ?: return
    val actualConstructor = actual as? TcType.Constructor ?: return
    if (expectedConstructor.classifierId != actualConstructor.classifierId || expectedConstructor.arguments.size != actualConstructor.arguments.size) {
        return
    }
    val declaredVariances = variancesForClassifier(expectedConstructor.classifierId)
    expectedConstructor.arguments.zip(actualConstructor.arguments).forEachIndexed { index, (expectedArgument, actualArgument) ->
        val nestedExpected =
            when (expectedArgument) {
                TcType.StarProjection -> return@forEachIndexed
                is TcType.Projected -> expectedArgument.type
                else -> expectedArgument
            }
        val nestedActual =
            when (actualArgument) {
                TcType.StarProjection -> return@forEachIndexed
                is TcType.Projected -> actualArgument.type
                else -> actualArgument
            }
        val nestedVariance =
            nestedInferenceVariance(
                expectedArgument = expectedArgument,
                actualArgument = actualArgument,
                declaredVariance = declaredVariances.getOrNull(index) ?: Variance.INVARIANT,
            ) ?: return@forEachIndexed
        collectTypeBindingConstraints(
            expected = nestedExpected,
            actual = nestedActual,
            bindableVariableIds = bindableVariableIds,
            variancesForClassifier = variancesForClassifier,
            position = position.compose(nestedVariance),
            constraintsByVariableId = constraintsByVariableId,
        )
    }
}

private fun nestedInferenceVariance(
    expectedArgument: TcType,
    actualArgument: TcType,
    declaredVariance: Variance,
): Variance? {
    val expectedVariance =
        when (expectedArgument) {
            TcType.StarProjection -> return null
            is TcType.Projected -> expectedArgument.variance
            else -> declaredVariance
        }
    return when (actualArgument) {
        TcType.StarProjection -> null
        is TcType.Projected ->
            if (actualArgument.variance == expectedVariance) {
                expectedVariance
            } else {
                null
            }
        else -> expectedVariance
    }
}

internal fun <T> resolveTypeBindingFromBounds(
    lowerBounds: List<TypeBindingBound<T>>,
    upperBounds: List<TypeBindingBound<T>>,
    isProvableSubtype: (TcType, TcType) -> Boolean,
): T? {
    val chosenLower = selectWidestLowerBound(lowerBounds, isProvableSubtype)
    if (chosenLower != null && upperBounds.all { upper -> isProvableSubtype(chosenLower.model, upper.model) }) {
        return chosenLower.value
    }
    val chosenUpper = selectNarrowestUpperBound(upperBounds, isProvableSubtype)
    if (chosenUpper != null && lowerBounds.all { lower -> isProvableSubtype(lower.model, chosenUpper.model) }) {
        return chosenUpper.value
    }
    return null
}

private fun <T> selectWidestLowerBound(
    bounds: List<TypeBindingBound<T>>,
    isProvableSubtype: (TcType, TcType) -> Boolean,
): TypeBindingBound<T>? {
    if (bounds.isEmpty()) {
        return null
    }
    val admissible = bounds.filter { candidate -> bounds.all { lower -> isProvableSubtype(lower.model, candidate.model) } }
    return admissible.singleOrNull { candidate ->
        admissible.all { other -> candidate === other || isProvableSubtype(candidate.model, other.model) }
    }
}

private fun <T> selectNarrowestUpperBound(
    bounds: List<TypeBindingBound<T>>,
    isProvableSubtype: (TcType, TcType) -> Boolean,
): TypeBindingBound<T>? {
    if (bounds.isEmpty()) {
        return null
    }
    val admissible = bounds.filter { candidate -> bounds.all { upper -> isProvableSubtype(candidate.model, upper.model) } }
    return admissible.singleOrNull { candidate ->
        admissible.all { other -> candidate === other || isProvableSubtype(other.model, candidate.model) }
    }
}
