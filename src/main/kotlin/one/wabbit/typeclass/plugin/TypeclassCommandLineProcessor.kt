@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.config.CompilerConfiguration

internal class TypeclassCommandLineProcessor : CommandLineProcessor {
    private val builtinKClassTypeclassOption =
        CliOption(
            optionName = "builtinKClassTypeclass",
            valueDescription = "<disabled|enabled>",
            description = "Controls synthetic KClass typeclass evidence generation.",
            required = false,
            allowMultipleOccurrences = true,
        )

    private val builtinKSerializerTypeclassOption =
        CliOption(
            optionName = "builtinKSerializerTypeclass",
            valueDescription = "<disabled|enabled>",
            description = "Controls synthetic KSerializer typeclass evidence generation.",
            required = false,
            allowMultipleOccurrences = true,
        )

    override val pluginId: String = TYPECLASS_PLUGIN_ID

    override val pluginOptions: Collection<AbstractCliOption> =
        listOf(
            builtinKClassTypeclassOption,
            builtinKSerializerTypeclassOption,
        )

    override fun processOption(
        option: AbstractCliOption,
        value: String,
        configuration: CompilerConfiguration,
    ) {
        when (option.optionName) {
            builtinKClassTypeclassOption.optionName ->
                configuration.put(
                    TypeclassConfigurationKeys.BUILTIN_KCLASS_TYPECLASS,
                    parseBuiltinMode(option.optionName, value),
                )

            builtinKSerializerTypeclassOption.optionName ->
                configuration.put(
                    TypeclassConfigurationKeys.BUILTIN_KSERIALIZER_TYPECLASS,
                    parseBuiltinMode(option.optionName, value),
                )

            else -> throw CliOptionProcessingException("Unknown option ${option.optionName}")
        }
    }

    private fun parseBuiltinMode(
        optionName: String,
        value: String,
    ): TypeclassBuiltinMode =
        try {
            TypeclassBuiltinMode.parse(optionName, value)
        } catch (error: IllegalArgumentException) {
            throw CliOptionProcessingException(error.message ?: "Invalid value '$value' for option '$optionName'")
        }
}
