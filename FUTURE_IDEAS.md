- Better coherence/import control. Right now the orphan policy is strict, but there is still no really nice story for “these instances are in scope here, but not there”. A great library wants explicit instance imports, local instance packs, or named instance modules.

- Typeclass-directed inference/improvement. Multi-parameter typeclasses are much weaker without functional dependencies, associated types, or some improvement mechanism.

- Optimization of generated instances. You already started this with [DerivationOptimizationSpec.kt](/Users/wabbit/ws/datatron/kotlin-typeclasses-plugin/src/test/kotlin/one/wabbit/typeclass/plugin/integration/derivation/DerivationOptimizationSpec.kt). Getting derived codecs/equality/order instances close to handwritten code matters a lot.

A few categories, roughly ordered by "this would actually change how people write code" vs "nice polish."

**Resolution model extensions:**

- **Negative instances / instance exclusion.** `@NoInstance` or similar — explicitly declare "there is no `Show<Secret>`" so downstream code gets a clear error instead of silence. Right now "missing" and "intentionally absent" are indistinguishable. Haskell never got this right either; you could.
- **Priority annotations on `@Instance`.** You already have `priority` on derived rules internally. Exposing `@Instance(priority = ...)` for manual rules would let library authors layer defaults and overrides without ambiguity errors. The alternative is telling everyone "just use local context" which doesn't scale for library design.
- **Conditional instances with negation.** "Provide `Show<A>` only when `NotSame<A, Secret>`" — you have the proof machinery, but there's no way to gate a rule on a *failing* proof. This is where `@NoInstance` and negative proofs intersect.

**Ergonomics:**

- **`deriving` blocks / bulk derive.** Instead of `@Derive(Show::class, Eq::class, Ord::class)` on every type, something like a file-level or module-level default derive list for all qualifying types in scope. Tedious annotation repetition is the #1 thing that makes Haskell's `deriving` feel nicer than what you have.
- **Instance aliases / re-export.** "This module re-exports `Show<Foo>` from that module under a different associated owner." Right now if you want to attach upstream evidence to a downstream type's companion, you have to write a forwarding wrapper. A declarative re-export would be cleaner.
- **Typeclass-constrained type aliases.** `typealias Showable<A> = context(Show<A>) A` or similar — named bundles of constraints. Kotlin doesn't have constraint aliases natively, but your plugin could desugar them. This is the single biggest ergonomic gap vs Haskell/Scala for complex constraint stacks.

**Power features:**

- **Multi-parameter typeclass coherence hints.** For `@Typeclass interface Convert<From, To>`, let the author declare functional dependencies or at least "determining parameters" — e.g., `From` determines `To`. This constrains inference and prevents the combinatorial explosion that makes multi-param typeclasses unusable without fundeps.
- **Superclass / prerequisite inheritance on `@Typeclass`.** `@Typeclass interface Ord<A> : Eq<A>` already works structurally, but the *resolution* side could automatically derive "if you have `Ord<A>`, you have `Eq<A>`" without requiring a separate `@Instance`. You partially do this through provided-type expansion, but making it explicit and documented as a first-class feature would matter.
- **Default method implementations that reference prerequisites.** Haskell's "minimal complete definition" pattern — define `eq` or `neq`, get the other for free. Your deriver model could support this: the typeclass author declares default implementations in terms of other methods, and derived instances only need to fill the non-defaulted ones.
- **Overlapping instance resolution with explicit overlap markers.** GHC's `{-# OVERLAPPING #-}` / `{-# OVERLAPPABLE #-}`. Controversial, but without it you can't write "use this specific instance for `List<Int>` and this generic one for `List<A>`" without ambiguity. Your priority system on derived rules is a half-step here.

**Tooling / DX:**

- **IDE gutter icons for resolved instances.** "This call resolved `Show<Box<Int>>` via `Box.Companion.boxShow → IntShow`." The IJ plugin currently just enables the compiler plugin; it could render resolution chains inline. This is the single most impactful DX feature for adoption — people need to *see* what the compiler chose.
- **`@ExplainInstance` or resolution dump to file.** Beyond tracing to compiler output, emit a structured JSON/YAML resolution report for CI. Useful for catching silent resolution changes in PRs.
- **Orphan instance lint with override.** Warn on orphan-ish patterns even within the current legal placement rules, with `@SuppressOrphanWarning` for intentional cases. The current hard restriction is good, but a softer lint layer would catch "technically legal but probably unintended" placements.

**Runtime integration:**

- **`summonAll<T>()` — collect all instances, not just one.** For plugin/extension systems where you want every registered `Handler<Event>`, not just a unique one. This breaks the "exactly one or error" model, so it'd need a separate entry point, but the underlying index already knows about multiple candidates.
- **`summonOrNull<T>()` — optional evidence.** Returns `null` instead of compilation error. Useful for "use `Show<A>` if available, fall back to `toString()` otherwise." This is `deriving via` for the call site rather than the declaration site.
- **Runtime instance registration.** For test fixtures and DI-like scenarios, let runtime code push instances into a scoped registry that participates in resolution. This is philosophically impure but practically necessary for testing. ServiceLoader-style discovery as an escape hatch.

**The one I'd actually build next:**

Functional dependencies or determining parameters for multi-param typeclasses. Without them, `Convert<From, To>`, `Lens<S, A>`, `Codec<Wire, Domain>` — basically every interesting two-parameter typeclass — is unusable because the solver can't infer the output parameter from the input. You'll hit this wall the moment anyone tries to build a real serialization or optics layer on top of your system. Everything else is quality-of-life; this one unlocks an entire class of designs that's currently impossible.

**Compile-time computation:**

- **Typeclass-level `const` evaluation.** If `TypeId<A>` produces a stable string, let `@Instance` functions be `constexpr`-like — the compiler inlines the resolved value at the call site instead of emitting a function call chain. Your derived instances already partially do this (object singletons are effectively constant), but generalizing it would eliminate the runtime overhead argument against typeclasses entirely.
- **Compile-time instance tables.** For a closed set of types (enum cases, sealed subclasses), emit a static dispatch table instead of a chain of `if/else` or virtual dispatch. You're already generating ordinal resolvers for enums — the generalization is a static array indexed by type tag.

**Interop surfaces:**

- **Java `ServiceLoader` bridge.** Auto-generate `META-INF/services` entries from `@Instance` objects so Java frameworks can discover them without reflection. Bidirectionally, auto-import `ServiceLoader`-registered implementations as `@Instance` candidates. This is the "make typeclasses work in Spring/Micronaut/Quarkus" feature.
- **KSP / annotation-processor bridge.** Let KSP-generated code declare `@Instance` values that your plugin picks up. Right now KSP and compiler plugins live in different compilation phases, so someone generating a codec with KSP can't feed it into your resolution. Even a metadata handshake protocol would help.
- **Kotlin/JS and Kotlin/Native materialization.** Your builtins lean on JVM semantics (`JvmIrTypeSystemContext`, `JarFile` scanning). Auditing which builtins are portable and which need platform-specific backends would matter before anyone ships a multiplatform library on this.

**Type system extensions:**

