I reviewed the tests too. The verdict is annoyingly mixed: **the suite has excellent breadth, but a lot of the negative coverage is softer than it looks**.

## What’s actually good

The strongest part of the suite is the ugly-shape coverage in `SurfaceTest` and `ResolutionTest`. Those are doing real work.

They hit things people usually forget until the compiler starts eating drywall:

* safe-call + `let`
* default arguments
* named arguments
* operator rewrites
* higher-order calls
* local functions
* constructor delegation
* interface delegation
* builder inference
* nested overload/self-call shapes
* companion-associated instances
* supertype entailment
* recursive derivation

That part is solid. It is much closer to “try to break the plugin” than the usual ceremonial unit test nonsense.

`UtilityProofTest` is also broad in a good way. It exercises a lot of proof-surface API, conversions, composition, and using proofs as prerequisites. Good instinct.

## A lot of the remaining failing tests still lean on weak message matching

The harness now has a structured diagnostic path, but a lot of callers still use legacy `expectedMessages` substring checks.

That means some tests still prove only that a compile error happened somewhere near words already present in the file, which is softer than it should be.

The main remaining offenders are many negative cases in `UtilityProofTest` and a fair number of declaration-site rejection tests in `DerivationTest`.

That also means the suite does **not** yet reliably catch the FIR/IR phase split I pointed out earlier. A builtin proof can be “accepted” too early in FIR, fail later in IR, and some legacy assertions can still go green because a backend message happened to contain the right token soup.

## A few tests are misleading or scoped wrong

`ResolutionTest.doesNotImplicitlyResolveNonTypeclassContexts` is the clearest offender.

It tries to test “non-`@Typeclass` contexts are not auto-resolved,” but the sample also declares:

```kotlin
@Instance
object IntEq : Eq<Int>
```

where `Eq` is **not** a typeclass.

So the test is mixing two different failures:

* invalid `@Instance` declaration
* missing context argument for `same(1)`

That is sloppy. If it fails, you do not know which behavior you actually exercised. The fix is simple: make `IntEq` an ordinary object, not an `@Instance`.

`KClassBuiltinTest.rejectsNullableKClassMaterializationEvenInsideReifiedHelpers` is also suspect. `KClass<T>` already has a non-null bound shape in Kotlin, so that test risks checking the language’s own type constraint more than your plugin’s rejection path.

`AtomicFuInteropTest` and `PowerAssertInteropTest` are mostly **co-loading smoke tests**, not semantic interop tests. That is fine, but the names oversell them. AtomicFu especially can compile and run plenty of code even if its compiler plugin does nothing interesting.

`ParcelizeInteropTest` is underpowered too. Those tests have `main` functions and could often be run, but you only assert compile success.

## The ignored tests are doing double duty as documentation, and it shows

Some ignored tests are reasonable reminders of blocked API gaps, like callable references and contextual properties.

But whole classes like `FutureFeatureTest` are not really tests. They are design notes in test clothing. That’s not evil, just noisy.

The more serious case is `GADTDerivationTest`. That whole class is ignored, but it contains a mix of:

* future variance-aware derivation ideas
* what look like current conservative invariants you probably *do* want enforced

Because the whole class is ignored, **none of it is guarding anything**. If you care about the current conservative derivation boundary, split those into an active suite and leave the speculative stuff ignored elsewhere.

Same story for the ignored import-visibility tests. Since your implementation currently scans too much, the tests that would catch that are parked in the attic.

## Unit coverage is too thin in the pure logic layer

`WrapperPlannerTest` is decent, but it is not hitting some of the most important edge cases:

* `supportsRecursiveResolution = true`
* occurs-check / self-reference behavior
* nullability
* projections and star projections
* alpha-equivalent recursive goals
* ambiguity from multiple applicable rules with different prerequisites

`SessionScopedCacheTest` only checks trivial per-key memoization. That is fine, but it gives zero protection against the real bug I flagged earlier, which is the **usage pattern** where the cached value strongly retains the weak key.

You also have a lot of pure logic that deserves direct tests instead of being reached only through giant integration samples:

* associated-owner computation
* builtin admissibility filters
* derivation admissibility for sum cases
* type-argument mapping / explicit-context preservation

Those are exactly the places where the current implementation is shaky.

## The most important remaining test change

If you only fix one thing next in the tests, make it this:

1. **Finish migrating negative assertions to structured diagnostics.**
   The harness support is there now. Use it broadly enough that failing tests stop depending on raw stdout substring soup.

## The next tests I would add

* A test that `@Derive(Show::class)` does **not** make `Eq<T>` look derivable.
* A test where an explicit wrong typeclass argument is supplied and must **not** be rebound to a different context parameter.
* A test where a builtin inapplicable proof candidate must not create false ambiguity.
* A test that import visibility actually controls instance visibility, once that feature is meant to exist.

So: **good suite, weak truthiness**. The broad semantic positives are valuable. The remaining legacy negative tests are still too easy to satisfy for the wrong reason. Finish migrating those, and the suite will stop flattering bugs.
