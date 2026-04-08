// SPDX-License-Identifier: AGPL-3.0-or-later

@file:OptIn(
    org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi::class,
    org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class,
)

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.IrStatementsBuilder
import org.jetbrains.kotlin.ir.builders.declarations.addGetter
import org.jetbrains.kotlin.ir.builders.declarations.addDefaultGetter
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irAs
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irIs
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irIfThenElse
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.declarations.IrAnonymousInitializer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrTypeArgument
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.addSimpleDelegatingConstructor
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.copyTypeParameters
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.ir.util.substitute
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import kotlin.collections.ArrayDeque

internal sealed interface TransportPlan {
    val sourceType: IrType
    val targetType: IrType

    data class Identity(
        override val sourceType: IrType,
        override val targetType: IrType,
    ) : TransportPlan

    data class Composite(
        override val sourceType: IrType,
        override val targetType: IrType,
        val steps: List<TransportPlan>,
    ) : TransportPlan

    data class Nullable(
        override val sourceType: IrType,
        override val targetType: IrType,
        val inner: TransportPlan,
    ) : TransportPlan

    data class ValueUnwrap(
        override val sourceType: IrType,
        override val targetType: IrType,
        val getter: IrSimpleFunction,
        val nested: TransportPlan,
    ) : TransportPlan

    data class ValueWrap(
        override val sourceType: IrType,
        override val targetType: IrType,
        val constructor: IrConstructor,
        val nested: TransportPlan,
    ) : TransportPlan

    data class Product(
        override val sourceType: IrType,
        override val targetType: IrType,
        val targetInfo: TransparentProductInfo,
        val assignments: List<ProductAssignment>,
    ) : TransportPlan

    data class Sum(
        override val sourceType: IrType,
        override val targetType: IrType,
        val mappings: List<SumCaseMapping>,
    ) : TransportPlan

    data class Function(
        override val sourceType: IrType,
        override val targetType: IrType,
        val parameterPlans: List<TransportPlan>,
        val returnPlan: TransportPlan,
    ) : TransportPlan

    data class PinnedIso(
        override val sourceType: IrType,
        override val targetType: IrType,
        val isoObject: IrClass,
        val method: IrSimpleFunction,
    ) : TransportPlan
}

internal data class ProductAssignment(
    val targetField: TransparentField,
    val sourceField: TransparentField?,
    val plan: TransportPlan?,
)

internal data class SumCaseMapping(
    val sourceCase: IrClass,
    val targetCase: IrClass,
    val plan: TransportPlan,
)

internal data class TransparentField(
    val property: IrProperty,
    val getter: IrSimpleFunction,
    val type: IrType,
    val isUnitLike: Boolean,
)

internal data class TransparentProductInfo(
    val klass: IrClass,
    val type: IrType,
    val constructor: IrConstructor?,
    val fields: List<TransparentField>,
    val isObjectLike: Boolean,
)

internal data class ResolvedViaPath(
    val viaType: IrType,
    val forwardPlan: TransportPlan,
    val backwardPlan: TransportPlan,
)

private data class EffectiveAbstractProperty(
    val property: IrProperty,
    val getter: IrSimpleFunction,
    val ownerSubstitution: Map<IrTypeParameterSymbol, IrType>,
)

private data class EffectiveAbstractFunction(
    val function: IrSimpleFunction,
    val ownerSubstitution: Map<IrTypeParameterSymbol, IrType>,
)

internal sealed interface TransportabilityFailure {
    data class Invalid(
        val message: String,
    ) : TransportabilityFailure

    data class Ambiguous(
        val message: String,
    ) : TransportabilityFailure
}

