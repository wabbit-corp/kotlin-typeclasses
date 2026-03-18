package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.extensions.FirStatusTransformerExtension
import org.jetbrains.kotlin.fir.extensions.transform
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.types.coneType

internal class TypeclassFirStatusTransformerExtension(
    session: FirSession,
) : FirStatusTransformerExtension(session) {
    override fun needTransformStatus(declaration: FirDeclaration): Boolean =
        declaration is FirSimpleFunction && declaration.shouldHideTypeclassOriginal(session)

    override fun transformStatus(
        status: org.jetbrains.kotlin.fir.declarations.FirDeclarationStatus,
        function: FirSimpleFunction,
        containingClass: FirClassLikeSymbol<*>?,
        isLocal: Boolean,
    ): org.jetbrains.kotlin.fir.declarations.FirDeclarationStatus =
        if (function.shouldHideTypeclassOriginal(session)) {
            status.transform(visibility = Visibilities.InvisibleFake)
        } else {
            status
        }
}

private fun FirSimpleFunction.shouldHideTypeclassOriginal(session: FirSession): Boolean {
    if (origin.generated) {
        return false
    }
    if (hasAnnotation(INSTANCE_ANNOTATION_CLASS_ID, session)) {
        return false
    }
    if (contextParameters.isEmpty()) {
        return false
    }
    return contextParameters.any { parameter ->
        isTypeclassType(parameter.returnTypeRef.coneType, session)
    }
}
