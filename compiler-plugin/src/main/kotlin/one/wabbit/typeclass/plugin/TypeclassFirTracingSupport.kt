@file:OptIn(
    org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess::class,
    org.jetbrains.kotlin.fir.symbols.SymbolInternals::class,
)

package one.wabbit.typeclass.plugin

import one.wabbit.typeclass.plugin.model.TcType
import one.wabbit.typeclass.plugin.model.TcTypeParameter
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirAnnotationContainer
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.extractEnumValueArgumentInfo
import org.jetbrains.kotlin.fir.declarations.findArgumentByName
import org.jetbrains.kotlin.fir.declarations.getAnnotationByClassId
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.resolve.calls.candidate.CallInfo
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames

internal data class FirTypeclassResolutionContext(
    val typeParameterModels: Map<FirTypeParameterSymbol, TcTypeParameter>,
    val bindableVariableIds: Set<String>,
    val directlyAvailableContextTypes: List<ConeKotlinType>,
    val directlyAvailableContextModels: List<TcType>,
    val directlyAvailableContextLabels: List<String>,
    val runtimeMaterializableVariableIds: Set<String>,
)

internal fun buildFirTypeclassResolutionContext(
    session: FirSession,
    sharedState: TypeclassPluginSharedState,
    containingFunctions: List<FirFunction>,
    calleeTypeParameters: List<FirTypeParameterSymbol>,
): FirTypeclassResolutionContext {
    val typeParameterModels = linkedMapOf<FirTypeParameterSymbol, TcTypeParameter>()
    containingFunctions.forEachIndexed { declarationIndex, declaration ->
        declaration.typeParameters.forEachIndexed { typeParameterIndex, typeParameter ->
            typeParameterModels.getOrPut(typeParameter.symbol) {
                TcTypeParameter(
                    id = "containing:$declarationIndex:$typeParameterIndex:${typeParameter.symbol.name.asString()}",
                    displayName = typeParameter.symbol.name.asString(),
                )
            }
        }
    }
    val bindableVariableIds =
        calleeTypeParameters.mapIndexedTo(linkedSetOf()) { typeParameterIndex, symbol ->
            typeParameterModels.getOrPut(symbol) {
                TcTypeParameter(
                    id = "callee:$typeParameterIndex:${symbol.name.asString()}",
                    displayName = symbol.name.asString(),
                )
            }.id
        }

    val directlyAvailableContexts =
        buildList<Pair<ConeKotlinType, String>> {
            containingFunctions.forEach { declaration ->
                declaration.receiverParameter?.typeRef?.coneType
                    ?.takeIf { type -> sharedState.isTypeclassType(session, type) }
                    ?.let { type -> add(type to "receiver") }
                declaration.contextParameters.forEach { parameter ->
                    parameter.returnTypeRef.coneType
                        .takeIf { type -> sharedState.isTypeclassType(session, type) }
                        ?.let { type -> add(type to parameter.name.asString()) }
                }
            }
        }

    val directlyAvailableContextTypes = directlyAvailableContexts.map(Pair<ConeKotlinType, String>::first)
    val directlyAvailableContextModels =
        directlyAvailableContextTypes
            .expandProvidedTypes(session = session, typeParameterBySymbol = typeParameterModels)
            .validTypes
    val runtimeMaterializableVariableIds =
        typeParameterModels.mapNotNullTo(linkedSetOf()) { (symbol, parameter) ->
            parameter.id.takeIf { symbol.fir.isReified }
        }
    return FirTypeclassResolutionContext(
        typeParameterModels = typeParameterModels,
        bindableVariableIds = bindableVariableIds,
        directlyAvailableContextTypes = directlyAvailableContextTypes,
        directlyAvailableContextModels = directlyAvailableContextModels,
        directlyAvailableContextLabels = directlyAvailableContexts.map(Pair<ConeKotlinType, String>::second),
        runtimeMaterializableVariableIds = runtimeMaterializableVariableIds,
    )
}

