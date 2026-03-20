@file:OptIn(
    org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi::class,
    org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class,
)

package one.wabbit.typeclass.plugin

import one.wabbit.typeclass.plugin.model.InstanceRule
import one.wabbit.typeclass.plugin.model.ResolutionPlan
import one.wabbit.typeclass.plugin.model.ResolutionSearchResult
import one.wabbit.typeclass.plugin.model.TcType
import one.wabbit.typeclass.plugin.model.TcTypeParameter
import one.wabbit.typeclass.plugin.model.TypeclassResolutionPlanner
import one.wabbit.typeclass.plugin.model.containsStarProjection
import one.wabbit.typeclass.plugin.model.isExactTypeIdentity
import one.wabbit.typeclass.plugin.model.isProvablyNotNullable
import one.wabbit.typeclass.plugin.model.isProvablyNullable
import one.wabbit.typeclass.plugin.model.normalizedKey
import one.wabbit.typeclass.plugin.model.render
import one.wabbit.typeclass.plugin.model.referencedVariableIds
import one.wabbit.typeclass.plugin.model.substituteType
import one.wabbit.typeclass.plugin.model.toCanonicalTypeIdName
import one.wabbit.typeclass.plugin.model.unifyTypes
import org.jetbrains.kotlin.backend.jvm.JvmIrTypeSystemContext
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.IrBlockBodyBuilder
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.IrStatementsBuilder
import org.jetbrains.kotlin.ir.builders.irAs
import org.jetbrains.kotlin.ir.builders.irBoolean
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irIfThenElse
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irIs
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irSet
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.builders.irVararg
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationBase
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrComposite
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrSpreadElement
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetEnumValueImpl
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrStarProjection
import org.jetbrains.kotlin.ir.types.IrTypeArgument
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.types.impl.IrStarProjectionImpl
import org.jetbrains.kotlin.ir.types.impl.makeTypeProjection
import org.jetbrains.kotlin.ir.util.callableId
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.ir.util.isSubtypeOf
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.ir.util.substitute
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance

internal class TypeclassIrGenerationExtension(
    private val sharedState: TypeclassPluginSharedState,
) : IrGenerationExtension {
    override fun generate(
        moduleFragment: IrModuleFragment,
        pluginContext: IrPluginContext,
    ) {
        val ruleIndex = IrRuleIndex.build(moduleFragment, pluginContext, sharedState)
        val transformer = TypeclassIrCallTransformer(pluginContext, ruleIndex)
        moduleFragment.transformChildrenVoid(transformer)
    }
}

