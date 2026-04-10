# Proofs And Builtins

This guide documents:

- the builtin proof typeclasses in the runtime library
- when the compiler can materialize them
- the optional `KClass<T>` and `KSerializer<T>` builtin surfaces

## Overview

The runtime proof surfaces are:

| Proof type | Meaning |
| --- | --- |
| `Same<A, B>` | semantic Kotlin type equality |
| `NotSame<A, B>` | provable type inequality |
| `Subtype<Sub, Super>` | semantic subtyping |
| `StrictSubtype<Sub, Super>` | proper subtyping: subtype but not equal |
| `Nullable<T>` | `null` is a valid inhabitant of `T` |
| `NotNullable<T>` | `T` excludes `null` |
| `IsTypeclassInstance<TC>` | `TC` is headed by a typeclass constructor |
| `SameTypeConstructor<A, B>` | `A` and `B` share the same outer type constructor |
| `KnownType<T>` | exact reflective `KType` for `T` |
| `TypeId<T>` | stable semantic identity token for `T` |

All of these can be used as prerequisites for ordinary rule search, not just as standalone summoned values.

## Using Proofs In Ordinary Rule Search

Proofs are ordinary typeclass evidence. They can appear as prerequisites on `@Instance` rules just like `Show<A>` or `Monoid<A>`.

```kotlin
@Typeclass
interface TypeWitness<A> {
    fun verdict(): String
}

@Instance
context(id: TypeId<A>, known: KnownType<A>)
fun <A> reflectiveWitness(): TypeWitness<A> =
    object : TypeWitness<A> {
        override fun verdict(): String = id.canonicalName + " | " + known.kType
    }
```

That means builtin proofs are not only debugging conveniences. They are part of the ordinary resolution model.

## Equality-Like Proofs

### `Same<A, B>`

Meaning:

- `A` and `B` are semantically the same Kotlin type
- plain type aliases collapse

Typical successes:

- `Same<Int, Int>`
- `Same<Int, typealias Age = Int>`
- `Same<A, A>`

Typical failures:

- `Same<Int, String>`

Useful APIs:

- `flip()`
- `andThen(...)`
- `compose(...)`
- `toSubtype()`
- `Same.bracket(...)`

### `NotSame<A, B>`

Meaning:

- the compiler can prove that `A` and `B` are distinct types

Typical successes:

- `NotSame<Int, String>`
- `NotSame<UserId, Int>` for distinct value classes and their underlying types

Typical failures:

- `NotSame<Int, Int>`
- `NotSame<Int, Age>` when `typealias Age = Int`
- unconstrained generic pairs like `NotSame<A, B>`

Useful APIs:

- `flip()`
- `contradicts(...)`
- `fromContradiction(...)`

### `SameTypeConstructor<A, B>`

Meaning:

- `A` and `B` have the same outer constructor

Typical success:

- `SameTypeConstructor<List<Int>, List<String>>`

Typical failure:

- `SameTypeConstructor<List<Int>, Set<Int>>`

This is about the outer constructor only, not full type equality.

## Subtyping Proofs

### `Subtype<Sub, Super>`

Meaning:

- any `Sub` can be used where `Super` is expected

The compiler can materialize this for ordinary subtyping, including:

- class inheritance
- bounded type parameters
- nullability widening such as `Any <: Any?`
- variance cases such as covariant and contravariant containers
- some star-projection cases

Useful APIs:

- `coerce(...)`
- `andThen(...)`
- `compose(...)`
- `Subtype.refl()`
- `Subtype.reify()`

### `StrictSubtype<Sub, Super>`

Meaning:

- `Sub` is a subtype of `Super`
- `Sub` and `Super` are not equal

Typical successes:

- `StrictSubtype<Dog, Animal>`
- `StrictSubtype<Puppy, Dog>`

Typical failures:

- equal types
- alias-equal types
- unrelated types

Useful APIs:

- all `Subtype`-style composition
- `toSubtype()`
- `toNotSame()`
- `contradicts(...)`

## Nullability Proofs

### `Nullable<T>`

Meaning:

- the compiler can prove `null` is a valid inhabitant of `T`

Typical successes:

- `Nullable<String?>`
- `Nullable<typealias MaybeName = String?>`

Typical failures:

- `Nullable<String>`
- unconstrained `Nullable<T>`

Useful APIs:

- `nullValue()`
- `andThen(Same)`
- `andThen(Subtype)`
- `compose(Same)`
- `contradicts(...)`

### `NotNullable<T>`

Meaning:

- the compiler can prove `T` excludes `null`

Typical successes:

- `NotNullable<String>`
- `NotNullable<List<String?>>`

Typical failures:

- `NotNullable<String?>`
- unconstrained `NotNullable<T>`

Useful APIs:

