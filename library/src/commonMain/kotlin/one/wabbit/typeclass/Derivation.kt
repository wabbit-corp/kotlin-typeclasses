// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass

/**
 * Metadata for one field of a product shape being derived.
 *
 * @property name source-level field name
 * @property typeName fully qualified rendered field type
 * @property instanceSlot compiler-managed storage that resolves to the field's
 *   required typeclass instance, potentially through a recursive cell
 * @property accessor function that reads the field value from a product instance
 */
public data class ProductFieldMetadata(
    val name: String,
    val typeName: String,
    val instanceSlot: Any,
    val accessor: (Any?) -> Any?,
) {
    /**
     * Resolves the field's typeclass instance from [instanceSlot].
     */
    public val instance: Any
        get() = resolveTypeclassInstance(instanceSlot)
}

/**
 * Reads this field's value from [value].
 */
public fun ProductFieldMetadata.get(value: Any?): Any? = accessor(value)

/**
 * Metadata for a product type being derived.
 *
 * @property typeName fully qualified rendered type name of the product
 * @property fields ordered derived-field metadata
 * @property isValueClass whether the product is represented as a Kotlin value
 *   class
 * @property constructor compiler-managed constructor bridge used by [construct]
 */
public class ProductTypeclassMetadata(
    val typeName: String,
    val fields: List<ProductFieldMetadata>,
    val isValueClass: Boolean,
    private val constructor: (List<Any?>) -> Any?,
) {
    /**
     * Reconstructs a product value from [arguments] in field order.
     */
    public fun construct(arguments: List<Any?>): Any? = constructor(arguments)

    /**
     * Reconstructs a product value from [arguments] in field order.
     */
    public fun construct(vararg arguments: Any?): Any? = constructor(arguments.toList())
}

/**
 * Metadata for one case of a sealed-sum shape being derived.
 *
 * @property name source-level case name
 * @property typeName fully qualified rendered case type
 * @property isValueClass whether the case is represented as a Kotlin value class
 * @property instanceSlot compiler-managed storage that resolves to the case's
 *   typeclass instance, potentially through a recursive cell
 * @property matcher function that tests whether a runtime value belongs to this
 *   case
 */
public data class SumCaseMetadata(
    val name: String,
    val typeName: String,
    val isValueClass: Boolean,
    val instanceSlot: Any,
    val matcher: (Any?) -> Boolean,
) {
    /**
     * Resolves the case's typeclass instance from [instanceSlot].
     */
    public val instance: Any
        get() = resolveTypeclassInstance(instanceSlot)
}

/**
 * Tests whether [value] belongs to this sum case.
 */
public fun SumCaseMetadata.matches(value: Any?): Boolean = matcher(value)

/**
 * Metadata for a sealed-sum type being derived.
 *
 * @property typeName fully qualified rendered type name of the sum root
 * @property cases ordered metadata for the derivable cases
 */
public data class SumTypeclassMetadata(
    val typeName: String,
    val cases: List<SumCaseMetadata>,
)

/**
 * Metadata for one enum entry.
 *
 * @property name source-level enum entry name
 */
public data class EnumEntryMetadata(
    val name: String,
)

/**
 * Metadata for an enum type being derived.
 *
 * @property typeName fully qualified rendered enum type name
 * @property entries enum entries in ordinal order
 * @property ordinalResolver compiler-managed bridge that extracts an ordinal from
 *   a runtime enum value
 * @property valueResolver compiler-managed bridge that reconstructs a value by
 *   ordinal
 */
public class EnumTypeclassMetadata(
    val typeName: String,
    val entries: List<EnumEntryMetadata>,
    private val ordinalResolver: (Any?) -> Int,
    private val valueResolver: (Int) -> Any?,
) {
    /**
     * Returns the ordinal of [value].
     */
    public fun ordinalOf(value: Any?): Int = ordinalResolver(value)

    /**
     * Returns the entry metadata for [value].
     */
    public fun entryOf(value: Any?): EnumEntryMetadata {
        val ordinal = ordinalOf(value)
        require(ordinal in entries.indices) {
            "Enum value is not one of the known entries for $typeName"
        }
        return entries[ordinal]
    }

    /**
     * Returns the enum value at [index].
     */
    public fun valueAt(index: Int): Any? {
        require(index in entries.indices) {
            "Enum index $index is out of range for $typeName"
        }
        return valueResolver(index) ?: error("Enum value resolver returned null for $typeName[$index]")
    }

    /**
     * Reconstructs the enum value at [index].
     */
    public fun construct(index: Int): Any? = valueAt(index)

    /**
     * Reconstructs the enum value named [name].
     */
    public fun construct(name: String): Any? {
        val index = entries.indexOfFirst { entry -> entry.name == name }
        require(index >= 0) {
            "Unknown enum entry $name for $typeName"
        }
        return valueAt(index)
    }
}

/**
 * Derivation hook for typeclasses that can be synthesized for product types.
 */
public interface ProductTypeclassDeriver {
    /**
     * Produces the derived typeclass instance for the supplied product metadata.
     */
    public fun deriveProduct(metadata: ProductTypeclassMetadata): Any
}

/**
 * Derivation hook for typeclasses that can be synthesized for products, sealed
 * sums, and optionally enums.
 */
public interface TypeclassDeriver : ProductTypeclassDeriver {
    /**
     * Produces the derived typeclass instance for the supplied sum metadata.
     */
    public fun deriveSum(metadata: SumTypeclassMetadata): Any

    /**
     * Produces the derived typeclass instance for the supplied enum metadata.
     *
     * Typeclasses that want enum derivation must override this method.
     */
    public fun deriveEnum(metadata: EnumTypeclassMetadata): Any =
        error("TypeclassDeriver must override deriveEnum to derive enum classes")
}

/**
 * Mutable cell used to tie recursive knots in generated derivation graphs.
 *
 * During recursive derivation the compiler may publish a placeholder cell first
 * and fill [value] once the final instance has been constructed.
 */
public class RecursiveTypeclassInstanceCell(
    public var value: Any? = null,
)

private fun resolveTypeclassInstance(instanceSlot: Any): Any =
    when (instanceSlot) {
        is RecursiveTypeclassInstanceCell ->
            instanceSlot.value ?: error("Recursive typeclass instance accessed before initialization")

        else -> instanceSlot
    }