private class TypeclassIrCallTransformer(
    private val pluginContext: IrPluginContext,
    private val ruleIndex: IrRuleIndex,
) : IrElementTransformerVoid() {
    private val configuration: TypeclassConfiguration = ruleIndex.configuration
    private val deriverReturnValidationCache: MutableMap<IrSimpleFunction, Boolean> = linkedMapOf()
    private val declarationStack = ArrayDeque<IrDeclarationBase>()
    private val functionStack = ArrayDeque<IrFunction>()

    override fun visitDeclaration(declaration: IrDeclarationBase): IrStatement {
        if (declaration is IrFunction) {
            return super.visitDeclaration(declaration)
        }
        declarationStack.addLast(declaration)
        val transformed = super.visitDeclaration(declaration)
        declarationStack.removeLast()
        return transformed
    }

    override fun visitFunction(declaration: IrFunction): IrStatement {
        declarationStack.addLast(declaration)
        functionStack.addLast(declaration)
        val transformed = super.visitFunction(declaration)
        functionStack.removeLast()
        declarationStack.removeLast()
        return transformed
    }

    override fun visitCall(expression: IrCall): IrExpression {
        val rewritten = super.visitCall(expression) as IrCall
        val callee = rewritten.symbol.owner
        val original =
            when {
                callee.requiresSyntheticTypeclassResolution(rewritten, configuration) -> callee
                else ->
                    ruleIndex.originalForWrapperLikeFunction(callee)?.takeIf { candidate ->
                        candidate.requiresSyntheticTypeclassResolution(rewritten, configuration)
                    }
            }
        if (original == null) {
            return rewritten
        }

        val currentDeclaration = declarationStack.lastOrNull()
            ?: error("Typeclass call ${callee.safeCallableIdentity()} is not enclosed by a declaration")
        val enclosingFunction = functionStack.lastOrNull()
        return buildResolvedTypeclassCall(
            call = rewritten,
            original = original,
            currentDeclaration = currentDeclaration,
            currentFunction = enclosingFunction,
            enclosingFunctions = currentDeclaration.enclosingFunctions(),
        )
    }

    private fun buildResolvedTypeclassCall(
        call: IrCall,
        original: IrSimpleFunction,
        currentDeclaration: IrDeclarationBase,
        currentFunction: IrFunction?,
        enclosingFunctions: List<IrFunction>,
    ): IrExpression =
        DeclarationIrBuilder(pluginContext, currentDeclaration.symbol, call.startOffset, call.endOffset).run {
            val visibleTypeParameters = visibleTypeParameters(currentDeclaration, enclosingFunctions)
            val localContexts = collectLocalContexts(enclosingFunctions, visibleTypeParameters, configuration)
            val normalizedCall = call.normalizedArgumentsForTypeclassRewrite(original)
            val inferredOriginalTypeArguments =
                try {
                    inferOriginalTypeArguments(
                        original = original,
                        normalizedCall = normalizedCall,
                        currentCallTypeArgumentsByName =
                            original.typeParameters.mapIndexedNotNull { index, typeParameter ->
                                call.getTypeArgument(index)?.let { irType ->
                                    typeParameter.name.asString() to irType
                                }
                            }.toMap(),
                        visibleTypeParameters = visibleTypeParameters,
                        localContexts = localContexts,
                        pluginContext = pluginContext,
                        configuration = configuration,
                    )
                } catch (error: TypeArgumentInferenceFailure) {
                    reportTypeclassResolutionFailure(error.message ?: "Type argument inference failed for ${original.renderIdentity()}")
                    return call
                }
            val explicitArguments =
                extractExplicitArguments(
                    original = original,
                    normalizedValueArguments = normalizedCall.valueArguments,
                    substitutionBySymbol = inferredOriginalTypeArguments.substitutionBySymbol,
                    visibleTypeParameters = visibleTypeParameters,
                    configuration = configuration,
                )
            buildOriginalCall(
                original = original,
                currentDeclaration = currentDeclaration,
                currentFunction = currentFunction,
                localContexts = localContexts,
                visibleTypeParameters = visibleTypeParameters,
                inferredOriginalTypeArguments = inferredOriginalTypeArguments,
                fallbackCall = call,
                dispatchReceiver = normalizedCall.dispatchReceiver,
                extensionReceiver = normalizedCall.extensionReceiver,
                explicitArguments = explicitArguments,
            )
        }

    private fun reportTypeclassResolutionFailure(
        message: String,
        diagnosticId: String? = null,
        location: CompilerMessageSourceLocation? = null,
    ) {
        pluginContext.reportTypeclassError(message, diagnosticId, location)
    }

    private fun ResolutionPlan.renderForDiagnostic(): String =
        when (this) {
            is ResolutionPlan.LocalContext -> "local-context[$index]"
            is ResolutionPlan.ApplyRule -> ruleId
            is ResolutionPlan.RecursiveReference -> "recursive[${providedType.render()}]"
        }

    private fun IrBuilderWithScope.buildOriginalCall(
        original: IrSimpleFunction,
        currentDeclaration: IrDeclarationBase,
        currentFunction: IrFunction?,
        localContexts: List<LocalTypeclassContext>,
        visibleTypeParameters: VisibleTypeParameters,
        inferredOriginalTypeArguments: InferredOriginalTypeArguments,
        fallbackCall: IrCall,
        dispatchReceiver: IrExpression?,
        extensionReceiver: IrExpression?,
        explicitArguments: ExtractedExplicitArguments,
    ): IrExpression {
        val typeArgumentMap = inferredOriginalTypeArguments.substitutionBySymbol
        val currentScopeIdentity = currentFunction?.renderIdentity() ?: currentDeclaration.renderIdentity()
        val planner =
            TypeclassResolutionPlanner(
                ruleProvider = { goal: TcType ->
                    ruleIndex.rulesForGoal(goal, visibleTypeParameters::canMaterializeRuntimeType)
                },
            )

        val originalCall = irCall(original.symbol)
        originalCall.dispatchReceiver = dispatchReceiver
        originalCall.extensionReceiver = extensionReceiver

        inferredOriginalTypeArguments.callTypeArguments.forEachIndexed { index, irType ->
            originalCall.putTypeArgument(index, irType)
        }

        val localContextTypes = localContexts.map(LocalTypeclassContext::providedType)
        val explicitIterator = explicitArguments.nonTypeclassArguments.iterator()

        original.regularAndContextParameters().forEachIndexed { parameterIndex, parameter ->
            val valueArgumentIndex = original.valueArgumentIndex(parameter, parameterIndex)
            val substitutedGoalType = parameter.type.substitute(typeArgumentMap)
            if (parameter.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Context && substitutedGoalType.isTypeclassType(configuration)) {
                explicitArguments.preservedTypeclassArguments[valueArgumentIndex]?.let { preservedExpression ->
                    originalCall.putValueArgument(valueArgumentIndex, irAs(preservedExpression, substitutedGoalType))
                    return@forEachIndexed
                }
                val goal =
                    irTypeToModel(
                        type = substitutedGoalType,
                        typeParameterBySymbol = visibleTypeParameters.bySymbol,
                    ) ?: error("Unsupported typeclass goal type ${substitutedGoalType.render()} in ${original.callableId}")
                val result = planner.resolve(goal, localContextTypes)
                val expression =
                    when (result) {
                        is ResolutionSearchResult.Success ->
                            irBlock(resultType = substitutedGoalType) {
                                +buildExpressionForPlan(
                                    plan = result.plan,
                                    currentDeclaration = currentDeclaration,
                                    currentFunction = currentFunction,
                                    localContexts = localContexts,
                                    visibleTypeParameters = visibleTypeParameters,
                                    recursiveDerivedResolvers = linkedMapOf(),
                                    diagnosticLocation = currentDeclaration.compilerMessageLocation(fallbackCall),
                                )
                            }

                        is ResolutionSearchResult.Ambiguous -> {
                            val message =
                                "Ambiguous typeclass instance for ${goal.render()} in $currentScopeIdentity." +
                                    " Candidates: ${result.matchingPlans.joinToString { it.renderForDiagnostic() }}"
                            reportTypeclassResolutionFailure(
                                message = message,
                                diagnosticId = TypeclassDiagnosticIds.AMBIGUOUS_INSTANCE,
                                location = currentDeclaration.compilerMessageLocation(fallbackCall),
                            )
                            return fallbackCall
                        }

                        is ResolutionSearchResult.Missing -> {
                            val message =
                                "Missing typeclass instance for ${goal.render()} in $currentScopeIdentity"
                            reportTypeclassResolutionFailure(
                                message = message,
                                diagnosticId = TypeclassDiagnosticIds.NO_CONTEXT_ARGUMENT,
                                location = currentDeclaration.compilerMessageLocation(fallbackCall),
                            )
                            return fallbackCall
                        }

                        is ResolutionSearchResult.Recursive -> {
                            val message = "Recursive typeclass resolution for ${goal.render()} in $currentScopeIdentity"
                            reportTypeclassResolutionFailure(
                                message = message,
                                diagnosticId = TypeclassDiagnosticIds.NO_CONTEXT_ARGUMENT,
                                location = currentDeclaration.compilerMessageLocation(fallbackCall),
                            )
                            return fallbackCall
                        }
                    }
                originalCall.putValueArgument(valueArgumentIndex, expression)
            } else {
                val nextArgument = explicitIterator.nextOrNull()
                    ?: error("Not enough explicit arguments when rewriting ${original.callableId}")
                when (nextArgument) {
                    ExplicitArgument.Omitted -> Unit
                    is ExplicitArgument.PassThrough -> originalCall.putValueArgument(valueArgumentIndex, nextArgument.expression)
                }
            }
        }

        return originalCall
    }

    private fun IrStatementsBuilder<*>.buildBuiltinKClassExpression(
        plan: ResolutionPlan.ApplyRule,
        visibleTypeParameters: VisibleTypeParameters,
        diagnosticLocation: CompilerMessageSourceLocation?,
    ): IrExpression {
        val expressionType = modelToIrType(plan.providedType, visibleTypeParameters, pluginContext)
        val targetModel = plan.appliedTypeArguments.singleOrNull()
            ?: return invalidBuiltinKClassExpression(
                expressionType = expressionType,
                message = "Builtin KClass typeclass resolution requires exactly one target type argument.",
                diagnosticLocation = diagnosticLocation,
            )
        val targetType = modelToIrType(targetModel, visibleTypeParameters, pluginContext)
        if (targetType.isNullable()) {
            return invalidBuiltinKClassExpression(
                expressionType = expressionType,
                message = "Builtin KClass typeclass resolution requires a non-null concrete runtime type, but found ${targetType.render()}.",
                diagnosticLocation = diagnosticLocation,
            )
        }
        val targetSimpleType = targetType as? IrSimpleType
            ?: return invalidBuiltinKClassExpression(
                expressionType = expressionType,
                message = "Builtin KClass typeclass resolution requires a concrete runtime type, but found ${targetType.render()}.",
                diagnosticLocation = diagnosticLocation,
            )
        val classifier = targetSimpleType.classifier
        if (classifier is org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol && !classifier.owner.isReified) {
            return invalidBuiltinKClassExpression(
                expressionType = expressionType,
                message = "Builtin KClass typeclass resolution requires a concrete runtime type or a reified type parameter, but found ${targetType.render()}.",
                diagnosticLocation = diagnosticLocation,
            )
        }
        return org.jetbrains.kotlin.ir.expressions.impl.IrClassReferenceImpl(
            startOffset,
            endOffset,
            expressionType,
            classifier,
            targetType,
        )
    }

    private fun IrStatementsBuilder<*>.invalidBuiltinKClassExpression(
        expressionType: IrType,
        message: String,
        diagnosticLocation: CompilerMessageSourceLocation?,
    ): IrExpression {
        reportTypeclassResolutionFailure(
            message = message,
            diagnosticId = TypeclassDiagnosticIds.INVALID_BUILTIN_EVIDENCE,
            location = diagnosticLocation,
        )
        return irAs(irNull(), expressionType)
    }

    private fun IrStatementsBuilder<*>.buildBuiltinKSerializerExpression(
        plan: ResolutionPlan.ApplyRule,
        visibleTypeParameters: VisibleTypeParameters,
        diagnosticLocation: CompilerMessageSourceLocation?,
    ): IrExpression {
        val expressionType = modelToIrType(plan.providedType, visibleTypeParameters, pluginContext)
        val targetModel = plan.appliedTypeArguments.singleOrNull()
            ?: return invalidBuiltinKSerializerExpression(
                expressionType = expressionType,
                message = "Builtin KSerializer typeclass resolution requires exactly one target type argument.",
                diagnosticLocation = diagnosticLocation,
            )
        val targetType = modelToIrType(targetModel, visibleTypeParameters, pluginContext)
        val targetSimpleType = targetType as? IrSimpleType
            ?: return invalidBuiltinKSerializerExpression(
                expressionType = expressionType,
                message = "Builtin KSerializer typeclass resolution requires a concrete or reified target type, but found ${targetType.render()}.",
                diagnosticLocation = diagnosticLocation,
            )
        val classifier = targetSimpleType.classifier
        if (classifier is org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol && !classifier.owner.isReified) {
            return invalidBuiltinKSerializerExpression(
                expressionType = expressionType,
                message = "Builtin KSerializer typeclass resolution requires a concrete target type or a reified type parameter, but found ${targetType.render()}.",
                diagnosticLocation = diagnosticLocation,
            )
        }
        val serializerFunction =
            pluginContext.referenceFunctions(CallableId(FqName("kotlinx.serialization"), Name.identifier("serializer")))
                .map { it.owner }
                .singleOrNull { function ->
                    function.dispatchReceiverParameter == null &&
                        function.extensionReceiverParameter == null &&
                        function.typeParameters.size == 1 &&
                        function.valueParameters.isEmpty()
                }
                ?: return invalidBuiltinKSerializerExpression(
                    expressionType = expressionType,
                    message = "Could not resolve kotlinx.serialization.serializer<T>() on the compilation classpath.",
                    diagnosticLocation = diagnosticLocation,
                )
        return irCall(serializerFunction.symbol, expressionType).apply {
            putTypeArgument(0, targetType)
        }
    }

    private fun IrStatementsBuilder<*>.invalidBuiltinKSerializerExpression(
        expressionType: IrType,
        message: String,
        diagnosticLocation: CompilerMessageSourceLocation?,
    ): IrExpression {
        reportTypeclassResolutionFailure(
            message = message,
            diagnosticId = TypeclassDiagnosticIds.INVALID_BUILTIN_EVIDENCE,
            location = diagnosticLocation,
        )
        return irAs(irNull(), expressionType)
    }

    private fun IrStatementsBuilder<*>.buildBuiltinProofSingletonExpression(
        expressionType: IrType,
        proofClassId: ClassId,
        diagnosticLocation: CompilerMessageSourceLocation?,
    ): IrExpression {
        val proofClass = pluginContext.referenceClass(proofClassId)?.owner
            ?: return invalidBuiltinProofExpression(
                expressionType = expressionType,
                message = "Could not resolve builtin proof carrier $proofClassId on the compilation classpath.",
                diagnosticLocation = diagnosticLocation,
            )
        return irAs(irGetObject(proofClass.symbol), expressionType)
    }

    private fun IrStatementsBuilder<*>.buildBuiltinNotSameExpression(
        plan: ResolutionPlan.ApplyRule,
        visibleTypeParameters: VisibleTypeParameters,
        diagnosticLocation: CompilerMessageSourceLocation?,
    ): IrExpression {
        val expressionType = modelToIrType(plan.providedType, visibleTypeParameters, pluginContext)
        val left = plan.appliedTypeArguments.getOrNull(0)
            ?: return invalidBuiltinProofExpression(expressionType, "NotSame proof requires two type arguments.", diagnosticLocation)
        val right = plan.appliedTypeArguments.getOrNull(1)
            ?: return invalidBuiltinProofExpression(expressionType, "NotSame proof requires two type arguments.", diagnosticLocation)
        if (!canProveNotSame(left, right)) {
            return invalidBuiltinProofExpression(
                expressionType = expressionType,
                message = "NotSame proof could not prove ${left.render()} and ${right.render()} differ.",
                diagnosticLocation = diagnosticLocation,
            )
        }
        return buildBuiltinProofSingletonExpression(expressionType, NOT_SAME_PROOF_CLASS_ID, diagnosticLocation)
    }

    private fun IrStatementsBuilder<*>.buildBuiltinSubtypeExpression(
        plan: ResolutionPlan.ApplyRule,
        visibleTypeParameters: VisibleTypeParameters,
        diagnosticLocation: CompilerMessageSourceLocation?,
    ): IrExpression {
        val expressionType = modelToIrType(plan.providedType, visibleTypeParameters, pluginContext)
        val subModel = plan.appliedTypeArguments.getOrNull(0)
            ?: return invalidBuiltinProofExpression(expressionType, "Subtype proof requires two type arguments.", diagnosticLocation)
        val superModel = plan.appliedTypeArguments.getOrNull(1)
            ?: return invalidBuiltinProofExpression(expressionType, "Subtype proof requires two type arguments.", diagnosticLocation)
        val subType = modelToIrType(subModel, visibleTypeParameters, pluginContext)
        val superType = modelToIrType(superModel, visibleTypeParameters, pluginContext)
        if (!canProveSubtype(subType, superType, pluginContext)) {
            return invalidBuiltinProofExpression(
                expressionType = expressionType,
                message = "Subtype proof could not prove ${subModel.render()} is a subtype of ${superModel.render()}.",
                diagnosticLocation = diagnosticLocation,
            )
        }
        return buildBuiltinProofSingletonExpression(expressionType, SUBTYPE_PROOF_CLASS_ID, diagnosticLocation)
    }

    private fun IrStatementsBuilder<*>.buildBuiltinStrictSubtypeExpression(
        plan: ResolutionPlan.ApplyRule,
        visibleTypeParameters: VisibleTypeParameters,
        diagnosticLocation: CompilerMessageSourceLocation?,
    ): IrExpression {
        val expressionType = modelToIrType(plan.providedType, visibleTypeParameters, pluginContext)
        val subModel = plan.appliedTypeArguments.getOrNull(0)
            ?: return invalidBuiltinProofExpression(expressionType, "StrictSubtype proof requires two type arguments.", diagnosticLocation)
        val superModel = plan.appliedTypeArguments.getOrNull(1)
            ?: return invalidBuiltinProofExpression(expressionType, "StrictSubtype proof requires two type arguments.", diagnosticLocation)
        val subType = modelToIrType(subModel, visibleTypeParameters, pluginContext)
        val superType = modelToIrType(superModel, visibleTypeParameters, pluginContext)
        if (!canProveSubtype(subType, superType, pluginContext) || !canProveNotSame(subModel, superModel)) {
            return invalidBuiltinProofExpression(
                expressionType = expressionType,
                message =
                    "StrictSubtype proof could not prove ${subModel.render()} is a proper subtype of ${superModel.render()}.",
                diagnosticLocation = diagnosticLocation,
            )
        }
        return buildBuiltinProofSingletonExpression(expressionType, STRICT_SUBTYPE_PROOF_CLASS_ID, diagnosticLocation)
    }

    private fun IrStatementsBuilder<*>.buildBuiltinNullableExpression(
        plan: ResolutionPlan.ApplyRule,
        visibleTypeParameters: VisibleTypeParameters,
        diagnosticLocation: CompilerMessageSourceLocation?,
    ): IrExpression {
        val expressionType = modelToIrType(plan.providedType, visibleTypeParameters, pluginContext)
        val targetModel = plan.appliedTypeArguments.singleOrNull()
            ?: return invalidBuiltinProofExpression(expressionType, "Nullable proof requires exactly one type argument.", diagnosticLocation)
        val targetType = modelToIrType(targetModel, visibleTypeParameters, pluginContext)
        if (!canProveNullable(targetType, pluginContext)) {
            return invalidBuiltinProofExpression(
                expressionType = expressionType,
                message = "Nullable proof could not prove null is a valid inhabitant of ${targetModel.render()}.",
                diagnosticLocation = diagnosticLocation,
            )
        }
        return buildBuiltinProofSingletonExpression(expressionType, NULLABLE_PROOF_CLASS_ID, diagnosticLocation)
    }

    private fun IrStatementsBuilder<*>.buildBuiltinNotNullableExpression(
        plan: ResolutionPlan.ApplyRule,
        visibleTypeParameters: VisibleTypeParameters,
        diagnosticLocation: CompilerMessageSourceLocation?,
    ): IrExpression {
        val expressionType = modelToIrType(plan.providedType, visibleTypeParameters, pluginContext)
        val targetModel = plan.appliedTypeArguments.singleOrNull()
            ?: return invalidBuiltinProofExpression(expressionType, "NotNullable proof requires exactly one type argument.", diagnosticLocation)
        val targetType = modelToIrType(targetModel, visibleTypeParameters, pluginContext)
        if (!canProveNotNullable(targetType, pluginContext)) {
            return invalidBuiltinProofExpression(
                expressionType = expressionType,
                message = "NotNullable proof could not prove ${targetModel.render()} excludes null.",
                diagnosticLocation = diagnosticLocation,
            )
        }
        return buildBuiltinProofSingletonExpression(expressionType, NOT_NULLABLE_PROOF_CLASS_ID, diagnosticLocation)
    }

    private fun IrStatementsBuilder<*>.buildBuiltinIsTypeclassInstanceExpression(
        plan: ResolutionPlan.ApplyRule,
        visibleTypeParameters: VisibleTypeParameters,
        diagnosticLocation: CompilerMessageSourceLocation?,
    ): IrExpression {
        val expressionType = modelToIrType(plan.providedType, visibleTypeParameters, pluginContext)
        val targetModel = plan.appliedTypeArguments.singleOrNull()
            ?: return invalidBuiltinProofExpression(
                expressionType,
                "IsTypeclassInstance proof requires exactly one type argument.",
                diagnosticLocation,
            )
        val targetType = modelToIrType(targetModel, visibleTypeParameters, pluginContext)
        if (!targetType.isTypeclassType(configuration)) {
            return invalidBuiltinProofExpression(
                expressionType = expressionType,
                message = "IsTypeclassInstance proof requires a typeclass application, but found ${targetModel.render()}.",
                diagnosticLocation = diagnosticLocation,
            )
        }
        return buildBuiltinProofSingletonExpression(expressionType, IS_TYPECLASS_INSTANCE_PROOF_CLASS_ID, diagnosticLocation)
    }

    private fun IrStatementsBuilder<*>.buildBuiltinKnownTypeExpression(
        plan: ResolutionPlan.ApplyRule,
        visibleTypeParameters: VisibleTypeParameters,
        diagnosticLocation: CompilerMessageSourceLocation?,
    ): IrExpression {
        val expressionType = modelToIrType(plan.providedType, visibleTypeParameters, pluginContext)
        val targetModel = plan.appliedTypeArguments.singleOrNull()
            ?: return invalidBuiltinProofExpression(expressionType, "KnownType proof requires exactly one type argument.", diagnosticLocation)
        val targetType = modelToIrType(targetModel, visibleTypeParameters, pluginContext)
        val targetSimpleType = targetType as? IrSimpleType
            ?: return invalidBuiltinProofExpression(
                expressionType = expressionType,
                message = "KnownType proof requires an exact known KType, but found ${targetModel.render()}.",
                diagnosticLocation = diagnosticLocation,
            )
        val classifier = targetSimpleType.classifier
        if (classifier is org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol && !classifier.owner.isReified) {
            return invalidBuiltinProofExpression(
                expressionType = expressionType,
                message = "KnownType proof requires an exact known KType, but found ${targetModel.render()}.",
                diagnosticLocation = diagnosticLocation,
            )
        }
        val typeOfFunction =
            pluginContext.referenceFunctions(CallableId(FqName("kotlin.reflect"), Name.identifier("typeOf")))
                .map { it.owner }
                .singleOrNull { function ->
                    function.typeParameters.size == 1 &&
                        function.valueParameters.isEmpty()
                }
                ?: return invalidBuiltinProofExpression(
                    expressionType = expressionType,
                    message = "Could not resolve kotlin.reflect.typeOf<T>() on the compilation classpath.",
                    diagnosticLocation = diagnosticLocation,
                )
        val knownTypeFactory =
            pluginContext.referenceFunctions(KNOWN_TYPE_FACTORY_CALLABLE_ID)
                .map { it.owner }
                .singleOrNull { function -> function.valueParameters.size == 1 }
                ?: return invalidBuiltinProofExpression(
                    expressionType = expressionType,
                    message = "Could not resolve one.wabbit.typeclass.knownType(...) on the compilation classpath.",
                    diagnosticLocation = diagnosticLocation,
                )
        val typeOfCall =
            irCall(typeOfFunction.symbol).apply {
                putTypeArgument(0, targetType)
            }
        val knownTypeCall =
            irCall(knownTypeFactory.symbol).apply {
                putValueArgument(0, typeOfCall)
            }
        return irAs(knownTypeCall, expressionType)
    }

    private fun IrStatementsBuilder<*>.buildBuiltinTypeIdExpression(
        plan: ResolutionPlan.ApplyRule,
        visibleTypeParameters: VisibleTypeParameters,
        diagnosticLocation: CompilerMessageSourceLocation?,
    ): IrExpression {
        val expressionType = modelToIrType(plan.providedType, visibleTypeParameters, pluginContext)
        val targetModel = plan.appliedTypeArguments.singleOrNull()
            ?: return invalidBuiltinProofExpression(expressionType, "TypeId proof requires exactly one type argument.", diagnosticLocation)
        val targetType = modelToIrType(targetModel, visibleTypeParameters, pluginContext)
        val targetSimpleType = targetType as? IrSimpleType
            ?: return invalidBuiltinProofExpression(
                expressionType = expressionType,
                message = "TypeId proof requires an exact semantic type, but found ${targetModel.render()}.",
                diagnosticLocation = diagnosticLocation,
            )
        if (targetModel.isExactTypeIdentity()) {
            val stringTypeIdFactory =
                pluginContext.referenceFunctions(TYPE_ID_FACTORY_CALLABLE_ID)
                    .map { it.owner }
                    .singleOrNull { function ->
                        function.valueParameters.singleOrNull()?.type?.classOrNull?.owner?.classId == STRING_CLASS_ID
                    }
                    ?: return invalidBuiltinProofExpression(
                        expressionType = expressionType,
                        message = "Could not resolve one.wabbit.typeclass.typeId(String) on the compilation classpath.",
                        diagnosticLocation = diagnosticLocation,
                    )
            val typeIdCall =
                irCall(stringTypeIdFactory.symbol).apply {
                    putValueArgument(0, irString(targetModel.toCanonicalTypeIdName()))
                }
            return irAs(typeIdCall, expressionType)
        }

        if (!canMaterializeTypeIdViaTypeOf(targetSimpleType)) {
            return invalidBuiltinProofExpression(
                expressionType = expressionType,
                message = "TypeId proof requires an exact semantic type, but found ${targetModel.render()}.",
                diagnosticLocation = diagnosticLocation,
            )
        }

        val typeOfFunction =
            pluginContext.referenceFunctions(CallableId(FqName("kotlin.reflect"), Name.identifier("typeOf")))
                .map { it.owner }
                .singleOrNull { function ->
                    function.typeParameters.size == 1 &&
                        function.valueParameters.isEmpty()
                }
                ?: return invalidBuiltinProofExpression(
                    expressionType = expressionType,
                    message = "Could not resolve kotlin.reflect.typeOf<T>() on the compilation classpath.",
                    diagnosticLocation = diagnosticLocation,
                )
        val kTypeIdFactory =
            pluginContext.referenceFunctions(TYPE_ID_FACTORY_CALLABLE_ID)
                .map { it.owner }
                .singleOrNull { function ->
                    function.valueParameters.singleOrNull()?.type?.classOrNull?.owner?.classId == KTYPE_CLASS_ID
                }
                ?: return invalidBuiltinProofExpression(
                    expressionType = expressionType,
                    message = "Could not resolve one.wabbit.typeclass.typeId(KType) on the compilation classpath.",
                    diagnosticLocation = diagnosticLocation,
                )
        val typeOfCall =
            irCall(typeOfFunction.symbol).apply {
                putTypeArgument(0, targetType)
            }
        val typeIdCall =
            irCall(kTypeIdFactory.symbol).apply {
                putValueArgument(0, typeOfCall)
            }
        return irAs(typeIdCall, expressionType)
    }

    private fun IrStatementsBuilder<*>.buildBuiltinSameTypeConstructorExpression(
        plan: ResolutionPlan.ApplyRule,
        visibleTypeParameters: VisibleTypeParameters,
        diagnosticLocation: CompilerMessageSourceLocation?,
    ): IrExpression {
        val expressionType = modelToIrType(plan.providedType, visibleTypeParameters, pluginContext)
        val left = plan.appliedTypeArguments.getOrNull(0)
            ?: return invalidBuiltinProofExpression(expressionType, "SameTypeConstructor proof requires two type arguments.", diagnosticLocation)
        val right = plan.appliedTypeArguments.getOrNull(1)
            ?: return invalidBuiltinProofExpression(expressionType, "SameTypeConstructor proof requires two type arguments.", diagnosticLocation)
        val valid =
            left is TcType.Constructor &&
                right is TcType.Constructor &&
                left.classifierId == right.classifierId
        if (!valid) {
            return invalidBuiltinProofExpression(
                expressionType = expressionType,
                message =
                    "SameTypeConstructor proof requires matching outer type constructors, but found ${left.render()} and ${right.render()}.",
                diagnosticLocation = diagnosticLocation,
            )
        }
        return buildBuiltinProofSingletonExpression(expressionType, SAME_TYPE_CONSTRUCTOR_PROOF_CLASS_ID, diagnosticLocation)
    }

    private fun IrStatementsBuilder<*>.invalidBuiltinProofExpression(
        expressionType: IrType,
        message: String,
        diagnosticLocation: CompilerMessageSourceLocation?,
    ): IrExpression {
        reportTypeclassResolutionFailure(
            message = message,
            diagnosticId = TypeclassDiagnosticIds.INVALID_BUILTIN_EVIDENCE,
            location = diagnosticLocation,
        )
        return irAs(irNull(), expressionType)
    }

    private fun IrStatementsBuilder<*>.buildExpressionForPlan(
        plan: ResolutionPlan,
        currentDeclaration: IrDeclarationBase,
        currentFunction: IrFunction?,
        localContexts: List<LocalTypeclassContext>,
        visibleTypeParameters: VisibleTypeParameters,
        recursiveDerivedResolvers: MutableMap<String, RecursiveDerivedResolver>,
        diagnosticLocation: CompilerMessageSourceLocation?,
    ): IrExpression =
        when (plan) {
            is ResolutionPlan.LocalContext -> localContexts[plan.index].expression.invoke(this)

            is ResolutionPlan.RecursiveReference ->
                recursiveDerivedResolvers[plan.providedType.normalizedKey()]
                    ?.let { resolver -> irAs(irGet(resolver.cache), resolver.expectedType) }
                    ?: error("Missing recursive resolver for ${plan.providedType.render()}")

            is ResolutionPlan.ApplyRule -> {
                val resolvedRule = ruleIndex.ruleById(plan.ruleId)
                    ?: error("Missing rule reference for ${plan.ruleId}")
                when (val reference = resolvedRule.reference) {
                    is RuleReference.DirectFunction -> {
                        val prerequisiteExpressions =
                            plan.prerequisitePlans.map { nested ->
                                buildExpressionForPlan(
                                    plan = nested,
                                    currentDeclaration = currentDeclaration,
                                    currentFunction = currentFunction,
                                    localContexts = localContexts,
                                    visibleTypeParameters = visibleTypeParameters,
                                    recursiveDerivedResolvers = recursiveDerivedResolvers,
                                    diagnosticLocation = diagnosticLocation,
                                )
                            }
                        buildInstanceFunctionCall(
                            function = reference.function,
                            rule = resolvedRule,
                            appliedTypeArguments = plan.appliedTypeArguments,
                            prerequisiteExpressions = prerequisiteExpressions,
                            visibleTypeParameters = visibleTypeParameters,
                            pluginContext = pluginContext,
                        )
                    }

                    is RuleReference.LookupFunction -> {
                        val function = ruleIndex.resolveLookupFunction(reference)
                            ?: error("Could not resolve instance function ${reference.callableId}")
                        val prerequisiteExpressions =
                            plan.prerequisitePlans.map { nested ->
                                buildExpressionForPlan(
                                    plan = nested,
                                    currentDeclaration = currentDeclaration,
                                    currentFunction = currentFunction,
                                    localContexts = localContexts,
                                    visibleTypeParameters = visibleTypeParameters,
                                    recursiveDerivedResolvers = recursiveDerivedResolvers,
                                    diagnosticLocation = diagnosticLocation,
                                )
                            }
                        buildInstanceFunctionCall(
                            function = function,
                            rule = resolvedRule,
                            appliedTypeArguments = plan.appliedTypeArguments,
                            prerequisiteExpressions = prerequisiteExpressions,
                            visibleTypeParameters = visibleTypeParameters,
                            pluginContext = pluginContext,
                        )
                    }

                    is RuleReference.DirectProperty ->
                        buildInstancePropertyAccess(reference.property)

                    is RuleReference.LookupProperty -> {
                        val property = ruleIndex.resolveLookupProperty(reference)
                            ?: error("Could not resolve instance property ${reference.callableId}")
                        buildInstancePropertyAccess(property)
                    }

                    is RuleReference.DirectObject -> irGetObject(reference.klass.symbol)

                    is RuleReference.LookupObject -> {
                        val klass = pluginContext.referenceClass(reference.classId)?.owner
                            ?: error("Could not resolve instance object ${reference.classId}")
                        irGetObject(klass.symbol)
                    }

                    RuleReference.BuiltinSame ->
                        buildBuiltinProofSingletonExpression(
                            expressionType = modelToIrType(plan.providedType, visibleTypeParameters, pluginContext),
                            proofClassId = SAME_PROOF_CLASS_ID,
                            diagnosticLocation = diagnosticLocation,
                        )

                    RuleReference.BuiltinNotSame ->
                        buildBuiltinNotSameExpression(
                            plan = plan,
                            visibleTypeParameters = visibleTypeParameters,
                            diagnosticLocation = diagnosticLocation,
                        )

                    RuleReference.BuiltinSubtype ->
                        buildBuiltinSubtypeExpression(
                            plan = plan,
                            visibleTypeParameters = visibleTypeParameters,
                            diagnosticLocation = diagnosticLocation,
                        )

                    RuleReference.BuiltinStrictSubtype ->
                        buildBuiltinStrictSubtypeExpression(
                            plan = plan,
                            visibleTypeParameters = visibleTypeParameters,
                            diagnosticLocation = diagnosticLocation,
                        )

                    RuleReference.BuiltinNullable ->
                        buildBuiltinNullableExpression(
                            plan = plan,
                            visibleTypeParameters = visibleTypeParameters,
                            diagnosticLocation = diagnosticLocation,
                        )

                    RuleReference.BuiltinNotNullable ->
                        buildBuiltinNotNullableExpression(
                            plan = plan,
                            visibleTypeParameters = visibleTypeParameters,
                            diagnosticLocation = diagnosticLocation,
                        )

                    RuleReference.BuiltinIsTypeclassInstance ->
                        buildBuiltinIsTypeclassInstanceExpression(
                            plan = plan,
                            visibleTypeParameters = visibleTypeParameters,
                            diagnosticLocation = diagnosticLocation,
                        )

                    RuleReference.BuiltinKnownType ->
                        buildBuiltinKnownTypeExpression(
                            plan = plan,
                            visibleTypeParameters = visibleTypeParameters,
                            diagnosticLocation = diagnosticLocation,
                        )

                    RuleReference.BuiltinTypeId ->
                        buildBuiltinTypeIdExpression(
                            plan = plan,
                            visibleTypeParameters = visibleTypeParameters,
                            diagnosticLocation = diagnosticLocation,
                        )

                    RuleReference.BuiltinSameTypeConstructor ->
                        buildBuiltinSameTypeConstructorExpression(
                            plan = plan,
                            visibleTypeParameters = visibleTypeParameters,
                            diagnosticLocation = diagnosticLocation,
                        )

                    RuleReference.BuiltinKClass ->
                        buildBuiltinKClassExpression(
                            plan = plan,
                            visibleTypeParameters = visibleTypeParameters,
                            diagnosticLocation = diagnosticLocation,
                        )

                    RuleReference.BuiltinKSerializer ->
                        buildBuiltinKSerializerExpression(
                            plan = plan,
                            visibleTypeParameters = visibleTypeParameters,
                            diagnosticLocation = diagnosticLocation,
                        )

                    is RuleReference.Derived ->
                        buildDerivedInstanceExpression(
                            reference = reference,
                            plan = plan,
                            currentDeclaration = currentDeclaration,
                            currentFunction = currentFunction,
                            localContexts = localContexts,
                            visibleTypeParameters = visibleTypeParameters,
                            recursiveDerivedResolvers = recursiveDerivedResolvers,
                            returnMetadataSlot = false,
                        )

                    is RuleReference.DerivedEquiv ->
                        buildGeneratedEquivExpression(
                            sourceType = reference.sourceType,
                            targetType = reference.targetType,
                            forwardPlan = reference.forwardPlan,
                            backwardPlan = reference.backwardPlan,
                            pluginContext = pluginContext,
                            lambdaParent = metadataLambdaParent(currentDeclaration, currentFunction),
                        )

                    is RuleReference.DerivedVia -> {
                        val prerequisiteExpression =
                            plan.prerequisitePlans.singleOrNull()?.let { nested ->
                                buildExpressionForPlan(
                                    plan = nested,
                                    currentDeclaration = currentDeclaration,
                                    currentFunction = currentFunction,
                                    localContexts = localContexts,
                                    visibleTypeParameters = visibleTypeParameters,
                                    recursiveDerivedResolvers = recursiveDerivedResolvers,
                                    diagnosticLocation = diagnosticLocation,
                                )
                            } ?: error("DeriveVia rule ${plan.ruleId} must have exactly one prerequisite")
                        buildDeriveViaAdapterExpression(
                            typeclassInterface = reference.typeclassInterface,
                            expectedType = modelToIrType(plan.providedType, visibleTypeParameters, pluginContext),
                            viaType = reference.viaType,
                            viaInstance = prerequisiteExpression,
                            targetType = reference.targetType,
                            forwardPlan = reference.forwardPlan,
                            backwardPlan = reference.backwardPlan,
                            pluginContext = pluginContext,
                            lambdaParent = metadataLambdaParent(currentDeclaration, currentFunction),
                        )
                    }
                }
            }
        }

    private fun IrStatementsBuilder<*>.buildInstanceSlotForPlan(
        plan: ResolutionPlan,
        currentDeclaration: IrDeclarationBase,
        currentFunction: IrFunction?,
        localContexts: List<LocalTypeclassContext>,
        visibleTypeParameters: VisibleTypeParameters,
        recursiveDerivedResolvers: MutableMap<String, RecursiveDerivedResolver>,
    ): IrExpression =
        when (plan) {
            is ResolutionPlan.RecursiveReference -> {
                val resolver =
                    recursiveDerivedResolvers[plan.providedType.normalizedKey()]
                        ?: error("Missing recursive resolver for ${plan.providedType.render()}")
                irGet(resolver.instanceCell)
            }

            is ResolutionPlan.LocalContext -> {
                val expression =
                    buildExpressionForPlan(
                        plan = plan,
                        currentDeclaration = currentDeclaration,
                        currentFunction = currentFunction,
                        localContexts = localContexts,
                        visibleTypeParameters = visibleTypeParameters,
                        recursiveDerivedResolvers = recursiveDerivedResolvers,
                        diagnosticLocation = currentDeclaration.compilerMessageLocation(),
                    )
                val slot = irTemporary(expression, nameHint = "typeclassMetadataInstanceSlot")
                irGet(slot)
            }

            is ResolutionPlan.ApplyRule -> {
                val resolvedRule = ruleIndex.ruleById(plan.ruleId)
                    ?: error("Missing rule reference for ${plan.ruleId}")
                when (val reference = resolvedRule.reference) {
                    is RuleReference.Derived ->
                        buildDerivedInstanceExpression(
                            reference = reference,
                            plan = plan,
                            currentDeclaration = currentDeclaration,
                            currentFunction = currentFunction,
                            localContexts = localContexts,
                            visibleTypeParameters = visibleTypeParameters,
                            recursiveDerivedResolvers = recursiveDerivedResolvers,
                            returnMetadataSlot = true,
                        )

                    else -> {
                        val expression =
                            buildExpressionForPlan(
                                plan = plan,
                                currentDeclaration = currentDeclaration,
                                currentFunction = currentFunction,
                                localContexts = localContexts,
                                visibleTypeParameters = visibleTypeParameters,
                                recursiveDerivedResolvers = recursiveDerivedResolvers,
                                diagnosticLocation = currentDeclaration.compilerMessageLocation(),
                            )
                        val slot = irTemporary(expression, nameHint = "typeclassMetadataInstanceSlot")
                        irGet(slot)
                    }
                }
            }
        }

    private fun IrBuilderWithScope.buildInstanceFunctionCall(
        function: IrSimpleFunction,
        rule: ResolvedRule,
        appliedTypeArguments: List<TcType>,
        prerequisiteExpressions: List<IrExpression>,
        visibleTypeParameters: VisibleTypeParameters,
        pluginContext: IrPluginContext,
    ): IrExpression {
        val call = irCall(function.symbol)
        val parentClass = function.parent as? IrClass
        if (parentClass != null && parentClass.isCompanion) {
            call.dispatchReceiver = irGetObject(parentClass.symbol)
        }

        rule.rule.typeParameters.zip(appliedTypeArguments).forEachIndexed { index, (_, appliedTypeArgument) ->
            call.putTypeArgument(index, modelToIrType(appliedTypeArgument, visibleTypeParameters, pluginContext))
        }

        val prerequisites = prerequisiteExpressions.iterator()
        function.regularAndContextParameters().forEachIndexed { parameterIndex, parameter ->
            if (parameter.kind != org.jetbrains.kotlin.ir.declarations.IrParameterKind.Context) {
                error("Instance function ${function.callableId} unexpectedly has a regular parameter")
            }
            val valueArgumentIndex = function.valueArgumentIndex(parameter, parameterIndex)
            call.putValueArgument(valueArgumentIndex, prerequisites.nextOrNull() ?: error("Missing prerequisite expression for ${function.callableId}"))
        }
        return call
    }

    private fun IrBuilderWithScope.buildInstancePropertyAccess(
        property: IrProperty,
    ): IrExpression {
        val getter = property.getter ?: error("Instance property ${property.name} is missing a getter")
        return irCall(getter.symbol).apply {
            val parentClass = property.parent as? IrClass
            if (parentClass != null && parentClass.isCompanion) {
                dispatchReceiver = irGetObject(parentClass.symbol)
            }
        }
    }

    private fun IrStatementsBuilder<*>.buildDerivedInstanceExpression(
        reference: RuleReference.Derived,
        plan: ResolutionPlan.ApplyRule,
        currentDeclaration: IrDeclarationBase,
        currentFunction: IrFunction?,
        localContexts: List<LocalTypeclassContext>,
        visibleTypeParameters: VisibleTypeParameters,
        recursiveDerivedResolvers: MutableMap<String, RecursiveDerivedResolver>,
        returnMetadataSlot: Boolean,
    ): IrExpression {
        val expectedType = modelToIrType(plan.providedType, visibleTypeParameters, pluginContext)
        val cacheType = expectedType.makeNullable()
        val resultType = if (returnMetadataSlot) pluginContext.irBuiltIns.anyType else expectedType
        val resolverKey = plan.providedType.normalizedKey()
        recursiveDerivedResolvers[resolverKey]?.let { existing ->
            return if (returnMetadataSlot) {
                irGet(existing.instanceCell)
            } else {
                irAs(irGet(existing.cache), existing.expectedType)
            }
        }
        val recursiveCellClass =
            pluginContext.referenceClass(RECURSIVE_TYPECLASS_INSTANCE_CELL_CLASS_ID)?.owner
                ?: error("Could not resolve $RECURSIVE_TYPECLASS_INSTANCE_CELL_FQ_NAME")
        val recursiveCellValueProperty =
            recursiveCellClass.declarations
                .filterIsInstance<IrProperty>()
                .singleOrNull { property -> property.name.asString() == "value" }
                ?: error("Could not resolve RecursiveTypeclassInstanceCell.value")
        val recursiveCellValueSetter =
            recursiveCellValueProperty.setter
                ?: error("Could not resolve RecursiveTypeclassInstanceCell.value setter")
        val cache = irTemporary(irAs(irNull(), cacheType), nameHint = "typeclassRecursiveCache", isMutable = true)
        val recursiveCell =
            irTemporary(
                irCallConstructor(recursiveCellClass.primaryConstructorSymbol(), emptyList()),
                nameHint = "typeclassRecursiveCell",
            )
        recursiveDerivedResolvers[resolverKey] =
            RecursiveDerivedResolver(
                cache = cache,
                instanceCell = recursiveCell,
                expectedType = expectedType,
            )

        val prerequisiteInstanceSlots =
            plan.prerequisitePlans.map { nested ->
                buildInstanceSlotForPlan(
                    plan = nested,
                    currentDeclaration = currentDeclaration,
                    currentFunction = currentFunction,
                    localContexts = localContexts,
                    visibleTypeParameters = visibleTypeParameters,
                    recursiveDerivedResolvers = recursiveDerivedResolvers,
                )
            }
        val appliedBindings =
            reference.ruleTypeParameters.zip(plan.appliedTypeArguments)
                .associate { (parameter, appliedType) -> parameter.id to appliedType }
        val metadataLambdaParent = metadataLambdaParent(currentDeclaration, currentFunction)
        val metadata =
            when (val shape = reference.shape) {
                is DerivedShape.Product ->
                    buildProductMetadata(
                        reference = reference,
                        shape = shape,
                        prerequisiteInstanceSlots = prerequisiteInstanceSlots,
                        appliedBindings = appliedBindings,
                        lambdaParent = metadataLambdaParent,
                        visibleTypeParameters = visibleTypeParameters,
                    )

                is DerivedShape.Sum ->
                    buildSumMetadata(
                        reference = reference,
                        shape = shape,
                        prerequisiteInstanceSlots = prerequisiteInstanceSlots,
                        appliedBindings = appliedBindings,
                        lambdaParent = metadataLambdaParent,
                        visibleTypeParameters = visibleTypeParameters,
                    )

                is DerivedShape.Enum ->
                    buildEnumMetadata(
                        reference = reference,
                        shape = shape,
                        lambdaParent = metadataLambdaParent,
                    )
            }
        val deriveMethodName =
            when (reference.shape) {
                is DerivedShape.Product -> "deriveProduct"
                is DerivedShape.Sum -> "deriveSum"
                is DerivedShape.Enum -> "deriveEnum"
            }
        val deriveMethod =
            reference.deriverCompanion.declarations
                .filterIsInstance<IrSimpleFunction>()
                .singleOrNull { function -> function.name.asString() == deriveMethodName }
                ?: error("Typeclass deriver ${reference.deriverCompanion.classIdOrFail} is missing $deriveMethodName")
        if (!validateKnownDeriverReturnTypeclass(reference.deriverCompanion.parentAsClass, deriveMethod)) {
            return irAs(irNull(), resultType)
        }

        return irIfThenElse(
            type = resultType,
            condition =
                irCall(pluginContext.irBuiltIns.eqeqSymbol).apply {
                    putValueArgument(0, irGet(cache))
                    putValueArgument(1, irNull())
                },
            thenPart =
                irBlock(resultType = resultType) {
                    val derivedInstance =
                        irTemporary(
                            irAs(
                                irCall(deriveMethod.symbol).apply {
                                    dispatchReceiver = irGetObject(reference.deriverCompanion.symbol)
                                    putValueArgument(0, metadata)
                                },
                                expectedType,
                            ),
                            nameHint = "typeclassDerivedInstance",
                        )
                    +irSet(
                        cache.symbol,
                        irGet(derivedInstance),
                    )
                    +irCall(recursiveCellValueSetter.symbol).apply {
                        dispatchReceiver = irGet(recursiveCell)
                        putValueArgument(0, irGet(derivedInstance))
                    }
                    if (returnMetadataSlot) {
                        +irGet(recursiveCell)
                    } else {
                        +irAs(irGet(cache), expectedType)
                    }
                },
            elsePart =
                if (returnMetadataSlot) {
                    irGet(recursiveCell)
                } else {
                    irAs(irGet(cache), expectedType)
                },
        )
    }

    private fun validateKnownDeriverReturnTypeclass(
        typeclassInterface: IrClass,
        deriveMethod: IrSimpleFunction,
    ): Boolean =
        deriverReturnValidationCache.getOrPut(deriveMethod) {
            val expectedTypeclassId = typeclassInterface.classIdOrFail.asString()
            deriveMethod.knownDeriverReturnTypes().forEach { returnType ->
                val knownTypeclassConstructors =
                    listOf(returnType)
                        .providedTypeExpansion(emptyMap(), configuration)
                        .validTypes
                        .mapNotNull { providedType -> (providedType as? TcType.Constructor)?.classifierId }
                        .distinct()
                if (knownTypeclassConstructors.isEmpty()) {
                    return@forEach
                }
                if (expectedTypeclassId in knownTypeclassConstructors) {
                    return@forEach
                }
                pluginContext.reportTypeclassError(
                    message =
                        "${deriveMethod.name.asString()} must return ${typeclassInterface.name.asString()}<...>; found ${knownTypeclassConstructors.joinToString { classifierId -> classifierId.shortClassNameOrSelf() }}",
                    diagnosticId = TypeclassDiagnosticIds.CANNOT_DERIVE,
                    location = deriveMethod.compilerMessageLocation(),
                )
                return@getOrPut false
            }
            true
        }

    private fun IrStatementsBuilder<*>.buildProductMetadata(
        reference: RuleReference.Derived,
        shape: DerivedShape.Product,
        prerequisiteInstanceSlots: List<IrExpression>,
        appliedBindings: Map<String, TcType>,
        lambdaParent: IrDeclarationParent,
        visibleTypeParameters: VisibleTypeParameters,
    ): IrExpression {
        val fieldClass =
            pluginContext.referenceClass(PRODUCT_FIELD_METADATA_CLASS_ID)?.owner
                ?: error("Could not resolve $PRODUCT_FIELD_METADATA_FQ_NAME")
        val metadataClass =
            pluginContext.referenceClass(PRODUCT_TYPECLASS_METADATA_CLASS_ID)?.owner
                ?: error("Could not resolve $PRODUCT_TYPECLASS_METADATA_FQ_NAME")
        val fieldElements =
            shape.fields.zip(prerequisiteInstanceSlots).map { (field, instanceSlotExpression) ->
                val instanceSlot =
                    irTemporary(
                        instanceSlotExpression,
                        nameHint = "typeclassMetadataInstanceSlot",
                    )
                irCallConstructor(fieldClass.primaryConstructorSymbol(), emptyList()).apply {
                    putValueArgument(0, irString(field.name))
                    putValueArgument(1, irString(field.type.substituteType(appliedBindings).render()))
                    putValueArgument(2, irGet(instanceSlot))
                    putValueArgument(
                        3,
                        buildProductFieldAccessor(
                            reference = reference,
                            field = field,
                            appliedBindings = appliedBindings,
                            lambdaParent = lambdaParent,
                            visibleTypeParameters = visibleTypeParameters,
                        ),
                    )
                }
            }
        return irCallConstructor(metadataClass.primaryConstructorSymbol(), emptyList()).apply {
            putValueArgument(0, irString(reference.targetClass.renderClassName()))
            putValueArgument(1, irListOf(fieldElements, fieldClass.symbol.defaultType))
            putValueArgument(2, irBoolean(reference.targetClass.isValue))
            putValueArgument(
                3,
                buildProductConstructor(
                    reference = reference,
                    shape = shape,
                    appliedBindings = appliedBindings,
                    lambdaParent = lambdaParent,
                    visibleTypeParameters = visibleTypeParameters,
                ),
            )
        }
    }

    private fun IrBuilderWithScope.buildProductConstructor(
        reference: RuleReference.Derived,
        shape: DerivedShape.Product,
        appliedBindings: Map<String, TcType>,
        lambdaParent: IrDeclarationParent,
        visibleTypeParameters: VisibleTypeParameters,
    ): IrExpression {
        val anyType = pluginContext.irBuiltIns.anyNType
        val listClass =
            pluginContext.referenceClass(ClassId.topLevel(FqName("kotlin.collections.List")))?.owner
                ?: error("Could not resolve kotlin.collections.List")
        val listType = listClass.symbol.typeWith(anyType)
        val getFunction =
            listClass.declarations
                .filterIsInstance<IrSimpleFunction>()
                .singleOrNull { function ->
                    function.name.asString() == "get" && function.valueParameters.size == 1
                } ?: error("Could not resolve kotlin.collections.List.get(Int)")
        val lambdaType =
            pluginContext.irBuiltIns.functionN(1).typeWith(
                listType,
                anyType,
            )
        val constructorLambda =
            context.irFactory.buildFun {
                name = Name.special("<typeclass-product-constructor>")
                origin = IrDeclarationOrigin.LOCAL_FUNCTION
                visibility = DescriptorVisibilities.LOCAL
                returnType = anyType
            }.apply {
                parent = lambdaParent
            }
        val argumentsParameter = constructorLambda.addValueParameter("arguments", listType)
        val appliedIrTypesByRuleId =
            reference.ruleTypeParameters.associate { parameter ->
                parameter.id to modelToIrType(
                    type = appliedBindings[parameter.id]
                        ?: error("Missing applied type binding for ${parameter.id}"),
                    visibleTypeParameters = visibleTypeParameters,
                    pluginContext = pluginContext,
                )
            }
        val constructorTypeArguments =
            reference.ruleTypeParameters.map { parameter ->
                appliedIrTypesByRuleId.getValue(parameter.id)
            }
        val typeArgumentMap =
            reference.targetClass.typeParameters.zip(reference.ruleTypeParameters).associate { (typeParameter, parameter) ->
                typeParameter.symbol to appliedIrTypesByRuleId.getValue(parameter.id)
            }
        constructorLambda.body =
            DeclarationIrBuilder(pluginContext, constructorLambda.symbol, startOffset, endOffset).irBlockBody {
                if (reference.targetClass.isObject) {
                    +irReturn(irGetObject(reference.targetClass.symbol))
                    return@irBlockBody
                }
                val constructor = shape.constructor
                    ?: error("Constructive product derivation requires a constructor for ${reference.targetClass.classIdOrFail}")
                +irReturn(
                    irCallConstructor(constructor.symbol, constructorTypeArguments).apply {
                        constructor.valueParameters.forEachIndexed { index, parameter ->
                            val argument =
                                irCall(getFunction.symbol).apply {
                                    dispatchReceiver = irGet(argumentsParameter)
                                    putValueArgument(0, irInt(index))
                                }
                            putValueArgument(index, irAs(argument, parameter.type.substitute(typeArgumentMap)))
                        }
                    },
                )
            }
        return IrFunctionExpressionImpl(
            startOffset = startOffset,
            endOffset = endOffset,
            type = lambdaType,
            function = constructorLambda,
            origin = IrStatementOrigin.LAMBDA,
        )
    }

    private fun IrBuilderWithScope.buildProductFieldAccessor(
        reference: RuleReference.Derived,
        field: DerivedField,
        appliedBindings: Map<String, TcType>,
        lambdaParent: IrDeclarationParent,
        visibleTypeParameters: VisibleTypeParameters,
    ): IrExpression {
        val anyType = pluginContext.irBuiltIns.anyNType
        val lambdaType =
            pluginContext.irBuiltIns.functionN(1).typeWith(
                anyType,
                anyType,
            )
        val targetType =
            reference.targetClass.symbol.typeWith(
                reference.ruleTypeParameters.map { parameter ->
                    modelToIrType(
                        type = appliedBindings[parameter.id]
                            ?: error("Missing applied type binding for ${parameter.id}"),
                        visibleTypeParameters = visibleTypeParameters,
                        pluginContext = pluginContext,
                    )
                },
            )
        val accessor =
            context.irFactory.buildFun {
                name = Name.special("<typeclass-field-accessor>")
                origin = IrDeclarationOrigin.LOCAL_FUNCTION
                visibility = DescriptorVisibilities.LOCAL
                returnType = anyType
            }.apply {
                parent = lambdaParent
            }
        val valueParameter = accessor.addValueParameter("value", anyType)
        accessor.body =
            DeclarationIrBuilder(pluginContext, accessor.symbol, startOffset, endOffset).irBlockBody {
                +irReturn(
                    irCall(field.getter.symbol).apply {
                        dispatchReceiver = irAs(irGet(valueParameter), targetType)
                    },
                )
            }
        return IrFunctionExpressionImpl(
            startOffset = startOffset,
            endOffset = endOffset,
            type = lambdaType,
            function = accessor,
            origin = IrStatementOrigin.LAMBDA,
        )
    }

    private fun IrStatementsBuilder<*>.buildSumMetadata(
        reference: RuleReference.Derived,
        shape: DerivedShape.Sum,
        prerequisiteInstanceSlots: List<IrExpression>,
        appliedBindings: Map<String, TcType>,
        lambdaParent: IrDeclarationParent,
        visibleTypeParameters: VisibleTypeParameters,
    ): IrExpression {
        val caseClass =
            pluginContext.referenceClass(SUM_CASE_METADATA_CLASS_ID)?.owner
                ?: error("Could not resolve $SUM_CASE_METADATA_FQ_NAME")
        val metadataClass =
            pluginContext.referenceClass(SUM_TYPECLASS_METADATA_CLASS_ID)?.owner
                ?: error("Could not resolve $SUM_TYPECLASS_METADATA_FQ_NAME")
        val caseElements =
            shape.cases.zip(prerequisiteInstanceSlots).map { (case, instanceSlotExpression) ->
                val instanceSlot =
                    irTemporary(
                        instanceSlotExpression,
                        nameHint = "typeclassMetadataInstanceSlot",
                    )
                irCallConstructor(caseClass.primaryConstructorSymbol(), emptyList()).apply {
                    putValueArgument(0, irString(case.name))
                    putValueArgument(1, irString(case.klass.renderClassName()))
                    putValueArgument(2, irBoolean(case.klass.isValue))
                    putValueArgument(
                        3,
                        irGet(instanceSlot),
                    )
                    putValueArgument(
                        4,
                        buildSumCaseMatcher(
                            case = case,
                            appliedBindings = appliedBindings,
                            lambdaParent = lambdaParent,
                            visibleTypeParameters = visibleTypeParameters,
                        ),
                    )
                }
            }
        return irCallConstructor(metadataClass.primaryConstructorSymbol(), emptyList()).apply {
            putValueArgument(0, irString(reference.targetClass.renderClassName()))
            putValueArgument(1, irListOf(caseElements, caseClass.symbol.defaultType))
        }
    }

    private fun IrStatementsBuilder<*>.buildEnumMetadata(
        reference: RuleReference.Derived,
        shape: DerivedShape.Enum,
        lambdaParent: IrDeclarationParent,
    ): IrExpression {
        val entryClass =
            pluginContext.referenceClass(ENUM_ENTRY_METADATA_CLASS_ID)?.owner
                ?: error("Could not resolve $ENUM_ENTRY_METADATA_FQ_NAME")
        val metadataClass =
            pluginContext.referenceClass(ENUM_TYPECLASS_METADATA_CLASS_ID)?.owner
                ?: error("Could not resolve $ENUM_TYPECLASS_METADATA_FQ_NAME")
        val entryElements =
            shape.entries.map { entry ->
                irCallConstructor(entryClass.primaryConstructorSymbol(), emptyList()).apply {
                    putValueArgument(0, irString(entry.name))
                }
            }
        return irCallConstructor(metadataClass.primaryConstructorSymbol(), emptyList()).apply {
            putValueArgument(0, irString(reference.targetClass.renderClassName()))
            putValueArgument(1, irListOf(entryElements, entryClass.symbol.defaultType))
            putValueArgument(
                2,
                buildEnumOrdinalResolver(
                    reference = reference,
                    shape = shape,
                    lambdaParent = lambdaParent,
                ),
            )
            putValueArgument(
                3,
                buildEnumValueResolver(
                    reference = reference,
                    shape = shape,
                    lambdaParent = lambdaParent,
                ),
            )
        }
    }

    private fun IrBuilderWithScope.buildEnumOrdinalResolver(
        reference: RuleReference.Derived,
        shape: DerivedShape.Enum,
        lambdaParent: IrDeclarationParent,
    ): IrExpression {
        val anyType = pluginContext.irBuiltIns.anyNType
        val intType = pluginContext.irBuiltIns.intType
        val lambdaType =
            pluginContext.irBuiltIns.functionN(1).typeWith(
                anyType,
                intType,
            )
        val resolver =
            context.irFactory.buildFun {
                name = Name.special("<typeclass-enum-ordinal>")
                origin = IrDeclarationOrigin.LOCAL_FUNCTION
                visibility = DescriptorVisibilities.LOCAL
                returnType = intType
            }.apply {
                parent = lambdaParent
            }
        val valueParameter = resolver.addValueParameter("value", anyType)
        resolver.body =
            DeclarationIrBuilder(pluginContext, resolver.symbol, startOffset, endOffset).irBlockBody {
                var result: IrExpression = irInt(-1)
                for (index in shape.entries.indices.reversed()) {
                    val entry = shape.entries[index]
                    result =
                        irIfThenElse(
                            type = intType,
                            condition =
                                irCall(pluginContext.irBuiltIns.eqeqSymbol).apply {
                                    putValueArgument(0, irGet(valueParameter))
                                    putValueArgument(1, irEnumEntryValue(reference.targetClass, entry.entry))
                                },
                            thenPart = irInt(index),
                            elsePart = result,
                        )
                }
                +irReturn(result)
            }
        return IrFunctionExpressionImpl(
            startOffset = startOffset,
            endOffset = endOffset,
            type = lambdaType,
            function = resolver,
            origin = IrStatementOrigin.LAMBDA,
        )
    }

    private fun IrBuilderWithScope.buildEnumValueResolver(
        reference: RuleReference.Derived,
        shape: DerivedShape.Enum,
        lambdaParent: IrDeclarationParent,
    ): IrExpression {
        val intType = pluginContext.irBuiltIns.intType
        val anyType = pluginContext.irBuiltIns.anyNType
        val lambdaType =
            pluginContext.irBuiltIns.functionN(1).typeWith(
                intType,
                anyType,
            )
        val resolver =
            context.irFactory.buildFun {
                name = Name.special("<typeclass-enum-value>")
                origin = IrDeclarationOrigin.LOCAL_FUNCTION
                visibility = DescriptorVisibilities.LOCAL
                returnType = anyType
            }.apply {
                parent = lambdaParent
            }
        val indexParameter = resolver.addValueParameter("index", intType)
        resolver.body =
            DeclarationIrBuilder(pluginContext, resolver.symbol, startOffset, endOffset).irBlockBody {
                var result: IrExpression = irNull()
                for (index in shape.entries.indices.reversed()) {
                    val entry = shape.entries[index]
                    result =
                        irIfThenElse(
                            type = anyType,
                            condition =
                                irCall(pluginContext.irBuiltIns.eqeqSymbol).apply {
                                    putValueArgument(0, irGet(indexParameter))
                                    putValueArgument(1, irInt(index))
                                },
                            thenPart = irEnumEntryValue(reference.targetClass, entry.entry),
                            elsePart = result,
                        )
                }
                +irReturn(result)
            }
        return IrFunctionExpressionImpl(
            startOffset = startOffset,
            endOffset = endOffset,
            type = lambdaType,
            function = resolver,
            origin = IrStatementOrigin.LAMBDA,
        )
    }

    private fun IrBuilderWithScope.irEnumEntryValue(
        enumClass: IrClass,
        entry: IrEnumEntry,
    ): IrExpression =
        IrGetEnumValueImpl(
            startOffset,
            endOffset,
            enumClass.symbol.defaultType,
            entry.symbol,
        )

    private fun IrBuilderWithScope.buildSumCaseMatcher(
        case: DerivedCase,
        appliedBindings: Map<String, TcType>,
        lambdaParent: IrDeclarationParent,
        visibleTypeParameters: VisibleTypeParameters,
    ): IrExpression {
        val anyType = pluginContext.irBuiltIns.anyNType
        val booleanType = pluginContext.irBuiltIns.booleanType
        val lambdaType =
            pluginContext.irBuiltIns.functionN(1).typeWith(
                anyType,
                booleanType,
            )
        val caseType =
            modelToIrType(
                type = case.type.substituteType(appliedBindings),
                visibleTypeParameters = visibleTypeParameters,
                pluginContext = pluginContext,
            )
        val matcher =
            context.irFactory.buildFun {
                name = Name.special("<typeclass-sum-case-matcher>")
                origin = IrDeclarationOrigin.LOCAL_FUNCTION
                visibility = DescriptorVisibilities.LOCAL
                returnType = booleanType
            }.apply {
                parent = lambdaParent
            }
        val valueParameter = matcher.addValueParameter("value", anyType)
        matcher.body =
            DeclarationIrBuilder(pluginContext, matcher.symbol, startOffset, endOffset).irBlockBody {
                +irReturn(irIs(irGet(valueParameter), caseType))
            }
        return IrFunctionExpressionImpl(
            startOffset = startOffset,
            endOffset = endOffset,
            type = lambdaType,
            function = matcher,
            origin = IrStatementOrigin.LAMBDA,
        )
    }

    private fun metadataLambdaParent(
        currentDeclaration: IrDeclarationBase,
        currentFunction: IrFunction?,
    ): IrDeclarationParent =
        currentFunction
            ?: (currentDeclaration as? IrDeclarationParent)
            ?: currentDeclaration.parent

    private fun IrBuilderWithScope.irListOf(
        elements: List<IrExpression>,
        elementType: IrType,
    ): IrExpression {
        val listOfFunction =
            pluginContext.referenceFunctions(CallableId(FqName("kotlin.collections"), Name.identifier("listOf")))
                .map { it.owner }
                .singleOrNull { function ->
                    function.valueParameters.singleOrNull()?.varargElementType != null
                }
                ?: error("Could not resolve kotlin.collections.listOf(vararg)")
        return irCall(listOfFunction.symbol).apply {
            putTypeArgument(0, elementType)
            putValueArgument(0, irVararg(elementType, elements))
        }
    }
}

