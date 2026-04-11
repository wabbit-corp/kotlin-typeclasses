# Future Ideas

This file is not a committed roadmap, but more of an "idea dump". Ideas are grouped by the part of the system they affect. The highest-value items are listed first; later sections include larger bets, ecosystem work, and ideas that may require Kotlin compiler support beyond what public plugin APIs currently expose.

## Highest-Leverage Bets

- **Functional dependencies / determining parameters.** Multi-parameter typeclasses such as `Convert<From, To>`, `Lens<S, A>`, and `Codec<Wire, Domain>` need a way to declare which parameters determine the others. Without this, inference for useful two-parameter typeclasses remains too weak.
- **IDE resolution visualization.** The IntelliJ plugin should show which instance was resolved and the prerequisite chain that led to it, for example `Show<Box<Int>>` via `Box.Companion.boxShow` and `IntShow`. This is likely the highest-impact adoption feature because it makes the system understandable.
- **Static singleton caching for fully resolved prerequisite chains.** Generic instance factories that capture only stateless singleton prerequisites should produce one cached evidence object per concrete prerequisite combination, not fresh anonymous objects at each resolution.
- **`summonOrNull<T>()`.** Optional evidence is a pragmatic escape hatch for code that wants to use an instance when one exists and fall back when it does not.
- **A standard typeclass prelude.** A `kotlin-typeclasses-stdlib` module with canonical instances for common Kotlin types would prevent every consumer from reinventing incompatible `Eq<Int>`, `Show<String>`, `Monoid<String>`, and similar instances.

## Resolution and Coherence

- **Typeclass-directed inference and improvement.** Add an improvement mechanism for resolving unknown type parameters from known constraints. Functional dependencies, associated types, and determining parameters are different ways to solve this.
- **Negative instances / instance exclusion.** Add an explicit way to state that no instance should exist for a type, for example `@NoInstance Show<Secret>`, so "intentionally absent" is distinguishable from "not found".
- **Overlapping instances with explicit markers.** Support carefully controlled overlap, such as a specific `List<Int>` instance coexisting with a generic `List<A>` instance. This should require explicit markers, not happen implicitly.

## Derivation and Ergonomics

- **Bulk deriving blocks.** Avoid repeating `@Derive(Show::class, Eq::class, Ord::class)` on every type by supporting file-level, package-level, or module-level derive defaults.
- **Richer `deriveVia`.** Support multi-parameter transport, intrinsic `deriveVia<TC>(...)`, cleaner waypoint syntax, and first-class representation-safe transport.
- **Constraint aliases / bundles.** Provide named bundles of constraints, for example `Hashable<A>` as `Eq<A> + Hash<A>`, to keep complex context parameter lists readable.
- **Typeclass-constrained type aliases.** Explore desugaring named aliases for context-constrained values or functions, since Kotlin does not provide constraint aliases natively.
- **Minimal-complete-definition support.** Ordinary Kotlin default methods with context parameters already work. The remaining design is an explicit authoring story for declaring which abstract members are required, validating derived/manual instances against that minimum, and documenting the resulting law/default-method pattern.
- **Law-friendly typeclass authoring.** Make it easy for typeclass authors to publish law checks alongside the interface and reuse them for manual and derived instances.

## Materializable Proofs

- **`HasCompanion<A, C>`.** Prove that `A` has a companion object of type `C`. Useful for generic factories, registries, and companion-as-strategy patterns.
- **`Reified<A>`.** Prove that `A` is runtime-available without materializing a full `KType`. This is cheaper than `KnownType<A>` and useful as an admission gate.
- **`DefaultValue<A>`.** Prove that `A` can be constructed with `A()` because its primary constructor exists and all parameters have defaults.
- **`Enum<A>`.** Prove that `A` is an enum and expose `entries`, `values`, and `valueOf` style operations without reflection.
- **`ValueClass<A, Underlying>`.** Prove that `A` is a value class wrapping `Underlying`, with wrap/unwrap operations. This should connect to `DeriveVia` and representation-safe coercions.
- **`Sealed<A>`.** Prove that `A` is sealed and optionally expose the known case list. Sum derivation already computes similar information.
- **`DataClass<A>`.** Prove that `A` is a data class and expose structural operations such as components or `copy` where practical.
- **`HasAnnotation<A, Ann>`.** Prove that `A` carries annotation `Ann` at compile time, avoiding runtime annotation reflection.
- **`Arity<A, N>`.** Prove the arity of function types for generic middleware and interceptor patterns.
- **`Coercible<A, B>`.** Prove that `A` and `B` have the same runtime representation, for value class wrapping, typealias collapse, and safe zero-cost transport.

