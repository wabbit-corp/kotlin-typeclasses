package one.wabbit.typeclass.plugin

import one.wabbit.typeclass.plugin.model.ResolutionSearchResult
import one.wabbit.typeclass.plugin.model.ResolutionTrace
import one.wabbit.typeclass.plugin.model.ResolutionTraceCandidate
import one.wabbit.typeclass.plugin.model.ResolutionTraceCandidateFamily
import one.wabbit.typeclass.plugin.model.ResolutionTraceCandidateState
import one.wabbit.typeclass.plugin.model.ResolutionTraceRulesState
import one.wabbit.typeclass.plugin.model.render
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.render as renderIrType
import java.nio.file.Path
import kotlin.io.path.name

internal enum class TypeclassTraceRootKind {
    RESOLUTION,
    DERIVATION,
}

internal data class TypeclassTraceScope(
    val mode: TypeclassTraceMode,
    val kind: String,
    val label: String,
)

internal data class TypeclassTraceActivation(
    val mode: TypeclassTraceMode,
    val scope: TypeclassTraceScope,
)

internal fun resolveTraceActivation(
    scopes: List<TypeclassTraceScope>,
    globalMode: TypeclassTraceMode,
): TypeclassTraceActivation? {
    val nearestExplicit = scopes.asReversed().firstOrNull { scope -> scope.mode != TypeclassTraceMode.INHERIT }
    return when {
        nearestExplicit?.mode == TypeclassTraceMode.DISABLED -> null
        nearestExplicit != null ->
            TypeclassTraceActivation(
                mode = nearestExplicit.mode,
                scope = nearestExplicit,
            )

        globalMode == TypeclassTraceMode.INHERIT || globalMode == TypeclassTraceMode.DISABLED -> null
        else ->
            TypeclassTraceActivation(
                mode = globalMode,
                scope =
                    TypeclassTraceScope(
                        mode = globalMode,
                        kind = "global",
                        label = "compiler option",
                    ),
            )
    }
}

internal fun renderResolutionTrace(
    trace: ResolutionTrace,
    activation: TypeclassTraceActivation,
    location: CompilerMessageSourceLocation?,
    localContextLabels: List<String>,
): String =
    buildTraceBlock(
        header = "Typeclass resolution trace for ${trace.goal.render().renderTraceType()}",
        rootKind = TypeclassTraceRootKind.RESOLUTION,
        activation = activation,
        location = location,
        body = renderResolutionTraceBody(trace, localContextLabels, indent = ""),
    )

internal fun renderDerivationTrace(
    goal: String,
    activation: TypeclassTraceActivation,
    location: CompilerMessageSourceLocation?,
    lines: List<String>,
): String =
    buildTraceBlock(
        header = "Typeclass derivation trace for ${goal.renderTraceType()}",
        rootKind = TypeclassTraceRootKind.DERIVATION,
        activation = activation,
        location = location,
        body = lines,
    )

private fun buildTraceBlock(
    header: String,
    rootKind: TypeclassTraceRootKind,
    activation: TypeclassTraceActivation,
    location: CompilerMessageSourceLocation?,
    body: List<String>,
): String =
    buildString {
        appendLine("[TC_TRACE] $header")
        appendLine("[TC_TRACE] root kind: ${rootKind.name.lowercase()}")
        location?.let { traceLocation ->
            appendLine("[TC_TRACE] root site: ${traceLocation.renderSite()}")
        }
        appendLine("[TC_TRACE] effective mode: ${activation.mode.name}")
        appendLine("[TC_TRACE] traced scope: ${activation.scope.kind} ${activation.scope.label}")
        body.forEach { line ->
            appendLine("[TC_TRACE] $line")
        }
    }.trimEnd()

private fun renderResolutionTraceBody(
    trace: ResolutionTrace,
    localContextLabels: List<String>,
    indent: String,
): List<String> {
    val lines = mutableListOf<String>()
    lines += "${indent}local contexts:"
    lines += renderCandidateGroup(
        candidates = trace.localContextCandidates,
        localContextLabels = localContextLabels,
        indent = indent,
    )
    lines += "${indent}explicit @Instance rules:"
    lines += renderCandidateFamily(trace, ResolutionTraceCandidateFamily.INSTANCE_RULE, indent)
    lines += "${indent}builtin rules:"
    lines += renderCandidateFamily(trace, ResolutionTraceCandidateFamily.BUILTIN_RULE, indent)
    lines += "${indent}derived rules:"
    lines += renderCandidateFamily(trace, ResolutionTraceCandidateFamily.DERIVED_RULE, indent)
    lines += "${indent}result: ${traceResultLabel(trace.result)}"
    return lines
}