- **Higher-kinded typeclass parameters.** `@Typeclass interface Functor<F<_>>`. Kotlin doesn't have HKT, but you could fake it with a defunctionalization scheme — `Functor<ForList>` where `ForList` is a marker and the plugin resolves `Kind<ForList, A>` to `List<A>`. Arrow tried this at the library level and it was ugly. A compiler plugin could make it invisible. This is the nuclear option but it's the one thing that would make Kotlin's typeclass story genuinely competitive with Scala 3.
- **Associated types.** `@Typeclass interface Collection<C> { type Element }` — the typeclass declares an output type determined by the input. Functional dependencies are the relational version; associated types are the functional version. Both solve the same inference problem differently. If you do fundeps, you probably don't need this. If you skip fundeps, this is the alternative.
- **Typeclass universes / instance scoping.** Named resolution scopes — `@Instance(scope = "json")` vs `@Instance(scope = "xml")` — so the same type can have different instances in different contexts without ambiguity. Scala 2's implicit scoping was a mess; Scala 3's `given`/`using` is better but still global. You could do this cleanly as a first-class feature because you control the resolver. The alternative is "just use newtypes" which works but is noisy.

**Correctness / safety:**

- **Typeclass laws as compiler-checked contracts.** `@Typeclass interface Monoid<A> { @Law fun associativity(a: A, b: A, c: A) = combine(combine(a, b), c) == combine(a, combine(b, c)) }`. The compiler doesn't *prove* the law, but it generates property-based test cases for every `@Instance` and `@Derive` site. Integrate with a test runner. This is what nobody has done well — Haskell has `hspec-checkers`, Scala has `discipline`, but neither is compiler-integrated.
- **Coherence checking as an opt-in lint pass.** Whole-program or whole-module scan that reports "these two rules could overlap for some instantiation of their type parameters" even if no current call site triggers the ambiguity. Expensive, but you could run it as a separate Gradle task rather than on every compilation.
- **Instance provenance tracking.** Attach metadata to resolved evidence: "this `Show<Box<Int>>` was resolved via `Box.Companion.boxShow` with prerequisite `IntShow` at line 42 of Foo.kt." Queryable at runtime. Useful for debugging, auditing, and "who registered this instance" questions in large codebases.

**One genuinely weird idea:**

- **Cross-typeclass implication rules.** "If `Ord<A>` exists, then `Eq<A>` exists with this derivation." Not superclass inheritance (which is structural), but a *resolution-level* implication that fires during search. This would let you model Haskell's superclass dictionary extraction without requiring interface inheritance. It's more general — you could say "if `Serialize<A>` and `Deserialize<A>` both exist, then `Codec<A>` exists" without `Codec` extending either. Dangerous if abused, but the resolution planner already handles prerequisite chains, so the machinery is mostly there.

Honestly though — if I had to pick the three things that would most change adoption trajectory, they'd be: IDE resolution visualization, higher-kinded type parameter support, and `summonOrNull`. The first makes the system *learnable*, the second makes it *powerful enough*, and the third makes it *pragmatic enough* for people who aren't type theory enthusiasts.

The core problem is indirection. Hand-written code is a direct call. Resolved typeclass code is: look up instance object → virtual dispatch through interface → actual logic. Derived instances add another layer: metadata construction, lambda wrappers for field accessors, constructor bridges.

**Monomorphization / specialization:**

The biggest single win. When the compiler knows the concrete types at a call site, inline the resolved instance's method body directly instead of emitting a virtual call through the interface.

```kotlin
// What you emit today:
val instance: Show<Int> = IntShow
instance.show(42)  // virtual call

// What you could emit:
42.toString()  // inlined body
```

This is what GHC does with specialization pragmas and what Rust does by default with monomorphization. Your IR rewriter already knows the concrete resolved instance at every call site — you have the resolution plan. The missing step is: instead of emitting `irCall(resolvedFunction)`, inline the body when it's small enough and the types are fully known.

Heuristic: inline when the instance is an object singleton, the method body is below some threshold (say 30 IR nodes), and all type parameters are concrete. That covers the overwhelming majority of primitive and simple product instances.

**Eliminate interface boxing for value types:**

Right now `Show<Int>.show(value: Int)` boxes `Int` because the interface method takes `A` which erases to `Any`. Hand-written `fun showInt(value: Int): String` doesn't box.

Two approaches:

- Monomorphization (above) eliminates this entirely for concrete sites
- For polymorphic sites where you can't monomorphize, emit specialized bridge methods on the JVM. When the transported type is a primitive or inline class, generate an additional non-boxing entry point and route concrete call sites to it. This is what `@JvmInline` value classes already do for their own methods.

**Derived instance overhead:**

Your derived instances construct metadata objects at first resolution, including lambda wrappers for field accessors and constructor bridges. That's one-time cost per type, but:

- The lambdas are never eliminated even when the deriver's `deriveProduct` implementation is trivial
- Each field accessor is a separate `IrFunctionExpressionImpl` lambda allocation
- The constructor bridge is another lambda

For hot paths, generate a *direct* implementation class instead of going through the metadata-plus-deriver-callback pattern. You already compute the full field/case structure in IR. Instead of:

```
metadata = ProductTypeclassMetadata(fields=[...], constructor=lambda{...})
instance = companion.deriveProduct(metadata)
```

Emit:

```
object GeneratedShowForBox : Show<Box<Int>> {
    override fun show(value: Box<Int>): String =
        "Box(" + IntShow.show(value.value) + ")"
}
```

The deriver callback is the extensibility mechanism — it lets typeclass authors control derivation logic. But when the deriver is a known companion in the current compilation and its `deriveProduct` body is visible, you could partially evaluate the deriver call at compile time and emit the result directly.

This is an aggressive optimization but the payoff is enormous: derived instances become zero-overhead compared to hand-written ones.

**Object allocation elimination for stateless instances:**

Most resolved instances are stateless singletons. But prerequisite-carrying instances aren't:

```kotlin
@Instance
context(show: Show<A>)
fun <A> boxShow(): Show<Box<A>> = object : Show<Box<A>> { ... }
```

Every resolution creates a fresh anonymous object capturing `show`. For recursive call chains this means allocation per resolved goal.

Fix: when all captured prerequisites are themselves stateless singletons (which you know from the resolution plan), emit a cached singleton parameterized on the specific prerequisite combination. In practice this means `Show<Box<Int>>` resolves to a single static object, not a fresh allocation per call.

You're halfway there with the `RecursiveTypeclassInstanceCell` caching. Generalize that caching to non-recursive cases where the prerequisite graph is fully static.

**Inline class wrapping for typeclass evidence:**

For the common case where evidence is passed through context parameters across a call chain, the evidence value itself is just a routing token — the actual work happens inside its methods. If the evidence type is a single-method interface, you could represent it as an inline value class wrapping a function pointer on the JVM, eliminating the interface dispatch entirely.

This is speculative but follows from: if `Show<A>` has one abstract method, it's structurally a `(A) -> String`. An inline class wrapping that function, with the compiler generating direct `invoke` calls, would eliminate vtable lookup.

**Compile-time dispatch tables for sealed/enum resolution:**

When resolving `Show<T>` where `T` is a sealed type and every case has a known instance, emit a `when` dispatch instead of a resolved-instance virtual call:

```kotlin
// Instead of resolving Show<Token> to a sum-derived instance that
// does runtime instanceof checks through lambda matchers:
when (value) {
    is Token.Keyword -> keywordShow.show(value)
    is Token.Literal -> literalShow.show(value)
}
```

