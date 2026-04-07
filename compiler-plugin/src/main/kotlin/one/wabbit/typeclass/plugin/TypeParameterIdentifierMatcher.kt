// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass.plugin

internal fun String.containsStandaloneTypeParameterIdentifier(
    transportedName: String,
    opaqueNames: Set<String>,
): Boolean {
    val tokens = kotlinIdentifierTokens()
    return tokens.anyIndexed { index, token ->
        token is KotlinIdentifierToken.Identifier &&
            token.text == transportedName &&
            token.text !in opaqueNames &&
            !tokens.isQualifiedIdentifier(index)
    }
}

private sealed interface KotlinIdentifierToken {
    data class Identifier(
        val text: String,
    ) : KotlinIdentifierToken

    data object Dot : KotlinIdentifierToken

    data object Other : KotlinIdentifierToken
}

private fun String.kotlinIdentifierTokens(): List<KotlinIdentifierToken> {
    val tokens = ArrayList<KotlinIdentifierToken>()
    var cursor = 0
    while (cursor < length) {
        when (val candidate = this[cursor]) {
            '.' -> {
                tokens += KotlinIdentifierToken.Dot
                cursor += 1
            }

            '`' -> {
                val end = indexOf('`', startIndex = cursor + 1)
                if (end < 0) {
                    tokens += KotlinIdentifierToken.Other
                    break
                }
                tokens += KotlinIdentifierToken.Identifier(substring(cursor + 1, end))
                cursor = end + 1
            }

            else ->
                when {
                    candidate.isWhitespace() -> cursor += 1
                    candidate.isKotlinIdentifierStart() -> {
                        val start = cursor
                        cursor += 1
                        while (cursor < length && this[cursor].isKotlinIdentifierPart()) {
                            cursor += 1
                        }
                        tokens += KotlinIdentifierToken.Identifier(substring(start, cursor))
                    }

                    else -> {
                        tokens += KotlinIdentifierToken.Other
                        cursor += 1
                    }
                }
        }
    }
    return tokens
}

private inline fun <T> List<T>.anyIndexed(predicate: (index: Int, value: T) -> Boolean): Boolean {
    for (index in indices) {
        if (predicate(index, this[index])) {
            return true
        }
    }
    return false
}

private fun List<KotlinIdentifierToken>.isQualifiedIdentifier(index: Int): Boolean {
    val previous = getOrNull(index - 1)
    val next = getOrNull(index + 1)
    return previous is KotlinIdentifierToken.Dot || next is KotlinIdentifierToken.Dot
}

private fun Char.isKotlinIdentifierStart(): Boolean = this == '_' || Character.isUnicodeIdentifierStart(this)

private fun Char.isKotlinIdentifierPart(): Boolean = this == '_' || Character.isUnicodeIdentifierPart(this)
