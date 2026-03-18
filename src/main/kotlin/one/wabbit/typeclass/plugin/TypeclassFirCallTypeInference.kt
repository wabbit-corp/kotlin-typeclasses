@file:OptIn(org.jetbrains.kotlin.fir.symbols.SymbolInternals::class)

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirTypeParameterRef
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.resolve.substitution.ConeSubstitutorByMap
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.FirTypeProjectionWithVariance
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.lowerBoundIfFlexible
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.type

internal fun FirExpression.safeResolvedOrInferredTypeOrNull(session: FirSession): ConeKotlinType? =
    safeResolvedTypeOrNull() ?: (this as? FirFunctionCall)?.inferReturnTypeOrNull(session)

private fun FirFunctionCall.inferReturnTypeOrNull(session: FirSession): ConeKotlinType? {
    val symbol = (calleeReference as? FirResolvedNamedReference)?.resolvedSymbol as? FirFunctionSymbol<*> ?: return null
    val function = symbol.fir
    if (function.typeParameters.isEmpty()) {
        return function.returnTypeRef.coneType
    }

    val inferred = linkedMapOf<FirTypeParameterSymbol, ConeKotlinType>()
    val functionTypeParameters = function.typeParameters.mapTo(linkedSetOf(), FirTypeParameterRef::symbol)

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

    val substitutor =
        inferred.takeIf { substitutions -> substitutions.isNotEmpty() }?.let { substitutions ->
            ConeSubstitutorByMap.create(substitutions, session, false)
        }
    return substitutor?.substituteOrSelf(function.returnTypeRef.coneType) ?: function.returnTypeRef.coneType
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
