@file:OptIn(
    org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess::class,
    org.jetbrains.kotlin.fir.symbols.SymbolInternals::class,
)

package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirPropertyChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirRegularClassChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirSimpleFunctionChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.SpecialNames

internal class TypeclassFirCheckersExtension(
    session: FirSession,
    private val sharedState: TypeclassPluginSharedState,
) : FirAdditionalCheckersExtension(session) {
    private val regularClassChecker =
        object : FirRegularClassChecker(MppCheckerKind.Common) {
            context(context: CheckerContext, reporter: DiagnosticReporter)
            override fun check(declaration: FirRegularClass) {
                if (!declaration.hasAnnotation(INSTANCE_ANNOTATION_CLASS_ID, session)) {
                    return
                }
                if (declaration.classKind != ClassKind.OBJECT) {
                    reportInvalid(declaration, "class-based instances are not allowed; use an object")
                    return
                }

                val providedType = declaration.instanceProvidedType(session) ?: return
                validateAssociatedScope(
                    ownerContext = classOwnerContext(declaration.symbol.classId),
                    providedType = providedType,
                    declaration = declaration,
                )
            }
        }

    private val simpleFunctionChecker =
        object : FirSimpleFunctionChecker(MppCheckerKind.Common) {
            context(context: CheckerContext, reporter: DiagnosticReporter)
            override fun check(declaration: FirSimpleFunction) {
                if (!declaration.hasAnnotation(INSTANCE_ANNOTATION_CLASS_ID, session)) {
                    return
                }
                when {
                    declaration.receiverParameter != null -> {
                        reportInvalid(declaration, "extension instance functions are not allowed")
                        return
                    }

                    declaration.valueParameters.isNotEmpty() -> {
                        reportInvalid(declaration, "instance functions cannot declare a regular parameter")
                        return
                    }

                    declaration.status.isSuspend -> {
                        reportInvalid(declaration, "suspend instance functions are not allowed")
                        return
                    }
                }

                val providedType = declaration.instanceProvidedType(session) ?: return
                validateAssociatedScope(
                    ownerContext = callableOwnerContext(declaration.symbol.callableId),
                    providedType = providedType,
                    declaration = declaration,
                )
            }
        }

    private val propertyChecker =
        object : FirPropertyChecker(MppCheckerKind.Common) {
            context(context: CheckerContext, reporter: DiagnosticReporter)
            override fun check(declaration: FirProperty) {
                if (!declaration.hasAnnotation(INSTANCE_ANNOTATION_CLASS_ID, session)) {
                    return
                }
                when {
                    declaration.receiverParameter != null -> {
                        reportInvalid(declaration, "extension instance properties are not allowed")
                        return
                    }

                    declaration.status.isLateInit -> {
                        reportInvalid(declaration, "lateinit instance property declarations are not allowed")
                        return
                    }

                    declaration.isVar -> {
                        reportInvalid(declaration, "mutable instance property declarations are not allowed")
                        return
                    }

                    declaration.getter?.body != null -> {
                        reportInvalid(declaration, "custom getter instance property declarations are not allowed")
                        return
                    }
                }

                val providedType = declaration.instanceProvidedType(session) ?: return
                validateAssociatedScope(
                    ownerContext = callableOwnerContext(declaration.symbol.callableId),
                    providedType = providedType,
                    declaration = declaration,
                )
            }
        }

    override val declarationCheckers: DeclarationCheckers =
        object : DeclarationCheckers() {
            override val regularClassCheckers: Set<FirRegularClassChecker> = setOf(regularClassChecker)
            override val simpleFunctionCheckers: Set<FirSimpleFunctionChecker> = setOf(simpleFunctionChecker)
            override val propertyCheckers: Set<FirPropertyChecker> = setOf(propertyChecker)
        }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun validateAssociatedScope(
        ownerContext: InstanceOwnerContext,
        providedType: one.wabbit.typeclass.plugin.model.TcType,
        declaration: org.jetbrains.kotlin.fir.declarations.FirDeclaration,
    ) {
        when {
            ownerContext.isTopLevel -> Unit
            !ownerContext.isCompanionScope -> {
                reportInvalid(
                    declaration,
                    "instance declarations must be top-level or live in a companion of the provided typeclass head or one of its associated owners",
                )
            }

            ownerContext.associatedOwner !in sharedState.allowedAssociatedOwnersForProvidedType(session, providedType) -> {
                reportInvalid(
                    declaration,
                    "associated owner does not match the provided typeclass head or its type arguments",
                )
            }
        }
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun reportInvalid(
        declaration: org.jetbrains.kotlin.fir.declarations.FirDeclaration,
        message: String,
    ) {
        val source = declaration.source ?: return
        reporter.reportOn(source, TypeclassErrors.INVALID_INSTANCE_DECLARATION, message)
    }

    private fun classOwnerContext(classId: ClassId): InstanceOwnerContext {
        if (classId.isLocal) {
            return InstanceOwnerContext(isTopLevel = false, isCompanionScope = false, associatedOwner = null)
        }
        return if (classId.shortClassName == SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT) {
            InstanceOwnerContext(
                isTopLevel = false,
                isCompanionScope = true,
                associatedOwner = classId.outerClassId,
            )
        } else {
            nestedOwnerContext(classId.outerClassId)
        }
    }

    private fun callableOwnerContext(callableId: CallableId?): InstanceOwnerContext =
        nestedOwnerContext(callableId?.classId)

    private fun nestedOwnerContext(ownerClassId: ClassId?): InstanceOwnerContext {
        if (ownerClassId == null) {
            return InstanceOwnerContext(isTopLevel = true, isCompanionScope = false, associatedOwner = null)
        }
        if (ownerClassId.isLocal) {
            return InstanceOwnerContext(isTopLevel = false, isCompanionScope = false, associatedOwner = null)
        }
        val ownerClass =
            session.symbolProvider.getClassLikeSymbolByClassId(ownerClassId) as? org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
                ?: return InstanceOwnerContext(isTopLevel = false, isCompanionScope = false, associatedOwner = null)
        return if (ownerClass.fir.classKind == ClassKind.OBJECT && ownerClassId.shortClassName == SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT) {
            InstanceOwnerContext(
                isTopLevel = false,
                isCompanionScope = true,
                associatedOwner = ownerClassId.outerClassId,
            )
        } else {
            InstanceOwnerContext(isTopLevel = false, isCompanionScope = false, associatedOwner = ownerClassId)
        }
    }
}

private data class InstanceOwnerContext(
    val isTopLevel: Boolean,
    val isCompanionScope: Boolean,
    val associatedOwner: ClassId?,
)
