// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.typeclass.plugin.integration.derivation

import one.wabbit.typeclass.plugin.integration.CompilerHarnessPlugin
import one.wabbit.typeclass.plugin.integration.IntegrationTestSupport
import kotlin.test.Test

class DerivationSemanticsTest : IntegrationTestSupport() {
    private val serializationPlugins = listOf(CompilerHarnessPlugin.Serialization)
    private val serializationRuntime = listOf(CompilerHarnessPlugin.SerializationRuntime)

    @Test
    fun derivesProductMonoidsSemantically() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.ProductTypeclassDeriver
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.get
            import one.wabbit.typeclass.summon

            @Typeclass
            interface Semigroup<A> {
                fun combine(left: A, right: A): A
            }

            @Typeclass
            interface Monoid<A> : Semigroup<A> {
                fun empty(): A

                companion object : ProductTypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                        object : Monoid<Any?> {
                            override fun combine(left: Any?, right: Any?): Any? {
                                require(left != null)
                                require(right != null)
                                val combinedFields =
                                    metadata.fields.map { field ->
                                        val fieldMonoid = field.instance as Monoid<Any?>
                                        fieldMonoid.combine(field.get(left), field.get(right))
                                    }
                                return metadata.construct(*combinedFields.toTypedArray())
                            }

                            override fun empty(): Any? {
                                val emptyFields =
                                    metadata.fields.map { field ->
                                        val fieldMonoid = field.instance as Monoid<Any?>
                                        fieldMonoid.empty()
                                    }
                                return metadata.construct(*emptyFields.toTypedArray())
                            }
                        }
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

    @Test
    fun derivesEqForGenericEitherLikeAdts() {
        val source =
            eqSource(
                definitions =
                    """
                    @Derive(Eq::class)
                    sealed interface Either<A, B>

                    @Derive(Eq::class)
                    data class Left<A, B>(val value: A) : Either<A, B>

                    @Derive(Eq::class)
                    data class Right<A, B>(val value: B) : Either<A, B>
                    """,
                mainBody =
                    """
                    println(same<Either<String, Int>>(Left<String, Int>("ok"), Left<String, Int>("ok")))
                    println(same<Either<String, Int>>(Left<String, Int>("ok"), Left<String, Int>("no")))
                    println(same<Either<String, Int>>(Left<String, Int>("ok"), Right<String, Int>(1)))
                    println(same<Either<String, Int>>(Right<String, Int>(1), Right<String, Int>(2)))
                    """,
            )

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                true
                false
                false
                false
                """.trimIndent(),
        )
    }

    @Test
    fun derivesEqForNestedSealedSubclasses() {
        val source =
            eqSource(
                definitions =
                    """
                    @Derive(Eq::class)
                    sealed interface Expr

                    @Derive(Eq::class)
                    sealed interface Binary : Expr

                    @Derive(Eq::class)
                    data class Lit(val value: Int) : Expr

                    @Derive(Eq::class)
                    data class Add(val left: Expr, val right: Expr) : Binary

                    @Derive(Eq::class)
                    data class Mul(val left: Expr, val right: Expr) : Binary

                    @Derive(Eq::class)
                    object End : Expr {
                        override fun toString(): String = "End"
                    }
                    """,
                mainBody =
                    """
                    println(same<Expr>(Add(Lit(1), End), Add(Lit(1), End)))
                    println(same<Expr>(Add(Lit(1), End), Mul(Lit(1), End)))
                    println(same<Expr>(Lit(1), End))
                    """,
            )

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
    fun derivesEqForMixedSealedCaseKinds() {
        val source =
            eqSource(
                definitions =
                    """
                    @Derive(Eq::class)
                    sealed interface Outcome

                    @Derive(Eq::class)
                    object Unknown : Outcome {
                        override fun toString(): String = "Unknown"
                    }

                    @Derive(Eq::class)
                    data object Loading : Outcome

                    @Derive(Eq::class)
                    data class Success(val value: Int) : Outcome

                    @Derive(Eq::class)
                    class Failure(val code: Int) : Outcome {
                        override fun toString(): String = "Failure(code=${'$'}code)"
                    }
                    """,
                mainBody =
                    """
                    println(same<Outcome>(Unknown, Unknown))
                    println(same<Outcome>(Loading, Loading))
                    println(same<Outcome>(Success(1), Success(1)))
                    println(same<Outcome>(Failure(7), Failure(7)))
                    println(same<Outcome>(Failure(7), Failure(8)))
                    println(same<Outcome>(Success(1), Failure(1)))
                    """,
            )

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                true
                true
                true
                true
                false
                false
                """.trimIndent(),
        )
    }

