# API Reference

The public API reference is generated with Dokka from source KDoc.

The hand-written guides explain the programming model. The generated reference is the source of truth for public symbols, signatures, nullability, constructors, properties, and opt-in annotations.

Published API docs:

- [wabbit-corp.github.io/kotlin-typeclasses](https://wabbit-corp.github.io/kotlin-typeclasses/)

## Generate Locally

From the repository root:

```bash
./gradlew :kotlin-typeclasses:dokkaGeneratePublicationHtml
./gradlew :kotlin-typeclasses-plugin:dokkaGeneratePublicationHtml
./gradlew :kotlin-typeclasses-gradle-plugin:dokkaGeneratePublicationHtml
./gradlew :kotlin-typeclasses-ij-plugin:dokkaGeneratePublicationHtml
```

The most important user-facing reference is the runtime library:

- `:kotlin-typeclasses:dokkaGeneratePublicationHtml`
- package: `one.wabbit.typeclass`
- module docs: [`library/docs/dokka-module.md`](../library/docs/dokka-module.md)
- package docs: [`library/docs/dokka-package.md`](../library/docs/dokka-package.md)

## What Counts As Public API

Primary end-user API:

- `@Typeclass`, `@Instance`, `summon()`
- `@Derive`, `@DeriveVia`, `@DeriveEquiv`
- derivation metadata such as `ProductTypeclassMetadata`, `SumTypeclassMetadata`, and `EnumTypeclassMetadata`
- deriver interfaces such as `ProductTypeclassDeriver` and `TypeclassDeriver`
- proof and type-metadata surfaces such as `Same`, `Subtype`, `KnownType`, and `TypeId`

Generated-code and compiler-owned support API:

- `Equiv`
- generated marker annotations
- declarations gated by `InternalTypeclassApi`

Those support surfaces are public for cross-module generated code and compiler integration. They are not the preferred API for ordinary application code unless the docs explicitly say so.

## Reference Discipline

Every public runtime symbol should have KDoc. If a public symbol exists only for generated code, the KDoc should say that directly and point users to the higher-level API.

The guides should link to the reference for exact signatures instead of copying large API inventories by hand.
