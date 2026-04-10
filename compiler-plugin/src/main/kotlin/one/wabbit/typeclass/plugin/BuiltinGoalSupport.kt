// SPDX-License-Identifier: AGPL-3.0-or-later

@file:OptIn(org.jetbrains.kotlin.fir.symbols.SymbolInternals::class)

package one.wabbit.typeclass.plugin

import one.wabbit.typeclass.plugin.model.TcType
import one.wabbit.typeclass.plugin.model.TcTypeParameter
import one.wabbit.typeclass.plugin.model.containsProjectionOrStar
import one.wabbit.typeclass.plugin.model.containsStarProjection
import one.wabbit.typeclass.plugin.model.isProvablyNotNullable
import one.wabbit.typeclass.plugin.model.isProvablyNullable
import one.wabbit.typeclass.plugin.model.normalizedKey
import one.wabbit.typeclass.plugin.model.referencedVariableIds
import one.wabbit.typeclass.plugin.model.substituteType
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjectionIn
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjectionOut
import org.jetbrains.kotlin.fir.types.ConeStarProjection
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.types.constructType
import org.jetbrains.kotlin.fir.types.typeContext
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.types.AbstractTypeChecker
import org.jetbrains.kotlin.types.Variance

internal enum class BuiltinGoalFeasibility {
    PROVABLE,
    SPECULATIVE,
    IMPOSSIBLE,
}

private enum class TypeRelationKnowledge {
    PROVABLE,
    DISPROVABLE,
    UNKNOWN,
}

internal enum class BuiltinGoalAcceptance {
    PROVABLE_ONLY,
    ALLOW_SPECULATIVE;

    fun accepts(feasibility: BuiltinGoalFeasibility): Boolean =
        when (this) {
            PROVABLE_ONLY -> feasibility == BuiltinGoalFeasibility.PROVABLE
            ALLOW_SPECULATIVE -> feasibility != BuiltinGoalFeasibility.IMPOSSIBLE
        }
}

internal data class FirBuiltinGoalExactContext(
    val session: FirSession,
    val typeParameterModels:
        Map<FirTypeParameterSymbol, one.wabbit.typeclass.plugin.model.TcTypeParameter>,
    val variableSymbolsById: Map<String, FirTypeParameterSymbol>,
)

internal fun builtinRuleCanMatchGoalHead(ruleId: String, goal: TcType): Boolean {
    val goalConstructor = goal as? TcType.Constructor ?: return true
    val builtinHead =
        when (ruleId) {
            "builtin:kclass" -> KCLASS_CLASS_ID.asString()
            "builtin:subtype" -> SUBTYPE_CLASS_ID.asString()
            "builtin:strict-subtype" -> STRICT_SUBTYPE_CLASS_ID.asString()
            "builtin:kserializer" -> KSERIALIZER_CLASS_ID.asString()
            "builtin:notsame" -> NOT_SAME_CLASS_ID.asString()
            "builtin:nullable" -> NULLABLE_CLASS_ID.asString()
            "builtin:not-nullable" -> NOT_NULLABLE_CLASS_ID.asString()
            "builtin:is-typeclass-instance" -> IS_TYPECLASS_INSTANCE_CLASS_ID.asString()
            "builtin:known-type" -> KNOWN_TYPE_CLASS_ID.asString()
            "builtin:type-id" -> TYPE_ID_CLASS_ID.asString()
            "builtin:same-type-constructor" -> SAME_TYPE_CONSTRUCTOR_CLASS_ID.asString()
            else -> return true
        }
    return goalConstructor.classifierId == builtinHead
}

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
    return !targetType.isProvablyNullable() &&
        supportsRuntimeTypeMaterialization(targetType, canMaterializeVariable)
}

internal fun supportsBuiltinNotSameGoal(goal: TcType): Boolean {
    return supportsBuiltinNotSameGoal(goal, emptyMap())
}

internal fun supportsBuiltinNotSameGoal(
    goal: TcType,
    classInfoById: Map<String, VisibleClassHierarchyInfo>,
    exactContext: FirBuiltinGoalExactContext? = null,
): Boolean {
    return builtinNotSameGoalFeasibility(goal, classInfoById, exactContext) !=
        BuiltinGoalFeasibility.IMPOSSIBLE
}