## Proof Logic, Lowering, and Erasure

- **Resolver-level proof composition.** Runtime proof combinators already support operations such as `Subtype.andThen`, `Subtype.compose`, `Same.flip`, and `Same.bracket`. The remaining step is letting the resolver synthesize proofs from arbitrary in-scope proof chains instead of requiring direct builtin materialization or explicit user composition.
- **Bounded proof search.** Add synthetic proof-composition rules with a small depth limit. For subtype proofs, candidate intermediate types can come from the compiler's known supertype graph.
- **Proof lowering into typechecking.** When `context(_: Subtype<A, B>)` is in scope, the compiler should treat `A` as a subtype of `B` inside that scope. `Same<A, B>` should act like scoped bidirectional equality.
- **Nullability proof lowering.** `NotNullable<A>` and `Nullable<A>` should inform definite-nullability checking where FIR APIs allow it.
- **GADT-style narrowing.** Use proof lowering in sealed match branches so evidence such as `Same<A, Int>` can narrow a generic type parameter within a branch.
- **Proof erasure.** Proof values whose only purpose is to witness static facts should disappear from runtime code. `Subtype.coerce(value)` and `Same.coerce(value)` should lower to the value itself when the proof was compiler-verified.
- **Partial erasure for runtime-carrying proofs.** `KnownType<A>` and `TypeId<A>` can erase the carrier object while preserving the actual runtime content, such as `typeOf<A>()` or a canonical type-id string literal.
- **Cross-module proof ABI.** Decide whether public proof context parameters are erased from signatures, kept as discarded bridge parameters, or handled with a stable compiler-known ABI convention.
- **Forgery policy.** Decide whether handwritten unsafe proof carriers are rejected by the plugin or documented as trusted/undefined behavior, similar to unchecked casts.

## Performance and Codegen

- **Monomorphization / specialization at concrete call sites.** When the resolved instance is known, inline the instance method body directly instead of calling through the typeclass interface.
- **Avoid boxing for primitive and value-class parameters.** Concrete call sites should route to non-boxing paths where possible. Polymorphic sites may need specialized JVM bridges.
- **Direct derived instance emission.** For known derivation patterns, emit a direct implementation object instead of metadata objects, accessor lambdas, and deriver callbacks.
- **Metadata-free derivation for closed shapes.** If the deriver body is visible and structurally simple, partially evaluate it using known product/sum metadata and emit direct field or case dispatch.
- **Compile-time dispatch tables.** For enums and sealed hierarchies, emit static dispatch tables or direct `when` chains instead of metadata-driven runtime matching.
- **Inline typeclass method chains.** If a resolved method body is small and visible, inline it into the call site, applying Kotlin `inline`-style behavior automatically for evidence calls.
- **Typeclass-level const evaluation.** Inline resolved constant-like values at call sites when the instance function is pure, visible, and fully determined at compile time.
- **Eliminate redundant casts.** Avoid `irAs` when the IR builder already knows the expression type. This matters for JVM `checkcast`, primitive boxing, and interface-table checks.
- **Dictionary-free specialized entry points.** For stateless singleton evidence used only for method calls, specialize callees so the evidence parameter becomes phantom and the callee references the singleton directly.
- **Inline-class or function-pointer evidence representations.** For single-method typeclasses, investigate compact evidence representations that avoid interface dispatch while keeping a usable source-level model.
- **Megamorphic call-site splitting.** Clone small generic functions for concrete call sites when one evidence call site would otherwise see many concrete receiver types.
- **Loop hoisting for `summon`.** Detect `summon<T>()` in hot loops and hoist resolution outside the loop when the concrete evidence is stable.
- **Escape-analysis-friendly IR.** Prefer singleton objects, immutable temporaries, and narrow allocation scopes so HotSpot can scalar-replace generated objects where possible.
- **Evidence class merging.** Explore replacing many anonymous instance classes with package/file-level static methods or compact evidence representations to reduce class loading, metaspace, and code cache pressure.

## Tooling and Diagnostics

- **IDE gutter icons for resolved instances.** Show resolved evidence and prerequisite chains inline in IntelliJ.
- **Resolution explanation reports.** Add `@ExplainInstance` or a compiler option that emits structured JSON/YAML resolution reports for CI.
- **Deeper failure diagnostics.** The current diagnostics, troubleshooting guide, and scoped trace modes cover common missing, ambiguous, and derivation failures. Remaining work is deeper prerequisite-level explanations, better ranking of root causes, and machine-readable output that tooling can consume.
- **Instance provenance tracking.** Attach source metadata to resolved evidence so users can inspect where an instance came from and which prerequisites were used.
- **Orphan instance lint with suppression.** Warn on technically legal but suspicious instance placements, with an explicit suppression for intentional cases.
- **Error recovery mode.** For IDE and migration scenarios, consider a mode where failed resolution emits a warning and a generated `TODO()`/`error()` stub so more of the file remains analyzable.

