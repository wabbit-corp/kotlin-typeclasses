# User Guide

This guide is a tutorial that starts from a working project and walks through one complete example, so you can see what `kotlin-typeclasses` adds on top of ordinary Kotlin context parameters.

If you do not already have a project, start with the pinned Quick Start in [`../README.md`](../README.md). Once that builds, come back here and replace `src/main/kotlin/demo/Main.kt` with the file below.

## What You Will Build

By the end of this guide you will have one runnable file that:

- defines a typeclass `Show<A>`
- publishes both concrete and generic `@Instance` rules
- uses companion-based associated lookup for `Box<A>`
- calls `summon<Show<A>>()` and gets evidence synthesized by the compiler plugin

## Complete Example

```kotlin
package demo

import one.wabbit.typeclass.Instance
import one.wabbit.typeclass.Typeclass
import one.wabbit.typeclass.summon

@Typeclass
interface Show<A> {
    fun show(value: A): String
}

@Instance
object IntShow : Show<Int> {
    override fun show(value: Int): String = value.toString()
}

@Instance
context(left: Show<A>, right: Show<B>)
fun <A, B> pairShow(): Show<Pair<A, B>> =
    object : Show<Pair<A, B>> {
        override fun show(value: Pair<A, B>): String =
            "(" + left.show(value.first) + ", " + right.show(value.second) + ")"
    }

data class Box<A>(val value: A) {
    companion object {
        @Instance
        context(show: Show<A>)
        fun <A> boxShow(): Show<Box<A>> =
            object : Show<Box<A>> {
                override fun show(value: Box<A>): String =
                    "Box(" + show.show(value.value) + ")"
            }
    }
}

context(_: Show<A>)
fun <A> render(value: A): String = summon<Show<A>>().show(value)

fun main() {
    println(render(1))
    println(render(1 to 2))
    println(render(Box(1 to 2)))
}
```

Run it:

```bash
./gradlew run
```

Expected output:

```text
1
(1, 2)
Box((1, 2))
```

## Step 1: Mark The Typeclass Head

The `@Typeclass` annotation is what makes `Show<A>` participate in typeclass resolution.

```kotlin
@Typeclass
interface Show<A> {
    fun show(value: A): String
}
```

That annotation matters because plain Kotlin context parameters are only explicit capability passing. The plugin adds a typeclass model on top: it knows which heads participate in search, where evidence can be published, and how prerequisites should be solved recursively.

For the full semantics of what counts as a typeclass, see [Typeclass Model](./typeclass-model.md).

## Step 2: Publish Evidence With `@Instance`

The tutorial uses two kinds of instance declarations:

- a concrete canonical instance as a top-level object
- a generic rule as a parameterless function whose context parameters are prerequisites

```kotlin
@Instance
object IntShow : Show<Int> {
    override fun show(value: Int): String = value.toString()
}

@Instance
context(left: Show<A>, right: Show<B>)
fun <A, B> pairShow(): Show<Pair<A, B>> =
    object : Show<Pair<A, B>> {
        override fun show(value: Pair<A, B>): String =
            "(" + left.show(value.first) + ", " + right.show(value.second) + ")"
    }
```

Operationally, the compiler reads `pairShow()` as:

- if you can solve `Show<A>`
- and you can solve `Show<B>`
- then you can build `Show<Pair<A, B>>`

That is the core mental model for generic `@Instance` functions.

Top-level instances are constrained; they must live in a legal owner file rather than an arbitrary orphan `Instances.kt`. When you start spreading instances across files or modules, use [Instance Authoring](./instance-authoring.md) for the placement rules.

## Step 3: Let Companions Contribute Associated Rules

`Box<A>` publishes its own `Show<Box<A>>` rule from its companion:

```kotlin
data class Box<A>(val value: A) {
    companion object {
        @Instance
        context(show: Show<A>)
        fun <A> boxShow(): Show<Box<A>> =
            object : Show<Box<A>> {
                override fun show(value: Box<A>): String =
                    "Box(" + show.show(value.value) + ")"
            }
    }
}
```

This works because associated lookup is part of the programming model. For a goal like `Show<Box<Pair<Int, Int>>>`, the resolver is allowed to inspect `Box`'s companion when searching for evidence.

That makes companion placement the normal home for rules that are primarily about one target type.

## Step 4: Consume Evidence With `summon()`

The consumer in this tutorial is deliberately small:

```kotlin
context(_: Show<A>)
fun <A> render(value: A): String = summon<Show<A>>().show(value)
```

`summon()` itself is just a context helper:

```kotlin
context(value: T)
fun <T> summon(): T = value
```

The plugin is what makes `summon<Show<A>>()` behave like a typeclass request instead of a plain lexical context lookup. If the needed evidence is not already present directly, the compiler is allowed to search for matching `@Instance` rules and solve their prerequisites.

You could also write the consumer with an explicit named context parameter:

```kotlin
context(show: Show<A>)
fun <A> renderExplicit(value: A): String = show.show(value)
```

Both styles are normal. `summon()` is usually nicer when the evidence is only needed briefly inside the function body.

## Step 5: Read The Example Like The Resolver

The three calls in `main()` show the recursive rule model:

- `render(1)` resolves directly to `IntShow`
- `render(1 to 2)` resolves to `pairShow()`, which in turn requires `Show<Int>` twice, both satisfied by `IntShow`
- `render(Box(1 to 2))` resolves to `Box.boxShow()`, which requires `Show<Pair<Int, Int>>`; that prerequisite resolves through `pairShow()`, which then requires `IntShow` twice

For the full resolution order, ambiguity rules, and associated-scope model, see [Typeclass Model](./typeclass-model.md).

## What This Tutorial Did Not Cover

This guide stops at manual instance authoring on purpose. The next step depends on what you are trying to do:

- automatic derivation with `@Derive`, `@DeriveVia`, or `@DeriveEquiv`: [Derivation](./derivation.md)
- builtin proof surfaces such as `Same`, `Subtype`, `KnownType`, or `TypeId`: [Proofs And Builtins](./proofs-and-builtins.md)
- failed or ambiguous lookups, including the tracing default where bare `@DebugTypeclassResolution` means `FAILURES` rather than `INHERIT`: [Troubleshooting](./troubleshooting.md)
- publishing instances across module boundaries: [Multi-Module Behavior](./multi-module.md)