private fun builtinNotSameGoalFeasibility(
    goal: TcType,
    classInfoById: Map<String, VisibleClassHierarchyInfo>,
    exactContext: FirBuiltinGoalExactContext?,
): BuiltinGoalFeasibility {
    val constructor = goal as? TcType.Constructor ?: return BuiltinGoalFeasibility.PROVABLE
    if (constructor.classifierId != NOT_SAME_CLASS_ID.asString()) {
        return BuiltinGoalFeasibility.PROVABLE
    }
    val left = constructor.arguments.getOrNull(0) ?: return BuiltinGoalFeasibility.IMPOSSIBLE
    val right = constructor.arguments.getOrNull(1) ?: return BuiltinGoalFeasibility.IMPOSSIBLE
    return exactContext?.exactNotSameFeasibility(left, right, classInfoById)
        ?: if (canProveNotSame(left, right)) {
            BuiltinGoalFeasibility.PROVABLE
        } else {
            BuiltinGoalFeasibility.IMPOSSIBLE
        }
}

internal fun supportsBuiltinSubtypeGoal(
    goal: TcType,
    classInfoById: Map<String, VisibleClassHierarchyInfo>,
    exactContext: FirBuiltinGoalExactContext? = null,
): Boolean {
    return builtinSubtypeGoalFeasibility(goal, classInfoById, exactContext) !=
        BuiltinGoalFeasibility.IMPOSSIBLE
}

internal fun supportsBuiltinStrictSubtypeGoal(
    goal: TcType,
    classInfoById: Map<String, VisibleClassHierarchyInfo>,
    exactContext: FirBuiltinGoalExactContext? = null,
): Boolean {
    return builtinStrictSubtypeGoalFeasibility(goal, classInfoById, exactContext) !=
        BuiltinGoalFeasibility.IMPOSSIBLE
}

internal fun provablySupportsBuiltinSubtypeGoal(
    goal: TcType,
    classInfoById: Map<String, VisibleClassHierarchyInfo>,
    exactContext: FirBuiltinGoalExactContext? = null,
): Boolean =
    builtinSubtypeGoalFeasibility(goal, classInfoById, exactContext) ==
        BuiltinGoalFeasibility.PROVABLE

internal fun provablySupportsBuiltinStrictSubtypeGoal(
    goal: TcType,
    classInfoById: Map<String, VisibleClassHierarchyInfo>,
    exactContext: FirBuiltinGoalExactContext? = null,
): Boolean =
    builtinStrictSubtypeGoalFeasibility(goal, classInfoById, exactContext) ==
        BuiltinGoalFeasibility.PROVABLE

internal fun supportsBuiltinNullableGoal(
    goal: TcType,
    exactContext: FirBuiltinGoalExactContext? = null,
): Boolean = builtinNullableGoalFeasibility(goal, exactContext) != BuiltinGoalFeasibility.IMPOSSIBLE

internal fun provablySupportsBuiltinNullableGoal(
    goal: TcType,
    exactContext: FirBuiltinGoalExactContext? = null,
): Boolean = builtinNullableGoalFeasibility(goal, exactContext) == BuiltinGoalFeasibility.PROVABLE

internal fun supportsBuiltinNotNullableGoal(
    goal: TcType,
    exactContext: FirBuiltinGoalExactContext? = null,
): Boolean =
    builtinNotNullableGoalFeasibility(goal, exactContext) != BuiltinGoalFeasibility.IMPOSSIBLE

internal fun provablySupportsBuiltinNotNullableGoal(
    goal: TcType,
    exactContext: FirBuiltinGoalExactContext? = null,
): Boolean =
    builtinNotNullableGoalFeasibility(goal, exactContext) == BuiltinGoalFeasibility.PROVABLE

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
    val notSameFeasibility =
        exactContext?.exactNotSameFeasibility(sub, sup, classInfoById)
            ?: if (canProveNotSame(sub, sup)) {
                BuiltinGoalFeasibility.PROVABLE
            } else {
                BuiltinGoalFeasibility.IMPOSSIBLE
            }
    if (notSameFeasibility == BuiltinGoalFeasibility.IMPOSSIBLE) {
        return BuiltinGoalFeasibility.IMPOSSIBLE
    }
    return subtypeFeasibility(sub, sup, classInfoById, exactContext)
}

