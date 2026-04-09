// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.typeclass.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeneratedIrRuntimeFailuresTest {
    @Test
    fun `product constructor arity message points at stale metadata or invalid callers`() {
        val message =
            productConstructorArityRuntimeMessage(
                className = "demo.Box",
                expectedArguments = 2,
            )

        assertEquals(
            "Internal typeclass derivation error: generated product constructor for demo.Box expected 2 arguments. This usually means stale generated metadata or an invalid runtime caller.",
            message,
        )
    }

    @Test
    fun `sum transport fallback message points at stale metadata or abi drift`() {
        val message = impossibleSumTransportRuntimeMessage("demo.Source", "demo.Target")

        assertEquals(
            "Internal typeclass transport error: generated sum transport from demo.Source to demo.Target reached an impossible fallback path. This usually means stale generated metadata or ABI drift.",
            message,
        )
    }

    @Test
    fun `enum resolver fallback messages are explicit internal errors`() {
        val ordinalMessage = impossibleEnumOrdinalResolverRuntimeMessage("demo.Color")
        val valueMessage = impossibleEnumValueResolverRuntimeMessage("demo.Color")

        assertTrue(ordinalMessage.contains("generated enum ordinal resolver for demo.Color"))
        assertTrue(valueMessage.contains("generated enum value resolver for demo.Color"))
        assertTrue(ordinalMessage.contains("stale generated metadata or ABI drift"))
        assertTrue(valueMessage.contains("stale generated metadata or ABI drift"))
    }
}
