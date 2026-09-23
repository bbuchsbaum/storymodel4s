# ADR 0020: declared-lossy tabular export of a validated StoryModel

Accepted direction, 2026-09-23, single-developer mode (SD5). Ticket
`bd-01M19XMQN4VK4WSTVSHBD3X1YB` (S5 output bundle). Owner decision 2026-09-22: 1.0 "stories
alone" includes a tabular StoryModel export.

## Context

Psychology and neuroimaging users read tables in R or Python; they do not read
`storymodel.json`. `StoryModelCodec` is the canonical, lossless interchange (the output-bundle epic
agreed not to invent a parallel semantic JSON). A table set cannot carry the whole model without
becoming a second, worse encoding of it, so the export is **lossy by design** and must say exactly
what it dropped. The mapping exchange (`docs/mapping-exchange.md`, ADR 0019 exports) already fixed a
wire for analysis tables; a second wire would split every downstream reader.

## Decision

1. **Input is `TextModel[Validated]` only.** A draft is refused by type. The command-line entry
   decodes `storymodel.json` (which yields a draft), revalidates with `StoryValidator`, and refuses
   when no validated model results. No field is defaulted on the way.
2. **Film/playback is out of scope for v0.1** and says so: the manifest declares
   `playback_evidence` unavailable. Anchored support met inside a text model is refused, never cast
   to character offsets and never mixed with text spans.
3. **Wire = the mapping-exchange wire** (`quoted-tsv/v1`): every cell quoted, tab-separated, LF
   after every record, UTF-8 without BOM, typed column schema in the manifest, SHA-256 and UTF-8
   byte length per file, integers in canonical decimal, doubles as binary64 hex, structured cells
   as canonical JSON. Absence is a tagged object (`{"status":"absent",...}`,
   `{"status":"not-applicable"}`), never an empty cell and never a zero. Every row carries
   `model_digest` = `StoryModelCodec.contentChecksum` of the exported model.
4. **Vocabulary cells are tagged terms**, `{"status":"standard","value":"Agent"}` or
   `{"status":"custom","namespace":...,"label":...}`. The in-code render `ns:label` is not used,
   because `Custom("a:b","c")` and `Custom("a","b:c")` render identically.
5. **Tables** (schema `storymodel-export/v0.1`): `nodes` (every entity, situation, context and
   segment with its claim id, epistemic status and context), per-kind detail tables `entities`,
   `situations`, `contexts`, `segments`, then `relations` (the seven edge layers with a target
   node), `circumstances` (situation + literal label, kept apart so a label is never read as a node
   id), `hierarchy` (containment), `evidence` (per claim evidence item: id, stage, upstream claims)
   and `spans` (UTF-16 `[start, end)` offsets from node support and from claim evidence, with the
   surface unit as a tagged optional). The source text itself is not exported; the manifest binds
   it by `source_checksum`.
6. **Loss records.** The manifest lists, for every structure the tables do not carry, a record
   `{structure, dropped, reason}` with `dropped` the count taken from the model, present even when
   the count is zero. v0.1 drops: trajectory steps, boundary beliefs, feature spaces, sidecars,
   feature refs, descriptors, hypotheses, sensory profiles, scoped attributes, mentions, resolved
   alternatives, resolved-value claims (entity labels, segment summaries: the value is exported,
   its own claim is not), claim credence and provenance, evidence extractors, and the build
   receipt. Accounting law: exported claims plus claims counted in loss records equal
   `model.claims`.
7. **No embedded lossless copy.** Unlike `mapping.json` in the mapping exchange, the bundle does not
   carry `storymodel.json`; it binds the model by digest. Users who need the full model keep the
   build output beside the export.
8. **Pure encoder in `codec`, publication in `pipeline`**, with the mapping exchange's publication
   discipline (new directory, `CREATE_NEW` payloads, manifest linked last as the completion
   marker) and a separate `storyModelExport MODEL OUTPUT` main. The TSV helpers are copied rather
   than lifted out of `MappingExchange`, to leave that file to its owner; consolidating them is a
   follow-up.
9. **Independent reader.** A standard-library Python reader re-derives the canonical TSV bytes,
   checks every hash, schema, join and loss record, and never imports Scala logic.

## Rejected alternatives

- *One wide `nodes` table with every kind's fields.* Most cells would be `not-applicable`, which
  reads as data in a spreadsheet. The common `nodes` table keeps the ticket's
  `(id, kind, context, status)` and the detail lives per kind.
- *Rendering vocabulary as `ns:label` strings.* Not injective (Decision 4).
- *Embedding `storymodel.json`.* Doubles the bundle for a reader who by construction does not use
  it, and makes "lossy" false in a way that invites treating the tables as complete.
- *Exporting credences as numbers.* `Credence.rawScore` is not a probability unless calibrated;
  flattening score and basis into one numeric column would publish exactly the conflation the
  design contract forbids. Credence stays a declared loss until its own column design exists.

## Not established by this ADR

No film export, no compatibility promise beyond the version string, no claim that the tables are
sufficient for any particular analysis.
