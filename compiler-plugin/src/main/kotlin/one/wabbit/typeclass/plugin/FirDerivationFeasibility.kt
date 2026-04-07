// SPDX-License-Identifier: AGPL-3.0-or-later

@file:OptIn(
    org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess::class,
    org.jetbrains.kotlin.fir.symbols.SymbolInternals::class,
)

package one.wabbit.typeclass.plugin

import one.wabbit.typeclass.plugin.model.TcType
import one.wabbit.typeclass.plugin.model.TcTypeParameter
import one.wabbit.typeclass.plugin.model.normalizedKey
import one.wabbit.typeclass.plugin.model.references
import one.wabbit.typeclass.plugin.model.render
import one.wabbit.typeclass.plugin.model.substituteType
import one.wabbit.typeclass.plugin.model.unifyTypes
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirAnonymousInitializer
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirTypeParameter
import org.jetbrains.kotlin.fir.declarations.FirValueParameterKind
import org.jetbrains.kotlin.fir.declarations.findArgumentByName
import org.jetbrains.kotlin.fir.declarations.getSealedClassInheritors
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.declarations.unwrapVarargValue
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirClassReferenceExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.resolve.providers.firProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.lowerBoundIfFlexible
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames

internal sealed interface FirDeriveViaPathSegment {
    val classId: ClassId

    data class Waypoint(
        override val classId: ClassId,
    ) : FirDeriveViaPathSegment

    data class PinnedIso(
        override val classId: ClassId,
    ) : FirDeriveViaPathSegment
}

internal data class FirDeriveViaRequest(
    val typeclassId: ClassId,
    val path: List<FirDeriveViaPathSegment>,
)

internal sealed interface FirDeriveViaAnnotationParseResult {
    val annotation: FirAnnotation

    data class Valid(
        override val annotation: FirAnnotation,
        val request: FirDeriveViaRequest,
    ) : FirDeriveViaAnnotationParseResult

    data class Invalid(
        override val annotation: FirAnnotation,
        val message: String,
    ) : FirDeriveViaAnnotationParseResult
}

internal data class FirShapeDerivedGoalMatch(
    val directTypeclassId: String,
    val targetType: TcType.Constructor,
)

