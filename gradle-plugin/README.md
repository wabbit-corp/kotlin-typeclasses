# kotlin-typeclasses-gradle-plugin

Gradle plugin for `one.wabbit:kotlin-typeclasses-plugin`.

## Plugin id

```kotlin
plugins {
    kotlin("jvm") version "2.3.10"
    id("one.wabbit.typeclass") version "0.0.1"
}
```

This is the recommended way to load the typeclass compiler plugin in Gradle builds.

The Gradle plugin keeps its own base version, but resolves the compiler plugin artifact as `<baseVersion>-kotlin-<kotlinVersion>` so the applied Kotlin Gradle plugin version selects the matching compiler-plugin variant. The repository itself uses `defaultKotlinVersion` for local builds and `supportedKotlinVersions` for publish-time fanout.

## What it does

- adds the `one.wabbit:kotlin-typeclasses-plugin:0.0.1-kotlin-2.3.10` compiler plugin to Kotlin compilations
- enables `-Xcontext-parameters` automatically

You still need the runtime dependency:

```kotlin
dependencies {
    implementation("one.wabbit:kotlin-typeclasses:0.0.1")
}
```

## Build

```bash
cd ../kotlin-typeclasses-gradle-plugin
./gradlew test
```

## Local development

The functional test publishes the sibling runtime and compiler plugin to Maven Local, then resolves the Gradle plugin through an included build of `../kotlin-typeclasses-gradle-plugin`. That exercises the normal `plugins { id("one.wabbit.typeclass") }` path instead of a raw injected compiler jar.

For local composite builds, the same pattern works:

```kotlin
// settings.gradle.kts
pluginManagement {
    includeBuild("../kotlin-typeclasses-gradle-plugin")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```
