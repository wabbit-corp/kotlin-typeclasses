// SPDX-License-Identifier: AGPL-3.0-or-later

@file:OptIn(
    org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess::class,
    org.jetbrains.kotlin.fir.symbols.SymbolInternals::class,
)

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.getAnnotationsByClassId
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.findArgumentByName
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.declarations.unwrapVarargValue
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.lowerBoundIfFlexible
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames

internal enum class DeriveMethodContract(
    val methodName: String,
    val metadataClassId: ClassId,
) {
    PRODUCT("deriveProduct", PRODUCT_TYPECLASS_METADATA_CLASS_ID),
    SUM("deriveSum", SUM_TYPECLASS_METADATA_CLASS_ID),
    ENUM("deriveEnum", ENUM_TYPECLASS_METADATA_CLASS_ID),
}

internal fun FirRegularClass.supportedDerivedTypeclassIds(session: FirSession): Set<String> {
    val generatedIds = generatedDerivedMetadata(session).mapTo(linkedSetOf()) { metadata -> metadata.typeclassId.asString() }
    val explicitIds =
        if (source == null) {
            emptySet()
        } else if (!supportsDeriveShape()) {
            emptySet()
        } else {
            derivedTypeclassIds(session).filterTo(linkedSetOf()) { typeclassId ->
                supportsDerivationForTypeclass(typeclassId, session)
            }
        }
    val deriveViaIds =
        if (typeParameters.isEmpty()) {
            deriveViaTypeclassIds(session).filterTo(linkedSetOf()) { typeclassId ->
                (
                    runCatching { ClassId.fromString(typeclassId) }.getOrNull()
                        ?.let { classId -> typeclassTypeParameterCount(classId, session)?.let { it >= 1 } == true }
                    ) == true
            }
        } else {
            emptySet()
        }
    val deriveEquivIds =
        if (typeParameters.isEmpty() && deriveEquivRequests(session).any { otherClassId ->
                (
                    runCatching { ClassId.fromString(otherClassId) }.getOrNull()
                        ?.let { classId -> session.regularClassSymbolOrNull(classId)?.fir?.typeParameters?.isEmpty() == true }
                    ) == true
            }
        ) {
            setOf(EQUIV_CLASS_ID.asString())
        } else {
            emptySet()
        }
    return buildSet {
        addAll(generatedIds)
        addAll(explicitIds)
        addAll(deriveViaIds)
        addAll(deriveEquivIds)
    }
}

internal fun FirRegularClass.supportsDeriveShape(): Boolean =
    when {
        classKind == ClassKind.OBJECT -> true
        status.modality == Modality.SEALED -> true
        status.modality == Modality.FINAL && classKind != ClassKind.INTERFACE -> true
        else -> false
    }

internal fun FirRegularClass.requiredDeriverInterfaceForDeriveShape(): ClassId =
    when {
        classKind == ClassKind.ENUM_CLASS -> TYPECLASS_DERIVER_CLASS_ID
        status.modality == Modality.SEALED -> TYPECLASS_DERIVER_CLASS_ID
        else -> PRODUCT_TYPECLASS_DERIVER_CLASS_ID
    }

internal fun FirRegularClass.supportsDerivationForTypeclass(
    typeclassId: String,
    session: FirSession,
): Boolean {
    val classId = runCatching { ClassId.fromString(typeclassId) }.getOrNull() ?: return false
    if (typeclassTypeParameterCount(classId, session) != 1) {
        return false
    }
    val requiredDeriverInterface = requiredDeriverInterfaceForDeriveShape()
    return typeclassSupportsDeriveShape(classId, requiredDeriverInterface, session)
}

internal fun FirRegularClass.requiredDeriveMethodNameForDeriveShape(): String? =
    when {
        classKind == ClassKind.ENUM_CLASS -> "deriveEnum"
        else -> null
    }

internal fun FirRegularClass.requiredDeriveMethodContractForDeriveShape(): DeriveMethodContract? =
    when {
        classKind == ClassKind.ENUM_CLASS -> DeriveMethodContract.ENUM
        else -> null
    }

internal fun FirRegularClass.generatedDerivedTypeclassIds(session: FirSession): Set<String> {
    val ownerId = symbol.classId.asString()
    return generatedDerivedMetadata(session).mapTo(linkedSetOf()) { metadata -> metadata.typeclassId.asString() }
}