You already generate `irIfThenElse` chains for sum transport. The optimization is: when the leaf instances are stateless singletons, inline their method bodies into the `when` branches. Now you have a flat dispatch with no interface calls, no lambda indirection, no metadata objects.

**Escape analysis hints:**

For JVM targets, annotate generated instance objects and lambda wrappers with `@JvmStatic` patterns and structure them so HotSpot's escape analysis can scalar-replace them. Concretely: avoid capturing mutable state, prefer `object` singletons over anonymous classes, and keep allocation scopes narrow.

This isn't something you *emit* differently, but you could structure IR generation to be EA-friendly. The current pattern of `irTemporary(irAs(irNull(), cacheType), isMutable = true)` in `buildDerivedInstanceExpression` is EA-hostile because the mutable variable escapes the local scope.

**The actual priority order I'd pursue:**

1. **Static singleton caching for fully-resolved prerequisite chains.** Low effort, high impact, eliminates most runtime allocation.
2. **Monomorphization of concrete call sites.** Medium effort, eliminates virtual dispatch and boxing for the common case.
3. **Direct derived instance emission** (bypass metadata+callback for known derivers). High effort, but makes derived instances truly zero-cost.
4. **Everything else** is diminishing returns unless profiling shows a specific bottleneck.

The dirty secret is that #1 alone gets you 80% of the way there. Most typeclass overhead in practice is allocation, not dispatch. A `Show<Pair<Box<Int>, List<String>>>` that resolves to a chain of five singleton objects with virtual dispatch is already fast — the JVM's inline caches handle that well. The problem is when those five objects are freshly allocated per call because the resolver doesn't realize they're static.

**Profile-guided deoptimization boundaries:**

The JVM's C2 compiler is good at devirtualizing when it sees one or two concrete receiver types at a call site (monomorphic/bimorphic inline caches). But your resolution chains can create *megamorphic* sites — a single `summon<Show<A>>().show(value)` in a generic function receives different concrete `Show` implementations depending on the caller. Once the site goes megamorphic, C2 gives up on inlining and you eat full vtable dispatch forever.

Mitigation: when the compiler can see that a generic function will be called with more than ~2 distinct resolved instances, clone the function body per call site and let each clone see a monomorphic receiver. This is essentially manual devirtualization at the IR level, trading code size for dispatch speed.

You'd want a heuristic: only clone when the function body is small, the call site's resolution is fully concrete, and the function is called from more than N distinct concrete instantiations. The IR transformer already visits every call site and knows the resolved instance — you just need to decide when cloning is worth it versus relying on the JVM.

**Typeclass dictionary passing vs. dictionary-free styles:**

Right now every resolved typeclass parameter passes an object reference at runtime. Even after singleton caching, you're still passing a pointer that the callee loads methods from. For deeply nested generic call chains, that's N extra parameters threaded through every frame.

Alternative: for stateless instances, don't pass them at all. The callee can reconstruct the instance from a static lookup keyed on the type. This is what Rust does — there's no "dictionary" passed at runtime; the compiler stamps out a specialized copy. You can't go full Rust (JVM doesn't support true monomorphization without code duplication), but you can eliminate the parameter when:

- The instance is a singleton object
- The callee doesn't store/return the instance, only calls methods on it
- The concrete type is known at the call site

In IR terms: instead of `putValueArgument(index, irGetObject(instance))`, emit nothing, and at the callee site replace `irGet(contextParameter)` with `irGetObject(instance)` directly. The context parameter becomes phantom.

This requires the callee to be recompiled or for you to emit two entry points — one with the dictionary parameter (for genuinely polymorphic callers) and one without (for monomorphic callers that resolved statically). The JVM has no trouble with overloading on this.

**Avoid megamorphic `Any` casts:**

Your IR generation is littered with `irAs(expression, targetType)`. Every one of those is a runtime `checkcast` on the JVM. For primitive types, that checkcast triggers boxing. For interface types, it's a linear search through the class's interface table on some JVM implementations.

In most cases you *know* the concrete type at IR generation time. Replace `irAs` with direct typed references where possible. When you construct an instance via `irCallConstructor`, the result type is already known — don't cast it to the interface type and then cast it back. Thread the concrete type through the IR builder instead of erasing to `Any` and recovering.

Specific offenders in the current codebase:

- `buildTransportExpression` casts to `plan.sourceType` even when the input expression's type already matches
- `buildDeriveViaAdapterExpression` casts `viaInstance` unnecessarily when the parameter type already carries it
- Product constructor bridges cast every field through `Any` via `List<Any?>.get()` — the compiler knows the concrete types, it could emit typed local variables instead of going through an untyped list

**Metadata-free derivation for closed shapes:**

The entire `ProductTypeclassMetadata` / `SumTypeclassMetadata` / `EnumTypeclassMetadata` abstraction exists so typeclass authors can write `deriveProduct` once and have it work for any product type. That's valuable for extensibility.

But for *known* derivers whose behavior you can predict at compile time, you could constant-fold the derivation entirely. If `Show.Companion.deriveProduct` is:

```kotlin
override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
    object : Show<Any> {
        override fun show(value: Any): String = buildString {
            append(metadata.typeName)
            append("(")
            metadata.fields.forEachIndexed { i, field ->
                if (i > 0) append(", ")
                append(field.name)
                append("=")
                append((field.instance as Show<Any>).show(field.accessor(value)))
            }
            append(")")
        }
    }
```

Then for `@Derive(Show::class) data class Point(val x: Int, val y: Int)`, the compiler *could* emit:

```kotlin
object PointShow : Show<Point> {
    override fun show(value: Point): String = buildString {
        append("Point(x=")
        append(IntShow.show(value.x))
        append(", y=")
        append(IntShow.show(value.y))
        append(")")
    }
}
```

No metadata objects, no field accessor lambdas, no `List<Any?>.get()`, no `checkcast`, no `forEachIndexed` loop. Just direct field access and direct method calls.

This is partial evaluation of the deriver body with respect to known metadata. It's hard in general but tractable when:

- The deriver body is in the current compilation (not a binary dependency)
- The metadata shape is fully known at compile time (which it always is for derivation)
- The deriver doesn't do anything the IR inliner can't handle (no reflection, no coroutines, no try/catch in the hot path)

You could start with a narrow whitelist of "recognized deriver patterns" — structural recursion over fields/cases with `instance.method(accessor(value))` — and expand later.

**Megamorphic callsite splitting for `summon`:**

`summon<T>()` compiles to a context parameter read. But when it appears inside a loop or a hot generic function, the JVM sees one call site receiving many different `T` implementations. Classic megamorphic problem.

If you detect a `summon<T>()` call inside a loop body where `T` varies per iteration (e.g., processing a heterogeneous list), hoist the resolution outside the loop and bind it to a local variable. The JVM's C2 can then see the local as monomorphic within each iteration.

More aggressively: if the loop iterates over a sealed/enum type, unroll the loop into per-case branches with inlined evidence. This is the "dispatch table" idea applied to iteration rather than single calls.

**Inlining entire typeclass method chains:**

Consider:

```kotlin
context(ord: Ord<A>)
fun <A> max(a: A, b: A): A = if (ord.compare(a, b) >= 0) a else b
```

After resolution, this becomes:

```kotlin
fun maxInt(a: Int, b: Int): Int = if (IntOrd.compare(a, b) >= 0) a else b
```

