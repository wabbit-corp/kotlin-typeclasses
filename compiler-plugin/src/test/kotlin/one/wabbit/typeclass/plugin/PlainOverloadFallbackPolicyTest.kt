// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.declarations.addTypeParameter
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.declarations.buildValueParameter
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.impl.IrExternalPackageFragmentImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl
import org.jetbrains.kotlin.ir.symbols.impl.DescriptorlessExternalPackageFragmentSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.impl.makeTypeProjection
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlainOverloadFallbackPolicyTest {
    @Test
    fun genericPlainFallbackCandidatesAreRejectedEvenWhenShapeCouldOtherwiseMatch() {
        assertFalse(
            supportsExactPlainOverloadFallbackTypeParameters(
                callableIsLocal = false,
                calleeDeclaredTypeParameterCount = 0,
                candidateDeclaredTypeParameterCount = 1,
            ),
        )
    }

    @Test
    fun nongenericTopLevelPlainFallbackCandidatesRemainEligible() {
        assertTrue(
            supportsExactPlainOverloadFallbackTypeParameters(
                callableIsLocal = false,
                calleeDeclaredTypeParameterCount = 0,
                candidateDeclaredTypeParameterCount = 0,
            ),
        )
    }

    @Test
    fun genericContextualCalleesNeverUseExactPlainFallback() {
        assertFalse(
            supportsExactPlainOverloadFallbackTypeParameters(
                callableIsLocal = false,
                calleeDeclaredTypeParameterCount = 1,
                candidateDeclaredTypeParameterCount = 0,
            ),
        )
    }

    @Test
    fun exactPlainFallbackShapeMatchingUsesTheActiveBuiltinConfiguration() {
        val reflectPackage = testPackageFragment("kotlin.reflect")
        val testPackage = testPackageFragment("test.fallback")
        val kClass = irClass("KClass", reflectPackage, kind = ClassKind.INTERFACE, modality = Modality.ABSTRACT, typeParameterNames = listOf("T"))
        val plain = plainFallbackFunction("render", testPackage)
        val contextual = contextualKClassFunction("render", testPackage, simpleType(kClass))

        assertTrue(
            exactPlainOverloadFallbackShapesMatch(
                candidate = plain,
                callee = contextual,
                configuration = TypeclassConfiguration(builtinKClassTypeclass = TypeclassBuiltinMode.ENABLED),
            ),
        )
        assertFalse(
            exactPlainOverloadFallbackShapesMatch(
                candidate = plain,
                callee = contextual,
                configuration = TypeclassConfiguration(),
            ),
        )
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

private fun plainFallbackFunction(
    name: String,
    packageFragment: IrExternalPackageFragmentImpl,
): IrSimpleFunction =
    IrFactoryImpl.buildFun {
        this.name = Name.identifier(name)
        returnType = placeholderAnyType()
        visibility = DescriptorVisibilities.PUBLIC
        modality = Modality.FINAL
    }.also { function ->
        function.parent = packageFragment
        packageFragment.declarations += function
        val typeParameter = function.addTypeParameter("T", placeholderAnyType())
        function.returnType = typeParameter.defaultType
        function.addValueParameter("value", typeParameter.defaultType)
    }

private fun contextualKClassFunction(
    name: String,
    packageFragment: IrExternalPackageFragmentImpl,
    kClassType: IrType,
): IrSimpleFunction =
    IrFactoryImpl.buildFun {
        this.name = Name.identifier(name)
        returnType = placeholderAnyType()
        visibility = DescriptorVisibilities.PUBLIC
        modality = Modality.FINAL
    }.also { function ->
        function.parent = packageFragment
        packageFragment.declarations += function
        val typeParameter = function.addTypeParameter("T", placeholderAnyType())
        function.returnType = typeParameter.defaultType
        function.parameters =
            function.parameters + buildContextParameter(
                function = function,
                name = "klass",
                type = kClassType.withTypeArgument(typeParameter.defaultType),
            )
        function.addValueParameter("value", typeParameter.defaultType)
    }

private fun buildContextParameter(
    function: IrSimpleFunction,
    name: String,
    type: IrType,
) =
    buildValueParameter(function) {
        this.name = Name.identifier(name)
        this.type = type
        this.kind = IrParameterKind.Context
        this.origin = IrDeclarationOrigin.DEFINED
        this.startOffset = UNDEFINED_OFFSET
        this.endOffset = UNDEFINED_OFFSET
    }

private fun IrType.withTypeArgument(argument: IrType): IrType {
    val simpleType = this as IrSimpleType
    return org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl(
        classifier = simpleType.classifier,
        hasQuestionMark = simpleType.isNullable(),
        arguments = listOf(makeTypeProjection(argument, Variance.INVARIANT)),
        annotations = simpleType.annotations,
    )
}

private fun simpleType(irClass: IrClass): IrType =
    org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl(
        classifier = irClass.symbol,
        hasQuestionMark = false,
        arguments = emptyList(),
        annotations = emptyList(),
    )

private fun placeholderAnyType(): IrType =
    simpleType(irClass("PlaceholderAny", testPackageFragment("test.placeholder")))
