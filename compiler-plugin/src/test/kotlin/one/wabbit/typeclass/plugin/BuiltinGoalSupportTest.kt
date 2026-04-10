// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.typeclass.plugin

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import one.wabbit.typeclass.plugin.model.TcType
import org.jetbrains.kotlin.types.Variance

class BuiltinGoalSupportTest {
    @Test
    fun `kclass admissibility rejects nullable and non-materializable targets`() {
        val nullableGoal =
            typeConstructor(
                KCLASS_CLASS_ID.asString(),
                typeConstructor("demo.Value", isNullable = true),
            )
        val variableGoal =
            typeConstructor(
                KCLASS_CLASS_ID.asString(),
                typeConstructor("demo.Box", typeVariable("T")),
            )

        assertFalse(supportsBuiltinKClassGoal(nullableGoal))
        assertFalse(supportsBuiltinKClassGoal(variableGoal) { false })
        assertTrue(supportsBuiltinKClassGoal(variableGoal) { it == "T" })
    }

    @Test
    fun `known type and type id admissibility follow nested runtime materialization`() {
        val target =
            typeConstructor("demo.Box", projected(Variance.OUT_VARIANCE, typeVariable("T")))
        val knownTypeGoal = typeConstructor(KNOWN_TYPE_CLASS_ID.asString(), target)
        val typeIdGoal = typeConstructor(TYPE_ID_CLASS_ID.asString(), target)

        assertFalse(supportsBuiltinKnownTypeGoal(knownTypeGoal) { false })
        assertFalse(supportsBuiltinTypeIdGoal(typeIdGoal) { false })
        assertTrue(supportsBuiltinKnownTypeGoal(knownTypeGoal) { it == "T" })
        assertTrue(supportsBuiltinTypeIdGoal(typeIdGoal) { it == "T" })
    }

    @Test
    fun `runtime type materialization rejects top level stars and projections but keeps nested ones`() {
        val topLevelProjection = projected(Variance.OUT_VARIANCE, typeVariable("T"))

        assertFalse(supportsRuntimeTypeMaterialization(TcType.StarProjection) { true })
        assertFalse(supportsRuntimeTypeMaterialization(topLevelProjection) { true })
        assertTrue(
            supportsRuntimeTypeMaterialization(typeConstructor("demo.Box", TcType.StarProjection)) {
                true
            }
        )
        assertTrue(
            supportsRuntimeTypeMaterialization(
                typeConstructor("demo.Box", projected(Variance.OUT_VARIANCE, typeVariable("T")))
            ) {
                it == "T"
            }
        )
    }

    @Test
    fun `builtin admissibility rejects top level stars and projections`() {
        val knownTypeStarGoal =
            typeConstructor(KNOWN_TYPE_CLASS_ID.asString(), TcType.StarProjection)
        val typeIdProjectedGoal =
            typeConstructor(
                TYPE_ID_CLASS_ID.asString(),
                projected(Variance.OUT_VARIANCE, typeConstructor("kotlin.Any", isNullable = true)),
            )
        val kClassStarGoal = typeConstructor(KCLASS_CLASS_ID.asString(), TcType.StarProjection)
        val kSerializerProjectedGoal =
            typeConstructor(
                KSERIALIZER_CLASS_ID.asString(),
                projected(Variance.OUT_VARIANCE, typeConstructor("kotlin.Any", isNullable = true)),
            )

        assertFalse(supportsBuiltinKnownTypeGoal(knownTypeStarGoal) { true })
        assertFalse(supportsBuiltinTypeIdGoal(typeIdProjectedGoal) { true })
        assertFalse(supportsBuiltinKClassGoal(kClassStarGoal) { true })
        assertFalse(supportsBuiltinKSerializerShape(kSerializerProjectedGoal) { true })
    }

    @Test
    fun `kserializer shape rejects stars and non-materializable nested variables`() {
        val starGoal =
            typeConstructor(
                KSERIALIZER_CLASS_ID.asString(),
                typeConstructor("demo.Box", TcType.StarProjection),
            )
        val variableGoal =
            typeConstructor(
                KSERIALIZER_CLASS_ID.asString(),
                typeConstructor("demo.Box", typeVariable("T")),
            )

        assertFalse(supportsBuiltinKSerializerShape(starGoal))
        assertFalse(supportsBuiltinKSerializerShape(variableGoal) { false })
        assertTrue(supportsBuiltinKSerializerShape(variableGoal) { it == "T" })
    }

