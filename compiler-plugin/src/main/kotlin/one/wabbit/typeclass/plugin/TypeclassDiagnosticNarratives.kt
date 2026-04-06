// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass.plugin

private val prefixedDiagnosticIdRegex = Regex("""^\[(TC_[A-Z_]+)]\s*(.*)$""")
private val noContextHeadlineRegex =
    Regex("""^No context argument for required instance (.+?)(?: while resolving (.+?))?\.$""")
private val ambiguousHeadlineRegex =
    Regex("""^Ambiguous typeclass instance for (.+?)(?: while resolving (.+?))?\.$""")
private val genericWhyFixRegex =
    Regex("""^(.*)\s+Why it failed:\s+(.*)\s+How to fix:\s+(.*)$""")
private val directRecursiveRegex = Regex("""^direct recursive instance rule for (.+) is not allowed\.$""")
private val topLevelOrphanRegex =
    Regex("""^top-level orphan instance declarations must be declared in the same file as one of: (.+)\.$""")
private val typeParameterPrereqRegex =
    Regex("""^instance type parameter (.+) must appear in the provided type, not only prerequisites\.$""")
private val constructiveMismatchRegex =
    Regex("""^Cannot derive (.+) because constructive product derivation requires constructor parameters to exactly match stored properties\.$""")
private val wrongDeriverReturnRegex =
    Regex("""^(deriveProduct|deriveSum|deriveEnum) must return ([^<]+)<\.\.\.>(?:; found (.+))?\.$""")
private val missingRequiredDeriverRegex =
    Regex("""^([^ ]+) companion must implement ([^;]+); (.+)\.$""")
private val missingEnumOverrideRegex =
    Regex("""^([^ ]+) companion must override deriveEnum to derive enum classes\.$""")

sealed interface TypeclassDiagnostic {
    val diagnosticId: String

    data class CannotDerive(val case: CannotDeriveCase) : TypeclassDiagnostic {
        override val diagnosticId: String = TypeclassDiagnosticIds.CANNOT_DERIVE
    }

    data class InvalidInstance(val case: InvalidInstanceCase) : TypeclassDiagnostic {
        override val diagnosticId: String = TypeclassDiagnosticIds.INVALID_INSTANCE_DECL
    }

    data class InvalidEquiv(val case: InvalidEquivCase) : TypeclassDiagnostic {
        override val diagnosticId: String = TypeclassDiagnosticIds.INVALID_EQUIV_DECL
    }

    data class InvalidBuiltinEvidence(val case: InvalidBuiltinEvidenceCase) : TypeclassDiagnostic {
        override val diagnosticId: String = TypeclassDiagnosticIds.INVALID_BUILTIN_EVIDENCE
    }

    data class NoContextArgument(
        val goal: String,
        val scope: String = "",
        val recursive: Boolean = false,
    ) : TypeclassDiagnostic {
        override val diagnosticId: String = TypeclassDiagnosticIds.NO_CONTEXT_ARGUMENT
    }

    data class AmbiguousInstance(
        val goal: String,
        val scope: String = "",
        val candidates: List<String>,
    ) : TypeclassDiagnostic {
        override val diagnosticId: String = TypeclassDiagnosticIds.AMBIGUOUS_INSTANCE

        init {
            require(candidates.isNotEmpty()) { "Ambiguous diagnostics must list at least one candidate" }
        }
    }

    sealed interface CannotDeriveCase {
        data class OnlyUnaryTypeclasses(val unit: Unit = Unit) : CannotDeriveCase
        data class UnsupportedShape(val unit: Unit = Unit) : CannotDeriveCase
        data class RequiresPrimaryConstructor(val unit: Unit = Unit) : CannotDeriveCase
        data class ConstructiveProductStoredPropertyMismatch(val typeName: String) : CannotDeriveCase
        data class WrongDeriverReturnType(
            val methodName: String,
            val typeclassName: String,
            val foundTypeclassName: String? = null,
        ) : CannotDeriveCase

        data class MissingRequiredDeriver(
            val typeclassName: String,
            val requiredName: String,
            val detail: String,
        ) : CannotDeriveCase

        data class MissingEnumOverride(val typeclassName: String) : CannotDeriveCase
        data class Generic(val reason: String, val fix: String) : CannotDeriveCase
    }

