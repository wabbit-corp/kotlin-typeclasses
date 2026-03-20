package one.wabbit.typeclass.plugin.model

internal class TypeclassResolutionPlanner(
    private val ruleProvider: (TcType) -> List<InstanceRule>,
    private val bindableDesiredVariableIds: Set<String> = emptySet(),
) {
    constructor(rules: List<InstanceRule>) : this(
        ruleProvider = { _: TcType -> rules },
        bindableDesiredVariableIds = emptySet(),
    )

    constructor(
        rules: List<InstanceRule>,
        bindableDesiredVariableIds: Set<String>,
    ) : this(
        ruleProvider = { _: TcType -> rules },
        bindableDesiredVariableIds = bindableDesiredVariableIds,
    )

    fun resolve(
        desiredType: TcType,
        localContextTypes: List<TcType>,
    ): ResolutionSearchResult =
        resolveInternal(
            desiredType = desiredType,
            localContextTypes = localContextTypes,
            inProgress = linkedSetOf(),
            allowedRecursiveGoals = linkedSetOf(),
            freshCounter = 0,
        ).result

    private fun resolveInternal(
        desiredType: TcType,
        localContextTypes: List<TcType>,
        inProgress: LinkedHashSet<String>,
        allowedRecursiveGoals: LinkedHashSet<String>,
        freshCounter: Int,
    ): InternalResolution {
        val normalizedDesired = desiredType.normalizedKey()
        if (!inProgress.add(normalizedDesired)) {
            val recursiveResult =
                if (normalizedDesired in allowedRecursiveGoals) {
                    ResolutionSearchResult.Success(ResolutionPlan.RecursiveReference(desiredType))
                } else {
                    ResolutionSearchResult.Recursive(desiredType)
                }
            return InternalResolution(recursiveResult, freshCounter)
        }

        try {
            val localMatches =
                localContextTypes.mapIndexedNotNull { index, localType ->
                    if (desiredType != localType) {
                        return@mapIndexedNotNull null
                    }
                    ResolutionPlan.LocalContext(index = index, providedType = desiredType)
                }

            if (localMatches.isNotEmpty()) {
                return InternalResolution(localMatches.toSearchResult(desiredType), freshCounter)
            }

            val successfulCandidates = mutableListOf<SuccessfulCandidate>()
            var sawRecursiveCandidate = false
            var nextFreshCounter = freshCounter

            ruleProvider(desiredType).forEach { rule ->
                val instantiatedRule = instantiateRule(rule, nextFreshCounter)
                nextFreshCounter = instantiatedRule.nextFreshCounter

                val substitution =
                    Unifier(
                        bindableVariableIds =
                            instantiatedRule.rule.typeParameters.mapTo(linkedSetOf(), TcTypeParameter::id).apply {
                                addAll(bindableDesiredVariableIds)
                            },
                    ).unify(
                        desiredType,
                        instantiatedRule.rule.providedType,
                    ) ?: return@forEach

                val appliedRule = instantiatedRule.rule.apply(substitution)
                val appliedLocals = localContextTypes.map { it.substitute(substitution) }

                val prerequisitePlans = mutableListOf<ResolutionPlan>()
                var candidateFreshCounter = nextFreshCounter
                val recursiveGoalKey = appliedRule.providedType.normalizedKey()
                val addedRecursiveGoal =
                    appliedRule.supportsRecursiveResolution && allowedRecursiveGoals.add(recursiveGoalKey)

                try {
                    for (prerequisiteType in appliedRule.prerequisiteTypes) {
                        val nested =
                            resolveInternal(
                                desiredType = prerequisiteType,
                                localContextTypes = appliedLocals,
                                inProgress = inProgress,
                                allowedRecursiveGoals = allowedRecursiveGoals,
                                freshCounter = candidateFreshCounter,
                            )
                        candidateFreshCounter = nested.freshCounter
                        when (val nestedResult = nested.result) {
                            is ResolutionSearchResult.Success -> prerequisitePlans += nestedResult.plan
                            is ResolutionSearchResult.Recursive -> {
                                sawRecursiveCandidate = true
                                return@forEach
                            }

                            else -> return@forEach
                        }
                    }
                } finally {
                    if (addedRecursiveGoal) {
                        allowedRecursiveGoals.remove(recursiveGoalKey)
                    }
                }

                nextFreshCounter = candidateFreshCounter
                successfulCandidates +=
                    SuccessfulCandidate(
                        priority = appliedRule.priority,
                        ruleId = appliedRule.id,
                        plan =
                            ResolutionPlan.ApplyRule(
                                ruleId = appliedRule.id,
                                providedType = appliedRule.providedType,
                                appliedTypeArguments =
                                    instantiatedRule.rule.typeParameters.map { parameter ->
                                        TcType.Variable(parameter.id, parameter.displayName).substitute(substitution)
                                    },
                                prerequisitePlans = prerequisitePlans,
                            ),
                    )
            }

            val result =
                when {
                    successfulCandidates.isNotEmpty() -> successfulCandidates.toCandidateSearchResult(desiredType)
                    sawRecursiveCandidate -> ResolutionSearchResult.Recursive(desiredType)
                    else -> ResolutionSearchResult.Missing(desiredType)
                }
            return InternalResolution(result, nextFreshCounter)
        } finally {
            inProgress.remove(normalizedDesired)
        }
    }

    private fun instantiateRule(
        rule: InstanceRule,
        freshCounter: Int,
    ): InstantiatedRule {
        val suffix = "__$freshCounter"
        val freshParameters =
            rule.typeParameters.map { parameter ->
                TcTypeParameter(
                    id = parameter.id + suffix,
                    displayName = parameter.displayName,
                )
            }
        val substitution =
            Substitution(
                rule.typeParameters.zip(freshParameters)
                    .associate { (original, fresh) ->
                        original.id to TcType.Variable(fresh.id, fresh.displayName)
                    },
            )
        return InstantiatedRule(
            rule =
                InstanceRule(
                    id = rule.id,
                    typeParameters = freshParameters,
                    providedType = rule.providedType.substitute(substitution),
                    prerequisiteTypes = rule.prerequisiteTypes.map { it.substitute(substitution) },
                    supportsRecursiveResolution = rule.supportsRecursiveResolution,
                    priority = rule.priority,
                ),
            nextFreshCounter = freshCounter + 1,
        )
    }
}

