// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

@file:OptIn(org.jetbrains.kotlin.fir.PrivateSessionConstructor::class)

package one.wabbit.typeclass.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.kotlin.fir.FirBinaryDependenciesModuleData
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.builder.buildTypeParameter
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance

class TypeclassFirTracingSupportTest {
    @Test
    fun `resolution context type parameter models include enclosing class parameters`() {
        val classTypeParameter = tracingTypeParameterSymbol("A")
        val calleeTypeParameter = tracingTypeParameterSymbol("Ctx")

        val models =
            buildFirResolutionTypeParameterModels(
                containingClassTypeParameters = listOf(classTypeParameter),
                containingFunctions = emptyList(),
                calleeTypeParameters = listOf(calleeTypeParameter),
            )

        assertEquals(listOf(classTypeParameter, calleeTypeParameter), models.keys.toList())
    }
}

private fun tracingTypeParameterSymbol(name: String): FirTypeParameterSymbol {
    val symbol = FirTypeParameterSymbol()
    buildTypeParameter {
        moduleData = TRACING_TEST_MODULE_DATA
        origin = FirDeclarationOrigin.Library
        this.name = Name.identifier(name)
        this.symbol = symbol
        containingDeclarationSymbol = TRACING_TEST_CONTAINING_DECLARATION_SYMBOL
        variance = Variance.INVARIANT
        isReified = false
    }
    return symbol
}

private val TRACING_TEST_MODULE_DATA =
    FirBinaryDependenciesModuleData(Name.special("<fir-tracing-support-test>"))

private val TRACING_TEST_CONTAINING_DECLARATION_SYMBOL =
    object : FirBasedSymbol<FirDeclaration>() {}
