// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass.plugin

import one.wabbit.typeclass.plugin.model.InstanceRule
import one.wabbit.typeclass.plugin.model.TcType
import org.jetbrains.kotlin.load.kotlin.JvmPackagePartSource
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedContainerSource
import org.jetbrains.kotlin.types.Variance

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
    val lookupReference: VisibleRuleLookupReference? = null,
    val isFromDependencyBinary: Boolean = false,
)

internal data class VisibleClassHierarchyInfo(
    val superClassifiers: Set<String>,
    val isSealed: Boolean,
    val typeParameterVariances: List<Variance>,
)

internal sealed interface VisibleRuleLookupReference {
    data class LookupFunction(
        val callableId: CallableId,
        val shape: LookupFunctionShape,
        val ownerKey: String? = null,
    ) : VisibleRuleLookupReference

    data class LookupProperty(
        val callableId: CallableId,
        val ownerKey: String? = null,
    ) : VisibleRuleLookupReference

    data class LookupObject(
        val classId: ClassId,
    ) : VisibleRuleLookupReference
}

internal data class ImportedTopLevelInstanceRule(
    val rule: InstanceRule,
    val reference: VisibleRuleLookupReference,
)

internal fun DeserializedContainerSource.lookupOwnerKeyOrNull(): String? =
    when (this) {
        is JvmPackagePartSource -> "jvm-package-part:${className.internalName}"
        else -> null
    }

internal fun sourceLookupOwnerKey(path: String): String = "source-file:$path"

internal fun classLookupOwnerKey(classId: ClassId): String = "class:${classId.asString()}"

internal fun VisibleRuleLookupReference.lookupIdentityKey(): String =
    when (this) {
        is VisibleRuleLookupReference.LookupFunction -> "fun:${ownerKey ?: "-"}:${callableId}:$shape"
        is VisibleRuleLookupReference.LookupProperty -> "prop:${ownerKey ?: "-"}:${callableId}"
        is VisibleRuleLookupReference.LookupObject -> "obj:${classId.asString()}"
    }

internal fun VisibleRuleLookupReference.packageFqName(): FqName =
    when (this) {
        is VisibleRuleLookupReference.LookupFunction -> callableId.packageName
        is VisibleRuleLookupReference.LookupProperty -> callableId.packageName
        is VisibleRuleLookupReference.LookupObject -> classId.packageFqName
    }
