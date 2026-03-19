package one.wabbit.typeclass.plugin.integration

import kotlin.test.Test

class DerivationLawTest : IntegrationTestSupport() {
    @Test
    fun derivesProductMonoidsSemantically() {
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
            import one.wabbit.typeclass.summon

            @Typeclass
            interface Semigroup<A> {
                fun combine(left: A, right: A): A
            }

            @Typeclass
            interface Monoid<A> : Semigroup<A> {
                fun empty(): A

                companion object : TypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any {
                        val constructor =
                            Class.forName(metadata.typeName)
                                .declaredConstructors
                                .single { candidate -> candidate.parameterCount == metadata.fields.size }
                                .also { candidate -> candidate.isAccessible = true }

                        fun instantiate(arguments: List<Any?>): Any? =
                            constructor.newInstance(*arguments.toTypedArray())

                        return object : Monoid<Any?> {
                            override fun combine(left: Any?, right: Any?): Any? {
                                require(left != null)
                                require(right != null)
                                val combinedFields =
                                    metadata.fields.map { field ->
                                        val fieldMonoid = field.instance as Monoid<Any?>
                                        fieldMonoid.combine(field.get(left), field.get(right))
                                    }
                                return instantiate(combinedFields)
                            }

                            override fun empty(): Any? {
                                val emptyFields =
                                    metadata.fields.map { field ->
                                        val fieldMonoid = field.instance as Monoid<Any?>
                                        fieldMonoid.empty()
                                    }
                                return instantiate(emptyFields)
                            }
                        }
                    }

                    override fun deriveSum(metadata: SumTypeclassMetadata): Any =
                        error("sum monoid derivation is not used in this test")
                }
            }

            @Instance
            object IntMonoid : Monoid<Int> {
                override fun combine(left: Int, right: Int): Int = left + right

                override fun empty(): Int = 0
            }

            @Instance
            object StringMonoid : Monoid<String> {
                override fun combine(left: String, right: String): String = left + right

                override fun empty(): String = ""
            }

            @Derive(Monoid::class)
            data class Stats(val count: Int, val label: String)

            context(monoid: Monoid<A>)
            fun <A> mergeWithEmpty(value: A): A = monoid.combine(monoid.empty(), value)

            context(semigroup: Semigroup<A>)
            fun <A> combineAll(left: A, right: A): A = semigroup.combine(left, right)

            fun main() {
                println(mergeWithEmpty(Stats(2, "ab")))
                println(combineAll(Stats(2, "ab"), Stats(3, "cd")))
                println(summon<Monoid<Stats>>().empty())
                println(summon<Semigroup<Stats>>().combine(Stats(1, "x"), Stats(4, "y")))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                Stats(count=2, label=ab)
                Stats(count=5, label=abcd)
                Stats(count=0, label=)
                Stats(count=5, label=xy)
                """.trimIndent(),
        )
    }

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
