// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass.plugin

internal fun <Type, Classifier, VisitKey> projectToMatchingSupertype(
    actualType: Type,
    expectedClassifier: Classifier,
    classifierOf: (Type) -> Classifier?,
    visitKeyOf: (Type) -> VisitKey,
    directSupertypes: (Type) -> Iterable<Type>,
    visited: MutableSet<VisitKey> = linkedSetOf(),
): Type? {
    val actualClassifier = classifierOf(actualType) ?: return null
    if (actualClassifier == expectedClassifier) {
        return actualType
    }
    if (!visited.add(visitKeyOf(actualType))) {
        return null
    }
    directSupertypes(actualType).forEach { superType ->
        val projected =
            projectToMatchingSupertype(
                actualType = superType,
                expectedClassifier = expectedClassifier,
                classifierOf = classifierOf,
                visitKeyOf = visitKeyOf,
                directSupertypes = directSupertypes,
                visited = visited,
            )
        if (projected != null) {
            return projected
        }
    }
    return null
}
