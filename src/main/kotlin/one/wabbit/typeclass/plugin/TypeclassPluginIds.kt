package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

internal const val TYPECLASS_PLUGIN_ID: String = "one.wabbit.typeclass"
internal const val TYPECLASS_ANNOTATION_FQ_NAME: String = "one.wabbit.typeclass.Typeclass"
internal const val INSTANCE_ANNOTATION_FQ_NAME: String = "one.wabbit.typeclass.Instance"
internal const val DERIVE_ANNOTATION_FQ_NAME: String = "one.wabbit.typeclass.Derive"
internal const val GENERATED_WRAPPER_ANNOTATION_FQ_NAME: String = "one.wabbit.typeclass.GeneratedTypeclassWrapper"
internal const val GENERATED_INSTANCE_ANNOTATION_FQ_NAME: String = "one.wabbit.typeclass.GeneratedTypeclassInstance"
internal const val TYPECLASS_DERIVER_FQ_NAME: String = "one.wabbit.typeclass.TypeclassDeriver"
internal const val PRODUCT_FIELD_METADATA_FQ_NAME: String = "one.wabbit.typeclass.ProductFieldMetadata"
internal const val PRODUCT_TYPECLASS_METADATA_FQ_NAME: String = "one.wabbit.typeclass.ProductTypeclassMetadata"
internal const val SUM_CASE_METADATA_FQ_NAME: String = "one.wabbit.typeclass.SumCaseMetadata"
internal const val SUM_TYPECLASS_METADATA_FQ_NAME: String = "one.wabbit.typeclass.SumTypeclassMetadata"
internal const val RECURSIVE_TYPECLASS_INSTANCE_CELL_FQ_NAME: String = "one.wabbit.typeclass.RecursiveTypeclassInstanceCell"
internal const val KCLASS_FQ_NAME: String = "kotlin.reflect.KClass"
internal const val KSERIALIZER_FQ_NAME: String = "kotlinx.serialization.KSerializer"
internal const val SERIALIZABLE_ANNOTATION_FQ_NAME: String = "kotlinx.serialization.Serializable"

internal val TYPECLASS_ANNOTATION_CLASS_ID: ClassId = ClassId.topLevel(FqName(TYPECLASS_ANNOTATION_FQ_NAME))
internal val INSTANCE_ANNOTATION_CLASS_ID: ClassId = ClassId.topLevel(FqName(INSTANCE_ANNOTATION_FQ_NAME))
internal val DERIVE_ANNOTATION_CLASS_ID: ClassId = ClassId.topLevel(FqName(DERIVE_ANNOTATION_FQ_NAME))
internal val GENERATED_WRAPPER_ANNOTATION_CLASS_ID: ClassId = ClassId.topLevel(FqName(GENERATED_WRAPPER_ANNOTATION_FQ_NAME))
internal val GENERATED_INSTANCE_ANNOTATION_CLASS_ID: ClassId = ClassId.topLevel(FqName(GENERATED_INSTANCE_ANNOTATION_FQ_NAME))
internal val TYPECLASS_DERIVER_CLASS_ID: ClassId = ClassId.topLevel(FqName(TYPECLASS_DERIVER_FQ_NAME))
internal val PRODUCT_FIELD_METADATA_CLASS_ID: ClassId = ClassId.topLevel(FqName(PRODUCT_FIELD_METADATA_FQ_NAME))
internal val PRODUCT_TYPECLASS_METADATA_CLASS_ID: ClassId = ClassId.topLevel(FqName(PRODUCT_TYPECLASS_METADATA_FQ_NAME))
internal val SUM_CASE_METADATA_CLASS_ID: ClassId = ClassId.topLevel(FqName(SUM_CASE_METADATA_FQ_NAME))
internal val SUM_TYPECLASS_METADATA_CLASS_ID: ClassId = ClassId.topLevel(FqName(SUM_TYPECLASS_METADATA_FQ_NAME))
internal val RECURSIVE_TYPECLASS_INSTANCE_CELL_CLASS_ID: ClassId =
    ClassId.topLevel(FqName(RECURSIVE_TYPECLASS_INSTANCE_CELL_FQ_NAME))
internal val KCLASS_CLASS_ID: ClassId = ClassId.topLevel(FqName(KCLASS_FQ_NAME))
internal val KSERIALIZER_CLASS_ID: ClassId = ClassId.topLevel(FqName(KSERIALIZER_FQ_NAME))
internal val SERIALIZABLE_ANNOTATION_CLASS_ID: ClassId = ClassId.topLevel(FqName(SERIALIZABLE_ANNOTATION_FQ_NAME))
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
