# kotlin-typeclasses-gradle-plugin

`kotlin-typeclasses-gradle-plugin` is the typed Gradle integration for the `one.wabbit.typeclass` compiler plugin.

It applies the compiler plugin to Kotlin compilations, enables `-Xcontext-parameters`, and resolves the Kotlin-matched compiler-plugin artifact automatically.

## Why This Module Exists

Compiler-plugin coordinates depend on the Kotlin compiler version. This module keeps that Kotlin-line-specific wiring out of application builds and gives users one stable Gradle plugin ID.

## Status

This module is experimental and follows the repository's pre-1.0 release policy. It supports the Kotlin compiler lines listed in [`../gradle.properties`](../gradle.properties); new Kotlin compiler lines require a matching compiler-plugin artifact before consumer builds can upgrade.

## Plugin Coordinates

- plugin id: `one.wabbit.typeclass`
- artifact: `one.wabbit:kotlin-typeclasses-gradle-plugin:0.1.0`

The runtime library remains a normal dependency:

- `one.wabbit:kotlin-typeclasses:0.1.0`

## Quick Start

For a JVM project, use:

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
```

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.3.10"
    application
    id("one.wabbit.typeclass") version "0.1.0"
}

dependencies {
    implementation("one.wabbit:kotlin-typeclasses:0.1.0")
}

kotlin {
    jvmToolchain(21)
}
```

For a complete source file that is compiled and run by the documentation consistency tests, see the root [Quick Start](../README.md#quick-start).

## Installation Notes

The plugin should be applied through `plugins { id("one.wabbit.typeclass") version "0.1.0" }`. The runtime library is still a normal dependency because source code imports `one.wabbit.typeclass.*`.

## What The Gradle Plugin Does

The Gradle plugin:

- adds the matching `one.wabbit:kotlin-typeclasses-plugin:<baseVersion>-kotlin-<kotlinVersion>` artifact to Kotlin compilations
- enables `-Xcontext-parameters`
- lets the applied Kotlin Gradle plugin version choose the compiler-plugin variant indirectly

This is the recommended consumer path because it keeps Kotlin-version-sensitive compiler-plugin wiring out of application build scripts.

## Kotlin Version Negotiation

The compiler plugin is resolved as:

- `one.wabbit:kotlin-typeclasses-plugin:<baseVersion>-kotlin-<kotlinVersion>`

`<kotlinVersion>` comes from the Kotlin Gradle plugin applied in the consumer build.

The repository currently publishes compiler-plugin variants for:

- `2.3.10`
- `2.4.0-Beta1`

If a build uses a Kotlin version without a published compiler-plugin variant, resolution fails fast instead of silently guessing compatibility.

## Effective Defaults

When the Gradle plugin is applied:

- `-Xcontext-parameters` is always added
- typeclass resolution is enabled for `@Typeclass` interfaces because the compiler plugin is present
- optional builtins such as synthetic `KClass<T>` and `KSerializer<T>` evidence remain off unless direct compiler-plugin options are added separately
- tracing remains off unless enabled through compiler-plugin options or source annotations

## Local Development

The Gradle plugin functional test is the best reference for intended consumer wiring:

- [`TypeclassGradlePluginFunctionalTest.kt`](./src/test/kotlin/one/wabbit/typeclass/gradle/TypeclassGradlePluginFunctionalTest.kt)

For local composite builds, use:

```kotlin
pluginManagement {
    includeBuild("../kotlin-typeclasses")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

Then publish the runtime and compiler plugin to Maven Local so the consumer build can resolve the matched artifacts:

```bash
./gradlew :kotlin-typeclasses:publishToMavenLocal
./gradlew -PkotlinVersion=2.3.10 :kotlin-typeclasses-plugin:publishToMavenLocal
```

That matches what the functional test does and exercises the normal `plugins { id("one.wabbit.typeclass") }` path rather than a hand-injected compiler jar.

## Build

Useful commands from the repo root:

```bash
./gradlew :kotlin-typeclasses-gradle-plugin:test
./gradlew :kotlin-typeclasses-gradle-plugin:publishToMavenLocal
```

## Changelog

Gradle wiring changes and Kotlin-version migration notes live in [`../docs/migration.md`](../docs/migration.md).

## Support

For setup failures, check [`../docs/troubleshooting.md`](../docs/troubleshooting.md). Report bugs through the repository issue tracker with the Kotlin Gradle plugin version and dependency-resolution output. For local plugin development, use [`../docs/development.md`](../docs/development.md) and the functional tests under [`./src/test/`](./src/test/).

## Scope

This module is narrow:

- it is a Gradle bridge, not a second typeclass engine
- it does not replace the runtime dependency; users still need `one.wabbit:kotlin-typeclasses`
- it does not add custom DSL for resolution semantics; the typeclass model stays in source annotations and compiler-plugin behavior

If you need direct compiler-plugin details or raw CLI option forms, see [`../compiler-plugin/README.md`](../compiler-plugin/README.md).