    sealed interface InvalidInstanceCase {
        data class ClassBased(val unit: Unit = Unit) : InvalidInstanceCase
        data class ExtensionFunction(val unit: Unit = Unit) : InvalidInstanceCase
        data class RegularParameter(val unit: Unit = Unit) : InvalidInstanceCase
        data class SuspendFunction(val unit: Unit = Unit) : InvalidInstanceCase
        data class ExtensionProperty(val unit: Unit = Unit) : InvalidInstanceCase
        data class LateinitProperty(val unit: Unit = Unit) : InvalidInstanceCase
        data class MutableProperty(val unit: Unit = Unit) : InvalidInstanceCase
        data class CustomGetterProperty(val unit: Unit = Unit) : InvalidInstanceCase
        data class NonTypeclassSupertypes(val unit: Unit = Unit) : InvalidInstanceCase
        data class MustProvideTypeclassType(val unit: Unit = Unit) : InvalidInstanceCase
        data class TopLevelOrphan(val hostNames: List<String>) : InvalidInstanceCase
        data class WrongScopeCompanion(val unit: Unit = Unit) : InvalidInstanceCase
        data class AssociatedOwnerMismatch(val unit: Unit = Unit) : InvalidInstanceCase
        data class NonTypeclassPrerequisites(val unit: Unit = Unit) : InvalidInstanceCase
        data class StarProjectedPrerequisites(val unit: Unit = Unit) : InvalidInstanceCase
        data class DefinitelyNotNullPrerequisites(val unit: Unit = Unit) : InvalidInstanceCase
        data class TypeParameterOnlyInPrerequisites(val parameterName: String) : InvalidInstanceCase
        data class DirectRecursive(val goal: String) : InvalidInstanceCase
        data class Generic(val reason: String, val fix: String) : InvalidInstanceCase
    }

    sealed interface InvalidEquivCase {
        data class Subclassing(val unit: Unit = Unit) : InvalidEquivCase
        data class PublishedInstance(val unit: Unit = Unit) : InvalidEquivCase
        data class Generic(val reason: String, val fix: String) : InvalidEquivCase
    }

    sealed interface InvalidBuiltinEvidenceCase {
        data class Generic(val reason: String, val fix: String) : InvalidBuiltinEvidenceCase
    }
}

internal fun TypeclassDiagnostic.renderBody(): String {
    val (headline, reason, fix) = when (this) {
        is TypeclassDiagnostic.NoContextArgument -> {
            val headline =
                buildString {
                    append("No context argument for required instance ")
                    append(goal)
                    if (scope.isNotBlank()) {
                        append(" while resolving ")
                        append(scope)
                    }
                    append('.')
                }
            val reason =
                if (recursive) {
                    "resolution became recursive before any non-recursive candidate completed."
                } else {
                    "no matching local context value or @Instance rule could produce that goal."
                }
            val fix =
                if (recursive) {
                    "add a non-recursive base @Instance, break the prerequisite cycle, or keep recursion only on the intended derived rule."
                } else {
                    "add or import an @Instance for $goal, satisfy the prerequisites of a rule that can produce it, or pass the intended context argument explicitly."
                }
            Triple(headline, reason, fix)
        }

        is TypeclassDiagnostic.AmbiguousInstance -> {
            val headline =
                buildString {
                    append("Ambiguous typeclass instance for ")
                    append(goal)
                    if (scope.isNotBlank()) {
                        append(" while resolving ")
                        append(scope)
                    }
                    append('.')
                }
            Triple(
                headline,
                "multiple candidates matched: ${candidates.sorted().joinToString()}.",
                "remove one candidate from scope, narrow the requested type so only one rule matches, or pass the intended context argument explicitly.",
            )
        }

        is TypeclassDiagnostic.CannotDerive -> renderCannotDeriveCase(case)
        is TypeclassDiagnostic.InvalidInstance -> renderInvalidInstanceCase(case)
        is TypeclassDiagnostic.InvalidEquiv -> renderInvalidEquivCase(case)
        is TypeclassDiagnostic.InvalidBuiltinEvidence -> renderInvalidBuiltinEvidenceCase(case)
    }
    return "$headline Why it failed: $reason How to fix: $fix"
}

internal fun TypeclassDiagnostic.render(): String = TypeclassDiagnosticIds.format(diagnosticId, renderBody())

internal val TypeclassDiagnostic.headline: String
    get() = when (this) {
        is TypeclassDiagnostic.NoContextArgument,
        is TypeclassDiagnostic.AmbiguousInstance,
        is TypeclassDiagnostic.CannotDerive,
        is TypeclassDiagnostic.InvalidInstance,
        is TypeclassDiagnostic.InvalidEquiv,
        is TypeclassDiagnostic.InvalidBuiltinEvidence,
        -> renderSections(this).first
    }

internal val TypeclassDiagnostic.reason: String
    get() = renderSections(this).second

internal val TypeclassDiagnostic.fix: String
    get() = renderSections(this).third

internal fun parseTypeclassDiagnostic(
    message: String,
    diagnosticId: String? = null,
): TypeclassDiagnostic? {
    val trimmed = message.trim()
    val prefixedMatch = prefixedDiagnosticIdRegex.matchEntire(trimmed)
    val effectiveDiagnosticId = prefixedMatch?.groupValues?.get(1) ?: diagnosticId
    val body = prefixedMatch?.groupValues?.get(2) ?: trimmed
    val sections = parseNarrativeSections(body) ?: return null
    val id = effectiveDiagnosticId ?: inferDiagnosticIdFromHeadline(sections.headline) ?: return null
    return when (id) {
        TypeclassDiagnosticIds.NO_CONTEXT_ARGUMENT -> parseNoContextDiagnostic(sections)
        TypeclassDiagnosticIds.AMBIGUOUS_INSTANCE -> parseAmbiguousDiagnostic(sections)
        TypeclassDiagnosticIds.CANNOT_DERIVE -> TypeclassDiagnostic.CannotDerive(parseCannotDeriveCase(sections.reason, sections.fix))
        TypeclassDiagnosticIds.INVALID_INSTANCE_DECL -> TypeclassDiagnostic.InvalidInstance(parseInvalidInstanceCase(sections.reason, sections.fix))
        TypeclassDiagnosticIds.INVALID_EQUIV_DECL -> TypeclassDiagnostic.InvalidEquiv(parseInvalidEquivCase(sections.reason, sections.fix))
        TypeclassDiagnosticIds.INVALID_BUILTIN_EVIDENCE -> TypeclassDiagnostic.InvalidBuiltinEvidence(TypeclassDiagnostic.InvalidBuiltinEvidenceCase.Generic(sections.reason, sections.fix))
        else -> null
    }
}

