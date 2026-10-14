# Stable API audit

`run.sh` compiles and runs a small Scala 3.7.4 `TastyInspector` against the immutable
release-workflow distribution. It writes a semantic, root-directed signature graph to the
requested JSON path (the default is the dated evidence directory).

The root list is intentionally explicit. The walker follows public signature types, type
arguments, parent types, public inherited methods/fields, accessible accessors, and protected
members. It follows public alternatives of reached enums and sealed traits, including each
alternative's payload types; unrelated alternatives outside this closure remain excluded. It does
not read method bodies, traverse reverse references, or turn an enclosing object into a root
because one nested result was reached. Directly private symbols are excluded. Scala 3 TASTy
does not make every package-scoped/generated accessor's caller visibility distinguishable through
this small walker, so those are reported as a residual review item rather than treated as a
construction door; the included compiler negative control covers the named private constructor.

This is a bounded audit aid, not a complete ABI extractor. Opaque aliases are recorded both as
their nominal symbol and their `dealias` display when TASTy exposes it. The fixture suite covers
method parameters/results, polymorphic bounds, overloads, opaque aliases, inherited `OpaqueId.T`,
case-class construction metadata, givens, and extensions. JVM ABI representation, bytecode
bridges/default-getter compatibility, and Java/Scala binary compatibility still need dedicated
review.

For each reached class, the walker follows an externally accessible constructor, both type- and
term-shaped parents, and public companion methods whose result structurally contains that class
(including wrappers). Arbitrary companion behavior whose result is unrelated remains outside the
root-directed closure.

Set `STABILITY_DIST` to an installed CLI distribution and `STABILITY_SOURCE` to its full source
commit. Before inspection, the runner verifies the manifest's source, Scala version, file sizes,
checksums and ordered classpath. The inventory provenance sidecar binds the distribution manifest,
root list, walker source and resulting inventory. Use `verify.sh` for the production roots and
compiled signature controls; use `STABILITY_ROOTS=.../inspection-roots.txt` to enumerate the
temporal inspection surface separately. Reachability alone does not assign stability or certify
a constructor's invariants.
