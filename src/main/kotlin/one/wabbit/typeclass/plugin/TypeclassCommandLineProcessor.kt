@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.config.CompilerConfiguration

internal class TypeclassCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = TYPECLASS_PLUGIN_ID

    override val pluginOptions: Collection<AbstractCliOption> = emptyList<CliOption>()

    override fun processOption(
        option: AbstractCliOption,
        value: String,
        configuration: CompilerConfiguration,
    ): Unit = throw CliOptionProcessingException("Unknown option ${option.optionName}")
}
