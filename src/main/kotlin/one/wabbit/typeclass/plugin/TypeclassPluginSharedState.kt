@file:OptIn(
    org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess::class,
    org.jetbrains.kotlin.fir.symbols.SymbolInternals::class,
)

package one.wabbit.typeclass.plugin

import one.wabbit.typeclass.plugin.model.InstanceRule
import one.wabbit.typeclass.plugin.model.TcType
import one.wabbit.typeclass.plugin.model.TcTypeParameter
import one.wabbit.typeclass.plugin.model.render
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
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
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.lowerBoundIfFlexible
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.name.*
import org.jetbrains.kotlin.name.SpecialNames
import java.util.Collections
import java.util.WeakHashMap

internal class TypeclassPluginSharedState(
    internal val configuration: TypeclassConfiguration = TypeclassConfiguration(),
) {
    private val sourceIndexes = SessionScopedCache<FirSession, SourceIndex>()
    private val resolutionIndexes = SessionScopedCache<FirSession, ResolutionIndex>()

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
    ): List<InstanceRule> = resolutionIndex(session).rulesForGoal(goal)

    fun canDeriveGoal(
        session: FirSession,
        goal: TcType,
    ): Boolean = resolutionIndex(session).canDeriveGoal(goal)

    fun allowedAssociatedOwnersForProvidedType(
        session: FirSession,
        providedType: TcType,
    ): Set<ClassId> = resolutionIndex(session).allowedAssociatedOwnersForGoal(providedType)

    fun isTypeclassType(
        session: FirSession,
        type: ConeKotlinType,
    ): Boolean = isTypeclassType(type, session, configuration)

    private fun sourceIndex(session: FirSession): SourceIndex =
        sourceIndexes.getOrPut(session) {
            buildSourceIndex(session)
        }

    private fun resolutionIndex(session: FirSession): ResolutionIndex =
        resolutionIndexes.getOrPut(session) {
            buildResolutionIndex(session)
        }

    private fun buildSourceIndex(session: FirSession): SourceIndex {
        val topLevelCallables = linkedSetOf<CallableId>()
        val topLevelPackages = linkedSetOf<FqName>()
        val memberNamesByOwner = linkedMapOf<ClassId, MutableSet<Name>>()
        val symbolProvider = session.firProvider.symbolProvider
        val symbolNamesProvider = symbolProvider.symbolNamesProvider
        val packageNames = symbolNamesProvider.getPackageNames().orEmpty()

        packageNames.forEach { packageName ->
            val fqName = FqName(packageName)

            symbolNamesProvider.getTopLevelCallableNamesInPackage(fqName).orEmpty().forEach { callableName ->
                symbolProvider.getTopLevelFunctionSymbols(fqName, callableName).forEach { functionSymbol ->
                    collectContextualFunctions(
                        declaration = functionSymbol.fir,
                        topLevelCallables = topLevelCallables,
                        topLevelPackages = topLevelPackages,
                        memberNamesByOwner = memberNamesByOwner,
                    )
                }
            }

            symbolNamesProvider.getTopLevelClassifierNamesInPackage(fqName).orEmpty().forEach { classifierName ->
                val classSymbol =
                    symbolProvider.getClassLikeSymbolByClassId(ClassId(fqName, classifierName))
                        as? FirRegularClassSymbol ?: return@forEach
                collectContextualFunctions(
                    declaration = classSymbol.fir,
                    topLevelCallables = topLevelCallables,
                    topLevelPackages = topLevelPackages,
                    memberNamesByOwner = memberNamesByOwner,
                )
            }
        }

        return SourceIndex(
            topLevelContextualCallables = topLevelCallables,
            topLevelContextualPackages = topLevelPackages,
            memberContextualNames = memberNamesByOwner.mapValues { (_, names) -> names.toSet() },
        )
    }

    private fun buildResolutionIndex(session: FirSession): ResolutionIndex {
        val scanner = FirResolutionScanner(session, configuration)
        val symbolProvider = session.firProvider.symbolProvider
        val symbolNamesProvider = symbolProvider.symbolNamesProvider
        val packageNames = symbolNamesProvider.getPackageNames().orEmpty()

        packageNames.forEach { packageName ->
            val fqName = FqName(packageName)

            symbolNamesProvider.getTopLevelCallableNamesInPackage(fqName).orEmpty().forEach { callableName ->
                symbolProvider.getTopLevelFunctionSymbols(fqName, callableName).forEach { functionSymbol ->
                    scanner.scanDeclaration(functionSymbol.fir, associatedOwner = null)
                }
                symbolProvider.getTopLevelPropertySymbols(fqName, callableName).forEach { propertySymbol ->
                    scanner.scanDeclaration(propertySymbol.fir, associatedOwner = null)
                }
            }

            symbolNamesProvider.getTopLevelClassifierNamesInPackage(fqName).orEmpty().forEach { classifierName ->
                val classSymbol =
                    symbolProvider.getClassLikeSymbolByClassId(ClassId(fqName, classifierName))
                        as? FirRegularClassSymbol ?: return@forEach
                scanner.scanDeclaration(classSymbol.fir, associatedOwner = null)
            }
        }

        return scanner.build()
    }

    @OptIn(DirectDeclarationsAccess::class)
    private fun collectContextualFunctions(
        declaration: FirDeclaration,
        topLevelCallables: MutableSet<CallableId>,
        topLevelPackages: MutableSet<FqName>,
        memberNamesByOwner: MutableMap<ClassId, MutableSet<Name>>,
    ) {
        when (declaration) {
            is FirSimpleFunction -> {
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

private class FirResolutionScanner(
    private val session: FirSession,
    private val configuration: TypeclassConfiguration,
) {
    private val topLevelRules = mutableListOf<InstanceRule>()
    private val associatedRulesByOwner = linkedMapOf<ClassId, MutableList<InstanceRule>>()
    private val classInfoById = linkedMapOf<String, ResolutionClassHierarchyInfo>()
    private val derivableClassIds = linkedSetOf<String>()

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
                        ResolutionClassHierarchyInfo(
                            superClassifiers =
                                declaration.superTypeRefs.mapNotNull { superTypeRef ->
                                    superTypeRef.coneType.classId?.asString()
                                }.toSet(),
                            isSealed = declaration.status.modality == Modality.SEALED,
                        )
                    if (declaration.hasAnnotation(DERIVE_ANNOTATION_CLASS_ID, session)) {
                        derivableClassIds += classId.asString()
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
                        associatedRulesByOwner.getOrPut(nextAssociatedOwner, ::mutableListOf) += rule
                    }
                }

                declaration.declarations.forEach { nestedDeclaration ->
                    scanDeclaration(nestedDeclaration, associatedOwner = nextAssociatedOwner)
                }
            }

            is FirSimpleFunction -> {
                declaration.toFunctionRules(session, configuration).forEach { rule ->
                    if (associatedOwner == null) {
                        topLevelRules += rule
                    } else {
                        associatedRulesByOwner.getOrPut(associatedOwner, ::mutableListOf) += rule
                    }
                }
            }

            is FirProperty -> {
                declaration.toPropertyRules(session, configuration).forEach { rule ->
                    if (associatedOwner == null) {
                        topLevelRules += rule
                    } else {
                        associatedRulesByOwner.getOrPut(associatedOwner, ::mutableListOf) += rule
                    }
                }
            }

            else -> Unit
        }
    }

    fun build(): ResolutionIndex =
        ResolutionIndex(
            topLevelRules = topLevelRules.toList() + configuration.builtinRules(),
            associatedRulesByOwner = associatedRulesByOwner.mapValues { (_, rules) -> rules.toList() },
            classInfoById = classInfoById.toMap(),
            derivableClassIds = derivableClassIds.toSet(),
        )
}