internal class FirDirectTransportPlanner(
    private val session: FirSession,
) {
    fun planEquiv(
        sourceType: TcType.Constructor,
        targetType: TcType.Constructor,
    ): Boolean = canTransport(sourceType, targetType, linkedSetOf()) && canTransport(targetType, sourceType, linkedSetOf())

    fun resolveViaPath(
        sourceType: TcType.Constructor,
        path: List<FirDeriveViaPathSegment>,
    ): TcType.Constructor? {
        if (path.isEmpty()) {
            return null
        }

        var current = sourceType
        for (segment in path) {
            when (segment) {
                is FirDeriveViaPathSegment.Waypoint -> {
                    val waypointClass = session.regularClassSymbolOrNull(segment.classId)?.fir ?: return null
                    if (waypointClass.typeParameters.isNotEmpty()) {
                        return null
                    }
                    val waypointType = TcType.Constructor(segment.classId.asString(), emptyList())
                    if (!planEquiv(current, waypointType)) {
                        return null
                    }
                    current = waypointType
                }

                is FirDeriveViaPathSegment.PinnedIso -> {
                    val isoClass = session.regularClassSymbolOrNull(segment.classId)?.fir ?: return null
                    if (isoClass.classKind != ClassKind.OBJECT) {
                        return null
                    }
                    val endpoints = isoClass.isoEndpoints(session) ?: return null
                    val leftReachable = planEquiv(current, endpoints.first)
                    val rightReachable = planEquiv(current, endpoints.second)
                    current =
                        when {
                            leftReachable && rightReachable -> return null
                            leftReachable -> endpoints.second
                            rightReachable -> endpoints.first
                            else -> return null
                        }
                }
            }
        }
        return current
    }

    private fun canTransport(
        sourceType: TcType,
        targetType: TcType,
        visiting: MutableSet<Pair<String, String>>,
    ): Boolean {
        if (sourceType.normalizedKey() == targetType.normalizedKey()) {
            return true
        }

        val sourceNonNull = sourceType.withoutNullability()
        val targetNonNull = targetType.withoutNullability()
        if (sourceNonNull != null || targetNonNull != null) {
            if (sourceNonNull == null || targetNonNull == null) {
                return false
            }
            return canTransport(sourceNonNull, targetNonNull, visiting)
        }

        val sourceFunction = sourceType.firFunctionTypeInfoOrNull()
        val targetFunction = targetType.firFunctionTypeInfoOrNull()
        if (sourceFunction != null || targetFunction != null) {
            if (sourceFunction == null || targetFunction == null) {
                return false
            }
            if (sourceFunction.kind != targetFunction.kind || sourceFunction.parameterTypes.size != targetFunction.parameterTypes.size) {
                return false
            }
            return sourceFunction.parameterTypes.zip(targetFunction.parameterTypes).all { (sourceParameter, targetParameter) ->
                canTransport(targetParameter, sourceParameter, visiting)
            } && canTransport(sourceFunction.returnType, targetFunction.returnType, visiting)
        }

        val sourceConstructor = sourceType as? TcType.Constructor ?: return false
        val targetConstructor = targetType as? TcType.Constructor ?: return false
        val visitKey = sourceConstructor.normalizedKey() to targetConstructor.normalizedKey()
        if (!visiting.add(visitKey)) {
            return false
        }
        try {
            val sourceClass = session.regularClassSymbolOrNull(sourceConstructor.classIdOrNull() ?: return false)?.fir ?: return false
            val targetClass = session.regularClassSymbolOrNull(targetConstructor.classIdOrNull() ?: return false)?.fir ?: return false

            sourceClass.transparentValueFieldType(
                concreteType = sourceConstructor,
                accessContext = sourceClass.transportAccessContext(session),
                requireConstructorAccess = false,
            )?.let { sourceFieldType ->
                if (sourceFieldType.normalizedKey() != sourceConstructor.normalizedKey() && canTransport(sourceFieldType, targetConstructor, visiting)) {
                    return true
                }
            }
            targetClass.transparentValueFieldType(
                concreteType = targetConstructor,
                accessContext = targetClass.transportAccessContext(session),
                requireConstructorAccess = true,
            )?.let { targetFieldType ->
                if (targetFieldType.normalizedKey() != targetConstructor.normalizedKey() && canTransport(sourceConstructor, targetFieldType, visiting)) {
                    return true
                }
            }

            canTransportProduct(sourceConstructor, targetConstructor, sourceClass, targetClass, visiting)?.let { return it }
            canTransportSum(sourceConstructor, targetConstructor, sourceClass, targetClass, visiting)?.let { return it }
            return false
        } finally {
            visiting.remove(visitKey)
        }
    }

    private fun canTransportProduct(
        sourceType: TcType.Constructor,
        targetType: TcType.Constructor,
        sourceClass: FirRegularClass,
        targetClass: FirRegularClass,
        visiting: MutableSet<Pair<String, String>>,
    ): Boolean? {
        val sourceInfo =
            sourceClass.transparentProductInfo(
                concreteType = sourceType,
                accessContext = sourceClass.transportAccessContext(session),
                requireConstructorAccess = false,
            ) ?: return null
        val targetInfo =
            targetClass.transparentProductInfo(
                concreteType = targetType,
                accessContext = targetClass.transportAccessContext(session),
                requireConstructorAccess = true,
            ) ?: return null
        if (sourceInfo.isObjectLike && targetInfo.isObjectLike) {
            return true
        }

        val sourceNonUnit = sourceInfo.fields.filterNot(FirTransparentFieldInfo::isUnitLike)
        val targetNonUnit = targetInfo.fields.filterNot(FirTransparentFieldInfo::isUnitLike)
        if (sourceNonUnit.size != targetNonUnit.size) {
            return false
        }

        val positionalWorks =
            targetNonUnit.zip(sourceNonUnit).all { (targetField, sourceField) ->
                canTransport(sourceField.type, targetField.type, visiting)
            }
        if (positionalWorks) {
            return true
        }

        val remaining = sourceNonUnit.toMutableSet()
        for (targetField in targetNonUnit) {
            val viable = remaining.filter { sourceField -> canTransport(sourceField.type, targetField.type, visiting) }
            if (viable.size != 1) {
                return false
            }
            remaining -= viable.single()
        }
        return true
    }

    private fun canTransportSum(
        sourceType: TcType.Constructor,
        targetType: TcType.Constructor,
        sourceClass: FirRegularClass,
        targetClass: FirRegularClass,
        visiting: MutableSet<Pair<String, String>>,
    ): Boolean? {
        if (sourceClass.status.modality != Modality.SEALED || targetClass.status.modality != Modality.SEALED) {
            return null
        }
        val sourceCases = sourceClass.transparentSealedCases(session) ?: return null
        val targetCases = targetClass.transparentSealedCases(session) ?: return null
        if (sourceCases.size != targetCases.size) {
            return false
        }

        val remainingTargets = targetCases.toMutableSet()
        for (sourceCase in sourceCases) {
            val sourceCaseType = TcType.Constructor(sourceCase.classId.asString(), emptyList())
            val viable =
                remainingTargets.filter { targetCase ->
                    canTransport(sourceCaseType, TcType.Constructor(targetCase.classId.asString(), emptyList()), visiting)
                }
            if (viable.size != 1) {
                return false
            }
            remainingTargets -= viable.single()
        }
        return true
    }
}

