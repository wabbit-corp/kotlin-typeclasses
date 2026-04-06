// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.typeclass.plugin.integration

import org.junit.Ignore
import kotlin.test.Test

class CallRewriteSurfaceTest : IntegrationTestSupport() {
    @Test fun resolvesSuspendContextualFunctions() {
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

    @Test fun resolvesContextualCallsInsideSuspendLambdas() {
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

    @Test fun supportsBuilderInferenceAroundContextualCalls() {
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

    @Test fun supportsFunInterfacesAsTypeclassesAndLambdaInstances() {
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

    @Test fun resolvesLocalContextualFunctions() {
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

    @Test fun resolvesContextualCallsInsideLocalDelegatedProperties() {
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

    @Test fun rewritesContextualCallsInsideDefaultValueExpressions() {
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

    @Test fun rewritesContextualCallsInsideConstructorDelegation() {
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

    @Test fun rewritesContextualCallsInsideInterfaceDelegation() {
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

    @Test fun rewritesContextualCallsInClassPropertyInitializers() {
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

    @Test fun rewritesContextualCallsInsideSecondaryConstructorDelegation() {
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

    @Test
    fun rewritesGenericContextCallsInsideHigherOrderContextualLambdasAndNestedHelpers() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface ItemComponentType<Type> {
                val tag: String
            }

            data class SomeItemComponent<C>(val value: C, val type: ItemComponentType<C>) {
                inline fun <R> with(f: context(ItemComponentType<C>) (ItemComponentType<C>, C) -> R): R = f(type, type, value)
            }

            data class Mechanic(val id: Int)

            object MechanicType : ItemComponentType<Mechanic> {
                override val tag: String = "mechanic"
            }

            class ItemMeta {
                val values = mutableListOf<String>()
            }

            class ItemStack {
                val values = mutableListOf<String>()
            }

            class Items {
                context(type: ItemComponentType<Type>)
                fun <Type> putComponent(meta: ItemMeta, value: Type) {
                    meta.values += "${'$'}{type.tag}:${'$'}value"
                }

                context(type: ItemComponentType<Type>)
                fun <Type> putComponent(item: ItemStack, value: Type): ItemStack {
                    item.values += "${'$'}{type.tag}:${'$'}value"
                    return item
                }

                context(type: ItemComponentType<Type>)
                fun <Type> getOrPutComponent(meta: ItemMeta, f: () -> Type): Type {
                    val value = f()
                    putComponent(meta, value)
                    return value
                }

                context(type: ItemComponentType<Type>)
                fun <Type> getOrPutComponent(item: ItemStack, f: () -> Type): Type {
                    val value = f()
                    putComponent(item, value)
                    return value
                }

                context(type: ItemComponentType<Type>)
                fun <Type> updateComponent(meta: ItemMeta, f: (Type?) -> Type): Type {
                    val value = f(null)
                    putComponent(meta, value)
                    return value
                }

                context(type: ItemComponentType<Type>)
                fun <Type> updateComponent(item: ItemStack, f: (Type?) -> Type): Type {
                    val value = f(null)
                    putComponent(item, value)
                    return value
                }
            }

            fun main() {
                val items = Items()
                val first = ItemMeta()
                val second = ItemMeta()
                val stack = ItemStack()
                val mechanics: List<SomeItemComponent<*>> = listOf(SomeItemComponent(Mechanic(1), MechanicType))

                for (mechanic in mechanics) {
                    mechanic.with { _, value ->
                        items.putComponent(first, value)
                    }
                }

                with(MechanicType) {
                    items.getOrPutComponent(second) { Mechanic(2) }
                    items.updateComponent(second) { current -> current ?: Mechanic(3) }
                    items.getOrPutComponent(stack) { Mechanic(4) }
                    items.updateComponent(stack) { current -> current ?: Mechanic(5) }
                }

                println(first.values.joinToString())
                println(second.values.joinToString())
                println(stack.values.joinToString())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                mechanic:Mechanic(id=1)
                mechanic:Mechanic(id=2), mechanic:Mechanic(id=3)
                mechanic:Mechanic(id=4), mechanic:Mechanic(id=5)
                """.trimIndent(),
        )
    }

    @Test
    fun rewritesProjectedContextCallsThroughNestedGenericHelpersInsideHigherOrderLambda() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface ItemComponentType<Type> {
                val tag: String
            }

            data class SomeItemComponent<C>(val value: C, val type: ItemComponentType<C>) {
                inline fun <R> with(f: context(ItemComponentType<C>) (ItemComponentType<C>, C) -> R): R = f(type, type, value)
            }

            data class Mechanic(val id: Int)

            object MechanicType : ItemComponentType<Mechanic> {
                override val tag: String = "mechanic"
            }

            class ItemMeta {
                val values = mutableListOf<String>()
            }

            class ItemStack {
                val values = mutableListOf<String>()
            }

            class Items {
                context(type: ItemComponentType<Type>)
                fun <Type> putComponent(meta: ItemMeta, value: Type) {
                    meta.values += "${'$'}{type.tag}:${'$'}value"
                }

                context(type: ItemComponentType<Type>)
                fun <Type> putComponent(item: ItemStack, value: Type) {
                    item.values += "${'$'}{type.tag}:${'$'}value"
                }

                context(type: ItemComponentType<Type>)
                fun <Type> getOrPutComponent(meta: ItemMeta, f: () -> Type): Type {
                    val value = f()
                    putComponent(meta, value)
                    return value
                }

                context(type: ItemComponentType<Type>)
                fun <Type> getOrPutComponent(item: ItemStack, f: () -> Type): Type {
                    val value = f()
                    putComponent(item, value)
                    return value
                }

                context(type: ItemComponentType<Type>)
                fun <Type> updateComponent(meta: ItemMeta, f: (Type?) -> Type): Type {
                    val value = f(null)
                    putComponent(meta, value)
                    return value
                }

                context(type: ItemComponentType<Type>)
                fun <Type> updateComponent(item: ItemStack, f: (Type?) -> Type): Type {
                    val value = f(null)
                    putComponent(item, value)
                    return value
                }
            }

            fun main() {
                val items = Items()
                val meta = ItemMeta()
                val stack = ItemStack()
                val mechanics: List<SomeItemComponent<*>> = listOf(SomeItemComponent(Mechanic(1), MechanicType))

                for (mechanic in mechanics) {
                    mechanic.with { _, value ->
                        items.getOrPutComponent(meta) { value }
                        items.updateComponent(meta) { current -> current ?: value }
                        items.getOrPutComponent(stack) { value }
                        items.updateComponent(stack) { current -> current ?: value }
                    }
                }

                println(meta.values.joinToString())
                println(stack.values.joinToString())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                mechanic:Mechanic(id=1), mechanic:Mechanic(id=1)
                mechanic:Mechanic(id=1), mechanic:Mechanic(id=1)
                """.trimIndent(),
        )
    }

    @Ignore("Pending explicit context-argument preservation coverage")
    @Test
    fun explicitWrongTypeclassArgumentsAreNotReboundToDifferentGoals() {
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
            object NullableIntShow : Show<Int?> {
                override fun label(): String = "nullable"
            }

            context(show: Show<A>)
            fun <A> render(): String = show.label()

            fun main() {
                context(NullableIntShow) {
                    println(render<Int>()) // E:TC_NO_CONTEXT_ARGUMENT explicit type arguments must not let the wrong explicit context be rebound
                }
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedErrorContaining("show")),
        )
    }

    @Ignore("Pending type-argument and explicit-context preservation coverage")
    @Test
    fun preservesExplicitContextArgumentsAndTypeArgumentsThroughRewrites() {
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
            object NullableIntShow : Show<Int?> {
                override fun show(value: Int?): String = "nullable:${'$'}value"
            }

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                context(NullableIntShow) {
                    println(render<Int?>(1))
                }
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "nullable:1",
        )
    }

    @Ignore("Blocked by broader explicit context-argument frontend support; keep as a focused substitution regression once that path is active")
    @Test
    fun explicitContextArgumentsRespectGenericSupertypeSubstitution() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            data class Box<A>(val value: A)

            object IntShow : Show<Int> {
                override fun show(value: Int): String = "int:${'$'}value"
            }

            open class GenericBoxShow<T>(
                private val elementShow: Show<T>,
            ) : Show<Box<T>> {
                override fun show(value: Box<T>): String = "box:${'$'}{elementShow.show(value.value)}"
            }

            object IntBoxShow : GenericBoxShow<Int>(IntShow)

            context(show: Show<Box<Int>>)
            fun render(value: Box<Int>): String = show.show(value)

            fun main() {
                println(render(IntBoxShow, Box(1)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "box:int:1",
        )
    }

}