private fun builtinNullableGoalFeasibility(
    goal: TcType,
    exactContext: FirBuiltinGoalExactContext?,
): BuiltinGoalFeasibility {
    val constructor = goal as? TcType.Constructor ?: return BuiltinGoalFeasibility.PROVABLE
    if (constructor.classifierId != NULLABLE_CLASS_ID.asString()) {
        return BuiltinGoalFeasibility.PROVABLE
    }
    val target = constructor.arguments.singleOrNull() ?: return BuiltinGoalFeasibility.IMPOSSIBLE
    exactContext?.exactNullableFeasibility(target)?.let {
        return it
    }
    return if (target.isProvablyNullable()) {
        BuiltinGoalFeasibility.PROVABLE
    } else {
        BuiltinGoalFeasibility.IMPOSSIBLE
    }
}

private fun builtinNotNullableGoalFeasibility(
    goal: TcType,
    exactContext: FirBuiltinGoalExactContext?,
): BuiltinGoalFeasibility {
    val constructor = goal as? TcType.Constructor ?: return BuiltinGoalFeasibility.PROVABLE
    if (constructor.classifierId != NOT_NULLABLE_CLASS_ID.asString()) {
        return BuiltinGoalFeasibility.PROVABLE
    }
    val target = constructor.arguments.singleOrNull() ?: return BuiltinGoalFeasibility.IMPOSSIBLE
    exactContext?.exactNotNullableFeasibility(target)?.let {
        return it
    }
    return if (target.isProvablyNotNullable()) {
        BuiltinGoalFeasibility.PROVABLE
    } else {
        BuiltinGoalFeasibility.IMPOSSIBLE
    }
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
    return builtinIsTypeclassInstanceGoalFeasibility(goal, isTypeclassClassifier, exactContext) !=
        BuiltinGoalFeasibility.IMPOSSIBLE
}

internal fun provablySupportsBuiltinIsTypeclassInstanceGoal(
    goal: TcType,
    isTypeclassClassifier: (String) -> Boolean,
    exactContext: FirBuiltinGoalExactContext? = null,
): Boolean =
    builtinIsTypeclassInstanceGoalFeasibility(goal, isTypeclassClassifier, exactContext) ==
        BuiltinGoalFeasibility.PROVABLE

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
    isTypeclassClassifier: (String) -> Boolean
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
            !isTopLevel &&
                supportsRuntimeTypeMaterialization(
                    type.type,
                    canMaterializeVariable,
                    isTopLevel = false,
                )
        is TcType.Variable -> canMaterializeVariable(type.id)
        is TcType.Constructor ->
            type.arguments.all { argument ->
                supportsRuntimeTypeMaterialization(
                    argument,
                    canMaterializeVariable,
                    isTopLevel = false,
                )
            }
    }

internal fun canProveNotSame(left: TcType, right: TcType): Boolean {
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
    exactContext?.exactSubtypeFeasibility(sub, sup, classInfoById)?.let {
        return it
    }
    if (sub.normalizedKey() == sup.normalizedKey()) {
        return BuiltinGoalFeasibility.PROVABLE
    }
    if (sub.referencedVariableIds().isNotEmpty() || sup.referencedVariableIds().isNotEmpty()) {
        return BuiltinGoalFeasibility.SPECULATIVE
    }
    return when {
        sub === TcType.StarProjection || sup === TcType.StarProjection ->
            BuiltinGoalFeasibility.SPECULATIVE
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
        return sameClassifierSubtypeFeasibility(
            sub,
            sup,
            classInfoById[sub.classifierId],
            classInfoById,
            exactContext,
        )
    }
    val pathFeasibility =
        hasSupertypePathFeasibility(sub.classifierId, sup.classifierId, classInfoById)
    if (sub.arguments.isNotEmpty() || sup.arguments.isNotEmpty()) {
        return projectedSupertypeSubtypeFeasibility(
            sub = sub,
            sup = sup,
            classInfoById = classInfoById,
            exactContext = exactContext,
            pathFeasibility = pathFeasibility,
        )
    }
    return pathFeasibility
}

