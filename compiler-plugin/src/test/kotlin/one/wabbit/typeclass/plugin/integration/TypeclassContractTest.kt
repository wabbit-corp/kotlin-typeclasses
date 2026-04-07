// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.typeclass.plugin.integration

import org.jetbrains.kotlin.cli.common.ExitCode
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
            expectedDiagnostics = listOf(expectedAmbiguousInstance("show")),
        )
    }

    @Test fun reportsMissingProvidedTypeclassWhenOnlyNonTypeclassIntermediateSupertypesRemain() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            abstract class IntShowBase : Show<Int> // this still does not provide a valid direct typeclass head

            @Instance
            object IntShow : IntShowBase() { // E:TC_INVALID_INSTANCE_DECL no valid @Typeclass head is provided here
                override fun show(value: Int): String = "int:${'$'}value"
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedExactInvalidInstanceDecl(
                        why = "@Instance declarations must provide a @Typeclass type.",
                    ),
                ),
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
                println(use()) // E ambiguous inherited intermediate typeclass instances should stay visible here
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
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

    @Test fun rejectsDuplicateNullaryTypeclassInstancesAcrossUnrelatedFiles() {
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
                    object AnotherEnabledFlag : FeatureFlag { // E:TC_INVALID_INSTANCE_DECL unrelated-file duplicate nullary instance should be rejected
                        override fun enabled(): Boolean = false
                    }
                    """.trimIndent(),
                "Main.kt" to
                    """
                    package demo

                    fun main() {
                        println(check())
                    }
                    """.trimIndent(),
            )

        assertDoesNotCompile(
            sources = sources,
            expectedDiagnostics = listOf(expectedInvalidInstanceDecl("same file", "featureflag")),
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
            expectedDiagnostics = listOf(expectedAmbiguousOrNoContext("show")),
        )
    }

    @Test fun rejectsAdditionalUnrelatedOrphanFilesInsteadOfChangingResolutionOutcome() {
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
                        object OtherIntShow : Show<Int> { // E:TC_INVALID_INSTANCE_DECL unrelated orphan file must not affect stable resolution
                            override fun show(value: Int): String = "other:${'$'}value"
                        }
                        """.trimIndent()
                    )

        assertDoesNotCompile(
            sources = unstableSources,
            expectedDiagnostics = listOf(expectedInvalidInstanceDecl("same file", "show")),
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

    @Test fun doesNotImplicitlyResolveNonTypeclassContexts() {
        val source =
            """
            package demo

            interface Eq<A> {
                fun eq(left: A, right: A): Boolean
            }

            object IntEq : Eq<Int> {
                override fun eq(left: Int, right: Int): Boolean = left == right
            }

            context(_: Eq<A>)
            fun <A> same(a: A): Boolean = true

            fun main() {
                println(same(1)) // E non-typeclass contexts should not be resolved implicitly
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedErrorContaining("no context argument", "eq")),
        )
    }

    @Test fun reportsMissingCompanionInstanceAnnotationWithoutCrashing() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface ItemComponentType<A>

            data class SomeItemComponent<C>(val value: C, val type: ItemComponentType<C>) {
                companion object {
                    context(type: ItemComponentType<C>)
                    operator fun <C> invoke(value: C): SomeItemComponent<C> = SomeItemComponent(value, type)
                }
            }

            data class Curse(val soulbound: Boolean) {
                companion object {
                    val itemComponentType =
                        object : ItemComponentType<Curse> {}
                }
            }

            fun main() {
                println(SomeItemComponent(Curse(true))) // E missing companion @Instance should surface as an unresolved context
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedErrorContaining("no context argument", "itemcomponenttype")),
        )
    }

    @Test fun rejectsTopLevelDuplicateInstancesAcrossUnrelatedFiles() {
        val sources =
            mapOf(
                "demo/InstancesOne.kt" to
                    """
                    package demo

                    import one.wabbit.typeclass.Instance
                    import one.wabbit.typeclass.Typeclass

                    @Typeclass
                    interface Show<A> {
                        fun show(): String
                    }

                    @Instance
                    object IntShowOne : Show<Int> { // ambiguous instance declaration
                        override fun show(): String = "one"
                    }
                    """.trimIndent(),
                "demo/InstancesTwo.kt" to
                    """
                    package demo

                    import one.wabbit.typeclass.Instance

                    @Instance
                    object IntShowTwo : Show<Int> { // E:TC_INVALID_INSTANCE_DECL unrelated-file duplicate instance should be rejected at declaration site
                        override fun show(): String = "two"
                    }
                    """.trimIndent(),
                "demo/Main.kt" to
                    """
                    package demo

                    context(show: Show<A>)
                    fun <A> render(): String = show.show()

                    fun main() {
                        println(render<Int>())
                    }
                    """.trimIndent(),
            )

        assertDoesNotCompile(
            sources = sources,
            expectedDiagnostics = listOf(expectedInvalidInstanceDecl("same file", "show")),
        )
    }

    @Test fun doesNotDiscoverLocalInstanceDeclarationsGlobally() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                @Instance // E:TC_INVALID_INSTANCE_DECL local @Instance declarations should not be auto-discovered
                fun localIntShow(): Show<Int> =
                    object : Show<Int> {
                        override fun show(value: Int): String = "int:${'$'}value"
                    }

                println(render(1)) // E:TC_NO_CONTEXT_ARGUMENT local @Instance declarations should not be auto-discovered
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedInvalidInstanceDecl("top-level orphan"),
                    expectedNoContextArgument(),
                ),
        )
    }

    @Test fun doesNotLeakPrivateTopLevelInstancesAcrossFiles() {
        val sources =
            mapOf(
                "Hidden.kt" to
                    """
                    package demo

                    import one.wabbit.typeclass.Instance
                    import one.wabbit.typeclass.Typeclass
                    import one.wabbit.typeclass.summon

                    @Typeclass
                    interface Show<A> {
                        fun show(): String
                    }

                    @Instance
                    private object HiddenIntShow : Show<Int> {
                        override fun show(): String = "hidden"
                    }

                    context(_: Show<A>)
                    fun <A> render(): String = summon<Show<A>>().show()
                    """.trimIndent(),
                "Main.kt" to
                    """
                    package demo

                    fun main() {
                        println(render<Int>()) // E:TC_NO_CONTEXT_ARGUMENT private @Instance declarations should not leak across files
                    }
                    """.trimIndent(),
            )

        assertDoesNotCompile(
            sources = sources,
            expectedDiagnostics = listOf(expectedErrorContaining("no context argument", "show")),
        )
    }

    @Test
    fun ambiguousImplicitContextsRemainVisibleToTheFrontend() {
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
            object IntShowOne : Show<Int> {
                override fun label(): String = "one"
            }

            @Instance
            object IntShowTwo : Show<Int> {
                override fun label(): String = "two"
            }

            context(_: Show<Int>)
            fun render(): String = "value"

            fun main() {
                println(render()) // E ambiguous implicit contexts should remain visible to the frontend
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedErrorContaining("no context argument", "show")),
        )
    }

    @Test
    fun recursiveImplicitContextsRemainVisibleToTheFrontend() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Foo<A> {
                fun label(): String
            }

            @Typeclass
            interface Bar<A> {
                fun label(): String
            }

            @Instance
            context(_: Bar<A>)
            fun <A> fooFromBar(): Foo<A> =
                object : Foo<A> {
                    override fun label(): String = "foo"
                }

            @Instance
            context(_: Foo<A>)
            fun <A> barFromFoo(): Bar<A> =
                object : Bar<A> {
                    override fun label(): String = "bar"
                }

            context(_: Foo<Int>)
            fun use(): String = "ok"

            fun main() {
                println(use()) // E recursive implicit contexts should remain visible to the frontend
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedErrorContaining("no context argument", "foo")),
        )
    }

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
