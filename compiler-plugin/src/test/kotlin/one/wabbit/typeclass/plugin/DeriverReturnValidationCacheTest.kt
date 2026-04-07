// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.typeclass.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeriverReturnValidationCacheTest {
    @Test
    fun sameMethodWithDifferentExpectedTypeclassesDoesNotReuseCachedResult() {
        val cache = DeriverReturnValidationCache<Any>()
        val sharedDeriveMethod = Any()
        var validations = 0

        val parentResult =
            cache.getOrPut(sharedDeriveMethod, "demo.Parent") {
                validations += 1
                true
            }
        val childResult =
            cache.getOrPut(sharedDeriveMethod, "demo.Child") {
                validations += 1
                false
            }
        val parentResultAgain =
            cache.getOrPut(sharedDeriveMethod, "demo.Parent") {
                validations += 1
                false
            }

        assertTrue(parentResult)
        assertFalse(childResult)
        assertTrue(parentResultAgain)
        assertEquals(2, validations)
    }
}
