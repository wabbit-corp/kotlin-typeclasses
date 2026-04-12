# kotlin-typeclasses-ij-plugin

IntelliJ IDEA support for `one.wabbit:kotlin-typeclasses-plugin`.

## Why This Module Exists

Kotlin compiler plugins need IDE-side loading before IntelliJ can analyze source with the same assumptions as Gradle. This module only bridges project configuration into IntelliJ's external K2 compiler-plugin loading path; it does not implement a separate typeclass analyzer.

## Status

This module is experimental phase-1 support for trusted IntelliJ IDEA projects. It targets the repository's current IntelliJ platform line and may change as Kotlin IDE plugin APIs change.

## What It Does

This plugin does not replace Kotlin analysis inside the IDE. Instead, it bridges into the Kotlin IDE plugin's existing external compiler-plugin loading path:

- it scans imported Kotlin compiler arguments for `kotlin-typeclasses-plugin`
- it also scans Gradle build files and version catalogs for `one.wabbit.typeclass`
- if found, it temporarily enables non-bundled K2 compiler plugins for the opened project
- if only the Gradle plugin declaration is visible, it requests a Gradle import so the compiler plugin classpath is imported into Kotlin project settings
- it exposes a manual refresh action under `Tools | Refresh Typeclass IDE Support`

That gives the Kotlin IDE plugin a chance to load the external compiler plugin registrar from the compiler plugin classpath already configured by the build.

The Gradle/build-file scan is best-effort. It targets common literal and simple string-indirection forms in build scripts, settings scripts, and version catalogs. Highly dynamic or computed Gradle logic can still require a manual refresh or Gradle import before IDE support activates.

Important detail: IntelliJ only exposes a coarse registry switch here. Enabling support for `kotlin-typeclasses` enables all non-bundled K2 compiler plugins for the current trusted project session, not only this one.

## Current Scope

This is phase-1 IDE support:

- detect typeclass plugin usage
- enable external K2 compiler plugins for the current trusted project session
- request Gradle reimport when only the Gradle plugin declaration is visible
- provide a refresh action and notifications

It does not yet add IntelliJ-native inspections, quick fixes, or a separate typeclass-specific analysis engine.

## What It Requires

- IntelliJ IDEA with the bundled Kotlin plugin
- a trusted project
- a build that already applies `one.wabbit.typeclass` or otherwise configures `kotlin-typeclasses-plugin`

This plugin does not synthesize Gradle or Maven compiler-plugin configuration by itself.

## Installation

Build the plugin ZIP from the repository root and install it through IntelliJ's local plugin installer:

```bash
./gradlew :kotlin-typeclasses-ij-plugin:buildPlugin
```

The project you open in IntelliJ must still apply `one.wabbit.typeclass` through Gradle or otherwise expose `kotlin-typeclasses-plugin` on the Kotlin compiler-plugin classpath.

## Build

```bash
./gradlew :kotlin-typeclasses-ij-plugin:buildPlugin
```

## Usage

1. Build or install the IntelliJ plugin.
2. Open a project that already applies `kotlin-typeclasses-plugin`.
   Applying `one.wabbit.typeclass` through Gradle is enough.
3. Trust the project when IntelliJ asks.
4. If needed, run `Tools | Refresh Typeclass IDE Support`.

When the plugin detects the compiler plugin classpath or Gradle plugin declaration, it enables external K2 compiler plugins for that project session. If it only sees the Gradle plugin declaration, it also requests a Gradle import and tells you if a manual reimport is still needed.

Expected success signal: after refresh or Gradle import, IntelliJ should stop reporting unresolved typeclass contextual calls that already compile through Gradle. If it does not, verify that the Gradle import includes `kotlin-typeclasses-plugin` in Kotlin compiler arguments.

## Changelog

IDE support changes are tracked with the repository release notes in [`../docs/migration.md`](../docs/migration.md).

## Support

Use [`../docs/troubleshooting.md`](../docs/troubleshooting.md) for project setup issues. Report bugs through the repository issue tracker with IntelliJ version, Kotlin plugin version, and whether Gradle builds pass. For contribution workflow and local development, see [`../docs/development.md`](../docs/development.md) and the legal docs under [`../legal/`](../legal/).
