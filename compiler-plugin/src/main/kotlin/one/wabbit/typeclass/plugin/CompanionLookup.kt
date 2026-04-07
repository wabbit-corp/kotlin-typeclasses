// SPDX-License-Identifier: AGPL-3.0-or-later

@file:OptIn(org.jetbrains.kotlin.fir.symbols.SymbolInternals::class)

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.utils.isCompanion
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.ir.declarations.IrClass

internal fun <T> directOrNestedCompanion(
    owner: ClassId,
    directCompanion: T?,
    nestedLookup: () -> T?,
): T? = directCompanion ?: nestedLookup()

internal fun FirRegularClass.isTypeclassCompanionDeclaration(): Boolean = isCompanion

internal fun FirRegularClassSymbol.isTypeclassCompanionDeclaration(): Boolean = fir.isTypeclassCompanionDeclaration()

internal fun IrClass.isTypeclassCompanionDeclaration(): Boolean = isCompanion

internal fun FirSession.companionSymbolOrNull(owner: ClassId): FirRegularClassSymbol? {
    val ownerSymbol = regularClassSymbolOrNull(owner) ?: return null
    return ownerSymbol.resolvedCompanionObjectSymbol
}