    @Test
    fun derivesJsonCodecForEnumsLikeKSerializer() {
        val source =
            jsonCodecSource(
                definitions =
                    """
                    @Serializable
                    @Derive(JsonCodec::class)
                    enum class Priority(val label: String, val code: Int) {
                        LOW("low", 1),
                        HIGH("high", 2),
                    }

                    @Serializable
                    @JvmInline
                    @Derive(JsonCodec::class)
                    value class TicketId(val value: Int)

                    @Serializable
                    @Derive(JsonCodec::class)
                    data class Ticket(val id: TicketId, val priority: Priority, val title: String)
                    """,
                mainBody =
                    """
                    val enumCodec = summon<JsonCodec<Priority>>()
                    println(enumCodec.encode(Priority.HIGH))
                    println(enumCodec.decode(JsonPrimitive("LOW")))
                    println(enumCodec.encode(Priority.HIGH) == Json.encodeToJsonElement(serializer<Priority>(), Priority.HIGH))

                    val ticketCodec = summon<JsonCodec<Ticket>>()
                    val ticket = Ticket(TicketId(7), Priority.HIGH, "ops")
                    val encoded = ticketCodec.encode(ticket)
                    val serializerEncoded = Json.encodeToJsonElement(serializer<Ticket>(), ticket)
                    println(encoded)
                    println(serializerEncoded)
                    println(encoded == serializerEncoded)
                    println(ticketCodec.decode(encoded))
                    """,
            )

        assertCompilesAndRuns(
            source = source,
            requiredPlugins = serializationPlugins,
            expectedStdout =
                """
                "HIGH"
                LOW
                true
                {"id":7,"priority":"HIGH","title":"ops"}
                {"id":7,"priority":"HIGH","title":"ops"}
                true
                Ticket(id=TicketId(value=7), priority=HIGH, title=ops)
                """.trimIndent(),
        )
    }

    @Test
    fun derivesJsonCodecForEnumsWithConstructorParametersLikeKSerializer() {
        val source =
            jsonCodecSource(
                definitions =
                    """
                    @Serializable
                    @Derive(JsonCodec::class)
                    enum class Mode(val label: String, val retries: Int) {
                        FAST("fast", 1),
                        SAFE("safe", 3),
                    }

                    @Serializable
                    @Derive(JsonCodec::class)
                    data class Settings(val mode: Mode)
                    """,
                mainBody =
                    """
                    val modeCodec = summon<JsonCodec<Mode>>()
                    println(modeCodec.encode(Mode.SAFE))
                    println(modeCodec.decode(JsonPrimitive("FAST")))
                    println(modeCodec.encode(Mode.SAFE) == Json.encodeToJsonElement(serializer<Mode>(), Mode.SAFE))

                    val settingsCodec = summon<JsonCodec<Settings>>()
                    val settings = Settings(Mode.FAST)
                    val encoded = settingsCodec.encode(settings)
                    val serializerEncoded = Json.encodeToJsonElement(serializer<Settings>(), settings)
                    println(encoded)
                    println(serializerEncoded)
                    println(encoded == serializerEncoded)
                    println(settingsCodec.decode(encoded))
                    """,
            )

        assertCompilesAndRuns(
            source = source,
            requiredPlugins = serializationPlugins,
            expectedStdout =
                """
                "SAFE"
                FAST
                true
                {"mode":"FAST"}
                {"mode":"FAST"}
                true
                Settings(mode=FAST)
                """.trimIndent(),
        )
    }

