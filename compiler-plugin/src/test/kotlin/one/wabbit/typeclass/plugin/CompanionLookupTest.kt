// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.typeclass.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.jetbrains.kotlin.name.ClassId

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
        var nestedLookupCalls = 0

        val resolved =
            directOrNestedCompanion(
                owner = owner,
                directCompanion = null,
                nestedLookup = {
                    nestedLookupCalls += 1
                    "nested"
                },
            )

        assertEquals("nested", resolved)
        assertEquals(1, nestedLookupCalls)
    }

    @Test
    fun returnsNullWhenNeitherDirectNorNestedCompanionExists() {
        val owner = ClassId.fromString("demo/Box")

        val resolved =
            directOrNestedCompanion(owner = owner, directCompanion = null, nestedLookup = { null })

        assertNull(resolved)
    }
}
