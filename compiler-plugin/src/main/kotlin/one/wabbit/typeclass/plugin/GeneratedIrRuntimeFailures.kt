// SPDX-License-Identifier: AGPL-3.0-or-later

@file:OptIn(org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi::class)

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

internal fun impossibleSumTransportRuntimeMessage(
    sourceType: String,
    targetType: String,
): String =
    "Internal typeclass transport error: generated sum transport from $sourceType to $targetType reached an impossible fallback path. This usually means stale generated metadata or ABI drift."

internal fun impossibleEnumOrdinalResolverRuntimeMessage(enumClassName: String): String =
    "Internal typeclass derivation error: generated enum ordinal resolver for $enumClassName reached an impossible fallback path. This usually means stale generated metadata or ABI drift."

internal fun impossibleEnumValueResolverRuntimeMessage(enumClassName: String): String =
    "Internal typeclass derivation error: generated enum value resolver for $enumClassName reached an impossible fallback path. This usually means stale generated metadata or ABI drift."

internal fun objectProductConstructorArityRuntimeMessage(className: String): String =
    "Internal typeclass derivation error: generated product constructor for object $className expected zero arguments. This usually means stale generated metadata or an invalid runtime caller."

internal fun productConstructorArityRuntimeMessage(
    className: String,
    expectedArguments: Int,
): String =
    "Internal typeclass derivation error: generated product constructor for $className expected $expectedArguments arguments. This usually means stale generated metadata or an invalid runtime caller."

internal fun IrBuilderWithScope.irTypeclassInternalError(
    pluginContext: IrPluginContext,
    message: String,
): IrExpression {
    val errorFunction =
        pluginContext.referenceFunctions(CallableId(FqName("kotlin"), Name.identifier("error")))
            .singleOrNull()
            ?: error("Could not resolve kotlin.error(String)")
    return irCall(errorFunction).apply {
        putValueArgument(0, irString(message))
    }
}
