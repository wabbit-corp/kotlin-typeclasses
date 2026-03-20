package one.wabbit.typeclass.plugin.integration.derivation

import one.wabbit.typeclass.plugin.integration.IntegrationTestSupport
import org.junit.Ignore
import kotlin.test.Test

/**
 * Design notes for future variance-aware derivation support for a limited GADT-like fragment.
 *
 * Hidden premise:
 * - Derivation is producing generic erased evidence for `TC<Root<A, ...>>`, not
 *   monomorphizing a fresh instance per concrete use site such as `TC<Root<Int>>`.
 * - That is why contravariant consumers can tolerate unreachable specialized cases while
 *   covariant / invariant producers cannot.
 * - Effective variance is therefore only an observational classifier over the callable
 *   surface, not the final admissibility policy.
 *
 * Proposed workflow:
 * 1. Classify the derived typeclass parameter as observationally effectively covariant,
 *    contravariant, invariant, or phantom.
 * 2. Apply any explicit GADT-derivation policy override for that typeclass or type
 *    parameter.
 * 3. Use the resulting admissibility policy to decide which constructor result-head
 *    refinements are admissible for the requested `TC<Root<...>>`.
 *
 * Effective variance classification for the relevant typeclass parameter `A`:
 * - Declaration-site variance is authoritative:
 *   - `in A` means effectively contravariant
 *   - `out A` means effectively covariant
 * - Otherwise infer from the full inherited callable surface of the typeclass.
 *   Implementations / bodies are irrelevant; only signatures matter.
 * - Inherited members may be handled either by scanning expanded signatures directly or
 *   by caching each supertype parameter's effective variance and composing it through the
 *   subtype-to-supertype application.
 * - Ignore occurrences wrapped in `@UnsafeVariance`; they contribute no constraints.
 * - Method parameter position contributes `ct`; method return position contributes `cv`.
 * - When traversing a nested generic type `F<T>`, compose through the referenced type
 *   constructor's declaration-site variance unless a use-site projection overrides it.
 *   Kotlin built-ins such as `List<out E>` and function types follow the same rule.
 * - Nested constructor variance must come from compiler symbols, not user-authored
 *   witness typeclasses such as `IsCovariant<T>`.
 * - Use-site projections are authoritative:
 *   - `out A` contributes `cv`
 *   - `in A` contributes `ct`
 *   - `*` contributes no occurrence
 * - Compose nested positions using the algebra from
 *   `https://wabbit.blog/posts/fp-variance.html`:
 *   - `cv * cv = cv`
 *   - `ct * ct = cv`
 *   - `cv * ct = ct`
 *   - `inv * X = inv`
 *   - `ph * X = ph`
 * - Combine independent occurrences with lattice meet / parallel composition:
 *   - `cv \/ ct = inv`
 *   - `ph \/ cv = cv`
 *   - `ph \/ ct = ct`
 * - Final classification:
 *   - no remaining occurrences => effectively phantom
 *   - only `ct` occurrences => effectively contravariant
 *   - only `cv` occurrences => effectively covariant
 *   - any mixture of `cv` and `ct`, or any `inv` edge => effectively invariant
 *
 * Proposed admissibility policy by effective variance:
 * - Default policy: trust the observational effective variance classifier.
 * - Override policy: some proof-like or case-sensitive typeclasses may need an explicit
 *   `CONSERVATIVE_ONLY` override even when their callable surface looks contravariant or
 *   phantom.
 * - Effectively covariant or invariant:
 *   allow only the conservative head-preserving fragment.
 * - Effectively contravariant:
 *   also allow constructor result-head refinements whose specialized result head unifies
 *   with the requested root head.
 * - Effectively phantom:
 *   start by enabling at least the contravariant fragment; it may admit an even larger
 *   subset because the type parameter is observationally irrelevant.
 * - In every relaxed bucket, once a constructor result head is admitted, every required
 *   field-evidence type must be expressible solely in terms of the admitted root
 *   parameters and concrete types.
 *
 * Conservative head-preserving fragment:
 * - The derived root is a sealed generic hierarchy.
 * - Each subclass result head is exactly the root head applied to the root's own
 *   parameters.
 *   Examples:
 *   - `FooInt<A> : Foo<A>` is allowed.
 *   - `Node<A> : Tree<A>` is allowed.
 *   - `IntLit : Expr<Int>` is not head-preserving.
 *   - `Many<A> : Container<List<A>>` is not head-preserving.
 * - Recursive field types are allowed when they are fully determined by the admitted
 *   result head.
 * - Phantom root parameters are allowed.
 *
 * Under the current metadata / evidence contract, still reject in every variance bucket
 * unless a later design adds explicit equality reasoning or relaxes eager field-evidence
 * requirements:
 * - Fresh subclass type parameters that do not appear in the admitted result head.
 * - Field evidence that depends on a type variable hidden from the admitted result head.
 * - Constructor-local equalities / proof obligations not honestly encoded in the admitted
 *   result head.
 *
 * Fixture caveat:
 * - `Show<A>` is effectively contravariant because `A` only appears in
 *   `show(value: A)`.
 * - In a variance-aware suite it belongs in the relaxed bucket alongside `JsonWriter`.
 * - Conservative rejection cases below should be exercised through effectively
 *   covariant or invariant harnesses such as `JsonReader` or `Codec`.
 * - Effective variance is only a derivation-admissibility classifier over the observable
 *   callable surface. Proof-like or case-sensitive APIs may still need a stricter future
 *   override if pure signature inference would classify them too permissively.
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

    private val jsonReaderDeriverPrelude =
        """
        package demo

        import one.wabbit.typeclass.Derive
        import one.wabbit.typeclass.Instance
        import one.wabbit.typeclass.ProductTypeclassMetadata
        import one.wabbit.typeclass.SumTypeclassMetadata
        import one.wabbit.typeclass.Typeclass
        import one.wabbit.typeclass.TypeclassDeriver

        @Typeclass
        interface JsonReader<out A> {
            fun read(json: String): A

            companion object : TypeclassDeriver {
                override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                    object : JsonReader<Any?> {
                        override fun read(json: String): Any? = error("stub")
                    }

                override fun deriveSum(metadata: SumTypeclassMetadata): Any =
                    object : JsonReader<Any?> {
                        override fun read(json: String): Any? = error("stub")
                    }
            }
        }

        @Instance
        object IntJsonReader : JsonReader<Int> {
            override fun read(json: String): Int = error("stub")
        }

        @Instance
        object BooleanJsonReader : JsonReader<Boolean> {
            override fun read(json: String): Boolean = error("stub")
        }

        @Instance
        context(reader: JsonReader<A>)
        fun <A> listJsonReader(): JsonReader<List<A>> =
            object : JsonReader<List<A>> {
                override fun read(json: String): List<A> = error("stub")
            }

        context(reader: JsonReader<A>)
        fun <A> decode(json: String): A = reader.read(json)
        """.trimIndent()

    private val codecDeriverPrelude =
        """
        package demo

        import one.wabbit.typeclass.Derive
        import one.wabbit.typeclass.Instance
        import one.wabbit.typeclass.ProductTypeclassMetadata
        import one.wabbit.typeclass.SumTypeclassMetadata
        import one.wabbit.typeclass.Typeclass
        import one.wabbit.typeclass.TypeclassDeriver

        @Typeclass
        interface Codec<A> {
            fun encode(value: A): String

            fun decode(raw: String): A

            companion object : TypeclassDeriver {
                override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                    object : Codec<Any?> {
                        override fun encode(value: Any?): String = metadata.typeName.substringAfterLast('.')

                        override fun decode(raw: String): Any? = error("stub")
                    }

                override fun deriveSum(metadata: SumTypeclassMetadata): Any =
                    object : Codec<Any?> {
                        override fun encode(value: Any?): String = metadata.typeName.substringAfterLast('.')

                        override fun decode(raw: String): Any? = error("stub")
                    }
            }
        }

        @Instance
        object IntCodec : Codec<Int> {
            override fun encode(value: Int): String = value.toString()

            override fun decode(raw: String): Int = error("stub")
        }

        @Instance
        object BooleanCodec : Codec<Boolean> {
            override fun encode(value: Boolean): String = value.toString()

            override fun decode(raw: String): Boolean = error("stub")
        }

        @Instance
        context(codec: Codec<A>)
        fun <A> listCodec(): Codec<List<A>> =
            object : Codec<List<A>> {
                override fun encode(value: List<A>): String = value.joinToString(prefix = "[", postfix = "]")

                override fun decode(raw: String): List<A> = error("stub")
            }

        context(codec: Codec<A>)
        fun <A> roundTrip(raw: String): A = codec.decode(raw)
        """.trimIndent()

    private val phantomTagDeriverPrelude =
        """
        package demo

        import one.wabbit.typeclass.Derive
        import one.wabbit.typeclass.ProductTypeclassMetadata
        import one.wabbit.typeclass.SumTypeclassMetadata
        import one.wabbit.typeclass.Typeclass
        import one.wabbit.typeclass.TypeclassDeriver

        @Typeclass
        interface Tag<A> {
            fun tag(): String

            companion object : TypeclassDeriver {
                override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                    object : Tag<Any?> {
                        override fun tag(): String = metadata.typeName.substringAfterLast('.')
                    }

                override fun deriveSum(metadata: SumTypeclassMetadata): Any =
                    object : Tag<Any?> {
                        override fun tag(): String = metadata.typeName.substringAfterLast('.')
                    }
            }
        }

        context(tag: Tag<A>)
        fun <A> tagName(): String = tag.tag()
        """.trimIndent()

    private val constructorsDeriverPrelude =
        """
        package demo

        import one.wabbit.typeclass.Derive
        import one.wabbit.typeclass.ProductTypeclassMetadata
        import one.wabbit.typeclass.SumTypeclassMetadata
        import one.wabbit.typeclass.Typeclass
        import one.wabbit.typeclass.TypeclassDeriver

        enum class GadtDerivationMode {
            SURFACE_TRUSTED,
            CONSERVATIVE_ONLY,
        }

        @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE_PARAMETER)
        annotation class GadtDerivationPolicy(val mode: GadtDerivationMode)

        @Typeclass
        interface Constructors<@GadtDerivationPolicy(GadtDerivationMode.CONSERVATIVE_ONLY) A> {
            fun caseNames(): List<String>

            companion object : TypeclassDeriver {
                override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                    object : Constructors<Any?> {
                        override fun caseNames(): List<String> =
                            listOf(metadata.typeName.substringAfterLast('.'))
                    }

                override fun deriveSum(metadata: SumTypeclassMetadata): Any =
                    object : Constructors<Any?> {
                        override fun caseNames(): List<String> =
                            metadata.cases.map { candidate -> candidate.name }
                    }
            }
        }

        context(constructors: Constructors<A>)
        fun <A> caseNames(): List<String> = constructors.caseNames()
        """.trimIndent()

    private val inheritedSinkDeriverPrelude =
        """
        package demo

        import one.wabbit.typeclass.Derive
        import one.wabbit.typeclass.ProductTypeclassMetadata
        import one.wabbit.typeclass.SumTypeclassMetadata
        import one.wabbit.typeclass.Typeclass
        import one.wabbit.typeclass.TypeclassDeriver

        interface BaseSink<A> {
            fun accept(value: A): String
        }

        @Typeclass
        interface InheritedSink<A> : BaseSink<A> {
            companion object : TypeclassDeriver {
                override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                    object : InheritedSink<Any?> {
                        override fun accept(value: Any?): String = metadata.typeName.substringAfterLast('.')
                    }

                override fun deriveSum(metadata: SumTypeclassMetadata): Any =
                    object : InheritedSink<Any?> {
                        override fun accept(value: Any?): String = metadata.typeName.substringAfterLast('.')
                    }
            }
        }

        context(sink: InheritedSink<A>)
        fun <A> consume(value: A): String = sink.accept(value)
        """.trimIndent()

    private val unsafeVarianceSinkDeriverPrelude =
        """
        package demo

        import kotlin.UnsafeVariance
        import one.wabbit.typeclass.Derive
        import one.wabbit.typeclass.ProductTypeclassMetadata
        import one.wabbit.typeclass.SumTypeclassMetadata
        import one.wabbit.typeclass.Typeclass
        import one.wabbit.typeclass.TypeclassDeriver

        @Typeclass
        interface UnsafeSink<A> {
            fun accept(value: A): String

            fun leak(): @UnsafeVariance A

            companion object : TypeclassDeriver {
                override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                    object : UnsafeSink<Any?> {
                        override fun accept(value: Any?): String = metadata.typeName.substringAfterLast('.')

                        override fun leak(): Any? = error("stub")
                    }

                override fun deriveSum(metadata: SumTypeclassMetadata): Any =
                    object : UnsafeSink<Any?> {
                        override fun accept(value: Any?): String = metadata.typeName.substringAfterLast('.')

                        override fun leak(): Any? = error("stub")
                    }
            }
        }

        context(sink: UnsafeSink<A>)
        fun <A> consumeUnsafe(value: A): String = sink.accept(value)
        """.trimIndent()

    private val projectedSinkDeriverPrelude =
        """
        package demo

        import one.wabbit.typeclass.Derive
        import one.wabbit.typeclass.ProductTypeclassMetadata
        import one.wabbit.typeclass.SumTypeclassMetadata
        import one.wabbit.typeclass.Typeclass
        import one.wabbit.typeclass.TypeclassDeriver

        class Box<T>(val value: T)

        @Typeclass
        interface ProjectedSink<A> {
            fun accept(box: Box<out A>): String

            companion object : TypeclassDeriver {
                override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                    object : ProjectedSink<Any?> {
                        override fun accept(box: Box<out Any?>): String = metadata.typeName.substringAfterLast('.')
                    }

                override fun deriveSum(metadata: SumTypeclassMetadata): Any =
                    object : ProjectedSink<Any?> {
                        override fun accept(box: Box<out Any?>): String = metadata.typeName.substringAfterLast('.')
                    }
            }
        }

        context(sink: ProjectedSink<A>)
        fun <A> consumeProjected(box: Box<out A>): String = sink.accept(box)

        @Typeclass
        interface ReverseProjectedSink<A> {
            fun accept(box: Box<in A>): String

            companion object : TypeclassDeriver {
                override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                    object : ReverseProjectedSink<Any?> {
                        override fun accept(box: Box<in Any?>): String = metadata.typeName.substringAfterLast('.')
                    }

                override fun deriveSum(metadata: SumTypeclassMetadata): Any =
                    object : ReverseProjectedSink<Any?> {
                        override fun accept(box: Box<in Any?>): String = metadata.typeName.substringAfterLast('.')
                    }
            }
        }

        context(sink: ReverseProjectedSink<A>)
        fun <A> consumeProjectedIn(box: Box<in A>): String = sink.accept(box)

        @Typeclass
        interface InvariantBoxSink<A> {
            fun accept(box: Box<A>): String

            companion object : TypeclassDeriver {
                override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                    object : InvariantBoxSink<Any?> {
                        override fun accept(box: Box<Any?>): String = metadata.typeName.substringAfterLast('.')
                    }

                override fun deriveSum(metadata: SumTypeclassMetadata): Any =
                    object : InvariantBoxSink<Any?> {
                        override fun accept(box: Box<Any?>): String = metadata.typeName.substringAfterLast('.')
                    }
            }
        }

        context(sink: InvariantBoxSink<A>)
        fun <A> consumeInvariantBox(box: Box<A>): String = sink.accept(box)

        @Typeclass
        interface ListSink<A> {
            fun accept(values: List<A>): String

            companion object : TypeclassDeriver {
                override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                    object : ListSink<Any?> {
                        override fun accept(values: List<Any?>): String = metadata.typeName.substringAfterLast('.')
                    }

                override fun deriveSum(metadata: SumTypeclassMetadata): Any =
                    object : ListSink<Any?> {
                        override fun accept(values: List<Any?>): String = metadata.typeName.substringAfterLast('.')
                    }
            }
        }

        context(sink: ListSink<A>)
        fun <A> consumeList(values: List<A>): String = sink.accept(values)

        @Typeclass
        interface StarTag<A> {
            fun observe(box: Box<*>): String

            fun tag(): String

            companion object : TypeclassDeriver {
                override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                    object : StarTag<Any?> {
                        override fun observe(box: Box<*>): String = metadata.typeName.substringAfterLast('.')

                        override fun tag(): String = metadata.typeName.substringAfterLast('.')
                    }

                override fun deriveSum(metadata: SumTypeclassMetadata): Any =
                    object : StarTag<Any?> {
                        override fun observe(box: Box<*>): String = metadata.typeName.substringAfterLast('.')

                        override fun tag(): String = metadata.typeName.substringAfterLast('.')
                    }
            }
        }

        context(tag: StarTag<A>)
        fun <A> starTagName(box: Box<*>): String = tag.tag() + ":" + tag.observe(box)
        """.trimIndent()

    // NEW
    // Rationale: this is the `data Foo a = Foo Int` shape. The root parameter is phantom,
    // but the subclass result head is still the conservative `Foo<A>` form.
    @Test
    fun derivesHeadPreservingConstructorsEvenWhenTheRootParameterIsPhantom() {
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
    // Rationale: declared `out A` is authoritative, so this stays in the conservative
    // bucket even if a future signature scanner would otherwise infer something else.
    @Test
    fun declaredCovariantTypeclassesRejectConcreteResultRefinements() {
        val source =
            """
            $jsonReaderDeriverPrelude

            @Derive(JsonReader::class)
            sealed interface Expr<A>

            @Derive(JsonReader::class)
            data object IntLit : Expr<Int>

            @Derive(JsonReader::class)
            data object BoolLit : Expr<Boolean>

            fun main() {
                val value: Expr<Int> = decode("{}")
                println(value)
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("refine", "result head"),
        )
    }

    // NEW
    // Rationale: `Codec<A>` is effectively invariant because it both consumes and
    // produces `A`, so non-head-preserving result refinements must remain rejected.
    @Test
    fun effectivelyInvariantTypeclassesRejectTypeConstructorResultRefinements() {
        val source =
            """
            $codecDeriverPrelude

            @Derive(Codec::class)
            sealed interface Container<A>

            @Derive(Codec::class)
            data class One<A>(val value: A) : Container<A>

            @Derive(Codec::class)
            data class Many<A>(val values: List<A>) : Container<List<A>>

            fun main() {
                val value: Container<List<Int>> = roundTrip("[]")
                println(value)
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("refine", "result head"),
        )
    }

    // NEW
    // Rationale: head-preserving recursive constructors are safe in the conservative
    // fragment. The runtime check uses `Show`; a reader-like compile-only check appears
    // below to keep that claim grounded.
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
    // Rationale: a covariant reader-like typeclass should also accept the conservative
    // head-preserving fragment.
    @Test
    fun declaredCovariantTypeclassesCanDeriveHeadPreservingRecursiveConstructors() {
        val source =
            """
            $jsonReaderDeriverPrelude

            @Derive(JsonReader::class)
            sealed interface Tree<A>

            @Derive(JsonReader::class)
            data class Leaf<A>(val value: A) : Tree<A>

            @Derive(JsonReader::class)
            data class Branch<A>(val left: Tree<A>, val right: Tree<A>) : Tree<A>

            fun main() {
                val value: Tree<Int> = decode("{}")
                println(value)
            }
            """.trimIndent()

        assertCompiles(source = source)
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
            data class Hidden<A, B>(val value: B) : Packed<A> // B is hidden from Packed<A>
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
            ) : Weird<A> // B is hidden from Weird<A>
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("hidden", "result head"),
        )
    }

    // NEW
    // Rationale: `Show<A>` has no declaration-site variance, so this exercises pure
    // signature-based effective-variance inference.
    @Test
    fun inferredContravariantTypeclassesCanDeriveConcreteResultRefinementsForShow() {
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
                val value: Expr<Int> = IntLit(1)
                println(render(value))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "IntLit(value=1)",
        )
    }

    // NEW
    // Rationale: once effective variance is implemented, this bucket should apply to any
    // effectively contravariant typeclass, including `Show`, not just `JsonWriter`.
    @Test
    fun declaredContravariantTypeclassesCanDeriveConcreteResultRefinementsForJsonWriter() {
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
    // Rationale: the classifier should also expose the phantom bucket; with no
    // occurrences of `A`, this is at least as permissive as the contravariant fragment.
    @Test
    fun effectivelyPhantomTypeclassesCanDeriveConcreteResultRefinementsForTag() {
        val source =
            """
            $phantomTagDeriverPrelude

            @Derive(Tag::class)
            sealed interface Expr<A>

            @Derive(Tag::class)
            data object IntLit : Expr<Int>

            @Derive(Tag::class)
            data object BoolLit : Expr<Boolean>

            fun main() {
                println(tagName<Expr<Int>>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "Expr",
        )
    }

    // NEW
    // Rationale: `Constructors<A>` is observationally phantom, but semantically depends
    // on the admitted constructor set. A future conservative-only policy override should
    // therefore reject relaxed GADT admission for this typeclass even though pure
    // surface-based variance would classify it as phantom.
    @Test
    fun surfacePhantomTypeclassesMayStillRequireConservativeOnlyPolicyOverrides() {
        val source =
            """
            $constructorsDeriverPrelude

            @Derive(Constructors::class)
            sealed interface Expr<A>

            @Derive(Constructors::class)
            data object IntLit : Expr<Int>

            @Derive(Constructors::class)
            data object BoolLit : Expr<Boolean>

            fun main() {
                println(caseNames<Expr<Int>>().joinToString())
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("conservative", "result head"),
        )
    }

    // NEW
    // Rationale: inherited callable signatures must contribute to the effective
    // variance calculation even when the subtype declares no methods of its own.
    @Test
    fun inheritedMembersContributeToEffectiveContravariance() {
        val source =
            """
            $inheritedSinkDeriverPrelude

            @Derive(InheritedSink::class)
            sealed interface Expr<A>

            @Derive(InheritedSink::class)
            data object IntLit : Expr<Int>

            @Derive(InheritedSink::class)
            data object BoolLit : Expr<Boolean>

            fun main() {
                val value: Expr<Int> = IntLit
                println(consume(value))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "Expr",
        )
    }

    // NEW
    // Rationale: the `leak()` result must not force invariance when it is marked with
    // `@UnsafeVariance`; only the safe surface should constrain effective variance.
    @Test
    fun unsafeVarianceOccurrencesAreIgnoredWhenClassifyingEffectiveVariance() {
        val source =
            """
            $unsafeVarianceSinkDeriverPrelude

            @Derive(UnsafeSink::class)
            sealed interface Expr<A>

            @Derive(UnsafeSink::class)
            data object IntLit : Expr<Int>

            @Derive(UnsafeSink::class)
            data object BoolLit : Expr<Boolean>

            fun main() {
                val value: Expr<Int> = IntLit
                println(consumeUnsafe(value))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "Expr",
        )
    }

    // NEW
    // Rationale: declaration-site covariance of `List<out E>` must participate in the
    // composition, not just explicit use-site projections.
    @Test
    fun nestedDeclarationSiteCovarianceCanStillProduceEffectiveContravariance() {
        val source =
            """
            $projectedSinkDeriverPrelude

            @Derive(ListSink::class)
            sealed interface Expr<A>

            @Derive(ListSink::class)
            data object IntLit : Expr<Int>

            @Derive(ListSink::class)
            data object BoolLit : Expr<Boolean>

            fun main() {
                val values: List<Expr<Int>> = listOf(IntLit)
                println(consumeList(values))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "Expr",
        )
    }

    // NEW
    // Rationale: `Box` itself is invariant, so accepting this case requires treating the
    // use-site `out A` projection as authoritative instead of collapsing to invariance.
    @Test
    fun useSiteOutProjectionsContributeToEffectiveContravariance() {
        val source =
            """
            $projectedSinkDeriverPrelude

            @Derive(ProjectedSink::class)
            sealed interface Expr<A>

            @Derive(ProjectedSink::class)
            data object IntLit : Expr<Int>

            @Derive(ProjectedSink::class)
            data object BoolLit : Expr<Boolean>

            fun main() {
                val boxed: Box<out Expr<Int>> = Box(IntLit)
                println(consumeProjected(boxed))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "Expr",
        )
    }

    // NEW
    // Rationale: plain invariant nesting `Box<A>` in parameter position should still
    // classify the typeclass as effectively invariant and therefore keep the
    // conservative rejection policy.
    @Test
    fun plainInvariantNestingRejectsConcreteResultRefinements() {
        val source =
            """
            $projectedSinkDeriverPrelude

            @Derive(InvariantBoxSink::class)
            sealed interface Expr<A>

            @Derive(InvariantBoxSink::class)
            data object IntLit : Expr<Int>

            @Derive(InvariantBoxSink::class)
            data object BoolLit : Expr<Boolean>

            fun main() {
                val boxed: Box<Expr<Int>> = Box(IntLit)
                println(consumeInvariantBox(boxed))
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("refine", "result head"),
        )
    }

    // NEW
    // Rationale: method parameters are negative, so a nested use-site `in A`
    // contributes `ct * ct = cv` and pushes this shape back into the conservative
    // covariant bucket.
    @Test
    fun useSiteInProjectionsCanFlipEffectiveContravarianceToCovariance() {
        val source =
            """
            $projectedSinkDeriverPrelude

            @Derive(ReverseProjectedSink::class)
            sealed interface Expr<A>

            @Derive(ReverseProjectedSink::class)
            data object IntLit : Expr<Int>

            @Derive(ReverseProjectedSink::class)
            data object BoolLit : Expr<Boolean>

            fun main() {
                val boxed: Box<in Expr<Int>> = Box<Any?>(IntLit)
                println(consumeProjectedIn(boxed))
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("refine", "result head"),
        )
    }

    // NEW
    // Rationale: star projections contribute no occurrence, so this shape should remain
    // effectively phantom instead of collapsing to invariance.
    @Test
    fun starProjectionsDoNotConstrainEffectiveVariance() {
        val source =
            """
            $projectedSinkDeriverPrelude

            @Derive(StarTag::class)
            sealed interface Expr<A>

            @Derive(StarTag::class)
            data object IntLit : Expr<Int>

            @Derive(StarTag::class)
            data object BoolLit : Expr<Boolean>

            fun main() {
                println(starTagName<Expr<Int>>(Box(IntLit)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "Expr:Expr",
        )
    }

    // NEW
    // Rationale: this remains unsafe for effectively covariant / invariant typeclasses,
    // but may be admissible once the target typeclass is only consuming the requested
    // root head.
    @Test
    fun declaredContravariantTypeclassesCanDeriveTypeConstructorResultRefinementsForJsonWriter() {
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
    // Rationale: typed expression GADTs become plausible for effectively contravariant
    // typeclasses once constructor admissibility is driven by unification against the
    // requested root head.
    @Test
    fun declaredContravariantTypeclassesCanDeriveTypedExpressionHierarchiesForJsonWriter() {
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

    @Ignore("Pending derivation admissibility work")
    @Test
    fun derivesOnlyAdmissibleSumCasesForRequestedTypeclasses() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.SumTypeclassMetadata
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.TypeclassDeriver

            @Typeclass
            interface Codec<A> {
                fun encode(value: A): String
                fun decode(value: String): A

                companion object : TypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                        error("placeholder")

                    override fun deriveSum(metadata: SumTypeclassMetadata): Any =
                        error("placeholder")
                }
            }

            @Derive(Codec::class)
            sealed interface Expr<A>

            data class Lit(val value: Int) : Expr<Int>

            data class Name(val value: String) : Expr<String>
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("derive", "expr"),
            expectedDiagnostics = listOf(expectedErrorContaining("derive")),
        )
    }
}
