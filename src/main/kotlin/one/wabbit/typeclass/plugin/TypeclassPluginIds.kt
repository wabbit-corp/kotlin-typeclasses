package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

internal const val TYPECLASS_PLUGIN_ID: String = "one.wabbit.typeclass"
internal const val TYPECLASS_ANNOTATION_FQ_NAME: String = "one.wabbit.typeclass.Typeclass"
internal const val INSTANCE_ANNOTATION_FQ_NAME: String = "one.wabbit.typeclass.Instance"
internal const val DERIVE_ANNOTATION_FQ_NAME: String = "one.wabbit.typeclass.Derive"
internal const val DERIVE_VIA_ANNOTATION_FQ_NAME: String = "one.wabbit.typeclass.DeriveVia"
internal const val DERIVE_EQUIV_ANNOTATION_FQ_NAME: String = "one.wabbit.typeclass.DeriveEquiv"
internal const val GADT_DERIVATION_POLICY_ANNOTATION_FQ_NAME: String = "one.wabbit.typeclass.GadtDerivationPolicy"
internal const val GADT_DERIVATION_MODE_FQ_NAME: String = "one.wabbit.typeclass.GadtDerivationMode"
internal const val GENERATED_WRAPPER_ANNOTATION_FQ_NAME: String = "one.wabbit.typeclass.GeneratedTypeclassWrapper"
internal const val GENERATED_INSTANCE_ANNOTATION_FQ_NAME: String = "one.wabbit.typeclass.GeneratedTypeclassInstance"
internal const val PRODUCT_TYPECLASS_DERIVER_FQ_NAME: String = "one.wabbit.typeclass.ProductTypeclassDeriver"
internal const val TYPECLASS_DERIVER_FQ_NAME: String = "one.wabbit.typeclass.TypeclassDeriver"
internal const val EQUIV_FQ_NAME: String = "one.wabbit.typeclass.Equiv"
internal const val ISO_FQ_NAME: String = "one.wabbit.typeclass.Iso"
internal const val PRODUCT_FIELD_METADATA_FQ_NAME: String = "one.wabbit.typeclass.ProductFieldMetadata"
internal const val PRODUCT_TYPECLASS_METADATA_FQ_NAME: String = "one.wabbit.typeclass.ProductTypeclassMetadata"
internal const val SUM_CASE_METADATA_FQ_NAME: String = "one.wabbit.typeclass.SumCaseMetadata"
internal const val SUM_TYPECLASS_METADATA_FQ_NAME: String = "one.wabbit.typeclass.SumTypeclassMetadata"
internal const val ENUM_ENTRY_METADATA_FQ_NAME: String = "one.wabbit.typeclass.EnumEntryMetadata"
internal const val ENUM_TYPECLASS_METADATA_FQ_NAME: String = "one.wabbit.typeclass.EnumTypeclassMetadata"
internal const val RECURSIVE_TYPECLASS_INSTANCE_CELL_FQ_NAME: String = "one.wabbit.typeclass.RecursiveTypeclassInstanceCell"
internal const val SAME_FQ_NAME: String = "one.wabbit.typeclass.Same"
internal const val SAME_PROOF_FQ_NAME: String = "one.wabbit.typeclass.UnsafeAssertSame"
internal const val NOT_SAME_FQ_NAME: String = "one.wabbit.typeclass.NotSame"
internal const val NOT_SAME_PROOF_FQ_NAME: String = "one.wabbit.typeclass.UnsafeAssertNotSame"
internal const val SUBTYPE_FQ_NAME: String = "one.wabbit.typeclass.Subtype"
internal const val SUBTYPE_PROOF_FQ_NAME: String = "one.wabbit.typeclass.UnsafeAssertSubtype"
internal const val STRICT_SUBTYPE_FQ_NAME: String = "one.wabbit.typeclass.StrictSubtype"
internal const val STRICT_SUBTYPE_PROOF_FQ_NAME: String = "one.wabbit.typeclass.UnsafeAssertStrictSubtype"
internal const val NULLABLE_FQ_NAME: String = "one.wabbit.typeclass.Nullable"
internal const val NULLABLE_PROOF_FQ_NAME: String = "one.wabbit.typeclass.UnsafeAssertNullable"
internal const val NOT_NULLABLE_FQ_NAME: String = "one.wabbit.typeclass.NotNullable"
internal const val NOT_NULLABLE_PROOF_FQ_NAME: String = "one.wabbit.typeclass.UnsafeAssertNotNullable"
internal const val IS_TYPECLASS_INSTANCE_FQ_NAME: String = "one.wabbit.typeclass.IsTypeclassInstance"
internal const val IS_TYPECLASS_INSTANCE_PROOF_FQ_NAME: String = "one.wabbit.typeclass.UnsafeAssertIsTypeclassInstance"
internal const val SAME_TYPE_CONSTRUCTOR_FQ_NAME: String = "one.wabbit.typeclass.SameTypeConstructor"
internal const val SAME_TYPE_CONSTRUCTOR_PROOF_FQ_NAME: String = "one.wabbit.typeclass.UnsafeAssertSameTypeConstructor"
internal const val KNOWN_TYPE_FQ_NAME: String = "one.wabbit.typeclass.KnownType"
internal const val KNOWN_TYPE_FACTORY_FQ_NAME: String = "one.wabbit.typeclass.knownType"
internal const val TYPE_ID_FQ_NAME: String = "one.wabbit.typeclass.TypeId"
internal const val TYPE_ID_FACTORY_FQ_NAME: String = "one.wabbit.typeclass.typeId"
internal const val KCLASS_FQ_NAME: String = "kotlin.reflect.KClass"
internal const val KTYPE_FQ_NAME: String = "kotlin.reflect.KType"
internal const val KSERIALIZER_FQ_NAME: String = "kotlinx.serialization.KSerializer"
internal const val SERIALIZABLE_ANNOTATION_FQ_NAME: String = "kotlinx.serialization.Serializable"

