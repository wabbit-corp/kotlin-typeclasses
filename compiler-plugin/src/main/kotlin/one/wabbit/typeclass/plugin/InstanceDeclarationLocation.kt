// SPDX-License-Identifier: AGPL-3.0-or-later

@file:OptIn(org.jetbrains.kotlin.fir.symbols.SymbolInternals::class)

package one.wabbit.typeclass.plugin

import one.wabbit.typeclass.plugin.model.TcType
import one.wabbit.typeclass.plugin.model.referencedClassifierIds
import org.jetbrains.kotlin.KtLightSourceElement
import org.jetbrains.kotlin.KtPsiSourceElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.resolve.providers.firProvider
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationBase
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.name.ClassId

internal fun ProvidedTypeExpansion.topLevelInstanceHostDisplayNames(): Set<String> =
    declaredTypes.flatMapTo(linkedSetOf()) { type ->
        type.referencedClassifierIds().map(::classifierDisplayName)
    }

internal fun FirDeclaration.isLegalTopLevelInstanceLocation(
    session: FirSession,
    providedTypes: ProvidedTypeExpansion,
): Boolean {
    val declarationPath = sourceFilePath(session)
        // Deserialized dependency declarations are revalidated in downstream FIR sessions,
        // but their original source file path is no longer available here.
        // Trust the producing compilation to have enforced the top-level placement rule.
        ?: return source == null
    return providedTypes.declaredTypes.isNotEmpty() &&
        providedTypes.declaredTypes.all { declaredType ->
            declarationPath in declaredType.topLevelInstanceHostPaths(session)
        }
}

internal fun IrDeclarationBase.isLegalTopLevelInstanceLocation(
    providedTypes: List<TcType>,
    sourceClassesById: Map<String, IrClass>,
): Boolean {
    val declarationPath = containingIrFile()?.fileEntry?.name ?: return false
    return providedTypes.isNotEmpty() &&
        providedTypes.all { providedType ->
            declarationPath in providedType.topLevelInstanceHostPaths(sourceClassesById)
        }
}

private fun TcType.topLevelInstanceHostPaths(session: FirSession): Set<String> =
    referencedClassifierIds().mapNotNullTo(linkedSetOf()) { classifierId ->
        session.classifierSourceFilePath(classifierId)
    }

private fun TcType.topLevelInstanceHostPaths(sourceClassesById: Map<String, IrClass>): Set<String> =
    referencedClassifierIds().mapNotNullTo(linkedSetOf()) { classifierId ->
        sourceClassesById[classifierId]?.containingIrFile()?.fileEntry?.name
    }

private fun FirSession.classifierSourceFilePath(classifierId: String): String? {
    val classId = runCatching { ClassId.fromString(classifierId) }.getOrNull() ?: return null
    return runCatching { firProvider.getFirClassifierContainerFileIfAny(classId)?.sourceFile?.path }.getOrNull()
}

private fun FirDeclaration.sourceFilePath(session: FirSession): String? =
    when (this) {
        is FirRegularClass -> runCatching { session.firProvider.getFirClassifierContainerFileIfAny(symbol.classId)?.sourceFile?.path }.getOrNull()
        is FirTypeclassFunctionDeclaration ->
            runCatching { session.firProvider.getFirCallableContainerFile(symbol)?.sourceFile?.path }.getOrNull()
        is FirProperty -> runCatching { session.firProvider.getFirCallableContainerFile(symbol)?.sourceFile?.path }.getOrNull()
        else -> sourcePsiFilePath()
    }

private fun FirDeclaration.sourcePsiFilePath(): String? {
    val sourceElement = source ?: return null
    val psiSource =
        when (sourceElement) {
            is KtPsiSourceElement -> sourceElement
            is KtLightSourceElement -> sourceElement.unwrapToKtPsiSourceElement()
        } ?: return null
    val file = psiSource.psi.getContainingFile()
    return file.getVirtualFile()?.path ?: file.getOriginalFile().getVirtualFile()?.path ?: file.getName()
}

private fun IrDeclarationBase.containingIrFile(): IrFile? {
    var current: IrDeclarationParent = parent
    while (true) {
        current =
            when (current) {
                is IrFile -> return current
                is IrDeclaration -> current.parent
                else -> return null
            }
    }
}

private fun classifierDisplayName(classifierId: String): String =
    runCatching { ClassId.fromString(classifierId).shortClassName.asString() }
        .getOrElse { classifierId.substringAfterLast('/').substringAfterLast('.') }