But `IntOrd.compare` is itself a virtual call. If `IntOrd.compare` is `fun compare(a: Int, b: Int) = a.compareTo(b)`, the fully inlined version is:

```kotlin
fun maxInt(a: Int, b: Int): Int = if (a.compareTo(b) >= 0) a else b
```

One level of inlining eliminates the typeclass overhead. Two levels (inlining `compareTo` for `Int`) gets you to raw comparison. The JVM *usually* does this through its own inliner, but only after warmup and only if the call site stays monomorphic.

You could do it eagerly in IR: when the resolved instance method's body is small and visible, splice it into the call site. This is Kotlin `inline fun` semantics applied automatically to resolved typeclass method calls. The decision is whether to make it opt-in (`@Typeclass(inline = true)`) or automatic based on body size.

**The thing nobody talks about:**

Allocation pressure from evidence values is a red herring for most applications. The real cost is **code cache pressure from generated classes**. Every anonymous `object : Show<Whatever>` from an `@Instance` function generates a JVM class file. Every derived instance generates another. Every `DeriveVia` adapter generates another. A moderately complex typeclass hierarchy with 50 types and 5 typeclasses generates 250+ anonymous classes.

That blows out the JVM's code cache, increases class loading time, increases metaspace consumption, and makes JIT compilation slower because there are more methods to compile.

The fix: **merge generated instance classes.** Instead of one anonymous class per `@Instance` function invocation, emit a single utility class per package/file with static methods. Replace `object : Show<Pair<A, B>> { override fun show(...) = ... }` with a static method `fun showPair(left: Show<A>, right: Show<B>, value: Pair<A, B>): String`. At resolved call sites, call the static method directly instead of going through an interface dispatch on an anonymous object.

This requires changing the runtime representation of evidence from "object implementing interface" to "function pointer or static method reference," which is a deep architectural shift. But it's the single biggest performance difference between, say, Scala 3's typeclass encoding (which suffers from the same class-per-instance problem) and Rust's (which has zero class overhead because monomorphization eliminates the abstraction entirely).

Short version: you've optimized the *resolution* side well. The remaining gap is the *representation* side — too many objects, too many classes, too many virtual calls, too many casts. Monomorphization and singleton caching attack the most common cases. Class merging attacks the long tail.

These are two distinct features that reinforce each other. Let me take them apart.

**Proof composition / automatic proving:**

Right now your proofs are atomic. The compiler materializes `Subtype<Dog, Animal>` directly or it doesn't. But proofs compose algebraically and your API already has the composition methods — `andThen`, `compose`, `flip`. The compiler just doesn't use them during resolution.

What's missing is a resolution layer that treats proofs as a small logic program:

```
// User asks for:
Subtype<Puppy, Any>

// Compiler has:
Subtype<Puppy, Dog>     (direct)
Subtype<Dog, Animal>    (direct)
Subtype<Animal, Any>    (direct)

// Compiler should derive:
Subtype<Puppy, Any> = Subtype<Puppy, Dog>.andThen(Subtype<Dog, Animal>).andThen(Subtype<Animal, Any>)
```

Same pattern for every proof family:

```
Same<A, B> + Same<B, C>           → Same<A, C>           via andThen
Same<A, B>                        → Same<B, A>           via flip
Subtype<A, B> + Subtype<B, C>     → Subtype<A, C>        via andThen
Same<A, B> + Subtype<B, C>        → Subtype<A, C>        via toSubtype + andThen
Subtype<A, B> + NotSame<A, B>     → StrictSubtype<A, B>  via construction
NotNullable<A> + Subtype<A, B?>   → Subtype<A, B>        via narrowing
Nullable<A> + NotNullable<A>      → ⊥                    via contradiction (compile error)
```

Implementation-wise this is a small forward-chaining prover bolted onto the existing resolution planner. When direct materialization fails for a proof goal, the prover searches for two-step or three-step compositions from available atomic proofs. You'd want a depth limit — probably 3 or 4 steps is enough for any practical chain.

The key insight is that your `ruleProvider` already returns candidate rules for a goal. Proof composition rules are just synthetic `InstanceRule` entries where the prerequisites are the component proofs. You could emit them lazily when the goal head matches a proof classifier and direct materialization failed.

Concretely in your planner:

```kotlin
// When direct materialization of Subtype<A, C> fails,
// inject a synthetic rule:
InstanceRule(
    id = "proof-compose:subtype:transitive",
    typeParameters = listOf(A, B, C),
    providedType = Subtype<A, C>,
    prerequisiteTypes = listOf(Subtype<A, B>, Subtype<B, C>),
)
```

The existential `B` is the hard part. You need to enumerate candidate intermediate types. But you already have `classInfoById` with the full supertype graph — the candidates for `B` in a `Subtype` transitivity chain are exactly the types on the supertype path from `A` to `C`. That's a finite, usually small set.

For `Same` composition the intermediate candidates come from alias expansion. For `NotSame` the candidates come from asymmetric subtype proofs. Each proof family has a small, bounded set of composition schemas.

**Proof lowering to compiler knowledge:**

This is the far more interesting one. Right now proofs are runtime values. They exist as objects the program carries around. But they *encode information the compiler could use during typechecking*.

The idea:

```kotlin
context(sub: Subtype<A, B>)
fun <A, B> coerceList(list: List<A>): List<B> = list  // should typecheck
```

Today this doesn't compile because Kotlin's type system doesn't know `A <: B` inside that scope. The `Subtype<A, B>` context parameter is just an opaque object to the typechecker.

What you'd want is: when a `Subtype<A, B>` context parameter is in scope, the compiler should treat `A` as a subtype of `B` for the purposes of type checking within that scope. Similarly:

- `Same<A, B>` in scope → `A` and `B` are interchangeable
- `NotNullable<A>` in scope → `A` can be treated as `A & Any`
- `Nullable<A>` in scope → `Nothing?` is assignable to `A`

This is **proof-carrying code** in the PL theory sense. The context parameters are witnesses, and the compiler extracts typing judgments from them.

Implementation approach for FIR:

The FIR phase has access to context parameter types. When a function declares `context(sub: Subtype<A, B>)`, your existing FIR extension could inject a synthetic upper bound `A : B` into the type parameter resolution for that scope. Kotlin's FIR already handles conditional type narrowing via smart casts — this would be a similar mechanism but driven by proof-typed context parameters rather than `is` checks.

Specifically, in `TypeclassFirExpressionResolutionExtension` or a new `FirTypeResolutionExtension`:

```kotlin
// When entering a scope with context(sub: Subtype<A, B>):
// Inject synthetic bound: A has upper bound B
// This makes A assignable to B within the scope
```

The FIR representation of type parameter bounds is mutable during resolution. You'd add temporary bounds that exist only within the declaring scope and are removed on exit.

For `Same<A, B>`, you'd inject bidirectional bounds — `A : B` and `B : A` — which effectively makes them type-equal within the scope.

**The hard problems:**

1. **Scope leakage.** If `context(sub: Subtype<A, B>) fun foo()` calls `bar()` without forwarding the proof, should `bar` still see `A <: B`? No — the bound is scoped to `foo`'s body. This means the synthetic bounds must be pushed/popped on a stack tied to the FIR scope, not globally registered.

