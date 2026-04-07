// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.name.FqName
import kotlin.test.Test
import kotlin.test.assertEquals

class TypeclassDiscoveryScanTest {
    @Test
    fun packageScopedDiscoveryOnlyScansTheRequestedPackage() {
        val declarationRequests = mutableListOf<FqName>()
        val source =
            object : TopLevelDeclarationsInPackageSource<String> {
                private val declarationsByPackage =
                    linkedMapOf(
                        FqName("alpha") to listOf("alpha.fun", "alpha.Type"),
                        FqName("beta") to listOf("beta.fun"),
                    )

                override fun declarationsInPackage(packageName: FqName): Sequence<String> {
                    declarationRequests += packageName
                    return declarationsByPackage.getValue(packageName).asSequence()
                }
            }

        val collected = mutableListOf<String>()
        scanTopLevelDeclarationsInPackage(FqName("beta"), source) { declaration ->
            collected += declaration
        }

        assertEquals(listOf(FqName("beta")), declarationRequests)
        assertEquals(listOf("beta.fun"), collected)
    }
}