    @Test
    fun `builtin head filtering rejects unrelated constructor goals but keeps variables`() {
        val unrelatedGoal = typeConstructor("demo.Show", typeConstructor("kotlin.Int"))
        val kClassGoal = typeConstructor(KCLASS_CLASS_ID.asString(), typeConstructor("kotlin.Int"))
        val variableGoal = typeVariable("T")

        assertFalse(builtinRuleCanMatchGoalHead("builtin:kclass", unrelatedGoal))
        assertFalse(builtinRuleCanMatchGoalHead("builtin:nullable", unrelatedGoal))
        assertFalse(builtinRuleCanMatchGoalHead("builtin:type-id", unrelatedGoal))
        assertFalse(builtinRuleCanMatchGoalHead("builtin:subtype", unrelatedGoal))
        assertTrue(builtinRuleCanMatchGoalHead("builtin:kclass", kClassGoal))
        assertFalse(builtinRuleCanMatchGoalHead("builtin:type-id", kClassGoal))
        assertTrue(builtinRuleCanMatchGoalHead("builtin:kclass", variableGoal))
        assertTrue(builtinRuleCanMatchGoalHead("manual:show", unrelatedGoal))
    }

    @Test
    fun `is typeclass instance rejects top level projected typeclass applications`() {
        val projectedTargetGoal =
            typeConstructor(
                IS_TYPECLASS_INSTANCE_CLASS_ID.asString(),
                projected(
                    Variance.OUT_VARIANCE,
                    typeConstructor(KCLASS_CLASS_ID.asString(), typeConstructor("kotlin.Int")),
                ),
            )

        assertFalse(
            supportsBuiltinIsTypeclassInstanceGoal(
                goal = projectedTargetGoal,
                isTypeclassClassifier = { classifierId ->
                    classifierId == KCLASS_CLASS_ID.asString()
                },
            )
        )
        assertFalse(
            provablySupportsBuiltinIsTypeclassInstanceGoal(
                goal = projectedTargetGoal,
                isTypeclassClassifier = { classifierId ->
                    classifierId == KCLASS_CLASS_ID.asString()
                },
            )
        )
    }

    @Test
    fun `is typeclass instance only accepts plausible typeclass applications`() {
        val nonTypeclassGoal =
            typeConstructor(
                IS_TYPECLASS_INSTANCE_CLASS_ID.asString(),
                typeConstructor("demo.NotATypeclass", typeConstructor("kotlin.Int")),
            )
        val typeclassGoal =
            typeConstructor(
                IS_TYPECLASS_INSTANCE_CLASS_ID.asString(),
                typeConstructor("demo.Show", typeConstructor("kotlin.Int")),
            )
        val variableGoal =
            typeConstructor(IS_TYPECLASS_INSTANCE_CLASS_ID.asString(), typeVariable("TC"))

        assertFalse(
            supportsBuiltinIsTypeclassInstanceGoal(
                goal = nonTypeclassGoal,
                isTypeclassClassifier = { classifierId -> classifierId == "demo.Show" },
            )
        )
        assertTrue(
            supportsBuiltinIsTypeclassInstanceGoal(
                goal = typeclassGoal,
                isTypeclassClassifier = { classifierId -> classifierId == "demo.Show" },
            )
        )
        assertTrue(
            supportsBuiltinIsTypeclassInstanceGoal(
                goal = variableGoal,
                isTypeclassClassifier = { false },
            )
        )
    }

    @Test
    fun `provable-only subtype support rejects speculative variables`() {
        val speculativeGoal =
            typeConstructor(
                SUBTYPE_CLASS_ID.asString(),
                typeVariable("T"),
                typeConstructor("kotlin.Number"),
            )
        val provableGoal =
            typeConstructor(
                SUBTYPE_CLASS_ID.asString(),
                typeConstructor("demo.Dog"),
                typeConstructor("demo.Animal"),
            )
        val classInfo =
            mapOf(
                "demo.Dog" to
                    VisibleClassHierarchyInfo(
                        superClassifiers = setOf("demo.Animal"),
                        isSealed = false,
                        typeParameterVariances = emptyList(),
                    ),
                "demo.Animal" to
                    VisibleClassHierarchyInfo(
                        superClassifiers = emptySet(),
                        isSealed = false,
                        typeParameterVariances = emptyList(),
                    ),
            )

        assertTrue(supportsBuiltinSubtypeGoal(speculativeGoal, classInfo))
        assertFalse(provablySupportsBuiltinSubtypeGoal(speculativeGoal, classInfo))
        assertTrue(provablySupportsBuiltinSubtypeGoal(provableGoal, classInfo))
    }

    @Test
    fun `cross-classifier generic subtype goals reject incompatible projected supertype arguments`() {
        val goal =
            typeConstructor(
                SUBTYPE_CLASS_ID.asString(),
                typeConstructor("demo.Foo"),
                typeConstructor("kotlin.collections.Collection", typeConstructor("kotlin.Int")),
            )
        val classInfo =
            mapOf(
                "demo.Foo" to
                    VisibleClassHierarchyInfo(
                        superClassifiers = setOf("kotlin.collections.Collection"),
                        isSealed = false,
                        typeParameterVariances = emptyList(),
                        directSuperTypes =
                            listOf(
                                typeConstructor(
                                    "kotlin.collections.Collection",
                                    typeConstructor("kotlin.String"),
                                )
                                    as TcType.Constructor
                            ),
                    ),
                "kotlin.collections.Collection" to
                    VisibleClassHierarchyInfo(
                        superClassifiers = emptySet(),
                        isSealed = false,
                        typeParameterVariances = listOf(Variance.OUT_VARIANCE),
                    ),
                "kotlin.String" to
                    VisibleClassHierarchyInfo(
                        superClassifiers = emptySet(),
                        isSealed = false,
                        typeParameterVariances = emptyList(),
                    ),
                "kotlin.Int" to
                    VisibleClassHierarchyInfo(
                        superClassifiers = emptySet(),
                        isSealed = false,
                        typeParameterVariances = emptyList(),
                    ),
            )

        assertFalse(supportsBuiltinSubtypeGoal(goal, classInfo))
        assertFalse(provablySupportsBuiltinSubtypeGoal(goal, classInfo))
    }

