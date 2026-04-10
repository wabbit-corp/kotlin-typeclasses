// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass.plugin.model

internal class TypeclassResolutionPlanner(
    private val ruleProvider: (TcType) -> List<InstanceRule>,
    private val bindableDesiredVariableIds: Set<String> = emptySet(),
) {
    constructor(
        rules: List<InstanceRule>
    ) : this(ruleProvider = { _: TcType -> rules }, bindableDesiredVariableIds = emptySet())

    constructor(
        rules: List<InstanceRule>,
        bindableDesiredVariableIds: Set<String>,
    ) : this(
        ruleProvider = { _: TcType -> rules },
        bindableDesiredVariableIds = bindableDesiredVariableIds,
    )

    fun resolve(desiredType: TcType, localContextTypes: List<TcType>): ResolutionSearchResult =
        resolveInternal(
                desiredType = desiredType,
                localContextTypes = localContextTypes,
                inProgress = linkedSetOf(),
                allowedRecursiveGoals = linkedSetOf(),
                freshCounter = 0,
                traceOptions = null,
            )
            .result

    fun resolveWithTrace(
        desiredType: TcType,
        localContextTypes: List<TcType>,
        explainAlternatives: Boolean = false,
    ): TracedResolutionSearchResult {
        val internal =
            resolveInternal(
                desiredType = desiredType,
                localContextTypes = localContextTypes,
                inProgress = linkedSetOf(),
                allowedRecursiveGoals = linkedSetOf(),
                freshCounter = 0,
                traceOptions = TraceOptions(explainAlternatives = explainAlternatives),
            )
        return TracedResolutionSearchResult(
            result = internal.result,
            trace = requireNotNull(internal.trace) { "Tracing requested but no trace was produced" },
        )
    }

    private fun resolveInternal(
        desiredType: TcType,
        localContextTypes: List<TcType>,
        inProgress: LinkedHashSet<String>,
        allowedRecursiveGoals: LinkedHashSet<String>,
        freshCounter: Int,
        traceOptions: TraceOptions?,
    ): InternalResolution {
        val normalizedDesired = desiredType.normalizedKey()
        if (!inProgress.add(normalizedDesired)) {
            val recursiveResult =
                if (normalizedDesired in allowedRecursiveGoals) {
                    ResolutionSearchResult.Success(ResolutionPlan.RecursiveReference(desiredType))
                } else {
                    ResolutionSearchResult.Recursive(desiredType)
                }
            return InternalResolution(
                result = recursiveResult,
                freshCounter = freshCounter,
                trace =
                    traceOptions?.let {
                        ResolutionTrace(
                            goal = desiredType,
                            localContextCandidates = emptyList(),
                            ruleCandidates = emptyList(),
                            rulesState = ResolutionTraceRulesState.SEARCHED,
                            result = recursiveResult,
                        )
                    },
            )
        }

        try {
            val matchingLocalIndexes = linkedSetOf<Int>()
            val localMatches =
                localContextTypes.mapIndexedNotNull { index, localType ->
                    val substitution =
                        Unifier(bindableDesiredVariableIds).unify(desiredType, localType)
                            ?: run {
                                return@mapIndexedNotNull null
                            }
                    matchingLocalIndexes += index
                    ResolutionPlan.LocalContext(
                        index = index,
                        providedType = desiredType.substitute(substitution),
                    )
                }

            if (localMatches.isNotEmpty()) {
                val localResult = localMatches.toSearchResult(desiredType)
                return InternalResolution(
                    result = localResult,
                    freshCounter = freshCounter,
                    trace =
                        traceOptions?.let { options ->
                            ResolutionTrace(
                                goal = desiredType,
                                localContextCandidates =
                                    localContextTypes.mapIndexed { index, localType ->
                                        ResolutionTraceCandidate(
                                            identity = "local-context[$index]",
                                            family = ResolutionTraceCandidateFamily.LOCAL_CONTEXT,
                                            state =
                                                when {
                                                    index !in matchingLocalIndexes ->
                                                        ResolutionTraceCandidateState.REJECTED
                                                    localMatches.size == 1 ->
                                                        ResolutionTraceCandidateState.SELECTED
                                                    else -> ResolutionTraceCandidateState.MATCHED
                                                },
                                            providedType = localType,
                                            reason =
                                                if (index in matchingLocalIndexes) {
                                                    null
                                                } else {
                                                    mismatchReason(desiredType, localType)
                                                },
                                        )
                                    },
                                ruleCandidates =
                                    if (options.explainAlternatives) {
                                        explainRuleAlternatives(desiredType, freshCounter)
                                    } else {
                                        emptyList()
                                    },
                                rulesState =
                                    if (options.explainAlternatives) {
                                        ResolutionTraceRulesState.SEARCHED
                                    } else {
                                        ResolutionTraceRulesState
                                            .NOT_EXPLORED_AFTER_LOCAL_CONTEXT_RESULT
                                    },
                                result = localResult,
                            )
                        },
                )
            }

            val successfulCandidates = mutableListOf<SuccessfulCandidate>()
            var sawApplicableCandidate = false
            var sawRecursiveCandidate = false
            var allApplicableCandidatesWereRecursive = true
            var nextFreshCounter = freshCounter
            val tracedRuleCandidates = mutableListOf<ResolutionTraceCandidate>()
            val tracedLocalCandidates =
                traceOptions
                    ?.let {
                        localContextTypes.mapIndexed { index, localType ->
                            ResolutionTraceCandidate(
                                identity = "local-context[$index]",
                                family = ResolutionTraceCandidateFamily.LOCAL_CONTEXT,
                                state = ResolutionTraceCandidateState.REJECTED,
                                providedType = localType,
                                reason = mismatchReason(desiredType, localType),
                            )
                        }
                    }
                    .orEmpty()

            ruleProvider(desiredType).forEach { rule ->
                val instantiatedRule = instantiateRule(rule, nextFreshCounter)
                nextFreshCounter = instantiatedRule.nextFreshCounter

                val substitution =
                    Unifier(
                            bindableVariableIds =
                                instantiatedRule.rule.typeParameters
                                    .mapTo(linkedSetOf(), TcTypeParameter::id)
                                    .apply { addAll(bindableDesiredVariableIds) }
                        )
                        .unify(desiredType, instantiatedRule.rule.providedType)
                if (substitution == null) {
                    if (traceOptions != null) {
                        tracedRuleCandidates +=
                            ResolutionTraceCandidate(
                                identity = instantiatedRule.rule.id,
                                family = resolutionTraceCandidateFamily(instantiatedRule.rule.id),
                                state = ResolutionTraceCandidateState.REJECTED,
                                providedType = instantiatedRule.rule.providedType,
                                reason =
                                    mismatchReason(desiredType, instantiatedRule.rule.providedType),
                            )
                    }
                    return@forEach
                }

                sawApplicableCandidate = true
                val appliedRule = instantiatedRule.rule.apply(substitution)
                val appliedLocals = localContextTypes.map { it.substitute(substitution) }

                val prerequisitePlans = mutableListOf<ResolutionPlan>()
                val prerequisiteTraces = mutableListOf<ResolutionTrace>()
                var candidateFreshCounter = nextFreshCounter
                val recursiveGoalKey = appliedRule.providedType.normalizedKey()
                val addedRecursiveGoal =
                    appliedRule.supportsRecursiveResolution &&
                        allowedRecursiveGoals.add(recursiveGoalKey)
                var rejectionReason: String? = null

                try {
                    for (prerequisiteType in appliedRule.prerequisiteTypes) {
                        val nested =
                            resolveInternal(
                                desiredType = prerequisiteType,
                                localContextTypes = appliedLocals,
                                inProgress = inProgress,
                                allowedRecursiveGoals = allowedRecursiveGoals,
                                freshCounter = candidateFreshCounter,
                                traceOptions = traceOptions,
                            )
                        candidateFreshCounter = nested.freshCounter
                        nested.trace?.let(prerequisiteTraces::add)
                        when (val nestedResult = nested.result) {
                            is ResolutionSearchResult.Success ->
                                prerequisitePlans += nestedResult.plan
                            is ResolutionSearchResult.Recursive -> {
                                sawRecursiveCandidate = true
                                rejectionReason =
                                    "recursive prerequisite ${nestedResult.desiredType.render()}"
                                return@forEach
                            }

                            is ResolutionSearchResult.Missing -> {
                                allApplicableCandidatesWereRecursive = false
                                rejectionReason =
                                    "missing prerequisite ${nestedResult.desiredType.render()}"
                                return@forEach
                            }

                            is ResolutionSearchResult.Ambiguous -> {
                                allApplicableCandidatesWereRecursive = false
                                rejectionReason =
                                    "ambiguous prerequisite ${nestedResult.desiredType.render()}"
                                return@forEach
                            }
                        }
                    }
                } finally {
                    if (addedRecursiveGoal) {
                        allowedRecursiveGoals.remove(recursiveGoalKey)
                    }
                }

                if (rejectionReason != null) {
                    if (traceOptions != null) {
                        tracedRuleCandidates +=
                            ResolutionTraceCandidate(
                                identity = appliedRule.id,
                                family = resolutionTraceCandidateFamily(appliedRule.id),
                                state = ResolutionTraceCandidateState.REJECTED,
                                providedType = appliedRule.providedType,
                                reason = rejectionReason,
                                prerequisiteTraces = prerequisiteTraces,
                            )
                    }
                    return@forEach
                }

                nextFreshCounter = candidateFreshCounter
                if (traceOptions != null) {
                    tracedRuleCandidates +=
                        ResolutionTraceCandidate(
                            identity = appliedRule.id,
                            family = resolutionTraceCandidateFamily(appliedRule.id),
                            state = ResolutionTraceCandidateState.MATCHED,
                            providedType = appliedRule.providedType,
                            prerequisiteTraces = prerequisiteTraces,
                        )
                }
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
                                        TcType.Variable(parameter.id, parameter.displayName)
                                            .substitute(substitution)
                                    },
                                prerequisitePlans = prerequisitePlans,
                            ),
                    )
            }

            val result =
                when {
                    successfulCandidates.isNotEmpty() ->
                        successfulCandidates.toCandidateSearchResult(desiredType)
                    sawApplicableCandidate &&
                        sawRecursiveCandidate &&
                        allApplicableCandidatesWereRecursive ->
                        ResolutionSearchResult.Recursive(desiredType)
                    else -> ResolutionSearchResult.Missing(desiredType)
                }
            val selectedLocalIndex =
                ((result as? ResolutionSearchResult.Success)?.plan as? ResolutionPlan.LocalContext)
                    ?.index
            val selectedRuleId =
                ((result as? ResolutionSearchResult.Success)?.plan as? ResolutionPlan.ApplyRule)
                    ?.ruleId
            return InternalResolution(
                result = result,
                freshCounter = nextFreshCounter,
                trace =
                    traceOptions?.let {
                        ResolutionTrace(
                            goal = desiredType,
                            localContextCandidates =
                                tracedLocalCandidates.map { candidate ->
                                    if (
                                        candidate.identity == "local-context[$selectedLocalIndex]"
                                    ) {
                                        candidate.copy(
                                            state = ResolutionTraceCandidateState.SELECTED,
                                            reason = null,
                                        )
                                    } else {
                                        candidate
                                    }
                                },
                            ruleCandidates =
                                tracedRuleCandidates.map { candidate ->
                                    if (candidate.identity == selectedRuleId) {
                                        candidate.copy(
                                            state = ResolutionTraceCandidateState.SELECTED
                                        )
                                    } else {
                                        candidate
                                    }
                                },
                            rulesState = ResolutionTraceRulesState.SEARCHED,
                            result = result,
                        )
                    },
            )
        } finally {
            inProgress.remove(normalizedDesired)
        }
    }

    private fun explainRuleAlternatives(
        desiredType: TcType,
        freshCounter: Int,
    ): List<ResolutionTraceCandidate> {
        var nextFreshCounter = freshCounter
        return ruleProvider(desiredType).map { rule ->
            val instantiatedRule = instantiateRule(rule, nextFreshCounter)
            nextFreshCounter = instantiatedRule.nextFreshCounter
            val substitution =
                Unifier(
                        bindableVariableIds =
                            instantiatedRule.rule.typeParameters
                                .mapTo(linkedSetOf(), TcTypeParameter::id)
                                .apply { addAll(bindableDesiredVariableIds) }
                    )
                    .unify(desiredType, instantiatedRule.rule.providedType)
            val appliedRule =
                substitution?.let(instantiatedRule.rule::apply) ?: instantiatedRule.rule
            ResolutionTraceCandidate(
                identity = appliedRule.id,
                family = resolutionTraceCandidateFamily(appliedRule.id),
                state = ResolutionTraceCandidateState.EXPLAINED_NOT_SEARCHED,
                providedType = appliedRule.providedType,
                reason =
                    if (substitution == null) {
                        mismatchReason(desiredType, appliedRule.providedType)
                    } else {
                        "head matches the requested goal; prerequisites not checked"
                    },
            )
        }
    }

    private fun instantiateRule(rule: InstanceRule, freshCounter: Int): InstantiatedRule {
        val suffix = "__$freshCounter"
        val freshParameters =
            rule.typeParameters.map { parameter ->
                TcTypeParameter(id = parameter.id + suffix, displayName = parameter.displayName)
            }
        val substitution =
            Substitution(
                rule.typeParameters.zip(freshParameters).associate { (original, fresh) ->
                    original.id to TcType.Variable(fresh.id, fresh.displayName)
                }
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
    data class Success(val plan: ResolutionPlan) : ResolutionSearchResult

    data class Missing(val desiredType: TcType) : ResolutionSearchResult

    data class Ambiguous(val desiredType: TcType, val matchingPlans: List<ResolutionPlan>) :
        ResolutionSearchResult

    data class Recursive(val desiredType: TcType) : ResolutionSearchResult
}

private data class InternalResolution(
    val result: ResolutionSearchResult,
    val freshCounter: Int,
    val trace: ResolutionTrace? = null,
)

private data class InstantiatedRule(val rule: InstanceRule, val nextFreshCounter: Int)

private data class TraceOptions(val explainAlternatives: Boolean)

private fun List<SuccessfulCandidate>.toCandidateSearchResult(
    desiredType: TcType
): ResolutionSearchResult =
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
                else ->
                    ResolutionSearchResult.Ambiguous(
                        desiredType,
                        highestPriority.map(SuccessfulCandidate::plan),
                    )
            }
        }
    }

