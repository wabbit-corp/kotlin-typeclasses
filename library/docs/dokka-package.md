# Package one.wabbit.typeclass

Public runtime package for the `kotlin-typeclasses` compiler-plugin family.

Most user code interacts with four groups of APIs:

- core annotations such as `@Typeclass` and `@Instance`
- evidence consumption through `summon()` and explicit context parameters
- derivation annotations and metadata such as `@Derive`, `@DeriveVia`, `ProductTypeclassMetadata`, and `TypeclassDeriver`
- builtin proof and type-metadata surfaces such as `Same`, `Subtype`, `KnownType`, and `TypeId`

## Core Annotations

Use:

- `@Typeclass` to mark interfaces that participate in implicit typeclass resolution
- `@Instance` to publish evidence declarations
- `@Derive`, `@DeriveVia`, and `@DeriveEquiv` to request compiler-synthesized evidence

These annotations are part of the public contract between user code and the compiler plugin.

## Evidence Consumption

`summon()` is the public helper used to request evidence from the current contextual scope.

Under the compiler plugin, `summon<Typeclass<...>>()` becomes the user-facing surface for typeclass resolution.

## Derivation API

Typeclass companions implement:

- `ProductTypeclassDeriver` for product-only derivation
- `TypeclassDeriver` for product, sum, and enum derivation

Metadata types such as `ProductFieldMetadata`, `SumCaseMetadata`, and `EnumTypeclassMetadata` describe the compiler-inspected structure of the derived type.

## Proof And Type Metadata API

The package exposes builtin proof-oriented typeclasses, including:

- equality and subtyping surfaces such as `Same`, `NotSame`, `Subtype`, and `StrictSubtype`
- nullability surfaces such as `Nullable` and `NotNullable`
- type-shape and reflection surfaces such as `SameTypeConstructor`, `KnownType`, and `TypeId`
- typeclass-meta reasoning such as `IsTypeclassInstance`

Use `KnownType` when you need exact reflective `KType` structure. Use `TypeId` when you need stable semantic identity and hashing.

## Tracing

`@DebugTypeclassResolution` and `TypeclassTraceMode` control compiler-side tracing and diagnostics. They are source-level debugging aids, not part of runtime program semantics.

## Generated-Code And Internal-Support API

Some declarations are public so generated code can reference them across module boundaries, but they are not intended as primary end-user APIs:

- `Equiv` is compiler-owned reversible equivalence evidence
- `GeneratedTypeclassWrapper` and `GeneratedTypeclassInstance` mark generated declarations
- `InternalTypeclassApi` gates low-level support surfaces such as `unsafeEquiv(...)` and compiler-facing carriers

If you are writing ordinary application or library code, prefer the higher-level annotations, proof interfaces, and derivation APIs instead of depending directly on those internal-support declarations.
