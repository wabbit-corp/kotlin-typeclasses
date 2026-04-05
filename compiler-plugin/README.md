# kotlin-typeclasses-plugin

K2 compiler plugin built against the Kotlin version selected by `-PkotlinVersion` in this repository, defaulting to `defaultKotlinVersion` from `gradle.properties`, and published for every entry in `supportedKotlinVersions`, that adds:

- implicit resolution for `context(...)` parameters whose interface is annotated with `@Typeclass`
- instance search through top-level `@Instance` objects/functions and associated companions
- `@Derive(...)` support for product types through companion-based `ProductTypeclassDeriver`s, and sealed sums through full `TypeclassDeriver`s

Prefer loading this through the Gradle plugin module:

```kotlin
plugins {
    kotlin("jvm") version "2.3.10"
    id("one.wabbit.typeclass") version "0.0.1"
}

dependencies {
    implementation("one.wabbit:kotlin-typeclasses:0.0.1")
}
```

## Build

Publish the runtime module first, then the compiler plugin:

```bash
cd ../kotlin-typeclasses
./gradlew publishToMavenLocal

cd ../kotlin-typeclasses-plugin
./gradlew publishToMavenLocal
```

Both modules target the configured repository Kotlin version and expect `-Xcontext-parameters`.

## Usage

Add the runtime dependency:

```kotlin
implementation("one.wabbit:kotlin-typeclasses:0.0.1")
```

Apply the compiler plugin jar in your Kotlin compiler configuration. A plain CLI invocation looks like this:

```bash
kotlinc \
  -Xcontext-parameters \
  -Xplugin="$HOME/.m2/repository/one/wabbit/kotlin-typeclasses-plugin/0.0.1-kotlin-2.3.10/kotlin-typeclasses-plugin-0.0.1-kotlin-2.3.10.jar" \
  ...
```

Then write typeclasses with context parameters. Use `ProductTypeclassDeriver` when a typeclass only supports product derivation, and `TypeclassDeriver` when it can also derive sealed sums:

```kotlin
import one.wabbit.typeclass.Derive
import one.wabbit.typeclass.Instance
import one.wabbit.typeclass.ProductTypeclassMetadata
import one.wabbit.typeclass.SumTypeclassMetadata
import one.wabbit.typeclass.Typeclass
import one.wabbit.typeclass.TypeclassDeriver
import one.wabbit.typeclass.get
import one.wabbit.typeclass.matches
import one.wabbit.typeclass.summon

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
                    val case = metadata.cases.single { it.matches(value) }
                    val caseShow = case.instance as Show<Any?>
                    return caseShow.show(value)
                }
            }
    }
}

@Instance
object IntShow : Show<Int> {
    override fun show(value: Int): String = value.toString()
}

@Instance
context(_: Show<A>, _: Show<B>)
fun <A, B> pairShow(): Show<Pair<A, B>> =
    object : Show<Pair<A, B>> {
        override fun show(value: Pair<A, B>): String = "(${value.first}, ${value.second})"
    }

@Derive(Show::class)
data class Box<A>(val value: A)

@Derive(Show::class)
sealed class Option<out A>
data class Some<A>(val value: A) : Option<A>()
object None : Option<Nothing>()

context(show: Show<A>)
fun <A> render(value: A): String = summon<Show<A>>().show(value)

fun main() {
    val some: Option<Int> = Some(1)
    println(render(Box(1)))
    println(render(some))
    println(render(None as Option<Int>))
}
```

## Resolution rules

Instances resolve only for `@Typeclass` interfaces.

Allowed instance locations for `Foo<A, B>`:

- top-level `@Instance` objects/functions
- `Foo`'s companion
- companions of sealed supertypes of `Foo`
- `A`'s companion and companions of sealed supertypes of `A`
- `B`'s companion and companions of sealed supertypes of `B`

There is no global coherence check. If multiple rules match the same goal, resolution fails as ambiguous.
