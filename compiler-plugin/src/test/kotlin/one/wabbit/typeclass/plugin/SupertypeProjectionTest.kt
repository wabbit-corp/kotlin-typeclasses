// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.typeclass.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SupertypeProjectionTest {
    @Test
    fun `projection can revisit the same classifier with a different instantiated shape`() {
        val root = SyntheticProjectionType(classifier = "Root", argument = "String")

        val projected =
            projectToMatchingSupertype(
                actualType = root,
                expectedClassifier = "Target",
                classifierOf = SyntheticProjectionType::classifier,
                visitKeyOf = SyntheticProjectionType::identity,
                directSupertypes = ::syntheticProjectionSupertypes,
            )

        assertEquals(SyntheticProjectionType(classifier = "Target", argument = "String"), projected)
    }

    @Test
    fun `projection still treats identical instantiated revisits as cycles`() {
        val root = SyntheticProjectionType(classifier = "Loop", argument = "String")

        val projected =
            projectToMatchingSupertype(
                actualType = root,
                expectedClassifier = "Target",
                classifierOf = SyntheticProjectionType::classifier,
                visitKeyOf = SyntheticProjectionType::identity,
                directSupertypes = ::syntheticProjectionSupertypes,
            )

        assertNull(projected)
    }
}

private data class SyntheticProjectionType(val classifier: String, val argument: String) {
    val identity: Pair<String, String>
        get() = classifier to argument
}

private fun syntheticProjectionSupertypes(
    type: SyntheticProjectionType
): List<SyntheticProjectionType> =
    when (type.classifier to type.argument) {
        "Root" to "String" ->
            listOf(SyntheticProjectionType(classifier = "Bridge", argument = "List<String>"))
        "Bridge" to "List<String>" ->
            listOf(SyntheticProjectionType(classifier = "Bridge", argument = "String"))
        "Bridge" to "String" ->
            listOf(SyntheticProjectionType(classifier = "Target", argument = "String"))
        "Loop" to "String" ->
            listOf(SyntheticProjectionType(classifier = "Loop", argument = "String"))
        else -> emptyList()
    }
