# Derivation

This guide covers the three derivation surfaces in `kotlin-typeclasses`:

- `@Derive`
- `@DeriveVia`
- `@DeriveEquiv`

It also explains the runtime deriver interfaces and the boundaries of the derivation model.

## `@Derive`

`@Derive(Typeclass::class)` asks the compiler to synthesize evidence for the annotated type.

```kotlin
@Derive(Show::class)
data class Box<A>(val value: A)
```

The annotated typeclass companion must implement one of the runtime derivation interfaces:

- `ProductTypeclassDeriver` for product-only derivation
- `TypeclassDeriver` for product, sealed-sum, and enum derivation

### Product derivation

Product derivation uses [`ProductTypeclassMetadata`](../library/src/commonMain/kotlin/one/wabbit/typeclass/Derivation.kt) and [`ProductFieldMetadata`](../library/src/commonMain/kotlin/one/wabbit/typeclass/Derivation.kt).

The companion receives:

- the type name
- ordered field metadata
- accessors for reading fields
- resolved or recursively-linked instance slots for each field
- a constructor bridge for reconstructing values

### Sum derivation

Sealed-sum derivation uses [`SumTypeclassMetadata`](../library/src/commonMain/kotlin/one/wabbit/typeclass/Derivation.kt) and [`SumCaseMetadata`](../library/src/commonMain/kotlin/one/wabbit/typeclass/Derivation.kt).

The companion receives:

- the root type name
- derivable cases
- case matchers
- resolved or recursively-linked instance slots for each case

### Enum derivation

Enum derivation uses [`EnumTypeclassMetadata`](../library/src/commonMain/kotlin/one/wabbit/typeclass/Derivation.kt).

Typeclasses that want enum derivation must override `deriveEnum(...)`.

## Deriver Contracts

Here is a complete `Show` deriver for products, sealed sums, and enums; the documentation consistency tests compile and run this example.

<!-- derive-example:start -->
```kotlin
package demo

import one.wabbit.typeclass.Derive
import one.wabbit.typeclass.EnumTypeclassMetadata
import one.wabbit.typeclass.Instance
import one.wabbit.typeclass.ProductTypeclassMetadata
import one.wabbit.typeclass.SumTypeclassMetadata
import one.wabbit.typeclass.Typeclass
import one.wabbit.typeclass.TypeclassDeriver
import one.wabbit.typeclass.get
import one.wabbit.typeclass.matches

@Typeclass
interface Show<A> {
    fun show(value: A): String

    companion object : TypeclassDeriver {
        override fun deriveProduct(metadata: ProductTypeclassMetadata): Any =
            object : Show<Any?> {
                override fun show(value: Any?): String {
                    require(value != null)
                    val renderedFields =
                        metadata.fields.joinToString(", ") { field ->
                            val fieldValue = field.get(value)
                            val fieldShow = field.instance as Show<Any?>
                            "${field.name}=${fieldShow.show(fieldValue)}"
                        }
                    val typeName = metadata.typeName.substringAfterLast('.')
                    return "$typeName($renderedFields)"
                }
            }

        override fun deriveSum(metadata: SumTypeclassMetadata): Any =
            object : Show<Any?> {
                override fun show(value: Any?): String {
                    require(value != null)
                    val matchingCase = metadata.cases.single { candidate -> candidate.matches(value) }
                    val caseShow = matchingCase.instance as Show<Any?>
                    return caseShow.show(value)
                }
            }

        override fun deriveEnum(metadata: EnumTypeclassMetadata): Any =
            object : Show<Any?> {
                override fun show(value: Any?): String =
                    metadata.entryOf(value).name
            }
    }
}

@Instance
object IntShow : Show<Int> {
    override fun show(value: Int): String = value.toString()
}

@Derive(Show::class)
data class Box<A>(val value: A)

@Derive(Show::class)
sealed class Option<out A>

data class Some<A>(val value: A) : Option<A>()

object None : Option<Nothing>()

@Derive(Show::class)
enum class Tone {
    Warm,
    Cool,
}

context(show: Show<A>)
fun <A> render(value: A): String = show.show(value)

fun main() {
    val some: Option<Int> = Some(1)
    val none: Option<Int> = None
    println(render(Box(1)))
    println(render(some))
    println(render(none))
    println(render(Tone.Warm))
}
```
<!-- derive-example:end -->