internal val TYPECLASS_ANNOTATION_CLASS_ID: ClassId = ClassId.topLevel(FqName(TYPECLASS_ANNOTATION_FQ_NAME))
internal val INSTANCE_ANNOTATION_CLASS_ID: ClassId = ClassId.topLevel(FqName(INSTANCE_ANNOTATION_FQ_NAME))
internal val DERIVE_ANNOTATION_CLASS_ID: ClassId = ClassId.topLevel(FqName(DERIVE_ANNOTATION_FQ_NAME))
internal val DERIVE_VIA_ANNOTATION_CLASS_ID: ClassId = ClassId.topLevel(FqName(DERIVE_VIA_ANNOTATION_FQ_NAME))
internal val DERIVE_EQUIV_ANNOTATION_CLASS_ID: ClassId = ClassId.topLevel(FqName(DERIVE_EQUIV_ANNOTATION_FQ_NAME))
internal val DERIVE_VIA_ANNOTATION_CONTAINER_CLASS_ID: ClassId =
    DERIVE_VIA_ANNOTATION_CLASS_ID.createNestedClassId(Name.identifier("Container"))
internal val DERIVE_EQUIV_ANNOTATION_CONTAINER_CLASS_ID: ClassId =
    DERIVE_EQUIV_ANNOTATION_CLASS_ID.createNestedClassId(Name.identifier("Container"))
internal val GADT_DERIVATION_POLICY_ANNOTATION_CLASS_ID: ClassId =
    ClassId.topLevel(FqName(GADT_DERIVATION_POLICY_ANNOTATION_FQ_NAME))
