@file:OptIn(
    org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess::class,
    org.jetbrains.kotlin.fir.symbols.SymbolInternals::class,
)

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirTypeParameterRef
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
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
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.lowerBoundIfFlexible
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.fir.types.typeContext
import org.jetbrains.kotlin.fir.types.withNullabilityOf

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
): Map<FirTypeParameterSymbol, ConeKotlinType> {
    val function = resolvedFunction.fir
    val inferred = linkedMapOf<FirTypeParameterSymbol, ConeKotlinType>()
    val functionTypeParameters = function.typeParameters.mapTo(linkedSetOf(), FirTypeParameterRef::symbol)
    function.typeParameters.zip(functionCall.typeArguments).forEach { (typeParameter, typeArgument) ->
        val explicitType = (typeArgument as? FirTypeProjectionWithVariance)?.typeRef?.coneType ?: return@forEach
        inferred[typeParameter.symbol] = explicitType
    }

    function.receiverParameter?.typeRef?.coneType?.let { receiverType ->
        functionCall.explicitReceiver
            ?.safeResolvedOrInferredTypeOrNull(session)
            ?.let { explicitReceiverType ->
                unifyFunctionTypeArgumentsForInference(
                    parameterType = receiverType,
                    argumentType = explicitReceiverType,
                    functionTypeParameters = functionTypeParameters,
                    inferred = inferred,
                )
            }
    }

    val parameterByArgument = functionCall.resolvedArgumentMapping
    if (parameterByArgument != null) {
        parameterByArgument.forEach { (argument, parameter) ->
            val argumentType =
                (argument as? FirFunctionCall)?.inferReturnTypeOrNull(session)
                    ?: argument.safeResolvedOrInferredTypeOrNull(session)
                    ?: return@forEach
            unifyFunctionTypeArgumentsForInference(
                parameterType = parameter.returnTypeRef.coneType,
                argumentType = argumentType,
                functionTypeParameters = functionTypeParameters,
                inferred = inferred,
            )
        }
    } else {
        function.valueParameters.zip(functionCall.arguments).forEach { (parameter, argument) ->
            val nestedCall = argument as? FirFunctionCall
            val argumentType =
                nestedCall?.inferReturnTypeOrNull(session)
                    ?: argument.safeResolvedOrInferredTypeOrNull(session)
                    ?: return@forEach
            unifyFunctionTypeArgumentsForInference(
                parameterType = parameter.returnTypeRef.coneType,
                argumentType = argumentType,
                functionTypeParameters = functionTypeParameters,
                inferred = inferred,
            )
        }
    }

    val localContextTypes =
        containingFunction?.fir?.contextParameters.orEmpty()
            .mapNotNull { parameter ->
                parameter.returnTypeRef.coneType.takeIf { isTypeclassType(it, session) }
            }

    function.contextParameters.forEach { parameter ->
        localContextTypes.forEach { localContextType ->
            unifyFunctionTypeArgumentsForInference(
                parameterType = parameter.returnTypeRef.coneType,
                argumentType = localContextType,
                functionTypeParameters = functionTypeParameters,
                inferred = inferred,
            )
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

    val inferred = linkedMapOf<FirTypeParameterSymbol, ConeKotlinType>()

    function.typeParameters.zip(typeArguments).forEach { (typeParameter, typeArgument) ->
        val explicitType = (typeArgument as? FirTypeProjectionWithVariance)?.typeRef?.coneType ?: return@forEach
            inferred[typeParameter.symbol] = explicitType
        }

    function.valueParameters.zip(arguments).forEach { (parameter, argument) ->
        val argumentType = argument.safeResolvedOrInferredTypeOrNull(session) ?: return@forEach
        unifyFunctionTypeArgumentsForInference(
            parameterType = parameter.returnTypeRef.coneType,
            argumentType = argumentType,
            functionTypeParameters = functionTypeParameters,
            inferred = inferred,
        )
    }

    val result = substituteInferredTypes(
        type = function.returnTypeRef.coneType,
        substitutions = inferred,
        session = session,
    )
    return result
}

private fun FirFunctionCall.inferConstructedClassTypeOrNull(session: FirSession): ConeKotlinType? {
    val resolved = safeResolvedTypeOrNull() ?: return null
    val classLike = resolved.lowerBoundIfFlexible() as? ConeClassLikeType ?: return null
    if (!resolved.containsTypeParameterReference()) {
        return resolved
    }
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
    primaryConstructor.valueParameters.zip(arguments).forEach { (parameter, argument) ->
        val argumentType = argument.safeResolvedOrInferredTypeOrNull(session) ?: return@forEach
        unifyFunctionTypeArgumentsForInference(
            parameterType = parameter.returnTypeRef.coneType,
            argumentType = argumentType,
            functionTypeParameters = classTypeParameters,
            inferred = inferred,
        )
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
        is ConeClassLikeType -> lowered.typeArguments.any { argument -> argument.type?.containsTypeParameterReference() == true }
        else -> false
    }

private fun unifyFunctionTypeArgumentsForInference(
    parameterType: ConeKotlinType,
    argumentType: ConeKotlinType,
    functionTypeParameters: Set<FirTypeParameterSymbol>,
    inferred: MutableMap<FirTypeParameterSymbol, ConeKotlinType>,
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
                (loweredParameter.typeConstructor.originalTypeParameter as? ConeTypeParameterLookupTag)
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
        unifyFunctionTypeArgumentsForInference(
            parameterType = nestedParameterType,
            argumentType = nestedActualType,
            functionTypeParameters = functionTypeParameters,
            inferred = inferred,
        )
    }
}

private fun FirExpression.safeResolvedTypeOrNull(): ConeKotlinType? =
    runCatching { resolvedType }.getOrNull()

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
