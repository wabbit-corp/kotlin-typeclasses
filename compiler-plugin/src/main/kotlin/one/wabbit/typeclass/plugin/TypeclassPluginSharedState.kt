// SPDX-License-Identifier: AGPL-3.0-or-later

@file:OptIn(
    org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess::class,
    org.jetbrains.kotlin.fir.symbols.SymbolInternals::class,
)

package one.wabbit.typeclass.plugin

import one.wabbit.typeclass.plugin.model.InstanceRule
import one.wabbit.typeclass.plugin.model.ResolutionSearchResult
import one.wabbit.typeclass.plugin.model.TcType
import one.wabbit.typeclass.plugin.model.TcTypeParameter
import one.wabbit.typeclass.plugin.model.TypeclassResolutionPlanner
import one.wabbit.typeclass.plugin.model.containsStarProjection
import one.wabbit.typeclass.plugin.model.isExactTypeIdentity
import one.wabbit.typeclass.plugin.model.normalizedKey
import one.wabbit.typeclass.plugin.model.referencedVariableIds
import one.wabbit.typeclass.plugin.model.render
import one.wabbit.typeclass.plugin.model.substituteType
import one.wabbit.typeclass.plugin.model.unifyTypes
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.extractEnumValueArgumentInfo
import org.jetbrains.kotlin.fir.declarations.getSealedClassInheritors
import org.jetbrains.kotlin.fir.declarations.findArgumentByName
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.declarations.unwrapVarargValue
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.FirClassReferenceExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression
import org.jetbrains.kotlin.fir.resolve.providers.firProvider
import org.jetbrains.kotlin.fir.resolve.providers.dependenciesSymbolProvider
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.providers.FirCompositeSymbolNamesProvider
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeIntegerLiteralType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.ProjectionKind
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.lowerBoundIfFlexible
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.name.*
import org.jetbrains.kotlin.types.Variance
import java.io.File
import java.util.Collections
import java.util.WeakHashMap

internal class TypeclassPluginSharedState(
    internal val configuration: TypeclassConfiguration = TypeclassConfiguration(),
    private val binaryClassPathRoots: List<File> = emptyList(),
) {
    private val discoveryIndexes = SessionScopedCache<FirSession, ResolutionIndex>()
    private val binaryGeneratedMetadataLoader = BinaryGeneratedDerivedMetadataLoader(binaryClassPathRoots)
    private val irImportedStateLock = Any()
    @Volatile
    private var latestIrImportedTopLevelRules: List<ImportedTopLevelInstanceRule> = emptyList()
    @Volatile
    private var latestIrImportedGeneratedDerivedMetadataByOwner: Map<String, List<GeneratedDerivedMetadata>> = emptyMap()

    fun importedTopLevelRulesForIr(): List<ImportedTopLevelInstanceRule> = latestIrImportedTopLevelRules

    fun importedGeneratedDerivedMetadataForIr(owner: ClassId): List<GeneratedDerivedMetadata> =
        latestIrImportedGeneratedDerivedMetadataByOwner[owner.asString()].orEmpty()

    fun binaryGeneratedDerivedMetadataForIr(owner: ClassId): List<GeneratedDerivedMetadata> =
        binaryGeneratedMetadataLoader.generatedMetadataFor(owner)

    fun provablySubtype(
        sub: TcType,
        sup: TcType,
        exactBuiltinGoalContext: FirBuiltinGoalExactContext? = null,
    ): Boolean =
        provablySupportsBuiltinSubtypeGoal(
            goal = TcType.Constructor(SUBTYPE_CLASS_ID.asString(), listOf(sub, sup)),
            classInfoById = resolutionIndex(exactBuiltinGoalContext?.session ?: error("Missing FIR session for subtype check")).classInfoById,
            exactContext = exactBuiltinGoalContext,
        )

    fun rulesForGoal(
        session: FirSession,
        goal: TcType,
        canMaterializeVariable: (String) -> Boolean = { true },
        builtinGoalAcceptance: BuiltinGoalAcceptance = BuiltinGoalAcceptance.ALLOW_SPECULATIVE,
        exactBuiltinGoalContext: FirBuiltinGoalExactContext? = null,
    ): List<InstanceRule> =
        resolutionIndex(session).rulesForGoal(
            goal = goal,
            session = session,
            canMaterializeVariable = canMaterializeVariable,
            builtinGoalAcceptance = builtinGoalAcceptance,
            exactBuiltinGoalContext = exactBuiltinGoalContext,
        )

    fun refinementRulesForGoal(
        session: FirSession,
        goal: TcType,
        canMaterializeVariable: (String) -> Boolean = { true },
        exactBuiltinGoalContext: FirBuiltinGoalExactContext? = null,
    ): List<InstanceRule> =
        resolutionIndex(session).refinementRulesForGoal(
            goal = goal,
            session = session,
            canMaterializeVariable = canMaterializeVariable,
            exactBuiltinGoalContext = exactBuiltinGoalContext,
        )

    fun canDeriveGoal(
        session: FirSession,
        goal: TcType,
        availableContexts: List<TcType> = emptyList(),
        canMaterializeVariable: (String) -> Boolean = { true },
        builtinGoalAcceptance: BuiltinGoalAcceptance = BuiltinGoalAcceptance.ALLOW_SPECULATIVE,
        exactBuiltinGoalContext: FirBuiltinGoalExactContext? = null,
    ): Boolean =
        resolutionIndex(session).canDeriveGoal(
            goal = goal,
            session = session,
            availableContexts = availableContexts,
            canMaterializeVariable = canMaterializeVariable,
            builtinGoalAcceptance = builtinGoalAcceptance,
            exactBuiltinGoalContext = exactBuiltinGoalContext,
        )

    fun allowedAssociatedOwnersForProvidedType(
        session: FirSession,
        providedType: TcType,
    ): Set<ClassId> = resolutionIndex(session).allowedAssociatedOwnersForGoal(providedType, session)

    fun declarationShapeDerivationFailure(
        session: FirSession,
        directTypeclassId: String,
        declaration: FirRegularClass,
    ): String? =
        resolutionIndex(session).declarationShapeDerivationFailure(
            directTypeclassId = directTypeclassId,
            declaration = declaration,
            session = session,
        )

    fun exactShapeDerivationFailure(
        session: FirSession,
        directTypeclassId: String,
        declaration: FirRegularClass,
    ): String? =
        resolutionIndex(session).exactShapeDerivationFailure(
            directTypeclassId = directTypeclassId,
            declaration = declaration,
            session = session,
        )

    fun isTypeclassType(
        session: FirSession,
        type: ConeKotlinType,
    ): Boolean = isTypeclassType(type, session, configuration)

    private fun resolutionIndex(session: FirSession): ResolutionIndex =
        discoveryIndexes(session)

    private fun discoveryIndexes(session: FirSession): ResolutionIndex =
        discoveryIndexes.getOrPut(session) {
            buildDiscoveryIndexes(session)
        }

    private fun recordImportedTopLevelRulesForIr(rules: List<VisibleInstanceRule>) {
        val importedRules =
            rules
                .asSequence()
                .filter(VisibleInstanceRule::isFromDependencyBinary)
                .mapNotNull { visibleRule ->
                    visibleRule.lookupReference?.let { lookupReference ->
                        ImportedTopLevelInstanceRule(
                            rule = visibleRule.rule,
                            reference = lookupReference,
                        )
                    }
                }.toList()
        if (importedRules.isEmpty()) {
            return
        }
        synchronized(irImportedStateLock) {
            latestIrImportedTopLevelRules =
                (latestIrImportedTopLevelRules + importedRules)
                    .distinctBy { importedRule -> importedRule.rule.id to importedRule.reference }
        }
    }

    private fun recordImportedGeneratedDerivedMetadataForIr(
        ownerId: String,
        metadata: List<GeneratedDerivedMetadata>,
    ) {
        if (metadata.isEmpty()) {
            return
        }
        synchronized(irImportedStateLock) {
            val existingByOwner = latestIrImportedGeneratedDerivedMetadataByOwner
            val mergedEntries = (existingByOwner[ownerId].orEmpty() + metadata).distinct()
            latestIrImportedGeneratedDerivedMetadataByOwner = existingByOwner + (ownerId to mergedEntries)
        }
    }

    private fun buildDiscoveryIndexes(session: FirSession): ResolutionIndex {
        BinaryGeneratedDerivedMetadataRegistry.install(session, binaryGeneratedMetadataLoader)
        val scanner = FirResolutionScanner(session, configuration)
        return scanner.build(
            recordImportedTopLevelRulesForIr = ::recordImportedTopLevelRulesForIr,
            recordImportedGeneratedDerivedMetadataForIr = ::recordImportedGeneratedDerivedMetadataForIr,
        )
    }
}

private fun FirSession.topLevelDeclarationProviders(): List<org.jetbrains.kotlin.fir.resolve.providers.FirSymbolProvider> =
    buildList {
        add(firProvider.symbolProvider)
        add(dependenciesSymbolProvider)
    }.distinct()

internal interface TopLevelDeclarationsInPackageSource<D> {
    fun declarationsInPackage(packageName: FqName): Sequence<D>
}

internal fun <D> scanTopLevelDeclarationsInPackage(
    packageName: FqName,
    source: TopLevelDeclarationsInPackageSource<D>,
    visit: (D) -> Unit,
) {
    source.declarationsInPackage(packageName).forEach(visit)
}

private class FirTopLevelDeclarationsInPackageSource(
    private val symbolProviders: List<org.jetbrains.kotlin.fir.resolve.providers.FirSymbolProvider>,
) : TopLevelDeclarationsInPackageSource<FirDeclaration> {
    override fun declarationsInPackage(packageName: FqName): Sequence<FirDeclaration> =
        sequence {
            val symbolNamesProvider = FirCompositeSymbolNamesProvider.fromSymbolProviders(symbolProviders)
            symbolNamesProvider.getTopLevelCallableNamesInPackage(packageName).orEmpty().forEach { callableName ->
                symbolProviders.forEach { symbolProvider ->
                    symbolProvider.getTopLevelFunctionSymbols(packageName, callableName).forEach { functionSymbol ->
                        yield(functionSymbol.fir)
                    }
                    symbolProvider.getTopLevelPropertySymbols(packageName, callableName).forEach { propertySymbol ->
                        yield(propertySymbol.fir)
                    }
                }
            }
            symbolNamesProvider.getTopLevelClassifierNamesInPackage(packageName).orEmpty().forEach { classifierName ->
                symbolProviders.forEach { symbolProvider ->
                    val classSymbol =
                        symbolProvider.getClassLikeSymbolByClassId(ClassId(packageName, classifierName))
                            as? FirRegularClassSymbol ?: return@forEach
                    yield(classSymbol.fir)
                }
            }
        }
}

internal fun scanTopLevelDeclarationsInPackage(
    packageName: FqName,
    symbolProviders: List<org.jetbrains.kotlin.fir.resolve.providers.FirSymbolProvider>,
    visit: (FirDeclaration) -> Unit,
) {
    scanTopLevelDeclarationsInPackage(
        packageName = packageName,
        source = FirTopLevelDeclarationsInPackageSource(symbolProviders),
        visit = visit,
    )
}

private class FirResolutionScanner(
    private val session: FirSession,
    private val configuration: TypeclassConfiguration,
) {
    private val topLevelRules = mutableListOf<VisibleInstanceRule>()
    private val associatedRulesByOwner = linkedMapOf<ClassId, MutableList<VisibleInstanceRule>>()
    private val classInfoById = linkedMapOf<String, VisibleClassHierarchyInfo>()
    private val derivableTypeclassIdsByOwner = linkedMapOf<String, MutableSet<String>>()
    private val deriveEquivTargetIdsByOwner = linkedMapOf<String, MutableSet<String>>()
    private val importedGeneratedDerivedMetadataByOwner = linkedMapOf<String, MutableSet<GeneratedDerivedMetadata>>()

    @OptIn(DirectDeclarationsAccess::class)
    fun scanDeclaration(
        declaration: FirDeclaration,
        associatedOwner: ClassId?,
    ) {
        when (declaration) {
            is FirRegularClass -> {
                val classId = declaration.symbol.classId
                if (!classId.isLocal) {
                    classInfoById[classId.asString()] =
                        VisibleClassHierarchyInfo(
                            superClassifiers =
                                declaration.superTypeRefs.mapNotNull { superTypeRef ->
                                    superTypeRef.coneType.classId?.asString()
                                }.toSet(),
                            isSealed = declaration.status.modality == Modality.SEALED,
                            typeParameterVariances = declaration.typeParameters.map { typeParameter -> typeParameter.symbol.fir.variance },
                        )
                    val derivedTypeclassIds =
                        declaration.supportedDerivedTypeclassIds(session).let { supportedTypeclassIds ->
                            supportedTypeclassIds + supportedTypeclassIds.expandedDerivedTypeclassIds(session, configuration)
                        }
                    if (derivedTypeclassIds.isNotEmpty()) {
                        derivableTypeclassIdsByOwner.getOrPut(classId.asString(), ::linkedSetOf) += derivedTypeclassIds
                    }
                    if (declaration.typeParameters.isEmpty()) {
                        val deriveEquivTargets =
                            declaration.deriveEquivRequests(session).filterTo(linkedSetOf()) { otherClassId ->
                                runCatching { ClassId.fromString(otherClassId) }.getOrNull()
                                    ?.let { otherClassIdValue ->
                                        session.regularClassSymbolOrNull(otherClassIdValue)?.fir?.typeParameters?.isEmpty() == true
                                    } == true
                            }
                        if (deriveEquivTargets.isNotEmpty()) {
                            deriveEquivTargetIdsByOwner.getOrPut(classId.asString(), ::linkedSetOf) += deriveEquivTargets
                        }
                    }
                    if (declaration.source == null) {
                        val generatedMetadata = declaration.generatedDerivedMetadata(session)
                        if (generatedMetadata.isNotEmpty()) {
                            importedGeneratedDerivedMetadataByOwner.getOrPut(classId.asString(), ::linkedSetOf) += generatedMetadata
                        }
                    }
                }

                val nextAssociatedOwner =
                    when {
                        declaration.isTypeclassCompanionDeclaration() -> classId.outerClassId
                        associatedOwner != null -> associatedOwner
                        else -> null
                    }

                declaration.toObjectRules(session, configuration).forEach { rule ->
                    if (nextAssociatedOwner == null) {
                        topLevelRules += rule
                    } else {
                        associatedRulesByOwner.getOrPut(nextAssociatedOwner, ::mutableListOf) +=
                            rule.copy(associatedOwner = nextAssociatedOwner)
                    }
                }

                declaration.declarations.forEach { nestedDeclaration ->
                    scanDeclaration(nestedDeclaration, associatedOwner = nextAssociatedOwner)
                }
            }

            is FirTypeclassFunctionDeclaration -> {
                declaration.toFunctionRules(session, configuration).forEach { rule ->
                    if (associatedOwner == null) {
                        topLevelRules += rule
                    } else {
                        associatedRulesByOwner.getOrPut(associatedOwner, ::mutableListOf) +=
                            rule.copy(associatedOwner = associatedOwner)
                    }
                }
            }

            is FirProperty -> {
                declaration.toPropertyRules(session, configuration).forEach { rule ->
                    if (associatedOwner == null) {
                        topLevelRules += rule
                    } else {
                        associatedRulesByOwner.getOrPut(associatedOwner, ::mutableListOf) +=
                            rule.copy(associatedOwner = associatedOwner)
                    }
                }
            }

            else -> Unit
        }
    }

    fun build(
        recordImportedTopLevelRulesForIr: (List<VisibleInstanceRule>) -> Unit,
        recordImportedGeneratedDerivedMetadataForIr: (String, List<GeneratedDerivedMetadata>) -> Unit,
    ): ResolutionIndex =
        ResolutionIndex(
            configuration = configuration,
            topLevelRules = topLevelRules.toList() + configuration.builtinRules().map { rule ->
                VisibleInstanceRule(rule = rule, associatedOwner = null)
            },
            recordImportedTopLevelRulesForIr = recordImportedTopLevelRulesForIr,
            recordImportedGeneratedDerivedMetadataForIr = recordImportedGeneratedDerivedMetadataForIr,
            topLevelRulesByPackage =
                topLevelRules
                    .mapNotNull { visibleRule ->
                        visibleRule.lookupReference?.packageFqName()?.let { packageName ->
                            packageName to visibleRule
                        }
                    }.groupBy(
                        keySelector = { (packageName, _) -> packageName },
                        valueTransform = { (_, visibleRule) -> visibleRule },
                    ),
            associatedRulesByOwner = associatedRulesByOwner.mapValues { (_, rules) -> rules.toList() },
            classInfoById = classInfoById.toMutableMap(),
            derivableTypeclassIdsByOwner =
                derivableTypeclassIdsByOwner.mapValues { (_, typeclassIds) -> typeclassIds.toSet() },
            deriveEquivTargetIdsByOwner =
                deriveEquivTargetIdsByOwner.mapValues { (_, targetIds) -> targetIds.toSet() },
        ).also {
            importedGeneratedDerivedMetadataByOwner.forEach { (ownerId, metadata) ->
                recordImportedGeneratedDerivedMetadataForIr(ownerId, metadata.toList())
            }
        }
}

