Static review notes, trimmed to the issues that still look materially open after the recent fixes.

Already covered and intentionally removed from this copy:
- FIR no longer hides `Ambiguous` / `Recursive` contexts as satisfiable.
- `canDeriveGoal()` is now typeclass-specific instead of keyed only by owner class.
- Illegal non-companion `@Instance` declarations are no longer indexed as real rules.
- Explicit preserved context arguments are checked against the expected contextual type.
- Non-generic sealed roots with generic subclasses are rejected during derivation.
- `SessionScopedCache` no longer self-retains the FIR session.
- Allowed associated-owner dependency companions are now visible in IR as well as FIR.
- Some model-only builtin proof candidates are filtered before planning instead of failing only at IR materialization.
- Impossible `Subtype` / `StrictSubtype` candidates are now filtered before planning instead of participating in ambiguity and failing only later.

Policy note:
- Whole-module top-level instance discovery across dependencies is not a goal. The intended surface is top-level declarations in the same compilation unit plus the allowed companion / sealed-owner search rules.

## Remaining problems

1. **There is still design drift in the FIR wrapper/generation path.**

   `TypeclassFirStatusTransformerExtension` is still not registered, `TypeclassFirGenerationExtension` is still effectively empty, and some wrapper-related helpers/keys remain dead. That is survivable, but it is misleading.

2. **Session-wide package scanning is still a performance risk.**

   `buildResolutionIndex()` and `buildSourceIndex()` still walk `symbolNamesProvider.getPackageNames()` across the session. Even if functionally correct, that is still a “scan the world and hope” shape that deserves either measurement or a narrower indexing strategy.

3. **Rule IDs may still be too collision-prone.**

   IDs based on `callableId + providedType.render()` are better than nothing, but overloads that differ only by contextual parameter structure or generic signature shape still deserve scrutiny.

Confidence: high on the dead-path drift. Medium on the package-scan cost and rule-id collision risk.
