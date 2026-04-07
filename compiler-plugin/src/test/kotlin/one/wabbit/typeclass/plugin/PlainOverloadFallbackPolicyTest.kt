// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.typeclass.plugin

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlainOverloadFallbackPolicyTest {
    @Test
    fun genericPlainFallbackCandidatesAreRejectedEvenWhenShapeCouldOtherwiseMatch() {
        assertFalse(
            supportsExactPlainOverloadFallbackTypeParameters(
                callableIsLocal = false,
                calleeDeclaredTypeParameterCount = 0,
                candidateDeclaredTypeParameterCount = 1,
            ),
        )
    }

    @Test
    fun nongenericTopLevelPlainFallbackCandidatesRemainEligible() {
        assertTrue(
            supportsExactPlainOverloadFallbackTypeParameters(
                callableIsLocal = false,
                calleeDeclaredTypeParameterCount = 0,
                candidateDeclaredTypeParameterCount = 0,
            ),
        )
    }

    @Test
    fun genericContextualCalleesNeverUseExactPlainFallback() {
        assertFalse(
            supportsExactPlainOverloadFallbackTypeParameters(
                callableIsLocal = false,
                calleeDeclaredTypeParameterCount = 1,
                candidateDeclaredTypeParameterCount = 0,
            ),
        )
    }
}