internal fun FirRegularClass.deriveViaRequests(session: FirSession): List<FirDeriveViaRequest> =
    if (source == null) {
        generatedDerivedMetadata(session)
            .filterIsInstance<GeneratedDerivedMetadata.DeriveVia>()
            .map { metadata ->
                FirDeriveViaRequest(
                    typeclassId = metadata.typeclassId,
                    path =
                        metadata.path.map { segment ->
                            when (segment.kind) {
                                GeneratedDeriveViaPathSegment.Kind.WAYPOINT ->
                                    FirDeriveViaPathSegment.Waypoint(segment.classId)

                                GeneratedDeriveViaPathSegment.Kind.PINNED_ISO ->
                                    FirDeriveViaPathSegment.PinnedIso(segment.classId)
                            }
                        },
                )
            }
    } else {
        deriveViaAnnotationParseResults(session)
            .mapNotNull { result -> (result as? FirDeriveViaAnnotationParseResult.Valid)?.request }
    }

internal fun FirRegularClass.deriveViaAnnotationParseResults(session: FirSession): List<FirDeriveViaAnnotationParseResult> =
    if (source == null) {
        emptyList()
    } else {
        resolvedRepeatableAnnotationsByClassId(
            annotationClassId = DERIVE_VIA_ANNOTATION_CLASS_ID,
            containerClassId = DERIVE_VIA_ANNOTATION_CONTAINER_CLASS_ID,
            session = session,
        ).mapNotNull { annotation ->
            annotation.parseDeriveViaAnnotation(owner = this, session = session)
        }
    }

