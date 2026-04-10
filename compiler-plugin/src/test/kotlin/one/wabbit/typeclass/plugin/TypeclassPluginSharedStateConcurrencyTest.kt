// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.typeclass.plugin

import java.lang.reflect.Method
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import one.wabbit.typeclass.plugin.model.InstanceRule
import one.wabbit.typeclass.plugin.model.TcType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TypeclassPluginSharedStateConcurrencyTest {
    @Test
    fun concurrentImportedTopLevelRuleUpdatesDoNotLoseEntries() {
        val recordMethod = recordImportedTopLevelRulesMethod()

        repeat(64) { round ->
            val state = TypeclassPluginSharedState()
            val rules = (0 until 32).map { index -> dependencyVisibleRule(round * 100 + index) }
            runConcurrently(rules.size) { index ->
                recordMethod.invoke(state, listOf(rules[index]))
            }

            assertEquals("round=$round", rules.size, state.importedTopLevelRulesForIr().size)
        }
    }

    @Test
    fun concurrentImportedGeneratedMetadataUpdatesDoNotLoseEntries() {
        val recordMethod = recordImportedGeneratedMetadataMethod()
        val owner = ClassId.fromString("demo/Owner")

        repeat(64) { round ->
            val state = TypeclassPluginSharedState()
            val entries = (0 until 32).map { index -> generatedMetadata(round * 100 + index) }
            runConcurrently(entries.size) { index ->
                recordMethod.invoke(state, owner.asString(), listOf(entries[index]))
            }

            assertEquals(
                "round=$round",
                entries.size,
                state.importedGeneratedDerivedMetadataForIr(owner).size,
            )
        }
    }

    private fun runConcurrently(taskCount: Int, action: (Int) -> Unit) {
        val executor = Executors.newFixedThreadPool(taskCount)
        val start = CountDownLatch(1)
        val done = CountDownLatch(taskCount)
        val failures = ConcurrentLinkedQueue<Throwable>()
        try {
            repeat(taskCount) { index ->
                executor.execute {
                    try {
                        start.await()
                        action(index)
                    } catch (t: Throwable) {
                        failures += t
                    } finally {
                        done.countDown()
                    }
                }
            }
            start.countDown()
            assertTrue("timed out waiting for concurrent tasks", done.await(30, TimeUnit.SECONDS))
            assertTrue("concurrent action failed: ${failures.firstOrNull()}", failures.isEmpty())
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(30, TimeUnit.SECONDS)
        }
    }

    private fun recordImportedTopLevelRulesMethod(): Method =
        TypeclassPluginSharedState::class
            .java
            .getDeclaredMethod("recordImportedTopLevelRulesForIr", List::class.java)
            .apply { isAccessible = true }

    private fun recordImportedGeneratedMetadataMethod(): Method =
        TypeclassPluginSharedState::class
            .java
            .getDeclaredMethod(
                "recordImportedGeneratedDerivedMetadataForIr",
                String::class.java,
                List::class.java,
            )
            .apply { isAccessible = true }

    private fun dependencyVisibleRule(index: Int): VisibleInstanceRule =
        VisibleInstanceRule(
            rule =
                InstanceRule(
                    id = "rule:$index",
                    typeParameters = emptyList(),
                    providedType =
                        TcType.Constructor(
                            "demo/Show",
                            listOf(TcType.Constructor("demo/Type$index", emptyList())),
                        ),
                    prerequisiteTypes = emptyList(),
                ),
            associatedOwner = null,
            lookupReference =
                VisibleRuleLookupReference.LookupFunction(
                    callableId = CallableId(FqName("demo"), Name.identifier("rule$index")),
                    shape =
                        LookupFunctionShape(
                            dispatchReceiver = false,
                            extensionReceiverType = null,
                            typeParameterCount = 0,
                            contextParameterTypes = emptyList(),
                            regularParameterTypes = emptyList(),
                        ),
                ),
            isFromDependencyBinary = true,
        )

    private fun generatedMetadata(index: Int): GeneratedDerivedMetadata =
        GeneratedDerivedMetadata.Derive(
            typeclassId = ClassId.fromString("demo/Show"),
            targetId = ClassId.fromString("demo/Target$index"),
        )
}
