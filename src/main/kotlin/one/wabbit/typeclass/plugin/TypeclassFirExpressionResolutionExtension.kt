@file:OptIn(org.jetbrains.kotlin.fir.symbols.SymbolInternals::class)

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.fakeElement
import one.wabbit.typeclass.plugin.model.InstanceRule
import one.wabbit.typeclass.plugin.model.ResolutionPlan
import one.wabbit.typeclass.plugin.model.ResolutionSearchResult
import one.wabbit.typeclass.plugin.model.TcType
import one.wabbit.typeclass.plugin.model.TcTypeParameter
import one.wabbit.typeclass.plugin.model.TypeclassResolutionPlanner
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.SessionAndScopeSessionHolder
import org.jetbrains.kotlin.fir.declarations.origin
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.declarations.builder.buildReceiverParameter
import org.jetbrains.kotlin.fir.extensions.FirExpressionResolutionExtension
import org.jetbrains.kotlin.fir.extensions.captureValueInAnalyze
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.resolve.calls.ImplicitExtensionReceiverValue
import org.jetbrains.kotlin.fir.resolve.providers.firProvider
import org.jetbrains.kotlin.fir.resolve.substitution.ConeSubstitutorByMap
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirReceiverParameterSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.FirTypeProjectionWithVariance
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.lowerBoundIfFlexible
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.toFirResolvedTypeRef
import org.jetbrains.kotlin.name.FqName

