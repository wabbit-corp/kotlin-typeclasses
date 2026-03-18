package one.wabbit.typeclass.plugin.integration

import org.jetbrains.kotlin.cli.common.ExitCode
import org.junit.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals

class DerivationTest : IntegrationTestSupport() {
    @Test fun derivesSealedInterfaces() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.SumTypeclassMetadata
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.TypeclassDeriver
            import one.wabbit.typeclass.get
            import one.wabbit.typeclass.matches

            @Typeclass
            interface Show<A> {
                fun show(value: A): String

                companion object : TypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                        object : Show<Any?> {
                            override fun show(value: Any?): String {
                                require(value != null)
                                val renderedFields =
                                    metadata.fields.joinToString(", ") { field ->
                                        val fieldValue = field.get(value)
                                        val fieldShow = field.instance as Show<Any?>
                                        "${'$'}{field.name}=${'$'}{fieldShow.show(fieldValue)}"
                                    }
                                val typeName = metadata.typeName.substringAfterLast('.')
                                return "${'$'}typeName(${'$'}renderedFields)"
                            }
                        }

                    override fun deriveSum(metadata: SumTypeclassMetadata): Any =
                        object : Show<Any?> {
                            override fun show(value: Any?): String {
                                require(value != null)
                                val matchingCase = metadata.cases.single { candidate -> candidate.matches(value) }
                                val caseShow = matchingCase.instance as Show<Any?>
                                return caseShow.show(value)
                            }
                        }
                }
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(value: Int): String = value.toString()
            }

            @Derive(Show::class)
            sealed interface Choice<out A>

            @Derive(Show::class)
            data class Present<A>(val value: A) : Choice<A>

            @Derive(Show::class)
            object Absent : Choice<Nothing>

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                val present: Choice<Int> = Present(1)
                val absent: Choice<Int> = Absent
                println(render(present))
                println(render(absent))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                Present(value=1)
                Absent()
                """.trimIndent(),
        )
    }

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
            expectedMessages = listOf("ambiguous typeclass instance"),
        )
    }

    @Test fun resolvesAssociatedInstancesThroughTypeArguments() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            data class Curse(val soulbound: Boolean) {
                companion object {
                    @Instance
                    object CurseListShow : Show<List<Curse>> {
                        override fun show(value: List<Curse>): String =
                            value.joinToString(prefix = "[", postfix = "]") { curse ->
                                if (curse.soulbound) "bound" else "free"
                            }
                    }
                }
            }

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(listOf(Curse(true), Curse(false))))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "[bound, free]",
        )
    }

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun ignoresInapplicableAssociatedSealedSupertypeCandidatesWhenResolvingSubtypeSpecificInstances() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            @Typeclass
            interface Show<A> {
                fun label(): String
            }

            @Typeclass
            interface Impossible<A>

            sealed interface Animal {
                data class Dog(val id: Int) : Animal {
                    companion object {
                        @Instance
                        object DogShow : Show<Dog> {
                            override fun label(): String = "dog"
                        }
                    }
                }

                companion object {
                    @Instance
                    context(_: Impossible<Nothing>)
                    fun hiddenDogShow(): Show<Dog> =
                        object : Show<Dog> {
                            override fun label(): String = "hidden"
                        }
                }
            }

            context(_: Show<A>)
            fun <A> which(): String = summon<Show<A>>().label()

            fun main() {
                println(which<Animal.Dog>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "dog",
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
        )
    }

    @Ignore("PHASE10A")
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
        )
    }

    @Ignore("PHASE10A")
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
        )
    }

    @Ignore("PHASE10A")
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
        )
    }

    // FIXME: re-review in the future
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
        )
    }

    // FIXME: re-review in the future
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
        )
    }

    // FIXME: re-review in the future
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
        )
    }

    @Test fun rewritesVarargCallsWithTrailingLambdas() {
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
                override fun show(value: Int): String = "int:${'$'}value"
            }

            context(show: Show<Int>)
            fun renderInt(value: Int): String = show.show(value)

            class Logger {
                context(_: Show<Int>)
                fun collect(vararg values: Int, block: (String) -> String): String =
                    block(values.joinToString("|") { value -> renderInt(value) })
            }

            fun main() {
                println(Logger().collect(1, 2, 3) { rendered -> "[${'$'}rendered]" })
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "[int:1|int:2|int:3]",
        )
    }

    @Test fun resolvesInlineReifiedHelpersAroundSummon() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            @Typeclass
            interface Show<A> {
                fun show(): String
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(): String = "int"
            }

            context(_: Show<A>)
            inline fun <reified A> render(): String = summon<Show<A>>().show()

            fun main() {
                println(render<Int>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "int",
        )
    }

    @Test fun resolvesAnonymousObjectsAndFunctionsThatCaptureLocalEvidence() {
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
                override fun show(value: Int): String = "int:${'$'}value"
            }

            context(show: Show<Int>)
            fun capture(): Pair<String, String> {
                val anonymous =
                    object {
                        fun render(): String = show.show(1)
                    }
                val function = fun(value: Int): String = show.show(value)
                return anonymous.render() to function(2)
            }

            fun main() {
                val (first, second) = capture()
                println(first)
                println(second)
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                int:1
                int:2
                """.trimIndent(),
        )
    }

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun resolvesOverloadsThatDifferOnlyByTypeclassContexts() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Eq<A> {
                fun label(): String
            }

            @Typeclass
            interface Show<A> {
                fun label(): String
            }

            @Instance
            object IntEq : Eq<Int> {
                override fun label(): String = "eq"
            }

            @Instance
            object IntShow : Show<Int> {
                override fun label(): String = "show"
            }

            context(_: Eq<Int>)
            fun parse(value: String): String = "eq:${'$'}value"

            context(_: Show<Int>)
            fun parse(value: String): String = "show:${'$'}value"

            fun main() {
                context(IntEq) {
                    println(parse("x"))
                }
                context(IntShow) {
                    println(parse("y"))
                }
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                eq:x
                show:y
                """.trimIndent(),
        )
    }

    @Test fun rewritesContextualCallsInContextClassPropertyInitializers() {
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
                override fun show(value: Int): String = "int:${'$'}value"
            }

            context(show: Show<Int>)
            fun renderInt(value: Int): String = show.show(value)

            class Holder {
                val rendered = renderInt(1)
            }

            fun main() {
                println(Holder().rendered)
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "int:1",
        )
    }

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun resolvesDefinitelyNonNullTypeclassGoals() {
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
                override fun show(value: String): String = "string:${'$'}value"
            }

            context(show: Show<T & Any>)
            fun <T> render(value: T & Any): String = show.show(value)

            fun main() {
                println(render<String>("x"))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "string:x",
        )
    }

    @Test fun exposesMultipleTypeclassInstancesFromOneObject() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Typeclass
            interface Eq<A> {
                fun eq(left: A, right: A): Boolean
            }

            @Instance
            object IntInstances : Show<Int>, Eq<Int> {
                override fun show(value: Int): String = "int:${'$'}value"

                override fun eq(left: Int, right: Int): Boolean = left == right
            }

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            context(_: Eq<A>)
            fun <A> same(value: A): Boolean = summon<Eq<A>>().eq(value, value)

            fun main() {
                println(render(1))
                println(same(1))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                int:1
                true
                """.trimIndent(),
        )
    }

    @Test fun mixesPreservedAndSynthesizedTypeclassArgumentsOnOneCall() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Eq<A> {
                fun eq(left: A, right: A): Boolean
            }

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance
            object IntEq : Eq<Int> {
                override fun eq(left: Int, right: Int): Boolean = left == right
            }

            @Instance
            context(eq: Eq<A>)
            fun <A> showFromEq(): Show<A> =
                object : Show<A> {
                    override fun show(value: A): String =
                        if (eq.eq(value, value)) "stable" else "unstable"
                }

            context(local: Eq<String>, shown: Show<Int>)
            fun use(): String = local.eq("x", "x").toString() + ":" + shown.show(1)

            fun main() {
                val localEq =
                    object : Eq<String> {
                        override fun eq(left: String, right: String): Boolean = left == right
                    }

                context(localEq) {
                    println(use())
                }
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "true:stable",
        )
    }

    @Test fun resolvesContextualCallsThroughInterfaceOverrides() {
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
                override fun show(value: Int): String = "int:${'$'}value"
            }

            interface Renderer {
                context(show: Show<Int>)
                fun render(value: Int): String
            }

            class Impl : Renderer {
                context(show: Show<Int>)
                override fun render(value: Int): String = show.show(value)
            }

            fun use(renderer: Renderer): String = renderer.render(1)

            fun main() {
                println(use(Impl()))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "int:1",
        )
    }

    @Test fun doesNotLeakPrivateCompanionInstancesAcrossFiles() {
        val sources =
            mapOf(
                "Box.kt" to
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
                            private object HiddenBoxShow : Show<Box> {
                                override fun show(value: Box): String = "hidden:${'$'}{value.value}"
                            }
                        }
                    }

                    context(show: Show<A>)
                    fun <A> render(value: A): String = show.show(value)
                    """.trimIndent(),
                "Main.kt" to
                    """
                    package demo

                    fun main() {
                        println(render(Box(1))) // ERROR private companion @Instance declarations should not leak across files
                    }
                    """.trimIndent(),
            )

        assertDoesNotCompile(
            sources = sources,
            expectedMessages = listOf("no context argument"),
        )
    }

    @Test fun usesExtensionReceiverAsTheOnlyAvailableEvidence() {
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
                override fun show(value: Int): String = "int:${'$'}value"
            }

            context(show: Show<Int>)
            fun renderInt(value: Int): String = show.show(value)

            fun Show<Int>.callViaReceiver(): String = renderInt(1)

            fun main() {
                println(IntShow.callViaReceiver())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "int:1",
        )
    }

    @Test fun keepsIntegerLiteralInferenceStableAcrossFirAndIr() {
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
                override fun show(value: Int): String = "int:${'$'}value"
            }

            @Instance
            object LongShow : Show<Long> {
                override fun show(value: Long): String = "long:${'$'}value"
            }

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(1))
                println(render(1L))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                int:1
                long:1
                """.trimIndent(),
        )
    }

    @Test
    fun reportsAmbiguityForNullableSpecificAndGenericNullEvidence() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun label(): String
            }

            @Instance
            object StringShow : Show<String> {
                override fun label(): String = "string"
            }

            @Instance
            object NullableStringShow : Show<String?> {
                override fun label(): String = "nullable-string"
            }

            @Instance
            context(show: Show<A>)
            fun <A> nullableShow(): Show<A?> =
                object : Show<A?> {
                    override fun label(): String = "generic-" + show.label()
                }

            context(show: Show<A>)
            fun <A> render(value: A): String = show.label()

            fun main() {
                println(render<String?>(null)) // ERROR ambiguous Show<String?> resolution
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("ambiguous typeclass instance"),
        )
    }

    @Test
    fun rejectsNonTypeclassIntermediateSupertypesThatExtendTypeclasses() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            abstract class IntShowBase : Show<Int> // ERROR non-typeclass intermediate supertypes should not extend typeclasses

            @Instance
            object IntShow : IntShowBase() {
                override fun show(value: Int): String = "int:${'$'}value"
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("typeclass"),
        )
    }

    @Test fun allowsIntermediateTypeclassSupertypesThatExtendTypeclasses() {
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
            interface IntShowBase : Show<Int>

            @Instance
            object IntShow : IntShowBase {
                override fun show(value: Int): String = "int:${'$'}value"
            }

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(1))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "int:1",
        )
    }

    @Test
    fun resolvesIntermediateTypeclassHierarchiesFromGroupInstances() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            @Typeclass
            interface Semigroup<A> {
                fun combine(left: A, right: A): A
            }

            @Typeclass
            interface Monoid<A> : Semigroup<A> {
                fun empty(): A
            }

            @Typeclass
            interface Group<A> : Monoid<A> {
                fun invert(value: A): A
            }

            @Instance
            object IntGroup : Group<Int> {
                override fun combine(left: Int, right: Int): Int = left + right

                override fun empty(): Int = 0

                override fun invert(value: Int): Int = -value
            }

            context(monoid: Monoid<Int>)
            fun renderFromMonoid(value: Int): Int = monoid.combine(monoid.empty(), value)

            context(group: Group<Int>)
            fun localMonoidSum(value: Int): Int =
                summon<Monoid<Int>>().combine(summon<Monoid<Int>>().empty(), value)

            context(group: Group<Int>)
            fun localSemigroupDouble(value: Int): Int =
                summon<Semigroup<Int>>().combine(value, value)

            fun main() {
                println(renderFromMonoid(4))
                context(IntGroup) {
                    println(localMonoidSum(5))
                    println(localSemigroupDouble(6))
                }
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                4
                5
                12
                """.trimIndent(),
        )
    }

    @Test
    fun reportsAmbiguousInheritedIntermediateTypeclassInstances() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Semigroup<A> {
                fun combine(left: A, right: A): A
            }

            @Typeclass
            interface Monoid<A> : Semigroup<A> {
                fun empty(): A
            }

            @Typeclass
            interface Group<A> : Monoid<A> {
                fun invert(value: A): A
            }

            @Instance
            object IntMonoid : Monoid<Int> {
                override fun combine(left: Int, right: Int): Int = left + right

                override fun empty(): Int = 0
            }

            @Instance
            object IntGroup : Group<Int> {
                override fun combine(left: Int, right: Int): Int = left + right

                override fun empty(): Int = 0

                override fun invert(value: Int): Int = -value
            }

            context(monoid: Monoid<Int>)
            fun use(): Int = monoid.empty()

            fun main() {
                println(use())
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("ambiguous typeclass instance"),
        )
    }

    @Test
    fun derivesSealedHierarchiesWithDataObjects() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.SumTypeclassMetadata
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.TypeclassDeriver
            import one.wabbit.typeclass.get
            import one.wabbit.typeclass.matches

            @Typeclass
            interface Show<A> {
                fun show(value: A): String

                companion object : TypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                        object : Show<Any?> {
                            override fun show(value: Any?): String {
                                require(value != null)
                                val renderedFields =
                                    metadata.fields.joinToString(", ") { field ->
                                        val fieldValue = field.get(value)
                                        val fieldShow = field.instance as Show<Any?>
                                        "${'$'}{field.name}=${'$'}{fieldShow.show(fieldValue)}"
                                    }
                                val typeName = metadata.typeName.substringAfterLast('.')
                                return "${'$'}typeName(${'$'}renderedFields)"
                            }
                        }

                    override fun deriveSum(metadata: SumTypeclassMetadata): Any =
                        object : Show<Any?> {
                            override fun show(value: Any?): String {
                                require(value != null)
                                val matchingCase = metadata.cases.single { candidate -> candidate.matches(value) }
                                val caseShow = matchingCase.instance as Show<Any?>
                                return caseShow.show(value)
                            }
                        }
                }
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(value: Int): String = value.toString()
            }

            @Derive(Show::class)
            sealed interface Token

            @Derive(Show::class)
            data class Lit(val value: Int) : Token

            @Derive(Show::class)
            data object End : Token

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(Lit(1)))
                println(render(End))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                Lit(value=1)
                End()
                """.trimIndent(),
        )
    }

    @Test
    fun rewritesContextualCallsInsideSecondaryConstructorDelegation() {
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
                override fun show(value: Int): String = "int:${'$'}value"
            }

            context(show: Show<Int>)
            fun renderInt(value: Int): String = show.show(value)

            class Holder(val rendered: String) {
                constructor() : this(renderInt(1))
            }

            fun main() {
                println(Holder().rendered)
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "int:1",
        )
    }

    @Test
    fun propagatesSuperclassStyleEvidenceFromOrdToEq() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Eq<A> {
                fun eq(left: A, right: A): Boolean
            }

            @Typeclass
            interface Ord<A> : Eq<A> {
                fun compare(left: A, right: A): Int
            }

            @Instance
            object IntOrd : Ord<Int> {
                override fun eq(left: Int, right: Int): Boolean = left == right

                override fun compare(left: Int, right: Int): Int = left.compareTo(right)
            }

            context(eq: Eq<A>)
            fun <A> same(value: A): Boolean = eq.eq(value, value)

            fun main() {
                println(same(1))

                val localOrd =
                    object : Ord<Int> {
                        override fun eq(left: Int, right: Int): Boolean = left == right

                        override fun compare(left: Int, right: Int): Int = left.compareTo(right)
                    }

                context(localOrd) {
                    println(same(2))
                }
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                true
                true
                """.trimIndent(),
        )
    }

    @Test
    fun rejectsInstanceRulesWithTypeParametersOnlyInPrerequisites() {
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
            context(_: Show<B>)
            fun <A, B> bad(): Show<List<A>> = // ERROR instance type parameter B only appears in prerequisites
                object : Show<List<A>> {
                    override fun show(value: List<A>): String = value.toString()
                }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("type parameter", "prerequisite"),
        )
    }

    @Test
    fun rejectsDirectSelfRecursiveInstanceRulesAtDeclarationSite() {
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

            @Instance
            context(_: Show<Box<A>>)
            fun <A> recursiveBoxShow(): Show<Box<A>> = // ERROR direct self-recursive instance rule Show<Box<A>> => Show<Box<A>>
                object : Show<Box<A>> {
                    override fun label(): String = "recursive"
                }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("recursive"),
        )
    }

    // NEW
    @Test
    fun localExactEvidenceOverridesDerivedGlobalEvidence() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            @Typeclass
            interface Eq<A> {
                fun label(): String
            }

            @Instance
            object StringEq : Eq<String> {
                override fun label(): String = "string"
            }

            @Instance
            context(eq: Eq<A>)
            fun <A> nullableEq(): Eq<A?> =
                object : Eq<A?> {
                    override fun label(): String = "nullable-" + eq.label()
                }

            context(_: Eq<A>)
            fun <A> which(): String = summon<Eq<A>>().label()

            fun main() {
                val localNullable =
                    object : Eq<String?> {
                        override fun label(): String = "local-nullable"
                    }

                context(localNullable) {
                    println(which<String?>())
                }
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "local-nullable",
        )
    }

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun resolvesApparentlyAmbiguousApisFromExpectedTypeReceiverTypeAndOuterContext() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            @Typeclass
            interface Factory<A> {
                fun make(): A
            }

            @Typeclass
            interface Describe<A> {
                fun label(): String
            }

            @Instance
            object IntFactory : Factory<Int> {
                override fun make(): Int = 1
            }

            @Instance
            object StringDescribe : Describe<String> {
                override fun label(): String = "string"
            }

            context(_: Factory<A>)
            fun <A> make(): A = summon<Factory<A>>().make()

            context(_: Describe<A>)
            fun <A> A.describe(): String = summon<Describe<A>>().label()

            context(_: Factory<A>)
            fun <A> outer(): A = make()

            fun main() {
                val fromExpected: Int = make()
                val fromReceiver = "x".describe()
                val fromOuter: Int = outer()
                println(fromExpected)
                println(fromReceiver)
                println(fromOuter)
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                1
                string
                1
                """.trimIndent(),
        )
    }

    @Test
    fun supportsNullaryTypeclasses() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface FeatureFlag {
                fun enabled(): Boolean
            }

            @Instance
            object EnabledFlag : FeatureFlag {
                override fun enabled(): Boolean = true
            }

            context(flag: FeatureFlag)
            fun check(): Boolean = flag.enabled()

            fun main() {
                println(check())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "true",
        )
    }

    @Test
    fun reportsDuplicateNullaryTypeclassInstancesAcrossFiles() {
        val sources =
            mapOf(
                "Flag.kt" to
                    """
                    package demo

                    import one.wabbit.typeclass.Instance
                    import one.wabbit.typeclass.Typeclass

                    @Typeclass
                    interface FeatureFlag {
                        fun enabled(): Boolean
                    }

                    @Instance
                    object EnabledFlag : FeatureFlag {
                        override fun enabled(): Boolean = true
                    }

                    context(flag: FeatureFlag)
                    fun check(): Boolean = flag.enabled()
                    """.trimIndent(),
                "Other.kt" to
                    """
                    package demo

                    import one.wabbit.typeclass.Instance

                    @Instance
                    object AnotherEnabledFlag : FeatureFlag { // ERROR duplicate nullary instance declaration
                        override fun enabled(): Boolean = false
                    }
                    """.trimIndent(),
                "Main.kt" to
                    """
                    package demo

                    fun main() {
                        println(check()) // ERROR ambiguous FeatureFlag resolution
                    }
                    """.trimIndent(),
            )

        assertDoesNotCompile(
            sources = sources,
            expectedMessages = listOf("ambiguous", "featureflag"),
        )
    }

    @Test
    fun supportsTypeclassMethodsWithAdditionalContext() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            @Typeclass interface Show<A> {
                fun show(value: A): String
            }

            @Typeclass interface Debug<A> {
                context(show: Show<A>)
                fun debug(value: A): String = "debug:" + show.show(value)
            }

            @Instance object IntShow : Show<Int> {
                override fun show(value: Int): String = "int:${'$'}value"
            }

            @Instance object IntDebug : Debug<Int>

            fun main() {
                println(summon<Debug<Int>>().debug(1))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "debug:int:1",
        )
    }

    @Test
    fun reportsAmbiguousEvidenceInsideDefaultTypeclassMethods() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Typeclass
            interface Debug<A> {
                context(show: Show<A>)
                fun debug(value: A): String = show.show(value)
            }

            @Instance
            object IntShowOne : Show<Int> {
                override fun show(value: Int): String = "one"
            }

            @Instance
            object IntShowTwo : Show<Int> { // ERROR duplicate evidence for Debug<Int>.debug
                override fun show(value: Int): String = "two"
            }

            @Instance
            object IntDebug : Debug<Int>

            fun main() {
                println(summon<Debug<Int>>().debug(1)) // ERROR ambiguous Show<Int> inside default method body
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("ambiguous typeclass instance"),
        )
    }

    @Test
    fun additionalUnrelatedFilesCanChangeResolutionOutcome() {
        val stableSources =
            mapOf(
                "Main.kt" to
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
                        override fun show(value: Int): String = "int:${'$'}value"
                    }

                    context(show: Show<A>)
                    fun <A> render(value: A): String = show.show(value)

                    fun main() {
                        println(render(1))
                    }
                    """.trimIndent(),
            )
        val stableResult = compileSourceInternal(stableSources)
        assertEquals(ExitCode.OK, stableResult.exitCode, stableResult.stdout)

        val unstableSources =
            stableSources +
                (
                    "Orphan.kt" to
                        """
                        package demo

                        import one.wabbit.typeclass.Instance

                        @Instance
                        object OtherIntShow : Show<Int> { // ERROR unrelated file changes stable instance resolution
                            override fun show(value: Int): String = "other:${'$'}value"
                        }
                        """.trimIndent()
                    )

        assertDoesNotCompile(
            sources = unstableSources,
            expectedMessages = listOf("ambiguous typeclass instance"),
        )
    }

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun reportsOverlapBetweenAliasSpecificAndGenericSpecializedInstances() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            typealias UserIds = List<Int>

            @Typeclass
            interface Show<A> {
                fun label(): String
            }

            @Instance
            object IntShow : Show<Int> {
                override fun label(): String = "int"
            }

            @Instance
            object UserIdsShow : Show<UserIds> {
                override fun label(): String = "alias"
            }

            @Instance
            context(show: Show<A>)
            fun <A> listShow(): Show<List<A>> =
                object : Show<List<A>> {
                    override fun label(): String = "list-" + show.label()
                }

            context(_: Show<A>)
            fun <A> which(): String = summon<Show<A>>().label()

            fun main() {
                println(which<UserIds>()) // ERROR alias-specific and generic list instances both satisfy Show<UserIds>
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("ambiguous typeclass instance"),
        )
    }

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun capturesDefinitionSiteEvidenceForReturnedLambdasObjectsAndBoundReferences() {
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
            object GlobalIntShow : Show<Int> {
                override fun show(value: Int): String = "global:${'$'}value"
            }

            context(show: Show<Int>)
            fun makeLambda(): () -> String = { show.show(1) }

            interface Renderer {
                fun render(): String
            }

            context(show: Show<Int>)
            fun makeRenderer(): Renderer =
                object : Renderer {
                    override fun render(): String = show.show(2)
                }

            fun main() {
                val localShow =
                    object : Show<Int> {
                        override fun show(value: Int): String = "local:${'$'}value"
                    }

                val globalLambda = makeLambda()
                val localLambda = context(localShow) { makeLambda() }
                val globalRenderer = makeRenderer()
                val localRenderer = context(localShow) { makeRenderer() }
                val globalBound = makeRenderer()::render
                val localBound = context(localShow) { makeRenderer()::render }

                println(globalLambda())
                context(localShow) {
                    println(globalLambda())
                }
                println(localLambda())
                println(globalRenderer.render())
                println(localRenderer.render())
                println(globalBound())
                println(localBound())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                global:1
                global:1
                local:1
                global:2
                local:2
                global:2
                local:2
                """.trimIndent(),
        )
    }

    // NEW
    @Test
    fun superclassEntailmentRespectsDirectLocalShadowing() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Eq<A> {
                fun label(): String
            }

            @Typeclass
            interface Ord<A> : Eq<A> {
                fun compare(left: A, right: A): Int
            }

            @Instance
            object IntOrd : Ord<Int> {
                override fun label(): String = "ord"

                override fun compare(left: Int, right: Int): Int = left.compareTo(right)
            }

            context(eq: Eq<A>)
            fun <A> which(): String = eq.label()

            fun main() {
                println(which<Int>())

                val localEq =
                    object : Eq<Int> {
                        override fun label(): String = "local-eq"
                    }

                context(localEq) {
                    println(which<Int>())
                }
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                ord
                local-eq
                """.trimIndent(),
        )
    }

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun oneObjectCanProvideMultipleHeadsAndSuperclassEvidence() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Eq<A> {
                fun label(): String
            }

            @Typeclass
            interface Ord<A> : Eq<A> {
                fun compare(left: A, right: A): Int
            }

            @Typeclass
            interface Hash<A> {
                fun hash(value: A): String
            }

            @Instance
            object IntInstances : Ord<Int>, Hash<Int> {
                override fun label(): String = "ord"

                override fun compare(left: Int, right: Int): Int = left.compareTo(right)

                override fun hash(value: Int): String = "hash:${'$'}value"
            }

            context(eq: Eq<A>, hash: Hash<A>)
            fun <A> summary(value: A): String = eq.label() + ":" + hash.hash(value)

            fun main() {
                println(summary(7))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "ord:hash:7",
        )
    }

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun purelyLocalEvidenceSelectsOverloads() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Eq<A>

            @Typeclass
            interface Show<A>

            context(_: Eq<Int>)
            fun choose(value: Int): String = "eq"

            context(_: Show<Int>)
            fun choose(value: Int): String = "show"

            fun main() {
                val localEq = object : Eq<Int> {}
                val localShow = object : Show<Int> {}

                context(localEq) {
                    println(choose(1))
                }
                context(localShow) {
                    println(choose(1))
                }
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                eq
                show
                """.trimIndent(),
        )
    }

    @Test fun reportsNestedAmbiguityFromPrerequisiteResolution() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface JsonWriter<A> {
                fun write(value: A): String
            }

            @Typeclass
            interface BodySerializer<A> {
                fun serialize(value: A): String
            }

            @Instance
            object IntJsonWriterOne : JsonWriter<Int> {
                override fun write(value: Int): String = "one:${'$'}value"
            }

            @Instance
            object IntJsonWriterTwo : JsonWriter<Int> { // ERROR duplicate prerequisite evidence
                override fun write(value: Int): String = "two:${'$'}value"
            }

            @Instance
            context(writer: JsonWriter<A>)
            fun <A> jsonBodySerializer(): BodySerializer<A> =
                object : BodySerializer<A> {
                    override fun serialize(value: A): String = writer.write(value)
                }

            context(serializer: BodySerializer<A>)
            fun <A> send(value: A): String = serializer.serialize(value)

            fun main() {
                println(send(1)) // ERROR should report ambiguous JsonWriter<Int>, not missing BodySerializer<Int>
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("ambiguous", "jsonwriter"),
        )
    }

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun preservesValueClassSpecificityWhenSolvingPrerequisites() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            @JvmInline
            value class UserId(val value: Int)

            data class Box<A>(val value: A)

            @Typeclass
            interface Show<A> {
                fun label(): String
            }

            @Instance
            object IntShow : Show<Int> {
                override fun label(): String = "int"
            }

            @Instance
            object UserIdShow : Show<UserId> {
                override fun label(): String = "user-id"
            }

            @Instance
            context(show: Show<A>)
            fun <A> boxShow(): Show<Box<A>> =
                object : Show<Box<A>> {
                    override fun label(): String = "box-" + show.label()
                }

            context(_: Show<A>)
            fun <A> which(): String = summon<Show<A>>().label()

            fun main() {
                println(which<Box<UserId>>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "box-user-id",
        )
    }

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun preservesProjectionSpecificityWhenSolvingPrerequisites() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            data class Box<A>(val value: A)

            @Typeclass
            interface Show<A> {
                fun label(): String
            }

            @Instance
            object OutStringArrayShow : Show<Array<out String>> {
                override fun label(): String = "out-string-array"
            }

            @Instance
            object InStringArrayShow : Show<Array<in String>> {
                override fun label(): String = "in-string-array"
            }

            @Instance
            context(show: Show<A>)
            fun <A> boxShow(): Show<Box<A>> =
                object : Show<Box<A>> {
                    override fun label(): String = "box-" + show.label()
                }

            context(_: Show<A>)
            fun <A> which(): String = summon<Show<A>>().label()

            fun main() {
                println(which<Box<Array<out String>>>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "box-out-string-array",
        )
    }

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun importsControlWhichInstancesAreVisibleAcrossPackages() {
        val sources =
            mapOf(
                "shared/Api.kt" to
                    """
                    package shared

                    import one.wabbit.typeclass.Typeclass

                    @Typeclass
                    interface Show<A> {
                        fun show(value: A): String
                    }

                    context(show: Show<A>)
                    fun <A> render(value: A): String = show.show(value)
                    """.trimIndent(),
                "alpha/Instances.kt" to
                    """
                    package alpha

                    import one.wabbit.typeclass.Instance
                    import shared.Show

                    @Instance
                    object IntShow : Show<Int> {
                        override fun show(value: Int): String = "alpha:${'$'}value"
                    }
                    """.trimIndent(),
                "beta/Instances.kt" to
                    """
                    package beta

                    import one.wabbit.typeclass.Instance
                    import shared.Show

                    @Instance
                    object IntShow : Show<Int> {
                        override fun show(value: Int): String = "beta:${'$'}value"
                    }
                    """.trimIndent(),
                "demo/UseAlpha.kt" to
                    """
                    package demo

                    import alpha.IntShow
                    import shared.render

                    fun main() {
                        println(render(1))
                    }
                    """.trimIndent(),
                "demo/UseBeta.kt" to
                    """
                    package demo

                    import beta.IntShow
                    import shared.render

                    fun main() {
                        println(render(1))
                    }
                    """.trimIndent(),
            )

        assertCompilesAndRuns(
            sources = sources,
            expectedStdout = "alpha:1",
            mainClass = "demo.UseAlphaKt",
        )
        assertCompilesAndRuns(
            sources = sources,
            expectedStdout = "beta:1",
            mainClass = "demo.UseBetaKt",
        )
    }
}
