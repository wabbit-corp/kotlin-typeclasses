package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.name.ClassId

internal enum class TypeclassBuiltinMode(
    val cliValue: String,
) {
    DISABLED("disabled"),
    ENABLED("enabled");

    companion object {
        fun parse(
            optionName: String,
            value: String,
        ): TypeclassBuiltinMode =
            entries.firstOrNull { mode -> mode.cliValue == value }
                ?: throw IllegalArgumentException(
                    "Unknown value '$value' for option '$optionName'. Expected one of: ${entries.joinToString { it.cliValue }}",
                )
    }
}

internal data class TypeclassConfiguration(
    val builtinKClassTypeclass: TypeclassBuiltinMode = TypeclassBuiltinMode.DISABLED,
    val builtinKSerializerTypeclass: TypeclassBuiltinMode = TypeclassBuiltinMode.DISABLED,
) {
    fun isBuiltinTypeclass(classId: ClassId): Boolean =
        when (classId) {
            KCLASS_CLASS_ID -> builtinKClassTypeclass == TypeclassBuiltinMode.ENABLED
            KSERIALIZER_CLASS_ID -> builtinKSerializerTypeclass == TypeclassBuiltinMode.ENABLED
            else -> false
        }
}

internal object TypeclassConfigurationKeys {
    val BUILTIN_KCLASS_TYPECLASS: CompilerConfigurationKey<TypeclassBuiltinMode> =
        CompilerConfigurationKey.create("typeclass builtin KClass mode")
    val BUILTIN_KSERIALIZER_TYPECLASS: CompilerConfigurationKey<TypeclassBuiltinMode> =
        CompilerConfigurationKey.create("typeclass builtin KSerializer mode")
}

internal fun CompilerConfiguration.toTypeclassConfiguration(): TypeclassConfiguration =
    TypeclassConfiguration(
        builtinKClassTypeclass =
            get(TypeclassConfigurationKeys.BUILTIN_KCLASS_TYPECLASS)
                ?: TypeclassConfiguration().builtinKClassTypeclass,
        builtinKSerializerTypeclass =
            get(TypeclassConfigurationKeys.BUILTIN_KSERIALIZER_TYPECLASS)
                ?: TypeclassConfiguration().builtinKSerializerTypeclass,
    )
