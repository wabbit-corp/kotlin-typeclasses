@file:OptIn(
    org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi::class,
    org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class,
)

package one.wabbit.typeclass.plugin

import one.wabbit.typeclass.plugin.model.InstanceRule
import one.wabbit.typeclass.plugin.model.ResolutionPlan
import one.wabbit.typeclass.plugin.model.ResolutionSearchResult
import one.wabbit.typeclass.plugin.model.TcType
import one.wabbit.typeclass.plugin.model.TcTypeParameter
import one.wabbit.typeclass.plugin.model.TypeclassResolutionPlanner
import one.wabbit.typeclass.plugin.model.render
import one.wabbit.typeclass.plugin.model.substituteType
import one.wabbit.typeclass.plugin.model.unifyTypes
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.IrBlockBodyBuilder
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irAs
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irIs
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irVararg
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrSpreadElement
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrStarProjection
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.callableId
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.ir.util.substitute
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

internal class TypeclassIrGenerationExtension(
    private val sharedState: TypeclassPluginSharedState,
) : IrGenerationExtension {
    override fun generate(
        moduleFragment: IrModuleFragment,
        pluginContext: IrPluginContext,
    ) {
        val ruleIndex = IrRuleIndex.build(moduleFragment, pluginContext, sharedState)
        val transformer = TypeclassIrCallTransformer(pluginContext, ruleIndex)
        moduleFragment.transformChildrenVoid(transformer)
    }
}

