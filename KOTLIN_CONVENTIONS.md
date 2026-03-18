# Kotlin Conventions (repo-wide)

This document summarizes conventions for Kotlin code. Update as patterns shift.

## Language and API modeling
- Use `kotlinx.coroutines` for async and concurrency.
- Use `kotlinx.serialization` for serialization.
- Use functional style and immutability where practical; prefer `val` and pure functions.
- Favor composition and delegation over inheritance; prefer interfaces and data classes.
- Use sealed interfaces/classes to model algebraic data types; cases are `data class` or `data object`.
- Prefer `sealed interface` when the base has no shared state; use `sealed class` when the base carries state or behavior.
- Use `@JvmInline value class` wrappers for strong types (IDs, counts, units) instead of raw primitives.

## Opt-in and experimental APIs
- Internal or unstable APIs are gated with `@RequiresOptIn` and `Internal*Api` annotations.
- Call sites use `@OptIn` for `ExperimentalSerializationApi`, `ExperimentalContracts`, `ExperimentalStdlibApi`, etc.
- Keep opt-in scope as small as practical (function or class level when possible).

## Serialization
- Prefer `kotlinx.serialization` with `@Serializable` models and custom `KSerializer` implementations when needed.
- Use `Json` for JSON handling and `@SerialName` for field mapping.

## HTTP and networking
- Prefer Ktor `HttpClient` for HTTP.
- Default to the CIO engine and install `ContentNegotiation` with JSON.
- Configure timeouts and check response status; accept `HttpClient` via constructor where possible.

## Coroutines
- Expose `suspend` APIs in libraries; bridge to blocking contexts via `runBlocking` in CLI/test code.

## Error handling
- Prefer:
  + Sealed result types (e.g. `sealed interface Result { data class Success(...); data class Failure(...); data class Failure2(...) }`)
  + Sealed exceptions for unrecoverable (or truly exceptional) errors.
  + Custom exceptions with context data for error cases that need rich info.
