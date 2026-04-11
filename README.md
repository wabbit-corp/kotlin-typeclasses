# kotlin-typeclasses

`kotlin-typeclasses` is a K2 compiler-plugin stack for typeclass-oriented programming in Kotlin with context parameters.

## Quick Start

This is a complete JVM application for the current documented release line.

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "typeclass-quickstart"
```

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.3.10"
    application
    id("one.wabbit.typeclass") version "0.0.1"
}

dependencies {
    implementation("one.wabbit:kotlin-typeclasses:0.0.1")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "demo.MainKt"
}
```

Then add `src/main/kotlin/demo/Main.kt`:

<!-- quickstart-source:start -->
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
<!-- quickstart-source:end -->

Run it:

```bash
./gradlew run
```

Expected output:

```text
(1, 2)
```

If you want to load the compiler plugin manually instead of using Gradle, add both `-Xcontext-parameters` and the compiler plugin artifact `one.wabbit:kotlin-typeclasses-plugin:<baseVersion>-kotlin-<kotlinVersion>`.

It combines:

- a small runtime library with annotations, `summon()`, derivation metadata, and proof APIs
- a K2 compiler plugin that resolves typeclass evidence and rewrites contextual calls
- a Gradle plugin that wires the compiler plugin into Kotlin builds
- an IntelliJ IDEA helper plugin that enables external compiler-plugin loading for projects that use this stack

## Why This Exists

Kotlin context parameters give the language a useful capability-passing syntax, but they do not by themselves provide a typeclass programming model.

`kotlin-typeclasses` adds the missing pieces:

- implicit evidence search for supported heads marked with `@Typeclass`
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
| [`compiler-plugin/`](./compiler-plugin/) | `:kotlin-typeclasses-plugin` | K2 compiler plugin: discovery, resolution planning, FIR validation/refinement, and IR rewriting/codegen |
| [`gradle-plugin/`](./gradle-plugin/) | `:kotlin-typeclasses-gradle-plugin` | Gradle integration for `one.wabbit.typeclass` |
| [`ij-plugin/`](./ij-plugin/) | `:kotlin-typeclasses-ij-plugin` | IntelliJ IDEA integration for loading the compiler plugin into IDE analysis |

## Published Modules

Most consumers need the runtime library and the Gradle plugin ID.

| Module | Coordinates or ID | Role |
| --- | --- | --- |
| Runtime library | `one.wabbit:kotlin-typeclasses:0.0.1` | Annotations, `summon()`, derivation metadata, and proof APIs |
| Gradle plugin | plugin id `one.wabbit.typeclass` | Kotlin build integration and compiler-plugin wiring |
| Gradle plugin artifact | `one.wabbit:kotlin-typeclasses-gradle-plugin:0.0.1` | Published Gradle plugin implementation artifact |
| Compiler plugin | `one.wabbit:kotlin-typeclasses-plugin:0.0.1-kotlin-2.3.10` and `one.wabbit:kotlin-typeclasses-plugin:0.0.1-kotlin-2.4.0-Beta1` | Kotlin-line-specific K2 compiler plugin |
| IntelliJ plugin | `one.wabbit:kotlin-typeclasses-ij-plugin:0.0.1` | IDE helper plugin for external compiler-plugin loading |

## Default Behavior

Out of the box:

- the Gradle plugin wires in the compiler plugin and enables `-Xcontext-parameters`
- typeclass resolution applies only to supported heads annotated with `@Typeclass`
- ordinary user-defined typeclasses should be interfaces; subclassable class heads are limited to advanced/compiler-owned surfaces
- directly available contextual evidence is preferred before global rule search
- synthetic `KClass<T>` and `KSerializer<T>` evidence stay disabled unless you opt in through compiler-plugin options
- resolution tracing stays disabled unless you opt in globally or by source annotation

## What The Plugin Adds

- Implicit resolution for `context(...)` parameters whose head is annotated with `@Typeclass`
- Instance search through top-level `@Instance` declarations and associated companions
- Derived instances via `@Derive`, `@DeriveVia`, and `@DeriveEquiv`
- Builtin proof materialization for APIs such as `Same`, `Subtype`, `KnownType`, and `TypeId`
- Scoped resolution tracing through `@DebugTypeclassResolution` and the compiler trace option

## Resolution Model

The important resolution rules are:

- only supported `@Typeclass` heads participate in implicit typeclass resolution
- ordinary application/library typeclasses should be interfaces; abstract/open class heads are an advanced path used by compiler-owned surfaces such as `Equiv` and by `@DeriveVia` only when the head is subclassable and has an accessible zero-argument constructor
- directly available contextual evidence is considered before global rule search
- global rules come from top-level `@Instance` objects, functions, and immutable properties, plus associated companions
- top-level `@Instance` declarations are restricted by file ownership: they must live with the typeclass head or one of the concrete provided classifiers in the target
- for a goal like `Foo<A, B>`, associated search includes the companion of `Foo`, companions of sealed supertypes of `Foo`, and companions of `A` and `B` plus their sealed supertypes
- derived rules created from `@Derive(...)` are part of the same search space
- there is no global coherence check; multiple matching candidates fail as ambiguous

