Current open items from this review:

## Infrastructure precision

- [ ] Decide whether the harness should expose normalized diagnostic kinds like `missing-context`, `ambiguous-instance`, `invalid-instance-declaration`, and `cannot-derive`.
- [ ] Re-evaluate `KClassBuiltinTest.rejectsNullableKClassMaterializationEvenInsideReifiedHelpers`.
  - [ ] Confirm whether the failure is plugin-owned or just Kotlin's `KClass<T : Any>` bound.
  - [ ] Replace the test if it is mostly language-owned.
- [ ] Decide whether `PowerAssertInteropTest` should stay compile-only smoke or be renamed more explicitly.

## Derivation-boundary follow-up

- [ ] Add a negative cross-file/module case for a missing sealed subclass in another file.
- [ ] Add a negative cross-file/module case for a manual instance conflicting with a derived instance.
- [ ] Add a negative cross-file/module case for multiple `@Derive(...)` heads on the same boundary-crossing type with mixed success/failure semantics.

## Ignored-test debt

- [ ] Split `GADTDerivationTest` into active conservative-boundary tests and speculative future-work tests.
- [ ] Decide whether `FutureFeatureTest` should stay in the test tree or move to notes/docs.
- [ ] Revisit ignored import-visibility cases only after the orphan-location policy is fully settled.
- [ ] Revisit ignored contextual-property cases only if Kotlin FIR adds a workable hook.

## Logic/unit-test gaps

- [ ] Add direct tests for associated-owner computation.
- [ ] Add direct tests for derivation/type-mapping logic that still exists only as integration coverage.
- [ ] Expand `WrapperPlannerTest` further for:
  - [ ] `supportsRecursiveResolution = true`
  - [ ] occurs-check / self-reference
  - [ ] nullability
  - [ ] projections and star projections
  - [ ] alpha-equivalent recursive goals
  - [ ] ambiguity from multiple applicable rules with different prerequisites
- [ ] Expand `SessionScopedCacheTest` for the weak-key / strong-value retention pattern and more session-scoped behavior.

## Policy edge

- [ ] Decide the remaining file-local orphan-location edge case (`Baz.kt`-style nested argument ownership).
