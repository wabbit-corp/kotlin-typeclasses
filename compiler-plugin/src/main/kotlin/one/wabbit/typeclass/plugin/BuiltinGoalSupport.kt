// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass.plugin

import one.wabbit.typeclass.plugin.model.TcType
import one.wabbit.typeclass.plugin.model.containsStarProjection
import one.wabbit.typeclass.plugin.model.isProvablyNullable
import one.wabbit.typeclass.plugin.model.normalizedKey
import one.wabbit.typeclass.plugin.model.referencedVariableIds
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.types.Variance

internal enum class BuiltinGoalFeasibility {
    PROVABLE,
    SPECULATIVE,
    IMPOSSIBLE,
}

internal enum class BuiltinGoalAcceptance {
    PROVABLE_ONLY,
    ALLOW_SPECULATIVE,
    ;

    fun accepts(feasibility: BuiltinGoalFeasibility): Boolean =
        when (this) {
            PROVABLE_ONLY -> feasibility == BuiltinGoalFeasibility.PROVABLE
            ALLOW_SPECULATIVE -> feasibility != BuiltinGoalFeasibility.IMPOSSIBLE
        }
}

internal data class FirBuiltinGoalExactContext(
    val session: FirSession,
    val typeParameterModels: Map<FirTypeParameterSymbol, one.wabbit.typeclass.plugin.model.TcTypeParameter>,
    val variableSymbolsById: Map<String, FirTypeParameterSymbol>,
)

internal fun supportsBuiltinKClassGoal(goal: TcType): Boolean {
    return supportsBuiltinKClassGoal(goal) { true }
}

internal fun supportsBuiltinKClassGoal(
    goal: TcType,
    canMaterializeVariable: (String) -> Boolean,
): Boolean {
    val constructor = goal as? TcType.Constructor ?: return true
    if (constructor.classifierId != KCLASS_CLASS_ID.asString()) {
        return true
    }
    val targetType = constructor.arguments.singleOrNull() ?: return false
    return !targetType.isProvablyNullable() && supportsRuntimeTypeMaterialization(targetType, canMaterializeVariable)
}

internal fun supportsBuiltinNotSameGoal(goal: TcType): Boolean {
    val constructor = goal as? TcType.Constructor ?: return true
    if (constructor.classifierId != NOT_SAME_CLASS_ID.asString()) {
        return true
    }
    val left = constructor.arguments.getOrNull(0) ?: return false
    val right = constructor.arguments.getOrNull(1) ?: return false
    return canProveNotSame(left, right)
}

internal fun supportsBuiltinSubtypeGoal(
    goal: TcType,
    classInfoById: Map<String, VisibleClassHierarchyInfo>,
    exactContext: FirBuiltinGoalExactContext? = null,
): Boolean {
    return builtinSubtypeGoalFeasibility(goal, classInfoById, exactContext) != BuiltinGoalFeasibility.IMPOSSIBLE
}

internal fun supportsBuiltinStrictSubtypeGoal(
    goal: TcType,
    classInfoById: Map<String, VisibleClassHierarchyInfo>,
    exactContext: FirBuiltinGoalExactContext? = null,
): Boolean {
    return builtinStrictSubtypeGoalFeasibility(goal, classInfoById, exactContext) != BuiltinGoalFeasibility.IMPOSSIBLE
}

internal fun provablySupportsBuiltinSubtypeGoal(
    goal: TcType,
    classInfoById: Map<String, VisibleClassHierarchyInfo>,
    exactContext: FirBuiltinGoalExactContext? = null,
): Boolean = builtinSubtypeGoalFeasibility(goal, classInfoById, exactContext) == BuiltinGoalFeasibility.PROVABLE

internal fun provablySupportsBuiltinStrictSubtypeGoal(
    goal: TcType,
    classInfoById: Map<String, VisibleClassHierarchyInfo>,
    exactContext: FirBuiltinGoalExactContext? = null,
): Boolean = builtinStrictSubtypeGoalFeasibility(goal, classInfoById, exactContext) == BuiltinGoalFeasibility.PROVABLE