    @Test
    fun derivesJsonCodecForSealedRootsWhoseEnumCasesEncodeAsPrimitives() {
        val source =
            jsonCodecSource(
                definitions =
                    """
                    @Serializable
                    @Derive(JsonCodec::class)
                    sealed interface Status

                    @Serializable
                    @Derive(JsonCodec::class)
                    enum class Priority : Status {
                        LOW,
                        HIGH,
                    }

                    @Serializable
                    @Derive(JsonCodec::class)
                    data class Note(val message: String) : Status
                    """,
                mainBody =
                    """
                    val statusCodec = summon<JsonCodec<Status>>()

                    val priorityJson = statusCodec.encode(Priority.HIGH)
                    println(priorityJson)
                    println(statusCodec.decode(priorityJson))

                    val noteJson = statusCodec.encode(Note("ops"))
                    println(noteJson)
                    println(statusCodec.decode(noteJson))
                    """,
            )

        assertCompilesAndRuns(
            source = source,
            requiredPlugins = serializationPlugins,
            expectedStdout =
                """
                {"type":"demo.Priority","value":"HIGH"}
                HIGH
                {"type":"demo.Note","message":"ops"}
                Note(message=ops)
                """.trimIndent(),
        )
    }

    @Test
    fun derivesJsonCodecForRecursiveAdtsValueClassesAndGenericProducts() {
        val source =
            jsonCodecSource(
                definitions =
                    """
                    @JvmInline
                    @Derive(JsonCodec::class)
                    value class Name(val value: String)

                    @Derive(JsonCodec::class)
                    data class Box<A>(val value: A)

                    @Derive(JsonCodec::class)
                    sealed interface Expr

                    @Derive(JsonCodec::class)
                    data class Lit(val value: Int) : Expr

                    @Derive(JsonCodec::class)
                    data class Var(val name: Name) : Expr

                    @Derive(JsonCodec::class)
                    data class Add(val left: Expr, val right: Expr) : Expr

                    @Derive(JsonCodec::class)
                    object End : Expr {
                        override fun toString(): String = "End"
                    }
                    """,
                mainBody =
                    """
                    val exprCodec = summon<JsonCodec<Expr>>()
                    val tree: Expr = Add(Var(Name("x")), Add(Lit(1), End))
                    val encodedTree = exprCodec.encode(tree)
                    println(encodedTree)
                    println(exprCodec.decode(encodedTree))

                    val boxCodec = summon<JsonCodec<Box<Name>>>()
                    val encodedBox = boxCodec.encode(Box(Name("y")))
                    println(encodedBox)
                    println(boxCodec.decode(encodedBox))
                    """,
            )

        assertCompilesAndRuns(
            source = source,
            requiredPlugins = serializationRuntime,
            expectedStdout =
                """
                {"type":"demo.Add","left":{"type":"demo.Var","name":"x"},"right":{"type":"demo.Add","left":{"type":"demo.Lit","value":1},"right":{"type":"demo.End"}}}
                Add(left=Var(name=Name(value=x)), right=Add(left=Lit(value=1), right=End))
                {"value":"y"}
                Box(value=Name(value=y))
                """.trimIndent(),
        )
    }

    @Test
    fun derivesJsonCodecForGenericValueClassesAndEmptyProducts() {
        val source =
            jsonCodecSource(
                definitions =
                    """
                    @JvmInline
                    @Derive(JsonCodec::class)
                    value class Token<A>(val value: A)

                    @Derive(JsonCodec::class)
                    class Marker() {
                        override fun toString(): String = "Marker()"
                    }
                    """,
                mainBody =
                    """
                    val tokenCodec = summon<JsonCodec<Token<String>>>()
                    println(tokenCodec.encode(Token("abc")))
                    println(tokenCodec.decode(JsonPrimitive("abc")))

                    val markerCodec = summon<JsonCodec<Marker>>()
                    val encodedMarker = markerCodec.encode(Marker())
                    println(encodedMarker)
                    println(markerCodec.decode(encodedMarker))
                    """,
            )

        assertCompilesAndRuns(
            source = source,
            requiredPlugins = serializationRuntime,
            expectedStdout =
                """
                "abc"
                Token(value=abc)
                {}
                Marker()
                """.trimIndent(),
        )
    }

