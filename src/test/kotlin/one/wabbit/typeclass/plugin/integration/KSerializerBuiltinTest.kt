package one.wabbit.typeclass.plugin.integration

import kotlin.test.Test

class KSerializerBuiltinTest : IntegrationTestSupport() {
    @Test fun treatsKSerializerAsBuiltinTypeclassWhenFlagEnabled() {
        val source =
            """
            package demo

            import kotlinx.serialization.KSerializer
            import kotlinx.serialization.Serializable

            @Serializable
            data class User(val name: String)

            context(serializer: KSerializer<T>)
            fun <T> serialName(): String = serializer.descriptor.serialName

            fun main() {
                println(serialName<User>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "demo.User",
            pluginOptions = listOf("builtinKSerializerTypeclass=enabled"),
        )
    }

    @Test fun reifiedHelpersCanSummonSyntheticKSerializers() {
        val source =
            """
            package demo

            import kotlinx.serialization.KSerializer
            import kotlinx.serialization.Serializable
            import one.wabbit.typeclass.summon

            @Serializable
            data class User(val name: String)

            context(serializer: KSerializer<T>)
            fun <T> contextualSerialName(): String = serializer.descriptor.serialName

            inline fun <reified T> reifiedSerialName(): String =
                summon<KSerializer<T>>().descriptor.serialName

            fun main() {
                println(contextualSerialName<User>())
                println(reifiedSerialName<User>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                demo.User
                demo.User
                """.trimIndent(),
            pluginOptions = listOf("builtinKSerializerTypeclass=enabled"),
        )
    }

    @Test fun rejectsSyntheticKSerializerEvidenceForNonSerializableTypes() {
        val source =
            """
            package demo

            import kotlinx.serialization.KSerializer

            data class User(val name: String)

            context(serializer: KSerializer<T>)
            fun <T> serialName(): String = serializer.descriptor.serialName

            fun main() {
                println(serialName<User>()) // ERROR no generated serializer exists for User
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("serializer", "user"),
            pluginOptions = listOf("builtinKSerializerTypeclass=enabled"),
        )
    }

    @Test fun materializesNestedGeneratedKSerializersFromTypeArguments() {
        val source =
            """
            package demo

            import kotlinx.serialization.KSerializer
            import kotlinx.serialization.Serializable
            import one.wabbit.typeclass.summon

            @Serializable
            data class Box<T>(val value: T)

            inline fun <reified T> boxSerialName(): String =
                summon<KSerializer<Box<T>>>().descriptor.serialName

            fun main() {
                println(boxSerialName<Int>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "demo.Box",
            pluginOptions = listOf("builtinKSerializerTypeclass=enabled"),
        )
    }

    @Test fun reifiedSyntheticKSerializerMatchesOfficialSerializerApi() {
        val source =
            """
            package demo

            import kotlinx.serialization.KSerializer
            import kotlinx.serialization.Serializable
            import kotlinx.serialization.serializer
            import one.wabbit.typeclass.summon

            @Serializable
            data class Box<T>(val value: T)

            inline fun <reified T> sameSerializerMetadata(): Boolean {
                val builtin = summon<KSerializer<T>>()
                val official = serializer<T>()
                return builtin.descriptor.serialName == official.descriptor.serialName
            }

            fun main() {
                println(sameSerializerMetadata<Box<String?>>())
                println(sameSerializerMetadata<List<Int?>>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                true
                true
                """.trimIndent(),
            pluginOptions = listOf("builtinKSerializerTypeclass=enabled"),
        )
    }

    @Test
    fun classLevelSerializableWithCustomSerializerMatchesOfficialSerializerApi() {
        val source =
            """
            package demo

            import kotlinx.serialization.KSerializer
            import kotlinx.serialization.Serializable
            import kotlinx.serialization.descriptors.PrimitiveKind
            import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
            import kotlinx.serialization.descriptors.SerialDescriptor
            import kotlinx.serialization.encoding.Decoder
            import kotlinx.serialization.encoding.Encoder
            import kotlinx.serialization.serializer
            import one.wabbit.typeclass.summon

            @Serializable(with = UserIdAsStringSerializer::class)
            data class UserId(val value: Int)

            object UserIdAsStringSerializer : KSerializer<UserId> {
                override val descriptor: SerialDescriptor =
                    PrimitiveSerialDescriptor("demo.UserIdAsString", PrimitiveKind.STRING)

                override fun serialize(encoder: Encoder, value: UserId) = error("unused")

                override fun deserialize(decoder: Decoder): UserId = error("unused")
            }

            inline fun <reified T> sameSerializerRuntimeType(): Boolean {
                val builtin = summon<KSerializer<T>>()
                val official = serializer<T>()
                return builtin::class == official::class
            }

            fun main() {
                println(summon<KSerializer<UserId>>() === UserIdAsStringSerializer)
                println(sameSerializerRuntimeType<UserId>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                true
                true
                """.trimIndent(),
            pluginOptions = listOf("builtinKSerializerTypeclass=enabled"),
        )
    }

    @Test
    fun propertyLevelSerializableWithCustomSerializerMatchesOfficialContainerSerializerOnly() {
        val source =
            """
            package demo

            import kotlinx.serialization.KSerializer
            import kotlinx.serialization.Serializable
            import kotlinx.serialization.descriptors.PrimitiveKind
            import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
            import kotlinx.serialization.descriptors.SerialDescriptor
            import kotlinx.serialization.encoding.Decoder
            import kotlinx.serialization.encoding.Encoder
            import kotlinx.serialization.serializer
            import one.wabbit.typeclass.summon

            object IntAsStringSerializer : KSerializer<Int> {
                override val descriptor: SerialDescriptor =
                    PrimitiveSerialDescriptor("demo.IntAsString", PrimitiveKind.STRING)

                override fun serialize(encoder: Encoder, value: Int) = error("unused")

                override fun deserialize(decoder: Decoder): Int = error("unused")
            }

            @Serializable
            data class Wrapper(
                @Serializable(with = IntAsStringSerializer::class)
                val value: Int,
            )

            inline fun <reified T> sameContainerSerializerRuntimeType(): Boolean {
                val builtin = summon<KSerializer<T>>()
                val official = serializer<T>()
                return builtin::class == official::class
            }

            fun main() {
                val wrapperSerializer = summon<KSerializer<Wrapper>>()
                println(wrapperSerializer.descriptor.getElementDescriptor(0).serialName == IntAsStringSerializer.descriptor.serialName)
                println(summon<KSerializer<Int>>() !== IntAsStringSerializer)
                println(sameContainerSerializerRuntimeType<Wrapper>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                true
                true
                true
                """.trimIndent(),
            pluginOptions = listOf("builtinKSerializerTypeclass=enabled"),
        )
    }

    @Test
    fun annotatedSerializableTypealiasMatchesOfficialSerializerApi() {
        val source =
            """
            package demo

            import kotlinx.serialization.KSerializer
            import kotlinx.serialization.Serializable
            import kotlinx.serialization.descriptors.PrimitiveKind
            import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
            import kotlinx.serialization.descriptors.SerialDescriptor
            import kotlinx.serialization.encoding.Decoder
            import kotlinx.serialization.encoding.Encoder
            import kotlinx.serialization.serializer
            import one.wabbit.typeclass.summon

            object IntAsStringSerializer : KSerializer<Int> {
                override val descriptor: SerialDescriptor =
                    PrimitiveSerialDescriptor("demo.IntAsString", PrimitiveKind.STRING)

                override fun serialize(encoder: Encoder, value: Int) = error("unused")

                override fun deserialize(decoder: Decoder): Int = error("unused")
            }

            typealias FancyInt = @Serializable(with = IntAsStringSerializer::class) Int

            inline fun <reified T> sameSerializerRuntimeType(): Boolean {
                val builtin = summon<KSerializer<T>>()
                val official = serializer<T>()
                return builtin::class == official::class
            }

            fun main() {
                println(sameSerializerRuntimeType<FancyInt>())
                println(sameSerializerRuntimeType<FancyInt?>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                true
                true
                """.trimIndent(),
            pluginOptions = listOf("builtinKSerializerTypeclass=enabled"),
        )
    }

    @Test
    fun containersUsingAnnotatedSerializableTypealiasesMatchOfficialSerializerApi() {
        val source =
            """
            package demo

            import kotlinx.serialization.KSerializer
            import kotlinx.serialization.Serializable
            import kotlinx.serialization.descriptors.PrimitiveKind
            import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
            import kotlinx.serialization.descriptors.SerialDescriptor
            import kotlinx.serialization.encoding.Decoder
            import kotlinx.serialization.encoding.Encoder
            import kotlinx.serialization.serializer
            import one.wabbit.typeclass.summon

            object IntAsStringSerializer : KSerializer<Int> {
                override val descriptor: SerialDescriptor =
                    PrimitiveSerialDescriptor("demo.IntAsString", PrimitiveKind.STRING)

                override fun serialize(encoder: Encoder, value: Int) = error("unused")

                override fun deserialize(decoder: Decoder): Int = error("unused")
            }

            typealias FancyInt = @Serializable(with = IntAsStringSerializer::class) Int

            @Serializable
            data class Wrapper(val value: FancyInt)

            inline fun <reified T> sameContainerSerializerRuntimeType(): Boolean {
                val builtin = summon<KSerializer<T>>()
                val official = serializer<T>()
                return builtin::class == official::class
            }

            fun main() {
                val builtin = summon<KSerializer<Wrapper>>()
                val official = serializer<Wrapper>()
                println(sameContainerSerializerRuntimeType<Wrapper>())
                println(builtin.descriptor.getElementDescriptor(0).serialName ==
                    official.descriptor.getElementDescriptor(0).serialName)
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                true
                true
                """.trimIndent(),
            pluginOptions = listOf("builtinKSerializerTypeclass=enabled"),
        )
    }
}