private fun IrPluginContext.reportTypeclassError(
    message: String,
    diagnosticId: String? = null,
    location: CompilerMessageSourceLocation? = null,
) {
    val renderedMessage =
        diagnosticId?.let { id -> "[$id] $message" } ?: message
    messageCollector.report(CompilerMessageSeverity.ERROR, renderedMessage, location)
}

private fun IrDeclarationBase.compilerMessageLocation(element: IrElement? = null): CompilerMessageSourceLocation? {
    val file = containingFile() ?: return null
    val offset = element?.startOffset ?: startOffset
    return file.compilerMessageLocation(offset)
}

private fun IrDeclarationBase.containingFile(): IrFile? {
    var current: IrDeclarationParent = parent
    while (true) {
        current =
            when (current) {
                is IrFile -> return current
                is IrDeclaration -> current.parent
                else -> return null
            }
    }
}

private fun IrFile.compilerMessageLocation(offset: Int): CompilerMessageSourceLocation {
    val clampedOffset = offset.coerceIn(0, fileEntry.maxOffset)
    val line = fileEntry.getLineNumber(clampedOffset) + 1
    val column = fileEntry.getColumnNumber(clampedOffset) + 1
    return object : CompilerMessageSourceLocation {
        override val path: String = fileEntry.name

        override val line: Int = line

        override val column: Int = column

        override val lineContent: String? = null
    }
}

