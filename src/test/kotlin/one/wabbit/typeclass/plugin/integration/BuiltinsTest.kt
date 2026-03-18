package one.wabbit.typeclass.plugin.integration

import org.junit.Ignore
import kotlin.test.Test

class BuiltinsTest : IntegrationTestSupport() {
    // NEW
    @Ignore("NEW: review before enabling")
    @Test
    fun associatedOwnerLookupShouldRespectImportedInstanceVisibility() {
        val sources =
            mapOf(
                "shared/Api.kt" to
                    """
                    package shared

                    import one.wabbit.typeclass.Instance
                    import one.wabbit.typeclass.Typeclass
                    import one.wabbit.typeclass.summon

                    data class Box<A>(val value: A)

                    @Typeclass
                    interface Show<A> {
                        fun label(): String
                    }

                    @Instance
                    context(show: Show<A>)
                    fun <A> boxShow(): Show<Box<A>> =
                        object : Show<Box<A>> {
                            override fun label(): String = "box-" + show.label()
                        }

                    context(_: Show<A>)
                    fun <A> which(): String = summon<Show<A>>().label()
                    """.trimIndent(),
                "alpha/AlphaId.kt" to
                    """
                    package alpha

                    import one.wabbit.typeclass.Instance
                    import shared.Show

                    data class AlphaId(val value: Int) {
                        companion object {
                            @Instance
                            val show =
                                object : Show<AlphaId> {
                                    override fun label(): String = "alpha-id"
                                }
                        }
                    }
                    """.trimIndent(),
                "beta/Instances.kt" to
                    """
                    package beta

                    import alpha.AlphaId
                    import one.wabbit.typeclass.Instance
                    import shared.Box
                    import shared.Show

                    @Instance
                    object BetaBoxAlphaIdShow : Show<Box<AlphaId>> {
                        override fun label(): String = "beta-box"
                    }
                    """.trimIndent(),
                "demo/Main.kt" to
                    """
                    package demo

                    import alpha.AlphaId
                    import shared.Box
                    import shared.which

                    fun main() {
                        println(which<Box<AlphaId>>())
                    }
                    """.trimIndent(),
            )

        assertCompilesAndRuns(
            sources = sources,
            expectedStdout = "box-alpha-id",
            mainClass = "demo.MainKt",
        )
    }
}