private data class ResolutionIndex(
    val configuration: TypeclassConfiguration,
    val topLevelRules: List<VisibleInstanceRule>,
    val recordImportedTopLevelRulesForIr: (List<VisibleInstanceRule>) -> Unit,
    val recordImportedGeneratedDerivedMetadataForIr: (String, List<GeneratedDerivedMetadata>) -> Unit,
    val topLevelRulesByPackage: Map<FqName, List<VisibleInstanceRule>>,
    val associatedRulesByOwner: Map<ClassId, List<VisibleInstanceRule>>,
    val classInfoById: MutableMap<String, VisibleClassHierarchyInfo>,
    val derivableTypeclassIdsByOwner: Map<String, Set<String>>,
    val deriveEquivTargetIdsByOwner: Map<String, Set<String>>,
) {
    private val lazilyDiscoveredTopLevelRulesByPackage: MutableMap<FqName, List<VisibleInstanceRule>> = linkedMapOf()
    private val lazilyDiscoveredAssociatedRulesByOwner: MutableMap<ClassId, List<VisibleInstanceRule>> = linkedMapOf()
    private val lazilyDiscoveredDerivableTypeclassIdsByOwner: MutableMap<String, Set<String>> = linkedMapOf()
    private val lazilyDiscoveredDeriveEquivTargetIdsByOwner: MutableMap<String, Set<String>> = linkedMapOf()

    private data class ShapeDerivationOption(
        val targetOwnerId: String,
        val directTypeclassId: String,
        val targetType: TcType.Constructor,
        val prerequisiteTypes: List<TcType>,
        val priority: Int,
    )

    private data class DeriveViaOption(
        val ownerId: String,
        val directTypeclassId: String,
        val prerequisiteType: TcType.Constructor,
        val pathKey: String,
    )

    fun rulesForGoal(
        goal: TcType,
        session: FirSession,
        canMaterializeVariable: (String) -> Boolean,
        builtinGoalAcceptance: BuiltinGoalAcceptance = BuiltinGoalAcceptance.ALLOW_SPECULATIVE,
        exactBuiltinGoalContext: FirBuiltinGoalExactContext? = null,
    ): List<InstanceRule> {
        val owners = allowedAssociatedOwnersForGoal(goal, session)
        val topLevel =
            topLevelRules + discoverTopLevelRulesForGoal(goal, session)
        val associated =
            owners.flatMapTo(linkedSetOf()) { owner ->
                associatedRulesByOwner[owner].orEmpty() + discoverAssociatedRules(owner, session)
            }
        val resolvedRules =
            (topLevel + associated)
            .asSequence()
            .filter { visibleRule ->
                builtinRuleCanMatchGoalHead(visibleRule.rule.id, goal)
            }
            .filter { visibleRule ->
                visibleRule.rule.id != "builtin:kclass" || supportsBuiltinKClassGoal(goal, canMaterializeVariable)
            }
            .filter { visibleRule ->
                visibleRule.rule.id != "builtin:subtype" ||
                    builtinGoalAcceptance.accepts(
                        if (builtinGoalAcceptance == BuiltinGoalAcceptance.PROVABLE_ONLY) {
                            if (provablySupportsBuiltinSubtypeGoal(goal, classInfoById, exactBuiltinGoalContext)) {
                                BuiltinGoalFeasibility.PROVABLE
                            } else {
                                BuiltinGoalFeasibility.IMPOSSIBLE
                            }
                        } else if (supportsBuiltinSubtypeGoal(goal, classInfoById, exactBuiltinGoalContext)) {
                            if (provablySupportsBuiltinSubtypeGoal(goal, classInfoById, exactBuiltinGoalContext)) {
                                BuiltinGoalFeasibility.PROVABLE
                            } else {
                                BuiltinGoalFeasibility.SPECULATIVE
                            }
                        } else {
                            BuiltinGoalFeasibility.IMPOSSIBLE
                        },
                    )
            }
            .filter { visibleRule ->
                visibleRule.rule.id != "builtin:strict-subtype" ||
                    builtinGoalAcceptance.accepts(
                        if (builtinGoalAcceptance == BuiltinGoalAcceptance.PROVABLE_ONLY) {
                            if (provablySupportsBuiltinStrictSubtypeGoal(goal, classInfoById, exactBuiltinGoalContext)) {
                                BuiltinGoalFeasibility.PROVABLE
                            } else {
                                BuiltinGoalFeasibility.IMPOSSIBLE
                            }
                        } else if (supportsBuiltinStrictSubtypeGoal(goal, classInfoById, exactBuiltinGoalContext)) {
                            if (provablySupportsBuiltinStrictSubtypeGoal(goal, classInfoById, exactBuiltinGoalContext)) {
                                BuiltinGoalFeasibility.PROVABLE
                            } else {
                                BuiltinGoalFeasibility.SPECULATIVE
                            }
                        } else {
                            BuiltinGoalFeasibility.IMPOSSIBLE
                        },
                    )
            }
            .filter { visibleRule ->
                visibleRule.rule.id != "builtin:kserializer" || supportsBuiltinKSerializerGoal(goal, session, canMaterializeVariable)
            }
            .filter { visibleRule ->
                visibleRule.rule.id != "builtin:notsame" ||
                    supportsBuiltinNotSameGoal(goal, classInfoById, exactBuiltinGoalContext)
            }
            .filter { visibleRule ->
                visibleRule.rule.id != "builtin:nullable" ||
                    builtinGoalAcceptance.accepts(
                        if (builtinGoalAcceptance == BuiltinGoalAcceptance.PROVABLE_ONLY) {
                            if (provablySupportsBuiltinNullableGoal(goal, exactBuiltinGoalContext)) {
                                BuiltinGoalFeasibility.PROVABLE
                            } else {
                                BuiltinGoalFeasibility.IMPOSSIBLE
                            }
                        } else if (supportsBuiltinNullableGoal(goal, exactBuiltinGoalContext)) {
                            if (provablySupportsBuiltinNullableGoal(goal, exactBuiltinGoalContext)) {
                                BuiltinGoalFeasibility.PROVABLE
                            } else {
                                BuiltinGoalFeasibility.SPECULATIVE
                            }
                        } else {
                            BuiltinGoalFeasibility.IMPOSSIBLE
                        },
                    )
            }
            .filter { visibleRule ->
                visibleRule.rule.id != "builtin:not-nullable" ||
                    builtinGoalAcceptance.accepts(
                        if (builtinGoalAcceptance == BuiltinGoalAcceptance.PROVABLE_ONLY) {
                            if (provablySupportsBuiltinNotNullableGoal(goal, exactBuiltinGoalContext)) {
                                BuiltinGoalFeasibility.PROVABLE
                            } else {
                                BuiltinGoalFeasibility.IMPOSSIBLE
                            }
                        } else if (supportsBuiltinNotNullableGoal(goal, exactBuiltinGoalContext)) {
                            if (provablySupportsBuiltinNotNullableGoal(goal, exactBuiltinGoalContext)) {
                                BuiltinGoalFeasibility.PROVABLE
                            } else {
                                BuiltinGoalFeasibility.SPECULATIVE
                            }
                        } else {
                            BuiltinGoalFeasibility.IMPOSSIBLE
                        },
                    )
            }
            .filter { visibleRule ->
                visibleRule.rule.id != "builtin:is-typeclass-instance" ||
                    builtinGoalAcceptance.accepts(
                        if (builtinGoalAcceptance == BuiltinGoalAcceptance.PROVABLE_ONLY) {
                            if (
                                provablySupportsBuiltinIsTypeclassInstanceGoal(
                                    goal = goal,
                                    isTypeclassClassifier = { classifierId ->
                                        configuration.supportsTypeclassClassifierId(classifierId, session)
                                    },
                                    exactContext = exactBuiltinGoalContext,
                                )
                            ) {
                                BuiltinGoalFeasibility.PROVABLE
                            } else {
                                BuiltinGoalFeasibility.IMPOSSIBLE
                            }
                        } else if (
                            supportsBuiltinIsTypeclassInstanceGoal(
                                goal = goal,
                                isTypeclassClassifier = { classifierId ->
                                    configuration.supportsTypeclassClassifierId(classifierId, session)
                                },
                                exactContext = exactBuiltinGoalContext,
                            )
                        ) {
                            if (
                                provablySupportsBuiltinIsTypeclassInstanceGoal(
                                    goal = goal,
                                    isTypeclassClassifier = { classifierId ->
                                        configuration.supportsTypeclassClassifierId(classifierId, session)
                                    },
                                    exactContext = exactBuiltinGoalContext,
                                )
                            ) {
                                BuiltinGoalFeasibility.PROVABLE
                            } else {
                                BuiltinGoalFeasibility.SPECULATIVE
                            }
                        } else {
                            BuiltinGoalFeasibility.IMPOSSIBLE
                        },
                    )
            }
            .filter { visibleRule ->
                visibleRule.rule.id != "builtin:known-type" || supportsBuiltinKnownTypeGoal(goal, canMaterializeVariable)
            }
            .filter { visibleRule ->
                visibleRule.rule.id != "builtin:type-id" || supportsBuiltinTypeIdGoal(goal, canMaterializeVariable)
            }
            .filter { visibleRule ->
                visibleRule.rule.id != "builtin:same-type-constructor" || supportsBuiltinSameTypeConstructorGoal(goal)
            }
            .distinctBy { visibleRule -> visibleRule.rule.id }
            .map(VisibleInstanceRule::rule)
            .toList()
        return resolvedRules
    }

    fun refinementRulesForGoal(
        goal: TcType,
        session: FirSession,
        canMaterializeVariable: (String) -> Boolean,
        exactBuiltinGoalContext: FirBuiltinGoalExactContext? = null,
    ): List<InstanceRule> =
        (
            rulesForGoal(
                goal = goal,
                session = session,
                canMaterializeVariable = canMaterializeVariable,
                builtinGoalAcceptance = BuiltinGoalAcceptance.PROVABLE_ONLY,
                exactBuiltinGoalContext = exactBuiltinGoalContext,
            ) +
                derivedRefinementRulesForGoal(goal, session)
        ).distinctBy(InstanceRule::directIdentityKey)

    private fun derivedRefinementRulesForGoal(
        goal: TcType,
        session: FirSession,
    ): List<InstanceRule> {
        val constructor = goal as? TcType.Constructor ?: return emptyList()
        return buildList {
            addAll(shapeDerivedRefinementRules(constructor, session))
            addAll(deriveViaRefinementRules(constructor, session))
            addAll(deriveEquivRefinementRules(constructor, session))
        }
    }

    private fun shapeDerivedRefinementRules(
        goal: TcType.Constructor,
        session: FirSession,
    ): List<InstanceRule> =
        shapeDerivationOptionsForGoal(goal, session).map { option ->
            buildShapeDerivedRefinementRule(
                goal = goal,
                targetOwnerId = option.targetOwnerId,
                directTypeclassId = option.directTypeclassId,
                targetType = option.targetType,
                prerequisiteTypes = option.prerequisiteTypes,
                priority = option.priority,
            )
        }

    private fun shapeDerivationOptionsForGoal(
        goal: TcType.Constructor,
        session: FirSession,
    ): List<ShapeDerivationOption> {
        if (goal.classifierId == EQUIV_CLASS_ID.asString()) {
            return emptyList()
        }
        return associatedOwnerIds(goal, session).flatMap { ownerId ->
            val ownerClassId = runCatching { ClassId.fromString(ownerId) }.getOrNull() ?: return@flatMap emptyList()
            val ownerDeclaration = session.regularClassSymbolOrNull(ownerClassId)?.fir ?: return@flatMap emptyList()
            ownerDeclaration.matchingShapeDerivedGoalMatches(goal, session, configuration).mapNotNull { match ->
                val targetClassId = runCatching { ClassId.fromString(match.targetType.classifierId) }.getOrNull() ?: return@mapNotNull null
                if (ownerId !in derivationOwnersForTarget(targetClassId.asString(), session)) {
                    return@mapNotNull null
                }
                val targetDeclaration = session.regularClassSymbolOrNull(targetClassId)?.fir ?: return@mapNotNull null
                val prerequisiteTypes =
                    exactShapeDerivedPrerequisiteGoals(
                        directTypeclassId = match.directTypeclassId,
                        targetType = match.targetType,
                        declaration = targetDeclaration,
                        session = session,
                    ) ?: return@mapNotNull null
                ShapeDerivationOption(
                    targetOwnerId = targetClassId.asString(),
                    directTypeclassId = match.directTypeclassId,
                    targetType = match.targetType,
                    prerequisiteTypes = prerequisiteTypes,
                    priority =
                        if (targetDeclaration.status.modality == Modality.SEALED) {
                            match.targetType.gadtSpecificityScore()
                        } else {
                            0
                        },
                )
            }
        }.distinctBy { option ->
            listOf(
                option.targetOwnerId,
                option.directTypeclassId,
                option.targetType.normalizedKey(),
                option.prerequisiteTypes.joinToString("|", transform = TcType::normalizedKey),
            ).joinToString("::")
        }
    }

    private fun exactShapeDerivedPrerequisiteGoals(
        directTypeclassId: String,
        targetType: TcType.Constructor,
        declaration: FirRegularClass,
        session: FirSession,
        reportFailure: ((String) -> Unit)? = null,
    ): List<TcType>? {
        fun fail(message: String): List<TcType>? {
            reportFailure?.invoke(message)
            return null
        }
        if (!declaration.supportsDeriveShape()) {
            return fail("@Derive is only supported on sealed or final classes and objects.")
        }
        val typeclassClassId = runCatching { ClassId.fromString(directTypeclassId) }.getOrNull()
            ?: return fail("@Derive currently only supports typeclasses with exactly one type parameter")
        declaration.requiredDeriveMethodContractForDeriveShape()?.let { contract ->
            if (typeclassCompanionResolveDeriveMethod(typeclassClassId, contract, session) == null) {
                val typeclassName = typeclassClassId.shortClassName.asString()
                return fail(
                    if (contract == DeriveMethodContract.ENUM) {
                        "$typeclassName companion must override deriveEnum to derive enum classes"
                    } else {
                        val companionId =
                            typeclassCompanionSymbol(typeclassClassId, session)?.classId?.asString()
                                ?: typeclassClassId.asString()
                        "Typeclass deriver $companionId is missing ${contract.methodName}"
                    },
                )
            }
        }
        if (declaration.classKind == ClassKind.ENUM_CLASS) {
            return emptyList()
        }
        if (declaration.status.modality == Modality.SEALED) {
            return exactSealedShapeDerivedPrerequisiteGoals(
                directTypeclassId = directTypeclassId,
                targetType = targetType,
                declaration = declaration,
                session = session,
                reportFailure = reportFailure,
            )
        }
        val ruleTypeParameters =
            declaration.typeParameters.mapIndexed { index, typeParameter ->
                TcTypeParameter(
                    id = "refine-derived:${declaration.symbol.classId.asString()}#$index",
                    displayName = typeParameter.symbol.name.asString(),
                )
            }
        val typeParameterBySymbol =
            declaration.typeParameters.zip(ruleTypeParameters).associate { (typeParameter, parameter) ->
                typeParameter.symbol to parameter
            }
        val appliedBindings =
            ruleTypeParameters.zip(targetType.arguments)
                .associate { (parameter, appliedType) -> parameter.id to appliedType }
        val storedProperties =
            declaration.declarations
                .filterIsInstance<FirProperty>()
                .filter { property -> property.getter != null && property.backingField != null }
        val visibleStoredProperties =
            storedProperties.map { property ->
                val getter = property.getter ?: return fail("Cannot derive ${declaration.symbol.classId.asFqNameString()} because constructive product derivation requires constructor parameters to exactly match stored properties")
                if (property.status.visibility != Visibilities.Public || getter.status.visibility != Visibilities.Public) {
                    return fail(
                        "constructive product derivation requires public stored properties; ${declaration.symbol.classId.asFqNameString()}.${property.name.asString()} is not public.",
                    )
                }
                property
            }
        if (declaration.classKind == ClassKind.OBJECT) {
            return visibleStoredProperties.map { property ->
                val getter =
                    property.getter
                        ?: return fail("Cannot derive ${declaration.symbol.classId.asFqNameString()} because constructive product derivation requires all stored property types to be representable")
                val fieldType =
                    coneTypeToModel(getter.returnTypeRef.coneType, typeParameterBySymbol)
                        ?: return fail("Cannot derive ${declaration.symbol.classId.asFqNameString()} because constructive product derivation requires all stored property types to be representable")
                TcType.Constructor(
                    classifierId = directTypeclassId,
                    arguments = listOf(fieldType.substituteType(appliedBindings)),
                )
            }
        }
        val constructor =
            declaration.declarations
                .filterIsInstance<org.jetbrains.kotlin.fir.declarations.FirConstructor>()
                .singleOrNull { candidate -> candidate.isPrimary }
                ?: return fail("Cannot derive ${declaration.symbol.classId.asFqNameString()} because constructive product derivation requires a primary constructor")
        if (constructor.status.visibility != Visibilities.Public) {
            return fail("constructive product derivation requires a public primary constructor for ${declaration.symbol.classId.asFqNameString()}.")
        }
        val constructorParameterNames = constructor.valueParameters.map { parameter -> parameter.name.asString() }
        val fieldNames = visibleStoredProperties.map { property -> property.name.asString() }
        if (constructorParameterNames != fieldNames) {
            return fail("Cannot derive ${declaration.symbol.classId.asFqNameString()} because constructive product derivation requires constructor parameters to exactly match stored properties")
        }
        return visibleStoredProperties.map { property ->
            val getter = property.getter ?: return fail("Cannot derive ${declaration.symbol.classId.asFqNameString()} because constructive product derivation requires constructor parameters to exactly match stored properties")
            val fieldType = coneTypeToModel(getter.returnTypeRef.coneType, typeParameterBySymbol)
                ?: return fail("Cannot derive ${declaration.symbol.classId.asFqNameString()} because constructive product derivation requires all stored property types to be representable")
            TcType.Constructor(
                classifierId = directTypeclassId,
                arguments = listOf(fieldType.substituteType(appliedBindings)),
            )
        }
    }

    internal fun declarationShapeDerivationFailure(
        directTypeclassId: String,
        declaration: FirRegularClass,
        session: FirSession,
    ): String? =
        if (declaration.status.modality == Modality.SEALED) {
            declarationSealedShapeDerivationFailure(
                directTypeclassId = directTypeclassId,
                declaration = declaration,
                session = session,
            )
        } else {
            exactShapeDerivationFailure(
                directTypeclassId = directTypeclassId,
                declaration = declaration,
                session = session,
            )
        }

    internal fun exactShapeDerivationFailure(
        directTypeclassId: String,
        declaration: FirRegularClass,
        session: FirSession,
        targetType: TcType.Constructor = declaration.defaultExactShapeDerivationTargetType(),
    ): String? {
        var failure: String? = null
        val prerequisiteGoals =
            exactShapeDerivedPrerequisiteGoals(
                directTypeclassId = directTypeclassId,
                targetType = targetType,
                declaration = declaration,
                session = session,
                reportFailure = { message ->
                    if (failure == null) {
                        failure = message
                    }
                },
            )
        return if (prerequisiteGoals != null) {
            null
        } else {
            failure
        }
    }

    private fun declarationSealedShapeDerivationFailure(
        directTypeclassId: String,
        declaration: FirRegularClass,
        session: FirSession,
    ): String? {
        val rootClassId = declaration.symbol.classId
        val subclassIds = stableSealedSubclassIds(rootClassId, declaration, session)
        if (subclassIds.isEmpty()) {
            return "Cannot derive ${rootClassId.asFqNameString()} because no sealed subclasses are admissible for the requested typeclass"
        }
        val caseInfos =
            subclassIds.map { subclassId ->
                buildFirDerivedSumCaseInfo(
                    rootClassId = rootClassId,
                    subclassId = subclassId,
                    session = session,
                ) ?: return "Cannot derive ${rootClassId.asFqNameString()} because one or more sealed subclasses cannot be expressed from the sealed root's type parameters"
            }
        val typeclassId = runCatching { ClassId.fromString(directTypeclassId) }.getOrNull() ?: return null
        val typeclassInterface = session.regularClassSymbolOrNull(typeclassId)?.fir ?: return null
        val admissionMode = typeclassInterface.firGadtAdmissionMode(session)
        val rootVariances = declaration.typeParameters.map { typeParameter -> typeParameter.symbol.fir.variance }
        val defaultTargetType = declaration.defaultExactShapeDerivationTargetType()
        val candidates =
            when (admissionMode) {
                FirGadtAdmissionMode.CONSERVATIVE_ONLY -> listOf(defaultTargetType)
                FirGadtAdmissionMode.SURFACE_TRUSTED ->
                    caseInfos
                        .map { caseInfo ->
                            caseInfo.projectedHead.toFirDerivedSumCandidate(
                                ownerId = rootClassId.asString(),
                                seed = caseInfo.subclassDeclaration.name.asString(),
                            )
                        }.distinctBy(TcType.Constructor::normalizedKey)
            }
        val conservativeOnly = admissionMode == FirGadtAdmissionMode.CONSERVATIVE_ONLY
        val firstRejectionMessages = mutableListOf<String>()
        val admittedCaseIds = linkedSetOf<String>()
        val unrecoverableCaseMessages = linkedMapOf<String, String>()

        candidates.forEach { candidate ->
            val admissions = caseInfos.map { caseInfo -> caseInfo.admitToTargetCandidate(candidate, rootVariances, conservativeOnly) }
            admissions.forEachIndexed { index, admission ->
                val caseInfo = caseInfos[index]
                val caseId = caseInfo.subclassDeclaration.symbol.classId.asString()
                when (admission) {
                    is FirCaseAdmission.Admitted -> {
                        val caseFailure =
                            exactShapeDerivationFailure(
                                directTypeclassId = directTypeclassId,
                                declaration = caseInfo.subclassDeclaration,
                                session = session,
                                targetType = admission.caseType,
                            )
                        if (caseFailure == null) {
                            admittedCaseIds += caseId
                        } else {
                            val message =
                                "Cannot derive ${rootClassId.asFqNameString()} because sealed subclass ${caseInfo.subclassDeclaration.symbol.classId.asFqNameString()} is not itself derivable: $caseFailure"
                            unrecoverableCaseMessages.putIfAbsent(caseId, message)
                            firstRejectionMessages += message
                        }
                    }
                    FirCaseAdmission.Ignored -> Unit
                    is FirCaseAdmission.Rejected -> {
                        val message = admission.rejectionMessage
                        unrecoverableCaseMessages.putIfAbsent(caseId, message)
                        firstRejectionMessages += message
                    }
                }
            }
            if (conservativeOnly && admissions.any { admission -> admission !is FirCaseAdmission.Admitted }) {
                return firstRejectionMessages.firstOrNull()
                    ?: "Cannot derive ${rootClassId.asFqNameString()} because one or more sealed subclasses require result-head refinements beyond the conservative admissibility policy"
            }
        }

        val unrecoverableCaseMessage =
            caseInfos.firstNotNullOfOrNull { caseInfo ->
                val caseId = caseInfo.subclassDeclaration.symbol.classId.asString()
                if (caseId !in admittedCaseIds) {
                    unrecoverableCaseMessages[caseId]
                } else {
                    null
                }
            }
        if (unrecoverableCaseMessage != null) {
            return unrecoverableCaseMessage
        }

        return if (admittedCaseIds.isEmpty()) {
            firstRejectionMessages.firstOrNull()
                ?: "Cannot derive ${rootClassId.asFqNameString()} because no sealed subclasses are admissible for the requested typeclass"
        } else {
            null
        }
    }

    private fun deriveViaRefinementRules(
        goal: TcType.Constructor,
        session: FirSession,
    ): List<InstanceRule> =
        deriveViaOptionsForGoal(goal, session).map { option ->
            InstanceRule(
                id = "derived-via:${option.directTypeclassId}:${option.ownerId}:${option.pathKey}:${goal.normalizedKey()}",
                typeParameters = emptyList(),
                providedType = goal,
                prerequisiteTypes = listOf(option.prerequisiteType),
            )
        }

    private fun deriveViaOptionsForGoal(
        goal: TcType.Constructor,
        session: FirSession,
    ): List<DeriveViaOption> {
        if (goal.classifierId == EQUIV_CLASS_ID.asString()) {
            return emptyList()
        }
        val planner = FirDirectTransportPlanner(session)
        return associatedOwnerIds(goal, session).flatMap { ownerId ->
            val ownerClassId = runCatching { ClassId.fromString(ownerId) }.getOrNull() ?: return@flatMap emptyList()
            val ownerDeclaration = session.regularClassSymbolOrNull(ownerClassId)?.fir ?: return@flatMap emptyList()
            if (ownerDeclaration.typeParameters.isNotEmpty()) {
                return@flatMap emptyList()
            }
            val ownerType = TcType.Constructor(ownerId, emptyList())
            ownerDeclaration.deriveViaRequests(session).distinctBy { request ->
                deriveViaRequestIdentityKey(request.typeclassId, request.path)
            }.flatMap requestLoop@{ request ->
                val typeclassInterface = session.regularClassSymbolOrNull(request.typeclassId)?.fir ?: return@requestLoop emptyList()
                if (!typeclassInterface.hasAnnotation(TYPECLASS_ANNOTATION_CLASS_ID, session)) {
                    return@requestLoop emptyList()
                }
                if (typeclassInterface.typeParameters.isEmpty()) {
                    return@requestLoop emptyList()
                }
                if (typeclassInterface.validateDeriveViaTransportability(session) != null) {
                    return@requestLoop emptyList()
                }
                val expansions = expandedDerivedTypeclassHeads(request.typeclassId.asString(), session, configuration)
                val directTypeParameters = expansions.firstOrNull()?.directTypeParameters.orEmpty()
                val transportedParameter = directTypeParameters.lastOrNull() ?: return@requestLoop emptyList()
                val bindableIds = directTypeParameters.mapTo(linkedSetOf(), TcTypeParameter::id)
                val viaType = planner.resolveViaPath(ownerType, request.path) ?: return@requestLoop emptyList()
                expansions.mapNotNull { expansion ->
                    if (expansion.head.classifierId != goal.classifierId) {
                        return@mapNotNull null
                    }
                    val bindings = unifyTypes(expansion.head, goal, bindableVariableIds = bindableIds) ?: return@mapNotNull null
                    val targetType = bindings[transportedParameter.id] as? TcType.Constructor ?: return@mapNotNull null
                    if (targetType != ownerType) {
                        return@mapNotNull null
                    }
                    val prerequisiteType =
                        expansion.head.substituteType(bindings + (transportedParameter.id to viaType)) as? TcType.Constructor
                            ?: return@mapNotNull null
                    DeriveViaOption(
                        ownerId = ownerId,
                        directTypeclassId = request.typeclassId.asString(),
                        prerequisiteType = prerequisiteType,
                        pathKey = deriveViaPathKey(request.path),
                    )
                }
            }
        }.distinctBy { option ->
            listOf(
                option.ownerId,
                option.directTypeclassId,
                option.pathKey,
                option.prerequisiteType.normalizedKey(),
            ).joinToString("::")
        }
    }

    private fun deriveViaRequestIdentityKey(
        typeclassId: ClassId,
        path: List<FirDeriveViaPathSegment>,
    ): String = "${typeclassId.asString()}:${deriveViaPathKey(path)}"

    private fun deriveViaPathKey(path: List<FirDeriveViaPathSegment>): String =
        path.joinToString("|") { segment ->
            val prefix =
                when (segment) {
                    is FirDeriveViaPathSegment.Waypoint -> "W"
                    is FirDeriveViaPathSegment.PinnedIso -> "I"
                }
            "$prefix:${segment.classId.asString()}"
        }

    private fun deriveEquivRefinementRules(
        goal: TcType.Constructor,
        session: FirSession,
    ): List<InstanceRule> {
        if (goal.classifierId != EQUIV_CLASS_ID.asString()) {
            return emptyList()
        }
        val sourceType = goal.arguments.getOrNull(0) as? TcType.Constructor ?: return emptyList()
        val targetType = goal.arguments.getOrNull(1) as? TcType.Constructor ?: return emptyList()
        if (sourceType.isNullable || targetType.isNullable) {
            return emptyList()
        }
        val sourceClassId = runCatching { ClassId.fromString(sourceType.classifierId) }.getOrNull() ?: return emptyList()
        val sourceDeclaration = session.regularClassSymbolOrNull(sourceClassId)?.fir ?: return emptyList()
        if (sourceDeclaration.typeParameters.isNotEmpty()) {
            return emptyList()
        }
        val preciseTargets = discoveredDeriveEquivTargetIds(sourceType.classifierId, session)
        if (targetType.classifierId !in preciseTargets) {
            return emptyList()
        }
        if (sourceDeclaration.source != null && !FirDirectTransportPlanner(session).planEquiv(sourceType, targetType)) {
            return emptyList()
        }
        return listOf(
            InstanceRule(
                id = "derived-equiv:${sourceType.classifierId}:${targetType.classifierId}",
                typeParameters = emptyList(),
                providedType = goal,
                prerequisiteTypes = emptyList(),
            ),
        )
    }

    private fun discoverTopLevelRulesForGoal(
        goal: TcType,
        session: FirSession,
    ): List<VisibleInstanceRule> =
        topLevelSearchPackagesForGoal(goal, session)
            .flatMapTo(linkedSetOf()) { packageName ->
                topLevelRulesByPackage[packageName].orEmpty() +
                    lazilyDiscoveredTopLevelRulesByPackage.getOrPut(packageName) {
                        discoverTopLevelRulesInPackage(packageName, session).also(recordImportedTopLevelRulesForIr)
                    }
            }.toList()

    private fun topLevelSearchPackagesForGoal(
        goal: TcType,
        session: FirSession,
    ): Set<FqName> =
        allowedAssociatedOwnersForGoal(goal, session)
            .mapTo(linkedSetOf()) { owner -> owner.packageFqName }

    private fun discoverTopLevelRulesInPackage(
        packageName: FqName,
        session: FirSession,
    ): List<VisibleInstanceRule> {
        val symbolProviders = session.topLevelDeclarationProviders()
        return buildList {
            scanTopLevelDeclarationsInPackage(packageName, symbolProviders) { declaration ->
                when (declaration) {
                    is FirTypeclassFunctionDeclaration -> addAll(declaration.toFunctionRules(session, configuration))
                    is FirProperty -> addAll(declaration.toPropertyRules(session, configuration))
                    is FirRegularClass -> addAll(declaration.toObjectRules(session, configuration))
                    else -> Unit
                }
            }
        }
    }

    fun canDeriveGoal(
        goal: TcType,
        session: FirSession,
        availableContexts: List<TcType> = emptyList(),
        visiting: MutableSet<String> = linkedSetOf(),
        allowedRecursiveGoals: Set<String> = emptySet(),
        canMaterializeVariable: (String) -> Boolean = { true },
        builtinGoalAcceptance: BuiltinGoalAcceptance = BuiltinGoalAcceptance.ALLOW_SPECULATIVE,
        exactBuiltinGoalContext: FirBuiltinGoalExactContext? = null,
    ): Boolean {
        val key = goal.normalizedKey()
        if (!visiting.add(key)) {
            return key in allowedRecursiveGoals
        }
        val directPlanner =
            TypeclassResolutionPlanner(
                ruleProvider = { nestedGoal ->
                    rulesForGoal(
                        goal = nestedGoal,
                        session = session,
                        canMaterializeVariable = canMaterializeVariable,
                        builtinGoalAcceptance = builtinGoalAcceptance,
                        exactBuiltinGoalContext = exactBuiltinGoalContext,
                    )
                },
                bindableDesiredVariableIds = emptySet(),
            )
        when (directPlanner.resolve(goal, availableContexts)) {
            is ResolutionSearchResult.Success -> return true
            else -> Unit
        }
        val constructor = goal as? TcType.Constructor ?: return false
        val typeclassId = constructor.classifierId
        return when (typeclassId) {
            EQUIV_CLASS_ID.asString() -> {
                val sourceType = constructor.arguments.getOrNull(0) as? TcType.Constructor ?: return false
                val targetType = constructor.arguments.getOrNull(1) as? TcType.Constructor ?: return false
                if (sourceType.isNullable || targetType.isNullable) {
                    return false
                }
                derivationOwnersForTarget(sourceType.classifierId, session).any { owner ->
                    if (owner != sourceType.classifierId) {
                        return@any false
                    }
                    val preciseTargets = discoveredDeriveEquivTargetIds(owner, session)
                    if (targetType.classifierId !in preciseTargets) {
                        return@any false
                    }
                    val ownerClassId = runCatching { ClassId.fromString(owner) }.getOrNull() ?: return@any false
                    val ownerSymbol = session.regularClassSymbolOrNull(ownerClassId) ?: return@any true
                    if (ownerSymbol.fir.source == null) {
                        return@any true
                    }
                    FirDirectTransportPlanner(session).planEquiv(sourceType, targetType)
                }
            }

            else ->
                shapeDerivationOptionsForGoal(constructor, session).any { option ->
                    val nestedAllowedRecursiveGoals =
                        linkedSetOf<String>().apply {
                            addAll(allowedRecursiveGoals)
                            add(key)
                        }
                    option.prerequisiteTypes.all { prerequisiteGoal ->
                        canDeriveGoal(
                            goal = prerequisiteGoal,
                            session = session,
                            availableContexts = availableContexts,
                            visiting = linkedSetOf<String>().apply { addAll(visiting) },
                            allowedRecursiveGoals = nestedAllowedRecursiveGoals,
                            canMaterializeVariable = canMaterializeVariable,
                            builtinGoalAcceptance = builtinGoalAcceptance,
                            exactBuiltinGoalContext = exactBuiltinGoalContext,
                        )
                    }
                } ||
                    deriveViaOptionsForGoal(constructor, session).any { option ->
                        canDeriveGoal(
                            goal = option.prerequisiteType,
                            session = session,
                            availableContexts = availableContexts,
                            visiting = linkedSetOf<String>().apply { addAll(visiting) },
                            allowedRecursiveGoals = allowedRecursiveGoals,
                            canMaterializeVariable = canMaterializeVariable,
                            builtinGoalAcceptance = builtinGoalAcceptance,
                            exactBuiltinGoalContext = exactBuiltinGoalContext,
                        )
                    }
        }
    }

    fun allowedAssociatedOwnersForGoal(
        goal: TcType,
        session: FirSession,
    ): Set<ClassId> {
        val constructor = goal as? TcType.Constructor ?: return emptySet()
        val ownerIds = linkedSetOf<String>()
        ownerIds += sealedOwnerChain(constructor.classifierId, session)
        constructor.arguments.forEach { argument ->
            ownerIds += associatedOwnerIds(argument, session)
        }
        return ownerIds.map(ClassId::fromString).toSet()
    }

    private fun associatedOwnerIds(
        type: TcType,
        session: FirSession,
    ): Set<String> =
        when (type) {
            TcType.StarProjection -> emptySet()
            is TcType.Projected -> associatedOwnerIds(type.type, session)
            is TcType.Variable -> emptySet()

            is TcType.Constructor -> {
                val owners = linkedSetOf<String>()
                owners += sealedOwnerChain(type.classifierId, session)
                type.arguments.forEach { argument ->
                    owners += associatedOwnerIds(argument, session)
                }
                owners
            }
        }

    private fun derivationOwnersForTarget(
        classifierId: String,
        session: FirSession,
    ): Set<String> = sealedOwnerChain(classifierId, session)

    private fun deriveSealedCaseType(
        rootClassId: ClassId,
        rootTargetType: TcType.Constructor,
        rootTypeParameterVariances: List<Variance>,
        subclassId: String,
        session: FirSession,
    ): TcType.Constructor? {
        val parsedSubclassId = runCatching { ClassId.fromString(subclassId) }.getOrNull() ?: return null
        val subclassSymbol = session.regularClassSymbolOrNull(parsedSubclassId) ?: return null
        val subclassDeclaration = subclassSymbol.fir
        val subclassTypeParameters =
            subclassDeclaration.typeParameters.mapIndexed { index, typeParameter ->
                TcTypeParameter(
                    id = "${parsedSubclassId.asString()}#$index",
                    displayName = typeParameter.symbol.name.asString(),
                )
            }
        val typeParameterBySymbol =
            subclassDeclaration.typeParameters.zip(subclassTypeParameters).associate { (typeParameter, parameter) ->
                typeParameter.symbol to parameter
            }
        val matchingRootSupertype =
            subclassDeclaration.superTypeRefs
                .mapNotNull { superTypeRef ->
                    coneTypeToModel(superTypeRef.coneType, typeParameterBySymbol) as? TcType.Constructor
                }.firstOrNull { superType -> superType.classifierId == rootClassId.asString() }
                ?: return null
        if (matchingRootSupertype.arguments.size != rootTargetType.arguments.size) {
            return null
        }
        val subclassVariableIds = subclassTypeParameters.mapTo(linkedSetOf(), TcTypeParameter::id)
        val bindings = linkedMapOf<String, TcType>()
        val supported =
            matchingRootSupertype.arguments.zip(rootTargetType.arguments).withIndex().all { (index, pair) ->
                val (declaredArgument, requestedArgument) = pair
                bindSimpleSealedRootArgument(
                    declared = declaredArgument,
                    requested = requestedArgument,
                    variance = rootTypeParameterVariances.getOrNull(index) ?: Variance.INVARIANT,
                    subclassVariableIds = subclassVariableIds,
                    bindings = bindings,
                )
            }
        if (!supported) {
            return null
        }
        val caseType =
            TcType.Constructor(
                classifierId = parsedSubclassId.asString(),
                arguments =
                    subclassTypeParameters.map { parameter ->
                        TcType.Variable(parameter.id, parameter.displayName)
                    },
            ).substituteType(bindings)
        val caseConstructor = caseType as? TcType.Constructor ?: return null
        return caseConstructor.takeUnless {
            it.containsAnyVariableIds(subclassVariableIds)
        }
    }

    private fun exactSealedShapeDerivedPrerequisiteGoals(
        directTypeclassId: String,
        targetType: TcType.Constructor,
        declaration: FirRegularClass,
        session: FirSession,
        reportFailure: ((String) -> Unit)? = null,
    ): List<TcType>? {
        fun fail(message: String): List<TcType>? {
            reportFailure?.invoke(message)
            return null
        }
        val rootClassId = declaration.symbol.classId
        val subclassIds = stableSealedSubclassIds(rootClassId, declaration, session)
        if (subclassIds.isEmpty()) {
            return fail("Cannot derive ${rootClassId.asFqNameString()} because no sealed subclasses are admissible for the requested typeclass")
        }
        val caseInfos =
            subclassIds.map { subclassId ->
                buildFirDerivedSumCaseInfo(
                    rootClassId = rootClassId,
                    subclassId = subclassId,
                    session = session,
                ) ?: return fail("Cannot derive ${rootClassId.asFqNameString()} because one or more sealed subclasses cannot be expressed from the sealed root's type parameters")
            }
        val typeclassId = runCatching { ClassId.fromString(directTypeclassId) }.getOrNull() ?: return null
        val typeclassInterface = session.regularClassSymbolOrNull(typeclassId)?.fir ?: return null
        val conservativeOnly = typeclassInterface.firGadtAdmissionMode(session) == FirGadtAdmissionMode.CONSERVATIVE_ONLY
        val rootVariances = declaration.typeParameters.map { typeParameter -> typeParameter.symbol.fir.variance }
        val prerequisiteGoals = mutableListOf<TcType>()
        caseInfos.forEach { caseInfo ->
            when (val admission = caseInfo.admitToTargetCandidate(targetType, rootVariances, conservativeOnly)) {
                is FirCaseAdmission.Rejected -> return fail(admission.rejectionMessage)
                FirCaseAdmission.Ignored -> Unit
                is FirCaseAdmission.Admitted -> {
                    exactShapeDerivationFailure(
                        directTypeclassId = directTypeclassId,
                        declaration = caseInfo.subclassDeclaration,
                        session = session,
                        targetType = admission.caseType,
                    )?.let { caseFailure ->
                        return fail(
                            "Cannot derive ${rootClassId.asFqNameString()} because sealed subclass ${caseInfo.subclassDeclaration.symbol.classId.asFqNameString()} is not itself derivable: $caseFailure",
                        )
                    }
                    prerequisiteGoals +=
                        TcType.Constructor(
                            classifierId = directTypeclassId,
                            arguments = listOf(admission.caseType),
                        )
                }
            }
        }
        return prerequisiteGoals.takeIf { it.isNotEmpty() }
            ?: fail("Cannot derive ${rootClassId.asFqNameString()} because no sealed subclasses are admissible for the requested typeclass")
    }

    private fun stableSealedSubclassIds(
        rootClassId: ClassId,
        declaration: FirRegularClass,
        session: FirSession,
    ): List<String> {
        val discoveredSubclassIds =
            buildSet {
                addAll(
                    classInfoById
                        .filterValues { info -> rootClassId.asString() in info.superClassifiers }
                        .keys,
                )
                addAll(
                    declaration
                        .getSealedClassInheritors(session)
                        .map(ClassId::asString),
                )
            }
        val sourceOrderedSubclassIds =
            declaration
                .getSealedClassInheritors(session)
                .map(ClassId::asString)
        return stableSealedSubclassIds(sourceOrderedSubclassIds, discoveredSubclassIds)
    }

    private fun FirRegularClass.defaultExactShapeDerivationTargetType(): TcType.Constructor {
        val targetTypeParameters =
            typeParameters.mapIndexed { index, typeParameter ->
                TcTypeParameter(
                    id = "validate-derived:${symbol.classId.asString()}#$index",
                    displayName = typeParameter.symbol.name.asString(),
                )
            }
        return TcType.Constructor(
            classifierId = symbol.classId.asString(),
            arguments = targetTypeParameters.map { parameter -> TcType.Variable(parameter.id, parameter.displayName) },
        )
    }

    private fun buildFirDerivedSumCaseInfo(
        rootClassId: ClassId,
        subclassId: String,
        session: FirSession,
    ): FirDerivedSumCaseInfo? {
        val parsedSubclassId = runCatching { ClassId.fromString(subclassId) }.getOrNull() ?: return null
        val subclassDeclaration = session.regularClassSymbolOrNull(parsedSubclassId)?.fir ?: return null
        val caseTypeParameters =
            subclassDeclaration.typeParameters.mapIndexed { index, typeParameter ->
                TcTypeParameter(
                    id = "${parsedSubclassId.asString()}#$index",
                    displayName = typeParameter.symbol.name.asString(),
                )
            }
        val typeParameterBySymbol =
            subclassDeclaration.typeParameters.zip(caseTypeParameters).associate { (typeParameter, parameter) ->
                typeParameter.symbol to parameter
            }
        val projectedHead =
            subclassDeclaration.superTypeRefs
                .firstOrNull { superTypeRef -> superTypeRef.coneType.classId == rootClassId }
                ?.let { superTypeRef -> coneTypeToModel(superTypeRef.coneType, typeParameterBySymbol) }
                as? TcType.Constructor ?: return null
        val fieldTypes =
            subclassDeclaration.declarations
                .filterIsInstance<FirProperty>()
                .filter { property -> property.getter != null && property.backingField != null }
                .map { property ->
                    val getter = property.getter ?: return null
                    coneTypeToModel(getter.returnTypeRef.coneType, typeParameterBySymbol) ?: return null
                }
        return FirDerivedSumCaseInfo(
            subclassDeclaration = subclassDeclaration,
            projectedHead = projectedHead,
            subclassDeclaredType =
                TcType.Constructor(
                    classifierId = parsedSubclassId.asString(),
                    arguments = caseTypeParameters.map { parameter -> TcType.Variable(parameter.id, parameter.displayName) },
                ),
            caseTypeParameters = caseTypeParameters,
            fieldTypes = fieldTypes,
        )
    }

    private fun FirDerivedSumCaseInfo.admitToTargetCandidate(
        targetType: TcType.Constructor,
        rootVariances: List<Variance>,
        conservativeOnly: Boolean,
    ): FirCaseAdmission {
        val bindings =
            unifyTypes(
                left = projectedHead,
                right = targetType,
                bindableVariableIds = caseTypeParameters.mapTo(linkedSetOf(), TcTypeParameter::id),
            ) ?: when {
                subclassDeclaredType.referencedVariableIds().isEmpty() &&
                    projectedHead.isVarianceCompatibleWithTargetCandidate(targetType, rootVariances) ->
                    emptyMap()

                conservativeOnly ->
                    return FirCaseAdmission.Rejected(
                        "Cannot derive ${targetType.classifierId.toFqNameOrSelf()} because sealed subclass ${subclassDeclaration.symbol.classId.asFqNameString()} refines the result head beyond the conservative admissibility policy",
                    )
                else -> return FirCaseAdmission.Ignored
            }

        val allowedVariableIds = targetType.referencedVariableIds()
        val caseType =
            subclassDeclaredType.substituteType(bindings) as? TcType.Constructor
                ?: return FirCaseAdmission.Rejected(
                    "Cannot derive ${targetType.classifierId.toFqNameOrSelf()} because sealed subclass ${subclassDeclaration.symbol.classId.asFqNameString()} cannot be expressed as an admitted result case",
                )
        if (!caseType.referencedVariableIds().all(allowedVariableIds::contains)) {
            return FirCaseAdmission.Rejected(
                "Cannot derive ${targetType.classifierId.toFqNameOrSelf()} because sealed subclass ${subclassDeclaration.symbol.classId.asFqNameString()} introduces type parameters that are not quantified by the admitted result head",
            )
        }
        fieldTypes.forEach { fieldType ->
            val substitutedFieldType = fieldType.substituteType(bindings)
            if (substitutedFieldType.containsStarProjection()) {
                return FirCaseAdmission.Rejected(
                    "Cannot derive ${targetType.classifierId.toFqNameOrSelf()} because sealed subclass ${subclassDeclaration.symbol.classId.asFqNameString()} requires proof/equality-carrying field evidence hidden from the admitted result head",
                )
            }
            if (!substitutedFieldType.referencedVariableIds().all(allowedVariableIds::contains)) {
                return FirCaseAdmission.Rejected(
                    "Cannot derive ${targetType.classifierId.toFqNameOrSelf()} because sealed subclass ${subclassDeclaration.symbol.classId.asFqNameString()} requires field evidence that is not recoverable from the admitted result head",
                )
            }
        }
        return FirCaseAdmission.Admitted(caseType)
    }

    private fun TcType.Constructor.toFirDerivedSumCandidate(
        ownerId: String,
        seed: String,
    ): TcType.Constructor {
        val referencedVariables = referencedVariableIds().toList()
        if (referencedVariables.isEmpty()) {
            return this
        }
        val ruleTypeParameters =
            referencedVariables.mapIndexed { index, variableId ->
                TcTypeParameter(
                    id = "derived-sum:$ownerId:$seed:$index",
                    displayName = "A$index",
                ) to variableId
            }
        return substituteType(
            ruleTypeParameters.associate { (parameter, variableId) ->
                variableId to TcType.Variable(parameter.id, parameter.displayName)
            },
        ) as TcType.Constructor
    }

    private fun String.toFqNameOrSelf(): String =
        runCatching { ClassId.fromString(this).asFqNameString() }.getOrDefault(this)

    private fun TcType.isVarianceCompatibleWithTargetCandidate(
        targetType: TcType.Constructor,
        rootVariances: List<Variance>,
    ): Boolean {
        val actual = this as? TcType.Constructor ?: return false
        if (actual.classifierId != targetType.classifierId || actual.arguments.size != targetType.arguments.size) {
            return false
        }
        return actual.arguments.indices.all { index ->
            val actualArgument = actual.arguments[index]
            val expectedArgument = targetType.arguments[index]
            when (rootVariances.getOrNull(index) ?: Variance.INVARIANT) {
                Variance.OUT_VARIANCE -> actualArgument.isVarianceSubtypeOf(expectedArgument)
                Variance.IN_VARIANCE -> expectedArgument.isVarianceSubtypeOf(actualArgument)
                Variance.INVARIANT -> actualArgument == expectedArgument
            }
        }
    }

    private fun TcType.isVarianceSubtypeOf(
        expected: TcType,
    ): Boolean =
        when {
            expected is TcType.Variable -> true
            this == expected -> true
            this is TcType.Constructor && expected is TcType.Constructor ->
                classifierId == expected.classifierId &&
                    arguments.size == expected.arguments.size &&
                    arguments.zip(expected.arguments).all { (left, right) -> left.isVarianceSubtypeOf(right) }

            else -> false
        }

    private fun FirRegularClass.firGadtAdmissionMode(
        session: FirSession,
    ): FirGadtAdmissionMode {
        val transported = typeParameters.lastOrNull()?.symbol ?: return FirGadtAdmissionMode.CONSERVATIVE_ONLY
        val declaredOverride = transported.firGadtPolicyMode(session) ?: firGadtPolicyMode(session)
        if (declaredOverride == FirGadtAdmissionMode.CONSERVATIVE_ONLY) {
            return FirGadtAdmissionMode.CONSERVATIVE_ONLY
        }
        return when (firEffectiveVarianceFor(transported, session)) {
            FirGadtEffectiveVariance.CONTRAVARIANT,
            FirGadtEffectiveVariance.PHANTOM,
            -> FirGadtAdmissionMode.SURFACE_TRUSTED

            FirGadtEffectiveVariance.COVARIANT,
            FirGadtEffectiveVariance.INVARIANT,
            -> FirGadtAdmissionMode.CONSERVATIVE_ONLY
        }
    }

    private fun FirRegularClass.firGadtPolicyMode(
        session: FirSession,
    ): FirGadtAdmissionMode? =
        resolvedAnnotationsByClassId(
            annotationClassId = GADT_DERIVATION_POLICY_ANNOTATION_CLASS_ID,
            session = session,
        ).firstNotNullOfOrNull { annotation -> annotation.firGadtPolicyMode() }

    private fun FirTypeParameterSymbol.firGadtPolicyMode(
        session: FirSession,
    ): FirGadtAdmissionMode? =
        fir.annotations
            .filterIsInstance<FirAnnotationCall>()
            .firstNotNullOfOrNull { annotation ->
                annotation
                    .takeIf { it.annotationTypeRef.coneType.classId == GADT_DERIVATION_POLICY_ANNOTATION_CLASS_ID }
                    ?.firGadtPolicyMode()
            }

    private fun FirAnnotation.firGadtPolicyMode(): FirGadtAdmissionMode? {
        val modeName =
            findArgumentByName(Name.identifier("mode"))
                ?.extractEnumValueArgumentInfo()
                ?.enumEntryName
                ?.asString()
                ?: return null
        return when (modeName) {
            "CONSERVATIVE_ONLY" -> FirGadtAdmissionMode.CONSERVATIVE_ONLY
            "SURFACE_TRUSTED" -> FirGadtAdmissionMode.SURFACE_TRUSTED
            else -> null
        }
    }

    private fun FirRegularClass.firEffectiveVarianceFor(
        transported: FirTypeParameterSymbol,
        session: FirSession,
    ): FirGadtEffectiveVariance =
        when (transported.fir.variance) {
            Variance.IN_VARIANCE -> FirGadtEffectiveVariance.CONTRAVARIANT
            Variance.OUT_VARIANCE -> FirGadtEffectiveVariance.COVARIANT
            Variance.INVARIANT ->
                analyzeFirCallableSurfaceVariance(
                    transportedId = transported.name.asString(),
                    substitution = typeParameters.associate { typeParameter ->
                        typeParameter.symbol.name.asString() to
                            TcType.Variable(typeParameter.symbol.name.asString(), typeParameter.symbol.name.asString())
                    },
                    session = session,
                    visited = linkedSetOf(),
                )

            else -> FirGadtEffectiveVariance.INVARIANT
        }

    private fun FirRegularClass.analyzeFirCallableSurfaceVariance(
        transportedId: String,
        substitution: Map<String, TcType>,
        session: FirSession,
        visited: MutableSet<String>,
    ): FirGadtEffectiveVariance {
        val visitKey =
            buildString {
                append(symbol.classId.asString())
                append(':')
                append(
                    typeParameters.joinToString(separator = ",") { typeParameter ->
                        substitution[typeParameter.symbol.name.asString()]?.render() ?: typeParameter.symbol.name.asString()
                    },
                )
            }
        if (!visited.add(visitKey)) {
            return FirGadtEffectiveVariance.PHANTOM
        }

        val parameterModels =
            typeParameters.associate { typeParameter ->
                typeParameter.symbol to
                    TcTypeParameter(
                        id = typeParameter.symbol.name.asString(),
                        displayName = typeParameter.symbol.name.asString(),
                    )
            }

        var result = FirGadtEffectiveVariance.PHANTOM

        declarations.filterIsInstance<FirProperty>().forEach { property ->
            val getter = property.getter ?: return@forEach
            if (getter.returnTypeRef.hasFirUnsafeVarianceMarker()) {
                return@forEach
            }
            val type = coneTypeToModel(getter.returnTypeRef.coneType, parameterModels)?.substituteType(substitution) ?: return@forEach
            result =
                result.parallelCombine(
                    analyzeFirTypeVariance(
                        type = type,
                        position = FirGadtEffectiveVariance.COVARIANT,
                        transportedId = transportedId,
                        session = session,
                    ),
                )
        }

        declarations.filterIsInstance<org.jetbrains.kotlin.fir.declarations.FirFunction>().forEach { function ->
            if (function is org.jetbrains.kotlin.fir.declarations.FirConstructor) {
                return@forEach
            }
            function.receiverParameter?.typeRef?.coneType
                ?.takeUnless { function.receiverParameter?.typeRef?.hasFirUnsafeVarianceMarker() == true }
                ?.let { coneTypeToModel(it, parameterModels)?.substituteType(substitution) }
                ?.let { type ->
                    result =
                        result.parallelCombine(
                            analyzeFirTypeVariance(
                                type = type,
                                position = FirGadtEffectiveVariance.CONTRAVARIANT,
                                transportedId = transportedId,
                                session = session,
                            ),
                        )
                }
            (function.contextParameters + function.valueParameters).forEach { parameter ->
                if (parameter.returnTypeRef.hasFirUnsafeVarianceMarker()) {
                    return@forEach
                }
                val type = coneTypeToModel(parameter.returnTypeRef.coneType, parameterModels)?.substituteType(substitution) ?: return@forEach
                result =
                    result.parallelCombine(
                        analyzeFirTypeVariance(
                            type = type,
                            position = FirGadtEffectiveVariance.CONTRAVARIANT,
                            transportedId = transportedId,
                            session = session,
                        ),
                    )
            }
            if (function.returnTypeRef.hasFirUnsafeVarianceMarker()) {
                return@forEach
            }
            val returnType = coneTypeToModel(function.returnTypeRef.coneType, parameterModels)?.substituteType(substitution) ?: return@forEach
            result =
                result.parallelCombine(
                    analyzeFirTypeVariance(
                        type = returnType,
                        position = FirGadtEffectiveVariance.COVARIANT,
                        transportedId = transportedId,
                        session = session,
                    ),
                )
        }

        superTypeRefs.forEach { superTypeRef ->
            val superType = coneTypeToModel(superTypeRef.coneType, parameterModels)?.substituteType(substitution) as? TcType.Constructor ?: return@forEach
            val superClassId = runCatching { ClassId.fromString(superType.classifierId) }.getOrNull() ?: return@forEach
            val superClass = session.regularClassSymbolOrNull(superClassId)?.fir ?: return@forEach
            val superSubstitution =
                superClass.typeParameters.mapIndexedNotNull { index, typeParameter ->
                    superType.arguments.getOrNull(index)?.let { argument ->
                        typeParameter.symbol.name.asString() to argument
                    }
                }.toMap()
            result =
                result.parallelCombine(
                    superClass.analyzeFirCallableSurfaceVariance(
                        transportedId = transportedId,
                        substitution = superSubstitution,
                        session = session,
                        visited = visited,
                    ),
                )
        }

        return result
    }

    private fun analyzeFirTypeVariance(
        type: TcType,
        position: FirGadtEffectiveVariance,
        transportedId: String,
        session: FirSession,
    ): FirGadtEffectiveVariance {
        type.firGadtFunctionTypeInfoOrNull()?.let { functionInfo ->
            var functionResult = FirGadtEffectiveVariance.PHANTOM
            functionInfo.parameterTypes.forEach { parameterType ->
                functionResult =
                    functionResult.parallelCombine(
                        analyzeFirTypeVariance(
                            type = parameterType,
                            position = position.composeWith(FirGadtEffectiveVariance.CONTRAVARIANT),
                            transportedId = transportedId,
                            session = session,
                        ),
                    )
            }
            functionResult =
                functionResult.parallelCombine(
                    analyzeFirTypeVariance(
                        type = functionInfo.returnType,
                        position = position.composeWith(FirGadtEffectiveVariance.COVARIANT),
                        transportedId = transportedId,
                        session = session,
                    ),
                )
            return functionResult
        }

        return when (type) {
            TcType.StarProjection -> FirGadtEffectiveVariance.PHANTOM
            is TcType.Variable ->
                if (type.id == transportedId) {
                    position
                } else {
                    FirGadtEffectiveVariance.PHANTOM
                }

            is TcType.Projected -> {
                val projectionVariance =
                    when (type.variance) {
                        Variance.IN_VARIANCE -> FirGadtEffectiveVariance.CONTRAVARIANT
                        Variance.OUT_VARIANCE -> FirGadtEffectiveVariance.COVARIANT
                        Variance.INVARIANT -> FirGadtEffectiveVariance.INVARIANT
                    }
                analyzeFirTypeVariance(
                    type = type.type,
                    position = position.composeWith(projectionVariance),
                    transportedId = transportedId,
                    session = session,
                )
            }

            is TcType.Constructor -> {
                val classId = runCatching { ClassId.fromString(type.classifierId) }.getOrNull()
                val klass = classId?.let { session.regularClassSymbolOrNull(it)?.fir }
                var result = FirGadtEffectiveVariance.PHANTOM
                type.arguments.forEachIndexed { index, argument ->
                    val nestedType = if (argument is TcType.Projected) argument.type else argument
                    val declaredVariance = klass?.typeParameters?.getOrNull(index)?.symbol?.fir?.variance ?: Variance.INVARIANT
                    val argumentVariance =
                        if (argument is TcType.Projected) {
                            when (argument.variance) {
                                Variance.IN_VARIANCE -> FirGadtEffectiveVariance.CONTRAVARIANT
                                Variance.OUT_VARIANCE -> FirGadtEffectiveVariance.COVARIANT
                                Variance.INVARIANT -> declaredVariance.toFirGadtEffectiveVariance()
                            }
                        } else {
                            declaredVariance.toFirGadtEffectiveVariance()
                        }
                    result =
                        result.parallelCombine(
                            analyzeFirTypeVariance(
                                type = nestedType,
                                position = position.composeWith(argumentVariance),
                                transportedId = transportedId,
                                session = session,
                            ),
                        )
                }
                result
            }
        }
    }

    private fun Variance.toFirGadtEffectiveVariance(): FirGadtEffectiveVariance =
        when (this) {
            Variance.INVARIANT -> FirGadtEffectiveVariance.INVARIANT
            Variance.IN_VARIANCE -> FirGadtEffectiveVariance.CONTRAVARIANT
            Variance.OUT_VARIANCE -> FirGadtEffectiveVariance.COVARIANT
        }

    private fun FirGadtEffectiveVariance.composeWith(
        nested: FirGadtEffectiveVariance,
    ): FirGadtEffectiveVariance =
        when {
            this == FirGadtEffectiveVariance.PHANTOM || nested == FirGadtEffectiveVariance.PHANTOM -> FirGadtEffectiveVariance.PHANTOM
            this == FirGadtEffectiveVariance.INVARIANT || nested == FirGadtEffectiveVariance.INVARIANT -> FirGadtEffectiveVariance.INVARIANT
            this == nested -> FirGadtEffectiveVariance.COVARIANT
            else -> FirGadtEffectiveVariance.CONTRAVARIANT
        }

    private fun FirGadtEffectiveVariance.parallelCombine(
        other: FirGadtEffectiveVariance,
    ): FirGadtEffectiveVariance =
        when {
            this == FirGadtEffectiveVariance.INVARIANT || other == FirGadtEffectiveVariance.INVARIANT -> FirGadtEffectiveVariance.INVARIANT
            this == FirGadtEffectiveVariance.PHANTOM -> other
            other == FirGadtEffectiveVariance.PHANTOM -> this
            this == other -> this
            else -> FirGadtEffectiveVariance.INVARIANT
        }

    private fun TcType.firGadtFunctionTypeInfoOrNull(): FirGadtFunctionTypeInfo? {
        val constructor = this as? TcType.Constructor ?: return null
        val normalizedClassifier = constructor.classifierId.replace('/', '.')
        if (!normalizedClassifier.startsWith("kotlin.Function") && !normalizedClassifier.startsWith("kotlin.SuspendFunction")) {
            return null
        }
        if (constructor.arguments.isEmpty()) {
            return null
        }
        return FirGadtFunctionTypeInfo(
            parameterTypes = constructor.arguments.dropLast(1),
            returnType = constructor.arguments.last(),
        )
    }

    private data class FirDerivedSumCaseInfo(
        val subclassDeclaration: FirRegularClass,
        val projectedHead: TcType.Constructor,
        val subclassDeclaredType: TcType.Constructor,
        val caseTypeParameters: List<TcTypeParameter>,
        val fieldTypes: List<TcType>,
    )

    private sealed interface FirCaseAdmission {
        data object Ignored : FirCaseAdmission
        data class Rejected(
            val rejectionMessage: String,
        ) : FirCaseAdmission
        data class Admitted(
            val caseType: TcType.Constructor,
        ) : FirCaseAdmission
    }

    private enum class FirGadtAdmissionMode {
        SURFACE_TRUSTED,
        CONSERVATIVE_ONLY,
    }

    private enum class FirGadtEffectiveVariance {
        PHANTOM,
        COVARIANT,
        CONTRAVARIANT,
        INVARIANT,
    }

    private data class FirGadtFunctionTypeInfo(
        val parameterTypes: List<TcType>,
        val returnType: TcType,
    )

    private fun org.jetbrains.kotlin.fir.types.FirTypeRef.hasFirUnsafeVarianceMarker(): Boolean =
        annotations.any { annotation ->
            annotation.annotationTypeRef.coneType.classId == ClassId.topLevel(FqName("kotlin.UnsafeVariance"))
        } || ((this as? org.jetbrains.kotlin.fir.types.FirResolvedTypeRef)?.delegatedTypeRef?.hasFirUnsafeVarianceMarker() == true)

    private fun bindSimpleSealedRootArgument(
        declared: TcType,
        requested: TcType,
        variance: Variance,
        subclassVariableIds: Set<String>,
        bindings: MutableMap<String, TcType>,
    ): Boolean =
        when {
            declared == requested -> true
            variance == Variance.OUT_VARIANCE && declared.isNothingType() -> true
            declared is TcType.Variable && declared.id in subclassVariableIds -> {
                val existing = bindings[declared.id]
                if (existing == null) {
                    bindings[declared.id] = requested
                    true
                } else {
                    existing == requested
                }
            }

            else -> false
        }

    private fun TcType.isNothingType(): Boolean =
        this is TcType.Constructor &&
            classifierId == ClassId.topLevel(FqName("kotlin.Nothing")).asString() &&
            !isNullable

    private fun TcType.containsAnyVariableIds(variableIds: Set<String>): Boolean =
        when (this) {
            TcType.StarProjection -> false
            is TcType.Projected -> type.containsAnyVariableIds(variableIds)
            is TcType.Constructor -> arguments.any { argument -> argument.containsAnyVariableIds(variableIds) }
            is TcType.Variable -> id in variableIds
        }

    private fun discoveredDerivableTypeclassIds(
        owner: String,
        session: FirSession?,
    ): Set<String> =
        derivableTypeclassIdsByOwner[owner]
            ?: lazilyDiscoveredDerivableTypeclassIdsByOwner[owner]
            ?: session?.let { activeSession ->
                lazilyDiscoveredDerivableTypeclassIdsByOwner.getOrPut(owner) {
                    val classId = runCatching { ClassId.fromString(owner) }.getOrNull() ?: return@getOrPut emptySet()
                    val symbol =
                        try {
                            activeSession.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol
                        } catch (_: IllegalArgumentException) {
                            null
                        } ?: return@getOrPut emptySet()
                    if (symbol.fir.source == null) {
                        val generatedMetadata = symbol.fir.generatedDerivedMetadata(activeSession)
                        if (generatedMetadata.isNotEmpty()) {
                            recordImportedGeneratedDerivedMetadataForIr(owner, generatedMetadata)
                        }
                    }
                    symbol.fir.supportedDerivedTypeclassIds(activeSession).let { supportedTypeclassIds ->
                        supportedTypeclassIds + supportedTypeclassIds.expandedDerivedTypeclassIds(activeSession, configuration)
                    }
                }
            }
            ?: emptySet()

    private fun discoveredDeriveEquivTargetIds(
        owner: String,
        session: FirSession?,
    ): Set<String> =
        deriveEquivTargetIdsByOwner[owner]
            ?: lazilyDiscoveredDeriveEquivTargetIdsByOwner[owner]
            ?: session?.let { activeSession ->
                lazilyDiscoveredDeriveEquivTargetIdsByOwner.getOrPut(owner) {
                    val classId = runCatching { ClassId.fromString(owner) }.getOrNull() ?: return@getOrPut emptySet()
                    val symbol = activeSession.regularClassSymbolOrNull(classId) ?: return@getOrPut emptySet()
                    if (symbol.fir.source == null) {
                        val generatedMetadata = symbol.fir.generatedDerivedMetadata(activeSession)
                        if (generatedMetadata.isNotEmpty()) {
                            recordImportedGeneratedDerivedMetadataForIr(owner, generatedMetadata)
                        }
                    }
                    if (symbol.fir.typeParameters.isNotEmpty()) {
                        return@getOrPut emptySet()
                    }
                    symbol.fir.deriveEquivRequests(activeSession).filterTo(linkedSetOf()) { otherClassId ->
                        runCatching { ClassId.fromString(otherClassId) }.getOrNull()
                            ?.let { otherClassIdValue ->
                                activeSession.regularClassSymbolOrNull(otherClassIdValue)?.fir?.typeParameters?.isEmpty() == true
                            } == true
                    }
                }
            }
            ?: emptySet()

    private fun sealedOwnerChain(
        classifierId: String,
        session: FirSession,
    ): Set<String> {
        val result = linkedSetOf(classifierId)
        val queue = ArrayDeque<String>()
        queue += classifierId
        val visited = linkedSetOf<String>()
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) {
                continue
            }
            discoverClassInfo(current, session)
            classInfoById[current]?.superClassifiers.orEmpty().forEach { superClassifier ->
                discoverClassInfo(superClassifier, session)
                if (classInfoById[superClassifier]?.isSealed == true) {
                    result += superClassifier
                }
                queue += superClassifier
            }
        }
        return result
    }

    private fun discoverClassInfo(
        classifierId: String,
        session: FirSession,
    ) {
        if (classInfoById.containsKey(classifierId)) {
            return
        }
        val classId = runCatching { ClassId.fromString(classifierId) }.getOrNull() ?: return
        val classSymbol =
            try {
                session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol
            } catch (_: IllegalArgumentException) {
                null
            } ?: return
        val declaration = classSymbol.fir
        classInfoById[classifierId] =
            VisibleClassHierarchyInfo(
                superClassifiers =
                    declaration.superTypeRefs.mapNotNull { superTypeRef ->
                        superTypeRef.coneType.classId?.asString()
                    }.toSet(),
                isSealed = declaration.status.modality == Modality.SEALED,
                typeParameterVariances = declaration.typeParameters.map { typeParameter -> typeParameter.symbol.fir.variance },
            )
    }

    private fun discoverAssociatedRules(
        owner: ClassId,
        session: FirSession,
    ): List<VisibleInstanceRule> =
        if (associatedRulesByOwner.containsKey(owner)) {
            emptyList()
        } else lazilyDiscoveredAssociatedRulesByOwner.getOrPut(owner) {
            val ownerSymbol = session.regularClassSymbolOrNull(owner) ?: return@getOrPut emptyList()
            val companion =
                directOrNestedCompanion(
                    owner = owner,
                    directCompanion = ownerSymbol.fir.declarations.filterIsInstance<FirRegularClass>().firstOrNull(FirRegularClass::isTypeclassCompanionDeclaration),
                    nestedLookup = { session.companionSymbolOrNull(owner)?.fir },
                ) ?: return@getOrPut emptyList()
            buildList {
                collectAssociatedRules(
                    declaration = companion,
                    associatedOwner = owner,
                    session = session,
                    configuration = configuration,
                    sink = this,
                )
            }
        }
}