private class TypeclassIrCallTransformer(
    private val pluginContext: IrPluginContext,
    private val ruleIndex: IrRuleIndex,
) : IrElementTransformerVoid() {
    private val functionStack = ArrayDeque<IrFunction>()

    override fun visitFunction(declaration: IrFunction): IrStatement {
        functionStack.addLast(declaration)
        declaration.transformChildrenVoid(this)
        functionStack.removeLast()
        return declaration
    }

    override fun visitCall(expression: IrCall): IrExpression {
        val rewritten = super.visitCall(expression) as IrCall
        val callee = rewritten.symbol.owner
        if (!callee.requiresSyntheticTypeclassResolution(rewritten)) {
            return rewritten
        }

        val enclosingFunction = functionStack.lastOrNull()
            ?: error("Typeclass call ${callee.callableId} is not enclosed by a function")
        return buildResolvedTypeclassCall(
            call = rewritten,
            original = callee,
            currentFunction = enclosingFunction,
            enclosingFunctions = enclosingFunction.enclosingFunctions(),
        )
    }

    private fun buildResolvedTypeclassCall(
        call: IrCall,
        original: IrSimpleFunction,
        currentFunction: IrFunction,
        enclosingFunctions: List<IrFunction>,
    ): IrExpression =
        DeclarationIrBuilder(pluginContext, currentFunction.symbol, call.startOffset, call.endOffset).run {
            val visibleTypeParameters = visibleTypeParameters(enclosingFunctions)
            val localContexts = collectLocalContexts(enclosingFunctions, visibleTypeParameters)
            val normalizedCall = call.normalizedArgumentsForTypeclassRewrite(original)
            val inferredOriginalTypeArguments =
                try {
                    inferOriginalTypeArguments(
                        original = original,
                        normalizedCall = normalizedCall,
                        currentCallTypeArgumentsByName =
                            original.typeParameters.mapIndexedNotNull { index, typeParameter ->
                                call.getTypeArgument(index)?.let { irType ->
                                    typeParameter.name.asString() to irType
                                }
                            }.toMap(),
                        visibleTypeParameters = visibleTypeParameters,
                        localContexts = localContexts,
                        pluginContext = pluginContext,
                    )
                } catch (error: TypeArgumentInferenceFailure) {
                    reportTypeclassResolutionFailure(error.message ?: "Type argument inference failed for ${original.renderIdentity()}")
                    return call
                }
            val explicitArguments =
                extractExplicitArguments(
                    original = original,
                    normalizedValueArguments = normalizedCall.valueArguments,
                    substitutionBySymbol = inferredOriginalTypeArguments.substitutionBySymbol,
                )
            buildOriginalCall(
                original = original,
                currentFunction = currentFunction,
                localContexts = localContexts,
                visibleTypeParameters = visibleTypeParameters,
                inferredOriginalTypeArguments = inferredOriginalTypeArguments,
                fallbackCall = call,
                dispatchReceiver = normalizedCall.dispatchReceiver,
                extensionReceiver = normalizedCall.extensionReceiver,
                explicitArguments = explicitArguments,
            )
        }

    private fun reportTypeclassResolutionFailure(message: String) {
        pluginContext.messageCollector.report(CompilerMessageSeverity.ERROR, message)
    }

    private fun ResolutionPlan.renderForDiagnostic(): String =
        when (this) {
            is ResolutionPlan.LocalContext -> "local-context[$index]"
            is ResolutionPlan.ApplyRule -> ruleId
        }

    private fun IrBuilderWithScope.buildOriginalCall(
        original: IrSimpleFunction,
        currentFunction: IrFunction,
        localContexts: List<LocalTypeclassContext>,
        visibleTypeParameters: VisibleTypeParameters,
        inferredOriginalTypeArguments: InferredOriginalTypeArguments,
        fallbackCall: IrCall,
        dispatchReceiver: IrExpression?,
        extensionReceiver: IrExpression?,
        explicitArguments: List<ExplicitArgument>,
    ): IrExpression {
        val typeArgumentMap = inferredOriginalTypeArguments.substitutionBySymbol
        val planner =
            TypeclassResolutionPlanner(
                ruleProvider = { goal: TcType ->
                    ruleIndex.rulesForGoal(goal)
                },
            )

        val originalCall = irCall(original.symbol)
        originalCall.dispatchReceiver = dispatchReceiver
        originalCall.extensionReceiver = extensionReceiver

        inferredOriginalTypeArguments.callTypeArguments.forEachIndexed { index, irType ->
            originalCall.putTypeArgument(index, irType)
        }

        val localContextTypes = localContexts.map(LocalTypeclassContext::providedType)
        val explicitIterator = explicitArguments.iterator()

        original.regularAndContextParameters().forEachIndexed { index, parameter ->
            val substitutedGoalType = parameter.type.substitute(typeArgumentMap)
            if (parameter.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Context && substitutedGoalType.isTypeclassType()) {
                val goal =
                    irTypeToModel(
                        type = substitutedGoalType,
                        typeParameterBySymbol = visibleTypeParameters.bySymbol,
                    ) ?: error("Unsupported typeclass goal type $substitutedGoalType in ${original.callableId}")
                val result = planner.resolve(goal, localContextTypes)
                val expression =
                    when (result) {
                        is ResolutionSearchResult.Success ->
                            buildExpressionForPlan(
                                plan = result.plan,
                                currentFunction = currentFunction,
                                localContexts = localContexts,
                                visibleTypeParameters = visibleTypeParameters,
                            )

                        is ResolutionSearchResult.Ambiguous -> {
                            val message =
                                "Ambiguous typeclass instance for ${goal.render()} in ${currentFunction.renderIdentity()}." +
                                    " Candidates: ${result.matchingPlans.joinToString { it.renderForDiagnostic() }}"
                            reportTypeclassResolutionFailure(message)
                            return fallbackCall
                        }

                        is ResolutionSearchResult.Missing -> {
                            val message = "Missing typeclass instance for ${goal.render()} in ${currentFunction.renderIdentity()}"
                            reportTypeclassResolutionFailure(message)
                            return fallbackCall
                        }

                        is ResolutionSearchResult.Recursive -> {
                            val message = "Recursive typeclass resolution for ${goal.render()} in ${currentFunction.renderIdentity()}"
                            reportTypeclassResolutionFailure(message)
                            return fallbackCall
                        }
                    }
                originalCall.putValueArgument(index, expression)
            } else {
                val nextArgument = explicitIterator.nextOrNull()
                    ?: error("Not enough explicit arguments when rewriting ${original.callableId}")
                when (nextArgument) {
                    ExplicitArgument.Omitted -> Unit
                    is ExplicitArgument.PassThrough -> originalCall.putValueArgument(index, nextArgument.expression)
                }
            }
        }

        return originalCall
    }

    private fun IrBuilderWithScope.buildExpressionForPlan(
        plan: ResolutionPlan,
        currentFunction: IrFunction,
        localContexts: List<LocalTypeclassContext>,
        visibleTypeParameters: VisibleTypeParameters,
    ): IrExpression =
        when (plan) {
            is ResolutionPlan.LocalContext -> irGet(localContexts[plan.index].parameter)

            is ResolutionPlan.ApplyRule -> {
                val resolvedRule = ruleIndex.ruleById(plan.ruleId)
                    ?: error("Missing rule reference for ${plan.ruleId}")
                when (val reference = resolvedRule.reference) {
                    is RuleReference.DirectFunction -> {
                        val prerequisiteExpressions =
                            plan.prerequisitePlans.map { nested ->
                                buildExpressionForPlan(
                                    plan = nested,
                                    currentFunction = currentFunction,
                                    localContexts = localContexts,
                                    visibleTypeParameters = visibleTypeParameters,
                                )
                            }
                        buildInstanceFunctionCall(
                            function = reference.function,
                            rule = resolvedRule,
                            appliedTypeArguments = plan.appliedTypeArguments,
                            prerequisiteExpressions = prerequisiteExpressions,
                            visibleTypeParameters = visibleTypeParameters,
                            pluginContext = pluginContext,
                        )
                    }

                    is RuleReference.LookupFunction -> {
                        val function = ruleIndex.resolveLookupFunction(reference)
                            ?: error("Could not resolve instance function ${reference.callableId}")
                        val prerequisiteExpressions =
                            plan.prerequisitePlans.map { nested ->
                                buildExpressionForPlan(
                                    plan = nested,
                                    currentFunction = currentFunction,
                                    localContexts = localContexts,
                                    visibleTypeParameters = visibleTypeParameters,
                                )
                            }
                        buildInstanceFunctionCall(
                            function = function,
                            rule = resolvedRule,
                            appliedTypeArguments = plan.appliedTypeArguments,
                            prerequisiteExpressions = prerequisiteExpressions,
                            visibleTypeParameters = visibleTypeParameters,
                            pluginContext = pluginContext,
                        )
                    }

                    is RuleReference.DirectProperty ->
                        buildInstancePropertyAccess(reference.property)

                    is RuleReference.LookupProperty -> {
                        val property = ruleIndex.resolveLookupProperty(reference)
                            ?: error("Could not resolve instance property ${reference.callableId}")
                        buildInstancePropertyAccess(property)
                    }

                    is RuleReference.DirectObject -> irGetObject(reference.klass.symbol)

                    is RuleReference.LookupObject -> {
                        val klass = pluginContext.referenceClass(reference.classId)?.owner
                            ?: error("Could not resolve instance object ${reference.classId}")
                        irGetObject(klass.symbol)
                    }

                    is RuleReference.Derived ->
                        buildDerivedInstanceExpression(
                            reference = reference,
                            plan = plan,
                            currentFunction = currentFunction,
                            localContexts = localContexts,
                            visibleTypeParameters = visibleTypeParameters,
                        )
                }
            }
        }

    private fun IrBuilderWithScope.buildInstanceFunctionCall(
        function: IrSimpleFunction,
        rule: ResolvedRule,
        appliedTypeArguments: List<TcType>,
        prerequisiteExpressions: List<IrExpression>,
        visibleTypeParameters: VisibleTypeParameters,
        pluginContext: IrPluginContext,
    ): IrExpression {
        val call = irCall(function.symbol)
        val parentClass = function.parent as? IrClass
        if (parentClass != null && parentClass.isCompanion) {
            call.dispatchReceiver = irGetObject(parentClass.symbol)
        }

        rule.rule.typeParameters.zip(appliedTypeArguments).forEachIndexed { index, (_, appliedTypeArgument) ->
            call.putTypeArgument(index, modelToIrType(appliedTypeArgument, visibleTypeParameters, pluginContext))
        }

        val prerequisites = prerequisiteExpressions.iterator()
        function.regularAndContextParameters().forEachIndexed { index, parameter ->
            if (parameter.kind != org.jetbrains.kotlin.ir.declarations.IrParameterKind.Context) {
                error("Instance function ${function.callableId} unexpectedly has a regular parameter")
            }
            call.putValueArgument(index, prerequisites.nextOrNull() ?: error("Missing prerequisite expression for ${function.callableId}"))
        }
        return call
    }

    private fun IrBuilderWithScope.buildInstancePropertyAccess(
        property: IrProperty,
    ): IrExpression {
        val getter = property.getter ?: error("Instance property ${property.name} is missing a getter")
        return irCall(getter.symbol).apply {
            val parentClass = property.parent as? IrClass
            if (parentClass != null && parentClass.isCompanion) {
                dispatchReceiver = irGetObject(parentClass.symbol)
            }
        }
    }

    private fun IrBuilderWithScope.buildDerivedInstanceExpression(
        reference: RuleReference.Derived,
        plan: ResolutionPlan.ApplyRule,
        currentFunction: IrFunction,
        localContexts: List<LocalTypeclassContext>,
        visibleTypeParameters: VisibleTypeParameters,
    ): IrExpression {
        val prerequisiteExpressions =
            plan.prerequisitePlans.map { nested ->
                buildExpressionForPlan(
                    plan = nested,
                    currentFunction = currentFunction,
                    localContexts = localContexts,
                    visibleTypeParameters = visibleTypeParameters,
                )
            }
        val appliedBindings =
            reference.ruleTypeParameters.zip(plan.appliedTypeArguments)
                .associate { (parameter, appliedType) -> parameter.id to appliedType }
        val metadata =
            when (val shape = reference.shape) {
                is DerivedShape.Product ->
                    buildProductMetadata(
                        reference = reference,
                        shape = shape,
                        prerequisiteExpressions = prerequisiteExpressions,
                        appliedBindings = appliedBindings,
                        currentFunction = currentFunction,
                        visibleTypeParameters = visibleTypeParameters,
                    )

                is DerivedShape.Sum ->
                    buildSumMetadata(
                        reference = reference,
                        shape = shape,
                        prerequisiteExpressions = prerequisiteExpressions,
                        appliedBindings = appliedBindings,
                        currentFunction = currentFunction,
                        visibleTypeParameters = visibleTypeParameters,
                    )
            }
        val deriveMethodName =
            when (reference.shape) {
                is DerivedShape.Product -> "deriveProduct"
                is DerivedShape.Sum -> "deriveSum"
            }
        val deriveMethod =
            reference.deriverCompanion.declarations
                .filterIsInstance<IrSimpleFunction>()
                .singleOrNull { function -> function.name.asString() == deriveMethodName }
                ?: error("Typeclass deriver ${reference.deriverCompanion.classIdOrFail} is missing $deriveMethodName")
        val expectedType = modelToIrType(plan.providedType, visibleTypeParameters, pluginContext)
        return irAs(
            irCall(deriveMethod.symbol).apply {
                dispatchReceiver = irGetObject(reference.deriverCompanion.symbol)
                putValueArgument(0, metadata)
            },
            expectedType,
        )
    }

    private fun IrBuilderWithScope.buildProductMetadata(
        reference: RuleReference.Derived,
        shape: DerivedShape.Product,
        prerequisiteExpressions: List<IrExpression>,
        appliedBindings: Map<String, TcType>,
        currentFunction: IrFunction,
        visibleTypeParameters: VisibleTypeParameters,
    ): IrExpression {
        val fieldClass =
            pluginContext.referenceClass(PRODUCT_FIELD_METADATA_CLASS_ID)?.owner
                ?: error("Could not resolve $PRODUCT_FIELD_METADATA_FQ_NAME")
        val metadataClass =
            pluginContext.referenceClass(PRODUCT_TYPECLASS_METADATA_CLASS_ID)?.owner
                ?: error("Could not resolve $PRODUCT_TYPECLASS_METADATA_FQ_NAME")
        val fieldElements =
            shape.fields.zip(prerequisiteExpressions).map { (field, instanceExpression) ->
                irCallConstructor(fieldClass.primaryConstructorSymbol(), emptyList()).apply {
                    putValueArgument(0, irString(field.name))
                    putValueArgument(1, irString(field.type.substituteType(appliedBindings).render()))
                    putValueArgument(2, instanceExpression)
                    putValueArgument(
                        3,
                        buildProductFieldAccessor(
                            reference = reference,
                            field = field,
                            appliedBindings = appliedBindings,
                            currentFunction = currentFunction,
                            visibleTypeParameters = visibleTypeParameters,
                        ),
                    )
                }
            }
        return irCallConstructor(metadataClass.primaryConstructorSymbol(), emptyList()).apply {
            putValueArgument(0, irString(reference.targetClass.renderClassName()))
            putValueArgument(1, irListOf(fieldElements, fieldClass.symbol.defaultType))
        }
    }

    private fun IrBuilderWithScope.buildProductFieldAccessor(
        reference: RuleReference.Derived,
        field: DerivedField,
        appliedBindings: Map<String, TcType>,
        currentFunction: IrFunction,
        visibleTypeParameters: VisibleTypeParameters,
    ): IrExpression {
        val anyType = pluginContext.irBuiltIns.anyNType
        val lambdaType =
            pluginContext.irBuiltIns.functionN(1).typeWith(
                anyType,
                anyType,
            )
        val targetType =
            reference.targetClass.symbol.typeWith(
                reference.ruleTypeParameters.map { parameter ->
                    modelToIrType(
                        type = appliedBindings[parameter.id]
                            ?: error("Missing applied type binding for ${parameter.id}"),
                        visibleTypeParameters = visibleTypeParameters,
                        pluginContext = pluginContext,
                    )
                },
            )
        val accessor =
            context.irFactory.buildFun {
                name = Name.special("<typeclass-field-accessor>")
                origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
                visibility = DescriptorVisibilities.LOCAL
                returnType = anyType
            }.apply {
                parent = currentFunction
            }
        val valueParameter = accessor.addValueParameter("value", anyType)
        accessor.body =
            DeclarationIrBuilder(pluginContext, accessor.symbol, startOffset, endOffset).irBlockBody {
                +irReturn(
                    irCall(field.getter.symbol).apply {
                        dispatchReceiver = irAs(irGet(valueParameter), targetType)
                    },
                )
            }
        return IrFunctionExpressionImpl(
            startOffset = startOffset,
            endOffset = endOffset,
            type = lambdaType,
            function = accessor,
            origin = IrStatementOrigin.LAMBDA,
        )
    }

    private fun IrBuilderWithScope.buildSumMetadata(
        reference: RuleReference.Derived,
        shape: DerivedShape.Sum,
        prerequisiteExpressions: List<IrExpression>,
        appliedBindings: Map<String, TcType>,
        currentFunction: IrFunction,
        visibleTypeParameters: VisibleTypeParameters,
    ): IrExpression {
        val caseClass =
            pluginContext.referenceClass(SUM_CASE_METADATA_CLASS_ID)?.owner
                ?: error("Could not resolve $SUM_CASE_METADATA_FQ_NAME")
        val metadataClass =
            pluginContext.referenceClass(SUM_TYPECLASS_METADATA_CLASS_ID)?.owner
                ?: error("Could not resolve $SUM_TYPECLASS_METADATA_FQ_NAME")
        val caseElements =
            shape.cases.zip(prerequisiteExpressions).map { (case, instanceExpression) ->
                irCallConstructor(caseClass.primaryConstructorSymbol(), emptyList()).apply {
                    putValueArgument(0, irString(case.name))
                    putValueArgument(1, irString(case.klass.renderClassName()))
                    putValueArgument(2, instanceExpression)
                    putValueArgument(
                        3,
                        buildSumCaseMatcher(
                            case = case,
                            appliedBindings = appliedBindings,
                            currentFunction = currentFunction,
                            visibleTypeParameters = visibleTypeParameters,
                        ),
                    )
                }
            }
        return irCallConstructor(metadataClass.primaryConstructorSymbol(), emptyList()).apply {
            putValueArgument(0, irString(reference.targetClass.renderClassName()))
            putValueArgument(1, irListOf(caseElements, caseClass.symbol.defaultType))
        }
    }

    private fun IrBuilderWithScope.buildSumCaseMatcher(
        case: DerivedCase,
        appliedBindings: Map<String, TcType>,
        currentFunction: IrFunction,
        visibleTypeParameters: VisibleTypeParameters,
    ): IrExpression {
        val anyType = pluginContext.irBuiltIns.anyNType
        val booleanType = pluginContext.irBuiltIns.booleanType
        val lambdaType =
            pluginContext.irBuiltIns.functionN(1).typeWith(
                anyType,
                booleanType,
            )
        val caseType =
            modelToIrType(
                type = case.type.substituteType(appliedBindings),
                visibleTypeParameters = visibleTypeParameters,
                pluginContext = pluginContext,
            )
        val matcher =
            context.irFactory.buildFun {
                name = Name.special("<typeclass-sum-case-matcher>")
                origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
                visibility = DescriptorVisibilities.LOCAL
                returnType = booleanType
            }.apply {
                parent = currentFunction
            }
        val valueParameter = matcher.addValueParameter("value", anyType)
        matcher.body =
            DeclarationIrBuilder(pluginContext, matcher.symbol, startOffset, endOffset).irBlockBody {
                +irReturn(irIs(irGet(valueParameter), caseType))
            }
        return IrFunctionExpressionImpl(
            startOffset = startOffset,
            endOffset = endOffset,
            type = lambdaType,
            function = matcher,
            origin = IrStatementOrigin.LAMBDA,
        )
    }

    private fun IrBuilderWithScope.irListOf(
        elements: List<IrExpression>,
        elementType: IrType,
    ): IrExpression {
        val listOfFunction =
            pluginContext.referenceFunctions(CallableId(FqName("kotlin.collections"), Name.identifier("listOf")))
                .map { it.owner }
                .singleOrNull { function ->
                    function.valueParameters.singleOrNull()?.varargElementType != null
                }
                ?: error("Could not resolve kotlin.collections.listOf(vararg)")
        return irCall(listOfFunction.symbol).apply {
            putTypeArgument(0, elementType)
            putValueArgument(0, irVararg(elementType, elements))
        }
    }
}