internal fun FirRegularClass.validateDeriveViaTransportability(session: FirSession): String? {
    val transported = typeParameters.lastOrNull()?.symbol ?: return "DeriveVia requires a typeclass with a final transported type parameter"
    val classParameters =
        typeParameters.mapIndexed { index, typeParameter ->
            TcTypeParameter(
                id = "${symbol.classId.asString()}#$index",
                displayName = typeParameter.symbol.name.asString(),
            )
        }
    val typeParameterBySymbol = typeParameters.zip(classParameters).associate { (typeParameter, parameter) ->
        typeParameter.symbol to parameter
    }
    val classOpaqueParameters =
        typeParameters.mapTo(linkedSetOf()) { typeParameter -> typeParameter.symbol }.apply {
            remove(transported)
        }

    for (property in declarations.filterIsInstance<FirProperty>()) {
        val getter = property.getter ?: continue
        val message =
            getter.returnTypeRef.coneType.transportabilityViolation(
                transported = transported,
                typeParameterBySymbol = typeParameterBySymbol,
                opaqueParameters = classOpaqueParameters,
                session = session,
            )
        if (message != null) {
            return message
        }
    }

    for (function in declarations.filterIsInstance<FirTypeclassFunctionDeclaration>()) {
        val methodOpaqueParameters = classOpaqueParameters.toMutableSet()
        methodOpaqueParameters += function.typeParameters.mapTo(linkedSetOf()) { typeParameter -> typeParameter.symbol }
        if (function.typeParameters.any { typeParameter ->
                typeParameter.bounds.any { bound ->
                    bound.coneType.mentionsTransportedType(
                        transported = transported,
                        opaqueParameters = methodOpaqueParameters,
                    )
                }
            }
        ) {
            return "DeriveVia does not support method type-parameter bounds that mention the transported type parameter"
        }
        function.receiverParameter?.typeRef?.coneType?.let { receiverType ->
            val message =
                receiverType.transportabilityViolation(
                    transported = transported,
                    typeParameterBySymbol = typeParameterBySymbol + function.typeParameters.toMethodTypeParameterModels(function),
                    opaqueParameters = methodOpaqueParameters,
                    session = session,
                )
            if (message != null) {
                return message
            }
        }
        function.contextParameters.forEach { parameter ->
            if (parameter.valueParameterKind == FirValueParameterKind.ContextParameter &&
                parameter.returnTypeRef.coneType.mentionsTransportedType(
                    transported = transported,
                    opaqueParameters = methodOpaqueParameters,
                )
            ) {
                return "DeriveVia does not support context parameters that mention the transported type parameter"
            }
        }
        function.valueParameters.forEach { parameter ->
            val message =
                parameter.returnTypeRef.coneType.transportabilityViolation(
                    transported = transported,
                    typeParameterBySymbol = typeParameterBySymbol + function.typeParameters.toMethodTypeParameterModels(function),
                    opaqueParameters = methodOpaqueParameters,
                    session = session,
                )
            if (message != null) {
                return message
            }
        }
        val returnMessage =
            function.returnTypeRef.coneType.transportabilityViolation(
                transported = transported,
                typeParameterBySymbol = typeParameterBySymbol + function.typeParameters.toMethodTypeParameterModels(function),
                opaqueParameters = methodOpaqueParameters,
                session = session,
            )
        if (returnMessage != null) {
            return returnMessage
        }
    }
    return null
}

internal fun FirRegularClass.matchingShapeDerivedGoalMatches(
    goal: TcType.Constructor,
    session: FirSession,
    configuration: TypeclassConfiguration,
): List<FirShapeDerivedGoalMatch> {
    val directIds =
        if (source == null) {
            generatedDerivedMetadata(session)
                .filterIsInstance<GeneratedDerivedMetadata.Derive>()
                .mapTo(linkedSetOf()) { metadata -> metadata.typeclassId.asString() }
        } else {
            if (!supportsDeriveShape()) {
                return emptyList()
            }
            derivedTypeclassIds(session).filterTo(linkedSetOf()) { candidate ->
                supportsDerivationForTypeclass(candidate, session)
            }
        }
    if (directIds.isEmpty()) {
        return emptyList()
    }
    val requiredContract = requiredDeriveMethodContractForDeriveShape()
    return buildList {
        directIds.forEach { directTypeclassId ->
            val directClassId = runCatching { ClassId.fromString(directTypeclassId) }.getOrNull() ?: return@forEach
            if (source != null && requiredContract != null && typeclassCompanionResolveDeriveMethod(directClassId, requiredContract, session) == null) {
                return@forEach
            }
            val expansions = expandedDerivedTypeclassHeads(directTypeclassId, session, configuration)
            val directTypeParameters = expansions.firstOrNull()?.directTypeParameters.orEmpty()
            if (directTypeParameters.size != 1) {
                return@forEach
            }
            val bindableIds = directTypeParameters.mapTo(linkedSetOf(), TcTypeParameter::id)
            val transportedParameter = directTypeParameters.single()
            expansions.forEach { expansion ->
                if (expansion.head.classifierId != goal.classifierId) {
                    return@forEach
                }
                val bindings = unifyTypes(expansion.head, goal, bindableVariableIds = bindableIds) ?: return@forEach
                val targetType = bindings[transportedParameter.id] as? TcType.Constructor ?: return@forEach
                if (targetType.isNullable) {
                    return@forEach
                }
                add(
                    FirShapeDerivedGoalMatch(
                        directTypeclassId = directTypeclassId,
                        targetType = targetType,
                    ),
                )
            }
        }
    }.distinct()
}

private fun List<FirTypeParameter>.toMethodTypeParameterModels(
    function: FirTypeclassFunctionDeclaration,
): Map<FirTypeParameterSymbol, TcTypeParameter> =
    mapIndexed { index, typeParameter ->
        typeParameter.symbol to
            TcTypeParameter(
                id = "${function.symbol.callableId}#$index",
                displayName = typeParameter.symbol.name.asString(),
            )
    }.toMap()

