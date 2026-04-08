// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass.plugin

internal data class UniquePerfectAssignment<out Assignment>(
    val sourceIndex: Int,
    val targetIndex: Int,
    val value: Assignment,
)

internal fun <Source, Target, Assignment> uniquePerfectAssignmentPreservingMultiplicity(
    sources: List<Source>,
    targets: List<Target>,
    buildAssignment: (source: Source, target: Target) -> Assignment?,
): List<UniquePerfectAssignment<Assignment>>? {
    if (sources.size != targets.size) {
        return null
    }
    if (targets.isEmpty()) {
        return emptyList()
    }

    val candidatesByTarget =
        Array(targets.size) { targetIndex ->
            buildList {
                sources.forEachIndexed { sourceIndex, source ->
                    buildAssignment(source, targets[targetIndex])?.let { assignment ->
                        add(
                            UniquePerfectAssignment(
                                sourceIndex = sourceIndex,
                                targetIndex = targetIndex,
                                value = assignment,
                            ),
                        )
                    }
                }
            }
        }
    if (candidatesByTarget.any(List<UniquePerfectAssignment<Assignment>>::isEmpty)) {
        return null
    }

    val usedSources = BooleanArray(sources.size)
    val assignedTargets = BooleanArray(targets.size)
    val current = MutableList<UniquePerfectAssignment<Assignment>?>(targets.size) { null }
    var solution: List<UniquePerfectAssignment<Assignment>>? = null
    var solutionsFound = 0

    fun search(depth: Int) {
        if (solutionsFound > 1) {
            return
        }
        if (depth == targets.size) {
            solutionsFound += 1
            if (solutionsFound == 1) {
                solution = current.filterNotNull()
            }
            return
        }

        var nextTargetIndex = -1
        var nextViable = emptyList<UniquePerfectAssignment<Assignment>>()
        for (targetIndex in targets.indices) {
            if (assignedTargets[targetIndex]) {
                continue
            }
            val viable =
                candidatesByTarget[targetIndex].filter { candidate ->
                    !usedSources[candidate.sourceIndex]
                }
            if (viable.isEmpty()) {
                return
            }
            if (nextTargetIndex == -1 || viable.size < nextViable.size) {
                nextTargetIndex = targetIndex
                nextViable = viable
                if (viable.size == 1) {
                    break
                }
            }
        }

        assignedTargets[nextTargetIndex] = true
        for (candidate in nextViable) {
            usedSources[candidate.sourceIndex] = true
            current[nextTargetIndex] = candidate
            search(depth + 1)
            current[nextTargetIndex] = null
            usedSources[candidate.sourceIndex] = false
            if (solutionsFound > 1) {
                break
            }
        }
        assignedTargets[nextTargetIndex] = false
    }

    search(depth = 0)
    return if (solutionsFound == 1) solution else null
}
