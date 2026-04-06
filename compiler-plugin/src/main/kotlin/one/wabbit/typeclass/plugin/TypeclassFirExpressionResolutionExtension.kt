// SPDX-License-Identifier: AGPL-3.0-or-later

@file:OptIn(org.jetbrains.kotlin.fir.symbols.SymbolInternals::class)

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirExpressionResolutionExtension
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.resolve.calls.ImplicitExtensionReceiverValue
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.coneType

internal class TypeclassFirExpressionResolutionExtension(
    session: FirSession,
    private val sharedState: TypeclassPluginSharedState,
) : FirExpressionResolutionExtension(session) {
    override fun addNewImplicitReceivers(
        functionCall: FirFunctionCall,
        sessionHolder: org.jetbrains.kotlin.fir.SessionAndScopeSessionHolder,
        containingCallableSymbol: org.jetbrains.kotlin.fir.symbols.FirBasedSymbol<*>,
    ): List<ImplicitExtensionReceiverValue> {
        val containingFunction = containingCallableSymbol as? FirNamedFunctionSymbol ?: return emptyList()
        val receiver = containingFunction.fir.receiverParameter ?: return emptyList()
        val receiverType = receiver.typeRef.coneType
        if (!sharedState.isTypeclassType(session, receiverType)) {
            return emptyList()
        }
        return listOf(
            ImplicitExtensionReceiverValue(
                receiver.symbol,
                receiverType,
                session,
                sessionHolder.scopeSession,
            ),
        )
    }
}