internal class DirectTransportPlanner(
    private val pluginContext: IrPluginContext,
) {
    fun planEquiv(
        sourceType: IrType,
        targetType: IrType,
    ): Pair<TransportPlan, TransportPlan>? {
        val forward = synthesize(sourceType, targetType) ?: return null
        val backward = synthesize(targetType, sourceType) ?: return null
        return forward to backward
    }

    fun resolveViaPath(
        sourceType: IrType,
        path: List<DeriveViaPathSegment>,
    ): Result<ResolvedViaPath> {
        if (path.isEmpty()) {
            return Result.failure(IllegalArgumentException("DeriveVia requires a non-empty path"))
        }

        var current = sourceType
        val forwardSteps = mutableListOf<TransportPlan>()
        val backwardSteps = ArrayDeque<TransportPlan>()

        for (segment in path) {
            when (segment) {
                is DeriveViaPathSegment.Waypoint -> {
                    val waypointClass =
                        pluginContext.referenceClass(segment.classId)?.owner
                            ?: return Result.failure(
                                IllegalArgumentException("Could not resolve DeriveVia waypoint ${segment.classId.asString()}"),
                            )
                    if (waypointClass.typeParameters.isNotEmpty()) {
                        return Result.failure(
                            IllegalArgumentException(
                                "Generic DeriveVia waypoints are not supported yet: ${segment.classId.asString()}",
                            ),
                        )
                    }
                    val waypointType = waypointClass.symbol.defaultType
                    val plans =
                        planEquiv(current, waypointType)
                            ?: return Result.failure(
                                IllegalArgumentException(
                                    "Cannot derive via ${segment.classId.asString()} from ${current.render()}",
                                ),
                            )
                    forwardSteps += plans.first
                    backwardSteps.addFirst(plans.second)
                    current = waypointType
                }

                is DeriveViaPathSegment.PinnedIso -> {
                    val isoClass =
                        pluginContext.referenceClass(segment.classId)?.owner
                            ?: return Result.failure(
                                IllegalArgumentException("Could not resolve pinned Iso ${segment.classId.asString()}"),
                            )
                    if (!isoClass.isObject) {
                        return Result.failure(
                            IllegalArgumentException("Pinned Iso path segments must name object singletons"),
                        )
                    }
                    val methods =
                        isoClass.findIsoMethods()
                            ?: return Result.failure(
                                IllegalArgumentException("Pinned Iso ${segment.classId.asString()} must define one exact Iso to/from override pair"),
                            )
                    val toMethod = methods.toMethod
                    val fromMethod = methods.fromMethod
                    val leftType = methods.leftType
                    val rightType = methods.rightType
                    val leftPlans = planEquiv(current, leftType)
                    val rightPlans = planEquiv(current, rightType)
                    when {
                        leftPlans != null && rightPlans != null ->
                            return Result.failure(
                                IllegalStateException(
                                    "Pinned Iso ${segment.classId.asString()} is ambiguous because both endpoints are reachable from ${current.render()}",
                                ),
                            )

                        leftPlans != null -> {
                            forwardSteps += leftPlans.first
                            forwardSteps += TransportPlan.PinnedIso(leftType, rightType, isoClass, toMethod)
                            backwardSteps.addFirst(leftPlans.second)
                            backwardSteps.addFirst(TransportPlan.PinnedIso(rightType, leftType, isoClass, fromMethod))
                            current = rightType
                        }

                        rightPlans != null -> {
                            forwardSteps += rightPlans.first
                            forwardSteps += TransportPlan.PinnedIso(rightType, leftType, isoClass, fromMethod)
                            backwardSteps.addFirst(rightPlans.second)
                            backwardSteps.addFirst(TransportPlan.PinnedIso(leftType, rightType, isoClass, toMethod))
                            current = leftType
                        }

                        else ->
                            return Result.failure(
                                IllegalArgumentException(
                                    "Pinned Iso ${segment.classId.asString()} is disconnected from ${current.render()}",
                                ),
                            )
                    }
                }
            }
        }

        val forward = compose(sourceType, current, forwardSteps)
        val backward = compose(current, sourceType, backwardSteps.toList())
        return Result.success(
            ResolvedViaPath(
                viaType = current,
                forwardPlan = forward,
                backwardPlan = backward,
            ),
        )
    }

    fun synthesize(
        sourceType: IrType,
        targetType: IrType,
    ): TransportPlan? = synthesize(sourceType, targetType, linkedSetOf())

    private fun synthesize(
        sourceType: IrType,
        targetType: IrType,
        visiting: MutableSet<Pair<TransportTypeShapeKey, TransportTypeShapeKey>>,
    ): TransportPlan? {
        if (sourceType.sameTypeShape(targetType)) {
            return TransportPlan.Identity(sourceType, targetType)
        }

        val visitKey = sourceType.transportTypeShapeKey() to targetType.transportTypeShapeKey()
        if (!visiting.add(visitKey)) {
            return null
        }
        try {
            if (sourceType.isMarkedNullable() || targetType.isMarkedNullable()) {
                if (!sourceType.isMarkedNullable() || !targetType.isMarkedNullable()) {
                    return null
                }
                val sourceInner = sourceType.withoutNullability() ?: return null
                val targetInner = targetType.withoutNullability() ?: return null
                val inner = synthesize(sourceInner, targetInner, visiting) ?: return null
                return TransportPlan.Nullable(sourceType, targetType, inner)
            }

            val sourceSimple = sourceType as? IrSimpleType ?: return null
            val targetSimple = targetType as? IrSimpleType ?: return null
            val sourceClass = sourceSimple.classOrNull?.owner
            val targetClass = targetSimple.classOrNull?.owner

            if (sourceClass == null || targetClass == null) {
                return null
            }

            if (sourceClass.isTransparentValueClass() && !targetType.sameTypeShape(sourceType)) {
                val sourceField =
                    sourceClass.transparentValueField(
                        accessContext = sourceClass.transportAccessContext(),
                        requireConstructorAccess = false,
                    ) ?: return null
                val nested = synthesize(sourceField.type, targetType, visiting) ?: return null
                return TransportPlan.ValueUnwrap(sourceType, targetType, sourceField.getter, nested)
            }

            if (targetClass.isTransparentValueClass() && !targetType.sameTypeShape(sourceType)) {
                val targetField =
                    targetClass.transparentValueField(
                        accessContext = targetClass.transportAccessContext(),
                        requireConstructorAccess = true,
                    ) ?: return null
                val constructor =
                    targetClass.primaryConstructorOrNullTransportAccessible(targetClass.transportAccessContext()) ?: return null
                val nested = synthesize(sourceType, targetField.type, visiting) ?: return null
                return TransportPlan.ValueWrap(sourceType, targetType, constructor, nested)
            }

            synthesizeFunction(sourceType, targetType, visiting)?.let { return it }
            synthesizeProduct(sourceType, targetType, sourceClass, targetClass, visiting)?.let { return it }
            synthesizeSum(sourceType, targetType, sourceClass, targetClass, visiting)?.let { return it }

            return null
        } finally {
            visiting.remove(visitKey)
        }
    }

    private fun synthesizeFunction(
        sourceType: IrType,
        targetType: IrType,
        visiting: MutableSet<Pair<TransportTypeShapeKey, TransportTypeShapeKey>>,
    ): TransportPlan? {
        val sourceInfo = sourceType.functionTypeInfoOrNull() ?: return null
        val targetInfo = targetType.functionTypeInfoOrNull() ?: return null
        if (sourceInfo.kind != targetInfo.kind || sourceInfo.parameterTypes.size != targetInfo.parameterTypes.size) {
            return null
        }
        val argumentPlans =
            sourceInfo.parameterTypes.zip(targetInfo.parameterTypes).map { (sourceParameter, targetParameter) ->
                synthesize(targetParameter, sourceParameter, visiting) ?: return null
            }
        val returnPlan =
            synthesize(sourceInfo.returnType, targetInfo.returnType, visiting)
                ?: return null
        return TransportPlan.Function(
            sourceType = sourceType,
            targetType = targetType,
            parameterPlans = argumentPlans,
            returnPlan = returnPlan,
        )
    }

    private fun synthesizeProduct(
        sourceType: IrType,
        targetType: IrType,
        sourceClass: IrClass,
        targetClass: IrClass,
        visiting: MutableSet<Pair<TransportTypeShapeKey, TransportTypeShapeKey>>,
    ): TransportPlan? {
        val sourceInfo =
            sourceClass.transparentProductInfo(
                concreteType = sourceType,
                accessContext = sourceClass.transportAccessContext(),
                requireConstructorAccess = false,
            ) ?: return null
        val targetInfo =
            targetClass.transparentProductInfo(
                concreteType = targetType,
                accessContext = targetClass.transportAccessContext(),
                requireConstructorAccess = true,
            ) ?: return null
        if (sourceInfo.isObjectLike && targetInfo.isObjectLike) {
            return TransportPlan.Product(sourceType, targetType, targetInfo, emptyList())
        }

        val sourceNonUnit = sourceInfo.fields.filterNot(TransparentField::isUnitLike)
        val targetNonUnit = targetInfo.fields.filterNot(TransparentField::isUnitLike)
        if (sourceNonUnit.size != targetNonUnit.size) {
            return null
        }

        val positionalAssignments =
            targetNonUnit.zip(sourceNonUnit).map { (targetField, sourceField) ->
                val plan = synthesize(sourceField.type, targetField.type, visiting) ?: return@map null
                ProductAssignment(targetField, sourceField, plan)
            }
        val normalizedAssignments =
            if (positionalAssignments.all { assignment -> assignment != null }) {
                positionalAssignments.filterNotNull()
            } else {
                val allMatches =
                    targetNonUnit.map { targetField ->
                        sourceNonUnit.mapNotNull { sourceField ->
                            val plan = synthesize(sourceField.type, targetField.type, visiting) ?: return@mapNotNull null
                            ProductAssignment(targetField, sourceField, plan)
                        }
                    }
                if (allMatches.any(List<ProductAssignment>::isEmpty)) {
                    return null
                }
                val remaining = sourceNonUnit.toMutableSet()
                val assignments = mutableListOf<ProductAssignment>()
                for (choices in allMatches) {
                    val viable = choices.filter { choice -> choice.sourceField in remaining }
                    if (viable.size != 1) {
                        return null
                    }
                    val selected = viable.single()
                    remaining -= selected.sourceField!!
                    assignments += selected
                }
                assignments
            }

        val assignmentsByTarget = normalizedAssignments.associateBy { assignment -> assignment.targetField.property.symbol }
        val finalAssignments =
            targetInfo.fields.map { targetField ->
                if (targetField.isUnitLike) {
                    ProductAssignment(targetField = targetField, sourceField = null, plan = null)
                } else {
                    assignmentsByTarget[targetField.property.symbol] ?: return null
                }
            }

        return TransportPlan.Product(
            sourceType = sourceType,
            targetType = targetType,
            targetInfo = targetInfo,
            assignments = finalAssignments,
        )
    }

    private fun synthesizeSum(
        sourceType: IrType,
        targetType: IrType,
        sourceClass: IrClass,
        targetClass: IrClass,
        visiting: MutableSet<Pair<TransportTypeShapeKey, TransportTypeShapeKey>>,
    ): TransportPlan? {
        if (sourceClass.modality != Modality.SEALED || targetClass.modality != Modality.SEALED) {
            return null
        }
        val sourceCases = sourceClass.transparentSealedCases(sourceClass.transportAccessContext()) ?: return null
        val targetCases = targetClass.transparentSealedCases(targetClass.transportAccessContext()) ?: return null
        if (sourceCases.size != targetCases.size) {
            return null
        }
        val remainingTargets = targetCases.toMutableSet()
        val mappings = mutableListOf<SumCaseMapping>()
        for (sourceCase in sourceCases) {
            val viable =
                remainingTargets.mapNotNull { targetCase ->
                    val plan = synthesize(sourceCase.symbol.defaultType, targetCase.symbol.defaultType, visiting) ?: return@mapNotNull null
                    SumCaseMapping(sourceCase = sourceCase, targetCase = targetCase, plan = plan)
                }
            if (viable.size != 1) {
                return null
            }
            val chosen = viable.single()
            remainingTargets -= chosen.targetCase
            mappings += chosen
        }
        return TransportPlan.Sum(sourceType, targetType, mappings)
    }

    private fun compose(
        sourceType: IrType,
        targetType: IrType,
        steps: List<TransportPlan>,
    ): TransportPlan =
        when (steps.size) {
            0 -> TransportPlan.Identity(sourceType, targetType)
            1 -> steps.single()
            else -> TransportPlan.Composite(sourceType, targetType, steps)
        }
}

