package one.wabbit.typeclass.plugin

import one.wabbit.typeclass.plugin.model.InstanceRule
import one.wabbit.typeclass.plugin.model.TcType
import org.jetbrains.kotlin.name.ClassId

internal data class LookupFunctionShape(
    val dispatchReceiver: Boolean,
    val extensionReceiverType: TcType?,
    val typeParameterCount: Int,
    val contextParameterTypes: List<TcType>,
    val regularParameterTypes: List<TcType>,
)

internal data class VisibleInstanceRule(
    val rule: InstanceRule,
    val associatedOwner: ClassId?,
)

internal data class VisibleClassHierarchyInfo(
    val superClassifiers: Set<String>,
    val isSealed: Boolean,
)
