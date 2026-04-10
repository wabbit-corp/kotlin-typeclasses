// SPDX-License-Identifier: AGPL-3.0-or-later

@file:OptIn(
    org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess::class,
    org.jetbrains.kotlin.fir.symbols.SymbolInternals::class,
)

package one.wabbit.typeclass.plugin

import one.wabbit.typeclass.plugin.model.TcTypeParameter
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirTypeParameterRef
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirNamedArgumentExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.substitution.AbstractConeSubstitutor
import org.jetbrains.kotlin.fir.resolve.withCombinedAttributesFrom
import org.jetbrains.kotlin.fir.symbols.ConeTypeParameterLookupTag
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjectionIn
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjectionOut
import org.jetbrains.kotlin.fir.types.ConeStarProjection
import org.jetbrains.kotlin.fir.types.ConeStubType
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.ConeTypeProjection
import org.jetbrains.kotlin.fir.types.ConeTypeVariableType
import org.jetbrains.kotlin.fir.types.FirTypeProjectionWithVariance
import org.jetbrains.kotlin.fir.types.ProjectionKind
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.lowerBoundIfFlexible
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.fir.types.typeContext
import org.jetbrains.kotlin.fir.types.variance
import org.jetbrains.kotlin.fir.types.withNullabilityOf
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.types.TypeSubstitutor
import org.jetbrains.kotlin.types.Variance

internal fun FirExpression.safeResolvedOrInferredTypeOrNull(
    session: FirSession,
    containingFunction: FirNamedFunctionSymbol? = null,
    containingClassTypeParameters: Iterable<FirTypeParameterSymbol> = emptyList(),
    sharedState: TypeclassPluginSharedState? = null,
    containingFunctions: List<FirNamedFunctionSymbol> = listOfNotNull(containingFunction),
): ConeKotlinType? {
    val resolved = safeResolvedTypeOrNull()
    val functionCall = this as? FirFunctionCall ?: return resolved
    val inferred =
        functionCall.inferReturnTypeOrNull(
            session = session,
            containingFunction = containingFunction,
            containingClassTypeParameters = containingClassTypeParameters,
            sharedState = sharedState,
            containingFunctions = containingFunctions,
        )
    return when {
        resolved == null -> inferred
        resolved.containsTypeParameterReference() && inferred != null -> inferred
        else -> resolved
    }
}

