# kotlin-typeclasses

`kotlin-typeclasses` is a K2 compiler-plugin stack for typeclass-oriented programming in Kotlin with context parameters.

It combines:

- a small runtime library with annotations, `summon()`, derivation metadata, and proof APIs
- a K2 compiler plugin that resolves typeclass evidence and rewrites contextual calls
- a Gradle plugin that wires the compiler plugin into Kotlin builds
- an IntelliJ IDEA helper plugin that enables external compiler-plugin loading for projects that use this stack

## Why This Exists

Kotlin context parameters give the language a useful capability-passing syntax, but they do not by themselves provide a typeclass programming model.

`kotlin-typeclasses` adds the missing pieces:

- implicit evidence search for interfaces marked with `@Typeclass`
- rule-style instance declarations with `@Instance`
- companion-based associated lookup
- derived instances for products, sums, enums, and equivalence-based shapes
- builtin proofs such as `Same`, `Subtype`, `KnownType`, and `TypeId`
- Gradle and IntelliJ integration so the feature is usable in real projects

The goal is to make typeclass-style programming explicit and compile-time checked without forcing every call site to thread evidence manually.

## Status

This repository is experimental and pre-1.0.

- Kotlin publish matrix is driven by `supportedKotlinVersions` in [`gradle.properties`](./gradle.properties). At the moment that matrix is `2.3.10` and `2.4.0-Beta1`.
- The compiler plugin is K2-only.
- Context parameters are required. The Gradle plugin enables `-Xcontext-parameters` automatically.
- Runtime, compiler-plugin, and Gradle-plugin builds target JDK 21. The IntelliJ plugin targets JVM 17 and IntelliJ IDEA 2025.3.

## Design Intent

This project is trying to be powerful without becoming mystical.

- Typeclass search is explicit and annotation-driven.
- Resolution prefers directly available context before global rule search.
- Ambiguity is an error, not something the plugin silently breaks by precedence heuristics.
- The runtime stays small; most semantics live in the compiler plugin.
- Current limits are documented instead of being treated as accidental quirks.

## Modules

| Module | Gradle project | Purpose |
| --- | --- | --- |
| [`library/`](./library/) | `:kotlin-typeclasses` | Public runtime API: `@Typeclass`, `@Instance`, `@Derive`, `summon()`, derivation metadata, and builtin proof types |
| [`compiler-plugin/`](./compiler-plugin/) | `:compiler-plugin` | K2 compiler plugin: discovery, resolution planning, FIR validation/refinement, and IR rewriting/codegen |
| [`gradle-plugin/`](./gradle-plugin/) | `:gradle-plugin` | Gradle integration for `one.wabbit.typeclass` |
| [`ij-plugin/`](./ij-plugin/) | `:ij-plugin` | IntelliJ IDEA integration for loading the compiler plugin into IDE analysis |

## Published Modules

Most consumers need the runtime library and the Gradle plugin ID.

| Module | Coordinates or ID | Role |
| --- | --- | --- |
| Runtime library | `one.wabbit:kotlin-typeclasses:<version>` | Annotations, `summon()`, derivation metadata, and proof APIs |
| Gradle plugin | plugin id `one.wabbit.typeclass` | Kotlin build integration and compiler-plugin wiring |
| Gradle plugin artifact | `one.wabbit:kotlin-typeclasses-gradle-plugin:<version>` | Published Gradle plugin implementation artifact |
| Compiler plugin | `one.wabbit:kotlin-typeclasses-plugin:<baseVersion>-kotlin-<kotlinVersion>` | Kotlin-line-specific K2 compiler plugin |
| IntelliJ plugin | `one.wabbit:kotlin-typeclasses-ij-plugin:<version>` | IDE helper plugin for external compiler-plugin loading |

## Quick Start

If you want the normal Gradle integration path, add `mavenCentral()` to `pluginManagement` as well as normal dependency repositories:

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

Then define typeclasses with `@Typeclass`, provide evidence with `@Instance`, and consume it through context parameters or `summon()`:

```kotlin
package demo

import one.wabbit.typeclass.Instance
import one.wabbit.typeclass.Typeclass
import one.wabbit.typeclass.summon

@Typeclass
interface Show<A> {
    fun show(value: A): String
}

@Instance
object IntShow : Show<Int> {
    override fun show(value: Int): String = value.toString()
}

@Instance
context(left: Show<A>, right: Show<B>)
fun <A, B> pairShow(): Show<Pair<A, B>> =
    object : Show<Pair<A, B>> {
        override fun show(value: Pair<A, B>): String =
            "(" + left.show(value.first) + ", " + right.show(value.second) + ")"
    }

context(_: Show<A>)
fun <A> render(value: A): String = summon<Show<A>>().show(value)

fun main() {
    println(render(1 to 2))
}
```

If you want to load the compiler plugin manually instead of using Gradle, add both `-Xcontext-parameters` and the compiler plugin artifact `one.wabbit:kotlin-typeclasses-plugin:<baseVersion>-kotlin-<kotlinVersion>`.

## Default Behavior

Out of the box:

- the Gradle plugin wires in the compiler plugin and enables `-Xcontext-parameters`
- typeclass resolution only applies to interfaces annotated with `@Typeclass`
- directly available contextual evidence is preferred before global rule search
- synthetic `KClass<T>` and `KSerializer<T>` evidence stay disabled unless you opt in through compiler-plugin options
- resolution tracing stays disabled unless you opt in globally or by source annotation

## What The Plugin Adds

