// SPDX-License-Identifier: AGPL-3.0-or-later

@file:OptIn(
    org.jetbrains.kotlin.fir.extensions.FirExtensionApiInternals::class,
    org.jetbrains.kotlin.fir.symbols.SymbolInternals::class,
)

package one.wabbit.typeclass.plugin

import one.wabbit.typeclass.plugin.model.ResolutionTrace
import one.wabbit.typeclass.plugin.model.ResolutionTraceCandidate
import one.wabbit.typeclass.plugin.model.ResolutionTraceCandidateState
import one.wabbit.typeclass.plugin.model.ResolutionSearchResult
import one.wabbit.typeclass.plugin.model.TcType
import one.wabbit.typeclass.plugin.model.TypeclassResolutionPlanner
import one.wabbit.typeclass.plugin.model.referencedVariableIds
import one.wabbit.typeclass.plugin.model.substituteType
import one.wabbit.typeclass.plugin.model.unifyTypes
import org.jetbrains.kotlin.fir.originalOrSelf
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.buildResolvedArgumentList
import org.jetbrains.kotlin.fir.expressions.builder.buildFunctionCallCopy
import org.jetbrains.kotlin.fir.expressions.impl.FirResolvedArgumentList
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.extensions.FirFunctionCallRefinementExtension
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.references.builder.buildResolvedNamedReference
import org.jetbrains.kotlin.fir.resolve.calls.candidate.CallInfo
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.toFirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjectionIn
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjectionOut
import org.jetbrains.kotlin.fir.types.ConeStarProjection
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.ConeTypeVariableType
import org.jetbrains.kotlin.fir.types.FirTypeProjectionWithVariance
import org.jetbrains.kotlin.fir.types.ProjectionKind
import org.jetbrains.kotlin.fir.types.builder.buildTypeProjectionWithVariance
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.types.constructType
import org.jetbrains.kotlin.fir.types.lowerBoundIfFlexible
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance

