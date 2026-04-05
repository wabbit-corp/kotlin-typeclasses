@file:OptIn(
    org.jetbrains.kotlin.fir.extensions.FirExtensionApiInternals::class,
    org.jetbrains.kotlin.fir.symbols.SymbolInternals::class,
)

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.lowerBoundIfFlexible
import org.jetbrains.kotlin.fir.types.type

internal class TypeclassFirExtensionRegistrar(
    private val sharedState: TypeclassPluginSharedState,
) : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +{ session: FirSession -> TypeclassFirCheckersExtension(session, sharedState) }
        +{ session: FirSession -> TypeclassFirExpressionResolutionExtension(session, sharedState) }
        +{ session: FirSession -> TypeclassFirFunctionCallRefinementExtension(session, sharedState) }
    }
}

internal fun FirNamedFunctionSymbol.wrapperVisibleTypeParameterNames(
    session: FirSession,
    sharedState: TypeclassPluginSharedState,
): List<String> {
    val function = fir
    val visibleSignatureTypes =
        buildList {
            function.receiverParameter?.typeRef?.coneType?.let(::add)
            add(function.returnTypeRef.coneType)
            function.valueParameters.mapTo(this) { parameter -> parameter.returnTypeRef.coneType }
            function.contextParameters
                .filterNot { parameter -> sharedState.isTypeclassType(session, parameter.returnTypeRef.coneType) }
                .mapTo(this) { parameter -> parameter.returnTypeRef.coneType }
        }

    return function.typeParameters.mapNotNull { typeParameter ->
        typeParameter.symbol.name.asString().takeIf { _ ->
            visibleSignatureTypes.any { type -> type.referencesTypeParameter(typeParameter.symbol) }
        }
    }
}

private fun org.jetbrains.kotlin.fir.types.ConeKotlinType.referencesTypeParameter(
    symbol: org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol,
): Boolean =
    when (val lowered = lowerBoundIfFlexible()) {
        is org.jetbrains.kotlin.fir.types.ConeTypeParameterType -> lowered.lookupTag.typeParameterSymbol == symbol
        is org.jetbrains.kotlin.fir.types.ConeClassLikeType ->
            lowered.typeArguments.any { argument ->
                argument.type?.referencesTypeParameter(symbol) == true
            }

        else -> false
    }