private class IrRuleIndex private constructor(
    private val pluginContext: IrPluginContext,
    private val wrapperToOriginal: Map<IrSimpleFunction, IrSimpleFunction>,
    private val originalsByCallableId: Map<CallableId, List<IrSimpleFunction>>,
    private val rulesById: Map<String, ResolvedRule>,
    private val topLevelRules: List<ResolvedRule>,
    private val associatedRulesByOwner: Map<ClassId, List<ResolvedRule>>,
    private val classInfoById: Map<String, ClassHierarchyInfo>,
) {
    fun originalForWrapper(wrapper: IrSimpleFunction): IrSimpleFunction? = wrapperToOriginal[wrapper]

    fun originalForWrapperLikeFunction(wrapperLikeFunction: IrSimpleFunction): IrSimpleFunction? {
        val candidates = originalsByCallableId[wrapperLikeFunction.callableId].orEmpty()
        return candidates.singleOrNull { candidate ->
            wrapperResolutionShape(candidate, dropTypeclassContexts = true) ==
                wrapperResolutionShape(wrapperLikeFunction, dropTypeclassContexts = false)
        }
    }

    fun ruleById(ruleId: String): ResolvedRule? = rulesById[ruleId]

    fun resolveLookupFunction(reference: RuleReference.LookupFunction): IrSimpleFunction? =
        pluginContext.referenceFunctions(reference.callableId)
            .map { it.owner }
            .singleOrNull { function ->
                !function.isGeneratedTypeclassWrapper() &&
                    functionShape(function, dropTypeclassContexts = false) == reference.shape
            }

    fun resolveLookupProperty(reference: RuleReference.LookupProperty): IrProperty? =
        pluginContext.referenceProperties(reference.callableId)
            .map { it.owner }
            .singleOrNull()

    fun rulesForGoal(goal: TcType): List<InstanceRule> {
        val owners = associatedOwnersForGoal(goal)
        val associated =
            owners.flatMapTo(linkedSetOf()) { owner ->
                associatedRulesByOwner[owner].orEmpty()
            }
        return (topLevelRules + associated).map(ResolvedRule::rule)
    }

    private fun associatedOwnersForGoal(goal: TcType): Set<ClassId> {
        val constructor = goal as? TcType.Constructor ?: return emptySet()
        val ownerIds = linkedSetOf<String>()
        ownerIds += sealedOwnerChain(constructor.classifierId)
        constructor.arguments.forEach { argument ->
            ownerIds += associatedOwnerIds(argument)
        }
        return ownerIds.map(ClassId::fromString).toSet()
    }

    private fun associatedOwnerIds(type: TcType): Set<String> =
        when (type) {
            is TcType.Variable -> emptySet()

            is TcType.Constructor -> {
                val owners = linkedSetOf<String>()
                owners += sealedOwnerChain(type.classifierId)
                type.arguments.forEach { argument ->
                    owners += associatedOwnerIds(argument)
                }
                owners
            }
        }

    private fun sealedOwnerChain(classifierId: String): Set<String> {
        val result = linkedSetOf(classifierId)
        val queue = ArrayDeque<String>()
        queue += classifierId
        val visited = linkedSetOf<String>()
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) {
                continue
            }
            classInfoById[current]?.superClassifiers.orEmpty().forEach { superClassifier ->
                if (classInfoById[superClassifier]?.isSealed == true) {
                    result += superClassifier
                }
                queue += superClassifier
            }
        }
        return result
    }

    companion object {
        fun build(
            moduleFragment: IrModuleFragment,
            pluginContext: IrPluginContext,
            sharedState: TypeclassPluginSharedState,
        ): IrRuleIndex {
            val scanner = IrModuleScanner(pluginContext)
            moduleFragment.files.forEach(scanner::scanFile)

            val wrapperToOriginal = scanner.buildWrapperMapping()
            val companionRules = scanner.buildCompanionRules()
            val topLevelRules = scanner.buildTopLevelRules()
            val derivedRules = scanner.buildDerivedRules()
            val allRules = topLevelRules + companionRules + derivedRules
            val associatedRules =
                (companionRules + derivedRules)
                    .groupBy { it.associatedOwner ?: error("Associated rule must have owner") }
            return IrRuleIndex(
                pluginContext = pluginContext,
                wrapperToOriginal = wrapperToOriginal,
                originalsByCallableId = scanner.buildOriginalIndex(),
                rulesById = allRules.associateBy { it.rule.id },
                topLevelRules = topLevelRules,
                associatedRulesByOwner = associatedRules,
                classInfoById = scanner.classInfoById,
            )
        }
    }
}