private fun IrSimpleFunction.knownDeriverReturnTypes(): List<IrType> {
    val body = body ?: return emptyList()
    val returnTypes = linkedSetOf<IrType>()

    fun record(expression: IrExpression?) {
        expression?.knownReturnedTypeOrNull()?.let(returnTypes::add)
    }

    when (body) {
        is IrExpressionBody -> record(body.expression)
        is org.jetbrains.kotlin.ir.expressions.IrBlockBody ->
            (body.statements.lastOrNull() as? IrExpression)?.let(::record)
        else -> Unit
    }

    body.acceptChildrenVoid(
        object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitFunction(declaration: IrFunction) {
                return
            }

            override fun visitClass(declaration: IrClass) {
                return
            }

            override fun visitReturn(expression: IrReturn) {
                if (expression.returnTargetSymbol == this@knownDeriverReturnTypes.symbol) {
                    record(expression.value)
                    return
                }
                super.visitReturn(expression)
            }
        },
    )
    return returnTypes.toList()
}

private fun IrExpression.knownReturnedTypeOrNull(): IrType? =
    when (this) {
        is IrTypeOperatorCall -> argument.knownReturnedTypeOrNull()
        is IrComposite -> (statements.lastOrNull() as? IrExpression)?.knownReturnedTypeOrNull() ?: type
        is org.jetbrains.kotlin.ir.expressions.IrBlock ->
            (statements.lastOrNull() as? IrExpression)?.knownReturnedTypeOrNull() ?: type
        else -> type
    }

private fun String.shortClassNameOrSelf(): String =
    runCatching { ClassId.fromString(this).shortClassName.asString() }.getOrDefault(this)

private class IrRuleIndex private constructor(
    private val pluginContext: IrPluginContext,
    val configuration: TypeclassConfiguration,
    private val scanner: IrModuleScanner,
    private val originalsByCallableId: Map<CallableId, List<IrSimpleFunction>>,
    private val rulesById: Map<String, ResolvedRule>,
    private val topLevelRules: List<ResolvedRule>,
    private val associatedRulesByOwner: Map<ClassId, List<ResolvedRule>>,
    private val classInfoById: MutableMap<String, VisibleClassHierarchyInfo>,
) {
    private val lazilyDiscoveredAssociatedRulesByOwner: MutableMap<ClassId, List<ResolvedRule>> = linkedMapOf()
    private val lazilyDiscoveredRulesById: MutableMap<String, ResolvedRule> = linkedMapOf()
    private val deriverReturnValidationCache: MutableMap<IrSimpleFunction, Boolean> = linkedMapOf()

    fun originalForWrapperLikeFunction(wrapperLikeFunction: IrSimpleFunction): IrSimpleFunction? {
        val callableId = wrapperLikeFunction.safeCallableIdOrNull() ?: return null
        val candidates = originalsByCallableId[callableId].orEmpty()
        return candidates.singleOrNull { candidate ->
            wrapperResolutionShape(candidate, dropTypeclassContexts = true, configuration = configuration) ==
                wrapperResolutionShape(wrapperLikeFunction, dropTypeclassContexts = false, configuration = configuration)
        }
    }

    fun ruleById(ruleId: String): ResolvedRule? = rulesById[ruleId] ?: lazilyDiscoveredRulesById[ruleId]

    fun resolveLookupFunction(reference: RuleReference.LookupFunction): IrSimpleFunction? =
        pluginContext.referenceFunctions(reference.callableId)
            .map { it.owner }
            .singleOrNull { function ->
                lookupFunctionShape(function, dropTypeclassContexts = false, configuration = configuration) == reference.shape
            }

    fun resolveLookupProperty(reference: RuleReference.LookupProperty): IrProperty? =
        pluginContext.referenceProperties(reference.callableId)
            .map { it.owner }
            .singleOrNull()

    fun rulesForGoal(
        goal: TcType,
        canMaterializeVariable: (String) -> Boolean = { true },
    ): List<InstanceRule> {
        val owners = associatedOwnersForGoal(goal)
        val associated =
            owners.flatMapTo(linkedSetOf()) { owner ->
                associatedRulesByOwner[owner].orEmpty() + discoverAssociatedRules(owner)
            }
        return (topLevelRules + associated)
            .asSequence()
            .filter { resolvedRule ->
                resolvedRule.rule.id != "builtin:kclass" || supportsBuiltinKClassGoal(goal, canMaterializeVariable)
            }
            .filter { resolvedRule ->
                resolvedRule.rule.id != "builtin:subtype" || supportsBuiltinSubtypeGoal(goal, classInfoById)
            }
            .filter { resolvedRule ->
                resolvedRule.rule.id != "builtin:strict-subtype" || supportsBuiltinStrictSubtypeGoal(goal, classInfoById)
            }
            .filter { resolvedRule ->
                resolvedRule.rule.id != "builtin:kserializer" || supportsBuiltinKSerializerGoal(goal, pluginContext, canMaterializeVariable)
            }
            .filter { resolvedRule ->
                resolvedRule.rule.id != "builtin:notsame" || supportsBuiltinNotSameGoal(goal)
            }
            .filter { resolvedRule ->
                resolvedRule.rule.id != "builtin:nullable" || supportsBuiltinNullableGoal(goal)
            }
            .filter { resolvedRule ->
                resolvedRule.rule.id != "builtin:not-nullable" || supportsBuiltinNotNullableGoal(goal)
            }
            .filter { resolvedRule ->
                resolvedRule.rule.id != "builtin:is-typeclass-instance" || supportsBuiltinIsTypeclassInstanceGoal(goal) { classifierId ->
                    pluginContext.supportsTypeclassClassifierId(classifierId, configuration)
                }
            }
            .filter { resolvedRule ->
                resolvedRule.rule.id != "builtin:known-type" || supportsBuiltinKnownTypeGoal(goal, canMaterializeVariable)
            }
            .filter { resolvedRule ->
                resolvedRule.rule.id != "builtin:type-id" || supportsBuiltinTypeIdGoal(goal, canMaterializeVariable)
            }
            .filter { resolvedRule ->
                resolvedRule.rule.id != "builtin:same-type-constructor" || supportsBuiltinSameTypeConstructorGoal(goal)
            }
            .distinctBy { resolvedRule -> resolvedRule.rule.id }
            .map(ResolvedRule::rule)
            .toList()
    }

    private fun associatedOwnersForGoal(goal: TcType): Set<ClassId> {
        val constructor = goal as? TcType.Constructor ?: return emptySet()
        val ownerIds = linkedSetOf<String>()
        ownerIds += sealedOwnerChain(constructor.classifierId)
        constructor.arguments.forEach { argument ->
            ownerIds += associatedOwnerIds(argument)
        }
        return ownerIds.map(ClassId::fromString).toSet()
    }

    private fun discoverAssociatedRules(owner: ClassId): List<ResolvedRule> =
        if (associatedRulesByOwner.containsKey(owner)) {
            emptyList()
        } else lazilyDiscoveredAssociatedRulesByOwner.getOrPut(owner) {
            val ownerClass = discoverReferencedClass(owner) ?: return@getOrPut emptyList()
            val companionRules =
                ownerClass.declarations
                    .filterIsInstance<IrClass>()
                    .firstOrNull(IrClass::isCompanion)
                    ?.let { companion ->
                        buildList {
                            collectAssociatedRules(
                                declaration = companion,
                                associatedOwner = owner,
                                sink = this,
                            )
                        }
                    }.orEmpty()
            val derivedRules = discoverDerivedRules(owner, ownerClass)
            buildList {
                addAll(companionRules)
                addAll(derivedRules)
            }.also { rules ->
                rules.forEach { rule ->
                    lazilyDiscoveredRulesById[rule.rule.id] = rule
                }
            }
        }

    private fun discoverReferencedClass(owner: ClassId): IrClass? {
        val ownerKey = owner.asString()
        scanner.classesById[ownerKey]?.let { return it }
        val referencedClass = pluginContext.referenceClass(owner)?.owner ?: return null
        scanner.registerDiscoveredClass(referencedClass)
        return scanner.classesById[ownerKey] ?: referencedClass
    }

    private fun discoverDerivedRules(
        owner: ClassId,
        ownerClass: IrClass,
    ): List<ResolvedRule> =
        scanner.derivedRulesForOwner(owner.asString()).ifEmpty {
            scanner.registerDiscoveredClass(ownerClass)
            scanner.derivedRulesForOwner(owner.asString())
        }

    private fun collectAssociatedRules(
        declaration: IrDeclaration,
        associatedOwner: ClassId,
        sink: MutableList<ResolvedRule>,
    ) {
        when (declaration) {
            is IrClass -> {
                declaration.toDiscoveredObjectRules(idPrefix = "lookup-associated-object", associatedOwner = associatedOwner).forEach(sink::add)
                declaration.declarations.forEach { nestedDeclaration ->
                    collectAssociatedRules(
                        declaration = nestedDeclaration,
                        associatedOwner = associatedOwner,
                        sink = sink,
                    )
                }
            }

            is IrSimpleFunction ->
                declaration.toDiscoveredFunctionRules(idPrefix = "lookup-associated-function", associatedOwner = associatedOwner).forEach(sink::add)

            is IrProperty ->
                declaration.toDiscoveredPropertyRules(idPrefix = "lookup-associated-property", associatedOwner = associatedOwner).forEach(sink::add)

            else -> Unit
        }
    }

    private fun IrClass.toDiscoveredObjectRules(
        idPrefix: String,
        associatedOwner: ClassId,
    ): List<ResolvedRule> {
        if (!hasAnnotation(INSTANCE_ANNOTATION_CLASS_ID) || !irInstanceOwnerContext(this).isIndexableScope) {
            return emptyList()
        }
        return superTypes.providedTypeExpansion(emptyMap(), configuration).validTypes.map { providedType ->
            ResolvedRule(
                rule =
                    InstanceRule(
                        id = directRuleId(
                            prefix = idPrefix,
                            declarationKey = classIdOrFail.asString(),
                            providedType = providedType,
                        ),
                        typeParameters = emptyList(),
                        providedType = providedType,
                        prerequisiteTypes = emptyList(),
                    ),
                reference = RuleReference.DirectObject(this),
                associatedOwner = associatedOwner,
            )
        }
    }

    private fun IrSimpleFunction.toDiscoveredFunctionRules(
        idPrefix: String,
        associatedOwner: ClassId,
    ): List<ResolvedRule> {
        if (!hasAnnotation(INSTANCE_ANNOTATION_CLASS_ID) || !irInstanceOwnerContext(this).isIndexableScope) {
            return emptyList()
        }
        if (extensionReceiverParameter != null) {
            return emptyList()
        }
        if (parameters.any { parameter -> parameter.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Regular }) {
            return emptyList()
        }
        val typeParameters = this.typeParameters.map { typeParameter ->
            TcTypeParameter(
                id = ruleTypeParameterId(this, typeParameter),
                displayName = typeParameter.name.asString(),
            )
        }
        val typeParameterBySymbol = typeParametersBySymbol(this, typeParameters)
        val prerequisites =
            contextParameters().mapNotNull { parameter ->
                parameter.type.takeIf { type -> type.isTypeclassType(configuration) }?.let { irTypeToModel(it, typeParameterBySymbol) }
            }
        if (prerequisites.size != contextParameters().size) {
            return emptyList()
        }
        return listOf(returnType).providedTypeExpansion(typeParameterBySymbol, configuration).validTypes.map { providedType ->
            ResolvedRule(
                rule =
                    InstanceRule(
                        id = directRuleId(
                            prefix = idPrefix,
                            declarationKey = callableId.toString(),
                            providedType = providedType,
                            prerequisiteTypes = prerequisites,
                            typeParameters = typeParameters,
                        ),
                        typeParameters = typeParameters,
                        providedType = providedType,
                        prerequisiteTypes = prerequisites,
                    ),
                reference = RuleReference.DirectFunction(this),
                associatedOwner = associatedOwner,
            )
        }
    }

    private fun IrProperty.toDiscoveredPropertyRules(
        idPrefix: String,
        associatedOwner: ClassId,
    ): List<ResolvedRule> {
        if (!hasAnnotation(INSTANCE_ANNOTATION_CLASS_ID) || !irInstanceOwnerContext(this).isIndexableScope) {
            return emptyList()
        }
        val getter = getter ?: return emptyList()
        if (getter.extensionReceiverParameter != null) {
            return emptyList()
        }
        return listOf(backingFieldOrGetterType()).providedTypeExpansion(emptyMap(), configuration).validTypes.map { providedType ->
            ResolvedRule(
                rule =
                    InstanceRule(
                        id = directRuleId(
                            prefix = idPrefix,
                            declarationKey = callableId.toString(),
                            providedType = providedType,
                        ),
                        typeParameters = emptyList(),
                        providedType = providedType,
                        prerequisiteTypes = emptyList(),
                    ),
                reference = RuleReference.DirectProperty(this),
                associatedOwner = associatedOwner,
            )
        }
    }

    private fun associatedOwnerIds(type: TcType): Set<String> =
        when (type) {
            TcType.StarProjection -> emptySet()
            is TcType.Projected -> associatedOwnerIds(type.type)
            is TcType.Variable -> emptySet()

            is TcType.Constructor -> {
                val owners = linkedSetOf<String>()
                owners += sealedOwnerChain(type.classifierId)
                type.arguments.forEach { argument ->
                    owners += associatedOwnerIds(argument)
                }
                owners
            }
        }

    private fun sealedOwnerChain(classifierId: String): Set<String> {
        val result = linkedSetOf(classifierId)
        val queue = ArrayDeque<String>()
        queue += classifierId
        val visited = linkedSetOf<String>()
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) {
                continue
            }
            discoverReferencedClass(ClassId.fromString(current))
            classInfoById[current]?.superClassifiers.orEmpty().forEach { superClassifier ->
                discoverReferencedClass(ClassId.fromString(superClassifier))
                if (classInfoById[superClassifier]?.isSealed == true) {
                    result += superClassifier
                }
                queue += superClassifier
            }
        }
        return result
    }

    companion object {
        fun build(
            moduleFragment: IrModuleFragment,
            pluginContext: IrPluginContext,
            sharedState: TypeclassPluginSharedState,
        ): IrRuleIndex {
            val scanner = IrModuleScanner(pluginContext, sharedState.configuration)
            moduleFragment.files.forEach(scanner::scanFile)

            val directCompanionRules = scanner.buildCompanionRules()
            val directTopLevelRules = scanner.buildTopLevelRules()
            val builtinRules = scanner.buildBuiltinRules()
            val derivedRules = scanner.buildDerivedRules()
            val topLevelRules = directTopLevelRules + builtinRules
            val associatedSourceRules = directCompanionRules + derivedRules
            val allRules = directTopLevelRules + directCompanionRules + derivedRules + builtinRules
            val associatedRules =
                associatedSourceRules
                    .groupBy { it.associatedOwner ?: error("Associated rule must have owner") }
            return IrRuleIndex(
                pluginContext = pluginContext,
                configuration = sharedState.configuration,
                scanner = scanner,
                originalsByCallableId = scanner.buildOriginalIndex(),
                rulesById = allRules.associateBy { it.rule.id },
                topLevelRules = topLevelRules,
                associatedRulesByOwner = associatedRules,
                classInfoById = scanner.classInfoById,
            )
        }
    }
}

