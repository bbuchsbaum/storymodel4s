# Stable API audit

`run.sh` compiles and runs a small Scala 3.7.4 `TastyInspector` against the immutable
release-workflow distribution. It writes a semantic, root-directed signature graph to the
requested JSON path (the default is the dated evidence directory).

The root list is intentionally explicit. The walker follows public signature types, type
arguments, parent types, public inherited methods/fields, accessible accessors, and protected
members. Each reached member has its own exact `fullName :: info.show` identity in `members`,
with `exposedBy` receiver nominal identities and `origins` (`constructor`, accessor, generated,
protected, inherited, ordinary member, or companion factory). Member signature dependencies are
emitted from that member identity, rather than from the receiver class. This retains overloaded
signatures independently and makes default/getter/constructor association explicit when TASTy
flags expose it. It follows public alternatives of reached enums and sealed traits, including each
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

`nominals` is the exact set of class and type declaration identities reached by the graph.
`validate-supported-closure.py INVENTORY.json DISPOSITIONS.json` separately checks an intended
supported surface; it does not change the full inventory. Its manifest uses
`storymodel4s-stable-api-disposition/v1` and two maps from **exact inventory identities** to
`stable` or `experimental`:

```json
{
  "schema": "storymodel4s-stable-api-disposition/v1",
  "nominals": { "<inventory.nominals entry>": "stable" },
  "members": { "<inventory.members[].id entry>": "stable" }
}
```

Use [dispositions.example.json](dispositions.example.json) as a schema-only starting point.
Every public/protected member exposed by a stable nominal needs an explicit member disposition;
an omitted member fails closed. Every internal dependency of a stable member, and every nominal
edge from a stable nominal except the member/classification links, must resolve to an explicitly
stable nominal. That includes parent and public-alternative edges. Experimental members are not
supported closure roots, but remain present in the full graph. In particular, class constructors
are independent member exposures, so classifying a companion factory as experimental never removes
a constructor obligation. The walker preserves
Scala/JDK/Cats/Circe references as terminal `external` records; the supported-closure validator
does not require dispositions for those external implementation APIs.

After copying exact identities into a reviewed manifest, run:

```sh
python3 tools/stability-audit/validate-supported-closure.py INVENTORY.json DISPOSITIONS.json
jq '[.members[].id] | unique | length' INVENTORY.json
jq '[.members[].origins[]] | group_by(.) | map({origin: .[0], count: length})' INVENTORY.json
```

The full `inventory` count and edge count will grow because public members are now distinct graph
vertices and their typed edges preserve their origin. The nominal signature closure, explicit roots
and external stopping rule are otherwise unchanged; this is an inventory omission correction, not
automatic stable promotion.

Set `STABILITY_DIST` to an installed CLI distribution and `STABILITY_SOURCE` to its full source
commit. Before inspection, the runner verifies the manifest's source, Scala version, file sizes,
checksums and ordered classpath. The inventory provenance sidecar binds the distribution manifest,
root list, walker source and resulting inventory. Use `verify.sh` for the production roots and
compiled signature controls; use `STABILITY_ROOTS=.../inspection-roots.txt` to enumerate the
temporal inspection surface separately. Reachability alone does not assign stability or certify
a constructor's invariants.
