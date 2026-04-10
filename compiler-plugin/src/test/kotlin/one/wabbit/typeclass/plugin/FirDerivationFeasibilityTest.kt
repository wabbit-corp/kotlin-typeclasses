// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

@file:OptIn(
    org.jetbrains.kotlin.fir.PrivateSessionConstructor::class,
    org.jetbrains.kotlin.fir.SessionConfiguration::class,
    org.jetbrains.kotlin.fir.scopes.DelicateScopeAPI::class,
)

package one.wabbit.typeclass.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirBinaryDependenciesModuleData
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.builder.buildField
import org.jetbrains.kotlin.fir.declarations.builder.buildRegularClass
import org.jetbrains.kotlin.fir.declarations.builder.buildTypeParameter
import org.jetbrains.kotlin.fir.declarations.impl.FirResolvedDeclarationStatusImpl
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.scopes.FirContainingNamesAwareScope
import org.jetbrains.kotlin.fir.scopes.FirScope
import org.jetbrains.kotlin.fir.scopes.FirScopeProvider
import org.jetbrains.kotlin.fir.scopes.FirTypeScope
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFieldSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeAttributes
import org.jetbrains.kotlin.fir.types.ConeCapturedType
import org.jetbrains.kotlin.fir.types.ConeCapturedTypeConstructor
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjectionOut
import org.jetbrains.kotlin.fir.types.impl.ConeTypeParameterTypeImpl
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.types.model.CaptureStatus

class FirDerivationFeasibilityTest {
    @Test
    fun `canonical transport assignments match same nominal shapes by identity`() {
        val assignments =
            canonicalTransportAssignments(
                sources =
                    listOf(
                        NamedField(identity = "left", kind = "string"),
                        NamedField(identity = "right", kind = "int"),
                    ),
                targets = listOf("right", "left"),
                sameNominalShape = true,
                sourceIdentity = NamedField::identity,
                targetIdentity = { it },
            ) { source, target ->
                source.takeIf { it.identity == target }
            }

        assertEquals(
            listOf("right", "left"),
            assignments?.sortedBy { it.targetIndex }?.map { it.value.identity },
        )
    }

    @Test
    fun `canonical transport assignments reject duplicate identities for same nominal shapes`() {
        val assignments =
            canonicalTransportAssignments(
                sources =
                    listOf(
                        NamedField(identity = "dup", kind = "left"),
                        NamedField(identity = "dup", kind = "right"),
                    ),
                targets = listOf("dup", "dup"),
                sameNominalShape = true,
                sourceIdentity = NamedField::identity,
                targetIdentity = { it },
            ) { source, target ->
                source.takeIf { it.identity == target }
            }

        assertEquals(null, assignments)
    }

    @Test
    fun `unique assignment preserves multiplicity for equal sources`() {
        val sources =
            listOf(
                CollapsingField(identity = "first", kind = "dup"),
                CollapsingField(identity = "second", kind = "dup"),
            )
        val targets = listOf("first", "second")

        val assignments =
            uniquePerfectAssignmentPreservingMultiplicity(sources = sources, targets = targets) {
                source,
                target ->
                source.takeIf { it.identity == target }
            }

        assertTrue(assignments != null)
        assertEquals(listOf("first", "second"), assignments.map { it.value.identity })
    }

    @Test
    fun `unique assignment finds globally forced product-style matching`() {
        val assignments =
            uniquePerfectAssignmentPreservingMultiplicity(
                sources = listOf("string", "int"),
                targets = listOf("any", "string"),
            ) { source, target ->
                when (target) {
                    "any" -> source
                    "string" -> source.takeIf { it == "string" }
                    else -> null
                }
            }

        assertEquals(
            listOf("int", "string"),
            assignments?.sortedBy { it.targetIndex }?.map { it.value },
        )
    }

    @Test
    fun `unique assignment finds globally forced sum-style matching`() {
        val assignments =
            uniquePerfectAssignmentPreservingMultiplicity(
                sources = listOf("textCase", "intCase"),
                targets = listOf("wideTarget", "textTarget"),
            ) { source, target ->
                when (target) {
                    "wideTarget" -> source
                    "textTarget" -> source.takeIf { it == "textCase" }
                    else -> null
                }
            }

        assertEquals(
            mapOf("wideTarget" to "intCase", "textTarget" to "textCase"),
            assignments
                ?.associate { it.targetIndex to it.value }
                ?.mapKeys { (targetIndex, _) -> listOf("wideTarget", "textTarget")[targetIndex] },
        )
    }

    @Test
    fun `unique assignment rejects ambiguous positional matches`() {
        val sources = listOf("left", "count", "right")

        assertFalse(
            uniquePerfectAssignmentPreservingMultiplicity(
                sources = sources,
                targets = listOf("firstString", "int", "secondString"),
            ) { source, target ->
                when (target) {
                    "firstString",
                    "secondString" -> source.takeIf { it != "count" }
                    "int" -> source.takeIf { it == "count" }
                    else -> null
                }
            } != null
        )
    }