private data class ResolutionIndex(
    val topLevelRules: List<InstanceRule>,
    val associatedRulesByOwner: Map<ClassId, List<InstanceRule>>,
    val classInfoById: Map<String, ResolutionClassHierarchyInfo>,
    val derivableClassIds: Set<String>,
) {
    fun rulesForGoal(goal: TcType): List<InstanceRule> {
        val owners = allowedAssociatedOwnersForGoal(goal)
        val associated =
            owners.flatMapTo(linkedSetOf()) { owner ->
                associatedRulesByOwner[owner].orEmpty()
            }
        return (topLevelRules + associated).distinctBy(InstanceRule::id)
    }

    fun canDeriveGoal(goal: TcType): Boolean {
        val constructor = goal as? TcType.Constructor ?: return false
        val targetType = constructor.arguments.singleOrNull() as? TcType.Constructor ?: return false
        return derivationOwnersForTarget(targetType.classifierId).isNotEmpty()
    }

    fun allowedAssociatedOwnersForGoal(goal: TcType): Set<ClassId> {
        val constructor = goal as? TcType.Constructor ?: return emptySet()
        val ownerIds = linkedSetOf<String>()
        ownerIds += sealedOwnerChain(constructor.classifierId)
        constructor.arguments.forEach { argument ->
            ownerIds += associatedOwnerIds(argument)
        }
        return ownerIds.map(ClassId::fromString).toSet()
    }

    private fun associatedOwnerIds(type: TcType): Set<String> =
        when (type) {
            is TcType.Variable -> emptySet()

            is TcType.Constructor -> {
                val owners = linkedSetOf<String>()
                owners += sealedOwnerChain(type.classifierId)
                type.arguments.forEach { argument ->
                    owners += associatedOwnerIds(argument)
                }
                owners
            }
        }

    private fun derivationOwnersForTarget(classifierId: String): Set<String> =
        sealedOwnerChain(classifierId).filterTo(linkedSetOf()) { candidate ->
            candidate in derivableClassIds
        }

    private fun sealedOwnerChain(classifierId: String): Set<String> {
        val result = linkedSetOf(classifierId)
        val queue = ArrayDeque<String>()
        queue += classifierId
        val visited = linkedSetOf<String>()
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) {
                continue
            }
            classInfoById[current]?.superClassifiers.orEmpty().forEach { superClassifier ->
                if (classInfoById[superClassifier]?.isSealed == true) {
                    result += superClassifier
                }
                queue += superClassifier
            }
        }
        return result
    }
}

