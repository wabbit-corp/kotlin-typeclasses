// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.ir.symbols.IrClassifierSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.isMarkedNullable

internal fun IrType.sameTypeShape(other: IrType): Boolean =
    transportTypeShapeKey() == other.transportTypeShapeKey()

internal fun IrType.transportTypeShapeKey(): TransportTypeShapeKey {
    val simpleType = this as? IrSimpleType ?: return TransportTypeShapeKey.NonSimple(this)
    return TransportTypeShapeKey.Simple(
        classifier = simpleType.classifier,
        isNullable = simpleType.isMarkedNullable(),
        arguments =
            simpleType.arguments.map { argument ->
                when (argument) {
                    is IrTypeProjection ->
                        TransportTypeArgumentShapeKey.Projection(
                            variance = argument.variance,
                            type = argument.type.transportTypeShapeKey(),
                        )

                    is IrType ->
                        TransportTypeArgumentShapeKey.Type(argument.transportTypeShapeKey())

                    else -> TransportTypeArgumentShapeKey.Kind(argument::class.java)
                }
            },
    )
}

internal sealed interface TransportTypeShapeKey {
    data class NonSimple(val type: IrType) : TransportTypeShapeKey

    data class Simple(
        val classifier: IrClassifierSymbol,
        val isNullable: Boolean,
        val arguments: List<TransportTypeArgumentShapeKey>,
    ) : TransportTypeShapeKey
}

internal sealed interface TransportTypeArgumentShapeKey {
    data class Projection(
        val variance: org.jetbrains.kotlin.types.Variance,
        val type: TransportTypeShapeKey,
    ) : TransportTypeArgumentShapeKey

    data class Type(val type: TransportTypeShapeKey) : TransportTypeArgumentShapeKey

    data class Kind(val klass: Class<*>) : TransportTypeArgumentShapeKey
}
