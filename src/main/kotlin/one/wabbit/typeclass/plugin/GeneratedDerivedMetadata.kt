package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.name.ClassId

internal sealed interface GeneratedDerivedMetadata {
    val typeclassId: ClassId
    val targetId: ClassId
    val kind: String

    fun payload(): String = ""

    data class Derive(
        override val typeclassId: ClassId,
        override val targetId: ClassId,
    ) : GeneratedDerivedMetadata {
        override val kind: String = "derive"
    }

    data class DeriveVia(
        override val typeclassId: ClassId,
        override val targetId: ClassId,
        val path: List<GeneratedDeriveViaPathSegment>,
    ) : GeneratedDerivedMetadata {
        override val kind: String = "derive-via"

        override fun payload(): String =
            path.joinToString("|") { segment ->
                "${segment.kind.tag}:${segment.classId.asString()}"
            }
    }

    data class DeriveEquiv(
        override val targetId: ClassId,
        val otherClassId: ClassId,
    ) : GeneratedDerivedMetadata {
        override val typeclassId: ClassId = EQUIV_CLASS_ID
        override val kind: String = "derive-equiv"

        override fun payload(): String = otherClassId.asString()
    }
}

internal data class GeneratedDeriveViaPathSegment(
    val kind: Kind,
    val classId: ClassId,
) {
    internal enum class Kind(
        val tag: Char,
    ) {
        WAYPOINT('W'),
        PINNED_ISO('I'),
    }
}

internal data class EncodedGeneratedDerivedMetadata(
    val typeclassId: String,
    val targetId: String,
    val kind: String,
    val payload: String,
)

internal fun GeneratedDerivedMetadata.encode(): EncodedGeneratedDerivedMetadata =
    EncodedGeneratedDerivedMetadata(
        typeclassId = typeclassId.asString(),
        targetId = targetId.asString(),
        kind = kind,
        payload = payload(),
    )

internal fun decodeGeneratedDerivedMetadata(
    typeclassId: String?,
    targetId: String?,
    kind: String?,
    payload: String?,
    expectedOwnerId: String? = null,
): GeneratedDerivedMetadata? {
    val decodedTargetId = targetId?.let(::decodeClassId) ?: return null
    if (expectedOwnerId != null && decodedTargetId.asString() != expectedOwnerId) {
        return null
    }
    return when (kind) {
        "derive" -> {
            val decodedTypeclassId = typeclassId?.let(::decodeClassId) ?: return null
            GeneratedDerivedMetadata.Derive(
                typeclassId = decodedTypeclassId,
                targetId = decodedTargetId,
            )
        }

        "derive-via" -> {
            val decodedTypeclassId = typeclassId?.let(::decodeClassId) ?: return null
            val decodedPath = payload?.let(::decodeGeneratedDeriveViaPath) ?: return null
            GeneratedDerivedMetadata.DeriveVia(
                typeclassId = decodedTypeclassId,
                targetId = decodedTargetId,
                path = decodedPath,
            )
        }

        "derive-equiv" -> {
            val otherClassId = payload?.let(::decodeClassId) ?: return null
            GeneratedDerivedMetadata.DeriveEquiv(
                targetId = decodedTargetId,
                otherClassId = otherClassId,
            )
        }

        else -> null
    }
}

private fun decodeGeneratedDeriveViaPath(payload: String): List<GeneratedDeriveViaPathSegment>? {
    if (payload.isEmpty()) {
        return null
    }
    return payload.split('|').mapNotNull { encodedSegment ->
        if (encodedSegment.length < 3 || encodedSegment[1] != ':') {
            return null
        }
        val kind =
            when (encodedSegment[0]) {
                GeneratedDeriveViaPathSegment.Kind.WAYPOINT.tag -> GeneratedDeriveViaPathSegment.Kind.WAYPOINT
                GeneratedDeriveViaPathSegment.Kind.PINNED_ISO.tag -> GeneratedDeriveViaPathSegment.Kind.PINNED_ISO
                else -> return null
            }
        val classId = decodeClassId(encodedSegment.substring(2)) ?: return null
        GeneratedDeriveViaPathSegment(kind = kind, classId = classId)
    }.takeIf { it.isNotEmpty() }
}

private fun decodeClassId(encoded: String): ClassId? =
    runCatching { ClassId.fromString(encoded) }.getOrNull()
