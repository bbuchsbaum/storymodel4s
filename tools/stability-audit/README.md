# Stable API audit

`run.sh` compiles and runs a small Scala 3.7.4 `TastyInspector` against the immutable
release-workflow distribution. It writes a semantic, root-directed signature graph to the
requested JSON path (the default is the dated evidence directory).

The root list is intentionally explicit. The walker follows public signature types, type
arguments, parent types, public inherited methods/fields, accessible accessors, and protected
members. It records enum/sealed case payloads insofar as they occur as public members. It does
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
