# Source accounts from recorded proposals

`prepare_source` anchors supplied observation rows in one exact source edition.
`compile_source` admits recorded entity, event, role and context proposals into a reusable
source-only account. Neither operation takes a recall session. The same saved account can
then be paired with any number of independently extracted recalls.

```python
from storymodel import compile_source, pair_accounts
from storymodel.source_demo import example_source_request, example_source_proposal

request = example_source_request()  # already admitted synthetic observations
result = compile_source(request, example_source_proposal())
source = result.account
assert [event.predicate for event in source.events] == ["places", "watches"]
assert [row.status for row in result.resolutions] == ["admitted", "admitted"]
# paired = pair_accounts(source, independently_extracted_recall)
```

For actual input, call `prepare_source(edition_key, text, rows)` with `SourceRow(key=...,
span=Span(start=..., end=...))` values. Spans are global half-open **UTF-16** offsets into
the exact supplied text. Rows must be nonempty, ordered and disjoint; separator gaps may
remain outside rows. No whitespace normalization, segmentation, scene grouping or time
inference is hidden in preparation. The document key identifies the edition, not a participant.

The provider receives `source_prompt(request)` and returns `source-proposal/v1` JSON.
Keys inside each row are local proposal names. Every event declares its context. Entity
mentions and role evidence must be the same exact quote, and predicates must be quoted
within event evidence. Context cues and content scopes differ; nested scopes must form a
tree, and an event must use the deepest scope that contains its whole evidence. Crossing
a declared attribution boundary or promoting its content to the narrated root refuses the row.

This is extractive structural admission, **not semantic entailment**: a quoted phrase does
not prove that a proposed agent role is correct, that the provider noticed every attribution,
or that all source content is represented. Normalized predicates, state/place/time models,
relations, identity resolution and cross-row claims are later P2 slices. No extraction accuracy
claim or provider-agreement criterion is supplied by this profile.

Every input row appears in `result.resolutions`, linked to its account unit and accepted IDs:

| Outcome | Meaning |
|---|---|
| `admitted` | The declared proposal passed structural admission; not a completeness guarantee. |
| `partial` | Search was limited or unresolved claims remain; accepted claims are retained. |
| `unsupported` | The provider explicitly said the row is outside its extraction profile, with a reason. |
| `failed` | The provider reported a processing failure. |
| `rejected` | The resolver rejected malformed or inconsistent claims; the row contributes no graph claims. |
| `not-attempted` | No proposal was supplied, or the provider explicitly did not attempt it. |

An empty `completed` proposal refuses rather than implying that no content exists.
Invalid row payloads fail atomically; valid sibling rows survive. Duplicate/foreign row keys,
ambiguous JSON and malformed envelopes refuse the whole reply because its identity is uncertain.

Keep **the result and recording**, not just the extracted account. Account v3 remains an
unchanged graph exchange format; row processing receipts are in `SourceResult`, not in the
recall-only `Account.inventories`. `verify_source(request, raw_reply, result)` recomputes all
row decisions and graph claims. Shape validation alone does not authenticate a result.
Source construction outcomes do not grant fidelity or supported source coverage.

## Offline recording and CLI

```sh
uv run python examples/source_workflow.py data/source-example
uv run storymodel source-prompt data/source-example/request.json
uv run storymodel replay-source data/source-example/request.json \
  data/source-example/recording --output data/source-replayed.json
```

The example compiles both source observations and saves a source account reused unchanged
by two independent recall sessions. Their source entities/events stay identical; their recall
identities differ. It uses only the already admitted demo text and synthetic raw proposals.

For your own local inputs:

```sh
uv run storymodel prepare-source source.txt rows.json --source-key edition-1 \
  --allow-export --output data/source-request.json
uv run storymodel source-prompt data/source-request.json --output data/source-prompt.json
uv run storymodel import-source data/source-request.json supplied-recording.json data/source-run
uv run storymodel replay-source data/source-request.json data/source-run \
  --output data/source-result.json
```

`rows.json` is an array of `{"key": "row-1", "span": {"start": 0, "end": 20}}` shapes,
with offsets appropriate to your document. `supplied-recording.json` is a `SourceRecording`
envelope containing the request digest, origin and raw reply string. Import records the raw
reply before parsing, even when malformed, and refuses existing destinations. Replay is offline,
checks input/profile/cache binding, and optionally checks an externally supplied import digest.
`--account-only` exports the checked graph when requested. Export permission defaults to denied.
No paid or live provider run is implied by these commands.

The [preservation map](preservation.md#p21-source-construction-contract) records source evidence,
compatibility and the remaining work. `schemas/source.json` is generated and drift-checked.
