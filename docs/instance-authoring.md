# Instance Authoring

This guide is about writing `@Instance` declarations that resolve predictably and stay maintainable across files and modules.

It is also the canonical reference for where top-level `@Instance` declarations are allowed to live.

## The Goal

Good instance placement should make these things true:

- the intended rule is easy to find from the requested goal
- unrelated code does not accidentally pick up surprising candidates
- downstream modules see the instances you meant to publish
- ambiguity is rare and obvious when it happens

## Supported `@Instance` Shapes

Supported declaration shapes are:

- objects
- parameterless functions whose context parameters are prerequisites
- immutable properties

Rule-of-thumb:

- objects and immutable properties are best for concrete canonical instances
- functions are best for generic compositional rules

## Prefer The Most Natural Owner

A good default is:

- put type-specific instances in the target type's companion
- put canonical head-owned rules in the typeclass companion
- use top-level instances when neither companion is the right home or not under your control

Examples:

- `Show<Box<A>>` usually belongs in `Box`'s companion
- broad rules closely tied to the typeclass itself can live in `Show`'s companion
- `Show<Int>` may be top-level when you do not own `Int`

This works well with associated lookup because the resolver already searches the typeclass head and target-type companions.

## Top-Level Instances Are Not Arbitrary Orphans

Top-level `@Instance` declarations are restricted.

They must live in the same file as:

- the typeclass head, or
- one of the concrete provided classifiers in the target type

Examples:

- `Show<AlphaId>` may live in the file that declares `Show` or `AlphaId`
- `Show<Box<AlphaId>>` may live in the file that declares `Show`, `Box`, or `AlphaId`
- `Rel<Foo, Boo<Baz>>` may live in the file that declares `Rel`, `Foo`, `Boo`, or `Baz`

What is not allowed:

- placing those instances in an unrelated `Instances.kt` file that owns none of those classifiers
- assuming a nearby supertype declaration makes a different typeclass head legal

This restriction exists to keep top-level instances explainable and prevent arbitrary orphan-instance sprawl.

## Generic Rule Shape

Generic `@Instance` functions should use the standard rule-shaped form:

- no ordinary value parameters
- context parameters are prerequisites
- the result is one provided typeclass value

Example:

```kotlin
@Instance
context(left: Show<A>, right: Show<B>)
fun <A, B> pairShow(): Show<Pair<A, B>> =
    object : Show<Pair<A, B>> {
        override fun show(value: Pair<A, B>): String =
            "(" + left.show(value.first) + ", " + right.show(value.second) + ")"
    }
```

If a declaration needs ordinary runtime inputs, it is usually not a typeclass rule and should probably be modeled as an ordinary helper instead.

## Avoid Overlap

Resolution gets hard to reason about when multiple rules can produce the same target.

Avoid:

- two generic rules that both produce the same head/target pair
- manual and derived instances for the same exported head/target pair
- multiple `@DeriveVia` paths that produce the same user-visible target unless you really want ambiguity

Prefer:

- one obvious canonical instance per reachable scope
- narrower companion placement over broad top-level placement
- explicit local context when you need a one-off override

## Coordinate Manual And Derived Evidence

`@Derive` publishes generated evidence into the same search space as manual `@Instance` declarations.

In practice:

- a manual root instance and a derived root instance can conflict
- exporting both from a library is usually a mistake
- leaf-only derivation does not automatically imply root derivation, so be explicit about what you want published

If you want custom behavior, either:

- keep the manual instance and remove the competing derivation, or
- let derivation own that head/target pair entirely

## Think About Module Boundaries

If downstream modules should use an instance, make it `public`.

Remember:

- `internal` dependency instances do not leak downstream
- `private` companion instances do not leak either
- public companion instances are usually the safest cross-module publishing shape

For cross-module behavior, see [Multi-Module Behavior](./multi-module.md).

## Use Local Context For Intentional Overrides

The clean way to override a global or synthetic rule for one call path is local context, not publishing another broad global instance.

Example:

```kotlin
context(custom: Show<Int>)
fun renderWithCustomInt(): String = summon<Show<Int>>().show(1)
```

Direct local context is considered before global search, so this is the narrowest and most predictable override mechanism.

## A Practical Placement Recipe

When adding a new instance:

1. ask whether the instance is primarily about the target type or the typeclass head
2. prefer the corresponding companion if you own it
3. if it must be top-level, place it in a legal owner file
4. check whether a derived instance already publishes the same target
5. think about downstream visibility before making it `public`
