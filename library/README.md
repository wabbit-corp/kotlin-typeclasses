# kotlin-typeclasses runtime library

`kotlin-typeclasses` is the published runtime library for the `one.wabbit.typeclass` compiler-plugin family.

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

## Artifact

- coordinates: `one.wabbit:kotlin-typeclasses:<version>`

## Public Surface

### `@Typeclass`

Marks an interface as participating in typeclass resolution.

Only interfaces annotated with `@Typeclass` are part of implicit typeclass search.

### `@Instance`

Marks an object, function, or immutable property as a source of evidence.

The compiler plugin treats instance functions as rule-like declarations: no ordinary value parameters, optional context-parameter prerequisites, and a provided typeclass result.

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

The singleton carriers used to implement some of these proofs are internal details. End users program against the public proof interfaces.

## Annotation Retention

The annotations in this module are intentionally not all the same:

- core compiler-consumed annotations such as `@Typeclass`, `@Instance`, and `@Derive` use binary retention
- tracing helpers such as `@DebugTypeclassResolution` use source retention

That split reflects their roles:

- binary-retained annotations define semantic structure the compiler plugin needs to see across boundaries
- source-retained tracing annotations are compile-time controls, not part of runtime behavior

## Typical Usage

With the companion Gradle plugin:

```kotlin
plugins {
    kotlin("jvm") version "2.3.10"
    id("one.wabbit.typeclass")
}

dependencies {
    implementation("one.wabbit:kotlin-typeclasses:<version>")
}
```

Then write source code using:

- `@Typeclass` to declare a typeclass
- `@Instance` to publish evidence
- `@Derive` and related annotations to opt types into derivation
- `summon()` or explicit context parameters to consume evidence

## Related Docs

- [`../README.md`](../README.md) for the project overview
- [`../docs/user-guide.md`](../docs/user-guide.md) for user-facing setup and semantics
- [`../docs/architecture.md`](../docs/architecture.md) for the repo-wide architecture
- [`../gradle-plugin/README.md`](../gradle-plugin/README.md) for build integration
- [`../compiler-plugin/README.md`](../compiler-plugin/README.md) for compiler-plugin behavior