private data class SuccessfulCandidate(
    val priority: Int,
    val ruleId: String,
    val plan: ResolutionPlan,
)

private fun List<ResolutionPlan>.toSearchResult(desiredType: TcType): ResolutionSearchResult =
    when (size) {
        0 -> ResolutionSearchResult.Missing(desiredType)
        1 -> ResolutionSearchResult.Success(single())
        else -> ResolutionSearchResult.Ambiguous(desiredType, this)
    }

internal sealed interface ResolutionSearchResult {
    data class Success(
        val plan: ResolutionPlan,
    ) : ResolutionSearchResult

    data class Missing(
        val desiredType: TcType,
    ) : ResolutionSearchResult

    data class Ambiguous(
        val desiredType: TcType,
        val matchingPlans: List<ResolutionPlan>,
    ) : ResolutionSearchResult

    data class Recursive(
        val desiredType: TcType,
    ) : ResolutionSearchResult
}

private data class InternalResolution(
    val result: ResolutionSearchResult,
    val freshCounter: Int,
)

private data class InstantiatedRule(
    val rule: InstanceRule,
    val nextFreshCounter: Int,
)

private fun List<SuccessfulCandidate>.toCandidateSearchResult(desiredType: TcType): ResolutionSearchResult =
    when (size) {
        0 -> ResolutionSearchResult.Missing(desiredType)
        1 -> ResolutionSearchResult.Success(single().plan)
        else -> {
            if (any { candidate -> !candidate.ruleId.startsWith("derived:") }) {
                return ResolutionSearchResult.Ambiguous(desiredType, map(SuccessfulCandidate::plan))
            }
            val maxPriority = maxOf(SuccessfulCandidate::priority)
            val highestPriority = filter { candidate -> candidate.priority == maxPriority }
            when (highestPriority.size) {
                1 -> ResolutionSearchResult.Success(highestPriority.single().plan)
                else -> ResolutionSearchResult.Ambiguous(desiredType, highestPriority.map(SuccessfulCandidate::plan))
            }
        }
    }

private data class Substitution(
    val bindings: Map<String, TcType>,
) {
    fun plus(
        variableId: String,
        value: TcType,
    ): Substitution = Substitution(bindings + (variableId to value))
}

private class Unifier {
    constructor() : this(bindableVariableIds = null)

    constructor(bindableVariableIds: Set<String>?) {
        this.bindableVariableIds = bindableVariableIds
    }