internal fun IrBuilderWithScope.buildTransportExpression(
    plan: TransportPlan,
    value: IrExpression,
    pluginContext: IrPluginContext,
    lambdaParent: IrDeclarationParent,
): IrExpression =
    when (plan) {
        is TransportPlan.Identity -> value

        is TransportPlan.Composite -> {
            var current = value
            for (step in plan.steps) {
                current = buildTransportExpression(step, current, pluginContext, lambdaParent)
            }
            current
        }

        is TransportPlan.Nullable -> {
            val stableValue = stabilizeTransportValueIfNeeded(value, "deriveViaNullableValue")
            irIfThenElse(
                type = plan.targetType,
                condition =
                    irCall(pluginContext.irBuiltIns.eqeqSymbol).apply {
                        putValueArgument(0, stableValue)
                        putValueArgument(1, irNull())
                    },
                thenPart = irNull(),
                elsePart =
                    buildTransportExpression(
                        plan = plan.inner,
                        value = irAs(stableValue, plan.inner.sourceType),
                        pluginContext = pluginContext,
                        lambdaParent = lambdaParent,
                    ).let { expression ->
                        if (expression.type == plan.targetType) {
                            expression
                        } else {
                            irAs(expression, plan.targetType)
                        }
                    },
            )
        }

        is TransportPlan.ValueUnwrap ->
            buildTransportExpression(
                plan = plan.nested,
                value =
                    irCall(plan.getter.symbol).apply {
                        dispatchReceiver = irAs(value, plan.sourceType)
                    },
                pluginContext = pluginContext,
                lambdaParent = lambdaParent,
            )

        is TransportPlan.ValueWrap ->
            irCallConstructor(plan.constructor.symbol, plan.targetType.classTypeArguments()).apply {
                putValueArgument(
                    0,
                    buildTransportExpression(
                        plan = plan.nested,
                        value = value,
                        pluginContext = pluginContext,
                        lambdaParent = lambdaParent,
                    ),
                )
            }

        is TransportPlan.Product ->
            if (plan.targetInfo.isObjectLike) {
                irGetObject(plan.targetInfo.klass.symbol)
            } else {
                val constructor =
                    plan.targetInfo.constructor
                        ?: error("Missing constructor for ${plan.targetInfo.klass.classIdOrFail}")
                irCallConstructor(constructor.symbol, plan.targetType.classTypeArguments()).apply {
                    plan.assignments.forEachIndexed { index, assignment ->
                        val argument =
                            if (assignment.sourceField == null) {
                                irGetObject(
                                    pluginContext.referenceClass(ClassId.topLevel(FqName("kotlin.Unit")))!!.owner.symbol,
                                )
                            } else {
                                buildTransportExpression(
                                    plan = assignment.plan ?: error("Missing field transport plan"),
                                    value =
                                        irCall(assignment.sourceField.getter.symbol).apply {
                                            dispatchReceiver = irAs(value, plan.sourceType)
                                        },
                                    pluginContext = pluginContext,
                                    lambdaParent = lambdaParent,
                                )
                            }
                        putValueArgument(index, argument)
                    }
                }
            }

        is TransportPlan.Sum -> {
            val stableValue = stabilizeTransportValueIfNeeded(value, "deriveViaSumValue")
            var elseBranch: IrExpression =
                irTypeclassInternalError(
                    pluginContext = pluginContext,
                    message =
                        impossibleSumTransportRuntimeMessage(
                            sourceType = plan.sourceType.render(),
                            targetType = plan.targetType.render(),
                        ),
                )
            for (mapping in plan.mappings.asReversed()) {
                elseBranch =
                    irIfThenElse(
                        type = plan.targetType,
                        condition = irIs(stableValue, mapping.sourceCase.symbol.defaultType),
                        thenPart =
                            buildTransportExpression(
                                plan = mapping.plan,
                                value = irAs(stableValue, mapping.sourceCase.symbol.defaultType),
                                pluginContext = pluginContext,
                                lambdaParent = lambdaParent,
                            ),
                        elsePart = elseBranch,
                    )
            }
            elseBranch
        }

        is TransportPlan.Function -> {
            val lambdaType = plan.targetType
            val targetInfo = plan.targetType.functionTypeInfoOrNull() ?: error("Missing target function info")
            val functionClass = plan.sourceType.classOrNull?.owner ?: error("Missing function classifier")
            val sourceFunctionValue =
                if (this is IrStatementsBuilder<*>) {
                    irTemporary(value, nameHint = "deriveViaFunctionValue")
                } else {
                    null
                }
            val invokeFunction =
                functionClass.declarations.filterIsInstance<IrSimpleFunction>().singleOrNull { function -> function.name.asString() == "invoke" }
                    ?: error("Missing invoke on ${functionClass.classIdOrFail}")
            val lambda =
                context.irFactory.buildFun {
                    name = Name.special("<derive-via-function-wrapper>")
                    origin = IrDeclarationOrigin.LOCAL_FUNCTION
                    visibility = DescriptorVisibilities.LOCAL
                    returnType = targetInfo.returnType
                    isSuspend = invokeFunction.isSuspend
                }.apply {
                    parent = lambdaParent
                }
            val copiedParameters =
                targetInfo.parameterTypes.mapIndexed { index, parameterType ->
                    lambda.addValueParameter("p$index", parameterType)
                }
            lambda.parameters = copiedParameters
            lambda.returnType = targetInfo.returnType
            lambda.body =
                DeclarationIrBuilder(pluginContext, lambda.symbol, startOffset, endOffset).irBlockBody {
                    val call =
                        irCall(invokeFunction.symbol).apply {
                            dispatchReceiver =
                                irAs(
                                    sourceFunctionValue?.let { irGet(it) } ?: value,
                                    plan.sourceType,
                                )
                        }
                    copiedParameters.forEachIndexed { index, parameter ->
                        call.putValueArgument(
                            index,
                            buildTransportExpression(
                                plan = plan.parameterPlans[index],
                                value = irGet(parameter),
                                pluginContext = pluginContext,
                                lambdaParent = lambdaParent,
                            ),
                        )
                    }
                    +irReturn(
                        buildTransportExpression(
                            plan = plan.returnPlan,
                            value = call,
                            pluginContext = pluginContext,
                            lambdaParent = lambdaParent,
                        ),
                    )
                }
            IrFunctionExpressionImpl(
                startOffset = startOffset,
                endOffset = endOffset,
                type = lambdaType,
                function = lambda,
                origin = IrStatementOrigin.LAMBDA,
            )
        }

        is TransportPlan.PinnedIso ->
            irCall(plan.method.symbol).apply {
                dispatchReceiver = irGetObject(plan.isoObject.symbol)
                putValueArgument(0, irAs(value, plan.sourceType))
            }
    }