    @Test
    fun `non-property FIR fields invalidate transparent product candidates`() {
        val session = object : FirSession(FirSession.Kind.Library) {}
        val classId = ClassId.topLevel(FqName("demo.Stateful"))
        val moduleData =
            FirBinaryDependenciesModuleData(Name.special("<fir-derivation-test>")).apply {
                bindSession(session)
            }
        val statelessClass = buildRegularClass {
            this.moduleData = moduleData
            origin = FirDeclarationOrigin.Library
            name = classId.shortClassName
            symbol = FirRegularClassSymbol(classId)
            status = FirResolvedDeclarationStatusImpl.DEFAULT_STATUS_FOR_STATUSLESS_DECLARATIONS
            scopeProvider = DerivationTestFirScopeProvider
            classKind = ClassKind.CLASS
            superTypeRefs += session.builtinTypes.anyType
        }
        val statefulClass = buildRegularClass {
            this.moduleData = moduleData
            origin = FirDeclarationOrigin.Library
            name = classId.shortClassName
            symbol = FirRegularClassSymbol(classId)
            status = FirResolvedDeclarationStatusImpl.DEFAULT_STATUS_FOR_STATUSLESS_DECLARATIONS
            scopeProvider = DerivationTestFirScopeProvider
            classKind = ClassKind.CLASS
            superTypeRefs += session.builtinTypes.anyType
            declarations += buildField {
                this.moduleData = moduleData
                origin = FirDeclarationOrigin.Library
                name = Name.identifier("hidden")
                symbol = FirFieldSymbol(CallableId(classId, name))
                status = FirResolvedDeclarationStatusImpl.DEFAULT_STATUS_FOR_STATUSLESS_DECLARATIONS
                returnTypeRef = session.builtinTypes.intType
                isVar = false
                javaClass.methods
                    .singleOrNull { method ->
                        method.name == "setLocal" &&
                            method.parameterTypes.singleOrNull() == Boolean::class.javaPrimitiveType
                    }
                    ?.invoke(this, false)
            }
        }

        assertFalse(statelessClass.hasNonPropertyBackingFields())
        assertTrue(statefulClass.hasNonPropertyBackingFields())
    }

    @Test
    fun `unsupported transported cone shape is rejected instead of ignored`() {
        val transported = boundTypeParameterSymbol("A")
        val transportedType =
            ConeTypeParameterTypeImpl(transported.toLookupTag(), false, ConeAttributes.Empty)
        val capturedType =
            ConeCapturedType(
                constructor =
                    ConeCapturedTypeConstructor(
                        projection = ConeKotlinTypeProjectionOut(transportedType),
                        lowerType = null,
                        captureStatus = CaptureStatus.FROM_EXPRESSION,
                        supertypes = emptyList(),
                        typeParameterMarker = null,
                    )
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

private class CollapsingField(val identity: String, private val kind: String) {
    override fun equals(other: Any?): Boolean = other is CollapsingField && kind == other.kind

    override fun hashCode(): Int = kind.hashCode()
}

private data class NamedField(val identity: String, val kind: String)

private object DerivationTestFirScopeProvider : FirScopeProvider() {
    override fun getUseSiteMemberScope(
        klass: org.jetbrains.kotlin.fir.declarations.FirClass,
        useSiteSession: FirSession,
        scopeSession: ScopeSession,
        memberRequiredPhase: org.jetbrains.kotlin.fir.declarations.FirResolvePhase?,
    ): FirTypeScope = FirTypeScope.Empty

    override fun getTypealiasConstructorScope(
        typeAlias: org.jetbrains.kotlin.fir.declarations.FirTypeAlias,
        useSiteSession: FirSession,
        scopeSession: ScopeSession,
    ): FirScope =
        object : FirScope() {
            override fun withReplacedSessionOrNull(
                newSession: FirSession,
                newScopeSession: ScopeSession,
            ): FirScope? = null
        }

    override fun getStaticCallableMemberScope(
        klass: org.jetbrains.kotlin.fir.declarations.FirClass,
        useSiteSession: FirSession,
        scopeSession: ScopeSession,
    ): FirContainingNamesAwareScope? = null

    override fun getStaticCallableMemberScopeForBackend(
        klass: org.jetbrains.kotlin.fir.declarations.FirClass,
        useSiteSession: FirSession,
        scopeSession: ScopeSession,
    ): FirContainingNamesAwareScope? = null

    override fun getNestedClassifierScope(
        klass: org.jetbrains.kotlin.fir.declarations.FirClass,
        useSiteSession: FirSession,
        scopeSession: ScopeSession,
    ): FirContainingNamesAwareScope? = null
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

private val TEST_MODULE_DATA =
    FirBinaryDependenciesModuleData(Name.special("<fir-derive-via-test>"))

private val TEST_CONTAINING_DECLARATION_SYMBOL = object : FirBasedSymbol<FirDeclaration>() {}
