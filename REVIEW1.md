# REVIEW1 Checklist

Use this file as a living trim list. When a section is fully handled, delete the whole section instead of keeping stale prose around.

## Covered Already

- [x] FIR no longer hides `Ambiguous` or `Recursive` contexts as satisfiable.
- [x] `canDeriveGoal()` is now keyed by `(owner class, requested typeclass)` rather than only the owner class.
- [x] Illegal non-companion `@Instance` declarations are no longer indexed as usable rules.
- [x] Preserved explicit context arguments are checked against the expected contextual type before rebinding.
- [x] Non-generic sealed roots with generic subclasses are rejected during derivation instead of failing later in IR.
- [x] `SessionScopedCache` no longer self-retains the FIR session through cached values.
- [x] Allowed dependency companion instances are visible in IR as well as FIR.
- [x] Impossible model-only builtin proof candidates are filtered before planning instead of failing only at IR materialization.
- [x] Impossible `Subtype` / `StrictSubtype` candidates are filtered before planning instead of poisoning ambiguity.
- [x] Nested runtime-type builtins like `KnownType<List<T>>`, `TypeId<List<T>>`, and builtin `KSerializer<List<T>>` are now filtered with call-site reifiedness in FIR and IR.

## Scope Notes

- [x] Whole-module top-level instance discovery across dependencies is intentionally out of scope.
- [x] The intended discovery surface is:
  - top-level declarations in the same compilation unit
  - allowed companion-owner discovery
  - allowed sealed-owner discovery

## Open Checklist

### FIR Wrapper / Generation Drift

- [x] Decide whether `TypeclassFirStatusTransformerExtension` is still part of the design.
- [x] If it is dead, delete the file and any comments or docs that imply it is active.
- [x] Decide whether `TypeclassFirGenerationExtension` should exist at all.
- [x] If the generation extension remains empty, remove the empty class and keep only the registrar/helper code.
- [x] Move wrapper-related helper functions out of “generation extension” files if they are not actually generation logic.
- [x] Confirm every wrapper-related helper referenced from FIR still corresponds to real generated or rewritten behavior.
- [x] Run `./gradlew --no-daemon test` after the cleanup.
- [x] Confirm every generated-declaration key still has a live producer and a live consumer.
- [x] Trim this entire section once there are no dead FIR extension shells left.

### Session-Wide Package Scanning

- [x] Inspect Kotlin 2.3.10 FIR provider sources to confirm what `symbolNamesProvider.getPackageNames()` actually ranges over.
- [x] Confirm that `FirProviderImpl` builds package names from recorded current-module `FirFile`s rather than from dependency classpath packages.
- [x] Record that indexing policy in `LEARNINGS.md`.
- [x] Trim this section once the “scan the world” concern is downgraded to ordinary current-module package iteration.

### Rule ID Collision Risk

- [x] Inventory every place rule ids are constructed from `callableId`, rendered provided types, or other lossy signature fragments.
- [x] Identify which rule-id paths are used only for diagnostics and which ones participate in planner identity or deduplication.
- [x] Add regressions for overload families that differ only by:
  - contextual parameter structure
  - generic parameter structure
  - inherited provided heads
  - same callable id with different wrapper-visible type shapes
- [x] Confirm whether `callableId + providedType.render()` can collide in those cases today.
- [x] If collisions are possible, replace the lossy id shape with a fuller structural signature key.
- [x] Re-run `./gradlew --no-daemon test`.
- [x] Trim this section once rule identity is demonstrably collision-resistant for supported overload shapes.

## Confidence

- [x] Confidence remains high on the FIR wrapper/generation drift being real.
- [x] Confidence remains medium on the package-scan cost until it is measured.
- [x] Confidence remains medium on rule-id collision risk until it has dedicated regressions.
