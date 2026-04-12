# Development

This guide covers local development, testing, versioning, and publishing for the repository.

## Prerequisites

- JDK 21 for the runtime library, compiler plugin, and Gradle plugin
- JDK 17-compatible environment for the IntelliJ plugin target
- a recent Gradle-compatible shell environment with the checked-in wrapper

The repository already pins the main build settings in [`gradle.properties`](../gradle.properties) and [`settings.gradle.kts`](../settings.gradle.kts).

## Project Names

Gradle project names:

- `:kotlin-typeclasses`
- `:kotlin-typeclasses-plugin`
- `:kotlin-typeclasses-gradle-plugin`
- `:kotlin-typeclasses-ij-plugin`

The runtime module is physically under `library/`, but its Gradle project name is `:kotlin-typeclasses`.

## Common Commands

From the repository root:

```bash
./gradlew build
./gradlew projects
./gradlew :kotlin-typeclasses:jvmTest
./gradlew :kotlin-typeclasses-plugin:test
./gradlew :kotlin-typeclasses-gradle-plugin:test
./gradlew :kotlin-typeclasses-ij-plugin:test
```

Targeted examples:

```bash
./gradlew :kotlin-typeclasses-plugin:test --tests 'one.wabbit.typeclass.plugin.integration.ResolutionTest'
./gradlew :kotlin-typeclasses:jvmTest --tests 'one.wabbit.typeclass.ProofsTest'
./gradlew :kotlin-typeclasses-gradle-plugin:test --tests 'one.wabbit.typeclass.gradle.TypeclassGradlePluginVersioningTest'
```

## Testing Against Different Kotlin Versions

The repository's Kotlin matrix is driven by:

- `defaultKotlinVersion`
- `supportedKotlinVersions`

Both are defined in [`gradle.properties`](../gradle.properties).

To run the compiler-plugin module against a specific supported Kotlin version:

```bash
./gradlew -PkotlinVersion=2.3.10 :kotlin-typeclasses-plugin:test
./gradlew -PkotlinVersion=2.4.0-Beta1 :kotlin-typeclasses-plugin:test
```

The compiler plugin publishes a distinct artifact for each Kotlin version in that matrix.

## Local Publishing

For local development against another project, publish the runtime first and then the compiler plugin variant you need:

```bash
./gradlew :kotlin-typeclasses:publishToMavenLocal
./gradlew -PkotlinVersion=2.3.10 :kotlin-typeclasses-plugin:publishToMavenLocal
./gradlew :kotlin-typeclasses-gradle-plugin:publishToMavenLocal
```

The Gradle plugin functional test is a good reference for local consumer wiring:

- [`TypeclassGradlePluginFunctionalTest.kt`](../gradle-plugin/src/test/kotlin/one/wabbit/typeclass/gradle/TypeclassGradlePluginFunctionalTest.kt)

It uses:

- `pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }`
- `dependencyResolutionManagement { repositories { mavenLocal(); mavenCentral() } }`

For local downstream work against an unreleased Gradle plugin, the usual pattern is:

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

## Versioning Model

Base version:

- `projectVersion` in [`gradle.properties`](../gradle.properties)

Derived versions:

- runtime library publishes exactly `projectVersion`
- Gradle plugin publishes exactly `projectVersion`
- compiler plugin publishes `<projectVersion>-kotlin-<kotlinVersion>`
- snapshot compiler-plugin versions become `<baseWithoutSnapshot>-kotlin-<kotlinVersion>+dev-SNAPSHOT`

This logic is implemented in:

- [`compiler-plugin/build.gradle.kts`](../compiler-plugin/build.gradle.kts)
- the shared `compilerPluginArtifactVersion(...)` helper from the included `kotlin-gradle-plugin-common` build

## Release And Snapshot Workflows

GitHub workflows:

- [`release-publish.yml`](../.github/workflows/release-publish.yml)
- [`snapshot-publish.yml`](../.github/workflows/snapshot-publish.yml)

Release workflow behavior:

- triggered from tags matching `v*.*.*`
- validates that the tag matches `projectVersion`
- publishes runtime and Gradle plugin once
- publishes compiler-plugin variants across the supported Kotlin matrix

Snapshot workflow behavior:

- triggered from `master` pushes and manual dispatch
- only publishes when versions end with `+dev-SNAPSHOT`
- uses the same Kotlin matrix fanout for the compiler plugin

## Where To Look When Debugging

### User-facing API shape

- [`library/src/commonMain/kotlin/one/wabbit/typeclass/`](../library/src/commonMain/kotlin/one/wabbit/typeclass/)

### Resolution and planner behavior

- [`TypeclassPluginSharedState.kt`](../compiler-plugin/src/main/kotlin/one/wabbit/typeclass/plugin/TypeclassPluginSharedState.kt)
- [`WrapperPlanner.kt`](../compiler-plugin/src/main/kotlin/one/wabbit/typeclass/plugin/model/WrapperPlanner.kt)

### FIR validation and refinement

- [`TypeclassFirCheckersExtension.kt`](../compiler-plugin/src/main/kotlin/one/wabbit/typeclass/plugin/TypeclassFirCheckersExtension.kt)
- [`TypeclassFirFunctionCallRefinementExtension.kt`](../compiler-plugin/src/main/kotlin/one/wabbit/typeclass/plugin/TypeclassFirFunctionCallRefinementExtension.kt)

### IR rewriting and derivation

- [`TypeclassIrGenerationExtension.kt`](../compiler-plugin/src/main/kotlin/one/wabbit/typeclass/plugin/TypeclassIrGenerationExtension.kt)

### Integration test harness

- [`IntegrationTestSupport.kt`](../compiler-plugin/src/test/kotlin/one/wabbit/typeclass/plugin/integration/IntegrationTestSupport.kt)

### Current design and backlog

- [`compiler-plugin/PLAN.md`](../compiler-plugin/PLAN.md)
- [`compiler-plugin/LEARNINGS.md`](../compiler-plugin/LEARNINGS.md)

## Suggested Reading Order

If you are new to the codebase, this reading order usually works well:

1. [`README.md`](../README.md)
2. [`docs/user-guide.md`](./user-guide.md)
3. [`docs/api-reference.md`](./api-reference.md)
4. [`docs/migration.md`](./migration.md)
5. [`docs/architecture.md`](./architecture.md)
6. [`compiler-plugin/README.md`](../compiler-plugin/README.md)
7. [`compiler-plugin/PLAN.md`](../compiler-plugin/PLAN.md)
8. [`compiler-plugin/LEARNINGS.md`](../compiler-plugin/LEARNINGS.md)
