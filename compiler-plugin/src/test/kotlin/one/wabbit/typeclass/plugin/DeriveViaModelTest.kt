// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.declarations.addTypeParameter
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.impl.IrExternalPackageFragmentImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl
import org.jetbrains.kotlin.ir.symbols.impl.DescriptorlessExternalPackageFragmentSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.impl.makeTypeProjection
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DeriveViaModelTest {
    @Test
    fun directIsoFallbackRejectsMultipleDistinctEndpointPairs() {
        val typeclassPackage = testPackageFragment("one.wabbit.typeclass")
        val testPackage = testPackageFragment("test.derivevia.model")
        val iso = irClass("Iso", typeclassPackage, kind = ClassKind.INTERFACE, modality = Modality.ABSTRACT, typeParameterNames = listOf("A", "B"))
        val token = irClass("Token", testPackage)
        val foo = irClass("Foo", testPackage)
        val left = irClass("Left", testPackage)
        val right = irClass("Right", testPackage)

        val ambiguousIso =
            irClass("AmbiguousIso", testPackage, kind = ClassKind.OBJECT, modality = Modality.FINAL).also { irClass ->
                irClass.superTypes =
                    listOf(
                        isoType(iso, simpleType(token), simpleType(foo)),
                        isoType(iso, simpleType(left), simpleType(right)),
                    )
                irClass.addUnaryFunction("to", simpleType(token), simpleType(foo))
                irClass.addUnaryFunction("from", simpleType(foo), simpleType(token))
                irClass.addUnaryFunction("to", simpleType(left), simpleType(right))
                irClass.addUnaryFunction("from", simpleType(right), simpleType(left))
            }

        assertNull(
            ambiguousIso.findIsoMethods(),
            "Pinned Iso discovery must reject objects that expose multiple distinct inherited endpoint pairs.",
        )
    }

    @Test
    fun directIsoFallbackStillAcceptsASingleEndpointPair() {
        val typeclassPackage = testPackageFragment("one.wabbit.typeclass")
        val testPackage = testPackageFragment("test.derivevia.model")
        val iso = irClass("Iso", typeclassPackage, kind = ClassKind.INTERFACE, modality = Modality.ABSTRACT, typeParameterNames = listOf("A", "B"))
        val token = irClass("Token", testPackage)
        val foo = irClass("Foo", testPackage)

        val tokenFooIso =
            irClass("TokenFooIso", testPackage, kind = ClassKind.OBJECT, modality = Modality.FINAL).also { irClass ->
                irClass.superTypes = listOf(isoType(iso, simpleType(token), simpleType(foo)))
                irClass.addUnaryFunction("to", simpleType(token), simpleType(foo))
                irClass.addUnaryFunction("from", simpleType(foo), simpleType(token))
            }

        assertNotNull(tokenFooIso.findIsoMethods())
    }
}

private fun testPackageFragment(packageName: String): IrExternalPackageFragmentImpl =
    IrExternalPackageFragmentImpl(
        DescriptorlessExternalPackageFragmentSymbol(),
        FqName(packageName),
    )

private fun irClass(
    name: String,
    packageFragment: IrExternalPackageFragmentImpl,
    kind: ClassKind = ClassKind.CLASS,
    modality: Modality = Modality.OPEN,
    typeParameterNames: List<String> = emptyList(),
) =
    IrFactoryImpl.buildClass {
        this.name = Name.identifier(name)
        this.kind = kind
        this.modality = modality
        this.visibility = DescriptorVisibilities.PUBLIC
    }.also { irClass ->
        irClass.parent = packageFragment
        packageFragment.declarations += irClass
        typeParameterNames.forEach { typeParameterName ->
            irClass.addTypeParameter(typeParameterName, placeholderAnyType())
        }
    }

private fun IrClass.addUnaryFunction(
    name: String,
    parameterType: IrType,
    returnType: IrType,
): IrSimpleFunction =
    IrFactoryImpl.buildFun {
        this.name = Name.identifier(name)
        this.returnType = returnType
        this.visibility = DescriptorVisibilities.PUBLIC
        this.modality = Modality.OPEN
        this.origin = IrDeclarationOrigin.DEFINED
    }.also { function ->
        function.parent = this
        declarations += function
        function.addValueParameter("value", parameterType)
    }

private fun isoType(
    isoClass: IrClass,
    leftType: IrType,
    rightType: IrType,
): IrType = simpleType(isoClass, listOf(leftType, rightType))

private fun simpleType(
    irClass: IrClass,
    arguments: List<IrType> = emptyList(),
): IrType =
    org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl(
        classifier = irClass.symbol,
        hasQuestionMark = false,
        arguments = arguments.map { argument -> makeTypeProjection(argument, Variance.INVARIANT) },
        annotations = emptyList(),
    )

private fun placeholderAnyType(): IrType =
    simpleType(irClass("PlaceholderAny", testPackageFragment("test.placeholder")))
