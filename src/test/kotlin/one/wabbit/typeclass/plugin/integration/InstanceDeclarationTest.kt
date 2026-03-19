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
                object IntShow : Show<Int> { // ERROR invalid scope: namespace objects are neither top-level nor associated owners
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
            object TopLevelBoxShow : Show<Box> { // ERROR duplicate instance declaration for Box
                override fun show(value: Box): String = "top:${'$'}{value.value}"
            }

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(Box(1))) // ERROR ambiguous Show<Box> resolution
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
                object IntShow : Show<Int> { // ERROR invalid scope: neither companion nor top-level nor Int's companion
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

            @Instance
            fun String.badInstance(): Show<String> = // ERROR extension @Instance functions should be rejected
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

            @Instance
            fun bad(value: Int): Show<Int> = // ERROR @Instance functions with regular parameters should be rejected
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

            @Instance
            context(prefix: Prefix, show: Show<Int>)
            fun boxShow(): Show<Box> = // ERROR @Instance functions must not depend on non-typeclass contexts
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
                    object StringShow : Show<String> { // ERROR associated instance owners must match the provided type
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

            @Instance
            class IntShow : Show<Int> { // ERROR class-based @Instance declarations are not allowed for now
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

            @Instance
            var intShow: Show<Int> = // ERROR mutable instance properties are not allowed
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

            @Instance
            lateinit var intShow: Show<Int> // ERROR lateinit instance properties are not allowed
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

            @Instance
            val intShow: Show<Int> // ERROR custom getter instance properties are not allowed
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

            @Instance
            suspend fun intShow(): Show<Int> = // ERROR suspend @Instance functions are not allowed for now
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