internal fun CheckerContext.resolveTypeclassTraceActivation(
    globalMode: TypeclassTraceMode,
): TypeclassTraceActivation? =
    resolveTraceActivation(typeclassTraceScopes(), globalMode)

internal fun CheckerContext.resolveTypeclassTraceActivation(
    currentContainer: FirAnnotationContainer?,
    globalMode: TypeclassTraceMode,
): TypeclassTraceActivation? =
    resolveTraceActivation(typeclassTraceScopes(currentContainer), globalMode)

internal fun CallInfo.resolveTypeclassTraceActivation(
    globalMode: TypeclassTraceMode,
): TypeclassTraceActivation? =
    resolveTraceActivation(typeclassTraceScopes(), globalMode)

internal fun CheckerContext.typeclassTraceScopes(): List<TypeclassTraceScope> {
    return typeclassTraceScopes(currentContainer = null)
}

internal fun CheckerContext.typeclassTraceScopes(
    currentContainer: FirAnnotationContainer?,
): List<TypeclassTraceScope> {
    val orderedContainers = linkedSetOf<FirAnnotationContainer>()
    containingFileSymbol?.fir?.let(orderedContainers::add)
    containingDeclarations.forEach { symbol ->
        (symbol.annotationContainerOrNull())?.let(orderedContainers::add)
    }
    currentContainer?.let(orderedContainers::add)
    annotationContainers.forEach(orderedContainers::add)
    return orderedContainers.mapNotNull { container -> container.debugTypeclassTraceScope(session) }
}

internal fun CallInfo.typeclassTraceScopes(): List<TypeclassTraceScope> {
    val orderedContainers = linkedSetOf<FirAnnotationContainer>()
    orderedContainers += containingFile
    containingDeclarations.forEach { declaration ->
        (declaration as? FirAnnotationContainer)?.let(orderedContainers::add)
    }
    return orderedContainers.mapNotNull { container -> container.debugTypeclassTraceScope(session) }
}

private fun FirBasedSymbol<*>.annotationContainerOrNull(): FirAnnotationContainer? =
    when (this) {
        is FirRegularClassSymbol -> fir
        is FirNamedFunctionSymbol -> fir
        else -> fir as? FirAnnotationContainer
    }

private fun FirAnnotationContainer.debugTypeclassTraceScope(session: FirSession): TypeclassTraceScope? {
    val annotation = getAnnotationByClassId(DEBUG_TYPECLASS_RESOLUTION_ANNOTATION_CLASS_ID, session) ?: return null
    val mode = annotation.debugTypeclassTraceMode()
    return when (this) {
        is FirFile ->
            TypeclassTraceScope(
                mode = mode,
                kind = "file",
                label = sourceFile?.path?.substringAfterLast('/') ?: "file",
            )

        is org.jetbrains.kotlin.fir.declarations.FirRegularClass ->
            TypeclassTraceScope(
                mode = mode,
                kind =
                    if (classKind == ClassKind.OBJECT || symbol.classId.shortClassName == SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT) {
                        "object"
                    } else {
                        "class"
                    },
                label = symbol.classId.asString(),
            )

        is FirProperty ->
            TypeclassTraceScope(
                mode = mode,
                kind = if (typeclassIsLocal()) "local variable" else "property",
                label = name.asString(),
            )

        is FirFunction ->
            TypeclassTraceScope(
                mode = mode,
                kind = "function",
                label = if (this is FirTypeclassFunctionDeclaration) name.asString() else "anonymous",
            )

        else -> null
    }
}

private fun FirAnnotation.debugTypeclassTraceMode(): TypeclassTraceMode {
    val modeName =
        findArgumentByName(Name.identifier("mode"))
            ?.extractEnumValueArgumentInfo()
            ?.enumEntryName
            ?.asString()
            ?: return TypeclassTraceMode.FAILURES
    return TypeclassTraceMode.entries.firstOrNull { mode -> mode.name == modeName }
        ?: TypeclassTraceMode.FAILURES
}