See the [User Guide](./docs/user-guide.md) for the full user-facing model.

## Kotlin Compatibility And Versioning

The compiler plugin is published per Kotlin compiler line because it depends on Kotlin compiler APIs.

Compiler-plugin coordinates use the form:

- `one.wabbit:kotlin-typeclasses-plugin:<baseVersion>-kotlin-<kotlinVersion>`

For this release train, the repository is configured to publish compiler-plugin variants for:

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

Enabling support here enables non-bundled K2 compiler plugins for the current trusted project session, not just `kotlin-typeclasses`.

## Current Boundaries

These are important boundaries rather than hidden surprises:

- there is no global coherence check across the whole program
- ambiguity is a hard error when multiple rules match
- runtime/proof builtins are selective and sometimes require runtime-materializable types
- contextual property getter reads are currently blocked by FIR API limits around property-read refinement

For the current property-read limitation, see [`compiler-plugin/ISSUE_PROPERTIES.md`](./compiler-plugin/ISSUE_PROPERTIES.md).

## Documentation Map

- Published API docs: [wabbit-corp.github.io/kotlin-typeclasses](https://wabbit-corp.github.io/kotlin-typeclasses/)
- Published guides: [wabbit-corp.github.io/kotlin-typeclasses/docs](https://wabbit-corp.github.io/kotlin-typeclasses/docs/)
- Published user guide: [wabbit-corp.github.io/kotlin-typeclasses/docs/user-guide](https://wabbit-corp.github.io/kotlin-typeclasses/docs/user-guide/)
- [User Guide](./docs/user-guide.md): setup, authoring typeclasses, derivation, builtin proofs, and tracing
- [Typeclass Model](./docs/typeclass-model.md): what counts as a typeclass, where evidence lives, typeclass scope, and resolution precedence
- [Instance Authoring](./docs/instance-authoring.md): placement strategy, top-level ownership rules, and ambiguity avoidance
- [Derivation](./docs/derivation.md): `@Derive`, `@DeriveVia`, `@DeriveEquiv`, deriver contracts, and current boundaries
- [Proofs And Builtins](./docs/proofs-and-builtins.md): all builtin proof types plus `KClass<T>` / `KSerializer<T>` summoning
- [Troubleshooting](./docs/troubleshooting.md): common diagnostics, tracing workflow, and builtin/debugging failure patterns
- [Multi-Module Behavior](./docs/multi-module.md): visibility, exported evidence, and dependency-boundary semantics
- [API Reference](./docs/api-reference.md): generated Dokka reference commands and intended reference surfaces
- [Migration](./docs/migration.md): release-line compatibility, breaking-change policy, and upgrade checklist
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
./gradlew :kotlin-typeclasses-plugin:test
./gradlew :kotlin-typeclasses-gradle-plugin:test
./gradlew :kotlin-typeclasses-ij-plugin:test
```

The runtime project name is `:kotlin-typeclasses`, not `:library`.

## Contributing And Licensing

- License: [`LICENSE.md`](./LICENSE.md)
- Code of conduct: [`legal/code-of-conduct/v1.0.0/CODE_OF_CONDUCT.md`](./legal/code-of-conduct/v1.0.0/CODE_OF_CONDUCT.md)
- CLA: [`legal/cla/v1.0.0/CLA.md`](./legal/cla/v1.0.0/CLA.md)
- Contributor privacy notice: [`legal/contributor-privacy/v1.0.0/CONTRIBUTOR_PRIVACY.md`](./legal/contributor-privacy/v1.0.0/CONTRIBUTOR_PRIVACY.md)

## Suggested Reading Order

If you are new to the repository, this order works well:

1. [README.md](./README.md)
2. [docs/user-guide.md](./docs/user-guide.md)
3. [docs/typeclass-model.md](./docs/typeclass-model.md)
4. [docs/instance-authoring.md](./docs/instance-authoring.md)
5. [docs/derivation.md](./docs/derivation.md)
6. [docs/proofs-and-builtins.md](./docs/proofs-and-builtins.md)
7. [docs/troubleshooting.md](./docs/troubleshooting.md)
8. [docs/multi-module.md](./docs/multi-module.md)
9. [docs/api-reference.md](./docs/api-reference.md)
10. [docs/migration.md](./docs/migration.md)
11. [library/README.md](./library/README.md)
12. [docs/architecture.md](./docs/architecture.md)
13. [compiler-plugin/README.md](./compiler-plugin/README.md)
14. [compiler-plugin/PLAN.md](./compiler-plugin/PLAN.md)
15. [compiler-plugin/LEARNINGS.md](./compiler-plugin/LEARNINGS.md)
