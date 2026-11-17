# States and circumstances in v4

`RichAccount` is an explicit new wire version. It adds state conditions and literal time/manner
circumstances to the existing event graph, sharing contextual validation with legacy accounts.
Existing v1–v3 schemas, example IDs, requests, prompts and recorded results remain unchanged.
`upgrade_account(v3)` is explicit; it preserves graph collections and IDs and records the input
digest. It does not claim that missing rich fields have been extracted.

```sh
uv run python examples/rich_construction.py data/rich-example
uv run storymodel replay-rich data/rich-example/source/request.json data/rich-example/source/recording
uv run storymodel situation-table data/rich-example/paired-0.json
```

The complete abstract-token example records three independent construction inputs, builds one
source and two recall accounts, reuses the unchanged source, saves both paired accounts, and
reads events/states/circumstances back through Python-derived tables. It tests engineering
contracts; token labels and hand-supplied outcomes are not linguistic gold.

## Representations

- `State` is a condition with its own occurrence identity, side, mandatory context, predicate,
  participants and exact evidence. Polarity and modality explicitly admit unknown; absence never
  defaults to positive or asserted. The first profile exposes these attributes for states only.
- A place is an existing entity occurrence in the `location` participant role. This slice does
  not introduce a second place-node hierarchy or infer cross-document location identity.
- `Circumstance` attaches a literal `time` or `manner` phrase and evidence to an event or state.
  Its context follows that situation. It is not a measured clock, date, duration or a time entity.
- Existing relation kinds can connect events and states under the same endpoint/context/cycle
  checks. A recall detail's retained wire field `event` can name either situation in v4.
  Full temporal algebra, richer relation semantics and a revised detail-facing API remain owed.

`situation_table` retains kinds, IDs, contexts, role evidence and literal circumstances. Event
polarity/modality is unavailable (`None`), not copied from the state defaults. Pairing validates
source-only and recall-only inputs, retains every graph collection and intersects export grants.
Public consumers revalidate copied objects, including those created through `model_copy`.

## Recorded construction

`prepare_rich_construction(key, side, text, rows, export_permission=...)` anchors exact document
text and explicitly supplied UTF-16 rows. `compile_rich_construction(request, raw)` checks row-local
proposals; `import_rich` and `replay_rich` use the existing content-keyed executor to retain raw
replies before parsing and replay offline. `verify_rich_construction` recomputes from original inputs.
The CLI provides `prepare-rich`, `rich-prompt`, `import-rich`, `replay-rich` and `situation-table`.

States and events share quote, role and attribution-scope admission. Every claim must use the
deepest declared scope; a reported condition cannot enter the narrated world merely by naming
the root. Roles must cite their local entity occurrence, and circumstances must cite exact text
within their owning situation's support. Admission establishes these declared structural/evidence
relationships, not semantic entailment or correct provider classification.

Each requested row has an outcome. A bad row is rejected atomically while earlier lawful rows
remain. Missing, unsupported, failed and limited work stay distinguishable. The construction
result and raw reply must accompany the graph when making completeness claims.

**Recall graph construction is not detail extraction.** Recall units get explicit not-attempted
detail inventories, including when their graph claims were admitted. No zero-detail completion or
absence claim is manufactured. Recall details, address/discourse, grouping and summaries remain
the next construction work.

## Compatibility boundary and remaining work

Old analysis, alignment, identity and direct-mapping consumers currently reject v4. They cannot
silently drop states or claim a complete event-only search. Updating those consumers to preserve
v4 and declaring their supported evidence universe remains required integration work; a
downgrade is not supplied. V1/v2 upgrade is also deferred rather than removing their extra roots
or changing legacy identities implicitly.

This is the first state/circumstance construction slice. Playback evidence, complete context-holder
attribution, event polarity/modality, normalized predicates, richer entity types, full hierarchy and
relation families, rich recall details and the full P2 exit remain owed. All P3–P6 obligations remain.
