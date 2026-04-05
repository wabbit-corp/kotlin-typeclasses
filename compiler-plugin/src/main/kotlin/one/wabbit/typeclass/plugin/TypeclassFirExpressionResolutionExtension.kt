@file:OptIn(org.jetbrains.kotlin.fir.symbols.SymbolInternals::class)

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirExpressionResolutionExtension
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.symbols.SymbolInternals

internal class TypeclassFirExpressionResolutionExtension(
    session: FirSession,
    @Suppress("UNUSED_PARAMETER")
    private val sharedState: TypeclassPluginSharedState,
) : FirExpressionResolutionExtension(session) {
    override fun addNewImplicitReceivers(
        functionCall: FirFunctionCall,
        sessionHolder: org.jetbrains.kotlin.fir.SessionAndScopeSessionHolder,
        containingCallableSymbol: org.jetbrains.kotlin.fir.symbols.FirBasedSymbol<*>,
    ): List<org.jetbrains.kotlin.fir.resolve.calls.ImplicitExtensionReceiverValue> = emptyList()
}
