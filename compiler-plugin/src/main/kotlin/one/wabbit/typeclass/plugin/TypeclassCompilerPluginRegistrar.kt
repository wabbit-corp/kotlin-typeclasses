// SPDX-License-Identifier: AGPL-3.0-or-later

@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.jvm.config.JvmClasspathRoot
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

internal class TypeclassCompilerPluginRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = TYPECLASS_PLUGIN_ID
    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val classpathRoots =
            configuration.getList(CLIConfigurationKeys.CONTENT_ROOTS).mapNotNull { contentRoot ->
                (contentRoot as? JvmClasspathRoot)?.file
            }
        val sharedState =
            TypeclassPluginSharedState(
                configuration = configuration.toTypeclassConfiguration(),
                binaryClassPathRoots = classpathRoots,
            )
        FirExtensionRegistrarAdapter.registerExtension(TypeclassFirExtensionRegistrar(sharedState))
        IrGenerationExtension.Companion.registerExtension(
            TypeclassIrGenerationExtension(sharedState)
        )
    }
}