private fun builtinSubtypeGoalFeasibility(
    goal: TcType,
    classInfoById: Map<String, VisibleClassHierarchyInfo>,
    exactContext: FirBuiltinGoalExactContext?,
): BuiltinGoalFeasibility {
    val constructor = goal as? TcType.Constructor ?: return BuiltinGoalFeasibility.PROVABLE
    if (constructor.classifierId != SUBTYPE_CLASS_ID.asString()) {
        return BuiltinGoalFeasibility.PROVABLE
    }
    val sub = constructor.arguments.getOrNull(0) ?: return BuiltinGoalFeasibility.IMPOSSIBLE
    val sup = constructor.arguments.getOrNull(1) ?: return BuiltinGoalFeasibility.IMPOSSIBLE
    return subtypeFeasibility(sub, sup, classInfoById, exactContext)
}

private fun builtinStrictSubtypeGoalFeasibility(
    goal: TcType,
    classInfoById: Map<String, VisibleClassHierarchyInfo>,
    exactContext: FirBuiltinGoalExactContext?,
): BuiltinGoalFeasibility {
    val constructor = goal as? TcType.Constructor ?: return BuiltinGoalFeasibility.PROVABLE
    if (constructor.classifierId != STRICT_SUBTYPE_CLASS_ID.asString()) {
        return BuiltinGoalFeasibility.PROVABLE
    }
    val sub = constructor.arguments.getOrNull(0) ?: return BuiltinGoalFeasibility.IMPOSSIBLE
    val sup = constructor.arguments.getOrNull(1) ?: return BuiltinGoalFeasibility.IMPOSSIBLE
    if (!canProveNotSame(sub, sup)) {
        return BuiltinGoalFeasibility.IMPOSSIBLE
    }
    return subtypeFeasibility(sub, sup, classInfoById, exactContext)
}

internal fun supportsBuiltinSameTypeConstructorGoal(goal: TcType): Boolean {
    val constructor = goal as? TcType.Constructor ?: return true
    if (constructor.classifierId != SAME_TYPE_CONSTRUCTOR_CLASS_ID.asString()) {
        return true
    }
    val left = constructor.arguments.getOrNull(0) as? TcType.Constructor ?: return false
    val right = constructor.arguments.getOrNull(1) as? TcType.Constructor ?: return false
    return left.classifierId == right.classifierId
}

internal fun supportsBuiltinIsTypeclassInstanceGoal(
    goal: TcType,
    isTypeclassClassifier: (String) -> Boolean,
    exactContext: FirBuiltinGoalExactContext? = null,
): Boolean {
    return builtinIsTypeclassInstanceGoalFeasibility(goal, isTypeclassClassifier, exactContext) != BuiltinGoalFeasibility.IMPOSSIBLE
}

internal fun provablySupportsBuiltinIsTypeclassInstanceGoal(
    goal: TcType,
    isTypeclassClassifier: (String) -> Boolean,
    exactContext: FirBuiltinGoalExactContext? = null,
): Boolean =
    builtinIsTypeclassInstanceGoalFeasibility(goal, isTypeclassClassifier, exactContext) == BuiltinGoalFeasibility.PROVABLE

internal fun supportsBuiltinKnownTypeGoal(goal: TcType): Boolean {
    return supportsBuiltinKnownTypeGoal(goal) { true }
}

internal fun supportsBuiltinKnownTypeGoal(
    goal: TcType,
    canMaterializeVariable: (String) -> Boolean,
): Boolean {
    val constructor = goal as? TcType.Constructor ?: return true
    if (constructor.classifierId != KNOWN_TYPE_CLASS_ID.asString()) {
        return true
    }
    val target = constructor.arguments.singleOrNull() ?: return false
    return supportsRuntimeTypeMaterialization(target, canMaterializeVariable)
}

internal fun supportsBuiltinTypeIdGoal(goal: TcType): Boolean {
    return supportsBuiltinTypeIdGoal(goal) { true }
}

