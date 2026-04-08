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
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeStubType
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.ConeTypeVariableType
import org.jetbrains.kotlin.fir.types.FirTypeProjectionWithVariance
import org.jetbrains.kotlin.fir.types.ProjectionKind
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.lowerBoundIfFlexible
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.fir.types.typeContext
import org.jetbrains.kotlin.fir.types.withNullabilityOf
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.types.Variance

internal fun FirExpression.safeResolvedOrInferredTypeOrNull(session: FirSession): ConeKotlinType? {
    val resolved = safeResolvedTypeOrNull()
    val functionCall = this as? FirFunctionCall ?: return resolved
    val inferred = functionCall.inferReturnTypeOrNull(session)
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
    sharedState: TypeclassPluginSharedState,
): Map<FirTypeParameterSymbol, ConeKotlinType> {
    val function = resolvedFunction.fir
    val inferred = linkedMapOf<FirTypeParameterSymbol, ConeKotlinType>()
    val functionTypeParameters = function.typeParameters.mapTo(linkedSetOf(), FirTypeParameterRef::symbol)
    val inferenceTypeParameterModels =
        buildInferenceTypeParameterModels(
            bindableTypeParameters = functionTypeParameters,
            containingFunction = containingFunction,
        )
    function.typeParameters.zip(functionCall.typeArguments).forEach { (typeParameter, typeArgument) ->
        val explicitType = (typeArgument as? FirTypeProjectionWithVariance)?.typeRef?.coneType ?: return@forEach
        inferred[typeParameter.symbol] = explicitType
    }

    val directConstraints = linkedMapOf<FirTypeParameterSymbol, MutableTypeBindingConstraints<ConeKotlinType>>()
    function.receiverParameter?.typeRef?.coneType?.let { receiverType ->
        functionCall.explicitReceiver
            ?.safeResolvedOrInferredTypeOrNull(session)
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

    val parameterByArgument = functionCall.parameterByArgumentMappingOrNull(function.valueParameters)
    if (parameterByArgument != null) {
        parameterByArgument.forEach { (argument, parameter) ->
            val argumentType =
                (argument as? FirFunctionCall)?.inferReturnTypeOrNull(session)
                    ?: argument.safeResolvedOrInferredTypeOrNull(session)
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
                nestedCall?.inferReturnTypeOrNull(session)
                    ?: argument.safeResolvedOrInferredTypeOrNull(session)
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
    ).resolvedByKey.forEach { (symbol, type) ->
        if (symbol !in inferred) {
            inferred[symbol] = type
        }
    }

    val localContextTypes =
        buildList {
            containingFunction?.fir?.receiverParameter?.typeRef?.coneType
                ?.localEvidenceTypes(session, sharedState.configuration)
                ?.let(::addAll)
            containingFunction?.fir?.contextParameters.orEmpty()
                .forEach { parameter ->
                    addAll(parameter.returnTypeRef.coneType.localEvidenceTypes(session, sharedState.configuration))
                }
        }

    val localContextConstraints = linkedMapOf<FirTypeParameterSymbol, MutableTypeBindingConstraints<ConeKotlinType>>()
    function.contextParameters.forEach { parameter ->
        localContextTypes.forEach { localContextType ->
            collectFunctionTypeArgumentConstraintsForInference(
                session = session,
                sharedState = sharedState,
                typeParameterModels = inferenceTypeParameterModels,
                parameterType = parameter.returnTypeRef.coneType,
                argumentType = localContextType,
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

internal fun FirFunctionCall.inferReturnTypeOrNull(session: FirSession): ConeKotlinType? {
    val symbol = (calleeReference as? FirResolvedNamedReference)?.resolvedSymbol as? FirFunctionSymbol<*>
        ?: return inferConstructedClassTypeOrNull(session)
    val function = symbol.fir
    val functionTypeParameters =
        buildSet {
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
                val explicitType = (typeArgument as? FirTypeProjectionWithVariance)?.typeRef?.coneType ?: return@forEach
                put(typeParameter.symbol, explicitType)
            }
        }
    val receiverConstraint =
        function.receiverParameter?.typeRef?.coneType
            ?.let { receiverType ->
                explicitReceiver
                    ?.safeResolvedOrInferredTypeOrNull(session)
                    ?.let { explicitReceiverType -> receiverType to explicitReceiverType }
            }
    val argumentConstraints =
        buildList {
            val parameterByArgument = parameterByArgumentMappingOrNull(function.valueParameters)
            if (parameterByArgument != null) {
                parameterByArgument.forEach { (argument, parameter) ->
                    val argumentType = argument.safeResolvedOrInferredTypeOrNull(session) ?: return@forEach
                    add(parameter.inferenceParameterType() to argumentType)
                }
            } else {
                function.valueParameters.zip(arguments).forEach { (parameter, argument) ->
                    val argumentType = argument.safeResolvedOrInferredTypeOrNull(session) ?: return@forEach
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
): ConeKotlinType {
    val inferred =
        inferTypeArgumentsFromCallSiteTypes(
            session = session,
            functionTypeParameters = functionTypeParameters,
            explicitTypeArguments = explicitTypeArguments,
            receiverConstraint = receiverConstraint,
            argumentConstraints = argumentConstraints,
        )
    return substituteInferredTypes(
        type = returnType,
        substitutions = inferred,
        session = session,
    )
}

internal fun inferTypeArgumentsFromCallSiteTypes(
    session: FirSession,
    functionTypeParameters: Set<FirTypeParameterSymbol>,
    explicitTypeArguments: Map<FirTypeParameterSymbol, ConeKotlinType>,
    receiverConstraint: Pair<ConeKotlinType, ConeKotlinType>?,
    argumentConstraints: Iterable<Pair<ConeKotlinType, ConeKotlinType>>,
): Map<FirTypeParameterSymbol, ConeKotlinType> {
    if (functionTypeParameters.isEmpty()) {
        return emptyMap()
    }

    val inferred = linkedMapOf<FirTypeParameterSymbol, ConeKotlinType>()
    inferred.putAll(explicitTypeArguments)
    val inferenceTypeParameterModels =
        buildInferenceTypeParameterModels(
            bindableTypeParameters = functionTypeParameters,
            containingFunction = null,
        )
    val directConstraints = linkedMapOf<FirTypeParameterSymbol, MutableTypeBindingConstraints<ConeKotlinType>>()

    receiverConstraint?.let { (parameterType, argumentType) ->
        collectFunctionTypeArgumentConstraintsForInference(
            session = session,
            sharedState = null,
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
            sharedState = null,
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
        sharedState = null,
        typeParameterModels = inferenceTypeParameterModels,
        constraintsBySymbol = directConstraints,
    ).resolvedByKey.forEach { (symbol, type) ->
        if (symbol !in inferred) {
            inferred[symbol] = type
        }
    }
    return inferred
}

private fun FirFunctionCall.inferConstructedClassTypeOrNull(session: FirSession): ConeKotlinType? {
    val resolved = safeResolvedTypeOrNull() ?: return null
    val classLike = resolved.lowerBoundIfFlexible() as? ConeClassLikeType ?: return null
    val classSymbol =
        session.symbolProvider.getClassLikeSymbolByClassId(classLike.lookupTag.classId)
            as? FirRegularClassSymbol ?: return resolved
    val primaryConstructor =
        classSymbol.fir.declarations
            .filterIsInstance<FirConstructor>()
            .singleOrNull { constructor -> constructor.isPrimary }
            ?: return resolved
    val classTypeParameters = classSymbol.fir.typeParameters.mapTo(linkedSetOf(), FirTypeParameterRef::symbol)
    if (classTypeParameters.isEmpty()) {
        return resolved
    }
    val inferred = linkedMapOf<FirTypeParameterSymbol, ConeKotlinType>()
    val inferenceTypeParameterModels =
        buildInferenceTypeParameterModels(
            bindableTypeParameters = classTypeParameters,
            containingFunction = null,
        )
    val directConstraints = linkedMapOf<FirTypeParameterSymbol, MutableTypeBindingConstraints<ConeKotlinType>>()
    val parameterByArgument = parameterByArgumentMappingOrNull(primaryConstructor.valueParameters)
    if (parameterByArgument != null) {
        parameterByArgument.forEach { (argument, parameter) ->
            val argumentType = argument.safeResolvedOrInferredTypeOrNull(session) ?: return@forEach
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
            val argumentType = argument.safeResolvedOrInferredTypeOrNull(session) ?: return@forEach
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
    ).resolvedByKey.forEach { (symbol, type) ->
        if (symbol !in inferred) {
            inferred[symbol] = type
        }
    }
    return substituteInferredTypes(
        type = resolved,
        substitutions = inferred,
        session = session,
    )
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
    constraintsBySymbol: MutableMap<FirTypeParameterSymbol, MutableTypeBindingConstraints<ConeKotlinType>>,
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
                (loweredParameter.typeConstructor.originalTypeParameter as? ConeTypeParameterLookupTag)
                    ?.typeParameterSymbol
                    ?: return
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
        (session.symbolProvider.getClassLikeSymbolByClassId(parameterClassLike.lookupTag.classId) as? FirRegularClassSymbol)
            ?.fir
            ?.typeParameters
            ?.map { typeParameter -> typeParameter.symbol.fir.variance }
            .orEmpty()

    parameterClassLike.typeArguments.zip(argumentClassLike.typeArguments).forEachIndexed { index, (parameterArgument, actualArgument) ->
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
    visited: MutableSet<ClassId> = linkedSetOf(),
): ConeKotlinType? {
    val actualSimpleType = lowerBoundIfFlexible() as? ConeClassLikeType ?: return null
    val actualClassId = actualSimpleType.lookupTag.classId
    if (actualClassId == expectedClassId) {
        return actualSimpleType
    }
    if (!visited.add(actualClassId)) {
        return null
    }
    val classSymbol =
        runCatching {
            session.symbolProvider.getClassLikeSymbolByClassId(actualClassId) as? FirRegularClassSymbol
        }.getOrNull() ?: return null
    val substitutions =
        classSymbol.fir.typeParameters.zip(actualSimpleType.typeArguments).mapNotNull { (parameter, argument) ->
            argument.type?.let { type -> parameter.symbol to type }
        }.toMap()
    classSymbol.fir.declaredOrResolvedSuperTypes().forEach { superType ->
        val substitutedSuperType =
            if (substitutions.isEmpty()) {
                superType
            } else {
                substituteInferredTypes(superType, substitutions, session)
            }.withNullabilityOf(actualSimpleType, session.typeContext)
        val projected =
            substitutedSuperType.projectToMatchingInferenceSupertype(
                expectedClassId = expectedClassId,
                session = session,
                visited = visited,
            )
        if (projected != null) {
            return projected
        }
    }
    return null
}

private fun FirFunctionCall.parameterByArgumentMappingOrNull(
    parameters: List<FirValueParameter>,
): Map<FirExpression, FirValueParameter>? =
    resolvedArgumentMapping ?: buildNamedAndPositionalArgumentMapping(parameters).takeIf { it.isNotEmpty() }

private fun FirFunctionCall.buildNamedAndPositionalArgumentMapping(
    parameters: List<FirValueParameter>,
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

    fun nextPositionalParameter(): P? {
        while (nextPositionalIndex < parameters.size) {
            val candidate = parameters[nextPositionalIndex]
            if (isVararg(candidate)) {
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

    arguments.forEach { (name, argument) ->
        val parameter = name?.let(parametersByName::get) ?: nextPositionalParameter() ?: return@forEach
        if (!isVararg(parameter) && !assignedNonVarargParameters.add(parameter)) {
            return@forEach
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
                    (type.constructor.variable.typeConstructor.originalTypeParameter as? ConeTypeParameterLookupTag)
                        ?.typeParameterSymbol
                        ?.let(substitutions::get)
                else -> null
            } ?: return null
        return replacement
            .withNullabilityOf(type, typeContext)
            .withCombinedAttributesFrom(type)
    }
}

internal fun buildInferenceTypeParameterModels(
    bindableTypeParameters: Set<FirTypeParameterSymbol>,
    containingFunction: FirNamedFunctionSymbol?,
): MutableMap<FirTypeParameterSymbol, TcTypeParameter> {
    val referencedSymbols = linkedSetOf<FirTypeParameterSymbol>()
    containingFunction?.fir?.typeParameters?.forEach { typeParameter -> referencedSymbols += typeParameter.symbol }
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
            variableSymbolsById = typeParameterModels.entries.associate { (symbol, parameter) -> parameter.id to symbol },
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
        ProjectionKind.IN -> if (parameterVariance == Variance.IN_VARIANCE) Variance.IN_VARIANCE else null
        ProjectionKind.OUT -> if (parameterVariance == Variance.OUT_VARIANCE) Variance.OUT_VARIANCE else null
    }
}