2. **Soundness with composition.** If you allow composed proofs AND lowered proofs, you get:

   ```kotlin
   context(ab: Subtype<A, B>, bc: Subtype<B, C>)
   fun <A, B, C> transitive(): Unit {
       // Compiler knows A <: B and B <: C
       // Does it automatically derive A <: C?
       // It should, because Kotlin's type system already handles transitive subtyping
       // once you inject the two direct bounds
   }
   ```

   This actually works for free — once you inject `A : B` and `B : C` as type parameter bounds, Kotlin's existing subtyping rules derive `A <: C` automatically. You don't need to do anything special for transitivity. The type system already handles it.

3. **Unsoundness with forgery.** If someone constructs a fake `Subtype<String, Int>` at runtime, the compiler would trust it and allow `String` to be used as `Int` within that scope. This is why your `UnsafeAssert*` carriers exist — but currently nothing prevents someone from constructing them directly.

   The fix: make the proof carrier constructors `internal` to the runtime module, and have the compiler plugin verify that proof values in context only come from compiler-materialized sources or from other proof-carrying context parameters. If someone smuggles in a handwritten `UnsafeAssertSubtype`, the compiler should reject the lowering.

   Alternatively, accept the unsoundness and document it as "proofs are trusted witnesses; forging them is undefined behavior." Kotlin already has `@UnsafeVariance` and unchecked casts — this would be the same category.

4. **Interaction with Kotlin's variance system.** `Subtype<A, B>` lowered to `A <: B` interacts with declaration-site variance:

   ```kotlin
   context(_: Subtype<A, B>)
   fun <A, B> test(list: List<A>): List<B> = list  // Works because List is covariant

   context(_: Subtype<A, B>)
   fun <A, B> test(set: MutableSet<A>): MutableSet<B> = set  // Should NOT work, MutableSet is invariant
   ```

   This falls out naturally from Kotlin's existing variance checking once the bound is injected. The compiler already rejects `MutableSet<A>` as `MutableSet<B>` even when `A <: B`. You just need to inject the bound correctly and let the existing type system do the rest.

**What this enables that's currently impossible:**

```kotlin
// Type-safe heterogeneous collections:
context(_: Subtype<A, B>)
fun <A : Any, B : Any> upcastElement(element: A): B = element as B  // today: unchecked cast. with lowering: checked by compiler

// Type-safe coercions without runtime cost:
context(_: Same<OldId, NewId>)
fun <OldId, NewId> migrateDatabase(ids: List<OldId>): List<NewId> = ids  // zero-cost, compiler-verified

// Conditional method availability:
context(_: NotNullable<A>)
fun <A> A.unwrap(): A & Any = this  // only available when A is proven non-null

// GADT-style pattern matching:
sealed interface Expr<A> {
    data class IntLit(val value: Int) : Expr<Int>
    data class BoolLit(val value: Boolean) : Expr<Boolean>
}

context(_: Same<A, Int>)  // compiler narrows A to Int
fun <A> Expr<A>.evalInt(): Int = when (this) {
    is Expr.IntLit -> value  // type-safe, compiler knows A = Int
}
```

That last example is real GADTs. Kotlin doesn't have them. Your proof system + lowering would give them to Kotlin. That's... significant.

**Staged implementation:**

1. **Proof composition in the resolver.** No FIR changes, just synthetic rules in the planner. Medium effort, immediately useful.
2. **`Subtype` lowering.** Inject synthetic upper bounds in FIR scope. Moderate FIR effort, enables safe coercions.
3. **`Same` lowering.** Inject bidirectional bounds. Enables type-safe migrations and the beginnings of GADT patterns.
4. **`NotNullable`/`Nullable` lowering.** Inject definite-nullability constraints. Enables conditional null-safety.
5. **Full GADT support via proof lowering on sealed match branches.** This is the endgame — when you match on a sealed subclass that carries a `Same<A, ConcreteType>` proof, the compiler narrows `A` to `ConcreteType` in that branch. Hard, but the machinery from steps 2-4 does most of the work.

The brutal honesty: step 1 is straightforward. Step 2 is where you'd hit the FIR API boundary — Kotlin's public FIR plugin API may not expose the hooks you need to inject synthetic type parameter bounds into an active resolution scope. You might need to use internal APIs or request upstream Kotlin support. Steps 3-5 follow mechanically from 2 if the FIR integration works.

But if you land steps 1-3, you'd have the most powerful type-level reasoning system in any JVM language, including Scala 3. That's not hyperbole — Scala 3's match types are more general in theory but less ergonomic in practice than what proof-carrying context parameters with compiler lowering would give you.

Right. Proofs are witnesses of static facts. At runtime they're useless objects consuming memory, passing through call frames, and generating class files. Every `Subtype<A, B>` parameter is a pointer to a singleton that nobody ever calls a meaningful method on — its entire purpose was satisfied at compile time.

**What erasure means concretely:**

```kotlin
// Source:
context(sub: Subtype<Dog, Animal>, notNull: NotNullable<Dog>)
fun <A> safeUpcast(dog: Dog): Animal = sub.coerce(dog)

// After resolution + erasure:
fun safeUpcast(dog: Dog): Animal = dog  // sub was erased, coerce was inlined to identity cast
```

No parameter. No object. No vtable. No class file for the proof carrier. The proof existed only during compilation.

**Three layers of erasure:**

**Layer 1: Parameter erasure.**

Proof-typed context parameters disappear from the emitted JVM signature entirely. You already do something adjacent to this — the FIR refinement extension hides typeclass context parameters from the user-facing call shape. Erasure is the IR-level completion of that: don't emit the parameter in the bytecode at all.

For every context parameter whose type is a proof typeclass (`Same`, `Subtype`, `NotSame`, `Nullable`, `NotNullable`, `StrictSubtype`, `IsTypeclassInstance`, `SameTypeConstructor`, `KnownType`, `TypeId`), the IR rewriter:

- removes the parameter from the function signature
- removes the corresponding argument at every call site
- replaces any `irGet(proofParameter)` in the body with the appropriate lowered operation or nothing

The ABI question: if a public function has `context(sub: Subtype<A, B>)`, downstream compiled code expects that parameter in the JVM signature. You need either:

- a stable ABI convention where proof parameters are always erased (downstream compilers know not to pass them)
- a bridge method that accepts the proof parameter and discards it (backward compat)
- a `@GeneratedTypeclassWrapper`-style companion that presents the erased signature

The cleanest answer: proof parameters are always erased from the JVM signature, and the compiler plugin on both sides knows this. The `@Typeclass` annotation on the proof interface is the signal. This is analogous to how Kotlin erases `inline class` wrappers from JVM signatures — the convention is baked into the compiler.

**Layer 2: Method erasure.**

Proof methods like `Subtype.coerce()`, `Same.flip()`, `NotNullable.contradicts()` are operations on static facts. Their runtime implementations are trivial — `coerce` is an unchecked cast, `flip` returns a new wrapper, `contradicts` throws.

After erasure:

- `sub.coerce(value)` → `value` (identity, or a cast the JVM already knows is safe from the injected bound)
- `same.flip()` → erased entirely (the flipped proof is itself erased)
- `same.andThen(other)` → erased entirely (the composed proof is itself erased)
- `notNull.contradicts(nullable)` → `throw` (unreachable if the type system is sound)

Every proof method has an erasure semantics:

