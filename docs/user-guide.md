# User Guide

This guide covers the user-facing programming model of `kotlin-typeclasses`.

For deeper topic-by-topic coverage, see:

- [Typeclass Model](./typeclass-model.md)
- [Instance Authoring](./instance-authoring.md)
- [Derivation](./derivation.md)
- [Proofs And Builtins](./proofs-and-builtins.md)
- [Troubleshooting](./troubleshooting.md)
- [Multi-Module Behavior](./multi-module.md)

## Why This Model Exists

The project treats Kotlin context parameters as the substrate for a typeclass model, not as the entire feature.

What this plugin adds on top of plain context parameters is:

- a marker for which interfaces participate in typeclass search
- a rule model for publishing evidence
- associated lookup through companions
- derived instances
- builtin proofs and type metadata

Without that extra structure, Kotlin context parameters are just explicit capability passing.

## Setup

### Gradle

The recommended integration path is the Gradle plugin:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
```

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.3.10"
    id("one.wabbit.typeclass") version "<version>"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("one.wabbit:kotlin-typeclasses:<version>")
}
```

The Gradle plugin selects the compiler-plugin artifact variant that matches the applied Kotlin Gradle plugin version and adds `-Xcontext-parameters` automatically.

### Default behavior

By default:

- typeclass resolution only applies to `@Typeclass` interfaces
- direct local context is preferred before global rule search
- `KClass<T>` and `KSerializer<T>` synthetic evidence are disabled
- tracing is disabled

### Manual compiler wiring

If you are not using the Gradle plugin, you need:

- the runtime dependency `one.wabbit:kotlin-typeclasses:<version>`
- the compiler plugin artifact `one.wabbit:kotlin-typeclasses-plugin:<baseVersion>-kotlin-<kotlinVersion>`
- the compiler flag `-Xcontext-parameters`

