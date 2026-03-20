# Plan

## Compiler Review Remediation

Tasks below are derived directly from the latest compiler review and are meant to be
closed only after a targeted regression test exists, the behavior is verified, and
the code is either fixed or the review claim is explicitly proven stale.

### 1. Fix `DeriveViaTransport` recursion bookkeeping

- [x] Add a regression that transports repeated sibling shapes through `DirectTransportPlanner.synthesize`.
  - [x] Use a shape equivalent to `P1(A, A) -> P2(B, B)` so sibling reuse hits the same `(A, B)` pair twice.
  - [x] Verified that the old planner falsely reported recursion/derivation failure.
- [x] Add a regression that validates `DeriveVia` transportability for repeated sibling nominal shapes.
  - [x] Use a transported member type with two sibling occurrences of the same structural transported wrapper.
  - [x] Verified that `transportabilityViolation` falsely reported recursive nominal transport shapes.
- [x] Replace the global visited-set behavior with stack-aware recursion tracking and/or memoization.
- [x] Re-run the new regressions plus the full `DeriveVia` suite.

### 2. Unify FIR and IR derivability checks

- [x] Add a regression proving `@DeriveEquiv(B::class)` on `A` is accepted when resolving `Equiv<A, B>`.
- [x] Add a regression proving `@Derive` on `Foo` does not make `TC<Foo?>` look derivable.
- [x] Add a regression for unsupported generic `@DeriveEquiv` / `@DeriveVia` targets.
  - [x] Verified that these unsupported shapes need declaration-site diagnostics instead of silent IR dropping.
- [x] Add a regression for `@Derive` on a non-unary typeclass.
  - [x] Verified and fixed declaration-site diagnosis for unsupported arity.
- [x] Replace `ResolutionIndex.canDeriveGoal` heuristics with a derivation model that matches actual rule generation.
  - [x] `Equiv` ownership and transported-slot logic now follow actual generated-rule ownership more closely, with precise derive-equiv targets when available and a binary-dependency fallback for exported generated metadata.
  - [x] Nullability, generic targets, and unsupported typeclass arities are now rejected consistently enough for rule generation and diagnostics; the nullable `Show<Foo?>` regression remains IR-reported today, so the test pins the current phase explicitly.
- [x] Re-run the new derivability regressions plus the full suite.

### 3. Preserve the full terminal `via` type in `DeriveVia`

- [x] Add a regression for `DeriveVia` through a nullable terminal type such as `String?`.
- [x] Add a regression for `DeriveVia` through a parameterized terminal type such as `List<Int>`.
- [x] Add a regression for `DeriveVia` through a function-shaped terminal type if the path solver admits one.
- [x] Change `toDeriveViaRules` so the prerequisite goal uses the full model from `resolvedPath.viaType`.
- [x] Re-run the new `DeriveVia` regressions plus the full suite.

### 4. Make `DeriveVia` adapters and validation see inherited abstract members

- [x] Add a regression where the requested typeclass extends another typeclass with inherited abstract members.
  - [x] Verified transportability validation sees the inherited surface.
  - [x] Verified generated adapters implement inherited abstract members.
- [x] Record the review claim as already fixed/stale rather than landing redundant backend churn.
- [x] Re-run the inheritance regression plus the full `DeriveVia` suite.

### 5. Scope recursive admissibility to the active rule

- [x] Add a planner-level regression where one rule for a goal supports recursion and another does not.
  - [x] Verified the non-recursive rule previously inherited recursive legality from the unrelated recursive candidate.
- [x] The planner-level regression was sufficient; no extra integration regression was needed.
- [x] Change `TypeclassResolutionPlanner.resolveInternal` so recursion is admitted only for the currently active rule path.
- [x] Re-run planner tests and the full suite.

### 6. Strengthen deriver return-type validation

- [x] Add a regression where a typeclass companion declares `deriveProduct(...): Any = 42`.
- [x] Add matching regressions for `deriveSum` and `deriveEnum` if they are validated through the same code path.
- [x] Change FIR validation so declared deriver return types must expand to the owning typeclass constructor.
- [x] Change IR validation to use the same declared-return-type rule when the declared return type is informative, and only enforce body-expression checks when IR can actually recognize the returned constructor shape.
- [x] Re-run the new validation regressions plus the full suite.

### 7. Resolve the `TypeId` semantic/doc mismatch

- [x] Review the existing implementation and docs.
- [x] Decide to weaken the docs rather than claim a stronger reflective canonicalization than the runtime currently implements.
  - [x] Update the sibling runtime `Proofs.kt` documentation locally so reflective fallback ids are allowed to retain reflective type-parameter names for non-concrete `KType` values.
- [x] No plugin-repo tests changed here because the runtime tree is outside this git repo; the plugin proof tests still passed unchanged.

### 8. Review smaller claims from the same review and either fix or explicitly close them as stale

- [x] Check `IrType.satisfiesExpectedContextType` for generic-supertype substitution errors.
  - [x] Added a focused explicit-context regression and confirmed that the broader explicit-context frontend path is still blocked before that backend check can be isolated; kept the regression ignored with that note, and tightened the IR-side matching logic in the meantime.
- [x] Audit unsupported derivation sites that are currently silently ignored.
  - [x] Generic/non-monomorphic `@DeriveVia` / `@DeriveEquiv` targets and non-unary `@Derive` heads now report `TC_CANNOT_DERIVE` instead of being silently dropped.
- [x] Record which review claims were already fixed before this pass.
  - [x] The inherited-abstract-member `DeriveVia` claim was already stale; the new regressions proved the existing backend already handled it.

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