internal val GADT_DERIVATION_MODE_CLASS_ID: ClassId = ClassId.topLevel(FqName(GADT_DERIVATION_MODE_FQ_NAME))
internal val GENERATED_WRAPPER_ANNOTATION_CLASS_ID: ClassId = ClassId.topLevel(FqName(GENERATED_WRAPPER_ANNOTATION_FQ_NAME))
internal val GENERATED_INSTANCE_ANNOTATION_CLASS_ID: ClassId = ClassId.topLevel(FqName(GENERATED_INSTANCE_ANNOTATION_FQ_NAME))
internal val GENERATED_INSTANCE_ANNOTATION_CONTAINER_CLASS_ID: ClassId =
    GENERATED_INSTANCE_ANNOTATION_CLASS_ID.createNestedClassId(Name.identifier("Container"))
internal val PRODUCT_TYPECLASS_DERIVER_CLASS_ID: ClassId = ClassId.topLevel(FqName(PRODUCT_TYPECLASS_DERIVER_FQ_NAME))
internal val TYPECLASS_DERIVER_CLASS_ID: ClassId = ClassId.topLevel(FqName(TYPECLASS_DERIVER_FQ_NAME))
internal val EQUIV_CLASS_ID: ClassId = ClassId.topLevel(FqName(EQUIV_FQ_NAME))
internal val ISO_CLASS_ID: ClassId = ClassId.topLevel(FqName(ISO_FQ_NAME))
internal val PRODUCT_FIELD_METADATA_CLASS_ID: ClassId = ClassId.topLevel(FqName(PRODUCT_FIELD_METADATA_FQ_NAME))
internal val PRODUCT_TYPECLASS_METADATA_CLASS_ID: ClassId = ClassId.topLevel(FqName(PRODUCT_TYPECLASS_METADATA_FQ_NAME))
internal val SUM_CASE_METADATA_CLASS_ID: ClassId = ClassId.topLevel(FqName(SUM_CASE_METADATA_FQ_NAME))
internal val SUM_TYPECLASS_METADATA_CLASS_ID: ClassId = ClassId.topLevel(FqName(SUM_TYPECLASS_METADATA_FQ_NAME))
internal val ENUM_ENTRY_METADATA_CLASS_ID: ClassId = ClassId.topLevel(FqName(ENUM_ENTRY_METADATA_FQ_NAME))
internal val ENUM_TYPECLASS_METADATA_CLASS_ID: ClassId = ClassId.topLevel(FqName(ENUM_TYPECLASS_METADATA_FQ_NAME))
internal val RECURSIVE_TYPECLASS_INSTANCE_CELL_CLASS_ID: ClassId =
    ClassId.topLevel(FqName(RECURSIVE_TYPECLASS_INSTANCE_CELL_FQ_NAME))
