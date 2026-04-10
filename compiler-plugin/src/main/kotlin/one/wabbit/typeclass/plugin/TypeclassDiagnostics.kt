// SPDX-License-Identifier: AGPL-3.0-or-later

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
    val CANNOT_DERIVE =
        KtDiagnosticFactory1<String>(
            TypeclassDiagnosticIds.CANNOT_DERIVE,
            Severity.ERROR,
            SourceElementPositioningStrategies.DEFAULT,
            PsiElement::class,
            TypeclassErrorMessages,
        )

    val INVALID_INSTANCE_DECLARATION =
        KtDiagnosticFactory1<String>(
            TypeclassDiagnosticIds.INVALID_INSTANCE_DECL,
            Severity.ERROR,
            SourceElementPositioningStrategies.DEFAULT,
            PsiElement::class,
            TypeclassErrorMessages,
        )

    val INVALID_EQUIV_DECLARATION =
        KtDiagnosticFactory1<String>(
            TypeclassDiagnosticIds.INVALID_EQUIV_DECL,
            Severity.ERROR,
            SourceElementPositioningStrategies.DEFAULT,
            PsiElement::class,
            TypeclassErrorMessages,
        )

    val NO_CONTEXT_ARGUMENT =
        KtDiagnosticFactory1<String>(
            TypeclassDiagnosticIds.NO_CONTEXT_ARGUMENT,
            Severity.ERROR,
            SourceElementPositioningStrategies.DEFAULT,
            PsiElement::class,
            TypeclassErrorMessages,
        )

    val AMBIGUOUS_INSTANCE =
        KtDiagnosticFactory1<String>(
            TypeclassDiagnosticIds.AMBIGUOUS_INSTANCE,
            Severity.ERROR,
            SourceElementPositioningStrategies.DEFAULT,
            PsiElement::class,
            TypeclassErrorMessages,
        )

    val INVALID_BUILTIN_EVIDENCE =
        KtDiagnosticFactory1<String>(
            TypeclassDiagnosticIds.INVALID_BUILTIN_EVIDENCE,
            Severity.ERROR,
            SourceElementPositioningStrategies.DEFAULT,
            PsiElement::class,
            TypeclassErrorMessages,
        )

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = TypeclassErrorMessages
}

internal object TypeclassErrorMessages : BaseDiagnosticRendererFactory() {
    override val MAP by
        KtDiagnosticFactoryToRendererMap("TypeclassErrors") { map ->
            map.put(
                TypeclassErrors.CANNOT_DERIVE,
                "[${TypeclassDiagnosticIds.CANNOT_DERIVE}] {0}",
                CommonRenderers.STRING,
            )
            map.put(
                TypeclassErrors.INVALID_INSTANCE_DECLARATION,
                "[${TypeclassDiagnosticIds.INVALID_INSTANCE_DECL}] {0}",
                CommonRenderers.STRING,
            )
            map.put(
                TypeclassErrors.INVALID_EQUIV_DECLARATION,
                "[${TypeclassDiagnosticIds.INVALID_EQUIV_DECL}] {0}",
                CommonRenderers.STRING,
            )
            map.put(
                TypeclassErrors.NO_CONTEXT_ARGUMENT,
                "[${TypeclassDiagnosticIds.NO_CONTEXT_ARGUMENT}] {0}",
                CommonRenderers.STRING,
            )
            map.put(
                TypeclassErrors.AMBIGUOUS_INSTANCE,
                "[${TypeclassDiagnosticIds.AMBIGUOUS_INSTANCE}] {0}",
                CommonRenderers.STRING,
            )
            map.put(
                TypeclassErrors.INVALID_BUILTIN_EVIDENCE,
                "[${TypeclassDiagnosticIds.INVALID_BUILTIN_EVIDENCE}] {0}",
                CommonRenderers.STRING,
            )
        }
}
