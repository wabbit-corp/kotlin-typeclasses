package one.wabbit.typeclass.plugin

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.Ignore
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.net.URI

class CompilerIntegrationTest {
    @Test
    fun compilesLocalInferenceThroughTopLevelInstanceFunction() {
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

    @Test
    fun compilesAssociatedCompanionInstanceFunction() {
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

    @Test
    fun resolvesCompanionInstancesForMultiParameterTypeclasses() {
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

    @Test
    fun resolvesMemberContextAccessorsWithDispatchReceiver() {
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

    @Test
    fun compilesAndRunsContextualExtensionThroughTopLevelObjectInstance() {
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

    @Test
    fun compilesAndRunsContextualExtensionThroughAssociatedCompanionInstance() {
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

    @Test
    fun compilesGenericContextualCompanionFactory() {
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

    @Test
    fun compilesAssociatedCompanionInvokeWithoutExplicitContext() {
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

    @Test
    fun supportsExplicitTypeArgumentsOnContextualCalls() {
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

    @Test
    fun infersTypeArgumentsFromOuterContextInsideNestedLambda() {
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

    @Test
    fun rewritesContextualCallsInsideSafeCallLet() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            class ItemMeta
            class ItemStack(val itemMeta: ItemMeta? = ItemMeta())

            @Typeclass
            interface ItemComponentType<A>

            data class Curse(val soulbound: Boolean) {
                companion object {
                    @Instance
                    val itemComponentType =
                        object : ItemComponentType<Curse> {}
                }
            }

            class Items {
                context(type: ItemComponentType<Type>)
                fun <Type> getComponent(item: ItemStack): Type? =
                    item.itemMeta?.let { getComponent(it) }

                context(type: ItemComponentType<Type>)
                fun <Type> getComponent(item: ItemMeta): Type? = null
            }

            fun main() {
                with(Curse.itemComponentType) {
                    println(Items().getComponent<Curse>(ItemStack()) == null)
                }
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "true",
        )
    }

    @Test
    fun rewritesContextualCallsInsideHigherOrderArguments() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            class ItemMeta

            @Typeclass
            interface ItemComponentType<A>

            data class Curse(val soulbound: Boolean) {
                companion object {
                    @Instance
                    val itemComponentType =
                        object : ItemComponentType<Curse> {}
                }
            }

            class Items {
                context(type: ItemComponentType<Type>)
                fun <Type> getComponent(item: ItemMeta): Type? = null

                context(type: ItemComponentType<Type>)
                fun <Type> updateComponent(item: ItemMeta, f: (Type?) -> Type): Type {
                    val value = f(getComponent(item))
                    return value
                }
            }

            fun main() {
                with(Curse.itemComponentType) {
                    println(Items().updateComponent(ItemMeta()) { Curse(true) }.soulbound)
                }
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "true",
        )
    }

    @Test
    fun resolvesAssociatedContextualOverloadInsidePredicateLambda() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            class ItemMeta
            class ItemStack(val itemMeta: ItemMeta? = ItemMeta())

            class DropList(
                private val items: MutableList<ItemStack>,
            ) {
                fun removeIf(predicate: (ItemStack) -> Boolean): Boolean = items.removeIf(predicate)
            }

            @Typeclass
            interface ItemComponentType<A>

            data class Curse(val soulbound: Boolean) {
                companion object {
                    @Instance
                    val itemComponentType =
                        object : ItemComponentType<Curse> {}
                }
            }

            class Items {
                context(type: ItemComponentType<Type>)
                fun <Type> hasComponent(item: ItemStack): Boolean =
                    item.itemMeta?.let { hasComponent<Type>(it) } ?: false

                context(type: ItemComponentType<Type>)
                fun <Type> hasComponent(item: ItemMeta): Boolean = true
            }

            fun main() {
                val drops = DropList(mutableListOf(ItemStack()))
                println(drops.removeIf { item -> Items().hasComponent<Curse>(item) })
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "true",
        )
    }

    @Test
    fun supportsExplicitTypeArgumentsOnNestedOverloadedSelfCall() {
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

    @Test
    fun usesUserDefinedContextAccessorWithoutSpecialCasingSummon() {
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

    @Test
    fun resolvesAnonymousLocalTypeclassContexts() {
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

    @Test
    fun doesNotImplicitlyResolveNonTypeclassContexts() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance

            interface Eq<A> {
                fun eq(left: A, right: A): Boolean
            }

            @Instance
            object IntEq : Eq<Int> {
                override fun eq(left: Int, right: Int): Boolean = left == right
            }

            context(_: Eq<A>)
            fun <A> same(a: A): Boolean = true

            fun main() {
                println(same(1))
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages =
                listOf(
                    "No context argument",
                    "same(1)",
                ),
        )
    }

    @Test
    fun reportsMissingCompanionInstanceAnnotationWithoutCrashing() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface ItemComponentType<A>

            data class SomeItemComponent<C>(val value: C, val type: ItemComponentType<C>) {
                companion object {
                    context(type: ItemComponentType<C>)
                    operator fun <C> invoke(value: C): SomeItemComponent<C> = SomeItemComponent(value, type)
                }
            }

            data class Curse(val soulbound: Boolean) {
                companion object {
                    val itemComponentType =
                        object : ItemComponentType<Curse> {}
                }
            }

            fun main() {
                println(SomeItemComponent(Curse(true)))
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages =
                listOf(
                    "No context argument",
                    "SomeItemComponent",
                ),
        )
    }

    @Test
    fun infersTypeArgumentsFromLocalTypeclassContext() {
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

    @Test
    fun preservesNullabilityWhenResolvingGenericInstances() {
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

    @Test
    fun resolvesTopLevelInstanceProperty() {
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

    @Test
    fun rewritesNamedAndDefaultArgumentsOnContextualCalls() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            class ItemMeta

            @Typeclass
            interface Tag<A> {
                fun value(): String
            }

            data class Curse(val soulbound: Boolean)

            @Instance
            object CurseTag : Tag<Curse> {
                override fun value(): String = "curse"
            }

            class Items {
                context(tag: Tag<T>)
                fun <T> update(item: ItemMeta, amount: Int = 1, suffix: String = tag.value()): String =
                    suffix + ":" + amount

                context(_: Tag<T>)
                fun <T> call(item: ItemMeta): String =
                    update(amount = 2, item = item)
            }

            fun main() {
                with(CurseTag) {
                    println(Items().call<Curse>(ItemMeta()))
                }
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "curse:2",
        )
    }

    @Test
    fun compilesAndRunsDerivedInstances() {
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

    @Test
    fun reportsAmbiguityBetweenSpecificAndGenericInstances() {
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
                println(render<Box<Int>>()) // ERROR generic and specific instances both match
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("ambiguous", "box"),
        )
    }

    @Test
    fun reportsAmbiguousPrerequisiteInsteadOfMissingResult() {
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
            object IntShowTwo : Show<Int> { // ERROR: ambiguous instance declaration
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
                println(render(Box(1))) // ERROR prerequisite Show<Int> is ambiguous
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("ambiguous", "int"),
        )
    }

    @Test
    fun rewritesContextualExtensionsWithDefaultsAndOverloads() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Tag<A> {
                fun value(): String
            }

            data class Curse(val soulbound: Boolean)

            @Instance
            object CurseTag : Tag<Curse> {
                override fun value(): String = "curse"
            }

            class Items {
                context(tag: Tag<T>)
                fun <T> T.renderSelf(prefix: String = tag.value()): String = prefix + ":" + this.toString()

                context(tag: Tag<T>)
                fun <T> render(value: T, prefix: String = tag.value()): String = value.renderSelf(prefix)

                context(_: Tag<T>)
                fun <T> call(value: T): String = render(value = value)
            }

            fun main() {
                with(CurseTag) {
                    println(Items().call(Curse(true)))
                }
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "curse:Curse(soulbound=true)",
        )
    }

    @Test
    fun reportsMissingLeafInstanceForLargeDerivedProduct() {
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
                println(render(Big(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, Payload("x")))) // ERROR missing Show<Payload>
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("missing", "big"),
        )
    }

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun resolutionIsStableAcrossDeclarationOrder() {
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

    // NEW
    @Ignore("NEW: review before enabling")
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

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun localEvidenceShadowsTopLevelInstance() {
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

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun resolvesAssociatedCompanionObjectInstances() {
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
    fun rejectsStarProjectedGoalsCleanly() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A>

            context(_: Show<A>)
            fun <A> render(): String = "ok"

            fun main() {
                println(render<List<*>>()) // ERROR star-projected goals should not crash the plugin
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("unsupported", "type argument"),
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

    // NEW
    @Ignore("NEW: review before enabling")
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

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun derivesSealedInterfaces() {
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

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun discoversInstancesInsideNamespaceObjects() {
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

            object Instances {
                @Instance
                object IntShow : Show<Int> {
                    override fun show(): String = "namespace-object"
                }
            }

            context(_: Show<A>)
            fun <A> render(): String = summon<Show<A>>().show()

            fun main() {
                println(render<Int>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "namespace-object",
        )
    }

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun reportsDuplicateInstancesAcrossCompanionAndTopLevelScopes() {
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

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun resolvesAssociatedInstancesThroughTypeArguments() {
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
    fun rejectsNonCompanionMemberInstanceObjectsAtDeclarationSite() {
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
                object IntShow : Show<Int> { // ERROR non-companion member @Instance objects should be rejected
                    override fun show(value: Int): String = "int:${'$'}value"
                }
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("companion"),
        )
    }

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun rejectsExtensionInstanceFunctionsAtDeclarationSite() {
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

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun rejectsInstanceFunctionsWithRegularParametersAtDeclarationSite() {
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

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun rewritesVarargCallsWithTrailingLambdas() {
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

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun resolvesInlineReifiedHelpersAroundSummon() {
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

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun resolvesAnonymousObjectsAndFunctionsThatCaptureLocalEvidence() {
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

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun rewritesContextualCallsInContextClassPropertyInitializers() {
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

            context(show: Show<Int>)
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

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun exposesMultipleTypeclassInstancesFromOneObject() {
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

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun mixesPreservedAndSynthesizedTypeclassArgumentsOnOneCall() {
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

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun resolvesContextualCallsThroughInterfaceOverrides() {
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

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun doesNotLeakPrivateCompanionInstancesAcrossFiles() {
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

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun usesExtensionReceiverAsTheOnlyAvailableEvidence() {
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

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun keepsIntegerLiteralInferenceStableAcrossFirAndIr() {
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

    // NEW
    @Ignore("NEW: review before enabling")
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

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun discoversInstancesInheritedThroughHelperBaseClasses() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            abstract class IntShowBase : Show<Int>

            @Instance
            object IntShow : IntShowBase() {
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

    // NEW
    @Ignore("NEW: review before enabling")
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

    // NEW
    @Ignore("NEW: review before enabling")
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

            context(show: Show<Int>)
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

    // NEW
    @Ignore("NEW: review before enabling")
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

    // NEW
    @Ignore("NEW: review before enabling")
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

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun remainsStableWhenRecursiveCandidatesAppearBeforeOrAfterViableOnes() {
        val recursiveFirst =
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
            object IntShow : Show<Int> {
                override fun label(): String = "int"
            }

            @Instance
            context(_: Show<Box<A>>)
            fun <A> recursiveBoxShow(): Show<Box<A>> =
                object : Show<Box<A>> {
                    override fun label(): String = "recursive"
                }

            @Instance
            context(_: Show<A>)
            fun <A> boxShow(): Show<Box<A>> =
                object : Show<Box<A>> {
                    override fun label(): String = "box"
                }

            context(_: Show<A>)
            fun <A> which(): String = summon<Show<A>>().label()

            fun main() {
                println(which<Box<Int>>())
            }
            """.trimIndent()

        val viableFirst =
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
            object IntShow : Show<Int> {
                override fun label(): String = "int"
            }

            @Instance
            context(_: Show<A>)
            fun <A> boxShow(): Show<Box<A>> =
                object : Show<Box<A>> {
                    override fun label(): String = "box"
                }

            @Instance
            context(_: Show<Box<A>>)
            fun <A> recursiveBoxShow(): Show<Box<A>> =
                object : Show<Box<A>> {
                    override fun label(): String = "recursive"
                }

            context(_: Show<A>)
            fun <A> which(): String = summon<Show<A>>().label()

            fun main() {
                println(which<Box<Int>>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = recursiveFirst,
            expectedStdout = "box",
        )
        assertCompilesAndRuns(
            source = viableFirst,
            expectedStdout = "box",
        )
    }

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun reportsOverlapBetweenLocalEvidenceAndDerivedEvidence() {
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
                    println(which<String?>()) // ERROR local and derived evidence both satisfy Eq<String?>
                }
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

    // NEW
    @Ignore("NEW: review before enabling")
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

    // NEW
    @Ignore("NEW: review before enabling")
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

    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun supportsTypeclassMethodsWithAdditionalContext() {
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
                fun debug(value: A): String = "debug:" + show.show(value)
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(value: Int): String = "int:${'$'}value"
            }

            @Instance
            object IntDebug : Debug<Int>

            fun main() {
                println(summon<Debug<Int>>().debug(1))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "debug:int:1",
        )
    }

    // NEW
    @Ignore("NEW: review before enabling")
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

    // NEW
    @Ignore("NEW: review before enabling")
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

    private fun assertCompiles(source: String) {
        compileSource(source)
    }

    private fun assertDoesNotCompile(
        source: String,
        expectedMessages: List<String>,
    ) {
        assertDoesNotCompile(
            sources = mapOf("Sample.kt" to source),
            expectedMessages = expectedMessages,
        )
    }

    private fun assertDoesNotCompile(
        sources: Map<String, String>,
        expectedMessages: List<String>,
    ) {
        val result = compileSourceInternal(sources)
        assertEquals(
            ExitCode.COMPILATION_ERROR,
            result.exitCode,
            result.stdout,
        )
        val lowercaseOutput = result.stdout.lowercase()
        expectedMessages.forEach { expectedMessage ->
            assertTrue(lowercaseOutput.contains(expectedMessage.lowercase()), result.stdout)
        }
    }

    private fun assertCompilesAndRuns(
        source: String,
        expectedStdout: String,
        mainClass: String = "demo.SampleKt",
    ) {
        val artifacts = compileSource(source)
        val javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toAbsolutePath().toString()
        val process =
            ProcessBuilder(
                javaExecutable,
                "-cp",
                listOf(artifacts.outputDir, artifacts.runtimeClasspathEntry, artifacts.stdlibJar)
                    .joinToString(separator = java.io.File.pathSeparator) { path -> path.toAbsolutePath().toString() },
                mainClass,
            ).redirectErrorStream(true)
                .start()
        val stdout = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        val exitCode = process.waitFor()
        assertEquals(0, exitCode, stdout)
        assertEquals(expectedStdout.trim(), stdout.trim())
    }

    private fun compileSource(source: String): CompilationArtifacts {
        val result = compileSourceInternal(mapOf("Sample.kt" to source))
        assertEquals(
            ExitCode.OK,
            result.exitCode,
            result.stdout,
        )
        return result.artifacts
    }

    private fun compileSourceInternal(source: String): CompilationResult {
        return compileSourceInternal(mapOf("Sample.kt" to source))
    }

    private fun compileSourceInternal(sources: Map<String, String>): CompilationResult {
        val workingDir = createTempDirectory(prefix = "typeclass-compile-")
        val outputDir = workingDir.resolve("out")
        outputDir.createDirectories()
        val sourceFiles =
            sources.map { (relativePath, contents) ->
                workingDir.resolve(relativePath).also { sourceFile ->
                    sourceFile.parent?.createDirectories()
                    sourceFile.writeText(contents)
                }
            }

        val pluginProjectRoot = locateProjectRoot()
        val runtimeProjectRoot = pluginProjectRoot.parent.resolve("kotlin-typeclasses")
        val pluginJar = locateBuiltJar(pluginProjectRoot.resolve("build/libs"), "kotlin-typeclasses-plugin")
        val runtimeClasspathEntry = locateRuntimeClasspathEntry(runtimeProjectRoot)
        val stdlibJar = locateStdlibJar()

        assertTrue(pluginJar.toFile().isFile, "Plugin jar is missing at $pluginJar")
        assertTrue(runtimeClasspathEntry.toFile().exists(), "Runtime classpath entry is missing at $runtimeClasspathEntry")
        assertTrue(stdlibJar.toFile().isFile, "Kotlin stdlib jar is missing at $stdlibJar")

        val stdout = ByteArrayOutputStream()
        val exitCode =
            K2JVMCompiler().exec(
                PrintStream(stdout),
                "-Xcontext-parameters",
                "-no-stdlib",
                "-no-reflect",
                "-Xplugin=${pluginJar.toAbsolutePath()}",
                "-classpath",
                listOf(runtimeClasspathEntry, stdlibJar).joinToString(separator = java.io.File.pathSeparator) {
                    it.toAbsolutePath().toString()
                },
                "-d",
                outputDir.toAbsolutePath().toString(),
                *sourceFiles.map { it.toAbsolutePath().toString() }.toTypedArray(),
            )

        return CompilationResult(
            exitCode = exitCode,
            stdout = stdout.toString(Charsets.UTF_8),
            artifacts =
                CompilationArtifacts(
                    outputDir = outputDir,
                    runtimeClasspathEntry = runtimeClasspathEntry,
                    stdlibJar = stdlibJar,
                ),
        )
    }

    private fun locateProjectRoot(): Path {
        val candidates =
            buildList {
                add(Path.of(System.getProperty("user.dir")).toAbsolutePath())
                val classResourcePath = javaClass.name.replace('.', '/') + ".class"
                val classResource = requireNotNull(javaClass.classLoader.getResource(classResourcePath)) {
                    "Could not locate $classResourcePath"
                }
                if (classResource.protocol == "file") {
                    val classPath = Path.of(classResource.toURI()).toAbsolutePath()
                    add(if (classPath.toFile().isFile) classPath.parent else classPath)
                }
            }

        return candidates
            .asSequence()
            .flatMap { start -> generateSequence(start) { current -> current.parent } }
            .firstOrNull { candidate ->
                candidate.resolve("build.gradle.kts").toFile().isFile &&
                    candidate.resolve("settings.gradle.kts").toFile().isFile
            }
            ?: error("Could not locate the kotlin-typeclasses-plugin project root from $candidates")
    }

    private fun locateBuiltJar(
        libsDirectory: Path,
        artifactPrefix: String,
    ): Path {
        val jar =
            libsDirectory.toFile().listFiles()
                ?.asSequence()
                ?.filter { file ->
                    file.isFile &&
                        file.extension == "jar" &&
                        file.name.startsWith("$artifactPrefix-") &&
                        !file.name.endsWith("-sources.jar") &&
                        !file.name.endsWith("-javadoc.jar")
                }
                ?.maxByOrNull { file -> file.lastModified() }
                ?.toPath()
        return requireNotNull(jar) {
            "Could not locate a built $artifactPrefix jar in $libsDirectory"
        }
    }

    private fun locateBuiltJvmJar(
        libsDirectory: Path,
        artifactPrefix: String,
    ): Path {
        val jvmJar =
            libsDirectory.toFile().listFiles()
                ?.asSequence()
                ?.filter { file ->
                    file.isFile &&
                        file.extension == "jar" &&
                        file.name.startsWith("${artifactPrefix}-jvm-") &&
                        !file.name.endsWith("-sources.jar") &&
                        !file.name.endsWith("-javadoc.jar")
                }
                ?.maxByOrNull { file -> file.lastModified() }
                ?.toPath()
        return jvmJar ?: locateBuiltJar(libsDirectory, artifactPrefix)
    }

    private fun locateRuntimeClasspathEntry(runtimeProjectRoot: Path): Path {
        val runtimeJar = locateBuiltJvmJar(runtimeProjectRoot.resolve("build/libs"), "kotlin-typeclasses")
        if (runtimeJarContainsTypeclassApi(runtimeJar)) {
            return runtimeJar
        }

        val compiledClasses = runtimeProjectRoot.resolve("build/classes/kotlin/jvm/main")
        require(compiledClasses.resolve("one/wabbit/typeclass/Typeclass.class").toFile().isFile) {
            "Could not locate compiled runtime classes in $compiledClasses"
        }
        return compiledClasses
    }

    private fun runtimeJarContainsTypeclassApi(runtimeJar: Path): Boolean =
        runCatching {
            java.util.jar.JarFile(runtimeJar.toFile()).use { jar ->
                jar.getEntry("one/wabbit/typeclass/Typeclass.class") != null
            }
        }.getOrDefault(false)

    private fun locateStdlibJar(): Path {
        val resource = requireNotNull(Thread.currentThread().contextClassLoader.getResource("kotlin/Unit.class")) {
            "Could not locate kotlin/Unit.class on the test classpath"
        }
        val externalForm = resource.toExternalForm()
        if (externalForm.startsWith("jar:file:")) {
            val jarUri = URI(externalForm.substringAfter("jar:").substringBefore("!"))
            return Path.of(jarUri)
        }
        val classpathEntry =
            System.getProperty("java.class.path")
                .split(java.io.File.pathSeparator)
                .firstOrNull { entry -> "kotlin-stdlib" in entry }
        return requireNotNull(classpathEntry) {
            "Could not locate kotlin-stdlib on the test classpath"
        }.let(Path::of)
    }
}

private data class CompilationArtifacts(
    val outputDir: Path,
    val runtimeClasspathEntry: Path,
    val stdlibJar: Path,
)

private data class CompilationResult(
    val exitCode: ExitCode,
    val stdout: String,
    val artifacts: CompilationArtifacts,
)