private fun projectedSupertypeSubtypeFeasibility(
    sub: TcType.Constructor,
    sup: TcType.Constructor,
    classInfoById: Map<String, VisibleClassHierarchyInfo>,
    exactContext: FirBuiltinGoalExactContext?,
    pathFeasibility: BuiltinGoalFeasibility,
): BuiltinGoalFeasibility {
    if (pathFeasibility == BuiltinGoalFeasibility.IMPOSSIBLE) {
        return BuiltinGoalFeasibility.IMPOSSIBLE
    }
    val classInfo = classInfoById[sub.classifierId] ?: return BuiltinGoalFeasibility.SPECULATIVE
    if (classInfo.directSuperTypes.isEmpty()) {
        return BuiltinGoalFeasibility.SPECULATIVE
    }
    if (classInfo.typeParameters.size != sub.arguments.size) {
        return BuiltinGoalFeasibility.SPECULATIVE
    }

    val bindings =
        classInfo.typeParameters
            .mapIndexed { index, parameter -> parameter.id to sub.arguments[index] }
            .toMap()
    val modeledSuperClassifiers =
        classInfo.directSuperTypes.mapTo(linkedSetOf()) { superType -> superType.classifierId }
    var sawSpeculative = !modeledSuperClassifiers.containsAll(classInfo.superClassifiers)
    classInfo.directSuperTypes.forEach { directSuperType ->
        val appliedSuperType = directSuperType.substituteType(bindings) as? TcType.Constructor
        if (appliedSuperType == null) {
            sawSpeculative = true
            return@forEach
        }
        when (subtypeFeasibility(appliedSuperType, sup, classInfoById, exactContext)) {
            BuiltinGoalFeasibility.PROVABLE -> return BuiltinGoalFeasibility.PROVABLE
            BuiltinGoalFeasibility.SPECULATIVE -> sawSpeculative = true
            BuiltinGoalFeasibility.IMPOSSIBLE -> Unit
        }
    }
    return if (sawSpeculative) BuiltinGoalFeasibility.SPECULATIVE
    else BuiltinGoalFeasibility.IMPOSSIBLE
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
                subArgument is TcType.Projected || superArgument is TcType.Projected ->
                    BuiltinGoalFeasibility.SPECULATIVE
                variance == Variance.OUT_VARIANCE ->
                    subtypeFeasibility(subArgument, superArgument, classInfoById, exactContext)
                variance == Variance.IN_VARIANCE ->
                    subtypeFeasibility(superArgument, subArgument, classInfoById, exactContext)
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
    return if (sawSpeculative) BuiltinGoalFeasibility.SPECULATIVE
    else BuiltinGoalFeasibility.PROVABLE
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
            return if (
                bounds.any { bound ->
                    exactSubtypeFeasibility(bound, sup, classInfoById, visiting) ==
                        BuiltinGoalFeasibility.PROVABLE
                }
            ) {
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
        if (sub.classifierId != sup.classifierId) {
            exactDeclaredSupertypeSubtypeFeasibility(sub, sup, classInfoById, visiting)?.let {
                return it
            }
        }
        return constructorSubtypeFeasibility(sub, sup, classInfoById, this)
    }
    return when {
        sub === TcType.StarProjection || sup === TcType.StarProjection ->
            BuiltinGoalFeasibility.IMPOSSIBLE
        sub is TcType.Projected || sup is TcType.Projected -> BuiltinGoalFeasibility.IMPOSSIBLE
        else -> BuiltinGoalFeasibility.IMPOSSIBLE
    }
}

