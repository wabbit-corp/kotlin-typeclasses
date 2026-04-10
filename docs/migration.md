# Migration

This project is pre-1.0. Breaking changes can still happen, especially in compiler-plugin behavior and derivation contracts.

The compatibility contract is intentionally explicit:

- the runtime library uses the base project version, for example `one.wabbit:kotlin-typeclasses:0.0.1`
- the compiler plugin is published per Kotlin compiler line, for example `one.wabbit:kotlin-typeclasses-plugin:0.0.1-kotlin-2.3.10`
- the Gradle plugin chooses the matching compiler-plugin variant from the applied Kotlin Gradle plugin version
- supported Kotlin compiler lines are listed in [`gradle.properties`](../gradle.properties)

## Current Line

These docs describe the `0.0.1` release line and the currently supported Kotlin matrix:

- `2.3.10`
- `2.4.0-Beta1`

If you use the Gradle plugin, keep these versions aligned:

```kotlin
plugins {
    kotlin("jvm") version "2.3.10"
    id("one.wabbit.typeclass") version "0.0.1"
}

dependencies {
    implementation("one.wabbit:kotlin-typeclasses:0.0.1")
}
```

If you wire the compiler plugin manually, the `-kotlin-<kotlinVersion>` suffix must match the compiler you are actually running.

## Upgrade Checklist

When upgrading:

1. Update the runtime dependency and Gradle plugin version together.
2. Confirm your Kotlin Gradle plugin version is in the supported matrix.
3. If you use direct compiler-plugin wiring, update the Kotlin-suffixed compiler-plugin artifact.
4. Run the compiler-plugin tests or at least compile modules that define `@Instance`, `@Derive`, `@DeriveVia`, and `@DeriveEquiv` declarations.
5. Check diagnostics for stricter validation around instance ownership, derivation shape, builtin materialization, and ambiguity.

## Breaking-Change Policy

Expected pre-1.0 breaking-change areas:

- stricter rejection of unsound `@Instance` declarations
- derivation validation becoming more precise
- builtin proof admissibility becoming more conservative when previous behavior was unsound
- generated metadata format changes before a stable cross-version binary contract is declared
- Kotlin compiler-version support changing with the published matrix

Changes should be documented in the module changelogs and, once a released version exists, in versioned migration notes here.

## Changelogs

The repository currently keeps per-module changelogs:

- [`library/CHANGELOG.md`](../library/CHANGELOG.md)
- [`compiler-plugin/CHANGELOG.md`](../compiler-plugin/CHANGELOG.md)
- [`gradle-plugin/CHANGELOG.md`](../gradle-plugin/CHANGELOG.md)
- [`ij-plugin/CHANGELOG.md`](../ij-plugin/CHANGELOG.md)

Before cutting a release, move relevant `Unreleased` entries into a versioned section and call out breaking changes with mechanical upgrade steps.

## Related Docs

- [User Guide](./user-guide.md)
- [Development](./development.md)
- [API Reference](./api-reference.md)
- [Troubleshooting](./troubleshooting.md)
