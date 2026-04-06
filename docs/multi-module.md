# Multi-Module Behavior

This guide documents what `kotlin-typeclasses` does across source-set and dependency boundaries.

The short version:

- evidence can cross module boundaries
- visibility still matters
- derivation must have completed successfully in the producer before downstream code can use the exported result
- transient solver-only evidence is not automatically published

## What Actually Crosses A Module Boundary

These surfaces are designed to survive binary publication:

- binary-retained annotations such as `@Typeclass`, `@Instance`, `@Derive`, `@DeriveVia`, and `@DeriveEquiv`
- public `@Instance` declarations
- public generated evidence declarations marked with compiler metadata
- public derivation metadata/runtime surfaces in `one.wabbit.typeclass`

That is why the runtime library keeps these declarations public and why the compiler plugin re-discovers them in downstream compilations.

## Visibility Rules Still Apply

Downstream code only sees evidence that is actually visible.

In practice:

- `public` dependency instances can participate in downstream resolution
- `internal` dependency instances do not leak into downstream modules
- `private` companion instances do not leak either

This applies to manual instances just like any other Kotlin declaration.

## Public Companion Instances From Dependencies

Associated companion instances are a good cross-module default because they stay attached to the type they describe.

If a dependency publishes:

```kotlin
data class Box(val value: Int) {
    companion object {
        @Instance
        val show: Show<Box> = ...
    }
}
```

then downstream code asking for `Show<Box>` can resolve that instance normally.

## Top-Level Instances Across Modules

Top-level instances also work across modules, but they must satisfy the same ownership rule as local code.

A top-level `@Instance` must live in the same file as:

- the typeclass head, or
- one of the concrete provided classifiers in the target

That restriction is not relaxed just because the instance lives in another module.

For authoring guidance, see [Instance Authoring](./instance-authoring.md).

## Derived Evidence Across Dependencies

Derived evidence is exported only if derivation actually succeeded in the producer module.

That matters for sealed roots in particular:

- if a root `@Derive` succeeds, downstream code can use the exported derived root evidence
- if the producer's sealed hierarchy is incomplete or contains an unsupported case, the root evidence is not exported downstream
- downstream use sites then fail just like any other missing-evidence case

This is intentionally all-or-nothing at the exported root level. A partially valid hierarchy does not publish a misleading root instance.

## `@DeriveVia` Across Dependencies

`@DeriveVia` can be compiled in one module and consumed in another.

Important supported shapes include:

- a producer module deriving an instance via an upstream waypoint type
- pinned `Iso` singleton objects that live in an upstream dependency module
- downstream consumers using the already-exported derived instance

What does not get exported:

- transient local `Equiv` glue synthesized only while completing one `@DeriveVia` request

So a producer may use local equivalence synthesis to finish one derivation, while downstream code still cannot later `summon<Equiv<A, B>>()` unless there is explicit exported `Equiv` evidence.

## `@DeriveEquiv` Across Dependencies

`@DeriveEquiv` is the explicit export surface for equivalence evidence.

If module `B` declares:

```kotlin
@DeriveEquiv(A::class)
data class B(val value: Int)
```

then downstream code can directly summon `Equiv<B, A>` once it opts into the internal-support API:

```kotlin
@OptIn(InternalTypeclassApi::class)
val equiv = summon<Equiv<B, A>>()
```

Important boundary:

- only the requested target pair is exported
- unrelated targets do not become derivable just because some other `@DeriveEquiv` exists nearby

## Consumer-Side Compiler Configuration

Published evidence and runtime annotations can cross module boundaries, but resolution still happens in the current compilation.

That means downstream source code still needs:

- the `kotlin-typeclasses` runtime on the classpath
- the compiler plugin enabled for the downstream compilation

Optional builtins are also consumer-side configuration:

- `builtinKClassTypeclass`
- `builtinKSerializerTypeclass`
- `typeclassTraceMode`

Treat those as properties of the current build, not as declarations a dependency "exports".

## Recommended Publishing Model

For reusable libraries:

- prefer public companion instances for type-specific evidence
- keep top-level instances in legal owner files
- avoid exporting both manual and derived evidence for the same head/target pair
- keep visibility intentional so downstream behavior is unsurprising

## Related Docs

- [Typeclass Model](./typeclass-model.md)
- [Derivation](./derivation.md)
- [Instance Authoring](./instance-authoring.md)
- [Troubleshooting](./troubleshooting.md)