internal fun cannotDeriveOnlyUnaryTypeclasses(): TypeclassDiagnostic =
    TypeclassDiagnostic.CannotDerive(TypeclassDiagnostic.CannotDeriveCase.OnlyUnaryTypeclasses())

internal fun cannotDeriveUnsupportedShape(): TypeclassDiagnostic =
    TypeclassDiagnostic.CannotDerive(TypeclassDiagnostic.CannotDeriveCase.UnsupportedShape())

internal fun cannotDeriveRequiresPrimaryConstructor(): TypeclassDiagnostic =
    TypeclassDiagnostic.CannotDerive(TypeclassDiagnostic.CannotDeriveCase.RequiresPrimaryConstructor())

internal fun cannotDeriveConstructiveProductStoredPropertyMismatch(typeName: String): TypeclassDiagnostic =
    TypeclassDiagnostic.CannotDerive(TypeclassDiagnostic.CannotDeriveCase.ConstructiveProductStoredPropertyMismatch(typeName))

internal fun cannotDeriveWrongDeriverReturnType(
    methodName: String,
    typeclassName: String,
    foundTypeclassName: String? = null,
): TypeclassDiagnostic =
    TypeclassDiagnostic.CannotDerive(
        TypeclassDiagnostic.CannotDeriveCase.WrongDeriverReturnType(
            methodName = methodName,
            typeclassName = typeclassName,
            foundTypeclassName = foundTypeclassName,
        ),
    )

internal fun cannotDeriveMissingRequiredDeriver(
    typeclassName: String,
    requiredName: String,
    detail: String,
): TypeclassDiagnostic =
    TypeclassDiagnostic.CannotDerive(
        TypeclassDiagnostic.CannotDeriveCase.MissingRequiredDeriver(
            typeclassName = typeclassName,
            requiredName = requiredName,
            detail = detail,
        ),
    )

internal fun cannotDeriveMissingEnumOverride(typeclassName: String): TypeclassDiagnostic =
    TypeclassDiagnostic.CannotDerive(TypeclassDiagnostic.CannotDeriveCase.MissingEnumOverride(typeclassName))

internal fun cannotDeriveDiagnostic(reason: String): TypeclassDiagnostic =
    TypeclassDiagnostic.CannotDerive(parseCannotDeriveCase(reason.ensureSentence(), fallbackCannotDeriveFix(reason.ensureSentence())))

internal fun invalidInstanceClassBased(): TypeclassDiagnostic =
    TypeclassDiagnostic.InvalidInstance(TypeclassDiagnostic.InvalidInstanceCase.ClassBased())

internal fun invalidInstanceExtensionFunction(): TypeclassDiagnostic =
    TypeclassDiagnostic.InvalidInstance(TypeclassDiagnostic.InvalidInstanceCase.ExtensionFunction())

internal fun invalidInstanceRegularParameter(): TypeclassDiagnostic =
    TypeclassDiagnostic.InvalidInstance(TypeclassDiagnostic.InvalidInstanceCase.RegularParameter())

internal fun invalidInstanceSuspendFunction(): TypeclassDiagnostic =
    TypeclassDiagnostic.InvalidInstance(TypeclassDiagnostic.InvalidInstanceCase.SuspendFunction())

internal fun invalidInstanceExtensionProperty(): TypeclassDiagnostic =
    TypeclassDiagnostic.InvalidInstance(TypeclassDiagnostic.InvalidInstanceCase.ExtensionProperty())

internal fun invalidInstanceLateinitProperty(): TypeclassDiagnostic =
    TypeclassDiagnostic.InvalidInstance(TypeclassDiagnostic.InvalidInstanceCase.LateinitProperty())

internal fun invalidInstanceMutableProperty(): TypeclassDiagnostic =
    TypeclassDiagnostic.InvalidInstance(TypeclassDiagnostic.InvalidInstanceCase.MutableProperty())

internal fun invalidInstanceCustomGetterProperty(): TypeclassDiagnostic =
    TypeclassDiagnostic.InvalidInstance(TypeclassDiagnostic.InvalidInstanceCase.CustomGetterProperty())

internal fun invalidInstanceNonTypeclassSupertypes(): TypeclassDiagnostic =
    TypeclassDiagnostic.InvalidInstance(TypeclassDiagnostic.InvalidInstanceCase.NonTypeclassSupertypes())

internal fun invalidInstanceMustProvideTypeclassType(): TypeclassDiagnostic =
    TypeclassDiagnostic.InvalidInstance(TypeclassDiagnostic.InvalidInstanceCase.MustProvideTypeclassType())