private fun collectAssociatedRules(
    declaration: FirDeclaration,
    associatedOwner: ClassId,
    session: FirSession,
    configuration: TypeclassConfiguration,
    sink: MutableList<VisibleInstanceRule>,
) {
    when (declaration) {
        is FirRegularClass -> {
            declaration.toObjectRules(session, configuration).forEach { rule ->
                sink += rule.copy(associatedOwner = associatedOwner)
            }
            declaration.declarations.forEach { nestedDeclaration ->
                collectAssociatedRules(
                    declaration = nestedDeclaration,
                    associatedOwner = associatedOwner,
                    session = session,
                    configuration = configuration,
                    sink = sink,
                )
            }
        }

        is FirTypeclassFunctionDeclaration ->
            declaration.toFunctionRules(session, configuration).forEach { rule ->
                sink += rule.copy(associatedOwner = associatedOwner)
            }

        is FirProperty ->
            declaration.toPropertyRules(session, configuration).forEach { rule ->
                sink += rule.copy(associatedOwner = associatedOwner)
            }

        else -> Unit
    }
}

internal fun FirRegularClass.derivedTypeclassIds(session: FirSession): Set<String> {
    return derivedAnnotationTargetClassIds(session)
        .filterTo(linkedSetOf()) { classId ->
            session.regularClassSymbolOrNull(classId)?.hasAnnotation(TYPECLASS_ANNOTATION_CLASS_ID, session) == true
        }.mapTo(linkedSetOf()) { classId ->
            classId.asString()
        }
}

