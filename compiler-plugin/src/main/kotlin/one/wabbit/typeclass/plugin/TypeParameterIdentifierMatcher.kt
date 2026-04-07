// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass.plugin

internal fun String.containsStandaloneTypeParameterIdentifier(
    transportedName: String,
    opaqueNames: Set<String>,
): Boolean =
    Regex("""[A-Za-z_][A-Za-z0-9_]*""")
        .findAll(this)
        .any { match ->
            val identifier = match.value
            identifier == transportedName &&
                identifier !in opaqueNames &&
                !isQualifiedIdentifier(match.range.first, match.range.last)
        }

private fun String.isQualifiedIdentifier(
    startInclusive: Int,
    endInclusive: Int,
): Boolean {
    val previous = previousNonWhitespaceChar(startInclusive - 1)
    val next = nextNonWhitespaceChar(endInclusive + 1)
    return previous == '.' || next == '.'
}

private fun String.previousNonWhitespaceChar(index: Int): Char? {
    var cursor = index
    while (cursor >= 0) {
        val candidate = this[cursor]
        if (!candidate.isWhitespace()) {
            return candidate
        }
        cursor -= 1
    }
    return null
}

private fun String.nextNonWhitespaceChar(index: Int): Char? {
    var cursor = index
    while (cursor < length) {
        val candidate = this[cursor]
        if (!candidate.isWhitespace()) {
            return candidate
        }
        cursor += 1
    }
    return null
}
