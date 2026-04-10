// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

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
            cache.getOrPut(sharedDeriveMethod, "demo.Parent", "demo.Parent.Companion") {
                validations += 1
                true
            }
        val childResult =
            cache.getOrPut(sharedDeriveMethod, "demo.Child", "demo.Child.Companion") {
                validations += 1
                false
            }
        val parentResultAgain =
            cache.getOrPut(sharedDeriveMethod, "demo.Parent", "demo.Parent.Companion") {
                validations += 1
                false
            }

        assertTrue(parentResult)
        assertFalse(childResult)
        assertTrue(parentResultAgain)
        assertEquals(2, validations)
    }

    @Test
    fun sameMethodWithDifferentDeriverCompanionsDoesNotReuseCachedResult() {
        val cache = DeriverReturnValidationCache<Any>()
        val inheritedDeriveMethod = Any()
        var validations = 0

        val firstCompanionResult =
            cache.getOrPut(inheritedDeriveMethod, "demo.Show", "demo.First.Companion") {
                validations += 1
                true
            }
        val secondCompanionResult =
            cache.getOrPut(inheritedDeriveMethod, "demo.Show", "demo.Second.Companion") {
                validations += 1
                false
            }
        val firstCompanionResultAgain =
            cache.getOrPut(inheritedDeriveMethod, "demo.Show", "demo.First.Companion") {
                validations += 1
                false
            }

        assertTrue(firstCompanionResult)
        assertFalse(secondCompanionResult)
        assertTrue(firstCompanionResultAgain)
        assertEquals(2, validations)
    }
}
