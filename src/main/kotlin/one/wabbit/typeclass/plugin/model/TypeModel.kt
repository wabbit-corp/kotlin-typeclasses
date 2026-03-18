package one.wabbit.typeclass.plugin.model

internal sealed interface TcType {
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
            is TcType.Constructor -> TcType.Constructor(type.classifierId, type.arguments.map(::rename), type.isNullable)
            is TcType.Variable -> TcType.Variable(nameFor(type.id), nameFor(type.id), type.isNullable)
        }

    fun nameFor(id: String): String =
        namesById.getOrPut(id) { "T${namesById.size}" }
}

internal fun TcType.references(id: String): Boolean =
    when (this) {
        is TcType.Constructor -> arguments.any { it.references(id) }
        is TcType.Variable -> this.id == id
    }

internal fun TcType.render(): String =
    when (this) {
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
