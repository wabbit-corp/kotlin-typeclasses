# kotlin-typeclasses-ij-plugin

IntelliJ IDEA support for `one.wabbit:kotlin-typeclasses-plugin`.

## What It Does

This plugin does not replace Kotlin analysis inside the IDE. Instead, it bridges into the Kotlin IDE plugin's existing external compiler-plugin loading path:

- it scans imported Kotlin compiler arguments for `kotlin-typeclasses-plugin`
- it also scans Gradle build files and version catalogs for `one.wabbit.typeclass`
- if found, it temporarily enables non-bundled K2 compiler plugins for the opened project
- if only the Gradle plugin declaration is visible, it requests a Gradle import so the compiler plugin classpath is imported into Kotlin project settings
- it exposes a manual refresh action under `Tools | Refresh Typeclass IDE Support`

That gives the Kotlin IDE plugin a chance to load the external compiler plugin registrar from the compiler plugin classpath already configured by the build.

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

## Build

```bash
./gradlew :ij-plugin:buildPlugin
```

## Usage

1. Build or install the IntelliJ plugin.
2. Open a project that already applies `kotlin-typeclasses-plugin`.
   Applying `one.wabbit.typeclass` through Gradle is enough.
3. Trust the project when IntelliJ asks.
4. If needed, run `Tools | Refresh Typeclass IDE Support`.

When the plugin detects the compiler plugin classpath or Gradle plugin declaration, it enables external K2 compiler plugins for that project session. If it only sees the Gradle plugin declaration, it also requests a Gradle import and tells you if a manual reimport is still needed.

## Related Docs

- [`../README.md`](../README.md)
- [`../docs/user-guide.md`](../docs/user-guide.md)
- [`../docs/development.md`](../docs/development.md)
- [`../docs/architecture.md`](../docs/architecture.md)
- [`../gradle-plugin/README.md`](../gradle-plugin/README.md)
- [`../compiler-plugin/README.md`](../compiler-plugin/README.md)
