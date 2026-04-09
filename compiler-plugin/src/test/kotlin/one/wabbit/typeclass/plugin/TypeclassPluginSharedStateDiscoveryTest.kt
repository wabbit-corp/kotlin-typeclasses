// SPDX-License-Identifier: AGPL-3.0-or-later

@file:OptIn(
    org.jetbrains.kotlin.fir.PrivateSessionConstructor::class,
    org.jetbrains.kotlin.fir.SessionConfiguration::class,
    org.jetbrains.kotlin.fir.resolve.providers.FirSymbolProviderInternals::class,
    org.jetbrains.kotlin.fir.scopes.DelicateScopeAPI::class,
    org.jetbrains.kotlin.fir.symbols.SymbolInternals::class,
)

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirBinaryDependenciesModuleData
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirScript
import org.jetbrains.kotlin.fir.declarations.builder.buildRegularClass
import org.jetbrains.kotlin.fir.declarations.impl.FirResolvedDeclarationStatusImpl
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.providers.DEPENDENCIES_SYMBOL_PROVIDER_QUALIFIED_KEY
import org.jetbrains.kotlin.fir.resolve.providers.FirEmptySymbolNamesProvider
import org.jetbrains.kotlin.fir.resolve.providers.FirProvider
import org.jetbrains.kotlin.fir.resolve.providers.FirSymbolNamesProvider
import org.jetbrains.kotlin.fir.resolve.providers.FirSymbolProvider
import org.jetbrains.kotlin.fir.scopes.FirContainingNamesAwareScope
import org.jetbrains.kotlin.fir.scopes.FirScope
import org.jetbrains.kotlin.fir.scopes.FirScopeProvider
import org.jetbrains.kotlin.fir.scopes.FirTypeScope
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirReplSnippetSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirScriptSymbol
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import java.lang.reflect.InvocationTargetException
import kotlin.test.Test
import kotlin.test.assertTrue

class TypeclassPluginSharedStateDiscoveryTest {
    @Test
    fun `buildDiscoveryIndexes eagerly scans advertised top level declarations`() {
        val session = object : FirSession(FirSession.Kind.Library) {}
        val classId = ClassId.topLevel(FqName("demo.discovery.Visible"))
        val classSymbol = buildTopLevelRegularClass(session, classId)
        val provider = SingleTopLevelClassSymbolProvider(session, classSymbol)

        session.register(FirSymbolProvider::class, provider)
        session.register(FirProvider::class, SingleSymbolFirProvider(provider))
        session.register(DEPENDENCIES_SYMBOL_PROVIDER_QUALIFIED_KEY, EmptySymbolProvider(session))

        val state = TypeclassPluginSharedState()
        val index = invokeBuildDiscoveryIndexes(state, session)
        val classInfoById = extractClassInfoById(index)

        assertTrue(
            classInfoById.containsKey(classId.asString()),
            "Discovery indexes should eagerly record advertised top-level classes before any lazy lookup runs.",
        )
    }
}

private fun invokeBuildDiscoveryIndexes(
    state: TypeclassPluginSharedState,
    session: FirSession,
): Any =
    try {
        TypeclassPluginSharedState::class.java
            .getDeclaredMethod("buildDiscoveryIndexes", FirSession::class.java)
            .apply { isAccessible = true }
            .invoke(state, session)
    } catch (target: InvocationTargetException) {
        throw (target.cause ?: target)
    }

@Suppress("UNCHECKED_CAST")
private fun extractClassInfoById(index: Any): Map<String, Any> =
    index.javaClass
        .getDeclaredField("classInfoById")
        .apply { isAccessible = true }
        .get(index) as Map<String, Any>

private fun buildTopLevelRegularClass(
    session: FirSession,
    classId: ClassId,
): FirRegularClassSymbol {
    val symbol = FirRegularClassSymbol(classId)
    buildRegularClass {
        moduleData = discoveryTestModuleData(session)
        origin = org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin.Library
        name = classId.shortClassName
        this.symbol = symbol
        status = FirResolvedDeclarationStatusImpl.DEFAULT_STATUS_FOR_STATUSLESS_DECLARATIONS
        scopeProvider = DiscoveryTestFirScopeProvider
        classKind = ClassKind.CLASS
        superTypeRefs += session.builtinTypes.anyType
    }
    return symbol
}

private fun discoveryTestModuleData(session: FirSession): FirBinaryDependenciesModuleData =
    FirBinaryDependenciesModuleData(Name.special("<fir-discovery-test>")).apply {
        bindSession(session)
    }

private object DiscoveryTestFirScopeProvider : FirScopeProvider() {
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

private class SingleTopLevelClassSymbolProvider(
    session: FirSession,
    private val classSymbol: FirRegularClassSymbol,
) : FirSymbolProvider(session) {
    override val symbolNamesProvider: FirSymbolNamesProvider =
        object : FirSymbolNamesProvider() {
            override fun getPackageNames(): Set<String> = setOf(classSymbol.classId.packageFqName.asString())

            override val hasSpecificClassifierPackageNamesComputation: Boolean get() = true

            override fun getPackageNamesWithTopLevelClassifiers(): Set<String> =
                setOf(classSymbol.classId.packageFqName.asString())

            override fun getTopLevelClassifierNamesInPackage(packageFqName: FqName): Set<Name> =
                if (packageFqName == classSymbol.classId.packageFqName) {
                    setOf(classSymbol.classId.shortClassName)
                } else {
                    emptySet()
                }

            override val hasSpecificCallablePackageNamesComputation: Boolean get() = true

            override fun getPackageNamesWithTopLevelCallables(): Set<String> = emptySet()

            override fun getTopLevelCallableNamesInPackage(packageFqName: FqName): Set<Name> = emptySet()
        }

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

private class EmptySymbolProvider(
    session: FirSession,
) : FirSymbolProvider(session) {
    override val symbolNamesProvider = FirEmptySymbolNamesProvider

    override fun getClassLikeSymbolByClassId(classId: ClassId): FirClassLikeSymbol<*>? = null

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

    override fun hasPackage(fqName: FqName): Boolean = false
}

private class SingleSymbolFirProvider(
    override val symbolProvider: FirSymbolProvider,
) : FirProvider() {
    override fun getFirClassifierByFqName(classId: ClassId): FirClassLikeDeclaration? =
        symbolProvider.getClassLikeSymbolByClassId(classId)?.fir

    override fun getFirClassifierContainerFile(fqName: ClassId): FirFile =
        error("Unexpected classifier container lookup in discovery bootstrap test.")

    override fun getFirClassifierContainerFileIfAny(fqName: ClassId): FirFile? = null

    override fun getFirCallableContainerFile(symbol: FirCallableSymbol<*>): FirFile? = null

    override fun getFirScriptContainerFile(symbol: FirScriptSymbol): FirFile? = null

    override fun getFirScriptByFilePath(path: String): FirScriptSymbol? = null

    override fun getFirReplSnippetContainerFile(symbol: FirReplSnippetSymbol): FirFile? = null

    override fun getFirFilesByPackage(fqName: FqName): List<FirFile> = emptyList()

    override fun getClassNamesInPackage(fqName: FqName): Set<Name> =
        symbolProvider.symbolNamesProvider.getTopLevelClassifierNamesInPackage(fqName).orEmpty()
}