internal fun FirRegularClass.generatedDerivedMetadata(session: FirSession): List<GeneratedDerivedMetadata> {
    val ownerId = symbol.classId.asString()
    val generatedAnnotations =
        buildList {
            addAll(
                resolvedAnnotationsByClassId(
                    annotationClassId = GENERATED_INSTANCE_ANNOTATION_CLASS_ID,
                    session = session,
                ),
            )
            addAll(
                buildList {
                    for (annotation in
                        resolvedAnnotationsByClassId(
                            annotationClassId = GENERATED_INSTANCE_ANNOTATION_CONTAINER_CLASS_ID,
                            session = session,
                        )
                    ) {
                        addAll(annotation.containedAnnotationArguments())
                    }
                },
            )
        }.ifEmpty {
            buildList {
                addAll(
                    annotations
                        .filterIsInstance<FirAnnotationCall>()
                        .filter { annotation ->
                            annotation.annotationTypeRef.coneType.classId == GENERATED_INSTANCE_ANNOTATION_CLASS_ID
                        },
                )
                addAll(
                    buildList {
                        for (annotation in annotations) {
                            val annotationCall = annotation as? FirAnnotationCall ?: continue
                            if (annotationCall.annotationTypeRef.coneType.classId == GENERATED_INSTANCE_ANNOTATION_CONTAINER_CLASS_ID) {
                                addAll(annotationCall.containedAnnotationArguments())
                            }
                        }
                    },
                )
            }
        }
    if (generatedAnnotations.isEmpty() && source == null) {
        val binaryMetadata = BinaryGeneratedDerivedMetadataRegistry.generatedMetadataFor(session, symbol.classId)
        if (binaryMetadata.isNotEmpty()) {
            return binaryMetadata
        }
    }
    return generatedAnnotations.mapNotNull { annotation ->
        decodeGeneratedDerivedMetadata(
            typeclassId = annotation.getStringArgumentCompat("typeclassId", session),
            targetId = annotation.getStringArgumentCompat("targetId", session),
            kind = annotation.getStringArgumentCompat("kind", session),
            payload = annotation.getStringArgumentCompat("payload", session),
            expectedOwnerId = ownerId,
        )
    }
}

private fun FirAnnotation.getStringArgumentCompat(
    name: String,
    session: FirSession,
): String? =
    typeclassGetStringArgument(Name.identifier(name), session)
        ?: ((this as? FirAnnotationCall)
            ?.argumentMapping
            ?.mapping
            ?.get(Name.identifier(name)) as? org.jetbrains.kotlin.fir.expressions.FirLiteralExpression)
            ?.value as? String
        ?: (findArgumentByName(Name.identifier(name)) as? org.jetbrains.kotlin.fir.expressions.FirLiteralExpression)
            ?.value as? String

internal fun FirRegularClass.resolvedAnnotationsByClassId(
    annotationClassId: ClassId,
    session: FirSession,
) : List<FirAnnotation> {
    val resolvedWithArguments =
        symbol.resolvedAnnotationsWithArguments
            .getAnnotationsByClassId(annotationClassId, session)
    if (resolvedWithArguments.isNotEmpty()) {
        return resolvedWithArguments
    }
    val resolvedWithClassIds =
        symbol.resolvedAnnotationsWithClassIds
            .getAnnotationsByClassId(annotationClassId, session)
    if (resolvedWithClassIds.isNotEmpty()) {
        return resolvedWithClassIds
    }
    return annotations
        .getAnnotationsByClassId(annotationClassId, session)
}

internal fun FirRegularClass.resolvedRepeatableAnnotationsByClassId(
    annotationClassId: ClassId,
    containerClassId: ClassId,
    session: FirSession,
): List<FirAnnotation> =
    buildList {
        addAll(resolvedAnnotationsByClassId(annotationClassId, session))
        for (annotation in resolvedAnnotationsByClassId(containerClassId, session)) {
            addAll(annotation.containedAnnotationArguments())
        }
    }

private fun FirAnnotation.containedAnnotationArguments(): List<FirAnnotation> {
    val valueName = Name.identifier("value")
    val directArguments = mutableListOf<FirAnnotation>()
    val directValueArgument = findArgumentByName(valueName)
    val directValueExpressions = directValueArgument?.unwrapVarargValue().orEmpty()
    if (directValueExpressions.isNotEmpty()) {
        for (argument in directValueExpressions) {
            val expression = argument as? FirExpression ?: continue
            for (flattened in flattenContainedAnnotationArgumentExpressions(expression)) {
                val annotation = flattened as? FirAnnotation ?: continue
                directArguments += annotation
            }
        }
    } else if (directValueArgument is FirExpression) {
        for (flattened in flattenContainedAnnotationArgumentExpressions(directValueArgument)) {
            val annotation = flattened as? FirAnnotation ?: continue
            directArguments += annotation
        }
    }
    if (directArguments.isNotEmpty()) {
        return directArguments
    }

    val annotationCall = this as? FirAnnotationCall ?: return emptyList()
    val mappedExpressions = mutableListOf<FirExpression>()
    for ((parameter, argument) in annotationCall.argumentMapping.mapping) {
        if (parameter == valueName && argument is FirExpression) {
            mappedExpressions += argument
        }
    }
    val sourceExpressions =
        if (mappedExpressions.isNotEmpty()) {
            mappedExpressions
        } else {
            buildList {
                for (argument in annotationCall.argumentList.arguments) {
                    val expression = argument as? FirExpression ?: continue
                    add(expression)
                }
            }
        }
    val annotations = mutableListOf<FirAnnotation>()
    for (expression in sourceExpressions) {
        for (flattened in flattenContainedAnnotationArgumentExpressions(expression)) {
            val annotation = flattened as? FirAnnotation ?: continue
            annotations += annotation
        }
    }
    return annotations
}

