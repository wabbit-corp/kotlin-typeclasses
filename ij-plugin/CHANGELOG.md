# Changelog

## Unreleased

- Added the initial `kotlin-typeclasses-ij-plugin` IntelliJ plugin module.
- Added project startup and manual refresh support that enable external K2 compiler plugins when `kotlin-typeclasses-plugin` is detected in imported Kotlin compiler arguments.
- Added tests for compiler plugin classpath detection.
- Detect `one.wabbit.typeclass` in Gradle build files and version catalogs so Gradle-loaded projects enable IDE support earlier.
- Request a Gradle import when only the Gradle plugin declaration is present, and restart analysis after enabling external compiler plugins.
- Declare Kotlin K2 compatibility in `plugin.xml` so IntelliJ no longer rejects the plugin in K2 mode.
