package one.wabbit.typeclass.plugin.integration

import kotlin.test.Test

class FirImplicitInstanceTypeCrashTest : IntegrationTestSupport() {
    @Test
    fun resolvesImplicitlyTypedCompanionInstancesWithoutFirCrash() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Codec<A> {
                fun encode(value: A): String
            }

            @Typeclass
            interface EntityComponentType<Type> {
                val codec: Codec<Type>
            }

            class Entity

            class Entities {
                context(type: EntityComponentType<Type>)
                fun <Type> updateComponent(entity: Entity, update: (Type?) -> Type): Type {
                    val value = update(null)
                    return value
                }
            }

            data class AlcoholIntoxication(val level: Double) {
                companion object {
                    @Instance
                    val codec =
                        object : Codec<AlcoholIntoxication> {
                            override fun encode(value: AlcoholIntoxication): String = value.level.toString()
                        }

                    @Instance
                    val entityComponentType =
                        object : EntityComponentType<AlcoholIntoxication> {
                            override val codec = AlcoholIntoxication.codec
                        }
                }
            }

            fun main() {
                val entities = Entities()
                val entity = Entity()
                with(AlcoholIntoxication.entityComponentType) {
                    val updated =
                        entities.updateComponent<AlcoholIntoxication>(entity) {
                            if (it == null) AlcoholIntoxication(1.5) else it.copy(level = it.level + 1.0)
                        }
                    println(updated.level)
                    println(codec.encode(updated))
                }
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                1.5
                1.5
                """.trimIndent(),
        )
    }
}
