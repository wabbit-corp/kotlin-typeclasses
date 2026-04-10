// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.typeclass.plugin.integration.interop

import kotlin.test.Test
import one.wabbit.typeclass.plugin.integration.CompilerHarnessPlugin
import one.wabbit.typeclass.plugin.integration.IntegrationTestSupport

class AllOpenNoArgInteropTest : IntegrationTestSupport() {
    @Test
    fun allOpenClassesCanStillProvideAssociatedCompanionInstances() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.Instance

            annotation class OpenForFramework

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @OpenForFramework
            class Box(val value: Int) {
                companion object {
                    @Instance
                    object BoxShow : Show<Box> {
                        override fun show(value: Box): String = "box:${'$'}{value.value}"
                    }
                }
            }

            class FancyBox() : Box(1)

            context(show: Show<Box>)
            fun render(value: Box): String = show.show(value)

            fun main() {
                println(render(FancyBox()))
            }
            """
                .trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "box:1",
            requiredPlugins =
                listOf(CompilerHarnessPlugin.AllOpen(annotations = listOf("demo.OpenForFramework"))),
        )
    }

    @Test
    fun noArgEntitiesCanStillDeriveTypeclasses() {
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

            annotation class Entity

            @Typeclass
            interface Show<A> {
                fun show(value: A): String

                companion object : TypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                        object : Show<Any?> {
                            override fun show(value: Any?): String {
                                require(value != null)
                                return metadata.fields.joinToString(
                                    prefix = metadata.typeName.substringAfterLast('.') + "(",
                                    postfix = ")",
                                ) { field ->
                                    val fieldShow = field.instance as Show<Any?>
                                    "${'$'}{field.name}=" + fieldShow.show(field.get(value))
                                }
                            }
                        }

                    override fun deriveSum(metadata: SumTypeclassMetadata): Any =
                        object : Show<Any?> {
                            override fun show(value: Any?): String {
                                require(value != null)
                                val matchingCase = metadata.cases.single { it.matches(value) }
                                return (matchingCase.instance as Show<Any?>).show(value)
                            }
                        }
                }
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(value: Int): String = value.toString()
            }

            @Entity
            @Derive(Show::class)
            class User(val id: Int)

            context(show: Show<User>)
            fun render(value: User): String = show.show(value)

            fun main() {
                val reflected = User::class.java.getDeclaredConstructor().newInstance()
                println(render(reflected))
            }
            """
                .trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "User(id=0)",
            requiredPlugins =
                listOf(CompilerHarnessPlugin.NoArg(annotations = listOf("demo.Entity"))),
        )
    }

    @Test
    fun allOpenAndNoArgTransformedClassesStillWorkInContextualCalls() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            annotation class OpenForFramework
            annotation class Entity

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @OpenForFramework
            @Entity
            class User(val id: Int)

            class FancyUser() : User(7)

            @Instance
            object UserShow : Show<User> {
                override fun show(value: User): String = "user:${'$'}{value.id}"
            }

            context(show: Show<User>)
            fun render(value: User): String = show.show(value)

            fun main() {
                val reflected = User::class.java.getDeclaredConstructor().newInstance()
                println(render(reflected))
                println(render(FancyUser()))
            }
            """
                .trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                user:0
                user:7
                """
                    .trimIndent(),
            requiredPlugins =
                listOf(
                    CompilerHarnessPlugin.AllOpen(annotations = listOf("demo.OpenForFramework")),
                    CompilerHarnessPlugin.NoArg(annotations = listOf("demo.Entity")),
                ),
        )
    }
}
