# Module kotlin-typeclasses runtime library

Public runtime surface for the `one.wabbit.typeclass` compiler-plugin family.

This module contains:

- source-facing annotations such as `@Typeclass`, `@Instance`, `@Derive`, `@DeriveVia`, and `@DeriveEquiv`
- helper APIs such as `summon()`
- derivation metadata and derivation interfaces used by typeclass companions
- builtin proof interfaces such as `Same`, `Subtype`, `KnownType`, and `TypeId`

The runtime is intentionally small. Most semantics live in the compiler plugin, but this module defines the public contract that user code and generated code share across module boundaries.