private fun FirBuiltinGoalExactContext.exactDeclaredSupertypeSubtypeFeasibility(
    sub: TcType.Constructor,
    sup: TcType.Constructor,
    classInfoById: Map<String, VisibleClassHierarchyInfo>,
    visiting: MutableSet<String>,
): BuiltinGoalFeasibility? {
    if (sub.isNullable && !sup.isNullable) {
        return BuiltinGoalFeasibility.IMPOSSIBLE
    }
    val classId = runCatching { ClassId.fromString(sub.classifierId) }.getOrNull() ?: return null
    val classSymbol = session.regularClassSymbolOrNull(classId) ?: return null
    val visitKey = "declared-super:${sub.normalizedKey()}<:${sup.normalizedKey()}"
    if (!visiting.add(visitKey)) {
        return BuiltinGoalFeasibility.IMPOSSIBLE
    }
    try {
        var sawSpeculative = false
        classSymbol.fir.declaredOrResolvedSuperTypes().forEach { superType ->
            val superModel =
                exactDeclaredSupertypeModel(classSymbol, sub, superType)
                    ?: run {
                        sawSpeculative = true
                        return@forEach
                    }
            when (exactSubtypeFeasibility(superModel, sup, classInfoById, visiting)) {
                BuiltinGoalFeasibility.PROVABLE -> return BuiltinGoalFeasibility.PROVABLE
                BuiltinGoalFeasibility.SPECULATIVE,
                null -> sawSpeculative = true
                BuiltinGoalFeasibility.IMPOSSIBLE -> Unit
            }
        }
        return if (sawSpeculative) BuiltinGoalFeasibility.SPECULATIVE
        else BuiltinGoalFeasibility.IMPOSSIBLE
    } finally {
        visiting.remove(visitKey)
    }
}

private fun FirBuiltinGoalExactContext.exactDeclaredSupertypeModel(
    classSymbol: FirRegularClassSymbol,
    sub: TcType.Constructor,
    superType: org.jetbrains.kotlin.fir.types.ConeKotlinType,
): TcType? {
    val classTypeParameters =
        classSymbol.fir.typeParameters.mapIndexed { index, typeParameter ->
            typeParameter.symbol to
                (typeParameterModels[typeParameter.symbol]
                    ?: TcTypeParameter(
                        id = "${classSymbol.classId.asString()}#$index",
                        displayName = typeParameter.symbol.name.asString(),
                    ))
        }
    if (classTypeParameters.size != sub.arguments.size) {
        return null
    }
    val superModel =
        coneTypeToModel(superType, typeParameterModels + classTypeParameters) ?: return null
    if (classTypeParameters.isEmpty()) {
        return superModel
    }
    val bindings =
        classTypeParameters
            .mapIndexed { index, (_, parameter) -> parameter.id to sub.arguments[index] }
            .toMap()
    return superModel.substituteType(bindings)
}

private fun FirBuiltinGoalExactContext.exactNotSameFeasibility(
    left: TcType,
    right: TcType,
    classInfoById: Map<String, VisibleClassHierarchyInfo>,
): BuiltinGoalFeasibility {
    if (left.normalizedKey() == right.normalizedKey()) {
        return BuiltinGoalFeasibility.IMPOSSIBLE
    }
    if (canProveNotSame(left, right)) {
        return BuiltinGoalFeasibility.PROVABLE
    }
    val leftSubtypeRight = exactSubtypeKnowledge(left, right, classInfoById)
    val rightSubtypeLeft = exactSubtypeKnowledge(right, left, classInfoById)
    return if (
        leftSubtypeRight == TypeRelationKnowledge.DISPROVABLE ||
            rightSubtypeLeft == TypeRelationKnowledge.DISPROVABLE
    ) {
        BuiltinGoalFeasibility.PROVABLE
    } else {
        BuiltinGoalFeasibility.IMPOSSIBLE
    }
}

