# Troubleshooting

This guide is for the common user-facing failure modes in `kotlin-typeclasses`.

The fast way to debug a failure is:

1. identify the diagnostic family
2. enable focused tracing on the failing scope
3. check placement, visibility, derivation prerequisites, and builtin materialization rules

## Quick Triage

The most common diagnostic families are:

- `TC_NO_CONTEXT_ARGUMENT`: no viable evidence was found for the requested goal
- `TC_AMBIGUOUS_INSTANCE`: more than one viable candidate remained
- `TC_CANNOT_DERIVE`: a requested derivation failed validation or could not be completed
- `TC_INVALID_EQUIV_DECL`: user code tried to author compiler-owned `Equiv` evidence directly

## `TC_NO_CONTEXT_ARGUMENT`

This means resolution did not end with a usable candidate.

Check these first:

- the requested interface is actually annotated with `@Typeclass`
- the call site or lexical scope already has direct contextual evidence
- a matching `@Instance` exists in associated scope or legal top-level scope
- the candidate's prerequisites can themselves be solved
- the relevant declaration is visible from the current module
- the goal is not asking for a builtin that is disabled or not materializable

### Common causes

- missing `@Typeclass` on the interface head
- `@Instance` declared in the wrong place
- top-level `@Instance` declared as an arbitrary orphan
- dependency instance is `internal` or `private`
- `@Derive` requested a typeclass that was never actually derived
- builtin requests like `KClass<T>`, `KSerializer<T>`, `KnownType<T>`, or `TypeId<T>` are made for a non-reified or otherwise non-materializable `T`

### Top-level instance ownership rule

Top-level `@Instance` declarations are intentionally constrained.

They must live in the same file as:

- the typeclass head, or
- one of the concrete provided classifiers in the target type

Examples:

- `Show<AlphaId>` may live beside `Show` or beside `AlphaId`
- `Show<Box<AlphaId>>` may live beside `Show`, `Box`, or `AlphaId`
- `Rel<Foo, Boo<Baz>>` may live beside `Rel`, `Foo`, `Boo`, or `Baz`

What is rejected:

- placing `Show<AlphaId>` in an unrelated `beta/Instances.kt`
- placing `Show<Box<AlphaId>>` in a file that owns none of `Show`, `Box`, or `AlphaId`
- assuming an entailed supertype like `Eq<Foo>` makes `Ord<Foo>` legal in `Eq.kt`

For placement strategy, see [Instance Authoring](./instance-authoring.md).

## `TC_AMBIGUOUS_INSTANCE`

This means more than one candidate survived resolution.

Typical causes:

- two overlapping generic rules
- a manual rule and a derived rule both provide the same target
- two different `@DeriveVia` paths reach the same requested typeclass goal through distinct user-visible paths
- local and non-local evidence both remain viable in a shape the resolver cannot collapse

How to fix it:

- remove or narrow one of the competing rules
- move instances into a more specific companion so unrelated code stops seeing them
- keep only one canonical rule per reachable scope for a given head/target pair
- pass the desired evidence explicitly through local context when you need a local override

## `TC_CANNOT_DERIVE`

This means the compiler validated a derivation request and rejected it, or could not complete it soundly.

Common causes for `@Derive`:

- the typeclass companion does not implement the required deriver contract
- a required field or case instance is missing
- the requested typeclass shape is outside the current derivation contract
- a sealed root cannot be exported because one case is unsupported or unresolved

Common causes for `@DeriveVia`:

- empty path
- generic target classes outside the current monomorphic boundary
- pinned `Iso` path segments that are disconnected, not singleton objects, or ambiguously attachable
- transported members mention the transported type parameter in unsupported positions

Common causes for `@DeriveEquiv`:

- validated or normalizing constructors
- extra hidden or mutable state
- ambiguous product permutations or sum-case matches
- generic targets outside the current supported boundary

For the full contracts, see [Derivation](./derivation.md).

