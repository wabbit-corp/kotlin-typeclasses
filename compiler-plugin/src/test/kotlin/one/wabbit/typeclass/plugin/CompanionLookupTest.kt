// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.name.ClassId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompanionLookupTest {
    @Test
    fun returnsDirectCompanionWithoutConsultingNestedLookup() {
        val owner = ClassId.fromString("demo/Box")
        var nestedLookupCalls = 0

        val resolved =
            directOrNestedCompanion(
                owner = owner,
                directCompanion = "direct",
                nestedLookup = {
                    nestedLookupCalls += 1
                    "nested"
                },
            )

        assertEquals("direct", resolved)
        assertEquals(0, nestedLookupCalls)
    }

    @Test
    fun fallsBackToNestedCompanionLookupWhenDirectCompanionIsMissing() {
        val owner = ClassId.fromString("demo/Box")
        var lookedUpCompanionId: ClassId? = null

        val resolved =
            directOrNestedCompanion(
                owner = owner,
                directCompanion = null,
                nestedLookup = { companionId ->
                    lookedUpCompanionId = companionId
                    "nested"
                },
            )

        assertEquals("nested", resolved)
        assertEquals(ClassId.fromString("demo/Box.Companion"), lookedUpCompanionId)
    }

    @Test
    fun returnsNullWhenNeitherDirectNorNestedCompanionExists() {
        val owner = ClassId.fromString("demo/Box")

        val resolved =
            directOrNestedCompanion(
                owner = owner,
                directCompanion = null,
                nestedLookup = { null },
            )

        assertNull(resolved)
    }
}
