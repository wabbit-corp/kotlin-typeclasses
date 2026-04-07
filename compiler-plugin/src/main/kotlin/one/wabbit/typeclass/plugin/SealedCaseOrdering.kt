// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass.plugin

internal fun stableSealedSubclassIds(
    sourceOrderedSubclassIds: Iterable<String>,
    discoveredSubclassIds: Iterable<String>,
): List<String> {
    val discovered = discoveredSubclassIds.toCollection(linkedSetOf())
    if (discovered.isEmpty()) {
        return emptyList()
    }

    val sourceOrdered =
        sourceOrderedSubclassIds
            .filter { subclassId -> subclassId in discovered }
            .distinct()

    val orderedIds = sourceOrdered.toHashSet()
    val fallbackSorted =
        discovered
            .asSequence()
            .filterNot(orderedIds::contains)
            .sorted()
            .toList()
    return sourceOrdered + fallbackSorted
}