Optional compiler-plugin options are described below in [Tracing And Optional Builtins](#tracing-and-optional-builtins).

## Authoring Typeclasses

Mark an interface with `@Typeclass`:

```kotlin
@Typeclass
interface Eq<A> {
    fun eq(left: A, right: A): Boolean
}
```

Only `@Typeclass` interfaces participate in implicit resolution.

For the full scope and search model, see [Typeclass Model](./typeclass-model.md).

## Search Precedence

The practical resolution order is:

1. directly available contextual evidence
2. indexed global and associated `@Instance` rules
3. derived rules where the target shape is derivable
4. builtin evidence when the goal matches a builtin contract

This is important because:

- a local context can satisfy a goal without any global search
- global and derived rules share one ambiguity space
- the plugin does not try to invent a hidden global coherence order

## Providing Evidence With `@Instance`

Supported instance shapes are:

- top-level `@Instance` objects
- top-level `@Instance` parameterless functions with context parameters
- top-level immutable `@Instance` properties
- associated companion members using the same shapes

Important rule:

- top-level instances are not arbitrary orphans; they must live in the same file as the typeclass head or one of the concrete provided classifiers in the target

Typical examples:

```kotlin
@Instance
object IntEq : Eq<Int> {
    override fun eq(left: Int, right: Int): Boolean = left == right
}

@Instance
context(left: Eq<A>, right: Eq<B>)
fun <A, B> pairEq(): Eq<Pair<A, B>> =
    object : Eq<Pair<A, B>> {
        override fun eq(leftValue: Pair<A, B>, rightValue: Pair<A, B>): Boolean =
            left.eq(leftValue.first, rightValue.first) &&
            right.eq(leftValue.second, rightValue.second)
    }
```

The design intent is that an `@Instance` function acts like a rule: it has no ordinary value parameters, and its context parameters describe prerequisites that must be resolved first.

For placement strategy, orphan restrictions, and ambiguity avoidance, see [Instance Authoring](./instance-authoring.md).

## Consuming Evidence

There are two normal ways to consume evidence:

- declare a context parameter explicitly
- summon the needed evidence from the current context

```kotlin
context(eq: Eq<A>)
fun <A> sameValue(value: A): Boolean = eq.eq(value, value)

context(_: Eq<A>)
fun <A> sameValueViaSummon(value: A): Boolean =
    summon<Eq<A>>().eq(value, value)
```

`summon()` itself is just:

```kotlin
context(value: T)
fun <T> summon(): T = value
```

The compiler plugin is what makes `summon<Eq<A>>()` behave like typeclass resolution rather than a plain context-parameter lookup.

## Associated Lookup

The plugin searches both direct contextual evidence and indexed `@Instance` declarations.

For a goal like `Show<Box<Int>>`, associated search includes:

- top-level `@Instance` declarations
- `Show`'s companion
- `Box`'s companion
- companions of sealed supertypes of `Show` and `Box`
- companions of type arguments such as `Int`, plus sealed supertypes where relevant

This lets companion-scoped rules participate naturally:

```kotlin
data class Box<A>(val value: A) {
    companion object {
        @Instance
        context(eq: Eq<A>)
        fun <A> boxEq(): Eq<Box<A>> =
            object : Eq<Box<A>> {
                override fun eq(left: Box<A>, right: Box<A>): Boolean =
                    eq.eq(left.value, right.value)
            }
    }
}
```

## Design Intent

The resolution model is intentionally constrained:

- annotation-driven rather than "any interface with the right shape"
- explicit about ambiguity
- companion-based rather than package-magic-based
- powerful enough for derivation and proofs, but still understandable from source

## Derivation

`kotlin-typeclasses` supports compiler-driven instance derivation.

Available annotations:

- `@Derive(Typeclass::class)` for ordinary derivation
- `@DeriveVia(typeclass = ..., path = ...)` for derivation through an intermediate type path
- `@DeriveEquiv(Other::class)` for equivalence-based derivation support

To make `@Derive` work, the typeclass companion implements one of the derivation interfaces from the runtime:

- `ProductTypeclassDeriver` for product-only derivation
- `TypeclassDeriver` for product, sealed-sum, and enum derivation

Conceptually:

```kotlin
@Typeclass
interface Show<A> {
    fun show(value: A): String

    companion object : TypeclassDeriver {
        override fun deriveProduct(metadata: ProductTypeclassMetadata): Any = TODO()
        override fun deriveSum(metadata: SumTypeclassMetadata): Any = TODO()
        override fun deriveEnum(metadata: EnumTypeclassMetadata): Any = TODO()
    }
}

@Derive(Show::class)
data class Box<A>(val value: A)

@Derive(Show::class)
sealed interface Token
```

The compiler plugin synthesizes the metadata and generated instances needed to call those derivation hooks.

For concrete end-to-end derivation examples, see:

- [`compiler-plugin/README.md`](../compiler-plugin/README.md)
- [`DerivationSurfaceTest.kt`](../compiler-plugin/src/test/kotlin/one/wabbit/typeclass/plugin/integration/derivation/DerivationSurfaceTest.kt)

For a dedicated derivation guide, including `@DeriveVia` and `@DeriveEquiv`, see [Derivation](./derivation.md).

## Builtin Proofs

The runtime includes proof-oriented typeclasses that the compiler can materialize directly. These are normal public APIs; users do not need to opt into the internal singleton carriers used to implement them.

Builtin proof surfaces include:

- `Same<A, B>`
- `NotSame<A, B>`
- `Subtype<Sub, Super>`
- `StrictSubtype<Sub, Super>`
- `Nullable<T>`
- `NotNullable<T>`
- `IsTypeclassInstance<TC>`
- `SameTypeConstructor<A, B>`
- `KnownType<T>`
- `TypeId<T>`

Example:

```kotlin
val same = summon<Same<Int, Int>>()
val subtype = summon<Subtype<Dog, Animal>>()
val known = summon<KnownType<List<String?>>>()
val typeId = summon<TypeId<List<String?>>>()
```

For concrete examples, see [`BuiltinProofApiSurfaceTest.kt`](../compiler-plugin/src/test/kotlin/one/wabbit/typeclass/plugin/integration/proofs/BuiltinProofApiSurfaceTest.kt).

For a dedicated guide that covers every builtin proof type plus `KClass<T>` and `KSerializer<T>`, see [Proofs And Builtins](./proofs-and-builtins.md).

## Tracing And Optional Builtins

Two compiler-plugin features are controlled by options instead of being always on:

- synthetic `KClass<T>` evidence
- synthetic `KSerializer<T>` evidence

The plugin also exposes a global trace mode.

CLI option names:

- `builtinKClassTypeclass=enabled|disabled`
- `builtinKSerializerTypeclass=enabled|disabled`
- `typeclassTraceMode=inherit|disabled|failures|failures-and-alternatives|all|all-and-alternatives`

On the Kotlin CLI, those options are passed with the standard compiler-plugin syntax:

```text
-P plugin:one.wabbit.typeclass:builtinKClassTypeclass=enabled
-P plugin:one.wabbit.typeclass:builtinKSerializerTypeclass=enabled
-P plugin:one.wabbit.typeclass:typeclassTraceMode=failures-and-alternatives
```

For source-scoped tracing, annotate a file, class, function, property, or local variable with `@DebugTypeclassResolution`:

```kotlin
@file:DebugTypeclassResolution(TypeclassTraceMode.FAILURES_AND_ALTERNATIVES)
```

For a practical debugging workflow, including how to interpret `TC_NO_CONTEXT_ARGUMENT`, `TC_AMBIGUOUS_INSTANCE`, and trace modes, see [Troubleshooting](./troubleshooting.md).

## Ambiguity, Coherence, And Failure Modes

The current model is intentionally explicit:

- direct contextual evidence is considered before global rule search
- multiple matching rules are an error
- there is no global coherence check across the whole program
- derived rules and manual rules share the same resolution space

In practice that means:

- exact design of instance placement matters
- overlapping generic rules are allowed to exist, but ambiguous use sites fail
- companion-based "associated" lookup is part of the programming model, not an implementation detail

## Current Boundaries And Non-Goals

The current implementation is intentionally not claiming more than it actually does.

Important current boundaries:

- there is no global coherence check across the whole program
- builtin evidence is selective and may reject goals whose types are not runtime-materializable
- contextual property getter reads are not fully supported yet because Kotlin's FIR plugin API exposes function-call refinement but not an equivalent property-read refinement hook

Examples of things the project is not trying to do:

- infer a hidden precedence order among overlapping global rules
- treat every interface as a typeclass based on structural shape alone
- turn the IntelliJ plugin into a second independent analysis engine

For the contextual-property limitation, see [`compiler-plugin/ISSUE_PROPERTIES.md`](../compiler-plugin/ISSUE_PROPERTIES.md).

## Where To Look Next

- Repo overview: [`README.md`](../README.md)
- Runtime API: [`library/README.md`](../library/README.md)
- Typeclass scope and resolution: [`typeclass-model.md`](./typeclass-model.md)
- Instance placement and publishing: [`instance-authoring.md`](./instance-authoring.md)
- Derivation details: [`derivation.md`](./derivation.md)
- Proofs and optional builtins: [`proofs-and-builtins.md`](./proofs-and-builtins.md)
- Diagnostics and debugging: [`troubleshooting.md`](./troubleshooting.md)
- Dependency and publishing behavior: [`multi-module.md`](./multi-module.md)
- Internal architecture: [`architecture.md`](./architecture.md)
- Local development and release model: [`development.md`](./development.md)
