# Plan

## Active Review Remediation

Tasks below come from the latest correctness-focused review. None of these should be
closed until there is:
- a targeted regression test that reproduces the claimed drift or false positive,
- an implementation change or an explicit proof that the review claim is stale,
- and a focused re-verification pass plus full-suite confirmation.

### 1. Fix FIR sealed-derivation false positives caused by non-local return inside `all { ... }`

Checklist:
- [x] Add a regression that proves `ResolutionIndex.canDeriveSealedGoalFromTarget(...)` currently over-accepts when one subclass cannot produce a usable sealed case type.
- [x] Cover the user-visible consequence in FIR masking / overload selection so the bug is pinned at the language level, not only at the helper level.
- [x] Replace the non-local `return true` inside the `subclassIds.all { ... }` lambda with correct per-subclass failure handling.
- [x] Re-verify the existing sealed-derivation FIR masking regressions after the fix.

Detailed notes:
- The specific hazard is in `TypeclassPluginSharedState.kt`, `ResolutionIndex.canDeriveSealedGoalFromTarget`.
- Today this code path contains `deriveSealedCaseType(...) ?: return true` inside an inline `all { ... }` lambda, which returns from the enclosing function rather than “skipping” the current subclass.
- That means one unhandled subclass can incorrectly turn the whole sealed root into “derivable = true”.
- Because `sharedState.canDeriveGoal(...)` feeds FIR context stripping, this can recreate the exact phase skew the earlier review already found: FIR hides a required context and IR later cannot actually derive it.
- Completed in this pass:
  - Added `unhandledGenericSealedSubclassesDoNotMakeConcreteRootsLookDerivable`.
  - Changed the inline-lambda bug from a non-local success return to per-subclass skipping, so FIR now evaluates the remaining expressible cases instead of short-circuiting the whole sealed root to derivable.
  - Re-verified the targeted FIR masking regression plus representative GADT derivation success/failure cases to keep the fix conservative without suppressing legitimate IR-side sealed derivation.

### 2. Unify product/sum deriver contracts between FIR validation and FIR/IR codegen lookup

Checklist:
- [ ] Decide the intended policy for inherited/default `deriveProduct` / `deriveSum` implementations on deriver companions.
- [ ] Add regressions for companions that satisfy `ProductTypeclassDeriver` / `TypeclassDeriver` only through inherited or default interface methods.
- [ ] If inherited/default methods are supported, teach both FIR and IR derive-method resolution to resolve the actual inherited implementation instead of only scanning declared methods.
- [ ] If inherited/default methods are not supported, tighten FIR validation so the declaration-side contract matches later codegen lookup.
- [ ] Re-verify enum/product/sum derivation diagnostics so all shapes enforce the same contract.

Detailed notes:
- FIR shape validation currently only requires an explicit override for enums via `requiredDeriveMethodNameForDeriveShape()`.
- Products and sums are treated as valid as soon as the companion implements the appropriate deriver interface.
- Later, both FIR and IR resolve derive methods via declaration-only scans:
  - `DeriveSupport.kt`, `FirRegularClassSymbol.resolveDeriveMethod`
  - `TypeclassIrGenerationExtension.kt`, `IrClass.resolveDeriveMethod`
- So the advertised declaration contract and the codegen contract differ today.
- The desired contract is “what passes declaration validation is exactly what codegen can invoke,” with no FIR/IR split over inherited defaults.

### 3. Bring FIR deriver return-shape validation up to parity with IR for `Any`-typed returns

Checklist:
- [x] Add declaration-site regressions for `deriveProduct(...): Any = ExistingInstanceObject`.
- [x] Add declaration-site regressions for `deriveProduct(...): Any = SomeImpl(...)`.
- [x] Extend FIR `knownReturnedTypeclassConstructors(...)` recognition to the same concrete return-expression shapes IR already accepts.
- [x] Re-verify that truly wrong-return-type derivers still fail with the intended FIR diagnostic.

Detailed notes:
- The FIR-side validation in `TypeclassFirCheckersExtension.validateTypeclassDeriverCompanionContracts` falls back to `knownReturnedTypeclassConstructors(...)`.
- That FIR helper currently only recognizes `FirAnonymousObjectExpression`.
- IR-side validation is broader and already accepts concrete object values and constructor calls.
- This creates a direct correctness split: FIR rejects code that the rest of the plugin treats as valid and that the diagnostic text itself says should be allowed.
- Completed in this pass:
  - Added `deriveProductMayReturnAnExistingInstanceObjectThroughAny`.
  - Added `deriveProductMayReturnAConcreteConstructorCallThroughAny`.
  - Extended FIR return-shape recognition to inspect resolved return implementations for named object values and constructor-call results, not just anonymous objects.
  - Re-verified the focused regressions while keeping the existing wrong-return-type negative coverage intact.