private fun mismatchReason(
    @Suppress("UNUSED_PARAMETER") desiredType: TcType,
    @Suppress("UNUSED_PARAMETER") candidateType: TcType,
): String = "not applicable due to head mismatch"

private data class Substitution(val bindings: Map<String, TcType>) {
    fun plus(variableId: String, value: TcType): Substitution =
        Substitution(bindings + (variableId to value))
}

private class Unifier {
    constructor() : this(bindableVariableIds = null)

    constructor(bindableVariableIds: Set<String>?) {
        this.bindableVariableIds = bindableVariableIds
    }

    private var substitution = Substitution(emptyMap())
    private val bindableVariableIds: Set<String>?

    fun unify(left: TcType, right: TcType): Substitution? {
        if (!unifyInternal(left.substitute(substitution), right.substitute(substitution))) {
            return null
        }
        return substitution
    }

    private fun unifyInternal(left: TcType, right: TcType): Boolean {
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

    private fun bindVariable(variable: TcType.Variable, value: TcType): Boolean {
        val normalizedValue =
            if (variable.isNullable) {
                value.stripOuterNullability() ?: return false
            } else {
                value
            }
        return bind(variable.id, normalizedValue)
    }

    private fun bind(variableId: String, value: TcType): Boolean {
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

internal fun TcType.substituteType(bindings: Map<String, TcType>): TcType =
    substitute(Substitution(bindings))

private fun TcType.substitute(substitution: Substitution): TcType =
    substitute(substitution, linkedSetOf())

private fun TcType.substitute(substitution: Substitution, visiting: MutableSet<String>): TcType =
    when (this) {
        TcType.StarProjection -> TcType.StarProjection
        is TcType.Projected -> TcType.Projected(variance, type.substitute(substitution, visiting))
        is TcType.Constructor ->
            TcType.Constructor(
                classifierId,
                arguments.map { it.substitute(substitution, visiting) },
                isNullable,
            )

        is TcType.Variable -> {
            if (!visiting.add(id)) {
                return this
            }
            try {
                val substituted =
                    substitution.bindings[id]?.substitute(substitution, visiting) ?: this
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
