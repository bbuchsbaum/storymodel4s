# StoryModel tables

`storyModelExport` writes a validated text StoryModel as tables for R and Python. The export is
**lossy by design** ([ADR 0020](adr/0020-storymodel-tabular-export.md)): it carries nodes,
relations, hierarchy and exact text evidence, and its manifest names every structure it leaves out.
The lossless interchange remains `storymodel.json` (`StoryModelCodec`). Keep that file beside the
tables if you need the full model; the tables do not contain it.

```sh
sbt "pipeline/runMain storymodel4s.pipeline.storyModelExport out/storymodel.json out/tables"
python3 examples/storymodel-export/read_storymodel_export.py out/tables
```

The command decodes `storymodel.json`, **revalidates it**, and refuses (exit 2, JSON on stderr) if
validation fails, the output directory exists, or the model carries playback-anchored support. On
success it prints one JSON receipt with the input, model and manifest digests. From Scala, call
`StoryModelExportBuild.write(model, path)` with a `TextModel[Validated]`; a draft does not compile.

## Scope

- Text models only. `playback_evidence` is declared `unavailable`; film/video export is not part
  of this version.
- `canonical_model` is declared `unavailable`: no reader can re-derive the full model from these
  tables.
- Credences are not exported (a raw score is not a probability; see the ADR).

## Files

Every table is `quoted-tsv/v1`, the [mapping exchange](mapping-exchange.md) wire: every cell
quoted, tab-separated, LF-terminated, UTF-8 without BOM. Column types are declared in the manifest.
Every row starts with `model_digest`, the SHA-256 of the canonical `storymodel.json` text.

| file | one row per | notes |
|---|---|---|
| `nodes.tsv` | entity, event, state, context, segment | `claim_id`, `claim_status` (epistemic status), `context` |
| `entities.tsv` | entity | `entity_type`, `label` |
| `situations.tsv` | event or state | predicate, polarity, modality, `aspect`, `discourse_position` |
| `contexts.tsv` | context frame | `context_kind`, `holder` |
| `segments.tsv` | segment | `segment_kind`, `level`, `summary` |
| `relations.tsv` | participant, temporal, causal, goal, state-change, reference or entity-relation edge | `from`, `relation`, `to` |
| `circumstances.tsv` | time or manner circumstance | the source's own `label`, never a node id |
| `hierarchy.tsv` | containment edge | `weight` as IEEE-754 binary64 hex |
| `evidence.tsv` | evidence item of an exported claim | `stage`, `upstream`, `span_count` |
| `spans.tsv` | cited text span | half-open `[utf16_start, utf16_end_exclusive)` on the canonical source text |

Offsets are UTF-16 code units into the model's canonical source text, which is not in the bundle;
`source_checksum` in the manifest identifies it. In Python, slice
`text.encode("utf-16-le")[2*start:2*end].decode("utf-16-le")`, not `text[start:end]`, whenever the
text has characters outside the Basic Multilingual Plane.

## Missing is not zero

A `canonical-json` cell is never empty and always has a `status`:
`{"status":"present","value":0}` is a measured zero, `{"status":"absent","reason":"not-supplied"}`
means the model did not supply the value, and `{"status":"not-applicable"}` means the field has no
meaning for that row (an entity has no context). Vocabulary values are tagged terms,
`{"status":"standard","value":"Agent"}` or
`{"status":"custom","namespace":"...","label":"..."}`.

## Loss records

`manifest.json` lists one record per omitted structure: trajectory steps, boundary beliefs, feature
spaces, sidecars, feature refs, descriptors, hypotheses, sensory profiles, scoped attributes,
mentions, resolved alternatives, resolved-value claims, claim credence and provenance, evidence
extractors, the build receipt, and the source text. `dropped` counts what the input carried
(zero is a real count). An optional input the model never supplied, such as a build receipt, is
`"status":"not-supplied"` instead. The `claims` fields satisfy
`exported_claims + sum(claims) = |StoryModel.claims|`. The reader checks this accounting and the
record schema; it cannot check that a `dropped` count is true without the canonical model.

`evidence.tsv`'s `upstream` may cite a claim that is not in any table (a descriptor, hypothesis or
label claim). That is lawful; the reader reports such references as `outside_tables`.

## Example and checks

`examples/storymodel-export/war-of-the-ghosts/` is the export of the War of the Ghosts fixture.
`check_export.py` runs the reader on it, checks the node, evidence and span counts, a hand-checked
row (entity `wog:ent:egulac`, spans `[0,28)` and `[1511,1534)`) and absent-versus-zero cells,
accepts and classifies a lawful out-of-table upstream reference, and confirms that eleven rehashed
corruptions are refused.

```sh
cd examples/storymodel-export && python3 check_export.py war-of-the-ghosts
```