private class IrModuleScanner(
    private val pluginContext: IrPluginContext,
) {
    val classInfoById: MutableMap<String, ClassHierarchyInfo> = linkedMapOf()

    private val classesById = linkedMapOf<String, IrClass>()
    private val declaredDerivationsByClassId = linkedMapOf<String, Set<ClassId>>()
    private val wrappers = mutableListOf<IrSimpleFunction>()
    private val originalsByCallableId = linkedMapOf<CallableId, MutableList<IrSimpleFunction>>()
    private val topLevelRules = mutableListOf<ResolvedRule>()
    private val companionRules = mutableListOf<ResolvedRule>()

    fun scanFile(file: IrFile) {
        file.declarations.forEach { declaration ->
            scanDeclaration(declaration, associatedOwner = null)
        }
    }

    fun buildWrapperMapping(): Map<IrSimpleFunction, IrSimpleFunction> =
        wrappers.associateWith { wrapper ->
            val candidates = originalsByCallableId[wrapper.callableId].orEmpty()
            candidates.singleOrNull { candidate ->
                wrapperResolutionShape(candidate, dropTypeclassContexts = true) ==
                    wrapperResolutionShape(wrapper, dropTypeclassContexts = false)
            } ?: error("Could not match generated wrapper ${wrapper.callableId} to an original declaration")
        }

    fun buildOriginalIndex(): Map<CallableId, List<IrSimpleFunction>> =
        originalsByCallableId.mapValues { (_, functions) -> functions.toList() }

    fun buildCompanionRules(): List<ResolvedRule> = companionRules

    fun buildTopLevelRules(): List<ResolvedRule> = topLevelRules

    fun buildDerivedRules(): List<ResolvedRule> {
        val subclassesBySuper =
            classInfoById.entries.fold(linkedMapOf<String, MutableSet<String>>()) { acc, (classId, info) ->
                info.superClassifiers.forEach { superClassifier ->
                    acc.getOrPut(superClassifier, ::linkedSetOf) += classId
                }
                acc
            }
        val expandedPairs = linkedSetOf<Pair<String, ClassId>>()
        val queue = ArrayDeque<Pair<String, ClassId>>()
        declaredDerivationsByClassId.forEach { (classId, typeclassIds) ->
            typeclassIds.forEach { typeclassId ->
                queue += classId to typeclassId
            }
        }
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (!expandedPairs.add(next)) {
                continue
            }
            val (classId, typeclassId) = next
            if (classInfoById[classId]?.isSealed == true) {
                subclassesBySuper[classId].orEmpty().forEach { subclassId ->
                    queue += subclassId to typeclassId
                }
            }
        }
        return expandedPairs.mapNotNull { (classId, typeclassId) ->
            classesById[classId]?.toDerivedRule(typeclassId, subclassesBySuper)
        }
    }

    private fun scanDeclaration(
        declaration: IrDeclaration,
        associatedOwner: ClassId?,
    ) {
        when (declaration) {
            is IrClass -> {
                declaration.classId?.let { classId ->
                    classesById[classId.asString()] = declaration
                    declaredDerivationsByClassId[classId.asString()] = declaration.derivedTypeclassIds()
                    classInfoById[classId.asString()] =
                        ClassHierarchyInfo(
                            superClassifiers =
                                declaration.superTypes.mapNotNull { superType ->
                                    superType.classOrNull?.owner?.classId?.asString()
                                },
                            isSealed = declaration.modality == Modality.SEALED,
                        )
                }

                val nextAssociatedOwner =
                    when {
                        declaration.isCompanion -> declaration.parentAsClass.classIdOrFail
                        associatedOwner != null -> associatedOwner
                        else -> null
                    }

                when {
                    declaration.isGeneratedTypeclassWrapperClass() -> Unit
                    declaration.isInstanceObject() && associatedOwner == null -> {
                        topLevelRules += declaration.toObjectRules(idPrefix = "top-level-object", associatedOwner = null)
                    }
                    declaration.isInstanceObject() && nextAssociatedOwner != null -> {
                        companionRules += declaration.toObjectRules(idPrefix = "associated-object", associatedOwner = nextAssociatedOwner)
                    }
                }

                declaration.declarations.forEach { nested ->
                    scanDeclaration(nested, associatedOwner = nextAssociatedOwner)
                }
            }

            is IrSimpleFunction -> {
                if (declaration.isGeneratedTypeclassWrapper()) {
                    wrappers += declaration
                } else {
                    originalsByCallableId.getOrPut(declaration.callableId, ::mutableListOf) += declaration
                }

                if (associatedOwner == null) {
                    topLevelRules += declaration.toFunctionRules(idPrefix = "top-level-function", associatedOwner = null)
                } else {
                    companionRules += declaration.toFunctionRules(idPrefix = "associated-function", associatedOwner = associatedOwner)
                }
            }

            is IrProperty -> {
                if (associatedOwner == null) {
                    topLevelRules += declaration.toPropertyRules(idPrefix = "top-level-property", associatedOwner = null)
                } else {
                    companionRules += declaration.toPropertyRules(idPrefix = "associated-property", associatedOwner = associatedOwner)
                }
            }
        }
    }

    private fun IrClass.toObjectRules(
        idPrefix: String,
        associatedOwner: ClassId?,
    ): List<ResolvedRule> {
        if (!hasAnnotation(INSTANCE_ANNOTATION_CLASS_ID)) {
            return emptyList()
        }
        return superTypes.providedTypeExpansion(emptyMap()).validTypes.map { providedType ->
            ResolvedRule(
                rule =
                    InstanceRule(
                        id = "$idPrefix:${classIdOrFail.asString()}:${providedType.render()}",
                        typeParameters = emptyList(),
                        providedType = providedType,
                        prerequisiteTypes = emptyList(),
                    ),
                reference = RuleReference.DirectObject(this),
                associatedOwner = associatedOwner,
            )
        }
    }

    private fun IrSimpleFunction.toFunctionRules(
        idPrefix: String,
        associatedOwner: ClassId?,
    ): List<ResolvedRule> {
        if (!hasAnnotation(INSTANCE_ANNOTATION_CLASS_ID)) {
            return emptyList()
        }
        if (dispatchReceiverParameter != null && !parentAsClass.isCompanion) {
            return emptyList()
        }
        if (extensionReceiverParameter != null) {
            return emptyList()
        }
        if (parameters.any { it.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Regular }) {
            return emptyList()
        }

        val typeParameters = this.typeParameters.map { typeParameter ->
            TcTypeParameter(
                id = ruleTypeParameterId(this, typeParameter),
                displayName = typeParameter.name.asString(),
            )
        }
        val typeParameterBySymbol = typeParametersBySymbol(this, typeParameters)
        val prerequisites =
            contextParameters().mapNotNull { parameter ->
                parameter.type.takeIf(IrType::isTypeclassType)?.let { irTypeToModel(it, typeParameterBySymbol) }
            }
        if (prerequisites.size != contextParameters().size) {
            return emptyList()
        }

        return listOf(returnType).providedTypeExpansion(typeParameterBySymbol).validTypes.map { providedType ->
            ResolvedRule(
                rule =
                    InstanceRule(
                        id = "$idPrefix:${callableId}:${providedType.render()}",
                        typeParameters = typeParameters,
                        providedType = providedType,
                        prerequisiteTypes = prerequisites,
                    ),
                reference = RuleReference.DirectFunction(this),
                associatedOwner = associatedOwner,
            )
        }
    }

    private fun IrProperty.toPropertyRules(
        idPrefix: String,
        associatedOwner: ClassId?,
    ): List<ResolvedRule> {
        if (!hasAnnotation(INSTANCE_ANNOTATION_CLASS_ID)) {
            return emptyList()
        }
        val getter = getter ?: return emptyList()
        if (getter.extensionReceiverParameter != null) {
            return emptyList()
        }
        if (getter.dispatchReceiverParameter != null) {
            val parentClass = parent as? IrClass ?: return emptyList()
            if (!parentClass.isCompanion) {
                return emptyList()
            }
        }
        return listOf(backingFieldOrGetterType()).providedTypeExpansion(emptyMap()).validTypes.map { providedType ->
            ResolvedRule(
                rule =
                    InstanceRule(
                        id = "$idPrefix:${callableId}:${providedType.render()}",
                        typeParameters = emptyList(),
                        providedType = providedType,
                        prerequisiteTypes = emptyList(),
                    ),
                reference = RuleReference.DirectProperty(this),
                associatedOwner = associatedOwner,
            )
        }
    }

    private fun IrClass.toDerivedRule(
        typeclassId: ClassId,
        subclassesBySuper: Map<String, Set<String>>,
    ): ResolvedRule? {
        val targetClassId = classId ?: return null
        val typeclassInterface = pluginContext.referenceClass(typeclassId)?.owner ?: return null
        if (!typeclassInterface.hasAnnotation(TYPECLASS_ANNOTATION_CLASS_ID)) {
            return null
        }
        if (typeclassInterface.typeParameters.size != 1) {
            return null
        }
        val deriverCompanion = typeclassInterface.findDeriverCompanion() ?: return null
        val ruleTypeParameters =
            typeParameters.mapIndexed { index, typeParameter ->
                TcTypeParameter(
                    id = "${targetClassId.asString()}#$index",
                    displayName = typeParameter.name.asString(),
                )
            }
        val typeParameterBySymbol = typeParameters.zip(ruleTypeParameters).associate { (symbol, parameter) ->
            symbol.symbol to parameter
        }
        val targetType =
            TcType.Constructor(
                targetClassId.asString(),
                ruleTypeParameters.map { parameter -> TcType.Variable(parameter.id, parameter.displayName) },
            )
        val shape =
            if (modality == Modality.SEALED) {
                buildDerivedSumShape(targetType, subclassesBySuper)
            } else {
                buildDerivedProductShape(typeParameterBySymbol)
            } ?: return null
        val prerequisiteTypes =
            when (shape) {
                is DerivedShape.Product -> shape.fields.map { field -> typeclassGoal(typeclassId, field.type) }
                is DerivedShape.Sum -> shape.cases.map { case -> typeclassGoal(typeclassId, case.type) }
            }
        val rule =
            InstanceRule(
                id = "derived:${typeclassId.asString()}:${targetClassId.asString()}",
                typeParameters = ruleTypeParameters,
                providedType = typeclassGoal(typeclassId, targetType),
                prerequisiteTypes = prerequisiteTypes,
            )
        return ResolvedRule(
            rule = rule,
            reference =
                RuleReference.Derived(
                    targetClass = this,
                    deriverCompanion = deriverCompanion,
                    shape = shape,
                    ruleTypeParameters = ruleTypeParameters,
                ),
            associatedOwner = targetClassId,
        )
    }

    private fun IrClass.buildDerivedProductShape(
        typeParameterBySymbol: Map<org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol, TcTypeParameter>,
    ): DerivedShape.Product? {
        val fields =
            structuralProperties().mapNotNull { property ->
                val getter = property.getter ?: return@mapNotNull null
                val fieldType = irTypeToModel(getter.returnType, typeParameterBySymbol) ?: return@mapNotNull null
                DerivedField(
                    name = property.name.asString(),
                    type = fieldType,
                    getter = getter,
                )
            }
        return DerivedShape.Product(fields)
    }

    private fun IrClass.buildDerivedSumShape(
        targetType: TcType.Constructor,
        subclassesBySuper: Map<String, Set<String>>,
    ): DerivedShape.Sum? {
        val directSubclasses = subclassesBySuper[classIdOrFail.asString()].orEmpty()
        if (directSubclasses.isEmpty()) {
            return null
        }
        val cases =
            directSubclasses.mapNotNull { subclassId ->
                val subclass = classesById[subclassId] ?: return@mapNotNull null
                val caseType = subclass.caseTypeForSealedBase(this, targetType) ?: return@mapNotNull null
                DerivedCase(
                    name = subclass.name.asString(),
                    klass = subclass,
                    type = caseType,
                )
            }
        return DerivedShape.Sum(cases)
    }

    private fun IrClass.caseTypeForSealedBase(
        sealedBase: IrClass,
        baseTargetType: TcType.Constructor,
    ): TcType? {
        val subclassClassId = classId ?: return null
        val caseTypeParameters =
            typeParameters.mapIndexed { index, typeParameter ->
                TcTypeParameter(
                    id = "${subclassClassId.asString()}#$index",
                    displayName = typeParameter.name.asString(),
                )
            }
        val caseTypeBySymbol = typeParameters.zip(caseTypeParameters).associate { (symbol, parameter) ->
            symbol.symbol to parameter
        }
        val subclassDeclaredType =
            TcType.Constructor(
                subclassClassId.asString(),
                caseTypeParameters.map { parameter -> TcType.Variable(parameter.id, parameter.displayName) },
            )
        val sealedSupertype =
            superTypes.firstOrNull { superType ->
                superType.classOrNull?.owner?.classId == sealedBase.classId
            } ?: return null
        val projectedSupertype = irTypeToModel(sealedSupertype, caseTypeBySymbol) ?: return null
        val bindings =
            unifyTypes(
                left = projectedSupertype,
                right = baseTargetType,
                bindableVariableIds = caseTypeParameters.mapTo(linkedSetOf(), TcTypeParameter::id),
            )
        return when {
            bindings != null -> subclassDeclaredType.substituteType(bindings)
            caseTypeParameters.isEmpty() -> subclassDeclaredType
            else -> null
        }
    }

    private fun IrClass.structuralProperties(): List<IrProperty> {
        return declarations.filterIsInstance<IrProperty>().filter { property ->
            property.getter != null && property.backingField != null
        }
    }

    private fun IrClass.derivedTypeclassIds(): Set<ClassId> {
        val annotation =
            annotations.firstOrNull { constructorCall ->
                constructorCall.symbol.owner.parentAsClass.classId == DERIVE_ANNOTATION_CLASS_ID
            } ?: return emptySet()
        val values = annotation.getValueArgument(0) as? IrVararg ?: return emptySet()
        return values.elements.mapNotNullTo(linkedSetOf()) { element ->
            val expression =
                when (element) {
                    is IrSpreadElement -> element.expression
                    is IrExpression -> element
                    else -> null
                } ?: return@mapNotNullTo null
            val classReference = expression as? IrClassReference ?: return@mapNotNullTo null
            val classId = classReference.classType.classOrNull?.owner?.classId ?: return@mapNotNullTo null
            val typeclassClass = pluginContext.referenceClass(classId)?.owner ?: return@mapNotNullTo null
            classId.takeIf { typeclassClass.hasAnnotation(TYPECLASS_ANNOTATION_CLASS_ID) }
        }
    }

    private fun IrClass.findDeriverCompanion(): IrClass? =
        declarations.filterIsInstance<IrClass>().singleOrNull { declaration ->
            declaration.isCompanion &&
                declaration.superTypes.any { superType ->
                    superType.classOrNull?.owner?.classId == TYPECLASS_DERIVER_CLASS_ID
                }
        }

    private fun typeclassGoal(
        typeclassId: ClassId,
        targetType: TcType,
    ): TcType = TcType.Constructor(typeclassId.asString(), listOf(targetType))
}

