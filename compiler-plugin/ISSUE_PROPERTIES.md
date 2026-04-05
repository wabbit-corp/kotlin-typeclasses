# Contextual Property Getter Resolution

## Scope

This note tracks the blocked property-getter cases in `SurfaceTest`:

- `resolvesContextualPropertyGetter`
- `resolvesContextualExtensionPropertyGetter`

Those tests are currently ignored in the main branch with:

`Blocked: Kotlin 2.3.10 FIR plugin API has no property-access refinement hook for contextual getter resolution`

This file now includes both the original API investigation and the later wrapper-property prototype findings.

## Current Bottom Line

- Kotlin `2.3.10` does not expose a FIR plugin hook that can refine a plain property read the way `FirFunctionCallRefinementExtension` refines function calls.
- I found one plausible plugin-side workaround: hide the original contextual property, generate a no-context wrapper property for source resolution, then rewrite wrapper getter calls back to the original getter in IR.
- That workaround can be made to pass the ignored tests in a same-module/source-only sandbox.
- That workaround is not safe for published-library or cross-module use yet. The current binary shape exports a synthetic wrapper getter stub and downstream runtime fails with `UnsatisfiedLinkError`.
- Checking newer Kotlin lines did not reveal a new upstream property-read hook. The public FIR extension surface is still function-oriented in `2.3.20` and current `master`.
- The plugin also does not build as-is on `2.3.20` or current `master` because FIR APIs moved after `2.3.10`.

## What I Verified on Kotlin 2.3.10

### 1. The getter declarations themselves compile

The contextual property declarations and getter bodies are accepted.

This shape compiles:

```kotlin
context(show: Show<Int>)
val intLabel: String
    get() = show.show(1)
```

Likewise for the extension-property form:

```kotlin
context(show: Show<Int>)
val Int.rendered: String
    get() = show.show(this)
```

### 2. The failure is at the read site

Compiling the test snippets through the integration harness fails on the property read:

```text
error: no context argument for 'show: Show<Int>' found.
    println(intLabel)
            ^^^^^^^^
```

and:

```text
error: no context argument for 'show: Show<Int>' found.
    println(1.rendered)
              ^^^^^^^^
```

So FIR understands the property/getter declaration well enough to type-check the getter body, but when the property is read it does not give the plugin a property-access refinement callback analogous to function-call refinement.

### 3. A function-only wrapper does not help

The cheap workaround does not work:

```kotlin
fun intLabel(): String = "x"

fun main() {
    println(intLabel)
}
```

That still fails with:

```text
error: function invocation 'intLabel()' expected.
    println(intLabel)
            ^^^^^^^^
```

So "just generate a function wrapper" does not unblock property syntax.

## Upstream FIR/API Findings

### 1. Kotlin 2.3.10 exposes function-call refinement, not property-read refinement

From the local compiler sources and API surface:

- `FirExpressionResolutionExtension` only receives `FirFunctionCall`
- `FirFunctionCallRefinementExtension` only intercepts `FirNamedFunctionSymbol` for `FirFunctionCall`
- `FirAssignExpressionAltererExtension` only handles assignment writes

I did not find a public FIR extension point in `2.3.10` that lets a plugin intercept or refine a plain property read (`FirPropertyAccessExpression` / `FirQualifiedAccessExpression`) the way function calls can be refined.

### 2. Newer public sources still do not show a property-read hook

I checked the official Kotlin sources for:

- `v2.3.10`
- `v2.3.20`
- current `master` at `7f1b44056c4197dd33a881a73cc110db244a534a` on `2026-03-18`

The relevant extension files are still function-oriented:

- `FirFunctionCallRefinementExtension`
- `FirExpressionResolutionExtension`
- `FirAssignExpressionAltererExtension`

I also checked post-`2.3.20` history for those extension files and found no changes at all. I do not see a public `FirPropertyAccess...` refinement/interception extension on current `master`.

Inference from the public sources: JetBrains has not yet landed the specific FIR plugin hook needed here.

### 3. JetBrains is fixing contextual properties generally, but not this plugin hook

I did find public Kotlin issues around contextual properties themselves, for example:

- `KT-58165`: overridden contextual property backend failure
- `KT-77205`: IDE/debugger crash on contextual property access

That suggests JetBrains is actively working on contextual-property correctness, but it is separate from exposing a property-access refinement API to compiler plugins.

## Existing Plugin Structure on Main

In the current main branch:

- `TypeclassFirGenerationExtension` only handles function wrappers.
- `TypeclassFirStatusTransformerExtension` is function-oriented and built around `FirSimpleFunction`.
- The IR rewrite path is already call-oriented and can rewrite wrapper-like `IrCall`s back to original contextual callables.

That matters because property reads lower to getter calls in IR. If source resolution can be redirected to a wrapper getter, the existing IR rewrite machinery is structurally close to what would be needed to recover the original getter and synthesize typeclass evidence.

## The Only Plausible Plugin-Side Unblock I Found

## Wrapper-property strategy

The workable idea is:

1. Keep the original contextual property as the semantic source of truth.
2. Hide that original property from ordinary FIR resolution.
3. Generate a second property with the same source-facing name, but with typeclass context parameters removed.
4. Let source property reads resolve to the generated wrapper property.
5. In IR, rewrite wrapper getter calls back to the original getter and synthesize typeclass evidence the same way contextual function calls are already rewritten.

In other words, instead of waiting for a property-access refinement hook from Kotlin, fake source resolution by making the property access resolve to a generated wrapper declaration that FIR can already handle.

## What Actually Worked in the Sandbox

I implemented that strategy in a disposable sandbox clone and got both ignored property-getter tests green, plus a full `./gradlew test` pass in that sandbox.