private fun IrBuilderWithScope.stabilizeTransportValueIfNeeded(
    value: IrExpression,
    nameHint: String,
): IrExpression =
    if (this is IrStatementsBuilder<*>) {
        irGet(irTemporary(value, nameHint = nameHint))
    } else {
        value
    }

internal fun IrStatementsBuilder<*>.buildGeneratedEquivExpression(
    sourceType: IrType,
    targetType: IrType,
    forwardPlan: TransportPlan,
    backwardPlan: TransportPlan,
    pluginContext: IrPluginContext,
    lambdaParent: IrDeclarationParent,
): IrExpression {
    val unsafeEquiv =
        pluginContext.referenceFunctions(CallableId(FqName("one.wabbit.typeclass"), Name.identifier("unsafeEquiv")))
            .map { it.owner }
            .singleOrNull { function -> function.typeParameters.size == 2 && function.valueParameters.size == 2 }
            ?: error("Could not resolve one.wabbit.typeclass.unsafeEquiv")
    val sourceAnyType = sourceType
    val targetAnyType = targetType
    val toLambda =
        buildTransportLambda(
            inputType = sourceAnyType,
            outputType = targetAnyType,
            plan = forwardPlan,
            pluginContext = pluginContext,
            lambdaParent = lambdaParent,
        )
    val fromLambda =
        buildTransportLambda(
            inputType = targetAnyType,
            outputType = sourceAnyType,
            plan = backwardPlan,
            pluginContext = pluginContext,
            lambdaParent = lambdaParent,
        )
    return irCall(unsafeEquiv.symbol).apply {
        putTypeArgument(0, sourceType)
        putTypeArgument(1, targetType)
        putValueArgument(0, toLambda)
        putValueArgument(1, fromLambda)
    }
}

