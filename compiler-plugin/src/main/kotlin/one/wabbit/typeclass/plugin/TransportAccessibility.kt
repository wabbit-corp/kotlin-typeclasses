// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.load.java.JavaDescriptorVisibilities

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
    when (this) {
        Visibilities.Public -> TransportSyntheticVisibility.PUBLIC
        Visibilities.Internal -> TransportSyntheticVisibility.INTERNAL
        Visibilities.Private,
        Visibilities.PrivateToThis,
        Visibilities.Protected,
        Visibilities.Local,
        Visibilities.Inherited,
        Visibilities.InvisibleFake,
        Visibilities.Unknown,
        -> TransportSyntheticVisibility.INACCESSIBLE

        else -> TransportSyntheticVisibility.INACCESSIBLE
    }

internal fun DescriptorVisibility.toTransportSyntheticVisibility(): TransportSyntheticVisibility =
    when (this) {
        DescriptorVisibilities.PUBLIC -> TransportSyntheticVisibility.PUBLIC
        DescriptorVisibilities.INTERNAL -> TransportSyntheticVisibility.INTERNAL
        DescriptorVisibilities.PRIVATE,
        DescriptorVisibilities.PRIVATE_TO_THIS,
        DescriptorVisibilities.PROTECTED,
        DescriptorVisibilities.LOCAL,
        DescriptorVisibilities.INHERITED,
        DescriptorVisibilities.INVISIBLE_FAKE,
        DescriptorVisibilities.UNKNOWN,
        JavaDescriptorVisibilities.PACKAGE_VISIBILITY,
        JavaDescriptorVisibilities.PROTECTED_STATIC_VISIBILITY,
        JavaDescriptorVisibilities.PROTECTED_AND_PACKAGE,
        -> TransportSyntheticVisibility.INACCESSIBLE

        else -> TransportSyntheticVisibility.INACCESSIBLE
    }