internal fun FirRegularClass.derivedAnnotationTargetClassIds(session: FirSession): Set<ClassId> {
    val referencedClassIds =
        resolvedAnnotationsByClassId(
            annotationClassId = DERIVE_ANNOTATION_CLASS_ID,
            session = session,
        )
            .asSequence()
            .flatMap { annotation -> annotation.derivedReferencedClassIds().asSequence() }
            .toCollection(linkedSetOf())
    if (referencedClassIds.isNotEmpty()) {
        return referencedClassIds
    }

    val sourceAnnotation =
        annotations
            .filterIsInstance<FirAnnotationCall>()
            .firstOrNull { annotation ->
                annotation.annotationTypeRef.coneType.classId == DERIVE_ANNOTATION_CLASS_ID
            }
            ?: return emptySet()
    return sourceAnnotation.derivedReferencedClassIds()
}

internal fun Iterable<String>.expandedDerivedTypeclassIds(
    session: FirSession,
    configuration: TypeclassConfiguration,
): Set<String> =
    expandedDerivedTypeclassHeads(session, configuration).mapTo(linkedSetOf()) { expansion -> expansion.head.classifierId }

internal data class ExpandedDerivedTypeclassHead(
    val directTypeclassId: String,
    val directTypeParameters: List<TcTypeParameter>,
    val head: TcType.Constructor,
)