| Method | Erased form |
|---|---|
| `Same.coerce(value)` | `value` |
| `Same.flip()` | nothing |
| `Same.andThen(other)` | nothing |
| `Subtype.coerce(value)` | `value` (or safe upcast) |
| `Subtype.andThen(other)` | nothing |
| `NotSame.flip()` | nothing |
| `Nullable.nullValue()` | `null` |
| `NotNullable` methods | nothing or unreachable |
| `KnownType.kType` | `typeOf<T>()` call (not erasable — has runtime content) |
| `TypeId.canonicalName` | string constant (erasable to literal) |

Notice that `KnownType` and `TypeId` are *partially* erasable — the proof carrier object can be erased, but the runtime values they expose (`kType`, `canonicalName`) still need to be computed. The difference: instead of materializing a `KnownType` object, passing it through three call frames, and then reading `.kType` at the use site, you inline the `typeOf<T>()` call directly at the use site.

**Layer 3: Carrier class erasure.**

`UnsafeAssertSame`, `UnsafeAssertSubtype`, `UnsafeAssertNotSame`, etc. — these singleton objects exist only to be the runtime representation of proofs. With full erasure, they're never instantiated, never referenced, never loaded.

The compiler can skip emitting them entirely when:

- all proof materializations in the module are erased
- no user code directly references the carrier type (which they shouldn't, since it requires `@OptIn(InternalTypeclassApi::class)`)

This eliminates the class files, the static initialization, and the metaspace consumption.

**Interaction with proof composition:**

Composition makes erasure more interesting, not harder. A composed proof like:

```kotlin
context(ab: Subtype<A, B>, bc: Subtype<B, C>)
fun <A, B, C> transitive(value: A): C = bc.coerce(ab.coerce(value))
```

After resolution + composition + erasure:

```kotlin
fun transitive(value: A): C = value  // both coercions erased to identity
```

If you also have proof lowering (from the previous discussion), the compiler already knows `A <: B <: C`, so the coercion is statically verified and the runtime representation is nothing.

Composed proof *values* are the easiest to erase because they're never stored — they're intermediate results that feed into another proof operation. The composition methods (`andThen`, `compose`, `flip`) produce proof values that are immediately consumed. The entire chain collapses.

**Interaction with proof-carrying context parameters across module boundaries:**

This is where it gets subtle. Module A exports:

```kotlin
context(sub: Subtype<A, B>)
fun <A, B> coerceList(list: List<A>): List<B>
```

Module B calls it with a resolved proof. If A erased the parameter from the JVM signature, B must know not to pass it. If A kept the parameter (for ABI stability), B passes the singleton and A ignores it.

The pragmatic answer: **two-phase erasure.**

Phase 1 (always): erase proof values from local code. Don't allocate proof objects, don't pass them to local functions, inline their methods.

Phase 2 (cross-module): keep proof parameters in public JVM signatures but mark them with a synthetic annotation. The consuming compiler knows to pass `null` or a sentinel. The receiving function ignores them. Over time, as the ABI convention stabilizes, you can drop them from signatures entirely with a major version bump.

This is exactly what Kotlin did with coroutines — the `Continuation` parameter exists in the JVM signature for ABI reasons but is managed entirely by the compiler.

**Partial erasure for `KnownType` and `TypeId`:**

These proofs carry actual runtime content. You can't erase them to nothing. But you can erase the *proof object* and inline the *content*.

```kotlin
// Before erasure:
context(known: KnownType<List<String?>>)
fun example(): String = known.kType.toString()

// After erasure:
fun example(): String = typeOf<List<String?>>().toString()
```

The proof parameter disappears. The `typeOf` call is inlined at the use site. No `KnownType` object exists at runtime.

For `TypeId`:

```kotlin
// Before:
context(id: TypeId<List<String?>>)
fun example(): String = id.canonicalName

// After:
fun example(): String = "kotlin.collections.List<kotlin.String?>"  // constant-folded string
```

Even cheaper — the canonical name is a compile-time constant, so the entire thing folds to a string literal.

**What makes a proof erasable vs non-erasable:**

A proof is fully erasable when:
- its runtime representation carries no information beyond what the compiler already verified
- none of its methods produce values that escape into non-proof computation

A proof is partially erasable when:
- the proof object can be eliminated
- but some methods produce runtime values that must be preserved (inlined at use site)

A proof is non-erasable when:
- user code stores it in a data structure
- user code passes it to reflection-based APIs
- user code serializes it

For the non-erasable case, you'd keep the current behavior — materialize the singleton proof carrier.

Detection: if an `irGet(proofParameter)` appears only as a dispatch receiver for proof methods (`.coerce()`, `.flip()`, `.kType`, etc.), the proof is erasable. If it appears as an argument to a non-proof function, stored in a field, or returned from a function, it's not.

**The implementation in your IR transformer:**

```kotlin
// In TypeclassIrCallTransformer.buildOriginalCall:

// For each context parameter that is a proof type:
if (parameter.type.isProofType(configuration)) {
    val usages = collectUsagesOf(parameter, functionBody)
    if (usages.all { usage -> usage.isProofMethodDispatch() }) {
        // Fully erasable: don't emit the parameter, inline method results
        eraseProofParameter(parameter, usages)
    } else if (usages.any { usage -> usage.isProofContentAccess() }) {
        // Partially erasable: don't emit the object, inline content
        inlineProofContent(parameter, usages)
    } else {
        // Non-erasable: keep current behavior
        emitProofParameter(parameter)
    }
}
```

For each proof method call on an erased parameter:

```kotlin
fun eraseProofMethodCall(call: IrCall, proofType: ProofTypeKind): IrExpression =
    when {
        proofType == SUBTYPE && call.methodName == "coerce" ->
            call.valueArguments[0]  // identity
        proofType == SAME && call.methodName == "coerce" ->
            call.valueArguments[0]  // identity
        proofType == SAME && call.methodName == "flip" ->
            ERASED_UNIT  // result is itself a proof, will be erased at its use site
        proofType == KNOWN_TYPE && call.methodName == "kType" ->
            irTypeOf(proofTypeArgument)  // inline typeOf<T>()
        proofType == TYPE_ID && call.methodName == "canonicalName" ->
            irString(proofTypeArgument.toCanonicalTypeIdName())  // constant fold
        // ...
    }
```

**Benchmark expectation:**

For proof-heavy code (lots of safe coercions, type-level programming, conditional nullability):

- Parameter erasure eliminates ~N pointer-sized values per proof-carrying call frame
- Method erasure eliminates ~N virtual calls per proof operation
- Carrier erasure eliminates ~N class files from the output
- Content inlining for `KnownType`/`TypeId` eliminates object allocation + field read, replacing with `typeOf<T>()` or string literal

For a function taking 3 proof parameters and calling 5 proof methods, that's 3 fewer parameters, 5 fewer virtual calls, 3 fewer argument-passing instructions at every call site, and 0-3 fewer class files. On a hot path that adds up to measurably less GC pressure, smaller stack frames, and better JIT inlining because the call chain is shorter.

**The philosophical point:**

Proofs are *propositions*, not *computations*. Their information content is zero bits at runtime — the type system already verified the proposition at compile time. Carrying a runtime witness of a statically verified fact is pure overhead. Erasure is not an optimization — it's the *correct* representation. The runtime objects are the hack; erasure is the fix.

Pulling back from individual features and looking at what's structurally missing for this to be a complete, adoptable system.

**Documentation that doesn't exist yet:**

- A migration guide from Arrow's typeclass encoding. That's your actual target audience — Kotlin developers who tried Arrow, found it too heavy, and gave up. Show them the before/after.
- A cookbook of 10-15 real patterns: serialization codec derivation, effect handler typeclasses, optics via DeriveVia, DI via typeclass resolution, test double injection via local context. The docs explain the *mechanism*; nobody explains *when to reach for it*.
- Performance characterization document. Compile-time overhead per N instances, per M derivations. Runtime overhead measurements. People will ask, and "we haven't measured" kills adoption.

**Error recovery and resilience:**

- Right now a single failed resolution aborts compilation at that site. For large codebases with partial migration, you want a mode where failed resolutions emit a warning and fall back to a `TODO()` or `error()` stub so the rest of the file can still compile. This is how Kotlin handles some unresolved references in the IDE — partial compilation for better DX.
- Incremental compilation interaction. Your scanner rebuilds the full index per session. When one file changes, does the entire resolution index invalidate? If yes, that's a compile-time scaling wall. You need to understand and document which changes require full re-resolution vs incremental.

**Testing infrastructure for consumers:**

- A test assertion library. `assertResolves<Show<MyType>>()`, `assertDoesNotResolve<Show<Secret>>()`, `assertResolvesVia<Show<Box<Int>>>("Box.Companion.boxShow")`. Right now the only way to test resolution behavior is to write code that compiles or doesn't. A structured test API would let library authors regression-test their instance surface.
- `@TestInstance` — instances that only participate in resolution during test compilation. Equivalent to test-scoped DI bindings. Right now people would have to structure their source sets carefully to get this, and most won't bother.

**Ecosystem integration points you'll need eventually:**

- **Coroutine integration.** `context(dispatcher: CoroutineDispatcher)` style patterns are increasingly common. Your plugin needs to coexist cleanly with structured concurrency context. Specifically: what happens when someone puts `@Typeclass` on an interface and passes it through `withContext`? Does the evidence survive coroutine suspension? It should, since context parameters are captured in the continuation, but have you tested it?
- **Compose integration.** Jetpack Compose uses its own `CompositionLocal` system for ambient state. A bridge that lets typeclass instances flow through composition locals would make this usable in UI code. Without it, Android developers — your largest potential Kotlin audience — can't use it where it matters.
- **Serialization round-trip.** Not `KSerializer<T>` as a builtin, but: can you derive a `KSerializer` *for* a typeclass instance? If someone wants to serialize a resolved evidence chain (for RPC, for caching), what happens? The answer is probably "don't do that" but you need to say so explicitly.

**Language-level things that will bite you:**

- **Context parameter forwarding.** Kotlin 2.x context parameters have implicit forwarding semantics — if a function has `context(show: Show<A>)` and calls another function that needs `Show<A>`, the compiler forwards it automatically. Your refinement extension intercepts some of these calls. What happens when the forwarded context and the refined context disagree? You need a clear precedence rule and tests for it.
- **Expect/actual declarations.** Multiplatform projects with `expect` functions that have typeclass context parameters and `actual` implementations on different platforms. Does the resolution index handle expect/actual correctly? Does the IR rewriter run on the right compilation unit?
- **Compiler plugin ordering.** If someone applies your plugin alongside the serialization plugin, the compose plugin, and allopen, what's the interaction? Plugin ordering on FIR extensions is underspecified in Kotlin. You should document known conflicts and test against common plugin combinations.

**Things the type model needs:**

- **Constraint aliases / bundles.** This keeps coming up. `Hashable<A>` meaning `context(eq: Eq<A>, hash: Hash<A>)` is the most requested ergonomic feature in every typeclass system. Without it, complex constraint stacks become unreadable. Implementing it as a macro expansion in the FIR phase — where `context(h: Hashable<A>)` desugars to `context(eq: Eq<A>, hash: Hash<A>)` — is probably the least invasive path.
- **Default instances with explicit override.** Haskell's `deriving` gives you a default, and you can override it. Your system has no concept of "this instance is a default that can be shadowed without causing ambiguity." Adding `@Instance(default = true)` with lower priority than non-default instances would solve a real authoring problem.
- **Orphan instance policy beyond file-level.** Your current rule is file-based. For larger projects, package-level or module-level orphan policies would be more practical. "Any file in `com.mycompany.instances` may declare orphan instances for types in `com.mycompany.domain`." This could be a `package-info.kt` level annotation.

**Things you should explicitly decide NOT to do and document why:**

- Global coherence checking. You've implicitly decided against it. Make that explicit, explain why (compilation cost, open-world assumption, practical irrelevance for most codebases), and point people at the ambiguity error as the local coherence mechanism.
- Implicit typeclass derivation. Some systems auto-derive everything they can. You require explicit `@Derive`. Document that this is intentional — derivation is a publishing decision, not an automatic behavior.
- Typeclass-based implicit conversions. Scala's dark path. `Same<A, B>` could enable silent conversion everywhere. You've correctly kept `coerce` explicit. Document that you won't add implicit conversion even though the proof machinery could support it.

**Build and distribution:**

- **Gradle plugin DSL for builtins and configuration.** Right now builtins are raw compiler-plugin options. A typed DSL would be:
  ```kotlin
  typeclasses {
      builtinKClass = true
      builtinKSerializer = true
      traceMode = TypeclassTraceMode.FAILURES
  }
  ```
  This is table-stakes for a Gradle plugin that wants to feel native.

- **BOM / version catalog entry.** Publish a Gradle version catalog so consumers can `libs.kotlin.typeclasses.runtime` and `libs.kotlin.typeclasses.plugin` without manually coordinating versions.

- **Kotlin version compatibility matrix published as a webpage.** Which Kotlin versions are supported, which are tested, which are best-effort. Updated automatically from CI.

**The thing nobody asks for but everyone needs:**

A `kotlin-typeclasses-stdlib` module that ships canonical instances for standard library types. `Show<Int>`, `Eq<String>`, `Ord<Double>`, `Monoid<String>`, `Functor<List>` (if you ever do HKT). Right now every consumer has to write their own. A blessed stdlib module with carefully chosen instances and explicit non-instances would bootstrap the ecosystem faster than any language feature.

Without it, every project reinvents the same 50 instances, they're all slightly different, and library interop breaks because project A's `Show<Int>` and project B's `Show<Int>` are different declarations that cause ambiguity when combined.

Ship the obvious ones. Refuse to ship the controversial ones. Document why each non-obvious decision was made. That stdlib module would do more for adoption than the next three compiler features combined.


- **Negative instances / instance exclusion.** `@NoInstance` or similar — explicitly declare "there is no `Show<Secret>`" so downstream code gets a clear error instead of silence. Right now "missing" and "intentionally absent" are indistinguishable. Haskell never got this right either; you could.

- **Priority annotations on `@Instance`.** You already have `priority` on derived rules internally. Exposing `@Instance(priority = ...)` for manual rules would let library authors layer defaults and overrides without ambiguity errors. The alternative is telling everyone "just use local context" which doesn't scale for library design.

- **Conditional instances with negation.** "Provide `Show<A>` only when `NotSame<A, Secret>`" — you have the proof machinery, but there's no way to gate a rule on a *failing* proof. This is where `@NoInstance` and negative proofs intersect.

**Power features:**

- **Multi-parameter typeclass coherence hints.** For `@Typeclass interface Convert<From, To>`, let the author declare functional dependencies or at least "determining parameters" — e.g., `From` determines `To`. This constrains inference and prevents the combinatorial explosion that makes multi-param typeclasses unusable without fundeps.
- **Superclass / prerequisite inheritance on `@Typeclass`.** `@Typeclass interface Ord<A> : Eq<A>` already works structurally, but the *resolution* side could automatically derive "if you have `Ord<A>`, you have `Eq<A>`" without requiring a separate `@Instance`. You partially do this through provided-type expansion, but making it explicit and documented as a first-class feature would matter.
- **Default method implementations that reference prerequisites.** Haskell's "minimal complete definition" pattern — define `eq` or `neq`, get the other for free. Your deriver model could support this: the typeclass author declares default implementations in terms of other methods, and derived instances only need to fill the non-defaulted ones.
- **Overlapping instance resolution with explicit overlap markers.** GHC's `{-# OVERLAPPING #-}` / `{-# OVERLAPPABLE #-}`. Controversial, but without it you can't write "use this specific instance for `List<Int>` and this generic one for `List<A>`" without ambiguity. Your priority system on derived rules is a half-step here.

**Tooling / DX:**

- **IDE gutter icons for resolved instances.** "This call resolved `Show<Box<Int>>` via `Box.Companion.boxShow → IntShow`." The IJ plugin currently just enables the compiler plugin; it could render resolution chains inline. This is the single most impactful DX feature for adoption — people need to *see* what the compiler chose.
- **`@ExplainInstance` or resolution dump to file.** Beyond tracing to compiler output, emit a structured JSON/YAML resolution report for CI. Useful for catching silent resolution changes in PRs.
- **Orphan instance lint with override.** Warn on orphan-ish patterns even within the current legal placement rules, with `@SuppressOrphanWarning` for intentional cases. The current hard restriction is good, but a softer lint layer would catch "technically legal but probably unintended" placements.

**Runtime integration:**

- **`summonAll<T>()` — collect all instances, not just one.** For plugin/extension systems where you want every registered `Handler<Event>`, not just a unique one. This breaks the "exactly one or error" model, so it'd need a separate entry point, but the underlying index already knows about multiple candidates.
- **`summonOrNull<T>()` — optional evidence.** Returns `null` instead of compilation error. Useful for "use `Show<A>` if available, fall back to `toString()` otherwise." This is `deriving via` for the call site rather than the declaration site.
- **Runtime instance registration.** For test fixtures and DI-like scenarios, let runtime code push instances into a scoped registry that participates in resolution. This is philosophically impure but practically necessary for testing. ServiceLoader-style discovery as an escape hatch.

**The one I'd actually build next:**

Functional dependencies or determining parameters for multi-param typeclasses. Without them, `Convert<From, To>`, `Lens<S, A>`, `Codec<Wire, Domain>` — basically every interesting two-parameter typeclass — is unusable because the solver can't infer the output parameter from the input. You'll hit this wall the moment anyone tries to build a real serialization or optics layer on top of your system. Everything else is quality-of-life; this one unlocks an entire class of designs that's currently impossible.


**High-value, straightforward to materialize:**

- **`HasCompanion<A, C>`** — proves `A` has companion object of type `C`. Compiler knows this trivially. Useful for generic factory patterns, registry lookups, companion-as-strategy.
- **`Reified<A>`** — proves `A` is reified/runtime-available. Strictly weaker than `KnownType` but cheaper — no `typeOf` call, just gates admission. Lets you write rules that say "I need runtime type dispatch" without paying for full reflection.
- **`DefaultValue<A>`** — materializes `A`'s default constructor (`A()`). Compiler can prove primary constructor exists with all-default parameters. Useful for serialization fallbacks, builder patterns, test fixtures.

**Medium-value, slightly more involved:**

- **`Enum<A>`** — proves `A` is an enum class, materializes `entries`/`values()` and `valueOf()`. The compiler already knows enum shape for `deriveEnum`; exposing it as a standalone proof lets generic code branch on "is this an enum" without reflection.
- **`ValueClass<A, Underlying>`** — proves `A` is a `@JvmInline value class` wrapping `Underlying`, materializes wrap/unwrap. You already have transport-level awareness of this in `DeriveViaTransport`; promoting it to a first-class proof makes newtype patterns composable.
- **`Sealed<A>`** — proves `A` is sealed, could carry the case list or just be a gate. You already compute sealed subclass sets for sum derivation; the question is whether standalone use justifies the API surface.
- **`DataClass<A>`** — proves `A` is a data class. Materializes `copy`, `componentN`, or at minimum just acts as a gate. Useful for generic structural transforms.

**Speculative / niche but defensible:**

- **`HasAnnotation<A, Ann>`** — proves `A` carries annotation `Ann` at the type level. Compile-time annotation reflection without runtime `Class.getAnnotations()`. Narrow but powerful for framework-style code.
- **`Arity<A, N>`** — for function types, proves arity is `N`. Compiler knows this from the `FunctionN` classifier. Useful for generic middleware/interceptor patterns.
- **`Coercible<A, B>`** — weaker than `Equiv`, proves the JVM representation is identical (value class unwrapping, type alias collapsing). You're already computing this in the transport planner; surfacing it as a proof is mostly API design.


- Better explicit-instance selection. You eventually want a story for multiple lawful instances of the same typeclass for the same type without falling into chaos.

- Richer `deriveVia` surface. Multi-parameter transport, intrinsic `deriveVia<TC>(...)`, and cleaner waypoint syntax are natural next steps.

- Better diagnostics and tracing. A “why did this resolve / why is this ambiguous / why can’t this derive” explanation mode would be extremely valuable.

- Law support. Not just law-like tests in your repo, but reusable law-checking helpers users can run against their own instances.

- Standard prelude/typeclass ecosystem. A great library needs a polished battery of `Eq`, `Ord`, `Semigroup`, `Monoid`, `Functor`, `Applicative`, `Monad`, `Traverse`, `Foldable`, `Codec`, etc., with predictable derivation behavior.

- **`deriving` blocks / bulk derive.** Instead of `@Derive(Show::class, Eq::class, Ord::class)` on every type, something like a file-level or module-level default derive list for all qualifying types in scope. Tedious annotation repetition is the #1 thing that makes Haskell's `deriving` feel nicer than what you have.
- **Instance aliases / re-export.** "This module re-exports `Show<Foo>` from that module under a different associated owner." Right now if you want to attach upstream evidence to a downstream type's companion, you have to write a forwarding wrapper. A declarative re-export would be cleaner.
- **Typeclass-constrained type aliases.** `typealias Showable<A> = context(Show<A>) A` or similar — named bundles of constraints. Kotlin doesn't have constraint aliases natively, but your plugin could desugar them. This is the single biggest ergonomic gap vs Haskell/Scala for complex constraint stacks.


# Resolution model extensions

# Materializable proofs

# Ergonomics and polish

# Blocked mostly by Kotlin

- First-class higher-kinded abstractions. Without HKTs, type lambdas, or a good encoding, `Functor`/`Traverse`/`Monad`-style APIs stay awkward.

- Callable-reference adaptation and property access adaptation. We already have blocked tests for both; that is a Kotlin FIR API gap, not just missing work in your plugin.

- Better overload resolution that accounts for typeclass contexts in more places. Some of this is possible, but some of it fights the host language pretty directly.

- Anything resembling associated types, quantified constraints, or real HKTs needs either new language support or very careful encodings.
