package one.wabbit.typeclass.plugin.integration

import kotlin.test.Test

class ContextualGenericCallCrashTest : IntegrationTestSupport() {
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
}
