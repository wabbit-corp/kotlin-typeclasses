package one.wabbit.typeclass.plugin.integration.interop

import one.wabbit.typeclass.plugin.integration.CompilerHarnessPlugin
import one.wabbit.typeclass.plugin.integration.IntegrationTestSupport
import kotlin.test.Test

/**
 * Parcelize compatibility design tests.
 *
 * These focus on coexistence with the Parcelize compiler plugin.
 */
class ParcelizeInteropTest : IntegrationTestSupport() {
    private val parcelizePlugins = listOf(CompilerHarnessPlugin.Parcelize)

    @Test
    fun parcelizedDataClassesCanDeriveTypeclasses() {
        val source =
            """
            package demo

            import android.os.Parcelable
            import kotlinx.parcelize.Parcelize
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

            @Parcelize
            @Derive(Show::class)
            data class User(val id: Int) : Parcelable

            context(show: Show<User>)
            fun render(value: User): String = show.show(value)

            fun main() {
                println(render(User(1)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "User(id=1)",
            requiredPlugins = parcelizePlugins,
        )
    }

    @Test
    fun parcelizedClassesCanUseAssociatedCompanionInstances() {
        val source =
            """
            package demo

            import android.os.Parcelable
            import kotlinx.parcelize.Parcelize
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Parcelize
            data class User(val id: Int) : Parcelable {
                companion object {
                    @Instance
                    object UserShow : Show<User> {
                        override fun show(value: User): String = "user:${'$'}{value.id}"
                    }
                }
            }

            context(show: Show<User>)
            fun render(value: User): String = show.show(value)

            fun main() {
                println(render(User(1)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "user:1",
            requiredPlugins = parcelizePlugins,
        )
    }

    @Test
    fun parcelizedTypesCanStillParticipateInContextualCalls() {
        val source =
            """
            package demo

            import android.os.Parcelable
            import kotlinx.parcelize.Parcelize
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Parcelize
            data class User(val id: Int) : Parcelable

            @Instance
            object UserShow : Show<User> {
                override fun show(value: User): String = "user:${'$'}{value.id}"
            }

            context(show: Show<User>)
            fun render(value: User): String = show.show(value)

            fun main() {
                println(render(User(1)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "user:1",
            requiredPlugins = parcelizePlugins,
        )
    }
}
