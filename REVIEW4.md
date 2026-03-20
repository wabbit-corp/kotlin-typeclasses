# REVIEW4 Open Checklist

## P0: Coherence and policy

- [ ] Decide whether nested argument files like `Baz.kt` are legal orphan locations for heads like `TC<Foo, Boo<Baz>>`.
- [ ] Add an active dependency-module test for legal same-file top-level exported instances if that behavior is intended.
- [ ] Decide and test semantics for global explicit serializer evidence vs synthetic builtin serializer evidence.
- [ ] Decide and test semantics for explicit user-defined builtin-shaped rules competing with synthetic builtin materialization.
- [ ] Add a repeat-success determinism test strong enough to prove cached discovery does not accumulate ghost ambiguity.

## P1: Semantic gaps

- [ ] Add a smart-cast test for `takeIf` or a similar scoping helper.
- [ ] Add explicit root-only `@Derive` semantics coverage across files.
- [ ] Add explicit leaf-only `@Derive` semantics coverage across files if supported.
- [ ] Add a test proving a missing or non-derived sealed case in another file yields a useful failure.
- [ ] Add a test proving a manual instance in another file conflicts sanely with a derived instance.
- [ ] Add a test for multiple `@Derive(...)` typeclasses on the same type across file boundaries.
- [ ] Add planner and integration coverage for a longer cycle such as `A -> B -> C -> A`.
- [ ] Decide and test semantics for direct exact global instance vs superclass-entailed candidate.
- [ ] Add a test for two generic rules where one structurally subsumes the other and lock down whether the result is ambiguity or dominance.

## P2: Additional lowering and interop coverage

- [ ] Add contextual-call coverage inside `try/catch/finally`.
- [ ] Add delegated-property operator coverage for `provideDelegate`, `getValue`, and `setValue`.
- [ ] Add unary operator coverage for `unaryMinus`, `not`, `inc`, and `dec`.
- [ ] Add `+=` fallback coverage when Kotlin rewrites to `plus`.
- [ ] Add richer `invoke` operator coverage.
- [ ] Add destructuring-in-lambda-parameter coverage.
- [ ] Add `for ((a, b) in xs)` coverage if contextual `componentN` support is intended.
- [ ] Add Compose tests for contextual calls inside composable lambda bodies, default parameter expressions, `remember`, and effect scopes.
- [ ] Add combined-plugin execution regressions such as `Serialization + Derive`, `Parcelize + Derive`, `AtomicFu + Derive`, or `AllOpen + NoArg + Derive`.

## P3: More derivation and proof coverage

- [ ] Add derivation coverage for a multi-parameter shape like `Either<A, B>`.
- [ ] Add derivation coverage for enums with constructor parameters.
- [ ] Add derivation coverage for nested sealed subclasses.
- [ ] Add derivation coverage mixing `object`, `data object`, `data class`, and plain class under one sealed interface.
- [ ] Add derivation coverage where a nested generic field instance is found via associated companion lookup.
- [ ] Add `KnownType` coverage across dependency boundaries.
- [ ] Add `TypeId` coverage across dependency boundaries.
- [ ] Add `Subtype` / `StrictSubtype` coverage for definitely-non-null or intersection-like interactions if intended.
- [ ] Add tests proving explicit local proof evidence beats builtin proof materialization when both are available.
- [ ] Add `SameTypeConstructor` coverage for nullable, function-shaped, or projection-heavy types if intended.

## P4: Small machinery unit tests

- [ ] Expand `SessionScopedCacheTest` for equal-but-not-identical keys, exception behavior, separate cache instances, and re-entrant same-key behavior.
- [ ] Expand `WrapperPlannerTest` further for bindable desired vars not overbinding hidden/internal vars.