private class IrModuleScanner(
    private val pluginContext: IrPluginContext,
    private val configuration: TypeclassConfiguration,
) {
    val classInfoById: MutableMap<String, VisibleClassHierarchyInfo> = linkedMapOf()

    val classesById = linkedMapOf<String, IrClass>()
    val declaredDerivationsByClassId = linkedMapOf<String, Set<ClassId>>()
    private val originalsByCallableId = linkedMapOf<CallableId, MutableList<IrSimpleFunction>>()
    private val topLevelRules = mutableListOf<ResolvedRule>()
    private val companionRules = mutableListOf<ResolvedRule>()

    fun scanFile(file: IrFile) {
        file.declarations.forEach { declaration ->
            scanDeclaration(declaration, associatedOwner = null)
        }
    }

    fun buildOriginalIndex(): Map<CallableId, List<IrSimpleFunction>> =
        originalsByCallableId.mapValues { (_, functions) -> functions.toList() }

    fun buildCompanionRules(): List<ResolvedRule> = companionRules

    fun buildTopLevelRules(): List<ResolvedRule> = topLevelRules

    fun buildBuiltinRules(): List<ResolvedRule> =
        buildList {
            add(
                ResolvedRule(
                    rule = builtinSameRule(),
                    reference = RuleReference.BuiltinSame,
                    associatedOwner = null,
                ),
            )
            add(
                ResolvedRule(
                    rule = builtinNotSameRule(),
                    reference = RuleReference.BuiltinNotSame,
                    associatedOwner = null,
                ),
            )
            add(
                ResolvedRule(
                    rule = builtinSubtypeRule(),
                    reference = RuleReference.BuiltinSubtype,
                    associatedOwner = null,
                ),
            )
            add(
                ResolvedRule(
                    rule = builtinStrictSubtypeRule(),
                    reference = RuleReference.BuiltinStrictSubtype,
                    associatedOwner = null,
                ),
            )
            add(
                ResolvedRule(
                    rule = builtinNullableRule(),
                    reference = RuleReference.BuiltinNullable,
                    associatedOwner = null,
                ),
            )
            add(
                ResolvedRule(
                    rule = builtinNotNullableRule(),
                    reference = RuleReference.BuiltinNotNullable,
                    associatedOwner = null,
                ),
            )
            add(
                ResolvedRule(
                    rule = builtinIsTypeclassInstanceRule(),
                    reference = RuleReference.BuiltinIsTypeclassInstance,
                    associatedOwner = null,
                ),
            )
            add(
                ResolvedRule(
                    rule = builtinKnownTypeRule(),
                    reference = RuleReference.BuiltinKnownType,
                    associatedOwner = null,
                ),
            )
            add(
                ResolvedRule(
                    rule = builtinTypeIdRule(),
                    reference = RuleReference.BuiltinTypeId,
                    associatedOwner = null,
                ),
            )
            add(
                ResolvedRule(
                    rule = builtinSameTypeConstructorRule(),
                    reference = RuleReference.BuiltinSameTypeConstructor,
                    associatedOwner = null,
                ),
            )
            if (configuration.builtinKClassTypeclass == TypeclassBuiltinMode.ENABLED) {
                add(
                    ResolvedRule(
                        rule = builtinKClassRule(),
                        reference = RuleReference.BuiltinKClass,
                        associatedOwner = null,
                    ),
                )
            }
            if (configuration.builtinKSerializerTypeclass == TypeclassBuiltinMode.ENABLED) {
                add(
                    ResolvedRule(
                        rule = builtinKSerializerRule(),
                        reference = RuleReference.BuiltinKSerializer,
                        associatedOwner = null,
                    ),
                )
            }
        }

    fun buildDerivedRules(): List<ResolvedRule> {
        val subclassesBySuper = subclassesBySuper()
        val expandedPairs = linkedSetOf<Pair<String, ClassId>>()
        val queue = ArrayDeque<Pair<String, ClassId>>()
        declaredDerivationsByClassId.forEach { (classId, typeclassIds) ->
            typeclassIds.forEach { typeclassId ->
                queue += classId to typeclassId
            }
        }
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (!expandedPairs.add(next)) {
                continue
            }
            val (classId, typeclassId) = next
            if (classInfoById[classId]?.isSealed == true) {
                subclassesBySuper[classId].orEmpty().forEach { subclassId ->
                    queue += subclassId to typeclassId
                }
            }
        }
        return buildList {
            addAll(
                expandedPairs.flatMap { (classId, typeclassId) ->
                    classesById[classId]?.toDerivedRules(typeclassId, subclassesBySuper).orEmpty()
                },
            )
            addAll(
                classesById.values.flatMap { klass ->
                    klass.toDerivedEquivRules() + klass.toDeriveViaRules()
                },
            )
        }
    }

    fun registerDiscoveredClass(declaration: IrClass) {
        val classId = declaration.classId ?: return
        val classKey = classId.asString()
        if (classesById.containsKey(classKey) && classInfoById.containsKey(classKey)) {
            return
        }
        classesById[classKey] = declaration
        declaredDerivationsByClassId.putIfAbsent(classKey, declaration.supportedDerivedTypeclassIds())
        classInfoById[classKey] =
            VisibleClassHierarchyInfo(
                superClassifiers =
                    declaration.superTypes.mapNotNull { superType ->
                        superType.classOrNull?.owner?.classId?.asString()
                    }.toSet(),
                isSealed = declaration.modality == Modality.SEALED,
                typeParameterVariances = declaration.typeParameters.map { typeParameter -> typeParameter.variance },
            )
        declaration.superTypes.mapNotNull { superType ->
            superType.classOrNull?.owner
        }.forEach(::registerDiscoveredClass)
        if (declaration.modality == Modality.SEALED) {
            declaration.sealedSubclasses.mapNotNull { subclassSymbol ->
                runCatching { subclassSymbol.owner }.getOrNull()
            }.forEach(::registerDiscoveredClass)
        }
    }

    fun derivedRulesForOwner(classifierId: String): List<ResolvedRule> {
        val ownerClass = classesById[classifierId] ?: return emptyList()
        val subclassesBySuper = subclassesBySuper()
        return applicableDerivedTypeclassIdsForOwner(classifierId).flatMap { typeclassId ->
            ownerClass.toDerivedRules(typeclassId, subclassesBySuper)
        }
    }

    private fun applicableDerivedTypeclassIdsForOwner(classifierId: String): Set<ClassId> =
        sealedOwnerChain(classifierId).flatMapTo(linkedSetOf()) { ownerId ->
            declaredDerivationsByClassId[ownerId].orEmpty()
        }

    private fun subclassesBySuper(): Map<String, Set<String>> =
        classInfoById.entries.fold(linkedMapOf<String, MutableSet<String>>()) { acc, (classId, info) ->
            info.superClassifiers.forEach { superClassifier ->
                acc.getOrPut(superClassifier, ::linkedSetOf) += classId
            }
            acc
        }

    private fun sealedOwnerChain(classifierId: String): Set<String> {
        val result = linkedSetOf(classifierId)
        val queue = ArrayDeque<String>()
        queue += classifierId
        val visited = linkedSetOf<String>()
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) {
                continue
            }
            classInfoById[current]?.superClassifiers.orEmpty().forEach { superClassifier ->
                if (classInfoById[superClassifier]?.isSealed == true) {
                    result += superClassifier
                }
                queue += superClassifier
            }
        }
        return result
    }

    private fun scanDeclaration(
        declaration: IrDeclaration,
        associatedOwner: ClassId?,
    ) {
        when (declaration) {
            is IrClass -> {
                declaration.classId?.let { classId ->
                    val supportedDerivedTypeclassIds = declaration.supportedDerivedTypeclassIds()
                    classesById[classId.asString()] = declaration
                    declaredDerivationsByClassId[classId.asString()] = supportedDerivedTypeclassIds
                    classInfoById[classId.asString()] =
                        VisibleClassHierarchyInfo(
                            superClassifiers =
                                declaration.superTypes.mapNotNull { superType ->
                                    superType.classOrNull?.owner?.classId?.asString()
                                }.toSet(),
                            isSealed = declaration.modality == Modality.SEALED,
                            typeParameterVariances = declaration.typeParameters.map { typeParameter -> typeParameter.variance },
                        )
                    if (supportedDerivedTypeclassIds.isNotEmpty()) {
                        declaration.recordGeneratedDerivedTypeclassMetadata(supportedDerivedTypeclassIds)
                    }
                }

                val nextAssociatedOwner =
                    when {
                        declaration.isCompanion -> declaration.parentAsClass.classIdOrFail
                        associatedOwner != null -> associatedOwner
                        else -> null
                    }

                when {
                    declaration.isInstanceObject() && associatedOwner == null -> {
                        topLevelRules += declaration.toObjectRules(idPrefix = "top-level-object", associatedOwner = null)
                    }
                    declaration.isInstanceObject() && nextAssociatedOwner != null -> {
                        companionRules += declaration.toObjectRules(idPrefix = "associated-object", associatedOwner = nextAssociatedOwner)
                    }
                }

                declaration.declarations.forEach { nested ->
                    scanDeclaration(nested, associatedOwner = nextAssociatedOwner)
                }
            }

            is IrSimpleFunction -> {
                originalsByCallableId.getOrPut(declaration.callableId, ::mutableListOf) += declaration

                if (associatedOwner == null) {
                    topLevelRules += declaration.toFunctionRules(idPrefix = "top-level-function", associatedOwner = null)
                } else {
                    companionRules += declaration.toFunctionRules(idPrefix = "associated-function", associatedOwner = associatedOwner)
                }
            }

            is IrProperty -> {
                if (associatedOwner == null) {
                    topLevelRules += declaration.toPropertyRules(idPrefix = "top-level-property", associatedOwner = null)
                } else {
                    companionRules += declaration.toPropertyRules(idPrefix = "associated-property", associatedOwner = associatedOwner)
                }
            }
        }
    }

    private fun IrClass.toObjectRules(
        idPrefix: String,
        associatedOwner: ClassId?,
    ): List<ResolvedRule> {
        if (!hasAnnotation(INSTANCE_ANNOTATION_CLASS_ID)) {
            return emptyList()
        }
        if (!irInstanceOwnerContext(this).isIndexableScope) {
            return emptyList()
        }
        val providedTypes = superTypes.providedTypeExpansion(emptyMap(), configuration).validTypes
        if (associatedOwner == null && !isLegalTopLevelInstanceLocation(providedTypes, classesById)) {
            return emptyList()
        }
        return providedTypes.map { providedType ->
            ResolvedRule(
                rule =
                    InstanceRule(
                        id = directRuleId(
                            prefix = idPrefix,
                            declarationKey = classIdOrFail.asString(),
                            providedType = providedType,
                        ),
                        typeParameters = emptyList(),
                        providedType = providedType,
                        prerequisiteTypes = emptyList(),
                    ),
                reference = RuleReference.DirectObject(this),
                associatedOwner = associatedOwner,
            )
        }
    }

    private fun IrSimpleFunction.toFunctionRules(
        idPrefix: String,
        associatedOwner: ClassId?,
    ): List<ResolvedRule> {
        if (!hasAnnotation(INSTANCE_ANNOTATION_CLASS_ID)) {
            return emptyList()
        }
        if (!irInstanceOwnerContext(this).isIndexableScope) {
            return emptyList()
        }
        if (extensionReceiverParameter != null) {
            return emptyList()
        }
        if (parameters.any { it.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Regular }) {
            return emptyList()
        }

        val typeParameters = this.typeParameters.map { typeParameter ->
            TcTypeParameter(
                id = ruleTypeParameterId(this, typeParameter),
                displayName = typeParameter.name.asString(),
            )
        }
        val typeParameterBySymbol = typeParametersBySymbol(this, typeParameters)
        val prerequisites =
            contextParameters().mapNotNull { parameter ->
                parameter.type.takeIf { type -> type.isTypeclassType(configuration) }?.let { irTypeToModel(it, typeParameterBySymbol) }
            }
        if (prerequisites.size != contextParameters().size) {
            return emptyList()
        }

        val providedTypes = listOf(returnType).providedTypeExpansion(typeParameterBySymbol, configuration).validTypes
        if (associatedOwner == null && !isLegalTopLevelInstanceLocation(providedTypes, classesById)) {
            return emptyList()
        }
        return providedTypes.map { providedType ->
            ResolvedRule(
                rule =
                    InstanceRule(
                        id = directRuleId(
                            prefix = idPrefix,
                            declarationKey = callableId.toString(),
                            providedType = providedType,
                            prerequisiteTypes = prerequisites,
                            typeParameters = typeParameters,
                        ),
                        typeParameters = typeParameters,
                        providedType = providedType,
                        prerequisiteTypes = prerequisites,
                    ),
                reference = RuleReference.DirectFunction(this),
                associatedOwner = associatedOwner,
            )
        }
    }

    private fun IrProperty.toPropertyRules(
        idPrefix: String,
        associatedOwner: ClassId?,
    ): List<ResolvedRule> {
        if (!hasAnnotation(INSTANCE_ANNOTATION_CLASS_ID)) {
            return emptyList()
        }
        if (!irInstanceOwnerContext(this).isIndexableScope) {
            return emptyList()
        }
        val getter = getter ?: return emptyList()
        if (getter.extensionReceiverParameter != null) {
            return emptyList()
        }
        val providedTypes = listOf(backingFieldOrGetterType()).providedTypeExpansion(emptyMap(), configuration).validTypes
        if (associatedOwner == null && !isLegalTopLevelInstanceLocation(providedTypes, classesById)) {
            return emptyList()
        }
        return providedTypes.map { providedType ->
            ResolvedRule(
                rule =
                    InstanceRule(
                        id = directRuleId(
                            prefix = idPrefix,
                            declarationKey = callableId.toString(),
                            providedType = providedType,
                        ),
                        typeParameters = emptyList(),
                        providedType = providedType,
                        prerequisiteTypes = emptyList(),
                    ),
                reference = RuleReference.DirectProperty(this),
                associatedOwner = associatedOwner,
            )
        }
    }

    private fun IrClass.toDerivedRules(
        typeclassId: ClassId,
        subclassesBySuper: Map<String, Set<String>>,
    ): List<ResolvedRule> {
        val targetClassId = classId ?: return emptyList()
        if (!supportsDeriveShape()) {
            pluginContext.reportTypeclassError(
                message = "@Derive is only supported on sealed or final classes and objects",
                diagnosticId = TypeclassDiagnosticIds.CANNOT_DERIVE,
                location = compilerMessageLocation(),
            )
            return emptyList()
        }
        val typeclassInterface = pluginContext.referenceClass(typeclassId)?.owner ?: return emptyList()
        if (!typeclassInterface.hasAnnotation(TYPECLASS_ANNOTATION_CLASS_ID)) {
            return emptyList()
        }
        if (typeclassInterface.typeParameters.size != 1) {
            return emptyList()
        }
        val ruleTypeParameters =
            typeParameters.mapIndexed { index, typeParameter ->
                TcTypeParameter(
                    id = "${targetClassId.asString()}#$index",
                    displayName = typeParameter.name.asString(),
                )
            }
        val typeParameterBySymbol = typeParameters.zip(ruleTypeParameters).associate { (symbol, parameter) ->
            symbol.symbol to parameter
        }
        val targetType =
            TcType.Constructor(
                targetClassId.asString(),
                ruleTypeParameters.map { parameter -> TcType.Variable(parameter.id, parameter.displayName) },
            )
        val shape =
            when {
                kind == ClassKind.ENUM_CLASS -> buildDerivedEnumShape()
                modality == Modality.SEALED -> buildDerivedSumShape(targetType, subclassesBySuper)
                else -> buildDerivedProductShape(typeParameterBySymbol)
            } ?: return emptyList()
        val requiredDeriverInterface =
            when (shape) {
                is DerivedShape.Product -> PRODUCT_TYPECLASS_DERIVER_CLASS_ID
                is DerivedShape.Sum, is DerivedShape.Enum -> TYPECLASS_DERIVER_CLASS_ID
            }
        val deriverCompanion =
            typeclassInterface.findDeriverCompanion(requiredDeriverInterface)
                ?: run {
                    val requiredName = requiredDeriverInterface.shortClassName.asString()
                    val message =
                        if (shape is DerivedShape.Enum) {
                            "${typeclassId.shortClassName.asString()} companion must implement $requiredName; ProductTypeclassDeriver only supports products, not enums"
                        } else if (shape is DerivedShape.Sum) {
                            "${typeclassId.shortClassName.asString()} companion must implement $requiredName; ProductTypeclassDeriver only supports products, not sealed sums"
                        } else {
                            "${typeclassId.shortClassName.asString()} companion must implement $requiredName to derive products"
                        }
                    pluginContext.reportTypeclassError(
                        message = message,
                        diagnosticId = TypeclassDiagnosticIds.CANNOT_DERIVE,
                        location = compilerMessageLocation(),
                    )
                    return emptyList()
                }
        val deriveMethodName =
            when (shape) {
                is DerivedShape.Product -> "deriveProduct"
                is DerivedShape.Sum -> "deriveSum"
                is DerivedShape.Enum -> "deriveEnum"
            }
        if (deriverCompanion.declarations.filterIsInstance<IrSimpleFunction>().none { function -> function.name.asString() == deriveMethodName }) {
            val message =
                if (shape is DerivedShape.Enum) {
                    "${typeclassId.shortClassName.asString()} companion must override deriveEnum to derive enum classes"
                } else {
                    "Typeclass deriver ${deriverCompanion.classIdOrFail.asString()} is missing $deriveMethodName"
                }
            pluginContext.reportTypeclassError(
                message = message,
                diagnosticId = TypeclassDiagnosticIds.CANNOT_DERIVE,
                location = compilerMessageLocation(),
            )
            return emptyList()
        }
        val prerequisiteTypes =
            when (shape) {
                is DerivedShape.Product -> shape.fields.map { field -> typeclassGoal(typeclassId, field.type) }
                is DerivedShape.Sum -> shape.cases.map { case -> typeclassGoal(typeclassId, case.type) }
                is DerivedShape.Enum -> emptyList()
            }
        val providedTypes = expandDerivedProvidedTypes(typeclassId, targetType)
        return providedTypes.map { providedType ->
            ResolvedRule(
                rule =
                    InstanceRule(
                        id =
                            directRuleId(
                                prefix = "derived",
                                declarationKey = "${typeclassId.asString()}:${targetClassId.asString()}",
                                providedType = providedType,
                                prerequisiteTypes = prerequisiteTypes,
                                typeParameters = ruleTypeParameters,
                            ),
                        typeParameters = ruleTypeParameters,
                        providedType = providedType,
                        prerequisiteTypes = prerequisiteTypes,
                        supportsRecursiveResolution = true,
                    ),
                reference =
                    RuleReference.Derived(
                        targetClass = this,
                        deriverCompanion = deriverCompanion,
                        shape = shape,
                        ruleTypeParameters = ruleTypeParameters,
                    ),
                associatedOwner = targetClassId,
            )
        }
    }

    private fun IrClass.toDerivedEquivRules(): List<ResolvedRule> {
        val targetClassId = classId ?: return emptyList()
        if (typeParameters.isNotEmpty()) {
            return emptyList()
        }
        val planner = DirectTransportPlanner(pluginContext)
        return deriveEquivRequests().mapNotNull { request ->
            val otherClass =
                pluginContext.referenceClass(request.otherClassId)?.owner
                    ?: return@mapNotNull null
            if (otherClass.typeParameters.isNotEmpty()) {
                pluginContext.reportTypeclassError(
                    message = "@DeriveEquiv only supports monomorphic classes for now",
                    diagnosticId = TypeclassDiagnosticIds.CANNOT_DERIVE,
                    location = compilerMessageLocation(),
                )
                return@mapNotNull null
            }
            val plans =
                planner.planEquiv(symbol.defaultType, otherClass.symbol.defaultType)
                    ?: run {
                        pluginContext.reportTypeclassError(
                            message =
                                "Cannot derive Equiv between ${targetClassId.asString()} and ${request.otherClassId.asString()}",
                            diagnosticId = TypeclassDiagnosticIds.CANNOT_DERIVE,
                            location = compilerMessageLocation(),
                        )
                        return@mapNotNull null
                    }
            ResolvedRule(
                rule =
                    InstanceRule(
                        id = "derived-equiv:${targetClassId.asString()}:${request.otherClassId.asString()}",
                        typeParameters = emptyList(),
                        providedType =
                            TcType.Constructor(
                                classifierId = EQUIV_CLASS_ID.asString(),
                                arguments =
                                    listOf(
                                        TcType.Constructor(targetClassId.asString(), emptyList()),
                                        TcType.Constructor(request.otherClassId.asString(), emptyList()),
                                    ),
                            ),
                        prerequisiteTypes = emptyList(),
                    ),
                reference =
                    RuleReference.DerivedEquiv(
                        sourceType = symbol.defaultType,
                        targetType = otherClass.symbol.defaultType,
                        forwardPlan = plans.first,
                        backwardPlan = plans.second,
                    ),
                associatedOwner = targetClassId,
            )
        }
    }

    private fun IrClass.toDeriveViaRules(): List<ResolvedRule> {
        val targetClassId = classId ?: return emptyList()
        if (typeParameters.isNotEmpty()) {
            return emptyList()
        }
        val planner = DirectTransportPlanner(pluginContext)
        return deriveViaRequests(pluginContext).mapNotNull { request ->
            if (request.path.isEmpty()) {
                pluginContext.reportTypeclassError(
                    message = "Cannot derive via an empty path",
                    diagnosticId = TypeclassDiagnosticIds.CANNOT_DERIVE,
                    location = compilerMessageLocation(),
                )
                return@mapNotNull null
            }
            val typeclassInterface = pluginContext.referenceClass(request.typeclassId)?.owner ?: return@mapNotNull null
            if (!typeclassInterface.hasAnnotation(TYPECLASS_ANNOTATION_CLASS_ID)) {
                return@mapNotNull null
            }
            if (typeclassInterface.typeParameters.isEmpty()) {
                pluginContext.reportTypeclassError(
                    message = "DeriveVia requires a typeclass with at least one type parameter",
                    diagnosticId = TypeclassDiagnosticIds.CANNOT_DERIVE,
                    location = compilerMessageLocation(),
                )
                return@mapNotNull null
            }
            typeclassInterface.validateDeriveViaTransportability()?.let { message ->
                pluginContext.reportTypeclassError(
                    message = message,
                    diagnosticId = TypeclassDiagnosticIds.CANNOT_DERIVE,
                    location = compilerMessageLocation(),
                )
                return@mapNotNull null
            }
            val resolvedPath =
                planner.resolveViaPath(symbol.defaultType, request.path)
                    .getOrElse { error ->
                        pluginContext.reportTypeclassError(
                            message = error.message ?: "Failed to resolve DeriveVia path",
                            diagnosticId = TypeclassDiagnosticIds.CANNOT_DERIVE,
                            location = compilerMessageLocation(),
                        )
                        return@mapNotNull null
                    }
            val prefixParameters =
                typeclassInterface.typeParameters.dropLast(1).mapIndexed { index, typeParameter ->
                    TcTypeParameter(
                        id = "derive-via:${targetClassId.asString()}:${request.typeclassId.asString()}:$index",
                        displayName = typeParameter.name.asString(),
                    )
                }
            val targetType =
                TcType.Constructor(targetClassId.asString(), emptyList())
            val viaTypeModel =
                (resolvedPath.viaType.classOrNull?.owner?.classId?.let { classId ->
                    TcType.Constructor(classId.asString(), emptyList())
                }) ?: run {
                    pluginContext.reportTypeclassError(
                        message = "DeriveVia terminal type must be a class type",
                        diagnosticId = TypeclassDiagnosticIds.CANNOT_DERIVE,
                        location = compilerMessageLocation(),
                    )
                    return@mapNotNull null
                }
            val providedType =
                TcType.Constructor(
                    classifierId = request.typeclassId.asString(),
                    arguments = prefixParameters.map { parameter -> TcType.Variable(parameter.id, parameter.displayName) } + targetType,
                )
            val prerequisiteType =
                TcType.Constructor(
                    classifierId = request.typeclassId.asString(),
                    arguments = prefixParameters.map { parameter -> TcType.Variable(parameter.id, parameter.displayName) } + viaTypeModel,
                )
            ResolvedRule(
                rule =
                    InstanceRule(
                        id = "derived-via:${request.typeclassId.asString()}:${targetClassId.asString()}:${request.path.joinToString("|") { it.classId.asString() }}",
                        typeParameters = prefixParameters,
                        providedType = providedType,
                        prerequisiteTypes = listOf(prerequisiteType),
                    ),
                reference =
                    RuleReference.DerivedVia(
                        typeclassInterface = typeclassInterface,
                        targetType = symbol.defaultType,
                        viaType = resolvedPath.viaType,
                        forwardPlan = resolvedPath.forwardPlan,
                        backwardPlan = resolvedPath.backwardPlan,
                    ),
                associatedOwner = targetClassId,
            )
        }
    }

    private fun IrClass.buildDerivedProductShape(
        typeParameterBySymbol: Map<org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol, TcTypeParameter>,
    ): DerivedShape.Product? {
        val fields =
            structuralProperties().mapNotNull { property ->
                val getter = property.getter ?: return@mapNotNull null
                val fieldType = irTypeToModel(getter.returnType, typeParameterBySymbol) ?: return@mapNotNull null
                DerivedField(
                    name = property.name.asString(),
                    type = fieldType,
                    getter = getter,
                )
            }
        if (isObject) {
            return DerivedShape.Product(fields = fields, constructor = null)
        }
        val constructor = primaryConstructorOrNull()
        if (constructor == null) {
            pluginContext.reportTypeclassError(
                message =
                    "Cannot derive ${classIdOrFail.asString()} because constructive product derivation requires a primary constructor",
                diagnosticId = TypeclassDiagnosticIds.CANNOT_DERIVE,
                location = compilerMessageLocation(),
            )
            return null
        }
        val constructorParameterNames = constructor.valueParameters.map { parameter -> parameter.name.asString() }
        val fieldNames = fields.map(DerivedField::name)
        if (constructorParameterNames != fieldNames) {
            pluginContext.reportTypeclassError(
                message =
                    "Cannot derive ${classIdOrFail.asString()} because constructive product derivation requires constructor parameters to exactly match stored properties",
                diagnosticId = TypeclassDiagnosticIds.CANNOT_DERIVE,
                location = compilerMessageLocation(),
            )
            return null
        }
        return DerivedShape.Product(fields = fields, constructor = constructor)
    }

    private fun IrClass.buildDerivedSumShape(
        targetType: TcType.Constructor,
        subclassesBySuper: Map<String, Set<String>>,
    ): DerivedShape.Sum? {
        val directSubclasses = subclassesBySuper[classIdOrFail.asString()].orEmpty()
        if (directSubclasses.isEmpty()) {
            return null
        }
        val allowedVariableIds = targetType.referencedVariableIds()
        val cases =
            directSubclasses.mapNotNull { subclassId ->
                val subclass = classesById[subclassId] ?: return@mapNotNull null
                val caseType = subclass.caseTypeForSealedBase(this, targetType) ?: return@mapNotNull null
                if (!caseType.referencedVariableIds().all(allowedVariableIds::contains)) {
                    pluginContext.reportTypeclassError(
                        message =
                            "Cannot derive ${classIdOrFail.asString()} because sealed subclass ${subclass.classIdOrFail.asString()} introduces type parameters that are not quantified by the sealed root",
                        diagnosticId = TypeclassDiagnosticIds.CANNOT_DERIVE,
                        location = subclass.compilerMessageLocation(),
                    )
                    return null
                }
                DerivedCase(
                    name = subclass.name.asString(),
                    klass = subclass,
                    type = caseType,
                )
            }
        if (cases.size != directSubclasses.size) {
            pluginContext.reportTypeclassError(
                message =
                    "Cannot derive ${classIdOrFail.asString()} because one or more sealed subclasses cannot be expressed from the sealed root's type parameters",
                diagnosticId = TypeclassDiagnosticIds.CANNOT_DERIVE,
                location = compilerMessageLocation(),
            )
            return null
        }
        return DerivedShape.Sum(cases)
    }

    private fun IrClass.buildDerivedEnumShape(): DerivedShape.Enum? {
        val entries =
            declarations.filterIsInstance<IrEnumEntry>().map { entry ->
                DerivedEnumEntry(
                    name = entry.name.asString(),
                    entry = entry,
                )
            }
        if (entries.isEmpty()) {
            pluginContext.reportTypeclassError(
                message = "Cannot derive ${classIdOrFail.asString()} because enum derivation requires at least one enum entry",
                diagnosticId = TypeclassDiagnosticIds.CANNOT_DERIVE,
                location = compilerMessageLocation(),
            )
            return null
        }
        return DerivedShape.Enum(entries)
    }

    private fun IrClass.caseTypeForSealedBase(
        sealedBase: IrClass,
        baseTargetType: TcType.Constructor,
    ): TcType? {
        val subclassClassId = classId ?: return null
        val caseTypeParameters =
            typeParameters.mapIndexed { index, typeParameter ->
                TcTypeParameter(
                    id = "${subclassClassId.asString()}#$index",
                    displayName = typeParameter.name.asString(),
                )
            }
        val caseTypeBySymbol = typeParameters.zip(caseTypeParameters).associate { (symbol, parameter) ->
            symbol.symbol to parameter
        }
        val subclassDeclaredType =
            TcType.Constructor(
                subclassClassId.asString(),
                caseTypeParameters.map { parameter -> TcType.Variable(parameter.id, parameter.displayName) },
            )
        val sealedSupertype =
            superTypes.firstOrNull { superType ->
                superType.classOrNull?.owner?.classId == sealedBase.classId
            } ?: return null
        val projectedSupertype = irTypeToModel(sealedSupertype, caseTypeBySymbol) ?: return null
        val bindings =
            unifyTypes(
                left = projectedSupertype,
                right = baseTargetType,
                bindableVariableIds = caseTypeParameters.mapTo(linkedSetOf(), TcTypeParameter::id),
            )
        return when {
            bindings != null -> subclassDeclaredType.substituteType(bindings)
            caseTypeParameters.isEmpty() -> subclassDeclaredType
            else -> null
        }
    }

    private fun IrClass.structuralProperties(): List<IrProperty> {
        return declarations.filterIsInstance<IrProperty>().filter { property ->
            property.getter != null && property.backingField != null
        }
    }

    private fun IrClass.derivedTypeclassIds(): Set<ClassId> {
        val annotation =
            annotations.firstOrNull { constructorCall ->
                constructorCall.symbol.owner.parentAsClass.classId == DERIVE_ANNOTATION_CLASS_ID
            } ?: return emptySet()
        val values = annotation.getValueArgument(0) as? IrVararg ?: return emptySet()
        return values.elements.mapNotNullTo(linkedSetOf()) { element ->
            val expression =
                when (element) {
                    is IrSpreadElement -> element.expression
                    is IrExpression -> element
                    else -> null
                } ?: return@mapNotNullTo null
            val classReference = expression as? IrClassReference ?: return@mapNotNullTo null
            val classId = classReference.classType.classOrNull?.owner?.classId ?: return@mapNotNullTo null
            val typeclassClass = pluginContext.referenceClass(classId)?.owner ?: return@mapNotNullTo null
            classId.takeIf { typeclassClass.hasAnnotation(TYPECLASS_ANNOTATION_CLASS_ID) }
        }
    }

    private fun IrClass.findDeriverCompanion(requiredInterface: ClassId): IrClass? =
        declarations.filterIsInstance<IrClass>().singleOrNull { declaration ->
            declaration.isCompanion &&
                declaration.implementsInterface(requiredInterface, linkedSetOf())
        }

    private fun IrClass.supportedDerivedTypeclassIds(): Set<ClassId> =
        buildSet {
            addAll(generatedDerivedTypeclassIds())
            addAll(derivedTypeclassIds())
        }

    private fun IrClass.generatedDerivedTypeclassIds(): Set<ClassId> =
        annotations
            .flatMap { annotation -> annotation.flattenGeneratedInstanceAnnotations() }
            .mapNotNullTo(linkedSetOf()) { annotation ->
                val typeclassId = (annotation.getValueArgument(0) as? IrConst)?.value as? String
                val targetId = (annotation.getValueArgument(1) as? IrConst)?.value as? String
                val kind = (annotation.getValueArgument(2) as? IrConst)?.value as? String
                typeclassId
                    ?.takeIf { kind == "derive" && (targetId == null || targetId == classIdOrFail.asString()) }
                    ?.let(ClassId::fromString)
            }

    private fun IrConstructorCall.flattenGeneratedInstanceAnnotations(): List<IrConstructorCall> =
        when (symbol.owner.parentAsClass.classId) {
            GENERATED_INSTANCE_ANNOTATION_CLASS_ID -> listOf(this)
            GENERATED_INSTANCE_ANNOTATION_CONTAINER_CLASS_ID ->
                ((getValueArgument(0) as? IrVararg)?.elements.orEmpty())
                    .mapNotNull { element -> element as? IrConstructorCall }
            else -> emptyList()
        }

    private fun IrClass.recordGeneratedDerivedTypeclassMetadata(
        directTypeclassIds: Set<ClassId>,
    ) {
        val annotationClass = pluginContext.referenceClass(GENERATED_INSTANCE_ANNOTATION_CLASS_ID)?.owner ?: return
        val annotationConstructor = annotationClass.primaryConstructorOrNull() ?: return
        val existingIds = generatedDerivedTypeclassIds().mapTo(linkedSetOf(), ClassId::asString)
        val builder = DeclarationIrBuilder(pluginContext, symbol, startOffset, endOffset)
        directTypeclassIds
            .flatMapTo(linkedSetOf()) { typeclassId ->
                expandDerivedTypeclassIds(
                    typeclassId = typeclassId,
                    previousWereTypeclass = true,
                    visited = linkedSetOf(),
                )
            }.filterNot { typeclassId -> typeclassId.asString() in existingIds }
            .forEach { typeclassId ->
                annotations +=
                    builder
                        .irCallConstructor(annotationConstructor.symbol, emptyList())
                        .apply {
                            putValueArgument(0, builder.irString(typeclassId.asString()))
                            putValueArgument(1, builder.irString(classIdOrFail.asString()))
                            putValueArgument(2, builder.irString("derive"))
                        }
            }
    }

    private fun typeclassGoal(
        typeclassId: ClassId,
        targetType: TcType,
    ): TcType = TcType.Constructor(typeclassId.asString(), listOf(targetType))

    private fun expandDerivedProvidedTypes(
        typeclassId: ClassId,
        targetType: TcType,
    ): List<TcType> =
        expandDerivedTypeclassIds(
            typeclassId = typeclassId,
            previousWereTypeclass = true,
            visited = linkedSetOf(),
        ).map { inheritedTypeclassId ->
            typeclassGoal(inheritedTypeclassId, targetType)
        }

    private fun expandDerivedTypeclassIds(
        typeclassId: ClassId,
        previousWereTypeclass: Boolean,
        visited: MutableSet<String>,
    ): Set<ClassId> {
        val visitKey = typeclassId.asString()
        if (!visited.add(visitKey)) {
            return emptySet()
        }
        val typeclassInterface = pluginContext.referenceClass(typeclassId)?.owner ?: return emptySet()
        val currentIsTypeclass = typeclassInterface.hasAnnotation(TYPECLASS_ANNOTATION_CLASS_ID)
        val expanded = linkedSetOf<ClassId>()
        if (currentIsTypeclass && previousWereTypeclass) {
            expanded += typeclassId
        }
        val nextPreviousWereTypeclass = previousWereTypeclass && currentIsTypeclass
        typeclassInterface.superTypes.forEach { superType ->
            val superTypeId = superType.classOrNull?.owner?.classId ?: return@forEach
            expanded +=
                expandDerivedTypeclassIds(
                    typeclassId = superTypeId,
                    previousWereTypeclass = nextPreviousWereTypeclass,
                    visited = visited,
                )
        }
        return expanded
    }
}