internal fun invalidInstanceTopLevelOrphan(hostNames: List<String>): TypeclassDiagnostic =
    TypeclassDiagnostic.InvalidInstance(TypeclassDiagnostic.InvalidInstanceCase.TopLevelOrphan(hostNames))

internal fun invalidInstanceWrongScopeCompanion(): TypeclassDiagnostic =
    TypeclassDiagnostic.InvalidInstance(TypeclassDiagnostic.InvalidInstanceCase.WrongScopeCompanion())

internal fun invalidInstanceAssociatedOwnerMismatch(): TypeclassDiagnostic =
    TypeclassDiagnostic.InvalidInstance(TypeclassDiagnostic.InvalidInstanceCase.AssociatedOwnerMismatch())

internal fun invalidInstanceNonTypeclassPrerequisites(): TypeclassDiagnostic =
    TypeclassDiagnostic.InvalidInstance(TypeclassDiagnostic.InvalidInstanceCase.NonTypeclassPrerequisites())

internal fun invalidInstanceStarProjectedPrerequisites(): TypeclassDiagnostic =
    TypeclassDiagnostic.InvalidInstance(TypeclassDiagnostic.InvalidInstanceCase.StarProjectedPrerequisites())

internal fun invalidInstanceDefinitelyNotNullPrerequisites(): TypeclassDiagnostic =
    TypeclassDiagnostic.InvalidInstance(TypeclassDiagnostic.InvalidInstanceCase.DefinitelyNotNullPrerequisites())

internal fun invalidInstanceTypeParameterOnlyInPrerequisites(parameterName: String): TypeclassDiagnostic =
    TypeclassDiagnostic.InvalidInstance(TypeclassDiagnostic.InvalidInstanceCase.TypeParameterOnlyInPrerequisites(parameterName))

internal fun invalidInstanceDirectRecursive(goal: String): TypeclassDiagnostic =
    TypeclassDiagnostic.InvalidInstance(TypeclassDiagnostic.InvalidInstanceCase.DirectRecursive(goal))

internal fun invalidInstanceDiagnostic(reason: String): TypeclassDiagnostic =
    TypeclassDiagnostic.InvalidInstance(parseInvalidInstanceCase(reason.ensureSentence(), fallbackInvalidInstanceFix(reason.ensureSentence())))

internal fun invalidEquivSubclassing(): TypeclassDiagnostic =
    TypeclassDiagnostic.InvalidEquiv(TypeclassDiagnostic.InvalidEquivCase.Subclassing())

internal fun invalidEquivPublishedInstance(): TypeclassDiagnostic =
    TypeclassDiagnostic.InvalidEquiv(TypeclassDiagnostic.InvalidEquivCase.PublishedInstance())

internal fun invalidEquivDiagnostic(reason: String): TypeclassDiagnostic =
    TypeclassDiagnostic.InvalidEquiv(parseInvalidEquivCase(reason.ensureSentence(), fallbackInvalidEquivFix(reason.ensureSentence())))

internal fun invalidBuiltinEvidenceDiagnostic(reason: String): TypeclassDiagnostic =
    TypeclassDiagnostic.InvalidBuiltinEvidence(
        TypeclassDiagnostic.InvalidBuiltinEvidenceCase.Generic(
            reason = reason.ensureSentence(),
            fix = fallbackInvalidBuiltinEvidenceFix(reason.ensureSentence()),
        ),
    )

internal fun missingTypeclassInstanceDiagnostic(goal: String, scope: String): String =
    TypeclassDiagnostic.NoContextArgument(goal = goal, scope = scope).renderBody()

internal fun ambiguousTypeclassInstanceDiagnostic(goal: String, scope: String, candidates: List<String>): String =
    TypeclassDiagnostic.AmbiguousInstance(goal = goal, scope = scope, candidates = candidates).renderBody()

internal fun recursiveTypeclassResolutionDiagnostic(goal: String, scope: String): String =
    TypeclassDiagnostic.NoContextArgument(goal = goal, scope = scope, recursive = true).renderBody()

internal fun enrichCannotDeriveDiagnostic(reason: String): String = cannotDeriveDiagnostic(reason).renderBody()
internal fun enrichInvalidInstanceDiagnostic(reason: String): String = invalidInstanceDiagnostic(reason).renderBody()
internal fun enrichInvalidEquivDiagnostic(reason: String): String = invalidEquivDiagnostic(reason).renderBody()
internal fun enrichInvalidBuiltinEvidenceDiagnostic(reason: String): String = invalidBuiltinEvidenceDiagnostic(reason).renderBody()

