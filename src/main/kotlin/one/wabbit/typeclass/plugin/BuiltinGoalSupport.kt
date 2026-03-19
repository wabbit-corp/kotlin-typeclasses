package one.wabbit.typeclass.plugin

import one.wabbit.typeclass.plugin.model.TcType
import one.wabbit.typeclass.plugin.model.isProvablyNullable
import one.wabbit.typeclass.plugin.model.normalizedKey
import one.wabbit.typeclass.plugin.model.referencedVariableIds
import org.jetbrains.kotlin.types.Variance

internal fun supportsBuiltinKClassGoal(goal: TcType): Boolean {
    val constructor = goal as? TcType.Constructor ?: return true
    if (constructor.classifierId != KCLASS_CLASS_ID.asString()) {
        return true
    }
    val targetType = constructor.arguments.singleOrNull() ?: return false
    return !targetType.isProvablyNullable()
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
): Boolean {
    val constructor = goal as? TcType.Constructor ?: return true
    if (constructor.classifierId != SUBTYPE_CLASS_ID.asString()) {
        return true
    }
    val sub = constructor.arguments.getOrNull(0) ?: return false
    val sup = constructor.arguments.getOrNull(1) ?: return false
    return canPossiblyProveSubtype(sub, sup, classInfoById)
}

internal fun supportsBuiltinStrictSubtypeGoal(
    goal: TcType,
    classInfoById: Map<String, VisibleClassHierarchyInfo>,
): Boolean {
    val constructor = goal as? TcType.Constructor ?: return true
    if (constructor.classifierId != STRICT_SUBTYPE_CLASS_ID.asString()) {
        return true
    }
    val sub = constructor.arguments.getOrNull(0) ?: return false
    val sup = constructor.arguments.getOrNull(1) ?: return false
    return canPossiblyProveSubtype(sub, sup, classInfoById) && canProveNotSame(sub, sup)
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
): Boolean {
    val constructor = goal as? TcType.Constructor ?: return true
    if (constructor.classifierId != IS_TYPECLASS_INSTANCE_CLASS_ID.asString()) {
        return true
    }
    val target = constructor.arguments.singleOrNull() ?: return false
    return target.isPotentialTypeclassApplication(isTypeclassClassifier)
}

private fun TcType.isPotentialTypeclassApplication(
    isTypeclassClassifier: (String) -> Boolean,
): Boolean =
    when (this) {
        TcType.StarProjection -> false
        is TcType.Variable -> true
        is TcType.Projected -> type.isPotentialTypeclassApplication(isTypeclassClassifier)
        is TcType.Constructor -> isTypeclassClassifier(classifierId)
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

private fun canPossiblyProveSubtype(
    sub: TcType,
    sup: TcType,
    classInfoById: Map<String, VisibleClassHierarchyInfo>,
): Boolean {
    if (sub.normalizedKey() == sup.normalizedKey()) {
        return true
    }
    if (sub.referencedVariableIds().isNotEmpty() || sup.referencedVariableIds().isNotEmpty()) {
        return true
    }
    return when {
        sub === TcType.StarProjection || sup === TcType.StarProjection -> true
        sub is TcType.Projected || sup is TcType.Projected -> true
        sub is TcType.Constructor && sup is TcType.Constructor ->
            canPossiblyProveConstructorSubtype(sub, sup, classInfoById)

        else -> false
    }
}

private fun canPossiblyProveConstructorSubtype(
    sub: TcType.Constructor,
    sup: TcType.Constructor,
    classInfoById: Map<String, VisibleClassHierarchyInfo>,
): Boolean {
    if (sub.isNullable && !sup.isNullable) {
        return false
    }
    if (sub.classifierId == sup.classifierId) {
        return canPossiblyProveSameClassifierSubtype(sub, sup, classInfoById[sub.classifierId], classInfoById)
    }
    return hasSupertypePath(sub.classifierId, sup.classifierId, classInfoById)
}

private fun canPossiblyProveSameClassifierSubtype(
    sub: TcType.Constructor,
    sup: TcType.Constructor,
    classInfo: VisibleClassHierarchyInfo?,
    classInfoById: Map<String, VisibleClassHierarchyInfo>,
): Boolean {
    if (classInfo == null) {
        return true
    }
    if (sub.arguments.size != sup.arguments.size) {
        return false
    }
    val variances = classInfo.typeParameterVariances
    if (variances.size != sub.arguments.size) {
        return true
    }
    return sub.arguments.indices.all { index ->
        val subArgument = sub.arguments[index]
        val superArgument = sup.arguments[index]
        if (superArgument === TcType.StarProjection) {
            return@all true
        }
        val variance = variances.getOrNull(index) ?: Variance.INVARIANT
        when {
            subArgument is TcType.Projected || superArgument is TcType.Projected -> true
            variance == Variance.OUT_VARIANCE -> canPossiblyProveSubtype(subArgument, superArgument, classInfoById)
            variance == Variance.IN_VARIANCE -> canPossiblyProveSubtype(superArgument, subArgument, classInfoById)
            else -> subArgument.normalizedKey() == superArgument.normalizedKey()
        }
    }
}

private fun hasSupertypePath(
    sourceClassifierId: String,
    targetClassifierId: String,
    classInfoById: Map<String, VisibleClassHierarchyInfo>,
): Boolean {
    val info = classInfoById[sourceClassifierId] ?: return true
    if (targetClassifierId in info.superClassifiers) {
        return true
    }
    val remaining = ArrayDeque(info.superClassifiers)
    val visited = linkedSetOf<String>()
    while (remaining.isNotEmpty()) {
        val current = remaining.removeFirst()
        if (!visited.add(current)) {
            continue
        }
        if (current == targetClassifierId) {
            return true
        }
        val currentInfo = classInfoById[current] ?: return true
        remaining.addAll(currentInfo.superClassifiers)
    }
    return false
}
