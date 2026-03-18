# Learnings

- K2 IR value-argument indices are not the same as `IrFunction.parameters` indices. For member access, the logical order is `context parameters`, then the extension receiver slot, then regular value parameters, while the dispatch receiver is stored separately.
- Receiver-only generic inference is not reliable if the plugin only reconstructs type arguments from explicit type arguments and local typeclass contexts. IR rewriting also needs to infer from dispatch receivers, extension receivers, and surviving value arguments.
- Member helper accessors like `context(t: Tc<A, B>) fun <A, B> customSummon(): Tc<A, B> = t` need dedicated regression coverage. Top-level helper coverage is not enough because dispatch receivers change the IR slot layout.
- Extension operators with unrelated regular argument types, such as `context(Index<A>) operator fun <A> A.get(index: Int)`, are a good canary for receiver-only inference bugs.
- Contextual lambdas can preserve already-resolved typeclass context arguments in the IR call's raw value-argument list. Rewriters cannot assume every `@Typeclass` context parameter has been dropped before they reconstruct type arguments or explicit-argument forwarding.
- Anonymous functions reached through higher-order calls are safer to analyze through their IR parent chain than through transformer-local traversal state alone. The parent chain is what exposed the real downstream failures in `cc-plugin-main`.
- `context(value: T) fun <T> summon(): T = value` stops being a typeclass lookup only after substitution. Any IR rewrite that classifies context parameters before substituting generic type arguments will eventually mis-handle `summon<SomeTypeclass<_>>()`.