private fun renderCannotDeriveCase(case: TypeclassDiagnostic.CannotDeriveCase): Triple<String, String, String> =
    when (case) {
        is TypeclassDiagnostic.CannotDeriveCase.OnlyUnaryTypeclasses ->
            Triple(
                "Cannot derive.",
                "@Derive currently only supports typeclasses with exactly one type parameter.",
                "request derivation only for unary typeclasses for now, or hand-write the instance until explicit multi-parameter derivation support exists.",
            )
        is TypeclassDiagnostic.CannotDeriveCase.UnsupportedShape ->
            Triple(
                "Cannot derive.",
                "@Derive is only supported on sealed or final classes and objects.",
                "derive only on final products, enum classes, objects, or sealed roots, or write the instance manually.",
            )
        is TypeclassDiagnostic.CannotDeriveCase.RequiresPrimaryConstructor ->
            Triple(
                "Cannot derive.",
                "constructive product derivation requires a primary constructor.",
                "add a primary constructor that can rebuild the stored properties, or write the instance manually.",
            )
        is TypeclassDiagnostic.CannotDeriveCase.ConstructiveProductStoredPropertyMismatch ->
            Triple(
                "Cannot derive.",
                "Cannot derive ${case.typeName} because constructive product derivation requires constructor parameters to exactly match stored properties.",
                "make the primary constructor parameters match the stored properties exactly, or write the instance manually.",
            )
        is TypeclassDiagnostic.CannotDeriveCase.WrongDeriverReturnType ->
            Triple(
                "Cannot derive.",
                buildString {
                    append(case.methodName)
                    append(" must return ")
                    append(case.typeclassName)
                    append("<...>")
                    if (case.foundTypeclassName != null) {
                        append("; found ")
                        append(case.foundTypeclassName)
                    }
                    append('.')
                },
                "make the derive method return the owning typeclass constructor, either directly or through Any whose concrete value implements that typeclass.",
            )
        is TypeclassDiagnostic.CannotDeriveCase.MissingRequiredDeriver ->
            Triple(
                "Cannot derive.",
                "${case.typeclassName} companion must implement ${case.requiredName}; ${case.detail}.",
                "implement ${case.requiredName} or stop deriving shapes that require it for ${case.typeclassName}.",
            )
        is TypeclassDiagnostic.CannotDeriveCase.MissingEnumOverride ->
            Triple(
                "Cannot derive.",
                "${case.typeclassName} companion must override deriveEnum to derive enum classes.",
                "add a deriveEnum override to the typeclass companion, or stop deriving enum classes for that typeclass.",
            )
        is TypeclassDiagnostic.CannotDeriveCase.Generic ->
            Triple("Cannot derive.", case.reason.ensureSentence(), case.fix.ensureSentence())
    }

private fun renderInvalidInstanceCase(case: TypeclassDiagnostic.InvalidInstanceCase): Triple<String, String, String> =
    when (case) {
        is TypeclassDiagnostic.InvalidInstanceCase.ClassBased ->
            Triple("Invalid @Instance declaration.", "class-based instances are not allowed; use an object.", "declare the instance as an object, a top-level @Instance function, or a val instead of a class.")
        is TypeclassDiagnostic.InvalidInstanceCase.ExtensionFunction ->
            Triple("Invalid @Instance declaration.", "extension instance functions are not allowed.", "drop the extension receiver and express dependencies through type parameters or context parameters instead.")
        is TypeclassDiagnostic.InvalidInstanceCase.RegularParameter ->
            Triple("Invalid @Instance declaration.", "instance functions cannot declare a regular parameter.", "remove regular value parameters from the @Instance function and model prerequisites as context parameters instead.")
        is TypeclassDiagnostic.InvalidInstanceCase.SuspendFunction ->
            Triple("Invalid @Instance declaration.", "suspend instance functions are not allowed.", "make the @Instance function eager and non-suspend, then call suspend helpers from inside the returned object if needed.")
        is TypeclassDiagnostic.InvalidInstanceCase.ExtensionProperty ->
            Triple("Invalid @Instance declaration.", "extension instance properties are not allowed.", "move the instance property to top level or into a companion, and express dependencies without an extension receiver.")
        is TypeclassDiagnostic.InvalidInstanceCase.LateinitProperty ->
            Triple("Invalid @Instance declaration.", "lateinit instance property declarations are not allowed.", "replace the declaration with an immutable val that is initialized directly at the declaration site.")
        is TypeclassDiagnostic.InvalidInstanceCase.MutableProperty ->
            Triple("Invalid @Instance declaration.", "mutable instance property declarations are not allowed.", "replace the declaration with an immutable val that is initialized directly at the declaration site.")
        is TypeclassDiagnostic.InvalidInstanceCase.CustomGetterProperty ->
            Triple("Invalid @Instance declaration.", "custom getter instance property declarations are not allowed.", "replace the declaration with an immutable val that is initialized directly at the declaration site.")
        is TypeclassDiagnostic.InvalidInstanceCase.NonTypeclassSupertypes ->
            Triple("Invalid @Instance declaration.", "non-@Typeclass intermediate supertypes cannot provide inherited typeclass instances.", "remove the non-typeclass intermediate supertype or provide the typeclass head directly.")
        is TypeclassDiagnostic.InvalidInstanceCase.MustProvideTypeclassType ->
            Triple("Invalid @Instance declaration.", "@Instance declarations must provide a @Typeclass type.", "change the provided type so the declaration returns a real @Typeclass application.")
        is TypeclassDiagnostic.InvalidInstanceCase.TopLevelOrphan ->
            Triple("Invalid @Instance declaration.", "top-level orphan instance declarations must be declared in the same file as one of: ${case.hostNames.joinToString()}.", "move the declaration into the same file as one of the allowed host types, or into an allowed companion.")
        is TypeclassDiagnostic.InvalidInstanceCase.WrongScopeCompanion ->
            Triple("Invalid @Instance declaration.", "instance declarations must be top-level or live in a companion of the provided typeclass head or one of its associated owners.", "move the declaration to top level or into the companion of the provided typeclass head or one of its associated owners.")
        is TypeclassDiagnostic.InvalidInstanceCase.AssociatedOwnerMismatch ->
            Triple("Invalid @Instance declaration.", "associated owner does not match the provided typeclass head or its type arguments.", "move the declaration into the companion of the provided typeclass head or one of the associated type arguments.")
        is TypeclassDiagnostic.InvalidInstanceCase.NonTypeclassPrerequisites ->
            Triple("Invalid @Instance declaration.", "instance function context parameters must be typeclass prerequisites.", "model prerequisites only as typeclass applications that the solver can satisfy directly.")
        is TypeclassDiagnostic.InvalidInstanceCase.StarProjectedPrerequisites ->
            Triple("Invalid @Instance declaration.", "instance function typeclass prerequisites must not use star projections.", "keep @Instance prerequisites as ordinary non-star-projected typeclass applications that the solver can satisfy directly.")
        is TypeclassDiagnostic.InvalidInstanceCase.DefinitelyNotNullPrerequisites ->
            Triple("Invalid @Instance declaration.", "instance function typeclass prerequisites must not use definitely-non-null type arguments.", "keep @Instance prerequisites as ordinary non-star-projected typeclass applications that the solver can satisfy directly.")
        is TypeclassDiagnostic.InvalidInstanceCase.TypeParameterOnlyInPrerequisites ->
            Triple("Invalid @Instance declaration.", "instance type parameter ${case.parameterName} must appear in the provided type, not only prerequisites.", "make every instance type parameter contribute to the provided typeclass head instead of only its prerequisites.")
        is TypeclassDiagnostic.InvalidInstanceCase.DirectRecursive ->
            Triple("Invalid @Instance declaration.", "direct recursive instance rule for ${case.goal} is not allowed.", "break the direct self-reference or move the recursion into a supported derived rule.")
        is TypeclassDiagnostic.InvalidInstanceCase.Generic ->
            Triple("Invalid @Instance declaration.", case.reason.ensureSentence(), case.fix.ensureSentence())
    }