    @Test
    fun `cross-classifier generic subtype goals follow hierarchy speculatively`() {
        val listToCollection =
            typeConstructor(
                SUBTYPE_CLASS_ID.asString(),
                typeConstructor("kotlin.collections.List", typeConstructor("kotlin.String")),
                typeConstructor("kotlin.collections.Collection", typeConstructor("kotlin.String")),
            )
        val mutableListToList =
            typeConstructor(
                SUBTYPE_CLASS_ID.asString(),
                typeConstructor("kotlin.collections.MutableList", typeConstructor("kotlin.Int")),
                typeConstructor("kotlin.collections.List", typeConstructor("kotlin.Int")),
            )
        val unrelated =
            typeConstructor(
                SUBTYPE_CLASS_ID.asString(),
                typeConstructor("kotlin.collections.List", typeConstructor("kotlin.String")),
                typeConstructor("kotlin.collections.Set", typeConstructor("kotlin.String")),
            )
        val classInfo =
            mapOf(
                "kotlin.collections.MutableList" to
                    VisibleClassHierarchyInfo(
                        superClassifiers = setOf("kotlin.collections.List"),
                        isSealed = false,
                        typeParameterVariances = listOf(Variance.OUT_VARIANCE),
                    ),
                "kotlin.collections.List" to
                    VisibleClassHierarchyInfo(
                        superClassifiers = setOf("kotlin.collections.Collection"),
                        isSealed = false,
                        typeParameterVariances = listOf(Variance.OUT_VARIANCE),
                    ),
                "kotlin.collections.Collection" to
                    VisibleClassHierarchyInfo(
                        superClassifiers = emptySet(),
                        isSealed = false,
                        typeParameterVariances = listOf(Variance.OUT_VARIANCE),
                    ),
                "kotlin.collections.Set" to
                    VisibleClassHierarchyInfo(
                        superClassifiers = setOf("kotlin.collections.Collection"),
                        isSealed = false,
                        typeParameterVariances = listOf(Variance.OUT_VARIANCE),
                    ),
            )

        assertTrue(supportsBuiltinSubtypeGoal(listToCollection, classInfo))
        assertFalse(provablySupportsBuiltinSubtypeGoal(listToCollection, classInfo))
        assertTrue(supportsBuiltinSubtypeGoal(mutableListToList, classInfo))
        assertFalse(provablySupportsBuiltinSubtypeGoal(mutableListToList, classInfo))
        assertFalse(supportsBuiltinSubtypeGoal(unrelated, classInfo))
        assertFalse(provablySupportsBuiltinSubtypeGoal(unrelated, classInfo))
    }

    @Test
    fun `provable-only is-typeclass-instance support rejects speculative variables`() {
        val speculativeGoal =
            typeConstructor(IS_TYPECLASS_INSTANCE_CLASS_ID.asString(), typeVariable("TC"))
        val provableGoal =
            typeConstructor(
                IS_TYPECLASS_INSTANCE_CLASS_ID.asString(),
                typeConstructor("demo.Show", typeConstructor("kotlin.Int")),
            )

        assertTrue(
            supportsBuiltinIsTypeclassInstanceGoal(
                goal = speculativeGoal,
                isTypeclassClassifier = { classifierId -> classifierId == "demo.Show" },
            )
        )
        assertFalse(
            provablySupportsBuiltinIsTypeclassInstanceGoal(
                goal = speculativeGoal,
                isTypeclassClassifier = { classifierId -> classifierId == "demo.Show" },
            )
        )
        assertTrue(
            provablySupportsBuiltinIsTypeclassInstanceGoal(
                goal = provableGoal,
                isTypeclassClassifier = { classifierId -> classifierId == "demo.Show" },
            )
        )
    }

    private fun typeConstructor(
        classifierId: String,
        vararg arguments: TcType,
        isNullable: Boolean = false,
    ): TcType =
        TcType.Constructor(
            classifierId = classifierId,
            arguments = arguments.toList(),
            isNullable = isNullable,
        )

    private fun typeVariable(id: String, isNullable: Boolean = false): TcType =
        TcType.Variable(id = id, displayName = id, isNullable = isNullable)

    private fun projected(variance: Variance, type: TcType): TcType =
        TcType.Projected(variance, type)
}
