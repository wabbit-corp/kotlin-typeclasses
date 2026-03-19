package one.wabbit.typeclass.plugin.integration

import org.jetbrains.kotlin.cli.common.ExitCode
import org.junit.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals

class TypeclassContractTest : IntegrationTestSupport() {

    @Test fun resolvesAssociatedInstancesThroughTypeArguments() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            data class Curse(val soulbound: Boolean) {
                companion object {
                    @Instance
                    object CurseListShow : Show<List<Curse>> {
                        override fun show(value: List<Curse>): String =
                            value.joinToString(prefix = "[", postfix = "]") { curse ->
                                if (curse.soulbound) "bound" else "free"
                            }
                    }
                }
            }

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(listOf(Curse(true), Curse(false))))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "[bound, free]",
        )
    }

    @Test fun ignoresInapplicableAssociatedSealedSupertypeCandidatesWhenResolvingSubtypeSpecificInstances() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            @Typeclass
            interface Show<A> {
                fun label(): String
            }

            @Typeclass
            interface Impossible<A>

            sealed interface Animal {
                data class Dog(val id: Int) : Animal {
                    companion object {
                        @Instance
                        object DogShow : Show<Dog> {
                            override fun label(): String = "dog"
                        }
                    }
                }

                companion object {
                    @Instance
                    context(_: Impossible<Nothing>)
                    fun hiddenDogShow(): Show<Dog> =
                        object : Show<Dog> {
                            override fun label(): String = "hidden"
                        }
                }
            }

            context(_: Show<A>)
            fun <A> which(): String = summon<Show<A>>().label()

            fun main() {
                println(which<Animal.Dog>())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "dog",
        )
    }

    @Test fun resolvesAnonymousObjectsAndFunctionsThatCaptureLocalEvidence() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance
            object IntShow : Show<Int> {
                override fun show(value: Int): String = "int:${'$'}value"
            }

            context(show: Show<Int>)
            fun capture(): Pair<String, String> {
                val anonymous =
                    object {
                        fun render(): String = show.show(1)
                    }
                val function = fun(value: Int): String = show.show(value)
                return anonymous.render() to function(2)
            }

            fun main() {
                val (first, second) = capture()
                println(first)
                println(second)
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                int:1
                int:2
                """.trimIndent(),
        )
    }

    @Test fun exposesMultipleTypeclassInstancesFromOneObject() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Typeclass
            interface Eq<A> {
                fun eq(left: A, right: A): Boolean
            }

            @Instance
            object IntInstances : Show<Int>, Eq<Int> {
                override fun show(value: Int): String = "int:${'$'}value"

                override fun eq(left: Int, right: Int): Boolean = left == right
            }

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            context(_: Eq<A>)
            fun <A> same(value: A): Boolean = summon<Eq<A>>().eq(value, value)

            fun main() {
                println(render(1))
                println(same(1))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                int:1
                true
                """.trimIndent(),
        )
    }

    @Test fun mixesPreservedAndSynthesizedTypeclassArgumentsOnOneCall() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Eq<A> {
                fun eq(left: A, right: A): Boolean
            }

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance
            object IntEq : Eq<Int> {
                override fun eq(left: Int, right: Int): Boolean = left == right
            }

            @Instance
            context(eq: Eq<A>)
            fun <A> showFromEq(): Show<A> =
                object : Show<A> {
                    override fun show(value: A): String =
                        if (eq.eq(value, value)) "stable" else "unstable"
                }

            context(local: Eq<String>, shown: Show<Int>)
            fun use(): String = local.eq("x", "x").toString() + ":" + shown.show(1)

            fun main() {
                val localEq =
                    object : Eq<String> {
                        override fun eq(left: String, right: String): Boolean = left == right
                    }

                context(localEq) {
                    println(use())
                }
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "true:stable",
        )
    }

    @Test fun doesNotLeakPrivateCompanionInstancesAcrossFiles() {
        val sources =
            mapOf(
                "Box.kt" to
                    """
                    package demo

                    import one.wabbit.typeclass.Instance
                    import one.wabbit.typeclass.Typeclass

                    @Typeclass
                    interface Show<A> {
                        fun show(value: A): String
                    }

                    data class Box(val value: Int) {
                        companion object {
                            @Instance
                            private object HiddenBoxShow : Show<Box> {
                                override fun show(value: Box): String = "hidden:${'$'}{value.value}"
                            }
                        }
                    }

                    context(show: Show<A>)
                    fun <A> render(value: A): String = show.show(value)
                    """.trimIndent(),
                "Main.kt" to
                    """
                    package demo

                    fun main() {
                        println(render(Box(1))) // E:TC_NO_CONTEXT_ARGUMENT private companion @Instance declarations should not leak across files
                    }
                    """.trimIndent(),
            )

        assertDoesNotCompile(
            sources = sources,
            expectedMessages = listOf("no context argument"),
            expectedDiagnostics = listOf(expectedErrorContaining("no context argument", "show")),
        )
    }

    @Test fun reportsAmbiguityForNullableSpecificAndGenericNullEvidence() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun label(): String
            }

            @Instance
            object StringShow : Show<String> {
                override fun label(): String = "string"
            }

            @Instance
            object NullableStringShow : Show<String?> {
                override fun label(): String = "nullable-string"
            }

            @Instance
            context(show: Show<A>)
            fun <A> nullableShow(): Show<A?> =
                object : Show<A?> {
                    override fun label(): String = "generic-" + show.label()
                }

            context(show: Show<A>)
            fun <A> render(value: A): String = show.label()

            fun main() {
                println(render<String?>(null)) // E:TC_AMBIGUOUS_INSTANCE ambiguous Show<String?> resolution
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("ambiguous typeclass instance"),
            expectedDiagnostics = listOf(expectedAmbiguousInstance("show")),
        )
    }

    @Test fun rejectsNonTypeclassIntermediateSupertypesThatExtendTypeclasses() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            abstract class IntShowBase : Show<Int> // non-typeclass intermediate supertypes should not extend typeclasses

            @Instance
            object IntShow : IntShowBase() { // E:TC_INVALID_INSTANCE_DECL non-typeclass intermediate supertypes should not extend typeclasses
                override fun show(value: Int): String = "int:${'$'}value"
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("typeclass"),
            expectedDiagnostics = listOf(expectedErrorContaining("typeclass")),
        )
    }

    @Test fun allowsIntermediateTypeclassSupertypesThatExtendTypeclasses() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Typeclass
            interface IntShowBase : Show<Int>

            @Instance
            object IntShow : IntShowBase {
                override fun show(value: Int): String = "int:${'$'}value"
            }

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(1))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "int:1",
        )
    }

    @Test fun resolvesIntermediateTypeclassHierarchiesFromGroupInstances() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            @Typeclass
            interface Semigroup<A> {
                fun combine(left: A, right: A): A
            }

            @Typeclass
            interface Monoid<A> : Semigroup<A> {
                fun empty(): A
            }

            @Typeclass
            interface Group<A> : Monoid<A> {
                fun invert(value: A): A
            }

            @Instance
            object IntGroup : Group<Int> {
                override fun combine(left: Int, right: Int): Int = left + right

                override fun empty(): Int = 0

                override fun invert(value: Int): Int = -value
            }

            context(monoid: Monoid<Int>)
            fun renderFromMonoid(value: Int): Int = monoid.combine(monoid.empty(), value)

            context(group: Group<Int>)
            fun localMonoidSum(value: Int): Int =
                summon<Monoid<Int>>().combine(summon<Monoid<Int>>().empty(), value)

            context(group: Group<Int>)
            fun localSemigroupDouble(value: Int): Int =
                summon<Semigroup<Int>>().combine(value, value)

            fun main() {
                println(renderFromMonoid(4))
                context(IntGroup) {
                    println(localMonoidSum(5))
                    println(localSemigroupDouble(6))
                }
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                4
                5
                12
                """.trimIndent(),
        )
    }

    @Test fun reportsAmbiguousInheritedIntermediateTypeclassInstances() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Semigroup<A> {
                fun combine(left: A, right: A): A
            }

            @Typeclass
            interface Monoid<A> : Semigroup<A> {
                fun empty(): A
            }

            @Typeclass
            interface Group<A> : Monoid<A> {
                fun invert(value: A): A
            }

            @Instance
            object IntMonoid : Monoid<Int> {
                override fun combine(left: Int, right: Int): Int = left + right

                override fun empty(): Int = 0
            }

            @Instance
            object IntGroup : Group<Int> {
                override fun combine(left: Int, right: Int): Int = left + right

                override fun empty(): Int = 0

                override fun invert(value: Int): Int = -value
            }

            context(monoid: Monoid<Int>)
            fun use(): Int = monoid.empty()

            fun main() {
                println(use())
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("no context argument", "monoid"),
            expectedDiagnostics = listOf(expectedAmbiguousOrNoContext("monoid")),
        )
    }

    @Test fun propagatesSuperclassStyleEvidenceFromOrdToEq() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Eq<A> {
                fun eq(left: A, right: A): Boolean
            }

            @Typeclass
            interface Ord<A> : Eq<A> {
                fun compare(left: A, right: A): Int
            }

            @Instance
            object IntOrd : Ord<Int> {
                override fun eq(left: Int, right: Int): Boolean = left == right

                override fun compare(left: Int, right: Int): Int = left.compareTo(right)
            }

            context(eq: Eq<A>)
            fun <A> same(value: A): Boolean = eq.eq(value, value)

            fun main() {
                println(same(1))

                val localOrd =
                    object : Ord<Int> {
                        override fun eq(left: Int, right: Int): Boolean = left == right

                        override fun compare(left: Int, right: Int): Int = left.compareTo(right)
                    }

                context(localOrd) {
                    println(same(2))
                }
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

    @Test fun rejectsInstanceRulesWithTypeParametersOnlyInPrerequisites() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance // E:TC_INVALID_INSTANCE_DECL instance type parameter B only appears in prerequisites
            context(_: Show<B>)
            fun <A, B> bad(): Show<List<A>> =
                object : Show<List<A>> {
                    override fun show(value: List<A>): String = value.toString()
                }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("type parameter", "prerequisite"),
            expectedDiagnostics = listOf(expectedErrorContaining("type parameter", "prerequisite")),
        )
    }

    @Test fun rejectsDirectSelfRecursiveInstanceRulesAtDeclarationSite() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            data class Box<A>(val value: A)

            @Typeclass
            interface Show<A> {
                fun label(): String
            }

            @Instance // E:TC_INVALID_INSTANCE_DECL direct self-recursive instance rule Show<Box<A>> => Show<Box<A>>
            context(_: Show<Box<A>>)
            fun <A> recursiveBoxShow(): Show<Box<A>> =
                object : Show<Box<A>> {
                    override fun label(): String = "recursive"
                }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("recursive"),
            expectedDiagnostics = listOf(expectedErrorContaining("recursive")),
        )
    }

    @Test fun localExactEvidenceOverridesDerivedGlobalEvidence() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            @Typeclass
            interface Eq<A> {
                fun label(): String
            }

            @Instance
            object StringEq : Eq<String> {
                override fun label(): String = "string"
            }

            @Instance
            context(eq: Eq<A>)
            fun <A> nullableEq(): Eq<A?> =
                object : Eq<A?> {
                    override fun label(): String = "nullable-" + eq.label()
                }

            context(_: Eq<A>)
            fun <A> which(): String = summon<Eq<A>>().label()

            fun main() {
                val localNullable =
                    object : Eq<String?> {
                        override fun label(): String = "local-nullable"
                    }

                context(localNullable) {
                    println(which<String?>())
                }
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "local-nullable",
        )
    }

    @Test fun supportsNullaryTypeclasses() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface FeatureFlag {
                fun enabled(): Boolean
            }

            @Instance
            object EnabledFlag : FeatureFlag {
                override fun enabled(): Boolean = true
            }

            context(flag: FeatureFlag)
            fun check(): Boolean = flag.enabled()

            fun main() {
                println(check())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "true",
        )
    }

    @Test fun reportsDuplicateNullaryTypeclassInstancesAcrossFiles() {
        val sources =
            mapOf(
                "Flag.kt" to
                    """
                    package demo

                    import one.wabbit.typeclass.Instance
                    import one.wabbit.typeclass.Typeclass

                    @Typeclass
                    interface FeatureFlag {
                        fun enabled(): Boolean
                    }

                    @Instance
                    object EnabledFlag : FeatureFlag {
                        override fun enabled(): Boolean = true
                    }

                    context(flag: FeatureFlag)
                    fun check(): Boolean = flag.enabled()
                    """.trimIndent(),
                "Other.kt" to
                    """
                    package demo

                    import one.wabbit.typeclass.Instance

                    @Instance
                    object AnotherEnabledFlag : FeatureFlag { // duplicate nullary instance declaration
                        override fun enabled(): Boolean = false
                    }
                    """.trimIndent(),
                "Main.kt" to
                    """
                    package demo

                    fun main() {
                        println(check()) // E:TC_NO_CONTEXT_ARGUMENT ambiguous FeatureFlag resolution
                    }
                    """.trimIndent(),
            )

        assertDoesNotCompile(
            sources = sources,
            expectedMessages = listOf("ambiguous", "featureflag"),
            expectedDiagnostics = listOf(expectedAmbiguousOrNoContext("featureflag")),
        )
    }

    @Test fun supportsTypeclassMethodsWithAdditionalContext() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            @Typeclass interface Show<A> {
                fun show(value: A): String
            }

            @Typeclass interface Debug<A> {
                context(show: Show<A>)
                fun debug(value: A): String = "debug:" + show.show(value)
            }

            @Instance object IntShow : Show<Int> {
                override fun show(value: Int): String = "int:${'$'}value"
            }

            @Instance object IntDebug : Debug<Int>

            fun main() {
                println(summon<Debug<Int>>().debug(1))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "debug:int:1",
        )
    }

    @Test fun reportsAmbiguousEvidenceInsideDefaultTypeclassMethods() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Typeclass
            interface Debug<A> {
                context(show: Show<A>)
                fun debug(value: A): String = show.show(value)
            }

            @Instance
            object IntShowOne : Show<Int> {
                override fun show(value: Int): String = "one"
            }

            @Instance
            object IntShowTwo : Show<Int> { // duplicate evidence for Debug<Int>.debug
                override fun show(value: Int): String = "two"
            }

            @Instance
            object IntDebug : Debug<Int>

            fun main() {
                println(summon<Debug<Int>>().debug(1)) // E:TC_NO_CONTEXT_ARGUMENT ambiguous Show<Int> inside default method body
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("no context argument", "show"),
            expectedDiagnostics = listOf(expectedAmbiguousOrNoContext("show")),
        )
    }

    @Test fun additionalUnrelatedFilesCanChangeResolutionOutcome() {
        val stableSources =
            mapOf(
                "Main.kt" to
                    """
                    package demo

                    import one.wabbit.typeclass.Instance
                    import one.wabbit.typeclass.Typeclass

                    @Typeclass
                    interface Show<A> {
                        fun show(value: A): String
                    }

                    @Instance
                    object IntShow : Show<Int> {
                        override fun show(value: Int): String = "int:${'$'}value"
                    }

                    context(show: Show<A>)
                    fun <A> render(value: A): String = show.show(value)

                    fun main() {
                        println(render(1))
                    }
                    """.trimIndent(),
            )
        val stableResult = compileSourceInternal(stableSources)
        assertEquals(ExitCode.OK, stableResult.exitCode, stableResult.stdout)

        val unstableSources =
            stableSources +
                (
                    "Orphan.kt" to
                        """
                        package demo

                        import one.wabbit.typeclass.Instance

                        @Instance
                        object OtherIntShow : Show<Int> { // unrelated file changes stable instance resolution
                            override fun show(value: Int): String = "other:${'$'}value"
                        }
                        """.trimIndent()
                    )

        assertDoesNotCompile(
            sources = unstableSources,
            expectedMessages = listOf("no context argument", "show"),
            expectedDiagnostics = listOf(expectedAmbiguousOrNoContext("show")),
        )
    }

    @Test fun reportsOverlapBetweenAliasSpecificAndGenericSpecializedInstances() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            typealias UserIds = List<Int>

            @Typeclass
            interface Show<A> {
                fun label(): String
            }

            @Instance
            object IntShow : Show<Int> {
                override fun label(): String = "int"
            }

            @Instance
            object UserIdsShow : Show<UserIds> {
                override fun label(): String = "alias"
            }

            @Instance
            context(show: Show<A>)
            fun <A> listShow(): Show<List<A>> =
                object : Show<List<A>> {
                    override fun label(): String = "list-" + show.label()
                }

            context(_: Show<A>)
            fun <A> which(): String = summon<Show<A>>().label()

            fun main() {
                println(which<UserIds>()) // E:TC_AMBIGUOUS_INSTANCE alias-specific and generic list instances both satisfy Show<UserIds>
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedMessages = listOf("ambiguous typeclass instance"),
            expectedDiagnostics = listOf(expectedAmbiguousOrNoContext("show")),
        )
    }

    @Test fun superclassEntailmentRespectsDirectLocalShadowing() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Eq<A> {
                fun label(): String
            }

            @Typeclass
            interface Ord<A> : Eq<A> {
                fun compare(left: A, right: A): Int
            }

            @Instance
            object IntOrd : Ord<Int> {
                override fun label(): String = "ord"

                override fun compare(left: Int, right: Int): Int = left.compareTo(right)
            }

            context(eq: Eq<A>)
            fun <A> which(): String = eq.label()

            fun main() {
                println(which<Int>())

                val localEq =
                    object : Eq<Int> {
                        override fun label(): String = "local-eq"
                    }

                context(localEq) {
                    println(which<Int>())
                }
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                ord
                local-eq
                """.trimIndent(),
        )
    }

    @Test fun oneObjectCanProvideMultipleHeadsAndSuperclassEvidence() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Eq<A> {
                fun label(): String
            }

            @Typeclass
            interface Ord<A> : Eq<A> {
                fun compare(left: A, right: A): Int
            }

            @Typeclass
            interface Hash<A> {
                fun hash(value: A): String
            }

            @Instance
            object IntInstances : Ord<Int>, Hash<Int> {
                override fun label(): String = "ord"

                override fun compare(left: Int, right: Int): Int = left.compareTo(right)

                override fun hash(value: Int): String = "hash:${'$'}value"
            }

            context(eq: Eq<A>, hash: Hash<A>)
            fun <A> summary(value: A): String = eq.label() + ":" + hash.hash(value)

            fun main() {
                println(summary(7))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "ord:hash:7",
        )
    }

}
