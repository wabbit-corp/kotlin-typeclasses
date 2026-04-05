package one.wabbit.typeclass

public data class ProductFieldMetadata(
    val name: String,
    val typeName: String,
    val instanceSlot: Any,
    val accessor: (Any?) -> Any?,
) {
    public val instance: Any
        get() = resolveTypeclassInstance(instanceSlot)
}

public fun ProductFieldMetadata.get(value: Any?): Any? = accessor(value)

public class ProductTypeclassMetadata(
    val typeName: String,
    val fields: List<ProductFieldMetadata>,
    val isValueClass: Boolean,
    private val constructor: (List<Any?>) -> Any?,
) {
    public fun construct(arguments: List<Any?>): Any? = constructor(arguments)

    public fun construct(vararg arguments: Any?): Any? = constructor(arguments.toList())
}

public data class SumCaseMetadata(
    val name: String,
    val typeName: String,
    val isValueClass: Boolean,
    val instanceSlot: Any,
    val matcher: (Any?) -> Boolean,
) {
    public val instance: Any
        get() = resolveTypeclassInstance(instanceSlot)
}

public fun SumCaseMetadata.matches(value: Any?): Boolean = matcher(value)

public data class SumTypeclassMetadata(
    val typeName: String,
    val cases: List<SumCaseMetadata>,
)

public data class EnumEntryMetadata(
    val name: String,
)

public class EnumTypeclassMetadata(
    val typeName: String,
    val entries: List<EnumEntryMetadata>,
    private val ordinalResolver: (Any?) -> Int,
    private val valueResolver: (Int) -> Any?,
) {
    public fun ordinalOf(value: Any?): Int = ordinalResolver(value)

    public fun entryOf(value: Any?): EnumEntryMetadata {
        val ordinal = ordinalOf(value)
        require(ordinal in entries.indices) {
            "Enum value is not one of the known entries for $typeName"
        }
        return entries[ordinal]
    }

    public fun valueAt(index: Int): Any? {
        require(index in entries.indices) {
            "Enum index $index is out of range for $typeName"
        }
        return valueResolver(index) ?: error("Enum value resolver returned null for $typeName[$index]")
    }

    public fun construct(index: Int): Any? = valueAt(index)

    public fun construct(name: String): Any? {
        val index = entries.indexOfFirst { entry -> entry.name == name }
        require(index >= 0) {
            "Unknown enum entry $name for $typeName"
        }
        return valueAt(index)
    }
}

public interface ProductTypeclassDeriver {
    public fun deriveProduct(metadata: ProductTypeclassMetadata): Any
}

public interface TypeclassDeriver : ProductTypeclassDeriver {
    public fun deriveSum(metadata: SumTypeclassMetadata): Any

    public fun deriveEnum(metadata: EnumTypeclassMetadata): Any =
        error("TypeclassDeriver must override deriveEnum to derive enum classes")
}

public class RecursiveTypeclassInstanceCell(
    public var value: Any? = null,
)

private fun resolveTypeclassInstance(instanceSlot: Any): Any =
    when (instanceSlot) {
        is RecursiveTypeclassInstanceCell ->
            instanceSlot.value ?: error("Recursive typeclass instance accessed before initialization")

        else -> instanceSlot
    }
