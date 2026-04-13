// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.typeclass.plugin.integration.proofs

import kotlin.test.Test
import one.wabbit.typeclass.plugin.integration.HarnessDependency
import one.wabbit.typeclass.plugin.integration.IntegrationTestSupport

class AnnotationProofTest : IntegrationTestSupport() {
    @Test
    fun materializesSourceRetentionAnnotationProofWithinSameCompilation() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.HasAnnotation
            import one.wabbit.typeclass.summon

            @Retention(AnnotationRetention.SOURCE)
            annotation class Info(val label: String)

            @Info("box")
            class Box

            fun main() {
                val proof = summon<HasAnnotation<Box, Info>>()
                println(proof.annotation.label)
            }
            """
                .trimIndent()

        assertCompilesAndRuns(source = source, expectedStdout = "box")
    }

    @Test
    fun materializesSingleAnnotationProofAndPreservesPayload() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.HasAnnotation
            import one.wabbit.typeclass.HasAnnotations
            import one.wabbit.typeclass.summon

            @Retention(AnnotationRetention.BINARY)
            annotation class Info(val label: String, val flags: Array<String>)

            @Info(label = "box", flags = ["a", "b"])
            class Box

            fun main() {
                val single = summon<HasAnnotation<Box, Info>>()
                val many = summon<HasAnnotations<Box, Info>>()

                println(single.annotation.label)
                println(single.annotation.flags.joinToString("|"))
                println(many.annotations.single().label)
            }
            """
                .trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                box
                a|b
                box
                """
                    .trimIndent(),
        )
    }

    @Test
    fun sourceRetentionAnnotationProofDoesNotSurviveSeparateCompilation() {
        val dependency =
            HarnessDependency(
                name = "dep-source-annotation",
                sources =
                    mapOf(
                        "dep/Api.kt" to
                            """
                            package dep

                            @Retention(AnnotationRetention.SOURCE)
                            annotation class Info(val label: String)

                            @Info("box")
                            class Box
                            """
                                .trimIndent()
                    ),
            )
        val source =
            """
            package demo

            import dep.Box
            import dep.Info
            import one.wabbit.typeclass.HasAnnotation
            import one.wabbit.typeclass.summon

            fun main() {
                println(summon<HasAnnotation<Box, Info>>()) // E:TC_NO_CONTEXT_ARGUMENT Box does not carry Info in dependency binaries
            }
            """
                .trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedNoContextArgument()),
            dependencies = listOf(dependency),
        )
    }

    @Test
    fun binaryRetentionAnnotationProofSurvivesSeparateCompilation() {
        val dependency =
            HarnessDependency(
                name = "dep-binary-annotation",
                sources =
                    mapOf(
                        "dep/Api.kt" to
                            """
                            package dep

                            @Retention(AnnotationRetention.BINARY)
                            annotation class Info(val label: String)

                            @Info("box")
                            class Box
                            """
                                .trimIndent()
                    ),
            )
        val source =
            """
            package demo

            import dep.Box
            import dep.Info
            import one.wabbit.typeclass.HasAnnotation
            import one.wabbit.typeclass.summon

            fun main() {
                val proof = summon<HasAnnotation<Box, Info>>()
                println(proof.annotation.label)
            }
            """
                .trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "box",
            dependencies = listOf(dependency),
        )
    }

    @Test
    fun runtimeRetentionAnnotationProofSurvivesSeparateCompilation() {
        val dependency =
            HarnessDependency(
                name = "dep-runtime-annotation",
                sources =
                    mapOf(
                        "dep/Api.kt" to
                            """
                            package dep

                            @Retention(AnnotationRetention.RUNTIME)
                            annotation class Info(val label: String)

                            @Info("box")
                            class Box
                            """
                                .trimIndent()
                    ),
            )
        val source =
            """
            package demo

            import dep.Box
            import dep.Info
            import one.wabbit.typeclass.HasAnnotation
            import one.wabbit.typeclass.summon

            fun main() {
                val proof = summon<HasAnnotation<Box, Info>>()
                println(proof.annotation.label)
            }
            """
                .trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "box",
            dependencies = listOf(dependency),
        )
    }

    @Test
    fun materializesRepeatableAnnotationListInSourceOrder() {
        val source =
            """
            package demo

            import kotlin.annotation.Repeatable
            import one.wabbit.typeclass.HasAnnotations
            import one.wabbit.typeclass.summon

            @Repeatable
            @Retention(AnnotationRetention.BINARY)
            annotation class Tag(val value: String)

            @Tag("alpha")
            @Tag("beta")
            class Tagged

            fun main() {
                val proof = summon<HasAnnotations<Tagged, Tag>>()
                println(proof.annotations.joinToString("|") { it.value })
            }
            """
                .trimIndent()

        assertCompilesAndRuns(source = source, expectedStdout = "alpha|beta")
    }

    @Test
    fun rejectsSingleAnnotationProofWhenMultipleRepeatableAnnotationsExist() {
        val source =
            """
            package demo

            import kotlin.annotation.Repeatable
            import one.wabbit.typeclass.HasAnnotation
            import one.wabbit.typeclass.summon

            @Repeatable
            @Retention(AnnotationRetention.BINARY)
            annotation class Tag(val value: String)

            @Tag("alpha")
            @Tag("beta")
            class Tagged

            fun main() {
                println(summon<HasAnnotation<Tagged, Tag>>()) // E:TC_NO_CONTEXT_ARGUMENT Tagged has more than one Tag annotation
            }
            """
                .trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedNoContextArgument()),
        )
    }

    @Test
    fun rejectsProofWhenTargetLacksRequestedAnnotation() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.HasAnnotation
            import one.wabbit.typeclass.summon

            @Retention(AnnotationRetention.BINARY)
            annotation class Info(val label: String)

            class Plain

            fun main() {
                println(summon<HasAnnotation<Plain, Info>>()) // E:TC_NO_CONTEXT_ARGUMENT Plain does not carry Info
            }
            """
                .trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedNoContextArgument()),
        )
    }

    @Test
    fun annotationProofCanActAsPrerequisiteForOrdinaryRuleSearch() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.HasAnnotation
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Retention(AnnotationRetention.BINARY)
            annotation class Info(val label: String)

            @Info("box")
            class Box

            @Typeclass
            interface Labelled<A> {
                fun label(): String
            }

            @Instance
            context(hasInfo: HasAnnotation<A, Info>)
            fun <A> labelledFromAnnotation(): Labelled<A> =
                object : Labelled<A> {
                    override fun label(): String = hasInfo.annotation.label
                }

            context(labelled: Labelled<A>)
            fun <A> render(): String = labelled.label()

            fun main() {
                println(render<Box>())
            }
            """
                .trimIndent()

        assertCompilesAndRuns(source = source, expectedStdout = "box")
    }
}
