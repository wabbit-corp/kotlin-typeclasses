package one.wabbit.typeclass.plugin.integration

import org.junit.Ignore
import kotlin.test.Test

class OperatorRewriteTest : IntegrationTestSupport() {
    @Test fun rewritesContextualGetOperatorCalls() {
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

    @Test fun rewritesContextualGetOperatorCallsInsideMemberDispatchScope() {
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

    @Test fun rewritesContextualSetOperatorCalls() {
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

    @Test fun rewritesContextualContainsOperatorCalls() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            class Tags(private val values: Set<String>) {
                fun hasTag(value: String): Boolean = value in values
            }

            @Typeclass
            interface Membership<A> {
                fun contains(receiver: A, value: String): Boolean
            }

            @Instance
            object TagsMembership : Membership<Tags> {
                override fun contains(receiver: Tags, value: String): Boolean = receiver.hasTag(value)
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

    @Test fun rewritesContextualIteratorOperatorCalls() {
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

    @Test fun rewritesContextualComponentOperatorCalls() {
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

    @Test fun rewritesContextualCompareToOperatorCalls() {
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

    @Test fun delegatedPropertyOperatorsRemainBlockedByKotlinContextParameterSupport() {
        val source =
            """
            package demo

            import kotlin.reflect.KProperty
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            class Slot(
                var boundName: String = "",
                var stored: String = "",
            )

            @Typeclass
            interface SlotBinder<A> {
                fun bind(receiver: A, propertyName: String): A
            }

            @Typeclass
            interface SlotReader<A> {
                fun read(receiver: A, propertyName: String): String
            }

            @Typeclass
            interface SlotWriter<A> {
                fun write(receiver: A, propertyName: String, value: String)
            }

            @Instance
            object SlotBinderInstance : SlotBinder<Slot> {
                override fun bind(receiver: Slot, propertyName: String): Slot =
                    receiver.apply {
                        boundName = propertyName
                        if (stored.isEmpty()) {
                            stored = "bound:${'$'}propertyName"
                        }
                    }
            }

            @Instance
            object SlotReaderInstance : SlotReader<Slot> {
                override fun read(receiver: Slot, propertyName: String): String =
                    "${'$'}{receiver.boundName}/${'$'}propertyName:${'$'}{receiver.stored}"
            }

            @Instance
            object SlotWriterInstance : SlotWriter<Slot> {
                override fun write(receiver: Slot, propertyName: String, value: String) {
                    receiver.boundName = propertyName
                    receiver.stored = value.uppercase()
                }
            }

            context(binder: SlotBinder<A>) // E
            operator fun <A> A.provideDelegate(thisRef: Any?, property: KProperty<*>): A =
                binder.bind(this, property.name)

            context(reader: SlotReader<A>) // E
            operator fun <A> A.getValue(thisRef: Any?, property: KProperty<*>): String =
                reader.read(this, property.name)

            context(writer: SlotWriter<A>) // E
            operator fun <A> A.setValue(thisRef: Any?, property: KProperty<*>, value: String) {
                writer.write(this, property.name, value)
            }

            class Form {
                var name by Slot()
            }

            fun main() {
                val form = Form()
                println(form.name)
                form.name = "ada"
                println(form.name)
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedErrorContaining(
                        "context parameters on delegation operators are unsupported",
                        file = "Sample.kt",
                        line = 52,
                    ),
                    expectedErrorContaining(
                        "context parameters on delegation operators are unsupported",
                        file = "Sample.kt",
                        line = 56,
                    ),
                    expectedErrorContaining(
                        "context parameters on delegation operators are unsupported",
                        file = "Sample.kt",
                        line = 60,
                    ),
                ),
        )
    }

    @Test fun rewritesContextualUnaryOperatorCalls() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            data class Signed(val value: Int)

            data class Flag(val value: Boolean)

            data class Counter(val value: Int)

            @Typeclass
            interface Negate<A> {
                fun negate(value: A): A
            }

            @Typeclass
            interface LogicalNot<A> {
                fun negate(value: A): A
            }

            @Typeclass
            interface Step<A> {
                fun increment(value: A): A

                fun decrement(value: A): A
            }

            @Instance
            object SignedNegate : Negate<Signed> {
                override fun negate(value: Signed): Signed = Signed(-value.value)
            }

            @Instance
            object FlagNot : LogicalNot<Flag> {
                override fun negate(value: Flag): Flag = Flag(!value.value)
            }

            @Instance
            object CounterStep : Step<Counter> {
                override fun increment(value: Counter): Counter = Counter(value.value + 1)

                override fun decrement(value: Counter): Counter = Counter(value.value - 1)
            }

            context(negate: Negate<A>)
            operator fun <A> A.unaryMinus(): A = negate.negate(this)

            context(logicalNot: LogicalNot<A>)
            operator fun <A> A.not(): A = logicalNot.negate(this)

            context(step: Step<A>)
            operator fun <A> A.inc(): A = step.increment(this)

            context(step: Step<A>)
            operator fun <A> A.dec(): A = step.decrement(this)

            fun main() {
                println((-Signed(2)).value)
                println((!Flag(false)).value)

                var counter = Counter(3)
                counter++
                println(counter.value)
                counter--
                println(counter.value)
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                -2
                true
                4
                3
                """.trimIndent(),
        )
    }

    @Test fun rewritesContextualPlusAssignOperatorCalls() {
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

    @Test fun rewritesPlusAssignFallbackToContextualPlusOperatorCalls() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            data class Counter(val total: Int)

            @Typeclass
            interface Add<A> {
                fun add(receiver: A, amount: Int): A
            }

            @Instance
            object CounterAdd : Add<Counter> {
                override fun add(receiver: Counter, amount: Int): Counter = Counter(receiver.total + amount)
            }

            context(add: Add<A>)
            operator fun <A> A.plus(amount: Int): A = add.add(this, amount)

            fun main() {
                var counter = Counter(5)
                counter += 7
                println(counter.total)
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "12",
        )
    }

    @Ignore("Explicit-argument invoke currently misrewrites receiver ordering; keep as a pending regression.")
    @Test fun rewritesContextualInvokeOperatorCallsWithDefaultAndNamedArguments() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            data class Greeter(val text: String)

            @Typeclass
            interface Invokable<A> {
                fun invoke(receiver: A, times: Int, suffix: String): String
            }

            @Instance
            object GreeterInvoke : Invokable<Greeter> {
                override fun invoke(receiver: Greeter, times: Int, suffix: String): String =
                    List(times) { receiver.text }.joinToString(separator = "") + suffix
            }

            context(invokable: Invokable<A>)
            operator fun <A> A.invoke(times: Int = 1, suffix: String = "!"): String =
                invokable.invoke(this, times, suffix)

            fun main() {
                val greeter = Greeter("go")
                println(greeter())
                println(greeter(times = 2, suffix = "?"))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                go!
                gogo?
                """.trimIndent(),
        )
    }

    @Ignore("Explicit-argument invoke currently misrewrites receiver ordering; keep as a pending regression.")
    @Test fun rewritesContextualInvokeOperatorCallsWithTrailingLambdas() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            data class Greeter(val text: String)

            @Typeclass
            interface Invokable<A> {
                fun invoke(receiver: A, times: Int, transform: (String) -> String): String
            }

            @Instance
            object GreeterInvoke : Invokable<Greeter> {
                override fun invoke(receiver: Greeter, times: Int, transform: (String) -> String): String =
                    transform(List(times) { receiver.text }.joinToString(separator = ""))
            }

            context(invokable: Invokable<A>)
            operator fun <A> A.invoke(times: Int, transform: (String) -> String): String =
                invokable.invoke(this, times, transform)

            fun main() {
                val greeter = Greeter("hi")
                println(greeter(2) { text -> text.uppercase() })
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "HIHI",
        )
    }
}
