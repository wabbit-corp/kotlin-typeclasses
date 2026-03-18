package one.wabbit.typeclass.plugin.integration

import org.junit.Ignore
import kotlin.test.Test

/**
 * Design notes for future derivation support for a limited GADT-like fragment.
 *
 * Conservative fragment, intended to be safe for any derivable typeclass:
 * - Show
 * - Ord
 * - JsonWriter
 * - JsonReader
 * - JsonCodec
 *
 * Allowed fragment:
 * - The derived root is a sealed generic hierarchy.
 * - Each subclass result head is head-preserving:
 *   it must be exactly the root head applied to the root's own type parameters.
 *   Examples:
 *   - `FooInt<A> : Foo<A>` is allowed.
 *   - `Node<A> : Tree<A>` is allowed.
 *   - `IntLit : Expr<Int>` is rejected in the conservative fragment.
 *   - `Many<A> : Container<List<A>>` is rejected in the conservative fragment.
 * - Recursive field types are allowed when they are still fully determined by the
 *   head-preserving result head.
 * - Phantom root parameters are allowed.
 *
 * Rejected fragment:
 * - Constructors that refine the result head to concrete types.
 * - Constructors that refine the result head using type constructors such as `List<A>`.
 * - Fresh subclass type parameters that do not appear in the specialized result head.
 * - Field evidence that would depend on a type variable hidden from the result head.
 * - Constructor-local equalities / proof obligations that are not honestly encoded
 *   in the specialized result head.
 *
 * Rationale:
 * A derived instance is requested for `TC<Root<A, ...>>`. For reader-like and codec-like
 * typeclasses, generic derivation must be able to construct a value of that exact head.
 * Once a constructor is allowed to refine the result to `Root<Int>` or `Root<List<A>>`,
 * a generic root-level reader/codec would need target-head specialization or runtime
 * equality reasoning to know when that constructor is admissible.
 *
 * That means:
 * - the conservative fragment is variance-agnostic and safe for all typeclasses
 * - a more relaxed fragment may still be sound for sink-only / contravariant
 *   typeclasses such as Show or JsonWriter, because they only consume already-typed
 *   values and never need to construct them
 */
@Ignore("REVIEWED: future GADT derivation design")
class GADTDerivationTest : IntegrationTestSupport() {
    private val showDeriverPrelude =
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
        object BooleanShow : Show<Boolean> {
            override fun show(value: Boolean): String = value.toString()
        }

        @Instance
        context(show: Show<A>)
        fun <A> listShow(): Show<List<A>> =
            object : Show<List<A>> {
                override fun show(value: List<A>): String =
                    value.joinToString(prefix = "[", postfix = "]") { item -> show.show(item) }
            }

        context(show: Show<A>)
        fun <A> render(value: A): String = show.show(value)
        """.trimIndent()

    private val jsonWriterDeriverPrelude =
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
        interface JsonWriter<in A> {
            fun write(value: A): String

            companion object : TypeclassDeriver {
                override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                    object : JsonWriter<Any?> {
                        override fun write(value: Any?): String {
                            require(value != null)
                            val renderedFields =
                                metadata.fields.joinToString(", ", prefix = "{", postfix = "}") { field ->
                                    val fieldValue = field.get(value)
                                    val fieldWriter = field.instance as JsonWriter<Any?>
                                    "\"${'$'}{field.name}\":" + fieldWriter.write(fieldValue)
                                }
                            return renderedFields
                        }
                    }

                override fun deriveSum(metadata: SumTypeclassMetadata): Any =
                    object : JsonWriter<Any?> {
                        override fun write(value: Any?): String {
                            require(value != null)
                            val matchingCase = metadata.cases.single { candidate -> candidate.matches(value) }
                            val caseWriter = matchingCase.instance as JsonWriter<Any?>
                            return caseWriter.write(value)
                        }
                    }
            }
        }

        @Instance
        object IntJsonWriter : JsonWriter<Int> {
            override fun write(value: Int): String = value.toString()
        }

        @Instance
        object BooleanJsonWriter : JsonWriter<Boolean> {
            override fun write(value: Boolean): String = value.toString()
        }

        @Instance
        context(writer: JsonWriter<A>)
        fun <A> listJsonWriter(): JsonWriter<List<A>> =
            object : JsonWriter<List<A>> {
                override fun write(value: List<A>): String =
                    value.joinToString(prefix = "[", postfix = "]") { item -> writer.write(item) }
            }

        context(writer: JsonWriter<A>)
        fun <A> encode(value: A): String = writer.write(value)
        """.trimIndent()