internal fun Iterable<String>.expandedDerivedTypeclassHeads(
    session: FirSession,
    configuration: TypeclassConfiguration,
): List<ExpandedDerivedTypeclassHead> =
    flatMap { typeclassId -> expandedDerivedTypeclassHeads(typeclassId, session, configuration) }
        .distinctBy { expansion -> "${expansion.directTypeclassId}:${expansion.head.normalizedKey()}" }

internal fun expandedDerivedTypeclassHeads(
    typeclassId: String,
    session: FirSession,
    configuration: TypeclassConfiguration,
): List<ExpandedDerivedTypeclassHead> {
    val classId = runCatching { ClassId.fromString(typeclassId) }.getOrNull() ?: return emptyList()
    val classSymbol =
        try {
            session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol
        } catch (_: IllegalArgumentException) {
            null
        } ?: return emptyList()
    val directTypeParameters =
        classSymbol.fir.typeParameters.mapIndexed { index, typeParameter ->
            TcTypeParameter(
                id = "derived-head:$typeclassId#$index",
                displayName = typeParameter.symbol.name.asString(),
            )
        }
    val rootHead =
        TcType.Constructor(
            classifierId = typeclassId,
            arguments = directTypeParameters.map { parameter -> TcType.Variable(parameter.id, parameter.displayName) },
        )
    return expandDerivedTypeclassHeads(
        classSymbol = classSymbol,
        currentHead = rootHead,
        directTypeclassId = typeclassId,
        directTypeParameters = directTypeParameters,
        session = session,
        configuration = configuration,
        previousWereTypeclass = true,
        visited = emptySet(),
    )
}

