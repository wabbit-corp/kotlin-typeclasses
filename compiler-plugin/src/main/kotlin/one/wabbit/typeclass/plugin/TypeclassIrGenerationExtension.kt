// SPDX-License-Identifier: AGPL-3.0-or-later

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
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithVisibility
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.DescriptorMetadataSource
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrMetadataSourceOwner
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrComposite
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrSpreadElement
import org.jetbrains.kotlin.ir.expressions.IrTry
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetEnumValueImpl
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
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
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedCallableMemberDescriptor
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
    private val deriverReturnValidationCache = DeriverReturnValidationCache<IrSimpleFunction>()
    private val declarationStack = ArrayDeque<IrDeclarationBase>()
    private val functionStack = ArrayDeque<IrFunction>()
    private val traceScopeStack = ArrayDeque<TypeclassTraceScope>()

    override fun visitFile(declaration: IrFile): IrFile {
        val traceScope = declaration.traceScopeOrNull()
        if (traceScope != null) {
            traceScopeStack.addLast(traceScope)
        }
        val transformed = super.visitFile(declaration) as IrFile
        if (traceScope != null) {
            traceScopeStack.removeLast()
        }
        return transformed
    }

    override fun visitDeclaration(declaration: IrDeclarationBase): IrStatement {
        if (declaration is IrFunction) {
            return super.visitDeclaration(declaration)
        }
        val traceScope = declaration.traceScopeOrNull()
        if (traceScope != null) {
            traceScopeStack.addLast(traceScope)
        }
        declarationStack.addLast(declaration)
        val transformed = super.visitDeclaration(declaration)
        declarationStack.removeLast()
        if (traceScope != null) {
            traceScopeStack.removeLast()
        }
        return transformed
    }

    override fun visitFunction(declaration: IrFunction): IrStatement {
        val traceScope = declaration.traceScopeOrNull()
        if (traceScope != null) {
            traceScopeStack.addLast(traceScope)
        }
        declarationStack.addLast(declaration)
        functionStack.addLast(declaration)
        val transformed = super.visitFunction(declaration)
        functionStack.removeLast()
        declarationStack.removeLast()
        if (traceScope != null) {
            traceScopeStack.removeLast()
        }
        return transformed
    }

    override fun visitVariable(declaration: IrVariable): IrStatement {
        val traceScope = declaration.traceScopeOrNull()
        if (traceScope != null) {
            traceScopeStack.addLast(traceScope)
        }
        declarationStack.addLast(declaration)
        val transformed = super.visitVariable(declaration)
        declarationStack.removeLast()
        if (traceScope != null) {
            traceScopeStack.removeLast()
        }
        return transformed
    }

    override fun visitCall(expression: IrCall): IrExpression {
        val rewritten = super.visitCall(expression) as IrCall
        val callee = rewritten.symbol.owner
        val original = selectSyntheticResolutionOriginal(callee, rewritten)
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

    private fun selectSyntheticResolutionOriginal(
        callee: IrSimpleFunction,
        call: IrCall,
    ): IrSimpleFunction? {
        val directCandidate =
            when {
                callee.requiresSyntheticTypeclassResolution(call, configuration) -> callee
                else ->
                    ruleIndex.originalForWrapperLikeFunction(callee)?.takeIf { candidate ->
                        candidate.requiresSyntheticTypeclassResolution(call, configuration)
                    }
            }
        if (directCandidate != null && directCandidate.canAcceptSyntheticResolutionCall(call, configuration)) {
            return directCandidate
        }

        val viableOverloads =
            contextualOverloadCandidates(callee, call).filter { candidate ->
                candidate.canAcceptSyntheticResolutionCall(call, configuration)
            }
        return when {
            directCandidate != null && viableOverloads.isEmpty() -> directCandidate
            directCandidate != null && directCandidate in viableOverloads -> directCandidate
            viableOverloads.size == 1 -> viableOverloads.single()
            else -> directCandidate ?: viableOverloads.singleOrNull()
        }
    }

    private fun contextualOverloadFallback(
        callee: IrSimpleFunction,
        call: IrCall,
    ): IrSimpleFunction? =
        contextualOverloadCandidates(callee, call).singleOrNull()

    private fun exactPlainOverloadFallback(
        callee: IrSimpleFunction,
        call: IrCall,
    ): IrSimpleFunction? {
        val callableId = callee.safeCallableIdOrNull() ?: return null
        if (
            !supportsExactPlainOverloadFallbackTypeParameters(
                callableIsLocal = callableId.isLocal,
                calleeDeclaredTypeParameterCount = callee.typeParameters.size,
                candidateDeclaredTypeParameterCount = 0,
            )
        ) {
            return null
        }
        val candidates =
            referencedCallableIdFunctions(callableId)
                .map { candidate -> ruleIndex.originalForWrapperLikeFunction(candidate) ?: candidate }
                .distinctBy { candidate -> candidate.symbol }
                .toList()
        val result =
            candidates
                .asSequence()
                .filter { candidate -> candidate != callee }
                .filter { candidate ->
                    supportsExactPlainOverloadFallbackTypeParameters(
                        callableIsLocal = false,
                        calleeDeclaredTypeParameterCount = callee.typeParameters.size,
                        candidateDeclaredTypeParameterCount = candidate.typeParameters.size,
                    )
                }
                .filter { candidate -> !candidate.requiresSyntheticTypeclassResolution(call, configuration) }
                .filter { candidate -> exactPlainOverloadFallbackShapesMatch(candidate, callee, configuration) }
                .singleOrNull()
        return result
    }

    private fun contextualOverloadCandidates(
        callee: IrSimpleFunction,
        call: IrCall,
    ): List<IrSimpleFunction> {
        val callableId = callee.safeCallableIdOrNull() ?: return emptyList()
        if (callableId.isLocal) {
            return emptyList()
        }
        val calleeVisibleParameterCount = callee.visibleNonTypeclassParameterCount(configuration)
        return referencedCallableIdFunctions(callableId)
            .map { candidate -> ruleIndex.originalForWrapperLikeFunction(candidate) ?: candidate }
            .filter { candidate -> candidate != callee }
            .filter { candidate ->
                candidate.dispatchReceiverParameter != null == (callee.dispatchReceiverParameter != null) &&
                    candidate.extensionReceiverParameter != null == (callee.extensionReceiverParameter != null)
            }
            .filter { candidate -> candidate.visibleNonTypeclassParameterCount(configuration) == calleeVisibleParameterCount }
            .filter { candidate -> candidate.requiresSyntheticTypeclassResolution(call, configuration) }
            .distinctBy { candidate -> candidate.symbol }
            .toList()
    }

    private fun referencedCallableIdFunctions(callableId: CallableId): Sequence<IrSimpleFunction> =
        pluginContext.referenceFunctions(callableId)
            .asSequence()
            .mapNotNull { symbol -> symbol.safeCallableLookupOwnerOrNull() }

    private fun IrSimpleFunctionSymbol.safeCallableLookupOwnerOrNull(): IrSimpleFunction? {
        val owner = runCatching { owner }.getOrNull() ?: return null
        return owner.takeUnless { it.origin == IrDeclarationOrigin.FAKE_OVERRIDE }
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
                                call.typeArgumentOrNull(index)?.let { irType ->
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
        supplementalMessage: String? = null,
    ) {
        pluginContext.reportTypeclassError(message, diagnosticId, location, supplementalMessage)
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
        val diagnosticLocation = currentDeclaration.compilerMessageLocation(fallbackCall)
        val plainOverloadFallback = exactPlainOverloadFallback(original, fallbackCall)
        val traceActivation = resolveTraceActivation(traceScopeStack.toList(), configuration.traceMode)
        val planner =
            TypeclassResolutionPlanner(
                ruleProvider = { goal: TcType ->
                    ruleIndex.rulesForGoal(
                        goal = goal,
                        canMaterializeVariable = visibleTypeParameters::canMaterializeRuntimeType,
                        builtinGoalAcceptance = BuiltinGoalAcceptance.PROVABLE_ONLY,
                        exactBuiltinGoalContext =
                            IrBuiltinGoalExactContext(
                                visibleTypeParameters = visibleTypeParameters,
                                pluginContext = pluginContext,
                                configuration = configuration,
                            ),
                    )
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
                val tracedResult =
                    traceActivation?.let { activation ->
                        planner.resolveWithTrace(goal, localContextTypes, activation.mode.explainsAlternatives)
                    }
                val result = tracedResult?.result ?: planner.resolve(goal, localContextTypes)
                val expression =
                    when (result) {
                        is ResolutionSearchResult.Success -> {
                            if (traceActivation?.mode?.tracesSuccesses == true && tracedResult != null) {
                                pluginContext.reportTypeclassTrace(
                                    renderResolutionTrace(
                                        trace = tracedResult.trace,
                                        activation = traceActivation,
                                        location = diagnosticLocation,
                                        localContextLabels =
                                            localContexts.mapIndexed { index, context ->
                                                "${context.displayName} (local context[$index])"
                                            },
                                    ),
                                    diagnosticLocation,
                                )
                            }
                            irBlock(resultType = substitutedGoalType) {
                                +buildExpressionForPlan(
                                    plan = result.plan,
                                    currentDeclaration = currentDeclaration,
                                    currentFunction = currentFunction,
                                    localContexts = localContexts,
                                    visibleTypeParameters = visibleTypeParameters,
                                    recursiveDerivedResolvers = linkedMapOf(),
                                    diagnosticLocation = diagnosticLocation,
                                )
                            }
                        }

                        is ResolutionSearchResult.Ambiguous -> {
                            reportTypeclassResolutionFailure(
                                message =
                                    ambiguousTypeclassInstanceDiagnostic(
                                        goal = goal.render(),
                                        scope = currentScopeIdentity,
                                        candidates = result.matchingPlans.map { it.renderForDiagnostic() },
                                    ),
                                diagnosticId = TypeclassDiagnosticIds.AMBIGUOUS_INSTANCE,
                                location = diagnosticLocation,
                                supplementalMessage =
                                    if (traceActivation != null && tracedResult != null) {
                                        renderResolutionTrace(
                                            trace = tracedResult.trace,
                                            activation = traceActivation,
                                            location = diagnosticLocation,
                                            localContextLabels =
                                                localContexts.mapIndexed { index, context ->
                                                    "${context.displayName} (local context[$index])"
                                                },
                                        )
                                    } else {
                                        null
                                    },
                            )
                            return fallbackCall
                        }

                        is ResolutionSearchResult.Missing -> {
                            if (plainOverloadFallback != null) {
                                return buildPlainOverloadCall(
                                    plainFallback = plainOverloadFallback,
                                    dispatchReceiver = dispatchReceiver,
                                    extensionReceiver = extensionReceiver,
                                    explicitArguments = explicitArguments,
                                )
                            }
                            reportTypeclassResolutionFailure(
                                message =
                                    missingTypeclassInstanceDiagnostic(
                                        goal = goal.render(),
                                        scope = currentScopeIdentity,
                                    ),
                                diagnosticId = TypeclassDiagnosticIds.NO_CONTEXT_ARGUMENT,
                                location = diagnosticLocation,
                                supplementalMessage =
                                    if (traceActivation != null && tracedResult != null) {
                                        renderResolutionTrace(
                                            trace = tracedResult.trace,
                                            activation = traceActivation,
                                            location = diagnosticLocation,
                                            localContextLabels =
                                                localContexts.mapIndexed { index, context ->
                                                    "${context.displayName} (local context[$index])"
                                                },
                                        )
                                    } else {
                                        null
                                    },
                            )
                            return fallbackCall
                        }

                        is ResolutionSearchResult.Recursive -> {
                            if (plainOverloadFallback != null) {
                                return buildPlainOverloadCall(
                                    plainFallback = plainOverloadFallback,
                                    dispatchReceiver = dispatchReceiver,
                                    extensionReceiver = extensionReceiver,
                                    explicitArguments = explicitArguments,
                                )
                            }
                            reportTypeclassResolutionFailure(
                                message =
                                    recursiveTypeclassResolutionDiagnostic(
                                        goal = goal.render(),
                                        scope = currentScopeIdentity,
                                    ),
                                diagnosticId = TypeclassDiagnosticIds.NO_CONTEXT_ARGUMENT,
                                location = diagnosticLocation,
                                supplementalMessage =
                                    if (traceActivation != null && tracedResult != null) {
                                        renderResolutionTrace(
                                            trace = tracedResult.trace,
                                            activation = traceActivation,
                                            location = diagnosticLocation,
                                            localContextLabels =
                                                localContexts.mapIndexed { index, context ->
                                                    "${context.displayName} (local context[$index])"
                                                },
                                        )
                                    } else {
                                        null
                                    },
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

    private fun IrBuilderWithScope.buildPlainOverloadCall(
        plainFallback: IrSimpleFunction,
        dispatchReceiver: IrExpression?,
        extensionReceiver: IrExpression?,
        explicitArguments: ExtractedExplicitArguments,
    ): IrExpression {
        require(plainFallback.typeParameters.isEmpty()) {
            "Generic plain overload fallback is unsupported for ${plainFallback.renderIdentity()}"
        }
        val plainCall = irCall(plainFallback.symbol)
        plainCall.dispatchReceiver = dispatchReceiver
        plainCall.extensionReceiver = extensionReceiver
        val explicitIterator = explicitArguments.nonTypeclassArguments.iterator()
        plainFallback.valueParameters.forEachIndexed { parameterIndex, parameter ->
            when (val nextArgument = explicitIterator.nextOrNull()) {
                ExplicitArgument.Omitted -> Unit
                is ExplicitArgument.PassThrough -> plainCall.putValueArgument(parameterIndex, nextArgument.expression)
                null -> error("Not enough explicit arguments when rewriting ${plainFallback.callableId}")
            }
        }
        return plainCall
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
                        val function = ruleIndex.resolveLookupFunction(reference, resolvedRule.rule)
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
                        val property = ruleIndex.resolveLookupProperty(reference, resolvedRule.rule)
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
        val deriveMethod = reference.deriveMethod
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
        deriverReturnValidationCache.getOrPut(
            method = deriveMethod,
            expectedTypeclassId = typeclassInterface.classIdOrFail.asString(),
        ) {
            val expectedTypeclassId = typeclassInterface.classIdOrFail.asString()
            val declaredReturnTypeclassConstructors =
                listOf(deriveMethod.returnType)
                    .providedTypeExpansion(emptyMap(), configuration)
                    .validTypes
                    .mapNotNull { providedType -> (providedType as? TcType.Constructor)?.classifierId }
                    .distinct()
            if (declaredReturnTypeclassConstructors.isNotEmpty()) {
                if (expectedTypeclassId in declaredReturnTypeclassConstructors) {
                    return@getOrPut true
                }
                pluginContext.reportTypeclassError(
                    message =
                        "${deriveMethod.name.asString()} must return ${typeclassInterface.name.asString()}<...>; found ${declaredReturnTypeclassConstructors.joinToString { classifierId -> classifierId.shortClassNameOrSelf() }}",
                    diagnosticId = TypeclassDiagnosticIds.CANNOT_DERIVE,
                    location = deriveMethod.compilerMessageLocation(),
                )
                return@getOrPut false
            }
            if (deriveMethod.origin == IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB && deriveMethod.body == null) {
                // External stubs do not carry bodies, so source-validated Any-return derivers are
                // indistinguishable from invalid ones here. Trust the producer once the declared
                // return type does not already contradict the owning typeclass.
                return@getOrPut true
            }
            val knownReturnExpressions = deriveMethod.knownDeriverReturnExpressions()
            if (knownReturnExpressions.isEmpty()) {
                pluginContext.reportTypeclassError(
                    message = "${deriveMethod.name.asString()} must return ${typeclassInterface.name.asString()}<...>",
                    diagnosticId = TypeclassDiagnosticIds.CANNOT_DERIVE,
                    location = deriveMethod.compilerMessageLocation(),
                )
                return@getOrPut false
            }
            knownReturnExpressions.forEach { returnExpression ->
                val knownTypeclassConstructors =
                    returnExpression.knownReturnedTypeclassConstructors(configuration)
                if (knownTypeclassConstructors.isEmpty()) {
                    pluginContext.reportTypeclassError(
                        message = "${deriveMethod.name.asString()} must return ${typeclassInterface.name.asString()}<...>",
                        diagnosticId = TypeclassDiagnosticIds.CANNOT_DERIVE,
                        location = deriveMethod.compilerMessageLocation(),
                    )
                    return@getOrPut false
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
                var result: IrExpression =
                    irTypeclassInternalError(
                        pluginContext = pluginContext,
                        message = impossibleEnumOrdinalResolverRuntimeMessage(reference.targetClass.renderClassName()),
                    )
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
                var result: IrExpression =
                    irTypeclassInternalError(
                        pluginContext = pluginContext,
                        message = impossibleEnumValueResolverRuntimeMessage(reference.targetClass.renderClassName()),
                    )
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
    supplementalMessage: String? = null,
) {
    val enrichedMessage =
        when (diagnosticId) {
            TypeclassDiagnosticIds.CANNOT_DERIVE -> enrichCannotDeriveDiagnostic(message)
            TypeclassDiagnosticIds.INVALID_BUILTIN_EVIDENCE -> enrichInvalidBuiltinEvidenceDiagnostic(message)
            else -> message
        }
    val renderedMessage =
        diagnosticId?.let { id -> "[$id] $enrichedMessage" } ?: enrichedMessage
    val finalMessage =
        if (supplementalMessage != null) {
            "$renderedMessage\n$supplementalMessage"
        } else {
            renderedMessage
        }
    messageCollector.report(CompilerMessageSeverity.ERROR, finalMessage, location)
}

private fun IrPluginContext.reportTypeclassTrace(
    message: String,
    location: CompilerMessageSourceLocation? = null,
) {
    messageCollector.report(CompilerMessageSeverity.INFO, message, location)
}

private fun IrPluginContext.reportCannotDeriveWithTrace(
    owner: IrClass,
    configuration: TypeclassConfiguration,
    goal: String,
    message: String,
    location: CompilerMessageSourceLocation? = null,
    extraLines: List<String> = emptyList(),
) {
    reportTypeclassError(
        message = message,
        diagnosticId = TypeclassDiagnosticIds.CANNOT_DERIVE,
        location = location,
        supplementalMessage =
            owner.derivationTraceActivation(configuration)?.let { activation ->
                renderDerivationTrace(
                    goal = goal,
                    activation = activation,
                    location = location,
                    lines = listOf("reason: $message") + extraLines,
                )
            },
    )
}

private fun IrPluginContext.reportDerivationSuccessTrace(
    owner: IrClass,
    configuration: TypeclassConfiguration,
    goal: String,
    location: CompilerMessageSourceLocation? = null,
    extraLines: List<String> = emptyList(),
) {
    val activation = owner.derivationTraceActivation(configuration) ?: return
    if (!activation.mode.tracesSuccesses) {
        return
    }
    reportTypeclassTrace(
        renderDerivationTrace(
            goal = goal,
            activation = activation,
            location = location,
            lines = extraLines,
        ),
        location = location,
    )
}

private fun IrFile.traceScopeOrNull(): TypeclassTraceScope? =
    annotations.firstNotNullOfOrNull { annotation -> annotation.debugTypeclassTraceScope(kind = "file", label = fileEntry.name.substringAfterLast('/')) }

private fun IrDeclarationBase.traceScopeOrNull(): TypeclassTraceScope? =
    annotations.firstNotNullOfOrNull { annotation ->
        annotation.debugTypeclassTraceScope(
            kind =
                when (this) {
                    is IrClass -> if (isObject) "object" else "class"
                    is IrProperty -> "property"
                    is IrVariable -> "local variable"
                    is IrFunction -> "function"
                    else -> "declaration"
                },
            label =
                when (this) {
                    is IrClass -> classId?.asString() ?: name.asString()
                    is IrProperty -> name.asString()
                    is IrVariable -> name.asString()
                    is IrFunction -> renderIdentity()
                    else -> renderIdentity()
                },
        )
    }

private fun org.jetbrains.kotlin.ir.expressions.IrConstructorCall.debugTypeclassTraceScope(
    kind: String,
    label: String,
): TypeclassTraceScope? {
    val annotationClass = symbol.owner.parentAsClass
    if (annotationClass.classId != DEBUG_TYPECLASS_RESOLUTION_ANNOTATION_CLASS_ID) {
        return null
    }
    return TypeclassTraceScope(
        mode = debugTypeclassTraceMode() ?: TypeclassTraceMode.FAILURES,
        kind = kind,
        label = label,
    )
}

private fun org.jetbrains.kotlin.ir.expressions.IrConstructorCall.debugTypeclassTraceMode(): TypeclassTraceMode? {
    val annotationClass = symbol.owner.parentAsClass
    if (annotationClass.classId != DEBUG_TYPECLASS_RESOLUTION_ANNOTATION_CLASS_ID) {
        return null
    }
    return when (val modeArgument = getValueArgument(0)) {
        null -> TypeclassTraceMode.FAILURES
        is org.jetbrains.kotlin.ir.expressions.IrGetEnumValue ->
            modeArgument.symbol.owner.takeIf { enumEntry ->
                enumEntry.parentAsClass.classId == TYPECLASS_TRACE_MODE_CLASS_ID
            }?.name?.asString()?.let { entryName ->
                when (entryName) {
                    "INHERIT" -> TypeclassTraceMode.INHERIT
                    "DISABLED" -> TypeclassTraceMode.DISABLED
                    "FAILURES" -> TypeclassTraceMode.FAILURES
                    "FAILURES_AND_ALTERNATIVES" -> TypeclassTraceMode.FAILURES_AND_ALTERNATIVES
                    "ALL" -> TypeclassTraceMode.ALL
                    "ALL_AND_ALTERNATIVES" -> TypeclassTraceMode.ALL_AND_ALTERNATIVES
                    else -> null
                }
            }

        else -> null
    }
}

private fun IrClass.derivationTraceActivation(configuration: TypeclassConfiguration): TypeclassTraceActivation? {
    val scopes = mutableListOf<TypeclassTraceScope>()
    var current: IrDeclarationParent? = this
    while (current != null) {
        when (current) {
            is IrDeclarationBase -> current.traceScopeOrNull()?.let(scopes::add)
            is IrFile -> current.traceScopeOrNull()?.let(scopes::add)
        }
        current =
            when (current) {
                is IrDeclaration -> current.parent
                else -> null
            }
    }
    return resolveTraceActivation(scopes.asReversed(), configuration.traceMode)
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

internal class DeriverReturnValidationCache<M : Any> {
    private val values: MutableMap<Pair<M, String>, Boolean> = linkedMapOf()

    fun getOrPut(
        method: M,
        expectedTypeclassId: String,
        compute: () -> Boolean,
    ): Boolean = values.getOrPut(method to expectedTypeclassId, compute)
}

private fun IrSimpleFunction.knownDeriverReturnExpressions(): List<IrExpression> {
    val body = body ?: return emptyList()
    val returnExpressions = linkedSetOf<IrExpression>()

    fun record(expression: IrExpression?) {
        expression?.let(returnExpressions::add)
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
                if (expression.returnTargetSymbol == this@knownDeriverReturnExpressions.symbol) {
                    record(expression.value)
                    return
                }
                super.visitReturn(expression)
            }
        },
    )
    return returnExpressions.toList()
}

private fun IrExpression.knownReturnedTypeclassConstructors(
    configuration: TypeclassConfiguration,
    visitedFunctions: MutableSet<IrSimpleFunction> = linkedSetOf(),
    visitedVariables: MutableSet<IrVariable> = linkedSetOf(),
): List<String> =
    knownReturnedExpressionOrSelf()
        .knownReturnedTypeclassConstructorsOrEmpty(
            configuration = configuration,
            visitedFunctions = visitedFunctions,
            visitedVariables = visitedVariables,
        )

private fun IrExpression.knownReturnedExpressionOrSelf(): IrExpression =
    when (this) {
        is IrReturn -> value.knownReturnedExpressionOrSelf()
        is IrTypeOperatorCall -> argument.knownReturnedExpressionOrSelf()
        is IrComposite -> (statements.lastOrNull() as? IrExpression)?.knownReturnedExpressionOrSelf() ?: this
        is org.jetbrains.kotlin.ir.expressions.IrBlock ->
            (statements.lastOrNull() as? IrExpression)?.knownReturnedExpressionOrSelf() ?: this
        else -> this
    }

private fun IrExpression.knownReturnedImplementationOwners(): List<IrClass> =
    when (this) {
        is IrTypeOperatorCall -> argument.knownReturnedImplementationOwners()
        is IrConstructorCall -> listOf(symbol.owner.parentAsClass)
        is IrGetObjectValue -> listOf(symbol.owner)
        is IrComposite ->
            buildList {
                addAll(statements.filterIsInstance<IrClass>())
                (statements.lastOrNull() as? IrExpression)?.let { lastExpression ->
                    addAll(lastExpression.knownReturnedImplementationOwners())
                }
            }
        is org.jetbrains.kotlin.ir.expressions.IrBlock ->
            buildList {
                addAll(statements.filterIsInstance<IrClass>())
                (statements.lastOrNull() as? IrExpression)?.let { lastExpression ->
                    addAll(lastExpression.knownReturnedImplementationOwners())
                }
            }
        else -> type.classOrNull?.owner?.let(::listOf).orEmpty()
    }

private fun IrExpression.knownReturnedTypeclassConstructorsOrEmpty(
    configuration: TypeclassConfiguration,
    visitedFunctions: MutableSet<IrSimpleFunction>,
    visitedVariables: MutableSet<IrVariable>,
): List<String> {
    val expression = knownReturnedExpressionOrSelf()
    val direct =
        listOf(expression.type)
            .providedTypeExpansion(emptyMap(), configuration)
            .validTypes
            .mapNotNull { providedType -> (providedType as? TcType.Constructor)?.classifierId }
            .distinct()
    val result = linkedSetOf<String>()
    result += direct
    val visited = linkedSetOf<String>()

    fun visitSuperType(superType: IrType) {
        val simpleType = superType as? IrSimpleType ?: return
        val currentClass = simpleType.classOrNull?.owner ?: return
        val visitKey = currentClass.classId?.asString() ?: currentClass.name.asString()
        if (!visited.add(visitKey)) {
            return
        }
        if (currentClass.hasAnnotation(TYPECLASS_ANNOTATION_CLASS_ID)) {
            val classifierId =
                (irTypeToModel(superType, emptyMap()) as? TcType.Constructor)?.classifierId
            if (classifierId != null) {
                result += classifierId
            }
        }
        currentClass.superTypes.forEach(::visitSuperType)
    }

    expression.knownReturnedImplementationOwners()
        .distinctBy { owner -> owner.classId?.asString() ?: owner.name.asString() }
        .forEach { owner ->
            owner.superTypes.forEach(::visitSuperType)
        }
    fun addNested(nested: IrExpression) {
        nested.knownReturnedTypeclassConstructors(
            configuration = configuration,
            visitedFunctions = visitedFunctions,
            visitedVariables = visitedVariables,
        ).forEach(result::add)
    }
    when (expression) {
        is IrWhen ->
            expression.branches.forEach { branch ->
                addNested(branch.result)
            }

        is IrTry -> {
            addNested(expression.tryResult)
            expression.catches.forEach { catch ->
                addNested(catch.result)
            }
        }

        is IrGetValue -> {
            val variable = expression.symbol.owner as? IrVariable
            if (variable != null && visitedVariables.add(variable)) {
                try {
                    variable.initializer
                        ?.let(::addNested)
                } finally {
                    visitedVariables.remove(variable)
                }
            }
        }

        is IrCall -> {
            val function = expression.symbol.owner
            if (visitedFunctions.add(function)) {
                try {
                    function.knownDeriverReturnExpressions().forEach(::addNested)
                } finally {
                    visitedFunctions.remove(function)
                }
            }
        }
    }
    return result.toList()
}

private fun String.shortClassNameOrSelf(): String =
    runCatching { ClassId.fromString(this).shortClassName.asString() }.getOrDefault(this)

private fun IrDeclaration.isVisibleTypeclassRuleCandidate(): Boolean {
    val visibleDeclaration = this as? IrDeclarationWithVisibility ?: return false
    if (visibleDeclaration.visibility == DescriptorVisibilities.PRIVATE) {
        return false
    }
    if (origin == IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB && visibleDeclaration.visibility != DescriptorVisibilities.PUBLIC) {
        return false
    }
    return true
}

private fun IrSimpleFunction.isValidTypeclassInstanceFunctionRuleCandidate(): Boolean {
    if (isSuspend) {
        return false
    }
    if (extensionReceiverParameter != null) {
        return false
    }
    if (parameters.any { it.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Regular }) {
        return false
    }
    return true
}

private fun IrProperty.isValidTypeclassInstancePropertyRuleCandidate(): Boolean {
    if (isVar || isLateinit) {
        return false
    }
    val getter = getter ?: return false
    if (getter.extensionReceiverParameter != null) {
        return false
    }
    if (getter.body != null && getter.origin != IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR) {
        return false
    }
    return true
}

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

    fun originalForWrapperLikeFunction(wrapperLikeFunction: IrSimpleFunction): IrSimpleFunction? {
        val callableId = wrapperLikeFunction.safeCallableIdOrNull() ?: return null
        val candidates = originalsByCallableId[callableId].orEmpty()
        return candidates.singleOrNull { candidate ->
            wrapperResolutionShape(candidate, dropTypeclassContexts = true, configuration = configuration) ==
                wrapperResolutionShape(wrapperLikeFunction, dropTypeclassContexts = false, configuration = configuration)
        }
    }

    fun ruleById(ruleId: String): ResolvedRule? = rulesById[ruleId] ?: lazilyDiscoveredRulesById[ruleId]

    fun resolveLookupFunction(
        reference: RuleReference.LookupFunction,
        targetRule: InstanceRule,
    ): IrSimpleFunction? {
        val candidates = pluginContext.referenceFunctions(reference.callableId).map { it.owner }
        val shapeMatchedCandidates =
            candidates.filter { function ->
                lookupFunctionShape(function, dropTypeclassContexts = false, configuration = configuration) == reference.shape
            }
        val ownerMatchedCandidates =
            shapeMatchedCandidates.filter { function ->
                reference.ownerKey == null || function.lookupOwnerKeyOrNull() == reference.ownerKey
            }
        return ownerMatchedCandidates.singleOrNull()
            ?: shapeMatchedCandidates.singleOrNull { function ->
                function.matchesImportedLookupRule(targetRule, configuration)
            }
    }

    fun resolveLookupProperty(
        reference: RuleReference.LookupProperty,
        targetRule: InstanceRule,
    ): IrProperty? {
        val candidates = pluginContext.referenceProperties(reference.callableId).map { it.owner }
        val ownerMatchedCandidates =
            candidates.filter { property ->
                reference.ownerKey == null || property.lookupOwnerKeyOrNull() == reference.ownerKey
            }
        return ownerMatchedCandidates.singleOrNull()
            ?: candidates.singleOrNull { property ->
                property.matchesImportedLookupRule(targetRule, configuration)
            }
    }

    fun rulesForGoal(
        goal: TcType,
        canMaterializeVariable: (String) -> Boolean = { true },
        builtinGoalAcceptance: BuiltinGoalAcceptance = BuiltinGoalAcceptance.ALLOW_SPECULATIVE,
        exactBuiltinGoalContext: IrBuiltinGoalExactContext? = null,
    ): List<InstanceRule> {
        val owners = associatedOwnersForGoal(goal)
        val associated =
            owners.flatMapTo(linkedSetOf()) { owner ->
                associatedRulesByOwner[owner].orEmpty() + discoverAssociatedRules(owner)
            }
        val resolvedRules =
            (topLevelRules + associated)
            .asSequence()
            .filter { resolvedRule ->
                builtinRuleCanMatchGoalHead(resolvedRule.rule.id, goal)
            }
            .filter { resolvedRule ->
                resolvedRule.rule.id != "builtin:kclass" || supportsBuiltinKClassGoal(goal, canMaterializeVariable)
            }
            .filter { resolvedRule ->
                resolvedRule.rule.id != "builtin:subtype" ||
                    builtinGoalAcceptance.accepts(
                        irBuiltinSubtypeFeasibility(goal, classInfoById, exactBuiltinGoalContext),
                    )
            }
            .filter { resolvedRule ->
                resolvedRule.rule.id != "builtin:strict-subtype" ||
                    builtinGoalAcceptance.accepts(
                        irBuiltinStrictSubtypeFeasibility(goal, classInfoById, exactBuiltinGoalContext),
                    )
            }
            .filter { resolvedRule ->
                resolvedRule.rule.id != "builtin:kserializer" || supportsBuiltinKSerializerGoal(goal, pluginContext, canMaterializeVariable)
            }
            .filter { resolvedRule ->
                resolvedRule.rule.id != "builtin:notsame" || supportsBuiltinNotSameGoal(goal)
            }
            .filter { resolvedRule ->
                resolvedRule.rule.id != "builtin:nullable" ||
                    builtinGoalAcceptance.accepts(
                        irBuiltinNullableFeasibility(goal, exactBuiltinGoalContext),
                    )
            }
            .filter { resolvedRule ->
                resolvedRule.rule.id != "builtin:not-nullable" ||
                    builtinGoalAcceptance.accepts(
                        irBuiltinNotNullableFeasibility(goal, exactBuiltinGoalContext),
                    )
            }
            .filter { resolvedRule ->
                resolvedRule.rule.id != "builtin:is-typeclass-instance" ||
                    builtinGoalAcceptance.accepts(
                        irBuiltinIsTypeclassInstanceFeasibility(
                            goal = goal,
                            exactBuiltinGoalContext = exactBuiltinGoalContext,
                            isTypeclassClassifier = { classifierId ->
                                pluginContext.supportsTypeclassClassifierId(classifierId, configuration)
                            },
                        ),
                    )
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
        return resolvedRules
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
        lazilyDiscoveredAssociatedRulesByOwner.getOrPut(owner) {
            val ownerClass = discoverReferencedClass(owner) ?: return@getOrPut emptyList()
            val companionRules =
                if (associatedRulesByOwner.containsKey(owner)) {
                    emptyList()
                } else {
                    directOrNestedCompanion(
                        owner = owner,
                        directCompanion = ownerClass.declarations.filterIsInstance<IrClass>().firstOrNull(IrClass::isCompanion),
                        nestedLookup = { null },
                    )
                        ?.let { companion ->
                            buildList {
                                collectAssociatedRules(
                                    declaration = companion,
                                    associatedOwner = owner,
                                    sink = this,
                                )
                            }
                        }.orEmpty()
                }
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
        if (!hasAnnotation(INSTANCE_ANNOTATION_CLASS_ID) || !irInstanceOwnerContext(this).isIndexableScope || !isVisibleTypeclassRuleCandidate()) {
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
        if (!hasAnnotation(INSTANCE_ANNOTATION_CLASS_ID) || !irInstanceOwnerContext(this).isIndexableScope || !isVisibleTypeclassRuleCandidate()) {
            return emptyList()
        }
        if (!isValidTypeclassInstanceFunctionRuleCandidate()) {
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
        if (!hasAnnotation(INSTANCE_ANNOTATION_CLASS_ID) || !irInstanceOwnerContext(this).isIndexableScope || !isVisibleTypeclassRuleCandidate()) {
            return emptyList()
        }
        if (!isValidTypeclassInstancePropertyRuleCandidate()) {
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
            val scanner = IrModuleScanner(pluginContext, sharedState.configuration, sharedState)
            moduleFragment.files.forEach(scanner::scanFile)
            scanner.publishDirectDerivedMetadata()

            val directCompanionRules = scanner.buildCompanionRules()
            val directTopLevelRules = scanner.buildTopLevelRules()
            val localTopLevelLookupKeys =
                directTopLevelRules.mapNotNullTo(linkedSetOf()) { resolvedRule ->
                    resolvedRule.reference.lookupIdentityKeyOrNull(sharedState.configuration)
                }
            val importedDependencyTopLevelRules =
                sharedState.importedTopLevelRulesForIr()
                    .filterNot { importedRule ->
                        importedRule.reference.lookupIdentityKey() in localTopLevelLookupKeys
                    }.map { importedRule ->
                        ResolvedRule(
                            rule = importedRule.rule,
                            reference = importedRule.reference.toIrRuleReference(),
                            associatedOwner = null,
                        )
                    }
            val builtinRules = scanner.buildBuiltinRules()
            val topLevelRules = directTopLevelRules + importedDependencyTopLevelRules + builtinRules
            val associatedSourceRules = directCompanionRules
            val allRules = topLevelRules + directCompanionRules
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

private fun VisibleRuleLookupReference.toIrRuleReference(): RuleReference =
    when (this) {
        is VisibleRuleLookupReference.LookupFunction ->
            RuleReference.LookupFunction(
                callableId = callableId,
                shape = shape,
                ownerKey = ownerKey,
            )

        is VisibleRuleLookupReference.LookupProperty ->
            RuleReference.LookupProperty(callableId, ownerKey = ownerKey)

        is VisibleRuleLookupReference.LookupObject ->
            RuleReference.LookupObject(classId)
    }

private fun RuleReference.lookupIdentityKeyOrNull(configuration: TypeclassConfiguration): String? =
    when (this) {
        is RuleReference.DirectFunction ->
            "fun:${function.lookupOwnerKeyOrNull() ?: "-"}:${function.callableId}:${lookupFunctionShape(function, dropTypeclassContexts = false, configuration = configuration)}"
        is RuleReference.DirectProperty -> "prop:${property.lookupOwnerKeyOrNull() ?: "-"}:${property.callableId}"
        is RuleReference.DirectObject -> "obj:${klass.classIdOrFail.asString()}"
        is RuleReference.LookupFunction -> "fun:${ownerKey ?: "-"}:${callableId}:$shape"
        is RuleReference.LookupProperty -> "prop:${ownerKey ?: "-"}:${callableId}"
        is RuleReference.LookupObject -> "obj:${classId.asString()}"
        else -> null
    }

private class IrModuleScanner(
    private val pluginContext: IrPluginContext,
    private val configuration: TypeclassConfiguration,
    private val sharedState: TypeclassPluginSharedState,
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

    fun publishDirectDerivedMetadata() {
        val subclassesBySuper = subclassesBySuper()
        val metadataEntries =
            buildList {
                declaredDerivationsByClassId.forEach { (classId, typeclassIds) ->
                    val klass = classesById[classId] ?: return@forEach
                    typeclassIds.forEach { typeclassId ->
                        klass.directDerivedMetadata(
                            typeclassId = typeclassId,
                            subclassesBySuper = subclassesBySuper,
                        )?.let { metadata ->
                            add(OwnedGeneratedDerivedMetadata(owner = klass, metadata = metadata))
                        }
                    }
                }
                classesById.values.forEach { klass ->
                    addAll(klass.directDerivedEquivMetadata())
                    addAll(klass.directDerivedViaMetadata())
                }
            }
        recordGeneratedDerivedMetadata(metadataEntries)
    }

    fun registerDiscoveredClass(declaration: IrClass) {
        val classId = declaration.classId ?: return
        val classKey = classId.asString()
        if (classesById.containsKey(classKey) && classInfoById.containsKey(classKey)) {
            return
        }
        classesById[classKey] = declaration
        declaredDerivationsByClassId.putIfAbsent(classKey, declaration.shapeDerivedTypeclassIds())
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
        return buildList {
            addAll(
                applicableDerivedTypeclassIdsForOwner(classifierId).flatMap { typeclassId ->
                    ownerClass.toDerivedRules(typeclassId, subclassesBySuper)
                },
            )
            addAll(ownerClass.toDerivedEquivRules())
            addAll(ownerClass.toDeriveViaRules())
        }
    }

    private fun recordGeneratedDerivedMetadata(
        entries: List<OwnedGeneratedDerivedMetadata>,
    ) {
        val metadataByOwner = linkedMapOf<IrClass, MutableSet<GeneratedDerivedMetadata>>()
        entries.forEach { entry ->
            if (entry.owner.origin == IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB) {
                return@forEach
            }
            metadataByOwner.getOrPut(entry.owner, ::linkedSetOf) += entry.metadata
        }

        metadataByOwner.forEach { (owner, entries) ->
            owner.recordGeneratedDerivedTypeclassMetadata(entries.toList())
        }
    }

    private data class OwnedGeneratedDerivedMetadata(
        val owner: IrClass,
        val metadata: GeneratedDerivedMetadata,
    )

    private fun applicableDerivedTypeclassIdsForOwner(classifierId: String): Set<ClassId> =
        sealedOwnerChain(classifierId).flatMapTo(linkedSetOf()) { ownerId ->
            declaredDerivationsByClassId[ownerId].orEmpty()
        }

    private fun subclassesBySuper(): Map<String, Set<String>> =
        classInfoById.entries
            .fold(linkedMapOf<String, MutableSet<String>>()) { acc, (classId, info) ->
                info.superClassifiers.forEach { superClassifier ->
                    acc.getOrPut(superClassifier, ::linkedSetOf) += classId
                }
                acc
            }.mapValues { (_, subclassIds) ->
                subclassIds.sorted().toCollection(linkedSetOf())
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
                    val supportedDerivedTypeclassIds = declaration.shapeDerivedTypeclassIds()
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
        if (!irInstanceOwnerContext(this).isIndexableScope || !isVisibleTypeclassRuleCandidate()) {
            return emptyList()
        }
        val providedTypeExpansion = superTypes.providedTypeExpansion(emptyMap(), configuration)
        val providedTypes = providedTypeExpansion.validTypes
        if (associatedOwner == null && !isLegalTopLevelInstanceLocation(providedTypeExpansion.declaredTypes, classesById)) {
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
        if (!irInstanceOwnerContext(this).isIndexableScope || !isVisibleTypeclassRuleCandidate()) {
            return emptyList()
        }
        if (!isValidTypeclassInstanceFunctionRuleCandidate()) {
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

        val providedTypeExpansion = listOf(returnType).providedTypeExpansion(typeParameterBySymbol, configuration)
        val providedTypes = providedTypeExpansion.validTypes
        if (associatedOwner == null && !isLegalTopLevelInstanceLocation(providedTypeExpansion.declaredTypes, classesById)) {
            return emptyList()
        }
        val declarationKey =
            RuleReference.LookupFunction(
                callableId = callableId,
                shape = lookupFunctionShape(this, dropTypeclassContexts = false, configuration = configuration),
                ownerKey = lookupOwnerKeyOrNull(),
            ).lookupIdentityKeyOrNull(configuration)
                ?: "fun:${lookupOwnerKeyOrNull() ?: "-"}:${callableId}"
        return providedTypes.map { providedType ->
            ResolvedRule(
                rule =
                    InstanceRule(
                        id = directRuleId(
                            prefix = idPrefix,
                            declarationKey = declarationKey,
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
        if (!irInstanceOwnerContext(this).isIndexableScope || !isVisibleTypeclassRuleCandidate()) {
            return emptyList()
        }
        if (!isValidTypeclassInstancePropertyRuleCandidate()) {
            return emptyList()
        }
        val providedTypeExpansion = listOf(backingFieldOrGetterType()).providedTypeExpansion(emptyMap(), configuration)
        val providedTypes = providedTypeExpansion.validTypes
        if (associatedOwner == null && !isLegalTopLevelInstanceLocation(providedTypeExpansion.declaredTypes, classesById)) {
            return emptyList()
        }
        val declarationKey =
            RuleReference.LookupProperty(
                callableId = callableId,
                ownerKey = lookupOwnerKeyOrNull(),
            ).lookupIdentityKeyOrNull(configuration)
                ?: "prop:${lookupOwnerKeyOrNull() ?: "-"}:${callableId}"
        return providedTypes.map { providedType ->
            ResolvedRule(
                rule =
                    InstanceRule(
                        id = directRuleId(
                            prefix = idPrefix,
                            declarationKey = declarationKey,
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

    private fun IrClass.directDerivedMetadata(
        typeclassId: ClassId,
        subclassesBySuper: Map<String, Set<String>>,
    ): GeneratedDerivedMetadata.Derive? {
        val targetClassId = classId ?: return null
        if (toDerivedRules(typeclassId, subclassesBySuper).isEmpty()) {
            return null
        }
        return GeneratedDerivedMetadata.Derive(
            typeclassId = typeclassId,
            targetId = targetClassId,
        )
    }

    private fun IrClass.directDerivedEquivMetadata(): List<OwnedGeneratedDerivedMetadata> {
        val targetClassId = classId ?: return emptyList()
        val fallbackGoal = "${EQUIV_CLASS_ID.asString()}<${targetClassId.asString()},?>"
        val requestSpecs =
            deriveEquivRequests()
                .map { request -> request.otherClassId to compilerMessageLocation(request.annotation) }
                .distinctBy { (otherClassId, _) -> otherClassId.asString() }
        if (requestSpecs.isEmpty()) {
            return emptyList()
        }
        if (typeParameters.isNotEmpty()) {
            requestSpecs.forEach { (_, location) ->
                pluginContext.reportCannotDeriveWithTrace(
                    owner = this,
                    configuration = configuration,
                    goal = fallbackGoal,
                    message = "@DeriveEquiv only supports monomorphic classes for now",
                    location = location,
                )
            }
            return emptyList()
        }
        val planner = DirectTransportPlanner(pluginContext)
        return requestSpecs.mapNotNull { (otherClassId, location) ->
            val otherClass =
                pluginContext.referenceClass(otherClassId)?.owner
                    ?: return@mapNotNull null
            if (otherClass.typeParameters.isNotEmpty()) {
                pluginContext.reportCannotDeriveWithTrace(
                    owner = this,
                    configuration = configuration,
                    goal = "${EQUIV_CLASS_ID.asString()}<${targetClassId.asString()},${otherClassId.asString()}>",
                    message = "@DeriveEquiv only supports monomorphic classes for now",
                    location = location,
                )
                return@mapNotNull null
            }
            val plans = planner.planEquiv(symbol.defaultType, otherClass.symbol.defaultType)
            if (plans == null) {
                pluginContext.reportCannotDeriveWithTrace(
                    owner = this,
                    configuration = configuration,
                    goal = "${EQUIV_CLASS_ID.asString()}<${targetClassId.asString()},${otherClassId.asString()}>",
                    message = "Cannot derive Equiv between ${targetClassId.asString()} and ${otherClassId.asString()}",
                    location = location,
                )
                return@mapNotNull null
            }
            OwnedGeneratedDerivedMetadata(
                owner = this,
                metadata =
                    GeneratedDerivedMetadata.DeriveEquiv(
                        targetId = targetClassId,
                        otherClassId = otherClassId,
                    ),
            )
        }
    }

    private fun IrClass.directDerivedViaMetadata(): List<OwnedGeneratedDerivedMetadata> {
        val targetClassId = classId ?: return emptyList()
        val requestSpecs =
            deriveViaRequests(pluginContext)
                .map { request ->
                    Triple(
                        request.typeclassId,
                        request.path,
                        compilerMessageLocation(request.annotation),
                    )
                }.distinctBy { (typeclassId, path, _) ->
                    buildString {
                        append(typeclassId.asString())
                        append(':')
                        append(path.joinToString("|") { segment ->
                            val prefix =
                                when (segment) {
                                    is DeriveViaPathSegment.Waypoint -> "W"
                                    is DeriveViaPathSegment.PinnedIso -> "I"
                                }
                            "$prefix:${segment.classId.asString()}"
                        })
                    }
                }
        if (requestSpecs.isEmpty()) {
            return emptyList()
        }
        if (typeParameters.isNotEmpty()) {
            requestSpecs.forEach { (_, _, location) ->
                pluginContext.reportCannotDeriveWithTrace(
                    owner = this,
                    configuration = configuration,
                    goal = "derive-via:${targetClassId.asString()}",
                    message = "@DeriveVia only supports monomorphic classes for now",
                    location = location,
                )
            }
            return emptyList()
        }
        val planner = DirectTransportPlanner(pluginContext)
        return requestSpecs.mapNotNull { (typeclassId, path, location) ->
            val rootGoal = "${typeclassId.asString()}<${targetClassId.asString()}>"
            if (path.isEmpty()) {
                pluginContext.reportCannotDeriveWithTrace(
                    owner = this,
                    configuration = configuration,
                    goal = rootGoal,
                    message = "Cannot derive via an empty path",
                    location = location,
                )
                return@mapNotNull null
            }
            val typeclassInterface = pluginContext.referenceClass(typeclassId)?.owner ?: return@mapNotNull null
            if (!typeclassInterface.hasAnnotation(TYPECLASS_ANNOTATION_CLASS_ID)) {
                return@mapNotNull null
            }
            if (typeclassInterface.typeParameters.isEmpty()) {
                pluginContext.reportCannotDeriveWithTrace(
                    owner = this,
                    configuration = configuration,
                    goal = rootGoal,
                    message = "DeriveVia requires a typeclass with at least one type parameter",
                    location = location,
                )
                return@mapNotNull null
            }
            typeclassInterface.validateDeriveViaTransportability()?.let { message ->
                pluginContext.reportCannotDeriveWithTrace(
                    owner = this,
                    configuration = configuration,
                    goal = rootGoal,
                    message = message,
                    location = location,
                )
                return@mapNotNull null
            }
            val resolvedPath =
                planner.resolveViaPath(symbol.defaultType, path)
                    .getOrElse { error ->
                        pluginContext.reportCannotDeriveWithTrace(
                            owner = this,
                            configuration = configuration,
                            goal = rootGoal,
                            message = error.message ?: "Failed to resolve DeriveVia path",
                            location = location,
                        )
                        return@mapNotNull null
                    }
            val targetType = TcType.Constructor(targetClassId.asString(), emptyList())
            val viaTypeModel =
                irTypeToModel(resolvedPath.viaType, emptyMap()) ?: run {
                    pluginContext.reportCannotDeriveWithTrace(
                        owner = this,
                        configuration = configuration,
                        goal = rootGoal,
                        message = "DeriveVia terminal type must be representable as a typeclass goal",
                        location = location,
                    )
                    return@mapNotNull null
                }
            if (viaTypeModel.normalizedKey() == targetType.normalizedKey()) {
                pluginContext.reportCannotDeriveWithTrace(
                    owner = this,
                    configuration = configuration,
                    goal = rootGoal,
                    message = "DeriveVia path must not resolve back to the annotated class",
                    location = location,
                )
                return@mapNotNull null
            }
            pluginContext.reportDerivationSuccessTrace(
                owner = this,
                configuration = configuration,
                goal = rootGoal,
                location = location,
                extraLines =
                    listOf(
                        "authored path: ${path.joinToString(" -> ") { segment -> segment.traceRender() }}",
                        "normalized plan: ${resolvedPath.forwardPlan.traceRender()}",
                    ),
            )
            OwnedGeneratedDerivedMetadata(
                owner = this,
                metadata =
                    GeneratedDerivedMetadata.DeriveVia(
                        typeclassId = typeclassId,
                        targetId = targetClassId,
                        path = path.map { segment -> segment.toGeneratedMetadataPathSegment() },
                    ),
            )
        }
    }

    private fun IrClass.toDerivedRules(
        typeclassId: ClassId,
        subclassesBySuper: Map<String, Set<String>>,
    ): List<ResolvedRule> {
        val targetClassId = classId ?: return emptyList()
        val rootGoal = "${typeclassId.asString()}<${targetClassId.asString()}>"
        if (!supportsDeriveShape()) {
            pluginContext.reportCannotDeriveWithTrace(
                owner = this,
                configuration = configuration,
                goal = rootGoal,
                message = "@Derive is only supported on sealed or final classes and objects",
                location = compilerMessageLocation(),
            )
            return emptyList()
        }
        val typeclassInterface = pluginContext.referenceClass(typeclassId)?.owner ?: return emptyList()
        if (!typeclassInterface.hasAnnotation(TYPECLASS_ANNOTATION_CLASS_ID)) {
            return emptyList()
        }
        if (typeclassInterface.typeParameters.size != 1) {
            pluginContext.reportCannotDeriveWithTrace(
                owner = this,
                configuration = configuration,
                goal = rootGoal,
                message = "@Derive currently only supports typeclasses with exactly one type parameter",
                location = compilerMessageLocation(),
            )
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
        val derivedSpecs =
            when {
                kind == ClassKind.ENUM_CLASS ->
                    buildDerivedEnumShape(rootGoal)?.let { shape ->
                        listOf(
                            DerivedRuleSpec(
                                targetType = targetType,
                                shape = shape,
                                ruleTypeParameters = ruleTypeParameters,
                            ),
                        )
                    }

                modality == Modality.SEALED ->
                    buildDerivedSumRuleSpecs(
                        typeclassInterface = typeclassInterface,
                        rootTargetType = targetType,
                        subclassesBySuper = subclassesBySuper,
                        rootGoal = rootGoal,
                    )

                else ->
                    buildDerivedProductShape(typeParameterBySymbol, rootGoal)?.let { shape ->
                        listOf(
                            DerivedRuleSpec(
                                targetType = targetType,
                                shape = shape,
                                ruleTypeParameters = ruleTypeParameters,
                            ),
                        )
                    }
            } ?: return emptyList()
        val representativeShape = derivedSpecs.firstOrNull()?.shape ?: return emptyList()
        val requiredDeriverInterface =
            when (representativeShape) {
                is DerivedShape.Product -> PRODUCT_TYPECLASS_DERIVER_CLASS_ID
                is DerivedShape.Sum, is DerivedShape.Enum -> TYPECLASS_DERIVER_CLASS_ID
            }
        val deriverCompanion =
            typeclassInterface.findDeriverCompanion(requiredDeriverInterface)
                ?: run {
                    val requiredName = requiredDeriverInterface.shortClassName.asString()
                    val message =
                        if (representativeShape is DerivedShape.Enum) {
                            "${typeclassId.shortClassName.asString()} companion must implement $requiredName; ProductTypeclassDeriver only supports products, not enums"
                        } else if (representativeShape is DerivedShape.Sum) {
                            "${typeclassId.shortClassName.asString()} companion must implement $requiredName; ProductTypeclassDeriver only supports products, not sealed sums"
                        } else {
                            "${typeclassId.shortClassName.asString()} companion must implement $requiredName to derive products"
                        }
                    pluginContext.reportCannotDeriveWithTrace(
                        owner = this,
                        configuration = configuration,
                        goal = rootGoal,
                        message = message,
                        location = compilerMessageLocation(),
                    )
                    return emptyList()
                }
        val deriveMethodContract =
            when (representativeShape) {
                is DerivedShape.Product -> DeriveMethodContract.PRODUCT
                is DerivedShape.Sum -> DeriveMethodContract.SUM
                is DerivedShape.Enum -> DeriveMethodContract.ENUM
            }
        val deriveMethod =
            deriverCompanion.resolveDeriveMethod(deriveMethodContract)
                ?: run {
            val message =
                if (representativeShape is DerivedShape.Enum) {
                    "${typeclassId.shortClassName.asString()} companion must override deriveEnum to derive enum classes"
                } else {
                    "Typeclass deriver ${deriverCompanion.classIdOrFail.asString()} is missing ${deriveMethodContract.methodName}"
                }
            pluginContext.reportCannotDeriveWithTrace(
                owner = this,
                configuration = configuration,
                goal = rootGoal,
                message = message,
                location = compilerMessageLocation(),
            )
            return emptyList()
        }
        return derivedSpecs.flatMap { spec ->
            val prerequisiteTypes =
                when (val shape = spec.shape) {
                    is DerivedShape.Product -> shape.fields.map { field -> typeclassGoal(typeclassId, field.type) }
                    is DerivedShape.Sum -> shape.cases.map { case -> typeclassGoal(typeclassId, case.type) }
                    is DerivedShape.Enum -> emptyList()
                }
            val providedTypes = expandDerivedProvidedTypes(typeclassId, spec.targetType)
            providedTypes.map { providedType ->
                ResolvedRule(
                    rule =
                        InstanceRule(
                            id =
                                directRuleId(
                                    prefix = "derived",
                                    declarationKey =
                                        "${typeclassId.asString()}:${targetClassId.asString()}:${spec.targetType.normalizedKey()}",
                                    providedType = providedType,
                                    prerequisiteTypes = prerequisiteTypes,
                                    typeParameters = spec.ruleTypeParameters,
                                ),
                            typeParameters = spec.ruleTypeParameters,
                            providedType = providedType,
                            prerequisiteTypes = prerequisiteTypes,
                            supportsRecursiveResolution = true,
                            priority = spec.priority,
                        ),
                    reference =
                        RuleReference.Derived(
                            targetClass = this,
                            deriverCompanion = deriverCompanion,
                            deriveMethod = deriveMethod,
                            shape = spec.shape,
                            ruleTypeParameters = spec.ruleTypeParameters,
                        ),
                    associatedOwner = targetClassId,
                )
            }
        }
    }

    private fun DeriveViaPathSegment.toGeneratedMetadataPathSegment(): GeneratedDeriveViaPathSegment =
        when (this) {
            is DeriveViaPathSegment.Waypoint ->
                GeneratedDeriveViaPathSegment(
                    kind = GeneratedDeriveViaPathSegment.Kind.WAYPOINT,
                    classId = classId,
                )

            is DeriveViaPathSegment.PinnedIso ->
                GeneratedDeriveViaPathSegment(
                    kind = GeneratedDeriveViaPathSegment.Kind.PINNED_ISO,
                    classId = classId,
                )
        }

    private fun IrClass.toDerivedEquivRules(): List<ResolvedRule> {
        val targetClassId = classId ?: return emptyList()
        val fallbackGoal = "${EQUIV_CLASS_ID.asString()}<${targetClassId.asString()},?>"
        val explicitRequests = deriveEquivRequests()
        val generatedRequests = publishedGeneratedDerivedMetadata().filterIsInstance<GeneratedDerivedMetadata.DeriveEquiv>()
        val requestSpecs =
            if (origin == IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB) {
                generatedRequests.map { metadata ->
                    metadata.otherClassId to compilerMessageLocation()
                }
            } else if (explicitRequests.isNotEmpty()) {
                explicitRequests.map { request ->
                    request.otherClassId to compilerMessageLocation(request.annotation)
                }
            } else if (generatedRequests.isNotEmpty()) {
                generatedRequests.map { metadata ->
                    metadata.otherClassId to compilerMessageLocation()
                }
            } else {
                explicitRequests.map { request ->
                    request.otherClassId to compilerMessageLocation(request.annotation)
                }
            }.distinctBy { (otherClassId, _) -> otherClassId.asString() }
        if (typeParameters.isNotEmpty()) {
            requestSpecs.forEach { (_, location) ->
                pluginContext.reportCannotDeriveWithTrace(
                    owner = this,
                    configuration = configuration,
                    goal = fallbackGoal,
                    message = "@DeriveEquiv only supports monomorphic classes for now",
                    location = location,
                )
            }
            return emptyList()
        }
        val planner = DirectTransportPlanner(pluginContext)
        return requestSpecs.mapNotNull { (otherClassId, location) ->
            val otherClass =
                pluginContext.referenceClass(otherClassId)?.owner
                    ?: return@mapNotNull null
            if (otherClass.typeParameters.isNotEmpty()) {
                pluginContext.reportCannotDeriveWithTrace(
                    owner = this,
                    configuration = configuration,
                    goal = "${EQUIV_CLASS_ID.asString()}<${targetClassId.asString()},${otherClassId.asString()}>",
                    message = "@DeriveEquiv only supports monomorphic classes for now",
                    location = location,
                )
                return@mapNotNull null
            }
            val plans =
                planner.planEquiv(symbol.defaultType, otherClass.symbol.defaultType)
                    ?: run {
                        pluginContext.reportCannotDeriveWithTrace(
                            owner = this,
                            configuration = configuration,
                            goal = "${EQUIV_CLASS_ID.asString()}<${targetClassId.asString()},${otherClassId.asString()}>",
                            message =
                                "Cannot derive Equiv between ${targetClassId.asString()} and ${otherClassId.asString()}",
                            location = location,
                        )
                        return@mapNotNull null
                    }
            ResolvedRule(
                rule =
                    InstanceRule(
                        id = "derived-equiv:${targetClassId.asString()}:${otherClassId.asString()}",
                        typeParameters = emptyList(),
                        providedType =
                            TcType.Constructor(
                                classifierId = EQUIV_CLASS_ID.asString(),
                                arguments =
                                    listOf(
                                        TcType.Constructor(targetClassId.asString(), emptyList()),
                                        TcType.Constructor(otherClassId.asString(), emptyList()),
                                    ),
                            ),
                        prerequisiteTypes = emptyList(),
                    ),
                reference =
                    RuleReference.DerivedEquiv(
                        targetClass = this,
                        otherClassId = otherClassId,
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
        val explicitRequests = deriveViaRequests(pluginContext)
        val generatedRequests = publishedGeneratedDerivedMetadata().filterIsInstance<GeneratedDerivedMetadata.DeriveVia>()
        val requestSpecs =
            (if (origin == IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB) {
                generatedRequests.map { metadata ->
                    Triple(
                        metadata.typeclassId,
                        metadata.path.map { segment ->
                            when (segment.kind) {
                                GeneratedDeriveViaPathSegment.Kind.WAYPOINT ->
                                    DeriveViaPathSegment.Waypoint(segment.classId)

                                GeneratedDeriveViaPathSegment.Kind.PINNED_ISO ->
                                    DeriveViaPathSegment.PinnedIso(segment.classId)
                            }
                        },
                        compilerMessageLocation(),
                    )
                }
            } else if (explicitRequests.isNotEmpty()) {
                explicitRequests.map { request ->
                    Triple(
                        request.typeclassId,
                        request.path,
                        compilerMessageLocation(request.annotation),
                    )
                }
            } else if (generatedRequests.isNotEmpty()) {
                generatedRequests.map { metadata ->
                    Triple(
                        metadata.typeclassId,
                        metadata.path.map { segment ->
                            when (segment.kind) {
                                GeneratedDeriveViaPathSegment.Kind.WAYPOINT ->
                                    DeriveViaPathSegment.Waypoint(segment.classId)

                                GeneratedDeriveViaPathSegment.Kind.PINNED_ISO ->
                                    DeriveViaPathSegment.PinnedIso(segment.classId)
                            }
                        },
                        compilerMessageLocation(),
                    )
                }
            } else {
                explicitRequests.map { request ->
                    Triple(
                        request.typeclassId,
                        request.path,
                        compilerMessageLocation(request.annotation),
                    )
                }
            }).distinctBy { (typeclassId, path, _) ->
                buildString {
                    append(typeclassId.asString())
                    append(':')
                    append(path.joinToString("|") { segment ->
                        val prefix =
                            when (segment) {
                                is DeriveViaPathSegment.Waypoint -> "W"
                                is DeriveViaPathSegment.PinnedIso -> "I"
                            }
                        "$prefix:${segment.classId.asString()}"
                    })
                }
            }
        if (typeParameters.isNotEmpty()) {
            requestSpecs.forEach { (_, _, location) ->
                pluginContext.reportCannotDeriveWithTrace(
                    owner = this,
                    configuration = configuration,
                    goal = "derive-via:${targetClassId.asString()}",
                    message = "@DeriveVia only supports monomorphic classes for now",
                    location = location,
                )
            }
            return emptyList()
        }
        val planner = DirectTransportPlanner(pluginContext)
        return requestSpecs.flatMap { (typeclassId, path, location) ->
            val rootGoal = "${typeclassId.asString()}<${targetClassId.asString()}>"
            if (path.isEmpty()) {
                pluginContext.reportCannotDeriveWithTrace(
                    owner = this,
                    configuration = configuration,
                    goal = rootGoal,
                    message = "Cannot derive via an empty path",
                    location = location,
                )
                return@flatMap emptyList()
            }
            val typeclassInterface = pluginContext.referenceClass(typeclassId)?.owner ?: return@flatMap emptyList()
            if (!typeclassInterface.hasAnnotation(TYPECLASS_ANNOTATION_CLASS_ID)) {
                return@flatMap emptyList()
            }
            if (typeclassInterface.typeParameters.isEmpty()) {
                pluginContext.reportCannotDeriveWithTrace(
                    owner = this,
                    configuration = configuration,
                    goal = rootGoal,
                    message = "DeriveVia requires a typeclass with at least one type parameter",
                    location = location,
                )
                return@flatMap emptyList()
            }
            typeclassInterface.validateDeriveViaTransportability()?.let { message ->
                pluginContext.reportCannotDeriveWithTrace(
                    owner = this,
                    configuration = configuration,
                    goal = rootGoal,
                    message = message,
                    location = location,
                )
                return@flatMap emptyList()
            }
            val resolvedPath =
                planner.resolveViaPath(symbol.defaultType, path)
                    .getOrElse { error ->
                        pluginContext.reportCannotDeriveWithTrace(
                            owner = this,
                            configuration = configuration,
                            goal = rootGoal,
                            message = error.message ?: "Failed to resolve DeriveVia path",
                            location = location,
                        )
                        return@flatMap emptyList()
                    }
            val targetType =
                TcType.Constructor(targetClassId.asString(), emptyList())
            val viaTypeModel =
                irTypeToModel(resolvedPath.viaType, emptyMap()) ?: run {
                    pluginContext.reportCannotDeriveWithTrace(
                        owner = this,
                        configuration = configuration,
                        goal = rootGoal,
                        message = "DeriveVia terminal type must be representable as a typeclass goal",
                        location = location,
                    )
                    return@flatMap emptyList()
                }
            if (viaTypeModel.normalizedKey() == targetType.normalizedKey()) {
                pluginContext.reportCannotDeriveWithTrace(
                    owner = this,
                    configuration = configuration,
                    goal = rootGoal,
                    message = "DeriveVia path must not resolve back to the annotated class",
                    location = location,
                )
                return@flatMap emptyList()
            }
            pluginContext.reportDerivationSuccessTrace(
                owner = this,
                configuration = configuration,
                goal = rootGoal,
                location = location,
                extraLines =
                    listOf(
                        "authored path: ${path.joinToString(" -> ") { segment -> segment.traceRender() }}",
                        "normalized plan: ${resolvedPath.forwardPlan.traceRender()}",
                    ),
            )
            val expansions = expandedDerivedTypeclassHeads(typeclassId)
            val directTypeParameters = expansions.firstOrNull()?.directTypeParameters.orEmpty()
            val transportedParameter = directTypeParameters.lastOrNull() ?: return@flatMap emptyList()
            val prefixParameters = directTypeParameters.dropLast(1)
            val prefixBindings =
                prefixParameters.associate { parameter ->
                    parameter.id to TcType.Variable(parameter.id, parameter.displayName)
                }
            expansions.mapNotNull { expansion ->
                val providedType =
                    expansion.head.substituteType(prefixBindings + (transportedParameter.id to targetType)) as? TcType.Constructor
                        ?: return@mapNotNull null
                val prerequisiteType =
                    expansion.head.substituteType(prefixBindings + (transportedParameter.id to viaTypeModel)) as? TcType.Constructor
                        ?: return@mapNotNull null
                val expandedTypeclassId = runCatching { ClassId.fromString(providedType.classifierId) }.getOrNull() ?: return@mapNotNull null
                val expandedTypeclassInterface = pluginContext.referenceClass(expandedTypeclassId)?.owner ?: return@mapNotNull null
                ResolvedRule(
                    rule =
                        InstanceRule(
                            id =
                                "derived-via:${typeclassId.asString()}:${providedType.normalizedKey()}:${path.joinToString("|") { it.classId.asString() }}",
                            typeParameters = prefixParameters,
                            providedType = providedType,
                            prerequisiteTypes = listOf(prerequisiteType),
                        ),
                    reference =
                        RuleReference.DerivedVia(
                            targetClass = this,
                            authoredPath = path,
                            typeclassInterface = expandedTypeclassInterface,
                            targetType = symbol.defaultType,
                            viaType = resolvedPath.viaType,
                            forwardPlan = resolvedPath.forwardPlan,
                            backwardPlan = resolvedPath.backwardPlan,
                        ),
                    associatedOwner = targetClassId,
                )
            }
        }
    }

    private fun IrClass.buildDerivedProductShape(
        typeParameterBySymbol: Map<org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol, TcTypeParameter>,
        rootGoal: String,
    ): DerivedShape.Product? {
        val storedProperties = structuralProperties()
        storedProperties.firstOrNull { property ->
            property.visibility != DescriptorVisibilities.PUBLIC ||
                property.getter?.visibility != DescriptorVisibilities.PUBLIC
        }?.let { nonPublicProperty ->
            pluginContext.reportCannotDeriveWithTrace(
                owner = this,
                configuration = configuration,
                goal = rootGoal,
                message =
                    "constructive product derivation requires public stored properties; ${renderClassName()}.${nonPublicProperty.name.asString()} is not public.",
                location = compilerMessageLocation(),
            )
            return null
        }
        val fields =
            storedProperties.mapNotNull { property ->
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
            pluginContext.reportCannotDeriveWithTrace(
                owner = this,
                configuration = configuration,
                goal = rootGoal,
                message =
                    "Cannot derive ${classIdOrFail.asString()} because constructive product derivation requires a primary constructor",
                location = compilerMessageLocation(),
            )
            return null
        }
        if (constructor.visibility != DescriptorVisibilities.PUBLIC) {
            pluginContext.reportCannotDeriveWithTrace(
                owner = this,
                configuration = configuration,
                goal = rootGoal,
                message =
                    "constructive product derivation requires a public primary constructor for ${renderClassName()}.",
                location = compilerMessageLocation(),
            )
            return null
        }
        val constructorParameterNames = constructor.valueParameters.map { parameter -> parameter.name.asString() }
        val fieldNames = fields.map(DerivedField::name)
        if (constructorParameterNames != fieldNames) {
            pluginContext.reportCannotDeriveWithTrace(
                owner = this,
                configuration = configuration,
                goal = rootGoal,
                message =
                    "Cannot derive ${classIdOrFail.asString()} because constructive product derivation requires constructor parameters to exactly match stored properties",
                location = compilerMessageLocation(),
            )
            return null
        }
        return DerivedShape.Product(fields = fields, constructor = constructor)
    }

    private fun IrClass.exactShapeDerivationFailure(
        typeclassId: ClassId,
        targetType: TcType.Constructor,
        subclassesBySuper: Map<String, Set<String>>,
    ): String? {
        if (!supportsDeriveShape()) {
            return "@Derive is only supported on sealed or final classes and objects"
        }
        val typeclassInterface = pluginContext.referenceClass(typeclassId)?.owner ?: return null
        requiredDeriveMethodContractForDeriveShape()?.let { contract ->
            val requiredDeriverInterface =
                when (contract) {
                    DeriveMethodContract.PRODUCT -> PRODUCT_TYPECLASS_DERIVER_CLASS_ID
                    DeriveMethodContract.SUM, DeriveMethodContract.ENUM -> TYPECLASS_DERIVER_CLASS_ID
                }
            val deriverCompanion =
                typeclassInterface.findDeriverCompanion(requiredDeriverInterface)
                    ?: return when (contract) {
                        DeriveMethodContract.ENUM ->
                            "${typeclassId.shortClassName.asString()} companion must implement ${requiredDeriverInterface.shortClassName.asString()}; ProductTypeclassDeriver only supports products, not enums"

                        DeriveMethodContract.SUM ->
                            "${typeclassId.shortClassName.asString()} companion must implement ${requiredDeriverInterface.shortClassName.asString()}; ProductTypeclassDeriver only supports products, not sealed sums"

                        DeriveMethodContract.PRODUCT ->
                            "${typeclassId.shortClassName.asString()} companion must implement ${requiredDeriverInterface.shortClassName.asString()} to derive products"
                    }
            if (deriverCompanion.resolveDeriveMethod(contract) == null) {
                return if (contract == DeriveMethodContract.ENUM) {
                    "${typeclassId.shortClassName.asString()} companion must override deriveEnum to derive enum classes"
                } else {
                    "Typeclass deriver ${deriverCompanion.classIdOrFail.asString()} is missing ${contract.methodName}"
                }
            }
        }
        if (isObject || kind == ClassKind.ENUM_CLASS) {
            return null
        }
        if (modality == Modality.SEALED) {
            val buildResult =
                buildDerivedSumRuleSpecsResult(
                    typeclassInterface = typeclassInterface,
                    rootTargetType = targetType,
                    subclassesBySuper = subclassesBySuper,
                )
            val specs = buildResult.specs
                ?: return buildResult.failureMessage
                    ?: "Cannot derive ${classIdOrFail.asString()} because no sealed subclasses are admissible for the requested typeclass"
            if (
                specs.any { spec ->
                    unifyTypes(
                        left = spec.targetType,
                        right = targetType,
                        bindableVariableIds = spec.ruleTypeParameters.mapTo(linkedSetOf(), TcTypeParameter::id),
                    ) != null
                }
            ) {
                return null
            }
            return buildResult.failureMessage ?: "Cannot derive ${classIdOrFail.asString()} because no sealed subclasses are admissible for the requested typeclass"
        }

        val ruleTypeParameters =
            typeParameters.mapIndexed { index, typeParameter ->
                TcTypeParameter(
                    id = "exact-derived:${classIdOrFail.asString()}#$index",
                    displayName = typeParameter.name.asString(),
                )
            }
        val typeParameterBySymbol = typeParameters.zip(ruleTypeParameters).associate { (symbol, parameter) -> symbol.symbol to parameter }
        val storedProperties = structuralProperties()
        storedProperties.firstOrNull { property ->
            property.visibility != DescriptorVisibilities.PUBLIC ||
                property.getter?.visibility != DescriptorVisibilities.PUBLIC
        }?.let { nonPublicProperty ->
            return "constructive product derivation requires public stored properties; ${renderClassName()}.${nonPublicProperty.name.asString()} is not public."
        }
        storedProperties.forEach { property ->
            val getter = property.getter ?: return "Cannot derive ${classIdOrFail.asString()} because constructive product derivation requires constructor parameters to exactly match stored properties"
            if (irTypeToModel(getter.returnType, typeParameterBySymbol) == null) {
                return "Cannot derive ${classIdOrFail.asString()} because constructive product derivation requires all stored property types to be representable"
            }
        }
        val constructor = primaryConstructorOrNull()
            ?: return "Cannot derive ${classIdOrFail.asString()} because constructive product derivation requires a primary constructor"
        if (constructor.visibility != DescriptorVisibilities.PUBLIC) {
            return "constructive product derivation requires a public primary constructor for ${renderClassName()}."
        }
        val constructorParameterNames = constructor.valueParameters.map { parameter -> parameter.name.asString() }
        val fieldNames = storedProperties.map { property -> property.name.asString() }
        if (constructorParameterNames != fieldNames) {
            return "Cannot derive ${classIdOrFail.asString()} because constructive product derivation requires constructor parameters to exactly match stored properties"
        }
        return null
    }

    private fun IrClass.buildDerivedSumRuleSpecs(
        typeclassInterface: IrClass,
        rootTargetType: TcType.Constructor,
        subclassesBySuper: Map<String, Set<String>>,
        rootGoal: String,
    ): List<DerivedRuleSpec>? {
        val buildResult =
            buildDerivedSumRuleSpecsResult(
                typeclassInterface = typeclassInterface,
                rootTargetType = rootTargetType,
                subclassesBySuper = subclassesBySuper,
            )
        val specs = buildResult.specs
        if (specs != null) {
            return specs
        }
        pluginContext.reportCannotDeriveWithTrace(
            owner = this,
            configuration = configuration,
            goal = rootGoal,
            message =
                buildResult.failureMessage
                    ?: "Cannot derive ${classIdOrFail.asString()} because no sealed subclasses are admissible for the requested typeclass",
            location = compilerMessageLocation(),
        )
        return null
    }

    private fun IrClass.buildDerivedSumRuleSpecsResult(
        typeclassInterface: IrClass,
        rootTargetType: TcType.Constructor,
        subclassesBySuper: Map<String, Set<String>>,
    ): DerivedSumRuleBuildResult {
        val directSubclasses = stableDirectSealedSubclassIds(subclassesBySuper)
        if (directSubclasses.isEmpty()) {
            return DerivedSumRuleBuildResult(
                specs = null,
                failureMessage = "Cannot derive ${classIdOrFail.asString()} because no sealed subclasses are admissible for the requested typeclass",
            )
        }
        val caseInfos =
            directSubclasses.mapNotNull { subclassId ->
                val subclass = classesById[subclassId] ?: return@mapNotNull null
                subclass.buildDerivedSumCaseInfo(this)
            }
        if (caseInfos.size != directSubclasses.size) {
            return DerivedSumRuleBuildResult(
                specs = null,
                failureMessage = "Cannot derive ${classIdOrFail.asString()} because one or more sealed subclasses cannot be expressed from the sealed root's type parameters",
            )
        }

        val admissionMode = typeclassInterface.gadtAdmissionMode()
        val candidates =
            when (admissionMode) {
                GadtAdmissionMode.CONSERVATIVE_ONLY ->
                    listOf(
                        DerivedSumCandidate(
                            targetType = rootTargetType,
                            ruleTypeParameters = typeParameters.mapIndexed { index, typeParameter ->
                                TcTypeParameter(
                                    id = "${classIdOrFail.asString()}#$index",
                                    displayName = typeParameter.name.asString(),
                                )
                            },
                            priority = rootTargetType.gadtSpecificityScore(),
                        ),
                    )

                GadtAdmissionMode.SURFACE_TRUSTED ->
                    caseInfos
                        .map { caseInfo ->
                            caseInfo.projectedHead.toDerivedSumCandidate(classIdOrFail.asString(), caseInfo.subclass.name.asString())
                        }.distinctBy { candidate -> candidate.targetType.normalizedKey() }
            }

        val firstRejectionMessages = mutableListOf<String>()
        val specs = mutableListOf<DerivedRuleSpec>()
        val admittedCaseIds = linkedSetOf<String>()
        val unrecoverableCaseMessages = linkedMapOf<String, String>()

        candidates.forEach { candidate ->
            val admissions =
                caseInfos.map { caseInfo ->
                    caseInfo.admitToCandidate(
                        rootClass = this,
                        candidate = candidate,
                        conservativeOnly = admissionMode == GadtAdmissionMode.CONSERVATIVE_ONLY,
                    )
                }
            val admittedCases = admissions.mapNotNull(CaseAdmission::derivedCase)
            admissions.forEachIndexed { index, admission ->
                val caseInfo = caseInfos[index]
                if (admission.derivedCase != null) {
                    val derivedCaseType = admission.derivedCase.type as? TcType.Constructor
                    val exactCaseFailure =
                        if (derivedCaseType == null) {
                            "Cannot derive ${classIdOrFail.asString()} because sealed subclass ${caseInfo.subclass.classIdOrFail.asString()} cannot be expressed as a typeclass goal"
                        } else {
                            caseInfo.subclass.exactShapeDerivationFailure(
                                typeclassId = typeclassInterface.classIdOrFail,
                                targetType = derivedCaseType,
                                subclassesBySuper = subclassesBySuper,
                            )
                        }
                    if (exactCaseFailure == null) {
                        admittedCaseIds += caseInfo.subclass.classIdOrFail.asString()
                    } else {
                        val message =
                            "Cannot derive ${classIdOrFail.asString()} because sealed subclass ${caseInfo.subclass.classIdOrFail.asString()} is not itself derivable: $exactCaseFailure"
                        unrecoverableCaseMessages.putIfAbsent(caseInfo.subclass.classIdOrFail.asString(), message)
                        firstRejectionMessages += message
                    }
                } else {
                    admission.rejectionMessage?.let { message ->
                        unrecoverableCaseMessages.putIfAbsent(caseInfo.subclass.classIdOrFail.asString(), message)
                        firstRejectionMessages += message
                    }
                }
            }

            if (admissionMode == GadtAdmissionMode.CONSERVATIVE_ONLY && admittedCases.size != caseInfos.size) {
                return DerivedSumRuleBuildResult(
                    specs = null,
                    failureMessage =
                    admissions.mapNotNull(CaseAdmission::rejectionMessage).firstOrNull()
                        ?: "Cannot derive ${classIdOrFail.asString()} because one or more sealed subclasses require result-head refinements beyond the conservative admissibility policy",
                )
            }

            if (admittedCases.isNotEmpty()) {
                specs +=
                    DerivedRuleSpec(
                        targetType = candidate.targetType,
                        shape = DerivedShape.Sum(admittedCases),
                        ruleTypeParameters = candidate.ruleTypeParameters,
                        priority = candidate.priority,
                    )
            }
        }

        val unrecoverableCaseMessage =
            caseInfos.firstNotNullOfOrNull { caseInfo ->
                val caseId = caseInfo.subclass.classIdOrFail.asString()
                if (caseId !in admittedCaseIds) {
                    unrecoverableCaseMessages[caseId]
                } else {
                    null
                }
            }
        if (unrecoverableCaseMessage != null) {
            return DerivedSumRuleBuildResult(
                specs = null,
                failureMessage = unrecoverableCaseMessage,
            )
        }

        if (specs.isEmpty()) {
            return DerivedSumRuleBuildResult(
                specs = null,
                failureMessage =
                    firstRejectionMessages.firstOrNull()
                        ?: "Cannot derive ${classIdOrFail.asString()} because no sealed subclasses are admissible for the requested typeclass",
            )
        }

        return DerivedSumRuleBuildResult(specs = specs)
    }

    private fun IrClass.stableDirectSealedSubclassIds(
        subclassesBySuper: Map<String, Set<String>>,
    ): List<String> {
        val discoveredSubclassIds = subclassesBySuper[classIdOrFail.asString()].orEmpty()
        val sourceOrderedSubclassIds =
            sealedSubclasses
                .mapNotNull { subclassSymbol ->
                    runCatching { subclassSymbol.owner }.getOrNull()?.classId?.asString()
                }
        return stableSealedSubclassIds(sourceOrderedSubclassIds, discoveredSubclassIds)
    }

    private fun IrClass.buildDerivedEnumShape(
        rootGoal: String,
    ): DerivedShape.Enum? {
        val entries =
            declarations.filterIsInstance<IrEnumEntry>().map { entry ->
                DerivedEnumEntry(
                    name = entry.name.asString(),
                    entry = entry,
                )
            }
        if (entries.isEmpty()) {
            pluginContext.reportCannotDeriveWithTrace(
                owner = this,
                configuration = configuration,
                goal = rootGoal,
                message = "Cannot derive ${classIdOrFail.asString()} because enum derivation requires at least one enum entry",
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

    private fun IrClass.buildDerivedSumCaseInfo(
        sealedBase: IrClass,
    ): DerivedSumCaseInfo? {
        val subclassClassId = classId ?: return null
        val caseTypeParameters =
            typeParameters.mapIndexed { index, typeParameter ->
                TcTypeParameter(
                    id = "${subclassClassId.asString()}#$index",
                    displayName = typeParameter.name.asString(),
                )
            }
        val caseTypeBySymbol =
            typeParameters.zip(caseTypeParameters).associate { (symbol, parameter) ->
                symbol.symbol to parameter
            }
        val projectedHead =
            superTypes.firstOrNull { superType ->
                superType.classOrNull?.owner?.classId == sealedBase.classId
            }?.let { superType ->
                irTypeToModel(superType, caseTypeBySymbol)
            } as? TcType.Constructor ?: return null
        val fieldTypes =
            structuralProperties().map { property ->
                val getter = property.getter ?: return null
                irTypeToModel(getter.returnType, caseTypeBySymbol) ?: return null
            }
        return DerivedSumCaseInfo(
            subclass = this,
            projectedHead = projectedHead,
            subclassDeclaredType =
                TcType.Constructor(
                    subclassClassId.asString(),
                    caseTypeParameters.map { parameter -> TcType.Variable(parameter.id, parameter.displayName) },
                ),
            caseTypeParameters = caseTypeParameters,
            fieldTypes = fieldTypes,
        )
    }

    private fun IrClass.structuralProperties(): List<IrProperty> {
        return declarations.filterIsInstance<IrProperty>().filter { property ->
            property.getter != null && property.backingField != null
        }
    }

    private fun IrClass.derivedTypeclassIds(): Set<ClassId> {
        return annotations
            .filter { constructorCall ->
                constructorCall.symbol.owner.parentAsClass.classId == DERIVE_ANNOTATION_CLASS_ID
            }.flatMapTo(linkedSetOf()) { annotation ->
                val values = annotation.getValueArgument(0) as? IrVararg ?: return@flatMapTo emptyList()
                values.elements.mapNotNull { element ->
                    val expression =
                        when (element) {
                            is IrSpreadElement -> element.expression
                            is IrExpression -> element
                            else -> null
                        } ?: return@mapNotNull null
                    val classReference = expression as? IrClassReference ?: return@mapNotNull null
                    val classId = classReference.classType.classOrNull?.owner?.classId ?: return@mapNotNull null
                    val typeclassClass = pluginContext.referenceClass(classId)?.owner ?: return@mapNotNull null
                    classId.takeIf { typeclassClass.hasAnnotation(TYPECLASS_ANNOTATION_CLASS_ID) }
                }
            }
    }

    private fun IrClass.findDeriverCompanion(requiredInterface: ClassId): IrClass? =
        declarations.filterIsInstance<IrClass>().singleOrNull { declaration ->
            declaration.isCompanion &&
                declaration.implementsInterface(requiredInterface, linkedSetOf())
        }

    private fun IrClass.shapeDerivedTypeclassIds(): Set<ClassId> =
        if (origin == IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB) {
            publishedGeneratedDerivedMetadata()
                .filterIsInstance<GeneratedDerivedMetadata.Derive>()
                .mapTo(linkedSetOf()) { metadata -> metadata.typeclassId }
        } else {
            buildSet {
                addAll(
                    publishedGeneratedDerivedMetadata()
                        .filterIsInstance<GeneratedDerivedMetadata.Derive>()
                        .map { metadata -> metadata.typeclassId },
                )
                addAll(derivedTypeclassIds())
            }
        }

    private fun IrClass.publishedGeneratedDerivedMetadata(): List<GeneratedDerivedMetadata> {
        val directMetadata = generatedDerivedMetadata()
        if (origin != IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB) {
            return directMetadata
        }
        val ownerId = classId ?: return directMetadata
        return (
            directMetadata +
                sharedState.importedGeneratedDerivedMetadataForIr(ownerId) +
                sharedState.binaryGeneratedDerivedMetadataForIr(ownerId)
        ).distinct()
    }

    private fun IrClass.generatedDerivedMetadata(): List<GeneratedDerivedMetadata> =
        annotations
            .flatMap { annotation -> annotation.flattenGeneratedInstanceAnnotations() }
            .mapNotNull { annotation ->
                decodeGeneratedDerivedMetadata(
                    typeclassId = (annotation.getValueArgument(0) as? IrConst)?.value as? String,
                    targetId = (annotation.getValueArgument(1) as? IrConst)?.value as? String,
                    kind = (annotation.getValueArgument(2) as? IrConst)?.value as? String,
                    payload = (annotation.getValueArgument(3) as? IrConst)?.value as? String,
                    expectedOwnerId = classIdOrFail.asString(),
                )
            }

    private fun IrConstructorCall.flattenGeneratedInstanceAnnotations(): List<IrConstructorCall> =
        when (symbol.owner.parentAsClass.classId) {
            GENERATED_INSTANCE_ANNOTATION_CLASS_ID -> listOf(this)
            GENERATED_INSTANCE_ANNOTATION_CONTAINER_CLASS_ID ->
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

    private fun IrClass.recordGeneratedDerivedTypeclassMetadata(
        directEntries: List<GeneratedDerivedMetadata>,
    ) {
        val annotationClass = pluginContext.referenceClass(GENERATED_INSTANCE_ANNOTATION_CLASS_ID)?.owner ?: return
        val annotationConstructor = annotationClass.primaryConstructorOrNull() ?: return
        val existingEntries =
            generatedDerivedMetadata().toSet()
        val builder = DeclarationIrBuilder(pluginContext, symbol, startOffset, endOffset)
        directEntries
            .filterNot(existingEntries::contains)
            .forEach { metadata ->
                val encoded = metadata.encode()
                val generatedAnnotation =
                    builder
                        .buildTypeclassGeneratedAnnotation(annotationConstructor)
                        .apply {
                            putValueArgument(0, builder.irString(encoded.typeclassId))
                            putValueArgument(1, builder.irString(encoded.targetId))
                            putValueArgument(2, builder.irString(encoded.kind))
                            putValueArgument(3, builder.irString(encoded.payload))
                        }
                appendTypeclassGeneratedAnnotation(generatedAnnotation)
            }
    }

    private data class IrExpandedDerivedTypeclassHead(
        val directTypeclassId: ClassId,
        val directTypeParameters: List<TcTypeParameter>,
        val head: TcType.Constructor,
    )

    private fun typeclassGoal(
        typeclassId: ClassId,
        targetType: TcType,
    ): TcType = TcType.Constructor(typeclassId.asString(), listOf(targetType))

    private fun expandDerivedProvidedTypes(
        typeclassId: ClassId,
        targetType: TcType,
    ): List<TcType> {
        val expansions = expandedDerivedTypeclassHeads(typeclassId)
        val directTypeParameters = expansions.firstOrNull()?.directTypeParameters.orEmpty()
        if (directTypeParameters.size != 1) {
            return emptyList()
        }
        val transportedParameter = directTypeParameters.single()
        return expansions.mapNotNull { expansion ->
            expansion.head.substituteType(mapOf(transportedParameter.id to targetType)) as? TcType.Constructor
        }.distinctBy(TcType::normalizedKey)
    }

    private fun expandedDerivedTypeclassHeads(
        typeclassId: ClassId,
    ): List<IrExpandedDerivedTypeclassHead> {
        val typeclassInterface = pluginContext.referenceClass(typeclassId)?.owner ?: return emptyList()
        val directTypeParameters =
            typeclassInterface.typeParameters.mapIndexed { index, typeParameter ->
                TcTypeParameter(
                    id = "derived-head:${typeclassId.asString()}#$index",
                    displayName = typeParameter.name.asString(),
                )
            }
        val rootHead =
            TcType.Constructor(
                classifierId = typeclassId.asString(),
                arguments = directTypeParameters.map { parameter -> TcType.Variable(parameter.id, parameter.displayName) },
            )
        return expandDerivedTypeclassHeads(
            typeclassInterface = typeclassInterface,
            currentHead = rootHead,
            directTypeclassId = typeclassId,
            directTypeParameters = directTypeParameters,
            previousWereTypeclass = true,
            visited = emptySet(),
        )
    }

    private fun expandDerivedTypeclassHeads(
        typeclassInterface: IrClass,
        currentHead: TcType.Constructor,
        directTypeclassId: ClassId,
        directTypeParameters: List<TcTypeParameter>,
        previousWereTypeclass: Boolean,
        visited: Set<String>,
    ): List<IrExpandedDerivedTypeclassHead> {
        val visitKey = currentHead.normalizedKey()
        if (visitKey in visited) {
            return emptyList()
        }
        val currentIsTypeclass = typeclassInterface.hasAnnotation(TYPECLASS_ANNOTATION_CLASS_ID)
        val localTypeParameters =
            typeclassInterface.typeParameters.mapIndexed { index, typeParameter ->
                TcTypeParameter(
                    id = "derived-head:${currentHead.classifierId}#$index",
                    displayName = typeParameter.name.asString(),
                )
            }
        val typeParameterBySymbol =
            typeclassInterface.typeParameters.zip(localTypeParameters).associate { (typeParameter, parameter) ->
                typeParameter.symbol to parameter
            }
        val bindings =
            localTypeParameters.zip(currentHead.arguments).associate { (parameter, argument) ->
                parameter.id to argument
            }
        val nextPreviousWereTypeclass = previousWereTypeclass && currentIsTypeclass
        val nextVisited = visited + visitKey
        return buildList {
            if (currentIsTypeclass && previousWereTypeclass) {
                add(
                    IrExpandedDerivedTypeclassHead(
                        directTypeclassId = directTypeclassId,
                        directTypeParameters = directTypeParameters,
                        head = currentHead,
                    ),
                )
            }
            typeclassInterface.superTypes.forEach { superType ->
                val appliedSuperType =
                    (irTypeToModel(superType, typeParameterBySymbol)?.substituteType(bindings) as? TcType.Constructor)
                        ?: return@forEach
                val superTypeclassId = runCatching { ClassId.fromString(appliedSuperType.classifierId) }.getOrNull() ?: return@forEach
                val superTypeclassInterface = pluginContext.referenceClass(superTypeclassId)?.owner ?: return@forEach
                addAll(
                    expandDerivedTypeclassHeads(
                        typeclassInterface = superTypeclassInterface,
                        currentHead = appliedSuperType,
                        directTypeclassId = directTypeclassId,
                        directTypeParameters = directTypeParameters,
                        previousWereTypeclass = nextPreviousWereTypeclass,
                        visited = nextVisited,
                    ),
                )
            }
        }.distinctBy { expansion -> "${expansion.directTypeclassId.asString()}:${expansion.head.normalizedKey()}" }
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
    return isProvablySerializableType(
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

private fun irBuiltinNullableFeasibility(
    goal: TcType,
    exactBuiltinGoalContext: IrBuiltinGoalExactContext?,
): BuiltinGoalFeasibility {
    val constructor = goal as? TcType.Constructor ?: return BuiltinGoalFeasibility.PROVABLE
    if (constructor.classifierId != NULLABLE_CLASS_ID.asString()) {
        return BuiltinGoalFeasibility.PROVABLE
    }
    val targetModel = constructor.arguments.singleOrNull() ?: return BuiltinGoalFeasibility.IMPOSSIBLE
    if (exactBuiltinGoalContext == null) {
        return if (supportsBuiltinNullableGoal(goal)) {
            BuiltinGoalFeasibility.PROVABLE
        } else {
            BuiltinGoalFeasibility.IMPOSSIBLE
        }
    }
    val targetType =
        runCatching { modelToIrType(targetModel, exactBuiltinGoalContext.visibleTypeParameters, exactBuiltinGoalContext.pluginContext) }.getOrNull()
            ?: return BuiltinGoalFeasibility.IMPOSSIBLE
    return if (canProveNullable(targetType, exactBuiltinGoalContext.pluginContext)) {
        BuiltinGoalFeasibility.PROVABLE
    } else {
        BuiltinGoalFeasibility.IMPOSSIBLE
    }
}

private fun irBuiltinNotNullableFeasibility(
    goal: TcType,
    exactBuiltinGoalContext: IrBuiltinGoalExactContext?,
): BuiltinGoalFeasibility {
    val constructor = goal as? TcType.Constructor ?: return BuiltinGoalFeasibility.PROVABLE
    if (constructor.classifierId != NOT_NULLABLE_CLASS_ID.asString()) {
        return BuiltinGoalFeasibility.PROVABLE
    }
    val targetModel = constructor.arguments.singleOrNull() ?: return BuiltinGoalFeasibility.IMPOSSIBLE
    if (exactBuiltinGoalContext == null) {
        return if (supportsBuiltinNotNullableGoal(goal)) {
            BuiltinGoalFeasibility.PROVABLE
        } else {
            BuiltinGoalFeasibility.IMPOSSIBLE
        }
    }
    val targetType =
        runCatching { modelToIrType(targetModel, exactBuiltinGoalContext.visibleTypeParameters, exactBuiltinGoalContext.pluginContext) }.getOrNull()
            ?: return BuiltinGoalFeasibility.IMPOSSIBLE
    return if (canProveNotNullable(targetType, exactBuiltinGoalContext.pluginContext)) {
        BuiltinGoalFeasibility.PROVABLE
    } else {
        BuiltinGoalFeasibility.IMPOSSIBLE
    }
}

private fun isProvablySerializableType(
    type: TcType,
    pluginContext: IrPluginContext,
    visiting: MutableSet<String>,
): Boolean {
    return when (type) {
        TcType.StarProjection -> false
        is TcType.Projected -> isProvablySerializableType(type.type, pluginContext, visiting)
        is TcType.Variable -> false

        is TcType.Constructor -> {
            val visitKey = type.normalizedKey()
            if (!visiting.add(visitKey)) {
                return true
            }
            if (type.classifierId in BUILTIN_SERIALIZABLE_CLASSIFIER_IDS) {
                return type.arguments.all { argument ->
                    isProvablySerializableType(argument, pluginContext, visiting)
                }
            }
            val classId = runCatching { ClassId.fromString(type.classifierId) }.getOrNull() ?: return false
            val klass = pluginContext.referenceClass(classId)?.owner ?: return false
            klass.hasAnnotation(SERIALIZABLE_ANNOTATION_CLASS_ID) &&
                type.arguments.all { argument ->
                    isProvablySerializableType(argument, pluginContext, visiting)
                }
        }
    }
}

private fun canProveSubtype(
    subType: IrType,
    superType: IrType,
    pluginContext: IrPluginContext,
): Boolean = subType.isSubtypeOf(superType, JvmIrTypeSystemContext(pluginContext.irBuiltIns))

private fun irBuiltinSubtypeFeasibility(
    goal: TcType,
    classInfoById: Map<String, VisibleClassHierarchyInfo>,
    exactBuiltinGoalContext: IrBuiltinGoalExactContext?,
): BuiltinGoalFeasibility {
    val constructor = goal as? TcType.Constructor ?: return BuiltinGoalFeasibility.PROVABLE
    if (constructor.classifierId != SUBTYPE_CLASS_ID.asString()) {
        return BuiltinGoalFeasibility.PROVABLE
    }
    if (exactBuiltinGoalContext == null) {
        return if (supportsBuiltinSubtypeGoal(goal, classInfoById)) BuiltinGoalFeasibility.SPECULATIVE else BuiltinGoalFeasibility.IMPOSSIBLE
    }
    val subModel = constructor.arguments.getOrNull(0) ?: return BuiltinGoalFeasibility.IMPOSSIBLE
    val superModel = constructor.arguments.getOrNull(1) ?: return BuiltinGoalFeasibility.IMPOSSIBLE
    val subType =
        runCatching { modelToIrType(subModel, exactBuiltinGoalContext.visibleTypeParameters, exactBuiltinGoalContext.pluginContext) }.getOrNull()
            ?: return BuiltinGoalFeasibility.IMPOSSIBLE
    val superType =
        runCatching { modelToIrType(superModel, exactBuiltinGoalContext.visibleTypeParameters, exactBuiltinGoalContext.pluginContext) }.getOrNull()
            ?: return BuiltinGoalFeasibility.IMPOSSIBLE
    return if (canProveSubtype(subType, superType, exactBuiltinGoalContext.pluginContext)) {
        BuiltinGoalFeasibility.PROVABLE
    } else {
        BuiltinGoalFeasibility.IMPOSSIBLE
    }
}

private fun irBuiltinStrictSubtypeFeasibility(
    goal: TcType,
    classInfoById: Map<String, VisibleClassHierarchyInfo>,
    exactBuiltinGoalContext: IrBuiltinGoalExactContext?,
): BuiltinGoalFeasibility {
    val constructor = goal as? TcType.Constructor ?: return BuiltinGoalFeasibility.PROVABLE
    if (constructor.classifierId != STRICT_SUBTYPE_CLASS_ID.asString()) {
        return BuiltinGoalFeasibility.PROVABLE
    }
    val subModel = constructor.arguments.getOrNull(0) ?: return BuiltinGoalFeasibility.IMPOSSIBLE
    val superModel = constructor.arguments.getOrNull(1) ?: return BuiltinGoalFeasibility.IMPOSSIBLE
    if (!canProveNotSame(subModel, superModel)) {
        return BuiltinGoalFeasibility.IMPOSSIBLE
    }
    if (exactBuiltinGoalContext == null) {
        return if (supportsBuiltinStrictSubtypeGoal(goal, classInfoById)) BuiltinGoalFeasibility.SPECULATIVE else BuiltinGoalFeasibility.IMPOSSIBLE
    }
    val subType =
        runCatching { modelToIrType(subModel, exactBuiltinGoalContext.visibleTypeParameters, exactBuiltinGoalContext.pluginContext) }.getOrNull()
            ?: return BuiltinGoalFeasibility.IMPOSSIBLE
    val superType =
        runCatching { modelToIrType(superModel, exactBuiltinGoalContext.visibleTypeParameters, exactBuiltinGoalContext.pluginContext) }.getOrNull()
            ?: return BuiltinGoalFeasibility.IMPOSSIBLE
    return if (canProveSubtype(subType, superType, exactBuiltinGoalContext.pluginContext)) {
        BuiltinGoalFeasibility.PROVABLE
    } else {
        BuiltinGoalFeasibility.IMPOSSIBLE
    }
}

private fun irBuiltinIsTypeclassInstanceFeasibility(
    goal: TcType,
    exactBuiltinGoalContext: IrBuiltinGoalExactContext?,
    isTypeclassClassifier: (String) -> Boolean,
): BuiltinGoalFeasibility {
    val constructor = goal as? TcType.Constructor ?: return BuiltinGoalFeasibility.PROVABLE
    if (constructor.classifierId != IS_TYPECLASS_INSTANCE_CLASS_ID.asString()) {
        return BuiltinGoalFeasibility.PROVABLE
    }
    val targetModel = constructor.arguments.singleOrNull() ?: return BuiltinGoalFeasibility.IMPOSSIBLE
    if (exactBuiltinGoalContext == null) {
        return if (
            supportsBuiltinIsTypeclassInstanceGoal(
                goal = goal,
                isTypeclassClassifier = isTypeclassClassifier,
            )
        ) {
            BuiltinGoalFeasibility.SPECULATIVE
        } else {
            BuiltinGoalFeasibility.IMPOSSIBLE
        }
    }
    val targetType =
        runCatching { modelToIrType(targetModel, exactBuiltinGoalContext.visibleTypeParameters, exactBuiltinGoalContext.pluginContext) }.getOrNull()
            ?: return BuiltinGoalFeasibility.IMPOSSIBLE
    return if (targetType.isTypeclassType(exactBuiltinGoalContext.configuration)) {
        BuiltinGoalFeasibility.PROVABLE
    } else {
        BuiltinGoalFeasibility.IMPOSSIBLE
    }
}

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
        val ownerKey: String? = null,
    ) : RuleReference

    data class DirectProperty(
        val property: IrProperty,
    ) : RuleReference

    data class LookupProperty(
        val callableId: CallableId,
        val ownerKey: String? = null,
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
        val deriveMethod: IrSimpleFunction,
        val shape: DerivedShape,
        val ruleTypeParameters: List<TcTypeParameter>,
    ) : RuleReference

    data class DerivedEquiv(
        val targetClass: IrClass,
        val otherClassId: ClassId,
        val sourceType: IrType,
        val targetType: IrType,
        val forwardPlan: TransportPlan,
        val backwardPlan: TransportPlan,
    ) : RuleReference

    data class DerivedVia(
        val targetClass: IrClass,
        val authoredPath: List<DeriveViaPathSegment>,
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

private data class DerivedRuleSpec(
    val targetType: TcType.Constructor,
    val shape: DerivedShape,
    val ruleTypeParameters: List<TcTypeParameter>,
    val priority: Int = 0,
)

private data class DerivedSumRuleBuildResult(
    val specs: List<DerivedRuleSpec>?,
    val failureMessage: String? = null,
)

private data class DerivedSumCandidate(
    val targetType: TcType.Constructor,
    val ruleTypeParameters: List<TcTypeParameter>,
    val priority: Int,
)

private data class DerivedSumCaseInfo(
    val subclass: IrClass,
    val projectedHead: TcType.Constructor,
    val subclassDeclaredType: TcType.Constructor,
    val caseTypeParameters: List<TcTypeParameter>,
    val fieldTypes: List<TcType>,
)

private data class CaseAdmission(
    val derivedCase: DerivedCase? = null,
    val rejectionMessage: String? = null,
)

private enum class GadtAdmissionMode {
    SURFACE_TRUSTED,
    CONSERVATIVE_ONLY,
}

private enum class GadtEffectiveVariance {
    PHANTOM,
    COVARIANT,
    CONTRAVARIANT,
    INVARIANT,
}

private fun IrClass.gadtAdmissionMode(): GadtAdmissionMode {
    val transported = typeParameters.lastOrNull() ?: return GadtAdmissionMode.CONSERVATIVE_ONLY
    val declaredOverride = transported.gadtPolicyMode() ?: gadtPolicyMode()
    if (declaredOverride == GadtAdmissionMode.CONSERVATIVE_ONLY) {
        return GadtAdmissionMode.CONSERVATIVE_ONLY
    }
    return when (effectiveVarianceFor(transported.symbol)) {
        GadtEffectiveVariance.CONTRAVARIANT,
        GadtEffectiveVariance.PHANTOM,
        -> GadtAdmissionMode.SURFACE_TRUSTED

        GadtEffectiveVariance.COVARIANT,
        GadtEffectiveVariance.INVARIANT,
        -> GadtAdmissionMode.CONSERVATIVE_ONLY
    }
}

private fun IrClass.gadtPolicyMode(): GadtAdmissionMode? =
    annotations.firstNotNullOfOrNull { annotation -> annotation.gadtPolicyMode() }

private fun IrTypeParameter.gadtPolicyMode(): GadtAdmissionMode? =
    annotations.firstNotNullOfOrNull { annotation -> annotation.gadtPolicyMode() }

private fun org.jetbrains.kotlin.ir.expressions.IrConstructorCall.gadtPolicyMode(): GadtAdmissionMode? {
    val annotationClass = symbol.owner.parentAsClass
    if (annotationClass.classId != GADT_DERIVATION_POLICY_ANNOTATION_CLASS_ID) {
        return null
    }
    return when (val modeArgument = getValueArgument(0)) {
        is org.jetbrains.kotlin.ir.expressions.IrGetEnumValue ->
            modeArgument.symbol.owner.takeIf { enumEntry ->
                enumEntry.parentAsClass.classId == GADT_DERIVATION_MODE_CLASS_ID
            }?.name?.asString()?.let { entryName ->
                when (entryName) {
                    "CONSERVATIVE_ONLY" -> GadtAdmissionMode.CONSERVATIVE_ONLY
                    "SURFACE_TRUSTED" -> GadtAdmissionMode.SURFACE_TRUSTED
                    else -> null
                }
            }

        else -> {
            val renderedMode = modeArgument?.render().orEmpty()
            when {
                "CONSERVATIVE_ONLY" in renderedMode -> GadtAdmissionMode.CONSERVATIVE_ONLY
                "SURFACE_TRUSTED" in renderedMode -> GadtAdmissionMode.SURFACE_TRUSTED
                else -> null
            }
        }
    }
}

private fun IrClass.effectiveVarianceFor(
    transported: IrTypeParameterSymbol,
): GadtEffectiveVariance {
    val declaredVariance = transported.owner.variance
    return when (declaredVariance) {
        Variance.IN_VARIANCE -> GadtEffectiveVariance.CONTRAVARIANT
        Variance.OUT_VARIANCE -> GadtEffectiveVariance.COVARIANT
        Variance.INVARIANT ->
            analyzeCallableSurfaceVariance(
                substitution = typeParameters.associate { parameter -> parameter.symbol to parameter.symbol.defaultType },
                transported = transported,
                visited = linkedSetOf(),
            )
        else -> GadtEffectiveVariance.INVARIANT
    }
}

private fun IrClass.analyzeCallableSurfaceVariance(
    substitution: Map<IrTypeParameterSymbol, IrType>,
    transported: IrTypeParameterSymbol,
    visited: MutableSet<String>,
): GadtEffectiveVariance {
    val visitKey =
        buildString {
            append(classIdOrFail.asString())
            append(':')
            append(
                typeParameters.joinToString(separator = ",") { parameter ->
                    substitution[parameter.symbol]?.render() ?: parameter.name.asString()
                },
            )
        }
    if (!visited.add(visitKey)) {
        return GadtEffectiveVariance.PHANTOM
    }

    var result = GadtEffectiveVariance.PHANTOM

    declarations.filterIsInstance<IrProperty>().forEach { property ->
        val getter = property.getter ?: return@forEach
        result =
            result.parallelCombine(
                analyzeTypeVariance(
                    type = getter.returnType.substitute(substitution),
                    position = GadtEffectiveVariance.COVARIANT,
                    transported = transported,
                ),
            )
    }

    declarations.filterIsInstance<IrSimpleFunction>().forEach { function ->
        if (function.name.asString() == "<init>") {
            return@forEach
        }
        function.extensionReceiverParameter?.let { receiver ->
            result =
                result.parallelCombine(
                    analyzeTypeVariance(
                        type = receiver.type.substitute(substitution),
                        position = GadtEffectiveVariance.CONTRAVARIANT,
                        transported = transported,
                    ),
                )
        }
        function.parameters
            .filter { parameter ->
                parameter.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Context ||
                    parameter.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Regular
            }
            .forEach { parameter ->
                result =
                    result.parallelCombine(
                        analyzeTypeVariance(
                            type = parameter.type.substitute(substitution),
                            position = GadtEffectiveVariance.CONTRAVARIANT,
                            transported = transported,
                        ),
                    )
            }
        result =
            result.parallelCombine(
                analyzeTypeVariance(
                    type = function.returnType.substitute(substitution),
                    position = GadtEffectiveVariance.COVARIANT,
                    transported = transported,
                ),
            )
    }

    superTypes.forEach { superType ->
        val substitutedSuperType = superType.substitute(substitution)
        val superSimpleType = substitutedSuperType as? IrSimpleType ?: return@forEach
        val superClass = superSimpleType.classOrNull?.owner ?: return@forEach
        val superSubstitution =
            superClass.typeParameters.mapIndexedNotNull { index, parameter ->
                val argument = superSimpleType.arguments.getOrNull(index)?.argumentTypeOrNull() ?: return@mapIndexedNotNull null
                parameter.symbol to argument
            }.toMap()
        result =
            result.parallelCombine(
                superClass.analyzeCallableSurfaceVariance(
                    substitution = superSubstitution,
                    transported = transported,
                    visited = visited,
                ),
            )
    }

    return result
}

private fun analyzeTypeVariance(
    type: IrType,
    position: GadtEffectiveVariance,
    transported: IrTypeParameterSymbol,
): GadtEffectiveVariance {
    if (type.hasUnsafeVarianceMarker()) {
        return GadtEffectiveVariance.PHANTOM
    }
    type.gadtFunctionTypeInfoOrNull()?.let { functionInfo ->
        var functionResult = GadtEffectiveVariance.PHANTOM
        functionInfo.parameterTypes.forEach { parameterType ->
            functionResult =
                functionResult.parallelCombine(
                    analyzeTypeVariance(
                        type = parameterType,
                        position = position.composeWith(GadtEffectiveVariance.CONTRAVARIANT),
                        transported = transported,
                    ),
                )
        }
        functionResult =
            functionResult.parallelCombine(
                analyzeTypeVariance(
                        type = functionInfo.returnType,
                        position = position.composeWith(GadtEffectiveVariance.COVARIANT),
                        transported = transported,
                    ),
                )
        return functionResult
    }

    val simpleType = type as? IrSimpleType ?: return GadtEffectiveVariance.PHANTOM
    when (val classifier = simpleType.classifier) {
        transported -> return position
        is IrTypeParameterSymbol -> return GadtEffectiveVariance.PHANTOM
        else -> Unit
    }

    val klass = simpleType.classOrNull?.owner ?: return GadtEffectiveVariance.PHANTOM
    var result = GadtEffectiveVariance.PHANTOM
    simpleType.arguments.forEachIndexed { index, argument ->
        val nestedType = argument.argumentTypeOrNull() ?: return@forEachIndexed
        val declaredVariance = klass.typeParameters.getOrNull(index)?.variance ?: Variance.INVARIANT
        val argumentVariance =
            when (argument) {
                is org.jetbrains.kotlin.ir.types.IrTypeProjection ->
                    when (argument.variance) {
                        Variance.IN_VARIANCE -> GadtEffectiveVariance.CONTRAVARIANT
                        Variance.OUT_VARIANCE -> GadtEffectiveVariance.COVARIANT
                        Variance.INVARIANT -> declaredVariance.toGadtEffectiveVariance()
                    }

                else -> declaredVariance.toGadtEffectiveVariance()
            }
        result =
            result.parallelCombine(
                analyzeTypeVariance(
                    type = nestedType,
                    position = position.composeWith(argumentVariance),
                    transported = transported,
                ),
            )
    }
    return result
}

private fun IrType.hasUnsafeVarianceMarker(): Boolean =
    (this as? IrSimpleType)
        ?.annotations
        ?.any { annotation ->
            annotation.symbol.owner.parentAsClass.classId ==
                ClassId.topLevel(FqName("kotlin.UnsafeVariance"))
        } == true

private fun Variance.toGadtEffectiveVariance(): GadtEffectiveVariance =
    when (this) {
        Variance.INVARIANT -> GadtEffectiveVariance.INVARIANT
        Variance.IN_VARIANCE -> GadtEffectiveVariance.CONTRAVARIANT
        Variance.OUT_VARIANCE -> GadtEffectiveVariance.COVARIANT
    }

private fun GadtEffectiveVariance.composeWith(
    nested: GadtEffectiveVariance,
): GadtEffectiveVariance =
    when {
        this == GadtEffectiveVariance.PHANTOM || nested == GadtEffectiveVariance.PHANTOM -> GadtEffectiveVariance.PHANTOM
        this == GadtEffectiveVariance.INVARIANT || nested == GadtEffectiveVariance.INVARIANT -> GadtEffectiveVariance.INVARIANT
        this == nested -> GadtEffectiveVariance.COVARIANT
        else -> GadtEffectiveVariance.CONTRAVARIANT
    }

private fun GadtEffectiveVariance.parallelCombine(
    other: GadtEffectiveVariance,
): GadtEffectiveVariance =
    when {
        this == GadtEffectiveVariance.INVARIANT || other == GadtEffectiveVariance.INVARIANT -> GadtEffectiveVariance.INVARIANT
        this == GadtEffectiveVariance.PHANTOM -> other
        other == GadtEffectiveVariance.PHANTOM -> this
        this == other -> this
        else -> GadtEffectiveVariance.INVARIANT
    }

private data class GadtFunctionTypeInfo(
    val parameterTypes: List<IrType>,
    val returnType: IrType,
)

private fun IrType.gadtFunctionTypeInfoOrNull(): GadtFunctionTypeInfo? {
    val simpleType = this as? IrSimpleType ?: return null
    val classId = simpleType.classOrNull?.owner?.classId ?: return null
    val fqName = classId.asSingleFqName().asString()
    if (!fqName.startsWith("kotlin.Function") && !fqName.startsWith("kotlin.SuspendFunction")) {
        return null
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
    return GadtFunctionTypeInfo(
        parameterTypes = arguments.dropLast(1),
        returnType = arguments.last(),
    )
}

private fun TcType.toDerivedSumCandidate(
    ownerId: String,
    seed: String,
): DerivedSumCandidate {
    val constructor = this as? TcType.Constructor ?: error("Derived sum candidates must be constructor heads")
    val referencedVariables = constructor.referencedVariableIds().toList()
    val ruleTypeParameters =
        referencedVariables.mapIndexed { index, variableId ->
            TcTypeParameter(
                id = "derived-sum:$ownerId:$seed:$index",
                displayName = "A$index",
            ) to variableId
        }
    val targetType =
        constructor.substituteType(
            ruleTypeParameters.associate { (parameter, variableId) ->
                variableId to TcType.Variable(parameter.id, parameter.displayName)
            },
        ) as TcType.Constructor
    return DerivedSumCandidate(
        targetType = targetType,
        ruleTypeParameters = ruleTypeParameters.map(Pair<TcTypeParameter, String>::first),
        priority = targetType.gadtSpecificityScore(),
    )
}

private fun DerivedSumCaseInfo.admitToCandidate(
    rootClass: IrClass,
    candidate: DerivedSumCandidate,
    conservativeOnly: Boolean,
): CaseAdmission {
    val bindings =
        unifyTypes(
            left = projectedHead,
            right = candidate.targetType,
            bindableVariableIds = caseTypeParameters.mapTo(linkedSetOf(), TcTypeParameter::id),
        ) ?: when {
            subclassDeclaredType.referencedVariableIds().isEmpty() &&
                projectedHead.isVarianceCompatibleWithCandidate(
                    candidate.targetType,
                    rootClass.typeParameters.map(IrTypeParameter::variance),
                ) ->
                emptyMap()

            conservativeOnly ->
                return CaseAdmission(
                    rejectionMessage =
                        "Cannot derive ${rootClass.classIdOrFail.asString()} because sealed subclass ${subclass.classIdOrFail.asString()} refines the result head beyond the conservative admissibility policy",
                )

            else -> return CaseAdmission()
        }

    val allowedVariableIds = candidate.targetType.referencedVariableIds()
    val caseType = subclassDeclaredType.substituteType(bindings)
    if (!caseType.referencedVariableIds().all(allowedVariableIds::contains)) {
        return CaseAdmission(
            rejectionMessage =
                "Cannot derive ${rootClass.classIdOrFail.asString()} because sealed subclass ${subclass.classIdOrFail.asString()} introduces type parameters that are not quantified by the admitted result head",
        )
    }
    fieldTypes.forEach { fieldType ->
        val substitutedFieldType = fieldType.substituteType(bindings)
        if (substitutedFieldType.containsStarProjection()) {
            return CaseAdmission(
                rejectionMessage =
                    "Cannot derive ${rootClass.classIdOrFail.asString()} because sealed subclass ${subclass.classIdOrFail.asString()} requires proof/equality-carrying field evidence hidden from the admitted result head",
            )
        }
        if (!substitutedFieldType.referencedVariableIds().all(allowedVariableIds::contains)) {
            return CaseAdmission(
                rejectionMessage =
                    "Cannot derive ${rootClass.classIdOrFail.asString()} because sealed subclass ${subclass.classIdOrFail.asString()} requires field evidence that is not recoverable from the admitted result head",
            )
        }
    }
    return CaseAdmission(
        derivedCase =
            DerivedCase(
                name = subclass.name.asString(),
                klass = subclass,
                type = caseType,
            ),
    )
}

private fun TcType.isVarianceCompatibleWithCandidate(
    candidate: TcType,
    variances: List<Variance>,
): Boolean {
    val actual = this as? TcType.Constructor ?: return false
    val expected = candidate as? TcType.Constructor ?: return false
    if (actual.classifierId != expected.classifierId || actual.arguments.size != expected.arguments.size) {
        return false
    }
    return actual.arguments.indices.all { index ->
        val actualArgument = actual.arguments[index]
        val expectedArgument = expected.arguments[index]
        when (variances.getOrNull(index) ?: Variance.INVARIANT) {
            Variance.OUT_VARIANCE -> actualArgument.isVarianceSubtypeOf(expectedArgument)
            Variance.IN_VARIANCE -> expectedArgument.isVarianceSubtypeOf(actualArgument)
            Variance.INVARIANT -> actualArgument == expectedArgument
        }
    }
}

private fun TcType.isVarianceSubtypeOf(
    expected: TcType,
): Boolean =
    when {
        expected is TcType.Variable -> true
        this == expected -> true
        this is TcType.Constructor && expected is TcType.Constructor ->
            this.classifierId == expected.classifierId &&
                this.arguments.size == expected.arguments.size &&
                this.arguments.zip(expected.arguments).all { (left, right) -> left.isVarianceSubtypeOf(right) }

        else -> false
    }

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

private data class IrBuiltinGoalExactContext(
    val visibleTypeParameters: VisibleTypeParameters,
    val pluginContext: IrPluginContext,
    val configuration: TypeclassConfiguration,
)

private data class LocalTypeclassContext(
    val expression: IrBuilderWithScope.() -> IrExpression,
    val providedType: TcType,
    val displayName: String,
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
            call.typeArgumentOrNull(index)?.let { argumentType ->
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

private fun IrSimpleFunction.canAcceptSyntheticResolutionCall(
    call: IrCall,
    configuration: TypeclassConfiguration,
): Boolean {
    val substitutionBySymbol =
        typeParameters.mapIndexedNotNull { index, typeParameter ->
            call.typeArgumentOrNull(index)?.let { argumentType ->
                typeParameter.symbol to argumentType
            }
        }.toMap()
    val normalizedCall = call.normalizedArgumentsForTypeclassRewrite(this)

    fun receiverMatches(
        actual: IrExpression?,
        expected: IrType,
    ): Boolean {
        val actualType = actual?.apparentType() ?: return false
        val substitutedExpected = expected.substitute(substitutionBySymbol)
        return actualType == substitutedExpected || actualType.satisfiesExpectedArgumentType(substitutedExpected)
    }

    dispatchReceiverParameter?.type?.let { expectedType ->
        if (!receiverMatches(normalizedCall.dispatchReceiver, expectedType)) {
            return false
        }
    }
    extensionReceiverParameter?.type?.let { expectedType ->
        if (!receiverMatches(normalizedCall.extensionReceiver, expectedType)) {
            return false
        }
    }

    val parameters = regularAndContextParameters()
    val mappedOriginalIndices =
        mapCurrentArgumentsToOriginalParameters(
            originalParameters = parameters,
            currentArguments = normalizedCall.valueArguments,
            typeArgumentMap = substitutionBySymbol,
            visibleTypeParameters = null,
            configuration = configuration,
        ).mapTo(linkedSetOf()) { (_, originalIndex) -> originalIndex }
    return parameters.allIndexed { parameterIndex, parameter ->
        when {
            parameterIndex in mappedOriginalIndices -> true
            parameter.defaultValue != null -> true
            parameter.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Context &&
                parameter.type.substitute(substitutionBySymbol).isTypeclassType(configuration) -> true
            else -> false
        }
    }
}

private fun IrSimpleFunction.visibleNonTypeclassParameterCount(
    configuration: TypeclassConfiguration,
): Int =
    regularAndContextParameters().count { parameter ->
        parameter.kind != org.jetbrains.kotlin.ir.declarations.IrParameterKind.Context ||
            !parameter.type.isTypeclassType(configuration)
    }

internal fun supportsExactPlainOverloadFallbackTypeParameters(
    callableIsLocal: Boolean,
    calleeDeclaredTypeParameterCount: Int,
    candidateDeclaredTypeParameterCount: Int,
): Boolean =
    !callableIsLocal &&
        calleeDeclaredTypeParameterCount == 0 &&
        candidateDeclaredTypeParameterCount == 0

internal fun exactPlainOverloadFallbackShapesMatch(
    candidate: IrSimpleFunction,
    callee: IrSimpleFunction,
    configuration: TypeclassConfiguration,
): Boolean =
    wrapperResolutionShape(candidate, dropTypeclassContexts = false, configuration = configuration) ==
        wrapperResolutionShape(callee, dropTypeclassContexts = true, configuration = configuration)

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

private inline fun <T> Iterable<T>.allIndexed(predicate: (index: Int, T) -> Boolean): Boolean {
    var index = 0
    for (element in this) {
        if (!predicate(index, element)) {
            return false
        }
        index += 1
    }
    return true
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

private fun IrClass.implementedDeriveMethodContracts(): Set<DeriveMethodContract> =
    buildSet {
        if (implementsInterface(TYPECLASS_DERIVER_CLASS_ID, linkedSetOf())) {
            addAll(DeriveMethodContract.entries)
        } else if (implementsInterface(PRODUCT_TYPECLASS_DERIVER_CLASS_ID, linkedSetOf())) {
            add(DeriveMethodContract.PRODUCT)
        }
    }

private fun IrClass.resolveDeriveMethod(
    contract: DeriveMethodContract,
): IrSimpleFunction? {
    if (contract !in implementedDeriveMethodContracts()) {
        return null
    }
    val directMethod = declaredDeriveMethod(contract)
    if (directMethod != null) {
        return directMethod
    }
    return resolveInheritedDeriveMethod(contract, linkedSetOf())
}

private fun IrClass.resolveInheritedDeriveMethod(
    contract: DeriveMethodContract,
    visited: MutableSet<String>,
): IrSimpleFunction? {
    val currentClassId = classIdOrFail.asString()
    if (!visited.add(currentClassId)) {
        return null
    }
    val matches =
        superTypes
            .mapNotNull { superType -> superType.classOrNull?.owner }
            .mapNotNull { superOwner ->
                superOwner.declaredDeriveMethod(contract)
                    ?: superOwner.resolveInheritedDeriveMethod(contract, visited)
            }.distinctBy { function ->
                function.symbol.signature?.toString()
                    ?: function.name.asString()
            }
    return matches.singleOrNull()
}

private fun IrClass.declaredDeriveMethod(
    contract: DeriveMethodContract,
): IrSimpleFunction? =
    declarations
        .filterIsInstance<IrSimpleFunction>()
        .singleOrNull { function ->
            function.isConcreteDeriveImplementation(owner = this, contract = contract) &&
                function.name.asString() == contract.methodName &&
                function.valueParameters.size == 1 &&
                function.valueParameters.single().type.classOrNull?.owner?.classId == contract.metadataClassId
        }

private fun IrSimpleFunction.isConcreteDeriveImplementation(
    owner: IrClass,
    contract: DeriveMethodContract,
): Boolean =
    !isTypeclassDeriverEnumSentinel(owner, contract) &&
        (body != null || (owner.origin == IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB && modality != Modality.ABSTRACT))

private fun isTypeclassDeriverEnumSentinel(
    owner: IrClass,
    contract: DeriveMethodContract,
): Boolean = contract == DeriveMethodContract.ENUM && owner.classId == TYPECLASS_DERIVER_CLASS_ID

private fun IrClass.renderClassName(): String = classId?.asFqNameString() ?: name.asString()

private fun IrClass.supportsDeriveShape(): Boolean =
    when {
        isObject -> true
        modality == Modality.SEALED -> true
        modality == Modality.FINAL && kind != ClassKind.INTERFACE -> true
        else -> false
    }

private fun IrClass.requiredDeriveMethodContractForDeriveShape(): DeriveMethodContract? =
    when {
        kind == ClassKind.ENUM_CLASS -> DeriveMethodContract.ENUM
        modality == Modality.SEALED -> DeriveMethodContract.SUM
        isObject -> DeriveMethodContract.PRODUCT
        modality == Modality.FINAL && kind != ClassKind.INTERFACE -> DeriveMethodContract.PRODUCT
        else -> null
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

private fun IrSimpleFunction.lookupOwnerKeyOrNull(): String? =
    safeCallableIdOrNull()?.classId?.let(::classLookupOwnerKey)
        ?: (this as? IrMetadataSourceOwner)?.deserializedLookupOwnerKeyOrNull()
        ?: containingIrFileOrNull()?.fileEntry?.name?.let(::sourceLookupOwnerKey)

private fun IrProperty.lookupOwnerKeyOrNull(): String? =
    runCatching { callableId }.getOrNull()?.classId?.let(::classLookupOwnerKey)
        ?: (this as? IrMetadataSourceOwner)?.deserializedLookupOwnerKeyOrNull()
        ?: containingIrFileOrNull()?.fileEntry?.name?.let(::sourceLookupOwnerKey)

private fun IrMetadataSourceOwner.deserializedLookupOwnerKeyOrNull(): String? =
    when (val metadataSource = metadata) {
        is DescriptorMetadataSource.Function ->
            (metadataSource.descriptor as? DeserializedCallableMemberDescriptor)?.containerSource?.lookupOwnerKeyOrNull()

        is DescriptorMetadataSource.Property ->
            (metadataSource.descriptor as? DeserializedCallableMemberDescriptor)?.containerSource?.lookupOwnerKeyOrNull()

        else -> null
    }

private tailrec fun IrDeclaration.containingIrFileOrNull(): IrFile? =
    when (val currentParent = parent) {
        is IrFile -> currentParent
        is IrDeclaration -> currentParent.containingIrFileOrNull()
        else -> null
    }

private fun IrSimpleFunction.matchesImportedLookupRule(
    targetRule: InstanceRule,
    configuration: TypeclassConfiguration,
): Boolean {
    val placeholderParameters = visibleTypeParametersForShape(this)
    val bySymbol =
        visibleTypeParameterSymbols(this, listOf(this)).zip(placeholderParameters).associate { (symbol, parameter) ->
            symbol to parameter
        }
    val targetProvidedType = targetRule.providedType.normalizeLookupRuleTypeParameters(targetRule.typeParameters)
    return listOf(returnType).providedTypeExpansion(bySymbol, configuration).validTypes.any { candidateProvidedType ->
        candidateProvidedType == targetProvidedType
    }
}

private fun IrProperty.matchesImportedLookupRule(
    targetRule: InstanceRule,
    configuration: TypeclassConfiguration,
): Boolean {
    val targetProvidedType = targetRule.providedType.normalizeLookupRuleTypeParameters(targetRule.typeParameters)
    return listOf(backingFieldOrGetterType()).providedTypeExpansion(emptyMap(), configuration).validTypes.any { candidateProvidedType ->
        candidateProvidedType == targetProvidedType
    }
}

private fun TcType.normalizeLookupRuleTypeParameters(typeParameters: List<TcTypeParameter>): TcType =
    substituteType(
        typeParameters.mapIndexed { index, parameter ->
            parameter.id to TcType.Variable(id = "P$index", displayName = "P$index")
        }.toMap(),
    )

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
                                displayName = parameter.name.asString(),
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
                                displayName = parameter.name.asString(),
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
    val declaredTypes = linkedMapOf<String, TcType>()
    val validTypes = linkedMapOf<String, TcType>()
    val invalidTypes = linkedMapOf<String, TcType>()
    for (type in this) {
        type.declaredProvidedTypeOrNull(typeParameterBySymbol, configuration)?.let { declaredType ->
            declaredTypes.putIfAbsent(declaredType.render(), declaredType)
        }
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
        declaredTypes = declaredTypes.values.toList(),
        validTypes = validTypes.values.toList(),
        invalidTypes = invalidTypes.values.toList(),
    )
}

private fun IrType.declaredProvidedTypeOrNull(
    typeParameterBySymbol: Map<org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol, TcTypeParameter>,
    configuration: TypeclassConfiguration,
): TcType? {
    val simpleType = this as? IrSimpleType ?: return null
    val currentType = irTypeToModel(this, typeParameterBySymbol) ?: return null
    return currentType.takeIf { simpleType.isTypeclassType(configuration) }
}

private fun IrType.providedTypeExpansion(
    typeParameterBySymbol: Map<org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol, TcTypeParameter>,
    configuration: TypeclassConfiguration,
    previousWereTypeclass: Boolean,
    visited: Set<String>,
): ProvidedTypeExpansion {
    val simpleType = this as? IrSimpleType ?: return ProvidedTypeExpansion(emptyList(), emptyList(), emptyList())
    val currentType = irTypeToModel(this, typeParameterBySymbol) ?: return ProvidedTypeExpansion(emptyList(), emptyList(), emptyList())
    val visitKey = currentType.render()
    if (visitKey in visited) {
        return ProvidedTypeExpansion(emptyList(), emptyList(), emptyList())
    }

    val classSymbol = simpleType.classOrNull ?: return ProvidedTypeExpansion(emptyList(), emptyList(), emptyList())
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
        declaredTypes = emptyList(),
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

private fun IrCall.typeArgumentOrNull(index: Int): IrType? =
    if (index in 0 until typeArgumentsCount) {
        getTypeArgument(index)
    } else {
        null
    }

private fun IrExpression.apparentType(): IrType =
    (this as? IrCall)
        ?.let { call ->
            val callee = call.symbol.owner
            val returnType = callee.returnType as? IrSimpleType ?: return@let null
            val returnedTypeParameter = returnType.classifier as? org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol ?: return@let null
            val typeParameterIndex = callee.typeParameters.indexOfFirst { parameter -> parameter.symbol == returnedTypeParameter }
            if (typeParameterIndex >= 0) {
                call.typeArgumentOrNull(typeParameterIndex)
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

private fun IrType.isNullableNothingType(): Boolean {
    val owner = (this as? IrSimpleType)?.classOrNull?.owner ?: classOrNull?.owner ?: return false
    return owner.classId?.asString() == "kotlin/Nothing" && isNullable()
}

private fun IrType.satisfiesExpectedArgumentType(
    expected: IrType,
    visibleTypeParameters: VisibleTypeParameters? = null,
    visited: MutableSet<String> = linkedSetOf(),
): Boolean {
    if (this == expected) {
        return true
    }
    if (isNullableNothingType() && expected.isNullable()) {
        return true
    }
    if (visibleTypeParameters != null && matchesTypeclassParameterType(expected, visibleTypeParameters)) {
        return true
    }

    val actualSimple = this as? IrSimpleType
    val expectedSimple = expected as? IrSimpleType
    val actualOwner = actualSimple?.classOrNull?.owner ?: classOrNull?.owner ?: return false
    val expectedOwner = expectedSimple?.classOrNull?.owner ?: expected.classOrNull?.owner

    if (actualOwner.symbol == expectedOwner?.symbol && actualSimple != null && expectedSimple != null) {
        if (actualSimple.isNullable() && !expectedSimple.isNullable()) {
            return false
        }
        if (actualSimple.arguments.size == expectedSimple.arguments.size &&
            actualSimple.arguments.zip(expectedSimple.arguments).all { (actualArgument, expectedArgument) ->
                when (expectedArgument) {
                    is IrStarProjection -> true
                    is org.jetbrains.kotlin.ir.types.IrTypeProjection ->
                        actualArgument.argumentTypeOrNull()?.satisfiesExpectedArgumentType(expectedArgument.type, visibleTypeParameters, visited) == true
                    is IrType ->
                        actualArgument.argumentTypeOrNull()?.satisfiesExpectedArgumentType(expectedArgument, visibleTypeParameters, visited) == true
                    else -> false
                }
            }
        ) {
            return true
        }
    }

    val visitKey = "${render()}<=${expected.render()}"
    if (!visited.add(visitKey)) {
        return false
    }
    val substitutions =
        if (actualSimple != null) {
            actualOwner.typeParameters.mapIndexedNotNull { index, parameter ->
                val argument = actualSimple.arguments.getOrNull(index)
                val argumentType =
                    when (argument) {
                        is org.jetbrains.kotlin.ir.types.IrTypeProjection -> argument.type
                        is IrType -> argument
                        else -> null
                    } ?: return@mapIndexedNotNull null
                parameter.symbol to argumentType
            }.toMap()
        } else {
            emptyMap()
        }
    return actualOwner.superTypes.any { superType ->
        val substitutedSuperType =
            if (substitutions.isEmpty()) {
                superType
            } else {
                superType.substitute(substitutions)
            }
        substitutedSuperType.satisfiesExpectedArgumentType(expected, visibleTypeParameters, visited)
    }
}

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
    val simpleType = this as? IrSimpleType
    val owner = simpleType?.classOrNull?.owner ?: classOrNull?.owner ?: return false
    val visitKey = render()
    if (!visited.add(visitKey)) {
        return false
    }
    val substitutions =
        if (simpleType != null) {
            owner.typeParameters.mapIndexedNotNull { index, parameter ->
                val argument = simpleType.arguments.getOrNull(index)
                val argumentType =
                    when (argument) {
                        is org.jetbrains.kotlin.ir.types.IrTypeProjection -> argument.type
                        is IrType -> argument
                        else -> null
                    } ?: return@mapIndexedNotNull null
                parameter.symbol to argumentType
            }.toMap()
        } else {
            emptyMap()
        }
    return owner.superTypes.any { superType ->
        val substitutedSuperType =
            if (substitutions.isEmpty()) {
                superType
            } else {
                superType.substitute(substitutions)
            }
        substitutedSuperType == expected ||
            (visibleTypeParameters != null && substitutedSuperType.matchesTypeclassParameterType(expected, visibleTypeParameters)) ||
            substitutedSuperType.satisfiesExpectedContextType(expected, visibleTypeParameters, visited)
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
    val classifierVariancesCache = linkedMapOf<String, List<Variance>>()

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
        val projectedActualType = projectTypeToMatchingInferenceSupertype(expectedType, actualType) ?: actualType
        val expectedModel =
            irTypeToModel(expectedType, originalTypeParameterBySymbol)
                ?.substituteType(bindings)
                ?: return
        projectedActualType.inferenceModels(visibleTypeParameters, configuration).forEach { actualModel ->
            val candidateBindings =
                inferTypeBindings(
                    expected = expectedModel,
                    actual = actualModel,
                    bindableVariableIds = bindableVariableIds,
                    variancesForClassifier = { classifierId ->
                        classifierVariancesCache.getOrPut(classifierId) {
                            val classId = runCatching { ClassId.fromString(classifierId) }.getOrNull() ?: return@getOrPut emptyList()
                            pluginContext.referenceClass(classId)?.owner?.typeParameters?.map(IrTypeParameter::variance).orEmpty()
                        }
                    },
                    isProvableSubtype = { sub, sup ->
                        when {
                            sub == sup -> true
                            else ->
                                runCatching {
                                    canProveSubtype(
                                        subType = modelToIrType(sub, visibleTypeParameters, pluginContext),
                                        superType = modelToIrType(sup, visibleTypeParameters, pluginContext),
                                        pluginContext = pluginContext,
                                    )
                                }.getOrDefault(false)
                        }
                    },
                )
            if (candidateBindings.isNotEmpty()) {
                mergeTypeBindings(bindings, candidateBindings, bindableVariableIds)
            }
        }
    }

    fun mergeBindingsFromReceiverTypeArguments(
        expectedType: IrType,
        actualType: IrType,
    ) {
        receiverTypeArgumentBindings(expectedType, actualType).forEach { (parameterSymbol, actualArgumentType) ->
            val parameter = parameterSymbol.owner
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

internal fun receiverTypeArgumentBindings(
    expectedType: IrType,
    actualType: IrType,
): Map<IrTypeParameterSymbol, IrType> {
    val expectedSimpleType = expectedType as? IrSimpleType ?: return emptyMap()
    val expectedClass = expectedSimpleType.classOrNull?.owner ?: return emptyMap()
    if (expectedClass.typeParameters.isEmpty()) {
        return emptyMap()
    }
    val projectedActualType = actualType.projectToMatchingSupertype(expectedClass) ?: return emptyMap()
    return expectedClass.typeParameters.mapIndexedNotNull { index, parameter ->
        projectedActualType.arguments.getOrNull(index)?.argumentTypeOrNull()?.let { argumentType ->
            parameter.symbol to argumentType
        }
    }.toMap()
}

internal fun projectTypeToMatchingInferenceSupertype(
    expectedType: IrType,
    actualType: IrType,
): IrType? {
    val expectedSimpleType = expectedType as? IrSimpleType ?: return null
    val expectedClass = expectedSimpleType.classOrNull?.owner ?: return null
    return actualType.projectToMatchingSupertype(expectedClass)
}

private fun IrType.projectToMatchingSupertype(
    expectedClass: IrClass,
    visited: MutableSet<org.jetbrains.kotlin.ir.symbols.IrClassSymbol> = linkedSetOf(),
): IrSimpleType? {
    val actualSimpleType = this as? IrSimpleType ?: return null
    val actualClass = actualSimpleType.classOrNull?.owner ?: return null
    if (actualClass.symbol == expectedClass.symbol) {
        return actualSimpleType
    }
    if (!visited.add(actualClass.symbol)) {
        return null
    }
    val substitutions =
        actualClass.typeParameters.mapIndexedNotNull { index, parameter ->
            actualSimpleType.arguments.getOrNull(index)?.argumentTypeOrNull()?.let { argumentType ->
                parameter.symbol to argumentType
            }
        }.toMap()
    actualClass.superTypes.forEach { superType ->
        val substitutedSuperType = if (substitutions.isEmpty()) superType else superType.substitute(substitutions)
        val projected = substitutedSuperType.projectToMatchingSupertype(expectedClass, visited)
        if (projected != null) {
            return projected
        }
    }
    return null
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
    ): Boolean {
        argument ?: return false
        val localEvidenceTypes = argument.type.localEvidenceTypes(visibleTypeParameterSymbols, configuration)
        val expectedModel = irTypeToModel(expectedType, visibleTypeParameterSymbols)
        val localEvidenceMatches =
            expectedModel != null &&
                localEvidenceTypes.any { candidate ->
                    candidate == expectedModel || candidate.matchesProjectedExplicitContextType(expectedModel)
                }
        return (
            argument.type.isTypeclassType(configuration) ||
                localEvidenceTypes.isNotEmpty()
        ) &&
            (
                argument.type.satisfiesExpectedContextType(expectedType, visibleTypeParameters) ||
                    argument.type.matchesProjectedExplicitContextType(expectedType, visibleTypeParameters) ||
                    localEvidenceMatches
            )
    }

    fun argumentMatchesRegularParameter(
        argument: IrExpression?,
        expectedType: IrType,
    ): Boolean {
        argument ?: return false
        return argument.type == expectedType ||
            (visibleTypeParameters != null && argument.type.matchesTypeclassParameterType(expectedType, visibleTypeParameters)) ||
            argument.type.satisfiesExpectedArgumentType(expectedType, visibleTypeParameters)
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

        // Kotlin 2.4 preserves omitted argument holes in some rewritten call shapes.
        // Skip null slots so later explicit arguments can still align with the original parameters.
        if (argument == null) {
            bestMapping = chooseBetterMapping(bestMapping, search(currentIndex + 1, originalIndex))
        }

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
