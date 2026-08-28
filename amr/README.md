# storymodel4s-amr

Standards-compatible AMR interop: PENMAN syntax, a phantom-typed propositional chart, structural
validation, role canonicalization, isomorphism/canonical form, a Smatch baseline, frame schema
checks, and an exact source-alignment sidecar.

AMR is an **adapter, not the foundation** of storymodel4s. Nothing in the narrative or interview
APIs depends on AMR types, on a frame-lexicon lookup succeeding, or on `Smatch`.

## Layout

| Package | Contents |
|---|---|
| `storymodel4s.amr.penman` | `PenmanTree` (lossless profile), `PenmanParser` (cats-parse), `PenmanPrinter` |
| `storymodel4s.amr.graph` | `AmrGraph[Check, RoleForm]`, `Decoder`/`Encoder`, `AmrValidator` (§42.2 laws), `RoleCanonicalizer`, `Shape`/`Acyclic`, `AmrIsomorphism`, `Canonical`, `Smatch`, `SoftCompatibility` |
| `storymodel4s.amr.schema` | `FrameLexicon`, `StarterLexicon`, `SchemaChecker` (unknown frames are warnings) |
| `storymodel4s.amr.align` | `AmrAlignment` sidecar: subgraph, relation, reentrancy, duplicate alignments over exact `SpanSet`s |

## Pipeline

```
PENMAN text ─parse─▶ PenmanTree ─decode─▶ AmrGraph[Unchecked, SurfaceRoles]
   ─validate─▶ AmrGraph[Checked, SurfaceRoles] ─canonicalize─▶ AmrGraph[Checked, CanonicalRoles]
```

Four notions of equality stay distinct: `Eq[PenmanTree]` (syntax), `Eq[AmrGraph]` (exact, ids
included), `AmrIsomorphism` (alpha-renaming/edge-order invariant, `Canonical.form` decides it), and
`Smatch` (graded).

## Differential oracle

`tools/penman-oracle.py` runs Python Penman 1.3.1 over `tools/penman-fixtures.txt` and regenerates
`amr/src/test/scala/storymodel4s/amr/PenmanGolden.scala`; released tests are Python-free.