internal fun supportsBuiltinTypeIdGoal(
    goal: TcType,
    canMaterializeVariable: (String) -> Boolean,
): Boolean {
    val constructor = goal as? TcType.Constructor ?: return true
    if (constructor.classifierId != TYPE_ID_CLASS_ID.asString()) {
        return true
    }
    val target = constructor.arguments.singleOrNull() ?: return false
    return supportsRuntimeTypeMaterialization(target, canMaterializeVariable)
}

internal fun supportsBuiltinKSerializerShape(goal: TcType): Boolean {
    return supportsBuiltinKSerializerShape(goal) { true }
}

internal fun supportsBuiltinKSerializerShape(
    goal: TcType,
    canMaterializeVariable: (String) -> Boolean,
): Boolean {
    val constructor = goal as? TcType.Constructor ?: return true
    if (constructor.classifierId != KSERIALIZER_CLASS_ID.asString()) {
        return true
    }
    val target = constructor.arguments.singleOrNull() ?: return false
    if (target.containsStarProjection()) {
        return false
    }
    return supportsRuntimeTypeMaterialization(target, canMaterializeVariable)
}

private fun TcType.isPotentialTypeclassApplication(
    isTypeclassClassifier: (String) -> Boolean,
): BuiltinGoalFeasibility =
    when (this) {
        TcType.StarProjection -> BuiltinGoalFeasibility.IMPOSSIBLE
        is TcType.Variable -> BuiltinGoalFeasibility.SPECULATIVE
        is TcType.Projected -> BuiltinGoalFeasibility.IMPOSSIBLE
        is TcType.Constructor ->
            if (isTypeclassClassifier(classifierId)) {
                BuiltinGoalFeasibility.PROVABLE
            } else {
                BuiltinGoalFeasibility.IMPOSSIBLE
            }
    }

internal fun supportsRuntimeTypeMaterialization(
    type: TcType,
    canMaterializeVariable: (String) -> Boolean,
): Boolean =
    supportsRuntimeTypeMaterialization(
        type = type,
        canMaterializeVariable = canMaterializeVariable,
        isTopLevel = true,
    )

private fun supportsRuntimeTypeMaterialization(
    type: TcType,
    canMaterializeVariable: (String) -> Boolean,
    isTopLevel: Boolean,
): Boolean =
    when (type) {
        TcType.StarProjection -> !isTopLevel
        is TcType.Projected ->
            !isTopLevel && supportsRuntimeTypeMaterialization(type.type, canMaterializeVariable, isTopLevel = false)
        is TcType.Variable -> canMaterializeVariable(type.id)
        is TcType.Constructor -> type.arguments.all { argument ->
            supportsRuntimeTypeMaterialization(argument, canMaterializeVariable, isTopLevel = false)
        }
    }

internal fun canProveNotSame(
    left: TcType,
    right: TcType,
): Boolean {
    if (left.normalizedKey() == right.normalizedKey()) {
        return false
    }
    return when {
        left === TcType.StarProjection || right === TcType.StarProjection -> false
        left is TcType.Projected && right is TcType.Projected ->
            left.variance != right.variance || canProveNotSame(left.type, right.type)

        left is TcType.Constructor && right is TcType.Constructor -> {
            left.classifierId != right.classifierId ||
                left.isNullable != right.isNullable ||
                left.arguments.size != right.arguments.size ||
                left.arguments.zip(right.arguments).any { (leftArgument, rightArgument) ->
                    canProveNotSame(leftArgument, rightArgument)
                }
        }

        else -> false
    }
}