## Testing, Laws, and Safety

- **Reusable law-checking helpers.** Publish test helpers for users to validate their own instances, not just examples inside this repository.
- **Compiler-integrated law generation.** Explore generating property-based tests for every `@Instance` and `@Derive` site when a typeclass declares laws.
- **Resolution test API.** Provide `assertResolves<T>()`, `assertDoesNotResolve<T>()`, and `assertResolvesVia<T>(...)` helpers so library authors can regression-test their instance surfaces.
- **`@TestInstance`.** Let test-scoped instances participate only in test compilation, mirroring test-scoped DI bindings.
- **Expanded consumer compatibility tests.** Fresh Gradle-consumer, docs-example, downstream, and Maven-local-style coverage already exists. Remaining work is Maven-Central-like release verification, more plugin combinations, and compatibility scenarios that exercise published artifacts rather than only local publication.

## Runtime, Interop, and Framework Integration

- **KSP / annotation processor bridge.** Define a metadata handshake so generated code can publish instances that the compiler plugin can see.

## Multiplatform and Compiler Integration

- **Kotlin/JS and Kotlin/Native portability.** Audit builtins and scanning logic that currently lean on JVM concepts such as jar scanning or JVM IR APIs.
- **`expect`/`actual` support.** Verify that typeclass context parameters, instances, and derivation behave correctly across multiplatform source sets.
- **Incremental compilation behavior.** Understand and document which changes invalidate the resolution index and which can remain incremental.
- **Callable-reference and property-access adaptation.** Blocked tests already document the expected behavior. Revisit implementation when public Kotlin FIR plugin APIs expose usable callable-reference or property-access refinement hooks.
- **Overload resolution gaps.** Some overload-resolution improvements may be possible in the plugin; others fight the host language and should be documented as limitations.

## Ecosystem, Documentation, and Distribution

- **Arrow migration guide.** Show before/after examples for Kotlin developers who previously used Arrow-style typeclass encodings.
- **Cookbook.** Document practical patterns: codec derivation, effect handlers, optics via `DeriveVia`, DI through local context, and test doubles through scoped instances.
- **Performance characterization.** Publish compile-time and runtime measurements for instance resolution, derivation, and hot-path evidence calls.
- **Gradle plugin DSL.** Replace raw compiler-plugin options with typed configuration:

  ```kotlin
  typeclasses {
      builtinKClass = true
      builtinKSerializer = true
      traceMode = TypeclassTraceMode.FAILURES
  }
  ```

- **BOM / version catalog support.** Make it easy for consumers to coordinate runtime, compiler plugin, Gradle plugin, and Kotlin-line-specific artifacts.
- **Automated compatibility matrix page.** The supported Kotlin matrix is documented and release CI reads it from `gradle.properties`; generate a versioned docs page from that source so supported, tested, and best-effort compiler lines stay visible.
- **Broader executable docs coverage.** README and derivation-guide examples already compile in tests; extend that protection to more task-oriented guide snippets as the docs grow.

## Larger Type-System Extensions

- **Higher-kinded typeclass parameters.** Support `Functor<F<_>>`-style APIs through a compiler-assisted defunctionalization encoding, avoiding Arrow-style boilerplate where possible.
- **Associated types.** Let a typeclass declare output types determined by input types. This overlaps with functional dependencies; choose one coherent story before implementing both.
- **Constraint aliases as syntax.** If Kotlin does not add constraint aliases, consider plugin-level macro expansion for common bundles.
- **GADT-style programming.** Proof lowering plus sealed matching could give Kotlin practical GADT-like narrowing.

## Mostly Blocked by Kotlin

- **First-class HKTs, type lambdas, quantified constraints, and associated types.** These either require language support or careful encodings with significant tradeoffs.
- **Callable-reference adaptation and property-access adaptation.** Existing blocked tests suggest this is mostly a FIR API gap rather than ordinary missing plugin work.
- **Full overload resolution informed by typeclass contexts.** Some improvements are possible, but broad changes would require deeper host-language integration.
- **Scoped proof lowering hooks.** Injecting temporary type bounds from proof context parameters may require FIR APIs that are currently internal or unavailable.