private fun flattenContainedAnnotationArgumentExpressions(expression: FirExpression): Sequence<FirExpression> =
    expression.typeclassCollectionLiteralArgumentsOrNull()?.flatMap(::flattenContainedAnnotationArgumentExpressions)
        ?: when (expression) {
            is FirVarargArgumentsExpression -> expression.arguments.asSequence().flatMap(::flattenContainedAnnotationArgumentExpressions)
            else -> sequenceOf(expression)
        }

internal fun FirAnnotation.getClassIdArgument(
    name: String,
    session: FirSession,
): ClassId? =
    typeclassGetKClassArgument(Name.identifier(name), session)?.lowerBoundIfFlexible()?.classId
        ?: findArgumentByName(Name.identifier(name))
            ?.unwrapVarargValue()
            .orEmpty()
            .ifEmpty { findArgumentByName(Name.identifier(name))?.let(::listOf).orEmpty() }
            .asSequence()
            .filterIsInstance<org.jetbrains.kotlin.fir.expressions.FirExpression>()
            .mapNotNull { expression ->
                when (expression) {
                    is org.jetbrains.kotlin.fir.expressions.FirGetClassCall ->
                        expression.argument.resolvedType.lowerBoundIfFlexible().classId

                    else -> expression.resolvedType.lowerBoundIfFlexible().classId
                }
            }.firstOrNull()

internal fun FirRegularClass.deriveViaTypeclassIds(session: FirSession): Set<String> =
    if (source == null) {
        generatedDerivedMetadata(session)
            .filterIsInstance<GeneratedDerivedMetadata.DeriveVia>()
            .mapTo(linkedSetOf()) { metadata -> metadata.typeclassId.asString() }
    } else {
        buildSet {
            addAll(
                deriveViaRequests(session).map { request -> request.typeclassId.asString() },
            )
            addAll(
                generatedDerivedMetadata(session)
                    .filterIsInstance<GeneratedDerivedMetadata.DeriveVia>()
                    .map { metadata -> metadata.typeclassId.asString() },
            )
        }
    }

internal fun FirRegularClass.deriveEquivRequests(session: FirSession): Set<String> =
    if (source == null) {
        generatedDerivedMetadata(session)
            .filterIsInstance<GeneratedDerivedMetadata.DeriveEquiv>()
            .mapTo(linkedSetOf()) { metadata -> metadata.otherClassId.asString() }
    } else {
        buildSet {
            addAll(
                resolvedRepeatableAnnotationsByClassId(
                    annotationClassId = DERIVE_EQUIV_ANNOTATION_CLASS_ID,
                    containerClassId = DERIVE_EQUIV_ANNOTATION_CONTAINER_CLASS_ID,
                    session = session,
                )
                    .mapNotNull { annotation -> annotation.getClassIdArgument("otherClass", session)?.asString() },
            )
            addAll(
                generatedDerivedMetadata(session)
                    .filterIsInstance<GeneratedDerivedMetadata.DeriveEquiv>()
                    .map { metadata -> metadata.otherClassId.asString() },
            )
        }
    }

internal fun typeclassSupportsDeriveShape(
    typeclassId: ClassId,
    requiredDeriverInterface: ClassId,
    session: FirSession,
): Boolean {
    val companionSymbol = typeclassCompanionSymbol(typeclassId, session) ?: return false
    return companionSymbol.implementsInterface(requiredDeriverInterface, session, linkedSetOf())
}

internal fun typeclassCompanionDeclaresDeriveMethod(
    typeclassId: ClassId,
    deriveMethodName: String,
    session: FirSession,
): Boolean {
    val contract = DeriveMethodContract.entries.firstOrNull { candidate -> candidate.methodName == deriveMethodName } ?: return false
    return typeclassCompanionResolveDeriveMethod(typeclassId, contract, session) != null
}

internal fun typeclassCompanionResolveDeriveMethod(
    typeclassId: ClassId,
    contract: DeriveMethodContract,
    session: FirSession,
): FirTypeclassFunctionDeclaration? {
    val companionSymbol = typeclassCompanionSymbol(typeclassId, session) ?: return null
    if (contract !in companionSymbol.implementedDeriveMethodContracts(session)) {
        return null
    }
    return companionSymbol.resolveDeriveMethod(contract, session)
}

