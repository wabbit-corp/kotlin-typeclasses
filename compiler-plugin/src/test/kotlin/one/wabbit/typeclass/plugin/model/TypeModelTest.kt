// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.typeclass.plugin.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypeModelTest {
    @Test
    fun `provable nullable respects nullable type variables`() {
        assertTrue(
            TcType.Variable(id = "test:T", displayName = "T", isNullable = true)
                .isProvablyNullable()
        )
        assertFalse(
            TcType.Variable(id = "test:T", displayName = "T", isNullable = false)
                .isProvablyNullable()
        )
    }
}
