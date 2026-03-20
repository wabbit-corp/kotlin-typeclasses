@file:OptIn(
    org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess::class,
    org.jetbrains.kotlin.fir.symbols.SymbolInternals::class,
)

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.getAnnotationsByClassId
import org.jetbrains.kotlin.fir.declarations.getStringArgument
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
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

internal fun FirRegularClass.supportedDerivedTypeclassIds(session: FirSession): Set<String> {
    val generatedIds = generatedDerivedTypeclassIds(session)
    val explicitIds =
        if (!supportsDeriveShape()) {
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

internal fun FirRegularClass.generatedDerivedTypeclassIds(session: FirSession): Set<String> {
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
                resolvedAnnotationsByClassId(
                    annotationClassId = GENERATED_INSTANCE_ANNOTATION_CONTAINER_CLASS_ID,
                    session = session,
                )
                    .flatMap { annotation -> annotation.containedGeneratedInstanceAnnotations() },
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
                    annotations
                        .filterIsInstance<FirAnnotationCall>()
                        .filter { annotation ->
                            annotation.annotationTypeRef.coneType.classId == GENERATED_INSTANCE_ANNOTATION_CONTAINER_CLASS_ID
                        }.flatMap { annotation ->
                            annotation.containedGeneratedInstanceAnnotations()
                        },
                )
            }
        }
    return generatedAnnotations.mapNotNullTo(linkedSetOf()) { annotation ->
        val typeclassId = annotation.getStringArgumentCompat("typeclassId", session)
        val targetId = annotation.getStringArgumentCompat("targetId", session)
        val kind = annotation.getStringArgumentCompat("kind", session)
        typeclassId?.takeIf {
            kind in setOf("derive", "derive-via", "derive-equiv") &&
                (targetId == null || targetId == ownerId)
        }
    }
}

private fun FirAnnotation.getStringArgumentCompat(
    name: String,
    session: FirSession,
): String? =
    getStringArgument(Name.identifier(name), session)
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

private fun FirAnnotation.containedGeneratedInstanceAnnotations(): List<FirAnnotation> {
    val valueArgument = findArgumentByName(Name.identifier("value")) ?: return emptyList()
    return valueArgument.unwrapVarargValue().filterIsInstance<FirAnnotation>()
}

internal fun FirAnnotation.getClassIdArgument(name: String): ClassId? =
    findArgumentByName(Name.identifier(name))
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
    resolvedAnnotationsByClassId(DERIVE_VIA_ANNOTATION_CLASS_ID, session)
        .mapNotNullTo(linkedSetOf()) { annotation ->
            annotation.getClassIdArgument("typeclass")?.asString()
        }

internal fun FirRegularClass.deriveEquivRequests(session: FirSession): Set<String> =
    resolvedAnnotationsByClassId(DERIVE_EQUIV_ANNOTATION_CLASS_ID, session)
        .mapNotNullTo(linkedSetOf()) { annotation ->
            annotation.getClassIdArgument("otherClass")?.asString()
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
    val companionSymbol = typeclassCompanionSymbol(typeclassId, session) ?: return false
    return companionSymbol.fir.declarations
        .filterIsInstance<FirSimpleFunction>()
        .any { function -> function.name.asString() == deriveMethodName }
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