private fun builtinKClassRule(): InstanceRule {
    val parameter = TcTypeParameter(id = "builtin:kclass:T", displayName = "T")
    return InstanceRule(
        id = "builtin:kclass",
        typeParameters = listOf(parameter),
        providedType =
            TcType.Constructor(
                classifierId = KCLASS_CLASS_ID.asString(),
                arguments = listOf(TcType.Variable(parameter.id, parameter.displayName)),
            ),
        prerequisiteTypes = emptyList(),
    )
}

private fun builtinSameRule(): InstanceRule {
    val parameter = TcTypeParameter(id = "builtin:same:T", displayName = "T")
    return InstanceRule(
        id = "builtin:same",
        typeParameters = listOf(parameter),
        providedType =
            TcType.Constructor(
                classifierId = SAME_CLASS_ID.asString(),
                arguments =
                    listOf(
                        TcType.Variable(parameter.id, parameter.displayName),
                        TcType.Variable(parameter.id, parameter.displayName),
                    ),
            ),
        prerequisiteTypes = emptyList(),
    )
}

private fun builtinNotSameRule(): InstanceRule {
    val left = TcTypeParameter(id = "builtin:notsame:A", displayName = "A")
    val right = TcTypeParameter(id = "builtin:notsame:B", displayName = "B")
    return InstanceRule(
        id = "builtin:notsame",
        typeParameters = listOf(left, right),
        providedType =
            TcType.Constructor(
                classifierId = NOT_SAME_CLASS_ID.asString(),
                arguments =
                    listOf(
                        TcType.Variable(left.id, left.displayName),
                        TcType.Variable(right.id, right.displayName),
                    ),
            ),
        prerequisiteTypes = emptyList(),
    )
}

private fun builtinSubtypeRule(): InstanceRule {
    val sub = TcTypeParameter(id = "builtin:subtype:Sub", displayName = "Sub")
    val sup = TcTypeParameter(id = "builtin:subtype:Super", displayName = "Super")
    return InstanceRule(
        id = "builtin:subtype",
        typeParameters = listOf(sub, sup),
        providedType =
            TcType.Constructor(
                classifierId = SUBTYPE_CLASS_ID.asString(),
                arguments =
                    listOf(
                        TcType.Variable(sub.id, sub.displayName),
                        TcType.Variable(sup.id, sup.displayName),
                    ),
            ),
        prerequisiteTypes = emptyList(),
    )
}

private fun builtinStrictSubtypeRule(): InstanceRule {
    val sub = TcTypeParameter(id = "builtin:strict-subtype:Sub", displayName = "Sub")
    val sup = TcTypeParameter(id = "builtin:strict-subtype:Super", displayName = "Super")
    val subType = TcType.Variable(sub.id, sub.displayName)
    val superType = TcType.Variable(sup.id, sup.displayName)
    return InstanceRule(
        id = "builtin:strict-subtype",
        typeParameters = listOf(sub, sup),
        providedType =
            TcType.Constructor(
                classifierId = STRICT_SUBTYPE_CLASS_ID.asString(),
                arguments = listOf(subType, superType),
            ),
        prerequisiteTypes =
            listOf(
                TcType.Constructor(
                    classifierId = SUBTYPE_CLASS_ID.asString(),
                    arguments = listOf(subType, superType),
                ),
                TcType.Constructor(
                    classifierId = NOT_SAME_CLASS_ID.asString(),
                    arguments = listOf(subType, superType),
                ),
            ),
    )
}

private fun builtinNullableRule(): InstanceRule {
    val parameter = TcTypeParameter(id = "builtin:nullable:T", displayName = "T")
    return InstanceRule(
        id = "builtin:nullable",
        typeParameters = listOf(parameter),
        providedType =
            TcType.Constructor(
                classifierId = NULLABLE_CLASS_ID.asString(),
                arguments = listOf(TcType.Variable(parameter.id, parameter.displayName)),
            ),
        prerequisiteTypes = emptyList(),
    )
}

private fun builtinNotNullableRule(): InstanceRule {
    val parameter = TcTypeParameter(id = "builtin:not-nullable:T", displayName = "T")
    return InstanceRule(
        id = "builtin:not-nullable",
        typeParameters = listOf(parameter),
        providedType =
            TcType.Constructor(
                classifierId = NOT_NULLABLE_CLASS_ID.asString(),
                arguments = listOf(TcType.Variable(parameter.id, parameter.displayName)),
            ),
        prerequisiteTypes = emptyList(),
    )
}

private fun builtinIsTypeclassInstanceRule(): InstanceRule {
    val parameter = TcTypeParameter(id = "builtin:is-typeclass-instance:TC", displayName = "TC")
    return InstanceRule(
        id = "builtin:is-typeclass-instance",
        typeParameters = listOf(parameter),
        providedType =
            TcType.Constructor(
                classifierId = IS_TYPECLASS_INSTANCE_CLASS_ID.asString(),
                arguments = listOf(TcType.Variable(parameter.id, parameter.displayName)),
            ),
        prerequisiteTypes = emptyList(),
    )
}

private fun builtinKnownTypeRule(): InstanceRule {
    val parameter = TcTypeParameter(id = "builtin:known-type:T", displayName = "T")
    return InstanceRule(
        id = "builtin:known-type",
        typeParameters = listOf(parameter),
        providedType =
            TcType.Constructor(
                classifierId = KNOWN_TYPE_CLASS_ID.asString(),
                arguments = listOf(TcType.Variable(parameter.id, parameter.displayName)),
            ),
        prerequisiteTypes = emptyList(),
    )
}

private fun builtinTypeIdRule(): InstanceRule {
    val parameter = TcTypeParameter(id = "builtin:type-id:T", displayName = "T")
    return InstanceRule(
        id = "builtin:type-id",
        typeParameters = listOf(parameter),
        providedType =
            TcType.Constructor(
                classifierId = TYPE_ID_CLASS_ID.asString(),
                arguments = listOf(TcType.Variable(parameter.id, parameter.displayName)),
            ),
        prerequisiteTypes = emptyList(),
    )
}

private fun builtinSameTypeConstructorRule(): InstanceRule {
    val left = TcTypeParameter(id = "builtin:same-type-constructor:A", displayName = "A")
    val right = TcTypeParameter(id = "builtin:same-type-constructor:B", displayName = "B")
    return InstanceRule(
        id = "builtin:same-type-constructor",
        typeParameters = listOf(left, right),
        providedType =
            TcType.Constructor(
                classifierId = SAME_TYPE_CONSTRUCTOR_CLASS_ID.asString(),
                arguments =
                    listOf(
                        TcType.Variable(left.id, left.displayName),
                        TcType.Variable(right.id, right.displayName),
                    ),
            ),
        prerequisiteTypes = emptyList(),
    )
}

private fun builtinKSerializerRule(): InstanceRule {
    val parameter = TcTypeParameter(id = "builtin:kserializer:T", displayName = "T")
    return InstanceRule(
        id = "builtin:kserializer",
        typeParameters = listOf(parameter),
        providedType =
            TcType.Constructor(
                classifierId = KSERIALIZER_CLASS_ID.asString(),
                arguments = listOf(TcType.Variable(parameter.id, parameter.displayName)),
            ),
        prerequisiteTypes = emptyList(),
    )
}

private fun supportsBuiltinKSerializerGoal(
    goal: TcType,
    pluginContext: IrPluginContext,
    canMaterializeVariable: (String) -> Boolean = { true },
): Boolean {
    if (!supportsBuiltinKSerializerShape(goal, canMaterializeVariable)) {
        return false
    }
    val constructor = goal as? TcType.Constructor ?: return true
    val targetType = constructor.arguments.single()
    return isPotentiallySerializableType(
        type = targetType,
        pluginContext = pluginContext,
        visiting = linkedSetOf(),
    )
}

private fun supportsBuiltinNullableGoal(goal: TcType): Boolean {
    val constructor = goal as? TcType.Constructor ?: return true
    if (constructor.classifierId != NULLABLE_CLASS_ID.asString()) {
        return true
    }
    val targetType = constructor.arguments.singleOrNull() ?: return false
    return targetType.isProvablyNullable()
}

private fun supportsBuiltinNotNullableGoal(goal: TcType): Boolean {
    val constructor = goal as? TcType.Constructor ?: return true
    if (constructor.classifierId != NOT_NULLABLE_CLASS_ID.asString()) {
        return true
    }
    val targetType = constructor.arguments.singleOrNull() ?: return false
    return targetType.isProvablyNotNullable()
}

private fun isPotentiallySerializableType(
    type: TcType,
    pluginContext: IrPluginContext,
    visiting: MutableSet<String>,
): Boolean {
    return when (type) {
        TcType.StarProjection -> true
        is TcType.Projected -> isPotentiallySerializableType(type.type, pluginContext, visiting)
        is TcType.Variable -> true

        is TcType.Constructor -> {
            val visitKey = type.normalizedKey()
            if (!visiting.add(visitKey)) {
                return true
            }
            if (type.classifierId in BUILTIN_SERIALIZABLE_CLASSIFIER_IDS) {
                return type.arguments.all { argument ->
                    isPotentiallySerializableType(argument, pluginContext, visiting)
                }
            }
            val classId = runCatching { ClassId.fromString(type.classifierId) }.getOrNull() ?: return false
            val klass = pluginContext.referenceClass(classId)?.owner ?: return false
            klass.hasAnnotation(SERIALIZABLE_ANNOTATION_CLASS_ID) &&
                type.arguments.all { argument ->
                    isPotentiallySerializableType(argument, pluginContext, visiting)
                }
        }
    }
}

private fun canProveSubtype(
    subType: IrType,
    superType: IrType,
    pluginContext: IrPluginContext,
): Boolean = subType.isSubtypeOf(superType, JvmIrTypeSystemContext(pluginContext.irBuiltIns))

private fun canProveNullable(
    targetType: IrType,
    pluginContext: IrPluginContext,
): Boolean = pluginContext.irBuiltIns.nothingNType.isSubtypeOf(targetType, JvmIrTypeSystemContext(pluginContext.irBuiltIns))

private fun canProveNotNullable(
    targetType: IrType,
    pluginContext: IrPluginContext,
): Boolean = targetType.isSubtypeOf(pluginContext.irBuiltIns.anyType, JvmIrTypeSystemContext(pluginContext.irBuiltIns))

private fun canMaterializeTypeIdViaTypeOf(targetType: IrType): Boolean {
    val simpleType = targetType as? IrSimpleType ?: return false
    return when (val classifier = simpleType.classifier) {
        is org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol -> classifier.owner.isReified
        is org.jetbrains.kotlin.ir.symbols.IrClassSymbol ->
            simpleType.arguments.all { argument ->
                when (argument) {
                    is IrStarProjection -> true
                    is org.jetbrains.kotlin.ir.types.IrTypeProjection -> canMaterializeTypeIdViaTypeOf(argument.type)
                    is IrType -> canMaterializeTypeIdViaTypeOf(argument)
                }
            }

        else -> false
    }
}

private fun IrPluginContext.supportsTypeclassClassifierId(
    classifierId: String,
    configuration: TypeclassConfiguration,
): Boolean {
    val classId = runCatching { ClassId.fromString(classifierId) }.getOrNull() ?: return false
    if (classId.isLocal) {
        return false
    }
    if (configuration.isBuiltinTypeclass(classId)) {
        return true
    }
    val klass = referenceClass(classId)?.owner ?: return false
    return klass.hasAnnotation(TYPECLASS_ANNOTATION_CLASS_ID)
}

private data class ResolvedRule(
    val rule: InstanceRule,
    val reference: RuleReference,
    val associatedOwner: ClassId?,
)

private sealed interface RuleReference {
    data class DirectFunction(
        val function: IrSimpleFunction,
    ) : RuleReference

    data class LookupFunction(
        val callableId: CallableId,
        val shape: LookupFunctionShape,
    ) : RuleReference

    data class DirectProperty(
        val property: IrProperty,
    ) : RuleReference

    data class LookupProperty(
        val callableId: CallableId,
    ) : RuleReference

    data class DirectObject(
        val klass: IrClass,
    ) : RuleReference

    data class LookupObject(
        val classId: ClassId,
    ) : RuleReference

    data object BuiltinSame : RuleReference

    data object BuiltinNotSame : RuleReference

    data object BuiltinSubtype : RuleReference

    data object BuiltinStrictSubtype : RuleReference

    data object BuiltinNullable : RuleReference

    data object BuiltinNotNullable : RuleReference

    data object BuiltinIsTypeclassInstance : RuleReference

    data object BuiltinKnownType : RuleReference

    data object BuiltinTypeId : RuleReference

    data object BuiltinSameTypeConstructor : RuleReference

    data object BuiltinKClass : RuleReference

    data object BuiltinKSerializer : RuleReference

    data class Derived(
        val targetClass: IrClass,
        val deriverCompanion: IrClass,
        val shape: DerivedShape,
        val ruleTypeParameters: List<TcTypeParameter>,
    ) : RuleReference

    data class DerivedEquiv(
        val sourceType: IrType,
        val targetType: IrType,
        val forwardPlan: TransportPlan,
        val backwardPlan: TransportPlan,
    ) : RuleReference

    data class DerivedVia(
        val typeclassInterface: IrClass,
        val targetType: IrType,
        val viaType: IrType,
        val forwardPlan: TransportPlan,
        val backwardPlan: TransportPlan,
    ) : RuleReference
}

private sealed interface DerivedShape {
    data class Product(
        val fields: List<DerivedField>,
        val constructor: IrConstructor?,
    ) : DerivedShape

    data class Sum(
        val cases: List<DerivedCase>,
    ) : DerivedShape

    data class Enum(
        val entries: List<DerivedEnumEntry>,
    ) : DerivedShape
}

private data class DerivedField(
    val name: String,
    val type: TcType,
    val getter: IrSimpleFunction,
)

private data class DerivedCase(
    val name: String,
    val klass: IrClass,
    val type: TcType,
)

private data class DerivedEnumEntry(
    val name: String,
    val entry: IrEnumEntry,
)

private data class WrapperResolutionShape(
    val dispatchReceiver: Boolean,
    val extensionReceiverType: String?,
    val typeParameterCount: Int,
    val contextParameterTypes: List<String>,
    val regularParameterTypes: List<String>,
)

private data class VisibleTypeParameters(
    val bySymbol: Map<org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol, TcTypeParameter>,
    val byId: Map<String, org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol>,
) {
    fun canMaterializeRuntimeType(variableId: String): Boolean =
        byId[variableId]?.owner?.isReified == true
}

private data class LocalTypeclassContext(
    val expression: IrBuilderWithScope.() -> IrExpression,
    val providedType: TcType,
)

