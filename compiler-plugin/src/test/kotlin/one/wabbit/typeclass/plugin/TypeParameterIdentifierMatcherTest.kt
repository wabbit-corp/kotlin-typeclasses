// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.typeclass.plugin

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypeParameterIdentifierMatcherTest {
    @Test
    fun `qualified classifier names do not count as transported type parameter mentions`() {
        assertFalse(
            "T & demo.A"
                .containsStandaloneTypeParameterIdentifier(
                    transportedName = "A",
                    opaqueNames = setOf("T"),
                )
        )
        assertFalse(
            "demo.A.Inner"
                .containsStandaloneTypeParameterIdentifier(
                    transportedName = "A",
                    opaqueNames = emptySet(),
                )
        )
    }

    @Test
    fun `standalone transported type parameter references still match`() {
        assertTrue(
            "T & A"
                .containsStandaloneTypeParameterIdentifier(
                    transportedName = "A",
                    opaqueNames = setOf("T"),
                )
        )
        assertTrue(
            "Map<A, String>"
                .containsStandaloneTypeParameterIdentifier(
                    transportedName = "A",
                    opaqueNames = emptySet(),
                )
        )
    }

    @Test
    fun `unicode and backticked transported type parameter references match`() {
        assertTrue(
            "Map<`alpha beta`, String>"
                .containsStandaloneTypeParameterIdentifier(
                    transportedName = "alpha beta",
                    opaqueNames = emptySet(),
                )
        )
        assertTrue(
            "Α & T"
                .containsStandaloneTypeParameterIdentifier(
                    transportedName = "Α",
                    opaqueNames = setOf("T"),
                )
        )
    }

    @Test
    fun `qualified unicode and backticked names do not count as transported references`() {
        assertFalse(
            "demo.`alpha beta`"
                .containsStandaloneTypeParameterIdentifier(
                    transportedName = "alpha beta",
                    opaqueNames = emptySet(),
                )
        )
        assertFalse(
            "demo.Α.Inner"
                .containsStandaloneTypeParameterIdentifier(
                    transportedName = "Α",
                    opaqueNames = emptySet(),
                )
        )
    }

    @Test
    fun `opaque names remain excluded even when they match textually`() {
        assertFalse(
            "T & Any"
                .containsStandaloneTypeParameterIdentifier(
                    transportedName = "T",
                    opaqueNames = setOf("T"),
                )
        )
    }
}