Expected output:

```text
Box(value=1)
Some(value=1)
None()
Warm
```

The compiler plugin is responsible for:

- validating that the companion satisfies the required contract
- synthesizing the metadata object
- generating the derived evidence used in the current compilation
- publishing successful derivations for downstream compilations through compiler metadata, which later compiler runs reconstruct back into rules

## Root And Leaf Semantics

For sealed hierarchies:

- root `@Derive` on a sealed hierarchy derives the root instance and synthesizes the leaf/case evidence needed to build it
- leaf-only `@Derive` derives only that leaf as a standalone type
- leaf-only derivation does not imply root derivation
- mixed root + leaf derivation is legal and behaves like the union of those requests rather than becoming ambiguous

This matters because "sealed derivation" is not just a local implementation detail; it affects what evidence downstream code can summon.

Across module boundaries, that exported behavior is metadata-driven. The producer does not primarily publish an ordinary generated declaration that downstream code imports directly; it publishes successful derivation metadata, and the downstream compiler reconstructs the derivation rule from that metadata.

## `@DeriveVia`

`@DeriveVia(typeclass = ..., path = ...)` derives a typeclass by transporting it through an equivalence path.

The mental model is:

- solve an equivalence between the annotated type and the requested via-path target
- obtain the requested typeclass instance at that via target
- transport the typeclass methods back across the equivalence

Example shape:

```kotlin
@JvmInline
value class Foo(val value: Int)

@Instance
object FooMonoid : Monoid<Foo> { /* ... */ }

@JvmInline
@DeriveVia(Monoid::class, Foo::class)
value class UserId(val value: Int)
```

### Path semantics

The `path` entries are interpreted as:

- via-type waypoints
- pinned `Iso` singleton classes for exact user-authored conversion segments

Concrete intuition:

- given `@DeriveVia(Show::class, UserIdIso::class, String::class)`, the `UserIdIso` segment uses that exact user-authored conversion, while the `String::class` segment asks the compiler to find or synthesize the surrounding `Equiv` step automatically

Current important rules:

- empty paths are rejected
- a waypoint like `Foo::class` means "solve an `Equiv<Current, Foo>` segment"
- a pinned `Iso` object means "use exactly this reversible conversion for one segment"
- only the `Iso` object itself is pinned; any surrounding `Equiv` segments are still solver-driven
- if a pinned `Iso` can attach in more than one way, derivation fails as ambiguous rather than guessing an orientation
- the implementation focuses on monomorphic target classes
- local equivalence steps the compiler synthesizes while completing one `@DeriveVia` request are not automatically exported as global evidence

In other words, `@DeriveVia` is a focused transport mechanism, not a general-purpose search for "some equivalent type somewhere".

### Transport position

Today `@DeriveVia` transports only the last type parameter of the requested typeclass.

In practice:

- single-parameter typeclasses like `Show<A>` and `Monoid<A>` fit naturally
- multi-parameter typeclasses currently participate only when the transported slot is the final type parameter
- more general parameter-selection rules would need an explicit future design

### Pinned `Iso` example

```kotlin
@JvmInline
value class UserId(val value: String?)

object UserIdIso : Iso<UserId, String?> {
    override fun to(value: UserId): String? = value.value
    override fun from(value: String?): UserId = UserId(value)
}

@JvmInline
@DeriveVia(Show::class, UserIdIso::class)
value class RenderedUserId(val value: String?)
```

This requests:

1. use `UserIdIso` for one exact transport segment
2. solve the remaining path around it, if needed, with compiler-owned `Equiv`
3. obtain `Show<String?>`
4. transport that `Show` instance back to `RenderedUserId`

## `@DeriveEquiv`

`@DeriveEquiv(Other::class)` requests exported compiler-synthesized equivalence evidence between the annotated class and `Other`.

Example:

