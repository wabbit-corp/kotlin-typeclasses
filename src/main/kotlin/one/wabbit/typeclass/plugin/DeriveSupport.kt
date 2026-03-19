@file:OptIn(
    org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess::class,
    org.jetbrains.kotlin.fir.symbols.SymbolInternals::class,
)

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.lowerBoundIfFlexible
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.SpecialNames

internal fun FirRegularClass.supportedDerivedTypeclassIds(session: FirSession): Set<String> {
    if (!supportsDeriveShape()) {
        return emptySet()
    }
    return derivedTypeclassIds(session).filterTo(linkedSetOf()) { typeclassId ->
        supportsDerivationForTypeclass(typeclassId, session)
    }
}

internal fun FirRegularClass.supportsDeriveShape(): Boolean =
    when {
        classKind == ClassKind.OBJECT -> true
        status.modality == Modality.SEALED -> true
        status.modality == Modality.FINAL && classKind != ClassKind.INTERFACE -> true
        else -> false
    }

internal fun FirRegularClass.supportsDerivationForTypeclass(
    typeclassId: String,
    session: FirSession,
): Boolean {
    val classId = runCatching { ClassId.fromString(typeclassId) }.getOrNull() ?: return false
    val requiredDeriverInterface =
        if (status.modality == Modality.SEALED) {
            TYPECLASS_DERIVER_CLASS_ID
        } else {
            PRODUCT_TYPECLASS_DERIVER_CLASS_ID
        }
    return typeclassSupportsDeriveShape(classId, requiredDeriverInterface, session)
}

internal fun typeclassSupportsDeriveShape(
    typeclassId: ClassId,
    requiredDeriverInterface: ClassId,
    session: FirSession,
): Boolean {
    val typeclassSymbol =
        try {
            session.symbolProvider.getClassLikeSymbolByClassId(typeclassId) as? FirRegularClassSymbol
        } catch (_: IllegalArgumentException) {
            null
        } ?: return false
    val companion =
        typeclassSymbol.fir.declarations
            .filterIsInstance<FirRegularClass>()
            .singleOrNull { declaration ->
                declaration.symbol.classId.shortClassName == SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT
            } ?: return false
    return companion.symbol.implementsInterface(requiredDeriverInterface, session, linkedSetOf())
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

