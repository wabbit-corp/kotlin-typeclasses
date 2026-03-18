@file:OptIn(
    org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess::class,
    org.jetbrains.kotlin.fir.symbols.SymbolInternals::class,
)

package one.wabbit.typeclass.plugin

import one.wabbit.typeclass.plugin.model.TcType
import one.wabbit.typeclass.plugin.model.TcTypeParameter
import one.wabbit.typeclass.plugin.model.normalizedKey
import one.wabbit.typeclass.plugin.model.references
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirPropertyChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirRegularClassChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirSimpleFunctionChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeDefinitelyNotNullType
import org.jetbrains.kotlin.fir.types.ConeIntersectionType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeStarProjection
import org.jetbrains.kotlin.fir.types.lowerBoundIfFlexible
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.SpecialNames

internal class TypeclassFirCheckersExtension(
    session: FirSession,
    private val sharedState: TypeclassPluginSharedState,
) : FirAdditionalCheckersExtension(session) {
    private val regularClassChecker =
        object : FirRegularClassChecker(MppCheckerKind.Common) {
            context(context: CheckerContext, reporter: DiagnosticReporter)
            override fun check(declaration: FirRegularClass) {
                if (!declaration.hasAnnotation(INSTANCE_ANNOTATION_CLASS_ID, session)) {
                    return
                }
                if (declaration.classKind != ClassKind.OBJECT) {
                    reportInvalid(declaration, "class-based instances are not allowed; use an object")
                    return
                }

                validateAssociatedScope(
                    ownerContext = classOwnerContext(declaration.symbol.classId),
                    providedTypes = declaration.instanceProvidedTypes(session, sharedState.configuration),
                    declaration = declaration,
                )
            }
        }

    private val simpleFunctionChecker =
        object : FirSimpleFunctionChecker(MppCheckerKind.Common) {
            context(context: CheckerContext, reporter: DiagnosticReporter)
            override fun check(declaration: FirSimpleFunction) {
                if (!declaration.hasAnnotation(INSTANCE_ANNOTATION_CLASS_ID, session)) {
                    return
                }
                when {
                    declaration.receiverParameter != null -> {
                        reportInvalid(declaration, "extension instance functions are not allowed")
                        return
                    }

                    declaration.valueParameters.isNotEmpty() -> {
                        reportInvalid(declaration, "instance functions cannot declare a regular parameter")
                        return
                    }

                    declaration.status.isSuspend -> {
                        reportInvalid(declaration, "suspend instance functions are not allowed")
                        return
                    }
                }

                validateAssociatedScope(
                    ownerContext = callableOwnerContext(declaration.symbol.callableId),
                    providedTypes = declaration.instanceProvidedTypes(session, sharedState.configuration),
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
                        reportInvalid(declaration, "extension instance properties are not allowed")
                        return
                    }

                    declaration.status.isLateInit -> {
                        reportInvalid(declaration, "lateinit instance property declarations are not allowed")
                        return
                    }

                    declaration.isVar -> {
                        reportInvalid(declaration, "mutable instance property declarations are not allowed")
                        return
                    }

                    declaration.getter?.body != null -> {
                        reportInvalid(declaration, "custom getter instance property declarations are not allowed")
                        return
                    }
                }

                validateAssociatedScope(
                    ownerContext = callableOwnerContext(declaration.symbol.callableId),
                    providedTypes = declaration.instanceProvidedTypes(session, sharedState.configuration),
                    declaration = declaration,
                )
            }
        }

    override val declarationCheckers: DeclarationCheckers =
        object : DeclarationCheckers() {
            override val regularClassCheckers: Set<FirRegularClassChecker> = setOf(regularClassChecker)
            override val simpleFunctionCheckers: Set<FirSimpleFunctionChecker> = setOf(simpleFunctionChecker)
            override val propertyCheckers: Set<FirPropertyChecker> = setOf(propertyChecker)
        }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun validateAssociatedScope(
        ownerContext: InstanceOwnerContext,
        providedTypes: ProvidedTypeExpansion,
        declaration: org.jetbrains.kotlin.fir.declarations.FirDeclaration,
    ) {
        when {
            providedTypes.invalidTypes.isNotEmpty() -> {
                reportInvalid(
                    declaration,
                    "non-@Typeclass intermediate supertypes cannot provide inherited typeclass instances",
                )
                return
            }

            providedTypes.validTypes.isEmpty() -> {
                reportInvalid(
                    declaration,
                    "@Instance declarations must provide a @Typeclass type",
                )
                return
            }
        }

        when {
            ownerContext.isTopLevel -> Unit
            !ownerContext.isCompanionScope -> {
                reportInvalid(
                    declaration,
                    "instance declarations must be top-level or live in a companion of the provided typeclass head or one of its associated owners",
                )
            }

            else -> {
                providedTypes.validTypes.forEach { providedType ->
                    if (ownerContext.associatedOwner !in sharedState.allowedAssociatedOwnersForProvidedType(session, providedType)) {
                        reportInvalid(
                            declaration,
                            "associated owner does not match the provided typeclass head or its type arguments",
                        )
                        return
                    }
                }
            }
        }
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun validateFunctionRuleSemantics(
        declaration: FirSimpleFunction,
    ) {
        declaration.contextParameters.firstNotNullOfOrNull { parameter ->
            invalidInstancePrerequisiteMessage(parameter.returnTypeRef.coneType)
        }?.let { message ->
            reportInvalid(declaration, message)
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
                "instance type parameter ${parameter.displayName} must appear in the provided type, not only prerequisites",
            )
            return
        }

        val recursiveProvidedType = providedTypes.validTypes.firstOrNull { providedType ->
            ruleShape.prerequisites.any { prerequisite ->
                prerequisite.normalizedKey() == ruleShape.declaredProvidedType.normalizedKey() &&
                    prerequisite.normalizedKey() == providedType.normalizedKey()
            }
        }
        if (recursiveProvidedType != null) {
            reportInvalid(
                declaration,
                "direct recursive instance rule for ${recursiveProvidedType.renderForMessage()} is not allowed",
            )
        }
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun reportInvalid(
        declaration: org.jetbrains.kotlin.fir.declarations.FirDeclaration,
        message: String,
    ) {
        val source = declaration.source ?: return
        reporter.reportOn(source, TypeclassErrors.INVALID_INSTANCE_DECLARATION, message)
    }

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

    private fun classOwnerContext(classId: ClassId): InstanceOwnerContext {
        if (classId.isLocal) {
            return InstanceOwnerContext(isTopLevel = false, isCompanionScope = false, associatedOwner = null)
        }
        return if (classId.shortClassName == SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT) {
            InstanceOwnerContext(
                isTopLevel = false,
                isCompanionScope = true,
                associatedOwner = classId.outerClassId,
            )
        } else {
            nestedOwnerContext(classId.outerClassId)
        }
    }

    private fun callableOwnerContext(callableId: CallableId?): InstanceOwnerContext =
        nestedOwnerContext(callableId?.classId)

    private fun nestedOwnerContext(ownerClassId: ClassId?): InstanceOwnerContext {
        if (ownerClassId == null) {
            return InstanceOwnerContext(isTopLevel = true, isCompanionScope = false, associatedOwner = null)
        }
        if (ownerClassId.isLocal) {
            return InstanceOwnerContext(isTopLevel = false, isCompanionScope = false, associatedOwner = null)
        }
        val ownerClass =
            session.symbolProvider.getClassLikeSymbolByClassId(ownerClassId) as? org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
                ?: return InstanceOwnerContext(isTopLevel = false, isCompanionScope = false, associatedOwner = null)
        return if (ownerClass.fir.classKind == ClassKind.OBJECT && ownerClassId.shortClassName == SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT) {
            InstanceOwnerContext(
                isTopLevel = false,
                isCompanionScope = true,
                associatedOwner = ownerClassId.outerClassId,
            )
        } else {
            InstanceOwnerContext(isTopLevel = false, isCompanionScope = false, associatedOwner = ownerClassId)
        }
    }
}

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

private data class InstanceOwnerContext(
    val isTopLevel: Boolean,
    val isCompanionScope: Boolean,
    val associatedOwner: ClassId?,
)

private data class InstanceFunctionRuleShape(
    val typeParameters: List<TcTypeParameter>,
    val declaredProvidedType: TcType,
    val prerequisites: List<TcType>,
)

private fun FirSimpleFunction.instanceFunctionRuleShape(
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
        is TcType.Constructor -> classifierId.substringAfterLast('.')
        is TcType.Variable -> displayName
    }
