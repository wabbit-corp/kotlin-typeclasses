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

## Negative diagnostics are in much better shape now

The harness now has a structured diagnostic path, and the active negative suites mostly use it.

That is a real improvement. The suite is less likely to pass because compiler stdout happened to echo the right source comment.

What remains is narrower:

* ignored `GADTDerivationTest` negatives still do not guard anything
* the harness still matches free-text messages rather than stable plugin diagnostic IDs
* backend-vs-frontend differences can still collapse into similarly worded generic diagnostics

## A few tests are misleading or scoped wrong

`KClassBuiltinTest.rejectsNullableKClassMaterializationEvenInsideReifiedHelpers` is also suspect. `KClass<T>` already has a non-null bound shape in Kotlin, so that test risks checking the language’s own type constraint more than your plugin’s rejection path.

`PowerAssertInteropTest` is still mostly a co-loading smoke test. That is fine, but the current name oversells it unless the harness starts running with `-ea`.

## The ignored tests are doing double duty as documentation, and it shows

Some ignored tests are reasonable reminders of blocked API gaps, like callable references and contextual properties.

But whole classes like `FutureFeatureTest` are not really tests. They are design notes in test clothing. That’s not evil, just noisy.

The more serious case is `GADTDerivationTest`. That whole class is ignored, but it contains a mix of:

* future variance-aware derivation ideas
* what look like current conservative invariants you probably *do* want enforced

Because the whole class is ignored, **none of it is guarding anything**. If you care about the current conservative derivation boundary, split those into an active suite and leave the speculative stuff ignored elsewhere.

The dedicated import-visibility suite is a better home for that coverage, but most of it is still ignored because the implementation still scans too broadly.

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
* some derivation and type-mapping logic that currently only has integration-level coverage or placeholders

Those are exactly the places where the current implementation is shaky.

## The most important remaining test change

If you only fix one thing next in the tests, make it this:

1. **Add stable diagnostic IDs and assert on those.**
   The big migration to structured diagnostics is mostly done in active suites. The next real quality jump is to stop keying tests off free-text messages altogether.

So: **good suite, better truthiness, still too stringly typed**. The broad semantic positives are valuable, and the negative tests are in much better shape than they were, but stable diagnostic IDs would make the failures much more trustworthy.