private fun subtypeFeasibility(
    sub: TcType,
    sup: TcType,
    classInfoById: Map<String, VisibleClassHierarchyInfo>,
    exactContext: FirBuiltinGoalExactContext?,
): BuiltinGoalFeasibility {
    exactContext?.exactSubtypeFeasibility(sub, sup, classInfoById)?.let { return it }
    if (sub.normalizedKey() == sup.normalizedKey()) {
        return BuiltinGoalFeasibility.PROVABLE
    }
    if (sub.referencedVariableIds().isNotEmpty() || sup.referencedVariableIds().isNotEmpty()) {
        return BuiltinGoalFeasibility.SPECULATIVE
    }
    return when {
        sub === TcType.StarProjection || sup === TcType.StarProjection -> BuiltinGoalFeasibility.SPECULATIVE
        sub is TcType.Projected || sup is TcType.Projected -> BuiltinGoalFeasibility.SPECULATIVE
        sub is TcType.Constructor && sup is TcType.Constructor ->
            constructorSubtypeFeasibility(sub, sup, classInfoById, exactContext)

        else -> BuiltinGoalFeasibility.IMPOSSIBLE
    }
}

private fun constructorSubtypeFeasibility(
    sub: TcType.Constructor,
    sup: TcType.Constructor,
    classInfoById: Map<String, VisibleClassHierarchyInfo>,
    exactContext: FirBuiltinGoalExactContext?,
): BuiltinGoalFeasibility {
    if (sub.isNullable && !sup.isNullable) {
        return BuiltinGoalFeasibility.IMPOSSIBLE
    }
    if (sub.classifierId == sup.classifierId) {
        return sameClassifierSubtypeFeasibility(sub, sup, classInfoById[sub.classifierId], classInfoById, exactContext)
    }
    return hasSupertypePathFeasibility(sub.classifierId, sup.classifierId, classInfoById)
}

private fun sameClassifierSubtypeFeasibility(
    sub: TcType.Constructor,
    sup: TcType.Constructor,
    classInfo: VisibleClassHierarchyInfo?,
    classInfoById: Map<String, VisibleClassHierarchyInfo>,
    exactContext: FirBuiltinGoalExactContext?,
): BuiltinGoalFeasibility {
    if (classInfo == null) {
        return BuiltinGoalFeasibility.SPECULATIVE
    }
    if (sub.arguments.size != sup.arguments.size) {
        return BuiltinGoalFeasibility.IMPOSSIBLE
    }
    val variances = classInfo.typeParameterVariances
    if (variances.size != sub.arguments.size) {
        return BuiltinGoalFeasibility.SPECULATIVE
    }
    var sawSpeculative = false
    sub.arguments.indices.forEach { index ->
        val subArgument = sub.arguments[index]
        val superArgument = sup.arguments[index]
        if (superArgument === TcType.StarProjection) {
            return@forEach
        }
        val variance = variances.getOrNull(index) ?: Variance.INVARIANT
        val argumentFeasibility =
            when {
                subArgument is TcType.Projected || superArgument is TcType.Projected -> BuiltinGoalFeasibility.SPECULATIVE
                variance == Variance.OUT_VARIANCE -> subtypeFeasibility(subArgument, superArgument, classInfoById, exactContext)
                variance == Variance.IN_VARIANCE -> subtypeFeasibility(superArgument, subArgument, classInfoById, exactContext)
                else ->
                    if (subArgument.normalizedKey() == superArgument.normalizedKey()) {
                        BuiltinGoalFeasibility.PROVABLE
                    } else {
                        BuiltinGoalFeasibility.IMPOSSIBLE
                    }
            }
        when (argumentFeasibility) {
            BuiltinGoalFeasibility.PROVABLE -> Unit
            BuiltinGoalFeasibility.SPECULATIVE -> sawSpeculative = true
            BuiltinGoalFeasibility.IMPOSSIBLE -> return BuiltinGoalFeasibility.IMPOSSIBLE
        }
    }
    return if (sawSpeculative) BuiltinGoalFeasibility.SPECULATIVE else BuiltinGoalFeasibility.PROVABLE
}

