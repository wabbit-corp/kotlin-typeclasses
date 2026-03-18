package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.GeneratedDeclarationKey

internal object TypeclassWrapperKey : GeneratedDeclarationKey()
internal object TypeclassSyntheticReceiverKey : GeneratedDeclarationKey()

internal const val TYPECLASS_WRAPPER_NAME_PREFIX = "<typeclass-wrapper:"
internal const val TYPECLASS_WRAPPER_NAME_SUFFIX = ">"
internal const val TYPECLASS_WRAPPER_MARKER_PARAMETER_NAME = "__typeclassWrapperMarker"

internal fun org.jetbrains.kotlin.name.CallableId.hiddenTypeclassWrapperCallableId(): org.jetbrains.kotlin.name.CallableId {
    val hiddenName =
        org.jetbrains.kotlin.name.Name.special(
            TYPECLASS_WRAPPER_NAME_PREFIX + callableName.asString() + TYPECLASS_WRAPPER_NAME_SUFFIX,
        )
    return classId?.let { ownerClassId ->
        org.jetbrains.kotlin.name.CallableId(ownerClassId, hiddenName)
    } ?: org.jetbrains.kotlin.name.CallableId(packageName, hiddenName)
}

internal fun org.jetbrains.kotlin.name.CallableId.originalFromHiddenTypeclassWrapperCallableId(): org.jetbrains.kotlin.name.CallableId? {
    val callableNameString = callableName.asString()
    if (!callableNameString.startsWith(TYPECLASS_WRAPPER_NAME_PREFIX) || !callableNameString.endsWith(TYPECLASS_WRAPPER_NAME_SUFFIX)) {
        return null
    }
    val originalName =
        org.jetbrains.kotlin.name.Name.identifier(
            callableNameString
                .removePrefix(TYPECLASS_WRAPPER_NAME_PREFIX)
                .removeSuffix(TYPECLASS_WRAPPER_NAME_SUFFIX),
        )
    return classId?.let { ownerClassId ->
        org.jetbrains.kotlin.name.CallableId(ownerClassId, originalName)
    } ?: org.jetbrains.kotlin.name.CallableId(packageName, originalName)
}