private fun expandDerivedTypeclassHeads(
    classSymbol: FirRegularClassSymbol,
    currentHead: TcType.Constructor,
    directTypeclassId: String,
    directTypeParameters: List<TcTypeParameter>,
    session: FirSession,
    configuration: TypeclassConfiguration,
    previousWereTypeclass: Boolean,
    visited: Set<String>,
): List<ExpandedDerivedTypeclassHead> {
    val visitKey = currentHead.normalizedKey()
    if (visitKey in visited) {
        return emptyList()
    }
    val currentClassId = runCatching { ClassId.fromString(currentHead.classifierId) }.getOrNull() ?: return emptyList()
    val currentIsTypeclass =
        configuration.isBuiltinTypeclass(currentClassId) || classSymbol.hasAnnotation(TYPECLASS_ANNOTATION_CLASS_ID, session)
    val expanded = buildList {
        if (currentIsTypeclass && previousWereTypeclass) {
            add(
                ExpandedDerivedTypeclassHead(
                    directTypeclassId = directTypeclassId,
                    directTypeParameters = directTypeParameters,
                    head = currentHead,
                ),
            )
        }
        val localTypeParameters =
            classSymbol.fir.typeParameters.mapIndexed { index, typeParameter ->
                TcTypeParameter(
                    id = "derived-head:${currentHead.classifierId}#$index",
                    displayName = typeParameter.symbol.name.asString(),
                )
            }
        val typeParameterBySymbol =
            classSymbol.fir.typeParameters.zip(localTypeParameters).associate { (typeParameter, parameter) ->
                typeParameter.symbol to parameter
            }
        val bindings =
            localTypeParameters.zip(currentHead.arguments).associate { (parameter, argument) ->
                parameter.id to argument
            }
        val nextPreviousWereTypeclass = previousWereTypeclass && currentIsTypeclass
        val nextVisited = visited + visitKey
        classSymbol.fir.declaredOrResolvedSuperTypes().forEach { superType ->
            val appliedSuperType =
                (coneTypeToModel(superType, typeParameterBySymbol)?.substituteType(bindings) as? TcType.Constructor)
                    ?: return@forEach
            val superClassId = runCatching { ClassId.fromString(appliedSuperType.classifierId) }.getOrNull() ?: return@forEach
            val superSymbol =
                try {
                    session.symbolProvider.getClassLikeSymbolByClassId(superClassId) as? FirRegularClassSymbol
                } catch (_: IllegalArgumentException) {
                    null
                } ?: return@forEach
            addAll(
                expandDerivedTypeclassHeads(
                    classSymbol = superSymbol,
                    currentHead = appliedSuperType,
                    directTypeclassId = directTypeclassId,
                    directTypeParameters = directTypeParameters,
                    session = session,
                    configuration = configuration,
                    previousWereTypeclass = nextPreviousWereTypeclass,
                    visited = nextVisited,
                ),
            )
        }
    }
    return expanded.distinctBy { expansion -> "${expansion.directTypeclassId}:${expansion.head.normalizedKey()}" }
}

private fun flattenDerivedTypeclassArgumentExpressions(expression: FirExpression): Sequence<FirExpression> =
    expression.typeclassCollectionLiteralArgumentsOrNull()?.flatMap(::flattenDerivedTypeclassArgumentExpressions)
        ?: when (expression) {
            is FirVarargArgumentsExpression -> expression.arguments.asSequence().flatMap(::flattenDerivedTypeclassArgumentExpressions)
            else -> sequenceOf(expression)
        }

private fun FirAnnotation.deriveValueExpressions(): Sequence<FirExpression> {
    val valueName = Name.identifier("value")
    val directArguments =
        findArgumentByName(valueName)
            ?.unwrapVarargValue()
            .orEmpty()
            .ifEmpty { findArgumentByName(valueName)?.let(::listOf).orEmpty() }
            .asSequence()
            .filterIsInstance<FirExpression>()
            .flatMap(::flattenDerivedTypeclassArgumentExpressions)
            .toList()
    if (directArguments.isNotEmpty()) {
        return directArguments.asSequence()
    }

    val annotationCall = this as? FirAnnotationCall ?: return emptySequence()
    val mappedArguments =
        annotationCall.argumentMapping.mapping
            .asSequence()
            .filter { (parameter, _) -> parameter == valueName }
            .map { (_, argument) -> argument }
            .ifEmpty { annotationCall.argumentList.arguments.asSequence() }
            .filterIsInstance<FirExpression>()
            .flatMap(::flattenDerivedTypeclassArgumentExpressions)
    return mappedArguments
}

private fun FirAnnotation.derivedReferencedClassIds(): Set<ClassId> =
    deriveValueExpressions()
        .mapNotNull { expression -> expression.derivedReferencedClassId() }
        .toCollection(linkedSetOf())

private fun FirExpression.derivedReferencedClassId(): ClassId? =
    when (this) {
        is FirGetClassCall ->
            when (val classExpression = argument) {
                is FirResolvedQualifier -> classExpression.classId
                is FirClassReferenceExpression -> classExpression.classTypeRef.coneType.lowerBoundIfFlexible().classId
                else -> classExpression.resolvedType.lowerBoundIfFlexible().classId
            }

        is FirResolvedQualifier -> classId
        else -> resolvedType.lowerBoundIfFlexible().classId
    }

