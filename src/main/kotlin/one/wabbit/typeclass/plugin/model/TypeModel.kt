package one.wabbit.typeclass.plugin.model

import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.types.Variance

internal sealed interface TcType {
    data object StarProjection : TcType

    data class Projected(
        val variance: Variance,
        val type: TcType,
    ) : TcType {
        init {
            require(variance != Variance.INVARIANT) {
                "Projected TcType must use IN or OUT variance."
            }
        }
    }

    data class Variable(
        val id: String,
        val displayName: String,
        val isNullable: Boolean = false,
    ) : TcType

    data class Constructor(
        val classifierId: String,
        val arguments: List<TcType>,
        val isNullable: Boolean = false,
    ) : TcType
}

internal data class TcTypeParameter(
    val id: String,
    val displayName: String,
)

internal data class InstanceRule(
    val id: String,
    val typeParameters: List<TcTypeParameter>,
    val providedType: TcType,
    val prerequisiteTypes: List<TcType>,
    val supportsRecursiveResolution: Boolean = false,
)

internal sealed interface ResolutionPlan {
    val providedType: TcType

    data class LocalContext(
        val index: Int,
        override val providedType: TcType,
    ) : ResolutionPlan

    data class ApplyRule(
        val ruleId: String,
        override val providedType: TcType,
        val appliedTypeArguments: List<TcType>,
        val prerequisitePlans: List<ResolutionPlan>,
    ) : ResolutionPlan

    data class RecursiveReference(
        override val providedType: TcType,
    ) : ResolutionPlan
}

internal class AlphaRenamer {
    private val namesById: LinkedHashMap<String, String> = linkedMapOf()

    fun rename(type: TcType): TcType =
        when (type) {
            TcType.StarProjection -> TcType.StarProjection
            is TcType.Projected -> TcType.Projected(type.variance, rename(type.type))
            is TcType.Constructor -> TcType.Constructor(type.classifierId, type.arguments.map(::rename), type.isNullable)
            is TcType.Variable -> TcType.Variable(nameFor(type.id), nameFor(type.id), type.isNullable)
        }

    fun nameFor(id: String): String =
        namesById.getOrPut(id) { "T${namesById.size}" }
}

internal fun TcType.references(id: String): Boolean =
    when (this) {
        TcType.StarProjection -> false
        is TcType.Projected -> type.references(id)
        is TcType.Constructor -> arguments.any { it.references(id) }
        is TcType.Variable -> this.id == id
    }

internal fun TcType.referencedVariableIds(): Set<String> =
    buildSet {
        collectReferencedVariableIds(this@referencedVariableIds, this)
    }

private fun collectReferencedVariableIds(
    type: TcType,
    sink: MutableSet<String>,
) {
    when (type) {
        TcType.StarProjection -> Unit
        is TcType.Projected -> collectReferencedVariableIds(type.type, sink)
        is TcType.Constructor -> type.arguments.forEach { argument -> collectReferencedVariableIds(argument, sink) }
        is TcType.Variable -> sink += type.id
    }
}

internal fun TcType.containsStarProjection(): Boolean =
    when (this) {
        TcType.StarProjection -> true
        is TcType.Projected -> type.containsStarProjection()
        is TcType.Constructor -> arguments.any(TcType::containsStarProjection)
        is TcType.Variable -> false
    }

internal fun TcType.render(): String =
    when (this) {
        TcType.StarProjection -> "*"
        is TcType.Projected ->
            buildString {
                append(
                    when (variance) {
                        Variance.IN_VARIANCE -> "in "
                        Variance.OUT_VARIANCE -> "out "
                        Variance.INVARIANT -> ""
                    },
                )
                append(type.render())
            }

        is TcType.Constructor ->
            buildString {
                if (arguments.isEmpty()) {
                    append(classifierId)
                } else {
                    append(classifierId)
                    append(arguments.joinToString(prefix = "<", postfix = ">", separator = ",", transform = TcType::render))
                }
                if (isNullable) {
                    append('?')
                }
            }

        is TcType.Variable -> buildString {
            append(displayName)
            if (isNullable) {
                append('?')
            }
        }
    }

internal fun TcType.normalizedKey(): String = AlphaRenamer().rename(this).render()

internal fun TcType.isProvablyNullable(): Boolean =
    when (this) {
        TcType.StarProjection -> false
        is TcType.Projected -> false
        is TcType.Constructor -> isNullable
        is TcType.Variable -> false
    }

internal fun TcType.isProvablyNotNullable(): Boolean =
    when (this) {
        TcType.StarProjection -> false
        is TcType.Projected -> false
        is TcType.Constructor -> !isNullable
        is TcType.Variable -> false
    }

internal fun TcType.isExactTypeIdentity(): Boolean =
    when (this) {
        TcType.StarProjection -> true
        is TcType.Projected -> type.isExactTypeIdentity()
        is TcType.Constructor -> arguments.all(TcType::isExactTypeIdentity)
        is TcType.Variable -> false
    }

internal fun TcType.toCanonicalTypeIdName(): String =
    when (this) {
        TcType.StarProjection -> "*"

        is TcType.Projected ->
            buildString {
                append(
                    when (variance) {
                        Variance.IN_VARIANCE -> "in "
                        Variance.OUT_VARIANCE -> "out "
                        Variance.INVARIANT -> ""
                    },
                )
                append(type.toCanonicalTypeIdName())
            }

        is TcType.Constructor ->
            buildString {
                append(normalizeClassifierId(classifierId))
                if (arguments.isNotEmpty()) {
                    append(arguments.joinToString(prefix = "<", postfix = ">", separator = ",", transform = TcType::toCanonicalTypeIdName))
                }
                if (isNullable) {
                    append('?')
                }
            }

        is TcType.Variable ->
            buildString {
                append(displayName)
                if (isNullable) {
                    append('?')
                }
            }
    }

private fun normalizeClassifierId(classifierId: String): String =
    runCatching { ClassId.fromString(classifierId).asSingleFqName().asString() }
        .getOrElse { classifierId.replace('/', '.') }
