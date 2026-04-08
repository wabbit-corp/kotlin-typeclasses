// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

@file:OptIn(
    org.jetbrains.kotlin.fir.PrivateSessionConstructor::class,
    org.jetbrains.kotlin.fir.SessionConfiguration::class,
    org.jetbrains.kotlin.fir.resolve.providers.FirSymbolProviderInternals::class,
    org.jetbrains.kotlin.fir.scopes.DelicateScopeAPI::class,
)

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirBinaryDependenciesModuleData
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.builder.buildRegularClass
import org.jetbrains.kotlin.fir.declarations.builder.buildTypeParameter
import org.jetbrains.kotlin.fir.declarations.impl.FirResolvedDeclarationStatusImpl
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.providers.FirNullSymbolNamesProvider
import org.jetbrains.kotlin.fir.resolve.providers.FirSymbolProvider
import org.jetbrains.kotlin.fir.scopes.FirContainingNamesAwareScope
import org.jetbrains.kotlin.fir.scopes.FirScope
import org.jetbrains.kotlin.fir.scopes.FirScopeProvider
import org.jetbrains.kotlin.fir.scopes.FirTypeScope
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirVariableSymbol
import org.jetbrains.kotlin.fir.types.ConeAttributes
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjectionOut
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.types.impl.ConeTypeParameterTypeImpl
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun `fallback mapping can advance past a non-final vararg for trailing positional arguments`() {
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
                    ),
                parameters = parameters,
                parameterName = FakeParameter::name,
                isVararg = FakeParameter::isVararg,
            )

        assertEquals(
            listOf("head", "values", "tail"),
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
    fun `projected receiver constraints do not infer exact classifier arguments`() {
        val session = object : FirSession(FirSession.Kind.Library) {}
        registerLibraryClass(
            session = session,
            classId = ClassId.topLevel(FqName("test.receiver.Box")),
            variances = listOf(Variance.INVARIANT),
        )
        val typeParameter = boundTypeParameterSymbol("A")
        val receiverType =
            ClassId.topLevel(FqName("test.receiver.Box")).constructClassLikeType(
                typeArguments =
                    arrayOf(
                        ConeTypeParameterTypeImpl(typeParameter.toLookupTag(), false, ConeAttributes.Empty),
                    ),
                isMarkedNullable = false,
            )
        val projectedReceiverType =
            ClassId.topLevel(FqName("test.receiver.Box")).constructClassLikeType(
                typeArguments =
                    arrayOf(
                        ConeKotlinTypeProjectionOut(session.builtinTypes.stringType.coneType),
                    ),
                isMarkedNullable = false,
            )

        val inferred =
            inferTypeArgumentsFromCallSiteTypes(
                session = session,
                functionTypeParameters = setOf(typeParameter),
                explicitTypeArguments = emptyMap(),
                receiverConstraint = receiverType to projectedReceiverType,
                argumentConstraints = emptyList(),
            )

        assertTrue(
            inferred.isEmpty(),
            "Projected receivers must not be flattened into exact type-argument bindings.",
        )
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

private fun registerLibraryClass(
    session: FirSession,
    classId: ClassId,
    variances: List<Variance>,
) {
    val symbol = FirRegularClassSymbol(classId)
    val typeParameters =
        variances.mapIndexed { index, variance ->
            val typeParameterSymbol = FirTypeParameterSymbol()
            buildTypeParameter {
                moduleData = TEST_MODULE_DATA
                origin = FirDeclarationOrigin.Library
                name = Name.identifier("T$index")
                this.symbol = typeParameterSymbol
                containingDeclarationSymbol = symbol
                this.variance = variance
                isReified = false
            }
        }
    buildRegularClass {
        moduleData = TEST_MODULE_DATA
        origin = FirDeclarationOrigin.Library
        name = classId.shortClassName
        this.symbol = symbol
        status = FirResolvedDeclarationStatusImpl.DEFAULT_STATUS_FOR_STATUSLESS_DECLARATIONS
        scopeProvider = TestFirScopeProvider
        classKind = ClassKind.CLASS
        this.typeParameters += typeParameters
    }
    session.register(FirSymbolProvider::class, SingleClassSymbolProvider(session, symbol))
}

private object TestFirScopeProvider : FirScopeProvider() {
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
    ): FirScope = object : FirScope() {
        override fun withReplacedSessionOrNull(newSession: FirSession, newScopeSession: ScopeSession): FirScope? = null
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

private class SingleClassSymbolProvider(
    session: FirSession,
    private val classSymbol: FirRegularClassSymbol,
) : FirSymbolProvider(session) {
    override val symbolNamesProvider = FirNullSymbolNamesProvider

    override fun getClassLikeSymbolByClassId(classId: ClassId): FirClassLikeSymbol<*>? =
        classSymbol.takeIf { it.classId == classId }

    override fun getTopLevelCallableSymbolsTo(
        destination: MutableList<FirCallableSymbol<*>>,
        packageFqName: FqName,
        name: Name,
    ) = Unit

    override fun getTopLevelFunctionSymbolsTo(
        destination: MutableList<FirNamedFunctionSymbol>,
        packageFqName: FqName,
        name: Name,
    ) = Unit

    override fun getTopLevelPropertySymbolsTo(
        destination: MutableList<FirPropertySymbol>,
        packageFqName: FqName,
        name: Name,
    ) = Unit

    override fun hasPackage(fqName: FqName): Boolean = fqName == classSymbol.classId.packageFqName
}
