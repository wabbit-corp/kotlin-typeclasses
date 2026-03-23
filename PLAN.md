# Plan

## Current Review Remediation

Tasks below are derived directly from the current compiler review and should only be
closed after a targeted regression test exists, the behavior is re-verified, and the
implementation is either fixed or the review claim is explicitly proven stale.

### 1. Stop FIR from stripping typeclass contexts before actual solvability is proven

Detailed notes:
- The core bug is phase skew: FIR currently treats some calls as context-free because the target head is derivable in principle, even when no actual instance can be constructed in scope.
- The desired contract is “if FIR strips a context, IR must be able to build the same plan without inventing new evidence later.”
- Completed in this pass:
  - Added `missingDerivedPrerequisitesDoNotBiasFirOverloadSelection`.
  - Added `missingDerivedPrerequisitesForSealedSumsDoNotBiasFirOverloadSelection`.
  - Added `missingDerivedPrerequisitesForGenericSealedSumsDoNotBiasFirOverloadSelection`.
  - Tightened FIR masking so direct-owner product derivation checks actual prerequisite solvability against available contexts instead of a pure shape heuristic.
  - Tightened the same FIR masking path for reproduced same-source sealed roots, including the simple generic sealed-sum case, so missing case prerequisites no longer bias overload resolution while binary/GADT-heavy sealed derivation stays on the older conservative path.
  - Re-verified the focused regressions, broader resolution / derivation suites, and the full test suite.

### 2. Unify generated derivation metadata and precise cross-module reconstruction

Detailed notes:
- The architectural problem is split-brain metadata: FIR currently has broader generated-metadata visibility than IR, while `Equiv` target precision can collapse to “owner supports Equiv at all.”
- The desired contract is “binary metadata alone must be enough to decide and reconstruct the same derived rule in both phases.”
- Completed in this pass:
  - Added `dependencyDeriveEquivDoesNotMakeUnrelatedTargetsLookDerivable`.
  - Added downstream consumer regressions for dependency-exported `@DeriveVia`, including both plain-waypoint and pinned-`Iso` boundary shapes.
  - Switched FIR-side `Equiv` acceptance to require a precise target match instead of the older “owner supports Equiv at all” fallback.
  - Fixed FIR binary annotation argument recovery so dependency `@DeriveEquiv(otherClass = ...)` requests are read precisely from compiled annotations.
  - Re-verified that dependency consumers can directly use exported `DeriveVia` and `DeriveEquiv` instances across module boundaries on the supported authoring path.
  - Added a shared `GeneratedDerivedMetadata` codec with round-trip tests for `derive`, `derive-via`, and `derive-equiv`.
  - Extended `GeneratedTypeclassInstance` metadata with a precise `payload` field so binary markers preserve `derive-equiv` targets and `derive-via` authored path segments.
  - Switched FIR and IR binary reconstruction to the same decoded marker model, while still falling back to authored annotations for source declarations and older/incomplete metadata.
  - Fixed IR generated-marker flattening and binary reconstruction for repeatable `DeriveVia` / `DeriveEquiv` containers.
  - Re-verified `GeneratedDerivedMetadataTest`, `DeriveViaSpec`, and the full test suite.

### 3. Replace name-only deriver discovery with contract-resolved methods

Detailed notes:
- The current failure mode is brittle happy-path logic: FIR checks “a method with this name exists,” while IR later does `singleOrNull { name == ... }` and can mis-handle helpers or overloads.
- The desired contract is “only the actual override that fulfills the deriver interface counts as the derive method.”
- Completed in this pass:
  - Added helper-overload regressions for product and enum derivation.
  - Replaced name-only deriver discovery with contract-resolved methods in FIR and IR.
  - Carried the resolved derive-method identity into IR rule references instead of re-searching by name later.

### 4. Restrict FIR deriver validation to actual deriver-interface overrides

Detailed notes:
- The current checker is over-eager: any companion helper whose name matches `deriveProduct` / `deriveSum` / `deriveEnum` is treated as if it were a deriver override.
- The desired contract is “deriver validation is opt-in through the implemented deriver interfaces, not through method spelling alone.”
- Completed in this pass:
  - Added a positive regression for helper methods named like derivers on plain `@Typeclass` companions.
  - Limited FIR validation to real deriver-interface overrides instead of bare helper-name matches.
  - Re-verified the derivation capability suite and the full test suite.

### 5. Stop scanning the whole classpath twice per FIR session

Detailed notes:
- This is primarily a performance and architecture fault, not just a micro-optimization.
- The desired contract is “the plugin should not walk every package, callable, and classifier twice just to find the small subset relevant to contextual resolution.”
- Completed in this pass:
  - Extracted a shared FIR discovery seam (`scanTopLevelDeclarations`) and unit-tested it in `TypeclassDiscoveryScanTest`.
  - Replaced the separate source/rule world scans with one shared discovery pass that feeds both collectors.
  - Re-verified the affected integration suites and the full test suite.

## Existing Backlog

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
