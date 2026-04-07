// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.fir.FirBinaryDependenciesModuleData
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.builder.buildTypeParameter
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeAttributes
import org.jetbrains.kotlin.fir.types.ConeCapturedType
import org.jetbrains.kotlin.fir.types.ConeCapturedTypeConstructor
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjectionOut
import org.jetbrains.kotlin.fir.types.impl.ConeTypeParameterTypeImpl
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.types.model.CaptureStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FirDerivationFeasibilityTest {
    @Test
    fun `greedy fallback assignment preserves multiplicity for equal sources`() {
        val sources =
            listOf(
                CollapsingField(identity = "first", kind = "dup"),
                CollapsingField(identity = "second", kind = "dup"),
            )
        val targets = listOf("first", "second")

        assertTrue(
            greedyUniqueAssignmentPreservingMultiplicity(
                sources = sources,
                targets = targets,
            ) { source, target ->
                source.identity == target
            },
        )
    }

    @Test
    fun `greedy fallback assignment still rejects ambiguous matches`() {
        val sources =
            listOf(
                CollapsingField(identity = "first", kind = "dup"),
                CollapsingField(identity = "second", kind = "dup"),
            )

        assertFalse(
            greedyUniqueAssignmentPreservingMultiplicity(
                sources = sources,
                targets = listOf("either"),
            ) { _, _ ->
                true
            },
        )
    }

    @Test
    fun `unsupported transported cone shape is rejected instead of ignored`() {
        val transported = boundTypeParameterSymbol("A")
        val transportedType = ConeTypeParameterTypeImpl(transported.toLookupTag(), false, ConeAttributes.Empty)
        val capturedType =
            ConeCapturedType(
                constructor =
                    ConeCapturedTypeConstructor(
                        projection = ConeKotlinTypeProjectionOut(transportedType),
                        lowerType = null,
                        captureStatus = CaptureStatus.FROM_EXPRESSION,
                        supertypes = emptyList(),
                        typeParameterMarker = null,
                    ),
            )

        val status =
            capturedType.transportabilityStatusForTransportedType(
                transported = transported,
                transportedId = "demo/Owner#0",
                concreteModel = null,
            )

        assertIs<FirOwnerContextTransportabilityStatus.Unsupported>(status)
        assertEquals(
            "DeriveVia does not support unsupported transported member type shape",
            status.message,
        )
    }
}

private class CollapsingField(
    val identity: String,
    private val kind: String,
) {
    override fun equals(other: Any?): Boolean = other is CollapsingField && kind == other.kind

    override fun hashCode(): Int = kind.hashCode()
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

private val TEST_MODULE_DATA = FirBinaryDependenciesModuleData(Name.special("<fir-derive-via-test>"))

private val TEST_CONTAINING_DECLARATION_SYMBOL =
    object : FirBasedSymbol<FirDeclaration>() {}
