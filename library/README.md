# kotlin-typeclasses runtime library

`kotlin-typeclasses` is the published runtime library for the `one.wabbit.typeclass` compiler-plugin family. Use it when you want Kotlin context parameters to behave like explicit, compile-time-checked typeclass evidence instead of manually threading dictionaries through every call.

It contains the public source and runtime surface used by normal Kotlin code:

- annotations such as `@Typeclass`, `@Instance`, `@Derive`, `@DeriveVia`, and `@DeriveEquiv`
- helper APIs such as `summon()`
- derivation metadata and derivation interfaces
- builtin proof interfaces such as `Same`, `Subtype`, `KnownType`, and `TypeId`

There are no compiler internals in this module.

## Repository Role

The typeclasses project family is split into four parts:

- `kotlin-typeclasses`: this runtime library
- `kotlin-typeclasses-plugin`: the Kotlin compiler plugin that performs resolution and code generation
- `kotlin-typeclasses-gradle-plugin`: the Gradle bridge that wires the compiler plugin into Kotlin builds
- `kotlin-typeclasses-ij-plugin`: the IntelliJ helper plugin for IDE-side compiler-plugin loading

Most consumers need this library together with the Gradle plugin.

The runtime library keeps the base project version, while the compiler plugin is published as a Kotlin-specific variant such as `one.wabbit:kotlin-typeclasses-plugin:0.0.1-kotlin-2.3.10`.

## Status

This module is experimental and pre-1.0. Annotation names, generated metadata contracts, and proof APIs may still change between minor releases; source-level breaks are called out in the migration guide with mechanical upgrade notes.

## Installation

- coordinates: `one.wabbit:kotlin-typeclasses:0.0.1`

Most applications should add this dependency and apply the Gradle plugin from [`../gradle-plugin/README.md`](../gradle-plugin/README.md). The runtime alone does not enable compiler-plugin resolution.

## Public Surface

### `@Typeclass`

Marks a supported typeclass head as participating in typeclass resolution.

Ordinary user-defined typeclasses should be interfaces annotated with `@Typeclass`.

There is also a narrow advanced path for subclassable abstract/open class heads with accessible zero-argument constructors. This exists mainly for compiler-owned surfaces such as `Equiv` and generated `@DeriveVia` adapter code; it is not the recommended shape for ordinary application typeclasses.

### `@Instance`

Marks an object, function, or immutable property as a source of evidence.

The compiler plugin treats instance functions as rule-like declarations: no ordinary value parameters, optional context-parameter prerequisites, and a provided typeclass result.

Top-level instances are restricted: they must live in the same file as the typeclass head or one of the concrete provided classifiers in the target.

### `summon()`

`summon()` is the user-facing way to ask for evidence from the current context:

```kotlin
context(value: T)
fun <T> summon(): T = value
```

On its own, that is just a context-parameter helper. The compiler plugin is what makes `summon<Typeclass<...>>()` participate in typeclass resolution.

## Derivation Surface

The runtime owns the public derivation APIs.

Annotations:

- `@Derive`
- `@DeriveVia`
- `@DeriveEquiv`

Metadata and interfaces:

- `ProductFieldMetadata`
- `ProductTypeclassMetadata`
- `SumCaseMetadata`
- `SumTypeclassMetadata`
- `EnumEntryMetadata`
- `EnumTypeclassMetadata`
- `ProductTypeclassDeriver`
- `TypeclassDeriver`
- `RecursiveTypeclassInstanceCell`

These types are the public contract between user-authored typeclass companions and compiler-synthesized derived instances.

## Builtin Proof APIs

The runtime also exposes proof-oriented typeclass interfaces that the compiler can materialize directly.

Current proof surfaces include:

- `Same`
- `NotSame`
- `Subtype`
- `StrictSubtype`
- `Nullable`
- `NotNullable`
- `IsTypeclassInstance`
- `SameTypeConstructor`
- `KnownType`
- `TypeId`
- `Equiv`
- `Iso`
- optional builtin `KClass<T>` evidence when `builtinKClassTypeclass=enabled`
- optional builtin `KSerializer<T>` evidence when `builtinKSerializerTypeclass=enabled`

The singleton carriers used to implement some of these proofs are internal details. End users program against the public proof interfaces.

## Annotation Retention

The annotations in this module are intentionally not all the same:

- core compiler-consumed annotations such as `@Typeclass`, `@Instance`, and `@Derive` use binary retention
- tracing helpers such as `@DebugTypeclassResolution` use source retention

That split reflects their roles:

- binary-retained annotations define semantic structure the compiler plugin needs to see across boundaries
- source-retained tracing annotations are compile-time controls, not part of runtime behavior

## Quick Start

With the companion Gradle plugin:

```kotlin
plugins {
    kotlin("jvm") version "2.3.10"
    id("one.wabbit.typeclass")
}

dependencies {
    implementation("one.wabbit:kotlin-typeclasses:0.0.1")
}
```

Then write source code using:

- `@Typeclass` to declare a typeclass
- `@Instance` to publish evidence
- `@Derive` and related annotations to opt types into derivation
- `summon()` or explicit context parameters to consume evidence

For detailed semantics of scope, derivation, proofs, and optional builtins, use the dedicated guides in [`../docs/`](../docs/). Recommended order for new users: root quick start, user guide, typeclass model, derivation guide, then proof/builtin details.

## Changelog

Breaking changes and mechanical upgrade notes live in [`../docs/migration.md`](../docs/migration.md). Release publishing is managed from the repository root.

## Support

Use [`../docs/troubleshooting.md`](../docs/troubleshooting.md) first for diagnostics, tracing, and known limits. Report bugs through the repository issue tracker, and use [`../docs/development.md`](../docs/development.md) plus the legal docs under [`../legal/`](../legal/) for contribution workflow.