internal class TypeclassFirExpressionResolutionExtension(
    session: FirSession,
    private val sharedState: TypeclassPluginSharedState,
) : FirExpressionResolutionExtension(session) {
    private val topLevelRules: List<InstanceRule> by lazy(LazyThreadSafetyMode.NONE) {
        buildTopLevelInstanceRules()
    }

    override fun addNewImplicitReceivers(
        functionCall: FirFunctionCall,
        sessionHolder: SessionAndScopeSessionHolder,
        containingCallableSymbol: FirBasedSymbol<*>,
    ): List<ImplicitExtensionReceiverValue> {
        val resolvedFunction =
            ((functionCall.calleeReference as? FirResolvedNamedReference)?.resolvedSymbol as? FirNamedFunctionSymbol)
                ?: return emptyList()

        val function = resolvedFunction.fir
        if (function.contextParameters.isEmpty()) {
            return emptyList()
        }

        val containingFunction = containingCallableSymbol as? FirNamedFunctionSymbol
        val visibleTypeParameters = buildVisibleTypeParameters(containingFunction)
        val substitutedContextTypes = substituteContextTypes(functionCall, resolvedFunction, containingFunction)
        if (substitutedContextTypes.isEmpty()) {
            return emptyList()
        }

        val localContextTypes = buildLocalContextTypes(containingFunction, visibleTypeParameters.bySymbol)
        val planner = TypeclassResolutionPlanner(topLevelRules)
        val receivers = linkedMapOf<String, ImplicitExtensionReceiverValue>()
        substitutedContextTypes.forEach { goalType ->
            val goalModel = coneTypeToModel(goalType, visibleTypeParameters.bySymbol) ?: return@forEach
            when (val resolution = planner.resolve(goalModel, localContextTypes)) {
                is ResolutionSearchResult.Success -> {
                    if (resolution.plan is ResolutionPlan.LocalContext) {
                        return@forEach
                    }
                    receivers.getOrPut(goalType.toString()) {
                        syntheticImplicitReceiver(
                            functionCall = functionCall,
                            containingCallableSymbol = containingCallableSymbol,
                            goalType = goalType,
                            sessionHolder = sessionHolder,
                        )
                    }
                }

                is ResolutionSearchResult.Missing -> {
                    if (sharedState.canDeriveGoal(session, goalModel)) {
                        receivers.getOrPut(goalType.toString()) {
                            syntheticImplicitReceiver(
                                functionCall = functionCall,
                                containingCallableSymbol = containingCallableSymbol,
                                goalType = goalType,
                                sessionHolder = sessionHolder,
                            )
                        }
                    }
                }

                else -> Unit
            }
        }
        return receivers.values.toList()
    }

    private fun syntheticImplicitReceiver(
        functionCall: FirFunctionCall,
        containingCallableSymbol: FirBasedSymbol<*>,
        goalType: ConeKotlinType,
        sessionHolder: SessionAndScopeSessionHolder,
    ): ImplicitExtensionReceiverValue {
        val receiver =
            buildReceiverParameter {
                source = functionCall.source?.fakeElement(KtFakeSourceElementKind.PluginGenerated)
                moduleData = containingCallableSymbol.fir.moduleData
                origin = TypeclassSyntheticReceiverKey.origin
                symbol = FirReceiverParameterSymbol()
                typeRef = goalType.toFirResolvedTypeRef(source)
                containingDeclarationSymbol = containingCallableSymbol
            }.also { parameter ->
                parameter.captureValueInAnalyze = false
            }
        return ImplicitExtensionReceiverValue(
            receiver.symbol,
            goalType,
            sessionHolder.session,
            sessionHolder.scopeSession,
        )
    }

    private fun substituteContextTypes(
        functionCall: FirFunctionCall,
        resolvedFunction: FirNamedFunctionSymbol,
        containingFunction: FirNamedFunctionSymbol?,
    ): List<ConeKotlinType> {
        val function = resolvedFunction.fir
        val typeParameters = function.typeParameters
        if (typeParameters.isEmpty()) {
            return function.contextParameters.map { it.returnTypeRef.coneType }
        }

        val functionTypeParameterSymbols = typeParameters.mapTo(linkedSetOf(), org.jetbrains.kotlin.fir.declarations.FirTypeParameterRef::symbol)
        val substitution = inferFunctionTypeArguments(functionCall, resolvedFunction, containingFunction)
        if (substitution.isEmpty() && function.contextParameters.any { referencesFunctionTypeParameter(it.returnTypeRef.coneType, functionTypeParameterSymbols) }) {
            return emptyList()
        }
        val substitutor = ConeSubstitutorByMap.Companion.create(substitution, session, false)
        return function.contextParameters.map { parameter ->
            substitutor.substituteOrSelf(parameter.returnTypeRef.coneType)
        }
    }

    private fun inferFunctionTypeArguments(
        functionCall: FirFunctionCall,
        resolvedFunction: FirNamedFunctionSymbol,
        containingFunction: FirNamedFunctionSymbol?,
    ): Map<FirTypeParameterSymbol, ConeKotlinType> {
        val function = resolvedFunction.fir
        val inferred = linkedMapOf<FirTypeParameterSymbol, ConeKotlinType>()
        val functionTypeParameters = function.typeParameters.mapTo(linkedSetOf(), org.jetbrains.kotlin.fir.declarations.FirTypeParameterRef::symbol)

        function.typeParameters.zip(functionCall.typeArguments).forEach { (typeParameter, typeArgument) ->
            val explicitType = (typeArgument as? FirTypeProjectionWithVariance)?.typeRef?.coneType ?: return@forEach
            inferred[typeParameter.symbol] = explicitType
        }

        val parameterByArgument = functionCall.resolvedArgumentMapping
        if (parameterByArgument != null) {
            parameterByArgument.forEach { (argument, parameter) ->
                unifyFunctionTypeArguments(
                    parameterType = parameter.returnTypeRef.coneType,
                    argumentType = argument.safeResolvedOrInferredTypeOrNull(session) ?: return@forEach,
                    functionTypeParameters = functionTypeParameters,
                    inferred = inferred,
                )
            }
        } else {
            function.valueParameters.zip(functionCall.arguments).forEach { (parameter, argument) ->
                unifyFunctionTypeArguments(
                    parameterType = parameter.returnTypeRef.coneType,
                    argumentType = argument.safeResolvedOrInferredTypeOrNull(session) ?: return@forEach,
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
                unifyFunctionTypeArguments(
                    parameterType = parameter.returnTypeRef.coneType,
                    argumentType = localContextType,
                    functionTypeParameters = functionTypeParameters,
                    inferred = inferred,
                )
            }
        }

        return inferred
    }

    private fun unifyFunctionTypeArguments(
        parameterType: ConeKotlinType,
        argumentType: ConeKotlinType,
        functionTypeParameters: Set<FirTypeParameterSymbol>,
        inferred: MutableMap<FirTypeParameterSymbol, ConeKotlinType>,
    ) {
        val normalizedArgumentType = argumentType.approximateIntegerLiteralType()
        when (val loweredParameter = parameterType.lowerBoundIfFlexible()) {
            is ConeTypeParameterType -> {
                val symbol = loweredParameter.lookupTag.typeParameterSymbol
                if (symbol !in functionTypeParameters) {
                    return
                }
                val existing = inferred[symbol]
                if (existing == null) {
                    inferred[symbol] = normalizedArgumentType
                }
                return
            }

            else -> Unit
        }

        val parameterClassLike = loweredParameterAsClassLike(parameterType) ?: return
        val argumentClassLike = loweredArgumentAsClassLike(normalizedArgumentType) ?: return
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

    private fun loweredParameterAsClassLike(type: ConeKotlinType) =
        type.lowerBoundIfFlexible() as? org.jetbrains.kotlin.fir.types.ConeClassLikeType

    private fun loweredArgumentAsClassLike(type: ConeKotlinType) =
        type.lowerBoundIfFlexible() as? org.jetbrains.kotlin.fir.types.ConeClassLikeType

    private fun referencesFunctionTypeParameter(
        type: ConeKotlinType,
        functionTypeParameters: Set<FirTypeParameterSymbol>,
    ): Boolean =
        when (val lowered = type.lowerBoundIfFlexible()) {
            is ConeTypeParameterType -> lowered.lookupTag.typeParameterSymbol in functionTypeParameters
            is org.jetbrains.kotlin.fir.types.ConeClassLikeType ->
                lowered.typeArguments.any { argument ->
                    val nested = argument.type ?: return@any false
                    referencesFunctionTypeParameter(nested, functionTypeParameters)
                }

            else -> false
        }

    private fun buildLocalContextTypes(
        containingFunction: FirNamedFunctionSymbol?,
        visibleTypeParameters: Map<org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol, TcTypeParameter>,
    ): List<TcType> =
        containingFunction?.fir?.contextParameters.orEmpty()
            .mapNotNull { parameter ->
                parameter.returnTypeRef.coneType.takeIf { isTypeclassType(it, session) }?.let {
                    coneTypeToModel(it, visibleTypeParameters)
                }
            }

    private fun buildVisibleTypeParameters(
        containingFunction: FirNamedFunctionSymbol?,
    ): VisibleFirTypeParameters {
        val parameters =
            containingFunction?.fir?.typeParameters.orEmpty().map { typeParameter ->
                typeParameter.symbol to
                    TcTypeParameter(
                        id = "function:${containingFunction?.callableId}:${typeParameter.symbol.name.asString()}",
                        displayName = typeParameter.symbol.name.asString(),
                    )
            }
        return VisibleFirTypeParameters(
            bySymbol = parameters.toMap(),
        )
    }

    private fun buildTopLevelInstanceRules(): List<InstanceRule> {
        val symbolProvider = session.firProvider.symbolProvider
        val packageNames = symbolProvider.symbolNamesProvider.getPackageNames().orEmpty()
        val rules = mutableListOf<InstanceRule>()

        packageNames.forEach { packageNameString ->
            val packageName = FqName(packageNameString)

            symbolProvider.symbolNamesProvider.getTopLevelCallableNamesInPackage(packageName).orEmpty().forEach { callableName ->
                symbolProvider.getTopLevelFunctionSymbols(packageName, callableName).forEach { functionSymbol ->
                    toTopLevelFunctionRule(functionSymbol)?.let(rules::add)
                }
            }

            symbolProvider.symbolNamesProvider.getTopLevelClassifierNamesInPackage(packageName).orEmpty().forEach { classifierName ->
                val classSymbol =
                    symbolProvider.getClassLikeSymbolByClassId(org.jetbrains.kotlin.name.ClassId(packageName, classifierName))
                        as? FirRegularClassSymbol ?: return@forEach
                toTopLevelObjectRule(classSymbol)?.let(rules::add)
            }
        }

        return rules
    }

    private fun toTopLevelFunctionRule(functionSymbol: FirNamedFunctionSymbol): InstanceRule? {
        if (!functionSymbol.hasAnnotation(INSTANCE_ANNOTATION_CLASS_ID, session)) {
            return null
        }

        val function = functionSymbol.fir
        if (function.status.visibility == Visibilities.Private) {
            return null
        }
        if (functionSymbol.callableId.classId != null) {
            return null
        }
        if (function.receiverParameter != null || function.valueParameters.isNotEmpty()) {
            return null
        }
        if (!isTypeclassType(function.returnTypeRef.coneType, session)) {
            return null
        }

        val typeParameters =
            function.typeParameters.map { typeParameter ->
                TcTypeParameter(
                    id = "function:${functionSymbol.callableId}:${typeParameter.symbol.name.asString()}",
                    displayName = typeParameter.symbol.name.asString(),
                )
            }
        val typeParameterBySymbol = function.typeParameters.zip(typeParameters).associate { (typeParameter, parameter) ->
            typeParameter.symbol to parameter
        }

        val providedType = coneTypeToModel(function.returnTypeRef.coneType, typeParameterBySymbol) ?: return null
        val prerequisites =
            function.contextParameters.mapNotNull { parameter ->
                parameter.returnTypeRef.coneType.takeIf { isTypeclassType(it, session) }?.let {
                    coneTypeToModel(it, typeParameterBySymbol)
                }
            }
        if (prerequisites.size != function.contextParameters.size) {
            return null
        }

        return InstanceRule(
            id = "top-level:${functionSymbol.callableId}",
            typeParameters = typeParameters,
            providedType = providedType,
            prerequisiteTypes = prerequisites,
        )
    }

    private fun toTopLevelObjectRule(classSymbol: FirRegularClassSymbol): InstanceRule? {
        if (classSymbol.classId.isNestedClass) {
            return null
        }
        val klass = classSymbol.fir
        if (klass.classKind != ClassKind.OBJECT || !classSymbol.hasAnnotation(INSTANCE_ANNOTATION_CLASS_ID, session)) {
            return null
        }
        if (klass.status.visibility == Visibilities.Private) {
            return null
        }

        val providedType = klass.instanceProvidedType(session) ?: return null

        return InstanceRule(
            id = "top-level-object:${classSymbol.classId.asString()}",
            typeParameters = emptyList(),
            providedType = providedType,
            prerequisiteTypes = emptyList(),
        )
    }
}

private data class VisibleFirTypeParameters(
    val bySymbol: Map<org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol, TcTypeParameter>,
)