private data class ResolutionClassHierarchyInfo(
    val superClassifiers: Set<String>,
    val isSealed: Boolean,
)

private fun TypeclassConfiguration.builtinRules(): List<InstanceRule> =
    buildList {
        if (builtinKClassTypeclass == TypeclassBuiltinMode.ENABLED) {
            add(builtinKClassRule())
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

internal data class ProvidedTypeExpansion(
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

internal fun FirSimpleFunction.instanceProvidedType(
    session: FirSession,
    configuration: TypeclassConfiguration = TypeclassConfiguration(),
): TcType? {
    return instanceProvidedTypes(session, configuration).validTypes.firstOrNull()
}

internal fun FirSimpleFunction.instanceProvidedTypes(
    session: FirSession,
    configuration: TypeclassConfiguration = TypeclassConfiguration(),
): ProvidedTypeExpansion {
    val returnType = returnTypeRef.coneType
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
    val returnType = returnTypeRef.coneType
    return listOf(returnType).expandProvidedTypes(
        session = session,
        typeParameterBySymbol = emptyMap(),
        configuration = configuration,
    )
}

private fun FirRegularClass.declaredOrResolvedSuperTypes(): List<ConeKotlinType> {
    val declared = superTypeRefs.map { it.coneType }
    if (declared.isNotEmpty()) {
        return declared
    }
    return symbol.resolvedSuperTypes
}

private fun FirRegularClass.toObjectRules(
    session: FirSession,
    configuration: TypeclassConfiguration,
): List<InstanceRule> {
    if (classKind != ClassKind.OBJECT || !hasAnnotation(INSTANCE_ANNOTATION_CLASS_ID, session)) {
        return emptyList()
    }
    if (status.visibility == Visibilities.Private) {
        return emptyList()
    }

    return instanceProvidedTypes(session, configuration).validTypes.map { providedType ->
        InstanceRule(
            id = "fir-object:${symbol.classId.asString()}:${providedType.render()}",
            typeParameters = emptyList(),
            providedType = providedType,
            prerequisiteTypes = emptyList(),
        )
    }
}

private fun FirSimpleFunction.toFunctionRules(
    session: FirSession,
    configuration: TypeclassConfiguration,
): List<InstanceRule> {
    if (!hasAnnotation(INSTANCE_ANNOTATION_CLASS_ID, session)) {
        return emptyList()
    }
    if (status.visibility == Visibilities.Private) {
        return emptyList()
    }
    if (receiverParameter != null || valueParameters.isNotEmpty()) {
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

    return instanceProvidedTypes(session, configuration).validTypes.map { providedType ->
        InstanceRule(
            id = "fir-function:${symbol.callableId}:${providedType.render()}",
            typeParameters = typeParameters,
            providedType = providedType,
            prerequisiteTypes = prerequisites,
        )
    }
}

private fun FirProperty.toPropertyRules(
    session: FirSession,
    configuration: TypeclassConfiguration,
): List<InstanceRule> {
    if (!hasAnnotation(INSTANCE_ANNOTATION_CLASS_ID, session)) {
        return emptyList()
    }
    if (status.visibility == Visibilities.Private) {
        return emptyList()
    }
    if (receiverParameter != null) {
        return emptyList()
    }

    return instanceProvidedTypes(session, configuration).validTypes.map { providedType ->
        InstanceRule(
            id = "fir-property:${symbol.callableId}:${providedType.render()}",
            typeParameters = emptyList(),
            providedType = providedType,
            prerequisiteTypes = emptyList(),
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

internal fun FirClassSymbol<*>.sourceDeclaredFunctions(): List<FirNamedFunctionSymbol> =
    fir.declarations
        .filterIsInstance<FirSimpleFunction>()
        .map(FirSimpleFunction::symbol)

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
                    val nested = argument.type ?: return null
                    coneTypeToModel(nested, typeParameterBySymbol) ?: return null
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
    val validTypes = linkedMapOf<String, TcType>()
    val invalidTypes = linkedMapOf<String, TcType>()
    for (type in this) {
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
        validTypes = validTypes.values.toList(),
        invalidTypes = invalidTypes.values.toList(),
    )
}

private fun ConeKotlinType.expandProvidedTypes(
    session: FirSession,
    typeParameterBySymbol: Map<FirTypeParameterSymbol, TcTypeParameter>,
    configuration: TypeclassConfiguration,
    previousWereTypeclass: Boolean,
    visited: Set<String>,
): ProvidedTypeExpansion {
    val lowered = approximateIntegerLiteralType().lowerBoundIfFlexible() as? ConeClassLikeType
        ?: return ProvidedTypeExpansion(emptyList(), emptyList())
    val currentType = coneTypeToModel(lowered, typeParameterBySymbol)
        ?: return ProvidedTypeExpansion(emptyList(), emptyList())
    val visitKey = currentType.render()
    if (visitKey in visited) {
        return ProvidedTypeExpansion(emptyList(), emptyList())
    }

    val classId = lowered.lookupTag.classId
    if (classId.isLocal) {
        return ProvidedTypeExpansion(emptyList(), emptyList())
    }
    val classSymbol =
        try {
            session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol
        } catch (_: IllegalArgumentException) {
            null
        } ?: return ProvidedTypeExpansion(emptyList(), emptyList())

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
        validTypes = validTypes.values.toList(),
        invalidTypes = invalidTypes.values.toList(),
    )
}
