# AGENTS

This file documents the local debugging context and reproducibility data that are most useful when working on `kotlin-typeclasses-plugin`, especially for IntelliJ/K2 integration failures.

Also follow the Kotlin coding conventions documented in `/Users/wabbit/ws/datatron/kotlin-typeclasses-plugin/KOTLIN_CONVENTIONS.md`.

## Scope

These notes apply to:

- `/Users/wabbit/ws/datatron/kotlin-typeclasses-plugin`
- `/Users/wabbit/ws/datatron/kotlin-typeclasses-ij-plugin`
- downstream repro projects that consume the Gradle/compiler plugins

## IntelliJ Debugging Checklist

When the IntelliJ plugin or IDE analysis looks wrong, collect all of the following before changing code:

1. The current `idea.log`.
2. The exact IntelliJ product, version, and build number.
3. The bundled Kotlin plugin version and whether the project is using K2 mode.
4. The installed `kotlin-typeclasses-ij-plugin` zip version.
5. The `kotlin-typeclasses-plugin` and `kotlin-typeclasses-gradle-plugin` versions used by the failing project.
6. Whether the issue is editor-only, build-only, or both.
7. A minimal code snippet that reproduces the failure.
8. The exact Gradle/compiler error text if the build fails.

Do not trust editor squiggles alone. Always compare them against a real Gradle compile.

## Important Local Paths

- Compiler plugin repo: `/Users/wabbit/ws/datatron/kotlin-typeclasses-plugin`
- IntelliJ plugin repo: `/Users/wabbit/ws/datatron/kotlin-typeclasses-ij-plugin`
- Runtime library repo: `/Users/wabbit/ws/datatron/kotlin-typeclasses`
- Downstream real-world repro project: `/Users/wabbit/ws/datatron/cc-plugin-main`
- Example IntelliJ log path: `/Users/wabbit/Library/Logs/JetBrains/IntelliJIdea2025.3/idea.log`
- Extracted IntelliJ app/jar dump: `/Users/wabbit/ij-contents`

The extracted IDE contents are useful for checking:

- bundled Kotlin plugin classes
- service loader metadata
- extension point registrations
- whether a given API exists in the exact IDE build being tested

## What To Look For In `idea.log`

Search for:

- `kotlin-typeclasses`
- `Typeclass`
- `non-bundled K2 compiler plugins`
- `CompilerPluginRegistrar`
- `CommandLineProcessor`
- `ServiceConfigurationError`
- `ClassNotFoundException`
- `Gradle import`
- `External Build`
- `Kotlin`

Useful questions while reading the log:

- Did the IDE plugin detect the Gradle plugin or compiler plugin classpath?
- Did IntelliJ enable loading of non-bundled K2 compiler plugins?
- Did the Kotlin IDE plugin actually load the external compiler plugin registrar?
- Did Gradle import succeed and refresh compiler arguments?
- Is there a version mismatch between the IDE Kotlin plugin and the external compiler plugin?

## Reproduction Procedure

When an IntelliJ issue is reported, try to reproduce it in this order:

1. Reproduce in `/Users/wabbit/ws/datatron/cc-plugin-main` if possible.
2. Run `./gradlew --no-daemon compileKotlin` in the failing project.
3. Run the focused compiler integration tests in `kotlin-typeclasses-plugin`.
4. If the failure is IDE-only, inspect `idea.log` and the extracted IDE contents in `/Users/wabbit/ij-contents`.
5. If the failure is build-only, prefer fixing the compiler plugin first; IDE state may be a secondary symptom.

## Known Fragile Scenarios To Keep Covered By Tests

Add or keep regression tests for:

- same-name overloads with contextual resolution
- nested self-calls with explicit type arguments
- local inference across function boundaries
- contextual extension functions and operators
- safe-call / `let` flows that preserve outer context
- contextual lambdas, especially anonymous functions
- star-projected receivers and arguments
- explicit context arguments that are still present in raw IR argument slots
- cross-file calls, not just single-file synthetic snippets
- IntelliJ/Gradle import detection paths

Real downstream patterns are more valuable than toy examples. If a bug appears in `cc-plugin-main`, add a test that mirrors the real shape as closely as possible.

## Behavioral Expectations

- `summon` must not be special-cased. It should work only because normal contextual resolution works.
- Only interfaces annotated with `@Typeclass` participate in implicit/contextual resolution.
- Instance lookup rules must match the intended companion/sealed-supertype search rules.
- Editor support is not considered correct unless Gradle compilation and IDE analysis agree.

## Where To Record New Findings

- Put concrete implementation lessons in `/Users/wabbit/ws/datatron/kotlin-typeclasses-plugin/LEARNINGS.md`.
- Keep `/Users/wabbit/ws/datatron/kotlin-typeclasses-plugin/CHANGELOG.md` updated when behavior changes.
- If a failure depends on a specific IDE build or log signature, add that fact here to keep the debugging checklist current.