private data class ClassHierarchyInfo(
    val superClassifiers: List<String>,
    val isSealed: Boolean,
)

private data class ResolvedRule(
    val rule: InstanceRule,
    val reference: RuleReference,
    val associatedOwner: ClassId?,
)

private sealed interface RuleReference {
    data class DirectFunction(
        val function: IrSimpleFunction,
    ) : RuleReference

    data class LookupFunction(
        val callableId: CallableId,
        val shape: FunctionShape,
    ) : RuleReference

    data class DirectProperty(
        val property: IrProperty,
    ) : RuleReference

    data class LookupProperty(
        val callableId: CallableId,
    ) : RuleReference

    data class DirectObject(
        val klass: IrClass,
    ) : RuleReference

    data class LookupObject(
        val classId: ClassId,
    ) : RuleReference

    data class Derived(
        val targetClass: IrClass,
        val deriverCompanion: IrClass,
        val shape: DerivedShape,
        val ruleTypeParameters: List<TcTypeParameter>,
    ) : RuleReference
}

private sealed interface DerivedShape {
    data class Product(
        val fields: List<DerivedField>,
    ) : DerivedShape

    data class Sum(
        val cases: List<DerivedCase>,
    ) : DerivedShape
}

private data class DerivedField(
    val name: String,
    val type: TcType,
    val getter: IrSimpleFunction,
)

private data class DerivedCase(
    val name: String,
    val klass: IrClass,
    val type: TcType,
)

private data class FunctionShape(
    val dispatchReceiver: Boolean,
    val extensionReceiverType: TcType?,
    val typeParameterCount: Int,
    val contextParameterTypes: List<TcType>,
    val regularParameterTypes: List<TcType>,
)

private data class WrapperResolutionShape(
    val dispatchReceiver: Boolean,
    val extensionReceiverType: String?,
    val typeParameterCount: Int,
    val contextParameterTypes: List<String>,
    val regularParameterTypes: List<String>,
)

private data class VisibleTypeParameters(
    val bySymbol: Map<org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol, TcTypeParameter>,
    val byId: Map<String, org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol>,
)

private data class LocalTypeclassContext(
    val parameter: IrValueParameter,
    val providedType: TcType,
)

private data class NormalizedCallArguments(
    val dispatchReceiver: IrExpression?,
    val extensionReceiver: IrExpression?,
    val valueArguments: List<IrExpression?>,
)

private data class InferredOriginalTypeArguments(
    val callTypeArguments: List<IrType>,
    val substitutionBySymbol: Map<org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol, IrType>,
)

private sealed interface ExplicitArgument {
    data object Omitted : ExplicitArgument

    data class PassThrough(
        val expression: IrExpression,
    ) : ExplicitArgument
}

private fun IrSimpleFunction.isGeneratedTypeclassWrapper(): Boolean =
    (origin as? IrDeclarationOrigin.GeneratedByPlugin)?.pluginKey == TypeclassWrapperKey

private fun IrClass.isGeneratedTypeclassWrapperClass(): Boolean = false

private fun IrType.isTypeclassType(): Boolean =
    classOrNull?.owner?.hasAnnotation(TYPECLASS_ANNOTATION_CLASS_ID) == true

private fun IrSimpleFunction.requiresSyntheticTypeclassResolution(call: IrCall): Boolean =
    contextParameters().anyIndexed { parameterIndex, parameter ->
        val substitutedType =
            parameter.type.substitute(
                typeParameters.mapIndexedNotNull { index, typeParameter ->
                    call.getTypeArgument(index)?.let { argumentType ->
                        typeParameter.symbol to argumentType
                    }
                }.toMap(),
            )
        val existingArgument = call.getValueArgument(parameterIndex)
        substitutedType.isTypeclassType() &&
            (existingArgument == null || !existingArgument.type.satisfiesExpectedContextType(substitutedType))
    }

private inline fun <T> Iterable<T>.anyIndexed(predicate: (index: Int, T) -> Boolean): Boolean {
    var index = 0
    for (element in this) {
        if (predicate(index, element)) {
            return true
        }
        index += 1
    }
    return false
}

private fun IrClass.isInstanceObject(): Boolean =
    hasAnnotation(INSTANCE_ANNOTATION_CLASS_ID) && isObject

private fun IrProperty.backingFieldOrGetterType(): IrType =
    backingField?.type ?: getter?.returnType ?: error("Property $name has neither backing field nor getter type")

private fun IrFunction.contextParameters(): List<IrValueParameter> =
    parameters.filter { it.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Context }

