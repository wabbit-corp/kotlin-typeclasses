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
import one.wabbit.typeclass.plugin.model.isProvablyNotNullable
import one.wabbit.typeclass.plugin.model.isProvablyNullable
import one.wabbit.typeclass.plugin.model.normalizedKey
import one.wabbit.typeclass.plugin.model.render
import one.wabbit.typeclass.plugin.model.substituteType
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.findArgumentByName
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.declarations.unwrapVarargValue
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression
import org.jetbrains.kotlin.fir.resolve.providers.firProvider
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
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
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.types.Variance
import java.util.Collections
import java.util.WeakHashMap

internal class TypeclassPluginSharedState(
    internal val configuration: TypeclassConfiguration = TypeclassConfiguration(),
) {
    private val discoveryIndexes = SessionScopedCache<FirSession, DiscoveryIndexes>()

    fun topLevelContextualCallables(session: FirSession): Set<CallableId> {
        return sourceIndex(session).topLevelContextualCallables
    }

    fun topLevelContextualPackages(session: FirSession): Set<FqName> {
        return sourceIndex(session).topLevelContextualPackages
    }

    fun memberContextualNames(
        session: FirSession,
        ownerClassId: ClassId,
    ): Set<Name> {
        return sourceIndex(session).memberContextualNames[ownerClassId].orEmpty()
    }

    fun rulesForGoal(
        session: FirSession,
        goal: TcType,
        canMaterializeVariable: (String) -> Boolean = { true },
    ): List<InstanceRule> = resolutionIndex(session).rulesForGoal(goal, session, canMaterializeVariable)

    fun canDeriveGoal(
        session: FirSession,
        goal: TcType,
        availableContexts: List<TcType> = emptyList(),
    ): Boolean = resolutionIndex(session).canDeriveGoal(goal, session, availableContexts)

    fun allowedAssociatedOwnersForProvidedType(
        session: FirSession,
        providedType: TcType,
    ): Set<ClassId> = resolutionIndex(session).allowedAssociatedOwnersForGoal(providedType, session)

    fun isTypeclassType(
        session: FirSession,
        type: ConeKotlinType,
    ): Boolean = isTypeclassType(type, session, configuration)

    private fun sourceIndex(session: FirSession): SourceIndex =
        discoveryIndexes(session).sourceIndex

    private fun resolutionIndex(session: FirSession): ResolutionIndex =
        discoveryIndexes(session).resolutionIndex

    private fun discoveryIndexes(session: FirSession): DiscoveryIndexes =
        discoveryIndexes.getOrPut(session) {
            buildDiscoveryIndexes(session)
        }

    private fun buildDiscoveryIndexes(session: FirSession): DiscoveryIndexes {
        val topLevelCallables = linkedSetOf<CallableId>()
        val topLevelPackages = linkedSetOf<FqName>()
        val memberNamesByOwner = linkedMapOf<ClassId, MutableSet<Name>>()
        val scanner = FirResolutionScanner(session, configuration)
        scanTopLevelDeclarations(
            source = FirTopLevelDeclarationSource(session),
            visit = { declaration ->
                collectContextualFunctions(
                    declaration = declaration,
                    topLevelCallables = topLevelCallables,
                    topLevelPackages = topLevelPackages,
                    memberNamesByOwner = memberNamesByOwner,
                )
                scanner.scanDeclaration(declaration, associatedOwner = null)
            },
        )

        return DiscoveryIndexes(
            sourceIndex =
                SourceIndex(
                    topLevelContextualCallables = topLevelCallables,
                    topLevelContextualPackages = topLevelPackages,
                    memberContextualNames = memberNamesByOwner.mapValues { (_, names) -> names.toSet() },
                ),
            resolutionIndex = scanner.build(),
        )
    }

    @OptIn(DirectDeclarationsAccess::class)
    private fun collectContextualFunctions(
        declaration: FirDeclaration,
        topLevelCallables: MutableSet<CallableId>,
        topLevelPackages: MutableSet<FqName>,
        memberNamesByOwner: MutableMap<ClassId, MutableSet<Name>>,
    ) {
        when (declaration) {
            is FirTypeclassFunctionDeclaration -> {
                if (declaration.contextParameters.isEmpty()) {
                    return
                }
                val callableId = declaration.symbol.callableId
                val ownerClassId = callableId.classId
                if (ownerClassId == null) {
                    topLevelCallables += callableId
                    topLevelPackages += callableId.packageName
                } else {
                    memberNamesByOwner.getOrPut(ownerClassId, ::linkedSetOf) += callableId.callableName
                }
            }

            is FirRegularClass -> {
                declaration.declarations.forEach { nestedDeclaration ->
                    collectContextualFunctions(
                        declaration = nestedDeclaration,
                        topLevelCallables = topLevelCallables,
                        topLevelPackages = topLevelPackages,
                        memberNamesByOwner = memberNamesByOwner,
                    )
                }
            }

            else -> Unit
        }
    }
}

