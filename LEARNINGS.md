# Learnings

- K2 IR value-argument indices are not the same as `IrFunction.parameters` indices. For member access, the logical order is `context parameters`, then the extension receiver slot, then regular value parameters, while the dispatch receiver is stored separately.
- Receiver-only generic inference is not reliable if the plugin only reconstructs type arguments from explicit type arguments and local typeclass contexts. IR rewriting also needs to infer from dispatch receivers, extension receivers, and surviving value arguments.
- Member helper accessors like `context(t: Tc<A, B>) fun <A, B> customSummon(): Tc<A, B> = t` need dedicated regression coverage. Top-level helper coverage is not enough because dispatch receivers change the IR slot layout.
- Extension operators with unrelated regular argument types, such as `context(Index<A>) operator fun <A> A.get(index: Int)`, are a good canary for receiver-only inference bugs.