```kotlin
@DeriveEquiv(WireUserId::class)
data class DomainUserId(val value: Int)
```

This differs from `@DeriveVia` in an important way:

- `@DeriveEquiv` exports regular resolution-visible equivalence evidence
- the exported evidence is bidirectional: both `Equiv<Annotated, Other>` and `Equiv<Other, Annotated>` are summonable
- local equivalence links synthesized only while satisfying one `@DeriveVia` request do not automatically become globally summonable

Across module boundaries, that export is still metadata-driven. Downstream compilations reconstruct the `Equiv` rule from dependency metadata rather than relying on an ordinary handwritten-looking declaration being present in the dependency API surface.

So if you want downstream code to be able to summon `Equiv<A, B>` or `Equiv<B, A>` directly, `@DeriveEquiv` is the explicit mechanism for that pair.

Direct user code that names or summons `Equiv<..., ...>` must also opt into `InternalTypeclassApi`, because `Equiv` is a compiler-owned low-level surface even when it is legitimately exported.

### Structural whitelist

`@DeriveEquiv` uses a narrow structural whitelist. The compiler is trying to prove transparent reversible structure, not "close enough" semantic similarity.

Current successful shapes are conservative, including things like:

- transparent value classes
- transparent data-class products
- transparent sealed sums built from transparent cases

Important rejection cases include:

- validated or normalizing constructors
- extra mutable or hidden backing state
- ambiguous product permutations or sum-case matches
- generic target classes outside the current monomorphic boundary

## `Iso` Versus `Equiv`

These two concepts are related but distinct.

### `Iso<A, B>`

- explicit user-authored reversible conversion value
- not a typeclass
- can be pinned in `@DeriveVia` paths

### `Equiv<A, B>`

- compiler-owned canonical reversible equivalence evidence
- is a typeclass
- may be exported through `@DeriveEquiv`
- should not be user-authored as manual `@Instance` evidence or direct subclasses

Practical rule:

- use `Iso` when you want to author a concrete reversible conversion
- use `@DeriveEquiv` or `@DeriveVia` when you want the compiler to synthesize and reason about equivalence evidence

## Recursive Derivation

Recursive derivation graphs use `RecursiveTypeclassInstanceCell` internally to tie recursive knots safely.

This matters for typeclass companions because field and case metadata may expose instance slots that are resolved through recursive cells rather than through an already-final object.

From the public API perspective, that is why metadata properties like `field.instance` and `case.instance` are accessors rather than plain stored final values.

## GADT-Like Derivation Policy

Advanced derivation can be constrained with:

- `@GadtDerivationPolicy`
- `GadtDerivationMode.SURFACE_TRUSTED`
- `GadtDerivationMode.CONSERVATIVE_ONLY`

This is a specialized override for GADT-like derivation fragments. Most users can ignore it unless they are working on advanced sealed/generic derivation boundaries.

## Current Boundaries

Derivation is conservative.

Important boundaries:

- ambiguity still applies: generated evidence shares the same resolution space as manual rules
- `@DeriveVia` and `@DeriveEquiv` focus on monomorphic classes
- transparent structural equivalence is preferred over aggressive semantic guessing
- derivation is not a substitute for a global coherence policy
- cross-module derivation reuse depends on successful metadata export and downstream rule reconstruction, not on shipping every generated declaration as ordinary API
- contextual property getter limitations in FIR can still affect source-level ergonomics around some contextual surfaces

## Where To Read The Actual Behavior

These are the best references when the precise contract matters:

- [`DerivationSurfaceTest.kt`](../compiler-plugin/src/test/kotlin/one/wabbit/typeclass/plugin/integration/derivation/DerivationSurfaceTest.kt)
- [`DeriveViaSpec.kt`](../compiler-plugin/src/test/kotlin/one/wabbit/typeclass/plugin/integration/derivation/DeriveViaSpec.kt)
- [`GADTDerivationTest.kt`](../compiler-plugin/src/test/kotlin/one/wabbit/typeclass/plugin/integration/derivation/GADTDerivationTest.kt)
- [`PLAN.md`](../compiler-plugin/PLAN.md)