private fun IrFunction.regularAndContextParameters(): List<IrValueParameter> =
    parameters.filter {
        (it.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Context ||
            it.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Regular) &&
            !it.isTypeclassWrapperMarkerParameter()
    }

private fun IrClass.primaryConstructorSymbol() =
    declarations.filterIsInstance<IrConstructor>()
        .singleOrNull { constructor -> constructor.isPrimary }
        ?.symbol
        ?: declarations.filterIsInstance<IrConstructor>().singleOrNull()?.symbol
        ?: error("Could not resolve a primary constructor for ${classIdOrFail}")

private fun IrClass.renderClassName(): String = classId?.asFqNameString() ?: name.asString()

private fun functionShape(
    function: IrSimpleFunction,
    dropTypeclassContexts: Boolean,
): FunctionShape {
    val placeholderParameters =
        visibleTypeParametersForShape(function).associateBy(TcTypeParameter::id)
    val bySymbol =
        visibleTypeParameterSymbols(listOf(function)).zip(placeholderParameters.values).associate { (symbol, parameter) ->
            symbol to parameter
        }
    val contextTypes =
        function.contextParameters()
            .filterNot { dropTypeclassContexts && it.type.isTypeclassType() }
            .map { parameter ->
                irTypeToModel(parameter.type, bySymbol)
                    ?: error("Unsupported signature type ${parameter.type} in ${function.callableId}")
            }
    val regularTypes =
        function.parameters.filter { it.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Regular }
            .filterNot(IrValueParameter::isTypeclassWrapperMarkerParameter)
            .map { parameter ->
                irTypeToModel(parameter.type, bySymbol)
                    ?: error("Unsupported signature type ${parameter.type} in ${function.callableId}")
            }
    val extensionType = function.extensionReceiverParameter?.type?.let { type -> irTypeToModel(type, bySymbol) }
    return FunctionShape(
        dispatchReceiver = function.dispatchReceiverParameter != null,
        extensionReceiverType = extensionType,
        typeParameterCount = function.visibleSignatureTypeParameterCount(dropTypeclassContexts),
        contextParameterTypes = contextTypes,
        regularParameterTypes = regularTypes,
    )
}

private fun wrapperResolutionShape(
    function: IrSimpleFunction,
    dropTypeclassContexts: Boolean,
): WrapperResolutionShape {
    val contextTypes =
        function.contextParameters()
            .filterNot { dropTypeclassContexts && it.type.isTypeclassType() }
            .map { parameter -> parameter.type.render() }
    val regularTypes =
        function.parameters.filter { it.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Regular }
            .filterNot(IrValueParameter::isTypeclassWrapperMarkerParameter)
            .map { parameter -> parameter.type.render() }
    val extensionType = function.extensionReceiverParameter?.type?.render()
    return WrapperResolutionShape(
        dispatchReceiver = function.dispatchReceiverParameter != null,
        extensionReceiverType = extensionType,
        typeParameterCount = function.visibleSignatureTypeParameterCount(dropTypeclassContexts),
        contextParameterTypes = contextTypes,
        regularParameterTypes = regularTypes,
    )
}

private fun visibleTypeParameters(enclosingFunctions: List<IrFunction>): VisibleTypeParameters {
    val symbols = visibleTypeParameterSymbols(enclosingFunctions)
    val parameters =
        symbols.map { symbol ->
            TcTypeParameter(
                id = visibleTypeParameterId(symbol),
                displayName = symbol.owner.name.asString(),
            )
        }
    return VisibleTypeParameters(
        bySymbol = symbols.zip(parameters).toMap(),
        byId = parameters.zip(symbols).associate { (parameter, symbol) -> parameter.id to symbol },
    )
}

private fun visibleTypeParametersForShape(function: IrFunction): List<TcTypeParameter> =
    visibleTypeParameterSymbols(listOf(function)).mapIndexed { index, _ ->
        TcTypeParameter(id = "P$index", displayName = "P$index")
    }

private fun visibleTypeParameterSymbols(enclosingFunctions: List<IrFunction>): List<org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol> {
    val result = mutableListOf<org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol>()
    enclosingFunctions.firstOrNull()?.let { outermostFunction ->
        collectParentTypeParameters(outermostFunction.parent, result)
    }
    enclosingFunctions.forEach { function ->
        function.typeParameters.mapTo(result) { it.symbol }
    }
    return result
}

private fun collectParentTypeParameters(
    parent: org.jetbrains.kotlin.ir.declarations.IrDeclarationParent,
    sink: MutableList<org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol>,
) {
    when (parent) {
        is IrClass -> {
            collectParentTypeParameters(parent.parent, sink)
            parent.typeParameters.mapTo(sink) { it.symbol }
        }

        is IrFunction -> {
            collectParentTypeParameters(parent.parent, sink)
            parent.typeParameters.mapTo(sink) { it.symbol }
        }
    }
}

private fun visibleTypeParameterId(symbol: org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol): String =
    when (val parent = symbol.owner.parent) {
        is IrClass -> "class:${parent.classIdOrFail.asString()}:${symbol.owner.name}"
        is IrSimpleFunction -> ruleTypeParameterId(parent, symbol.owner)
        else -> "type:${symbol.owner.name}:${symbol.hashCode()}"
    }

private fun IrFunction.enclosingFunctions(): List<IrFunction> {
    val result = ArrayDeque<IrFunction>()
    var current: org.jetbrains.kotlin.ir.declarations.IrDeclarationParent? = this
    while (current is IrDeclaration) {
        if (current is IrFunction) {
            result.addFirst(current)
        }
        current = current.parent
    }
    return result.toList()
}

private fun typeParameterOwnerId(function: IrSimpleFunction): String {
    val shape = wrapperResolutionShape(function, dropTypeclassContexts = false)
    return buildString {
        append(function.safeCallableIdentity())
        append("#dispatch=")
        append(shape.dispatchReceiver)
        append("#ext=")
        append(shape.extensionReceiverType ?: "-")
        append("#ctx=")
        append(shape.contextParameterTypes.joinToString(","))
        append("#regular=")
        append(shape.regularParameterTypes.joinToString(","))
        append("#tp=")
        append(shape.typeParameterCount)
    }
}

private fun IrSimpleFunction.safeCallableIdentity(): String =
    runCatching { callableId.toString() }.getOrElse {
        buildString {
            append("local:")
            append(name.asString())
            append('@')
            append(startOffset)
            append(':')
            append(endOffset)
            append("#parent=")
            append(parent.localDeclarationIdentity())
        }
    }

private fun org.jetbrains.kotlin.ir.declarations.IrDeclarationParent.localDeclarationIdentity(): String =
    when (this) {
        is IrSimpleFunction ->
            buildString {
                append("fun:")
                append(name.asString())
                append('@')
                append(startOffset)
                append(':')
                append(endOffset)
                append("->")
                append(parent.localDeclarationIdentity())
            }
        is IrClass -> "class:${name.asString()}"
        is IrFile -> "file:${fileEntry.name}"
        else -> this::class.simpleName ?: "unknown-parent"
    }

private fun ruleTypeParameterId(
    function: IrSimpleFunction,
    typeParameter: IrTypeParameter,
): String = "function:${typeParameterOwnerId(function)}:${typeParameter.name}"

private fun typeParametersBySymbol(
    function: IrSimpleFunction,
    parameters: List<TcTypeParameter>,
): Map<org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol, TcTypeParameter> =
    function.typeParameters.zip(parameters).associate { (typeParameter, parameter) ->
        typeParameter.symbol to parameter
    }

private fun collectLocalContexts(
    enclosingFunctions: List<IrFunction>,
    visible: VisibleTypeParameters,
): List<LocalTypeclassContext> {
    return buildList {
        enclosingFunctions.asReversed().forEach { function ->
            function.extensionReceiverParameter
                ?.takeIf { parameter -> parameter.type.isTypeclassType() }
                ?.let { parameter ->
                    listOf(parameter.type)
                        .providedTypeExpansion(visible.bySymbol)
                        .validTypes
                        .forEach { providedType ->
                            add(LocalTypeclassContext(parameter = parameter, providedType = providedType))
                        }
                }
            function.contextParameters()
                .filter { parameter -> parameter.type.isTypeclassType() }
                .forEach { parameter ->
                    listOf(parameter.type)
                        .providedTypeExpansion(visible.bySymbol)
                        .validTypes
                        .forEach { providedType ->
                            add(LocalTypeclassContext(parameter = parameter, providedType = providedType))
                        }
                }
        }
    }
}

private fun irTypeToModel(
    type: IrType,
    typeParameterBySymbol: Map<org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol, TcTypeParameter>,
): TcType? {
    val simpleType = type as? IrSimpleType ?: return null
    return when (val classifier = simpleType.classifier) {
        is org.jetbrains.kotlin.ir.symbols.IrClassSymbol -> {
            val classifierId = classifier.owner.classId?.asString() ?: return null
            val arguments =
                simpleType.arguments.map { argument ->
                    when (argument) {
                        is IrType -> irTypeToModel(argument, typeParameterBySymbol)
                        is org.jetbrains.kotlin.ir.types.IrTypeProjection -> irTypeToModel(argument.type, typeParameterBySymbol)
                        is IrStarProjection -> null
                    } ?: return null
                }
            TcType.Constructor(classifierId, arguments, isNullable = type.isNullable())
        }

        is org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol -> {
            val parameter =
                typeParameterBySymbol[classifier]
                    ?: typeParameterBySymbol.values.singleOrNull { candidate ->
                        candidate.displayName == classifier.owner.name.asString()
                    }
                    ?: return null
            TcType.Variable(parameter.id, parameter.displayName)
        }

        else -> null
    }
}

