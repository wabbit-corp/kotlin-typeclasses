package one.wabbit.typeclass.plugin

import one.wabbit.typeclass.plugin.model.TcType
import one.wabbit.typeclass.plugin.model.isProvablyNullable
import one.wabbit.typeclass.plugin.model.normalizedKey

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

internal fun supportsBuiltinSameTypeConstructorGoal(goal: TcType): Boolean {
    val constructor = goal as? TcType.Constructor ?: return true
    if (constructor.classifierId != SAME_TYPE_CONSTRUCTOR_CLASS_ID.asString()) {
        return true
    }
    val left = constructor.arguments.getOrNull(0) as? TcType.Constructor ?: return false
    val right = constructor.arguments.getOrNull(1) as? TcType.Constructor ?: return false
    return left.classifierId == right.classifierId
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
