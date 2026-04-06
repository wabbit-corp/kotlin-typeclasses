// SPDX-License-Identifier: AGPL-3.0-or-later

@file:OptIn(
    org.jetbrains.kotlin.fir.extensions.FirExtensionApiInternals::class,
    org.jetbrains.kotlin.fir.symbols.SymbolInternals::class,
)

package one.wabbit.typeclass.plugin

import one.wabbit.typeclass.plugin.model.ResolutionSearchResult
import one.wabbit.typeclass.plugin.model.TypeclassResolutionPlanner
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
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.FirTypeProjectionWithVariance
import org.jetbrains.kotlin.fir.types.builder.buildTypeProjectionWithVariance
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.ConeTypeVariableType
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.toFirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.lowerBoundIfFlexible
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance

internal class TypeclassFirFunctionCallRefinementExtension(
    session: FirSession,
    private val sharedState: TypeclassPluginSharedState,
) : FirFunctionCallRefinementExtension(session) {
    override fun intercept(
        callInfo: CallInfo,
        symbol: FirNamedFunctionSymbol,
    ): CallReturnType? {
        if (shouldDeferToNativeContextResolution(callInfo, symbol)) {
            return null
        }
        val typeclassContextMask = typeclassContextMask(symbol, callInfo)
        if (!typeclassContextMask.any { it }) {
            return null
        }

        val visibleTypeParameterNames = symbol.wrapperVisibleTypeParameterNames(session, sharedState).toSet()
        val shouldDropInvisibleTypeParameters = callInfo.typeArguments.isEmpty()
        return CallReturnType(symbol.resolvedReturnTypeRef) { refinedSymbol ->
            val refinedFunction = refinedSymbol.fir
            refinedFunction.replaceContextParameters(
                refinedFunction.contextParameters.filterIndexed { index, _ -> !typeclassContextMask.getOrElse(index) { false } },
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

    private fun typeclassContextMask(
        symbol: FirNamedFunctionSymbol,
        callInfo: CallInfo,
    ): List<Boolean> {
        val function = symbol.fir
        if (function.origin.generated) {
            return emptyList()
        }
        if (function.hasAnnotation(INSTANCE_ANNOTATION_CLASS_ID, session)) {
            return emptyList()
        }
        if (function.contextParameters.isEmpty()) {
            return emptyList()
        }

        val typeContext = buildTypeContext(callInfo, symbol)
        val inferredTypeArguments = inferFunctionTypeArguments(callInfo, symbol, typeContext)
        val planner =
            TypeclassResolutionPlanner(
                ruleProvider = { goal ->
                    sharedState.rulesForGoal(
                        session = session,
                        goal = goal,
                        canMaterializeVariable = typeContext.runtimeMaterializableVariableIds::contains,
                    )
                },
                bindableDesiredVariableIds = typeContext.bindableVariableIds,
            )

        return function.contextParameters.map { parameter ->
            val substitutedType =
                substituteInferredTypes(
                    type = parameter.returnTypeRef.coneType,
                    substitutions = inferredTypeArguments,
                    session = session,
                )
            val isTypeclass = sharedState.isTypeclassType(session, substitutedType)
            if (!isTypeclass) {
                return@map false
            }
            val goalModel = coneTypeToModel(substitutedType, typeContext.typeParameterModels) ?: return@map false
            val resolution = planner.resolve(goalModel, typeContext.directlyAvailableContextModels)
            when (resolution) {
                is ResolutionSearchResult.Success -> true
                is ResolutionSearchResult.Missing ->
                    sharedState.canDeriveGoal(
                        session = session,
                        goal = goalModel,
                        availableContexts = typeContext.directlyAvailableContextModels,
                    )
                else -> false
            }
        }
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
        if (typeContext.directlyAvailableContextModels.isEmpty()) {
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
                typeContext.directlyAvailableContextModels.any { availableContext ->
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

        function.typeParameters.zip(callInfo.typeArguments).forEach { (typeParameter, typeArgument) ->
            val explicitType = (typeArgument as? FirTypeProjectionWithVariance)?.typeRef?.coneType ?: return@forEach
            inferred[typeParameter.symbol] = explicitType
        }

        function.receiverParameter?.typeRef?.coneType?.let { receiverType ->
            callInfo.explicitReceiver?.safeResolvedOrInferredTypeOrNull(session)?.let { explicitReceiverType ->
                unifyFunctionTypeArguments(
                    parameterType = receiverType,
                    argumentType = explicitReceiverType,
                    functionTypeParameters = functionTypeParameters,
                    inferred = inferred,
                )
            }
        }

        function.valueParameters.zip(callInfo.arguments).forEach { (parameter, argument) ->
            val argumentType = argument.safeResolvedOrInferredTypeOrNull(session) ?: return@forEach
            unifyFunctionTypeArguments(
                parameterType = parameter.returnTypeRef.coneType,
                argumentType = argumentType,
                functionTypeParameters = functionTypeParameters,
                inferred = inferred,
            )
        }

        function.contextParameters.forEach { parameter ->
            typeContext.directlyAvailableContextTypes.forEach { availableType ->
                unifyFunctionTypeArguments(
                    parameterType = parameter.returnTypeRef.coneType,
                    argumentType = availableType,
                    functionTypeParameters = functionTypeParameters,
                    inferred = inferred,
                )
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

        unifyFunctionTypeArguments(
            parameterType = originalFunction.returnTypeRef.coneType,
            argumentType = call.resolvedType,
            functionTypeParameters = functionTypeParameters,
            inferred = inferred,
        )

        call.resolvedArgumentMapping?.forEach { (argument, parameter) ->
            val originalParameter =
                refinedParameters.indexOf(parameter).takeIf { index -> index >= 0 }?.let { index ->
                    originalFunction.valueParameters.getOrNull(index)
                } ?: originalParametersByName[parameter.name]
            if (originalParameter != null) {
                unifyFunctionTypeArguments(
                    parameterType = originalParameter.returnTypeRef.coneType,
                    argumentType = argument.resolvedType,
                    functionTypeParameters = functionTypeParameters,
                    inferred = inferred,
                )
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

    private fun unifyFunctionTypeArguments(
        parameterType: org.jetbrains.kotlin.fir.types.ConeKotlinType,
        argumentType: org.jetbrains.kotlin.fir.types.ConeKotlinType,
        functionTypeParameters: Set<FirTypeParameterSymbol>,
        inferred: MutableMap<FirTypeParameterSymbol, org.jetbrains.kotlin.fir.types.ConeKotlinType>,
    ) {
        val normalizedArgumentType = argumentType.approximateIntegerLiteralType()
        when (val loweredParameter = parameterType.lowerBoundIfFlexible()) {
            is ConeTypeParameterType -> {
                val symbol = loweredParameter.lookupTag.typeParameterSymbol
                if (symbol !in functionTypeParameters || symbol in inferred) {
                    return
                }
                inferred[symbol] = normalizedArgumentType
                return
            }

            is ConeTypeVariableType -> {
                val symbol =
                    (loweredParameter.typeConstructor.originalTypeParameter as? org.jetbrains.kotlin.fir.symbols.ConeTypeParameterLookupTag)
                        ?.typeParameterSymbol
                        ?: return
                if (symbol !in functionTypeParameters || symbol in inferred) {
                    return
                }
                inferred[symbol] = normalizedArgumentType
                return
            }

            else -> Unit
        }

        val parameterClassLike = parameterType.lowerBoundIfFlexible() as? ConeClassLikeType ?: return
        val argumentClassLike = normalizedArgumentType.lowerBoundIfFlexible() as? ConeClassLikeType ?: return
        if (parameterClassLike.lookupTag.classId != argumentClassLike.lookupTag.classId) {
            return
        }

        parameterClassLike.typeArguments.zip(argumentClassLike.typeArguments).forEach { (parameterArgument, actualArgument) ->
            val nestedParameterType = parameterArgument.type ?: return@forEach
            val nestedActualType = actualArgument.type ?: return@forEach
            unifyFunctionTypeArguments(
                parameterType = nestedParameterType,
                argumentType = nestedActualType,
                functionTypeParameters = functionTypeParameters,
                inferred = inferred,
            )
        }
    }
}