private fun Iterable<IrType>.providedTypeExpansion(
    typeParameterBySymbol: Map<org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol, TcTypeParameter>,
): ProvidedTypeExpansion {
    val validTypes = linkedMapOf<String, TcType>()
    val invalidTypes = linkedMapOf<String, TcType>()
    for (type in this) {
        val expansion =
            type.providedTypeExpansion(
                typeParameterBySymbol = typeParameterBySymbol,
                previousWereTypeclass = true,
                visited = emptySet(),
            )
        expansion.validTypes.forEach { candidate ->
            validTypes.putIfAbsent(candidate.render(), candidate)
        }
        expansion.invalidTypes.forEach { candidate ->
            invalidTypes.putIfAbsent(candidate.render(), candidate)
        }
    }
    return ProvidedTypeExpansion(
        validTypes = validTypes.values.toList(),
        invalidTypes = invalidTypes.values.toList(),
    )
}

private fun IrType.providedTypeExpansion(
    typeParameterBySymbol: Map<org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol, TcTypeParameter>,
    previousWereTypeclass: Boolean,
    visited: Set<String>,
): ProvidedTypeExpansion {
    val simpleType = this as? IrSimpleType ?: return ProvidedTypeExpansion(emptyList(), emptyList())
    val currentType = irTypeToModel(this, typeParameterBySymbol) ?: return ProvidedTypeExpansion(emptyList(), emptyList())
    val visitKey = currentType.render()
    if (visitKey in visited) {
        return ProvidedTypeExpansion(emptyList(), emptyList())
    }

    val classSymbol = simpleType.classOrNull ?: return ProvidedTypeExpansion(emptyList(), emptyList())
    val currentIsTypeclass = classSymbol.owner.hasAnnotation(TYPECLASS_ANNOTATION_CLASS_ID)
    val validTypes = linkedMapOf<String, TcType>()
    val invalidTypes = linkedMapOf<String, TcType>()
    if (currentIsTypeclass) {
        if (previousWereTypeclass) {
            validTypes[visitKey] = currentType
        } else {
            invalidTypes[visitKey] = currentType
        }
    }

    val substitutions =
        classSymbol.owner.typeParameters.zip(simpleType.arguments).mapNotNull { (parameter, argument) ->
            argument.argumentTypeOrNull()?.let { type -> parameter.symbol to type }
        }.toMap()
    val nextPreviousWereTypeclass = previousWereTypeclass && currentIsTypeclass
    val nextVisited = visited + visitKey
    classSymbol.owner.superTypes.forEach { superType ->
        val substitutedSuperType =
            if (substitutions.isEmpty()) {
                superType
            } else {
                superType.substitute(substitutions)
            }
        val nested =
            substitutedSuperType.providedTypeExpansion(
                typeParameterBySymbol = typeParameterBySymbol,
                previousWereTypeclass = nextPreviousWereTypeclass,
                visited = nextVisited,
            )
        nested.validTypes.forEach { candidate ->
            validTypes.putIfAbsent(candidate.render(), candidate)
        }
        nested.invalidTypes.forEach { candidate ->
            invalidTypes.putIfAbsent(candidate.render(), candidate)
        }
    }

    return ProvidedTypeExpansion(
        validTypes = validTypes.values.toList(),
        invalidTypes = invalidTypes.values.toList(),
    )
}

private fun org.jetbrains.kotlin.ir.types.IrTypeArgument.argumentTypeOrNull(): IrType? =
    when (this) {
        is IrType -> this
        is org.jetbrains.kotlin.ir.types.IrTypeProjection -> type
        is IrStarProjection -> null
    }

private fun modelToIrType(
    type: TcType,
    visibleTypeParameters: VisibleTypeParameters,
    pluginContext: IrPluginContext,
): IrType =
    when (type) {
        is TcType.Variable -> {
            val symbol = visibleTypeParameters.byId[type.id]
                ?: error("Unbound type variable ${type.displayName}")
            symbol.defaultType
        }

        is TcType.Constructor -> {
            val classId = ClassId.fromString(type.classifierId)
            val classifier = pluginContext.referenceClass(classId)
                ?: error("Could not resolve classifier ${type.classifierId}")
            classifier.typeWith(type.arguments.map { nested -> modelToIrType(nested, visibleTypeParameters, pluginContext) })
                .let { irType -> if (type.isNullable) irType.makeNullable() else irType }
        }
    }

private fun <T> Iterator<T>.nextOrNull(): T? = if (hasNext()) next() else null

private fun IrCall.valueArguments(): List<IrExpression?> =
    (0 until valueArgumentsCount).map(::getValueArgument)

private fun IrCall.normalizedArgumentsForTypeclassRewrite(original: IrSimpleFunction): NormalizedCallArguments {
    val rawValueArguments = valueArguments()
    if (origin == IrStatementOrigin.GET_ARRAY_ELEMENT && original.extensionReceiverParameter != null && rawValueArguments.isNotEmpty()) {
        return NormalizedCallArguments(
            dispatchReceiver = dispatchReceiver,
            extensionReceiver = rawValueArguments.first(),
            valueArguments =
                buildList {
                    add(extensionReceiver)
                    addAll(rawValueArguments.drop(1))
                },
        )
    }

    return NormalizedCallArguments(
        dispatchReceiver = dispatchReceiver,
        extensionReceiver = extensionReceiver,
        valueArguments = rawValueArguments,
    )
}

private fun extractExplicitArguments(
    original: IrSimpleFunction,
    normalizedValueArguments: List<IrExpression?>,
    substitutionBySymbol: Map<org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol, IrType>,
): List<ExplicitArgument> {
    val originalParameters = original.regularAndContextParameters()
    val currentToOriginal =
        mapCurrentArgumentsToOriginalParameters(
            originalParameters = originalParameters,
            currentArguments = normalizedValueArguments,
            typeArgumentMap = substitutionBySymbol,
            visibleTypeParameters = null,
        )

    val explicitByOriginalIndex = linkedMapOf<Int, ExplicitArgument>()
    currentToOriginal.forEach { (currentIndex, originalIndex) ->
        val argument = normalizedValueArguments.getOrNull(currentIndex)
        val parameter = originalParameters[originalIndex]
        explicitByOriginalIndex[originalIndex] =
            when {
                argument != null -> ExplicitArgument.PassThrough(argument)
                parameter.defaultValue != null -> ExplicitArgument.Omitted
                parameter.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Context -> ExplicitArgument.Omitted
                else -> error("Missing explicit argument for ${original.callableId}")
            }
    }

    return originalParameters.mapIndexedNotNull { index, parameter ->
        val substitutedType = parameter.type.substitute(substitutionBySymbol)
        if (parameter.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Context && substitutedType.isTypeclassType()) {
            return@mapIndexedNotNull null
        }
        explicitByOriginalIndex[index] ?: when {
            parameter.defaultValue != null -> ExplicitArgument.Omitted
            parameter.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Context -> ExplicitArgument.Omitted
            else -> error("Missing explicit argument for ${original.callableId}")
        }
    }
}

private fun IrType.matchesTypeclassParameterType(
    expected: IrType,
    visibleTypeParameters: VisibleTypeParameters,
): Boolean =
    irTypeToModel(this, visibleTypeParameters.bySymbol) == irTypeToModel(expected, visibleTypeParameters.bySymbol)

private fun IrType.satisfiesExpectedContextType(
    expected: IrType,
    visibleTypeParameters: VisibleTypeParameters? = null,
    visited: MutableSet<String> = linkedSetOf(),
): Boolean {
    if (this == expected) {
        return true
    }
    if (visibleTypeParameters != null && matchesTypeclassParameterType(expected, visibleTypeParameters)) {
        return true
    }
    val owner = classOrNull?.owner ?: return false
    val visitKey = owner.classId?.asString() ?: owner.name.asString()
    if (!visited.add(visitKey)) {
        return false
    }
    return owner.superTypes.any { superType ->
        superType == expected ||
            (visibleTypeParameters != null && superType.matchesTypeclassParameterType(expected, visibleTypeParameters)) ||
            superType.satisfiesExpectedContextType(expected, visibleTypeParameters, visited)
    }
}

private fun IrValueParameter.isTypeclassWrapperMarkerParameter(): Boolean =
    name.asString() == TYPECLASS_WRAPPER_MARKER_PARAMETER_NAME

private fun IrSimpleFunction.visibleSignatureTypeParameterCount(
    dropTypeclassContexts: Boolean,
): Int {
    val referenced = linkedSetOf<org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol>()

    extensionReceiverParameter?.type?.collectReferencedTypeParameters(referenced)
    returnType.collectReferencedTypeParameters(referenced)
    parameters
        .filter { parameter ->
            (parameter.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Regular && !parameter.isTypeclassWrapperMarkerParameter()) ||
                (parameter.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Context &&
                    !(dropTypeclassContexts && parameter.type.isTypeclassType()))
        }.forEach { parameter ->
            parameter.type.collectReferencedTypeParameters(referenced)
        }

    return typeParameters.count { it.symbol in referenced }
}