private data class RecursiveDerivedResolver(
    val cache: org.jetbrains.kotlin.ir.declarations.IrVariable,
    val instanceCell: org.jetbrains.kotlin.ir.declarations.IrVariable,
    val expectedType: IrType,
)

private data class NormalizedCallArguments(
    val dispatchReceiver: IrExpression?,
    val extensionReceiver: IrExpression?,
    val valueArguments: List<IrExpression?>,
)

private data class InferredOriginalTypeArguments(
    val callTypeArguments: List<IrType>,
    val substitutionBySymbol: Map<org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol, IrType>,
)

private data class ExtractedExplicitArguments(
    val nonTypeclassArguments: List<ExplicitArgument>,
    val preservedTypeclassArguments: Map<Int, IrExpression>,
)

private sealed interface ExplicitArgument {
    data object Omitted : ExplicitArgument

    data class PassThrough(
        val expression: IrExpression,
    ) : ExplicitArgument
}

private fun IrType.isTypeclassType(configuration: TypeclassConfiguration): Boolean {
    val classId = classOrNull?.owner?.classId
    return when {
        classId != null && configuration.isBuiltinTypeclass(classId) -> true
        else -> classOrNull?.owner?.hasAnnotation(TYPECLASS_ANNOTATION_CLASS_ID) == true
    }
}

private fun IrSimpleFunction.requiresSyntheticTypeclassResolution(
    call: IrCall,
    configuration: TypeclassConfiguration,
): Boolean {
    val substitutionBySymbol =
        typeParameters.mapIndexedNotNull { index, typeParameter ->
            call.getTypeArgument(index)?.let { argumentType ->
                typeParameter.symbol to argumentType
            }
        }.toMap()
    val parameters = regularAndContextParameters()
    val mappedOriginalIndices =
        mapCurrentArgumentsToOriginalParameters(
            originalParameters = parameters,
            currentArguments = call.valueArguments(),
            typeArgumentMap = substitutionBySymbol,
            visibleTypeParameters = null,
            configuration = configuration,
        ).mapTo(linkedSetOf()) { (_, originalIndex) -> originalIndex }
    return parameters.anyIndexed { parameterIndex, parameter ->
        val substitutedType = parameter.type.substitute(substitutionBySymbol)
        parameter.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Context &&
            substitutedType.isTypeclassType(configuration) &&
            parameterIndex !in mappedOriginalIndices
    }
}

private inline fun <T> Iterable<T>.anyIndexed(predicate: (index: Int, T) -> Boolean): Boolean {
    var index = 0
    for (element in this) {
        if (predicate(index, element)) {
            return true
        }
        index += 1
    }
    return false
}

private fun IrClass.isInstanceObject(): Boolean =
    hasAnnotation(INSTANCE_ANNOTATION_CLASS_ID) && isObject

private fun IrProperty.backingFieldOrGetterType(): IrType =
    backingField?.type ?: getter?.returnType ?: error("Property $name has neither backing field nor getter type")

private fun IrFunction.contextParameters(): List<IrValueParameter> =
    parameters.filter { it.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Context }

private fun IrFunction.regularAndContextParameters(): List<IrValueParameter> =
    parameters.filter {
        (it.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Context ||
            it.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Regular) &&
            !it.isTypeclassWrapperMarkerParameter()
    }

private fun IrFunction.valueArgumentIndex(
    parameter: IrValueParameter,
    fallbackIndex: Int,
): Int =
    valueParameters.indexOfFirst { candidate ->
        candidate.symbol == parameter.symbol
    }.takeIf { index -> index >= 0 } ?: fallbackIndex

private fun IrClass.primaryConstructorSymbol() =
    primaryConstructorOrNull()?.symbol
        ?: error("Could not resolve a primary constructor for ${classIdOrFail}")

private fun IrClass.primaryConstructorOrNull(): IrConstructor? =
    declarations.filterIsInstance<IrConstructor>()
        .singleOrNull { constructor -> constructor.isPrimary }
        ?: declarations.filterIsInstance<IrConstructor>().singleOrNull()

internal fun IrClass.implementsInterface(
    targetInterface: ClassId,
    visited: MutableSet<String>,
): Boolean {
    val currentClassId = classIdOrFail.asString()
    if (!visited.add(currentClassId)) {
        return false
    }
    if (classId == targetInterface) {
        return true
    }
    return superTypes.any { superType ->
        val superOwner = superType.classOrNull?.owner ?: return@any false
        val superClassId = superOwner.classId ?: return@any false
        if (superClassId == targetInterface) {
            true
        } else {
            superOwner.implementsInterface(targetInterface, visited)
        }
    }
}

private fun IrClass.renderClassName(): String = classId?.asFqNameString() ?: name.asString()

private fun IrClass.supportsDeriveShape(): Boolean =
    when {
        isObject -> true
        modality == Modality.SEALED -> true
        modality == Modality.FINAL && kind != ClassKind.INTERFACE -> true
        else -> false
    }

private fun lookupFunctionShape(
    function: IrSimpleFunction,
    dropTypeclassContexts: Boolean,
    configuration: TypeclassConfiguration,
): LookupFunctionShape {
    val placeholderParameters =
        visibleTypeParametersForShape(function).associateBy(TcTypeParameter::id)
    val bySymbol =
        visibleTypeParameterSymbols(function, listOf(function)).zip(placeholderParameters.values).associate { (symbol, parameter) ->
            symbol to parameter
        }
    val contextTypes =
        function.contextParameters()
            .filterNot { dropTypeclassContexts && it.type.isTypeclassType(configuration) }
            .map { parameter ->
                irTypeToModel(parameter.type, bySymbol)
                    ?: error("Unsupported signature type ${parameter.type} in ${function.callableId}")
            }
    val regularTypes =
        function.parameters.filter { it.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Regular }
            .filterNot(IrValueParameter::isTypeclassWrapperMarkerParameter)
            .map { parameter ->
                irTypeToModel(parameter.type, bySymbol)
                    ?: error("Unsupported signature type ${parameter.type} in ${function.callableId}")
            }
    val extensionType = function.extensionReceiverParameter?.type?.let { type -> irTypeToModel(type, bySymbol) }
    return LookupFunctionShape(
        dispatchReceiver = function.dispatchReceiverParameter != null,
        extensionReceiverType = extensionType,
        typeParameterCount = function.visibleSignatureTypeParameterCount(dropTypeclassContexts, configuration),
        contextParameterTypes = contextTypes,
        regularParameterTypes = regularTypes,
    )
}

private fun wrapperResolutionShape(
    function: IrSimpleFunction,
    dropTypeclassContexts: Boolean,
    configuration: TypeclassConfiguration,
): WrapperResolutionShape {
    val contextTypes =
        function.contextParameters()
            .filterNot { dropTypeclassContexts && it.type.isTypeclassType(configuration) }
            .map { parameter -> parameter.type.render() }
    val regularTypes =
        function.parameters.filter { it.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Regular }
            .filterNot(IrValueParameter::isTypeclassWrapperMarkerParameter)
            .map { parameter -> parameter.type.render() }
    val extensionType = function.extensionReceiverParameter?.type?.render()
    return WrapperResolutionShape(
        dispatchReceiver = function.dispatchReceiverParameter != null,
        extensionReceiverType = extensionType,
        typeParameterCount = function.visibleSignatureTypeParameterCount(dropTypeclassContexts, configuration),
        contextParameterTypes = contextTypes,
        regularParameterTypes = regularTypes,
    )
}

private fun visibleTypeParameters(
    currentDeclaration: IrDeclarationBase,
    enclosingFunctions: List<IrFunction>,
): VisibleTypeParameters {
    val symbols = visibleTypeParameterSymbols(currentDeclaration, enclosingFunctions)
    val parameters =
        symbols.map { symbol ->
            TcTypeParameter(
                id = visibleTypeParameterId(symbol),
                displayName = symbol.owner.name.asString(),
            )
        }
    return VisibleTypeParameters(
        bySymbol = symbols.zip(parameters).toMap(),
        byId = parameters.zip(symbols).associate { (parameter, symbol) -> parameter.id to symbol },
    )
}

private fun visibleTypeParametersForShape(function: IrFunction): List<TcTypeParameter> =
    visibleTypeParameterSymbols(function, listOf(function)).mapIndexed { index, _ ->
        TcTypeParameter(id = "P$index", displayName = "P$index")
    }

private fun visibleTypeParameterSymbols(
    currentDeclaration: IrDeclarationBase,
    enclosingFunctions: List<IrFunction>,
): List<org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol> {
    val result = mutableListOf<org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol>()
    val parentWithTypeParameters =
        enclosingFunctions.firstOrNull()?.parent ?: currentDeclaration.parent
    collectParentTypeParameters(parentWithTypeParameters, result)
    enclosingFunctions.forEach { function ->
        function.typeParameters.mapTo(result) { it.symbol }
    }
    return result
}

private fun collectParentTypeParameters(
    parent: org.jetbrains.kotlin.ir.declarations.IrDeclarationParent?,
    sink: MutableList<org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol>,
) {
    when (parent) {
        is IrClass -> {
            collectParentTypeParameters(parent.parent, sink)
            parent.typeParameters.mapTo(sink) { it.symbol }
        }

        is IrFunction -> {
            collectParentTypeParameters(parent.parent, sink)
            parent.typeParameters.mapTo(sink) { it.symbol }
        }
    }
}

private fun visibleTypeParameterId(symbol: org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol): String =
    when (val parent = symbol.owner.parent) {
        is IrClass -> "class:${parent.classIdOrFail.asString()}:${symbol.owner.name}"
        is IrSimpleFunction -> ruleTypeParameterId(parent, symbol.owner)
        else -> "type:${symbol.owner.name}:${symbol.hashCode()}"
    }

private fun IrDeclarationBase.enclosingFunctions(): List<IrFunction> {
    val result = ArrayDeque<IrFunction>()
    var current: IrDeclarationBase? = this
    while (current != null) {
        if (current is IrFunction) {
            result.addFirst(current)
        }
        current = current.parent as? IrDeclarationBase
    }
    return result.toList()
}

private fun typeParameterOwnerId(function: IrSimpleFunction): String {
    val shape = wrapperResolutionShape(function, dropTypeclassContexts = false, configuration = TypeclassConfiguration())
    return buildString {
        append(function.safeCallableIdentity())
        append("#dispatch=")
        append(shape.dispatchReceiver)
        append("#ext=")
        append(shape.extensionReceiverType ?: "-")
        append("#ctx=")
        append(shape.contextParameterTypes.joinToString(","))
        append("#regular=")
        append(shape.regularParameterTypes.joinToString(","))
        append("#tp=")
        append(shape.typeParameterCount)
    }
}

private fun IrSimpleFunction.safeCallableIdentity(): String =
    runCatching { callableId.toString() }.getOrElse {
        buildString {
            append("local:")
            append(name.asString())
            append('@')
            append(startOffset)
            append(':')
            append(endOffset)
            append("#parent=")
            append(parent.localDeclarationIdentity())
        }
    }

private fun IrSimpleFunction.safeCallableIdOrNull(): CallableId? =
    runCatching { callableId }.getOrNull()

private fun org.jetbrains.kotlin.ir.declarations.IrDeclarationParent.localDeclarationIdentity(): String =
    when (this) {
        is IrSimpleFunction ->
            buildString {
                append("fun:")
                append(name.asString())
                append('@')
                append(startOffset)
                append(':')
                append(endOffset)
                append("->")
                append(parent.localDeclarationIdentity())
            }
        is IrClass -> "class:${name.asString()}"
        is IrFile -> "file:${fileEntry.name}"
        else -> this::class.simpleName ?: "unknown-parent"
    }

private fun ruleTypeParameterId(
    function: IrSimpleFunction,
    typeParameter: IrTypeParameter,
): String = "function:${typeParameterOwnerId(function)}:${typeParameter.name}"

private fun typeParametersBySymbol(
    function: IrSimpleFunction,
    parameters: List<TcTypeParameter>,
): Map<org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol, TcTypeParameter> =
    function.typeParameters.zip(parameters).associate { (typeParameter, parameter) ->
        typeParameter.symbol to parameter
    }

private fun collectLocalContexts(
    enclosingFunctions: List<IrFunction>,
    visible: VisibleTypeParameters,
    configuration: TypeclassConfiguration,
): List<LocalTypeclassContext> {
    return buildList {
        enclosingFunctions.asReversed().forEach { function ->
            function.extensionReceiverParameter
                ?.let { parameter ->
                    parameter.type.localEvidenceTypes(visible.bySymbol, configuration).forEach { providedType ->
                        add(
                            LocalTypeclassContext(
                                expression = { irGet(parameter) },
                                providedType = providedType,
                            ),
                        )
                    }
                }
            function.contextParameters()
                .forEach { parameter ->
                    parameter.type.localEvidenceTypes(visible.bySymbol, configuration).forEach { providedType ->
                        add(
                            LocalTypeclassContext(
                                expression = { irGet(parameter) },
                                providedType = providedType,
                            ),
                        )
                    }
                }
        }
    }
}

private fun irTypeToModel(
    type: IrType,
    typeParameterBySymbol: Map<org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol, TcTypeParameter>,
): TcType? {
    val simpleType = type as? IrSimpleType ?: return null
    return when (val classifier = simpleType.classifier) {
        is org.jetbrains.kotlin.ir.symbols.IrClassSymbol -> {
            val classifierId = classifier.owner.classId?.asString() ?: return null
            val arguments =
                simpleType.arguments.map { argument ->
                    irTypeArgumentToModel(argument, typeParameterBySymbol) ?: return null
                }
            TcType.Constructor(classifierId, arguments, isNullable = type.isNullable())
        }

        is org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol -> {
            val parameter =
                typeParameterBySymbol[classifier]
                    ?: typeParameterBySymbol.values.singleOrNull { candidate ->
                        candidate.displayName == classifier.owner.name.asString()
                    }
                    ?: return null
            TcType.Variable(
                parameter.id,
                parameter.displayName,
                isNullable = type.render().endsWith("?"),
            )
        }

        else -> null
    }
}

private fun irTypeArgumentToModel(
    argument: org.jetbrains.kotlin.ir.types.IrTypeArgument,
    typeParameterBySymbol: Map<org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol, TcTypeParameter>,
): TcType? =
    when (argument) {
        is IrStarProjection -> TcType.StarProjection
        is org.jetbrains.kotlin.ir.types.IrTypeProjection -> {
            val nested = irTypeToModel(argument.type, typeParameterBySymbol) ?: return null
            when (argument.variance) {
                Variance.INVARIANT -> nested
                Variance.IN_VARIANCE -> TcType.Projected(Variance.IN_VARIANCE, nested)
                Variance.OUT_VARIANCE -> TcType.Projected(Variance.OUT_VARIANCE, nested)
            }
        }

        is IrType -> irTypeToModel(argument, typeParameterBySymbol)
    }

private fun Iterable<IrType>.providedTypeExpansion(
    typeParameterBySymbol: Map<org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol, TcTypeParameter>,
    configuration: TypeclassConfiguration,
): ProvidedTypeExpansion {
    val validTypes = linkedMapOf<String, TcType>()
    val invalidTypes = linkedMapOf<String, TcType>()
    for (type in this) {
        val expansion =
            type.providedTypeExpansion(
                typeParameterBySymbol = typeParameterBySymbol,
                configuration = configuration,
                previousWereTypeclass = true,
                visited = emptySet(),
            )
        expansion.validTypes.forEach { candidate ->
            validTypes.putIfAbsent(candidate.render(), candidate)
        }
        expansion.invalidTypes.forEach { candidate ->
            invalidTypes.putIfAbsent(candidate.render(), candidate)
        }
    }
    return ProvidedTypeExpansion(
        validTypes = validTypes.values.toList(),
        invalidTypes = invalidTypes.values.toList(),
    )
}

private fun IrType.providedTypeExpansion(
    typeParameterBySymbol: Map<org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol, TcTypeParameter>,
    configuration: TypeclassConfiguration,
    previousWereTypeclass: Boolean,
    visited: Set<String>,
): ProvidedTypeExpansion {
    val simpleType = this as? IrSimpleType ?: return ProvidedTypeExpansion(emptyList(), emptyList())
    val currentType = irTypeToModel(this, typeParameterBySymbol) ?: return ProvidedTypeExpansion(emptyList(), emptyList())
    val visitKey = currentType.render()
    if (visitKey in visited) {
        return ProvidedTypeExpansion(emptyList(), emptyList())
    }

    val classSymbol = simpleType.classOrNull ?: return ProvidedTypeExpansion(emptyList(), emptyList())
    val currentIsTypeclass = isTypeclassType(configuration)
    val validTypes = linkedMapOf<String, TcType>()
    val invalidTypes = linkedMapOf<String, TcType>()
    if (currentIsTypeclass) {
        if (previousWereTypeclass) {
            validTypes[visitKey] = currentType
        } else {
            invalidTypes[visitKey] = currentType
        }
    }

    val substitutions =
        classSymbol.owner.typeParameters.zip(simpleType.arguments).mapNotNull { (parameter, argument) ->
            argument.argumentTypeOrNull()?.let { type -> parameter.symbol to type }
        }.toMap()
    val nextPreviousWereTypeclass = previousWereTypeclass && currentIsTypeclass
    val nextVisited = visited + visitKey
    classSymbol.owner.superTypes.forEach { superType ->
        val substitutedSuperType =
            if (substitutions.isEmpty()) {
                superType
            } else {
                superType.substitute(substitutions)
            }
        val nested =
            substitutedSuperType.providedTypeExpansion(
                typeParameterBySymbol = typeParameterBySymbol,
                configuration = configuration,
                previousWereTypeclass = nextPreviousWereTypeclass,
                visited = nextVisited,
            )
        nested.validTypes.forEach { candidate ->
            validTypes.putIfAbsent(candidate.render(), candidate)
        }
        nested.invalidTypes.forEach { candidate ->
            invalidTypes.putIfAbsent(candidate.render(), candidate)
        }
    }

    return ProvidedTypeExpansion(
        validTypes = validTypes.values.toList(),
        invalidTypes = invalidTypes.values.toList(),
    )
}

private fun org.jetbrains.kotlin.ir.types.IrTypeArgument.argumentTypeOrNull(): IrType? =
    when (this) {
        is IrType -> this
        is org.jetbrains.kotlin.ir.types.IrTypeProjection -> type
        is IrStarProjection -> null
    }

private fun modelToIrType(
    type: TcType,
    visibleTypeParameters: VisibleTypeParameters,
    pluginContext: IrPluginContext,
): IrType =
    when (type) {
        TcType.StarProjection -> error("Top-level star projections cannot be materialized as standalone IR types.")
        is TcType.Projected -> error("Top-level projected arguments cannot be materialized as standalone IR types.")
        is TcType.Variable -> {
            val symbol = visibleTypeParameters.byId[type.id]
                ?: error("Unbound type variable ${type.displayName}")
            symbol.defaultType.let { irType ->
                if (type.isNullable) {
                    irType.makeNullable()
                } else {
                    irType
                }
            }
        }

        is TcType.Constructor -> {
            val classId = ClassId.fromString(type.classifierId)
            val classifier = pluginContext.referenceClass(classId)
                ?: error("Could not resolve classifier ${type.classifierId}")
            val arguments: List<IrTypeArgument> =
                type.arguments.map { nested ->
                    when (nested) {
                        TcType.StarProjection -> IrStarProjectionImpl
                        is TcType.Projected ->
                            makeTypeProjection(
                                modelToIrType(nested.type, visibleTypeParameters, pluginContext),
                                nested.variance,
                            )
                        else -> modelToIrType(nested, visibleTypeParameters, pluginContext)
                    }
                }
            IrSimpleTypeImpl(
                classifier = classifier,
                hasQuestionMark = type.isNullable,
                arguments = arguments,
                annotations = emptyList(),
            )
        }
    }

private fun <T> Iterator<T>.nextOrNull(): T? = if (hasNext()) next() else null

private fun IrCall.valueArguments(): List<IrExpression?> =
    (0 until valueArgumentsCount)
        .map(::getValueArgument)
        .let { rawArguments ->
            val lastPresentIndex = rawArguments.indexOfLast { argument -> argument != null }
            if (lastPresentIndex < 0) {
                emptyList()
            } else {
                rawArguments.subList(0, lastPresentIndex + 1)
            }
        }

private fun IrExpression.apparentType(): IrType =
    (this as? IrCall)
        ?.let { call ->
            val callee = call.symbol.owner
            val returnType = callee.returnType as? IrSimpleType ?: return@let null
            val returnedTypeParameter = returnType.classifier as? org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol ?: return@let null
            val typeParameterIndex = callee.typeParameters.indexOfFirst { parameter -> parameter.symbol == returnedTypeParameter }
            if (typeParameterIndex >= 0) {
                call.getTypeArgument(typeParameterIndex)
            } else {
                null
            }
        }
        ?: type

private fun IrCall.normalizedArgumentsForTypeclassRewrite(original: IrSimpleFunction): NormalizedCallArguments {
    val rawValueArguments =
        valueArguments().let { currentArguments ->
            if (origin == IrStatementOrigin.EQ && original.name.asString() == "set" && currentArguments.lastOrNull() == null) {
                currentArguments.dropLast(1)
            } else {
                currentArguments
            }
        }
    if (origin == IrStatementOrigin.EQ &&
        original.name.asString() == "set" &&
        original.extensionReceiverParameter != null &&
        extensionReceiver != null &&
        rawValueArguments.isNotEmpty()
    ) {
        return NormalizedCallArguments(
            dispatchReceiver = dispatchReceiver,
            extensionReceiver = rawValueArguments.first(),
            valueArguments =
                buildList {
                    add(extensionReceiver)
                    addAll(rawValueArguments.drop(1))
                },
        )
    }
    if (origin == IrStatementOrigin.IN &&
        original.name.asString() == "contains" &&
        original.extensionReceiverParameter != null &&
        extensionReceiver != null &&
        rawValueArguments.isNotEmpty()
    ) {
        return NormalizedCallArguments(
            dispatchReceiver = dispatchReceiver,
            extensionReceiver = rawValueArguments.first(),
            valueArguments =
                buildList {
                    add(extensionReceiver)
                    addAll(rawValueArguments.drop(1))
                },
        )
    }
    if (origin == IrStatementOrigin.GET_ARRAY_ELEMENT && original.extensionReceiverParameter != null && rawValueArguments.isNotEmpty()) {
        return NormalizedCallArguments(
            dispatchReceiver = dispatchReceiver,
            extensionReceiver = rawValueArguments.first(),
            valueArguments =
                buildList {
                    add(extensionReceiver)
                    addAll(rawValueArguments.drop(1))
                },
        )
    }

    if (original.extensionReceiverParameter != null && extensionReceiver == null && rawValueArguments.isNotEmpty()) {
        val firstParameter = original.regularAndContextParameters().firstOrNull()
        val firstRawArgument = rawValueArguments.first()
        val firstRawBelongsToExtensionReceiver =
            firstRawArgument != null && (
                firstParameter == null ||
                    !firstRawArgument.type.satisfiesExpectedContextType(firstParameter.type)
                )
        if (firstRawBelongsToExtensionReceiver) {
            return NormalizedCallArguments(
                dispatchReceiver = dispatchReceiver,
                extensionReceiver = firstRawArgument,
                valueArguments = rawValueArguments.drop(1),
            )
        }
    }

    return NormalizedCallArguments(
        dispatchReceiver = dispatchReceiver,
        extensionReceiver = extensionReceiver,
        valueArguments = rawValueArguments,
    )
}

