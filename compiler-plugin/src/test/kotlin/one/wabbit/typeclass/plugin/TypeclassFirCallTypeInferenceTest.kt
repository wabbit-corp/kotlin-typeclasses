// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

@file:OptIn(org.jetbrains.kotlin.fir.PrivateSessionConstructor::class)

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.fir.FirBinaryDependenciesModuleData
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.builder.buildTypeParameter
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeAttributes
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.impl.ConeTypeParameterTypeImpl
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance
import kotlin.test.Test
import kotlin.test.assertEquals

class TypeclassFirCallTypeInferenceTest {
    private data class FakeParameter(
        val name: String,
        val isVararg: Boolean = false,
    )

    @Test
    fun `fallback mapping keeps assigning repeated positional arguments to vararg parameters`() {
        val parameters =
            listOf(
                FakeParameter("head"),
                FakeParameter("values", isVararg = true),
                FakeParameter("tail"),
            )

        val mapping =
            buildNamedAndPositionalArgumentMapping(
                arguments =
                    listOf(
                        null to "first",
                        null to "second",
                        null to "third",
                        "tail" to "tail-arg",
                    ),
                parameters = parameters,
                parameterName = FakeParameter::name,
                isVararg = FakeParameter::isVararg,
            )

        assertEquals(
            listOf("head", "values", "values", "tail"),
            mapping.values.map(FakeParameter::name),
        )
    }

    @Test
    fun `return inference can bind type parameters from the explicit receiver alone`() {
        val session = object : FirSession(FirSession.Kind.Library) {}
        val typeParameter = boundTypeParameterSymbol("A")
        val receiverType = ConeTypeParameterTypeImpl(typeParameter.toLookupTag(), false, ConeAttributes.Empty)
        val intType = session.builtinTypes.intType.coneType

        val inferred =
            inferTypeArgumentsFromCallSiteTypes(
                session = session,
                functionTypeParameters = setOf(typeParameter),
                explicitTypeArguments = emptyMap(),
                receiverConstraint = receiverType to intType,
                argumentConstraints = emptyList(),
            )

        assertEquals(intType, inferred[typeParameter])
    }

    @Test
    fun `return inference can bind type parameters from local context constraints alone`() {
        val session = object : FirSession(FirSession.Kind.Library) {}
        val typeParameter = boundTypeParameterSymbol("A")
        val contextParameterType = ConeTypeParameterTypeImpl(typeParameter.toLookupTag(), false, ConeAttributes.Empty)
        val stringType = session.builtinTypes.stringType.coneType

        val inferred =
            inferTypeArgumentsFromCallSiteTypes(
                session = session,
                functionTypeParameters = setOf(typeParameter),
                explicitTypeArguments = emptyMap(),
                receiverConstraint = null,
                argumentConstraints = emptyList(),
                localContextConstraints = listOf(contextParameterType to stringType),
            )

        assertEquals(stringType, inferred[typeParameter])
    }

    @Test
    fun `inference type parameter models include enclosing class parameters`() {
        val classTypeParameter = boundTypeParameterSymbol("A")
        val functionTypeParameter = boundTypeParameterSymbol("B")

        val models =
            buildInferenceTypeParameterModels(
                bindableTypeParameters = setOf(functionTypeParameter),
                containingFunction = null,
                containingClassTypeParameters = listOf(classTypeParameter),
            )

        assertEquals(
            listOf(classTypeParameter, functionTypeParameter),
            models.keys.toList(),
        )
    }
}

private fun boundTypeParameterSymbol(name: String): FirTypeParameterSymbol {
    val symbol = FirTypeParameterSymbol()
    buildTypeParameter {
        moduleData = TEST_MODULE_DATA
        origin = FirDeclarationOrigin.Library
        this.name = Name.identifier(name)
        this.symbol = symbol
        containingDeclarationSymbol = TEST_CONTAINING_DECLARATION_SYMBOL
        variance = Variance.INVARIANT
        isReified = false
    }
    return symbol
}

private val TEST_MODULE_DATA = FirBinaryDependenciesModuleData(Name.special("<fir-type-inference-test>"))

private val TEST_CONTAINING_DECLARATION_SYMBOL =
    object : FirBasedSymbol<FirDeclaration>() {}
