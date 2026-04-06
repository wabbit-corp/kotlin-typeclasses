# kotlin-typeclasses-gradle-plugin

`kotlin-typeclasses-gradle-plugin` is the typed Gradle integration for the `one.wabbit.typeclass` compiler plugin.

It applies the compiler plugin to Kotlin compilations, enables `-Xcontext-parameters`, and resolves the Kotlin-matched compiler-plugin artifact automatically.

## Plugin Coordinates

- plugin id: `one.wabbit.typeclass`
- artifact: `one.wabbit:kotlin-typeclasses-gradle-plugin:<version>`

The runtime library remains a normal dependency:

- `one.wabbit:kotlin-typeclasses:<version>`

## Installation

Assuming Maven Central publication:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
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
./gradlew -PkotlinVersion=2.3.10 :compiler-plugin:publishToMavenLocal
```

That matches what the functional test does and exercises the normal `plugins { id("one.wabbit.typeclass") }` path rather than a hand-injected compiler jar.

## Build

Useful commands from the repo root:

```bash
./gradlew :gradle-plugin:test
./gradlew :gradle-plugin:publishToMavenLocal
```

## Scope

This module is intentionally narrow:

- it is a Gradle bridge, not a second typeclass engine
- it does not replace the runtime dependency; users still need `one.wabbit:kotlin-typeclasses`
- it does not add custom DSL for resolution semantics; the typeclass model stays in source annotations and compiler-plugin behavior

If you need direct compiler-plugin details or raw CLI option forms, see [`../compiler-plugin/README.md`](../compiler-plugin/README.md).

## Related Docs

- [`../README.md`](../README.md)
- [`../docs/user-guide.md`](../docs/user-guide.md)
- [`../docs/development.md`](../docs/development.md)
- [`../docs/architecture.md`](../docs/architecture.md)
- [`../library/README.md`](../library/README.md)
- [`../compiler-plugin/README.md`](../compiler-plugin/README.md)
