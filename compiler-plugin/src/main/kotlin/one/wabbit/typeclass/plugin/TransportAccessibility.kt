// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.load.java.JavaDescriptorVisibilities

internal enum class TransportSyntheticAccessContext {
    SAME_MODULE_SOURCE,
    DEPENDENCY_BINARY,
}

internal enum class TransportSyntheticVisibility {
    PUBLIC,
    INTERNAL,
    PROTECTED,
    INACCESSIBLE,
}

internal const val DERIVE_VIA_SUBCLASSABLE_TYPECLASS_HEAD_MESSAGE: String =
    "DeriveVia requires an interface typeclass head or a subclassable class head with an accessible zero-arg constructor"

internal fun TransportSyntheticAccessContext.allowsTransportVisibility(
    visibility: TransportSyntheticVisibility
): Boolean =
    when (this) {
        TransportSyntheticAccessContext.SAME_MODULE_SOURCE ->
            visibility == TransportSyntheticVisibility.PUBLIC ||
                visibility == TransportSyntheticVisibility.INTERNAL

        TransportSyntheticAccessContext.DEPENDENCY_BINARY ->
            visibility == TransportSyntheticVisibility.PUBLIC
    }

internal fun TransportSyntheticAccessContext.allowsDeriveViaSuperclassConstructorVisibility(
    visibility: TransportSyntheticVisibility
): Boolean =
    when (this) {
        TransportSyntheticAccessContext.SAME_MODULE_SOURCE ->
            visibility == TransportSyntheticVisibility.PUBLIC ||
                visibility == TransportSyntheticVisibility.INTERNAL ||
                visibility == TransportSyntheticVisibility.PROTECTED

        TransportSyntheticAccessContext.DEPENDENCY_BINARY ->
            visibility == TransportSyntheticVisibility.PUBLIC ||
                visibility == TransportSyntheticVisibility.PROTECTED
    }

internal fun Visibility.toTransportSyntheticVisibility(): TransportSyntheticVisibility =
    when (this) {
        Visibilities.Public -> TransportSyntheticVisibility.PUBLIC
        Visibilities.Internal -> TransportSyntheticVisibility.INTERNAL
        Visibilities.Protected -> TransportSyntheticVisibility.PROTECTED
        Visibilities.Private,
        Visibilities.PrivateToThis,
        Visibilities.Local,
        Visibilities.Inherited,
        Visibilities.InvisibleFake,
        Visibilities.Unknown -> TransportSyntheticVisibility.INACCESSIBLE

        else -> TransportSyntheticVisibility.INACCESSIBLE
    }

internal fun DescriptorVisibility.toTransportSyntheticVisibility(): TransportSyntheticVisibility =
    when (this) {
        DescriptorVisibilities.PUBLIC -> TransportSyntheticVisibility.PUBLIC
        DescriptorVisibilities.INTERNAL -> TransportSyntheticVisibility.INTERNAL
        DescriptorVisibilities.PROTECTED -> TransportSyntheticVisibility.PROTECTED
        DescriptorVisibilities.PRIVATE,
        DescriptorVisibilities.PRIVATE_TO_THIS,
        DescriptorVisibilities.LOCAL,
        DescriptorVisibilities.INHERITED,
        DescriptorVisibilities.INVISIBLE_FAKE,
        DescriptorVisibilities.UNKNOWN,
        JavaDescriptorVisibilities.PACKAGE_VISIBILITY,
        JavaDescriptorVisibilities.PROTECTED_STATIC_VISIBILITY,
        JavaDescriptorVisibilities.PROTECTED_AND_PACKAGE ->
            TransportSyntheticVisibility.INACCESSIBLE

        else -> TransportSyntheticVisibility.INACCESSIBLE
    }
