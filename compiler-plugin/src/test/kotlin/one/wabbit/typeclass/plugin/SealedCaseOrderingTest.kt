// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.typeclass.plugin

import kotlin.test.Test
import kotlin.test.assertEquals

class SealedCaseOrderingTest {
    @Test
    fun `preserves source declaration order for discovered subclasses`() {
        assertEquals(
            listOf("demo/Zed", "demo/Alpha"),
            stableSealedSubclassIds(
                sourceOrderedSubclassIds = listOf("demo/Zed", "demo/Alpha"),
                discoveredSubclassIds = listOf("demo/Alpha", "demo/Zed"),
            ),
        )
    }

    @Test
    fun `falls back to fq-name sorting for discovered subclasses without source order`() {
        assertEquals(
            listOf("demo/Alpha", "demo/Middle", "demo/Zed"),
            stableSealedSubclassIds(
                sourceOrderedSubclassIds = emptyList(),
                discoveredSubclassIds = listOf("demo/Zed", "demo/Alpha", "demo/Middle"),
            ),
        )
    }

    @Test
    fun `appends undiscovered source gaps in deterministic sorted order`() {
        assertEquals(
            listOf("demo/Zed", "demo/Alpha", "demo/Middle"),
            stableSealedSubclassIds(
                sourceOrderedSubclassIds = listOf("demo/Zed"),
                discoveredSubclassIds = listOf("demo/Middle", "demo/Alpha", "demo/Zed"),
            ),
        )
    }
}