private fun IrType.collectReferencedTypeParameters(
    sink: MutableSet<org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol>,
) {
    val simpleType = this as? IrSimpleType ?: return
    when (val classifier = simpleType.classifier) {
        is org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol -> sink += classifier
        is org.jetbrains.kotlin.ir.symbols.IrClassSymbol -> Unit
        else -> Unit
    }
    simpleType.arguments.forEach { argument ->
        when (argument) {
            is IrType -> argument.collectReferencedTypeParameters(sink)
            is org.jetbrains.kotlin.ir.types.IrTypeProjection -> argument.type.collectReferencedTypeParameters(sink)
            is IrStarProjection -> Unit
        }
    }
}

private fun inferOriginalTypeArguments(
    original: IrSimpleFunction,
    normalizedCall: NormalizedCallArguments,
    currentCallTypeArgumentsByName: Map<String, IrType>,
    visibleTypeParameters: VisibleTypeParameters,
    localContexts: List<LocalTypeclassContext>,
    pluginContext: IrPluginContext,
): InferredOriginalTypeArguments {
    val originalTypeParameterBySymbol =
        visibleTypeParameterSymbols(listOf(original)).associateWith { symbol ->
            TcTypeParameter(
                id = visibleTypeParameterId(symbol),
                displayName = symbol.owner.name.asString(),
            )
        }
    val bindableVariableIds = originalTypeParameterBySymbol.values.mapTo(linkedSetOf(), TcTypeParameter::id)
    val bindings = linkedMapOf<String, TcType>()

    fun mergeBindingsFromIrTypes(
        expectedType: IrType,
        actualType: IrType,
    ) {
        val expectedModel =
            irTypeToModel(expectedType, originalTypeParameterBySymbol)
                ?.substituteType(bindings)
                ?: return
        actualType.inferenceModels(visibleTypeParameters).forEach { actualModel ->
            val candidateBindings = unifyTypes(expectedModel, actualModel, bindableVariableIds) ?: return@forEach
            mergeTypeBindings(bindings, candidateBindings, bindableVariableIds)
        }
    }

    original.dispatchReceiverParameter?.type?.let { expectedDispatchType ->
        normalizedCall.dispatchReceiver?.type?.let { actualDispatchType ->
            mergeBindingsFromIrTypes(expectedDispatchType, actualDispatchType)
        }
    }

    original.extensionReceiverParameter?.type?.let { expectedExtensionType ->
        normalizedCall.extensionReceiver?.type?.let { actualExtensionType ->
            mergeBindingsFromIrTypes(expectedExtensionType, actualExtensionType)
        }
    }

    val originalParameters = original.regularAndContextParameters()
    val currentToOriginal =
        mapCurrentArgumentsToOriginalParameters(
            originalParameters = originalParameters,
            currentArguments = normalizedCall.valueArguments,
            typeArgumentMap = null,
            visibleTypeParameters = null,
        )
    currentToOriginal.forEach { (currentValueArgumentIndex, originalParameterIndex) ->
        val parameter = originalParameters[originalParameterIndex]
        val actualArgumentType = normalizedCall.valueArguments.getOrNull(currentValueArgumentIndex)?.type
        if (actualArgumentType != null) {
            mergeBindingsFromIrTypes(parameter.type, actualArgumentType)
        }
    }

    original.contextParameters()
        .filter { parameter -> parameter.type.isTypeclassType() }
        .forEach { parameter ->
            val goalType =
                irTypeToModel(parameter.type, originalTypeParameterBySymbol)
                    ?.substituteType(bindings)
                    ?: error("Unsupported typeclass context ${parameter.type} in ${original.callableId}")
            localContexts.forEach { localContext ->
                val candidateBindings =
                    unifyTypes(goalType, localContext.providedType, bindableVariableIds) ?: return@forEach
                mergeTypeBindings(bindings, candidateBindings, bindableVariableIds)
            }
        }

    original.typeParameters.forEach { typeParameter ->
        val bindingId = ruleTypeParameterId(original, typeParameter)
        if (bindingId in bindings) {
            return@forEach
        }
        val currentType = currentCallTypeArgumentsByName[typeParameter.name.asString()] ?: return@forEach
        val currentModel =
            irTypeToModel(currentType, visibleTypeParameters.bySymbol)
                ?: error("Unsupported type argument $currentType for ${original.callableId}")
        bindings[bindingId] = currentModel
    }

    val substitutionBySymbol =
        originalTypeParameterBySymbol.mapNotNull { (symbol, parameter) ->
            val model = bindings[parameter.id]?.substituteType(bindings) ?: return@mapNotNull null
            symbol to modelToIrType(model, visibleTypeParameters, pluginContext)
        }.toMap()

    val callTypeArguments =
        original.typeParameters.map { typeParameter ->
            val parameter =
                originalTypeParameterBySymbol[typeParameter.symbol]
                    ?: error("Missing visible type parameter metadata for ${typeParameter.symbol}")
            val model =
                bindings[parameter.id]?.substituteType(bindings)
                    ?: throw TypeArgumentInferenceFailure(
                        missingTypeArgumentMessage(original, typeParameter),
                    )
            modelToIrType(model, visibleTypeParameters, pluginContext)
        }

    return InferredOriginalTypeArguments(
        callTypeArguments = callTypeArguments,
        substitutionBySymbol = substitutionBySymbol,
    )
}

private class TypeArgumentInferenceFailure(
    message: String,
) : IllegalStateException(message)

private fun missingTypeArgumentMessage(
    original: IrSimpleFunction,
    typeParameter: IrTypeParameter,
): String {
    val typeclassContextCount = original.contextParameters().count { parameter -> parameter.type.isTypeclassType() }
    return if (typeclassContextCount > 1) {
        "Conflicting type bindings prevented inferring ${typeParameter.name} in ${original.renderIdentity()}"
    } else {
        "Missing type argument for ${typeParameter.name} in ${original.renderIdentity()}"
    }
}

private fun mergeTypeBindings(
    existing: MutableMap<String, TcType>,
    incoming: Map<String, TcType>,
    bindableVariableIds: Set<String>,
) {
    incoming.forEach { (key, value) ->
        val current = existing[key]
        if (current == null) {
            existing[key] = value
        } else {
            val unified = unifyTypes(current, value, bindableVariableIds)
                ?: throw TypeArgumentInferenceFailure(
                    "Conflicting type bindings for $key: ${current.render()} vs ${value.render()}",
                )
            unified.forEach { (nestedKey, nestedValue) ->
                existing[nestedKey] = nestedValue
            }
        }
    }
}

private fun IrType.inferenceModels(
    visibleTypeParameters: VisibleTypeParameters,
): List<TcType> {
    val directModel = irTypeToModel(this, visibleTypeParameters.bySymbol)
    val expandedModels =
        listOf(this)
            .providedTypeExpansion(visibleTypeParameters.bySymbol)
            .validTypes
    return buildList {
        directModel?.let(::add)
        expandedModels.forEach { candidate ->
            if (candidate != directModel) {
                add(candidate)
            }
        }
    }
}

private fun IrFunction.renderIdentity(): String =
    (this as? IrSimpleFunction)?.let { function ->
        runCatching { function.callableId.toString() }.getOrElse { function.name.asString() }
    } ?: name.asString()

private fun mapCurrentArgumentsToOriginalParameters(
    originalParameters: List<IrValueParameter>,
    currentArguments: List<IrExpression?>,
    typeArgumentMap: Map<org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol, IrType>?,
    visibleTypeParameters: VisibleTypeParameters?,
): List<Pair<Int, Int>> {
    val mapping = mutableListOf<Pair<Int, Int>>()
    var currentIndex = 0
    originalParameters.forEachIndexed { originalIndex, parameter ->
        if (currentIndex >= currentArguments.size) {
            return@forEachIndexed
        }
        if (parameter.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Context && parameter.type.isTypeclassType()) {
            val remainingCurrentArguments = currentArguments.size - currentIndex
            val remainingRequiredArguments =
                originalParameters.drop(originalIndex).count { remainingParameter ->
                    remainingParameter.kind != org.jetbrains.kotlin.ir.declarations.IrParameterKind.Context ||
                        !remainingParameter.type.isTypeclassType()
                }
            val currentArgument = currentArguments[currentIndex]
            val substitutedType = typeArgumentMap?.let(parameter.type::substitute) ?: parameter.type
            val currentArgumentProvidesTypeclass =
                currentArgument != null &&
                    (
                        currentArgument.type.isTypeclassType() ||
                            listOf(currentArgument.type)
                                .providedTypeExpansion(visibleTypeParameters?.bySymbol.orEmpty())
                                .validTypes
                                .isNotEmpty()
                    )
            val preservedTypeclassContext =
                remainingCurrentArguments > remainingRequiredArguments &&
                    currentArgumentProvidesTypeclass &&
                    (
                        visibleTypeParameters == null ||
                            currentArgument.type.satisfiesExpectedContextType(substitutedType, visibleTypeParameters)
                    )
            if (preservedTypeclassContext) {
                mapping += currentIndex to originalIndex
                currentIndex += 1
            }
            return@forEachIndexed
        }
        mapping += currentIndex to originalIndex
        currentIndex += 1
    }
    return mapping
}