private fun FirBuiltinGoalExactContext.exactSubtypeKnowledge(
    sub: TcType,
    sup: TcType,
    classInfoById: Map<String, VisibleClassHierarchyInfo>,
    visiting: MutableSet<String> = linkedSetOf(),
): TypeRelationKnowledge {
    if (sub.normalizedKey() == sup.normalizedKey()) {
        return TypeRelationKnowledge.PROVABLE
    }
    if (sub.containsProjectionOrStar() || sup.containsProjectionOrStar()) {
        exactMaterializedSubtypeKnowledge(sub, sup)?.let {
            return it
        }
    }
    when (sub) {
        TcType.StarProjection -> return TypeRelationKnowledge.UNKNOWN
        is TcType.Projected -> return TypeRelationKnowledge.UNKNOWN
        is TcType.Variable -> {
            val visitKey = "knowledge-sub:${sub.id}->${sup.normalizedKey()}"
            if (!visiting.add(visitKey)) {
                return TypeRelationKnowledge.UNKNOWN
            }
            try {
                val symbol = variableSymbolsById[sub.id] ?: return TypeRelationKnowledge.UNKNOWN
                val bounds =
                    symbol.resolvedBounds.mapNotNull { boundTypeRef ->
                        coneTypeToModel(boundTypeRef.coneType, typeParameterModels)
                    }
                if (
                    bounds.any { bound ->
                        exactSubtypeKnowledge(bound, sup, classInfoById, visiting) ==
                            TypeRelationKnowledge.PROVABLE
                    }
                ) {
                    return TypeRelationKnowledge.PROVABLE
                }
                return TypeRelationKnowledge.UNKNOWN
            } finally {
                visiting.remove(visitKey)
            }
        }

        is TcType.Constructor -> Unit
    }

    when (sup) {
        TcType.StarProjection -> return TypeRelationKnowledge.UNKNOWN
        is TcType.Projected -> return TypeRelationKnowledge.UNKNOWN
        is TcType.Variable -> {
            val visitKey = "knowledge-sup:${sub.normalizedKey()}->${sup.id}"
            if (!visiting.add(visitKey)) {
                return TypeRelationKnowledge.UNKNOWN
            }
            try {
                val symbol = variableSymbolsById[sup.id] ?: return TypeRelationKnowledge.UNKNOWN
                val bounds =
                    symbol.resolvedBounds.mapNotNull { boundTypeRef ->
                        coneTypeToModel(boundTypeRef.coneType, typeParameterModels)
                    }
                if (bounds.isEmpty()) {
                    return TypeRelationKnowledge.UNKNOWN
                }
                bounds.forEach { bound ->
                    if (
                        exactSubtypeKnowledge(sub, bound, classInfoById, visiting) ==
                            TypeRelationKnowledge.DISPROVABLE
                    ) {
                        return TypeRelationKnowledge.DISPROVABLE
                    }
                }
                return TypeRelationKnowledge.UNKNOWN
            } finally {
                visiting.remove(visitKey)
            }
        }

        is TcType.Constructor -> Unit
    }

    val subConstructor = sub as? TcType.Constructor ?: return TypeRelationKnowledge.UNKNOWN
    val supConstructor = sup as? TcType.Constructor ?: return TypeRelationKnowledge.UNKNOWN
    if (subConstructor.isNullable && !supConstructor.isNullable) {
        return TypeRelationKnowledge.DISPROVABLE
    }
    if (subConstructor.classifierId == supConstructor.classifierId) {
        return exactSameClassifierSubtypeKnowledge(
            subConstructor,
            supConstructor,
            classInfoById,
            visiting,
        )
    }
    val declaredKnowledge =
        exactDeclaredSupertypeSubtypeKnowledge(
            subConstructor,
            supConstructor,
            classInfoById,
            visiting,
        ) ?: TypeRelationKnowledge.UNKNOWN
    if (declaredKnowledge == TypeRelationKnowledge.PROVABLE) {
        return TypeRelationKnowledge.PROVABLE
    }
    if (subConstructor.arguments.isNotEmpty() || supConstructor.arguments.isNotEmpty()) {
        return declaredKnowledge
    }
    val pathKnowledge =
        hasSupertypePathKnowledge(
            subConstructor.classifierId,
            supConstructor.classifierId,
            classInfoById,
        )
    return when {
        pathKnowledge == TypeRelationKnowledge.PROVABLE -> TypeRelationKnowledge.PROVABLE
        declaredKnowledge == TypeRelationKnowledge.UNKNOWN ||
            pathKnowledge == TypeRelationKnowledge.UNKNOWN -> TypeRelationKnowledge.UNKNOWN
        else -> TypeRelationKnowledge.DISPROVABLE
    }
}