internal class TypeclassFirFunctionCallRefinementExtension(
    session: FirSession,
    private val sharedState: TypeclassPluginSharedState,
) : FirFunctionCallRefinementExtension(session) {
    private val inferredTypeArgumentsByCallKey = linkedMapOf<String, Map<String, ConeKotlinType>>()

    override fun intercept(
        callInfo: CallInfo,
        symbol: FirNamedFunctionSymbol,
    ): CallReturnType? {
        if (shouldDeferToNativeContextResolution(callInfo, symbol)) {
            return null
        }
        val typeclassContextResolution = typeclassContextResolution(symbol, callInfo)
        if (!typeclassContextResolution.mask.any { it }) {
            return null
        }

        val originalSymbol = symbol.originalOrSelf()
        (callInfo.callSite as? FirFunctionCall)
            ?.refinementCallKey(originalSymbol)
            ?.let { key ->
                inferredTypeArgumentsByCallKey[key] =
                    typeclassContextResolution.inferredTypeArguments.mapKeys { (typeParameter, _) ->
                        typeParameter.name.asString()
                    }
            }

        val visibleTypeParameterNames = symbol.wrapperVisibleTypeParameterNames(session, sharedState).toSet()
        val shouldDropInvisibleTypeParameters = callInfo.typeArguments.isEmpty()
        return CallReturnType(symbol.resolvedReturnTypeRef) { refinedSymbol ->
            val refinedFunction = refinedSymbol.fir
            refinedFunction.replaceContextParameters(
                refinedFunction.contextParameters.filterIndexed { index, _ -> !typeclassContextResolution.mask.getOrElse(index) { false } },
            )
            if (shouldDropInvisibleTypeParameters) {
                @Suppress("UNCHECKED_CAST")
                (refinedFunction.typeParameters as MutableList<org.jetbrains.kotlin.fir.declarations.FirTypeParameter>).retainAll { typeParameter ->
                    typeParameter.symbol.name.asString() in visibleTypeParameterNames
                }
            }
        }
    }

    override fun transform(
        call: FirFunctionCall,
        originalSymbol: FirNamedFunctionSymbol,
    ): FirFunctionCall {
        val restoredOriginalSymbol = originalSymbol.originalOrSelf()
        val refinedSymbol = (call.calleeReference as? FirResolvedNamedReference)?.resolvedSymbol as? FirNamedFunctionSymbol
        val restoredTypeArguments = restoreTypeArguments(call, restoredOriginalSymbol, refinedSymbol)
        return buildFunctionCallCopy(call) {
            calleeReference =
                buildResolvedNamedReference {
                    source = call.calleeReference.source
                    name = call.calleeReference.name
                    resolvedSymbol = restoredOriginalSymbol
                }
            typeArguments.clear()
            typeArguments += restoredTypeArguments
            argumentList = rewriteArgumentList(call.argumentList, refinedSymbol, restoredOriginalSymbol)
        }
    }

    override fun ownsSymbol(symbol: FirRegularClassSymbol): Boolean = false

    override fun anchorElement(symbol: FirRegularClassSymbol) =
        error("Typeclass function-call refinement does not own local classes")

    override fun restoreSymbol(
        call: FirFunctionCall,
        name: Name,
    ): FirRegularClassSymbol? = null

    private fun typeclassContextResolution(
        symbol: FirNamedFunctionSymbol,
        callInfo: CallInfo,
    ): TypeclassContextResolution {
        val function = symbol.fir
        if (function.origin.generated) {
            return TypeclassContextResolution()
        }
        if (function.hasAnnotation(INSTANCE_ANNOTATION_CLASS_ID, session)) {
            return TypeclassContextResolution()
        }
        if (function.contextParameters.isEmpty()) {
            return TypeclassContextResolution()
        }

        val typeContext = buildTypeContext(callInfo, symbol)
        val inferredTypeArguments = inferFunctionTypeArguments(callInfo, symbol, typeContext)
        val inferredTypeArgumentsWithContextResolution = linkedMapOf<FirTypeParameterSymbol, ConeKotlinType>()
        inferredTypeArgumentsWithContextResolution.putAll(inferredTypeArguments)
        val eligibleTypeParameters = function.typeParameters.mapTo(linkedSetOf(), org.jetbrains.kotlin.fir.declarations.FirTypeParameterRef::symbol)
        val exactBuiltinGoalContext = typeContext.exactBuiltinGoalContext()
        val planner =
            TypeclassResolutionPlanner(
                ruleProvider = { goal ->
                    sharedState.refinementRulesForGoal(
                        session = session,
                        goal = goal,
                        canMaterializeVariable = typeContext.runtimeMaterializableVariableIds::contains,
                        exactBuiltinGoalContext = exactBuiltinGoalContext,
                    )
                },
                bindableDesiredVariableIds = typeContext.bindableVariableIds,
            )

        val mask = function.contextParameters.map { parameter ->
            val substitutedType =
                substituteInferredTypes(
                    type = parameter.returnTypeRef.coneType,
                    substitutions = inferredTypeArgumentsWithContextResolution,
                    session = session,
                )
            val isTypeclass = sharedState.isTypeclassType(session, substitutedType)
            if (!isTypeclass) {
                return@map false
            }
            val goalModel = coneTypeToModel(substitutedType, typeContext.typeParameterModels) ?: return@map false
            val tracedResolution =
                planner.resolveWithTrace(
                    desiredType = goalModel,
                    localContextTypes = typeContext.directlyAvailableContextModels,
                )
            when (tracedResolution.result) {
                is ResolutionSearchResult.Success -> {
                    inferredTypeArgumentsWithContextResolution.mergeResolutionInferredTypeArguments(
                        trace = tracedResolution.trace,
                        variableSymbolsById = exactBuiltinGoalContext.variableSymbolsById,
                        eligibleSymbols = eligibleTypeParameters,
                    )
                    true
                }
                is ResolutionSearchResult.Missing ->
                    sharedState.canDeriveGoal(
                        session = session,
                        goal = goalModel,
                        availableContexts = typeContext.directlyAvailableContextModels,
                        canMaterializeVariable = typeContext.runtimeMaterializableVariableIds::contains,
                        builtinGoalAcceptance = BuiltinGoalAcceptance.PROVABLE_ONLY,
                        exactBuiltinGoalContext = exactBuiltinGoalContext,
                    )
                is ResolutionSearchResult.Ambiguous -> function.typeParameters.isNotEmpty()
                else -> false
            }
        }
        return TypeclassContextResolution(
            mask = mask,
            inferredTypeArguments = inferredTypeArgumentsWithContextResolution,
        )
    }

    private fun rewriteArgumentList(
        argumentList: org.jetbrains.kotlin.fir.expressions.FirArgumentList,
        refinedSymbol: FirNamedFunctionSymbol?,
        originalSymbol: FirNamedFunctionSymbol,
    ): org.jetbrains.kotlin.fir.expressions.FirArgumentList {
        val resolvedArgumentList = argumentList as? FirResolvedArgumentList ?: return argumentList
        val originalParameters = originalSymbol.fir.valueParameters
        val remapped =
            LinkedHashMap<org.jetbrains.kotlin.fir.expressions.FirExpression, org.jetbrains.kotlin.fir.declarations.FirValueParameter>()
        resolvedArgumentList.mapping.forEach { (argument, parameter) ->
            val remappedParameter =
                refinedSymbol?.fir?.valueParameters?.indexOf(parameter)?.takeIf { index -> index in originalParameters.indices }?.let { index ->
                    originalParameters[index]
                } ?: originalParameters.singleOrNull { originalParameter ->
                    originalParameter.name == parameter.name
                }
            if (remappedParameter != null) {
                remapped[argument] = remappedParameter
            }
        }
        return buildResolvedArgumentList(resolvedArgumentList.originalArgumentList, remapped)
    }

    private fun shouldDeferToNativeContextResolution(
        callInfo: CallInfo,
        symbol: FirNamedFunctionSymbol,
    ): Boolean {
        val typeContext = buildTypeContext(callInfo, symbol)
        if (typeContext.nativelyAvailableContextModels.isEmpty()) {
            return false
        }

        val inferredTypeArguments = inferFunctionTypeArguments(callInfo, symbol, typeContext)
        val requiredContextModels =
            symbol.fir.contextParameters.mapNotNull { parameter ->
                parameter.returnTypeRef.coneType
                    .takeIf { type -> sharedState.isTypeclassType(session, type) }
                    ?.let { type ->
                        substituteInferredTypes(
                            type = type,
                            substitutions = inferredTypeArguments,
                            session = session,
                        )
                    }
                    ?.let { requiredType -> coneTypeToModel(requiredType, typeContext.typeParameterModels) }
            }
        return requiredContextModels.isNotEmpty() &&
            requiredContextModels.all { requiredContext ->
                typeContext.nativelyAvailableContextModels.any { availableContext ->
                    unifyTypes(
                        left = requiredContext,
                        right = availableContext,
                        bindableVariableIds = typeContext.bindableVariableIds,
                    ) != null
                }
            }
    }

    private fun buildTypeContext(
        callInfo: CallInfo,
        symbol: FirNamedFunctionSymbol,
    ): FirTypeclassResolutionContext =
        buildFirTypeclassResolutionContext(
            session = session,
            sharedState = sharedState,
            containingFunctions = callInfo.containingDeclarations.filterIsInstance<FirFunction>(),
            calleeTypeParameters = symbol.fir.typeParameters.map { typeParameter -> typeParameter.symbol },
        )

    private fun inferFunctionTypeArguments(
        callInfo: CallInfo,
        symbol: FirNamedFunctionSymbol,
        typeContext: FirTypeclassResolutionContext,
    ): Map<FirTypeParameterSymbol, org.jetbrains.kotlin.fir.types.ConeKotlinType> {
        val containingFunction =
            callInfo.containingDeclarations.filterIsInstance<FirTypeclassFunctionDeclaration>().lastOrNull()?.symbol
        val functionCall = callInfo.callSite as? FirFunctionCall
        if (functionCall != null) {
            return inferFunctionTypeArgumentsFromCallSite(
                session = session,
                functionCall = functionCall,
                resolvedFunction = symbol,
                containingFunction = containingFunction,
                sharedState = sharedState,
            )
        }

        val function = symbol.fir
        val inferred = linkedMapOf<FirTypeParameterSymbol, org.jetbrains.kotlin.fir.types.ConeKotlinType>()
        val functionTypeParameters = function.typeParameters.mapTo(linkedSetOf(), org.jetbrains.kotlin.fir.declarations.FirTypeParameterRef::symbol)
        val inferenceTypeParameterModels = typeContext.typeParameterModels.toMutableMap()
        val directConstraints = linkedMapOf<FirTypeParameterSymbol, MutableTypeBindingConstraints<org.jetbrains.kotlin.fir.types.ConeKotlinType>>()

        function.typeParameters.zip(callInfo.typeArguments).forEach { (typeParameter, typeArgument) ->
            val explicitType = (typeArgument as? FirTypeProjectionWithVariance)?.typeRef?.coneType ?: return@forEach
            inferred[typeParameter.symbol] = explicitType
        }

        function.receiverParameter?.typeRef?.coneType?.let { receiverType ->
            callInfo.explicitReceiver?.safeResolvedOrInferredTypeOrNull(session)?.let { explicitReceiverType ->
                collectFunctionTypeArgumentConstraintsForInference(
                    session = session,
                    sharedState = sharedState,
                    typeParameterModels = inferenceTypeParameterModels,
                    parameterType = receiverType,
                    argumentType = explicitReceiverType,
                    functionTypeParameters = functionTypeParameters,
                    excludedSymbols = inferred.keys,
                    constraintsBySymbol = directConstraints,
                )
            }
        }

        function.valueParameters.zip(callInfo.arguments).forEach { (parameter, argument) ->
            val argumentType = argument.safeResolvedOrInferredTypeOrNull(session) ?: return@forEach
            collectFunctionTypeArgumentConstraintsForInference(
                session = session,
                sharedState = sharedState,
                typeParameterModels = inferenceTypeParameterModels,
                parameterType = parameter.returnTypeRef.coneType,
                argumentType = argumentType,
                functionTypeParameters = functionTypeParameters,
                excludedSymbols = inferred.keys,
                constraintsBySymbol = directConstraints,
            )
        }
        resolveFirTypeBindingConstraints(
            session = session,
            sharedState = sharedState,
            typeParameterModels = inferenceTypeParameterModels,
            constraintsBySymbol = directConstraints,
        ).resolvedByKey.forEach { (symbol, type) ->
            if (symbol !in inferred) {
                inferred[symbol] = type
            }
        }

        val localContextConstraints = linkedMapOf<FirTypeParameterSymbol, MutableTypeBindingConstraints<org.jetbrains.kotlin.fir.types.ConeKotlinType>>()
        function.contextParameters.forEach { parameter ->
            typeContext.directlyAvailableContextTypes.forEach { availableType ->
                collectFunctionTypeArgumentConstraintsForInference(
                    session = session,
                    sharedState = sharedState,
                    typeParameterModels = inferenceTypeParameterModels,
                    parameterType = parameter.returnTypeRef.coneType,
                    argumentType = availableType,
                    functionTypeParameters = functionTypeParameters,
                    excludedSymbols = inferred.keys,
                    constraintsBySymbol = localContextConstraints,
                )
            }
        }
        resolveFirTypeBindingConstraints(
            session = session,
            sharedState = sharedState,
            typeParameterModels = inferenceTypeParameterModels,
            constraintsBySymbol = localContextConstraints,
        ).resolvedByKey.forEach { (symbol, type) ->
            if (symbol !in inferred) {
                inferred[symbol] = type
            }
        }

        return inferred
    }

    private fun restoreTypeArguments(
        call: FirFunctionCall,
        originalSymbol: FirNamedFunctionSymbol,
        refinedSymbol: FirNamedFunctionSymbol?,
    ): List<org.jetbrains.kotlin.fir.types.FirTypeProjection> {
        val originalFunction = originalSymbol.fir
        if (originalFunction.typeParameters.isEmpty()) {
            return call.typeArguments
        }
        if (call.typeArguments.size == originalFunction.typeParameters.size) {
            return call.typeArguments
        }

        val functionTypeParameters = originalFunction.typeParameters.mapTo(linkedSetOf(), org.jetbrains.kotlin.fir.declarations.FirTypeParameterRef::symbol)
        val inferred = linkedMapOf<FirTypeParameterSymbol, org.jetbrains.kotlin.fir.types.ConeKotlinType>()
        call.refinementCallKey(originalSymbol)
            ?.let(inferredTypeArgumentsByCallKey::remove)
            .orEmpty()
            .forEach { (typeParameterName, inferredType) ->
                originalFunction.typeParameters.singleOrNull { candidate ->
                    candidate.symbol.name.asString() == typeParameterName
                }?.let { typeParameter ->
                    inferred[typeParameter.symbol] = inferredType
                }
            }
        val inferenceTypeParameterModels =
            buildInferenceTypeParameterModels(
                bindableTypeParameters = functionTypeParameters,
                containingFunction = null,
            )
        val directConstraints = linkedMapOf<FirTypeParameterSymbol, MutableTypeBindingConstraints<org.jetbrains.kotlin.fir.types.ConeKotlinType>>()
        val originalParametersByName = originalFunction.valueParameters.associateBy { it.name }
        val refinedParameters = refinedSymbol?.fir?.valueParameters.orEmpty()

        val currentTypeParameters = refinedSymbol?.fir?.typeParameters ?: originalFunction.typeParameters
        currentTypeParameters.zip(call.typeArguments).forEach { (typeParameter, typeArgument) ->
            val explicitType = (typeArgument as? FirTypeProjectionWithVariance)?.typeRef?.coneType ?: return@forEach
            val originalTypeParameter =
                originalFunction.typeParameters.singleOrNull { candidate ->
                    candidate.symbol.name == typeParameter.symbol.name
                } ?: return@forEach
            inferred[originalTypeParameter.symbol] = explicitType
        }

        collectFunctionTypeArgumentConstraintsForInference(
            session = session,
            sharedState = sharedState,
            typeParameterModels = inferenceTypeParameterModels,
            parameterType = originalFunction.returnTypeRef.coneType,
            argumentType = call.resolvedType,
            functionTypeParameters = functionTypeParameters,
            excludedSymbols = inferred.keys,
            constraintsBySymbol = directConstraints,
        )

        call.resolvedArgumentMapping?.forEach { (argument, parameter) ->
            val originalParameter =
                refinedParameters.indexOf(parameter).takeIf { index -> index >= 0 }?.let { index ->
                    originalFunction.valueParameters.getOrNull(index)
                } ?: originalParametersByName[parameter.name]
            if (originalParameter != null) {
                collectFunctionTypeArgumentConstraintsForInference(
                    session = session,
                    sharedState = sharedState,
                    typeParameterModels = inferenceTypeParameterModels,
                    parameterType = originalParameter.returnTypeRef.coneType,
                    argumentType = argument.resolvedType,
                    functionTypeParameters = functionTypeParameters,
                    excludedSymbols = inferred.keys,
                    constraintsBySymbol = directConstraints,
                )
            }
        }
        resolveFirTypeBindingConstraints(
            session = session,
            sharedState = sharedState,
            typeParameterModels = inferenceTypeParameterModels,
            constraintsBySymbol = directConstraints,
        ).resolvedByKey.forEach { (symbol, type) ->
            if (symbol !in inferred) {
                inferred[symbol] = type
            }
        }

        return originalFunction.typeParameters.map { typeParameter ->
            val inferredType = inferred[typeParameter.symbol] ?: return call.typeArguments
            buildTypeProjectionWithVariance {
                source = call.source
                typeRef = inferredType.toFirResolvedTypeRef(call.source)
                variance = Variance.INVARIANT
            }
        }
    }

    private fun FirTypeclassResolutionContext.exactBuiltinGoalContext(): FirBuiltinGoalExactContext =
        FirBuiltinGoalExactContext(
            session = session,
            typeParameterModels = typeParameterModels,
            variableSymbolsById = typeParameterModels.entries.associate { (symbol, parameter) -> parameter.id to symbol },
        )
}

