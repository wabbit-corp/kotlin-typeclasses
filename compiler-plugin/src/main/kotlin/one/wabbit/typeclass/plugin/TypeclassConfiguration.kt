// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.name.ClassId

internal enum class TypeclassBuiltinMode(val cliValue: String) {
    DISABLED("disabled"),
    ENABLED("enabled");

    companion object {
        fun parse(optionName: String, value: String): TypeclassBuiltinMode =
            entries.firstOrNull { mode -> mode.cliValue == value.lowercase() }
                ?: throw IllegalArgumentException(
                    "Unknown value '$value' for option '$optionName'. Expected one of: ${entries.joinToString { it.cliValue }}"
                )
    }
}

internal enum class TypeclassTraceMode(val cliValue: String) {
    INHERIT("inherit"),
    DISABLED("disabled"),
    FAILURES("failures"),
    FAILURES_AND_ALTERNATIVES("failures-and-alternatives"),
    ALL("all"),
    ALL_AND_ALTERNATIVES("all-and-alternatives");

    val tracesSuccesses: Boolean
        get() = this == ALL || this == ALL_AND_ALTERNATIVES

    val explainsAlternatives: Boolean
        get() = this == FAILURES_AND_ALTERNATIVES || this == ALL_AND_ALTERNATIVES

    companion object {
        fun parse(optionName: String, value: String): TypeclassTraceMode =
            entries.firstOrNull { mode -> mode.cliValue == value.lowercase() }
                ?: throw IllegalArgumentException(
                    "Unknown value '$value' for option '$optionName'. Expected one of: ${entries.joinToString { it.cliValue }}"
                )
    }
}

internal data class TypeclassConfiguration(
    val builtinKClassTypeclass: TypeclassBuiltinMode = TypeclassBuiltinMode.DISABLED,
    val builtinKSerializerTypeclass: TypeclassBuiltinMode = TypeclassBuiltinMode.DISABLED,
    val traceMode: TypeclassTraceMode = TypeclassTraceMode.DISABLED,
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
    val TRACE_MODE: CompilerConfigurationKey<TypeclassTraceMode> =
        CompilerConfigurationKey.create("typeclass trace mode")
}

internal fun CompilerConfiguration.toTypeclassConfiguration(): TypeclassConfiguration =
    TypeclassConfiguration(
        builtinKClassTypeclass =
            get(TypeclassConfigurationKeys.BUILTIN_KCLASS_TYPECLASS)
                ?: TypeclassConfiguration().builtinKClassTypeclass,
        builtinKSerializerTypeclass =
            get(TypeclassConfigurationKeys.BUILTIN_KSERIALIZER_TYPECLASS)
                ?: TypeclassConfiguration().builtinKSerializerTypeclass,
        traceMode = get(TypeclassConfigurationKeys.TRACE_MODE) ?: TypeclassConfiguration().traceMode,
    )