private fun extractExplicitArguments(
    original: IrSimpleFunction,
    normalizedValueArguments: List<IrExpression?>,
    substitutionBySymbol: Map<org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol, IrType>,
    visibleTypeParameters: VisibleTypeParameters,
    configuration: TypeclassConfiguration,
): ExtractedExplicitArguments {
    val originalParameters = original.regularAndContextParameters()
    val currentToOriginal =
        mapCurrentArgumentsToOriginalParameters(
            originalParameters = originalParameters,
            currentArguments = normalizedValueArguments,
            typeArgumentMap = substitutionBySymbol,
            visibleTypeParameters = visibleTypeParameters,
            configuration = configuration,
        )

    fun missingArgumentError(parameterIndex: Int? = null): Nothing =
        error(
            buildString {
                append("Missing explicit argument for ")
                append(original.callableId)
                parameterIndex?.let {
                    append(" at parameter ")
                    append(it)
                }
            },
        )

    val explicitByOriginalIndex = linkedMapOf<Int, ExplicitArgument>()
    val preservedTypeclassByOriginalIndex = linkedMapOf<Int, IrExpression>()
    currentToOriginal.forEach { (currentIndex, originalIndex) ->
        val argument = normalizedValueArguments.getOrNull(currentIndex)
        val parameter = originalParameters[originalIndex]
        if (parameter.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Context &&
            parameter.type.substitute(substitutionBySymbol).isTypeclassType(configuration)
        ) {
            if (argument != null) {
                preservedTypeclassByOriginalIndex[originalIndex] = argument
            }
            return@forEach
        }
        explicitByOriginalIndex[originalIndex] =
            when {
                argument != null -> ExplicitArgument.PassThrough(argument)
                parameter.defaultValue != null -> ExplicitArgument.Omitted
                parameter.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Context -> ExplicitArgument.Omitted
                else -> missingArgumentError(originalIndex)
            }
    }

    val nonTypeclassParameterIndices =
        originalParameters.mapIndexedNotNull { index, parameter ->
            val substitutedType = parameter.type.substitute(substitutionBySymbol)
            index.takeIf {
                !(parameter.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Context &&
                    substitutedType.isTypeclassType(configuration))
            }
        }
    val missingNonTypeclassParameters =
        nonTypeclassParameterIndices.filterNot { parameterIndex -> parameterIndex in explicitByOriginalIndex }
    if (missingNonTypeclassParameters.isNotEmpty() && nonTypeclassParameterIndices.size == normalizedValueArguments.size) {
        explicitByOriginalIndex.clear()
        preservedTypeclassByOriginalIndex.clear()
        nonTypeclassParameterIndices.zip(normalizedValueArguments).forEach { (parameterIndex, argument) ->
            explicitByOriginalIndex[parameterIndex] =
                when {
                    argument != null -> ExplicitArgument.PassThrough(argument)
                    originalParameters[parameterIndex].defaultValue != null -> ExplicitArgument.Omitted
                    else -> missingArgumentError(parameterIndex)
                }
        }
    }

    val nonTypeclassArguments =
        originalParameters.mapIndexedNotNull { index, parameter ->
            val substitutedType = parameter.type.substitute(substitutionBySymbol)
            if (parameter.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Context && substitutedType.isTypeclassType(configuration)) {
                return@mapIndexedNotNull null
            }
            explicitByOriginalIndex[index] ?: when {
                parameter.defaultValue != null -> ExplicitArgument.Omitted
                parameter.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Context -> ExplicitArgument.Omitted
                else -> missingArgumentError(index)
            }
        }

    return ExtractedExplicitArguments(
        nonTypeclassArguments = nonTypeclassArguments,
        preservedTypeclassArguments = preservedTypeclassByOriginalIndex,
    )
}

private fun IrType.matchesTypeclassParameterType(
    expected: IrType,
    visibleTypeParameters: VisibleTypeParameters,
): Boolean =
    irTypeToModel(this, visibleTypeParameters.bySymbol) == irTypeToModel(expected, visibleTypeParameters.bySymbol)

private fun IrType.satisfiesExpectedContextType(
    expected: IrType,
    visibleTypeParameters: VisibleTypeParameters? = null,
    visited: MutableSet<String> = linkedSetOf(),
): Boolean {
    if (this == expected) {
        return true
    }
    if (visibleTypeParameters != null && matchesTypeclassParameterType(expected, visibleTypeParameters)) {
        return true
    }
    val owner = classOrNull?.owner ?: return false
    val visitKey = owner.classId?.asString() ?: owner.name.asString()
    if (!visited.add(visitKey)) {
        return false
    }
    return owner.superTypes.any { superType ->
        superType == expected ||
            (visibleTypeParameters != null && superType.matchesTypeclassParameterType(expected, visibleTypeParameters)) ||
            superType.satisfiesExpectedContextType(expected, visibleTypeParameters, visited)
    }
}

private fun IrType.matchesProjectedExplicitContextType(
    expected: IrType,
    visibleTypeParameters: VisibleTypeParameters? = null,
): Boolean {
    val actualModel = irTypeToModel(this, visibleTypeParameters?.bySymbol.orEmpty()) ?: return false
    val expectedModel = irTypeToModel(expected, visibleTypeParameters?.bySymbol.orEmpty()) ?: return false
    return actualModel.matchesProjectedExplicitContextType(expectedModel)
}

private fun TcType.matchesProjectedExplicitContextType(expected: TcType): Boolean =
    when {
        this == expected -> true
        this is TcType.Projected &&
            this.variance == org.jetbrains.kotlin.types.Variance.OUT_VARIANCE &&
            this.type == expected -> true

        this is TcType.Constructor && expected is TcType.Constructor ->
            this.classifierId == expected.classifierId &&
                this.isNullable == expected.isNullable &&
                this.arguments.size == expected.arguments.size &&
                this.arguments.zip(expected.arguments).all { (actualArgument, expectedArgument) ->
                    actualArgument.matchesProjectedExplicitContextType(expectedArgument)
                }

        this is TcType.Projected && expected is TcType.Projected ->
            this.variance == expected.variance &&
                this.type.matchesProjectedExplicitContextType(expected.type)

        else -> false
    }

private fun IrValueParameter.isTypeclassWrapperMarkerParameter(): Boolean =
    name.asString() == TYPECLASS_WRAPPER_MARKER_PARAMETER_NAME

private fun IrSimpleFunction.visibleSignatureTypeParameterCount(
    dropTypeclassContexts: Boolean,
    configuration: TypeclassConfiguration,
): Int {
    val referenced = linkedSetOf<org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol>()

    extensionReceiverParameter?.type?.collectReferencedTypeParameters(referenced)
    returnType.collectReferencedTypeParameters(referenced)
    parameters
        .filter { parameter ->
            (parameter.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Regular && !parameter.isTypeclassWrapperMarkerParameter()) ||
                (parameter.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Context &&
                    !(dropTypeclassContexts && parameter.type.isTypeclassType(configuration)))
        }.forEach { parameter ->
            parameter.type.collectReferencedTypeParameters(referenced)
        }

    return typeParameters.count { it.symbol in referenced }
}

private fun IrType.collectReferencedTypeParameters(
    sink: MutableSet<org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol>,
) {
    val simpleType = this as? IrSimpleType ?: return
    when (val classifier = simpleType.classifier) {
        is org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol -> sink += classifier
        is org.jetbrains.kotlin.ir.symbols.IrClassSymbol -> Unit
        else -> Unit
    }
    simpleType.arguments.forEach { argument ->
        when (argument) {
            is IrType -> argument.collectReferencedTypeParameters(sink)
            is org.jetbrains.kotlin.ir.types.IrTypeProjection -> argument.type.collectReferencedTypeParameters(sink)
            is IrStarProjection -> Unit
        }
    }
}

private fun inferOriginalTypeArguments(
    original: IrSimpleFunction,
    normalizedCall: NormalizedCallArguments,
    currentCallTypeArgumentsByName: Map<String, IrType>,
    visibleTypeParameters: VisibleTypeParameters,
    localContexts: List<LocalTypeclassContext>,
    pluginContext: IrPluginContext,
    configuration: TypeclassConfiguration,
): InferredOriginalTypeArguments {
    val originalTypeParameterBySymbol =
        visibleTypeParameterSymbols(original, listOf(original)).associateWith { symbol ->
            TcTypeParameter(
                id = visibleTypeParameterId(symbol),
                displayName = symbol.owner.name.asString(),
            )
        }
    val bindableVariableIds = originalTypeParameterBySymbol.values.mapTo(linkedSetOf(), TcTypeParameter::id)
    val bindings = linkedMapOf<String, TcType>()

    original.typeParameters.forEach { typeParameter ->
        val currentType = currentCallTypeArgumentsByName[typeParameter.name.asString()] ?: return@forEach
        val parameter =
            originalTypeParameterBySymbol[typeParameter.symbol]
                ?: error("Missing visible type parameter metadata for ${typeParameter.symbol}")
        val currentModel =
            irTypeToModel(currentType, visibleTypeParameters.bySymbol)
                ?: error("Unsupported type argument $currentType for ${original.callableId}")
        bindings[parameter.id] = currentModel
    }

    fun mergeBindingsFromIrTypes(
        expectedType: IrType,
        actualType: IrType,
    ) {
        val expectedModel =
            irTypeToModel(expectedType, originalTypeParameterBySymbol)
                ?.substituteType(bindings)
                ?: return
        actualType.inferenceModels(visibleTypeParameters, configuration).forEach { actualModel ->
            val candidateBindings = unifyTypes(expectedModel, actualModel, bindableVariableIds) ?: return@forEach
            mergeTypeBindings(bindings, candidateBindings, bindableVariableIds)
        }
    }

    fun mergeBindingsFromReceiverTypeArguments(
        expectedType: IrType,
        actualType: IrType,
    ) {
        val expectedSimpleType = expectedType as? IrSimpleType ?: return
        val actualSimpleType = actualType as? IrSimpleType ?: return
        val expectedClass = expectedSimpleType.classOrNull?.owner ?: return
        if (expectedClass.typeParameters.isEmpty()) {
            return
        }
        expectedClass.typeParameters.zip(actualSimpleType.arguments).forEach { (parameter, argument) ->
            val actualArgumentType = argument.argumentTypeOrNull() ?: return@forEach
            val tcParameter = originalTypeParameterBySymbol[parameter.symbol] ?: return@forEach
            val actualModel = irTypeToModel(actualArgumentType, visibleTypeParameters.bySymbol) ?: return@forEach
            mergeTypeBindings(
                existing = bindings,
                incoming = mapOf(tcParameter.id to actualModel),
                bindableVariableIds = bindableVariableIds,
            )
        }
    }

    original.dispatchReceiverParameter?.type?.let { expectedDispatchType ->
        normalizedCall.dispatchReceiver?.apparentType()?.let { actualDispatchType ->
            mergeBindingsFromIrTypes(expectedDispatchType, actualDispatchType)
            mergeBindingsFromReceiverTypeArguments(expectedDispatchType, actualDispatchType)
        }
    }

    original.extensionReceiverParameter?.type?.let { expectedExtensionType ->
        normalizedCall.extensionReceiver?.apparentType()?.let { actualExtensionType ->
            mergeBindingsFromIrTypes(expectedExtensionType, actualExtensionType)
            mergeBindingsFromReceiverTypeArguments(expectedExtensionType, actualExtensionType)
        }
    }

    val originalParameters = original.regularAndContextParameters()
    val currentToOriginal =
        mapCurrentArgumentsToOriginalParameters(
            originalParameters = originalParameters,
            currentArguments = normalizedCall.valueArguments,
            typeArgumentMap = null,
            visibleTypeParameters = null,
            configuration = configuration,
        )
    currentToOriginal.forEach { (currentValueArgumentIndex, originalParameterIndex) ->
        val parameter = originalParameters[originalParameterIndex]
        val actualArgumentType = normalizedCall.valueArguments.getOrNull(currentValueArgumentIndex)?.apparentType()
        if (actualArgumentType != null) {
            mergeBindingsFromIrTypes(parameter.type, actualArgumentType)
        }
    }

    original.contextParameters()
        .filter { parameter -> parameter.type.isTypeclassType(configuration) }
        .forEach { parameter ->
            val goalType =
                irTypeToModel(parameter.type, originalTypeParameterBySymbol)
                    ?.substituteType(bindings)
                    ?: error("Unsupported typeclass context ${parameter.type} in ${original.callableId}")
            localContexts.forEach { localContext ->
                val candidateBindings =
                    unifyTypes(goalType, localContext.providedType, bindableVariableIds) ?: return@forEach
                mergeTypeBindings(bindings, candidateBindings, bindableVariableIds)
            }
        }

    val substitutionBySymbol =
        originalTypeParameterBySymbol.mapNotNull { (symbol, parameter) ->
            val model = bindings[parameter.id]?.substituteType(bindings) ?: return@mapNotNull null
            symbol to modelToIrType(model, visibleTypeParameters, pluginContext)
        }.toMap()

    val callTypeArguments =
        original.typeParameters.map { typeParameter ->
            val parameter =
                originalTypeParameterBySymbol[typeParameter.symbol]
                    ?: error("Missing visible type parameter metadata for ${typeParameter.symbol}")
            val model =
                bindings[parameter.id]?.substituteType(bindings)
                    ?: throw TypeArgumentInferenceFailure(
                        missingTypeArgumentMessage(original, typeParameter, configuration),
                    )
            modelToIrType(model, visibleTypeParameters, pluginContext)
        }

    return InferredOriginalTypeArguments(
        callTypeArguments = callTypeArguments,
        substitutionBySymbol = substitutionBySymbol,
    )
}

private class TypeArgumentInferenceFailure(
    message: String,
) : IllegalStateException(message)

private fun missingTypeArgumentMessage(
    original: IrSimpleFunction,
    typeParameter: IrTypeParameter,
    configuration: TypeclassConfiguration,
): String {
    val typeclassContextCount = original.contextParameters().count { parameter -> parameter.type.isTypeclassType(configuration) }
    return if (typeclassContextCount > 1) {
        "Conflicting type bindings prevented inferring ${typeParameter.name} in ${original.renderIdentity()}"
    } else {
        "Missing type argument for ${typeParameter.name} in ${original.renderIdentity()}"
    }
}

private fun mergeTypeBindings(
    existing: MutableMap<String, TcType>,
    incoming: Map<String, TcType>,
    bindableVariableIds: Set<String>,
) {
    incoming.forEach { (key, value) ->
        val current = existing[key]
        if (current == null) {
            existing[key] = value
        } else {
            val unified = unifyTypes(current, value, bindableVariableIds)
                ?: throw TypeArgumentInferenceFailure(
                    "Conflicting type bindings for $key: ${current.render()} vs ${value.render()}",
                )
            unified.forEach { (nestedKey, nestedValue) ->
                existing[nestedKey] = nestedValue
            }
        }
    }
}

private fun IrType.inferenceModels(
    visibleTypeParameters: VisibleTypeParameters,
    configuration: TypeclassConfiguration,
): List<TcType> {
    val directModel = irTypeToModel(this, visibleTypeParameters.bySymbol)
    val expandedModels =
        listOf(this)
            .providedTypeExpansion(visibleTypeParameters.bySymbol, configuration)
            .validTypes
    return buildList {
        directModel?.let(::add)
        expandedModels.forEach { candidate ->
            if (candidate != directModel) {
                add(candidate)
            }
        }
    }
}

private fun IrType.localEvidenceTypes(
    typeParameterBySymbol: Map<org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol, TcTypeParameter>,
    configuration: TypeclassConfiguration,
): List<TcType> = localEvidenceTypes(typeParameterBySymbol, configuration, visited = linkedSetOf())

private fun IrType.localEvidenceTypes(
    typeParameterBySymbol: Map<org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol, TcTypeParameter>,
    configuration: TypeclassConfiguration,
    visited: MutableSet<String>,
): List<TcType> {
    val simpleType = this as? IrSimpleType ?: return emptyList()
    val classSymbol = simpleType.classOrNull ?: return emptyList()
    val currentModel = irTypeToModel(this, typeParameterBySymbol)
    val visitKey = currentModel?.render() ?: render()
    if (!visited.add(visitKey)) {
        return emptyList()
    }

    val collected = linkedMapOf<String, TcType>()
    if (isTypeclassType(configuration) && currentModel != null) {
        collected[visitKey] = currentModel
    }

    val substitutions =
        classSymbol.owner.typeParameters.zip(simpleType.arguments).mapNotNull { (parameter, argument) ->
            argument.argumentTypeOrNull()?.let { type -> parameter.symbol to type }
        }.toMap()
    classSymbol.owner.superTypes.forEach { superType ->
        val substitutedSuperType = if (substitutions.isEmpty()) superType else superType.substitute(substitutions)
        substitutedSuperType.localEvidenceTypes(typeParameterBySymbol, configuration, visited).forEach { candidate ->
            collected.putIfAbsent(candidate.render(), candidate)
        }
    }

    return collected.values.toList()
}

private fun IrFunction.renderIdentity(): String =
    (this as? IrSimpleFunction)?.let { function ->
        runCatching { function.callableId.toString() }.getOrElse { function.name.asString() }
    } ?: name.asString()

private fun IrDeclarationBase.renderIdentity(): String =
    when (this) {
        is IrFunction -> renderIdentity()
        is IrField -> "field:${name.asString()}"
        is IrProperty -> "property:${name.asString()}"
        is IrClass -> classId?.asString() ?: "class:${name.asString()}"
        else -> this::class.simpleName ?: "declaration"
    }

private fun mapCurrentArgumentsToOriginalParameters(
    originalParameters: List<IrValueParameter>,
    currentArguments: List<IrExpression?>,
    typeArgumentMap: Map<org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol, IrType>?,
    visibleTypeParameters: VisibleTypeParameters?,
    configuration: TypeclassConfiguration,
): List<Pair<Int, Int>> {
    val visibleTypeParameterSymbols = visibleTypeParameters?.bySymbol.orEmpty()
    val substitutedParameterTypes =
        originalParameters.map { parameter ->
            typeArgumentMap?.let(parameter.type::substitute) ?: parameter.type
        }
    val cache = mutableMapOf<Pair<Int, Int>, List<Pair<Int, Int>>?>()

    fun IrValueParameter.isTypeclassContext(index: Int): Boolean =
        kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Context &&
            substitutedParameterTypes[index].isTypeclassType(configuration)

    fun IrValueParameter.isOmittable(index: Int): Boolean =
        isTypeclassContext(index) || defaultValue != null

    fun argumentProvidesContext(
        argument: IrExpression?,
        expectedType: IrType,
    ): Boolean =
        argument != null &&
            (
                argument.type.isTypeclassType(configuration) ||
                    argument.type.localEvidenceTypes(visibleTypeParameterSymbols, configuration).isNotEmpty()
            ) &&
            (
                argument.type.satisfiesExpectedContextType(expectedType, visibleTypeParameters) ||
                    argument.type.matchesProjectedExplicitContextType(expectedType, visibleTypeParameters)
            )

    fun argumentMatchesRegularParameter(
        argument: IrExpression?,
        expectedType: IrType,
    ): Boolean {
        argument ?: return false
        return argument.type == expectedType ||
            (visibleTypeParameters != null && argument.type.matchesTypeclassParameterType(expectedType, visibleTypeParameters)) ||
            argument.type.satisfiesExpectedContextType(expectedType, visibleTypeParameters)
    }

    fun mappingScore(mapping: List<Pair<Int, Int>>): Int =
        mapping.count { (_, originalIndex) -> !originalParameters[originalIndex].isTypeclassContext(originalIndex) }

    fun chooseBetterMapping(
        left: List<Pair<Int, Int>>?,
        right: List<Pair<Int, Int>>?,
    ): List<Pair<Int, Int>>? =
        when {
            left == null -> right
            right == null -> left
            mappingScore(right) > mappingScore(left) -> right
            mappingScore(right) < mappingScore(left) -> left
            right.size > left.size -> right
            else -> left
        }

    fun search(
        currentIndex: Int,
        originalIndex: Int,
    ): List<Pair<Int, Int>>? {
        val cacheKey = currentIndex to originalIndex
        cache[cacheKey]?.let { cached -> return cached }

        if (currentIndex >= currentArguments.size) {
            val result =
                if (originalParameters.drop(originalIndex).withIndex().all { (offset, parameter) ->
                        parameter.isOmittable(originalIndex + offset)
                    }
                ) {
                    emptyList<Pair<Int, Int>>()
                } else {
                    null
                }
            cache[cacheKey] = result
            return result
        }
        if (originalIndex >= originalParameters.size) {
            cache[cacheKey] = null
            return null
        }

        val parameter = originalParameters[originalIndex]
        val expectedType = substitutedParameterTypes[originalIndex]
        val argument = currentArguments[currentIndex]

        var bestMapping: List<Pair<Int, Int>>? = null

        if (parameter.isTypeclassContext(originalIndex)) {
            if (argumentProvidesContext(argument, expectedType)) {
                val preservedTail = search(currentIndex + 1, originalIndex + 1)
                if (preservedTail != null) {
                    bestMapping = listOf(currentIndex to originalIndex) + preservedTail
                }
            }
            if (parameter.isOmittable(originalIndex)) {
                bestMapping = chooseBetterMapping(bestMapping, search(currentIndex, originalIndex + 1))
            }
        } else {
            if (argumentMatchesRegularParameter(argument, expectedType)) {
                val consumedTail = search(currentIndex + 1, originalIndex + 1)
                if (consumedTail != null) {
                    bestMapping = listOf(currentIndex to originalIndex) + consumedTail
                }
            }
            if (parameter.isOmittable(originalIndex)) {
                bestMapping = chooseBetterMapping(bestMapping, search(currentIndex, originalIndex + 1))
            }
        }

        cache[cacheKey] = bestMapping
        return bestMapping
    }

    return search(currentIndex = 0, originalIndex = 0).orEmpty()
}