- `andThen(Same)`
- `compose(Same)`
- `compose(Subtype)`
- `contradicts(...)`

## Meta Proofs

### `IsTypeclassInstance<TC>`

Meaning:

- `TC` is an application of a typeclass constructor

Typical success:

- `IsTypeclassInstance<Show<Int>>`

Typical failure:

- `IsTypeclassInstance<List<Int>>`

Important detail:

- this proof also recognizes optional builtin surfaces like `KClass<Int>` and `KSerializer<User>` only when those builtin modes are enabled

This proof is useful when you want to write generic rules that only apply to typeclass-shaped arguments.

## Runtime Type Proofs

### `KnownType<T>`

Meaning:

- exact reflective `KType` for a fully known type

Use it when you need:

- exact type reflection including arguments and nullability
- a prerequisite for rules that depend on precise type information

Typical success:

- `KnownType<List<String?>>`

Typical failure:

- `KnownType<T>` for an unfixed generic `T`

Important distinction from `KClass<T>`:

- `KClass<T>` erases type arguments and nullability
- `KnownType<T>` preserves them

### `TypeId<T>`

Meaning:

- stable semantic identity token for `T`

Use it when you need:

- equality and hashing on semantic type identity
- durable keys in maps and sets
- a compact identity surface instead of full `KType`

Typical properties:

- aliases collapse when semantically equal
- type arguments matter
- nullability matters
- canonical name is part of the public contract

Typical failure:

- `TypeId<T>` for an unfixed generic `T`

`TypeId<T>` is the best choice when you want semantic type identity as a key. `KnownType<T>` is the best choice when you want exact reflective structure.

## `KnownType` Versus `TypeId` Versus `KClass`

| Surface | Preserves type arguments | Preserves nullability | Intended use |
| --- | --- | --- | --- |
| `KClass<T>` | no | no | runtime classifier identity |
| `KnownType<T>` | yes | yes | exact reflection |
| `TypeId<T>` | yes | yes | stable semantic identity and hashing |

In practical terms:

- `KClass<List<String>>` and `KClass<List<Int>>` both erase to `List::class`
- `KnownType<List<String>>` and `KnownType<List<Int>>` stay distinct
- `TypeId<List<String>>` and `TypeId<List<Int>>` stay distinct and are safe map keys

## Optional Builtin `KClass<T>`

`KClass<T>` can participate as a builtin typeclass only when:

- `builtinKClassTypeclass=enabled`

By default it is disabled.

### What it gives you

When enabled, the compiler can materialize:

- contextual `KClass<T>` evidence
- `summon<KClass<T>>()`
- `IsTypeclassInstance<KClass<T>>`

### What it requires

The requested type must be:

- non-nullable
- runtime-materializable

Typical success:

- `summon<KClass<Int>>()` in reified or concrete contexts

Typical failures:

- flag disabled
- `summon<KClass<T>>()` for a non-reified unfixed generic `T`
- `summon<KClass<String?>>()`

### Common summoning patterns

```kotlin
import kotlin.reflect.KClass
import one.wabbit.typeclass.summon

context(kClass: KClass<T>)
fun <T : Any> contextualClass(): KClass<T> = kClass

inline fun <reified T : Any> reifiedClass(): KClass<T> = summon<KClass<T>>()
```

Important boundary:

- builtin `KClass<T>` only exists for non-nullable runtime-available types
- reified helpers work because `T` becomes runtime-materializable
- plain generic `fun <T : Any> ... summon<KClass<T>>()` does not

### Precedence

Explicit local `KClass<T>` context still wins over the synthetic builtin.

If you need precise type arguments or nullability, use `KnownType<T>` or `TypeId<T>` instead of `KClass<T>`.

## Optional Builtin `KSerializer<T>`

`KSerializer<T>` can participate as a builtin typeclass only when:

- `builtinKSerializerTypeclass=enabled`

By default it is disabled.

### What it gives you

When enabled, the compiler can materialize:

- contextual `KSerializer<T>` evidence
- `summon<KSerializer<T>>()`
- `IsTypeclassInstance<KSerializer<T>>`

### What it requires

The requested type must be:

- runtime-materializable
- provably serializable to the plugin at typeclass-resolution time
- not star-projected at the requested goal shape

You still need the normal serialization ecosystem:

- `kotlinx.serialization` runtime on the classpath
- the serialization compiler plugin where required

The builtin does not invent serializers for non-serializable types. It uses a conservative admissibility check during typeclass resolution and lowers successful resolutions to `kotlinx.serialization.serializer<T>()` later.

### Behavior notes

The builtin is intentionally narrower than full `serializer<T>()` parity.

Today it only proves builtin `KSerializer<T>` goals when the target is already admissible from the plugin's static model, namely:

