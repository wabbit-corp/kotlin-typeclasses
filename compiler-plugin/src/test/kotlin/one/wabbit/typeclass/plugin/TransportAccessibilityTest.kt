// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.typeclass.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptorWithVisibility
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.load.java.JavaDescriptorVisibilities
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue

class TransportAccessibilityTest {
    @Test
    fun `java package private visibility is not treated as internal`() {
        val packagePrivate =
            JavaDescriptorVisibilities.PACKAGE_VISIBILITY.toTransportSyntheticVisibility()

        assertEquals(TransportSyntheticVisibility.INACCESSIBLE, packagePrivate)
        assertFalse(
            TransportSyntheticAccessContext.SAME_MODULE_SOURCE.allowsTransportVisibility(
                packagePrivate
            )
        )
        assertFalse(
            TransportSyntheticAccessContext.DEPENDENCY_BINARY.allowsTransportVisibility(
                packagePrivate
            )
        )
    }

    @Test
    fun `kotlin internal visibility remains available to same module source only`() {
        val internal = DescriptorVisibilities.INTERNAL.toTransportSyntheticVisibility()

        assertEquals(TransportSyntheticVisibility.INTERNAL, internal)
        assertTrue(
            TransportSyntheticAccessContext.SAME_MODULE_SOURCE.allowsTransportVisibility(internal)
        )
        assertFalse(
            TransportSyntheticAccessContext.DEPENDENCY_BINARY.allowsTransportVisibility(internal)
        )
    }

    @Test
    fun `unknown descriptor visibilities are inaccessible even if their display text looks public`() {
        val fakePublic =
            object : DescriptorVisibility() {
                override val delegate: Visibility
                    get() = org.jetbrains.kotlin.descriptors.Visibilities.Public

                override fun isVisible(
                    receiver: ReceiverValue?,
                    what: DeclarationDescriptorWithVisibility,
                    from: DeclarationDescriptor,
                    useSpecialRulesForPrivateSealedConstructors: Boolean,
                ): Boolean = false

                override fun mustCheckInImports(): Boolean = false

                override val internalDisplayName: String
                    get() = "fake-public"

                override val externalDisplayName: String
                    get() = "public"

                override fun normalize(): DescriptorVisibility = this
            }

        assertEquals(
            TransportSyntheticVisibility.INACCESSIBLE,
            fakePublic.toTransportSyntheticVisibility(),
        )
    }

    @Test
    fun `unknown raw visibilities are inaccessible even if their display text looks public`() {
        val fakePublic =
            object : Visibility("public", true) {
                override fun mustCheckInImports(): Boolean = false

                override val externalDisplayName: String
                    get() = "public"
            }

        assertEquals(
            TransportSyntheticVisibility.INACCESSIBLE,
            fakePublic.toTransportSyntheticVisibility(),
        )
    }
}
