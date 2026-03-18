package one.wabbit.typeclass.plugin.integration

import org.junit.Ignore
import kotlin.test.Test

class SurfaceTest : IntegrationTestSupport() {
    @Test fun resolutionIsStableAcrossDeclarationOrder() {
        val sourceWithWorkingRuleFirst =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            @Typeclass
            interface Show<A> {
                fun show(): String
            }

            @Typeclass
            interface Extra<A>

            @Instance
            object IntShow : Show<Int> {
                override fun show(): String = "ok"
            }

            @Instance
            context(_: Show<A>)
            fun <A> listShow(): Show<List<A>> =
                object : Show<List<A>> {
                    override fun show(): String = "ok"
                }

            @Instance
            context(_: Show<A>, _: Extra<A>)
            fun <A> brokenListShow(): Show<List<A>> =
                object : Show<List<A>> {
                    override fun show(): String = "broken"
                }

            context(_: Show<A>)
            fun <A> render(): String = summon<Show<A>>().show()

            fun main() {
                println(render<List<Int>>())
            }
            """.trimIndent()

        val sourceWithBrokenRuleFirst =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            @Typeclass
            interface Show<A> {
                fun show(): String
            }

            @Typeclass
            interface Extra<A>

            @Instance
            object IntShow : Show<Int> {
                override fun show(): String = "ok"
            }

            @Instance
            context(_: Show<A>, _: Extra<A>)
            fun <A> brokenListShow(): Show<List<A>> =
                object : Show<List<A>> {
                    override fun show(): String = "broken"
                }

            @Instance
            context(_: Show<A>)
            fun <A> listShow(): Show<List<A>> =
                object : Show<List<A>> {
                    override fun show(): String = "ok"
                }

            context(_: Show<A>)
            fun <A> render(): String = summon<Show<A>>().show()

            fun main() {
                println(render<List<Int>>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = sourceWithWorkingRuleFirst,
            expectedStdout = "ok",
        )
        assertCompilesAndRuns(
            source = sourceWithBrokenRuleFirst,
            expectedStdout = "ok",
        )
    }

    @Ignore("PHASE2")
    @Test
    fun detectsMutualRecursionAcrossTypeclasses() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Foo<A> {
                fun label(): String
            }

            @Typeclass
            interface Bar<A> {
                fun label(): String
            }

            @Instance
            context(_: Bar<A>)
            fun <A> fooFromBar(): Foo<A> =
                object : Foo<A> {
                    override fun label(): String = "foo"
                }

            @Instance
            context(_: Foo<A>)
            fun <A> barFromFoo(): Bar<A> =
                object : Bar<A> {
                    override fun label(): String = "bar"
                }

            context(_: Foo<A>)
            fun <A> use(): String = "ok"

            fun main() {
                println(use<Int>()) // ERROR Foo<Int> depends on Bar<Int> which depends on Foo<Int>
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("recursive", "foo"),
        )
    }

    @Test fun localEvidenceShadowsTopLevelInstance() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(): String
            }

            @Instance
            object GlobalIntShow : Show<Int> {
                override fun show(): String = "global"
            }

            val localIntShow =
                object : Show<Int> {
                    override fun show(): String = "local"
                }

            context(show: Show<A>)
            fun <A> render(): String = show.show()

            fun main() {
                println(render<Int>())
                context(localIntShow) {
                    println(render<Int>())
                }
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                global
                local
                """.trimIndent(),
        )
    }

    @Test
    fun reportsDuplicateInstancesAcrossFiles() {
        val sources =
            mapOf(
                "demo/InstancesOne.kt" to
                    """
                    package demo

                    import one.wabbit.typeclass.Instance
                    import one.wabbit.typeclass.Typeclass

                    @Typeclass
                    interface Show<A> {
                        fun show(): String
                    }

                    @Instance
                    object IntShowOne : Show<Int> { // ERROR: ambiguous instance declaration
                        override fun show(): String = "one"
                    }
                    """.trimIndent(),
                "demo/InstancesTwo.kt" to
                    """
                    package demo

                    import one.wabbit.typeclass.Instance

                    @Instance
                    object IntShowTwo : Show<Int> { // ERROR: ambiguous instance declaration
                        override fun show(): String = "two"
                    }
                    """.trimIndent(),
                "demo/Main.kt" to
                    """
                    package demo

                    context(show: Show<A>)
                    fun <A> render(): String = show.show()

                    fun main() {
                        println(render<Int>()) // ERROR duplicate instances across files
                    }
                    """.trimIndent(),
            )

        assertDoesNotCompile(
            sources = sources,
            expectedMessages = listOf("ambiguous", "show"),
        )
    }

    @Test fun resolvesAssociatedCompanionObjectInstances() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            data class Box<A>(val value: A) {
                companion object {
                    @Instance
                    object IntBoxShow : Show<Box<Int>> {
                        override fun show(value: Box<Int>): String = "companion-object:${'$'}{value.value}"
                    }
                }
            }

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(Box(1)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "companion-object:1",
        )
    }

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun handlesRecursiveDerivedAdtsWithoutCrashing() {
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

            @Derive(Show::class)
            sealed class Tree

            @Derive(Show::class)
            data class Branch(val left: Tree, val right: Tree) : Tree()

            @Derive(Show::class)
            object Leaf : Tree()

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(Branch(Leaf, Leaf))) // ERROR recursive derivation should fail clearly or work safely
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("recursive", "show"),
        )
    }

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun derivesNestedGenericSealedHierarchies() {
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
            sealed class Envelope<out A>

            @Derive(Show::class)
            data class Value<A>(val value: A) : Envelope<A>()

            @Derive(Show::class)
            sealed class Marker : Envelope<Nothing>()

            @Derive(Show::class)
            object Missing : Marker()

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                val missing: Envelope<Int> = Missing
                println(render(Value(1)))
                println(render(missing))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                Value(value=1)
                Missing()
                """.trimIndent(),
        )
    }

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun distinguishesValueClassesFromUnderlyingTypes() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @JvmInline
            value class UserId(val value: Int)

            data class Box<A>(val value: A)

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(value: Int): String = "int:${'$'}value"
            }

            @Instance
            object UserIdShow : Show<UserId> {
                override fun show(value: UserId): String = "user:${'$'}{value.value}"
            }

            @Instance
            context(show: Show<A>)
            fun <A> boxShow(): Show<Box<A>> =
                object : Show<Box<A>> {
                    override fun show(value: Box<A>): String = "box-" + show.show(value.value)
                }

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(UserId(1)))
                println(render(Box(UserId(2))))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                user:1
                box-user:2
                """.trimIndent(),
        )
    }

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun reportsConflictingBindingsFromLocalContexts() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A>

            @Instance
            object IntShow : Show<Int>

            @Instance
            object StringListShow : Show<List<String>>

            context(_: Show<A>, _: Show<List<A>>)
            fun <A> choose(): String = "ok"

            fun main() {
                context(IntShow) {
                    context(StringListShow) {
                        println(choose()) // ERROR A is inferred as both Int and String
                    }
                }
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("conflicting", "binding"),
        )
    }

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun derivedInstancesCanUseContextualFieldInstances() {
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

            @Instance
            context(show: Show<A>)
            fun <A> listShow(): Show<List<A>> =
                object : Show<List<A>> {
                    override fun show(value: List<A>): String =
                        value.joinToString(prefix = "[", postfix = "]") { element -> show.show(element) }
                }

            @Derive(Show::class)
            data class Wrapper<A>(val values: List<A>)

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(Wrapper(listOf(1, 2))))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "Wrapper(values=[1, 2])",
        )
    }

    @Test
    fun reportsAmbiguityBetweenNullableSpecificAndGenericRules() {
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
            object NullableIntEq : Eq<Int?> {
                override fun label(): String = "nullable"
            }

            @Instance
            context(eq: Eq<A>)
            fun <A> listEq(): Eq<List<A>> =
                object : Eq<List<A>> {
                    override fun label(): String = "generic-" + eq.label()
                }

            @Instance
            object NullableIntListEq : Eq<List<Int?>> {
                override fun label(): String = "specific-nullable"
            }

            context(_: Eq<A>)
            fun <A> which(): String = summon<Eq<A>>().label()

            fun main() {
                println(which<List<Int?>>()) // ERROR decide whether specific or generic nullable rule should win
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("ambiguous", "nullable"),
        )
    }

    @Ignore("NEW: contextual property access support is not implemented yet")
    @Test
    fun resolvesContextualPropertyGetter() {
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
            val intLabel: String
                get() = show.show(1)

            fun main() {
                println(intLabel)
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "int:1",
        )
    }

    @Ignore("NEW: contextual extension property access support is not implemented yet")
    @Test
    fun resolvesContextualExtensionPropertyGetter() {
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
            val Int.rendered: String
                get() = show.show(this)

            fun main() {
                println(1.rendered)
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "int:1",
        )
    }

    @Ignore("NEW: contextual callable-reference adaptation is not implemented yet")
    @Test
    fun adaptsCallableReferencesToContextualTopLevelFunctions() {
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

            fun consume(f: (Int) -> String): String = f(1)

            fun main() {
                println(consume(::renderInt))
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
    fun adaptsBoundCallableReferencesToContextualMemberFunctions() {
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

            class Items {
                context(show: Show<Int>)
                fun renderInt(value: Int): String = show.show(value)
            }

            fun consume(f: (Int) -> String): String = f(1)

            fun main() {
                println(consume(Items()::renderInt))
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
    fun adaptsPropertyReferencesToContextualProperties() {
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
            val intLabel: String
                get() = show.show(1)

            fun main() {
                val getter = ::intLabel
                println(getter.get())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "int:1",
        )
    }

    @Test
    fun resolvesSuspendContextualFunctions() {
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
            suspend fun renderInt(value: Int): String = show.show(value)

            suspend fun main() {
                println(renderInt(1))
                context(IntShow) {
                    println(renderInt(2))
                }
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
    fun resolvesContextualCallsInsideSuspendLambdas() {
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
            suspend fun renderInt(value: Int): String = show.show(value)

            suspend fun consume(block: suspend () -> String): String = block()

            suspend fun main() {
                println(consume { renderInt(1) })
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
    fun supportsBuilderInferenceAroundContextualCalls() {
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

            fun main() {
                val rendered = buildList {
                    add(renderInt(1))
                    add(renderInt(2))
                }
                println(rendered.joinToString())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "int:1, int:2",
        )
    }

    @Ignore("PHASE4")
    @Test
    fun treatsTypeAliasesAsTransparentForResolution() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            typealias UserId = Int

            @Typeclass
            interface Show<A> {
                fun show(): String
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(): String = "int"
            }

            context(_: Show<A>)
            fun <A> render(): String = summon<Show<A>>().show()

            fun main() {
                println(render<UserId>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "int",
        )
    }

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun treatsNestedTypeAliasesAsTransparentForResolution() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            object Aliases {
                typealias UserId = Int
            }

            @Typeclass
            interface Show<A> {
                fun show(): String
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(): String = "int"
            }

            context(_: Show<A>)
            fun <A> render(): String = summon<Show<A>>().show()

            fun main() {
                println(render<Aliases.UserId>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "int",
        )
    }

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun supportsFunInterfacesAsTypeclassesAndLambdaInstances() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            fun interface Eq<A> {
                fun eq(left: A, right: A): Boolean
            }

            @Instance
            val intEq = Eq<Int> { left, right -> left == right }

            context(eq: Eq<A>)
            fun <A> same(value: A): Boolean = eq.eq(value, value)

            fun main() {
                println(same(1))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "true",
        )
    }

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun preservesUseSiteVarianceInTypeclassGoals() {
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
            object IntShow : Show<Int> {
                override fun label(): String = "int"
            }

            @Instance
            context(show: Show<A>)
            fun <A> outArrayShow(): Show<Array<out A>> =
                object : Show<Array<out A>> {
                    override fun label(): String = "array-" + show.label()
                }

            context(show: Show<Array<out Int>>)
            fun render(): String = show.label()

            fun main() {
                println(render())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "array-int",
        )
    }

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun resolvesLocalContextualFunctions() {
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

            fun main() {
                context(show: Show<Int>)
                fun renderLocal(value: Int): String = show.show(value)

                println(renderLocal(1))
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
    fun doesNotDiscoverLocalInstanceDeclarationsGlobally() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                @Instance
                object LocalIntShow : Show<Int> {
                    override fun show(value: Int): String = "int:${'$'}value"
                }

                println(render(1)) // ERROR local @Instance declarations should not be auto-discovered
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("no context argument", "render(1)"),
        )
    }

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun resolvesContextualCallsInsideLocalDelegatedProperties() {
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

            fun main() {
                val rendered by lazy { renderInt(1) }
                println(rendered)
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
    fun preservesExtensionFunctionReferenceInterchangeability() {
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
            fun Int.rendered(suffix: String): String = show.show(this) + suffix

            fun consumeAsExtension(f: Int.(String) -> String): String = 1.f("!")

            fun consumeAsPlain(f: (Int, String) -> String): String = f(1, "?")

            fun main() {
                println(consumeAsExtension(Int::rendered))
                println(consumeAsPlain(Int::rendered))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                int:1!
                int:1?
                """.trimIndent(),
        )
    }

    @Test
    fun doesNotLeakPrivateTopLevelInstancesAcrossFiles() {
        val sources =
            mapOf(
                "Hidden.kt" to
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
                    private object HiddenIntShow : Show<Int> {
                        override fun show(): String = "hidden"
                    }

                    context(_: Show<A>)
                    fun <A> render(): String = summon<Show<A>>().show()
                    """.trimIndent(),
                "Main.kt" to
                    """
                    package demo

                    fun main() {
                        println(render<Int>()) // ERROR private @Instance declarations should not leak across files
                    }
                    """.trimIndent(),
            )

        assertDoesNotCompile(
            sources = sources,
            expectedMessages = listOf("no context argument"),
        )
    }

    @Test
    fun combinesExplicitRegularContextWithImplicitTypeclassResolution() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            data class Prefix(val value: String)

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(value: Int): String = "int:${'$'}value"
            }

            context(prefix: Prefix, show: Show<Int>)
            fun renderInt(value: Int): String = prefix.value + show.show(value)

            fun main() {
                context(Prefix("value=")) {
                    println(renderInt(1))
                }
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "value=int:1",
        )
    }

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun distinguishesShadowedTypeParametersAcrossNestedGenericScopes() {
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
            object StringShow : Show<String> {
                override fun show(value: String): String = "string:${'$'}value"
            }

            context(outerShow: Show<T>)
            fun <T> outer(value: T): String {
                context(innerShow: Show<T>)
                fun <T> inner(inner: T): String = outerShow.show(value) + "/" + innerShow.show(inner)

                return inner("x")
            }

            fun main() {
                println(outer(1))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "int:1/string:x",
        )
    }

    @Test
    fun rewritesContextualCallsInsideDefaultValueExpressions() {
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

            context(_: Show<Int>)
            fun outer(prefix: String = renderInt(1)): String = prefix + "!"

            fun main() {
                println(outer())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "int:1!",
        )
    }

    @Test
    fun rewritesContextualCallsInsideConstructorDelegation() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            open class Base(val rendered: String)

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

            fun main() {
                class Derived : Base(renderInt(1))

                println(Derived().rendered)
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "int:1",
        )
    }

    @Test
    fun rewritesContextualCallsInsideInterfaceDelegation() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            interface Renderer {
                fun render(): String
            }

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(value: Int): String = "int:${'$'}value"
            }

            context(show: Show<Int>)
            fun makeRenderer(): Renderer =
                object : Renderer {
                    override fun render(): String = show.show(1)
                }

            fun main() {
                class Derived : Renderer by makeRenderer()

                println(Derived().render())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "int:1",
        )
    }

    @Test
    fun rewritesContextualGetOperatorCalls() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            class Bag

            @Typeclass
            interface Index<A> {
                fun read(receiver: A, index: Int): String
            }

            @Instance
            object BagIndex : Index<Bag> {
                override fun read(receiver: Bag, index: Int): String = "slot:${'$'}index"
            }

            context(accessor: Index<A>)
            operator fun <A> A.get(index: Int): String = accessor.read(this, index)

            fun main() {
                println(Bag()[1])
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "slot:1",
        )
    }

    @Test
    fun rewritesContextualGetOperatorCallsInsideMemberDispatchScope() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Index<A> {
                fun read(receiver: A, index: Int): String
            }

            class Bag {
                companion object {
                    @Instance
                    val index =
                        object : Index<Bag> {
                            override fun read(receiver: Bag, index: Int): String = "slot:${'$'}index"
                        }
                }
            }

            class Accessors {
                context(accessor: Index<A>)
                operator fun <A> A.get(index: Int): String = accessor.read(this, index)
            }

            fun main() {
                with(Accessors()) {
                    println(Bag()[1])
                }
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "slot:1",
        )
    }

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun rewritesContextualSetOperatorCalls() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            class Bag {
                val entries = linkedMapOf<Int, String>()
            }

            @Typeclass
            interface MutableIndex<A> {
                fun write(receiver: A, index: Int, value: String)
            }

            @Instance
            object BagMutableIndex : MutableIndex<Bag> {
                override fun write(receiver: Bag, index: Int, value: String) {
                    receiver.entries[index] = value
                }
            }

            context(index: MutableIndex<A>)
            operator fun <A> A.set(position: Int, value: String) {
                index.write(this, position, value)
            }

            fun main() {
                val bag = Bag()
                bag[2] = "written"
                println(bag.entries[2])
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "written",
        )
    }

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun rewritesContextualContainsOperatorCalls() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            class Tags(private val values: Set<String>)

            @Typeclass
            interface Membership<A> {
                fun contains(receiver: A, value: String): Boolean
            }

            @Instance
            object TagsMembership : Membership<Tags> {
                override fun contains(receiver: Tags, value: String): Boolean = value in receiver.values
            }

            context(membership: Membership<A>)
            operator fun <A> A.contains(value: String): Boolean = membership.contains(this, value)

            fun main() {
                println("green" in Tags(setOf("green", "blue")))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "true",
        )
    }

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun rewritesContextualIteratorOperatorCalls() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            class Tokens(val values: List<String>)

            @Typeclass
            interface IterableTc<A> {
                fun iterator(receiver: A): Iterator<String>
            }

            @Instance
            object TokensIterable : IterableTc<Tokens> {
                override fun iterator(receiver: Tokens): Iterator<String> = receiver.values.iterator()
            }

            context(iterable: IterableTc<A>)
            operator fun <A> A.iterator(): Iterator<String> = iterable.iterator(this)

            fun main() {
                val tokens = Tokens(listOf("a", "b", "c"))
                val rendered = buildString {
                    for (token in tokens) {
                        append(token)
                    }
                }
                println(rendered)
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "abc",
        )
    }

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun rewritesContextualComponentOperatorCalls() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            data class PairBox(val left: String, val right: String)

            @Typeclass
            interface Components<A> {
                fun first(receiver: A): String
                fun second(receiver: A): String
            }

            @Instance
            object PairBoxComponents : Components<PairBox> {
                override fun first(receiver: PairBox): String = receiver.left
                override fun second(receiver: PairBox): String = receiver.right
            }

            context(components: Components<A>)
            operator fun <A> A.component1(): String = components.first(this)

            context(components: Components<A>)
            operator fun <A> A.component2(): String = components.second(this)

            fun main() {
                val (left, right) = PairBox("L", "R")
                println("${'$'}left/${'$'}right")
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "L/R",
        )
    }

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun rewritesContextualCompareToOperatorCalls() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Ord<A> {
                fun compare(left: A, right: A): Int
            }

            @Instance
            object IntOrd : Ord<Int> {
                override fun compare(left: Int, right: Int): Int = left.compareTo(right)
            }

            context(ord: Ord<A>)
            operator fun <A> A.compareTo(other: A): Int = ord.compare(this, other)

            fun main() {
                println(1 < 2)
                println(2 > 1)
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

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun rewritesContextualPlusAssignOperatorCalls() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            class Counter(var total: Int)

            @Typeclass
            interface AddInto<A> {
                fun add(receiver: A, amount: Int)
            }

            @Instance
            object CounterAddInto : AddInto<Counter> {
                override fun add(receiver: Counter, amount: Int) {
                    receiver.total += amount
                }
            }

            context(addInto: AddInto<A>)
            operator fun <A> A.plusAssign(amount: Int) {
                addInto.add(this, amount)
            }

            fun main() {
                val counter = Counter(5)
                counter += 7
                println(counter.total)
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "12",
        )
    }

    @Test
    fun derivesEnumClasses() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.SumTypeclassMetadata
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.TypeclassDeriver

            @Typeclass
            interface Show<A> {
                fun show(value: A): String

                companion object : TypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                        object : Show<Any?> {
                            override fun show(value: Any?): String = value.toString()
                        }

                    override fun deriveSum(metadata: SumTypeclassMetadata): Any =
                        object : Show<Any?> {
                            override fun show(value: Any?): String = value.toString()
                        }
                }
            }

            @Derive(Show::class)
            enum class Color {
                RED,
                BLUE,
            }

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(Color.RED))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "RED",
        )
    }
}