private fun typeclassCompanionSymbol(
    typeclassId: ClassId,
    session: FirSession,
): FirRegularClassSymbol? {
    val typeclassSymbol = session.regularClassSymbolOrNull(typeclassId) ?: return null
    return typeclassSymbol.fir.declarations
        .filterIsInstance<FirRegularClass>()
        .singleOrNull { declaration ->
            declaration.symbol.classId.shortClassName == SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT
        }?.symbol
        ?: try {
            session.symbolProvider.getClassLikeSymbolByClassId(
                typeclassId.createNestedClassId(SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT),
            ) as? FirRegularClassSymbol
        } catch (_: IllegalArgumentException) {
            null
        }
}

internal fun typeclassTypeParameterCount(
    typeclassId: ClassId,
    session: FirSession,
): Int? = session.regularClassSymbolOrNull(typeclassId)?.fir?.typeParameters?.size

internal fun FirSession.regularClassSymbolOrNull(classId: ClassId): FirRegularClassSymbol? =
    try {
        symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol
    } catch (_: IllegalArgumentException) {
        null
    }

internal fun FirRegularClassSymbol.implementsInterface(
    targetInterface: ClassId,
    session: FirSession,
    visited: MutableSet<String>,
): Boolean {
    val currentClassId = classId.asString()
    if (!visited.add(currentClassId)) {
        return false
    }
    if (classId == targetInterface) {
        return true
    }
    return fir.declaredOrResolvedSuperTypes().any { superType ->
        val superClassId = superType.lowerBoundIfFlexible().classId ?: return@any false
        if (superClassId == targetInterface) {
            true
        } else {
            val superSymbol =
                try {
                    session.symbolProvider.getClassLikeSymbolByClassId(superClassId) as? FirRegularClassSymbol
                } catch (_: IllegalArgumentException) {
                    null
                }
            superSymbol?.implementsInterface(targetInterface, session, visited) == true
        }
    }
}

internal fun FirRegularClassSymbol.implementedDeriveMethodContracts(
    session: FirSession,
): Set<DeriveMethodContract> =
    buildSet {
        if (implementsInterface(TYPECLASS_DERIVER_CLASS_ID, session, linkedSetOf())) {
            addAll(DeriveMethodContract.entries)
        } else if (implementsInterface(PRODUCT_TYPECLASS_DERIVER_CLASS_ID, session, linkedSetOf())) {
            add(DeriveMethodContract.PRODUCT)
        }
    }

internal fun FirRegularClassSymbol.resolveDeriveMethod(
    contract: DeriveMethodContract,
    session: FirSession,
): FirTypeclassFunctionDeclaration? {
    val directMethod = declaredDeriveMethod(contract)
    if (directMethod != null) {
        return directMethod
    }
    return resolveInheritedDeriveMethod(contract, session, linkedSetOf())
}

private fun FirRegularClassSymbol.resolveInheritedDeriveMethod(
    contract: DeriveMethodContract,
    session: FirSession,
    visited: MutableSet<String>,
): FirTypeclassFunctionDeclaration? {
    if (!visited.add(classId.asString())) {
        return null
    }
    val matches =
        fir.declaredOrResolvedSuperTypes()
            .mapNotNull { superType -> superType.lowerBoundIfFlexible().classId }
            .mapNotNull { superClassId -> session.regularClassSymbolOrNull(superClassId) }
            .mapNotNull { superSymbol ->
                superSymbol.declaredDeriveMethod(contract)
                    ?: superSymbol.resolveInheritedDeriveMethod(contract, session, visited)
            }.distinctBy { function ->
                function.symbol.callableId.toString()
            }
    return matches.singleOrNull()
}

private fun FirRegularClassSymbol.declaredDeriveMethod(
    contract: DeriveMethodContract,
): FirTypeclassFunctionDeclaration? =
    fir.declarations
        .filterIsInstance<FirTypeclassFunctionDeclaration>()
        .singleOrNull { function ->
            function.isConcreteDeriveImplementation(owner = fir, contract = contract) &&
                function.name.asString() == contract.methodName &&
                function.valueParameters.size == 1 &&
                function.valueParameters.single().returnTypeRef.coneType.lowerBoundIfFlexible().classId == contract.metadataClassId
        }

private fun FirTypeclassFunctionDeclaration.isConcreteDeriveImplementation(
    owner: FirRegularClass,
    contract: DeriveMethodContract,
): Boolean =
    !isTypeclassDeriverEnumSentinel(owner, contract) &&
        (body != null || (owner.source == null && status.modality != Modality.ABSTRACT))

private fun isTypeclassDeriverEnumSentinel(
    owner: FirRegularClass,
    contract: DeriveMethodContract,
): Boolean = contract == DeriveMethodContract.ENUM && owner.symbol.classId == TYPECLASS_DERIVER_CLASS_ID
