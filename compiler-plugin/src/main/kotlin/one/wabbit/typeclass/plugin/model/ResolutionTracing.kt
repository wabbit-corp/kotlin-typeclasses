package one.wabbit.typeclass.plugin.model

internal data class TracedResolutionSearchResult(
    val result: ResolutionSearchResult,
    val trace: ResolutionTrace,
)

internal data class ResolutionTrace(
    val goal: TcType,
    val localContextCandidates: List<ResolutionTraceCandidate>,
    val ruleCandidates: List<ResolutionTraceCandidate>,
    val rulesState: ResolutionTraceRulesState,
    val result: ResolutionSearchResult,
)

internal data class ResolutionTraceCandidate(
    val identity: String,
    val family: ResolutionTraceCandidateFamily,
    val state: ResolutionTraceCandidateState,
    val providedType: TcType,
    val reason: String? = null,
    val prerequisiteTraces: List<ResolutionTrace> = emptyList(),
)

internal enum class ResolutionTraceRulesState {
    SEARCHED,
    NOT_EXPLORED_AFTER_LOCAL_CONTEXT_RESULT,
}

internal enum class ResolutionTraceCandidateFamily {
    LOCAL_CONTEXT,
    INSTANCE_RULE,
    BUILTIN_RULE,
    DERIVED_RULE,
}

internal enum class ResolutionTraceCandidateState {
    SELECTED,
    MATCHED,
    REJECTED,
    EXPLAINED_NOT_SEARCHED,
}

internal fun resolutionTraceCandidateFamily(ruleId: String): ResolutionTraceCandidateFamily =
    when {
        ruleId.startsWith("builtin:") -> ResolutionTraceCandidateFamily.BUILTIN_RULE
        ruleId.startsWith("derived:") ||
            ruleId.startsWith("derived-via:") ||
            ruleId.startsWith("derived-equiv:") ->
            ResolutionTraceCandidateFamily.DERIVED_RULE

        else -> ResolutionTraceCandidateFamily.INSTANCE_RULE
    }
