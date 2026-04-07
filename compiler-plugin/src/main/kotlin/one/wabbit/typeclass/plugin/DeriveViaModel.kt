// SPDX-License-Identifier: AGPL-3.0-or-later

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
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
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

internal data class ResolvedIsoMethods(
    val leftType: IrType,
    val rightType: IrType,
    val toMethod: IrSimpleFunction,
    val fromMethod: IrSimpleFunction,
)

@OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)
internal fun IrClass.deriveViaRequests(pluginContext: IrPluginContext): List<DeriveViaRequest> =
    annotations
        .flatMap { annotation -> annotation.flattenRepeatableAnnotations(DERIVE_VIA_ANNOTATION_CLASS_ID, DERIVE_VIA_ANNOTATION_CONTAINER_CLASS_ID) }
        .mapNotNull { annotation ->
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
                    if (klass != null && klass.implementsInterface(ISO_CLASS_ID, linkedSetOf())) {
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
        .flatMap { annotation -> annotation.flattenRepeatableAnnotations(DERIVE_EQUIV_ANNOTATION_CLASS_ID, DERIVE_EQUIV_ANNOTATION_CONTAINER_CLASS_ID) }
        .mapNotNull { annotation ->
            val otherClassId =
                (annotation.getValueArgument(0) as? IrClassReference)
                    ?.classType
                    ?.classOrNull
                    ?.owner
                    ?.classId
                    ?: return@mapNotNull null
            DeriveEquivRequest(otherClassId, annotation)
        }

private fun IrConstructorCall.flattenRepeatableAnnotations(
    annotationClassId: ClassId,
    containerClassId: ClassId,
): List<IrConstructorCall> =
    when (symbol.owner.parentAsClass.classId) {
        annotationClassId -> listOf(this)
        containerClassId ->
            ((getValueArgument(0) as? IrVararg)?.elements.orEmpty())
                .mapNotNull { element ->
                    when (element) {
                        is IrSpreadElement -> element.expression as? IrConstructorCall
                        is IrExpression -> element as? IrConstructorCall
                        else -> null
                    }
                }

        else -> emptyList()
    }

@OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)
internal fun IrClass.isEquivTypeclass(): Boolean = hasAnnotation(EQUIV_CLASS_ID)

@OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)
internal fun IrClass.findIsoMethods(): ResolvedIsoMethods? {
    val overrideResolved = findIsoMethodsByOverrides()
    if (overrideResolved != null) {
        return overrideResolved
    }

    val declaredEndpoints = declaredIsoEndpoints() ?: return null
    val toMethod =
        declarations
            .filterIsInstance<IrSimpleFunction>()
            .singleOrNull { function ->
                function.name.asString() == "to" &&
                    function.valueParameters.size == 1 &&
                    function.valueParameters.single().type.sameTypeShape(declaredEndpoints.first) &&
                    function.returnType.sameTypeShape(declaredEndpoints.second)
            } ?: return null
    val fromMethod =
        declarations
            .filterIsInstance<IrSimpleFunction>()
            .singleOrNull { function ->
                function.name.asString() == "from" &&
                    function.valueParameters.size == 1 &&
                    function.valueParameters.single().type.sameTypeShape(declaredEndpoints.second) &&
                    function.returnType.sameTypeShape(declaredEndpoints.first)
            } ?: return null
    return ResolvedIsoMethods(
        leftType = declaredEndpoints.first,
        rightType = declaredEndpoints.second,
        toMethod = toMethod,
        fromMethod = fromMethod,
    )
}

private fun IrClass.findIsoMethodsByOverrides(): ResolvedIsoMethods? {
    val toMethod =
        declarations
            .filterIsInstance<IrSimpleFunction>()
                    .singleOrNull { function ->
                function.valueParameters.size == 1 &&
                    function.overriddenSymbols.any { overridden ->
                        overridden.owner.name.asString() == "to" &&
                            overridden.owner.parentAsClass.implementsInterface(ISO_CLASS_ID, linkedSetOf())
                    }
            } ?: return null
    val fromMethod =
        declarations
            .filterIsInstance<IrSimpleFunction>()
                    .singleOrNull { function ->
                function.valueParameters.size == 1 &&
                    function.overriddenSymbols.any { overridden ->
                        overridden.owner.name.asString() == "from" &&
                            overridden.owner.parentAsClass.implementsInterface(ISO_CLASS_ID, linkedSetOf())
                    }
            } ?: return null
    val leftType = toMethod.valueParameters.single().type
    val rightType = toMethod.returnType
    if (!fromMethod.valueParameters.single().type.sameTypeShape(rightType)) {
        return null
    }
    if (!fromMethod.returnType.sameTypeShape(leftType)) {
        return null
    }
    return ResolvedIsoMethods(
        leftType = leftType,
        rightType = rightType,
        toMethod = toMethod,
        fromMethod = fromMethod,
    )
}

private fun IrClass.declaredIsoEndpoints(): Pair<IrType, IrType>? =
    superTypes
        .filterIsInstance<IrSimpleType>()
        .firstNotNullOfOrNull { superType ->
            val superClassId = superType.classOrNull?.owner?.classId
            if (superClassId != ISO_CLASS_ID) {
                return@firstNotNullOfOrNull null
            }
            val leftType = superType.arguments.getOrNull(0).asIrTypeOrNull() ?: return@firstNotNullOfOrNull null
            val rightType = superType.arguments.getOrNull(1).asIrTypeOrNull() ?: return@firstNotNullOfOrNull null
            leftType to rightType
        }

private fun org.jetbrains.kotlin.ir.types.IrTypeArgument?.asIrTypeOrNull(): IrType? =
    when (this) {
        is IrType -> this
        is IrTypeProjection -> type
        else -> null
    }