### 4. Make derived product metadata visibility-safe

Checklist:
- [x] Add regressions for `@Derive` on products with private stored properties.
- [x] Add regressions for `@Derive` on products with private primary constructors.
- [x] Decide whether the intended policy is “reject non-public product shapes” or “emit metadata lambdas inside an allowed scope”.
- [x] Align derived product metadata generation with the chosen visibility policy.
- [x] Re-verify existing constructive product/value-class derivation coverage after the change.

Detailed notes:
- `TypeclassIrGenerationExtension.buildDerivedProductShape` currently accepts stored properties and the primary constructor without visibility filtering.
- `buildProductFieldAccessor` emits direct getter calls.
- `buildProductConstructor` emits direct constructor calls.
- That can synthesize illegal accesses when derivation metadata is materialized from outside the target type’s visibility boundary.
- Completed in this pass:
  - Added `constructiveProductDerivationRejectsPrivateStoredProperties`.
  - Added `constructiveProductDerivationRejectsPrivatePrimaryConstructors`.
  - Chose the simpler conservative policy: reject non-public product shapes instead of trying to synthesize metadata lambdas inside privileged scopes.
  - Added IR-side visibility gates for stored-property getters and primary constructors so derived product metadata can no longer emit illegal accesses.
  - Re-verified the focused constructive product regressions and the full suite.

### 5. Include extension-receiver evidence in FIR type-argument inference

Checklist:
- [x] Add a regression where a generic call can only infer `T` from receiver-backed typeclass evidence.
- [x] Add the matching control case using an ordinary context parameter to prove FIR and IR should behave the same.
- [x] Extend `TypeclassFirCallTypeInference.inferFunctionTypeArgumentsFromCallSite(...)` to mine enclosing receiver-backed typeclass evidence alongside context parameters.
- [x] Re-verify FIR refinement / overload-selection behavior for receiver-scoped contextual environments.

Detailed notes:
- `TypeclassFirCallTypeInference.inferFunctionTypeArgumentsFromCallSite` currently mines only `containingFunction?.fir?.contextParameters` as local evidence for contextual inference.
- The enclosing receiver parameter is omitted there, even though `buildTypeContext()` and the IR path already treat receiver evidence as available.
- That creates a receiver-vs-context-parameter skew in FIR: the same logical evidence can refine or infer type arguments in IR, but be invisible to FIR when it is supplied via the receiver.
- Completed in this pass:
  - Tightened the receiver-backed regression so overload selection really depends on type inference from the enclosing typeclass receiver.
  - Kept the existing ordinary context-parameter case as the control specimen.
  - Extended FIR call-site inference to mine enclosing typeclass receivers alongside context parameters.
  - Re-verified the focused receiver-backed regression, the sealed masking regressions it interacts with, and the full suite.

## Completed Review Remediation

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

- [x] Add negative derivation-boundary coverage for missing or incomplete boundary shapes.
  - [x] Missing sealed case in another file.
  - [x] Missing sealed case in another module.
  - Detailed notes:
    - Added a same-compilation split-file regression proving a bad sealed case in another file prevents exporting the root derived instance even when the consumer only uses a supported case.
    - Added the matching dependency-module regression proving a bad dependency-side sealed case blocks `Show<Token>` export for a supported root-typed consumer use.
    - Kept the stronger existing dependency-side missing-field-evidence regression that exercises the failing case directly.
- [x] Add negative derivation-boundary coverage for conflicts.
  - [x] Manual instance in another file conflicting with a derived instance.
  - [x] Manual instance in another module conflicting with a derived instance.
  - Detailed notes:
    - Added a same-module split-file ambiguity regression using a legal top-level instance in the typeclass file to conflict with a derived sealed-root instance.
    - Reused the existing dependency-module companion-export ambiguity regression for the cross-module conflict case.
    - Deliberately did not extend this pass to top-level same-file dependency exports, because that remains the separate open policy item in P0.
- [x] Add derivation-boundary coverage for multiple `@Derive(...)` heads on the same type.
  - [x] Mixed success/failure semantics in one file boundary.
  - [x] Mixed success/failure semantics across module boundaries.
  - Detailed notes:
    - Added the split-file same-compilation pair proving `Show<Token>` still works while `Eq<Token>` stays unavailable.
    - Kept the existing dependency-module pair proving the same partial success/failure contract across module boundaries.
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