## `TC_INVALID_EQUIV_DECL`

`Equiv<A, B>` is compiler-owned.

That means user code should not:

- subclass `Equiv` directly
- publish manual `@Instance` values of `Equiv`
- treat `Equiv` as the ordinary user-authored reversible-conversion surface

Use:

- `Iso<A, B>` for explicit user-authored reversible conversions
- `@DeriveEquiv` when you want compiler-exported equivalence evidence
- `@DeriveVia` when you want one derivation request to transport through equivalence

## Resolution Tracing

When the failure is not obvious, enable tracing at the narrowest useful scope.

Global compiler option:

```text
-P plugin:one.wabbit.typeclass:typeclassTraceMode=failures-and-alternatives
```

Source-scoped tracing:

```kotlin
@file:DebugTypeclassResolution(TypeclassTraceMode.FAILURES_AND_ALTERNATIVES)
```

Useful modes:

- `FAILURES`: trace failed and ambiguous roots
- `FAILURES_AND_ALTERNATIVES`: same, plus rejected-alternative explanation
- `ALL`: also trace successful roots
- `ALL_AND_ALTERNATIVES`: full local tracing
- `INHERIT`: keep the parent or global mode
- `DISABLED`: mute a nested scope

Important detail:

- bare `@DebugTypeclassResolution` means `FAILURES`
- it does not mean "inherit whatever the outer scope is doing"
- if you want to preserve an outer `ALL` or `ALL_AND_ALTERNATIVES` mode, use `mode = INHERIT`

Tracing is rooted at the failing declaration or call site. Annotating a callee does not automatically trace every caller.

## Builtin Materialization Failures

These failures often present as ordinary `TC_NO_CONTEXT_ARGUMENT`, but the real issue is materialization.

### `KClass<T>`

Check:

- `builtinKClassTypeclass=enabled`
- `T` is non-nullable
- `T` is runtime-materializable
- generic helpers are `reified`

What fails:

- `summon<KClass<T>>()` in a plain non-reified generic function
- `summon<KClass<String?>>()`

### `KSerializer<T>`

Check:

- `builtinKSerializerTypeclass=enabled`
- `kotlinx.serialization` runtime is present
- the serialization compiler plugin is applied where needed
- `T` is serializable and runtime-materializable
- the requested goal is not star-projected

What fails:

- non-serializable target types
- `KSerializer<List<*>>`
- non-reified generic `T`

### `KnownType<T>` And `TypeId<T>`

Check:

- `T` is concrete or reified enough for runtime materialization

What fails:

- unfixed non-reified generic `T`

For exact contracts, see [Proofs And Builtins](./proofs-and-builtins.md).

## Cross-Module Surprises

When a producer module compiles but a consumer cannot resolve evidence, check:

- whether the instance is actually `public`
- whether the evidence was exported at all
- whether the consumer compilation itself has the plugin enabled

Important examples:

- `internal` and `private` dependency instances do not leak downstream
- public companion instances from dependencies do participate in downstream resolution
- derived sealed-root evidence is not exported if the producer's hierarchy is incomplete or unsupported
- `@DeriveEquiv` exports summonable `Equiv`, but transient `Equiv` links used only inside one `@DeriveVia` request do not

For the full cross-module model, see [Multi-Module Behavior](./multi-module.md).

## Current Hard Limitation

Contextual property getter reads are still limited by the public FIR plugin API's lack of a property-read refinement hook.

That means source shapes like contextual property reads can still fail even when analogous function calls work.

See [compiler-plugin/ISSUE_PROPERTIES.md](../compiler-plugin/ISSUE_PROPERTIES.md) for the current status.

## Related Docs

- [Typeclass Model](./typeclass-model.md)
- [Instance Authoring](./instance-authoring.md)
- [Derivation](./derivation.md)
- [Proofs And Builtins](./proofs-and-builtins.md)
- [Multi-Module Behavior](./multi-module.md)
