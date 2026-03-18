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

    @Test fun rewritesContextualCallsInsideSafeCallLet() {
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

    @Test fun infersOuterContextInsideSafeCallLetWithoutExplicitTypeArguments() {
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

                context(type: ItemComponentType<Type>)
                fun <Type> use(item: ItemStack): Boolean = getComponent(item) == null
            }

            fun main() {
                with(Curse.itemComponentType) {
                    println(Items().use(ItemStack()))
                }
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "true",
        )
    }

    @Test fun infersOuterContextAcrossSameNameOverloadsInsideSafeCallLet() {
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
                    println(Items().getComponent(ItemStack()) == null)
                }
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "true",
        )
    }

    @Test fun infersOuterContextAcrossPlatformSafeCallLetInsideSameNameOverload() {
        val sources =
            mapOf(
                "demo/Items.kt" to
                    """
                    package demo

                    import java.util.concurrent.atomic.AtomicReference
                    import one.wabbit.typeclass.Typeclass

                    class ItemMeta

                    class ItemStack(meta: ItemMeta? = ItemMeta()) {
                        val itemMetaRef = AtomicReference(meta)
                    }

                    @Typeclass
                    interface ItemComponentType<A>

                    class Items {
                        context(type: ItemComponentType<Type>)
                        fun <Type> getComponent(item: ItemStack): Type? =
                            item.itemMetaRef.get()?.let { getComponent(it) }

                        context(type: ItemComponentType<Type>)
                        fun <Type> getComponent(item: ItemMeta): Type? = null
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
                                object : ItemComponentType<Curse> {}
                        }
                    }

                    fun main() {
                        with(Curse.itemComponentType) {
                            println(Items().getComponent(ItemStack()) == null)
                        }
                    }
                    """.trimIndent(),
            )

        assertCompilesAndRuns(
            sources = sources,
            expectedStdout = "true",
            mainClass = "demo.MainKt",
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

    @Test fun rewritesContextualCallsInsideHigherOrderArguments() {
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

    @Test fun resolvesAssociatedContextualOverloadInsidePredicateLambda() {
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

    @Test fun doesNotImplicitlyResolveNonTypeclassContexts() {
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

    @Test fun reportsMissingCompanionInstanceAnnotationWithoutCrashing() {
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

    @Test fun rewritesNamedAndDefaultArgumentsOnContextualCalls() {
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
                println(render<Box<Int>>()) // ERROR generic and specific instances both match
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("no context argument", "show"),
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

    @Test fun rewritesContextualExtensionsWithDefaultsAndOverloads() {
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
                println(render(Big(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, Payload("x")))) // ERROR missing Show<Payload>
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("missing", "big"),
        )
    }

}