private fun renderCandidateFamily(
    trace: ResolutionTrace,
    family: ResolutionTraceCandidateFamily,
    indent: String,
): List<String> {
    val candidates = trace.ruleCandidates.filter { candidate -> candidate.family == family }
    if (candidates.isEmpty()) {
        return when (trace.rulesState) {
            ResolutionTraceRulesState.SEARCHED -> listOf("${indent}- none")
            ResolutionTraceRulesState.NOT_EXPLORED_AFTER_LOCAL_CONTEXT_RESULT ->
                listOf("${indent}- not explored after local-context result")
        }
    }
    return renderCandidateGroup(candidates, emptyList(), indent)
}

private fun renderCandidateGroup(
    candidates: List<ResolutionTraceCandidate>,
    localContextLabels: List<String>,
    indent: String,
): List<String> {
    if (candidates.isEmpty()) {
        return listOf("${indent}- none")
    }
    return candidates
        .sortedWith(compareBy(ResolutionTraceCandidate::family, ResolutionTraceCandidate::identity))
        .flatMap { candidate ->
            val line =
                buildString {
                    append(indent)
                    append("- ")
                    append(candidateDisplayName(candidate, localContextLabels))
                    append(": ")
                    append(candidateStateLabel(candidate.state))
                    candidate.reason?.let { reason ->
                        append("; ")
                        append(reason)
                    }
                }
            listOf(line) +
                candidate.prerequisiteTraces.flatMap { nested ->
                    renderResolutionTraceBody(
                        trace = nested,
                        localContextLabels = localContextLabels,
                        indent = "$indent  ",
                    )
                }
        }
}

private fun candidateDisplayName(
    candidate: ResolutionTraceCandidate,
    localContextLabels: List<String>,
): String =
    when (candidate.family) {
        ResolutionTraceCandidateFamily.LOCAL_CONTEXT -> {
            val index =
                candidate.identity.substringAfter("local-context[").substringBefore(']').toIntOrNull()
            localContextLabels.getOrNull(index ?: -1) ?: candidate.identity
        }

        else -> renderRuleIdentity(candidate.identity)
    }

private fun candidateStateLabel(state: ResolutionTraceCandidateState): String =
    when (state) {
        ResolutionTraceCandidateState.SELECTED -> "selected"
        ResolutionTraceCandidateState.MATCHED -> "matched"
        ResolutionTraceCandidateState.REJECTED -> "rejected"
        ResolutionTraceCandidateState.EXPLAINED_NOT_SEARCHED -> "explained but not searched"
    }

private fun traceResultLabel(result: ResolutionSearchResult): String =
    when (result) {
        is ResolutionSearchResult.Success -> "success"
        is ResolutionSearchResult.Missing -> "failure"
        is ResolutionSearchResult.Ambiguous -> "ambiguity"
        is ResolutionSearchResult.Recursive -> "recursive failure"
    }

private fun renderRuleIdentity(identity: String): String =
    when {
        identity.startsWith("direct:") ->
            identity.substringAfter("direct:").substringBefore(":provided=").replace('/', '.')

        identity.startsWith("builtin:") ->
            identity.substringBefore(":provided=").replace(':', ' ')

        else -> identity.substringBefore(":provided=").replace('/', '.')
    }

private fun String.renderTraceType(): String = replace('/', '.')

private fun CompilerMessageSourceLocation.renderSite(): String {
    val fileName =
        runCatching { Path.of(path).name }
            .getOrElse { path.substringAfterLast('/') }
    return "$fileName:$line:$column"
}

internal fun DeriveViaPathSegment.traceRender(): String =
    when (this) {
        is DeriveViaPathSegment.Waypoint -> "waypoint ${classId.asString().renderTraceType()}"
        is DeriveViaPathSegment.PinnedIso -> "pinned ${classId.asString().renderTraceType()}"
    }

internal fun TransportPlan.traceRender(): String =
    when (this) {
        is TransportPlan.Identity -> "identity ${sourceType.renderIrType()} -> ${targetType.renderIrType()}"
        is TransportPlan.Composite -> steps.joinToString(" -> ") { step -> step.traceRender() }
        is TransportPlan.Nullable -> "nullable(${inner.traceRender()})"
        is TransportPlan.ValueUnwrap ->
            "unwrap ${sourceType.renderIrType()} -> ${targetType.renderIrType()}" +
                if (nested is TransportPlan.Identity) "" else " -> ${nested.traceRender()}"

        is TransportPlan.ValueWrap ->
            "wrap ${sourceType.renderIrType()} -> ${targetType.renderIrType()}" +
                if (nested is TransportPlan.Identity) "" else " -> ${nested.traceRender()}"

        is TransportPlan.Product -> "product ${sourceType.renderIrType()} -> ${targetType.renderIrType()}"
        is TransportPlan.Sum -> "sum ${sourceType.renderIrType()} -> ${targetType.renderIrType()}"
        is TransportPlan.Function -> "function ${sourceType.renderIrType()} -> ${targetType.renderIrType()}"
        is TransportPlan.PinnedIso ->
            "pinned-iso ${isoObject.classIdOrFail.asString().renderTraceType()} ${sourceType.renderIrType()} -> ${targetType.renderIrType()}"
    }
