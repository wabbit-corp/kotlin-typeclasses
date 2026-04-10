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
import org.jetbrains.kotlin.fir.declarations.FirDeclarationStatus
import org.jetbrains.kotlin.fir.declarations.FirField
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
import org.jetbrains.kotlin.fir.types.ConeDefinitelyNotNullType
import org.jetbrains.kotlin.fir.types.ConeIntersectionType
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

internal data class FirDeriveEquivRequest(
    val otherClassId: ClassId,
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

internal sealed interface FirDeriveEquivAnnotationParseResult {
    val annotation: FirAnnotation

    data class Valid(
        override val annotation: FirAnnotation,
        val request: FirDeriveEquivRequest,
    ) : FirDeriveEquivAnnotationParseResult

    data class Invalid(
        override val annotation: FirAnnotation,
        val message: String,
    ) : FirDeriveEquivAnnotationParseResult
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
    ): TcType.Constructor? =
        resolveViaPathDetailed(sourceType, path).getOrNull()

    fun resolveViaPathDetailed(
        sourceType: TcType.Constructor,
        path: List<FirDeriveViaPathSegment>,
    ): Result<TcType.Constructor> {
        if (path.isEmpty()) {
            return Result.failure(IllegalArgumentException("DeriveVia requires a non-empty path"))
        }

        var current = sourceType
        for (segment in path) {
            when (segment) {
                is FirDeriveViaPathSegment.Waypoint -> {
                    val waypointClass =
                        session.regularClassSymbolOrNull(segment.classId)?.fir
                            ?: return Result.failure(
                                IllegalArgumentException("Could not resolve DeriveVia waypoint ${segment.classId.asString()}"),
                            )
                    if (waypointClass.typeParameters.isNotEmpty()) {
                        return Result.failure(
                            IllegalArgumentException(
                                "Generic DeriveVia waypoints are not supported yet: ${segment.classId.asString()}",
                            ),
                        )
                    }
                    val waypointType = TcType.Constructor(segment.classId.asString(), emptyList())
                    if (!planEquiv(current, waypointType)) {
                        return Result.failure(
                            IllegalArgumentException(
                                "Cannot derive via ${segment.classId.asString()} from ${current.render()}",
                            ),
                        )
                    }
                    current = waypointType
                }

                is FirDeriveViaPathSegment.PinnedIso -> {
                    val isoClass =
                        session.regularClassSymbolOrNull(segment.classId)?.fir
                            ?: return Result.failure(
                                IllegalArgumentException("Could not resolve pinned Iso ${segment.classId.asString()}"),
                            )
                    if (isoClass.classKind != ClassKind.OBJECT) {
                        return Result.failure(
                            IllegalArgumentException("Pinned Iso path segments must name object singletons"),
                        )
                    }
                    val endpoints =
                        isoClass.isoEndpoints(session)
                            ?: return Result.failure(
                                IllegalArgumentException(
                                    "Pinned Iso ${segment.classId.asString()} must define one exact Iso to/from override pair",
                                ),
                            )
                    val leftReachable = planEquiv(current, endpoints.first)
                    val rightReachable = planEquiv(current, endpoints.second)
                    current =
                        when {
                            leftReachable && rightReachable ->
                                return Result.failure(
                                    IllegalStateException(
                                        "Pinned Iso ${segment.classId.asString()} is ambiguous because both endpoints are reachable from ${current.render()}",
                                    ),
                                )

                            leftReachable -> endpoints.second
                            rightReachable -> endpoints.first
                            else ->
                                return Result.failure(
                                    IllegalArgumentException(
                                        "Pinned Iso ${segment.classId.asString()} is disconnected from ${current.render()}",
                                    ),
                                )
                        }
                }
            }
        }
        return Result.success(current)
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

        return canonicalTransportAssignments(
            sources = sourceNonUnit,
            targets = targetNonUnit,
            sameNominalShape = sourceClass.symbol.classId == targetClass.symbol.classId,
            sourceIdentity = FirTransparentFieldInfo::identity,
            targetIdentity = FirTransparentFieldInfo::identity,
        ) { sourceField, targetField ->
            sourceField.takeIf { canTransport(sourceField.type, targetField.type, visiting) }
        } != null
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
        val sourceCases = sourceClass.transparentSealedCases(session, sourceClass.transportAccessContext(session)) ?: return null
        val targetCases = targetClass.transparentSealedCases(session, targetClass.transportAccessContext(session)) ?: return null
        if (sourceCases.size != targetCases.size) {
            return false
        }

        return canonicalTransportAssignments(
            sources = sourceCases,
            targets = targetCases,
            sameNominalShape = sourceClass.symbol.classId == targetClass.symbol.classId,
            sourceIdentity = FirRegularClassSymbol::classId,
            targetIdentity = FirRegularClassSymbol::classId,
        ) { sourceCase, targetCase ->
            val sourceCaseType = TcType.Constructor(sourceCase.classId.asString(), emptyList())
            val targetCaseType = TcType.Constructor(targetCase.classId.asString(), emptyList())
            sourceCase.takeIf { canTransport(sourceCaseType, targetCaseType, visiting) }
        } != null
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

internal fun FirRegularClass.deriveEquivRequestsValidated(session: FirSession): List<FirDeriveEquivRequest> =
    if (source == null) {
        generatedDerivedMetadata(session)
            .filterIsInstance<GeneratedDerivedMetadata.DeriveEquiv>()
            .map { metadata -> FirDeriveEquivRequest(metadata.otherClassId) }
    } else {
        deriveEquivAnnotationParseResults(session)
            .mapNotNull { result -> (result as? FirDeriveEquivAnnotationParseResult.Valid)?.request }
    }

internal fun FirRegularClass.deriveViaAnnotationParseResults(session: FirSession): List<FirDeriveViaAnnotationParseResult> =
    if (source == null) {
        emptyList()
    } else {
        resolvedRepeatableAnnotationsByClassId(
            annotationClassId = DERIVE_VIA_ANNOTATION_CLASS_ID,
            containerClassId = DERIVE_VIA_ANNOTATION_CONTAINER_CLASS_ID,
            session = session,
        ).map { annotation ->
            annotation.parseDeriveViaAnnotation(owner = this, session = session)
        }
    }

internal fun FirRegularClass.deriveEquivAnnotationParseResults(session: FirSession): List<FirDeriveEquivAnnotationParseResult> =
    if (source == null) {
        emptyList()
    } else {
        resolvedRepeatableAnnotationsByClassId(
            annotationClassId = DERIVE_EQUIV_ANNOTATION_CLASS_ID,
            containerClassId = DERIVE_EQUIV_ANNOTATION_CONTAINER_CLASS_ID,
            session = session,
        ).map { annotation ->
            annotation.parseDeriveEquivAnnotation(owner = this, session = session)
        }
    }

internal fun FirRegularClass.validateDeriveViaTransportability(session: FirSession): String? {
    deriveViaSubclassabilityViolation(session)?.let { return it }
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

    val concreteType = defaultConcreteType()
    val transportedId = typeParameterBySymbol[transported]?.id ?: return "DeriveVia requires a typeclass with a final transported type parameter"
    return validateInheritedForwardedTypeclassSurface(
        session = session,
        concreteType = concreteType,
        transportedId = transportedId,
        seenPropertyKeys = linkedSetOf(),
        seenFunctionKeys = linkedSetOf(),
        visited = linkedSetOf(),
    )
}

private fun FirRegularClass.deriveViaSubclassabilityViolation(session: FirSession): String? {
    if (classKind == ClassKind.INTERFACE) {
        return null
    }
    if (status.modality == Modality.FINAL) {
        return DERIVE_VIA_SUBCLASSABLE_TYPECLASS_HEAD_MESSAGE
    }
    val accessContext = transportAccessContext(session)
    val constructors = declarations.filterIsInstance<FirConstructor>()
    if (constructors.isEmpty()) {
        return if (accessContext.allowsDeriveViaSuperclassConstructorVisibility(status.visibility.toTransportSyntheticVisibility())) {
            null
        } else {
            DERIVE_VIA_SUBCLASSABLE_TYPECLASS_HEAD_MESSAGE
        }
    }
    if (constructors.any { constructor ->
            constructor.valueParameters.isEmpty() &&
                accessContext.allowsDeriveViaSuperclassConstructorVisibility(
                    constructor.status.visibility.toTransportSyntheticVisibility(),
                )
        }
    ) {
        return null
    }
    return DERIVE_VIA_SUBCLASSABLE_TYPECLASS_HEAD_MESSAGE
}

private fun FirRegularClass.defaultConcreteType(): TcType.Constructor =
    TcType.Constructor(
        classifierId = symbol.classId.asString(),
        arguments =
            typeParameters.mapIndexed { index, typeParameter ->
                TcType.Variable(
                    id = "${symbol.classId.asString()}#$index",
                    displayName = typeParameter.symbol.name.asString(),
                )
            },
    )

private fun FirRegularClass.validateInheritedForwardedTypeclassSurface(
    session: FirSession,
    concreteType: TcType.Constructor,
    transportedId: String,
    seenPropertyKeys: MutableSet<String>,
    seenFunctionKeys: MutableSet<String>,
    visited: MutableSet<String>,
): String? {
    val visitKey = "${symbol.classId.asString()}:${concreteType.render()}"
    if (!visited.add(visitKey)) {
        return null
    }

    for (property in declarations.filterIsInstance<FirProperty>()) {
        val getter = property.getter ?: continue
        val signatureKey = property.signatureKey(this, concreteType, getter)
        if (!seenPropertyKeys.add(signatureKey)) {
            continue
        }
        if (!getter.status.isForwardedDeriveViaMember()) {
            continue
        }
        val message =
            getter.returnTypeRef.coneType.transportabilityViolationInOwnerContext(
                owner = this,
                concreteType = concreteType,
                transportedId = transportedId,
                session = session,
            )
        if (message != null) {
            return message
        }
        val setter = property.setter
        if (setter != null && setter.status.isForwardedDeriveViaMember()) {
            val setterValueType =
                setter.valueParameters.singleOrNull()?.returnTypeRef?.coneType
                    ?: getter.returnTypeRef.coneType
            val setterMessage =
                setterValueType.transportabilityViolationInOwnerContext(
                    owner = this,
                    concreteType = concreteType,
                    transportedId = transportedId,
                    session = session,
                )
            if (setterMessage != null) {
                return setterMessage
            }
        }
    }

    for (function in declarations.filterIsInstance<FirTypeclassFunctionDeclaration>()) {
        val signatureKey = function.signatureKey(this, concreteType)
        if (!seenFunctionKeys.add(signatureKey)) {
            continue
        }
        if (!function.status.isForwardedDeriveViaMember()) {
            continue
        }
        val methodTypeParameters = function.typeParameters.toMethodTypeParameterModels(function)
        if (function.typeParameters.any { typeParameter ->
                typeParameter.bounds.any { bound ->
                    bound.coneType.referencesTransportedTypeInOwnerContext(
                        owner = this,
                        concreteType = concreteType,
                        transportedId = transportedId,
                        additionalTypeParameters = methodTypeParameters,
                    )
                }
            }
        ) {
            return "DeriveVia does not support method type-parameter bounds that mention the transported type parameter"
        }
        function.receiverParameter?.typeRef?.coneType?.let { receiverType ->
            val message =
                receiverType.transportabilityViolationInOwnerContext(
                    owner = this,
                    concreteType = concreteType,
                    transportedId = transportedId,
                    session = session,
                    additionalTypeParameters = methodTypeParameters,
                )
            if (message != null) {
                return message
            }
        }
        for (parameter in function.contextParameters) {
            if (parameter.valueParameterKind == FirValueParameterKind.ContextParameter &&
                parameter.returnTypeRef.coneType.referencesTransportedTypeInOwnerContext(
                    owner = this,
                    concreteType = concreteType,
                    transportedId = transportedId,
                    additionalTypeParameters = methodTypeParameters,
                )
            ) {
                return "DeriveVia does not support context parameters that mention the transported type parameter"
            }
        }
        for (parameter in function.valueParameters) {
            val message =
                parameter.returnTypeRef.coneType.transportabilityViolationInOwnerContext(
                    owner = this,
                    concreteType = concreteType,
                    transportedId = transportedId,
                    session = session,
                    additionalTypeParameters = methodTypeParameters,
                )
            if (message != null) {
                return message
            }
        }
        val returnMessage =
            function.returnTypeRef.coneType.transportabilityViolationInOwnerContext(
                owner = this,
                concreteType = concreteType,
                transportedId = transportedId,
                session = session,
                additionalTypeParameters = methodTypeParameters,
            )
        if (returnMessage != null) {
            return returnMessage
        }
    }

    for (superType in declaredOrResolvedSuperTypes()) {
        val superConcreteType = superType.toConcreteType(this, concreteType) as? TcType.Constructor ?: continue
        val superClassId = superConcreteType.classIdOrNull() ?: continue
        val superClass = session.regularClassSymbolOrNull(superClassId)?.fir ?: continue
        val message =
            superClass.validateInheritedForwardedTypeclassSurface(
                session = session,
                concreteType = superConcreteType,
                transportedId = transportedId,
                seenPropertyKeys = seenPropertyKeys,
                seenFunctionKeys = seenFunctionKeys,
                visited = visited,
            )
        if (message != null) {
            return message
        }
    }
    return null
}

private fun FirDeclarationStatus.isForwardedDeriveViaMember(): Boolean =
    visibility != Visibilities.Private &&
        visibility != Visibilities.PrivateToThis &&
        modality != Modality.FINAL

private fun FirProperty.signatureKey(
    owner: FirRegularClass,
    concreteType: TcType.Constructor,
    getter: org.jetbrains.kotlin.fir.declarations.FirPropertyAccessor,
): String =
    buildString {
        append("property:")
        append(name.asString())
        append(":")
        append(getter.returnTypeRef.coneType.signatureRenderInOwnerContext(owner, concreteType))
    }

private fun FirTypeclassFunctionDeclaration.signatureKey(
    owner: FirRegularClass,
    concreteType: TcType.Constructor,
): String {
    val methodTypeParameters = typeParameters.toMethodTypeParameterModels(this)
    return buildString {
        append("function:")
        append(name.asString())
        append(":suspend=")
        append(status.isSuspend)
        append(":typeParams=")
        append(typeParameters.size)
        append(":ext=")
        append(
            receiverParameter?.typeRef?.coneType?.signatureRenderInOwnerContext(
                owner = owner,
                concreteType = concreteType,
                additionalTypeParameters = methodTypeParameters,
            ) ?: "-",
        )
        (contextParameters + valueParameters)
            .forEach { parameter ->
                append(":")
                append(parameter.valueParameterKind.name)
                append("=")
                append(
                    parameter.returnTypeRef.coneType.signatureRenderInOwnerContext(
                        owner = owner,
                        concreteType = concreteType,
                        additionalTypeParameters = methodTypeParameters,
                    ),
                )
            }
        append(":ret=")
        append(
            returnTypeRef.coneType.signatureRenderInOwnerContext(
                owner = owner,
                concreteType = concreteType,
                additionalTypeParameters = methodTypeParameters,
            ),
        )
    }
}

private fun ConeKotlinType.signatureRenderInOwnerContext(
    owner: FirRegularClass,
    concreteType: TcType.Constructor,
    additionalTypeParameters: Map<FirTypeParameterSymbol, TcTypeParameter> = emptyMap(),
): String =
    toConcreteType(
        owner = owner,
        concreteType = concreteType,
        additionalTypeParameters = additionalTypeParameters,
    )?.render() ?: approximateIntegerLiteralType().lowerBoundIfFlexible().toString()

private fun ConeKotlinType.referencesTransportedTypeInOwnerContext(
    owner: FirRegularClass,
    concreteType: TcType.Constructor,
    transportedId: String,
    additionalTypeParameters: Map<FirTypeParameterSymbol, TcTypeParameter> = emptyMap(),
): Boolean =
    ownerContextTransportabilityStatus(
        owner = owner,
        concreteType = concreteType,
        transportedId = transportedId,
        additionalTypeParameters = additionalTypeParameters,
        concreteModel =
            toConcreteType(
                owner = owner,
                concreteType = concreteType,
                additionalTypeParameters = additionalTypeParameters,
            ),
    ) !is FirOwnerContextTransportabilityStatus.NotTransportedReference

private fun ConeKotlinType.transportabilityViolationInOwnerContext(
    owner: FirRegularClass,
    concreteType: TcType.Constructor,
    transportedId: String,
    session: FirSession,
    additionalTypeParameters: Map<FirTypeParameterSymbol, TcTypeParameter> = emptyMap(),
    visiting: MutableSet<String> = linkedSetOf(),
): String? {
    val status =
        ownerContextTransportabilityStatus(
            owner = owner,
            concreteType = concreteType,
            transportedId = transportedId,
            additionalTypeParameters = additionalTypeParameters,
            concreteModel =
                toConcreteType(
                    owner = owner,
                    concreteType = concreteType,
                    additionalTypeParameters = additionalTypeParameters,
                ),
        )
    return when (status) {
        FirOwnerContextTransportabilityStatus.NotTransportedReference -> null
        is FirOwnerContextTransportabilityStatus.Unsupported -> status.message
        is FirOwnerContextTransportabilityStatus.Concrete ->
            status.model.transportabilityViolation(transportedId, session, visiting)
    }
}

internal sealed interface FirOwnerContextTransportabilityStatus {
    data object NotTransportedReference : FirOwnerContextTransportabilityStatus

    data class Unsupported(
        val message: String,
    ) : FirOwnerContextTransportabilityStatus

    data class Concrete(
        val model: TcType,
    ) : FirOwnerContextTransportabilityStatus
}

internal fun ConeKotlinType.ownerContextTransportabilityStatus(
    owner: FirRegularClass,
    concreteType: TcType.Constructor,
    transportedId: String,
    additionalTypeParameters: Map<FirTypeParameterSymbol, TcTypeParameter> = emptyMap(),
    concreteModel: TcType? =
        toConcreteType(
            owner = owner,
            concreteType = concreteType,
            additionalTypeParameters = additionalTypeParameters,
        ),
): FirOwnerContextTransportabilityStatus {
    val lowered = approximateIntegerLiteralType().lowerBoundIfFlexible()
    val rendered = lowered.toString()
    val transportedSymbol =
        owner.ownerContextTypeParameters(additionalTypeParameters)
            .firstOrNull { (_, parameter) -> parameter.id == transportedId }
            ?.first
    return transportabilityStatusForTransportedType(
        transported = transportedSymbol,
        transportedId = transportedId,
        opaqueParameters = additionalTypeParameters.keys,
        concreteModel = concreteModel,
        rendered = rendered,
    )
}

internal fun ConeKotlinType.transportabilityStatusForTransportedType(
    transported: FirTypeParameterSymbol?,
    transportedId: String,
    opaqueParameters: Set<FirTypeParameterSymbol> = emptySet(),
    concreteModel: TcType?,
    rendered: String = approximateIntegerLiteralType().lowerBoundIfFlexible().toString(),
): FirOwnerContextTransportabilityStatus {
    val lowered = approximateIntegerLiteralType().lowerBoundIfFlexible()
    val rawMention =
        transported != null &&
            lowered.mentionsTransportedType(
                transported = transported,
                opaqueParameters = opaqueParameters,
            )
    val modeledMention = concreteModel?.references(transportedId) == true
    if (!rawMention && !modeledMention) {
        return FirOwnerContextTransportabilityStatus.NotTransportedReference
    }
    if (rendered.contains("&")) {
        return FirOwnerContextTransportabilityStatus.Unsupported(
            "DeriveVia does not support definitely-non-null or intersection member types",
        )
    }
    if (concreteModel == null) {
        return FirOwnerContextTransportabilityStatus.Unsupported(
            "DeriveVia does not support unsupported transported member type shape",
        )
    }
    return FirOwnerContextTransportabilityStatus.Concrete(concreteModel)
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
                .filter(GeneratedDerivedMetadata.Derive::validatedReturnTypeclass)
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
): FirDeriveViaAnnotationParseResult {
    if (owner.typeParameters.isNotEmpty()) {
        return FirDeriveViaAnnotationParseResult.Invalid(
            annotation = this,
            message = "@DeriveVia only supports monomorphic classes for now",
        )
    }
    val typeclassId =
        getClassIdArgument("typeclass", session)
            ?: return FirDeriveViaAnnotationParseResult.Invalid(
                annotation = this,
                message = "@DeriveVia typeclass argument must be a resolvable class literal",
            )
    val typeclassInterface =
        session.regularClassSymbolOrNull(typeclassId)?.fir
            ?: return FirDeriveViaAnnotationParseResult.Invalid(
                annotation = this,
                message = "@DeriveVia typeclass argument must be a resolvable class literal: ${typeclassId.asString()}",
            )
    if (!typeclassInterface.hasAnnotation(TYPECLASS_ANNOTATION_CLASS_ID, session)) {
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
    FirDirectTransportPlanner(session)
        .resolveViaPathDetailed(
            sourceType = TcType.Constructor(owner.symbol.classId.asString(), emptyList()),
            path = path,
        ).fold(
            onSuccess = { resolvedType ->
                if (resolvedType.normalizedKey() == TcType.Constructor(owner.symbol.classId.asString(), emptyList()).normalizedKey()) {
                    return FirDeriveViaAnnotationParseResult.Invalid(
                        annotation = this,
                        message = "DeriveVia path must not resolve back to the annotated class",
                    )
                }
            },
            onFailure = { error ->
            return FirDeriveViaAnnotationParseResult.Invalid(
                annotation = this,
                message = error.message ?: "Failed to resolve DeriveVia path",
            )
            },
        )
    return FirDeriveViaAnnotationParseResult.Valid(
        annotation = this,
        request = FirDeriveViaRequest(typeclassId = typeclassId, path = path),
    )
}

private fun FirAnnotation.parseDeriveEquivAnnotation(
    owner: FirRegularClass,
    session: FirSession,
): FirDeriveEquivAnnotationParseResult {
    if (owner.typeParameters.isNotEmpty()) {
        return FirDeriveEquivAnnotationParseResult.Invalid(
            annotation = this,
            message = "@DeriveEquiv only supports monomorphic classes for now",
        )
    }
    val otherClassId =
        getClassIdArgument("otherClass", session)
            ?: return FirDeriveEquivAnnotationParseResult.Invalid(
                annotation = this,
                message = "@DeriveEquiv otherClass argument must be a resolvable class literal",
            )
    val otherClass =
        session.regularClassSymbolOrNull(otherClassId)?.fir
            ?: return FirDeriveEquivAnnotationParseResult.Invalid(
                annotation = this,
                message = "@DeriveEquiv otherClass argument must be a resolvable class literal: ${otherClassId.asString()}",
            )
    if (otherClass.typeParameters.isNotEmpty()) {
        return FirDeriveEquivAnnotationParseResult.Invalid(
            annotation = this,
            message = "@DeriveEquiv only supports monomorphic classes for now",
        )
    }
    val ownerType = TcType.Constructor(owner.symbol.classId.asString(), emptyList())
    val otherType = TcType.Constructor(otherClassId.asString(), emptyList())
    if (!FirDirectTransportPlanner(session).planEquiv(ownerType, otherType)) {
        return FirDeriveEquivAnnotationParseResult.Invalid(
            annotation = this,
            message = "Cannot derive Equiv between ${owner.symbol.classId.asString()} and ${otherClassId.asString()}",
        )
    }
    return FirDeriveEquivAnnotationParseResult.Valid(
        annotation = this,
        request = FirDeriveEquivRequest(otherClassId),
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
    val endpointPairs = linkedMapOf<String, Pair<TcType.Constructor, TcType.Constructor>>()
    val collected =
        collectIsoEndpointPairsInConcreteContext(
            session = session,
            concreteType = defaultConcreteType(),
            visited = linkedSetOf(),
            endpointPairs = endpointPairs,
        )
    return endpointPairs.values.singleOrNull().takeIf { collected }
}

private fun FirRegularClass.collectIsoEndpointPairsInConcreteContext(
    session: FirSession,
    concreteType: TcType.Constructor,
    visited: MutableSet<String>,
    endpointPairs: MutableMap<String, Pair<TcType.Constructor, TcType.Constructor>>,
): Boolean {
    val visitKey = "${symbol.classId.asString()}:${concreteType.render()}"
    if (!visited.add(visitKey)) {
        return true
    }

    for (superType in declaredOrResolvedSuperTypes()) {
        val superConcreteType = superType.toConcreteType(this, concreteType) as? TcType.Constructor ?: continue
        if (superConcreteType.classifierId == ISO_CLASS_ID.asString()) {
            val leftType = superConcreteType.arguments.getOrNull(0) as? TcType.Constructor ?: return false
            val rightType = superConcreteType.arguments.getOrNull(1) as? TcType.Constructor ?: return false
            endpointPairs.putIfAbsent(
                "${leftType.normalizedKey()}->${rightType.normalizedKey()}",
                leftType to rightType,
            )
            continue
        }
        val superClassId = superConcreteType.classIdOrNull() ?: continue
        val superClass = session.regularClassSymbolOrNull(superClassId)?.fir ?: continue
        if (!superClass.collectIsoEndpointPairsInConcreteContext(
            session = session,
            concreteType = superConcreteType,
            visited = visited,
            endpointPairs = endpointPairs,
        )) {
            return false
        }
    }
    return true
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

        is ConeDefinitelyNotNullType -> lowered.original.mentionsTransportedType(transported, opaqueParameters)
        is ConeIntersectionType -> lowered.intersectedTypes.any { intersected -> intersected.mentionsTransportedType(transported, opaqueParameters) }

        else ->
            lowered.toString().containsStandaloneTypeParameterIdentifier(
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
                val sealedCases = klass.transparentSealedCases(session, klass.transportAccessContext(session))
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
    if (hasNonPropertyBackingFields()) {
        return null
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
                identity = parameter.name.asString(),
                type = fieldType,
                isUnitLike = fieldType.isUnitLike(),
            )
        }
    return FirTransparentProductInfo(
        isObjectLike = false,
        fields = fields,
    )
}

internal fun FirRegularClass.hasNonPropertyBackingFields(): Boolean =
    declarations.any { declaration -> declaration is FirField }

private fun FirRegularClass.transparentSealedCases(
    session: FirSession,
    accessContext: TransportSyntheticAccessContext,
): List<FirRegularClassSymbol>? {
    if (status.modality != Modality.SEALED || typeParameters.isNotEmpty()) {
        return null
    }
    if (!accessContext.allowsTransportVisibility(status.visibility.toTransportSyntheticVisibility())) {
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
            accessContext.allowsTransportVisibility(case.fir.status.visibility.toTransportSyntheticVisibility()) &&
                (case.fir.classKind == ClassKind.OBJECT ||
                (case.fir.status.isData && case.fir.typeParameters.isEmpty() && !case.fir.hasAnonymousInitializer())
                )
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
    additionalTypeParameters: Map<FirTypeParameterSymbol, TcTypeParameter> = emptyMap(),
): TcType? {
    val typeParameters = owner.ownerContextTypeParameters(additionalTypeParameters)
    if (typeParameters.size != concreteType.arguments.size) {
        val ownerParameterCount = owner.typeParameters.size
        if (ownerParameterCount != concreteType.arguments.size) {
            return null
        }
    }
    val parameterModels = typeParameters.toMap()
    val bindings =
        owner.typeParameters.mapIndexed { index, typeParameter ->
            val parameter = parameterModels[typeParameter.symbol] ?: return null
            parameter.id to concreteType.arguments[index]
        }.toMap()
    return coneTypeToModel(this, parameterModels)?.substituteType(bindings)
}

private fun FirRegularClass.ownerContextTypeParameters(
    additionalTypeParameters: Map<FirTypeParameterSymbol, TcTypeParameter>,
): List<Pair<FirTypeParameterSymbol, TcTypeParameter>> =
    typeParameters.mapIndexed { index, typeParameter ->
        typeParameter.symbol to
            TcTypeParameter(
                id = "${symbol.classId.asString()}#$index",
                displayName = typeParameter.symbol.name.asString(),
            )
    } + additionalTypeParameters.toList()

private data class FirTransparentProductInfo(
    val isObjectLike: Boolean,
    val fields: List<FirTransparentFieldInfo>,
)

private data class FirTransparentFieldInfo(
    val identity: String,
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
