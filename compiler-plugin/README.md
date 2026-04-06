# kotlin-typeclasses-plugin

`kotlin-typeclasses-plugin` is the K2 compiler plugin that powers the `one.wabbit.typeclass` programming model.

Most projects should apply `one.wabbit.typeclass` through the companion Gradle plugin, but this module is the actual compiler-side implementation and the right entry point for:

- direct compiler integration
- build-tool adapters outside Gradle
- Kotlin-version-specific plugin debugging
- Dokka/API documentation for compiler-side behavior

## Artifact

The compiler plugin is published as a Kotlin-line-specific artifact:

- `one.wabbit:kotlin-typeclasses-plugin:<baseVersion>-kotlin-<kotlinVersion>`

The `-kotlin-<kotlinVersion>` suffix is intentional. Compiler-plugin binaries are coupled to the Kotlin compiler APIs they were built against.

The current release train publishes compiler-plugin variants for:

- `2.3.10`
- `2.4.0-Beta1`

If you use Gradle, the companion plugin resolves the matching variant automatically.

## What The Compiler Plugin Adds

The compiler plugin is responsible for:

- implicit resolution for `context(...)` parameters whose interface is annotated with `@Typeclass`
- instance search through top-level `@Instance` declarations and associated companions
- built-in proof materialization such as `Same`, `Subtype`, `KnownType`, and `TypeId`
- derivation through `@Derive`, `@DeriveVia`, and `@DeriveEquiv`
- FIR diagnostics and call-shape refinement
- IR rewriting, generated metadata, and generated evidence publication

## Current Scope

Important current boundaries:

- only interfaces marked with `@Typeclass` participate in typeclass search
- directly available contextual evidence is preferred before global rule search
- ambiguity is an error; there is no hidden global coherence policy
- `@DeriveVia` and `@DeriveEquiv` are intentionally conservative and currently focus on monomorphic target classes
- contextual property getter reads are currently limited by the public FIR plugin API's lack of a property-read refinement hook

For the property-read limitation, see [`ISSUE_PROPERTIES.md`](./ISSUE_PROPERTIES.md).

## Compiler Options

The plugin accepts these options:

- `builtinKClassTypeclass=disabled|enabled`
- `builtinKSerializerTypeclass=disabled|enabled`
- `typeclassTraceMode=inherit|disabled|failures|failures-and-alternatives|all|all-and-alternatives`

Raw CLI form:

```text
-P plugin:one.wabbit.typeclass:builtinKClassTypeclass=disabled|enabled
-P plugin:one.wabbit.typeclass:builtinKSerializerTypeclass=disabled|enabled
-P plugin:one.wabbit.typeclass:typeclassTraceMode=inherit|disabled|failures|failures-and-alternatives|all|all-and-alternatives
```

These options control optional builtins and tracing. Source annotations from `one.wabbit:kotlin-typeclasses` then refine behavior inside the compilation, for example through `@Typeclass`, `@Instance`, `@Derive`, and `@DebugTypeclassResolution`.

## Direct Usage

If you are wiring the compiler plugin directly:

```text
-Xcontext-parameters
-Xplugin=/path/to/kotlin-typeclasses-plugin.jar
-P plugin:one.wabbit.typeclass:builtinKClassTypeclass=enabled
-P plugin:one.wabbit.typeclass:typeclassTraceMode=failures-and-alternatives
```

If source code imports `one.wabbit.typeclass.*`, the runtime library still needs to be present on the compilation classpath:

- `one.wabbit:kotlin-typeclasses:<version>`

## Preferred Gradle Usage

Most consumers should use the Gradle plugin instead:

```kotlin
plugins {
    kotlin("jvm") version "2.3.10"
    id("one.wabbit.typeclass") version "<version>"
}

dependencies {
    implementation("one.wabbit:kotlin-typeclasses:<version>")
}
```

That is the normal consumer path because it:

- resolves the Kotlin-matched compiler-plugin artifact automatically
- adds `-Xcontext-parameters`
- avoids forcing users to manage compiler-plugin jars directly

## Resolution Model

Instances resolve only for `@Typeclass` interfaces.

Allowed instance locations for `Foo<A, B>`:

- top-level `@Instance` objects, parameterless functions, and immutable properties
- `Foo`'s companion
- companions of sealed supertypes of `Foo`
- `A`'s companion and companions of sealed supertypes of `A`
- `B`'s companion and companions of sealed supertypes of `B`

Derived rules created through `@Derive`, `@DeriveVia`, and `@DeriveEquiv` participate in the same search space.

There is no global coherence check. If multiple candidates match the same goal, resolution fails as ambiguous.

## Compiler Pipeline

At a high level, the implementation is:

1. `TypeclassCommandLineProcessor` parses compiler-plugin options.
2. `TypeclassCompilerPluginRegistrar` registers the FIR and IR extensions.
3. `TypeclassPluginSharedState` builds session-scoped discovery indexes and rule lookup state.
4. `TypeclassFirCheckersExtension` validates declarations and reports source-facing diagnostics.
5. `TypeclassFirFunctionCallRefinementExtension` hides satisfiable typeclass context parameters from source call shapes.
6. `TypeclassIrGenerationExtension` rewrites calls, materializes builtins, and emits generated derivation metadata/evidence.

The core design choice is shared planning. FIR and IR both rely on the same resolution-model machinery so frontend masking and backend rewriting stay aligned.

## Worked Example

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
```

## Build

Useful commands from the repo root:

```bash
./gradlew :compiler-plugin:test
./gradlew -PkotlinVersion=2.3.10 :compiler-plugin:test
./gradlew -PkotlinVersion=2.3.10 :compiler-plugin:publishToMavenLocal
```

If you are doing local downstream testing, publish the runtime first and then the compiler plugin variant:

```bash
./gradlew :kotlin-typeclasses:publishToMavenLocal
./gradlew -PkotlinVersion=2.3.10 :compiler-plugin:publishToMavenLocal
```

## When To Use This Module Directly

Use this artifact directly when:

- integrating with a non-Gradle build pipeline
- debugging compiler-plugin behavior
- testing Kotlin-version-specific compiler-plugin variants
- reading the compiler-side Dokka surface

If you are using Gradle, prefer [`../gradle-plugin/README.md`](../gradle-plugin/README.md).

## Related Docs

- [`../README.md`](../README.md)
- [`../docs/user-guide.md`](../docs/user-guide.md)
- [`../docs/development.md`](../docs/development.md)
- [`../docs/architecture.md`](../docs/architecture.md)
- [`../library/README.md`](../library/README.md)
- [`../gradle-plugin/README.md`](../gradle-plugin/README.md)
- [`PLAN.md`](./PLAN.md)
- [`LEARNINGS.md`](./LEARNINGS.md)
- [`ISSUE_PROPERTIES.md`](./ISSUE_PROPERTIES.md)
- [`ISSUE_SERIALIZABLE_TYPEALIASES.md`](./ISSUE_SERIALIZABLE_TYPEALIASES.md)
