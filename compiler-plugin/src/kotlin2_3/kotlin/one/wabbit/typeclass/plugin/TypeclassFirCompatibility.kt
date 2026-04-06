// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.declarations.getKClassArgument
import org.jetbrains.kotlin.fir.declarations.getStringArgument
import org.jetbrains.kotlin.fir.declarations.isLocal
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.name.Name

typealias FirTypeclassFunctionDeclaration = FirSimpleFunction

internal fun FirAnnotation.typeclassGetStringArgument(
    name: Name,
    session: FirSession,
): String? = getStringArgument(name, session)

internal fun FirAnnotation.typeclassGetKClassArgument(
    name: Name,
    session: FirSession,
): ConeKotlinType? = getKClassArgument(name, session)

internal fun FirProperty.typeclassIsLocal(): Boolean = isLocal

internal fun FirExpression.typeclassCollectionLiteralArgumentsOrNull(): Sequence<FirExpression>? = null

internal fun DeclarationIrBuilder.buildTypeclassGeneratedAnnotation(
    annotationConstructor: IrConstructor,
): IrConstructorCall = irCallConstructor(annotationConstructor.symbol, emptyList())

internal fun IrClass.appendTypeclassGeneratedAnnotation(annotation: IrConstructorCall) {
    annotations += annotation
}
