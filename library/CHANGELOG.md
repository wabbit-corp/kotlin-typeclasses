# Changelog

## Unreleased

- Add portable enum-derivation metadata via `EnumTypeclassMetadata` / `EnumEntryMetadata`, and extend `TypeclassDeriver` with `deriveEnum(...)` for enum-specific constructive derivation.
- Add constructive product derivation metadata via `ProductTypeclassMetadata.construct(...)`, and split deriver capabilities into `ProductTypeclassDeriver` plus `TypeclassDeriver` for full product+sum derivation.
- Add `ProductTypeclassMetadata.isValueClass` so constructive derivation can preserve value-class behavior instead of treating every product as an object-shaped record.
- Add `SumCaseMetadata.isValueClass` so constructive sealed-sum decoders can distinguish transparent value cases from one-field product cases without JVM reflection.
- Make the constructive derivation contract portable across targets by treating product reconstruction as metadata instead of JVM reflection.
- Add runtime `typeId(KType)` canonicalization so compiler-synthesized reified `TypeId<T>` evidence can preserve semantic identity without relying on unstable raw `KType.toString()` output.
- Add PHASE13E/PHASE13F runtime proof surfaces for `Nullable`, `NotNullable`, `TypeId`, and richer `SameTypeConstructor` composition, and document the semantic split between `KClass`, `KnownType`, and `TypeId`.
- Add PHASE13C/PHASE13D runtime proof combinators for `Same`, `Subtype`, `NotSame`, and `StrictSubtype`, including `coerce`, `flip`, transitive composition, `Same.refl`, `Subtype.refl`, bounded `Subtype.reify`, `NotSame.fromContradiction`, `NotSame.irreflexive`, `StrictSubtype.contradicts`, `Same.bracket`, and `TypeId.compare` with stable `equals`/`hashCode`.
- Add the initial runtime module for typeclass annotations, generated marker annotations, and derivation metadata.
- Finalize the 2.3.10 derivation contract around `TypeclassDeriver`, product-field metadata, and sealed-sum metadata.
- Add generated product-field accessors and sum-case matchers to derivation metadata, plus library `summon()` based on context parameters.
- Add PHASE12 runtime proof interfaces and carriers for `Same`, `NotSame`, `Subtype`, `IsTypeclassInstance`, `SameTypeConstructor`, and `KnownType`, including the `knownType(kType)` factory used by compiler-synthesized evidence.