private data class DiscoveryIndexes(
    val sourceIndex: SourceIndex,
    val resolutionIndex: ResolutionIndex,
)

internal interface TopLevelDeclarationSource<D> {
    fun packageNames(): Sequence<FqName>

    fun declarationsInPackage(packageName: FqName): Sequence<D>
}

internal fun <D> scanTopLevelDeclarations(
    source: TopLevelDeclarationSource<D>,
    visit: (D) -> Unit,
) {
    source.packageNames().forEach { packageName ->
        source.declarationsInPackage(packageName).forEach(visit)
    }
}

private class FirTopLevelDeclarationSource(
    session: FirSession,
) : TopLevelDeclarationSource<FirDeclaration> {
    private val symbolProvider = session.firProvider.symbolProvider
    private val symbolNamesProvider = symbolProvider.symbolNamesProvider

    override fun packageNames(): Sequence<FqName> =
        symbolNamesProvider.getPackageNames().orEmpty().asSequence().map(::FqName)

    override fun declarationsInPackage(packageName: FqName): Sequence<FirDeclaration> =
        sequence {
            symbolNamesProvider.getTopLevelCallableNamesInPackage(packageName).orEmpty().forEach { callableName ->
                symbolProvider.getTopLevelFunctionSymbols(packageName, callableName).forEach { functionSymbol ->
                    yield(functionSymbol.fir)
                }
                symbolProvider.getTopLevelPropertySymbols(packageName, callableName).forEach { propertySymbol ->
                    yield(propertySymbol.fir)
                }
            }
            symbolNamesProvider.getTopLevelClassifierNamesInPackage(packageName).orEmpty().forEach { classifierName ->
                val classSymbol =
                    symbolProvider.getClassLikeSymbolByClassId(ClassId(packageName, classifierName))
                        as? FirRegularClassSymbol ?: return@forEach
                yield(classSymbol.fir)
            }
        }
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
                }

                val nextAssociatedOwner =
                    when {
                        classId.shortClassName == SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT -> classId.outerClassId
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

    fun build(): ResolutionIndex =
        ResolutionIndex(
            configuration = configuration,
            topLevelRules = topLevelRules.toList() + configuration.builtinRules().map { rule ->
                VisibleInstanceRule(rule = rule, associatedOwner = null)
            },
            associatedRulesByOwner = associatedRulesByOwner.mapValues { (_, rules) -> rules.toList() },
            classInfoById = classInfoById.toMutableMap(),
            derivableTypeclassIdsByOwner =
                derivableTypeclassIdsByOwner.mapValues { (_, typeclassIds) -> typeclassIds.toSet() },
            deriveEquivTargetIdsByOwner =
                deriveEquivTargetIdsByOwner.mapValues { (_, targetIds) -> targetIds.toSet() },
        )
}