internal fun IrStatementsBuilder<*>.buildDeriveViaAdapterExpression(
    typeclassInterface: IrClass,
    expectedType: IrType,
    viaType: IrType,
    viaInstance: IrExpression,
    targetType: IrType,
    forwardPlan: TransportPlan,
    backwardPlan: TransportPlan,
    pluginContext: IrPluginContext,
    lambdaParent: IrDeclarationParent,
): IrExpression {
    val adapterClass =
        context.irFactory.buildClass {
            startOffset = this@buildDeriveViaAdapterExpression.startOffset
            endOffset = this@buildDeriveViaAdapterExpression.endOffset
            origin = IrDeclarationOrigin.DEFINED
            name = Name.special("<derive-via-adapter>")
            visibility = DescriptorVisibilities.LOCAL
            kind = ClassKind.CLASS
            modality = Modality.FINAL
        }.apply {
            createThisReceiverParameter()
            parent = lambdaParent
            superTypes = listOf(expectedType)
        }
    +adapterClass

    val viaField =
        adapterClass.addField {
            startOffset = this@buildDeriveViaAdapterExpression.startOffset
            endOffset = this@buildDeriveViaAdapterExpression.endOffset
            origin = IrDeclarationOrigin.DEFINED
            name = Name.identifier("viaInstance")
            type = viaInstance.type
            visibility = DescriptorVisibilities.PRIVATE
            isFinal = true
        }

    val anyConstructor = pluginContext.irBuiltIns.anyClass.owner.primaryConstructorOrNullLocal()
        ?: error("Could not resolve Any.<init>")
    val constructor = adapterClass.addSimpleDelegatingConstructor(anyConstructor, pluginContext.irBuiltIns, isPrimary = true)
    val viaParameter = constructor.addValueParameter("via", viaInstance.type)
    constructor.body =
        DeclarationIrBuilder(pluginContext, constructor.symbol, startOffset, endOffset).irBlockBody {
            +irDelegatingConstructorCall(anyConstructor)
            +irSetField(irGet(adapterClass.thisReceiver!!), viaField, irGet(viaParameter))
        }

    val expectedSimple = expectedType as? IrSimpleType ?: error("Expected a simple typeclass interface type")
    val expectedArguments =
        expectedSimple.arguments.map { argument ->
            when (argument) {
                is org.jetbrains.kotlin.ir.types.IrTypeProjection -> argument.type
                is IrType -> argument
                else -> error("Star projections are not supported in DeriveVia typeclass heads")
            }
        }
    val viaArguments = expectedArguments.dropLast(1) + viaType
    val targetSubstitutionBase =
        typeclassInterface.typeParameters.zip(expectedArguments).associate { (parameter, argument) ->
            parameter.symbol to argument
        }
    val viaSubstitutionBase =
        typeclassInterface.typeParameters.zip(viaArguments).associate { (parameter, argument) ->
            parameter.symbol to argument
        }
    val abstractSurface = typeclassInterface.effectiveAbstractTypeclassSurface()

    abstractSurface.properties.forEach { member ->
        val property = member.property
        val getter = property.getter ?: return@forEach
        val targetProperty =
            adapterClass.addProperty {
                startOffset = property.startOffset
                endOffset = property.endOffset
                origin = IrDeclarationOrigin.DEFINED
                name = property.name
                visibility = property.visibility
                modality = Modality.OPEN
                isVar = false
            }
        val targetGetter =
            targetProperty.addGetter {
                startOffset = getter.startOffset
                endOffset = getter.endOffset
                origin = IrDeclarationOrigin.DEFINED
                name = getter.name
                visibility = getter.visibility
                modality = Modality.OPEN
                returnType =
                    member
                        .substituteInOwnerContext(getter.returnType)
                        .substitute(targetSubstitutionBase)
            }
        targetGetter.dispatchReceiverParameter =
            adapterClass.thisReceiver?.copyTo(
                targetGetter,
                type = adapterClass.symbol.defaultType,
                kind = org.jetbrains.kotlin.ir.declarations.IrParameterKind.DispatchReceiver,
            )
        targetGetter.overriddenSymbols = listOf(getter.symbol)
        targetGetter.body =
            DeclarationIrBuilder(pluginContext, targetGetter.symbol, startOffset, endOffset).irBlockBody {
                val viaCall =
                    irCall(getter.symbol).apply {
                        dispatchReceiver =
                            irGetField(irGet(targetGetter.dispatchReceiverParameter!!), viaField)
                    }
                +irReturn(
                    buildTransportExpression(
                        plan =
                            planMemberTransport(
                                sourceType =
                                    member
                                        .substituteInOwnerContext(getter.returnType)
                                        .substitute(viaSubstitutionBase),
                                targetType =
                                    member
                                        .substituteInOwnerContext(getter.returnType)
                                        .substitute(targetSubstitutionBase),
                                viaType = viaType,
                                targetValueType = targetType,
                                forwardPlan = forwardPlan,
                                backwardPlan = backwardPlan,
                                pluginContext = pluginContext,
                            ) ?: TransportPlan.Identity(viaCall.type, viaCall.type),
                        value = viaCall,
                        pluginContext = pluginContext,
                        lambdaParent = lambdaParent,
                    ),
                )
            }
    }

    abstractSurface.functions.forEach { member ->
        val function = member.function
        val overrideFunction =
            adapterClass.addFunction {
                startOffset = function.startOffset
                endOffset = function.endOffset
                origin = IrDeclarationOrigin.DEFINED
                name = function.name
                visibility = function.visibility
                modality = Modality.OPEN
                isSuspend = function.isSuspend
                returnType =
                    member
                        .substituteInOwnerContext(function.returnType)
                        .substitute(targetSubstitutionBase)
            }
        overrideFunction.dispatchReceiverParameter =
            adapterClass.thisReceiver?.copyTo(
                overrideFunction,
                type = adapterClass.symbol.defaultType,
                kind = org.jetbrains.kotlin.ir.declarations.IrParameterKind.DispatchReceiver,
            )
        overrideFunction.overriddenSymbols = listOf(function.symbol)
        val copiedTypeParameters = overrideFunction.copyTypeParameters(function.typeParameters)
        val typeParameterSubstitutions =
            copiedTypeParameters.zip(function.typeParameters).associate { (copied, original) ->
                original.symbol to copied.symbol.defaultType
            }
        val targetSubstitution = targetSubstitutionBase + typeParameterSubstitutions
        val viaSubstitution = viaSubstitutionBase + typeParameterSubstitutions
        function.extensionReceiverParameter?.let { receiver ->
            overrideFunction.extensionReceiverParameter =
                receiver.copyTo(
                    overrideFunction,
                    type =
                        member
                            .substituteInOwnerContext(receiver.type)
                            .substitute(targetSubstitution),
                    kind = receiver.kind,
                )
        }
        val copiedParameters =
            function.parameters.filter { parameter ->
                parameter.kind != org.jetbrains.kotlin.ir.declarations.IrParameterKind.DispatchReceiver
            }.map { parameter ->
                parameter.copyTo(
                    overrideFunction,
                    type =
                        when (parameter.kind) {
                            org.jetbrains.kotlin.ir.declarations.IrParameterKind.Context,
                            org.jetbrains.kotlin.ir.declarations.IrParameterKind.Regular,
                            org.jetbrains.kotlin.ir.declarations.IrParameterKind.ExtensionReceiver ->
                                member
                                    .substituteInOwnerContext(parameter.type)
                                    .substitute(targetSubstitution)
                            else -> parameter.type
                        },
                    kind = parameter.kind,
                )
            }
        overrideFunction.parameters =
            buildList {
                add(overrideFunction.dispatchReceiverParameter!!)
                overrideFunction.extensionReceiverParameter?.let(::add)
                addAll(copiedParameters.filter { it.kind != org.jetbrains.kotlin.ir.declarations.IrParameterKind.ExtensionReceiver })
            }
        overrideFunction.returnType =
            member
                .substituteInOwnerContext(function.returnType)
                .substitute(targetSubstitution)
        overrideFunction.body =
            DeclarationIrBuilder(pluginContext, overrideFunction.symbol, startOffset, endOffset).irBlockBody {
                val viaCall =
                    irCall(function.symbol).apply {
                        dispatchReceiver =
                            irGetField(irGet(overrideFunction.dispatchReceiverParameter!!), viaField)
                    }
                function.typeParameters.forEachIndexed { index, _ ->
                    viaCall.putTypeArgument(index, copiedTypeParameters[index].symbol.defaultType)
                }
                function.extensionReceiverParameter?.let { originalReceiver ->
                    val receiver = overrideFunction.extensionReceiverParameter ?: error("Missing copied extension receiver")
                    val plan =
                        planMemberTransport(
                            sourceType = receiver.type,
                            targetType =
                                member
                                    .substituteInOwnerContext(originalReceiver.type)
                                    .substitute(viaSubstitution),
                            viaType = viaType,
                            targetValueType = targetType,
                            forwardPlan = forwardPlan,
                            backwardPlan = backwardPlan,
                            pluginContext = pluginContext,
                        ) ?: TransportPlan.Identity(receiver.type, receiver.type)
                    viaCall.extensionReceiver =
                        buildTransportExpression(plan, irGet(receiver), pluginContext, lambdaParent)
                }
                val valueParameters =
                    copiedParameters.filter { parameter ->
                        parameter.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Context ||
                            parameter.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Regular
                    }
                valueParameters.forEachIndexed { index, parameter ->
                    val originalParameter =
                        function.parameters.filter { candidate ->
                            candidate.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Context ||
                                candidate.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Regular
                        }[index]
                    val plan =
                        planMemberTransport(
                            sourceType = parameter.type,
                            targetType =
                                member
                                    .substituteInOwnerContext(originalParameter.type)
                                    .substitute(viaSubstitution),
                            viaType = viaType,
                            targetValueType = targetType,
                            forwardPlan = forwardPlan,
                            backwardPlan = backwardPlan,
                            pluginContext = pluginContext,
                        ) ?: TransportPlan.Identity(parameter.type, parameter.type)
                    viaCall.putValueArgument(
                        function.valueArgumentIndexOrFallback(originalParameter, index),
                        buildTransportExpression(plan, irGet(parameter), pluginContext, lambdaParent),
                    )
                }
                val returnPlan =
                    planMemberTransport(
                        sourceType =
                            member
                                .substituteInOwnerContext(function.returnType)
                                .substitute(viaSubstitution),
                        targetType =
                            member
                                .substituteInOwnerContext(function.returnType)
                                .substitute(targetSubstitution),
                        viaType = viaType,
                        targetValueType = targetType,
                        forwardPlan = forwardPlan,
                        backwardPlan = backwardPlan,
                        pluginContext = pluginContext,
                    ) ?: TransportPlan.Identity(viaCall.type, viaCall.type)
                +irReturn(
                    buildTransportExpression(
                        plan = returnPlan,
                        value = viaCall,
                        pluginContext = pluginContext,
                        lambdaParent = lambdaParent,
                    ),
                )
            }
    }

    return irCallConstructor(constructor.symbol, emptyList()).apply {
        putValueArgument(0, viaInstance)
    }
}

