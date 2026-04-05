# Serializable Typealiases And `TypeId`

## Problem

`TypeId` is intended to model semantic, observable type identity rather than source spelling.

That means:

- `TypeId<Int>` and `TypeId<typealias Age = Int>` should be equal when `Age` has no behaviorally relevant differences.
- But `TypeId<typealias A = @Serializable(...) Int>` should not necessarily collapse to `TypeId<Int>` if the alias-level annotation changes compiler or runtime behavior.

## Why This Matters

The current PHASE13G behavior is acceptable for plain aliases because they do not introduce observable differences. But a typealias that carries plugin-relevant metadata, especially serialization-related metadata, can behave differently from its expansion.

If `TypeId` erases that distinction, then:

- proof search can treat behaviorally different types as identical
- builtin evidence such as serialization-related evidence can be selected incorrectly
- equality/hash semantics for `TypeId` can stop matching actual compiler behavior

## Current Risk

The current reified fallback for `TypeId<T>` goes through `typeOf<T>()` and a runtime `typeId(KType)` factory.

This is useful as an interim implementation for reified cases, but it is likely too weak for future semantics because `KType`-based identity may erase metadata that the compiler still treats as behaviorally relevant, including alias-origin information or annotation-driven distinctions.

## Desired Rule

`TypeId` should collapse distinctions that are not observable, but preserve distinctions that can affect behavior.

In practice:

- plain aliases may collapse
- aliases with behaviorally relevant annotations should not collapse
- the authoritative identity should eventually come from compiler semantic type data, not only from reflection-derived `KType`

## Likely Follow-Up

Future work should revisit the reified `TypeId<T>` path and replace or augment the `typeOf<T>()` fallback with compiler-synthesized semantic identity data that can preserve:

- alias-level behaviorally relevant annotations
- plugin-visible metadata
- any other distinctions that affect generated code, resolution, or serialization behavior
