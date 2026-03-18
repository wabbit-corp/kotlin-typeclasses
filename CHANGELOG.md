# Changelog

## Unreleased

- Target Kotlin 2.3.10 with context-parameter inference for `@Typeclass` contexts only.
- Support object and function instances, including associated companion lookup for target types and type arguments.
- Add IR-backed `@Derive` support for product types and sealed sums through `TypeclassDeriver` companions.
- Make the compiler integration tests portable by removing hardcoded jar paths and adding compile-and-run coverage for derivation and contextual extensions.
- Added the `one.wabbit.typeclass` Gradle plugin path as the recommended way to load the compiler plugin in Gradle builds.
- Generate non-reflective product field accessors and sum-case matchers in derivation metadata, and cover library `summon()` in integration tests.
- Scope FIR contextual-function indexing per session so IntelliJ K2 analysis does not reuse wrapper-generation state from the wrong module session.
- Replace FIR file-by-package scanning with symbol-provider enumeration so IntelliJ FIR analysis no longer trips `LLFirProvider.getFirFilesByPackage`.
- Make compiler integration tests fall back to compiled JVM classes when the local runtime jar artifact is unavailable or empty.
- Resolve generated contextual wrapper statuses before FIR validation and cover generic companion factory wrappers in integration tests.
- Fix IR context-slot indexing for member contextual helpers, infer rewritten call type arguments from receivers as well as surviving value arguments, and add regressions for member `customSummon`-style helpers plus dispatch+extension operator calls.
- Document the current compiler-plugin edge cases and Kotlin IR slot-layout learnings in `LEARNINGS.md`.
- Fix IR rewriting for preserved explicit context arguments inside contextual lambdas, overloaded self-calls in anonymous functions, and generic `summon<T>()` calls whose contextuality only appears after type substitution.
- Add regressions for platform-type safe calls, cross-file overloaded wrappers, and star-projected contextual lambdas that forward associated typeclass evidence through higher-order calls.
- Fix receiver-only FIR type inference for contextual extension/operator calls, and stop synthetic implicit receiver injection from polluting unrelated lambda overload resolution such as `run { ... }`.
