// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.typeclass.plugin

import kotlin.test.Test
import kotlin.test.assertEquals

class TypeclassFirCallTypeInferenceTest {
    private data class FakeParameter(
        val name: String,
        val isVararg: Boolean = false,
    )

    @Test
    fun `fallback mapping keeps assigning repeated positional arguments to vararg parameters`() {
        val parameters =
            listOf(
                FakeParameter("head"),
                FakeParameter("values", isVararg = true),
                FakeParameter("tail"),
            )

        val mapping =
            buildNamedAndPositionalArgumentMapping(
                arguments =
                    listOf(
                        null to "first",
                        null to "second",
                        null to "third",
                        "tail" to "tail-arg",
                    ),
                parameters = parameters,
                parameterName = FakeParameter::name,
                isVararg = FakeParameter::isVararg,
            )

        assertEquals(
            listOf("head", "values", "values", "tail"),
            mapping.values.map(FakeParameter::name),
        )
    }
}
