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

## Rules worth knowing

- **Primary `-of` roles** are exactly `consist-of`, `prep-on-behalf-of`, `prep-out-of` (pinned to
  Penman's AMR model); every other `X-of` is the inverse of `X`, so `parse(render(r)) == r` holds
  over the whole role inventory.
- **Canonical form** is individualization–refinement: label-invariant even where 1-WL cannot
  separate nodes (a 6-cycle beside two 3-cycles), with twin pruning so identical fillers stay
  linear. `Canonical.formBounded` reports whether the `BranchBudget` sufficed.
- **Number literals** are canonical in the graph (`1e3` → `1000`, `5.0` → `5`); the PENMAN tree
  keeps the surface spelling. `FromChart` renders numbers the same way.
- **Frame shape** is a heuristic (`[a-z]{2,}(-[a-z]+)*-NN`): `f-16` is not a frame; `covid-19`
  still looks like one and is settled only by the lexicon (`SchemaChecker.UnknownFrame` warning).
- **Parser totality**: nesting is bounded by `PenmanParser.MaxDepth` (`TooDeep`), alignment
  indices by `MaxAlignmentDigits`, and nothing escapes `parse` as an exception.
- **Alignment markers are evidence**: `AmrCandidates.fromPenman(..., tokens = Some(spans))` turns
  `~e.N` markers into an `AmrAlignment` sidecar and chart alignments; without token spans the
  default `MarkerPolicy.Strict` refuses with `InteropError.Lossy` rather than dropping them.
- **`FromChart` is honest about loss**: `lossReasons(chart, lexicon)` is empty exactly when
  `ToChart(FromChart(c)) ≅ c` — unknown predicate polarity, alignments, glosses, sense credences,
  foreign namespaces, non-regenerable embeddings/normalized roles, and number-shaped symbols are
  all reported; `Policy.Strict` refuses them.
- Raw scores on normalized roles (`StandardRoleRawScore`, `LexiconRawScore`) are uncalibrated
  and never probabilities.

## Differential oracle

`tools/penman-oracle.py` runs Python Penman 1.3.1 over `tools/penman-fixtures.txt` and regenerates
`amr/src/test/scala/storymodel4s/amr/PenmanGolden.scala`; released tests are Python-free.
