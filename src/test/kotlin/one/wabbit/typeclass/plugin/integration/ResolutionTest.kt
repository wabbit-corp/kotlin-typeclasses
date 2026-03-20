package one.wabbit.typeclass.plugin.integration

import org.junit.Ignore
import kotlin.test.Test

class ResolutionTest : IntegrationTestSupport() {
    @Test fun compilesLocalInferenceThroughTopLevelInstanceFunction() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            @Typeclass
            interface Eq<A> {
                fun eq(left: A, right: A): Boolean
            }

            @Instance
            object IntEq : Eq<Int> {
                override fun eq(left: Int, right: Int): Boolean = left == right
            }

            @Instance
            context(A: Eq<A>, B: Eq<B>)
            fun <A, B> pairEq(): Eq<Pair<A, B>> =
                object : Eq<Pair<A, B>> {
                    override fun eq(left: Pair<A, B>, right: Pair<A, B>): Boolean =
                        A.eq(left.first, right.first) &&
                        B.eq(left.second, right.second)
                }

            context(_: Eq<A>)
            fun <A> foo(a: A): Boolean = summon<Eq<A>>().eq(a, a)

            context(_: Eq<A>)
            fun <A> bar(a: A): Boolean = foo<Pair<A, A>>(a to a)

            fun main() {
                println(bar(1))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "true",
        )
    }

    @Test fun instantiatesAllTypeParametersFromOrdinaryArgumentsBeforeResolvingContextualEvidenceOnMemberCalls() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            class Foo(val value: Int)
            class Bar(val value: String)

            @Typeclass
            interface Pairing<A, B> {
                fun label(): String
            }

            @Instance
            object FooBarPairing : Pairing<Foo, Bar> {
                override fun label(): String = "foo-bar"
            }

            class Ops {
                context(pairing: Pairing<A, B>)
                fun <A, B> combine(left: A, right: B): String =
                    pairing.label() + ":" + (left is Foo) + ":" + (right is Bar)
            }

            fun main() {
                val ops = Ops()
                println(ops.combine(Foo(1), Bar("x")))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "foo-bar:true:true",
        )
    }

    @Test fun compilesAssociatedCompanionInstanceFunction() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Eq<A> {
                fun eq(left: A, right: A): Boolean
            }

            @Instance
            object IntEq : Eq<Int> {
                override fun eq(left: Int, right: Int): Boolean = left == right
            }

            data class Box<A>(val value: A) {
                companion object {
                    @Instance
                    context(_: Eq<A>)
                    fun <A> boxEq(): Eq<Box<A>> =
                        object : Eq<Box<A>> {
                            override fun eq(left: Box<A>, right: Box<A>): Boolean = true
                        }
                }
            }

            context(_: Eq<A>)
            fun <A> useBox(box: Box<A>): Boolean = true

            context(_: Eq<A>)
            fun <A> nested(a: A): Boolean = useBox(Box(a))

            fun main() {
                println(nested(1))
            }
            """.trimIndent()

        assertCompiles(source)
    }

    @Test fun resolvesCompanionInstancesForMultiParameterTypeclasses() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            object Host {
                @Typeclass
                interface TestTypeclass<A, B> {
                    companion object {
                        @Instance
                        val instance: TestTypeclass<String, Int> =
                            object : TestTypeclass<String, Int> {}

                        @Instance
                        fun <A> refl(): TestTypeclass<A, A> =
                            object : TestTypeclass<A, A> {}
                    }
                }
            }

            context(t: Host.TestTypeclass<A, B>)
            fun <A, B> customSummon(): Host.TestTypeclass<A, B> = t

            fun main() {
                println(summon<Host.TestTypeclass<String, Int>>() === Host.TestTypeclass.instance)
                println(summon<Host.TestTypeclass<Int, Int>>() is Host.TestTypeclass<*, *>)
                println(customSummon<String, Int>() === Host.TestTypeclass.instance)
                run {
                    println(customSummon<Nothing, Nothing>() is Host.TestTypeclass<*, *>)
                }
                context(Host.TestTypeclass.refl<Int>()) {
                    println(summon<Host.TestTypeclass<Int, Int>>() is Host.TestTypeclass<*, *>)
                }
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                true
                true
                true
                true
                true
                """.trimIndent(),
        )
    }

    @Test fun resolvesMemberContextAccessorsWithDispatchReceiver() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            object Host {
                @Typeclass
                interface TestTypeclass<A, B> {
                    companion object {
                        @Instance
                        val instance: TestTypeclass<String, Int> =
                            object : TestTypeclass<String, Int> {}

                        @Instance
                        fun <A> refl(): TestTypeclass<A, A> =
                            object : TestTypeclass<A, A> {}
                    }
                }

                context(t: TestTypeclass<A, B>)
                fun <A, B> customSummon(): TestTypeclass<A, B> = t

                fun runChecks() {
                    println(summon<TestTypeclass<String, Int>>() === TestTypeclass.instance)
                    println(summon<TestTypeclass<Int, Int>>() is TestTypeclass<*, *>)
                    println(customSummon<String, Int>() === TestTypeclass.instance)
                    run {
                        println(customSummon<Nothing, Nothing>() is TestTypeclass<*, *>)
                    }
                    context(TestTypeclass.refl<Int>()) {
                        println(summon<TestTypeclass<Int, Int>>() is TestTypeclass<*, *>)
                    }
                }
            }

            fun main() {
                Host.runChecks()
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                true
                true
                true
                true
                true
                """.trimIndent(),
        )
    }

    @Test fun compilesAndRunsContextualExtensionThroughTopLevelObjectInstance() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Monoid<A> {
                fun combine(left: A, right: A): A
            }

            @Instance
            object IntMonoid : Monoid<Int> {
                override fun combine(left: Int, right: Int): Int = left + right
            }

            data class Duo(val left: Int, val right: Int)

            @Instance
            object DuoMonoid : Monoid<Duo> {
                override fun combine(left: Duo, right: Duo): Duo =
                    Duo(left.left + right.left, left.right + right.right)
            }

            context(monoid: Monoid<A>)
            fun <A> A.combineWith(other: A): A = monoid.combine(this, other)

            fun main() {
                println(Duo(1, 1).combineWith(Duo(1, 1)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "Duo(left=2, right=2)",
        )
    }

    @Test fun compilesAndRunsContextualExtensionThroughAssociatedCompanionInstance() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            @Typeclass
            interface Monoid<A> {
                fun combine(left: A, right: A): A
            }

            @Instance
            object IntMonoid : Monoid<Int> {
                override fun combine(left: Int, right: Int): Int = left + right
            }

            data class Box<A>(val value: A) {
                companion object {
                    @Instance
                    context(monoid: Monoid<A>)
                    fun <A> boxMonoid(): Monoid<Box<A>> =
                        object : Monoid<Box<A>> {
                            override fun combine(left: Box<A>, right: Box<A>): Box<A> =
                                Box(monoid.combine(left.value, right.value))
                        }
                }
            }

            context(_: Monoid<A>)
            operator fun <A> A.plus(other: A): A = summon<Monoid<A>>().combine(this@plus, other)

            fun main() {
                println((Box(1) + Box(2)).value)
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "3",
        )
    }

    @Test fun compilesGenericContextualCompanionFactory() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface ItemComponentType<A> {
                fun render(value: A): String
            }

            data class SomeItemComponent<C>(val value: C, val type: ItemComponentType<C>) {
                companion object {
                    context(type: ItemComponentType<C>)
                    fun <C> create(value: C): SomeItemComponent<C> = SomeItemComponent(value, type)
                }
            }

            data class Curse(val soulbound: Boolean) {
                companion object {
                    @Instance
                    val itemComponentType =
                        object : ItemComponentType<Curse> {
                            override fun render(value: Curse): String = "Curse(${ '$' }{value.soulbound})"
                        }
                }
            }

            fun main() {
                with(Curse.itemComponentType) {
                    println(SomeItemComponent.create(Curse(true)).value.soulbound)
                }
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "true",
        )
    }

    @Test fun compilesAssociatedCompanionInvokeWithoutExplicitContext() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface ItemComponentType<A> {
                fun label(): String
            }

            data class SomeItemComponent<C>(val value: C, val type: ItemComponentType<C>) {
                companion object {
                    context(type: ItemComponentType<C>)
                    operator fun <C> invoke(value: C): SomeItemComponent<C> = SomeItemComponent(value, type)
                }
            }

            data class Curse(val soulbound: Boolean) {
                companion object {
                    @Instance
                    val itemComponentType =
                        object : ItemComponentType<Curse> {
                            override fun label(): String = "curse"
                        }
                }
            }

            fun main() {
                println(SomeItemComponent(Curse(true)).type.label())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "curse",
        )
    }

    @Test fun supportsExplicitTypeArgumentsOnContextualCalls() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            class ItemStack

            @Typeclass
            interface ItemComponentType<A> {
                fun label(): String
            }

            data class Curse(val soulbound: Boolean) {
                companion object {
                    @Instance
                    val itemComponentType =
                        object : ItemComponentType<Curse> {
                            override fun label(): String = "curse"
                        }
                }
            }

            class Items {
                context(type: ItemComponentType<Type>)
                fun <Type> hasComponent(item: ItemStack): Boolean = type.label() == "curse"
            }

            fun main() {
                println(Items().hasComponent<Curse>(ItemStack()))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "true",
        )
    }

    @Test fun infersTypeArgumentsFromOuterContextInsideNestedLambda() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            class ItemMeta

            class ItemStack {
                fun withItemMeta(block: (ItemMeta) -> Unit) {
                    block(ItemMeta())
                }
            }

            @Typeclass
            interface ItemComponentType<A> {
                fun label(): String
            }

            data class Curse(val soulbound: Boolean) {
                companion object {
                    @Instance
                    val itemComponentType =
                        object : ItemComponentType<Curse> {
                            override fun label(): String = "curse"
                        }
                }
            }

            class Items {
                context(type: ItemComponentType<Type>)
                fun <Type> removeComponent(item: ItemMeta) {
                    println(type.label())
                }

                context(type: ItemComponentType<Type>)
                fun <Type> removeFromItem(item: ItemStack) {
                    item.withItemMeta { meta ->
                        removeComponent(meta)
                    }
                }
            }

            fun main() {
                with(Curse.itemComponentType) {
                    Items().removeFromItem(ItemStack())
                }
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "curse",
        )
    }

    @Test fun supportsCrossFileExplicitTypeArgumentsOnNestedOverloadedSelfCall() {
        val sources =
            mapOf(
                "demo/Helpers.kt" to
                    """
                    package demo

                    class ItemMeta

                    class ItemStack {
                        fun withItemMeta(block: (ItemMeta) -> Unit): ItemStack {
                            block(ItemMeta())
                            return this
                        }
                    }
                    """.trimIndent(),
                "demo/Items.kt" to
                    """
                    package demo

                    import one.wabbit.typeclass.Typeclass

                    @Typeclass
                    interface ItemComponentType<A> {
                        fun label(): String
                    }

                    class Items {
                        context(type: ItemComponentType<Type>)
                        fun <Type> removeComponent(item: ItemStack): ItemStack =
                            item.withItemMeta { meta -> removeComponent<Type>(meta) }

                        context(type: ItemComponentType<Type>)
                        fun <Type> removeComponent(item: ItemMeta) {
                            println(type.label())
                        }
                    }
                    """.trimIndent(),
                "demo/Main.kt" to
                    """
                    package demo

                    import one.wabbit.typeclass.Instance

                    data class Curse(val soulbound: Boolean) {
                        companion object {
                            @Instance
                            val itemComponentType =
                                object : ItemComponentType<Curse> {
                                    override fun label(): String = "curse"
                                }
                        }
                    }

                    fun main() {
                        println(Items().removeComponent<Curse>(ItemStack()) is ItemStack)
                    }
                    """.trimIndent(),
            )

        assertCompilesAndRuns(
            sources = sources,
            expectedStdout =
                """
                curse
                true
                """.trimIndent(),
            mainClass = "demo.MainKt",
        )
    }

    @Test fun supportsExplicitTypeArgumentsOnNestedOverloadedSelfCall() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            class ItemMeta

            class ItemStack {
                fun withItemMeta(block: (ItemMeta) -> Unit): ItemStack {
                    block(ItemMeta())
                    return this
                }
            }

            @Typeclass
            interface ItemComponentType<A> {
                fun label(): String
            }

            data class Curse(val soulbound: Boolean) {
                companion object {
                    @Instance
                    val itemComponentType =
                        object : ItemComponentType<Curse> {
                            override fun label(): String = "curse"
                        }
                }
            }

            class Items {
                context(type: ItemComponentType<Type>)
                fun <Type> removeComponent(item: ItemStack): ItemStack =
                    item.withItemMeta { meta -> removeComponent<Type>(meta) }

                context(type: ItemComponentType<Type>)
                fun <Type> removeComponent(item: ItemMeta) {
                    println(type.label())
                }
            }

            fun main() {
                println(Items().removeComponent<Curse>(ItemStack()) is ItemStack)
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                curse
                true
                """.trimIndent(),
        )
    }

    @Test fun usesUserDefinedContextAccessorWithoutSpecialCasingSummon() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Eq<A> {
                fun eq(left: A, right: A): Boolean
            }

            @Instance
            object IntEq : Eq<Int> {
                override fun eq(left: Int, right: Int): Boolean = left == right
            }

            context(value: T)
            fun <T> ask(): T = value

            context(_: Eq<A>)
            fun <A> same(a: A): Boolean = ask<Eq<A>>().eq(a, a)

            fun main() {
                println(same(1))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "true",
        )
    }

    @Test fun resolvesAnonymousLocalTypeclassContexts() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            @Typeclass
            interface Marker

            fun main() {
                context(object : Marker {}) {
                    println(summon<Marker>() is Marker)
                }
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "true",
        )
    }

    @Test fun infersTypeArgumentsFromLocalTypeclassContext() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            class ItemMeta

            @Typeclass
            interface ItemComponentType<A> {
                fun label(): String
            }

            data class Curse(val soulbound: Boolean) {
                companion object {
                    @Instance
                    val itemComponentType =
                        object : ItemComponentType<Curse> {
                            override fun label(): String = "curse"
                        }
                }
            }

            context(type: ItemComponentType<Type>)
            fun <Type> removeComponent(item: ItemMeta) {
                println(type.label())
            }

            context(type: ItemComponentType<Type>)
            fun <Type> removeFromMeta(item: ItemMeta) {
                removeComponent(item)
            }

            fun main() {
                with(Curse.itemComponentType) {
                    removeFromMeta(ItemMeta())
                }
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "curse",
        )
    }

    @Test fun preservesNullabilityWhenResolvingGenericInstances() {
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
            object IntEq : Eq<Int> {
                override fun label(): String = "int"
            }

            @Instance
            object NullableIntEq : Eq<Int?> {
                override fun label(): String = "nullable"
            }

            @Instance
            context(element: Eq<A>)
            fun <A> listEq(): Eq<List<A>> =
                object : Eq<List<A>> {
                    override fun label(): String = "list-" + element.label()
                }

            context(_: Eq<A>)
            fun <A> which(): String = summon<Eq<A>>().label()

            fun main() {
                println(which<List<Int>>())
                println(which<List<Int?>>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                list-int
                list-nullable
                """.trimIndent(),
        )
    }

    @Test fun resolvesTopLevelInstanceProperty() {
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
            val intShow =
                object : Show<Int> {
                    override fun show(): String = "property"
                }

            context(_: Show<A>)
            fun <A> render(): String = summon<Show<A>>().show()

            fun main() {
                println(render<Int>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "property",
        )
    }

    @Test fun compilesAndRunsDerivedInstances() {
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
            data class Box<A>(val value: A)

            @Derive(Show::class)
            sealed class Option<out A>
            data class Some<A>(val value: A) : Option<A>()
            object None : Option<Nothing>()

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                val some: Option<Int> = Some(1)
                val none: Option<Int> = None
                println(render(Box<Int>(1)))
                println(render(some))
                println(render(none))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                Box(value=1)
                Some(value=1)
                None()
                """.trimIndent(),
        )
    }

    @Test fun reportsAmbiguityBetweenSpecificAndGenericInstances() {
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

            data class Box<A>(val value: A) {
                companion object {
                    @Instance
                    context(_: Show<A>)
                    fun <A> genericBoxShow(): Show<Box<A>> =
                        object : Show<Box<A>> {
                            override fun show(): String = "generic"
                        }

                    @Instance
                    val intBoxShow =
                        object : Show<Box<Int>> {
                            override fun show(): String = "specific"
                        }
                }
            }

            context(_: Show<A>)
            fun <A> render(): String = summon<Show<A>>().show()

            fun main() {
                println(render<Box<Int>>()) // E:TC_NO_CONTEXT_ARGUMENT generic and specific instances both match
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("no context argument", "show"),
            expectedDiagnostics =
                listOf(
                    ExpectedDiagnostic.Error(messageRegex = "(?i)no context argument.*show"),
                ),
        )
    }

    @Test fun reportsAmbiguousPrerequisiteInsteadOfMissingResult() {
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
            object IntShowOne : Show<Int> {
                override fun show(value: Int): String = "one"
            }

            @Instance
            object IntShowTwo : Show<Int> { // ambiguous instance declaration
                override fun show(value: Int): String = "two"
            }

            data class Box<A>(val value: A)

            @Instance
            context(show: Show<A>)
            fun <A> boxShow(): Show<Box<A>> =
                object : Show<Box<A>> {
                    override fun show(value: Box<A>): String = show.show(value.value)
                }

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(Box(1))) // E:TC_NO_CONTEXT_ARGUMENT prerequisite Show<Int> is ambiguous
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("ambiguous", "int"),
            expectedDiagnostics = listOf(expectedErrorContaining("no context argument", "show")),
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

    @Test fun resolvesOverloadsThatDifferOnlyByTypeclassContexts() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Eq<A> {
                fun label(): String
            }

            @Typeclass
            interface Show<A> {
                fun label(): String
            }

            object IntEq : Eq<Int> {
                override fun label(): String = "eq"
            }

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

    @Test fun resolvesDefinitelyNonNullTypeclassGoals() {
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

    @Test fun resolvesApparentlyAmbiguousApisFromExpectedTypeReceiverTypeAndOuterContext() {
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

    @Test fun purelyLocalEvidenceSelectsOverloads() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Eq<A> {
                fun label(): String
            }

            @Typeclass
            interface Show<A> {
                fun label(): String
            }

            context(eq: Eq<Int>)
            fun choose(value: Int): String = "eq:" + eq.label()

            context(show: Show<Int>)
            fun choose(value: Int): String = "show:" + show.label()

            fun main() {
                val localEq =
                    object : Eq<Int> {
                        override fun label(): String = "local-eq"
                    }
                val localShow =
                    object : Show<Int> {
                        override fun label(): String = "local-show"
                    }

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
                eq:local-eq
                show:local-show
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
            object IntJsonWriterTwo : JsonWriter<Int> { // duplicate prerequisite evidence
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
                println(send(1)) // E:TC_NO_CONTEXT_ARGUMENT should report ambiguous JsonWriter<Int>, not missing BodySerializer<Int>
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("ambiguous", "jsonwriter"),
            expectedDiagnostics = listOf(expectedErrorContaining("no context argument", "bodyserializer")),
        )
    }

    @Test fun preservesValueClassSpecificityWhenSolvingPrerequisites() {
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

    @Test fun preservesProjectionSpecificityWhenSolvingPrerequisites() {
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

    @Test fun reportsMissingLeafInstanceForLargeDerivedProduct() {
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

            @Typeclass
            interface Show<A> {
                fun show(value: A): String

                companion object : TypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                        object : Show<Any?> {
                            override fun show(value: Any?): String {
                                require(value != null)
                                return metadata.fields.joinToString(separator = "|") { field ->
                                    val fieldShow = field.instance as Show<Any?>
                                    fieldShow.show(field.get(value))
                                }
                            }
                        }

                    override fun deriveSum(metadata: SumTypeclassMetadata): Any =
                        object : Show<Any?> {
                            override fun show(value: Any?): String = "sum"
                        }
                }
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(value: Int): String = value.toString()
            }

            data class Payload(val raw: String)

            @Derive(Show::class)
            data class Big(
                val a1: Int,
                val a2: Int,
                val a3: Int,
                val a4: Int,
                val a5: Int,
                val a6: Int,
                val a7: Int,
                val a8: Int,
                val a9: Int,
                val a10: Int,
                val a11: Int,
                val a12: Int,
                val missing: Payload,
            )

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(Big(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, Payload("x")))) // E:TC_NO_CONTEXT_ARGUMENT missing Show<Payload>
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("missing", "big"),
            expectedDiagnostics = listOf(expectedErrorContaining("missing", "show", "big")),
        )
    }

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

    @Test fun detectsMutualRecursionAcrossTypeclasses() {
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
                println(use<Int>()) // E:TC_NO_CONTEXT_ARGUMENT Foo<Int> depends on Bar<Int> which depends on Foo<Int>
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("no context argument", "foo"),
            expectedDiagnostics = listOf(expectedNoContextArgument("foo")),
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

    @Test fun resolvesContextualOverloadsBetweenSingleAndVarargAlternatives() {
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

            class Logger {
                context(show: Show<Int>)
                fun log(value: Int): String = "one:" + show.show(value)

                context(show: Show<Int>)
                fun log(vararg values: Int): String =
                    "many:" + values.joinToString("|") { value -> show.show(value) }
            }

            fun main() {
                val logger = Logger()
                println(logger.log(1))
                println(logger.log(1, 2))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                one:int:1
                many:int:1|int:2
                """.trimIndent(),
        )
    }

    @Test fun prefersExactContravariantNothingInstanceOverBroaderCandidates() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            @Typeclass
            interface Show<in A> {
                fun label(): String
            }

            @Instance
            object NothingShow : Show<Nothing> {
                override fun label(): String = "nothing"
            }

            @Instance
            object AnyShow : Show<Any> {
                override fun label(): String = "any"
            }

            context(_: Show<Nothing>)
            fun which(): String = summon<Show<Nothing>>().label()

            fun main() {
                println(which())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "nothing",
        )
    }

    @Test fun broaderContravariantCandidatesForNothingWithoutExactMatchDoNotResolve() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            @Typeclass
            interface Show<in A> {
                fun label(): String
            }

            @Instance
            object AnyShow : Show<Any> {
                override fun label(): String = "any"
            }

            @Instance
            object StringShow : Show<String> {
                override fun label(): String = "string"
            }

            context(_: Show<Nothing>)
            fun which(): String = summon<Show<Nothing>>().label()

            fun main() {
                println(which()) // multiple broader contravariant instances match Show<Nothing>
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("show", "nothing"),
            expectedDiagnostics = listOf(expectedNoContextArgument("show")),
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

    @Test fun distinguishesValueClassesFromUnderlyingTypes() {
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

    @Test fun reportsConflictingBindingsFromLocalContexts() {
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
                        println(choose()) // A is inferred as both Int and String
                    }
                }
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("conflicting", "binding"),
            expectedDiagnostics =
                listOf(
                    ExpectedDiagnostic.Error(
                        messageRegex = "(?i)(conflicting|cannot infer|inferred as|type mismatch)",
                    ),
                ),
        )
    }

    @Test fun reportsAmbiguityBetweenNullableSpecificAndGenericRules() {
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
                println(which<List<Int?>>()) // E:TC_NO_CONTEXT_ARGUMENT decide whether specific or generic nullable rule should win
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("no context argument", "eq"),
            expectedDiagnostics = listOf(expectedAmbiguousOrNoContext("eq")),
        )
    }

    @Test fun treatsTypeAliasesAsTransparentForResolution() {
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

    @Test fun treatsNestedTypeAliasesAsTransparentForResolution() {
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

    @Test fun preservesUseSiteVarianceInTypeclassGoals() {
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

    @Test fun combinesExplicitRegularContextWithImplicitTypeclassResolution() {
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

    @Test fun distinguishesShadowedTypeParametersAcrossNestedGenericScopes() {
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

    @Test fun capturesDefinitionSiteEvidenceForReturnedLambdasObjectsAndBoundReferences() {
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

    @Test
    fun overloadedInstanceRulesWithSameNameAndProvidedTypeDoNotCollapseById() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Alpha<A>

            @Typeclass
            interface Beta<A>

            @Typeclass
            interface Show<A> {
                fun label(): String
            }

            @Instance
            object AlphaInt : Alpha<Int>

            @Instance
            context(_: Beta<Int>)
            fun witness(): Show<Int> =
                object : Show<Int> {
                    override fun label(): String = "beta"
                }

            @Instance
            context(_: Alpha<Int>)
            fun witness(): Show<Int> =
                object : Show<Int> {
                    override fun label(): String = "alpha"
                }

            context(show: Show<Int>)
            fun render(): String = show.label()

            context(_: Alpha<Int>)
            fun useAlphaOnly(): String = render()

            fun main() {
                println(useAlphaOnly())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "alpha",
        )
    }

    @Test
    fun overloadedInstanceRulesWithSameNameAndProvidedTypeStillReportAmbiguity() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Alpha<A>

            @Typeclass
            interface Beta<A>

            @Typeclass
            interface Show<A> {
                fun label(): String
            }

            @Instance
            object AlphaInt : Alpha<Int>

            @Instance
            object BetaInt : Beta<Int>

            @Instance
            context(_: Beta<Int>)
            fun witness(): Show<Int> =
                object : Show<Int> {
                    override fun label(): String = "beta"
                }

            @Instance
            context(_: Alpha<Int>)
            fun witness(): Show<Int> =
                object : Show<Int> {
                    override fun label(): String = "alpha"
                }

            context(show: Show<Int>)
            fun render(): String = show.label()

            context(_: Alpha<Int>, _: Beta<Int>)
            fun useBoth(): String = render() // E:TC_NO_CONTEXT_ARGUMENT ambiguous Show<Int> resolution
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("ambiguous", "show"),
            expectedDiagnostics = listOf(expectedAmbiguousOrNoContext("show")),
        )
    }

}
