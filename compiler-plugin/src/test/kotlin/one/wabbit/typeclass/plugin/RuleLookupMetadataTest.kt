// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.typeclass.plugin

import one.wabbit.typeclass.plugin.model.TcType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import kotlin.test.Test
import kotlin.test.assertNotEquals

class RuleLookupMetadataTest {
    @Test
    fun lookupFunctionIdentityIncludesOwnerKey() {
        val callableId = CallableId(FqName("demo"), Name.identifier("show"))
        val shape =
            LookupFunctionShape(
                dispatchReceiver = false,
                extensionReceiverType = null,
                typeParameterCount = 0,
                contextParameterTypes = emptyList(),
                regularParameterTypes = listOf(TcType.Constructor("demo.IntBox", emptyList())),
            )

        val first =
            VisibleRuleLookupReference.LookupFunction(
                callableId = callableId,
                shape = shape,
                ownerKey = "jvm-package-part:demo/IntsKt",
            )
        val second =
            VisibleRuleLookupReference.LookupFunction(
                callableId = callableId,
                shape = shape,
                ownerKey = "jvm-package-part:demo/StringsKt",
            )

        assertNotEquals(first.lookupIdentityKey(), second.lookupIdentityKey())
    }

    @Test
    fun lookupPropertyIdentityIncludesOwnerKey() {
        val callableId = CallableId(FqName("demo"), Name.identifier("show"))
        val first =
            VisibleRuleLookupReference.LookupProperty(
                callableId = callableId,
                ownerKey = "jvm-package-part:demo/IntsKt",
            )
        val second =
            VisibleRuleLookupReference.LookupProperty(
                callableId = callableId,
                ownerKey = "jvm-package-part:demo/StringsKt",
            )

        assertNotEquals(first.lookupIdentityKey(), second.lookupIdentityKey())
    }
}
