# storymodel4s-proposition

The canonical local semantic contract of storymodel4s (design record §99.1, §102).
Every layer above `core` — `amr-interop`, `acquire`, `document`, `story`, `recall`,
`align`, `interview` — speaks in `PropositionChart`s. Nothing above this module
may reference an AMR, PENMAN, or parser-specific type.

## The contract

A `PropositionChart[C <: CheckState]` is a small, **partial**, evidence-backed
local chart:

| Part | Meaning | Partiality allowed |
|---|---|---|
| `concepts: Map[ConceptId, Concept]` | lemma, optional gloss, optional `FrameRef`, `ConceptKind` | `Concept.unknown`; frame absent |
| `relations` | `from --RoleAssignment--> ConceptTarget` | `ConceptTarget.Unknown` |
| `RoleAssignment(source, normalized)` | the role as expressed (`ARG1`, `:location`, …) plus an optional `ParticipantRole` with its **own** credence | normalized absent |
| `polarity: Map[ConceptId, Polarity]` | per predicate | absent = `Unknown` |
| `embedded` | `content` is *held* under `container` with an `EmbeddingKind` (speech, belief, …), not asserted | — |
| `alignments` | exact `SpanSet` support, discontinuous, many-to-many, credence + `ClaimMeta` | concepts with no span |
| `provenance` | origin (hand / parser / agent / converted / resolved), receipts, rival checksums | — |
| `focus` | the chart's focus concept (not "the main event") | absent |

Reentrancy is a concept that several relations target; nothing else.

### Laws other modules may rely on

- `ChartValidator.validate` is structural only: it never consults a frame lexicon.
  A checked chart can have unknown frames, bare lemmas, and unknown fillers.
- **A numbered role on a frameless concept never carries a normalized role.**
  `ARG0` without a frame is not `Agent`.
- `Canonical.form` / `Canonical.checksum` are invariant under concept renaming and
  relation order and ignore glosses, credences, alignments, and provenance.
  `ChartIsomorphism.isomorphic(a, b) ⇔ Canonical.form(a) == Canonical.form(b)`.
- `ChartCompatibility.compare` is symmetric, deterministic, and keeps **gates**
  (`roleReversal`, `polarityConflict`, `embeddingConflict`) separate from the
  graded `structuralScore ∈ [0,1]`. `compare(a, a)` has score 1 and no gates.
- `Gloss.conservative` never emits a content word that is not a lemma or literal
  of the chart (only the declared `functionWords`, e.g. `not`).

### What other modules must not assume

- That a chart is connected, acyclic, rooted, or has a focus.
- That frame sense identity is reliable: treat it as weak evidence (§102).
- That `ConceptId`s are stable across charts or builds — they are chart-local.
- That alignments are complete: abstract or implied concepts may have none.