internal fun inferFunctionTypeArgumentsFromCallSite(
    session: FirSession,
    functionCall: FirFunctionCall,
    resolvedFunction: FirNamedFunctionSymbol,
    containingFunction: FirNamedFunctionSymbol?,
    containingClassTypeParameters: Iterable<FirTypeParameterSymbol> = emptyList(),
    sharedState: TypeclassPluginSharedState,
    containingFunctions: List<FirNamedFunctionSymbol> = listOfNotNull(containingFunction),
): Map<FirTypeParameterSymbol, ConeKotlinType> {
    val function = resolvedFunction.fir
    val inferred = linkedMapOf<FirTypeParameterSymbol, ConeKotlinType>()
    val functionTypeParameters =
        function.typeParameters.mapTo(linkedSetOf(), FirTypeParameterRef::symbol)
    val inferenceTypeParameterModels =
        buildInferenceTypeParameterModels(
            bindableTypeParameters = functionTypeParameters,
            containingFunction = containingFunction,
            containingClassTypeParameters = containingClassTypeParameters,
            containingFunctions = containingFunctions,
        )
    resolvedFunction
        .explicitCallTypeParameters(functionCall.typeArguments.size, session, sharedState)
        .zip(functionCall.typeArguments)
        .forEach { (typeParameter, typeArgument) ->
            val explicitType =
                (typeArgument as? FirTypeProjectionWithVariance)?.typeRef?.coneType
                    ?: return@forEach
            inferred[typeParameter.symbol] = explicitType
        }

    val directConstraints =
        linkedMapOf<FirTypeParameterSymbol, MutableTypeBindingConstraints<ConeKotlinType>>()
    function.receiverParameter?.typeRef?.coneType?.let { receiverType ->
        functionCall.explicitReceiver
            ?.safeResolvedOrInferredTypeOrNull(
                session = session,
                containingFunction = containingFunction,
                containingClassTypeParameters = containingClassTypeParameters,
                sharedState = sharedState,
                containingFunctions = containingFunctions,
            )
            ?.let { explicitReceiverType ->
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

    val parameterByArgument =
        functionCall.parameterByArgumentMappingOrNull(function.valueParameters)
    if (parameterByArgument != null) {
        parameterByArgument.forEach { (argument, parameter) ->
            val argumentType =
                (argument as? FirFunctionCall)?.inferReturnTypeOrNull(
                    session,
                    containingFunction,
                    containingClassTypeParameters,
                    sharedState,
                    containingFunctions,
                )
                    ?: argument.safeResolvedOrInferredTypeOrNull(
                        session,
                        containingFunction,
                        containingClassTypeParameters,
                        sharedState,
                        containingFunctions,
                    )
                    ?: return@forEach
            collectFunctionTypeArgumentConstraintsForInference(
                session = session,
                sharedState = sharedState,
                typeParameterModels = inferenceTypeParameterModels,
                parameterType = parameter.inferenceParameterType(),
                argumentType = argumentType,
                functionTypeParameters = functionTypeParameters,
                excludedSymbols = inferred.keys,
                constraintsBySymbol = directConstraints,
            )
        }
    } else {
        function.valueParameters.zip(functionCall.arguments).forEach { (parameter, argument) ->
            val nestedCall = argument as? FirFunctionCall
            val argumentType =
                nestedCall?.inferReturnTypeOrNull(
                    session,
                    containingFunction,
                    containingClassTypeParameters,
                    sharedState,
                    containingFunctions,
                )
                    ?: argument.safeResolvedOrInferredTypeOrNull(
                        session,
                        containingFunction,
                        containingClassTypeParameters,
                        sharedState,
                        containingFunctions,
                    )
                    ?: return@forEach
            collectFunctionTypeArgumentConstraintsForInference(
                session = session,
                sharedState = sharedState,
                typeParameterModels = inferenceTypeParameterModels,
                parameterType = parameter.inferenceParameterType(),
                argumentType = argumentType,
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
        )
        .resolvedByKey
        .forEach { (symbol, type) ->
            if (symbol !in inferred) {
                inferred[symbol] = type
            }
        }

    val localContextConstraints =
        linkedMapOf<FirTypeParameterSymbol, MutableTypeBindingConstraints<ConeKotlinType>>()
    buildLocalContextConstraintsForInference(
            session = session,
            sharedState = sharedState,
            containingFunction = containingFunction,
            contextParameterTypes =
                function.contextParameters.map { parameter -> parameter.returnTypeRef.coneType },
            containingFunctions = containingFunctions,
        )
        .forEach { (parameterType, argumentType) ->
            collectFunctionTypeArgumentConstraintsForInference(
                session = session,
                sharedState = sharedState,
                typeParameterModels = inferenceTypeParameterModels,
                parameterType = parameterType,
                argumentType = argumentType,
                functionTypeParameters = functionTypeParameters,
                excludedSymbols = inferred.keys,
                constraintsBySymbol = localContextConstraints,
            )
        }
    resolveFirTypeBindingConstraints(
            session = session,
            sharedState = sharedState,
            typeParameterModels = inferenceTypeParameterModels,
            constraintsBySymbol = localContextConstraints,
        )
        .resolvedByKey
        .forEach { (symbol, type) ->
            if (symbol !in inferred) {
                inferred[symbol] = type
            }
        }

    return inferred
}

internal fun FirNamedFunctionSymbol.explicitCallTypeParameters(
    explicitTypeArgumentCount: Int,
    session: FirSession,
    sharedState: TypeclassPluginSharedState,
): List<FirTypeParameterRef> {
    val visibleTypeParameterNames = wrapperVisibleTypeParameterNames(session, sharedState).toSet()
    return if (explicitTypeArgumentCount <= visibleTypeParameterNames.size) {
        fir.typeParameters.filter { typeParameter ->
            typeParameter.symbol.name.asString() in visibleTypeParameterNames
        }
    } else {
        fir.typeParameters
    }
}

internal fun FirFunctionCall.inferReturnTypeOrNull(
    session: FirSession,
    containingFunction: FirNamedFunctionSymbol? = null,
    containingClassTypeParameters: Iterable<FirTypeParameterSymbol> = emptyList(),
    sharedState: TypeclassPluginSharedState? = null,
    containingFunctions: List<FirNamedFunctionSymbol> = listOfNotNull(containingFunction),
): ConeKotlinType? {
    val symbol =
        (calleeReference as? FirResolvedNamedReference)?.resolvedSymbol as? FirFunctionSymbol<*>
            ?: return inferConstructedClassTypeOrNull(
                session = session,
                containingFunction = containingFunction,
                containingClassTypeParameters = containingClassTypeParameters,
                sharedState = sharedState,
                containingFunctions = containingFunctions,
            )
    val function = symbol.fir
    val functionTypeParameters = buildSet {
        addAll(function.typeParameters.mapTo(linkedSetOf(), FirTypeParameterRef::symbol))
        collectBindableTypeParameters(function.returnTypeRef.coneType)
        function.receiverParameter?.typeRef?.coneType?.let(::collectBindableTypeParameters)
        function.valueParameters.forEach { parameter ->
            collectBindableTypeParameters(parameter.returnTypeRef.coneType)
        }
    }
    if (functionTypeParameters.isEmpty()) {
        return function.returnTypeRef.coneType
    }

    val explicitTypeArguments =
        buildMap<FirTypeParameterSymbol, ConeKotlinType> {
            function.typeParameters.zip(typeArguments).forEach { (typeParameter, typeArgument) ->
                val explicitType =
                    (typeArgument as? FirTypeProjectionWithVariance)?.typeRef?.coneType
                        ?: return@forEach
                put(typeParameter.symbol, explicitType)
            }
        }
    val receiverConstraint =
        function.receiverParameter?.typeRef?.coneType?.let { receiverType ->
            explicitReceiver
                ?.safeResolvedOrInferredTypeOrNull(
                    session = session,
                    containingFunction = containingFunction,
                    containingClassTypeParameters = containingClassTypeParameters,
                    sharedState = sharedState,
                    containingFunctions = containingFunctions,
                )
                ?.let { explicitReceiverType -> receiverType to explicitReceiverType }
        }
    val argumentConstraints = buildList {
        val parameterByArgument = parameterByArgumentMappingOrNull(function.valueParameters)
        if (parameterByArgument != null) {
            parameterByArgument.forEach { (argument, parameter) ->
                val argumentType =
                    argument.safeResolvedOrInferredTypeOrNull(
                        session,
                        containingFunction,
                        containingClassTypeParameters,
                        sharedState,
                        containingFunctions,
                    ) ?: return@forEach
                add(parameter.inferenceParameterType() to argumentType)
            }
        } else {
            function.valueParameters.zip(arguments).forEach { (parameter, argument) ->
                val argumentType =
                    argument.safeResolvedOrInferredTypeOrNull(
                        session,
                        containingFunction,
                        containingClassTypeParameters,
                        sharedState,
                        containingFunctions,
                    ) ?: return@forEach
                add(parameter.inferenceParameterType() to argumentType)
            }
        }
    }

    val result =
        inferReturnTypeFromCallSiteTypes(
            session = session,
            returnType = function.returnTypeRef.coneType,
            functionTypeParameters = functionTypeParameters,
            explicitTypeArguments = explicitTypeArguments,
            receiverConstraint = receiverConstraint,
            argumentConstraints = argumentConstraints,
            localContextConstraints =
                buildLocalContextConstraintsForInference(
                    session = session,
                    sharedState = sharedState,
                    containingFunction = containingFunction,
                    contextParameterTypes =
                        function.contextParameters.map { parameter ->
                            parameter.returnTypeRef.coneType
                        },
                    containingFunctions = containingFunctions,
                ),
            sharedState = sharedState,
            containingFunction = containingFunction,
            containingClassTypeParameters = containingClassTypeParameters,
            containingFunctions = containingFunctions,
        )
    return result
}

internal fun inferReturnTypeFromCallSiteTypes(
    session: FirSession,
    returnType: ConeKotlinType,
    functionTypeParameters: Set<FirTypeParameterSymbol>,
    explicitTypeArguments: Map<FirTypeParameterSymbol, ConeKotlinType>,
    receiverConstraint: Pair<ConeKotlinType, ConeKotlinType>?,
    argumentConstraints: Iterable<Pair<ConeKotlinType, ConeKotlinType>>,
    localContextConstraints: Iterable<Pair<ConeKotlinType, ConeKotlinType>> = emptyList(),
    sharedState: TypeclassPluginSharedState? = null,
    containingFunction: FirNamedFunctionSymbol? = null,
    containingClassTypeParameters: Iterable<FirTypeParameterSymbol> = emptyList(),
    containingFunctions: List<FirNamedFunctionSymbol> = listOfNotNull(containingFunction),
): ConeKotlinType {
    val inferred =
        inferTypeArgumentsFromCallSiteTypes(
            session = session,
            functionTypeParameters = functionTypeParameters,
            explicitTypeArguments = explicitTypeArguments,
            receiverConstraint = receiverConstraint,
            argumentConstraints = argumentConstraints,
            localContextConstraints = localContextConstraints,
            sharedState = sharedState,
            containingFunction = containingFunction,
            containingClassTypeParameters = containingClassTypeParameters,
            containingFunctions = containingFunctions,
        )
    return substituteInferredTypes(type = returnType, substitutions = inferred, session = session)
}

internal fun inferTypeArgumentsFromCallSiteTypes(
    session: FirSession,
    functionTypeParameters: Set<FirTypeParameterSymbol>,
    explicitTypeArguments: Map<FirTypeParameterSymbol, ConeKotlinType>,
    receiverConstraint: Pair<ConeKotlinType, ConeKotlinType>?,
    argumentConstraints: Iterable<Pair<ConeKotlinType, ConeKotlinType>>,
    localContextConstraints: Iterable<Pair<ConeKotlinType, ConeKotlinType>> = emptyList(),
    sharedState: TypeclassPluginSharedState? = null,
    containingFunction: FirNamedFunctionSymbol? = null,
    containingClassTypeParameters: Iterable<FirTypeParameterSymbol> = emptyList(),
    containingFunctions: List<FirNamedFunctionSymbol> = listOfNotNull(containingFunction),
): Map<FirTypeParameterSymbol, ConeKotlinType> {
    if (functionTypeParameters.isEmpty()) {
        return emptyMap()
    }

    val inferred = linkedMapOf<FirTypeParameterSymbol, ConeKotlinType>()
    inferred.putAll(explicitTypeArguments)
    val inferenceTypeParameterModels =
        buildInferenceTypeParameterModels(
            bindableTypeParameters = functionTypeParameters,
            containingFunction = containingFunction,
            containingClassTypeParameters = containingClassTypeParameters,
            containingFunctions = containingFunctions,
        )
    val directConstraints =
        linkedMapOf<FirTypeParameterSymbol, MutableTypeBindingConstraints<ConeKotlinType>>()

    receiverConstraint?.let { (parameterType, argumentType) ->
        collectFunctionTypeArgumentConstraintsForInference(
            session = session,
            sharedState = sharedState,
            typeParameterModels = inferenceTypeParameterModels,
            parameterType = parameterType,
            argumentType = argumentType,
            functionTypeParameters = functionTypeParameters,
            excludedSymbols = inferred.keys,
            constraintsBySymbol = directConstraints,
        )
    }
    argumentConstraints.forEach { (parameterType, argumentType) ->
        collectFunctionTypeArgumentConstraintsForInference(
            session = session,
            sharedState = sharedState,
            typeParameterModels = inferenceTypeParameterModels,
            parameterType = parameterType,
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
        )
        .resolvedByKey
        .forEach { (symbol, type) ->
            if (symbol !in inferred) {
                inferred[symbol] = type
            }
        }
    if (localContextConstraints.none()) {
        return inferred
    }

    val localConstraints =
        linkedMapOf<FirTypeParameterSymbol, MutableTypeBindingConstraints<ConeKotlinType>>()
    localContextConstraints.forEach { (parameterType, argumentType) ->
        collectFunctionTypeArgumentConstraintsForInference(
            session = session,
            sharedState = sharedState,
            typeParameterModels = inferenceTypeParameterModels,
            parameterType = parameterType,
            argumentType = argumentType,
            functionTypeParameters = functionTypeParameters,
            excludedSymbols = inferred.keys,
            constraintsBySymbol = localConstraints,
        )
    }
    resolveFirTypeBindingConstraints(
            session = session,
            sharedState = sharedState,
            typeParameterModels = inferenceTypeParameterModels,
            constraintsBySymbol = localConstraints,
        )
        .resolvedByKey
        .forEach { (symbol, type) ->
            if (symbol !in inferred) {
                inferred[symbol] = type
            }
        }
    return inferred
}

private fun FirFunctionCall.inferConstructedClassTypeOrNull(
    session: FirSession,
    containingFunction: FirNamedFunctionSymbol? = null,
    containingClassTypeParameters: Iterable<FirTypeParameterSymbol> = emptyList(),
    sharedState: TypeclassPluginSharedState? = null,
    containingFunctions: List<FirNamedFunctionSymbol> = listOfNotNull(containingFunction),
): ConeKotlinType? {
    val resolved = safeResolvedTypeOrNull() ?: return null
    val classLike = resolved.lowerBoundIfFlexible() as? ConeClassLikeType ?: return null
    val classSymbol =
        session.symbolProvider.getClassLikeSymbolByClassId(classLike.lookupTag.classId)
            as? FirRegularClassSymbol ?: return resolved
    val primaryConstructor =
        classSymbol.fir.declarations.filterIsInstance<FirConstructor>().singleOrNull { constructor
            ->
            constructor.isPrimary
        } ?: return resolved
    val classTypeParameters =
        classSymbol.fir.typeParameters.mapTo(linkedSetOf(), FirTypeParameterRef::symbol)
    if (classTypeParameters.isEmpty()) {
        return resolved
    }
    val inferred = linkedMapOf<FirTypeParameterSymbol, ConeKotlinType>()
    val inferenceTypeParameterModels =
        buildInferenceTypeParameterModels(
            bindableTypeParameters = classTypeParameters,
            containingFunction = null,
            containingClassTypeParameters = containingClassTypeParameters,
            containingFunctions = containingFunctions,
        )
    val directConstraints =
        linkedMapOf<FirTypeParameterSymbol, MutableTypeBindingConstraints<ConeKotlinType>>()
    val parameterByArgument = parameterByArgumentMappingOrNull(primaryConstructor.valueParameters)
    if (parameterByArgument != null) {
        parameterByArgument.forEach { (argument, parameter) ->
            val argumentType =
                argument.safeResolvedOrInferredTypeOrNull(
                    session = session,
                    containingFunction = containingFunction,
                    containingClassTypeParameters = containingClassTypeParameters,
                    sharedState = sharedState,
                    containingFunctions = containingFunctions,
                ) ?: return@forEach
            collectFunctionTypeArgumentConstraintsForInference(
                session = session,
                sharedState = null,
                typeParameterModels = inferenceTypeParameterModels,
                parameterType = parameter.inferenceParameterType(),
                argumentType = argumentType,
                functionTypeParameters = classTypeParameters,
                excludedSymbols = inferred.keys,
                constraintsBySymbol = directConstraints,
            )
        }
    } else {
        primaryConstructor.valueParameters.zip(arguments).forEach { (parameter, argument) ->
            val argumentType =
                argument.safeResolvedOrInferredTypeOrNull(
                    session = session,
                    containingFunction = containingFunction,
                    containingClassTypeParameters = containingClassTypeParameters,
                    sharedState = sharedState,
                    containingFunctions = containingFunctions,
                ) ?: return@forEach
            collectFunctionTypeArgumentConstraintsForInference(
                session = session,
                sharedState = null,
                typeParameterModels = inferenceTypeParameterModels,
                parameterType = parameter.inferenceParameterType(),
                argumentType = argumentType,
                functionTypeParameters = classTypeParameters,
                excludedSymbols = inferred.keys,
                constraintsBySymbol = directConstraints,
            )
        }
    }
    resolveFirTypeBindingConstraints(
            session = session,
            sharedState = null,
            typeParameterModels = inferenceTypeParameterModels,
            constraintsBySymbol = directConstraints,
        )
        .resolvedByKey
        .forEach { (symbol, type) ->
            if (symbol !in inferred) {
                inferred[symbol] = type
            }
        }
    return substituteInferredTypes(type = resolved, substitutions = inferred, session = session)
}

private fun MutableSet<FirTypeParameterSymbol>.collectBindableTypeParameters(type: ConeKotlinType) {
    when (val lowered = type.lowerBoundIfFlexible()) {
        is ConeTypeParameterType -> add(lowered.lookupTag.typeParameterSymbol)
        is ConeTypeVariableType ->
            (lowered.typeConstructor.originalTypeParameter as? ConeTypeParameterLookupTag)
                ?.typeParameterSymbol
                ?.let(::add)
        is ConeClassLikeType ->
            lowered.typeArguments.forEach { argument ->
                argument.type?.let(::collectBindableTypeParameters)
            }
        else -> Unit
    }
}

private fun ConeKotlinType.containsTypeParameterReference(): Boolean =
    when (val lowered = lowerBoundIfFlexible()) {
        is ConeTypeParameterType -> true
        is ConeTypeVariableType -> true
        is ConeStubType -> true
        is ConeClassLikeType ->
            lowered.typeArguments.any { argument ->
                argument.type == null || argument.type?.containsTypeParameterReference() == true
            }
        else -> false
    }

internal fun collectFunctionTypeArgumentConstraintsForInference(
    session: FirSession,
    sharedState: TypeclassPluginSharedState?,
    typeParameterModels: MutableMap<FirTypeParameterSymbol, TcTypeParameter>,
    parameterType: ConeKotlinType,
    argumentType: ConeKotlinType,
    functionTypeParameters: Set<FirTypeParameterSymbol>,
    excludedSymbols: Set<FirTypeParameterSymbol>,
    constraintsBySymbol:
        MutableMap<FirTypeParameterSymbol, MutableTypeBindingConstraints<ConeKotlinType>>,
    position: ExactTypeArgumentPosition = ExactTypeArgumentPosition.EXACT,
) {
    val normalizedArgumentType = argumentType.approximateIntegerLiteralType()
    ensureInferenceTypeParameterModels(typeParameterModels, parameterType, normalizedArgumentType)
    when (val loweredParameter = parameterType.lowerBoundIfFlexible()) {
        is ConeTypeParameterType -> {
            val symbol = loweredParameter.lookupTag.typeParameterSymbol
            if (symbol !in functionTypeParameters || symbol in excludedSymbols) {
                return
            }
            val actualModel = coneTypeToModel(normalizedArgumentType, typeParameterModels) ?: return
            recordTypeBindingConstraint(
                constraintsByKey = constraintsBySymbol,
                key = symbol,
                candidate = TypeBindingBound(value = normalizedArgumentType, model = actualModel),
                position = position,
            )
            return
        }

        is ConeTypeVariableType -> {
            val symbol =
                (loweredParameter.typeConstructor.originalTypeParameter
                        as? ConeTypeParameterLookupTag)
                    ?.typeParameterSymbol ?: return
            if (symbol !in functionTypeParameters || symbol in excludedSymbols) {
                return
            }
            val actualModel = coneTypeToModel(normalizedArgumentType, typeParameterModels) ?: return
            recordTypeBindingConstraint(
                constraintsByKey = constraintsBySymbol,
                key = symbol,
                candidate = TypeBindingBound(value = normalizedArgumentType, model = actualModel),
                position = position,
            )
            return
        }

        else -> Unit
    }

    val parameterClassLike = parameterType.lowerBoundIfFlexible() as? ConeClassLikeType ?: return
    val argumentClassLike =
        normalizedArgumentType.projectToMatchingInferenceSupertype(
            expectedClassId = parameterClassLike.lookupTag.classId,
            session = session,
        ) as? ConeClassLikeType ?: return

    val declaredVariances =
        (session.symbolProvider.getClassLikeSymbolByClassId(parameterClassLike.lookupTag.classId)
                as? FirRegularClassSymbol)
            ?.fir
            ?.typeParameters
            ?.map { typeParameter -> typeParameter.symbol.fir.variance }
            .orEmpty()

    parameterClassLike.typeArguments.zip(argumentClassLike.typeArguments).forEachIndexed {
        index,
        (parameterArgument, actualArgument) ->
        val nestedParameterType = parameterArgument.type ?: return@forEachIndexed
        val nestedActualType = actualArgument.type ?: return@forEachIndexed
        val nestedVariance =
            nestedInferenceVariance(
                parameterKind = parameterArgument.kind,
                actualKind = actualArgument.kind,
                declaredVariance = declaredVariances.getOrNull(index) ?: Variance.INVARIANT,
            ) ?: return@forEachIndexed
        collectFunctionTypeArgumentConstraintsForInference(
            session = session,
            sharedState = sharedState,
            typeParameterModels = typeParameterModels,
            parameterType = nestedParameterType,
            argumentType = nestedActualType,
            functionTypeParameters = functionTypeParameters,
            excludedSymbols = excludedSymbols,
            constraintsBySymbol = constraintsBySymbol,
            position = position.compose(nestedVariance),
        )
    }
}

private fun FirExpression.safeResolvedTypeOrNull(): ConeKotlinType? =
    runCatching { resolvedType }.getOrNull()

private fun ConeKotlinType.projectToMatchingInferenceSupertype(
    expectedClassId: ClassId,
    session: FirSession,
): ConeKotlinType? {
    return projectToMatchingSupertype(
        actualType = this,
        expectedClassifier = expectedClassId,
        classifierOf = { type ->
            (type.lowerBoundIfFlexible() as? ConeClassLikeType)?.lookupTag?.classId
        },
        visitKeyOf = ConeKotlinType::inferenceProjectionVisitKey,
        directSupertypes = { currentType ->
            val actualSimpleType =
                currentType.lowerBoundIfFlexible() as? ConeClassLikeType
                    ?: return@projectToMatchingSupertype emptyList()
            val actualClassId = actualSimpleType.lookupTag.classId
            val classSymbol =
                runCatching {
                        session.symbolProvider.getClassLikeSymbolByClassId(actualClassId)
                            as? FirRegularClassSymbol
                    }
                    .getOrNull() ?: return@projectToMatchingSupertype emptyList()
            val substitutions =
                classSymbol.fir.typeParameters.zip(actualSimpleType.typeArguments).associate {
                    (parameter, argument) ->
                    parameter.symbol to argument
                }
            classSymbol.fir.declaredOrResolvedSuperTypes().map { superType ->
                if (substitutions.isEmpty()) {
                        superType
                    } else {
                        substituteInferredTypeProjections(superType, substitutions, session)
                    }
                    .withNullabilityOf(actualSimpleType, session.typeContext)
            }
        },
    )
}

private sealed interface FirInferenceProjectionVisitKey {
    data class ClassLike(
        val classId: ClassId,
        val isNullable: Boolean,
        val arguments: List<FirInferenceProjectionArgumentKey>,
    ) : FirInferenceProjectionVisitKey

    data class TypeParameter(val symbol: FirTypeParameterSymbol, val isNullable: Boolean) :
        FirInferenceProjectionVisitKey

    data class TypeVariable(val symbol: FirTypeParameterSymbol?, val isNullable: Boolean) :
        FirInferenceProjectionVisitKey

    data class Stub(val symbol: FirTypeParameterSymbol?, val isNullable: Boolean) :
        FirInferenceProjectionVisitKey

    data class Opaque(val kind: String, val isNullable: Boolean) : FirInferenceProjectionVisitKey
}

private data class FirInferenceProjectionArgumentKey(
    val kind: ProjectionKind,
    val type: FirInferenceProjectionVisitKey?,
)

private fun ConeKotlinType.inferenceProjectionVisitKey(): FirInferenceProjectionVisitKey =
    when (val lowered = lowerBoundIfFlexible()) {
        is ConeClassLikeType ->
            FirInferenceProjectionVisitKey.ClassLike(
                classId = lowered.lookupTag.classId,
                isNullable = lowered.isMarkedNullable,
                arguments =
                    lowered.typeArguments.map(ConeTypeProjection::inferenceProjectionArgumentKey),
            )
        is ConeTypeParameterType ->
            FirInferenceProjectionVisitKey.TypeParameter(
                symbol = lowered.lookupTag.typeParameterSymbol,
                isNullable = lowered.isMarkedNullable,
            )
        is ConeTypeVariableType ->
            FirInferenceProjectionVisitKey.TypeVariable(
                symbol =
                    (lowered.typeConstructor.originalTypeParameter as? ConeTypeParameterLookupTag)
                        ?.typeParameterSymbol,
                isNullable = lowered.isMarkedNullable,
            )
        is ConeStubType ->
            FirInferenceProjectionVisitKey.Stub(
                symbol =
                    (lowered.constructor.variable.typeConstructor.originalTypeParameter
                            as? ConeTypeParameterLookupTag)
                        ?.typeParameterSymbol,
                isNullable = lowered.isMarkedNullable,
            )
        else ->
            FirInferenceProjectionVisitKey.Opaque(
                kind = lowered::class.qualifiedName ?: lowered::class.simpleName ?: "opaque",
                isNullable = lowered.isMarkedNullable,
            )
    }

private fun ConeTypeProjection.inferenceProjectionArgumentKey(): FirInferenceProjectionArgumentKey =
    FirInferenceProjectionArgumentKey(kind = kind, type = type?.inferenceProjectionVisitKey())

private fun buildLocalContextConstraintsForInference(
    session: FirSession,
    sharedState: TypeclassPluginSharedState?,
    containingFunction: FirNamedFunctionSymbol?,
    contextParameterTypes: List<ConeKotlinType>,
    containingFunctions: List<FirNamedFunctionSymbol> = listOfNotNull(containingFunction),
): List<Pair<ConeKotlinType, ConeKotlinType>> {
    if (sharedState == null || containingFunctions.isEmpty() || contextParameterTypes.isEmpty()) {
        return emptyList()
    }
    val localContextTypes = buildList {
        containingFunctions.forEach { function ->
            function.fir.receiverParameter
                ?.typeRef
                ?.coneType
                ?.localEvidenceTypes(session, sharedState.configuration)
                ?.let(::addAll)
            function.fir.contextParameters.forEach { parameter ->
                addAll(
                    parameter.returnTypeRef.coneType.localEvidenceTypes(
                        session,
                        sharedState.configuration,
                    )
                )
            }
        }
    }
    if (localContextTypes.isEmpty()) {
        return emptyList()
    }
    return buildList {
        contextParameterTypes.forEach { parameterType ->
            localContextTypes.forEach { localContextType -> add(parameterType to localContextType) }
        }
    }
}

private fun FirFunctionCall.parameterByArgumentMappingOrNull(
    parameters: List<FirValueParameter>
): Map<FirExpression, FirValueParameter>? =
    resolvedArgumentMapping
        ?: buildNamedAndPositionalArgumentMapping(parameters).takeIf { it.isNotEmpty() }

private fun FirFunctionCall.buildNamedAndPositionalArgumentMapping(
    parameters: List<FirValueParameter>
): LinkedHashMap<FirExpression, FirValueParameter> {
    return buildNamedAndPositionalArgumentMapping(
        arguments =
            arguments.map { argument ->
                (argument as? FirNamedArgumentExpression)?.name to argument.unwrapArgument()
            },
        parameters = parameters,
        parameterName = FirValueParameter::name,
        isVararg = FirValueParameter::isVararg,
    )
}

internal fun <A, P, N> buildNamedAndPositionalArgumentMapping(
    arguments: List<Pair<N?, A>>,
    parameters: List<P>,
    parameterName: (P) -> N,
    isVararg: (P) -> Boolean,
): LinkedHashMap<A, P> {
    if (arguments.isEmpty() || parameters.isEmpty()) {
        return linkedMapOf()
    }

    val parametersByName = parameters.associateBy(parameterName)
    val assignedNonVarargParameters = linkedSetOf<P>()
    var nextPositionalIndex = 0
    val mapping = linkedMapOf<A, P>()
    val remainingNamedParametersByArgumentIndex = Array(arguments.size + 1) { emptySet<N>() }
    val remainingNamedParameters = linkedSetOf<N>()

    for (argumentIndex in arguments.indices.reversed()) {
        arguments[argumentIndex].first?.let(remainingNamedParameters::add)
        remainingNamedParametersByArgumentIndex[argumentIndex] = remainingNamedParameters.toSet()
    }

    fun nextPositionalParameter(argumentIndex: Int): P? {
        while (nextPositionalIndex < parameters.size) {
            val candidate = parameters[nextPositionalIndex]
            if (isVararg(candidate)) {
                val namedParametersAfterCurrentArgument =
                    remainingNamedParametersByArgumentIndex.getOrElse(argumentIndex + 1) {
                        emptySet()
                    }
                val remainingUnnamedArguments =
                    arguments.subList(argumentIndex, arguments.size).count { (name, _) ->
                        name == null
                    }
                val laterRequiredPositionalParameters =
                    parameters.subList(nextPositionalIndex + 1, parameters.size).count { parameter
                        ->
                        !isVararg(parameter) &&
                            parameter !in assignedNonVarargParameters &&
                            parameterName(parameter) !in namedParametersAfterCurrentArgument
                    }
                if (remainingUnnamedArguments <= laterRequiredPositionalParameters) {
                    nextPositionalIndex++
                    continue
                }
                return candidate
            }
            nextPositionalIndex++
            if (candidate in assignedNonVarargParameters) {
                continue
            }
            return candidate
        }
        return null
    }

    arguments.forEachIndexed { argumentIndex, (name, argument) ->
        val parameter =
            name?.let(parametersByName::get)
                ?: nextPositionalParameter(argumentIndex)
                ?: return@forEachIndexed
        if (!isVararg(parameter) && !assignedNonVarargParameters.add(parameter)) {
            return@forEachIndexed
        }
        mapping[argument] = parameter
    }

    return mapping
}

private fun FirValueParameter.inferenceParameterType(): ConeKotlinType =
    if (isVararg) {
        returnTypeRef.coneType.varargElementTypeOrSelf()
    } else {
        returnTypeRef.coneType
    }

private fun ConeKotlinType.varargElementTypeOrSelf(): ConeKotlinType {
    val lowered = lowerBoundIfFlexible() as? ConeClassLikeType ?: return this
    return lowered.typeArguments.singleOrNull()?.type ?: this
}

internal fun substituteInferredTypes(
    type: ConeKotlinType,
    substitutions: Map<FirTypeParameterSymbol, ConeKotlinType>,
    session: FirSession,
): ConeKotlinType {
    if (substitutions.isEmpty()) {
        return type
    }
    return InferredTypeSubstitutor(substitutions, session).substituteOrSelf(type)
}

private fun substituteInferredTypeProjections(
    type: ConeKotlinType,
    substitutions: Map<FirTypeParameterSymbol, ConeTypeProjection>,
    session: FirSession,
): ConeKotlinType {
    if (substitutions.isEmpty()) {
        return type
    }
    return InferredTypeProjectionSubstitutor(substitutions, session).substituteOrSelf(type)
}

private class InferredTypeSubstitutor(
    private val substitutions: Map<FirTypeParameterSymbol, ConeKotlinType>,
    session: FirSession,
) : AbstractConeSubstitutor(session.typeContext) {
    override fun substituteType(type: ConeKotlinType): ConeKotlinType? {
        val replacement =
            when (type) {
                is ConeTypeParameterType -> substitutions[type.lookupTag.typeParameterSymbol]
                is ConeTypeVariableType ->
                    (type.typeConstructor.originalTypeParameter as? ConeTypeParameterLookupTag)
                        ?.typeParameterSymbol
                        ?.let(substitutions::get)
                is ConeStubType ->
                    (type.constructor.variable.typeConstructor.originalTypeParameter
                            as? ConeTypeParameterLookupTag)
                        ?.typeParameterSymbol
                        ?.let(substitutions::get)
                else -> null
            } ?: return null
        return replacement.withNullabilityOf(type, typeContext).withCombinedAttributesFrom(type)
    }
}

private class InferredTypeProjectionSubstitutor(
    private val substitutions: Map<FirTypeParameterSymbol, ConeTypeProjection>,
    session: FirSession,
) : AbstractConeSubstitutor(session.typeContext) {
    override fun substituteType(type: ConeKotlinType): ConeKotlinType? {
        val replacement = replacementFor(type) ?: return null
        val replacementType = replacement.type ?: return null
        return replacementType.withNullabilityOf(type, typeContext).withCombinedAttributesFrom(type)
    }

    override fun substituteArgument(
        projection: ConeTypeProjection,
        index: Int,
    ): ConeTypeProjection? {
        val originalType = projection.type ?: return null
        val replacement =
            replacementFor(originalType) ?: return super.substituteArgument(projection, index)
        val replacementType =
            replacement.type
                ?.withNullabilityOf(originalType, typeContext)
                ?.withCombinedAttributesFrom(originalType)
        return combineProjections(
            original = projection,
            replacement = replacement,
            replacementType = replacementType,
        )
    }

    private fun replacementFor(type: ConeKotlinType): ConeTypeProjection? =
        when (type) {
            is ConeTypeParameterType -> substitutions[type.lookupTag.typeParameterSymbol]
            is ConeTypeVariableType ->
                (type.typeConstructor.originalTypeParameter as? ConeTypeParameterLookupTag)
                    ?.typeParameterSymbol
                    ?.let(substitutions::get)
            is ConeStubType ->
                (type.constructor.variable.typeConstructor.originalTypeParameter
                        as? ConeTypeParameterLookupTag)
                    ?.typeParameterSymbol
                    ?.let(substitutions::get)
            else -> null
        }
}

private fun combineProjections(
    original: ConeTypeProjection,
    replacement: ConeTypeProjection,
    replacementType: ConeKotlinType?,
): ConeTypeProjection {
    if (
        original.kind == ProjectionKind.STAR ||
            replacement.kind == ProjectionKind.STAR ||
            replacementType == null
    ) {
        return ConeStarProjection
    }
    val combinedVariance = TypeSubstitutor.combine(original.variance, replacement.variance)
    return coneProjectionOfVariance(combinedVariance, replacementType)
}

private fun coneProjectionOfVariance(variance: Variance, type: ConeKotlinType): ConeTypeProjection =
    when (variance) {
        Variance.INVARIANT -> type
        Variance.IN_VARIANCE -> ConeKotlinTypeProjectionIn(type)
        Variance.OUT_VARIANCE -> ConeKotlinTypeProjectionOut(type)
    }

internal fun buildInferenceTypeParameterModels(
    bindableTypeParameters: Set<FirTypeParameterSymbol>,
    containingFunction: FirNamedFunctionSymbol?,
    containingClassTypeParameters: Iterable<FirTypeParameterSymbol> = emptyList(),
    containingFunctions: List<FirNamedFunctionSymbol> = listOfNotNull(containingFunction),
): MutableMap<FirTypeParameterSymbol, TcTypeParameter> {
    val referencedSymbols = linkedSetOf<FirTypeParameterSymbol>()
    referencedSymbols += containingClassTypeParameters
    containingFunctions.forEach { function ->
        function.fir.typeParameters.forEach { typeParameter ->
            referencedSymbols += typeParameter.symbol
        }
    }
    referencedSymbols += bindableTypeParameters
    val result = linkedMapOf<FirTypeParameterSymbol, TcTypeParameter>()
    referencedSymbols.forEachIndexed { index, symbol ->
        result[symbol] =
            TcTypeParameter(
                id = "inference:$index:${symbol.name.asString()}",
                displayName = symbol.name.asString(),
            )
    }
    return result
}

private fun ensureInferenceTypeParameterModels(
    typeParameterModels: MutableMap<FirTypeParameterSymbol, TcTypeParameter>,
    vararg types: ConeKotlinType,
) {
    val referencedSymbols = linkedSetOf<FirTypeParameterSymbol>()
    types.forEach { type -> referencedSymbols.collectBindableTypeParameters(type) }
    referencedSymbols.forEach { symbol ->
        typeParameterModels.getOrPut(symbol) {
            TcTypeParameter(
                id = "inference:${typeParameterModels.size}:${symbol.name.asString()}",
                displayName = symbol.name.asString(),
            )
        }
    }
}

internal fun resolveFirTypeBindingConstraints(
    session: FirSession,
    sharedState: TypeclassPluginSharedState?,
    typeParameterModels: Map<FirTypeParameterSymbol, TcTypeParameter>,
    constraintsBySymbol: Map<FirTypeParameterSymbol, MutableTypeBindingConstraints<ConeKotlinType>>,
): ResolvedTypeBindingConstraints<FirTypeParameterSymbol, ConeKotlinType> {
    val exactContext =
        FirBuiltinGoalExactContext(
            session = session,
            typeParameterModels = typeParameterModels,
            variableSymbolsById =
                typeParameterModels.entries.associate { (symbol, parameter) ->
                    parameter.id to symbol
                },
        )
    return resolveTypeBindingConstraints(
        constraintsByKey = constraintsBySymbol,
        isProvableSubtype = { sub, sup ->
            when {
                sub == sup -> true
                sharedState != null -> sharedState.provablySubtype(sub, sup, exactContext)
                else -> false
            }
        },
    )
}

private fun nestedInferenceVariance(
    parameterKind: ProjectionKind,
    actualKind: ProjectionKind,
    declaredVariance: Variance,
): Variance? {
    val parameterVariance =
        when (parameterKind) {
            ProjectionKind.STAR -> return null
            ProjectionKind.IN -> Variance.IN_VARIANCE
            ProjectionKind.OUT -> Variance.OUT_VARIANCE
            ProjectionKind.INVARIANT -> declaredVariance
        }
    return when (actualKind) {
        ProjectionKind.STAR -> null
        ProjectionKind.INVARIANT -> parameterVariance
        ProjectionKind.IN ->
            if (parameterVariance == Variance.IN_VARIANCE) Variance.IN_VARIANCE else null
        ProjectionKind.OUT ->
            if (parameterVariance == Variance.OUT_VARIANCE) Variance.OUT_VARIANCE else null
    }
}
