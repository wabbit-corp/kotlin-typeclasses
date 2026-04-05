# Changelog

## Unreleased

- Added the `one.wabbit.typeclass` Gradle plugin module.
- Apply the `one.wabbit:kotlin-typeclasses-plugin` compiler plugin artifact automatically to Kotlin compilations.
- Enable `-Xcontext-parameters` automatically for compilations that use the plugin.
- Added a Gradle TestKit integration test that publishes the sibling runtime/compiler artifacts and compiles a real inference-plus-extension example through the Gradle plugin.
- Resolve the Gradle plugin itself through an included build during functional testing so the test follows the real `plugins { id("one.wabbit.typeclass") }` path.