private fun planMemberTransport(
    sourceType: IrType,
    targetType: IrType,
    viaType: IrType,
    targetValueType: IrType,
    forwardPlan: TransportPlan,
    backwardPlan: TransportPlan,
    pluginContext: IrPluginContext,
): TransportPlan? {
    if (sourceType.sameTypeShape(viaType) && targetType.sameTypeShape(targetValueType)) {
        return backwardPlan
    }
    if (sourceType.sameTypeShape(targetValueType) && targetType.sameTypeShape(viaType)) {
        return forwardPlan
    }
    return DirectTransportPlanner(pluginContext).synthesize(sourceType, targetType)
}

internal fun IrStatementsBuilder<*>.buildTransportLambda(
    inputType: IrType,
    outputType: IrType,
    plan: TransportPlan,
    pluginContext: IrPluginContext,
    lambdaParent: IrDeclarationParent,
): IrFunctionExpression {
    val lambdaType =
        pluginContext.irBuiltIns.functionN(1).typeWith(
            inputType,
            outputType,
        )
    val lambda =
        context.irFactory.buildFun {
            name = Name.special("<unsafe-equiv-lambda>")
            origin = IrDeclarationOrigin.LOCAL_FUNCTION
            visibility = DescriptorVisibilities.LOCAL
            returnType = outputType
        }.apply {
            parent = lambdaParent
        }
    val parameter = lambda.addValueParameter("value", inputType)
    lambda.body =
        DeclarationIrBuilder(pluginContext, lambda.symbol, startOffset, endOffset).irBlockBody {
            +irReturn(buildTransportExpression(plan, irGet(parameter), pluginContext, lambdaParent))
        }
    return IrFunctionExpressionImpl(
        startOffset = startOffset,
        endOffset = endOffset,
        type = lambdaType,
        function = lambda,
        origin = IrStatementOrigin.LAMBDA,
    )
}

internal fun IrClass.validateDeriveViaTransportability(): String? {
    val transported = typeParameters.lastOrNull()?.symbol ?: return "DeriveVia requires a typeclass with a final transported type parameter"
    val classOpaqueParameters = typeParameters.map { it.symbol }.filterNot { it == transported }.toSet()
    val abstractSurface = effectiveAbstractTypeclassSurface()
    for (property in abstractSurface.properties) {
        val getter = property.getter
        val message =
            property
                .substituteInOwnerContext(getter.returnType)
                .transportabilityViolation(transported, classOpaqueParameters)
        if (message != null) {
            return message
        }
    }
    for (member in abstractSurface.functions) {
        val function = member.function
        val methodOpaqueParameters = classOpaqueParameters.toMutableSet()
        methodOpaqueParameters += function.typeParameters.map { it.symbol }
        if (function.typeParameters.any { typeParameter ->
                typeParameter.superTypes.any { superType ->
                    member
                        .substituteInOwnerContext(superType)
                        .mentionsTransportedType(transported, methodOpaqueParameters)
                }
            }
        ) {
            return "DeriveVia does not support method type-parameter bounds that mention the transported type parameter"
        }
        function.parameters.forEach { parameter ->
            when (parameter.kind) {
                IrParameterKind.DispatchReceiver -> Unit
                IrParameterKind.Context ->
                    if (member.substituteInOwnerContext(parameter.type).mentionsTransportedType(transported, methodOpaqueParameters)) {
                        return "DeriveVia does not support context parameters that mention the transported type parameter"
                    }
                else -> {
                    val message =
                        member
                            .substituteInOwnerContext(parameter.type)
                            .transportabilityViolation(transported, methodOpaqueParameters)
                    if (message != null) {
                        return message
                    }
                }
            }
        }
        val returnMessage =
            member
                .substituteInOwnerContext(function.returnType)
                .transportabilityViolation(transported, methodOpaqueParameters)
        if (returnMessage != null) {
            return returnMessage
        }
    }
    return null
}

private fun IrClass.effectiveAbstractTypeclassSurface(): EffectiveAbstractTypeclassSurface {
    val concreteType = symbol.defaultType
    val properties = mutableListOf<EffectiveAbstractProperty>()
    val functions = mutableListOf<EffectiveAbstractFunction>()
    collectEffectiveAbstractTypeclassSurface(
        concreteType = concreteType,
        properties = properties,
        functions = functions,
        seenPropertyKeys = linkedSetOf(),
        seenFunctionKeys = linkedSetOf(),
        coveredPropertyGetters = linkedSetOf(),
        coveredFunctions = linkedSetOf(),
        visited = linkedSetOf(),
    )
    return EffectiveAbstractTypeclassSurface(
        properties = properties,
        functions = functions,
    )
}

private data class EffectiveAbstractTypeclassSurface(
    val properties: List<EffectiveAbstractProperty>,
    val functions: List<EffectiveAbstractFunction>,
)

private fun IrClass.collectEffectiveAbstractTypeclassSurface(
    concreteType: IrSimpleType,
    properties: MutableList<EffectiveAbstractProperty>,
    functions: MutableList<EffectiveAbstractFunction>,
    seenPropertyKeys: MutableSet<String>,
    seenFunctionKeys: MutableSet<String>,
    coveredPropertyGetters: MutableSet<IrSimpleFunctionSymbol>,
    coveredFunctions: MutableSet<IrSimpleFunctionSymbol>,
    visited: MutableSet<String>,
) {
    val visitKey = concreteType.render()
    if (!visited.add(visitKey)) {
        return
    }

    val ownerSubstitution = ownerSubstitution(concreteType)
    declarations.filterIsInstance<IrProperty>().forEach { property ->
        val getter = property.getter ?: return@forEach
        if (getter.symbol in coveredPropertyGetters) {
            return@forEach
        }
        val member = EffectiveAbstractProperty(property, getter, ownerSubstitution)
        if (getter.modality == Modality.ABSTRACT && seenPropertyKeys.add(member.signatureKey())) {
            properties += member
        }
        getter.markCovered(coveredPropertyGetters)
    }

    declarations.filterIsInstance<IrSimpleFunction>().forEach { function ->
        if (function.name.asString() == "<init>" || function.symbol in coveredFunctions) {
            return@forEach
        }
        val member = EffectiveAbstractFunction(function, ownerSubstitution)
        if (function.modality == Modality.ABSTRACT && seenFunctionKeys.add(member.signatureKey())) {
            functions += member
        }
        function.markCovered(coveredFunctions)
    }

    superTypes.forEach { superType ->
        val substitutedSuper = if (ownerSubstitution.isEmpty()) superType else superType.substitute(ownerSubstitution)
        val superSimpleType = substitutedSuper as? IrSimpleType ?: return@forEach
        val superClass = superSimpleType.classOrNull?.owner ?: return@forEach
        superClass.collectEffectiveAbstractTypeclassSurface(
            concreteType = superSimpleType,
            properties = properties,
            functions = functions,
            seenPropertyKeys = seenPropertyKeys,
            seenFunctionKeys = seenFunctionKeys,
            coveredPropertyGetters = coveredPropertyGetters,
            coveredFunctions = coveredFunctions,
            visited = visited,
        )
    }
}

private fun IrClass.ownerSubstitution(concreteType: IrSimpleType): Map<IrTypeParameterSymbol, IrType> =
    typeParameters.mapIndexedNotNull { index, parameter ->
        concreteType.arguments.getOrNull(index)?.argumentTypeOrNull()?.let { argumentType ->
            parameter.symbol to argumentType
        }
    }.toMap()

