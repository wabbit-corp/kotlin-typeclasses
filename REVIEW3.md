Ignoring the GADT file, the suite is mostly sane and, annoyingly, pretty good.

It is strongest where compiler-plugin tests usually go to die: weird Kotlin lowering sites. `ResolutionTest`, `SurfaceTest`, and `UtilityProofTest` are the best parts. They cover shadowing, ambiguity, recursion, nullability, value classes, projections, default arguments, lambdas, suspend, delegation, operator lowering, and proof composition. That is real coverage, not decorative testing.

The big strengths first:

* You consistently test both positive and negative resolution paths.
* You prefer ambiguity over “clever” overlap resolution in many places. That is the safer FP instinct.
* Local explicit evidence shadowing global evidence is covered well. Good.
* The proof-object tests are genuinely algebraic, not just “summon didn’t explode”.
* Interop suites are scoped sensibly, especially Compose, where compile-only is the correct level.

Now the parts that need tightening, because humans always stop two inches short of discipline.

### 1. The active negative diagnostic assertions are much less weak than they were

The harness now has a structured diagnostic path, and the active suites mostly use it.

That is good progress. The remaining problem is not broad caller adoption so much as the fact that the matcher still keys off human-readable message text.

What is still weak:

* ignored `GADTDerivationTest` negatives still do not protect anything
* message text is still easier to churn than stable diagnostic IDs
* generic frontend/backend wording can hide which phase really failed

* stable diagnostic IDs from your plugin, like `TC_NO_CONTEXT_ARGUMENT`, `TC_AMBIGUOUS_INSTANCE`, `TC_INVALID_INSTANCE_DECL`

### 2. Suite boundaries are blurry

The tests are good, but the file organization is not.

Examples:

* the split between `DerivationTest` and `TypeclassContractTest` is much clearer now
* `SurfaceTest` and `ResolutionTest` overlap heavily.
* `ContextualPropertyTest` and `ImportVisibilityTest` are much clearer names than before, but the overall file boundaries are still uneven.
* You mix `org.junit.Ignore` and `kotlin.test.Ignore`. Pick one and stop collecting annotation dialects like Victorian spoons.

This does not break correctness, but it does make the suite harder to navigate and maintain.

### 3. A few tests are misnamed or underpowered

Specific callouts:

* `SurfaceTest.rewritesContextualContainsOperatorCalls`
  The original private-property bug in the fixture is gone, but the test is still ignored because the operator rewrite currently trips a runtime `ClassCastException`. That is at least a real bug now, not just a broken fixture.

* `SessionScopedCacheTest`
  Fine as a smoke test, but it tests per-key caching, not anything recognizably “session-scoped”. If the type really has session semantics, the name is ahead of the evidence.

### 4. Some compile-only interop tests should probably run

Compose compile-only is sensible. You are testing rewrite coexistence, not UI execution.

`PowerAssertInteropTest` is fine as compile-only unless you change the harness to run with `-ea`, because otherwise JVM assertions are a theatrical prop.

### 5. The derivation contract is not consistently expressed

Some tests annotate both the sealed root and every subclass with `@Derive`.
Others, like `ResolutionTest.compilesAndRunsDerivedInstances`, annotate only the root.

That means one of three things is true:

1. root-only derivation is the real contract, and the extra leaf annotations elsewhere are redundant
2. both styles are supported, but the suite does not say so clearly
3. the contract is fuzzy

Pick one story and make the tests reflect it deliberately. Right now the suite implies two different APIs.

### 6. FP-coherence wise, this is good in one direction and bad in another

The good:

* ambiguity is often preferred over ad hoc overlap resolution
* local evidence shadows global evidence
* proof values compose explicitly
* non-typeclass contexts are not implicitly searched

The bad:

* global orphan-style discovery is still a coherence smell

`TypeclassContractTest.additionalUnrelatedFilesCanChangeResolutionOutcome` is an honest test, but it documents the main philosophical wart: adding an unrelated file can change resolution. That is bad for local reasoning, modularity, and general typeclass hygiene.

The dedicated import-visibility suite suggests you already know this. If “functional programming goodness” matters beyond mere convenience, import-scoped visibility or associated-only discovery is the cleaner direction.

### 7. The suite is mostly about plumbing, not lawfulness

That is not a sin. It is a compiler-plugin suite.

But if you want more FP credibility, add at least a small amount of law-bearing coverage for derivation. Right now most derivation tests use `Show`, which is cheap and lawless. It proves structural wiring, not algebraic sanity.

A couple of useful additions:

* derived `Eq` on products/sums with reflexivity/symmetry smoke tests
* maybe one `Monoid` or `Semigroup` derivation case if that is part of the intended surface

`UtilityProofTest` is already the most FP-looking file. The derivation side could borrow some of that seriousness.

## Net judgment

I would keep most of this suite.

The core is solid. The best tests are genuinely good and show careful thought about Kotlin’s ugly corners, which is where this kind of plugin earns or loses its keep.

But I would not call the suite “tight” yet. The three things I would fix first now are:

1. add stable diagnostic IDs and assert on those instead of human-readable message text
2. make the coherence story less global and less orphan-happy, or at least admit that it is a deliberate tradeoff
3. keep collapsing the remaining `SurfaceTest` / `ResolutionTest` overlap and clean up a few misleading tests

So: sane overall, strong on compiler plumbing, mixed on consistency, and only partly “FP-good” because the coherence story is still a bit too global and magical. A respectable suite with a few bad habits, like most institutions and nearly every programmer.