private fun TypeclassConfiguration.builtinRules(): List<InstanceRule> =
    buildList {
        add(builtinSameRule())
        add(builtinNotSameRule())
        add(builtinSubtypeRule())
        add(builtinStrictSubtypeRule())
        add(builtinNullableRule())
        add(builtinNotNullableRule())
        add(builtinIsTypeclassInstanceRule())
        add(builtinKnownTypeRule())
        add(builtinTypeIdRule())
        add(builtinSameTypeConstructorRule())
        if (builtinKClassTypeclass == TypeclassBuiltinMode.ENABLED) {
            add(builtinKClassRule())
        }
        if (builtinKSerializerTypeclass == TypeclassBuiltinMode.ENABLED) {
            add(builtinKSerializerRule())
        }
    }

private fun builtinKClassRule(): InstanceRule {
    val parameter = TcTypeParameter(id = "builtin:kclass:T", displayName = "T")
    return InstanceRule(
        id = "builtin:kclass",
        typeParameters = listOf(parameter),
        providedType =
            TcType.Constructor(
                classifierId = KCLASS_CLASS_ID.asString(),
                arguments = listOf(TcType.Variable(parameter.id, parameter.displayName)),
            ),
        prerequisiteTypes = emptyList(),
    )
}

private fun builtinSameRule(): InstanceRule {
    val parameter = TcTypeParameter(id = "builtin:same:T", displayName = "T")
    return InstanceRule(
        id = "builtin:same",
        typeParameters = listOf(parameter),
        providedType =
            TcType.Constructor(
                classifierId = SAME_CLASS_ID.asString(),
                arguments =
                    listOf(
                        TcType.Variable(parameter.id, parameter.displayName),
                        TcType.Variable(parameter.id, parameter.displayName),
                    ),
            ),
        prerequisiteTypes = emptyList(),
    )
}

private fun builtinNotSameRule(): InstanceRule {
    val left = TcTypeParameter(id = "builtin:notsame:A", displayName = "A")
    val right = TcTypeParameter(id = "builtin:notsame:B", displayName = "B")
    return InstanceRule(
        id = "builtin:notsame",
        typeParameters = listOf(left, right),
        providedType =
            TcType.Constructor(
                classifierId = NOT_SAME_CLASS_ID.asString(),
                arguments =
                    listOf(
                        TcType.Variable(left.id, left.displayName),
                        TcType.Variable(right.id, right.displayName),
                    ),
            ),
        prerequisiteTypes = emptyList(),
    )
}

private fun builtinSubtypeRule(): InstanceRule {
    val sub = TcTypeParameter(id = "builtin:subtype:Sub", displayName = "Sub")
    val sup = TcTypeParameter(id = "builtin:subtype:Super", displayName = "Super")
    return InstanceRule(
        id = "builtin:subtype",
        typeParameters = listOf(sub, sup),
        providedType =
            TcType.Constructor(
                classifierId = SUBTYPE_CLASS_ID.asString(),
                arguments =
                    listOf(
                        TcType.Variable(sub.id, sub.displayName),
                        TcType.Variable(sup.id, sup.displayName),
                    ),
            ),
        prerequisiteTypes = emptyList(),
    )
}

private fun builtinStrictSubtypeRule(): InstanceRule {
    val sub = TcTypeParameter(id = "builtin:strict-subtype:Sub", displayName = "Sub")
    val sup = TcTypeParameter(id = "builtin:strict-subtype:Super", displayName = "Super")
    val subType = TcType.Variable(sub.id, sub.displayName)
    val superType = TcType.Variable(sup.id, sup.displayName)
    return InstanceRule(
        id = "builtin:strict-subtype",
        typeParameters = listOf(sub, sup),
        providedType =
            TcType.Constructor(
                classifierId = STRICT_SUBTYPE_CLASS_ID.asString(),
                arguments = listOf(subType, superType),
            ),
        prerequisiteTypes =
            listOf(
                TcType.Constructor(
                    classifierId = SUBTYPE_CLASS_ID.asString(),
                    arguments = listOf(subType, superType),
                ),
                TcType.Constructor(
                    classifierId = NOT_SAME_CLASS_ID.asString(),
                    arguments = listOf(subType, superType),
                ),
            ),
    )
}

private fun builtinNullableRule(): InstanceRule {
    val parameter = TcTypeParameter(id = "builtin:nullable:T", displayName = "T")
    return InstanceRule(
        id = "builtin:nullable",
        typeParameters = listOf(parameter),
        providedType =
            TcType.Constructor(
                classifierId = NULLABLE_CLASS_ID.asString(),
                arguments = listOf(TcType.Variable(parameter.id, parameter.displayName)),
            ),
        prerequisiteTypes = emptyList(),
    )
}

private fun builtinNotNullableRule(): InstanceRule {
    val parameter = TcTypeParameter(id = "builtin:not-nullable:T", displayName = "T")
    return InstanceRule(
        id = "builtin:not-nullable",
        typeParameters = listOf(parameter),
        providedType =
            TcType.Constructor(
                classifierId = NOT_NULLABLE_CLASS_ID.asString(),
                arguments = listOf(TcType.Variable(parameter.id, parameter.displayName)),
            ),
        prerequisiteTypes = emptyList(),
    )
}

private fun builtinIsTypeclassInstanceRule(): InstanceRule {
    val parameter = TcTypeParameter(id = "builtin:is-typeclass-instance:TC", displayName = "TC")
    return InstanceRule(
        id = "builtin:is-typeclass-instance",
        typeParameters = listOf(parameter),
        providedType =
            TcType.Constructor(
                classifierId = IS_TYPECLASS_INSTANCE_CLASS_ID.asString(),
                arguments = listOf(TcType.Variable(parameter.id, parameter.displayName)),
            ),
        prerequisiteTypes = emptyList(),
    )
}

private fun builtinKnownTypeRule(): InstanceRule {
    val parameter = TcTypeParameter(id = "builtin:known-type:T", displayName = "T")
    return InstanceRule(
        id = "builtin:known-type",
        typeParameters = listOf(parameter),
        providedType =
            TcType.Constructor(
                classifierId = KNOWN_TYPE_CLASS_ID.asString(),
                arguments = listOf(TcType.Variable(parameter.id, parameter.displayName)),
            ),
        prerequisiteTypes = emptyList(),
    )
}

private fun builtinTypeIdRule(): InstanceRule {
    val parameter = TcTypeParameter(id = "builtin:type-id:T", displayName = "T")
    return InstanceRule(
        id = "builtin:type-id",
        typeParameters = listOf(parameter),
        providedType =
            TcType.Constructor(
                classifierId = TYPE_ID_CLASS_ID.asString(),
                arguments = listOf(TcType.Variable(parameter.id, parameter.displayName)),
            ),
        prerequisiteTypes = emptyList(),
    )
}

private fun builtinSameTypeConstructorRule(): InstanceRule {
    val left = TcTypeParameter(id = "builtin:same-type-constructor:A", displayName = "A")
    val right = TcTypeParameter(id = "builtin:same-type-constructor:B", displayName = "B")
    return InstanceRule(
        id = "builtin:same-type-constructor",
        typeParameters = listOf(left, right),
        providedType =
            TcType.Constructor(
                classifierId = SAME_TYPE_CONSTRUCTOR_CLASS_ID.asString(),
                arguments =
                    listOf(
                        TcType.Variable(left.id, left.displayName),
                        TcType.Variable(right.id, right.displayName),
                    ),
            ),
        prerequisiteTypes = emptyList(),
    )
}

private fun builtinKSerializerRule(): InstanceRule {
    val parameter = TcTypeParameter(id = "builtin:kserializer:T", displayName = "T")
    return InstanceRule(
        id = "builtin:kserializer",
        typeParameters = listOf(parameter),
        providedType =
            TcType.Constructor(
                classifierId = KSERIALIZER_CLASS_ID.asString(),
                arguments = listOf(TcType.Variable(parameter.id, parameter.displayName)),
            ),
        prerequisiteTypes = emptyList(),
    )
}

private fun supportsBuiltinKSerializerGoal(
    goal: TcType,
    session: FirSession,
    canMaterializeVariable: (String) -> Boolean = { true },
): Boolean {
    if (!supportsBuiltinKSerializerShape(goal, canMaterializeVariable)) {
        return false
    }
    val constructor = goal as? TcType.Constructor ?: return true
    val targetType = constructor.arguments.single()
    return isProvablySerializableType(
        type = targetType,
        session = session,
        visiting = linkedSetOf(),
    )
}

private fun isProvablySerializableType(
    type: TcType,
    session: FirSession,
    visiting: MutableSet<String>,
): Boolean {
    return when (type) {
        TcType.StarProjection -> false
        is TcType.Projected -> isProvablySerializableType(type.type, session, visiting)
        is TcType.Variable -> false

        is TcType.Constructor -> {
            val visitKey = type.normalizedKey()
            if (!visiting.add(visitKey)) {
                return true
            }
            if (type.classifierId in BUILTIN_SERIALIZABLE_CLASSIFIER_IDS) {
                return type.arguments.all { argument ->
                    isProvablySerializableType(argument, session, visiting)
                }
            }
            val classId = runCatching { ClassId.fromString(type.classifierId) }.getOrNull() ?: return false
            val classSymbol =
                try {
                    session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol
                } catch (_: IllegalArgumentException) {
                    null
                } ?: return false
            classSymbol.hasAnnotation(SERIALIZABLE_ANNOTATION_CLASS_ID, session) &&
                type.arguments.all { argument ->
                    isProvablySerializableType(argument, session, visiting)
                }
        }
    }
}

internal data class ProvidedTypeExpansion(
    val declaredTypes: List<TcType>,
    val validTypes: List<TcType>,
    val invalidTypes: List<TcType>,
)

internal fun FirRegularClass.instanceProvidedType(
    session: FirSession,
    configuration: TypeclassConfiguration = TypeclassConfiguration(),
): TcType? = instanceProvidedTypes(session, configuration).validTypes.firstOrNull()

internal fun FirRegularClass.instanceProvidedTypes(
    session: FirSession,
    configuration: TypeclassConfiguration = TypeclassConfiguration(),
): ProvidedTypeExpansion =
    declaredOrResolvedSuperTypes().expandProvidedTypes(
        session = session,
        typeParameterBySymbol = emptyMap(),
        configuration = configuration,
    )

internal fun FirTypeclassFunctionDeclaration.instanceProvidedType(
    session: FirSession,
    configuration: TypeclassConfiguration = TypeclassConfiguration(),
): TcType? {
    return instanceProvidedTypes(session, configuration).validTypes.firstOrNull()
}

internal fun FirTypeclassFunctionDeclaration.instanceProvidedTypes(
    session: FirSession,
    configuration: TypeclassConfiguration = TypeclassConfiguration(),
): ProvidedTypeExpansion {
    val returnType = resolvedReturnConeTypeOrNull() ?: return ProvidedTypeExpansion(emptyList(), emptyList(), emptyList())
    val typeParameters =
        typeParameters.mapIndexed { index, typeParameter ->
            TcTypeParameter(
                id = "fir-function:${symbol.callableId}:$index:${typeParameter.symbol.name.asString()}",
                displayName = typeParameter.symbol.name.asString(),
            )
        }
    val typeParameterBySymbol =
        this.typeParameters.zip(typeParameters).associate { (typeParameter, parameter) ->
            typeParameter.symbol to parameter
        }
    return listOf(returnType).expandProvidedTypes(
        session = session,
        typeParameterBySymbol = typeParameterBySymbol,
        configuration = configuration,
    )
}

internal fun FirProperty.instanceProvidedType(
    session: FirSession,
    configuration: TypeclassConfiguration = TypeclassConfiguration(),
): TcType? {
    return instanceProvidedTypes(session, configuration).validTypes.firstOrNull()
}

internal fun FirProperty.instanceProvidedTypes(
    session: FirSession,
    configuration: TypeclassConfiguration = TypeclassConfiguration(),
): ProvidedTypeExpansion {
    val returnType = resolvedReturnConeTypeOrNull() ?: return ProvidedTypeExpansion(emptyList(), emptyList(), emptyList())
    return listOf(returnType).expandProvidedTypes(
        session = session,
        typeParameterBySymbol = emptyMap(),
        configuration = configuration,
    )
}

private fun FirTypeclassFunctionDeclaration.resolvedReturnConeTypeOrNull(): ConeKotlinType? =
    runCatching { symbol.resolvedReturnTypeRef.coneType }.getOrNull()

private fun FirProperty.resolvedReturnConeTypeOrNull(): ConeKotlinType? =
    runCatching { symbol.resolvedReturnTypeRef.coneType }.getOrNull()

internal fun FirRegularClass.declaredOrResolvedSuperTypes(): List<ConeKotlinType> {
    val declared = superTypeRefs.map { it.coneType }
    if (declared.isNotEmpty()) {
        return declared
    }
    return symbol.resolvedSuperTypes
}

private fun FirRegularClass.toObjectRules(
    session: FirSession,
    configuration: TypeclassConfiguration,
): List<VisibleInstanceRule> {
    if (classKind != ClassKind.OBJECT || !hasAnnotation(INSTANCE_ANNOTATION_CLASS_ID, session)) {
        return emptyList()
    }
    if (!firInstanceOwnerContext(session, symbol.classId).isIndexableScope) {
        return emptyList()
    }
    if (status.visibility == Visibilities.Private) {
        return emptyList()
    }
    if (source == null && status.visibility != Visibilities.Public) {
        return emptyList()
    }
    val ownerContext = firInstanceOwnerContext(session, symbol.classId)
    val providedTypes = instanceProvidedTypes(session, configuration)
    if (ownerContext.isTopLevel && !isLegalTopLevelInstanceLocation(session, providedTypes)) {
        return emptyList()
    }

    return providedTypes.validTypes.map { providedType ->
        VisibleInstanceRule(
            rule =
                InstanceRule(
                    id = directRuleId(
                        prefix = "fir-object",
                        declarationKey = symbol.classId.asString(),
                        providedType = providedType,
                    ),
                    typeParameters = emptyList(),
                    providedType = providedType,
                    prerequisiteTypes = emptyList(),
                ),
            associatedOwner = null,
            lookupReference = VisibleRuleLookupReference.LookupObject(symbol.classId),
            isFromDependencyBinary = source == null,
        )
    }
}

private fun FirTypeclassFunctionDeclaration.toFunctionRules(
    session: FirSession,
    configuration: TypeclassConfiguration,
): List<VisibleInstanceRule> {
    if (!hasAnnotation(INSTANCE_ANNOTATION_CLASS_ID, session)) {
        return emptyList()
    }
    val callableId = symbol.callableId
    val ownerContext = firInstanceOwnerContext(session, symbol.callableId)
    if (!ownerContext.isIndexableScope) {
        return emptyList()
    }
    if (status.visibility == Visibilities.Private) {
        return emptyList()
    }
    if (source == null && status.visibility != Visibilities.Public) {
        return emptyList()
    }
    if (receiverParameter != null || valueParameters.isNotEmpty() || status.isSuspend) {
        return emptyList()
    }
    val providedTypes = instanceProvidedTypes(session, configuration)
    if (ownerContext.isTopLevel && !isLegalTopLevelInstanceLocation(session, providedTypes)) {
        return emptyList()
    }

    val typeParameters =
        typeParameters.mapIndexed { index, typeParameter ->
            TcTypeParameter(
                id = "fir-function:${symbol.callableId}:$index:${typeParameter.symbol.name.asString()}",
                displayName = typeParameter.symbol.name.asString(),
            )
        }
    val typeParameterBySymbol =
        this.typeParameters.zip(typeParameters).associate { (typeParameter, parameter) ->
            typeParameter.symbol to parameter
        }
    val prerequisites =
        contextParameters.mapNotNull { parameter ->
            parameter.returnTypeRef.coneType.takeIf { type -> isTypeclassType(type, session, configuration) }?.let { type ->
                coneTypeToModel(type, typeParameterBySymbol)
            }
        }
    if (prerequisites.size != contextParameters.size) {
        return emptyList()
    }
    val lookupOwnerKey = symbol.lookupOwnerKey(session)
    val lookupReference =
        lookupFunctionShape(session, configuration)?.let { shape ->
            VisibleRuleLookupReference.LookupFunction(
                callableId = callableId,
                shape = shape,
                ownerKey = lookupOwnerKey,
            )
        }
    val declarationKey =
        lookupReference?.lookupIdentityKey()
            ?: "fun:${lookupOwnerKey ?: "-"}:${callableId}"

    return providedTypes.validTypes.map { providedType ->
        VisibleInstanceRule(
            rule =
                InstanceRule(
                    id = directRuleId(
                        prefix = "fir-function",
                        declarationKey = declarationKey,
                        providedType = providedType,
                        prerequisiteTypes = prerequisites,
                        typeParameters = typeParameters,
                    ),
                    typeParameters = typeParameters,
                    providedType = providedType,
                    prerequisiteTypes = prerequisites,
                ),
            associatedOwner = null,
            lookupReference = lookupReference,
            isFromDependencyBinary = source == null,
        )
    }
}

