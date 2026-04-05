Current open items from this review:

## Suite-boundary cleanup

- [ ] Collapse the remaining `SurfaceTest` / `ResolutionTest` overlap.
  - [ ] Keep lowering-site and syntax-shape coverage in `SurfaceTest`.
  - [ ] Keep solver behavior in `ResolutionTest`.
  - [ ] Keep global contract behavior in `TypeclassContractTest`.
- [ ] Standardize on `kotlin.test.Ignore`.
- [ ] Keep trimming names that oversell smoke coverage as semantic coverage.

## Test naming / underpowered cases

- [ ] Re-evaluate `SessionScopedCacheTest` naming versus what it actually proves.
- [ ] Decide whether `PowerAssertInteropTest` should stay compile-only smoke or grow runtime semantics.

## Derivation contract

- [ ] Decide whether root-only `@Derive` is the intended contract or whether leaf annotations are equally first-class.
- [ ] Make the suite express that contract consistently.
  - [ ] Remove redundant leaf annotations if root-only is the contract.
  - [ ] Or add explicit tests for both styles if both are supported.

## Coherence policy

- [ ] Decide whether nested argument files like `Baz.kt` are legal instance locations for heads like `TC<Foo, Boo<Baz>>`.
- [ ] Decide whether legal same-file top-level instances in dependency modules are part of the intended public model.

## Ignored-test triage

- [ ] Split `GADTDerivationTest` into active conservative-boundary tests and speculative future-work tests.
- [ ] Decide whether `FutureFeatureTest` belongs in the test tree at all.
- [ ] Revisit ignored import-visibility cases once orphan-location semantics are final.
- [ ] Revisit ignored contextual-property cases only if FIR API support changes.

## Follow-up priorities from this review

- [ ] Add more helper-scope smart-cast cases beyond `?.let`, `also`, and `run`.
