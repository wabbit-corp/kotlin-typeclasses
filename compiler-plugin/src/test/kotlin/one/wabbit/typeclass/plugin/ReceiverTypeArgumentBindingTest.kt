// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

@file:OptIn(
    org.jetbrains.kotlin.ir.IrImplementationDetail::class,
    org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class,
)

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.SourceElement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.impl.IrClassImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrExternalPackageFragmentImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrTypeParameterImpl
import org.jetbrains.kotlin.ir.symbols.impl.DescriptorlessExternalPackageFragmentSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrClassSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrTypeParameterSymbolImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.types.impl.makeTypeProjection
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReceiverTypeArgumentBindingTest {
    @Test
    fun remappedReceiverSupertypesBindExpectedClassifierArguments() {
        val packageFragment = testPackageFragment()
        val box = irClass("Box", packageFragment, listOf("T"))
        val weird = irClass("Weird", packageFragment, listOf("A", "B"))
        val first = irClass("First", packageFragment)
        val second = irClass("Second", packageFragment)

        weird.superTypes = listOf(simpleType(box, listOf(weird.typeParameters[1].defaultType)))

        val bindings =
            receiverTypeArgumentBindings(
                expectedType = simpleType(box, listOf(box.typeParameters.single().defaultType)),
                actualType = simpleType(weird, listOf(simpleType(first), simpleType(second))),
            )

        assertEquals(
            simpleType(second),
            bindings[box.typeParameters.single().symbol],
            "Receiver remapping should bind Box<T> from Weird<A, B> : Box<B> as T = B.",
        )
    }

    @Test
    fun unrelatedReceiversDoNotProduceBindings() {
        val packageFragment = testPackageFragment()
        val box = irClass("Box", packageFragment, listOf("T"))
        val plain = irClass("Plain", packageFragment, listOf("U"))
        val argument = irClass("Arg", packageFragment)

        val bindings =
            receiverTypeArgumentBindings(
                expectedType = simpleType(box, listOf(box.typeParameters.single().defaultType)),
                actualType = simpleType(plain, listOf(simpleType(argument))),
            )

        assertTrue(bindings.isEmpty())
    }
}

private fun testPackageFragment(): IrExternalPackageFragmentImpl =
    IrExternalPackageFragmentImpl(
        DescriptorlessExternalPackageFragmentSymbol(),
        FqName("test.receiver"),
    )

private fun irClass(
    name: String,
    packageFragment: IrExternalPackageFragmentImpl,
    typeParameterNames: List<String> = emptyList(),
): IrClass {
    val irClass =
        IrClassImpl(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            origin = IrDeclarationOrigin.DEFINED,
            factory = IrFactoryImpl,
            name = Name.identifier(name),
            visibility = DescriptorVisibilities.PUBLIC,
            symbol = IrClassSymbolImpl(),
            kind = ClassKind.CLASS,
            modality = Modality.OPEN,
            source = SourceElement.NO_SOURCE,
        )
    irClass.parent = packageFragment
    packageFragment.declarations += irClass
    irClass.typeParameters =
        typeParameterNames.mapIndexed { index, typeParameterName ->
            IrTypeParameterImpl(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                origin = IrDeclarationOrigin.DEFINED,
                factory = IrFactoryImpl,
                name = Name.identifier(typeParameterName),
                symbol = IrTypeParameterSymbolImpl(),
                variance = Variance.INVARIANT,
                index = index,
                isReified = false,
            ).also { typeParameter ->
                typeParameter.parent = irClass
            }
        }
    return irClass
}

private fun simpleType(
    irClass: IrClass,
    arguments: List<IrType> = emptyList(),
): IrType =
    IrSimpleTypeImpl(
        classifier = irClass.symbol,
        hasQuestionMark = false,
        arguments = arguments.map { argument -> makeTypeProjection(argument, Variance.INVARIANT) },
        annotations = emptyList(),
    )