private fun FirAnnotation.parseDeriveViaAnnotation(
    owner: FirRegularClass,
    session: FirSession,
): FirDeriveViaAnnotationParseResult? {
    if (owner.typeParameters.isNotEmpty()) {
        return FirDeriveViaAnnotationParseResult.Invalid(
            annotation = this,
            message = "@DeriveVia only supports monomorphic classes for now",
        )
    }
    val typeclassId = getClassIdArgument("typeclass", session) ?: return null
    val typeclassInterface = session.regularClassSymbolOrNull(typeclassId)?.fir
    if (typeclassInterface?.hasAnnotation(TYPECLASS_ANNOTATION_CLASS_ID, session) != true) {
        return FirDeriveViaAnnotationParseResult.Invalid(
            annotation = this,
            message = "${typeclassId.shortClassName.asString()} is not annotated with @Typeclass",
        )
    }
    val pathArguments = getArgumentExpressions("path")
    if (pathArguments.isEmpty()) {
        return FirDeriveViaAnnotationParseResult.Invalid(
            annotation = this,
            message = "Cannot derive via an empty path",
        )
    }
    val path = mutableListOf<FirDeriveViaPathSegment>()
    for (expression in pathArguments) {
        val classId =
            expression.asReferencedClassId()
                ?: return FirDeriveViaAnnotationParseResult.Invalid(
                    annotation = this,
                    message = "DeriveVia path elements must be class literals; the path contained an unparsable element",
                )
        val symbol = session.regularClassSymbolOrNull(classId)
        if (symbol?.implementsInterface(ISO_CLASS_ID, session, linkedSetOf()) == true) {
            if (symbol.classKind != ClassKind.OBJECT) {
                return FirDeriveViaAnnotationParseResult.Invalid(
                    annotation = this,
                    message = "Pinned Iso segments must name object singletons",
                )
            }
            path += FirDeriveViaPathSegment.PinnedIso(classId)
        } else {
            path += FirDeriveViaPathSegment.Waypoint(classId)
        }
    }
    if (typeclassTypeParameterCount(typeclassId, session)?.let { it >= 1 } != true) {
        return FirDeriveViaAnnotationParseResult.Invalid(
            annotation = this,
            message = "DeriveVia requires a typeclass with at least one type parameter",
        )
    }
    typeclassInterface.validateDeriveViaTransportability(session)?.let { message ->
        return FirDeriveViaAnnotationParseResult.Invalid(
            annotation = this,
            message = message,
        )
    }
    return FirDeriveViaAnnotationParseResult.Valid(
        annotation = this,
        request = FirDeriveViaRequest(typeclassId = typeclassId, path = path),
    )
}

private fun FirAnnotation.getArgumentExpressions(
    name: String,
): List<FirExpression> {
    val argument = findArgumentByName(Name.identifier(name)) ?: return emptyList()
    return argument.unwrapVarargValue()
        .ifEmpty { listOf(argument) }
}

private fun FirExpression.asReferencedClassId(): ClassId? =
    when (this) {
        is FirGetClassCall ->
            when (val classExpression = argument) {
                is FirResolvedQualifier -> classExpression.classId
                is FirClassReferenceExpression -> classExpression.classTypeRef.coneType.lowerBoundIfFlexible().classId
                else -> null
            }

        is FirResolvedQualifier -> classId
        is FirClassReferenceExpression -> classTypeRef.coneType.lowerBoundIfFlexible().classId
        else -> null
    }

private fun FirRegularClass.isoEndpoints(session: FirSession): Pair<TcType.Constructor, TcType.Constructor>? {
    val superType =
        declaredOrResolvedSuperTypes()
            .firstOrNull { candidate -> candidate.lowerBoundIfFlexible().classId == ISO_CLASS_ID }
            as? ConeClassLikeType ?: return null
    val leftType = coneTypeToModel(superType.typeArguments.getOrNull(0)?.type ?: return null, emptyMap()) as? TcType.Constructor ?: return null
    val rightType = coneTypeToModel(superType.typeArguments.getOrNull(1)?.type ?: return null, emptyMap()) as? TcType.Constructor ?: return null
    return leftType to rightType
}

