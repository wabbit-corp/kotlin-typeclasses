// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.typeclass.plugin.integration.derivation

import one.wabbit.typeclass.plugin.invalidEquivSubclassing
import one.wabbit.typeclass.plugin.integration.CompilerHarnessPlugin
import one.wabbit.typeclass.plugin.integration.HarnessDependency
import one.wabbit.typeclass.plugin.integration.IntegrationTestSupport
import kotlin.test.Test

class DeriveViaSpec : IntegrationTestSupport() {
    private val coroutinesRuntime = listOf(CompilerHarnessPlugin.CoroutinesRuntime)
    private val serializationRuntime = listOf(CompilerHarnessPlugin.SerializationRuntime)

    // Exact intended semantics:
    // - Equiv<A, B> is compiler-owned canonical evidence the compiler can solve and compose.
    // - Users must not author @Instance values of Equiv<A, B>, subclass it directly, or manually construct it;
    //   explicit user-authored reversible conversions belong in Iso<A, B> instead.
    // - This is enforced by the plugin with a dedicated diagnostic, e.g. TC_INVALID_EQUIV_DECL;
    //   the library class shape alone is not treated as sufficient enforcement.
    // - @DeriveEquiv(B::class) exports a globally visible, summonable Equiv<Annotated, B>.
    // - During explicit derivation annotations only, the compiler may also synthesize transient local Equiv edges/chains
    //   between any whitelist-admissible pair of types if that is needed to complete the requested derivation.
    // - Those transient Equivs are not published to ordinary typeclass search and cannot later be summoned as standalone
    //   evidence; only @DeriveEquiv exports regular resolution-visible Equiv instances.
    //
    // abstract class Equiv<A, B> @InternalTypeclassApi protected constructor() {
    //     fun to(value: A): B
    //     fun from(value: B): A
    //     // must satisfy to . from = id and from . to = id
    //
    //     fun inverse(): Equiv<B, A> = ...
    //     fun <C> compose(that: Equiv<B, C>): Equiv<A, C> = ...
    // }
    //
    // - The solver may freely reorient solved Equiv<A, B> evidence as Equiv<B, A>.
    // - Canonical means Equiv search is normalized by endpoint pair, not by proof tree.
    // - Distinct direct/composed/internal proof trees that establish the same Equiv<X, Y> do not count as ambiguity
    //   by themselves, whether those trees use exported DeriveEquiv evidence or transient DeriveVia-local synthesis.
    // - Ambiguity is reserved for user-visible choices, especially:
    //   * different pinned Iso objects
    //   * different pinned-Iso attachments/orientations
    //   * different terminal typeclass instances reached by DeriveVia
    // - Compiler-synthesized Equiv is only sound for a conservative syntactic whitelist of transparent total shapes.
    // - The checker should reject anything outside that whitelist rather than trying to prove semantic transparency.
    // - For value classes, the whitelist is:
    //   * one public primary-constructor property
    //   * no `init` blocks
    //   * no secondary constructors
    //   * no extra backing fields or delegated properties with stored state
    // - For structural DeriveEquiv products, the whitelist is:
    //   * data classes only
    //   * only primary-constructor properties participate
    //   * participating properties must be public `val`s
    //   * no `init` blocks
    //   * no secondary constructors
    //   * no extra backing fields or delegated properties with stored state
    // - For structural DeriveEquiv sums, the whitelist is:
    //   * sealed hierarchies only
    //   * cases must be transparent `data class` / `data object` declarations that satisfy the same whitelist

    // - Iso<A, B> is an explicit value, not a typeclass; DeriveVia may pin exact Iso objects in its path.
    //
    // interface Iso<A, B> {     // NOT A TYPECLASS
    //     fun to(value: A): B
    //     fun from(value: B): A
    //     // must satisfy to . from = id and from . to = id
    //     fun <C> compose(that: Equiv<B, C>): Iso<A, C> = ...
    //     fun <C> compose(that: Iso<B, C>): Iso<A, C> = ...
    // }

    // - DeriveVia(TC::class, Foo::class) should mean: solve Equiv<Target, Foo> plus TC<Foo>,
    //   then transport the instance methods of TC across that equivalence.
    //
    // @Target(AnnotationTarget.CLASS)
    // @Repeatable
    // annotation class DeriveVia(
    //     val typeclass: KClass<*>,
    //     vararg val path: KClass<*>, // via types or pinned Iso objects
    // )
    // - Empty paths are rejected at compile time; DeriveVia must name at least one waypoint or pinned Iso segment.
    // - For now, DeriveVia transports only the last type parameter of the requested typeclass.
    // - Multi-parameter typeclasses therefore participate only when their transported slot is the final one.
    // - More general parameter-selection rules would need an explicit future design.
    //
    // Path semantics:
    // - a type waypoint Foo::class means "solve Equiv<Current, Foo>"
    // - a pinned Iso object FooIso::class means "use exactly that Iso singleton for one segment"
    // - compiler-solved Equiv glue may be inserted before or after a pinned Iso segment as needed, but only when
    //   exactly one endpoint of that pinned Iso is reachable from the current state
    // - only the Iso object itself is pinned; the surrounding Equiv path remains solver-driven
    // - if both endpoints of a pinned Iso are reachable from the current state, derivation must fail as ambiguous
    //   immediately rather than picking an attachment/orientation
    //
    // Transport boundary:
    // - let F(A) be the smallest class of transportable shapes such that:
    //   * A itself is in F(A)
    //   * any type not mentioning A is in F(A)
    //   * if X and Y are in F(A), then ordinary, suspend, and extension-function shapes built from X and Y are in F(A)
    //   * contextual function shapes are in F(A) when their context parameter types do not mention A
    //   * nullable A? is in F(A) whenever A is in F(A)
    //   * type aliases are transparent; Alias<A> is in F(A) exactly when its expansion is
    //   * star projections are allowed in type positions when the resulting type still satisfies the structural whitelist
    //   * generic methods with additional unconstrained type parameters are allowed when, in the schematic member
    //     signature, those extra parameters are treated as rigid opaque variables and every A-occurrence remains
    //     inside F(A)
    //   * admissible nominal shapes in F(A) are limited to whitelist-approved, compiler-inspectable structural
    //     product/sum types whose stored fields are themselves in F(A), including covariant, contravariant,
    //     invariant, and phantom occurrences of A
    //   * admissible structural shapes must expose only read-only stored state:
    //     constructor properties participating in structural transport must be `val`, not `var`,
    //     and there must be no additional mutable backing state
    // - DeriveVia should transport a typeclass exactly when every appearance of the distinguished type parameter
    //   in all members lies inside F(A)
    // - if any member mentions A outside this closure, derivation should fail at compile time rather than guessing
    // - the real boundary is "compiler-inspectable structural representation" versus "opaque / behavioral nominal type"
    // - named generic types are not excluded merely for being nominal; they are excluded when the compiler does not
    //   treat them as decomposable structural shapes under the whitelist above
    // - ordinary nominal containers are therefore not assumed transportable just because they look "functor-ish";
    //   List<A>, Map<String, A>, Comparator<A>, KClass<A>, Result<A>, and custom nominal classes with their own
    //   behavior are out unless the compiler grows an explicit structural rule for them
    // - a product like Pair<A, String> is only in scope if the compiler explicitly chooses to decompose it as a
    //   structural two-field product under the same whitelist; otherwise it remains out with the other opaque nominal types
    // - recursive nominal shapes are out by default; the closure only admits finite non-recursive structural shapes
    // - mutable structural shapes are out by default because transport must preserve aliasing and mutation semantics
    // - method type-parameter bounds mentioning A are out for now
    // - definitely-non-null / intersection shapes like A & Any are out for now
    // - inherited default methods on the typeclass interface participate like ordinary methods and are expected to
    //   observe the same transport rules as explicit members
    // - DeriveVia preserves extensional behavior only; it does not guarantee referential identity (`===`),
    //   allocation patterns, wrapper reuse, object identity, or stable `==` / `hashCode()` / `toString()` behavior
    //   for transported higher-order structural wrappers that may need freshly allocated adapter functions

    // - For transport, the compiler should materialize the adapter from method signatures directly;
    //   no typeclass companion hook like deriveVia(...) should be required.
    @Test fun supportsDerivingViaThroughEquivTypes() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Equiv
            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Monoid<A> {
                fun empty(): A
                fun combine(left: A, right: A): A
            }

            @JvmInline
            value class Foo(val value: Int)

            @Instance
            object FooMonoid : Monoid<Foo> {
                override fun empty(): Foo = Foo(0)

                override fun combine(left: Foo, right: Foo): Foo = Foo(left.value + right.value)
            }

            // Spec:
            // - while solving this explicit DeriveVia annotation, the compiler may locally synthesize Equiv<UserId, Int>
            // - if it can also locally derive/synthesize Equiv<Int, Foo>, then Equiv<UserId, Foo> should be available
            //   by composition for this derivation only
            // - DeriveVia(Monoid::class, Foo::class) should therefore produce Monoid<UserId> from Monoid<Foo>
            @JvmInline
            @DeriveVia(Monoid::class, Foo::class)
            value class UserId(val value: Int)

            context(monoid: Monoid<A>)
            fun <A> combineTwice(value: A): A = monoid.combine(value, monoid.combine(monoid.empty(), value))