private data class ResolutionIndex(
    val configuration: TypeclassConfiguration,
    val topLevelRules: List<VisibleInstanceRule>,
    val associatedRulesByOwner: Map<ClassId, List<VisibleInstanceRule>>,
    val classInfoById: MutableMap<String, VisibleClassHierarchyInfo>,
    val derivableTypeclassIdsByOwner: Map<String, Set<String>>,
    val deriveEquivTargetIdsByOwner: Map<String, Set<String>>,
) {
    private val lazilyDiscoveredAssociatedRulesByOwner: MutableMap<ClassId, List<VisibleInstanceRule>> = linkedMapOf()
    private val lazilyDiscoveredDerivableTypeclassIdsByOwner: MutableMap<String, Set<String>> = linkedMapOf()
    private val lazilyDiscoveredDeriveEquivTargetIdsByOwner: MutableMap<String, Set<String>> = linkedMapOf()

    fun rulesForGoal(
        goal: TcType,
        session: FirSession,
        canMaterializeVariable: (String) -> Boolean,
    ): List<InstanceRule> {
        val owners = allowedAssociatedOwnersForGoal(goal, session)
        val associated =
            owners.flatMapTo(linkedSetOf()) { owner ->
                associatedRulesByOwner[owner].orEmpty() + discoverAssociatedRules(owner, session)
            }
        return (topLevelRules + associated)
            .asSequence()
            .filter { visibleRule ->
                visibleRule.rule.id != "builtin:kclass" || supportsBuiltinKClassGoal(goal, canMaterializeVariable)
            }
            .filter { visibleRule ->
                visibleRule.rule.id != "builtin:subtype" || supportsBuiltinSubtypeGoal(goal, classInfoById)
            }
            .filter { visibleRule ->
                visibleRule.rule.id != "builtin:strict-subtype" || supportsBuiltinStrictSubtypeGoal(goal, classInfoById)
            }
            .filter { visibleRule ->
                visibleRule.rule.id != "builtin:kserializer" || supportsBuiltinKSerializerGoal(goal, session, canMaterializeVariable)
            }
            .filter { visibleRule ->
                visibleRule.rule.id != "builtin:notsame" || supportsBuiltinNotSameGoal(goal)
            }
            .filter { visibleRule ->
                visibleRule.rule.id != "builtin:nullable" || supportsBuiltinNullableGoal(goal)
            }
            .filter { visibleRule ->
                visibleRule.rule.id != "builtin:not-nullable" || supportsBuiltinNotNullableGoal(goal)
            }
            .filter { visibleRule ->
                visibleRule.rule.id != "builtin:is-typeclass-instance" || supportsBuiltinIsTypeclassInstanceGoal(goal) { classifierId ->
                    configuration.supportsTypeclassClassifierId(classifierId, session)
                }
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
    }

    fun canDeriveGoal(
        goal: TcType,
        session: FirSession,
        availableContexts: List<TcType> = emptyList(),
        visiting: MutableSet<String> = linkedSetOf(),
    ): Boolean {
        val key = goal.normalizedKey()
        if (!visiting.add(key)) {
            return true
        }
        val directPlanner =
            TypeclassResolutionPlanner(
                ruleProvider = { nestedGoal -> rulesForGoal(nestedGoal, session) { true } },
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
                    val preciseTargets = discoveredDeriveEquivTargetIds(owner, session)
                    preciseTargets.isNotEmpty() && targetType.classifierId in preciseTargets
                }
            }

            else -> {
                val targetType = constructor.arguments.lastOrNull() as? TcType.Constructor ?: return false
                if (targetType.isNullable) {
                    return false
                }
                derivationOwnersForTarget(targetType.classifierId, session).any { owner ->
                    canDeriveUnaryGoalFromOwner(
                        typeclassId = typeclassId,
                        targetType = targetType,
                        owner = owner,
                        session = session,
                        availableContexts = availableContexts,
                        visiting = visiting,
                    )
                }
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

    private fun canDeriveUnaryGoalFromOwner(
        typeclassId: String,
        targetType: TcType.Constructor,
        owner: String,
        session: FirSession,
        availableContexts: List<TcType>,
        visiting: MutableSet<String>,
    ): Boolean {
        if (typeclassId !in discoveredDerivableTypeclassIds(owner, session)) {
            return false
        }
        if (owner != targetType.classifierId) {
            val ownerClassId = runCatching { ClassId.fromString(owner) }.getOrNull() ?: return true
            val ownerSymbol = session.regularClassSymbolOrNull(ownerClassId) ?: return true
            val ownerDeclaration = ownerSymbol.fir
            if (ownerDeclaration.status.modality != Modality.SEALED || ownerDeclaration.source == null) {
                return true
            }
            val targetClassId = runCatching { ClassId.fromString(targetType.classifierId) }.getOrNull() ?: return true
            val targetSymbol = session.regularClassSymbolOrNull(targetClassId) ?: return true
            return canDeriveTargetShape(
                typeclassId = typeclassId,
                targetType = targetType,
                classId = targetClassId,
                declaration = targetSymbol.fir,
                session = session,
                availableContexts = availableContexts,
                visiting = visiting,
            )
        }
        val classId = runCatching { ClassId.fromString(owner) }.getOrNull() ?: return false
        val symbol = session.regularClassSymbolOrNull(classId) ?: return true
        return canDeriveTargetShape(
            typeclassId = typeclassId,
            targetType = targetType,
            classId = classId,
            declaration = symbol.fir,
            session = session,
            availableContexts = availableContexts,
            visiting = visiting,
        )
    }

    private fun canDeriveTargetShape(
        typeclassId: String,
        targetType: TcType.Constructor,
        classId: ClassId,
        declaration: FirRegularClass,
        session: FirSession,
        availableContexts: List<TcType>,
        visiting: MutableSet<String>,
    ): Boolean {
        val expandedDeriveViaTypeclassIds =
            declaration.deriveViaTypeclassIds(session).let { supportedTypeclassIds ->
                supportedTypeclassIds + supportedTypeclassIds.expandedDerivedTypeclassIds(session, configuration)
            }
        if (typeclassId in expandedDeriveViaTypeclassIds) {
            return true
        }
        if (declaration.classKind == ClassKind.OBJECT || declaration.classKind == ClassKind.ENUM_CLASS) {
            return true
        }
        if (declaration.status.modality == Modality.SEALED) {
            if (declaration.source == null) {
                return true
            }
            return canDeriveSealedGoalFromTarget(
                typeclassId = typeclassId,
                classId = classId,
                rootType = targetType,
                rootTypeParameterVariances = declaration.typeParameters.map { typeParameter -> typeParameter.symbol.fir.variance },
                session = session,
                availableContexts = availableContexts,
                visiting = visiting,
            )
        }
        val constructor =
            declaration.declarations
                .filterIsInstance<org.jetbrains.kotlin.fir.declarations.FirConstructor>()
                .singleOrNull { candidate -> candidate.isPrimary }
                ?: return false
        val ruleTypeParameters =
            declaration.typeParameters.mapIndexed { index, typeParameter ->
                TcTypeParameter(
                    id = "${classId.asString()}#$index",
                    displayName = typeParameter.symbol.name.asString(),
                )
            }
        val typeParameterBySymbol = declaration.typeParameters.zip(ruleTypeParameters).associate { (typeParameter, parameter) ->
            typeParameter.symbol to parameter
        }
        val appliedBindings =
            ruleTypeParameters.zip(targetType.arguments)
                .associate { (parameter, appliedType) -> parameter.id to appliedType }
        return constructor.valueParameters.all { parameter ->
            val fieldType = coneTypeToModel(parameter.returnTypeRef.coneType, typeParameterBySymbol) ?: return@all false
            val prerequisiteGoal =
                TcType.Constructor(
                    classifierId = typeclassId,
                    arguments = listOf(fieldType.substituteType(appliedBindings)),
                )
            canDeriveGoal(
                goal = prerequisiteGoal,
                session = session,
                availableContexts = availableContexts,
                visiting = visiting,
            )
        }
    }

    private fun canDeriveSealedGoalFromTarget(
        typeclassId: String,
        classId: ClassId,
        rootType: TcType.Constructor,
        rootTypeParameterVariances: List<Variance>,
        session: FirSession,
        availableContexts: List<TcType>,
        visiting: MutableSet<String>,
    ): Boolean {
        val subclassIds =
            classInfoById
                .filterValues { info -> classId.asString() in info.superClassifiers }
                .keys
        if (subclassIds.isEmpty()) {
            return false
        }
        return subclassIds.all { subclassId ->
            val caseType =
                deriveSealedCaseType(
                    rootClassId = classId,
                    rootTargetType = rootType,
                    rootTypeParameterVariances = rootTypeParameterVariances,
                    subclassId = subclassId,
                    session = session,
                ) ?: return@all true
            val prerequisiteGoal =
                TcType.Constructor(
                    classifierId = typeclassId,
                    arguments = listOf(caseType),
                )
            canDeriveGoal(
                goal = prerequisiteGoal,
                session = session,
                availableContexts = availableContexts,
                visiting = visiting,
            )
        }
    }

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
            val ownerSymbol = session.symbolProvider.getClassLikeSymbolByClassId(owner) as? FirRegularClassSymbol ?: return@getOrPut emptyList()
            val companion =
                ownerSymbol.fir.declarations
                    .filterIsInstance<FirRegularClass>()
                    .firstOrNull { declaration ->
                        declaration.symbol.classId.shortClassName == SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT
                    } ?: return@getOrPut emptyList()
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
    val deriveArguments =
        buildList {
            addAll(
                resolvedAnnotationsByClassId(
                    annotationClassId = DERIVE_ANNOTATION_CLASS_ID,
                    session = session,
                )
                    .flatMap { annotation ->
                        annotation.findArgumentByName(Name.identifier("value"))
                            ?.unwrapVarargValue()
                            .orEmpty()
                    },
            )
            if (isEmpty()) {
                val deriveAnnotation =
                    annotations
                        .filterIsInstance<FirAnnotationCall>()
                        .firstOrNull { annotation ->
                            annotation.annotationTypeRef.coneType.classId == DERIVE_ANNOTATION_CLASS_ID
                        }
                if (deriveAnnotation != null) {
                    addAll(
                        deriveAnnotation.argumentMapping.mapping.values.ifEmpty {
                            deriveAnnotation.argumentList.arguments
                        },
                    )
                }
            }
        }
    if (deriveArguments.isEmpty()) {
        return emptySet()
    }
    val derivedIds =
        deriveArguments
            .asSequence()
        .flatMap(::flattenDerivedTypeclassArgumentExpressions)
        .mapNotNull { expression -> expression.derivedTypeclassId(session) }
        .toCollection(linkedSetOf())
    return derivedIds
}

private fun Iterable<String>.expandedDerivedTypeclassIds(
    session: FirSession,
    configuration: TypeclassConfiguration,
): Set<String> =
    flatMapTo(linkedSetOf()) { typeclassId ->
        expandDerivedTypeclassIds(
            typeclassId = typeclassId,
            session = session,
            configuration = configuration,
            previousWereTypeclass = true,
            visited = linkedSetOf(),
        )
    }

private fun expandDerivedTypeclassIds(
    typeclassId: String,
    session: FirSession,
    configuration: TypeclassConfiguration,
    previousWereTypeclass: Boolean,
    visited: MutableSet<String>,
): Set<String> {
    if (!visited.add(typeclassId)) {
        return emptySet()
    }
    val classId = runCatching { ClassId.fromString(typeclassId) }.getOrNull() ?: return emptySet()
    val classSymbol =
        try {
            session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol
        } catch (_: IllegalArgumentException) {
            null
        } ?: return emptySet()
    val currentIsTypeclass =
        configuration.isBuiltinTypeclass(classId) || classSymbol.hasAnnotation(TYPECLASS_ANNOTATION_CLASS_ID, session)
    val expanded = linkedSetOf<String>()
    if (currentIsTypeclass && previousWereTypeclass) {
        expanded += typeclassId
    }
    val nextPreviousWereTypeclass = previousWereTypeclass && currentIsTypeclass
    classSymbol.fir.declaredOrResolvedSuperTypes().forEach { superType ->
        val superTypeId = superType.lowerBoundIfFlexible().classId?.asString() ?: return@forEach
        expanded +=
            expandDerivedTypeclassIds(
                typeclassId = superTypeId,
                session = session,
                configuration = configuration,
                previousWereTypeclass = nextPreviousWereTypeclass,
                visited = visited,
            )
    }
    return expanded
}

private fun flattenDerivedTypeclassArgumentExpressions(expression: FirExpression): Sequence<FirExpression> =
    when (expression) {
        is FirVarargArgumentsExpression -> expression.arguments.asSequence().flatMap(::flattenDerivedTypeclassArgumentExpressions)
        else -> sequenceOf(expression)
    }

private fun FirExpression.derivedTypeclassId(session: FirSession): String? {
    val classId =
        when (this) {
            is FirGetClassCall -> argument.resolvedType.lowerBoundIfFlexible().classId
            else -> resolvedType.lowerBoundIfFlexible().classId
        } ?: return null
    val classSymbol =
        try {
            session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null
    return classId.asString().takeIf { classSymbol.hasAnnotation(TYPECLASS_ANNOTATION_CLASS_ID, session) }
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
    return isPotentiallySerializableType(
        type = targetType,
        session = session,
        visiting = linkedSetOf(),
    )
}

private fun supportsBuiltinNullableGoal(goal: TcType): Boolean {
    val constructor = goal as? TcType.Constructor ?: return true
    if (constructor.classifierId != NULLABLE_CLASS_ID.asString()) {
        return true
    }
    val targetType = constructor.arguments.singleOrNull() ?: return false
    return targetType.isProvablyNullable()
}

private fun supportsBuiltinNotNullableGoal(goal: TcType): Boolean {
    val constructor = goal as? TcType.Constructor ?: return true
    if (constructor.classifierId != NOT_NULLABLE_CLASS_ID.asString()) {
        return true
    }
    val targetType = constructor.arguments.singleOrNull() ?: return false
    return targetType.isProvablyNotNullable()
}

private fun isPotentiallySerializableType(
    type: TcType,
    session: FirSession,
    visiting: MutableSet<String>,
): Boolean {
    return when (type) {
        TcType.StarProjection -> true
        is TcType.Projected -> isPotentiallySerializableType(type.type, session, visiting)
        is TcType.Variable -> true

        is TcType.Constructor -> {
            val visitKey = type.normalizedKey()
            if (!visiting.add(visitKey)) {
                return true
            }
            if (type.classifierId in BUILTIN_SERIALIZABLE_CLASSIFIER_IDS) {
                return type.arguments.all { argument ->
                    isPotentiallySerializableType(argument, session, visiting)
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
                    isPotentiallySerializableType(argument, session, visiting)
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
    if (receiverParameter != null || valueParameters.isNotEmpty()) {
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

    return providedTypes.validTypes.map { providedType ->
        VisibleInstanceRule(
            rule =
                InstanceRule(
                    id = directRuleId(
                        prefix = "fir-function",
                        declarationKey = symbol.callableId.toString(),
                        providedType = providedType,
                        prerequisiteTypes = prerequisites,
                        typeParameters = typeParameters,
                    ),
                    typeParameters = typeParameters,
                    providedType = providedType,
                    prerequisiteTypes = prerequisites,
                ),
            associatedOwner = null,
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
    if (receiverParameter != null) {
        return emptyList()
    }
    val providedTypes = instanceProvidedTypes(session, configuration)
    if (ownerContext.isTopLevel && !isLegalTopLevelInstanceLocation(session, providedTypes)) {
        return emptyList()
    }

    return providedTypes.validTypes.map { providedType ->
        VisibleInstanceRule(
            rule =
                InstanceRule(
                    id = directRuleId(
                        prefix = "fir-property",
                        declarationKey = symbol.callableId.toString(),
                        providedType = providedType,
                    ),
                    typeParameters = emptyList(),
                    providedType = providedType,
                    prerequisiteTypes = emptyList(),
                ),
            associatedOwner = null,
        )
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

internal data class SourceIndex(
    val topLevelContextualCallables: Set<CallableId>,
    val topLevelContextualPackages: Set<FqName>,
    val memberContextualNames: Map<ClassId, Set<Name>>,
)

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