private data class TypeclassContextResolution(
    val mask: List<Boolean> = emptyList(),
    val inferredTypeArguments: Map<FirTypeParameterSymbol, ConeKotlinType> = emptyMap(),
)

private fun FirFunctionCall.refinementCallKey(
    symbol: FirNamedFunctionSymbol,
): String? =
    source?.let { sourceElement ->
        "${symbol.callableId}:${sourceElement.hashCode()}"
    }

private fun MutableMap<FirTypeParameterSymbol, ConeKotlinType>.mergeResolutionInferredTypeArguments(
    trace: ResolutionTrace,
    variableSymbolsById: Map<String, FirTypeParameterSymbol>,
    eligibleSymbols: Set<FirTypeParameterSymbol>,
) {
    val inferredBindings = linkedMapOf<String, TcType>()
    if (!trace.collectSelectedBindings(inferredBindings)) {
        return
    }
    inferredBindings.forEach { (variableId, modelType) ->
        val symbol = variableSymbolsById[variableId] ?: return@forEach
        if (symbol !in eligibleSymbols || symbol in keys) {
            return@forEach
        }
        modelType.toConeKotlinType(variableSymbolsById)?.let { inferredType ->
            this[symbol] = inferredType
        }
    }
}

private fun ResolutionTrace.collectSelectedBindings(
    bindings: MutableMap<String, TcType>,
): Boolean {
    val selectedCandidate = selectedCandidateOrNull() ?: return true
    val selectedBindings =
        unifyTypes(
            left = goal.substituteType(bindings),
            right = selectedCandidate.providedType.substituteType(bindings),
            bindableVariableIds =
                buildSet {
                    addAll(goal.referencedVariableIds())
                    addAll(selectedCandidate.providedType.referencedVariableIds())
                    addAll(bindings.keys)
                },
        ) ?: return false
    if (!bindings.mergeModelBindings(selectedBindings)) {
        return false
    }
    return selectedCandidate.prerequisiteTraces.all { prerequisiteTrace ->
        prerequisiteTrace.collectSelectedBindings(bindings)
    }
}