internal val SAME_CLASS_ID: ClassId = ClassId.topLevel(FqName(SAME_FQ_NAME))
internal val SAME_PROOF_CLASS_ID: ClassId = ClassId.topLevel(FqName(SAME_PROOF_FQ_NAME))
internal val NOT_SAME_CLASS_ID: ClassId = ClassId.topLevel(FqName(NOT_SAME_FQ_NAME))
internal val NOT_SAME_PROOF_CLASS_ID: ClassId = ClassId.topLevel(FqName(NOT_SAME_PROOF_FQ_NAME))
internal val SUBTYPE_CLASS_ID: ClassId = ClassId.topLevel(FqName(SUBTYPE_FQ_NAME))
internal val SUBTYPE_PROOF_CLASS_ID: ClassId = ClassId.topLevel(FqName(SUBTYPE_PROOF_FQ_NAME))
internal val STRICT_SUBTYPE_CLASS_ID: ClassId = ClassId.topLevel(FqName(STRICT_SUBTYPE_FQ_NAME))
internal val STRICT_SUBTYPE_PROOF_CLASS_ID: ClassId = ClassId.topLevel(FqName(STRICT_SUBTYPE_PROOF_FQ_NAME))
internal val NULLABLE_CLASS_ID: ClassId = ClassId.topLevel(FqName(NULLABLE_FQ_NAME))
internal val NULLABLE_PROOF_CLASS_ID: ClassId = ClassId.topLevel(FqName(NULLABLE_PROOF_FQ_NAME))
internal val NOT_NULLABLE_CLASS_ID: ClassId = ClassId.topLevel(FqName(NOT_NULLABLE_FQ_NAME))
internal val NOT_NULLABLE_PROOF_CLASS_ID: ClassId = ClassId.topLevel(FqName(NOT_NULLABLE_PROOF_FQ_NAME))
internal val IS_TYPECLASS_INSTANCE_CLASS_ID: ClassId = ClassId.topLevel(FqName(IS_TYPECLASS_INSTANCE_FQ_NAME))
internal val IS_TYPECLASS_INSTANCE_PROOF_CLASS_ID: ClassId = ClassId.topLevel(FqName(IS_TYPECLASS_INSTANCE_PROOF_FQ_NAME))
internal val SAME_TYPE_CONSTRUCTOR_CLASS_ID: ClassId = ClassId.topLevel(FqName(SAME_TYPE_CONSTRUCTOR_FQ_NAME))
internal val SAME_TYPE_CONSTRUCTOR_PROOF_CLASS_ID: ClassId = ClassId.topLevel(FqName(SAME_TYPE_CONSTRUCTOR_PROOF_FQ_NAME))
internal val KNOWN_TYPE_CLASS_ID: ClassId = ClassId.topLevel(FqName(KNOWN_TYPE_FQ_NAME))
internal val TYPE_ID_CLASS_ID: ClassId = ClassId.topLevel(FqName(TYPE_ID_FQ_NAME))
internal val STRING_CLASS_ID: ClassId = ClassId.topLevel(FqName("kotlin.String"))
internal val KCLASS_CLASS_ID: ClassId = ClassId.topLevel(FqName(KCLASS_FQ_NAME))
internal val KTYPE_CLASS_ID: ClassId = ClassId.topLevel(FqName(KTYPE_FQ_NAME))
internal val KSERIALIZER_CLASS_ID: ClassId = ClassId.topLevel(FqName(KSERIALIZER_FQ_NAME))
internal val SERIALIZABLE_ANNOTATION_CLASS_ID: ClassId = ClassId.topLevel(FqName(SERIALIZABLE_ANNOTATION_FQ_NAME))
internal val KNOWN_TYPE_FACTORY_CALLABLE_ID: CallableId =
    CallableId(FqName("one.wabbit.typeclass"), Name.identifier("knownType"))
internal val TYPE_ID_FACTORY_CALLABLE_ID: CallableId =
    CallableId(FqName("one.wabbit.typeclass"), Name.identifier("typeId"))
internal val BUILTIN_SERIALIZABLE_CLASSIFIER_IDS: Set<String> =
    setOf(
        ClassId.topLevel(FqName("kotlin.Boolean")).asString(),
        ClassId.topLevel(FqName("kotlin.Byte")).asString(),
        ClassId.topLevel(FqName("kotlin.Char")).asString(),
        ClassId.topLevel(FqName("kotlin.Double")).asString(),
        ClassId.topLevel(FqName("kotlin.Float")).asString(),
        ClassId.topLevel(FqName("kotlin.Int")).asString(),
        ClassId.topLevel(FqName("kotlin.Long")).asString(),
        ClassId.topLevel(FqName("kotlin.Short")).asString(),
        ClassId.topLevel(FqName("kotlin.String")).asString(),
        ClassId.topLevel(FqName("kotlin.Unit")).asString(),
        ClassId.topLevel(FqName("kotlin.Pair")).asString(),
        ClassId.topLevel(FqName("kotlin.Triple")).asString(),
        ClassId.topLevel(FqName("kotlin.Array")).asString(),
        ClassId.topLevel(FqName("kotlin.collections.List")).asString(),
        ClassId.topLevel(FqName("kotlin.collections.MutableList")).asString(),
        ClassId.topLevel(FqName("kotlin.collections.Set")).asString(),
        ClassId.topLevel(FqName("kotlin.collections.MutableSet")).asString(),
        ClassId.topLevel(FqName("kotlin.collections.Map")).asString(),
        ClassId.topLevel(FqName("kotlin.collections.MutableMap")).asString(),
    )
