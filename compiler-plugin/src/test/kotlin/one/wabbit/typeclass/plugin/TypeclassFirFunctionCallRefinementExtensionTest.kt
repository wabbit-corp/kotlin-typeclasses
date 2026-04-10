// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.typeclass.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

class TypeclassFirFunctionCallRefinementExtensionTest {
    @Test
    fun `refinement call keys stay distinct across overload candidates at the same call site`() {
        val callableId = CallableId(FqName("demo"), Name.identifier("keep"))
        val sharedSource = Any()
        val firstOverload = FirNamedFunctionSymbol(callableId)
        val secondOverload = FirNamedFunctionSymbol(callableId)

        assertNotEquals(
            refinementCallKey(sharedSource, firstOverload),
            refinementCallKey(sharedSource, secondOverload),
        )
    }

    @Test
    fun `refinement call keys stay stable for the same candidate and call site`() {
        val callableId = CallableId(FqName("demo"), Name.identifier("keep"))
        val sharedSource = Any()
        val overload = FirNamedFunctionSymbol(callableId)

        assertEquals(
            refinementCallKey(sharedSource, overload),
            refinementCallKey(sharedSource, overload),
        )
    }
}
