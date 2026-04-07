// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.typeclass.plugin.integration

import one.wabbit.typeclass.plugin.invalidInstanceExtensionFunction
import one.wabbit.typeclass.plugin.invalidInstanceMustProvideTypeclassType
import one.wabbit.typeclass.plugin.invalidInstanceNonTypeclassPrerequisites
import one.wabbit.typeclass.plugin.invalidInstanceNonTypeclassSupertypes
import one.wabbit.typeclass.plugin.invalidInstanceRegularParameter
import one.wabbit.typeclass.plugin.invalidInstanceStarProjectedPrerequisites
import kotlin.test.Test

class InstanceDeclarationTest : IntegrationTestSupport() {
    @Test fun rejectsInstanceObjectsDeclaredInsideNamespaceObjectsAtDeclarationSite() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(): String
            }

            object Instances {
                @Instance
                object IntShow : Show<Int> { // E:TC_INVALID_INSTANCE_DECL invalid scope: namespace objects are neither top-level nor associated owners
                    override fun show(): String = "namespace-object"
                }
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedInvalidInstanceDecl("top-level", "associated owners"),
                ),
        )
    }

    @Test fun reportsDuplicateInstancesAcrossCompanionAndTopLevelScopes() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            data class Box(val value: Int) {
                companion object {
                    @Instance
                    object CompanionBoxShow : Show<Box> {
                        override fun show(value: Box): String = "companion:${'$'}{value.value}"
                    }
                }
            }

            @Instance
            object TopLevelBoxShow : Show<Box> { // duplicate instance declaration for Box
                override fun show(value: Box): String = "top:${'$'}{value.value}"
            }

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(Box(1))) // E:TC_AMBIGUOUS_INSTANCE ambiguous Show<Box> resolution
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedAmbiguousInstance("show", "box")),
        )
    }

    @Test fun rejectsInstanceObjectsDeclaredOutsideAllowedScopesAtDeclarationSite() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            class Holder {
                @Instance
                object IntShow : Show<Int> { // E:TC_INVALID_INSTANCE_DECL invalid scope: neither companion nor top-level nor Int's companion
                    override fun show(value: Int): String = "int:${'$'}value"
                }
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedInvalidInstanceDecl("companion", "top-level"),
                ),
        )
    }

    @Test fun rejectsExtensionInstanceFunctionsAtDeclarationSite() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance // E:TC_INVALID_INSTANCE_DECL extension @Instance functions should be rejected
            fun String.badInstance(): Show<String> =
                object : Show<String> {
                    override fun show(value: String): String = value
                }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedTypeclassDiagnostic(invalidInstanceExtensionFunction()),
                ),
        )
    }

    @Test fun rejectsInstanceFunctionsWithRegularParametersAtDeclarationSite() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance // E:TC_INVALID_INSTANCE_DECL @Instance functions with regular parameters should be rejected
            fun bad(value: Int): Show<Int> =
                object : Show<Int> {
                    override fun show(value: Int): String = value.toString()
                }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedTypeclassDiagnostic(invalidInstanceRegularParameter()),
                ),
        )
    }

    @Test fun reportsMissingProvidedTypeclassBeforeNonTypeclassSupertypeExpansionNoise() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            interface WrappedShow<A> : Show<A>

            @Instance
            object WrappedIntShow : WrappedShow<Int> { // E:TC_INVALID_INSTANCE_DECL no direct @Typeclass head is provided here
                override fun show(value: Int): String = value.toString()
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedTypeclassDiagnostic(invalidInstanceMustProvideTypeclassType()),
                ),
        )
    }

    @Test fun stillReportsNonTypeclassIntermediateSupertypesWhenAValidHeadAlsoExists() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Typeclass
            interface Eq<A> {
                fun eq(left: A, right: A): Boolean
            }

            interface WrappedEq<A> : Eq<A>

            @Instance
            object IntEvidence : Show<Int>, WrappedEq<Int> { // E:TC_INVALID_INSTANCE_DECL mixed valid and invalid provided heads should keep the non-typeclass-supertype diagnostic
                override fun show(value: Int): String = value.toString()
                override fun eq(left: Int, right: Int): Boolean = left == right
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedTypeclassDiagnostic(invalidInstanceNonTypeclassSupertypes()),
                ),
        )
    }

    @Test fun allowsSiblingMarkerSupertypesAlongsideAValidTypeclassHead() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import java.io.Serializable

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance
            object IntShow : Show<Int>, Serializable {
                override fun show(value: Int): String = "show:${'$'}value"
            }

            context(show: Show<Int>)
            fun render(): String = show.show(7)

            fun main() {
                println(render())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "show:7",
        )
    }

    @Test fun rejectsInstanceFunctionsWithNonTypeclassContextParametersAtDeclarationSite() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            data class Prefix(val value: String)
            data class Box(val value: Int)

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(value: Int): String = value.toString()
            }

            @Instance // E:TC_INVALID_INSTANCE_DECL @Instance functions must not depend on non-typeclass contexts
            context(prefix: Prefix, show: Show<Int>)
            fun boxShow(): Show<Box> =
                object : Show<Box> {
                    override fun show(value: Box): String = prefix.value + show.show(value.value)
                }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedTypeclassDiagnostic(invalidInstanceNonTypeclassPrerequisites()),
                ),
        )
    }

    @Test fun rejectsInstanceFunctionsWithStarProjectedTypeclassPrerequisitesAtDeclarationSite() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance
            object StringShow : Show<String> {
                override fun show(value: String): String = value
            }

            @Instance // E:TC_INVALID_INSTANCE_DECL @Instance functions must not depend on star-projected typeclass prerequisites
            context(_: Show<*>)
            fun listShow(): Show<List<String>> =
                object : Show<List<String>> {
                    override fun show(value: List<String>): String = value.joinToString(",")
                }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedTypeclassDiagnostic(invalidInstanceStarProjectedPrerequisites()),
                ),
        )
    }

    @Test fun rejectsInstanceFunctionsWithDefinitelyNonNullTypeclassPrerequisitesAtDeclarationSite() {
        // FIXME: perhaps we will want to re-enable this behavior in the future.
        // For now, a safe strategy is to disallow non-nullable typeclass prerequisites on @Instance functions.

        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            data class Box<A>(val value: A)

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance
            object StringShow : Show<String> {
                override fun show(value: String): String = value
            }

            @Instance // E:TC_INVALID_INSTANCE_DECL @Instance functions must not depend on definitely-non-null typeclass prerequisites
            context(_: Show<T & Any>)
            fun <T> boxShow(): Show<Box<T>> =
                object : Show<Box<T>> {
                    override fun show(value: Box<T>): String = value.value.toString()
                }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedExactInvalidInstanceDecl(
                        why = "instance function typeclass prerequisites must not use definitely-non-null type arguments.",
                    ),
                ),
        )
    }

    @Test fun rejectsInstancesDeclaredInUnrelatedCompanionObjectsAtDeclarationSite() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            data class Box(val value: Int) {
                companion object {
                    @Instance
                    object StringShow : Show<String> { // E:TC_INVALID_INSTANCE_DECL associated instance owners must match the provided type
                        override fun show(value: String): String = value
                    }
                }
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedExactInvalidInstanceDecl(
                        why = "associated owner does not match the provided typeclass head or its type arguments.",
                    ),
                ),
        )
    }

    @Test fun rejectsClassBasedInstancesAtDeclarationSite() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance // E:TC_INVALID_INSTANCE_DECL class-based @Instance declarations are not allowed for now
            class IntShow : Show<Int> {
                override fun show(value: Int): String = value.toString()
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedExactInvalidInstanceDecl(
                        why = "class-based instances are not allowed; use an object.",
                    ),
                ),
        )
    }

    @Test fun rejectsMutableInstancePropertiesAtDeclarationSite() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance // E:TC_INVALID_INSTANCE_DECL mutable instance properties are not allowed
            var intShow: Show<Int> =
                object : Show<Int> {
                    override fun show(value: Int): String = value.toString()
                }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedExactInvalidInstanceDecl(
                        why = "mutable instance property declarations are not allowed.",
                    ),
                ),
        )
    }

    @Test fun rejectsLateinitInstancePropertiesAtDeclarationSite() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance // E:TC_INVALID_INSTANCE_DECL lateinit instance properties are not allowed
            lateinit var intShow: Show<Int>
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedExactInvalidInstanceDecl(
                        why = "lateinit instance property declarations are not allowed.",
                    ),
                ),
        )
    }

    @Test fun rejectsCustomGetterInstancePropertiesAtDeclarationSite() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance // E:TC_INVALID_INSTANCE_DECL custom getter instance properties are not allowed
            val intShow: Show<Int>
                get() =
                    object : Show<Int> {
                        override fun show(value: Int): String = value.toString()
                    }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedExactInvalidInstanceDecl(
                        why = "custom getter instance property declarations are not allowed.",
                    ),
                ),
        )
    }

    @Test fun rejectsSuspendInstanceFunctionsAtDeclarationSite() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance // E:TC_INVALID_INSTANCE_DECL suspend @Instance functions are not allowed for now
            suspend fun intShow(): Show<Int> =
                object : Show<Int> {
                    override fun show(value: Int): String = value.toString()
                }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedExactInvalidInstanceDecl(
                        why = "suspend instance functions are not allowed.",
                    ),
                ),
        )
    }

    @Test fun illegalMutableInstancePropertiesDoNotCreateSpuriousCallSiteAmbiguity() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(value: Int): String = "good:${'$'}value"
            }

            @Instance // E:TC_INVALID_INSTANCE_DECL mutable instance property declarations must remain invalid and must not affect resolution
            var badShow: Show<Int> =
                object : Show<Int> {
                    override fun show(value: Int): String = "bad:${'$'}value"
                }

            context(show: Show<Int>)
            fun render(value: Int): String = show.show(value)

            fun main() {
                println(render(1))
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            unexpectedMessages = listOf("ambiguous"),
            expectedDiagnostics =
                listOf(
                    expectedExactInvalidInstanceDecl(
                        why = "mutable instance property declarations are not allowed.",
                    ),
                ),
        )
    }

    @Test fun illegalLateinitInstancePropertiesDoNotCreateSpuriousCallSiteAmbiguity() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(value: Int): String = "good:${'$'}value"
            }

            @Instance // E:TC_INVALID_INSTANCE_DECL lateinit instance property declarations must remain invalid and must not affect resolution
            lateinit var badShow: Show<Int>

            context(show: Show<Int>)
            fun render(value: Int): String = show.show(value)

            fun main() {
                println(render(1))
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            unexpectedMessages = listOf("ambiguous"),
            expectedDiagnostics =
                listOf(
                    expectedExactInvalidInstanceDecl(
                        why = "lateinit instance property declarations are not allowed.",
                    ),
                ),
        )
    }

    @Test fun illegalCustomGetterInstancePropertiesDoNotCreateSpuriousCallSiteAmbiguity() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(value: Int): String = "good:${'$'}value"
            }

            @Instance // E:TC_INVALID_INSTANCE_DECL custom getter instance property declarations must remain invalid and must not affect resolution
            val badShow: Show<Int>
                get() =
                    object : Show<Int> {
                        override fun show(value: Int): String = "bad:${'$'}value"
                    }

            context(show: Show<Int>)
            fun render(value: Int): String = show.show(value)

            fun main() {
                println(render(1))
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            unexpectedMessages = listOf("ambiguous"),
            expectedDiagnostics =
                listOf(
                    expectedExactInvalidInstanceDecl(
                        why = "custom getter instance property declarations are not allowed.",
                    ),
                ),
        )
    }

    @Test fun illegalSuspendInstanceFunctionsDoNotCreateSpuriousCallSiteAmbiguity() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(value: Int): String = "good:${'$'}value"
            }

            @Instance // E:TC_INVALID_INSTANCE_DECL suspend instance declarations must remain invalid and must not affect resolution
            suspend fun badShow(): Show<Int> =
                object : Show<Int> {
                    override fun show(value: Int): String = "bad:${'$'}value"
                }

            context(show: Show<Int>)
            fun render(value: Int): String = show.show(value)

            fun main() {
                println(render(1))
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            unexpectedMessages = listOf("ambiguous"),
            expectedDiagnostics =
                listOf(
                    expectedExactInvalidInstanceDecl(
                        why = "suspend instance functions are not allowed.",
                    ),
                ),
        )
    }

    @Test
    fun illegalMemberInstanceFunctionsDoNotCreateSpuriousCallSiteAmbiguity() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(value: Int): String = "good:${'$'}value"
            }

            class BadScope {
                @Instance // E:TC_INVALID_INSTANCE_DECL member @Instance declarations must remain invalid and must not affect resolution
                fun badShow(): Show<Int> =
                    object : Show<Int> {
                        override fun show(value: Int): String = "bad:${'$'}value"
                    }
            }

            context(show: Show<Int>)
            fun render(): String = show.show(1)

            fun main() {
                println(render())
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            unexpectedMessages = listOf("ambiguous"),
            expectedDiagnostics = listOf(expectedInvalidInstanceDecl("companion")),
        )
    }

    @Test
    fun illegalNestedInstanceObjectsDoNotCreateSpuriousCallSiteAmbiguity() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(value: Int): String = "good:${'$'}value"
            }

            class BadScope {
                @Instance
                object BadShow : Show<Int> { // E:TC_INVALID_INSTANCE_DECL nested @Instance declarations must remain invalid and must not affect resolution
                    override fun show(value: Int): String = "bad:${'$'}value"
                }
            }

            context(show: Show<Int>)
            fun render(): String = show.show(1)

            fun main() {
                println(render())
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            unexpectedMessages = listOf("ambiguous"),
            expectedDiagnostics = listOf(expectedInvalidInstanceDecl("companion")),
        )
    }

    @Test
    fun rejectsDirectSelfRecursiveWrapperReturningInstanceRulesAtDeclarationSite() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            data class Box<A>(val value: A)

            @Typeclass
            interface Show<A> {
                fun label(): String
            }

            @Typeclass
            interface WrappedShow<A> : Show<A>

            @Instance // E:TC_INVALID_INSTANCE_DECL expands to Show<Box<A>> => Show<Box<A>>
            context(_: Show<Box<A>>)
            fun <A> recursiveWrappedBoxShow(): WrappedShow<Box<A>> =
                object : WrappedShow<Box<A>> {
                    override fun label(): String = "recursive"
                }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedExactInvalidInstanceDecl(
                        why = "direct recursive instance rule for demo/Show is not allowed.",
                    ),
                ),
        )
    }

    @Test
    fun allowsWrapperReturningInstanceRulesWhenExpandedProvidedTypeIsNotRecursive() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            data class Box<A>(val value: A)

            @Typeclass
            interface Show<A> {
                fun label(): String
            }

            @Typeclass
            interface WrappedShow<A> : Show<A>

            @Instance
            object IntShow : Show<Int> {
                override fun label(): String = "int"
            }

            @Instance
            context(_: Show<A>)
            fun <A> wrappedBoxShow(): WrappedShow<Box<A>> =
                object : WrappedShow<Box<A>> {
                    override fun label(): String = "box"
                }

            context(show: Show<A>)
            fun <A> label(): String = show.label()

            fun main() {
                println(label<Box<Int>>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "box",
        )
    }

    @Test
    fun rejectsTopLevelMultiHeadInstancesWhenAnyHeadIsOrphaned() {
        val sources =
            mapOf(
                "demo/Show.kt" to
                    """
                    package demo

                    import one.wabbit.typeclass.Instance
                    import one.wabbit.typeclass.Typeclass

                    @Typeclass
                    interface Show<A> {
                        fun show(value: A): String
                    }

                    @Instance
                    object IntShowEq : Show<Int>, Eq<Int> { // E:TC_INVALID_INSTANCE_DECL one legal head must not launder a second orphan head
                        override fun show(value: Int): String = "show:${'$'}value"

                        override fun eq(left: Int, right: Int): Boolean = left == right
                    }
                    """.trimIndent(),
                "demo/Eq.kt" to
                    """
                    package demo

                    import one.wabbit.typeclass.Typeclass

                    @Typeclass
                    interface Eq<A> {
                        fun eq(left: A, right: A): Boolean
                    }
                    """.trimIndent(),
            )

        assertDoesNotCompile(
            sources = sources,
            expectedDiagnostics =
                listOf(
                    expectedInvalidInstanceDecl(
                        "top-level orphan",
                        "same file as one of",
                        "Show",
                        "Eq",
                    ),
                ),
        )
    }
}