private fun FirBuiltinGoalExactContext.exactSameClassifierSubtypeKnowledge(
    sub: TcType.Constructor,
    sup: TcType.Constructor,
    classInfoById: Map<String, VisibleClassHierarchyInfo>,
    visiting: MutableSet<String>,
): TypeRelationKnowledge {
    val classInfo = classInfoById[sub.classifierId] ?: return TypeRelationKnowledge.UNKNOWN
    if (sub.arguments.size != sup.arguments.size) {
        return TypeRelationKnowledge.DISPROVABLE
    }
    val variances = classInfo.typeParameterVariances
    if (variances.size != sub.arguments.size) {
        return TypeRelationKnowledge.UNKNOWN
    }
    var sawUnknown = false
    sub.arguments.indices.forEach { index ->
        val subArgument = sub.arguments[index]
        val superArgument = sup.arguments[index]
        if (superArgument === TcType.StarProjection) {
            sawUnknown = true
            return@forEach
        }
        val variance = variances.getOrNull(index) ?: Variance.INVARIANT
        val argumentKnowledge =
            when {
                subArgument is TcType.Projected || superArgument is TcType.Projected ->
                    TypeRelationKnowledge.UNKNOWN
                variance == Variance.OUT_VARIANCE ->
                    exactSubtypeKnowledge(subArgument, superArgument, classInfoById, visiting)
                variance == Variance.IN_VARIANCE ->
                    exactSubtypeKnowledge(superArgument, subArgument, classInfoById, visiting)
                else ->
                    if (subArgument.normalizedKey() == superArgument.normalizedKey()) {
                        TypeRelationKnowledge.PROVABLE
                    } else {
                        TypeRelationKnowledge.DISPROVABLE
                    }
            }
        when (argumentKnowledge) {
            TypeRelationKnowledge.PROVABLE -> Unit
            TypeRelationKnowledge.UNKNOWN -> sawUnknown = true
            TypeRelationKnowledge.DISPROVABLE -> return TypeRelationKnowledge.DISPROVABLE
        }
    }
    return if (sawUnknown) TypeRelationKnowledge.UNKNOWN else TypeRelationKnowledge.PROVABLE
}

private fun FirBuiltinGoalExactContext.exactMaterializedSubtypeKnowledge(
    sub: TcType,
    sup: TcType,
): TypeRelationKnowledge? {
    val subType = sub.toConeKotlinType(variableSymbolsById) ?: return null
    val supType = sup.toConeKotlinType(variableSymbolsById) ?: return null
    return if (AbstractTypeChecker.isSubtypeOf(session.typeContext, subType, supType, false)) {
        TypeRelationKnowledge.PROVABLE
    } else {
        TypeRelationKnowledge.DISPROVABLE
    }
}

private fun TcType.toConeKotlinType(
    variableSymbolsById: Map<String, FirTypeParameterSymbol>
): ConeKotlinType? =
    when (this) {
        TcType.StarProjection -> null

        is TcType.Projected -> null

        is TcType.Variable -> variableSymbolsById[id]?.constructType(isMarkedNullable = isNullable)

        is TcType.Constructor -> {
            val classId =
                runCatching { ClassId.fromString(classifierId) }.getOrNull() ?: return null
            val arguments =
                this.arguments
                    .map { argument ->
                        argument.toConeTypeProjection(variableSymbolsById) ?: return null
                    }
                    .toTypedArray()
            classId.constructClassLikeType(typeArguments = arguments, isMarkedNullable = isNullable)
        }
    }

private fun TcType.toConeTypeProjection(
    variableSymbolsById: Map<String, FirTypeParameterSymbol>
): org.jetbrains.kotlin.fir.types.ConeTypeProjection? =
    when (this) {
        TcType.StarProjection -> ConeStarProjection

        is TcType.Projected -> {
            val nestedType = type.toConeKotlinType(variableSymbolsById) ?: return null
            when (variance) {
                Variance.IN_VARIANCE -> ConeKotlinTypeProjectionIn(nestedType)
                Variance.OUT_VARIANCE -> ConeKotlinTypeProjectionOut(nestedType)
                Variance.INVARIANT -> nestedType
            }
        }

        is TcType.Variable,
        is TcType.Constructor -> toConeKotlinType(variableSymbolsById)
    }

