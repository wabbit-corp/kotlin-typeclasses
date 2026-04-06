// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.typeclass.plugin

import one.wabbit.typeclass.plugin.model.TcType
import org.jetbrains.kotlin.types.Variance
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        val target = typeConstructor("demo.Box", projected(Variance.OUT_VARIANCE, typeVariable("T")))
        val knownTypeGoal = typeConstructor(KNOWN_TYPE_CLASS_ID.asString(), target)
        val typeIdGoal = typeConstructor(TYPE_ID_CLASS_ID.asString(), target)

        assertFalse(supportsBuiltinKnownTypeGoal(knownTypeGoal) { false })
        assertFalse(supportsBuiltinTypeIdGoal(typeIdGoal) { false })
        assertTrue(supportsBuiltinKnownTypeGoal(knownTypeGoal) { it == "T" })
        assertTrue(supportsBuiltinTypeIdGoal(typeIdGoal) { it == "T" })
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
            typeConstructor(
                IS_TYPECLASS_INSTANCE_CLASS_ID.asString(),
                typeVariable("TC"),
            )

        assertFalse(
            supportsBuiltinIsTypeclassInstanceGoal(
                goal = nonTypeclassGoal,
                isTypeclassClassifier = { classifierId -> classifierId == "demo.Show" },
            ),
        )
        assertTrue(
            supportsBuiltinIsTypeclassInstanceGoal(
                goal = typeclassGoal,
                isTypeclassClassifier = { classifierId -> classifierId == "demo.Show" },
            ),
        )
        assertTrue(
            supportsBuiltinIsTypeclassInstanceGoal(
                goal = variableGoal,
                isTypeclassClassifier = { false },
            ),
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
    fun `provable-only is-typeclass-instance support rejects speculative variables`() {
        val speculativeGoal =
            typeConstructor(
                IS_TYPECLASS_INSTANCE_CLASS_ID.asString(),
                typeVariable("TC"),
            )
        val provableGoal =
            typeConstructor(
                IS_TYPECLASS_INSTANCE_CLASS_ID.asString(),
                typeConstructor("demo.Show", typeConstructor("kotlin.Int")),
            )

        assertTrue(
            supportsBuiltinIsTypeclassInstanceGoal(
                goal = speculativeGoal,
                isTypeclassClassifier = { classifierId -> classifierId == "demo.Show" },
            ),
        )
        assertFalse(
            provablySupportsBuiltinIsTypeclassInstanceGoal(
                goal = speculativeGoal,
                isTypeclassClassifier = { classifierId -> classifierId == "demo.Show" },
            ),
        )
        assertTrue(
            provablySupportsBuiltinIsTypeclassInstanceGoal(
                goal = provableGoal,
                isTypeclassClassifier = { classifierId -> classifierId == "demo.Show" },
            ),
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

    private fun typeVariable(
        id: String,
        isNullable: Boolean = false,
    ): TcType = TcType.Variable(id = id, displayName = id, isNullable = isNullable)

    private fun projected(
        variance: Variance,
        type: TcType,
    ): TcType = TcType.Projected(variance, type)
}