    private var substitution = Substitution(emptyMap())
    private val bindableVariableIds: Set<String>?

    fun unify(
        left: TcType,
        right: TcType,
    ): Substitution? {
        if (!unifyInternal(left.substitute(substitution), right.substitute(substitution))) {
            return null
        }
        return substitution
    }

    private fun unifyInternal(
        left: TcType,
        right: TcType,
    ): Boolean {
        if (left == right) {
            return true
        }

        return when {
            left === TcType.StarProjection && right === TcType.StarProjection -> true
            left is TcType.Projected && right is TcType.Projected ->
                left.variance == right.variance &&
                    unifyInternal(
                        left.type.substitute(substitution),
                        right.type.substitute(substitution),
                    )

            left is TcType.Variable && right is TcType.Variable -> {
                when {
                    isBindable(left.id) && bindVariable(left, right) -> true
                    isBindable(right.id) && bindVariable(right, left) -> true
                    else -> false
                }
            }

            left is TcType.Variable && isBindable(left.id) -> bindVariable(left, right)
            right is TcType.Variable && isBindable(right.id) -> bindVariable(right, left)
            left is TcType.Constructor && right is TcType.Constructor ->
                left.classifierId == right.classifierId &&
                    left.isNullable == right.isNullable &&
                    left.arguments.size == right.arguments.size &&
                    left.arguments.zip(right.arguments).all { (leftArgument, rightArgument) ->
                        unifyInternal(
                            leftArgument.substitute(substitution),
                            rightArgument.substitute(substitution),
                        )
                    }

            else -> false
        }
    }

    private fun isBindable(variableId: String): Boolean =
        bindableVariableIds == null || variableId in bindableVariableIds

    private fun bindVariable(
        variable: TcType.Variable,
        value: TcType,
    ): Boolean {
        val normalizedValue =
            if (variable.isNullable) {
                value.stripOuterNullability() ?: return false
            } else {
                value
            }
        return bind(variable.id, normalizedValue)
    }

    private fun bind(
        variableId: String,
        value: TcType,
    ): Boolean {
        val existing = substitution.bindings[variableId]
        if (existing != null) {
            return unifyInternal(existing, value)
        }
        if (!isBindable(variableId)) {
            return false
        }
        val normalizedValue = value.substitute(substitution)
        if (normalizedValue.references(variableId)) {
            return false
        }
        substitution = substitution.plus(variableId, normalizedValue)
        return true
    }
}

private fun InstanceRule.apply(substitution: Substitution): InstanceRule =
    copy(
        providedType = providedType.substitute(substitution),
        prerequisiteTypes = prerequisiteTypes.map { it.substitute(substitution) },
    )

internal fun unifyTypes(
    left: TcType,
    right: TcType,
    bindableVariableIds: Set<String>? = null,
): Map<String, TcType>? = Unifier(bindableVariableIds).unify(left, right)?.bindings

internal fun TcType.substituteType(bindings: Map<String, TcType>): TcType = substitute(Substitution(bindings))

private fun TcType.substitute(substitution: Substitution): TcType =
    substitute(substitution, linkedSetOf())

private fun TcType.substitute(
    substitution: Substitution,
    visiting: MutableSet<String>,
): TcType =
    when (this) {
        TcType.StarProjection -> TcType.StarProjection
        is TcType.Projected -> TcType.Projected(variance, type.substitute(substitution, visiting))
        is TcType.Constructor -> TcType.Constructor(classifierId, arguments.map { it.substitute(substitution, visiting) }, isNullable)

        is TcType.Variable -> {
            if (!visiting.add(id)) {
                return this
            }
            try {
                val substituted = substitution.bindings[id]?.substitute(substitution, visiting) ?: this
                if (isNullable) {
                    substituted.makeNullable()
                } else {
                    substituted
                }
            } finally {
                visiting.remove(id)
            }
        }
    }

private fun TcType.stripOuterNullability(): TcType? =
    when (this) {
        TcType.StarProjection -> null
        is TcType.Projected -> null
        is TcType.Constructor -> if (isNullable) copy(isNullable = false) else null
        is TcType.Variable -> if (isNullable) copy(isNullable = false) else null
    }

private fun TcType.makeNullable(): TcType =
    when (this) {
        TcType.StarProjection -> this
        is TcType.Projected -> this
        is TcType.Constructor -> if (isNullable) this else copy(isNullable = true)
        is TcType.Variable -> if (isNullable) this else copy(isNullable = true)
    }