    @Test
    fun derivesJsonCodecForSumsWhoseCasesShareFieldNames() {
        val source =
            jsonCodecSource(
                definitions =
                    """
                    @Derive(JsonCodec::class)
                    sealed interface Payload

                    @Derive(JsonCodec::class)
                    data class Text(val value: String) : Payload

                    @Derive(JsonCodec::class)
                    data class Count(val value: Int) : Payload

                    @Derive(JsonCodec::class)
                    data class Envelope(val payload: Payload)
                    """,
                mainBody =
                    """
                    val payloadCodec = summon<JsonCodec<Payload>>()
                    val textJson = payloadCodec.encode(Text("hi"))
                    println(textJson)
                    println(payloadCodec.decode(textJson))

                    val countJson = payloadCodec.encode(Count(7))
                    println(countJson)
                    println(payloadCodec.decode(countJson))

                    val envelopeCodec = summon<JsonCodec<Envelope>>()
                    val envelopeJson = envelopeCodec.encode(Envelope(Count(9)))
                    println(envelopeJson)
                    println(envelopeCodec.decode(envelopeJson))
                    """,
            )

        assertCompilesAndRuns(
            source = source,
            requiredPlugins = serializationRuntime,
            expectedStdout =
                """
                {"type":"demo.Text","value":"hi"}
                Text(value=hi)
                {"type":"demo.Count","value":7}
                Count(value=7)
                {"payload":{"type":"demo.Count","value":9}}
                Envelope(payload=Count(value=9))
                """.trimIndent(),
        )
    }

