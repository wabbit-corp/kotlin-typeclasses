// SPDX-License-Identifier: AGPL-3.0-or-later

@file:OptIn(
    org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess::class,
    org.jetbrains.kotlin.fir.symbols.SymbolInternals::class,
    org.jetbrains.kotlin.fir.expressions.UnresolvedExpressionTypeAccess::class,
)

package one.wabbit.typeclass.plugin

import one.wabbit.typeclass.plugin.model.TcType
import one.wabbit.typeclass.plugin.model.TcTypeParameter
import one.wabbit.typeclass.plugin.model.ResolutionPlan
import one.wabbit.typeclass.plugin.model.ResolutionSearchResult
import one.wabbit.typeclass.plugin.model.TypeclassResolutionPlanner
import one.wabbit.typeclass.plugin.model.normalizedKey
import one.wabbit.typeclass.plugin.model.render
import one.wabbit.typeclass.plugin.model.references
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirPropertyChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirRegularClassChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirAnonymousObjectExpression
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirTypeOperatorCall
import org.jetbrains.kotlin.fir.references.FirErrorNamedReference
import org.jetbrains.kotlin.fir.references.FirResolvedErrorReference
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.resolve.providers.firProvider
import org.jetbrains.kotlin.fir.resolve.calls.AmbiguousContextArgument
import org.jetbrains.kotlin.fir.resolve.calls.NoContextArgument
import org.jetbrains.kotlin.fir.resolve.diagnostics.ConeInapplicableCandidateError
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeDefinitelyNotNullType
import org.jetbrains.kotlin.fir.types.ConeIntersectionType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.ConeStarProjection
import org.jetbrains.kotlin.fir.types.lowerBoundIfFlexible
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.fir.visitors.FirDefaultVisitorVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId

