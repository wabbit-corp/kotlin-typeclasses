# Plan

Deduplicated open work from `REVIEW2.md`, `REVIEW3.md`, and `REVIEW4.md`.

## P0: Policy and Coherence

- [ ] Decide the remaining orphan-location edge case for heads like `TC<Foo, Boo<Baz>>`.
  - [ ] Decide whether `Baz.kt`-style nested-argument ownership is legal.
  - [ ] Lock the answer down with active tests.
- [ ] Decide whether legal same-file top-level exported instances in dependency modules are part of the intended public model.
  - [ ] If yes, add an active dependency-module regression.
  - [ ] If no, add a negative declaration/resolution test instead.
- [ ] Decide root-only vs leaf `@Derive` semantics.
  - [ ] Decide whether root-only is the intended contract.
  - [ ] Decide whether leaf-only or mixed root+leaf annotation styles are also supported.
  - [ ] Express that contract consistently across files and modules.
- [ ] Decide global builtin override semantics.
  - [ ] Decide semantics for explicit global serializer evidence vs synthetic builtin serializer evidence.
  - [ ] Decide semantics for explicit user-defined builtin-shaped rules competing with synthetic builtin materialization.
- [ ] Add a repeat-success determinism regression strong enough to prove cached discovery does not accumulate ghost ambiguity.

## P1: Correctness and Semantic Gaps

- [ ] Add negative derivation-boundary coverage for missing or incomplete boundary shapes.
  - [ ] Missing sealed case in another file.
  - [ ] Missing sealed case in another module.
- [ ] Add negative derivation-boundary coverage for conflicts.
  - [ ] Manual instance in another file conflicting with a derived instance.
  - [ ] Manual instance in another module conflicting with a derived instance.
- [ ] Add derivation-boundary coverage for multiple `@Derive(...)` heads on the same type.
  - [ ] Mixed success/failure semantics in one file boundary.
  - [ ] Mixed success/failure semantics across module boundaries.
- [ ] Add more helper-scope smart-cast coverage.
  - [ ] `takeIf` or equivalent scoping helper.
- [ ] Add planner/integration coverage for longer recursive cycles such as `A -> B -> C -> A`.
- [ ] Decide and test exact-vs-entailed preference semantics.
  - [ ] Direct exact global instance vs superclass-entailed candidate.
- [ ] Decide and test rule-subsumption semantics.
  - [ ] Two generic rules where one structurally subsumes the other.
- [ ] Re-evaluate `KClassBuiltinTest.rejectsNullableKClassMaterializationEvenInsideReifiedHelpers`.
  - [ ] Confirm whether the failure is plugin-owned or just Kotlin’s `KClass<T : Any>` bound.
  - [ ] Replace or reframe the test if it is mostly language-owned.

## P2: Derivation and Proof Coverage

- [ ] Add derivation coverage where nested generic field evidence is found via associated companion lookup.
- [ ] Add dependency-boundary proof coverage.
  - [ ] `KnownType` across dependency boundaries.
  - [ ] `TypeId` across dependency boundaries.
- [ ] Add proof-behavior coverage for edge cases.
  - [ ] `Subtype` / `StrictSubtype` with definitely-non-null or intersection-like interactions, if intended.
  - [ ] Explicit local proof evidence beating builtin proof materialization.
  - [ ] `SameTypeConstructor` with nullable, function-shaped, or projection-heavy types, if intended.

## P3: Lowering and Interop Coverage

- [ ] Add contextual-call coverage in more lowered/control-flow positions.
  - [ ] `try/catch/finally`
  - [ ] Destructuring in lambda parameters
  - [ ] `for ((a, b) in xs)` if contextual `componentN` support is intended

## P4: Test Organization and Suite Hygiene

- [ ] Standardize on `kotlin.test.Ignore`.
- [ ] Revisit ignored import-visibility cases only after orphan-location policy is final.

## P5: Small Machinery and Unit-Test Gaps

- [ ] Add direct tests for associated-owner computation.
- [ ] Add direct tests for derivation/type-mapping logic that is currently only covered through integration tests.
- [ ] Expand `WrapperPlannerTest`.
  - [ ] `supportsRecursiveResolution = true`
  - [ ] Occurs-check / self-reference
  - [ ] Nullability
  - [ ] Projections and star projections
  - [ ] Alpha-equivalent recursive goals
  - [ ] Ambiguity from multiple applicable rules with different prerequisites
  - [ ] Bindable desired vars not overbinding hidden/internal vars
- [ ] Expand `SessionScopedCacheTest`.
  - [ ] Weak-key / strong-value retention pattern
  - [ ] Equal-but-not-identical keys
  - [ ] Exception behavior
  - [ ] Separate cache instances
  - [ ] Re-entrant same-key behavior