- Implicit resolution for `context(...)` parameters whose type is annotated with `@Typeclass`
- Instance search through top-level `@Instance` declarations and associated companions
- Derived instances via `@Derive`, `@DeriveVia`, and `@DeriveEquiv`
- Builtin proof materialization for APIs such as `Same`, `Subtype`, `KnownType`, and `TypeId`
- Scoped resolution tracing through `@DebugTypeclassResolution` and the compiler trace option

## Resolution Model

The important resolution rules are:

- Only `@Typeclass` interfaces participate in implicit typeclass resolution.
- Directly available contextual evidence is considered before global rule search.
- Global rules come from top-level `@Instance` objects, functions, and immutable properties, plus associated companions.
- For a goal like `Foo<A, B>`, associated search includes the companion of `Foo`, companions of sealed supertypes of `Foo`, and companions of `A` and `B` plus their sealed supertypes.
- Derived rules created from `@Derive(...)` are part of the same search space.
- There is no global coherence check. If multiple candidates match, resolution fails as ambiguous.

See the [User Guide](./docs/user-guide.md) for the full user-facing model.

## Kotlin Compatibility And Versioning

The compiler plugin is published per Kotlin compiler line because it depends on Kotlin compiler APIs.

Compiler-plugin coordinates use the form:

- `one.wabbit:kotlin-typeclasses-plugin:<baseVersion>-kotlin-<kotlinVersion>`

For the current release train, the repository is configured to publish compiler-plugin variants for:

- `2.3.10`
- `2.4.0-Beta1`

The Gradle plugin chooses the matching compiler-plugin artifact automatically. If you wire the compiler plugin directly, the `-kotlin-<kotlinVersion>` suffix must match the Kotlin compiler you are actually using.

## Direct Compiler Usage

If you are not using Gradle, wire the compiler plugin directly:

```text
-Xcontext-parameters
-Xplugin=/path/to/kotlin-typeclasses-plugin.jar
-P plugin:one.wabbit.typeclass:builtinKClassTypeclass=disabled|enabled
-P plugin:one.wabbit.typeclass:builtinKSerializerTypeclass=disabled|enabled
-P plugin:one.wabbit.typeclass:typeclassTraceMode=inherit|disabled|failures|failures-and-alternatives|all|all-and-alternatives
```

If source code imports `one.wabbit.typeclass.*`, the runtime library still needs to be on the compilation classpath.

## IntelliJ Support

The IntelliJ plugin in this repository does not replace Kotlin analysis with a separate typeclass engine.

Its current job is narrower:

- detect imported compiler-plugin classpaths and Gradle plugin declarations
- enable IntelliJ's external K2 compiler-plugin loading path for the current trusted project
- request Gradle reimport when only the Gradle plugin declaration is visible

Important detail:

- enabling support here enables non-bundled K2 compiler plugins for the current trusted project session, not just `kotlin-typeclasses`

## Current Boundaries

These are important current boundaries rather than hidden surprises:

- there is no global coherence check across the whole program
- ambiguity is a hard error when multiple rules match
- runtime/proof builtins are selective and sometimes require runtime-materializable types
- contextual property getter reads are currently blocked by FIR API limits around property-read refinement

For the current property-read limitation, see [`compiler-plugin/ISSUE_PROPERTIES.md`](./compiler-plugin/ISSUE_PROPERTIES.md).

## Documentation Map

- [User Guide](./docs/user-guide.md): setup, authoring typeclasses, derivation, builtin proofs, and tracing
- [Architecture](./docs/architecture.md): how the runtime, compiler plugin, Gradle plugin, and IntelliJ plugin fit together
- [Development](./docs/development.md): local build, test, versioning, publishing, and release workflow notes
- [Runtime Library README](./library/README.md): public runtime API surface and artifact role
- [Compiler Plugin README](./compiler-plugin/README.md): compiler-plugin-specific usage
- [Gradle Plugin README](./gradle-plugin/README.md): Gradle-plugin-specific usage
- [IntelliJ Plugin README](./ij-plugin/README.md): IDE integration details
- [Compiler Plugin Plan](./compiler-plugin/PLAN.md): active correctness backlog and policy decisions
- [Compiler Plugin Learnings](./compiler-plugin/LEARNINGS.md): implementation notes and edge cases discovered during development

## Build And Test

Common commands from the repo root:

```bash
./gradlew build
./gradlew :kotlin-typeclasses:jvmTest
./gradlew :compiler-plugin:test
./gradlew :gradle-plugin:test
./gradlew :ij-plugin:test
```

The runtime project name is `:kotlin-typeclasses`, not `:library`.

## Contributing And Licensing

- License: [`LICENSE.md`](./LICENSE.md)
- Code of conduct: [`CODE_OF_CONDUCT.md`](./CODE_OF_CONDUCT.md)
- CLA: [`CLA.md`](./CLA.md)
- Contributor privacy notice: [`CONTRIBUTOR_PRIVACY.md`](./CONTRIBUTOR_PRIVACY.md)

## Suggested Reading Order

If you are new to the repository, this order works well:

1. [README.md](./README.md)
2. [docs/user-guide.md](./docs/user-guide.md)
3. [library/README.md](./library/README.md)
4. [docs/architecture.md](./docs/architecture.md)
5. [compiler-plugin/README.md](./compiler-plugin/README.md)
6. [compiler-plugin/PLAN.md](./compiler-plugin/PLAN.md)
7. [compiler-plugin/LEARNINGS.md](./compiler-plugin/LEARNINGS.md)
