// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass.plugin

internal object TypeclassDiagnosticIds {
    const val NO_CONTEXT_ARGUMENT = "TC_NO_CONTEXT_ARGUMENT"
    const val AMBIGUOUS_INSTANCE = "TC_AMBIGUOUS_INSTANCE"
    const val INVALID_INSTANCE_DECL = "TC_INVALID_INSTANCE_DECL"
    const val INVALID_EQUIV_DECL = "TC_INVALID_EQUIV_DECL"
    const val CANNOT_DERIVE = "TC_CANNOT_DERIVE"
    const val INVALID_BUILTIN_EVIDENCE = "TC_INVALID_BUILTIN_EVIDENCE"

    fun prefix(id: String): String = "[$id]"

    fun format(id: String, message: String): String = "${prefix(id)} $message"
}