private fun ConeKotlinType.transportabilityViolation(
    transported: FirTypeParameterSymbol,
    typeParameterBySymbol: Map<FirTypeParameterSymbol, TcTypeParameter>,
    opaqueParameters: Set<FirTypeParameterSymbol>,
    session: FirSession,
    visiting: MutableSet<String> = linkedSetOf(),
): String? {
    if (!mentionsTransportedType(transported, opaqueParameters)) {
        return null
    }
    val rendered = lowerBoundIfFlexible().toString()
    if (rendered.contains("&")) {
        return "DeriveVia does not support definitely-non-null or intersection member types"
    }
    val model = coneTypeToModel(this, typeParameterBySymbol)
    if (model == null) {
        return "DeriveVia only supports first-order, function, or structural product/sum member types"
    }
    return model.transportabilityViolation(
        transportedId = typeParameterBySymbol[transported]?.id ?: return "DeriveVia requires a typeclass with a final transported type parameter",
        session = session,
        visiting = visiting,
    )
}

private fun ConeKotlinType.mentionsTransportedType(
    transported: FirTypeParameterSymbol,
    opaqueParameters: Set<FirTypeParameterSymbol>,
): Boolean {
    val lowered = approximateIntegerLiteralType().lowerBoundIfFlexible()
    return when (lowered) {
        is ConeTypeParameterType -> lowered.lookupTag.typeParameterSymbol == transported && lowered.lookupTag.typeParameterSymbol !in opaqueParameters
        is ConeClassLikeType ->
            lowered.typeArguments.any { argument ->
                argument.type?.mentionsTransportedType(transported, opaqueParameters) == true
            }

        else ->
            lowered.toString().containsTypeParameterIdentifier(
                transportedName = transported.name.asString(),
                opaqueNames = opaqueParameters.mapTo(linkedSetOf()) { symbol -> symbol.name.asString() },
            )
    }
}

private fun TcType.transportabilityViolation(
    transportedId: String,
    session: FirSession,
    visiting: MutableSet<String>,
): String? {
    if (!references(transportedId)) {
        return null
    }
    return when (this) {
        TcType.StarProjection -> null
        is TcType.Projected -> type.transportabilityViolation(transportedId, session, visiting)
        is TcType.Variable -> if (id == transportedId) null else null
        is TcType.Constructor -> {
            withoutNullability()?.let { inner ->
                return inner.transportabilityViolation(transportedId, session, visiting)
            }
            firFunctionTypeInfoOrNull()?.let { functionInfo ->
                functionInfo.parameterTypes.forEach { parameterType ->
                    val message = parameterType.transportabilityViolation(transportedId, session, visiting)
                    if (message != null) {
                        return message
                    }
                }
                return functionInfo.returnType.transportabilityViolation(transportedId, session, visiting)
            }
            val classId = classIdOrNull() ?: return "DeriveVia only supports structural class types in transported positions"
            val klass = session.regularClassSymbolOrNull(classId)?.fir ?: return "DeriveVia only supports named structural class types in transported positions"
            val visitKey = "${classId.asString()}:${render()}"
            if (!visiting.add(visitKey)) {
                return "DeriveVia does not support recursive nominal transport shapes"
            }
            try {
                klass.transparentValueFieldType(
                    concreteType = this,
                    accessContext = klass.transportAccessContext(session),
                    requireConstructorAccess = true,
                )?.let { fieldType ->
                    return fieldType.transportabilityViolation(transportedId, session, visiting)
                }
                klass.transparentProductInfo(
                    concreteType = this,
                    accessContext = klass.transportAccessContext(session),
                    requireConstructorAccess = true,
                )?.let { productInfo ->
                    productInfo.fields.forEach { field ->
                        val message = field.type.transportabilityViolation(transportedId, session, visiting)
                        if (message != null) {
                            return message
                        }
                    }
                    return null
                }
                val sealedCases = klass.transparentSealedCases(session)
                if (sealedCases != null) {
                    sealedCases.forEach { case ->
                        val message =
                            TcType.Constructor(case.classId.asString(), emptyList())
                                .transportabilityViolation(transportedId, session, visiting)
                        if (message != null) {
                            return message
                        }
                    }
                    return null
                }
                "DeriveVia does not support opaque or mutable nominal containers in transported positions"
            } finally {
                visiting.remove(visitKey)
            }
        }
    }
}

