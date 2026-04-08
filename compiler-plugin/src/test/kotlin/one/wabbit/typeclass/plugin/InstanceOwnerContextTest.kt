// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

@file:OptIn(
    org.jetbrains.kotlin.fir.PrivateSessionConstructor::class,
    org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class,
)

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.declarations.buildProperty
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.impl.IrExternalPackageFragmentImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl
import org.jetbrains.kotlin.ir.symbols.impl.DescriptorlessExternalPackageFragmentSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import kotlin.test.Test
import kotlin.test.assertFalse

class InstanceOwnerContextTest {
    @Test
    fun `fir callable owner context treats local callable ids as non-indexable`() {
        val session = object : FirSession(FirSession.Kind.Library) {}

        val ownerContext = firInstanceOwnerContext(session, CallableId(Name.identifier("localInstance")))

        assertFalse(ownerContext.isTopLevel)
        assertFalse(ownerContext.isIndexableScope)
    }

    @Test
    fun `fir callable owner context treats missing callable ids as non-indexable`() {
        val session = object : FirSession(FirSession.Kind.Library) {}

        val ownerContext = firInstanceOwnerContext(session, callableId = null)

        assertFalse(ownerContext.isTopLevel)
        assertFalse(ownerContext.isIndexableScope)
    }

    @Test
    fun `ir function owner context treats local functions as non-indexable`() {
        val packageFragment = testPackageFragment("demo.instance.owner")
        val enclosingFunction = irFunction("enclosing", packageFragment)
        val localFunction = irFunction("localInstance", enclosingFunction)

        val ownerContext = irInstanceOwnerContext(localFunction)

        assertFalse(ownerContext.isTopLevel)
        assertFalse(ownerContext.isIndexableScope)
    }

    @Test
    fun `ir property owner context treats local properties as non-indexable`() {
        val packageFragment = testPackageFragment("demo.instance.owner")
        val enclosingFunction = irFunction("enclosing", packageFragment)
        val localProperty = irProperty("localInstance", enclosingFunction)

        val ownerContext = irInstanceOwnerContext(localProperty)

        assertFalse(ownerContext.isTopLevel)
        assertFalse(ownerContext.isIndexableScope)
    }

}

private fun testPackageFragment(packageName: String): IrExternalPackageFragmentImpl =
    IrExternalPackageFragmentImpl(
        DescriptorlessExternalPackageFragmentSymbol(),
        FqName(packageName),
    )

private fun irFunction(
    name: String,
    parent: org.jetbrains.kotlin.ir.declarations.IrDeclarationParent,
): IrSimpleFunction =
    IrFactoryImpl.buildFun {
        this.name = Name.identifier(name)
        returnType = placeholderAnyType()
        visibility = DescriptorVisibilities.PUBLIC
        modality = Modality.FINAL
    }.also { function ->
        function.parent = parent
    }

private fun irProperty(
    name: String,
    parent: org.jetbrains.kotlin.ir.declarations.IrDeclarationParent,
): IrProperty =
    IrFactoryImpl.buildProperty {
        this.name = Name.identifier(name)
        visibility = DescriptorVisibilities.PUBLIC
        modality = Modality.FINAL
        isVar = false
    }.also { property ->
        property.parent = parent
    }

private fun placeholderAnyType(): IrType =
    IrFactoryImpl.buildClass {
        name = Name.identifier("PlaceholderAny")
        visibility = DescriptorVisibilities.PUBLIC
        modality = Modality.OPEN
        kind = org.jetbrains.kotlin.descriptors.ClassKind.CLASS
    }.also { klass ->
        klass.parent = testPackageFragment("test.placeholder")
    }.let { klass ->
        IrSimpleTypeImpl(
            classifier = klass.symbol,
            hasQuestionMark = false,
            arguments = emptyList(),
            annotations = emptyList(),
        )
    }
