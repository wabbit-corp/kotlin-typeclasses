@file:OptIn(org.jetbrains.kotlin.fir.symbols.SymbolInternals::class)

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.SpecialNames

internal data class InstanceOwnerContext(
    val isTopLevel: Boolean,
    val isCompanionScope: Boolean,
    val associatedOwner: ClassId?,
) {
    val isIndexableScope: Boolean
        get() = isTopLevel || isCompanionScope
}

internal fun firInstanceOwnerContext(
    session: FirSession,
    classId: ClassId,
): InstanceOwnerContext {
    if (classId.isLocal) {
        return InstanceOwnerContext(isTopLevel = false, isCompanionScope = false, associatedOwner = null)
    }
    return if (classId.shortClassName == SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT) {
        InstanceOwnerContext(
            isTopLevel = false,
            isCompanionScope = true,
            associatedOwner = classId.outerClassId,
        )
    } else {
        firNestedOwnerContext(session, classId.outerClassId)
    }
}

internal fun firInstanceOwnerContext(
    session: FirSession,
    callableId: CallableId?,
): InstanceOwnerContext = firNestedOwnerContext(session, callableId?.classId)

private fun firNestedOwnerContext(
    session: FirSession,
    ownerClassId: ClassId?,
): InstanceOwnerContext {
    if (ownerClassId == null) {
        return InstanceOwnerContext(isTopLevel = true, isCompanionScope = false, associatedOwner = null)
    }
    if (ownerClassId.isLocal) {
        return InstanceOwnerContext(isTopLevel = false, isCompanionScope = false, associatedOwner = null)
    }
    val ownerClass = session.symbolProvider.getClassLikeSymbolByClassId(ownerClassId) as? FirRegularClassSymbol
    return if (ownerClass?.fir?.classKind == ClassKind.OBJECT && ownerClassId.shortClassName == SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT) {
        InstanceOwnerContext(
            isTopLevel = false,
            isCompanionScope = true,
            associatedOwner = ownerClassId.outerClassId,
        )
    } else {
        InstanceOwnerContext(isTopLevel = false, isCompanionScope = false, associatedOwner = ownerClassId)
    }
}

internal fun irInstanceOwnerContext(irClass: IrClass): InstanceOwnerContext {
    val classId = irClass.classId ?: return InstanceOwnerContext(isTopLevel = false, isCompanionScope = false, associatedOwner = null)
    return if (irClass.isCompanion) {
        InstanceOwnerContext(
            isTopLevel = false,
            isCompanionScope = true,
            associatedOwner = irClass.parentAsClass.classIdOrFail,
        )
    } else {
        irNestedOwnerContext(irClass.parent as? IrClass)
    }
}

internal fun irInstanceOwnerContext(function: IrSimpleFunction): InstanceOwnerContext =
    irNestedOwnerContext(function.parent as? IrClass)

internal fun irInstanceOwnerContext(property: IrProperty): InstanceOwnerContext =
    irNestedOwnerContext(property.parent as? IrClass)

private fun irNestedOwnerContext(ownerClass: IrClass?): InstanceOwnerContext {
    if (ownerClass == null) {
        return InstanceOwnerContext(isTopLevel = true, isCompanionScope = false, associatedOwner = null)
    }
    val ownerClassId = ownerClass.classId ?: return InstanceOwnerContext(isTopLevel = false, isCompanionScope = false, associatedOwner = null)
    return if (ownerClass.isCompanion) {
        InstanceOwnerContext(
            isTopLevel = false,
            isCompanionScope = true,
            associatedOwner = ownerClass.parentAsClass.classIdOrFail,
        )
    } else {
        InstanceOwnerContext(isTopLevel = false, isCompanionScope = false, associatedOwner = ownerClassId)
    }
}
