package one.wabbit.typeclass.plugin.integration

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
            expectedMessages = listOf("scope"),
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
                println(render(Box(1))) // E:TC_NO_CONTEXT_ARGUMENT ambiguous Show<Box> resolution
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("no context argument", "show"),
            expectedDiagnostics =
                listOf(
                    ExpectedDiagnostic.Error(messageRegex = "(?i)(duplicate|no context argument)"),
                ),
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
            expectedMessages = listOf("companion"),
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
            expectedMessages = listOf("extension"),
            expectedDiagnostics = listOf(expectedErrorContaining("extension")),
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
            expectedMessages = listOf("regular parameter"),
            expectedDiagnostics =
                listOf(
                    ExpectedDiagnostic.Error(messageRegex = "(?i)(regular parameter|invalid @instance declaration|parameter)"),
                ),
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

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                context(Prefix("box=")) {
                    println(render(Box(1)))
                }
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("invalid @instance declaration", "context", "typeclass"),
            expectedDiagnostics = listOf(expectedErrorContaining("invalid @instance declaration", "typeclass")),
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

            @Instance
            context(_: Show<*>)
            fun listShow(): Show<List<String>> =
                object : Show<List<String>> {
                    override fun show(value: List<String>): String = value.joinToString(",")
                }

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(listOf("a")))
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("invalid @instance declaration", "star", "instance"),
            expectedDiagnostics = listOf(expectedErrorContaining("invalid @instance declaration", "star")),
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

            @Instance
            context(_: Show<T & Any>)
            fun <T> boxShow(): Show<Box<T>> =
                object : Show<Box<T>> {
                    override fun show(value: Box<T>): String = value.value.toString()
                }

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(Box("a")))
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("invalid @instance declaration", "context", "instance"),
            expectedDiagnostics =
                listOf(
                    ExpectedDiagnostic.Error(messageRegex = "(?i)(invalid @instance declaration|definitely|instance)"),
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
            expectedMessages = listOf("associated", "owner"),
            expectedDiagnostics = listOf(expectedErrorContaining("associated", "owner")),
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
            expectedMessages = listOf("class"),
            expectedDiagnostics = listOf(expectedErrorContaining("class")),
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
            expectedMessages = listOf("mutable", "property"),
            expectedDiagnostics = listOf(expectedErrorContaining("mutable", "property")),
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
            expectedMessages = listOf("lateinit", "property"),
            expectedDiagnostics = listOf(expectedErrorContaining("lateinit", "property")),
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
            expectedMessages = listOf("getter", "property"),
            expectedDiagnostics = listOf(expectedErrorContaining("getter", "property")),
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
            expectedMessages = listOf("suspend"),
            expectedDiagnostics = listOf(expectedErrorContaining("suspend")),
        )
    }
}