private fun IrSimpleFunction.markCovered(coveredFunctions: MutableSet<IrSimpleFunctionSymbol>) {
    if (!coveredFunctions.add(symbol)) {
        return
    }
    overriddenSymbols.forEach { overridden ->
        overridden.owner.markCovered(coveredFunctions)
    }
}

private fun EffectiveAbstractProperty.substituteInOwnerContext(type: IrType): IrType =
    if (ownerSubstitution.isEmpty()) {
        type
    } else {
        type.substitute(ownerSubstitution)
    }

private fun EffectiveAbstractFunction.substituteInOwnerContext(type: IrType): IrType =
    if (ownerSubstitution.isEmpty()) {
        type
    } else {
        type.substitute(ownerSubstitution)
    }

private fun EffectiveAbstractProperty.signatureKey(): String =
    buildString {
        append("property:")
        append(property.name.asString())
        append(":")
        append(substituteInOwnerContext(getter.returnType).render())
    }

private fun EffectiveAbstractFunction.signatureKey(): String =
    buildString {
        append("function:")
        append(function.name.asString())
        append(":suspend=")
        append(function.isSuspend)
        append(":typeParams=")
        append(function.typeParameters.size)
        append(":ext=")
        append(function.extensionReceiverParameter?.let { parameter -> substituteInOwnerContext(parameter.type).render() } ?: "-")
        function.parameters
            .filter { parameter -> parameter.kind != IrParameterKind.DispatchReceiver }
            .forEach { parameter ->
                append(":")
                append(parameter.kind.name)
                append("=")
                append(substituteInOwnerContext(parameter.type).render())
            }
        append(":ret=")
        append(substituteInOwnerContext(function.returnType).render())
    }

private fun IrTypeArgument.argumentTypeOrNull(): IrType? =
    when (this) {
        is IrType -> this
        is org.jetbrains.kotlin.ir.types.IrTypeProjection -> type
        else -> null
    }

private fun IrType.transportabilityViolation(
    transported: IrTypeParameterSymbol,
    opaqueParameters: Set<IrTypeParameterSymbol>,
    visiting: MutableSet<String> = linkedSetOf(),
): String? {
    if (!mentionsTransportedType(transported, opaqueParameters)) {
        return null
    }
    if (render().contains("&")) {
        return "DeriveVia does not support definitely-non-null or intersection member types"
    }
    val nullableInner = withoutNullability()
    if (nullableInner != null && nullableInner != this) {
        return nullableInner.transportabilityViolation(transported, opaqueParameters, visiting)
    }
    val functionInfo = functionTypeInfoOrNull()
    if (functionInfo != null) {
        functionInfo.parameterTypes.forEach { parameterType ->
            val message = parameterType.transportabilityViolation(transported, opaqueParameters, visiting)
            if (message != null) {
                return message
            }
        }
        return functionInfo.returnType.transportabilityViolation(transported, opaqueParameters, visiting)
    }
    val simpleType = this as? IrSimpleType ?: return "DeriveVia only supports first-order, function, or structural product/sum member types"
    when (val classifier = simpleType.classifier) {
        transported -> return null
        is IrTypeParameterSymbol -> return null
        else -> Unit
    }
    val klass = simpleType.classOrNull?.owner ?: return "DeriveVia only supports structural class types in transported positions"
    val classId = klass.classId ?: return "DeriveVia only supports named structural class types in transported positions"
    val visitKey = "${classId.asString()}:${simpleType.render()}"
    if (!visiting.add(visitKey)) {
        return "DeriveVia does not support recursive nominal transport shapes"
    }
    try {
        if (klass.isTransparentValueClass()) {
            val field =
                klass.transparentValueField(
                    accessContext = klass.transportAccessContext(),
                    requireConstructorAccess = true,
                ) ?: return "DeriveVia requires transparent total value classes"
            return field.type.transportabilityViolation(transported, opaqueParameters, visiting)
        }
        val productInfo =
            klass.transparentProductInfo(
                concreteType = this,
                accessContext = klass.transportAccessContext(),
                requireConstructorAccess = true,
            )
        if (productInfo != null) {
            productInfo.fields.forEach { field ->
                val message = field.type.transportabilityViolation(transported, opaqueParameters, visiting)
                if (message != null) {
                    return message
                }
            }
            return null
        }
        val sumCases = klass.transparentSealedCases(klass.transportAccessContext())
        if (sumCases != null) {
            sumCases.forEach { case ->
                val message =
                    case.symbol.defaultType.transportabilityViolation(
                        transported = transported,
                        opaqueParameters = opaqueParameters,
                        visiting = visiting,
                    )
                if (message != null) {
                    return message
                }
            }
            return null
        }
        return "DeriveVia does not support opaque or mutable nominal containers in transported positions"
    } finally {
        visiting.remove(visitKey)
    }
}

private fun IrType.mentionsTransportedType(
    transported: IrTypeParameterSymbol,
    opaqueParameters: Set<IrTypeParameterSymbol>,
): Boolean {
    val simpleType = this as? IrSimpleType
    if (simpleType == null) {
        withoutNullability()?.let { inner ->
            if (inner != this) {
                return inner.mentionsTransportedType(transported, opaqueParameters)
            }
        }
        return render().containsStandaloneTypeParameterIdentifier(
            transportedName = transported.owner.name.asString(),
            opaqueNames = opaqueParameters.mapTo(linkedSetOf()) { symbol -> symbol.owner.name.asString() },
        )
    }
    when (val classifier = simpleType.classifier) {
        transported -> return true
        is IrTypeParameterSymbol -> return false
        else -> Unit
    }
    return simpleType.arguments.any { argument ->
        when (argument) {
            is IrType -> argument.mentionsTransportedType(transported, opaqueParameters)
            is org.jetbrains.kotlin.ir.types.IrTypeProjection -> argument.type.mentionsTransportedType(transported, opaqueParameters)
            else -> false
        }
    }
}

private data class FunctionTypeInfo(
    val kind: String,
    val parameterTypes: List<IrType>,
    val returnType: IrType,
)

private fun IrType.functionTypeInfoOrNull(): FunctionTypeInfo? {
    val simpleType = this as? IrSimpleType ?: return null
    val classId = simpleType.classOrNull?.owner?.classId ?: return null
    val fqName = classId.asSingleFqName().asString()
    val kind =
        when {
            fqName.startsWith("kotlin.Function") -> "function"
            fqName.startsWith("kotlin.SuspendFunction") -> "suspend-function"
            else -> return null
        }
    val arguments =
        simpleType.arguments.mapNotNull { argument ->
            when (argument) {
                is IrType -> argument
                is org.jetbrains.kotlin.ir.types.IrTypeProjection -> argument.type
                else -> null
            }
        }
    if (arguments.isEmpty()) {
        return null
    }
    return FunctionTypeInfo(
        kind = kind,
        parameterTypes = arguments.dropLast(1),
        returnType = arguments.last(),
    )
}

private fun IrType.withoutNullability(): IrType? {
    val simpleType = this as? IrSimpleType ?: return null
    return if (!simpleType.isMarkedNullable()) {
        simpleType
    } else {
        simpleType.makeNullable(false)
    }
}

private fun IrType.classTypeArguments(): List<IrType> =
    (this as? IrSimpleType)
        ?.arguments
        ?.mapNotNull { argument ->
            when (argument) {
                is IrType -> argument
                is org.jetbrains.kotlin.ir.types.IrTypeProjection -> argument.type
                else -> null
            }
        } ?: emptyList()

