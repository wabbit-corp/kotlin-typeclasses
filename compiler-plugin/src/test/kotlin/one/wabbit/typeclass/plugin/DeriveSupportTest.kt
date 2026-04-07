// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeriveSupportTest {
    @Test
    fun `derive method contracts cover product sum and enum shapes`() {
        assertEquals(
            DeriveMethodContract.PRODUCT,
            deriveMethodContractForShape(
                classKind = ClassKind.CLASS,
                modality = Modality.FINAL,
            ),
        )
        assertEquals(
            DeriveMethodContract.PRODUCT,
            deriveMethodContractForShape(
                classKind = ClassKind.OBJECT,
                modality = Modality.FINAL,
            ),
        )
        assertEquals(
            DeriveMethodContract.SUM,
            deriveMethodContractForShape(
                classKind = ClassKind.INTERFACE,
                modality = Modality.SEALED,
            ),
        )
        assertEquals(
            DeriveMethodContract.ENUM,
            deriveMethodContractForShape(
                classKind = ClassKind.ENUM_CLASS,
                modality = Modality.FINAL,
            ),
        )
    }

    @Test
    fun `unsupported shapes have no derive method contract`() {
        assertNull(
            deriveMethodContractForShape(
                classKind = ClassKind.INTERFACE,
                modality = Modality.ABSTRACT,
            ),
        )
        assertNull(
            deriveMethodContractForShape(
                classKind = ClassKind.CLASS,
                modality = Modality.OPEN,
            ),
        )
    }
}
