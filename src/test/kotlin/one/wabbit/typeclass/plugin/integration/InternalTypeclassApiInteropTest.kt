package one.wabbit.typeclass.plugin.integration

import kotlin.test.Test

class InternalTypeclassApiInteropTest : IntegrationTestSupport() {
    @Test
    fun builtinProofSummoningDoesNotRequireInternalOptIn() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.KnownType
            import one.wabbit.typeclass.NotNullable
            import one.wabbit.typeclass.NotSame
            import one.wabbit.typeclass.Nullable
            import one.wabbit.typeclass.Same
            import one.wabbit.typeclass.SameTypeConstructor
            import one.wabbit.typeclass.StrictSubtype
            import one.wabbit.typeclass.Subtype
            import one.wabbit.typeclass.TypeId
            import one.wabbit.typeclass.summon

            typealias Age = Int

            open class Animal
            class Dog : Animal()

            fun main() {
                val same = summon<Same<Int, Age>>()
                val notSame = summon<NotSame<Int, String>>()
                val subtype = summon<Subtype<Dog, Animal>>()
                val strict = summon<StrictSubtype<Dog, Animal>>()
                val sameCtor = summon<SameTypeConstructor<List<Int>, List<String>>>()
                val known = summon<KnownType<List<String?>>>()
                val nullable = summon<Nullable<String?>>()
                val notNullable = summon<NotNullable<String>>()
                val typeId = summon<TypeId<List<String?>>>()

                println(same.flip().coerce(1))
                println(notSame.flip() != null)
                println(subtype.coerce(Dog()) is Animal)
                println(strict.toNotSame() != null)
                println(sameCtor.flip() != null)
                println("List" in known.kType.toString() && "String" in known.kType.toString())
                println(nullable.nullValue() == null)
                println(notNullable != null)
                println("List" in typeId.canonicalName && "String?" in typeId.canonicalName)
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                1
                true
                true
                true
                true
                true
                true
                true
                true
                """.trimIndent(),
        )
    }
}