    // NEW
    // Rationale: this is the `data Foo a = Foo Int` shape. The root parameter is phantom, but the result head is still `Foo<A>`.
    @Test
    fun derivesPhantomParameterConstructors() {
        val source =
            """
            $showDeriverPrelude

            @Derive(Show::class)
            sealed interface Foo<A>

            @Derive(Show::class)
            data class FooInt<A>(val value: Int) : Foo<A>

            fun main() {
                val value: Foo<String> = FooInt<String>(1)
                println(render(value))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "FooInt(value=1)",
        )
    }

    // NEW
    // Rationale: `Expr<Int>` and `Expr<Boolean>` are fine for writers, but not for a generic `JsonReader<Expr<A>>` or `JsonCodec<Expr<A>>`.
    @Test
    fun rejectsConstructorsThatRefineResultsToConcreteTypesInTheConservativeFragment() {
        val source =
            """
            $showDeriverPrelude

            @Derive(Show::class)
            sealed interface Expr<A>

            @Derive(Show::class)
            data class IntLit(val value: Int) : Expr<Int>

            @Derive(Show::class)
            data class BoolLit(val value: Boolean) : Expr<Boolean>

            fun main() {
                val intExpr: Expr<Int> = IntLit(1)
                val boolExpr: Expr<Boolean> = BoolLit(true)
                println(render(intExpr))
                println(render(boolExpr))
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("refine", "result head"),
        )
    }

    // NEW
    // Rationale: `Container<List<A>>` is not head-preserving, so a generic reader/codec for `Container<T>` would need extra reasoning.
    @Test
    fun rejectsConstructorsThatRefineResultsUsingTypeConstructorsInTheConservativeFragment() {
        val source =
            """
            $showDeriverPrelude

            @Derive(Show::class)
            sealed interface Container<A>

            @Derive(Show::class)
            data class One<A>(val value: A) : Container<A>

            @Derive(Show::class)
            data class Many<A>(val values: List<A>) : Container<List<A>>

            fun main() {
                val one: Container<Int> = One(1)
                val many: Container<List<Int>> = Many(listOf(1, 2))
                println(render(one))
                println(render(many))
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("refine", "result head"),
        )
    }

    // NEW
    // Rationale: head-preserving recursive constructors are safe even for reader/codec derivation.
    @Test
    fun derivesHeadPreservingRecursiveConstructors() {
        val source =
            """
            $showDeriverPrelude

            @Derive(Show::class)
            sealed interface Tree<A>

            @Derive(Show::class)
            data class Leaf<A>(val value: A) : Tree<A>

            @Derive(Show::class)
            data class Branch<A>(val left: Tree<A>, val right: Tree<A>) : Tree<A>

            fun main() {
                val value: Tree<Int> = Branch(Leaf(1), Branch(Leaf(2), Leaf(3)))
                println(render(value))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "Branch(left=Leaf(value=1), right=Branch(left=Leaf(value=2), right=Leaf(value=3)))",
        )
    }