private fun renderInvalidEquivCase(case: TypeclassDiagnostic.InvalidEquivCase): Triple<String, String, String> =
    when (case) {
        is TypeclassDiagnostic.InvalidEquivCase.Subclassing ->
            Triple("Invalid Equiv declaration.", "Equiv is compiler-owned; users must not subclass it directly.", "use Iso for explicit user-authored reversible conversions, or @DeriveEquiv / @DeriveVia when the compiler should synthesize the evidence.")
        is TypeclassDiagnostic.InvalidEquivCase.PublishedInstance ->
            Triple("Invalid Equiv declaration.", "Equiv is compiler-owned and cannot be published as an @Instance.", "remove the Equiv @Instance and expose an Iso instead; Equiv is compiler-owned evidence.")
        is TypeclassDiagnostic.InvalidEquivCase.Generic ->
            Triple("Invalid Equiv declaration.", case.reason.ensureSentence(), case.fix.ensureSentence())
    }

private fun renderInvalidBuiltinEvidenceCase(case: TypeclassDiagnostic.InvalidBuiltinEvidenceCase): Triple<String, String, String> =
    when (case) {
        is TypeclassDiagnostic.InvalidBuiltinEvidenceCase.Generic ->
            Triple("Invalid builtin evidence request.", case.reason.ensureSentence(), case.fix.ensureSentence())
    }

private fun renderSections(diagnostic: TypeclassDiagnostic): Triple<String, String, String> =
    when (diagnostic) {
        is TypeclassDiagnostic.NoContextArgument,
        is TypeclassDiagnostic.AmbiguousInstance,
        is TypeclassDiagnostic.CannotDerive,
        is TypeclassDiagnostic.InvalidInstance,
        is TypeclassDiagnostic.InvalidEquiv,
        is TypeclassDiagnostic.InvalidBuiltinEvidence,
        -> {
            val body = diagnostic.renderBody()
            val sections = parseNarrativeSections(body) ?: error("Failed to parse self-rendered diagnostic: $body")
            Triple(sections.headline, sections.reason, sections.fix)
        }
    }

private fun parseNoContextDiagnostic(sections: ParsedNarrativeSections): TypeclassDiagnostic? {
    val match = noContextHeadlineRegex.matchEntire(sections.headline) ?: return null
    val goal = match.groupValues[1]
    val scope = match.groupValues.getOrElse(2) { "" }
    return when (sections.reason.ensureSentence()) {
        "no matching local context value or @Instance rule could produce that goal." ->
            TypeclassDiagnostic.NoContextArgument(goal = goal, scope = scope, recursive = false)
        "resolution became recursive before any non-recursive candidate completed." ->
            TypeclassDiagnostic.NoContextArgument(goal = goal, scope = scope, recursive = true)
        else -> null
    }
}

