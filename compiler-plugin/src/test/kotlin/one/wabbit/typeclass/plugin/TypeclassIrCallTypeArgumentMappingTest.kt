// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

@file:OptIn(
    org.jetbrains.kotlin.ir.IrImplementationDetail::class,
    org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class,
)

package one.wabbit.typeclass.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.SourceElement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.impl.IrClassImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrExternalPackageFragmentImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl
import org.jetbrains.kotlin.ir.symbols.impl.DescriptorlessExternalPackageFragmentSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrClassSymbolImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.util.SYNTHETIC_OFFSET
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

class TypeclassIrCallTypeArgumentMappingTest {
    @Test
    fun `visible-only explicit call type arguments map by visible parameter names`() {
        val packageFragment = testPackageFragment()
        val intType = simpleType(irClass("IntType", packageFragment))
        val stringType = simpleType(irClass("StringType", packageFragment))

        val mapped =
            mapCallTypeArgumentsByOriginalParameterName(
                typeArguments = listOf(intType, stringType),
                visibleTypeParameterNames = listOf("A", "B"),
                originalTypeParameterNames = listOf("A", "Hidden", "B"),
            )

        assertEquals(linkedMapOf("A" to intType, "B" to stringType), mapped)
    }

    @Test
    fun `full explicit call type arguments keep original type parameter order`() {
        val packageFragment = testPackageFragment()
        val intType = simpleType(irClass("IntType", packageFragment))
        val booleanType = simpleType(irClass("BooleanType", packageFragment))
        val stringType = simpleType(irClass("StringType", packageFragment))

        val mapped =
            mapCallTypeArgumentsByOriginalParameterName(
                typeArguments = listOf(intType, booleanType, stringType),
                visibleTypeParameterNames = listOf("A", "B"),
                originalTypeParameterNames = listOf("A", "Hidden", "B"),
            )

        assertEquals(
            linkedMapOf("A" to intType, "Hidden" to booleanType, "B" to stringType),
            mapped,
        )
    }
}

private fun testPackageFragment(): IrExternalPackageFragmentImpl =
    IrExternalPackageFragmentImpl(
        DescriptorlessExternalPackageFragmentSymbol(),
        FqName("test.ir.call.mapping"),
    )

private fun irClass(name: String, packageFragment: IrExternalPackageFragmentImpl): IrClassImpl {
    val irClass =
        IrClassImpl(
            startOffset = UNDEFINED_OFFSET,
            endOffset = SYNTHETIC_OFFSET,
            origin = IrDeclarationOrigin.DEFINED,
            factory = IrFactoryImpl,
            name = Name.identifier(name),
            visibility = DescriptorVisibilities.PUBLIC,
            symbol = IrClassSymbolImpl(),
            kind = ClassKind.CLASS,
            modality = Modality.FINAL,
            source = SourceElement.NO_SOURCE,
        )
    irClass.parent = packageFragment
    packageFragment.declarations += irClass
    return irClass
}

private fun simpleType(irClass: IrClassImpl): IrType =
    IrSimpleTypeImpl(
        classifier = irClass.symbol,
        hasQuestionMark = false,
        arguments = emptyList(),
        annotations = emptyList(),
    )