The important implementation details were not what I first guessed:

### 1. `InvisibleFake` does not work cleanly for contextual properties

Trying to hide the original property with `InvisibleFake` breaks property metadata / backend handling.

The working shape was:

- hide the original contextual property as `private` in FIR
- generate a wrapper property/getter that is `public`
- mark the wrapper `isExternal = true`

### 2. FIR2IR lowers generated wrapper getters as default accessors

The wrapper getter does not come through IR as a nice plugin-origin function. In practice, the getter is a default property accessor, so IR wrapper detection has to look at the corresponding property origin, not just the getter origin.

### 3. IR must restore the original property visibility before JVM codegen

The sandbox needed an IR-side repair step that copied the wrapper visibility back onto the matched original property/getter before JVM codegen. Without that, codegen/backend validation was unhappy with the hidden original shape.

### 4. Top-level contextual properties needed explicit indexing

The shared FIR/plugin state had to grow dedicated indexing for top-level contextual properties. Function-oriented indexing alone was not enough.

## What This Unblocked

In the sandbox, the two ignored tests were unignored and passed:

- `resolvesContextualPropertyGetter`
- `resolvesContextualExtensionPropertyGetter`

So the wrapper-property approach is not just theoretical. It can be made to work for same-module source compilation on Kotlin `2.3.10`.

## Why I Do Not Consider the Sandbox Shape Merge-Safe Yet

### 1. Cross-module / published-library use is broken

The current wrapper-property implementation is only source-module safe.

In a producer/consumer repro:

- the producer module compiled
- the consumer module compiled
- runtime then failed with `UnsatisfiedLinkError`

The bad shape was:

- the real contextual getter lived in the normal file class
- the generated wrapper getter was exported separately in `__GENERATED__CALLABLES__Kt`
- downstream code bound to the synthetic no-context wrapper metadata
- runtime then tried to call the synthetic wrapper getter stub directly

Worse, the downstream consumer could compile even without the plugin, because the wrapper metadata looked like an ordinary public property API.

This is the most important reason not to merge the wrapper approach as-is.

### 2. Property references are still riskier than direct reads

Direct reads like:

- `intLabel`
- `1.rendered`

go through getter calls that IR can see and rewrite.

Property references are less certain:

- `::intLabel`
- `Int::rendered`

Those may bypass the simple getter-call rewrite path or hit the wrapper body/stub shape more directly.

### 3. IntelliJ support is plausible, not proven

The wrapper is FIR-first, so same-module IDE behavior is plausible if IntelliJ loads the compiler plugin correctly.

What I did verify locally:

- IntelliJ on this machine can load the plugin in principle.
- `idea.log` showed prior execution of plugin code paths.

What I did not fully prove:

- same-module editor correctness for the wrapper properties
- downstream module correctness against a compiled library that exports wrapper properties

So IDE support is still a separate validation item.

## Upstream-Version Compatibility Findings

I also checked whether the plugin still builds on newer Kotlin lines:

- released `2.3.20`
- current Kotlin `master`, which is already on the `2.4` line; I tested with `2.4.0-dev-5318`

### 1. The runtime side is not the blocker

The included `kotlin-typeclasses` runtime build got through JVM compilation on both newer lines.

### 2. The compiler plugin itself does not build as-is

Both upgrade attempts failed at the plugin's `:compileKotlin` task before tests ran.

The failures are source/API compatibility failures in FIR-facing code, not repository-resolution failures.

### 3. The breakage is concentrated in old FIR function APIs

The first failures are all around `FirSimpleFunction` and related old signatures:

- checker code
- status transformer code
- shared-state helpers
- function-call refinement code

Representative breakage patterns:

- `FirSimpleFunction` is no longer available as used here
- checker override signatures now point at `FirNamedFunction`
- status-transformer override signatures changed
- some older property-style accesses now expect different APIs

This is separate from the property-read-hook problem. It means that even if upstream had added the hook, this plugin would still need a general FIR API port before it could build against newer Kotlin versions.

### 4. Current `master` adds only one extra visible signal

On current `master`, the build also warns that:

- `-Xcontext-parameters` is redundant for language version `2.4`

That is not the main blocker. The real blocker is still FIR API churn.

## Things I Ruled Out

- Waiting for `FirExpressionResolutionExtension`: it only sees `FirFunctionCall`.
- Reusing `FirFunctionCallRefinementExtension` directly: it only refines function calls, not property reads.
- Reusing `FirAssignExpressionAltererExtension`: it only helps with writes/assignments.
- Exposing a wrapper function instead of a wrapper property: source still requires `intLabel()`, so it does not unblock property syntax.
- Assuming `2.3.20` or current `master` already solved this upstream: I do not see a public property-read FIR hook there either.

## Practical Conclusion

There are really two separate problems here:

### Problem 1: Kotlin `2.3.10` lacks the property-read hook we actually want

That is why the ignored tests are blocked in the first place.

### Problem 2: The only viable workaround found so far is not binary-safe

The wrapper-property approach can unblock same-module source compilation, but its current ABI shape breaks downstream/published-library use.

So the realistic options are:

1. Keep the tests ignored on main and wait for upstream Kotlin to expose the needed property-read hook.
2. Merge a same-module-only workaround and accept broken binary behavior, which I do not recommend.
3. Continue the wrapper-property work until it emits a binary-safe bridge shape and survives downstream module boundaries.
4. Separately port the plugin's FIR integration off the old `2.3.10` APIs so it can even build against newer Kotlin versions.

At this point, the only credible plugin-side route I have actually seen work is the wrapper-property strategy, but it still needs a binary-safe design before it is safe to ship.
