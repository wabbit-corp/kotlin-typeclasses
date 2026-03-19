package one.wabbit.typeclass.plugin.integration

import kotlin.test.Test

class DerivationLawTest : IntegrationTestSupport() {
    @Test
    fun derivesEqForProductsSemantically() {
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
            interface Eq<A> {
                fun eq(left: A, right: A): Boolean

                companion object : TypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                        object : Eq<Any?> {
                            override fun eq(left: Any?, right: Any?): Boolean {
                                if (left == null || right == null) {
                                    return left == right
                                }
                                return metadata.fields.all { field ->
                                    val fieldEq = field.instance as Eq<Any?>
                                    fieldEq.eq(field.get(left), field.get(right))
                                }
                            }
                        }

                    override fun deriveSum(metadata: SumTypeclassMetadata): Any =
                        object : Eq<Any?> {
                            override fun eq(left: Any?, right: Any?): Boolean {
                                if (left == null || right == null) {
                                    return left == right
                                }
                                val leftCase = metadata.cases.single { candidate -> candidate.matches(left) }
                                val rightCase = metadata.cases.single { candidate -> candidate.matches(right) }
                                if (leftCase.name != rightCase.name) {
                                    return false
                                }
                                val caseEq = leftCase.instance as Eq<Any?>
                                return caseEq.eq(left, right)
                            }
                        }
                }
            }

            @Instance
            object IntEq : Eq<Int> {
                override fun eq(left: Int, right: Int): Boolean = left == right
            }

            @Instance
            object StringEq : Eq<String> {
                override fun eq(left: String, right: String): Boolean = left == right
            }

            @Derive(Eq::class)
            data class Person(val name: String, val age: Int)

            context(eq: Eq<A>)
            fun <A> same(left: A, right: A): Boolean = eq.eq(left, right)

            fun main() {
                println(same(Person("Ada", 1), Person("Ada", 1)))
                println(same(Person("Ada", 1), Person("Ada", 2)))
                println(same(Person("Ada", 1), Person("Bob", 1)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                true
                false
                false
                """.trimIndent(),
        )
    }

    @Test
    fun derivesEqForSumsSemantically() {
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
            interface Eq<A> {
                fun eq(left: A, right: A): Boolean

                companion object : TypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                        object : Eq<Any?> {
                            override fun eq(left: Any?, right: Any?): Boolean {
                                if (left == null || right == null) {
                                    return left == right
                                }
                                return metadata.fields.all { field ->
                                    val fieldEq = field.instance as Eq<Any?>
                                    fieldEq.eq(field.get(left), field.get(right))
                                }
                            }
                        }

                    override fun deriveSum(metadata: SumTypeclassMetadata): Any =
                        object : Eq<Any?> {
                            override fun eq(left: Any?, right: Any?): Boolean {
                                if (left == null || right == null) {
                                    return left == right
                                }
                                val leftCase = metadata.cases.single { candidate -> candidate.matches(left) }
                                val rightCase = metadata.cases.single { candidate -> candidate.matches(right) }
                                if (leftCase.name != rightCase.name) {
                                    return false
                                }
                                val caseEq = leftCase.instance as Eq<Any?>
                                return caseEq.eq(left, right)
                            }
                        }
                }
            }

            @Instance
            object IntEq : Eq<Int> {
                override fun eq(left: Int, right: Int): Boolean = left == right
            }

            @Instance
            object StringEq : Eq<String> {
                override fun eq(left: String, right: String): Boolean = left == right
            }

            @Derive(Eq::class)
            sealed interface Token

            @Derive(Eq::class)
            data class Word(val value: String) : Token

            @Derive(Eq::class)
            data class Number(val value: Int) : Token

            @Derive(Eq::class)
            object End : Token

            context(eq: Eq<A>)
            fun <A> same(left: A, right: A): Boolean = eq.eq(left, right)

            fun main() {
                println(same<Token>(Word("a"), Word("a")))
                println(same<Token>(Word("a"), Word("b")))
                println(same<Token>(Word("a"), Number(1)))
                println(same<Token>(End, End))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                true
                false
                false
                true
                """.trimIndent(),
        )
    }
}