private fun FirRegularClass.transparentValueFieldType(
    concreteType: TcType.Constructor,
    accessContext: TransportSyntheticAccessContext,
    requireConstructorAccess: Boolean,
): TcType? {
    if (!isTransparentValueClass()) {
        return null
    }
    if (!accessContext.allowsTransportVisibility(status.visibility.toTransportSyntheticVisibility())) {
        return null
    }
    if (requireConstructorAccess) {
        val constructor =
            declarations.filterIsInstance<FirConstructor>().singleOrNull { candidate -> candidate.isPrimary }
                ?: declarations.filterIsInstance<FirConstructor>().singleOrNull()
                ?: return null
        if (!accessContext.allowsTransportVisibility(constructor.status.visibility.toTransportSyntheticVisibility())) {
            return null
        }
    }
    val property =
        declarations
            .filterIsInstance<FirProperty>()
            .singleOrNull { candidate ->
                candidate.backingField != null &&
                    candidate.getter != null &&
                    candidate.setter == null &&
                    accessContext.allowsTransportVisibility(candidate.status.visibility.toTransportSyntheticVisibility()) &&
                    accessContext.allowsTransportVisibility(
                        (candidate.getter ?: return@singleOrNull false).status.visibility.toTransportSyntheticVisibility(),
                    )
            } ?: return null
    val getter = property.getter ?: return null
    return getter.returnTypeRef.coneType.toConcreteType(this, concreteType)
}

private fun FirRegularClass.transparentProductInfo(
    concreteType: TcType.Constructor,
    accessContext: TransportSyntheticAccessContext,
    requireConstructorAccess: Boolean,
): FirTransparentProductInfo? {
    if (!accessContext.allowsTransportVisibility(status.visibility.toTransportSyntheticVisibility())) {
        return null
    }
    if (classKind == ClassKind.OBJECT) {
        return FirTransparentProductInfo(isObjectLike = true, fields = emptyList())
    }
    if (!status.isData || classKind == ClassKind.ENUM_CLASS || hasAnonymousInitializer()) {
        return null
    }
    val constructors = declarations.filterIsInstance<FirConstructor>()
    val primary = constructors.singleOrNull { constructor -> constructor.isPrimary } ?: return null
    if (constructors.size != 1) {
        return null
    }
    if (requireConstructorAccess &&
        !accessContext.allowsTransportVisibility(primary.status.visibility.toTransportSyntheticVisibility())
    ) {
        return null
    }
    val storedProperties =
        declarations.filterIsInstance<FirProperty>().filter { property ->
            property.backingField != null
        }
    if (storedProperties.any { property -> property.delegate != null }) {
        return null
    }
    if (storedProperties.any { property ->
            property.setter != null ||
                !accessContext.allowsTransportVisibility(property.status.visibility.toTransportSyntheticVisibility()) ||
                !accessContext.allowsTransportVisibility(
                    (property.getter?.status?.visibility ?: return null).toTransportSyntheticVisibility(),
                )
        }
    ) {
        return null
    }
    val properties = storedProperties.filter { property -> property.getter != null }
    if (properties.size != primary.valueParameters.size) {
        return null
    }
    val primaryParameterNames = primary.valueParameters.map { parameter -> parameter.name }.toSet()
    if (properties.any { property -> property.name !in primaryParameterNames }) {
        return null
    }
    val fields =
        primary.valueParameters.map { parameter ->
            val property = properties.singleOrNull { candidate -> candidate.name == parameter.name } ?: return null
            val fieldType = property.getter?.returnTypeRef?.coneType?.toConcreteType(this, concreteType) ?: return null
            FirTransparentFieldInfo(
                type = fieldType,
                isUnitLike = fieldType.isUnitLike(),
            )
        }
    return FirTransparentProductInfo(
        isObjectLike = false,
        fields = fields,
    )
}

private fun FirRegularClass.transparentSealedCases(
    session: FirSession,
): List<FirRegularClassSymbol>? {
    if (status.modality != Modality.SEALED || typeParameters.isNotEmpty()) {
        return null
    }
    val subclasses =
        getSealedClassInheritors(session)
            .mapNotNull { classId -> session.regularClassSymbolOrNull(classId) }
    if (subclasses.isEmpty()) {
        return null
    }
    return subclasses.takeIf { cases ->
        cases.all { case ->
            case.fir.classKind == ClassKind.OBJECT ||
                (case.fir.status.isData && case.fir.typeParameters.isEmpty() && !case.fir.hasAnonymousInitializer())
        }
    }
}