    // NEW
    // Rationale: `B` is existential from the viewpoint of `Packed<A>`, so derivation cannot know what evidence to use for `value`.
    @Test
    fun rejectsConstructorsWithFreshTypeParametersHiddenFromTheSpecializedResultHead() {
        val source =
            """
            $showDeriverPrelude

            @Derive(Show::class)
            sealed interface Packed<A>

            @Derive(Show::class)
            data class Hidden<A, B>(val value: B) : Packed<A> // ERROR B is hidden from Packed<A>
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("hidden", "result head"),
        )
    }

    // NEW
    // Rationale: the recursive field `Weird<B>` would require evidence for a hidden `B` that is not recoverable from `Weird<A>`.
    @Test
    fun rejectsRecursiveConstructorsWithHiddenTypeParametersInFieldEvidence() {
        val source =
            """
            $showDeriverPrelude

            @Derive(Show::class)
            sealed interface Weird<A>

            @Derive(Show::class)
            data class IntLeaf(val value: Int) : Weird<Int>

            @Derive(Show::class)
            data class Leak<A, B>(
                val left: Weird<A>,
                val right: Weird<B>,
            ) : Weird<A> // ERROR B is hidden from Weird<A>
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("hidden", "result head"),
        )
    }

    // NEW
    @Ignore("NEW: relaxed rule for sink-only / contravariant typeclasses only")
    // Rationale: writer-like derivation can consume already-typed values of `Expr<Int>` and `Expr<Boolean>` without constructing them.
    @Test
    fun relaxedContravariantRulesCanDeriveConcreteResultRefinementsForJsonWriter() {
        val source =
            """
            $jsonWriterDeriverPrelude

            @Derive(JsonWriter::class)
            sealed interface Expr<A>

            @Derive(JsonWriter::class)
            data class IntLit(val value: Int) : Expr<Int>

            @Derive(JsonWriter::class)
            data class BoolLit(val value: Boolean) : Expr<Boolean>

            fun main() {
                val intExpr: Expr<Int> = IntLit(1)
                val boolExpr: Expr<Boolean> = BoolLit(true)
                println(encode(intExpr))
                println(encode(boolExpr))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                {"value":1}
                {"value":true}
                """.trimIndent(),
        )
    }

    // NEW
    @Ignore("NEW: relaxed rule for sink-only / contravariant typeclasses only")
    // Rationale: this is still unsafe for codecs, but may be fine for writers because only output is produced.
    @Test
    fun relaxedContravariantRulesCanDeriveTypeConstructorRefinementsForJsonWriter() {
        val source =
            """
            $jsonWriterDeriverPrelude

            @Derive(JsonWriter::class)
            sealed interface Container<A>

            @Derive(JsonWriter::class)
            data class One<A>(val value: A) : Container<A>

            @Derive(JsonWriter::class)
            data class Many<A>(val values: List<A>) : Container<List<A>>

            fun main() {
                val one: Container<Int> = One(1)
                val many: Container<List<Int>> = Many(listOf(1, 2))
                println(encode(one))
                println(encode(many))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                {"value":1}
                {"values":[1, 2]}
                """.trimIndent(),
        )
    }

    // NEW
    @Ignore("NEW: relaxed rule for sink-only / contravariant typeclasses only")
    // Rationale: typed expression GADTs become more plausible for writers than for readers/codecs.
    @Test
    fun relaxedContravariantRulesCanDeriveTypedExpressionHierarchiesForJsonWriter() {
        val source =
            """
            $jsonWriterDeriverPrelude

            @Derive(JsonWriter::class)
            sealed interface Expr<A>

            @Derive(JsonWriter::class)
            data class IntLit(val value: Int) : Expr<Int>

            @Derive(JsonWriter::class)
            data class BoolLit(val value: Boolean) : Expr<Boolean>

            @Derive(JsonWriter::class)
            data class If<A>(
                val cond: Expr<Boolean>,
                val ifTrue: Expr<A>,
                val ifFalse: Expr<A>,
            ) : Expr<A>

            fun main() {
                val value: Expr<Int> = If(BoolLit(true), IntLit(1), IntLit(2))
                println(encode(value))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "{\"cond\":{\"value\":true}, \"ifTrue\":{\"value\":1}, \"ifFalse\":{\"value\":2}}",
        )
    }
}
