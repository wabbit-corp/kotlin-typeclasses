@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

internal class TypeclassCompilerPluginRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = TYPECLASS_PLUGIN_ID
    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val sharedState = TypeclassPluginSharedState()
        FirExtensionRegistrarAdapter.registerExtension(TypeclassFirExtensionRegistrar(sharedState))
        IrGenerationExtension.registerExtension(TypeclassIrGenerationExtension(sharedState))
    }
}