private fun parseAmbiguousDiagnostic(sections: ParsedNarrativeSections): TypeclassDiagnostic? {
    val match = ambiguousHeadlineRegex.matchEntire(sections.headline) ?: return null
    if (!sections.reason.startsWith("multiple candidates matched: ")) return null
    val candidates = sections.reason.removePrefix("multiple candidates matched: ").removeSuffix(".").split(", ").filter(String::isNotBlank)
    return TypeclassDiagnostic.AmbiguousInstance(
        goal = match.groupValues[1],
        scope = match.groupValues.getOrElse(2) { "" },
        candidates = candidates,
    )
}

private fun parseCannotDeriveCase(reason: String, fix: String): TypeclassDiagnostic.CannotDeriveCase =
    when {
        reason == "@Derive currently only supports typeclasses with exactly one type parameter." ->
            TypeclassDiagnostic.CannotDeriveCase.OnlyUnaryTypeclasses()
        reason == "@Derive is only supported on sealed or final classes and objects." ->
            TypeclassDiagnostic.CannotDeriveCase.UnsupportedShape()
        reason == "constructive product derivation requires a primary constructor." ->
            TypeclassDiagnostic.CannotDeriveCase.RequiresPrimaryConstructor()
        constructiveMismatchRegex.matches(reason) ->
            TypeclassDiagnostic.CannotDeriveCase.ConstructiveProductStoredPropertyMismatch(
                constructiveMismatchRegex.matchEntire(reason)!!.groupValues[1],
            )
        wrongDeriverReturnRegex.matches(reason) -> {
            val m = wrongDeriverReturnRegex.matchEntire(reason)!!
            TypeclassDiagnostic.CannotDeriveCase.WrongDeriverReturnType(m.groupValues[1], m.groupValues[2], m.groupValues.getOrNull(3)?.ifBlank { null })
        }
        missingRequiredDeriverRegex.matches(reason) -> {
            val m = missingRequiredDeriverRegex.matchEntire(reason)!!
            TypeclassDiagnostic.CannotDeriveCase.MissingRequiredDeriver(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        }
        missingEnumOverrideRegex.matches(reason) ->
            TypeclassDiagnostic.CannotDeriveCase.MissingEnumOverride(missingEnumOverrideRegex.matchEntire(reason)!!.groupValues[1])
        else -> TypeclassDiagnostic.CannotDeriveCase.Generic(reason.ensureSentence(), fix.ensureSentence())
    }

private fun parseInvalidInstanceCase(reason: String, fix: String): TypeclassDiagnostic.InvalidInstanceCase =
    when {
        reason == "class-based instances are not allowed; use an object." -> TypeclassDiagnostic.InvalidInstanceCase.ClassBased()
        reason == "extension instance functions are not allowed." -> TypeclassDiagnostic.InvalidInstanceCase.ExtensionFunction()
        reason == "instance functions cannot declare a regular parameter." -> TypeclassDiagnostic.InvalidInstanceCase.RegularParameter()
        reason == "suspend instance functions are not allowed." -> TypeclassDiagnostic.InvalidInstanceCase.SuspendFunction()
        reason == "extension instance properties are not allowed." -> TypeclassDiagnostic.InvalidInstanceCase.ExtensionProperty()
        reason == "lateinit instance property declarations are not allowed." -> TypeclassDiagnostic.InvalidInstanceCase.LateinitProperty()
        reason == "mutable instance property declarations are not allowed." -> TypeclassDiagnostic.InvalidInstanceCase.MutableProperty()
        reason == "custom getter instance property declarations are not allowed." -> TypeclassDiagnostic.InvalidInstanceCase.CustomGetterProperty()
        reason == "non-@Typeclass intermediate supertypes cannot provide inherited typeclass instances." -> TypeclassDiagnostic.InvalidInstanceCase.NonTypeclassSupertypes()
        reason == "@Instance declarations must provide a @Typeclass type." -> TypeclassDiagnostic.InvalidInstanceCase.MustProvideTypeclassType()
        reason == "instance declarations must be top-level or live in a companion of the provided typeclass head or one of its associated owners." -> TypeclassDiagnostic.InvalidInstanceCase.WrongScopeCompanion()
        reason == "associated owner does not match the provided typeclass head or its type arguments." -> TypeclassDiagnostic.InvalidInstanceCase.AssociatedOwnerMismatch()
        reason == "instance function context parameters must be typeclass prerequisites." -> TypeclassDiagnostic.InvalidInstanceCase.NonTypeclassPrerequisites()
        reason == "instance function typeclass prerequisites must not use star projections." -> TypeclassDiagnostic.InvalidInstanceCase.StarProjectedPrerequisites()
        reason == "instance function typeclass prerequisites must not use definitely-non-null type arguments." -> TypeclassDiagnostic.InvalidInstanceCase.DefinitelyNotNullPrerequisites()
        topLevelOrphanRegex.matches(reason) ->
            TypeclassDiagnostic.InvalidInstanceCase.TopLevelOrphan(
                topLevelOrphanRegex.matchEntire(reason)!!.groupValues[1].split(", ").filter(String::isNotBlank),
            )
        typeParameterPrereqRegex.matches(reason) ->
            TypeclassDiagnostic.InvalidInstanceCase.TypeParameterOnlyInPrerequisites(typeParameterPrereqRegex.matchEntire(reason)!!.groupValues[1])
        directRecursiveRegex.matches(reason) ->
            TypeclassDiagnostic.InvalidInstanceCase.DirectRecursive(directRecursiveRegex.matchEntire(reason)!!.groupValues[1])
        else -> TypeclassDiagnostic.InvalidInstanceCase.Generic(reason.ensureSentence(), fix.ensureSentence())
    }

private fun parseInvalidEquivCase(reason: String, fix: String): TypeclassDiagnostic.InvalidEquivCase =
    when (reason.ensureSentence()) {
        "Equiv is compiler-owned; users must not subclass it directly." -> TypeclassDiagnostic.InvalidEquivCase.Subclassing()
        "Equiv is compiler-owned and cannot be published as an @Instance." -> TypeclassDiagnostic.InvalidEquivCase.PublishedInstance()
        else -> TypeclassDiagnostic.InvalidEquivCase.Generic(reason.ensureSentence(), fix.ensureSentence())
    }

private fun inferDiagnosticIdFromHeadline(headline: String): String? =
    when {
        headline == "Cannot derive." -> TypeclassDiagnosticIds.CANNOT_DERIVE
        headline == "Invalid @Instance declaration." -> TypeclassDiagnosticIds.INVALID_INSTANCE_DECL
        headline == "Invalid Equiv declaration." -> TypeclassDiagnosticIds.INVALID_EQUIV_DECL
        headline == "Invalid builtin evidence request." -> TypeclassDiagnosticIds.INVALID_BUILTIN_EVIDENCE
        headline.startsWith("No context argument for required instance ") -> TypeclassDiagnosticIds.NO_CONTEXT_ARGUMENT
        headline.startsWith("Ambiguous typeclass instance for ") -> TypeclassDiagnosticIds.AMBIGUOUS_INSTANCE
        else -> null
    }

private data class ParsedNarrativeSections(val headline: String, val reason: String, val fix: String)

private fun parseNarrativeSections(message: String): ParsedNarrativeSections? {
    val match = genericWhyFixRegex.matchEntire(message.trim()) ?: return null
    return ParsedNarrativeSections(
        headline = match.groupValues[1].trim().ensureSentence(),
        reason = match.groupValues[2].trim().ensureSentence(),
        fix = match.groupValues[3].trim().ensureSentence(),
    )
}

private fun fallbackCannotDeriveFix(reason: String): String =
    when {
        reason.contains("monomorphic", ignoreCase = true) ->
            "move the annotation to a concrete non-generic class, or write and export the instance explicitly."
        reason.contains("empty path", ignoreCase = true) ->
            "add at least one via-type waypoint or pinned Iso segment to the @DeriveVia annotation."
        reason.contains("typeclass with at least one type parameter", ignoreCase = true) ->
            "target an actual typeclass interface whose transported slot is a real type parameter."
        reason.contains("DeriveVia terminal type", ignoreCase = true) ->
            "choose a terminal via type that can appear as a regular typeclass goal."
        reason.contains("Equiv between", ignoreCase = true) ->
            "restrict @DeriveEquiv to transparent total shapes, or switch to an explicit @DeriveVia path with pinned Iso segments."
        reason.contains("Cannot derive via", ignoreCase = true) || reason.contains("disconnected", ignoreCase = true) || reason.contains("ambiguous", ignoreCase = true) ->
            "adjust the DeriveVia path so every segment attaches uniquely from the annotated target to the requested terminal type."
        reason.contains("result head", ignoreCase = true) || reason.contains("admissible", ignoreCase = true) || reason.contains("not quantified", ignoreCase = true) ->
            "simplify the sealed hierarchy so every admitted case is recoverable from the root result head, or write the instance manually."
        else ->
            "adjust the declaration, deriver, or transport path so the requested typeclass can be derived from the visible shape, or write the instance manually."
    }

private fun fallbackInvalidInstanceFix(reason: String): String =
    "move the declaration into an allowed scope and keep the @Instance shape to supported objects, properties, and functions only."

private fun fallbackInvalidEquivFix(reason: String): String =
    "stop constructing Equiv directly and use Iso, @DeriveEquiv, or @DeriveVia instead."

private fun fallbackInvalidBuiltinEvidenceFix(reason: String): String =
    when {
        reason.contains("KClass", ignoreCase = true) ->
            "make the target type concrete and non-null at the call site, or pass explicit KClass evidence."
        reason.contains("KSerializer", ignoreCase = true) ->
            "make the target type serializable and runtime-available, or pass explicit KSerializer evidence."
        reason.contains("proof", ignoreCase = true) ->
            "use only builtin proof goals that the plugin can prove from the visible types, or pass explicit evidence."
        else ->
            "request builtin evidence only for goals the plugin can materialize from the visible runtime type information."
    }

private fun String.ensureSentence(): String =
    trim().trimEnd().let { text ->
        if (text.endsWith('.')) text else "$text."
    }
