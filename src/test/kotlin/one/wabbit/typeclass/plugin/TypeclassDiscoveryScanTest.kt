package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.name.FqName
import kotlin.test.Test
import kotlin.test.assertEquals

class TypeclassDiscoveryScanTest {
    @Test
    fun sharedTopLevelDiscoveryPassFeedsMultipleCollectorsWithoutRescanningPackages() {
        val packageRequests = mutableListOf<FqName>()
        val declarationRequests = mutableListOf<FqName>()
        val source =
            object : TopLevelDeclarationSource<String> {
                private val declarationsByPackage =
                    linkedMapOf(
                        FqName("alpha") to listOf("alpha.fun", "alpha.Type"),
                        FqName("beta") to listOf("beta.fun"),
                    )

                override fun packageNames(): Sequence<FqName> {
                    packageRequests += declarationsByPackage.keys
                    return declarationsByPackage.keys.asSequence()
                }

                override fun declarationsInPackage(packageName: FqName): Sequence<String> {
                    declarationRequests += packageName
                    return declarationsByPackage.getValue(packageName).asSequence()
                }
            }

        val contextualCollector = mutableListOf<String>()
        val resolutionCollector = mutableListOf<String>()

        scanTopLevelDeclarations(source) { declaration ->
            contextualCollector += declaration
            resolutionCollector += declaration
        }

        assertEquals(
            listOf(FqName("alpha"), FqName("beta")),
            packageRequests,
        )
        assertEquals(
            listOf(FqName("alpha"), FqName("beta")),
            declarationRequests,
        )
        assertEquals(
            listOf("alpha.fun", "alpha.Type", "beta.fun"),
            contextualCollector,
        )
        assertEquals(
            contextualCollector,
            resolutionCollector,
        )
    }
}