private fun ResolutionTrace.selectedCandidateOrNull(): ResolutionTraceCandidate? =
    (ruleCandidates + localContextCandidates).firstOrNull { candidate ->
        candidate.state == ResolutionTraceCandidateState.SELECTED
    }

private fun MutableMap<String, TcType>.mergeModelBindings(
    incoming: Map<String, TcType>,
): Boolean {
    incoming.forEach { (key, value) ->
        val normalizedValue = value.substituteType(this)
        val current = this[key]
        if (current == null) {
            this[key] = normalizedValue
        } else {
            val unified =
                unifyTypes(
                    left = current.substituteType(this),
                    right = normalizedValue,
                    bindableVariableIds =
                        buildSet {
                            addAll(keys)
                            addAll(incoming.keys)
                            addAll(current.referencedVariableIds())
                            addAll(normalizedValue.referencedVariableIds())
                        },
                ) ?: return false
            putAll(unified)
        }
    }
    val snapshot = toMap()
    snapshot.forEach { (key, value) ->
        this[key] = value.substituteType(snapshot)
    }
    return true
}

private fun TcType.toConeKotlinType(
    variableSymbolsById: Map<String, FirTypeParameterSymbol>,
): ConeKotlinType? =
    when (this) {
        TcType.StarProjection -> null
        is TcType.Projected -> null

        is TcType.Variable ->
            variableSymbolsById[id]?.constructType(isMarkedNullable = isNullable)

        is TcType.Constructor -> {
            val classId = runCatching { ClassId.fromString(classifierId) }.getOrNull() ?: return null
            val arguments =
                this.arguments.map { argument ->
                    argument.toConeTypeProjection(variableSymbolsById) ?: return null
                }.toTypedArray()
            classId.constructClassLikeType(
                typeArguments = arguments,
                isMarkedNullable = isNullable,
            )
        }
    }

private fun TcType.toConeTypeProjection(
    variableSymbolsById: Map<String, FirTypeParameterSymbol>,
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
        is TcType.Constructor,
        -> toConeKotlinType(variableSymbolsById)
    }