    private fun jsonCodecSource(
        definitions: String,
        mainBody: String,
    ): String =
        """
        package demo

        import kotlinx.serialization.json.JsonElement
        import kotlinx.serialization.json.JsonObject
        import kotlinx.serialization.json.JsonPrimitive
        import kotlinx.serialization.json.Json
        import kotlinx.serialization.json.buildJsonObject
        import kotlinx.serialization.json.int
        import kotlinx.serialization.json.jsonObject
        import kotlinx.serialization.json.jsonPrimitive
        import kotlinx.serialization.Serializable
        import kotlinx.serialization.serializer
        import one.wabbit.typeclass.Derive
        import one.wabbit.typeclass.EnumTypeclassMetadata
        import one.wabbit.typeclass.Instance
        import one.wabbit.typeclass.ProductTypeclassMetadata
        import one.wabbit.typeclass.Typeclass
        import one.wabbit.typeclass.TypeclassDeriver
        import one.wabbit.typeclass.get
        import one.wabbit.typeclass.matches
        import one.wabbit.typeclass.summon

        enum class JsonCodecRepresentation {
            INLINE,
            OBJECT,
        }

        @Typeclass
        interface JsonCodec<A> {
            val representation: JsonCodecRepresentation

            fun encode(value: A): JsonElement

            fun decode(element: JsonElement): A

            companion object : TypeclassDeriver {
                override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                    object : JsonCodec<Any?> {
                        override val representation: JsonCodecRepresentation =
                            if (metadata.isValueClass) {
                                JsonCodecRepresentation.INLINE
                            } else {
                                JsonCodecRepresentation.OBJECT
                            }

                        override fun encode(value: Any?): JsonElement {
                            require(value != null)
                            if (metadata.isValueClass) {
                                val field = metadata.fields.single()
                                val fieldCodec = field.instance as JsonCodec<Any?>
                                return fieldCodec.encode(field.get(value))
                            }
                            return buildJsonObject {
                                metadata.fields.forEach { field ->
                                    val fieldCodec = field.instance as JsonCodec<Any?>
                                    put(field.name, fieldCodec.encode(field.get(value)))
                                }
                            }
                        }

                        override fun decode(element: JsonElement): Any? {
                            if (metadata.isValueClass) {
                                val field = metadata.fields.single()
                                val fieldCodec = field.instance as JsonCodec<Any?>
                                return metadata.construct(fieldCodec.decode(element))
                            }
                            val jsonObject = element.jsonObject
                            val arguments =
                                metadata.fields.map { field ->
                                    val fieldCodec = field.instance as JsonCodec<Any?>
                                    fieldCodec.decode(jsonObject.getValue(field.name))
                                }
                            return metadata.construct(arguments)
                        }
                    }

                override fun deriveSum(metadata: one.wabbit.typeclass.SumTypeclassMetadata): Any =
                    object : JsonCodec<Any?> {
                        override val representation: JsonCodecRepresentation = JsonCodecRepresentation.OBJECT

                        override fun encode(value: Any?): JsonElement {
                            require(value != null)
                            val selected = metadata.cases.single { candidate -> candidate.matches(value) }
                            val caseCodec = selected.instance as JsonCodec<Any?>
                            val encoded = caseCodec.encode(value)
                            return when (encoded) {
                                is JsonObject ->
                                    buildJsonObject {
                                        put("type", JsonPrimitive(selected.typeName))
                                        encoded.forEach { (key, fieldValue) -> put(key, fieldValue) }
                                    }

                                else ->
                                    buildJsonObject {
                                        put("type", JsonPrimitive(selected.typeName))
                                        put("value", encoded)
                                    }
                            }
                        }

                        override fun decode(element: JsonElement): Any? {
                            val jsonObject = element.jsonObject
                            val selected =
                                metadata.cases.single { candidate ->
                                    candidate.typeName == jsonObject.getValue("type").jsonPrimitive.content
                                }
                            val caseCodec = selected.instance as JsonCodec<Any?>
                            val payload =
                                when (caseCodec.representation) {
                                    JsonCodecRepresentation.INLINE -> jsonObject.getValue("value")
                                    JsonCodecRepresentation.OBJECT -> JsonObject(jsonObject.filterKeys { key -> key != "type" })
                                }
                            return caseCodec.decode(payload)
                        }
                    }

                override fun deriveEnum(metadata: EnumTypeclassMetadata): Any =
                    object : JsonCodec<Any?> {
                        override val representation: JsonCodecRepresentation = JsonCodecRepresentation.INLINE

                        override fun encode(value: Any?): JsonElement {
                            require(value != null)
                            return JsonPrimitive(metadata.entryOf(value).name)
                        }

                        override fun decode(element: JsonElement): Any? = metadata.construct(element.jsonPrimitive.content)
                    }
            }
        }

        @Instance
        object IntJsonCodec : JsonCodec<Int> {
            override val representation: JsonCodecRepresentation = JsonCodecRepresentation.INLINE

            override fun encode(value: Int): JsonElement = JsonPrimitive(value)

            override fun decode(element: JsonElement): Int = element.jsonPrimitive.int
        }

        @Instance
        object StringJsonCodec : JsonCodec<String> {
            override val representation: JsonCodecRepresentation = JsonCodecRepresentation.INLINE

            override fun encode(value: String): JsonElement = JsonPrimitive(value)

            override fun decode(element: JsonElement): String = element.jsonPrimitive.content
        }

        ${definitions.trimIndent()}

        fun main() {
        ${mainBody.trimIndent().prependIndent("    ")}
        }
        """.trimIndent()

    private fun eqSource(
        definitions: String,
        mainBody: String,
    ): String =
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

        ${definitions.trimIndent()}

        context(eq: Eq<A>)
        fun <A> same(left: A, right: A): Boolean = eq.eq(left, right)

        fun main() {
        ${mainBody.trimIndent().prependIndent("    ")}
        }
        """.trimIndent()
}