            fun main() {
                println(combineTwice(UserId(5)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "UserId(value=10)",
        )
    }

    // Exact intended semantics:
    // - DeriveVia must not accept an empty path
    // - the annotation must name at least one via-type waypoint or pinned Iso segment
    // - otherwise the feature degenerates into unconstrained "search some equivalent type" behavior
    @Test fun rejectsEmptyDeriveViaPaths() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @JvmInline
            @DeriveVia(Show::class) // E:TC_CANNOT_DERIVE empty DeriveVia paths must be rejected
            value class UserId(val value: Int)
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedCannotDerive("empty path"),
                ),
        )
    }

    @Test fun validatesRepeatableEmptyDeriveViaPathsInFirAndKeepsThemOutOfRefinement() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @JvmInline
            @DeriveVia(Show::class) // E:TC_CANNOT_DERIVE repeated empty DeriveVia paths must be rejected in FIR
            @DeriveVia(Show::class) // E:TC_CANNOT_DERIVE repeated empty DeriveVia paths must be rejected in FIR
            value class UserId(val value: Int)

            context(show: Show<UserId>)
            fun render(value: UserId): String = show.show(value)

            fun render(value: UserId): String = "plain:${'$'}{value.value}"

            fun main() {
                println(render(UserId(1)))
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedCannotDerive("empty path")),
            unexpectedMessages =
                listOf(
                    "no context argument",
                    "required instance",
                    "overload resolution ambiguity",
                ),
        )
    }

    @Test fun deriveViaRejectsNonTypeclassTargetsInFir() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia

            interface Plain<A>

            data class Wire(val value: String)

            @DeriveVia(Plain::class, Wire::class) // E:TC_CANNOT_DERIVE non-typeclass DeriveVia targets must be diagnosed explicitly
            data class UserId(val value: String)
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedCannotDerive("plain", "@typeclass")),
            unexpectedMessages =
                listOf(
                    "no context argument",
                    "overload resolution ambiguity",
                ),
        )
    }

    // Exact intended semantics:
    // - Equiv search is canonical by endpoint pair, not by proof tree
    // - if multiple direct/composed Equiv derivations all establish the same endpoint pair, they do not by themselves
    //   create ambiguity for DeriveVia
    // - ambiguity is only about user-visible path choices or distinct terminal typeclass instances
    @Test fun treatsMultipleEquivProofTreesForTheSameEndpointPairAsOneCanonicalEquiv() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveEquiv
            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            data class ViaB(val value: Int)

            @Instance
            object ViaBShow : Show<ViaB> {
                override fun show(value: ViaB): String = "b:${'$'}{value.value}"
            }

            @DeriveEquiv(ViaB::class)
            data class ViaC2(val value: Int)

            @DeriveEquiv(ViaB::class)
            @DeriveEquiv(ViaC2::class)
            data class UserId(val value: Int)

            // Spec:
            // - UserId exports multiple Equiv proof trees to ViaB:
            //   direct UserId ~ ViaB
            //   composed UserId ~ ViaC2 ~ ViaB
            // - TaggedUserId itself does NOT export an Equiv.
            // - DeriveVia(Show::class, ViaB::class) may still locally synthesize TaggedUserId ~ UserId and/or
            //   TaggedUserId ~ ViaB while searching this one derivation.
            // - The resulting local+exported proof trees all collapse to the same endpoint pair TaggedUserId ~ ViaB,
            //   so derivation should still succeed.
            @DeriveVia(Show::class, ViaB::class)
            data class TaggedUserId(val value: Int)

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(TaggedUserId(3)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "b:3",
        )
    }

    // Exact intended semantics:
    // - DeriveVia may synthesize transient local Equiv chains while deriving one requested typeclass
    // - those transient Equivs are NOT exported to ordinary search and cannot be summoned later as standalone proofs
    @Test fun deriveViaLocalEquivSynthesisIsNotPublishedToOrdinaryResolution() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Equiv
            import one.wabbit.typeclass.InternalTypeclassApi
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            data class ViaB(val value: Int)

            @Instance
            object ViaBShow : Show<ViaB> {
                override fun show(value: ViaB): String = "b:${'$'}{value.value}"
            }

            @DeriveVia(Show::class, ViaB::class)
            data class TaggedUserId(val value: Int)

            @OptIn(InternalTypeclassApi::class)
            fun main() {
                println(summon<Equiv<TaggedUserId, ViaB>>()) // E:TC_NO_CONTEXT_ARGUMENT TaggedUserId does not export Equiv without @DeriveEquiv
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedNoContextArgument()),
        )
    }

    // Exact intended semantics:
    // - @DeriveVia may be compiled in one dependency module while the typeclass and via waypoint live in an upstream module.
    // - The deriving module itself must be able to use that derived instance while compiling against the upstream module.
    @Test fun dependencyModuleCanCompileDeriveViaWhenViaWaypointLivesInUpstreamModule() {
        val moduleA =
            HarnessDependency(
                name = "derivevia-upstream-waypoint-a",
                sources =
                    mapOf(
                        "depa/Show.kt" to showTypeclassSource("depa"),
                        "depa/Foo.kt" to
                            """
                            package depa

                            import one.wabbit.typeclass.Instance

                            @JvmInline
                            value class Foo(val value: Int) {
                                companion object {
                                    @Instance
                                    val show: Show<Foo> =
                                        object : Show<Foo> {
                                            override fun show(value: Foo): String = "foo:${'$'}{value.value}"
                                        }
                                }
                            }
                            """.trimIndent(),
                    ),
            )
        val moduleB =
            HarnessDependency(
                name = "derivevia-upstream-waypoint-b",
                dependencies = listOf(moduleA),
                sources =
                    mapOf(
                        "depb/UserId.kt" to
                            """
                            package depb

                            import depa.Foo
                            import depa.Show
                            import depa.render
                            import one.wabbit.typeclass.DeriveVia

                            @JvmInline
                            @DeriveVia(Show::class, Foo::class)
                            value class UserId(val value: Int)

                            fun renderUserId(): String = render(UserId(5))
                            """.trimIndent(),
                    ),
            )
        val source =
            """
            package demo

            import depb.renderUserId

            fun main() {
                println(renderUserId())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "foo:5",
            dependencies = listOf(moduleB),
        )
    }

    // Exact intended semantics:
    // - once a dependency module exports a class annotated with @DeriveVia, downstream consumer modules
    //   must be able to use the resulting derived typeclass instance directly.
    // - this exercises binary/cross-module DeriveVia reconstruction at the actual consumer request site,
    //   not just inside the module that authored the annotation.
    @Test fun consumerModuleCanUseDependencyDeriveViaAcrossDependencyModules() {
        val moduleA =
            HarnessDependency(
                name = "derivevia-consumer-boundary-a",
                sources =
                    mapOf(
                        "depa/Show.kt" to showTypeclassSource("depa"),
                        "depa/Foo.kt" to
                            """
                            package depa

                            import one.wabbit.typeclass.Instance

                            @JvmInline
                            value class Foo(val value: Int) {
                                companion object {
                                    @Instance
                                    val show: Show<Foo> =
                                        object : Show<Foo> {
                                            override fun show(value: Foo): String = "foo:${'$'}{value.value}"
                                        }
                                }
                            }
                            """.trimIndent(),
                    ),
            )
        val moduleB =
            HarnessDependency(
                name = "derivevia-consumer-boundary-b",
                dependencies = listOf(moduleA),
                sources =
                    mapOf(
                        "depb/UserId.kt" to
                            """
                            package depb

                            import depa.Foo
                            import depa.Show
                            import one.wabbit.typeclass.DeriveVia

                            @JvmInline
                            @DeriveVia(Show::class, Foo::class)
                            value class UserId(val value: Int)
                            """.trimIndent(),
                    ),
            )
        val source =
            """
            package demo

            import depa.render
            import depb.UserId

            fun main() {
                println(render(UserId(12)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "foo:12",
            dependencies = listOf(moduleB),
        )
    }

    @Test fun supportsDerivingViaThroughSameModuleInternalValueMembers() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @JvmInline
            value class Via internal constructor(internal val value: Int)

            @Instance
            object ViaShow : Show<Via> {
                override fun show(value: Via): String = "via:${'$'}{value.value}"
            }

            @JvmInline
            @DeriveVia(Show::class, Via::class)
            value class UserId(val value: Int)

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(UserId(7)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "via:7",
        )
    }

    @Test fun privateValueConstructorsAreRejectedWithoutCreatingOverloadAmbiguity() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @JvmInline
            value class Via private constructor(val value: Int) {
                companion object {
                    fun of(value: Int): Via = Via(value)
                }
            }

            @Instance
            object ViaShow : Show<Via> {
                override fun show(value: Via): String = "via:${'$'}{value.value}"
            }

            @JvmInline
            @DeriveVia(Show::class, Via::class) // E:TC_CANNOT_DERIVE private transport members must not make DeriveVia look usable
            value class UserId(val value: Int)

            context(show: Show<UserId>)
            fun render(value: UserId): String = show.show(value)

            fun render(value: UserId): String = "plain:${'$'}{value.value}"

            fun main() {
                println(render(UserId(7)))
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedCannotDerive("derive via", "via", "userid")),
            unexpectedMessages =
                listOf(
                    "overload resolution ambiguity",
                    "no context argument",
                ),
        )
    }

    @Test fun dependencyInternalValueMembersAreRejectedAtTheAnnotationSite() {
        val moduleA =
            HarnessDependency(
                name = "derivevia-internal-value-upstream",
                sources =
                    mapOf(
                        "depa/Show.kt" to showTypeclassSource("depa"),
                        "depa/Via.kt" to
                            """
                            package depa

                            import one.wabbit.typeclass.Instance

                            @JvmInline
                            value class Via internal constructor(internal val value: Int)

                            @Instance
                            object ViaShow : Show<Via> {
                                override fun show(value: Via): String = "via:${'$'}{value.value}"
                            }
                            """.trimIndent(),
                    ),
            )
        val moduleB =
            HarnessDependency(
                name = "derivevia-internal-value-consumer",
                dependencies = listOf(moduleA),
                sources =
                    mapOf(
                        "depb/UserId.kt" to
                            """
                            package depb

                            import depa.Show
                            import depa.Via
                            import one.wabbit.typeclass.DeriveVia

                            @JvmInline
                            @DeriveVia(Show::class, Via::class) // E:TC_CANNOT_DERIVE dependency-internal value transport members must not make DeriveVia look usable
                            value class UserId(val value: Int)

                            context(show: Show<UserId>)
                            fun render(value: UserId): String = show.show(value)

                            fun render(value: UserId): String = "plain:${'$'}{value.value}"

                            fun renderUserId(): String = render(UserId(7))
                            """.trimIndent(),
                    ),
            )
        assertDoesNotCompile(
            sources = moduleB.sources,
            expectedDiagnostics = listOf(expectedCannotDerive("derive via", "via", "userid")),
            dependencies = listOf(moduleA),
        )
    }

    @Test fun supportsDerivingViaThroughSameModuleInternalProductMembers() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            data class Via internal constructor(internal val value: Int)

            @Instance
            object ViaShow : Show<Via> {
                override fun show(value: Via): String = "via:${'$'}{value.value}"
            }

            @DeriveVia(Show::class, Via::class)
            data class UserId(val value: Int)

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(UserId(7)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "via:7",
        )
    }

    @Test fun dependencyInternalProductMembersAreRejectedAtTheAnnotationSite() {
        val moduleA =
            HarnessDependency(
                name = "derivevia-internal-product-upstream",
                sources =
                    mapOf(
                        "depa/Show.kt" to showTypeclassSource("depa"),
                        "depa/Via.kt" to
                            """
                            package depa

                            import one.wabbit.typeclass.Instance

                            data class Via internal constructor(internal val value: Int)

                            @Instance
                            object ViaShow : Show<Via> {
                                override fun show(value: Via): String = "via:${'$'}{value.value}"
                            }
                            """.trimIndent(),
                    ),
            )
        val moduleB =
            HarnessDependency(
                name = "derivevia-internal-product-consumer",
                dependencies = listOf(moduleA),
                sources =
                    mapOf(
                        "depb/UserId.kt" to
                            """
                            package depb

                            import depa.Show
                            import depa.Via
                            import one.wabbit.typeclass.DeriveVia

                            @DeriveVia(Show::class, Via::class) // E:TC_CANNOT_DERIVE dependency-internal product transport members must not make DeriveVia look usable
                            data class UserId(val value: Int)

                            context(show: Show<UserId>)
                            fun render(value: UserId): String = show.show(value)

                            fun render(value: UserId): String = "plain:${'$'}{value.value}"

                            fun renderUserId(): String = render(UserId(7))
                            """.trimIndent(),
                    ),
            )
        assertDoesNotCompile(
            sources = moduleB.sources,
            expectedDiagnostics = listOf(expectedCannotDerive("derive via", "via", "userid")),
            dependencies = listOf(moduleA),
        )
    }

    @Test fun supportsDerivingViaThroughSameModuleInternalSealedCases() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            sealed interface Via

            internal data object ViaEmpty : Via

            internal data class ViaBox(val value: Int) : Via

            @Instance
            object ViaShow : Show<Via> {
                override fun show(value: Via): String =
                    when (value) {
                        ViaEmpty -> "empty"
                        is ViaBox -> "box:${'$'}{value.value}"
                    }
            }

            @DeriveVia(Show::class, Via::class)
            sealed interface User {
                data object Empty : User
                data class Box(val value: Int) : User
            }

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render<User>(User.Empty))
                println(render<User>(User.Box(7)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                empty
                box:7
                """.trimIndent(),
        )
    }

    @Test fun dependencyInternalSealedCasesAreRejectedAtTheAnnotationSite() {
        val moduleA =
            HarnessDependency(
                name = "derivevia-internal-sealed-upstream",
                sources =
                    mapOf(
                        "depa/Show.kt" to showTypeclassSource("depa"),
                        "depa/Via.kt" to
                            """
                            package depa

                            import one.wabbit.typeclass.Instance

                            sealed interface Via

                            internal data object ViaEmpty : Via

                            internal data class ViaBox(val value: Int) : Via

                            @Instance
                            object ViaShow : Show<Via> {
                                override fun show(value: Via): String =
                                    when (value) {
                                        ViaEmpty -> "empty"
                                        is ViaBox -> "box:${'$'}{value.value}"
                                    }
                            }
                            """.trimIndent(),
                    ),
            )
        assertDoesNotCompile(
            sources =
                mapOf(
                    "depb/User.kt" to
                        """
                        package depb

                        import depa.Show
                        import depa.Via
                        import one.wabbit.typeclass.DeriveVia

                        @DeriveVia(Show::class, Via::class) // E:TC_CANNOT_DERIVE dependency-internal sealed cases must not make DeriveVia look usable
                        sealed interface User {
                            data object Empty : User
                            data class Box(val value: Int) : User
                        }

                        context(show: Show<A>)
                        fun <A> render(value: A): String = show.show(value)

                        fun render(value: User): String = "plain"

                        fun renderUser(): String = render(User.Empty)
                        """.trimIndent(),
                ),
            expectedDiagnostics = listOf(expectedCannotDerive("derive via", "via", "user")),
            unexpectedMessages =
                listOf(
                    "overload resolution ambiguity",
                    "no context argument",
                ),
            dependencies = listOf(moduleA),
        )
    }

    // Exact intended semantics:
    // - pinned Iso path segments may live in an upstream dependency module.
    // - The deriving module may still rely on transient local Equiv glue to reach the pinned segment
    //   while compiling against that upstream module.
    @Test fun dependencyModuleCanCompileDeriveViaWhenPinnedIsoLivesInUpstreamModule() {
        val moduleA =
            HarnessDependency(
                name = "derivevia-upstream-iso-a",
                sources =
                    mapOf(
                        "depa/Show.kt" to showTypeclassSource("depa"),
                        "depa/Foo.kt" to
                            """
                            package depa

                            import one.wabbit.typeclass.Instance

                            @JvmInline
                            value class Foo(val value: Int) {
                                companion object {
                                    @Instance
                                    val show: Show<Foo> =
                                        object : Show<Foo> {
                                            override fun show(value: Foo): String = "foo:${'$'}{value.value}"
                                        }
                                }
                            }
                            """.trimIndent(),
                        "depa/Token.kt" to
                            """
                            package depa

                            data class Token(val raw: Int)
                            """.trimIndent(),
                        "depa/TokenFooIso.kt" to
                            """
                            package depa

                            import one.wabbit.typeclass.Iso

                            object TokenFooIso : Iso<Token, Foo> {
                                override fun to(value: Token): Foo = Foo(value.raw)

                                override fun from(value: Foo): Token = Token(value.value)
                            }
                            """.trimIndent(),
                    ),
            )
        val moduleB =
            HarnessDependency(
                name = "derivevia-upstream-iso-b",
                dependencies = listOf(moduleA),
                sources =
                    mapOf(
                        "depb/UserId.kt" to
                            """
                            package depb

                            import depa.Show
                            import depa.Token
                            import depa.TokenFooIso
                            import depa.render
                            import one.wabbit.typeclass.DeriveVia

                            @JvmInline
                            @DeriveVia(Show::class, TokenFooIso::class)
                            value class UserId(val value: Token)

                            fun renderUserId(): String = render(UserId(Token(9)))
                            """.trimIndent(),
                    ),
            )
        val source =
            """
            package demo

            import depb.renderUserId

            fun main() {
                println(renderUserId())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "foo:9",
            dependencies = listOf(moduleB),
        )
    }

    // Exact intended semantics:
    // - downstream consumer modules must also be able to use dependency-exported DeriveVia instances whose
    //   path includes a pinned upstream Iso object.
    // - this exercises binary DeriveVia reconstruction for the less trivial pinned-Iso boundary shape.
    @Test fun consumerModuleCanUseDependencyDeriveViaWithPinnedIsoAcrossDependencyModules() {
        val moduleA =
            HarnessDependency(
                name = "derivevia-consumer-pinned-a",
                sources =
                    mapOf(
                        "depa/Show.kt" to showTypeclassSource("depa"),
                        "depa/Foo.kt" to
                            """
                            package depa

                            import one.wabbit.typeclass.Instance

                            @JvmInline
                            value class Foo(val value: Int) {
                                companion object {
                                    @Instance
                                    val show: Show<Foo> =
                                        object : Show<Foo> {
                                            override fun show(value: Foo): String = "foo:${'$'}{value.value}"
                                        }
                                }
                            }
                            """.trimIndent(),
                        "depa/Token.kt" to
                            """
                            package depa

                            data class Token(val raw: Int)
                            """.trimIndent(),
                        "depa/TokenFooIso.kt" to
                            """
                            package depa

                            import one.wabbit.typeclass.Iso

                            object TokenFooIso : Iso<Token, Foo> {
                                override fun to(value: Token): Foo = Foo(value.raw)

                                override fun from(value: Foo): Token = Token(value.value)
                            }
                            """.trimIndent(),
                    ),
            )
        val moduleB =
            HarnessDependency(
                name = "derivevia-consumer-pinned-b",
                dependencies = listOf(moduleA),
                sources =
                    mapOf(
                        "depb/UserId.kt" to
                            """
                            package depb

                            import depa.Show
                            import depa.Token
                            import depa.TokenFooIso
                            import one.wabbit.typeclass.DeriveVia

                            @JvmInline
                            @DeriveVia(Show::class, TokenFooIso::class)
                            value class UserId(val value: Token)
                            """.trimIndent(),
                    ),
            )
        val source =
            """
            package demo

            import depa.Token
            import depa.render
            import depb.UserId

            fun main() {
                println(render(UserId(Token(21))))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "foo:21",
            dependencies = listOf(moduleB),
        )
    }

    // Exact intended semantics:
    // - @DeriveEquiv(A::class) compiled in module B should export a summonable Equiv<B, A>,
    //   even when A itself is declared in a different upstream module.
    @Test fun consumerModuleCanSummonDependencyDeriveEquivAcrossDependencyModules() {
        val moduleA =
            HarnessDependency(
                name = "deriveequiv-upstream-a",
                sources =
                    mapOf(
                        "depa/A.kt" to
                            """
                            package depa

                            data class A(val value: Int)
                            """.trimIndent(),
                    ),
            )
        val moduleB =
            HarnessDependency(
                name = "deriveequiv-upstream-b",
                dependencies = listOf(moduleA),
                sources =
                    mapOf(
                        "depb/B.kt" to
                            """
                            package depb

                            import depa.A
                            import one.wabbit.typeclass.DeriveEquiv

                            @DeriveEquiv(A::class)
                            data class B(val value: Int)
                            """.trimIndent(),
                    ),
            )
        val source =
            """
            package demo

            import depa.A
            import depb.B
            import one.wabbit.typeclass.Equiv
            import one.wabbit.typeclass.InternalTypeclassApi
            import one.wabbit.typeclass.summon

            @OptIn(InternalTypeclassApi::class)
            fun main() {
                val equiv = summon<Equiv<B, A>>()
                println(equiv.to(B(7)))
                println(equiv.from(A(8)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                A(value=7)
                B(value=8)
                """.trimIndent(),
            dependencies = listOf(moduleB),
        )
    }

    @Test fun dependencyDeriveEquivDoesNotMakeUnrelatedTargetsLookDerivable() {
        val moduleA =
            HarnessDependency(
                name = "deriveequiv-target-precision-a",
                sources =
                    mapOf(
                        "depa/A.kt" to
                            """
                            package depa

                            data class A(val value: Int)
                            """.trimIndent(),
                        "depa/C.kt" to
                            """
                            package depa

                            data class C(val value: Int)
                            """.trimIndent(),
                    ),
            )
        val moduleB =
            HarnessDependency(
                name = "deriveequiv-target-precision-b",
                dependencies = listOf(moduleA),
                sources =
                    mapOf(
                        "depb/B.kt" to
                            """
                            package depb

                            import depa.A
                            import one.wabbit.typeclass.DeriveEquiv

                            @DeriveEquiv(A::class)
                            data class B(val value: Int)
                            """.trimIndent(),
                    ),
            )
        val source =
            """
            package demo

            import depa.C
            import depb.B
            import one.wabbit.typeclass.Equiv
            import one.wabbit.typeclass.InternalTypeclassApi

            @OptIn(InternalTypeclassApi::class)
            context(_: Equiv<B, C>)
            fun choose(value: B): String = "equiv"

            fun choose(value: B): String = "plain"

            fun main() {
                println(choose(B(1)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "plain",
            dependencies = listOf(moduleB),
        )
    }

    @Test fun deriveViaMissingViaInstanceDoesNotHidePlainOverload() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @JvmInline
            @DeriveVia(Show::class, Int::class)
            value class UserId(val value: Int)

            context(_: Show<UserId>)
            fun choose(value: UserId): String = "derive-via"

            fun choose(value: UserId): String = "plain"

            fun main() {
                println(choose(UserId(1)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "plain",
        )
    }

    @Test fun disconnectedDeriveViaPathDoesNotHidePlainOverload() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @JvmInline
            @DeriveVia(Show::class, String::class) // E:TC_CANNOT_DERIVE disconnected DeriveVia path should stay an annotation-site error
            value class UserId(val value: Int)

            context(_: Show<UserId>)
            fun choose(value: UserId): String = "derive-via"

            fun choose(value: UserId): String = "plain"

            fun main() {
                println(choose(UserId(2)))
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedCannotDerive("derive via", "kotlin/string", "userid"),
                ),
        )
    }

    @Test fun importedDeriveViaRulesMustStillSolveConcretePrerequisitesInFir() {
        val dependency =
            HarnessDependency(
                name = "dep-imported-derive-via-hidden-prereq",
                sources =
                    mapOf(
                        "dep/Api.kt" to
                            """
                            package dep

                            import one.wabbit.typeclass.DeriveVia
                            import one.wabbit.typeclass.Instance
                            import one.wabbit.typeclass.Typeclass

                            @Typeclass
                            interface Show<A> {
                                fun show(value: A): String
                            }

                            data class Hidden(val value: String)

                            @Instance
                            internal object HiddenShow : Show<Hidden> {
                                override fun show(value: Hidden): String = value.value
                            }

                            @JvmInline
                            @DeriveVia(Show::class, Hidden::class)
                            value class Token(val value: Hidden)
                            """.trimIndent(),
                    ),
            )
        val source =
            """
            package demo

            import dep.Show
            import dep.Token

            context(_: Show<Token>)
            fun choose(value: Token): String = "derive-via"

            fun choose(value: Token): String = "plain"

            fun main() {
                println(choose(Token(dep.Hidden("nope"))))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "plain",
            dependencies = listOf(dependency),
        )
    }

    @Test fun infeasibleDeriveEquivDoesNotHidePlainOverload() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveEquiv
            import one.wabbit.typeclass.Equiv
            import one.wabbit.typeclass.InternalTypeclassApi

            data class PlainBox(val value: Int)

            @DeriveEquiv(PlainBox::class) // E:TC_CANNOT_DERIVE invalid structural Equiv should stay an annotation-site error
            data class PositiveBox(val value: Int) {
                init {
                    require(value > 0)
                }
            }

            @OptIn(InternalTypeclassApi::class)
            context(_: Equiv<PositiveBox, PlainBox>)
            fun choose(value: PositiveBox): String = "derive-equiv"

            fun choose(value: PositiveBox): String = "plain"

            fun main() {
                println(choose(PositiveBox(1)))
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedCannotDerive("equiv", "positivebox", "plainbox"),
                ),
        )
    }

    @Test fun deriveEquivSupportsRepeatedSiblingTransportPairs() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveEquiv
            import one.wabbit.typeclass.Equiv
            import one.wabbit.typeclass.InternalTypeclassApi
            import one.wabbit.typeclass.summon

            @JvmInline
            value class A(val value: Int)

            @JvmInline
            value class B(val value: Int)

            @DeriveEquiv(Right::class)
            data class Left(val first: A, val second: A)

            data class Right(val first: B, val second: B)

            @OptIn(InternalTypeclassApi::class)
            fun main() {
                val equiv = summon<Equiv<Left, Right>>()
                println(equiv.to(Left(A(1), A(2))))
                println(equiv.from(Right(B(3), B(4))))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                Right(first=B(value=1), second=B(value=2))
                Left(first=A(value=3), second=A(value=4))
                """.trimIndent(),
        )
    }

    @Test fun deriveViaSupportsRepeatedSiblingStructuralTransportShapes() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            data class Box<A>(val value: A)
            data class Nest<A>(val left: Box<A>, val right: Box<A>)

            @Typeclass
            interface Fancy<A> {
                fun adjust(value: Nest<A>): Nest<A>
            }

            @JvmInline
            value class Foo(val value: Int)

            @Instance
            object FooFancy : Fancy<Foo> {
                override fun adjust(value: Nest<Foo>): Nest<Foo> =
                    Nest(
                        left = Box(Foo(value.left.value.value + 1)),
                        right = Box(Foo(value.right.value.value + 1)),
                    )
            }

            @JvmInline
            @DeriveVia(Fancy::class, Foo::class)
            value class UserId(val value: Int)

            context(fancy: Fancy<A>)
            fun <A> adjust(value: Nest<A>): Nest<A> = fancy.adjust(value)

            fun main() {
                println(adjust(Nest(Box(UserId(1)), Box(UserId(2)))))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "Nest(left=Box(value=UserId(value=2)), right=Box(value=UserId(value=3)))",
        )
    }

    @Test fun deriveViaPreservesNullableTerminalViaTypes() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Iso
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance
            object NullableStringShow : Show<String?> {
                override fun show(value: String?): String = value ?: "<null>"
            }

            @JvmInline
            @DeriveVia(Show::class, UserIdIso::class)
            value class UserId(val value: Int)

            object UserIdIso : Iso<UserId, String?> {
                override fun to(value: UserId): String? = value.value.takeIf { it >= 0 }?.toString()

                override fun from(value: String?): UserId = UserId(value?.toInt() ?: -1)
            }

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(UserId(7)))
                println(render(UserId(-1)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                7
                <null>
                """.trimIndent(),
        )
    }

    @Test fun deriveViaPreservesParameterizedTerminalViaTypes() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Iso
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance
            object IntListShow : Show<List<Int>> {
                override fun show(value: List<Int>): String = value.joinToString("|")
            }

            @JvmInline
            @DeriveVia(Show::class, UserIdIso::class)
            value class UserId(val value: Int)

            object UserIdIso : Iso<UserId, List<Int>> {
                override fun to(value: UserId): List<Int> = listOf(value.value, value.value + 1)

                override fun from(value: List<Int>): UserId = UserId(value.first())
            }

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(UserId(4)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "4|5",
        )
    }

    @Test fun deriveViaImplementsInheritedAbstractMembers() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Render<A> {
                fun render(value: A): String
            }

            @Typeclass
            interface Pretty<A> : Render<A> {
                fun pretty(value: A): String
            }

            @JvmInline
            value class Foo(val value: Int)

            @Instance
            object FooPretty : Pretty<Foo> {
                override fun render(value: Foo): String = "render:${'$'}{value.value}"

                override fun pretty(value: Foo): String = "pretty:${'$'}{value.value}"
            }

            @JvmInline
            @DeriveVia(Pretty::class, Foo::class)
            value class UserId(val value: Int)

            context(pretty: Pretty<A>)
            fun <A> describe(value: A): String = pretty.render(value) + "|" + pretty.pretty(value)

            fun main() {
                println(describe(UserId(9)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "render:9|pretty:9",
        )
    }

    @Test fun deriveViaExpandsInheritedTypeclassHeadsForResolution() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Eq<A> {
                fun label(value: A): String
            }

            @Typeclass
            interface Ord<A> : Eq<A> {
                fun compare(left: A, right: A): Int
            }

            @JvmInline
            value class Foo(val value: Int)

            @Instance
            object FooOrd : Ord<Foo> {
                override fun label(value: Foo): String = "foo:${'$'}{value.value}"

                override fun compare(left: Foo, right: Foo): Int = left.value.compareTo(right.value)
            }

            @JvmInline
            @DeriveVia(Ord::class, Foo::class)
            value class UserId(val value: Int)

            context(eq: Eq<A>)
            fun <A> render(value: A): String = eq.label(value)

            fun main() {
                println(render(UserId(7)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "foo:7",
        )
    }

    @Test fun deriveViaRejectsUnsupportedInheritedAbstractMembers() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            data class MutableBox<A>(var value: A)

            @Typeclass
            interface Render<A> {
                fun renderAll(value: MutableBox<A>): MutableBox<A>
            }

            @Typeclass
            interface Pretty<A> : Render<A> {
                fun pretty(value: A): String
            }

            @JvmInline
            value class Foo(val value: Int)

            @Instance
            object FooPretty : Pretty<Foo> {
                override fun renderAll(value: MutableBox<Foo>): MutableBox<Foo> = value

                override fun pretty(value: Foo): String = value.value.toString()
            }

            @JvmInline
            @DeriveVia(Pretty::class, Foo::class) // E:TC_CANNOT_DERIVE inherited abstract members must participate in DeriveVia validation
            value class UserId(val value: Int)
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedCannotDerive("opaque or mutable nominal containers")),
        )
    }

    @Test fun deriveViaRejectsUnsupportedTransitivelyInheritedAbstractMembers() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            data class MutableBox<A>(var value: A)

            @Typeclass
            interface Render<A> {
                fun renderAll(value: MutableBox<A>): MutableBox<A>
            }

            @Typeclass
            interface MidPretty<A> : Render<A>

            @Typeclass
            interface Pretty<A> : MidPretty<A> {
                fun pretty(value: A): String
            }

            @JvmInline
            value class Foo(val value: Int)

            @Instance
            object FooPretty : Pretty<Foo> {
                override fun renderAll(value: MutableBox<Foo>): MutableBox<Foo> = value

                override fun pretty(value: Foo): String = value.value.toString()
            }

            @JvmInline
            @DeriveVia(Pretty::class, Foo::class) // E:TC_CANNOT_DERIVE transitively inherited abstract members must participate in DeriveVia validation
            value class UserId(val value: Int)
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedCannotDerive("opaque or mutable nominal containers")),
        )
    }

    @Test fun deriveViaAdaptsInheritedAbstractMembersAcrossInterfaceChains() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            @Typeclass
            interface Render<A> {
                fun render(value: A): String
            }

            @Typeclass
            interface MidPretty<A> : Render<A>

            @Typeclass
            interface Pretty<A> : MidPretty<A> {
                fun pretty(value: A): String
            }

            @JvmInline
            value class Foo(val value: Int)

            @Instance
            object FooPretty : Pretty<Foo> {
                override fun render(value: Foo): String = "render:" + value.value

                override fun pretty(value: Foo): String = "pretty:" + value.value
            }

            @JvmInline
            @DeriveVia(Pretty::class, Foo::class)
            value class UserId(val value: Int)

            fun main() {
                val pretty = summon<Pretty<UserId>>()
                println(pretty.render(UserId(7)))
                println(pretty.pretty(UserId(7)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                render:7
                pretty:7
                """.trimIndent(),
        )
    }

    @Test fun deriveViaAdaptsPurelyInheritedAbstractTypeclassSurfaces() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            @Typeclass
            interface Render<A> {
                fun render(value: A): String
            }

            @Typeclass
            interface Pretty<A> : Render<A>

            @JvmInline
            value class Foo(val value: Int)

            @Instance
            object FooPretty : Pretty<Foo> {
                override fun render(value: Foo): String = "render:" + value.value
            }

            @JvmInline
            @DeriveVia(Pretty::class, Foo::class)
            value class UserId(val value: Int)

            fun main() {
                val pretty = summon<Pretty<UserId>>()
                println(pretty.render(UserId(9)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "render:9",
        )
    }

    @Test fun deriveViaAdaptsInheritedAbstractProperties() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.summon

            @Typeclass
            interface HasValue<A> {
                val zero: A
            }

            @Typeclass
            interface Pretty<A> : HasValue<A>

            @JvmInline
            value class Foo(val value: Int)

            @Instance
            object FooPretty : Pretty<Foo> {
                override val zero: Foo = Foo(11)
            }

            @JvmInline
            @DeriveVia(Pretty::class, Foo::class)
            value class UserId(val value: Int)

            fun main() {
                val pretty = summon<Pretty<UserId>>()
                println(pretty.zero.value)
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "11",
        )
    }

    @Test fun genericDeriveEquivTargetsAreRejectedAtTheAnnotationSite() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveEquiv

            data class PlainIntBox(val value: Int)

            @DeriveEquiv(PlainIntBox::class) // E:TC_CANNOT_DERIVE generic DeriveEquiv targets are not supported yet
            data class GenericBox<A>(val value: A)
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedCannotDerive("monomorphic")),
        )
    }

    @Test fun validatesRepeatableDeriveEquivAnnotationsInFir() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveEquiv

            data class GenericBox<A>(val value: A)

            @DeriveEquiv(GenericBox::class) // E:TC_CANNOT_DERIVE repeatable generic DeriveEquiv targets must be rejected in FIR
            @DeriveEquiv(GenericBox::class) // E:TC_CANNOT_DERIVE repeatable generic DeriveEquiv targets must be rejected in FIR
            data class PlainIntBox(val value: Int)
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedCannotDerive("monomorphic")),
        )
    }

    @Test fun validatesRepeatableDisconnectedDeriveViaPathsInFir() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @JvmInline
            @DeriveVia(Show::class, String::class) // E:TC_CANNOT_DERIVE repeatable disconnected DeriveVia paths must be rejected in FIR
            @DeriveVia(Show::class, String::class) // E:TC_CANNOT_DERIVE repeatable disconnected DeriveVia paths must be rejected in FIR
            value class UserId(val value: Int)
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedCannotDerive("derive via", "kotlin/string", "userid")),
        )
    }

    @Test fun validatesRepeatableInfeasibleDeriveEquivAnnotationsInFir() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveEquiv

            data class PlainBox(val value: Int)

            @DeriveEquiv(PlainBox::class) // E:TC_CANNOT_DERIVE repeatable infeasible DeriveEquiv requests must be rejected in FIR
            @DeriveEquiv(PlainBox::class) // E:TC_CANNOT_DERIVE repeatable infeasible DeriveEquiv requests must be rejected in FIR
            data class PositiveBox(val value: Int) {
                init {
                    require(value > 0)
                }
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedCannotDerive("equiv", "positivebox", "plainbox")),
        )
    }

    @Test fun genericDeriveViaTargetsAreRejectedAtTheAnnotationSite() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @JvmInline
            value class Foo(val value: Int)

            @Instance
            object FooShow : Show<Foo> {
                override fun show(value: Foo): String = value.value.toString()
            }

            @DeriveVia(Show::class, Foo::class) // E:TC_CANNOT_DERIVE generic DeriveVia targets are not supported yet
            data class GenericBox<A>(val value: A)
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedCannotDerive("monomorphic")),
        )
    }

    // Exact intended semantics:
    // - if the same declaration requests the same typeclass through multiple viable DeriveVia paths,
    //   those are user-visible terminal choices rather than harmless internal Equiv proof-tree differences
    // - the resulting derived instances must therefore be treated as ambiguous
    @Test fun rejectsMultipleViableDeriveViaAnnotationsForTheSameTypeclass() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @Instance
            object ViaAShow : Show<ViaA> {
                override fun show(value: ViaA): String = "a:${'$'}{value.value}"
            }

            @Instance
            object ViaBShow : Show<ViaB> {
                override fun show(value: ViaB): String = "b:${'$'}{value.value}"
            }

            @JvmInline
            value class ViaA(val value: Int)

            @JvmInline
            value class ViaB(val value: Int)

            @DeriveVia(Show::class, ViaA::class)
            @DeriveVia(Show::class, ViaB::class)
            @JvmInline
            value class UserId(val value: Int)

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(UserId(3))) // E:TC_AMBIGUOUS_INSTANCE two viable DeriveVia paths produce ambiguous Show<UserId>
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics = listOf(expectedAmbiguousInstance("show", "userid")),
        )
    }

    // Exact intended semantics:
    // - duplicate identical DeriveVia annotations are redundant declarations of the same route
    // - they must not create extra refinement rules or make a single contextual call ambiguous
    @Test fun duplicateIdenticalDeriveViaAnnotationsDoNotCreateExtraRefinementCandidates() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @JvmInline
            value class Via(val value: Int)

            @Instance
            object ViaShow : Show<Via> {
                override fun show(value: Via): String = "via:${'$'}{value.value}"
            }

            @DeriveVia(Show::class, Via::class)
            @DeriveVia(Show::class, Via::class)
            @JvmInline
            value class UserId(val value: Int)

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(UserId(7)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "via:7",
        )
    }

    @Test fun failedSiblingDeriveViaBranchesMustNotLeakRecursiveFeasibilityState() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @DeriveVia(Show::class, ViaB::class)
            @JvmInline
            value class ViaA(val value: Int)

            @JvmInline
            value class ViaB(val value: Int)

            @DeriveVia(Show::class, ViaA::class)
            @DeriveVia(Show::class, ViaB::class)
            @JvmInline
            value class UserId(val value: Int)

            context(show: Show<UserId>)
            fun render(value: UserId): String = "derived:${'$'}{show.show(value)}"

            fun render(value: UserId): String = "plain:${'$'}{value.value}"

            fun main() {
                println(render(UserId(7)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "plain:7",
        )
    }

    // Exact intended semantics:
    // - for multi-parameter typeclasses, DeriveVia transports only the last type parameter
    // - so Decoder<E, A> should support deriving Decoder<String, UserId> from Decoder<String, Foo>
    @Test fun supportsDerivingViaForMultiParameterTypeclassesWhenTheLastSlotIsTransported() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Decoder<E, A> {
                fun decode(input: E): A
            }

            @JvmInline
            value class Foo(val value: Int)

            @Instance
            object StringFooDecoder : Decoder<String, Foo> {
                override fun decode(input: String): Foo = Foo(input.toInt())
            }

            @JvmInline
            @DeriveVia(Decoder::class, Foo::class)
            value class UserId(val value: Int)

            context(decoder: Decoder<E, A>)
            fun <E, A> decode(input: E): A = decoder.decode(input)

            fun main() {
                println(decode<String, UserId>("41"))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "UserId(value=41)",
        )
    }

    // Exact intended semantics:
    // - if the annotated type appears in a non-final typeclass slot, DeriveVia does not transport it
    // - so annotating UserId for Encoder<A, E> must not synthesize Encoder<UserId, String> from Encoder<Foo, String>
    @Test fun rejectsAssumingDeriveViaTransportsNonFinalTypeParameters() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Encoder<A, E> {
                fun encode(value: A): E
            }

            @JvmInline
            value class Foo(val value: Int)

            @Instance
            object FooStringEncoder : Encoder<Foo, String> {
                override fun encode(value: Foo): String = value.value.toString()
            }

            @JvmInline
            @DeriveVia(Encoder::class, Foo::class)
            value class UserId(val value: Int)

            fun main() {
                val encoder = one.wabbit.typeclass.summon<Encoder<UserId, String>>() // E:TC_NO_CONTEXT_ARGUMENT DeriveVia only transports the final typeclass slot
                println(encoder.encode(UserId(1)))
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedNoContextArgument(),
                ),
        )
    }

    // Exact intended semantics:
    // - automatic Equiv synthesis for value classes is only valid for transparent total wrappers
    // - a validating wrapper like PositiveInt is not transparently equivalent to Int, because Int -> PositiveInt is partial
    // - therefore DeriveVia(Show::class, Int::class) must fail here
    @Test fun rejectsAutomaticEquivForValidatedValueClasses() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
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

            @JvmInline
            @DeriveVia(Show::class, Int::class) // E:TC_CANNOT_DERIVE validated wrappers are not transparent total value classes
            value class PositiveInt(val value: Int) {
                init {
                    require(value > 0)
                }
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedCannotDerive("derive via", "positiveint"),
                ),
        )
    }

    // Exact intended semantics:
    // - transparent-looking structural products must still be rejected when they carry extra backing state
    // - extra non-constructor fields invalidate structural Equiv synthesis even if the primary-constructor shape matches
    @Test fun rejectsStructuralDeriveEquivForProductsWithExtraBackingFields() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveEquiv

            data class PlainBox(val value: Int)

            @DeriveEquiv(PlainBox::class) // E:TC_CANNOT_DERIVE extra backing fields break structural Equiv transparency
            data class StatefulBox(val value: Int) {
                private var cached: Int? = null
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedCannotDerive("equiv", "statefulbox", "plainbox"),
                ),
        )
    }

    // Exact intended semantics:
    // - delegated stored properties add hidden state and are outside the transparency whitelist
    // - a would-be transparent product using property delegation must therefore be rejected
    @Test fun rejectsStructuralDeriveEquivForProductsWithDelegatedStoredProperties() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveEquiv

            data class PlainBox(val value: Int)

            @DeriveEquiv(PlainBox::class) // E:TC_CANNOT_DERIVE delegated stored state is outside the transparency whitelist
            data class DelegatedBox(val value: Int) {
                val cached: Int by lazy { value }
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedCannotDerive("equiv", "delegatedbox", "plainbox"),
                ),
        )
    }

    // Exact intended semantics:
    // - secondary constructors introduce additional construction paths and therefore violate the transparency whitelist
    // - structural DeriveEquiv must reject products that would otherwise match but define such constructors
    @Test fun rejectsStructuralDeriveEquivForProductsWithSecondaryConstructors() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveEquiv

            data class PlainBox(val value: Int)

            @DeriveEquiv(PlainBox::class) // E:TC_CANNOT_DERIVE secondary constructors break structural Equiv transparency
            data class SecondaryBox(val value: Int) {
                constructor(text: String) : this(text.length)
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedCannotDerive("equiv", "secondarybox", "plainbox"),
                ),
        )
    }

    // Exact intended semantics:
    // - direct A values and ordinary function positions should transport independently of the rest of the `Fancy<A>`
    //   surface
    @Test fun supportsTransportAcrossDirectValuesAndOrdinaryFunctionPositions() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Fancy<A> {
                val default: A
                fun render(value: A): String
                fun predicate(): (A) -> Boolean
                fun accepts(pred: (A) -> Boolean): Boolean
            }

            @JvmInline
            value class Foo(val value: Int)

            @Instance
            object FooFancy : Fancy<Foo> {
                override val default: Foo = Foo(0)

                override fun render(value: Foo): String = "foo:${'$'}{value.value}"

                override fun predicate(): (Foo) -> Boolean = { value -> value.value % 2 == 0 }

                override fun accepts(pred: (Foo) -> Boolean): Boolean = pred(Foo(4))
            }

            @JvmInline
            @DeriveVia(Fancy::class, Foo::class)
            value class UserId(val value: Int)

            context(fancy: Fancy<A>)
            fun <A> probe(value: A): String {
                val predicate = fancy.predicate()
                return listOf(
                    fancy.render(value),
                    predicate(value).toString(),
                    fancy.accepts(predicate).toString(),
                    fancy.default.toString(),
                ).joinToString("|")
            }

            fun main() {
                println(probe(UserId(7)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "foo:7|false|true|UserId(value=0)",
        )
    }

    // Exact intended semantics:
    // - transparent structural wrappers with covariant, contravariant, invariant, and phantom A-occurrences should
    //   transport independently of suspend/extension/context machinery
    // - the contract here is extensional only: repeated calls may allocate fresh wrappers, so `===` and allocation
    //   patterns are not part of the promised semantics
    @Test fun supportsTransportAcrossStructuralProductWrappers() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            data class CovBox<X>(val value: X)
            data class ContraBox<X>(val accepts: (X) -> Boolean)
            data class InvariantBox<X>(val encode: (X) -> String, val decode: (String) -> X)
            data class PhantomBox<X>(val tag: String)

            @Typeclass
            interface Fancy<A> {
                fun covariantBox(value: A): CovBox<A>
                fun contravariantBox(): ContraBox<A>
                fun invariantBox(): InvariantBox<A>
                fun phantomBox(): PhantomBox<A>
            }

            @JvmInline
            value class Foo(val value: Int)

            @Instance
            object FooFancy : Fancy<Foo> {
                override fun covariantBox(value: Foo): CovBox<Foo> = CovBox(value)

                override fun contravariantBox(): ContraBox<Foo> = ContraBox { value -> value.value >= 0 }

                override fun invariantBox(): InvariantBox<Foo> =
                    InvariantBox(
                        encode = { value -> "box:${'$'}{value.value}" },
                        decode = { text -> Foo(text.removePrefix("box:").toInt()) },
                    )

                override fun phantomBox(): PhantomBox<Foo> = PhantomBox("phantom")
            }

            @JvmInline
            @DeriveVia(Fancy::class, Foo::class)
            value class UserId(val value: Int)

            context(fancy: Fancy<A>)
            fun <A> probe(value: A): String {
                val invariant = fancy.invariantBox()
                return listOf(
                    fancy.covariantBox(value).toString(),
                    fancy.contravariantBox().accepts(value).toString(),
                    invariant.encode(value),
                    invariant.decode("box:9").toString(),
                    fancy.phantomBox().tag,
                ).joinToString("|")
            }

            fun main() {
                println(probe(UserId(7)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "CovBox(value=UserId(value=7))|true|box:7|UserId(value=9)|phantom",
        )
    }

    // Exact intended semantics:
    // - suspend members and extension receivers should transport independently of structural wrappers or context
    //   receivers
    @Test fun supportsTransportAcrossSuspendFunctionsAndExtensionReceivers() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Fancy<A> {
                suspend fun decode(text: String): A
                fun A.decorate(prefix: String): String
            }

            @JvmInline
            value class Foo(val value: Int)

            @Instance
            object FooFancy : Fancy<Foo> {
                override suspend fun decode(text: String): Foo = Foo(text.toInt())

                override fun Foo.decorate(prefix: String): String = "${'$'}prefix:${'$'}value"
            }

            @JvmInline
            @DeriveVia(Fancy::class, Foo::class)
            value class UserId(val value: Int)

            context(fancy: Fancy<A>)
            fun <A> probe(value: A): String =
                listOf(
                    with(fancy) { value.decorate("decor") },
                    kotlinx.coroutines.runBlocking { fancy.decode("11") }.toString(),
                ).joinToString("|")

            fun main() {
                println(probe(UserId(7)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "decor:7|UserId(value=11)",
            requiredPlugins = coroutinesRuntime,
        )
    }

    // Exact intended semantics:
    // - context receivers whose context does not mention A should transport independently
    @Test fun supportsTransportAcrossContextIndependentContextReceivers() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            data class Prefix(val value: String)

            @Typeclass
            interface Fancy<A> {
                context(prefix: Prefix)
                fun contextualRender(value: A): String
            }

            @JvmInline
            value class Foo(val value: Int)

            @Instance
            object FooFancy : Fancy<Foo> {
                context(prefix: Prefix)
                override fun contextualRender(value: Foo): String = "${'$'}{prefix.value}:${'$'}{value.value}"
            }

            @JvmInline
            @DeriveVia(Fancy::class, Foo::class)
            value class UserId(val value: Int)

            context(fancy: Fancy<A>)
            fun <A> probe(value: A): String =
                with(Prefix("ctx")) { fancy.contextualRender(value) }

            fun main() {
                println(probe(UserId(7)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "ctx:7",
        )
    }

    // Exact intended semantics:
    // - the transport closure F(A) should cover direct values, higher-order functions, suspend functions,
    //   extension receivers, context-independent contextual methods, and simple non-recursive ADTs that mention A
    //   in covariant, contravariant, invariant, or phantom positions
    // - the interface below intentionally mixes those shapes as a broad integration specimen
    // - DeriveVia(Fancy::class, Foo::class) should succeed because every appearance of A lies inside F(A)
    // - the contract here is extensional only: repeated calls may allocate fresh wrappers, so `===` and allocation
    //   patterns are not part of the promised semantics
    @Test fun supportsTransportAcrossStructuredHigherOrderAndContextIndependentShapes() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            data class Prefix(val value: String)

            data class CovBox<X>(val value: X)
            data class ContraBox<X>(val accepts: (X) -> Boolean)
            data class InvariantBox<X>(val encode: (X) -> String, val decode: (String) -> X)
            data class PhantomBox<X>(val tag: String)

            @Typeclass
            interface Fancy<A> {
                val default: A
                fun render(value: A): String
                fun predicate(): (A) -> Boolean
                fun accepts(pred: (A) -> Boolean): Boolean
                fun covariantBox(value: A): CovBox<A>
                fun contravariantBox(): ContraBox<A>
                fun invariantBox(): InvariantBox<A>
                fun phantomBox(): PhantomBox<A>
                suspend fun decode(text: String): A
                fun A.decorate(prefix: String): String
                context(prefix: Prefix)
                fun contextualRender(value: A): String
            }

            @JvmInline
            value class Foo(val value: Int)

            @Instance
            object FooFancy : Fancy<Foo> {
                override val default: Foo = Foo(0)

                override fun render(value: Foo): String = "foo:${'$'}{value.value}"

                override fun predicate(): (Foo) -> Boolean = { value -> value.value % 2 == 0 }

                override fun accepts(pred: (Foo) -> Boolean): Boolean = pred(Foo(4))

                override fun covariantBox(value: Foo): CovBox<Foo> = CovBox(value)

                override fun contravariantBox(): ContraBox<Foo> = ContraBox { value -> value.value >= 0 }

                override fun invariantBox(): InvariantBox<Foo> =
                    InvariantBox(
                        encode = { value -> "box:${'$'}{value.value}" },
                        decode = { text -> Foo(text.removePrefix("box:").toInt()) },
                    )

                override fun phantomBox(): PhantomBox<Foo> = PhantomBox("phantom")

                override suspend fun decode(text: String): Foo = Foo(text.toInt())

                override fun Foo.decorate(prefix: String): String = "${'$'}prefix:${'$'}value"

                context(prefix: Prefix)
                override fun contextualRender(value: Foo): String = "${'$'}{prefix.value}:${'$'}{value.value}"
            }

            // Spec:
            // - DeriveVia(Fancy::class, Foo::class) should transport every Fancy member because all
            //   A-occurrences are inside the supported closure F(A)
            @JvmInline
            @DeriveVia(Fancy::class, Foo::class)
            value class UserId(val value: Int)

            context(fancy: Fancy<A>)
            fun <A> probe(value: A): String {
                val predicate = fancy.predicate()
                val invariant = fancy.invariantBox()
                return buildList {
                    add(fancy.render(value))
                    add(predicate(value).toString())
                    add(fancy.accepts(predicate).toString())
                    add(fancy.covariantBox(value).toString())
                    add(fancy.contravariantBox().accepts(value).toString())
                    add(invariant.encode(value))
                    add(invariant.decode("box:9").toString())
                    add(fancy.phantomBox().tag)
                    add(with(fancy) { value.decorate("decor") })
                    add(with(Prefix("ctx")) { fancy.contextualRender(value) })
                    add(fancy.default.toString())
                    add(kotlinx.coroutines.runBlocking { fancy.decode("11") }.toString())
                }.joinToString("|")
            }

            fun main() {
                println(probe(UserId(7)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                "foo:7|false|true|CovBox(value=UserId(value=7))|true|box:7|UserId(value=9)|phantom|decor:7|ctx:7|UserId(value=0)|UserId(value=11)",
            requiredPlugins = coroutinesRuntime,
        )
    }

    // Exact intended semantics:
    // - nullability, type aliases, star projections, inherited default methods, and unconstrained extra type
    //   parameters are all allowed when their A-occurrences remain inside F(A)
    @Test fun supportsTransportAcrossNullabilityAliasesGenericMethodsStarProjectionsAndInheritedDefaults() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            data class PairBox<X, Y>(val first: X, val second: Y)
            data class Witness<X>(val value: X)
            typealias Alias<X> = PairBox<X, String>

            @Typeclass
            interface Fancy<A> {
                fun render(value: A): String
                fun maybe(value: A?): A?
                fun <T> pair(value: A, other: T): PairBox<A, T>
                fun alias(value: A): Alias<A>
                fun observe(other: Witness<*>): String = other.value.toString()
                fun bracketed(value: A): String = "[" + render(value) + "]"
            }

            @JvmInline
            value class Foo(val value: Int)

            @Instance
            object FooFancy : Fancy<Foo> {
                override fun render(value: Foo): String = "foo:${'$'}{value.value}"
                override fun maybe(value: Foo?): Foo? = value?.let { Foo(it.value + 1) }
                override fun <T> pair(value: Foo, other: T): PairBox<Foo, T> = PairBox(value, other)
                override fun alias(value: Foo): Alias<Foo> = PairBox(value, "alias")
            }

            @JvmInline
            @DeriveVia(Fancy::class, Foo::class)
            value class UserId(val value: Int)

            context(fancy: Fancy<A>)
            fun <A> probe(value: A): String =
                listOf(
                    fancy.render(value),
                    fancy.bracketed(value),
                    fancy.maybe(value).toString(),
                    fancy.pair(value, 7).toString(),
                    fancy.alias(value).toString(),
                    fancy.observe(Witness("star")),
                ).joinToString("|")

            fun main() {
                println(probe(UserId(3)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                "foo:3|[foo:3]|UserId(value=4)|PairBox(first=UserId(value=3), second=7)|PairBox(first=UserId(value=3), second=alias)|star",
        )
    }

    // Exact intended semantics:
    // - DeriveVia only needs to transport the effective abstract typeclass surface
    // - concrete helper members can mention A in unsupported ways because the adapter inherits them unchanged
    @Test fun ignoresConcreteHelperMembersWhenValidatingFirTransportability() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Fancy<A> {
                fun render(value: A): String

                context(dep: A)
                fun helper(): String = dep.toString()
            }

            @JvmInline
            value class Foo(val value: Int)

            @Instance
            object FooFancy : Fancy<Foo> {
                override fun render(value: Foo): String = "foo:${'$'}{value.value}"
            }

            @JvmInline
            @DeriveVia(Fancy::class, Foo::class)
            value class UserId(val value: Int)

            context(fancy: Fancy<A>)
            fun <A> probe(value: A): String = fancy.render(value)

            fun main() {
                println(probe(UserId(7)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "foo:7",
        )
    }

    // Exact intended semantics:
    // - the transport closure F(A) does not automatically admit arbitrary nominal containers
    // - nominal types like List<A> or Comparator<A> are outside the structural subset unless the compiler grows
    //   an explicit rule for them
    // - therefore DeriveVia should reject a typeclass whose only A-occurrence is inside List<A>
    @Test fun rejectsTransportAcrossUnspecifiedNominalContainers() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Fancy<A> {
                fun values(): List<A>
            }

            @JvmInline
            value class Foo(val value: Int)

            @Instance
            object FooFancy : Fancy<Foo> {
                override fun values(): List<Foo> = listOf(Foo(1), Foo(2))
            }

            @JvmInline
            @DeriveVia(Fancy::class, Foo::class) // E:TC_CANNOT_DERIVE List<A> is an opaque nominal container here
            value class UserId(val value: Int)
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedCannotDerive("opaque or mutable nominal containers"),
                ),
        )
    }

    // Exact intended semantics:
    // - structural nominal shapes in F(A) must expose only read-only stored state
    // - mutable `var` fields are outside the allowed closure, because a transport adapter would copy the box and
    //   lose aliasing/mutation behavior
    @Test fun rejectsTransportAcrossMutableStructuralShapes() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            data class Box<X>(var value: X)

            @Typeclass
            interface Fancy<A> {
                fun box(value: A): Box<A>
            }

            @JvmInline
            value class Foo(val value: Int)

            @Instance
            object FooFancy : Fancy<Foo> {
                override fun box(value: Foo): Box<Foo> = Box(value)
            }

            @JvmInline
            @DeriveVia(Fancy::class, Foo::class) // E:TC_CANNOT_DERIVE mutable structural boxes are outside the transport closure
            value class UserId(val value: Int)
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedCannotDerive("opaque or mutable nominal containers"),
                ),
        )
    }

    // Exact intended semantics:
    // - contextual method shapes are only in F(A) when their context parameter types do not mention A
    // - a member whose context parameter depends on A is outside the allowed transport closure
    @Test fun rejectsTransportWhenContextParametersDependOnA() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Env<A> {
                fun tag(): String
            }

            @Typeclass
            interface Fancy<A> {
                context(env: Env<A>)
                fun render(value: A): String
            }

            @JvmInline
            value class Foo(val value: Int)

            @Instance
            object FooFancy : Fancy<Foo> {
                context(env: Env<Foo>)
                override fun render(value: Foo): String = env.tag() + ":" + value.value
            }

            @JvmInline
            @DeriveVia(Fancy::class, Foo::class) // E:TC_CANNOT_DERIVE context parameters must not mention the transported type parameter
            value class UserId(val value: Int)
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedCannotDerive("context parameters", "transported type parameter"),
                ),
        )
    }

    // Exact intended semantics:
    // - additional method type parameters are rigid opaque variables
    // - transportability must therefore inspect the actual type structure rather than text-rendered names
    // - unrelated definitely-non-null uses like method-local `A & Any` must not be mistaken for the transported class-level `A`
    @Test fun allowsOpaqueMethodTypeParametersInsideUnrelatedDefinitelyNonNullTypes() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Fancy<A> {
                fun <A> witness(tag: A & Any): A & Any
            }

            @JvmInline
            value class Foo(val value: Int)

            @Instance
            object FooFancy : Fancy<Foo> {
                override fun <A> witness(tag: A & Any): A & Any = tag
            }

            @JvmInline
            @DeriveVia(Fancy::class, Foo::class)
            value class UserId(val value: Int)

            context(fancy: Fancy<UserId>)
            fun render(): String = fancy.witness("age").toString()

            fun main() {
                println(render())
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "age",
        )
    }

    // Exact intended semantics:
    // - additional method type parameters are allowed only when their bounds do not mention A
    // - a method like fun <T : A> narrow(value: T): A is therefore outside the supported transport boundary
    @Test fun rejectsTransportAcrossGenericMethodBoundsMentioningA() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Fancy<A> {
                fun <T : A> narrow(value: T): A
            }

            @JvmInline
            value class Foo(val value: Int)

            @Instance
            object FooFancy : Fancy<Foo> {
                override fun <T : Foo> narrow(value: T): Foo = value
            }

            @JvmInline
            @DeriveVia(Fancy::class, Foo::class) // E:TC_CANNOT_DERIVE method bounds mentioning the transported type parameter are unsupported
            value class UserId(val value: Int)
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedCannotDerive("type-parameter bounds", "transported type parameter"),
                ),
        )
    }

    // Exact intended semantics:
    // - inherited abstract members are part of the transported typeclass surface
    // - DeriveVia must therefore validate inherited method bounds the same way it validates direct members
    // - the inherited invalid member must be rejected during FIR validation rather than being discovered only later
    @Test fun rejectsTransportAcrossInheritedGenericMethodBoundsMentioningA() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface BaseFancy<A> {
                fun <T : A> narrow(value: T): A
            }

            @Typeclass
            interface Fancy<A> : BaseFancy<A>

            @JvmInline
            value class Foo(val value: Int)

            @Instance
            object FooFancy : Fancy<Foo> {
                override fun <T : Foo> narrow(value: T): Foo = value
            }

            @JvmInline
            @DeriveVia(Fancy::class, Foo::class) // E:TC_CANNOT_DERIVE inherited method bounds mentioning the transported type parameter are unsupported
            value class UserId(val value: Int)
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedCannotDerive("type-parameter bounds", "transported type parameter"),
                ),
            unexpectedMessages =
                listOf(
                    "no context argument",
                    "required instance",
                    "overload resolution ambiguity",
                ),
        )
    }

    // Exact intended semantics:
    // - definitely-non-null / intersection forms like A & Any are outside the supported transport boundary for now
    @Test fun rejectsTransportAcrossDefinitelyNonNullIntersectionShapes() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Fancy<A> {
                fun strict(value: A & Any): A & Any
            }

            @JvmInline
            value class Foo(val value: Int)

            @Instance
            object FooFancy : Fancy<Foo> {
                override fun strict(value: Foo): Foo = value
            }

            @JvmInline
            @DeriveVia(Fancy::class, Foo::class) // E:TC_CANNOT_DERIVE definitely-non-null and intersection member types are unsupported
            value class UserId(val value: Int)
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedCannotDerive("definitely-non-null", "intersection member types"),
                ),
        )
    }

    // Exact intended semantics:
    // - recursive nominal shapes are outside F(A) by default
    // - even when their fields are otherwise transparent, the transport closure only admits finite non-recursive shapes
    @Test fun rejectsTransportAcrossRecursiveNominalShapes() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            data class Node<A>(val value: A, val next: Node<A>?)

            @Typeclass
            interface Fancy<A> {
                fun node(value: A): Node<A>
            }

            @JvmInline
            value class Foo(val value: Int)

            @Instance
            object FooFancy : Fancy<Foo> {
                override fun node(value: Foo): Node<Foo> = Node(value, null)
            }

            @JvmInline
            @DeriveVia(Fancy::class, Foo::class) // E:TC_CANNOT_DERIVE recursive nominal transport shapes are unsupported
            value class UserId(val value: Int)
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedCannotDerive("recursive nominal transport shapes"),
                ),
        )
    }

    // Exact intended semantics:
    // - Unit fields are zero-information positions and may be inserted/elided during structural Equiv synthesis
    // - ambiguity checking for sums must happen after that Unit-normalization, not before
    // - therefore a nullary case and a unary Unit case collide and must make DeriveEquiv fail
    @Test fun rejectsStructuralDeriveEquivWhenUnitNormalizationMakesSumCasesAmbiguous() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveEquiv

            sealed interface RightShape {
                data object Empty : RightShape
            }

            // Spec:
            // - after Unit-normalization, Empty and UnitBox(Unit) are both nullary cases
            // - both can map to RightShape.Empty, so the sum match is ambiguous
            // - DeriveEquiv(RightShape::class) must therefore fail at compile time
            @DeriveEquiv(RightShape::class) // E:TC_CANNOT_DERIVE Unit-normalized sum cases become ambiguous here
            sealed interface LeftShape {
                data object Empty : LeftShape
                data class UnitBox(val unit: Unit) : LeftShape
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedCannotDerive("equiv", "leftshape", "rightshape"),
                ),
        )
    }

    // Exact intended semantics:
    // - product permutation is only allowed when it is unambiguous
    // - repeated field types can make multiple permutations equally valid
    // - in that case DeriveEquiv must fail rather than guessing a field alignment
    @Test fun rejectsStructuralDeriveEquivWhenProductPermutationIsAmbiguous() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveEquiv

            data class RightShape(val x: String, val y: String, val z: Int)

            @DeriveEquiv(RightShape::class) // E:TC_CANNOT_DERIVE repeated field types make the product permutation ambiguous
            data class LeftShape(val a: String, val b: Int, val c: String)
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedCannotDerive("equiv", "leftshape", "rightshape"),
                ),
        )
    }

    // Exact intended semantics:
    // - products are matched positionally first
    // - repeated field types do not create ambiguity when the positional match already succeeds
    // - unambiguous permutation is only needed when positional alignment fails
    @Test fun prefersPositionalProductMatchEvenWhenRepeatedFieldTypesExist() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveEquiv
            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            data class RightShape(val left: String, val count: Int, val right: String)

            @Instance
            object RightShapeShow : Show<RightShape> {
                override fun show(value: RightShape): String = "${'$'}{value.left}|${'$'}{value.count}|${'$'}{value.right}"
            }

            @DeriveEquiv(RightShape::class)
            @DeriveVia(Show::class, RightShape::class)
            data class LeftShape(val first: String, val amount: Int, val second: String)

            context(show: Show<A>)
            fun <A> render(value: A): String = show.show(value)

            fun main() {
                println(render(LeftShape("x", 1, "y")))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "x|1|y",
        )
    }

    // Exact intended semantics:
    // - because Equiv is compiler-owned canonical evidence, users must not be allowed to declare
    //   ordinary @Instance values for Equiv<A, B>
    // - the explicit, user-authored escape hatch is Iso<A, B>, referenced by pinned DeriveVia path segments
    // - attempting to publish a user @Instance Equiv should fail at compile time
    @Test fun rejectsUserAuthoredEquivInstances() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.InternalTypeclassApi
            import one.wabbit.typeclass.Instance

            data class UserId(val value: Int)

            @OptIn(InternalTypeclassApi::class)
            @Instance
            object UserIdIntEquiv : one.wabbit.typeclass.Equiv<UserId, Int>() { // err@E:TC_INVALID_EQUIV_DECL Equiv is compiler-owned
                override fun to(value: UserId): Int = value.value
                override fun from(value: Int): UserId = UserId(value)
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                mapOf(
                    "err" to invalidEquivSubclassing(),
                ),
        )
    }

    // Exact intended semantics:
    // - "compiler-owned" is stronger than "not an @Instance"
    // - users must not subclass or manually construct library Equiv values even if they never enter instance search
    // - the escape hatch for explicit user conversions remains Iso<A, B>, not hand-made Equiv objects
    @Test fun rejectsManualUserSubclassingOfEquiv() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.InternalTypeclassApi

            data class UserId(val value: Int)

            @OptIn(InternalTypeclassApi::class)
            object SneakyEquiv : one.wabbit.typeclass.Equiv<UserId, Int>() { // err@E:TC_INVALID_EQUIV_DECL Equiv is compiler-owned
                override fun to(value: UserId): Int = value.value
                override fun from(value: Int): UserId = UserId(value)
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                mapOf(
                    "err" to invalidEquivSubclassing(),
                ),
        )
    }

    // Exact intended semantics:
    // - DeriveVia may pin explicit Iso objects in the path.
    // - Each pinned Iso object contributes one exact conversion segment; the compiler must not search for
    //   alternative Iso values for that segment.
    // - Compiler-solved Equiv glue may still be inserted before or after that pinned segment, but only if exactly
    //   one endpoint is reachable from the current state.
    // - Path orientation is inferred from that uniquely reachable endpoint.
    // - The final via type must have the requested typeclass instance, which is then transported back to the target type.
    @Test fun supportsDerivingViaPinnedIsoObjectChains() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Equiv
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Iso
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Monoid<A> {
                fun empty(): A
                fun combine(left: A, right: A): A
            }

            data class Token(val raw: Int)

            class Foo(val value: Int)

            @Instance
            object FooMonoid : Monoid<Foo> {
                override fun empty(): Foo = Foo(0)

                override fun combine(left: Foo, right: Foo): Foo = Foo(left.value + right.value)
            }

            object FooIso : Iso<Foo, Token> {
                override fun to(value: Foo): Token = Token(value.value)
                override fun from(value: Token): Foo = Foo(value.raw)
            }

            @JvmInline
            value class UserId(val value: Int)

            object UserIdIso : Iso<UserId, Token> {
                override fun to(value: UserId): Token = Token(value.value)
                override fun from(value: Token): UserId = UserId(value.raw)
            }

            @JvmInline
            @DeriveVia(Monoid::class, UserIdIso::class, FooIso::class)
            value class DerivedUserId(val value: UserId)

            // Spec:
            // - pinned Iso segments are exact objects, but the solver may insert Equiv glue around them when
            //   exactly one endpoint is reachable
            // - DeriveVia(Monoid::class, UserIdIso::class, FooIso::class) means:
            //   DerivedUserId --Equiv--> UserId --UserIdIso--> Token --FooIso.inverse--> Foo
            // - this actually exercises the pinned segments: neither Token nor Foo is transparently Equiv-reachable
            //   from DerivedUserId, so the pins are doing real path work instead of being ornamental
            // - require Monoid<Foo>
            // - synthesize Monoid<DerivedUserId> by transporting every Monoid method through that exact chain
            context(monoid: Monoid<A>)
            fun <A> combineTwice(value: A): A = monoid.combine(value, monoid.combine(monoid.empty(), value))

            fun main() {
                println(combineTwice(DerivedUserId(UserId(7))))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "DerivedUserId(value=UserId(value=14))",
        )
    }

    // Exact intended semantics:
    // - a pinned path segment must name an object singleton implementing Iso
    // - naming an ordinary class that implements Iso is invalid because it does not identify one exact edge value
    @Test fun rejectsPinnedIsoSegmentsThatAreNotObjects() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Iso
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @JvmInline
            value class Foo(val value: Int)

            @Instance
            object FooShow : Show<Foo> {
                override fun show(value: Foo): String = "foo:${'$'}{value.value}"
            }

            class BadIso : Iso<Foo, Int> {
                override fun to(value: Foo): Int = value.value
                override fun from(value: Int): Foo = Foo(value)
            }

            @JvmInline
            @DeriveVia(Show::class, BadIso::class) // E:TC_CANNOT_DERIVE pinned Iso segments must name object singletons
            value class UserId(val value: Int)
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedCannotDerive("pinned iso", "object singleton"),
                ),
        )
    }

    // Exact intended semantics:
    // - a pinned Iso object is identified by the actual Iso override pair, not by name-plus-arity alone
    // - extra one-argument helper overloads must not make a valid pinned segment fail validation
    @Test fun supportsPinnedIsoObjectsEvenWhenTheyDeclareExtraOneArgHelpers() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Iso
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @JvmInline
            value class Foo(val value: Int)

            @Instance
            object FooShow : Show<Foo> {
                override fun show(value: Foo): String = "foo:${'$'}{value.value}"
            }

            data class Token(val raw: Int)

            object TokenFooIso : Iso<Token, Foo> {
                override fun to(value: Token): Foo = Foo(value.raw)

                override fun from(value: Foo): Token = Token(value.value)

                fun to(value: Int): Int = value + 1

                fun from(value: String): String = value.reversed()
            }

            @JvmInline
            @DeriveVia(Show::class, TokenFooIso::class)
            value class UserId(val value: Token)

            context(show: Show<UserId>)
            fun render(value: UserId): String = show.show(value)

            fun main() {
                println(render(UserId(Token(9))))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "foo:9",
        )
    }

    // Exact intended semantics:
    // - pinned Iso segments may use inserted Equiv glue around them
    // - but if no Equiv-augmented attachment can connect the current type to either endpoint, the path is disconnected
    // - a disconnected pinned path must fail rather than being ignored
    @Test fun rejectsDisconnectedPinnedIsoPathsEvenWithAllowedEquivGlue() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Iso
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @JvmInline
            value class Foo(val value: String)

            @Instance
            object FooShow : Show<Foo> {
                override fun show(value: Foo): String = value.value
            }

            @JvmInline
            value class Mid(val value: String)

            object MidFooIso : Iso<Mid, Foo> {
                override fun to(value: Mid): Foo = Foo(value.value)
                override fun from(value: Foo): Mid = Mid(value.value)
            }

            @JvmInline
            @DeriveVia(Show::class, MidFooIso::class) // E:TC_CANNOT_DERIVE disconnected pinned Iso paths must fail
            value class UserId(val value: Int)
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedCannotDerive("disconnected", "midfooiso"),
                ),
        )
    }

    // Exact intended semantics:
    // - if the current type is Equiv-reachable to both endpoints of a pinned Iso, the attachment is already ambiguous
    // - DeriveVia must fail immediately rather than trying both orientations
    @Test fun rejectsPinnedIsoPathsWhenMultipleOrientationsRemainViable() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Iso
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            @JvmInline
            value class LeftVia(val value: Int)

            @JvmInline
            value class RightVia(val value: Int)

            @Instance
            object LeftShow : Show<LeftVia> {
                override fun show(value: LeftVia): String = "left:${'$'}{value.value}"
            }

            @Instance
            object RightShow : Show<RightVia> {
                override fun show(value: RightVia): String = "right:${'$'}{value.value}"
            }

            object BridgeIso : Iso<LeftVia, RightVia> {
                override fun to(value: LeftVia): RightVia = RightVia(value.value)
                override fun from(value: RightVia): LeftVia = LeftVia(value.value)
            }

            @JvmInline
            @DeriveVia(Show::class, BridgeIso::class) // E:TC_CANNOT_DERIVE pinned Iso attachment is ambiguous when both endpoints are reachable
            value class UserId(val value: Int)

            // Spec:
            // - assume UserId is Equiv-reachable to both LeftVia and RightVia
            // - that already makes the pinned BridgeIso attachment ambiguous:
            //   UserId --Equiv--> LeftVia --BridgeIso--> ...
            //   UserId --Equiv--> RightVia --BridgeIso.inverse--> ...
            // - DeriveVia must therefore fail immediately rather than trying to continue either path
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedCannotDerive("ambiguous", "bridgeiso", "both endpoints"),
                ),
        )
    }

    // Exact intended semantics:
    // - DeriveEquiv(otherClass) should synthesize a total Equiv<AnnotatedClass, otherClass>.
    // - Because the annotation is placed on the annotated class, that class is always one endpoint of the proof.
    // - This is only valid for the conservative structural whitelist above; anything outside it is rejected.
    //
    // @Target(AnnotationTarget.CLASS)
    // @Repeatable
    // annotation class DeriveEquiv(
    //     val otherClass: KClass<*>,
    // )

    // - unambiguous product permutation should be sufficient for structural DeriveEquiv on products
    @Test fun supportsStructuralDeriveEquivForUnambiguousProductPermutation() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveEquiv
            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            data class A(val a: Int, val b: String)

            @Instance
            object AShow : Show<A> {
                override fun show(value: A): String = "${'$'}{value.a}|${'$'}{value.b}"
            }

            @DeriveEquiv(A::class)
            @DeriveVia(Show::class, A::class)
            data class B(val x: String, val y: Int)

            context(show: Show<T>)
            fun <T> render(value: T): String = show.show(value)

            fun main() {
                println(render(B("x", 1)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "1|x",
        )
    }

    // - unambiguous sum-case matching should be sufficient for structural DeriveEquiv on sums
    @Test fun supportsStructuralDeriveEquivForUnambiguousSumMatching() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveEquiv
            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            sealed interface A {
                data object Empty : A
                data class NonEmpty(val a: Int, val b: String) : A
            }

            @Instance
            object AShow : Show<A> {
                override fun show(value: A): String =
                    when (value) {
                        A.Empty -> "empty"
                        is A.NonEmpty -> "${'$'}{value.a}|${'$'}{value.b}"
                    }
            }

            @DeriveEquiv(A::class)
            @DeriveVia(Show::class, A::class)
            sealed interface B {
                data object Wuzzle : B
                data class Wozzle(val a: Int, val b: String) : B
            }

            context(show: Show<T>)
            fun <T> render(value: T): String = show.show(value)

            fun main() {
                val empty: B = B.Wuzzle
                val nonEmpty: B = B.Wozzle(1, "x")
                println(render(empty))
                println(render(nonEmpty))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                empty
                1|x
                """.trimIndent(),
        )
    }

    // - Unit fields may be ignored/inserted as zero-information positions when the product match remains unambiguous
    @Test fun supportsStructuralDeriveEquivForUnitElision() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveEquiv
            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.Typeclass

            @Typeclass
            interface Show<A> {
                fun show(value: A): String
            }

            data class A(val a: Int, val b: String)

            @Instance
            object AShow : Show<A> {
                override fun show(value: A): String = "${'$'}{value.a}|${'$'}{value.b}"
            }

            @DeriveEquiv(A::class)
            @DeriveVia(Show::class, A::class)
            data class B(val a: Int, val b: String, val unit: Unit)

            context(show: Show<T>)
            fun <T> render(value: T): String = show.show(value)

            fun main() {
                println(render(B(1, "x", Unit)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout = "1|x",
        )
    }

    // - Product constructors are matched positionally, or by an unambiguous permutation if the field types force it.
    // - Unit fields may be ignored/inserted as zero-information positions.
    // - Sum cases must match without ambiguity in both directions.
    // - Once these equivalences exist, DeriveVia(JsonCodec::class, B::class) should only need Equiv<C, B> plus JsonCodec<B>.
    // - This broader case remains useful as an integration specimen, but it is no longer the only place proving
    //   product permutation, sum matching, Unit elision, and via reuse.
    @Test fun supportsStructuralDeriveEquivForSumsOfProductsAndViaReuse() {
        val source =
            """
            package demo

            import kotlinx.serialization.json.JsonElement
            import kotlinx.serialization.json.JsonPrimitive
            import one.wabbit.typeclass.Derive
            import one.wabbit.typeclass.DeriveEquiv
            import one.wabbit.typeclass.DeriveVia
            import one.wabbit.typeclass.Instance
            import one.wabbit.typeclass.ProductTypeclassMetadata
            import one.wabbit.typeclass.SumTypeclassMetadata
            import one.wabbit.typeclass.Typeclass
            import one.wabbit.typeclass.TypeclassDeriver
            import one.wabbit.typeclass.get
            import one.wabbit.typeclass.matches

            @Typeclass
            interface JsonCodec<A> {
                fun encode(value: A): JsonElement
                fun decode(json: JsonElement): A

                companion object : TypeclassDeriver {
                    override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
                        object : JsonCodec<Any?> {
                            override fun encode(value: Any?): JsonElement {
                                require(value != null)
                                return JsonPrimitive(
                                    metadata.fields.joinToString("|") { field ->
                                        val codec = field.instance as JsonCodec<Any?>
                                        codec.encode(field.get(value)).toString()
                                    },
                                )
                            }

                            override fun decode(json: JsonElement): Any? = error("decode not relevant to this spec")
                        }

                    override fun deriveSum(metadata: SumTypeclassMetadata): Any =
                        object : JsonCodec<Any?> {
                            override fun encode(value: Any?): JsonElement {
                                require(value != null)
                                val matchingCase = metadata.cases.single { candidate -> candidate.matches(value) }
                                val codec = matchingCase.instance as JsonCodec<Any?>
                                return codec.encode(value)
                            }

                            override fun decode(json: JsonElement): Any? = error("decode not relevant to this spec")
                        }
                }
            }

            @Instance
            object IntJsonCodec : JsonCodec<Int> {
                override fun encode(value: Int): JsonElement = JsonPrimitive(value)
                override fun decode(json: JsonElement): Int = error("decode not relevant to this spec")
            }

            @Instance
            object StringJsonCodec : JsonCodec<String> {
                override fun encode(value: String): JsonElement = JsonPrimitive(value)
                override fun decode(json: JsonElement): String = error("decode not relevant to this spec")
            }

            sealed interface A {
                data object Empty : A
                data class NonEmpty(val a: Int, val b: String) : A
            }

            @DeriveEquiv(A::class)
            @Derive(JsonCodec::class)
            sealed interface B {
                data class Wozzle(val x: String, val y: Int) : B
                data object Wuzzle : B
            }

            // Spec:
            // - DeriveEquiv(A::class) on B should succeed because:
            //   Wuzzle <-> Empty is an unambiguous nullary case match
            //   Wozzle(String, Int) <-> NonEmpty(Int, String) is an unambiguous product permutation
            // - DeriveVia(JsonCodec::class, B::class) should then reuse JsonCodec<B> to synthesize JsonCodec<C>
            @DeriveEquiv(A::class)
            @DeriveVia(JsonCodec::class, B::class)
            sealed interface C {
                data class Foo(val z: String, val w: Int, val u: Unit) : C
                data object Bar : C
            }

            fun main() {
                val codec = one.wabbit.typeclass.summon<JsonCodec<C>>()
                println(codec.encode(C.Bar))
                println(codec.encode(C.Foo("x", 1, Unit)))
            }
            """.trimIndent()

        assertCompilesAndRuns(
            source = source,
            expectedStdout =
                """
                ""
                "\"x\"|1"
                """.trimIndent(),
            requiredPlugins = serializationRuntime,
        )
    }

    // Exact intended semantics:
    // - structural DeriveEquiv is only sound for transparent products
    // - if one side validates or normalizes in its constructor, the compiler must reject the derived Equiv
    @Test fun rejectsStructuralDeriveEquivForValidatedProducts() {
        val source =
            """
            package demo

            import one.wabbit.typeclass.DeriveEquiv

            data class PlainBox(val value: Int)

            @DeriveEquiv(PlainBox::class) // E:TC_CANNOT_DERIVE validated products are not transparently equivalent
            data class PositiveBox(val value: Int) {
                init {
                    require(value > 0)
                }
            }
            """.trimIndent()

        assertDoesNotCompile(
            source = source,
            expectedDiagnostics =
                listOf(
                    expectedCannotDerive("equiv", "positivebox", "plainbox"),
                ),
        )
    }
}

private fun showTypeclassSource(packageName: String): String =
    """
    package $packageName

    import one.wabbit.typeclass.Typeclass

    @Typeclass
    interface Show<A> {
        fun show(value: A): String
    }

    context(show: Show<A>)
    fun <A> render(value: A): String = show.show(value)
    """.trimIndent()
