// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.typeclass.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TypeclassDiagnosticNarrativesTest {
    @Test
    fun structuredNarrativesRoundTrip() {
        val narratives =
            listOf(
                cannotDeriveDiagnostic(
                    "@Derive currently only supports typeclasses with exactly one type parameter"
                ),
                invalidInstanceDiagnostic("extension instance functions are not allowed"),
                invalidEquivDiagnostic(
                    "Equiv is compiler-owned; users must not subclass it directly"
                ),
                invalidBuiltinEvidenceDiagnostic(
                    "KSerializer evidence cannot be materialized for this goal"
                ),
            )

        narratives.forEach { narrative ->
            assertEquals(
                narrative,
                parseTypeclassDiagnostic(narrative.render(), narrative.diagnosticId),
            )
            assertEquals(
                narrative,
                parseTypeclassDiagnostic(narrative.renderBody(), narrative.diagnosticId),
            )
        }
    }

    @Test
    fun noContextNarrativesRoundTrip() {
        val narratives =
            listOf(
                TypeclassDiagnostic.NoContextArgument(
                    goal = "demo/Show<demo/Box>",
                    scope = "render",
                ),
                TypeclassDiagnostic.NoContextArgument(
                    goal = "demo/Eq<demo/Box>",
                    scope = "same",
                    recursive = true,
                ),
            )

        narratives.forEach { narrative ->
            assertEquals(
                narrative,
                parseTypeclassDiagnostic(narrative.render(), narrative.diagnosticId),
            )
            assertEquals(
                narrative,
                parseTypeclassDiagnostic(narrative.renderBody(), narrative.diagnosticId),
            )
        }
    }

    @Test
    fun ambiguousNarrativesRoundTripAndNormalizeCandidateOrdering() {
        val narrative =
            TypeclassDiagnostic.AmbiguousInstance(
                goal = "demo/Show<demo/Box>",
                scope = "render",
                candidates = listOf("candidate-b", "candidate-a"),
            )

        val parsed = parseTypeclassDiagnostic(narrative.render(), narrative.diagnosticId)

        assertEquals(
            TypeclassDiagnostic.AmbiguousInstance(
                goal = "demo/Show<demo/Box>",
                scope = "render",
                candidates = listOf("candidate-a", "candidate-b"),
            ),
            parsed,
        )
        assertEquals(
            parsed?.render(),
            parseTypeclassDiagnostic(parsed!!.render(), parsed.diagnosticId)?.render(),
        )
    }

    @Test
    fun parserRejectsNonNarrativeMessages() {
        assertNull(parseTypeclassDiagnostic("backend error: boom"))
        assertNull(parseTypeclassDiagnostic("No context argument for show"))
    }
}
