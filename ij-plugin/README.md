# kotlin-typeclasses-ij-plugin

IntelliJ IDEA support for `one.wabbit:kotlin-typeclasses-plugin`.

## What it does

This plugin does not replace Kotlin analysis inside the IDE. Instead, it bridges into the Kotlin IDE plugin's existing external compiler plugin loading path:

- it scans imported Kotlin compiler arguments for `kotlin-typeclasses-plugin`
- it also scans Gradle build files and version catalogs for `one.wabbit.typeclass`
- if found, it temporarily enables non-bundled K2 compiler plugins for the opened project
- if only the Gradle plugin declaration is visible, it requests a Gradle import so the compiler plugin classpath is imported into Kotlin project settings
- it exposes a manual refresh action under `Tools | Refresh Typeclass IDE Support`

That allows the Kotlin IDE plugin to load the compiler plugin registrar from the compiler plugin classpath already configured by the build.

## What it requires

- IntelliJ IDEA with the bundled Kotlin plugin
- a trusted project
- your build must already configure `kotlin-typeclasses-plugin` in Kotlin compiler arguments, or apply `one.wabbit.typeclass` in Gradle

This plugin does not currently synthesize Gradle or Maven compiler plugin configuration on its own.

## Build

```bash
cd ../kotlin-typeclasses-ij-plugin
./gradlew buildPlugin
```

## Usage

1. Build or install the IntelliJ plugin.
2. Open a project that already applies `kotlin-typeclasses-plugin`.
   Applying `one.wabbit.typeclass` through Gradle is enough.
3. Trust the project when IntelliJ asks.
4. If needed, run `Tools | Refresh Typeclass IDE Support`.

When the plugin detects the compiler plugin classpath or Gradle plugin declaration, it enables external K2 compiler plugins for that project session. If it only sees the Gradle plugin declaration, it also requests a Gradle import and tells you if a manual reimport is still needed.