private fun FirRegularClass.isTransparentValueClass(): Boolean =
    status.isValue &&
        typeParameters.isEmpty() &&
        !hasAnonymousInitializer() &&
        declarations.filterIsInstance<FirConstructor>().all(FirConstructor::isPrimary)

private fun FirRegularClass.transportAccessContext(session: FirSession): TransportSyntheticAccessContext =
    if (
        runCatching { session.firProvider.symbolProvider.getClassLikeSymbolByClassId(symbol.classId) }.getOrNull() != null ||
        runCatching { session.firProvider.getFirClassifierContainerFileIfAny(symbol.classId) }.getOrNull() != null
    ) {
        TransportSyntheticAccessContext.SAME_MODULE_SOURCE
    } else {
        TransportSyntheticAccessContext.DEPENDENCY_BINARY
    }

private fun FirRegularClass.hasAnonymousInitializer(): Boolean =
    declarations.any { declaration -> declaration is FirAnonymousInitializer }

private fun ConeKotlinType.toConcreteType(
    owner: FirRegularClass,
    concreteType: TcType.Constructor,
): TcType? {
    val typeParameters =
        owner.typeParameters.mapIndexed { index, typeParameter ->
            typeParameter.symbol to
                TcTypeParameter(
                    id = "${owner.symbol.classId.asString()}#$index",
                    displayName = typeParameter.symbol.name.asString(),
                )
        }
    if (typeParameters.size != concreteType.arguments.size) {
        return null
    }
    val parameterModels = typeParameters.toMap()
    val bindings =
        typeParameters.mapIndexed { index, (_, parameter) ->
            parameter.id to concreteType.arguments[index]
        }.toMap()
    return coneTypeToModel(this, parameterModels)?.substituteType(bindings)
}

private data class FirTransparentProductInfo(
    val isObjectLike: Boolean,
    val fields: List<FirTransparentFieldInfo>,
)

private data class FirTransparentFieldInfo(
    val type: TcType,
    val isUnitLike: Boolean,
)

private data class FirFunctionTypeInfo(
    val kind: String,
    val parameterTypes: List<TcType>,
    val returnType: TcType,
)

private fun TcType.firFunctionTypeInfoOrNull(): FirFunctionTypeInfo? {
    val constructor = this as? TcType.Constructor ?: return null
    val normalizedClassifier = constructor.classifierId.replace('/', '.')
    val kind =
        when {
            normalizedClassifier.startsWith("kotlin.Function") -> "function"
            normalizedClassifier.startsWith("kotlin.SuspendFunction") -> "suspend-function"
            else -> return null
        }
    if (constructor.arguments.isEmpty()) {
        return null
    }
    return FirFunctionTypeInfo(
        kind = kind,
        parameterTypes = constructor.arguments.dropLast(1),
        returnType = constructor.arguments.last(),
    )
}

private fun TcType.isUnitLike(): Boolean =
    this is TcType.Constructor &&
        classifierId == ClassId.topLevel(org.jetbrains.kotlin.name.FqName("kotlin.Unit")).asString() &&
        !isNullable

private fun TcType.classIdOrNull(): ClassId? =
    (this as? TcType.Constructor)?.let { constructor ->
        runCatching { ClassId.fromString(constructor.classifierId) }.getOrNull()
    }

private fun TcType.withoutNullability(): TcType? =
    when (this) {
        TcType.StarProjection -> null
        is TcType.Projected -> type.withoutNullability()?.let { inner -> TcType.Projected(variance, inner) }
        is TcType.Variable -> if (isNullable) copy(isNullable = false) else null
        is TcType.Constructor -> if (isNullable) copy(isNullable = false) else null
    }

private fun String.containsTypeParameterIdentifier(
    transportedName: String,
    opaqueNames: Set<String>,
): Boolean =
    Regex("""[A-Za-z_][A-Za-z0-9_]*""")
        .findAll(this)
        .map { match -> match.value }
        .any { identifier ->
            identifier == transportedName && identifier !in opaqueNames
        }
