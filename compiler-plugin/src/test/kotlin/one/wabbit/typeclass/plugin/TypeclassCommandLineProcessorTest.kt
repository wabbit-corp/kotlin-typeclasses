// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.typeclass.plugin

import java.lang.reflect.InvocationTargetException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.config.CompilerConfiguration

class TypeclassCommandLineProcessorTest {
    private val processor = TypeclassCommandLineProcessor()

    @Test
    fun `builtin mode parsing is case insensitive`() {
        val configuration = newCompilerConfiguration()

        processor.invokeProcessOption(
            option = processor.option("builtinKClassTypeclass"),
            value = "ENABLED",
            configuration = configuration,
        )

        assertEquals(
            TypeclassBuiltinMode.ENABLED,
            configuration.toTypeclassConfiguration().builtinKClassTypeclass,
        )
    }

    @Test
    fun `duplicate cli options are rejected explicitly`() {
        val configuration = newCompilerConfiguration()
        val option = processor.option("typeclassTraceMode")

        processor.invokeProcessOption(
            option = option,
            value = "failures",
            configuration = configuration,
        )

        val error =
            assertFailsWith<CliOptionProcessingException> {
                processor.invokeProcessOption(
                    option = option,
                    value = "all",
                    configuration = configuration,
                )
            }

        assertEquals("Option 'typeclassTraceMode' may be specified at most once.", error.message)
    }

    @Test
    fun `plugin options do not advertise multiple occurrences`() {
        assertFalse(processor.option("builtinKClassTypeclass").allowMultipleOccurrences)
        assertFalse(processor.option("builtinKSerializerTypeclass").allowMultipleOccurrences)
        assertFalse(processor.option("typeclassTraceMode").allowMultipleOccurrences)
    }
}

private fun TypeclassCommandLineProcessor.option(name: String) =
    pluginOptions.single { option -> option.optionName == name }

private fun TypeclassCommandLineProcessor.invokeProcessOption(
    option: org.jetbrains.kotlin.compiler.plugin.AbstractCliOption,
    value: String,
    configuration: CompilerConfiguration,
) {
    try {
        javaClass
            .getMethod(
                "processOption",
                org.jetbrains.kotlin.compiler.plugin.AbstractCliOption::class.java,
                String::class.java,
                CompilerConfiguration::class.java,
            )
            .invoke(this, option, value, configuration)
    } catch (error: InvocationTargetException) {
        when (val cause = error.targetException) {
            is RuntimeException -> throw cause
            is Error -> throw cause
            else -> throw error
        }
    }
}

private fun newCompilerConfiguration(): CompilerConfiguration =
    CompilerConfiguration::class.java.getDeclaredConstructor().newInstance()
