// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.descriptors.Visibility

internal enum class TransportSyntheticAccessContext {
    SAME_MODULE_SOURCE,
    DEPENDENCY_BINARY,
}

internal enum class TransportSyntheticVisibility {
    PUBLIC,
    INTERNAL,
    INACCESSIBLE,
}

internal fun TransportSyntheticAccessContext.allowsTransportVisibility(
    visibility: TransportSyntheticVisibility,
): Boolean =
    when (this) {
        TransportSyntheticAccessContext.SAME_MODULE_SOURCE ->
            visibility == TransportSyntheticVisibility.PUBLIC ||
                visibility == TransportSyntheticVisibility.INTERNAL

        TransportSyntheticAccessContext.DEPENDENCY_BINARY ->
            visibility == TransportSyntheticVisibility.PUBLIC
    }

internal fun Visibility.toTransportSyntheticVisibility(): TransportSyntheticVisibility =
    transportSyntheticVisibility(
        rendered = renderTransportVisibility(),
        isPublic = this == org.jetbrains.kotlin.descriptors.Visibilities.Public,
        isInternal = this == org.jetbrains.kotlin.descriptors.Visibilities.Internal,
    )

internal fun DescriptorVisibility.toTransportSyntheticVisibility(): TransportSyntheticVisibility =
    transportSyntheticVisibility(
        rendered = renderTransportVisibility(),
        isPublic = this == org.jetbrains.kotlin.descriptors.DescriptorVisibilities.PUBLIC,
        isInternal = this == org.jetbrains.kotlin.descriptors.DescriptorVisibilities.INTERNAL,
    )

private fun Visibility.renderTransportVisibility(): String =
    "$this ${externalDisplayName}".lowercase()

private fun DescriptorVisibility.renderTransportVisibility(): String =
    "$this ${externalDisplayName}".lowercase()

private fun transportSyntheticVisibility(
    rendered: String,
    isPublic: Boolean,
    isInternal: Boolean,
): TransportSyntheticVisibility =
    when {
        isPublic || (rendered.contains("public") && !rendered.contains("package")) ->
            TransportSyntheticVisibility.PUBLIC

        isInternal || rendered.contains("internal") || rendered.contains("package") ->
            TransportSyntheticVisibility.INTERNAL

        rendered.contains("private") ||
            rendered.contains("protected") ||
            rendered.contains("local") ||
            rendered.contains("invisible") ->
            TransportSyntheticVisibility.INACCESSIBLE

        else -> TransportSyntheticVisibility.INACCESSIBLE
    }