internal class TypeclassFirCheckersExtension(
    session: FirSession,
    private val sharedState: TypeclassPluginSharedState,
) : FirAdditionalCheckersExtension(session) {
    private val regularClassChecker =
        object : FirRegularClassChecker(MppCheckerKind.Common) {
            context(context: CheckerContext, reporter: DiagnosticReporter)
            override fun check(declaration: FirRegularClass) {
                if (declaration.hasAnnotation(TYPECLASS_ANNOTATION_CLASS_ID, session)) {
                    validateTypeclassDeriverCompanionContracts(declaration)
                }
                if (declaration.hasAnnotation(DERIVE_ANNOTATION_CLASS_ID, session)) {
                    validateDeriveDeclaration(declaration)
                }
                if (
                    declaration.hasAnnotation(DERIVE_VIA_ANNOTATION_CLASS_ID, session) ||
                    declaration.hasAnnotation(DERIVE_VIA_ANNOTATION_CONTAINER_CLASS_ID, session)
                ) {
                    validateDeriveViaDeclarations(declaration)
                }
                if (
                    declaration.hasAnnotation(DERIVE_EQUIV_ANNOTATION_CLASS_ID, session) ||
                    declaration.hasAnnotation(DERIVE_EQUIV_ANNOTATION_CONTAINER_CLASS_ID, session)
                ) {
                    validateDeriveEquivDeclarations(declaration)
                }
                if (declaration.directlyExtendsEquiv()) {
                    reportInvalidEquiv(
                        declaration,
                        invalidEquivSubclassing(),
                    )
                    return
                }
                if (!declaration.hasAnnotation(INSTANCE_ANNOTATION_CLASS_ID, session)) {
                    return
                }
                if (declaration.classKind != ClassKind.OBJECT) {
                    reportInvalid(declaration, invalidInstanceClassBased())
                    return
                }

                validateAssociatedScope(
                    ownerContext = firInstanceOwnerContext(session, declaration.symbol.classId),
                    providedTypes = declaration.instanceProvidedTypes(session, sharedState.configuration),
                    declaration = declaration,
                )
            }
        }

    private val simpleFunctionChecker =
        object : FirDeclarationChecker<FirTypeclassFunctionDeclaration>(MppCheckerKind.Common) {
            context(context: CheckerContext, reporter: DiagnosticReporter)
            override fun check(declaration: FirTypeclassFunctionDeclaration) {
                maybeReportFunctionBodyResolutionTrace(declaration)
                if (!declaration.hasAnnotation(INSTANCE_ANNOTATION_CLASS_ID, session)) {
                    return
                }
                when {
                    declaration.receiverParameter != null -> {
                        reportInvalid(declaration, invalidInstanceExtensionFunction())
                        return
                    }

                    declaration.valueParameters.isNotEmpty() -> {
                        reportInvalid(declaration, invalidInstanceRegularParameter())
                        return
                    }

                    declaration.status.isSuspend -> {
                        reportInvalid(declaration, invalidInstanceSuspendFunction())
                        return
                    }
                }

                val providedTypes = declaration.instanceProvidedTypes(session, sharedState.configuration)
                if (providedTypes.validTypes.any(::isEquivType)) {
                    reportInvalidEquiv(
                        declaration,
                        invalidEquivPublishedInstance(),
                    )
                    return
                }

                validateAssociatedScope(
                    ownerContext = firInstanceOwnerContext(session, declaration.symbol.callableId),
                    providedTypes = providedTypes,
                    declaration = declaration,
                )
                validateFunctionRuleSemantics(declaration)
            }
        }

    private val propertyChecker =
        object : FirPropertyChecker(MppCheckerKind.Common) {
            context(context: CheckerContext, reporter: DiagnosticReporter)
            override fun check(declaration: FirProperty) {
                if (!declaration.hasAnnotation(INSTANCE_ANNOTATION_CLASS_ID, session)) {
                    return
                }
                when {
                    declaration.receiverParameter != null -> {
                        reportInvalid(declaration, invalidInstanceExtensionProperty())
                        return
                    }

                    declaration.status.isLateInit -> {
                        reportInvalid(declaration, invalidInstanceLateinitProperty())
                        return
                    }

                    declaration.isVar -> {
                        reportInvalid(declaration, invalidInstanceMutableProperty())
                        return
                    }

                    declaration.getter?.body != null -> {
                        reportInvalid(declaration, invalidInstanceCustomGetterProperty())
                        return
                    }
                }

                val providedTypes = declaration.instanceProvidedTypes(session, sharedState.configuration)
                if (providedTypes.validTypes.any(::isEquivType)) {
                    reportInvalidEquiv(
                        declaration,
                        invalidEquivPublishedInstance(),
                    )
                    return
                }

                validateAssociatedScope(
                    ownerContext = firInstanceOwnerContext(session, declaration.symbol.callableId),
                    providedTypes = providedTypes,
                    declaration = declaration,
                )
            }
        }

    override val declarationCheckers: DeclarationCheckers =
        object : DeclarationCheckers() {
            override val regularClassCheckers: Set<FirRegularClassChecker> = setOf(regularClassChecker)
            override val simpleFunctionCheckers: Set<FirDeclarationChecker<FirTypeclassFunctionDeclaration>> = setOf(simpleFunctionChecker)
            override val propertyCheckers: Set<FirPropertyChecker> = setOf(propertyChecker)
        }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun maybeReportFunctionBodyResolutionTrace(
        declaration: FirTypeclassFunctionDeclaration,
    ) {
        val activation =
            context.resolveTypeclassTraceActivation(
                currentContainer = declaration,
                globalMode = sharedState.configuration.traceMode,
            ) ?: return
        val containingFunctions =
            buildList {
                addAll(context.containingDeclarations.mapNotNull { symbol -> symbol.firFunctionOrNull() })
                add(declaration)
            }
        val typeContext =
            buildFirTypeclassResolutionContext(
                session = session,
                sharedState = sharedState,
                containingFunctions = containingFunctions,
                calleeTypeParameters = emptyList(),
            )
        declaration.body?.acceptChildren(
            object : FirDefaultVisitorVoid() {
                override fun visitElement(element: FirElement) {
                    element.acceptChildren(this)
                }

                override fun visitFunction(function: FirFunction) {
                    return
                }

                override fun visitAnonymousFunctionExpression(anonymousFunctionExpression: FirAnonymousFunctionExpression) {
                    return
                }

                override fun visitRegularClass(regularClass: FirRegularClass) {
                    return
                }

                override fun visitFunctionCall(functionCall: FirFunctionCall) {
                    maybeReportResolutionTraceForFunctionCall(
                        functionCall = functionCall,
                        declaration = declaration,
                        activation = activation,
                        typeContext = typeContext,
                    )
                    functionCall.acceptChildren(this)
                }
            },
        )
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun maybeReportResolutionTraceForFunctionCall(
        functionCall: FirFunctionCall,
        declaration: FirTypeclassFunctionDeclaration,
        activation: TypeclassTraceActivation,
        typeContext: FirTypeclassResolutionContext,
    ) {
        val reference = functionCall.calleeReference
        val referenceDiagnostic =
            when (reference) {
                is FirResolvedErrorReference -> reference.diagnostic
                is FirErrorNamedReference -> reference.diagnostic
                else -> return
            }
        val inapplicableCandidate = referenceDiagnostic as? ConeInapplicableCandidateError ?: return
        val targetParameterSymbol: FirValueParameterSymbol
        val resolvedFunction: FirNamedFunctionSymbol =
            inapplicableCandidate.candidate.symbol as? FirNamedFunctionSymbol ?: return
        val candidateDiagnostic =
            inapplicableCandidate.candidate.diagnostics.firstOrNull { diagnostic ->
                diagnostic is NoContextArgument || diagnostic is AmbiguousContextArgument
            }
        targetParameterSymbol =
            when (candidateDiagnostic) {
                is NoContextArgument -> candidateDiagnostic.symbol
                is AmbiguousContextArgument -> candidateDiagnostic.symbol
                else -> return
            }
        val function = resolvedFunction.fir
        if (function.origin.generated) {
            return
        }
        if (function.hasAnnotation(INSTANCE_ANNOTATION_CLASS_ID, session)) {
            return
        }
        val targetParameter =
            function.contextParameters.firstOrNull { parameter ->
                parameter.symbol == targetParameterSymbol
            } ?: return
        val inferredTypeArguments =
            inferFunctionTypeArgumentsFromCallSite(
                session = session,
                functionCall = functionCall,
                resolvedFunction = resolvedFunction,
                containingFunction = declaration.symbol,
                sharedState = sharedState,
            )
        val substitutedType =
            substituteInferredTypes(
                type = targetParameter.returnTypeRef.coneType,
                substitutions = inferredTypeArguments,
                session = session,
            )
        if (!sharedState.isTypeclassType(session, substitutedType)) {
            return
        }
        val goal =
            coneTypeToModel(substitutedType, typeContext.typeParameterModels)
                ?: return
        val exactBuiltinGoalContext =
            FirBuiltinGoalExactContext(
                session = session,
                typeParameterModels = typeContext.typeParameterModels,
                variableSymbolsById =
                    typeContext.typeParameterModels.entries.associate { (symbol, parameter) ->
                        parameter.id to symbol
                    },
            )
        val planner =
            TypeclassResolutionPlanner(
                ruleProvider = { desiredGoal ->
                    sharedState.refinementRulesForGoal(
                        session = session,
                        goal = desiredGoal,
                        canMaterializeVariable = typeContext.runtimeMaterializableVariableIds::contains,
                        exactBuiltinGoalContext = exactBuiltinGoalContext,
                    )
                },
                bindableDesiredVariableIds = typeContext.bindableVariableIds,
            )
        val tracedResult =
            planner.resolveWithTrace(
                desiredType = goal,
                localContextTypes = typeContext.directlyAvailableContextModels,
                explainAlternatives = activation.mode.explainsAlternatives,
            )
        when (tracedResult.result) {
            is ResolutionSearchResult.Missing ->
                reportNoContextArgumentWithTrace(
                    expression = functionCall,
                    narrative =
                        TypeclassDiagnostic.NoContextArgument(
                            goal = goal.render().replace('/', '.'),
                            scope = "",
                        ),
                    trace =
                        renderResolutionTrace(
                            trace = tracedResult.trace,
                            activation = activation,
                            location = null,
                            localContextLabels = typeContext.directlyAvailableContextLabels,
                        ),
                )
            is ResolutionSearchResult.Recursive ->
                reportNoContextArgumentWithTrace(
                    expression = functionCall,
                    narrative =
                        TypeclassDiagnostic.NoContextArgument(
                            goal = goal.render().replace('/', '.'),
                            scope = "",
                            recursive = true,
                        ),
                    trace =
                        renderResolutionTrace(
                            trace = tracedResult.trace,
                            activation = activation,
                            location = null,
                            localContextLabels = typeContext.directlyAvailableContextLabels,
                        ),
                )
            is ResolutionSearchResult.Ambiguous ->
                reportAmbiguousInstanceWithTrace(
                    expression = functionCall,
                    narrative =
                        TypeclassDiagnostic.AmbiguousInstance(
                            goal = goal.render().replace('/', '.'),
                            scope = "",
                            candidates = tracedResult.result.matchingPlans.map { plan -> plan.renderForFirDiagnostic() },
                        ),
                    trace =
                        renderResolutionTrace(
                            trace = tracedResult.trace,
                            activation = activation,
                            location = null,
                            localContextLabels = typeContext.directlyAvailableContextLabels,
                        ),
                )
            is ResolutionSearchResult.Success -> Unit
        }
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun validateAssociatedScope(
        ownerContext: InstanceOwnerContext,
        providedTypes: ProvidedTypeExpansion,
        declaration: org.jetbrains.kotlin.fir.declarations.FirDeclaration,
    ) {
        when {
            providedTypes.validTypes.isEmpty() -> {
                reportInvalid(declaration, invalidInstanceMustProvideTypeclassType())
                return
            }

            providedTypes.invalidTypes.isNotEmpty() -> {
                reportInvalid(declaration, invalidInstanceNonTypeclassSupertypes())
                return
            }
        }

        when {
            ownerContext.isTopLevel -> {
                if (!declaration.isLegalTopLevelInstanceLocation(session, providedTypes)) {
                    reportInvalid(declaration, invalidInstanceTopLevelOrphan(providedTypes.topLevelInstanceHostDisplayNames().toList()))
                }
            }
            !ownerContext.isCompanionScope -> {
                reportInvalid(declaration, invalidInstanceWrongScopeCompanion())
            }

            else -> {
                providedTypes.validTypes.forEach { providedType ->
                    if (ownerContext.associatedOwner !in sharedState.allowedAssociatedOwnersForProvidedType(session, providedType)) {
                        reportInvalid(declaration, invalidInstanceAssociatedOwnerMismatch())
                        return
                    }
                }
            }
        }
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun validateFunctionRuleSemantics(
        declaration: FirTypeclassFunctionDeclaration,
    ) {
        declaration.contextParameters.firstNotNullOfOrNull { parameter ->
            invalidInstancePrerequisiteMessage(parameter.returnTypeRef.coneType)
        }?.let { message ->
            reportInvalid(
                declaration,
                when (message) {
                    "instance function context parameters must be typeclass prerequisites" ->
                        invalidInstanceNonTypeclassPrerequisites()
                    "instance function typeclass prerequisites must not use star projections" ->
                        invalidInstanceStarProjectedPrerequisites()
                    "instance function typeclass prerequisites must not use definitely-non-null type arguments" ->
                        invalidInstanceDefinitelyNotNullPrerequisites()
                    else -> invalidInstanceDiagnostic(message)
                },
            )
            return
        }

        val ruleShape = declaration.instanceFunctionRuleShape(session, sharedState) ?: return
        val providedTypes = declaration.instanceProvidedTypes(session, sharedState.configuration)
        if (providedTypes.validTypes.isEmpty()) {
            return
        }

        ruleShape.typeParameters.forEach { parameter ->
            if (ruleShape.declaredProvidedType.references(parameter.id)) {
                return@forEach
            }
            reportInvalid(
                declaration,
                invalidInstanceTypeParameterOnlyInPrerequisites(parameter.displayName),
            )
            return
        }

        val recursiveProvidedType = providedTypes.validTypes.firstOrNull { providedType ->
            ruleShape.prerequisites.any { prerequisite ->
                prerequisite.normalizedKey() == providedType.normalizedKey()
            }
        }
        if (recursiveProvidedType != null) {
            reportInvalid(declaration, invalidInstanceDirectRecursive(recursiveProvidedType.renderForMessage()))
        }
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun validateDeriveDeclaration(
        declaration: FirRegularClass,
    ) {
        if (!declaration.supportsDeriveShape()) {
            reportCannotDerive(declaration, cannotDeriveUnsupportedShape())
            return
        }
        if (
            declaration.classKind != ClassKind.OBJECT &&
            declaration.classKind != ClassKind.ENUM_CLASS &&
            declaration.status.modality != Modality.SEALED &&
            declaration.declarations
                .filterIsInstance<FirConstructor>()
                .none { constructor -> constructor.isPrimary }
        ) {
            reportCannotDerive(declaration, cannotDeriveRequiresPrimaryConstructor())
            return
        }

        declaration.derivedAnnotationTargetClassIds(session).forEach { targetClassId ->
            if (session.regularClassSymbolOrNull(targetClassId)?.hasAnnotation(TYPECLASS_ANNOTATION_CLASS_ID, session) != true) {
                reportCannotDerive(
                    declaration,
                    cannotDeriveDiagnostic("${targetClassId.shortClassName.asString()} is not annotated with @Typeclass"),
                )
            }
        }

        declaration.derivedTypeclassIds(session).forEach { typeclassIdString ->
            val typeclassId = runCatching { ClassId.fromString(typeclassIdString) }.getOrNull() ?: return@forEach
            if (typeclassTypeParameterCount(typeclassId, session) != 1) {
                reportCannotDerive(declaration, cannotDeriveOnlyUnaryTypeclasses())
                return
            }
            val requiredDeriverInterface = declaration.requiredDeriverInterfaceForDeriveShape()
            if (!typeclassSupportsDeriveShape(typeclassId, requiredDeriverInterface, session)) {
                val requiredName = requiredDeriverInterface.shortClassName.asString()
                val targetName = typeclassId.shortClassName.asString()
                val message =
                    if (declaration.classKind == ClassKind.ENUM_CLASS) {
                        "$targetName companion must implement $requiredName; ProductTypeclassDeriver only supports products, not enums"
                    } else if (requiredDeriverInterface == TYPECLASS_DERIVER_CLASS_ID) {
                        "$targetName companion must implement $requiredName; ProductTypeclassDeriver only supports products, not sealed sums"
                    } else {
                        "$targetName companion must implement $requiredName to derive products"
                    }
                reportCannotDerive(
                    declaration,
                    cannotDeriveDiagnostic(message),
                )
                return@forEach
            }
            declaration.requiredDeriveMethodContractForDeriveShape()?.let { contract ->
                if (typeclassCompanionResolveDeriveMethod(typeclassId, contract, session) == null) {
                    if (contract == DeriveMethodContract.ENUM) {
                        reportCannotDerive(declaration, cannotDeriveMissingEnumOverride(typeclassId.shortClassName.asString()))
                    } else {
                        val companionId = typeclassCompanionSymbol(typeclassId, session)?.classId?.asString() ?: typeclassId.asString()
                        reportCannotDerive(
                            declaration,
                            cannotDeriveDiagnostic("Typeclass deriver $companionId is missing ${contract.methodName}"),
                        )
                    }
                    return@forEach
                }
            }
            sharedState.declarationShapeDerivationFailure(
                session = session,
                directTypeclassId = typeclassId.asString(),
                declaration = declaration,
            )?.let { failure ->
                val storedPropertyMismatchMessage =
                    "Cannot derive ${declaration.symbol.classId.asFqNameString()} because constructive product derivation requires constructor parameters to exactly match stored properties"
                val narrative =
                    if (failure == storedPropertyMismatchMessage) {
                        cannotDeriveConstructiveProductStoredPropertyMismatch(declaration.symbol.classId.asFqNameString())
                    } else {
                        cannotDeriveDiagnostic(failure)
                    }
                reportCannotDerive(declaration, narrative)
                return@forEach
            }
        }
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun validateDeriveViaDeclarations(
        declaration: FirRegularClass,
    ) {
        declaration.deriveViaAnnotationParseResults(session).forEach { result ->
            if (result is FirDeriveViaAnnotationParseResult.Invalid) {
                reportCannotDerive(
                    result.annotation,
                    cannotDeriveDiagnostic(result.message),
                )
            }
        }
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun validateDeriveEquivDeclarations(
        declaration: FirRegularClass,
    ) {
        declaration.deriveEquivAnnotationParseResults(session).forEach { result ->
            if (result is FirDeriveEquivAnnotationParseResult.Invalid) {
                reportCannotDerive(
                    result.annotation,
                    cannotDeriveDiagnostic(result.message),
                )
            }
        }
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun validateTypeclassDeriverCompanionContracts(
        declaration: FirRegularClass,
    ) {
        val companion =
            declaration.declarations
                .filterIsInstance<FirRegularClass>()
                .singleOrNull(FirRegularClass::isTypeclassCompanionDeclaration)
                ?: session.companionSymbolOrNull(declaration.symbol.classId)?.fir
                ?: return
        val typeclassId = declaration.symbol.classId
        val deriveMethods =
            companion.symbol.implementedDeriveMethodContracts(session)
                .mapNotNull { contract ->
                    companion.symbol.resolveDeriveMethod(contract, session)?.let { function -> contract.methodName to function }
                }
        deriveMethods.forEach { (deriveMethodName, function) ->
                val declaredReturnConstructors =
                    listOf(function.returnTypeRef.coneType)
                        .expandProvidedTypes(session, emptyMap(), sharedState.configuration)
                        .validTypes
                        .mapNotNull { providedType -> (providedType as? TcType.Constructor)?.classifierId }
                        .distinct()
                if (declaredReturnConstructors.isNotEmpty()) {
                    if (typeclassId.asString() !in declaredReturnConstructors) {
                        reportCannotDerive(
                            function,
                            cannotDeriveWrongDeriverReturnType(
                                methodName = deriveMethodName,
                                typeclassName = typeclassId.shortClassName.asString(),
                                foundTypeclassName =
                                    declaredReturnConstructors.joinToString { classifierId ->
                                        classifierId.shortClassNameOrSelf()
                                    },
                            ),
                        )
                    }
                    return@forEach
                }
                function.knownDeriverReturnExpressions().forEach { expression ->
                    val knownTypeclassConstructors =
                        expression.knownReturnedTypeclassConstructors(session, sharedState.configuration)
                    if (knownTypeclassConstructors.isEmpty()) {
                        reportCannotDerive(
                            function,
                            cannotDeriveWrongDeriverReturnType(
                                methodName = deriveMethodName,
                                typeclassName = typeclassId.shortClassName.asString(),
                            ),
                        )
                        return
                    }
                    if (typeclassId.asString() in knownTypeclassConstructors) {
                        return@forEach
                    }
                    reportCannotDerive(
                        function,
                        cannotDeriveWrongDeriverReturnType(
                            methodName = deriveMethodName,
                            typeclassName = typeclassId.shortClassName.asString(),
                            foundTypeclassName =
                                knownTypeclassConstructors.joinToString { classifierId ->
                                    classifierId.shortClassNameOrSelf()
                                },
                        ),
                    )
                    return
                }
            }
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun reportInvalid(
        declaration: org.jetbrains.kotlin.fir.declarations.FirDeclaration,
        message: String,
    ) = reportInvalid(declaration, invalidInstanceDiagnostic(message))

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun reportInvalid(
        declaration: org.jetbrains.kotlin.fir.declarations.FirDeclaration,
        narrative: TypeclassDiagnostic,
    ) {
        val source = declaration.source ?: return
        reporter.reportOn(
            source,
            TypeclassErrors.INVALID_INSTANCE_DECLARATION,
            narrative.renderBody(),
        )
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun reportInvalidEquiv(
        declaration: org.jetbrains.kotlin.fir.declarations.FirDeclaration,
        message: String,
    ) = reportInvalidEquiv(declaration, invalidEquivDiagnostic(message))

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun reportInvalidEquiv(
        declaration: org.jetbrains.kotlin.fir.declarations.FirDeclaration,
        narrative: TypeclassDiagnostic,
    ) {
        val source = declaration.source ?: return
        reporter.reportOn(
            source,
            TypeclassErrors.INVALID_EQUIV_DECLARATION,
            narrative.renderBody(),
        )
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun reportCannotDerive(
        declaration: org.jetbrains.kotlin.fir.declarations.FirDeclaration,
        message: String,
    ) = reportCannotDerive(declaration, cannotDeriveDiagnostic(message))

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun reportCannotDerive(
        declaration: org.jetbrains.kotlin.fir.declarations.FirDeclaration,
        narrative: TypeclassDiagnostic,
    ) {
        val source = declaration.source ?: return
        reporter.reportOn(
            source,
            TypeclassErrors.CANNOT_DERIVE,
            narrative.renderBody(),
        )
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun reportCannotDerive(
        annotation: FirAnnotation,
        message: String,
    ) = reportCannotDerive(annotation, cannotDeriveDiagnostic(message))

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun reportCannotDerive(
        annotation: FirAnnotation,
        narrative: TypeclassDiagnostic,
    ) {
        val source = annotation.source ?: return
        reporter.reportOn(
            source,
            TypeclassErrors.CANNOT_DERIVE,
            narrative.renderBody(),
        )
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun reportNoContextArgumentWithTrace(
        expression: FirExpression,
        narrative: TypeclassDiagnostic.NoContextArgument,
        trace: String,
    ) {
        val source = expression.source ?: return
        reporter.reportOn(
            source,
            TypeclassErrors.NO_CONTEXT_ARGUMENT,
            "${narrative.renderBody()}\n$trace",
        )
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun reportAmbiguousInstanceWithTrace(
        expression: FirExpression,
        narrative: TypeclassDiagnostic.AmbiguousInstance,
        trace: String,
    ) {
        val source = expression.source ?: return
        reporter.reportOn(
            source,
            TypeclassErrors.AMBIGUOUS_INSTANCE,
            "${narrative.renderBody()}\n$trace",
        )
    }

    private fun ResolutionPlan.renderForFirDiagnostic(): String =
        when (this) {
            is ResolutionPlan.LocalContext -> "local-context[$index]"
            is ResolutionPlan.ApplyRule -> ruleId
            is ResolutionPlan.RecursiveReference -> "recursive[${providedType.render()}]"
        }

    private fun FirBasedSymbol<*>.firFunctionOrNull(): FirFunction? = fir as? FirFunction

    private fun invalidInstancePrerequisiteMessage(type: ConeKotlinType): String? =
        when {
            !sharedState.isTypeclassType(session, type) ->
                "instance function context parameters must be typeclass prerequisites"

            containsStarProjection(type) ->
                "instance function typeclass prerequisites must not use star projections"

            containsDefinitelyNonNullOrIntersection(type) ->
                "instance function typeclass prerequisites must not use definitely-non-null type arguments"

            else -> null
        }

}

private fun FirRegularClass.directlyExtendsEquiv(): Boolean =
    superTypeRefs.any { superTypeRef ->
        superTypeRef.coneType.classId == EQUIV_CLASS_ID
    }

private fun isEquivType(type: TcType): Boolean =
    (type as? TcType.Constructor)?.classifierId == EQUIV_CLASS_ID.asString()

private fun FirTypeclassFunctionDeclaration.knownDeriverReturnExpressions(): List<FirExpression> {
    val expressions = linkedSetOf<FirExpression>()
    val body = body ?: return emptyList()
    body.statements.lastOrNull()?.let { statement ->
        when (statement) {
            is FirReturnExpression -> expressions += statement.result
            is FirExpression -> expressions += statement
        }
    }
    body.acceptChildren(
        object : FirDefaultVisitorVoid() {
            override fun visitElement(element: FirElement) {
                element.acceptChildren(this)
            }

            override fun visitFunction(function: FirFunction) {
                return
            }

            override fun visitAnonymousFunctionExpression(anonymousFunctionExpression: FirAnonymousFunctionExpression) {
                return
            }

            override fun visitRegularClass(regularClass: FirRegularClass) {
                return
            }

            override fun visitReturnExpression(returnExpression: FirReturnExpression) {
                if (returnExpression.target.labeledElement === this@knownDeriverReturnExpressions) {
                    expressions += returnExpression.result
                }
                returnExpression.result.acceptChildren(this)
            }
        },
    )
    return expressions.toList()
}

private fun FirExpression.knownReturnedTypeclassConstructors(
    session: FirSession,
    configuration: TypeclassConfiguration,
    visitedCallables: MutableSet<FirCallableSymbol<*>> = linkedSetOf(),
): List<String> {
    val expression = knownReturnedExpressionOrSelf()
    val knownConstructors = linkedSetOf<String>()
    expression.safeResolvedOrInferredTypeOrNull(session)?.let { resolvedType ->
        listOf(resolvedType)
            .expandProvidedTypes(session, emptyMap(), configuration)
            .validTypes
            .mapNotNull { providedType -> (providedType as? TcType.Constructor)?.classifierId }
            .forEach(knownConstructors::add)
    }
    expression.implementationClassIdForKnownReturn(session)?.let { implementationClassId ->
        knownTypeclassConstructorsForImplementation(implementationClassId, session, configuration)
            .forEach(knownConstructors::add)
    }
    if (expression is FirAnonymousObjectExpression) {
        expression.anonymousObject.superTypeRefs
            .mapNotNull { typeRef -> typeRef.coneType.lowerBoundIfFlexible().classId }
            .forEach { superTypeClassId ->
                val classifierId = superTypeClassId.asString()
                if (configuration.supportsReturnedTypeclassClassifierId(classifierId, session)) {
                    knownConstructors += classifierId
                }
                knownTypeclassConstructorsForImplementation(superTypeClassId, session, configuration)
                    .forEach(knownConstructors::add)
            }
    }
    when (expression) {
        is FirPropertyAccessExpression -> {
            val symbol = (expression.calleeReference as? FirResolvedNamedReference)?.resolvedSymbol as? FirCallableSymbol<*> ?: return knownConstructors.toList()
            if (!visitedCallables.add(symbol)) {
                return knownConstructors.toList()
            }
            try {
                when (val declaration = symbol.fir) {
                    is FirProperty ->
                        declaration.initializer
                            ?.knownReturnedTypeclassConstructors(session, configuration, visitedCallables)
                            ?.forEach(knownConstructors::add)

                    is FirTypeclassFunctionDeclaration ->
                        declaration.knownDeriverReturnExpressions()
                            .forEach { nested ->
                                nested.knownReturnedTypeclassConstructors(session, configuration, visitedCallables)
                                    .forEach(knownConstructors::add)
                            }

                    else -> Unit
                }
            } finally {
                visitedCallables.remove(symbol)
            }
        }

        is FirFunctionCall -> {
            val symbol = (expression.calleeReference as? FirResolvedNamedReference)?.resolvedSymbol as? FirCallableSymbol<*> ?: return knownConstructors.toList()
            if (!visitedCallables.add(symbol)) {
                return knownConstructors.toList()
            }
            try {
                (symbol.fir as? FirTypeclassFunctionDeclaration)
                    ?.knownDeriverReturnExpressions()
                    ?.forEach { nested ->
                        nested.knownReturnedTypeclassConstructors(session, configuration, visitedCallables)
                            .forEach(knownConstructors::add)
                    }
            } finally {
                visitedCallables.remove(symbol)
            }
        }
    }
    return knownConstructors.toList()
}

private fun FirExpression.knownReturnedExpressionOrSelf(): FirExpression =
    when (this) {
        is FirTypeOperatorCall -> argumentList.arguments.singleOrNull()?.knownReturnedExpressionOrSelf() ?: this
        is FirSmartCastExpression -> originalExpression.knownReturnedExpressionOrSelf()
        is FirBlock -> (statements.lastOrNull() as? FirExpression)?.knownReturnedExpressionOrSelf() ?: this
        else -> this
    }

private fun FirExpression.implementationClassIdForKnownReturn(
    session: FirSession,
): ClassId? =
    when (this) {
        is FirResolvedQualifier -> symbol?.classId ?: classId
        is FirFunctionCall ->
            ((calleeReference as? FirResolvedNamedReference)?.resolvedSymbol as? FirCallableSymbol<*>)?.callableId?.classId
        is FirPropertyAccessExpression ->
            ((calleeReference as? FirResolvedNamedReference)?.resolvedSymbol as? FirCallableSymbol<*>)?.resolvedReturnType
                ?.lowerBoundIfFlexible()
                ?.classId
        else -> null
    }?.takeUnless(ClassId::isLocal)

private fun knownTypeclassConstructorsForImplementation(
    implementationClassId: ClassId,
    session: FirSession,
    configuration: TypeclassConfiguration,
): List<String> {
    val classSymbol = session.regularClassSymbolOrNull(implementationClassId) ?: return emptyList()
    return classSymbol.fir.declaredOrResolvedSuperTypes()
        .expandProvidedTypes(session, emptyMap(), configuration)
        .validTypes
        .mapNotNull { providedType -> (providedType as? TcType.Constructor)?.classifierId }
        .distinct()
}

private fun TypeclassConfiguration.supportsReturnedTypeclassClassifierId(
    classifierId: String,
    session: FirSession,
): Boolean {
    val classId = runCatching { ClassId.fromString(classifierId) }.getOrNull() ?: return false
    if (isBuiltinTypeclass(classId)) {
        return true
    }
    if (classId.isLocal) {
        return false
    }
    val classSymbol = session.regularClassSymbolOrNull(classId) ?: return false
    return classSymbol.fir.hasAnnotation(TYPECLASS_ANNOTATION_CLASS_ID, session)
}

private fun String.shortClassNameOrSelf(): String =
    runCatching { ClassId.fromString(this).shortClassName.asString() }.getOrDefault(this)

private fun containsStarProjection(type: ConeKotlinType): Boolean {
    val lowerBound = type.lowerBoundIfFlexible()
    return when (lowerBound) {
        is ConeClassLikeType ->
            lowerBound.typeArguments.any { argument ->
                argument is ConeStarProjection || argument.type?.let(::containsStarProjection) == true
            }

        is ConeDefinitelyNotNullType -> containsStarProjection(lowerBound.original)
        else -> false
    }
}

private fun containsDefinitelyNonNullOrIntersection(type: ConeKotlinType): Boolean {
    val lowerBound = type.lowerBoundIfFlexible()
    return when (lowerBound) {
        is ConeDefinitelyNotNullType -> true
        is ConeIntersectionType -> true
        is ConeClassLikeType ->
            lowerBound.typeArguments.any { argument ->
                argument.type?.let(::containsDefinitelyNonNullOrIntersection) == true
            }

        else -> false
    }
}

private data class InstanceFunctionRuleShape(
    val typeParameters: List<TcTypeParameter>,
    val declaredProvidedType: TcType,
    val prerequisites: List<TcType>,
)

private fun FirTypeclassFunctionDeclaration.instanceFunctionRuleShape(
    session: FirSession,
    sharedState: TypeclassPluginSharedState,
): InstanceFunctionRuleShape? {
    val typeParameters =
        typeParameters.mapIndexed { index, typeParameter ->
            TcTypeParameter(
                id = "fir-function:${symbol.callableId}:$index:${typeParameter.symbol.name.asString()}",
                displayName = typeParameter.symbol.name.asString(),
            )
        }
    val typeParameterBySymbol =
        this.typeParameters.zip(typeParameters).associate { (typeParameter, parameter) ->
            typeParameter.symbol to parameter
        }
    val declaredProvidedType = coneTypeToModel(returnTypeRef.coneType, typeParameterBySymbol) ?: return null
    val prerequisites =
        contextParameters.mapNotNull { parameter ->
            parameter.returnTypeRef.coneType
                .takeIf { type -> sharedState.isTypeclassType(session, type) }
                ?.let { type -> coneTypeToModel(type, typeParameterBySymbol) }
        }
    if (prerequisites.size != contextParameters.size) {
        return null
    }
    return InstanceFunctionRuleShape(
        typeParameters = typeParameters,
        declaredProvidedType = declaredProvidedType,
        prerequisites = prerequisites,
    )
}

private fun TcType.renderForMessage(): String =
    when (this) {
        TcType.StarProjection -> "*"
        is TcType.Projected -> "${variance.label} ${type.renderForMessage()}"
        is TcType.Constructor -> classifierId.substringAfterLast('.')
        is TcType.Variable -> displayName
    }
