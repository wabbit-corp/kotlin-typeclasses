// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.load.java.JavaDescriptorVisibilities
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransportAccessibilityTest {
    @Test
    fun `java package private visibility is not treated as internal`() {
        val packagePrivate = JavaDescriptorVisibilities.PACKAGE_VISIBILITY.toTransportSyntheticVisibility()

        assertEquals(TransportSyntheticVisibility.INACCESSIBLE, packagePrivate)
        assertFalse(
            TransportSyntheticAccessContext.SAME_MODULE_SOURCE.allowsTransportVisibility(packagePrivate),
        )
        assertFalse(
            TransportSyntheticAccessContext.DEPENDENCY_BINARY.allowsTransportVisibility(packagePrivate),
        )
    }

    @Test
    fun `kotlin internal visibility remains available to same module source only`() {
        val internal = DescriptorVisibilities.INTERNAL.toTransportSyntheticVisibility()

        assertEquals(TransportSyntheticVisibility.INTERNAL, internal)
        assertTrue(
            TransportSyntheticAccessContext.SAME_MODULE_SOURCE.allowsTransportVisibility(internal),
        )
        assertFalse(
            TransportSyntheticAccessContext.DEPENDENCY_BINARY.allowsTransportVisibility(internal),
        )
    }
}