- generated serializers for `@Serializable` classes
- nested serializers for container types when the element serializers exist
- recursively serializable type arguments for those shapes

That means this builtin proof/search surface does not promise that every type accepted by `serializer<T>()` will also be admissible as builtin `KSerializer<T>` evidence.

### Common summoning patterns

```kotlin
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import one.wabbit.typeclass.summon

@Serializable
data class User(val name: String)

context(serializer: KSerializer<T>)
fun <T> contextualSerialName(): String = serializer.descriptor.serialName

fun directSerialName(): String =
    summon<KSerializer<User>>().descriptor.serialName
```

This works for:

- concrete serializable types like `User`
- nested serializable goals like `Box<List<Int?>>`

Typical failures:

- flag disabled
- non-serializable target type
- star-projected serializer goals like `KSerializer<List<*>>`
- arbitrary generic `KSerializer<T>` goals, including inside `inline reified` helpers, when `T` is not already concrete and provably serializable during typeclass resolution

Two details matter in practice:

- class-level `@Serializable(with = ...)` affects the standalone builtin `KSerializer<MyType>`
- property-level `@Serializable(with = ...)` affects the enclosing container serializer, not the standalone builtin `KSerializer<PropertyType>`

This builtin is a conservative proof/search surface, not a full reimplementation of the official `serializer<T>()` admissibility model.

### Precedence

Explicit local `KSerializer<T>` context still wins over the synthetic builtin.

## Enabling `KClass` And `KSerializer`

Raw compiler form:

```text
-P plugin:one.wabbit.typeclass:builtinKClassTypeclass=enabled
-P plugin:one.wabbit.typeclass:builtinKSerializerTypeclass=enabled
```

In Gradle builds, the current plugin does not expose a dedicated typed DSL for these options. Forward the same raw compiler-plugin options through Kotlin compiler arguments when you want these builtins enabled.

## Example

```kotlin
import kotlin.reflect.KClass
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import one.wabbit.typeclass.KnownType
import one.wabbit.typeclass.Same
import one.wabbit.typeclass.Subtype
import one.wabbit.typeclass.TypeId
import one.wabbit.typeclass.summon

@Serializable
data class User(val name: String)

open class Animal
class Dog : Animal()

fun main() {
    val same = summon<Same<Int, Int>>()
    val subtype = summon<Subtype<Dog, Animal>>()
    val known = summon<KnownType<List<String?>>>()
    val typeId = summon<TypeId<List<String?>>>()
    val kClass = summon<KClass<Int>>()
    val serializer = summon<KSerializer<User>>()

    println(same != null)
    println(subtype.coerce(Dog()) is Animal)
    println("String?" in known.kType.toString())
    println("List" in typeId.canonicalName)
    println(kClass == Int::class)
    println(serializer.descriptor.serialName)
}
```

This example assumes:

- `builtinKClassTypeclass=enabled`
- `builtinKSerializerTypeclass=enabled`
- the serialization compiler/runtime setup required by `kotlinx.serialization`

## Best References For Exact Current Behavior

- [`BuiltinProofApiSurfaceTest.kt`](../compiler-plugin/src/test/kotlin/one/wabbit/typeclass/plugin/integration/proofs/BuiltinProofApiSurfaceTest.kt)
- [`EqualityProofTest.kt`](../compiler-plugin/src/test/kotlin/one/wabbit/typeclass/plugin/integration/proofs/EqualityProofTest.kt)
- [`SubtypeProofTest.kt`](../compiler-plugin/src/test/kotlin/one/wabbit/typeclass/plugin/integration/proofs/SubtypeProofTest.kt)
- [`NullabilityProofTest.kt`](../compiler-plugin/src/test/kotlin/one/wabbit/typeclass/plugin/integration/proofs/NullabilityProofTest.kt)
- [`RuntimeTypeProofTest.kt`](../compiler-plugin/src/test/kotlin/one/wabbit/typeclass/plugin/integration/proofs/RuntimeTypeProofTest.kt)
- [`TypeclassMetaProofTest.kt`](../compiler-plugin/src/test/kotlin/one/wabbit/typeclass/plugin/integration/proofs/TypeclassMetaProofTest.kt)
- [`KClassBuiltinTest.kt`](../compiler-plugin/src/test/kotlin/one/wabbit/typeclass/plugin/integration/KClassBuiltinTest.kt)
- [`KSerializerBuiltinTest.kt`](../compiler-plugin/src/test/kotlin/one/wabbit/typeclass/plugin/integration/KSerializerBuiltinTest.kt)

## Related Docs

- [Typeclass Model](./typeclass-model.md)
- [Derivation](./derivation.md)
- [User Guide](./user-guide.md)
- [Troubleshooting](./troubleshooting.md)