private fun FirProperty.toPropertyRules(
    session: FirSession,
    configuration: TypeclassConfiguration,
): List<VisibleInstanceRule> {
    if (!hasAnnotation(INSTANCE_ANNOTATION_CLASS_ID, session)) {
        return emptyList()
    }
    val callableId = symbol.callableId ?: return emptyList()
    val ownerContext = firInstanceOwnerContext(session, symbol.callableId)
    if (!ownerContext.isIndexableScope) {
        return emptyList()
    }
    if (status.visibility == Visibilities.Private) {
        return emptyList()
    }
    if (source == null && status.visibility != Visibilities.Public) {
        return emptyList()
    }
    if (receiverParameter != null || status.isLateInit || isVar || getter?.body != null) {
        return emptyList()
    }
    val providedTypes = instanceProvidedTypes(session, configuration)
    if (ownerContext.isTopLevel && !isLegalTopLevelInstanceLocation(session, providedTypes)) {
        return emptyList()
    }
    val lookupOwnerKey = symbol.lookupOwnerKey(session)
    val lookupReference = VisibleRuleLookupReference.LookupProperty(callableId, ownerKey = lookupOwnerKey)
    val declarationKey = lookupReference.lookupIdentityKey()

    return providedTypes.validTypes.map { providedType ->
        VisibleInstanceRule(
            rule =
                InstanceRule(
                    id = directRuleId(
                        prefix = "fir-property",
                        declarationKey = declarationKey,
                        providedType = providedType,
                    ),
                    typeParameters = emptyList(),
                    providedType = providedType,
                    prerequisiteTypes = emptyList(),
                ),
            associatedOwner = null,
            lookupReference = lookupReference,
            isFromDependencyBinary = source == null,
        )
    }
}

private fun org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol<*>.lookupOwnerKey(session: FirSession): String? =
    callableId?.classId?.let(::classLookupOwnerKey)
        ?: runCatching { session.firProvider.getFirCallableContainerFile(this)?.sourceFile?.path }
            .getOrNull()
            ?.let(::sourceLookupOwnerKey)
        ?: containerSource?.lookupOwnerKeyOrNull()

private fun FirTypeclassFunctionDeclaration.lookupFunctionShape(
    session: FirSession,
    configuration: TypeclassConfiguration,
): LookupFunctionShape? {
    val placeholderParameters =
        typeParameters.mapIndexed { index, _ ->
            TcTypeParameter(id = "P$index", displayName = "P$index")
        }
    val typeParameterBySymbol =
        this.typeParameters.zip(placeholderParameters).associate { (typeParameter, parameter) ->
            typeParameter.symbol to parameter
        }
    val contextTypes =
        contextParameters.map { parameter ->
            coneTypeToModel(parameter.returnTypeRef.coneType, typeParameterBySymbol) ?: return null
        }
    val regularTypes =
        valueParameters.map { parameter ->
            coneTypeToModel(parameter.returnTypeRef.coneType, typeParameterBySymbol) ?: return null
        }
    val extensionType =
        receiverParameter?.typeRef?.coneType?.let { receiverType ->
            coneTypeToModel(receiverType, typeParameterBySymbol) ?: return null
        }
    return LookupFunctionShape(
        dispatchReceiver = symbol.callableId.classId != null,
        extensionReceiverType = extensionType,
        typeParameterCount = visibleSignatureTypeParameterCount(session, configuration, dropTypeclassContexts = false),
        contextParameterTypes = contextTypes,
        regularParameterTypes = regularTypes,
    )
}

private fun FirTypeclassFunctionDeclaration.visibleSignatureTypeParameterCount(
    session: FirSession,
    configuration: TypeclassConfiguration,
    dropTypeclassContexts: Boolean,
): Int {
    val referenced = linkedSetOf<FirTypeParameterSymbol>()
    receiverParameter?.typeRef?.coneType?.collectReferencedTypeParameters(referenced)
    returnTypeRef.coneType.collectReferencedTypeParameters(referenced)
    valueParameters.forEach { parameter ->
        parameter.returnTypeRef.coneType.collectReferencedTypeParameters(referenced)
    }
    contextParameters
        .filterNot { parameter ->
            dropTypeclassContexts && isTypeclassType(parameter.returnTypeRef.coneType, session, configuration)
        }.forEach { parameter ->
            parameter.returnTypeRef.coneType.collectReferencedTypeParameters(referenced)
        }
    return typeParameters.count { typeParameter -> typeParameter.symbol in referenced }
}

private fun ConeKotlinType.collectReferencedTypeParameters(
    sink: MutableSet<FirTypeParameterSymbol>,
) {
    when (val lowered = lowerBoundIfFlexible()) {
        is ConeTypeParameterType -> sink += lowered.lookupTag.typeParameterSymbol
        is ConeClassLikeType ->
            lowered.typeArguments.forEach { argument ->
                argument.type?.collectReferencedTypeParameters(sink)
            }

        else -> Unit
    }
}

internal class SessionScopedCache<K : Any, V> {
    private val values = Collections.synchronizedMap(WeakHashMap<K, V>())

    fun getOrPut(
        key: K,
        defaultValue: () -> V,
    ): V {
        values[key]?.let { return it }
        return synchronized(values) {
            values[key] ?: defaultValue().also { computed ->
                values[key] = computed
            }
        }
    }
}

internal fun isTypeclassType(
    type: ConeKotlinType,
    session: FirSession,
    configuration: TypeclassConfiguration,
): Boolean {
    val classId = type.lowerBoundIfFlexible().classId ?: return false
    if (classId.isLocal) {
        return false
    }
    if (configuration.isBuiltinTypeclass(classId)) {
        return true
    }
    val classSymbol =
        try {
            session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol
        } catch (_: IllegalArgumentException) {
            null
        } ?: return false
    return classSymbol.hasAnnotation(TYPECLASS_ANNOTATION_CLASS_ID, session)
}

private fun TypeclassConfiguration.supportsTypeclassClassifierId(
    classifierId: String,
    session: FirSession,
): Boolean {
    val classId = runCatching { ClassId.fromString(classifierId) }.getOrNull() ?: return false
    if (classId.isLocal) {
        return false
    }
    if (isBuiltinTypeclass(classId)) {
        return true
    }
    val classSymbol =
        try {
            session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol
        } catch (_: IllegalArgumentException) {
            null
        } ?: return false
    return classSymbol.hasAnnotation(TYPECLASS_ANNOTATION_CLASS_ID, session)
}

internal fun FirClassSymbol<*>.sourceDeclaredFunctions(): List<FirNamedFunctionSymbol> =
    fir.declarations
        .filterIsInstance<FirTypeclassFunctionDeclaration>()
        .map(FirTypeclassFunctionDeclaration::symbol)

internal fun coneTypeToModel(
    type: ConeKotlinType,
    typeParameterBySymbol: Map<FirTypeParameterSymbol, TcTypeParameter>,
): TcType? {
    val lowerBound = type.approximateIntegerLiteralType().lowerBoundIfFlexible()
    return when (lowerBound) {
        is ConeClassLikeType -> {
            val classifierId = lowerBound.lookupTag.classId
            val arguments =
                lowerBound.typeArguments.map { argument ->
                    val nested = argument.type
                    if (nested == null) {
                        TcType.StarProjection
                    } else {
                        val nestedModel = coneTypeToModel(nested, typeParameterBySymbol) ?: return null
                        when (argument.kind) {
                            ProjectionKind.STAR -> TcType.StarProjection
                            ProjectionKind.IN -> TcType.Projected(org.jetbrains.kotlin.types.Variance.IN_VARIANCE, nestedModel)
                            ProjectionKind.OUT -> TcType.Projected(org.jetbrains.kotlin.types.Variance.OUT_VARIANCE, nestedModel)
                            ProjectionKind.INVARIANT -> nestedModel
                        }
                    }
                }
            TcType.Constructor(classifierId.asString(), arguments, isNullable = lowerBound.isMarkedNullable)
        }

        is ConeTypeParameterType -> {
            val parameter = typeParameterBySymbol[lowerBound.lookupTag.typeParameterSymbol] ?: return null
            TcType.Variable(parameter.id, parameter.displayName, isNullable = lowerBound.isMarkedNullable)
        }

        else -> null
    }
}

internal fun ConeKotlinType.approximateIntegerLiteralType(): ConeKotlinType =
    when (val lowerBound = lowerBoundIfFlexible()) {
        is ConeIntegerLiteralType -> lowerBound.getApproximatedType()
        else -> this
    }

internal fun Iterable<ConeKotlinType>.expandProvidedTypes(
    session: FirSession,
    typeParameterBySymbol: Map<FirTypeParameterSymbol, TcTypeParameter>,
    configuration: TypeclassConfiguration = TypeclassConfiguration(),
): ProvidedTypeExpansion {
    val declaredTypes = linkedMapOf<String, TcType>()
    val validTypes = linkedMapOf<String, TcType>()
    val invalidTypes = linkedMapOf<String, TcType>()
    for (type in this) {
        type.declaredProvidedTypeOrNull(session, typeParameterBySymbol, configuration)?.let { declaredType ->
            declaredTypes.putIfAbsent(declaredType.render(), declaredType)
        }
        val expansion =
            type.expandProvidedTypes(
                session = session,
                typeParameterBySymbol = typeParameterBySymbol,
                configuration = configuration,
                previousWereTypeclass = true,
                visited = linkedSetOf(),
            )
        expansion.validTypes.forEach { candidate ->
            validTypes.putIfAbsent(candidate.render(), candidate)
        }
        expansion.invalidTypes.forEach { candidate ->
            invalidTypes.putIfAbsent(candidate.render(), candidate)
        }
    }
    return ProvidedTypeExpansion(
        declaredTypes = declaredTypes.values.toList(),
        validTypes = validTypes.values.toList(),
        invalidTypes = invalidTypes.values.toList(),
    )
}

internal fun ConeKotlinType.localEvidenceTypes(
    session: FirSession,
    configuration: TypeclassConfiguration,
): List<ConeKotlinType> = localEvidenceTypes(session, configuration, visited = linkedSetOf())

private fun ConeKotlinType.localEvidenceTypes(
    session: FirSession,
    configuration: TypeclassConfiguration,
    visited: MutableSet<String>,
): List<ConeKotlinType> {
    val lowered = approximateIntegerLiteralType().lowerBoundIfFlexible() as? ConeClassLikeType ?: return emptyList()
    val classId = lowered.lookupTag.classId
    if (classId.isLocal) {
        return emptyList()
    }
    val visitKey = lowered.toString()
    if (!visited.add(visitKey)) {
        return emptyList()
    }

    val classSymbol =
        try {
            session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol
        } catch (_: IllegalArgumentException) {
            null
        } ?: return emptyList()

    val collected = linkedMapOf<String, ConeKotlinType>()
    val currentIsTypeclass =
        configuration.isBuiltinTypeclass(classId) || classSymbol.hasAnnotation(TYPECLASS_ANNOTATION_CLASS_ID, session)
    if (currentIsTypeclass) {
        collected[visitKey] = lowered
    }

    val substitutions =
        classSymbol.fir.typeParameters.zip(lowered.typeArguments).mapNotNull { (parameter, argument) ->
            argument.type?.let { type -> parameter.symbol to type }
        }.toMap()
    classSymbol.fir.declaredOrResolvedSuperTypes().forEach { superType ->
        val substitutedSuperType = substituteInferredTypes(superType, substitutions, session)
        substitutedSuperType.localEvidenceTypes(session, configuration, visited).forEach { candidate ->
            collected.putIfAbsent(candidate.toString(), candidate)
        }
    }

    return collected.values.toList()
}

private fun ConeKotlinType.declaredProvidedTypeOrNull(
    session: FirSession,
    typeParameterBySymbol: Map<FirTypeParameterSymbol, TcTypeParameter>,
    configuration: TypeclassConfiguration,
): TcType? {
    val lowered = approximateIntegerLiteralType().lowerBoundIfFlexible() as? ConeClassLikeType ?: return null
    val currentType = coneTypeToModel(lowered, typeParameterBySymbol) ?: return null
    val classId = lowered.lookupTag.classId
    if (classId.isLocal) {
        return null
    }
    val classSymbol =
        try {
            session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null
    val currentIsTypeclass =
        configuration.isBuiltinTypeclass(classId) || classSymbol.hasAnnotation(TYPECLASS_ANNOTATION_CLASS_ID, session)
    return currentType.takeIf { currentIsTypeclass }
}

private fun ConeKotlinType.expandProvidedTypes(
    session: FirSession,
    typeParameterBySymbol: Map<FirTypeParameterSymbol, TcTypeParameter>,
    configuration: TypeclassConfiguration,
    previousWereTypeclass: Boolean,
    visited: Set<String>,
): ProvidedTypeExpansion {
    val lowered = approximateIntegerLiteralType().lowerBoundIfFlexible() as? ConeClassLikeType
        ?: return ProvidedTypeExpansion(emptyList(), emptyList(), emptyList())
    val currentType = coneTypeToModel(lowered, typeParameterBySymbol)
        ?: return ProvidedTypeExpansion(emptyList(), emptyList(), emptyList())
    val visitKey = currentType.render()
    if (visitKey in visited) {
        return ProvidedTypeExpansion(emptyList(), emptyList(), emptyList())
    }

    val classId = lowered.lookupTag.classId
    if (classId.isLocal) {
        return ProvidedTypeExpansion(emptyList(), emptyList(), emptyList())
    }
    val classSymbol =
        try {
            session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol
        } catch (_: IllegalArgumentException) {
            null
        } ?: return ProvidedTypeExpansion(emptyList(), emptyList(), emptyList())

    val currentIsTypeclass =
        configuration.isBuiltinTypeclass(classId) || classSymbol.hasAnnotation(TYPECLASS_ANNOTATION_CLASS_ID, session)
    val validTypes = linkedMapOf<String, TcType>()
    val invalidTypes = linkedMapOf<String, TcType>()
    if (currentIsTypeclass) {
        if (previousWereTypeclass) {
            validTypes[visitKey] = currentType
        } else {
            invalidTypes[visitKey] = currentType
        }
    }

    val substitutions =
        classSymbol.fir.typeParameters.zip(lowered.typeArguments).mapNotNull { (parameter, argument) ->
            argument.type?.let { type -> parameter.symbol to type }
        }.toMap()
    val nextPreviousWereTypeclass = previousWereTypeclass && currentIsTypeclass
    val nextVisited = visited + visitKey
    classSymbol.fir.declaredOrResolvedSuperTypes().forEach { superType ->
        val substitutedSuperType = substituteInferredTypes(superType, substitutions, session)
        val nested =
            substitutedSuperType.expandProvidedTypes(
                session = session,
                typeParameterBySymbol = typeParameterBySymbol,
                configuration = configuration,
                previousWereTypeclass = nextPreviousWereTypeclass,
                visited = nextVisited,
            )
        nested.validTypes.forEach { candidate ->
            validTypes.putIfAbsent(candidate.render(), candidate)
        }
        nested.invalidTypes.forEach { candidate ->
            invalidTypes.putIfAbsent(candidate.render(), candidate)
        }
    }
    return ProvidedTypeExpansion(
        declaredTypes = emptyList(),
        validTypes = validTypes.values.toList(),
        invalidTypes = invalidTypes.values.toList(),
    )
}
