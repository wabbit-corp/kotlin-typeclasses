// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.name.ClassId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class GeneratedDerivedMetadataTest {
    @Test
    fun deriveMetadataRoundTrips() {
        val metadata =
            GeneratedDerivedMetadata.Derive(
                typeclassId = ClassId.fromString("demo/Show"),
                targetId = ClassId.fromString("demo/User"),
            )

        val encoded = metadata.encode()
        val decoded =
            decodeGeneratedDerivedMetadata(
                typeclassId = encoded.typeclassId,
                targetId = encoded.targetId,
                kind = encoded.kind,
                payload = encoded.payload,
                expectedOwnerId = encoded.targetId,
            )

        assertEquals(metadata, decoded)
    }

    @Test
    fun deriveViaMetadataRoundTripsPinnedAndWaypointSegments() {
        val metadata =
            GeneratedDerivedMetadata.DeriveVia(
                typeclassId = ClassId.fromString("demo/Show"),
                targetId = ClassId.fromString("demo/UserId"),
                path =
                    listOf(
                        GeneratedDeriveViaPathSegment(
                            kind = GeneratedDeriveViaPathSegment.Kind.PINNED_ISO,
                            classId = ClassId.fromString("demo/UserTokenIso"),
                        ),
                        GeneratedDeriveViaPathSegment(
                            kind = GeneratedDeriveViaPathSegment.Kind.WAYPOINT,
                            classId = ClassId.fromString("demo/Wire"),
                        ),
                    ),
            )

        val encoded = metadata.encode()
        val decoded =
            decodeGeneratedDerivedMetadata(
                typeclassId = encoded.typeclassId,
                targetId = encoded.targetId,
                kind = encoded.kind,
                payload = encoded.payload,
                expectedOwnerId = encoded.targetId,
            )

        assertEquals(metadata, decoded)
    }

    @Test
    fun deriveEquivMetadataRoundTripsPreciseOtherClass() {
        val metadata =
            GeneratedDerivedMetadata.DeriveEquiv(
                targetId = ClassId.fromString("demo/UserId"),
                otherClassId = ClassId.fromString("demo/Token"),
            )

        val encoded = metadata.encode()
        val decoded =
            decodeGeneratedDerivedMetadata(
                typeclassId = encoded.typeclassId,
                targetId = encoded.targetId,
                kind = encoded.kind,
                payload = encoded.payload,
                expectedOwnerId = encoded.targetId,
            )

        assertEquals(metadata, decoded)
    }

    @Test
    fun deriveEquivMetadataWithoutPreciseTargetDoesNotDecode() {
        val decoded =
            decodeGeneratedDerivedMetadata(
                typeclassId = EQUIV_CLASS_ID.asString(),
                targetId = "demo/UserId",
                kind = "derive-equiv",
                payload = "",
                expectedOwnerId = "demo/UserId",
            )

        assertNull(decoded)
    }

    @Test
    fun deriveViaMetadataWithoutPathDoesNotDecode() {
        val decoded =
            decodeGeneratedDerivedMetadata(
                typeclassId = "demo/Show",
                targetId = "demo/UserId",
                kind = "derive-via",
                payload = "",
                expectedOwnerId = "demo/UserId",
            )

        assertNull(decoded)
    }

    @Test
    fun deriveViaPayloadPreservesSegmentKinds() {
        val encoded =
            GeneratedDerivedMetadata.DeriveVia(
                typeclassId = ClassId.fromString("demo/Show"),
                targetId = ClassId.fromString("demo/UserId"),
                path =
                    listOf(
                        GeneratedDeriveViaPathSegment(
                            kind = GeneratedDeriveViaPathSegment.Kind.WAYPOINT,
                            classId = ClassId.fromString("demo/Token"),
                        ),
                        GeneratedDeriveViaPathSegment(
                            kind = GeneratedDeriveViaPathSegment.Kind.PINNED_ISO,
                            classId = ClassId.fromString("demo/TokenWireIso"),
                        ),
                    ),
            ).encode()

        val decoded =
            decodeGeneratedDerivedMetadata(
                typeclassId = encoded.typeclassId,
                targetId = encoded.targetId,
                kind = encoded.kind,
                payload = encoded.payload,
                expectedOwnerId = encoded.targetId,
            )

        val deriveVia = assertIs<GeneratedDerivedMetadata.DeriveVia>(decoded)
        assertEquals(GeneratedDeriveViaPathSegment.Kind.WAYPOINT, deriveVia.path[0].kind)
        assertEquals(GeneratedDeriveViaPathSegment.Kind.PINNED_ISO, deriveVia.path[1].kind)
    }
}