private fun hasSupertypePathFeasibility(
    sourceClassifierId: String,
    targetClassifierId: String,
    classInfoById: Map<String, VisibleClassHierarchyInfo>,
): BuiltinGoalFeasibility {
    val info = classInfoById[sourceClassifierId] ?: return BuiltinGoalFeasibility.SPECULATIVE
    if (targetClassifierId in info.superClassifiers) {
        return BuiltinGoalFeasibility.PROVABLE
    }
    val remaining = ArrayDeque(info.superClassifiers)
    val visited = linkedSetOf<String>()
    while (remaining.isNotEmpty()) {
        val current = remaining.removeFirst()
        if (!visited.add(current)) {
            continue
        }
        if (current == targetClassifierId) {
            return BuiltinGoalFeasibility.PROVABLE
        }
        val currentInfo = classInfoById[current] ?: return BuiltinGoalFeasibility.SPECULATIVE
        remaining.addAll(currentInfo.superClassifiers)
    }
    return BuiltinGoalFeasibility.IMPOSSIBLE
}

private fun builtinIsTypeclassInstanceGoalFeasibility(
    goal: TcType,
    isTypeclassClassifier: (String) -> Boolean,
    exactContext: FirBuiltinGoalExactContext?,
): BuiltinGoalFeasibility {
    val constructor = goal as? TcType.Constructor ?: return BuiltinGoalFeasibility.PROVABLE
    if (constructor.classifierId != IS_TYPECLASS_INSTANCE_CLASS_ID.asString()) {
        return BuiltinGoalFeasibility.PROVABLE
    }
    val target = constructor.arguments.singleOrNull() ?: return BuiltinGoalFeasibility.IMPOSSIBLE
    exactContext?.let {
        return when (target) {
            TcType.StarProjection -> BuiltinGoalFeasibility.IMPOSSIBLE
            is TcType.Variable -> BuiltinGoalFeasibility.IMPOSSIBLE
            is TcType.Projected -> BuiltinGoalFeasibility.IMPOSSIBLE
            is TcType.Constructor ->
                if (isTypeclassClassifier(target.classifierId)) {
                    BuiltinGoalFeasibility.PROVABLE
                } else {
                    BuiltinGoalFeasibility.IMPOSSIBLE
                }
        }
    }
    return target.isPotentialTypeclassApplication(isTypeclassClassifier)
}

private fun FirBuiltinGoalExactContext.exactSubtypeFeasibility(
    sub: TcType,
    sup: TcType,
    classInfoById: Map<String, VisibleClassHierarchyInfo>,
    visiting: MutableSet<String> = linkedSetOf(),
): BuiltinGoalFeasibility? {
    if (sub.normalizedKey() == sup.normalizedKey()) {
        return BuiltinGoalFeasibility.PROVABLE
    }
    val subVariable = sub as? TcType.Variable
    if (subVariable != null) {
        val visitKey = "${subVariable.id}->${sup.normalizedKey()}"
        if (!visiting.add(visitKey)) {
            return BuiltinGoalFeasibility.IMPOSSIBLE
        }
        try {
            val symbol = variableSymbolsById[subVariable.id] ?: return null
            val bounds =
                symbol.resolvedBounds.mapNotNull { boundTypeRef ->
                    coneTypeToModel(boundTypeRef.coneType, typeParameterModels)
                }
            if (bounds.isEmpty()) {
                return BuiltinGoalFeasibility.IMPOSSIBLE
            }
            return if (bounds.any { bound -> exactSubtypeFeasibility(bound, sup, classInfoById, visiting) == BuiltinGoalFeasibility.PROVABLE }) {
                BuiltinGoalFeasibility.PROVABLE
            } else {
                BuiltinGoalFeasibility.IMPOSSIBLE
            }
        } finally {
            visiting.remove(visitKey)
        }
    }
    if (sup is TcType.Variable) {
        return BuiltinGoalFeasibility.IMPOSSIBLE
    }
    if (sub is TcType.Constructor && sup is TcType.Constructor) {
        return constructorSubtypeFeasibility(sub, sup, classInfoById, this)
    }
    return when {
        sub === TcType.StarProjection || sup === TcType.StarProjection -> BuiltinGoalFeasibility.IMPOSSIBLE
        sub is TcType.Projected || sup is TcType.Projected -> BuiltinGoalFeasibility.IMPOSSIBLE
        else -> BuiltinGoalFeasibility.IMPOSSIBLE
    }
}
