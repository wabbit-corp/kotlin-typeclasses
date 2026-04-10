// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.typeclass.plugin

import kotlin.test.Test
import kotlin.test.assertTrue

class TypeclassDiagnosticsTest {
    @Test
    fun firDiagnosticContainerExposesTheDocumentedDiagnosticSurface() {
        val fieldNames = TypeclassErrors::class.java.declaredFields.map { it.name }.toSet()

        assertTrue("CANNOT_DERIVE" in fieldNames)
        assertTrue("INVALID_INSTANCE_DECLARATION" in fieldNames)
        assertTrue("INVALID_EQUIV_DECLARATION" in fieldNames)
        assertTrue("NO_CONTEXT_ARGUMENT" in fieldNames)
        assertTrue("AMBIGUOUS_INSTANCE" in fieldNames)
        assertTrue("INVALID_BUILTIN_EVIDENCE" in fieldNames)
    }
}
