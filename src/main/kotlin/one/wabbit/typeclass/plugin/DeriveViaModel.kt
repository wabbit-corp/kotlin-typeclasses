@file:OptIn(
    org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi::class,
    org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class,
)

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrSpreadElement
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.name.ClassId

internal sealed interface DeriveViaPathSegment {
    val classId: ClassId

    data class Waypoint(
        override val classId: ClassId,
    ) : DeriveViaPathSegment

    data class PinnedIso(
        override val classId: ClassId,
    ) : DeriveViaPathSegment
}

internal data class DeriveViaRequest(
    val typeclassId: ClassId,
    val path: List<DeriveViaPathSegment>,
    val annotation: IrConstructorCall,
)

internal data class DeriveEquivRequest(
    val otherClassId: ClassId,
    val annotation: IrConstructorCall,
)

@OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)
internal fun IrClass.deriveViaRequests(pluginContext: IrPluginContext): List<DeriveViaRequest> =
    annotations
        .filter { annotation ->
            annotation.symbol.owner.parentAsClass.classId == DERIVE_VIA_ANNOTATION_CLASS_ID
        }.mapNotNull { annotation ->
            val typeclassId =
                (annotation.getValueArgument(0) as? IrClassReference)
                    ?.classType
                    ?.classOrNull
                    ?.owner
                    ?.classId
                    ?: return@mapNotNull null
            val pathVararg = annotation.getValueArgument(1) as? IrVararg ?: return@mapNotNull null
            val path =
                pathVararg.elements.mapNotNull { element ->
                    val expression =
                        when (element) {
                            is IrSpreadElement -> element.expression
                            is IrExpression -> element
                            else -> null
                        } ?: return@mapNotNull null
                    val classId =
                        (expression as? IrClassReference)
                            ?.classType
                            ?.classOrNull
                            ?.owner
                            ?.classId
                            ?: return@mapNotNull null
                    val klass = pluginContext.referenceClass(classId)?.owner
                    if (klass != null && klass.isObject && klass.implementsInterface(ISO_CLASS_ID, linkedSetOf())) {
                        DeriveViaPathSegment.PinnedIso(classId)
                    } else {
                        DeriveViaPathSegment.Waypoint(classId)
                    }
                }
            DeriveViaRequest(typeclassId = typeclassId, path = path, annotation = annotation)
        }

@OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)
internal fun IrClass.deriveEquivRequests(): List<DeriveEquivRequest> =
    annotations
        .filter { annotation ->
            annotation.symbol.owner.parentAsClass.classId == DERIVE_EQUIV_ANNOTATION_CLASS_ID
        }.mapNotNull { annotation ->
            val otherClassId =
                (annotation.getValueArgument(0) as? IrClassReference)
                    ?.classType
                    ?.classOrNull
                    ?.owner
                    ?.classId
                    ?: return@mapNotNull null
            DeriveEquivRequest(otherClassId, annotation)
        }

@OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)
internal fun IrClass.isEquivTypeclass(): Boolean = hasAnnotation(EQUIV_CLASS_ID)

@OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)
internal fun IrClass.findIsoMethods(): Pair<IrSimpleFunction, IrSimpleFunction>? {
    val toMethod =
        declarations
            .filterIsInstance<IrSimpleFunction>()
            .singleOrNull { function ->
                function.name.asString() == "to" && function.valueParameters.size == 1
            } ?: return null
    val fromMethod =
        declarations
            .filterIsInstance<IrSimpleFunction>()
            .singleOrNull { function ->
                function.name.asString() == "from" && function.valueParameters.size == 1
            } ?: return null
    return toMethod to fromMethod
}