private fun FirBuiltinGoalExactContext.exactDeclaredSupertypeSubtypeKnowledge(
    sub: TcType.Constructor,
    sup: TcType.Constructor,
    classInfoById: Map<String, VisibleClassHierarchyInfo>,
    visiting: MutableSet<String>,
): TypeRelationKnowledge? {
    val classId = runCatching { ClassId.fromString(sub.classifierId) }.getOrNull() ?: return null
    val classSymbol = session.regularClassSymbolOrNull(classId) ?: return null
    val visitKey = "declared-super-knowledge:${sub.normalizedKey()}<:${sup.normalizedKey()}"
    if (!visiting.add(visitKey)) {
        return TypeRelationKnowledge.UNKNOWN
    }
    try {
        var sawUnknown = false
        classSymbol.fir.declaredOrResolvedSuperTypes().forEach { superType ->
            val superModel =
                exactDeclaredSupertypeModel(classSymbol, sub, superType)
                    ?: run {
                        sawUnknown = true
                        return@forEach
                    }
            when (exactSubtypeKnowledge(superModel, sup, classInfoById, visiting)) {
                TypeRelationKnowledge.PROVABLE -> return TypeRelationKnowledge.PROVABLE
                TypeRelationKnowledge.UNKNOWN -> sawUnknown = true
                TypeRelationKnowledge.DISPROVABLE -> Unit
            }
        }
        return if (sawUnknown) TypeRelationKnowledge.UNKNOWN else TypeRelationKnowledge.DISPROVABLE
    } finally {
        visiting.remove(visitKey)
    }
}

private fun hasSupertypePathKnowledge(
    sourceClassifierId: String,
    targetClassifierId: String,
    classInfoById: Map<String, VisibleClassHierarchyInfo>,
): TypeRelationKnowledge =
    when (hasSupertypePathFeasibility(sourceClassifierId, targetClassifierId, classInfoById)) {
        BuiltinGoalFeasibility.PROVABLE -> TypeRelationKnowledge.PROVABLE
        BuiltinGoalFeasibility.SPECULATIVE -> TypeRelationKnowledge.UNKNOWN
        BuiltinGoalFeasibility.IMPOSSIBLE -> TypeRelationKnowledge.DISPROVABLE
    }

private fun FirBuiltinGoalExactContext.exactNullableFeasibility(
    target: TcType
): BuiltinGoalFeasibility? =
    when (target) {
        TcType.StarProjection -> BuiltinGoalFeasibility.IMPOSSIBLE
        is TcType.Projected -> BuiltinGoalFeasibility.IMPOSSIBLE
        is TcType.Constructor ->
            if (target.isNullable) {
                BuiltinGoalFeasibility.PROVABLE
            } else {
                BuiltinGoalFeasibility.IMPOSSIBLE
            }

        is TcType.Variable ->
            if (target.isNullable) {
                BuiltinGoalFeasibility.PROVABLE
            } else {
                BuiltinGoalFeasibility.IMPOSSIBLE
            }
    }

private fun FirBuiltinGoalExactContext.exactNotNullableFeasibility(
    target: TcType,
    visiting: MutableSet<String> = linkedSetOf(),
): BuiltinGoalFeasibility? =
    when (target) {
        TcType.StarProjection -> BuiltinGoalFeasibility.IMPOSSIBLE
        is TcType.Projected -> BuiltinGoalFeasibility.IMPOSSIBLE
        is TcType.Constructor ->
            if (!target.isNullable) {
                BuiltinGoalFeasibility.PROVABLE
            } else {
                BuiltinGoalFeasibility.IMPOSSIBLE
            }

        is TcType.Variable -> {
            if (target.isNullable) {
                return BuiltinGoalFeasibility.IMPOSSIBLE
            }
            val visitKey = "not-nullable:${target.id}"
            if (!visiting.add(visitKey)) {
                return BuiltinGoalFeasibility.IMPOSSIBLE
            }
            try {
                val symbol =
                    variableSymbolsById[target.id] ?: return BuiltinGoalFeasibility.IMPOSSIBLE
                val bounds =
                    symbol.resolvedBounds.mapNotNull { boundTypeRef ->
                        coneTypeToModel(boundTypeRef.coneType, typeParameterModels)
                    }
                if (
                    bounds.any { bound ->
                        exactNotNullableFeasibility(bound, visiting) ==
                            BuiltinGoalFeasibility.PROVABLE
                    }
                ) {
                    BuiltinGoalFeasibility.PROVABLE
                } else {
                    BuiltinGoalFeasibility.IMPOSSIBLE
                }
            } finally {
                visiting.remove(visitKey)
            }
        }
    }
