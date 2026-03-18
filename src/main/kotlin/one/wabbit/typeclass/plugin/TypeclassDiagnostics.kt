package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers

internal object TypeclassErrors : KtDiagnosticsContainer() {
    val INVALID_INSTANCE_DECLARATION =
        KtDiagnosticFactory1<String>(
            "INVALID_INSTANCE_DECLARATION",
            Severity.ERROR,
            SourceElementPositioningStrategies.DEFAULT,
            PsiElement::class,
            TypeclassErrorMessages,
        )

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = TypeclassErrorMessages
}

internal object TypeclassErrorMessages : BaseDiagnosticRendererFactory() {
    override val MAP by KtDiagnosticFactoryToRendererMap("TypeclassErrors") { map ->
        map.put(
            TypeclassErrors.INVALID_INSTANCE_DECLARATION,
            "Invalid @Instance declaration: {0}",
            CommonRenderers.STRING,
        )
    }
}