private fun IrSimpleType.makeNullable(nullable: Boolean): IrSimpleType =
    org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl(
        classifier = classifier,
        hasQuestionMark = nullable,
        arguments = arguments,
        annotations = annotations,
    )

private fun IrClass.isTransparentValueClass(): Boolean =
    isValue &&
        typeParameters.isEmpty() &&
        declarations.none { it is IrAnonymousInitializer } &&
        declarations.filterIsInstance<IrConstructor>().all(IrConstructor::isPrimary)

private fun IrClass.transparentValueField(
    accessContext: TransportSyntheticAccessContext,
    requireConstructorAccess: Boolean,
): TransparentField? {
    if (!isTransparentValueClass()) {
        return null
    }
    if (!accessContext.allowsTransportVisibility(visibility.toTransportSyntheticVisibility())) {
        return null
    }
    if (requireConstructorAccess && primaryConstructorOrNullTransportAccessible(accessContext) == null) {
        return null
    }
    val properties =
        declarations.filterIsInstance<IrProperty>().filter { property ->
            property.backingField != null &&
                property.getter != null &&
                property.setter == null &&
                accessContext.allowsTransportVisibility(property.visibility.toTransportSyntheticVisibility()) &&
                accessContext.allowsTransportVisibility(
                    (property.getter ?: return@filter false).visibility.toTransportSyntheticVisibility(),
                )
        }
    if (properties.size != 1) {
        return null
    }
    val property = properties.single()
    val getter = property.getter ?: return null
    return TransparentField(
        property = property,
        getter = getter,
        type = getter.returnType,
        isUnitLike = getter.returnType.classOrNull?.owner?.classId == ClassId.topLevel(FqName("kotlin.Unit")),
    )
}

private fun IrClass.transparentProductInfo(
    concreteType: IrType,
    accessContext: TransportSyntheticAccessContext,
    requireConstructorAccess: Boolean,
): TransparentProductInfo? {
    if (!accessContext.allowsTransportVisibility(visibility.toTransportSyntheticVisibility())) {
        return null
    }
    val concreteSimpleType = concreteType as? IrSimpleType ?: return null
    val typeArgumentSubstitution =
        typeParameters.mapIndexedNotNull { index, parameter ->
            val argument =
                concreteSimpleType.arguments.getOrNull(index)?.let { projection ->
                    when (projection) {
                        is IrType -> projection
                        is org.jetbrains.kotlin.ir.types.IrTypeProjection -> projection.type
                        else -> null
                    }
                } ?: return@mapIndexedNotNull null
            parameter.symbol to argument
        }.toMap()
    if (isObject) {
        return TransparentProductInfo(this, concreteType, null, emptyList(), isObjectLike = true)
    }
    if (!isData || kind == ClassKind.ENUM_CLASS) {
        return null
    }
    if (declarations.any { it is IrAnonymousInitializer }) {
        return null
    }
    val constructors = declarations.filterIsInstance<IrConstructor>()
    val primary = constructors.singleOrNull { it.isPrimary } ?: return null
    if (constructors.size != 1) {
        return null
    }
    if (requireConstructorAccess &&
        !accessContext.allowsTransportVisibility(primary.visibility.toTransportSyntheticVisibility())
    ) {
        return null
    }
    val storedProperties =
        declarations.filterIsInstance<IrProperty>().filter { property ->
            property.backingField != null
        }
    if (storedProperties.any(IrProperty::isDelegated)) {
        return null
    }
    if (storedProperties.any { property -> property.setter != null }) {
        return null
    }
    if (storedProperties.any { property ->
            !accessContext.allowsTransportVisibility(property.visibility.toTransportSyntheticVisibility()) ||
                !accessContext.allowsTransportVisibility(
                    (property.getter?.visibility ?: return null).toTransportSyntheticVisibility(),
                )
        }
    ) {
        return null
    }
    val properties =
        storedProperties.filter { property ->
            property.getter != null
        }
    if (properties.size != primary.valueParameters.size) {
        return null
    }
    val primaryParameterNames = primary.valueParameters.map { it.name }.toSet()
    if (properties.any { property -> property.name !in primaryParameterNames }) {
        return null
    }
    val extraFields =
        declarations.filterIsInstance<org.jetbrains.kotlin.ir.declarations.IrField>().filterNot { field ->
            storedProperties.any { property -> property.backingField?.symbol == field.symbol }
        }
    if (extraFields.isNotEmpty()) {
        return null
    }
    val fields =
        primary.valueParameters.map { parameter ->
            val property =
                properties.singleOrNull { property -> property.name == parameter.name }
                    ?: return null
            val getter = property.getter ?: return null
            TransparentField(
                property = property,
                getter = getter,
                type = getter.returnType.substitute(typeArgumentSubstitution),
                isUnitLike =
                    getter.returnType
                        .substitute(typeArgumentSubstitution)
                        .classOrNull
                        ?.owner
                        ?.classId == ClassId.topLevel(FqName("kotlin.Unit")),
            )
        }
    return TransparentProductInfo(
        klass = this,
        type = concreteType,
        constructor = primary,
        fields = fields,
        isObjectLike = false,
    )
}

private fun IrClass.transparentSealedCases(
    accessContext: TransportSyntheticAccessContext,
): List<IrClass>? {
    if (modality != Modality.SEALED || typeParameters.isNotEmpty()) {
        return null
    }
    if (!accessContext.allowsTransportVisibility(visibility.toTransportSyntheticVisibility())) {
        return null
    }
    val subclasses = sealedSubclasses.mapNotNull { symbol -> runCatching { symbol.owner }.getOrNull() }
    if (subclasses.isEmpty()) {
        return null
    }
    return subclasses.takeIf { cases ->
        cases.all { case ->
            accessContext.allowsTransportVisibility(case.visibility.toTransportSyntheticVisibility()) &&
                (case.isObject ||
                (case.isData && case.declarations.none { it is IrAnonymousInitializer } && case.typeParameters.isEmpty())
                )
        }
    }
}

private fun IrClass.primaryConstructorOrNullLocal(): IrConstructor? =
    declarations.filterIsInstance<IrConstructor>().singleOrNull { constructor -> constructor.isPrimary }
        ?: declarations.filterIsInstance<IrConstructor>().singleOrNull()

private fun IrClass.primaryConstructorOrNullTransportAccessible(
    accessContext: TransportSyntheticAccessContext,
): IrConstructor? =
    primaryConstructorOrNullLocal()?.takeIf { constructor ->
        accessContext.allowsTransportVisibility(constructor.visibility.toTransportSyntheticVisibility())
    }

private fun IrClass.transportAccessContext(): TransportSyntheticAccessContext =
    if (origin == IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB) {
        TransportSyntheticAccessContext.DEPENDENCY_BINARY
    } else {
        TransportSyntheticAccessContext.SAME_MODULE_SOURCE
    }

private fun IrFunction.valueArgumentIndexOrFallback(
    parameter: IrValueParameter,
    fallbackIndex: Int,
): Int =
    valueParameters.indexOfFirst { candidate -> candidate.symbol == parameter.symbol }
        .takeIf { index -> index >= 0 } ?: fallbackIndex
