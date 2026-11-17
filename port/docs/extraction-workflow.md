# Recorded event/detail extraction

This slice turns raw recall into an inspectable event/detail account through recorded,
typed proposals. It reuses intake, recorded execution, account validation and analysis.
There is no live model client, source matching or fidelity inference.
[Verification receipt](../evidence/extraction-workflow.json): full suite, cold review,
attribution mutation, v1 identity compatibility and installed-wheel checks.

Run the admitted synthetic example from `port/`, using a new output directory:

```sh
uv sync --locked
uv run python examples/extraction_workflow.py data/extraction-demo
uv run storymodel validate data/extraction-demo/account.json
uv run storymodel analyze data/extraction-demo/account.json \
  --policy target-event-only/v1 --output data/extraction-events.json
```

The example uses unchanged `detail-coding-synthetic/v2` text already admitted in `demo.py`.
Its provider-shaped JSON is explicitly synthetic, not a recording from a live model or
accuracy gold. The source account is retained independently. Raw recall is segmented by
the existing intake; the raw JSON then creates two recall events, three details and two
recall-local entities. Two details share the placement event. The indefinite actor's identity
remains unresolved. Source correspondence and fidelity are unattempted, and source coverage
is not assessable. The example never borrows its known source bindings to fill those gaps.

The directory contains raw recall, request, imported reply, immutable recording, resolution
report, account and the existing three-view analysis. `resolution.json` maps each local
proposal key to the admitted content-derived ID. Rejected proposals remain in the raw
recording, with their response digest and unit failure reason in the report.

## Contract

`prepare_extraction(key, raw_utf8, source=None)` accepts raw recall and an optional checked
**source-only** account. Source documents, entities, events and relations pass through
unchanged. Neither recall nor its provider can rewrite them. Extraction works without a
source account; it does not invent one. Export is denied by default and cannot exceed the
source producer's grant.

`extraction_prompt(request)` supplies canonical text, units, exact UTF-16 coordinates,
the response schema and instructions. It supplies no source content. The request digest
binds all inputs and permissions; using a different source or raw file requires a new
recording even when canonical recall is unchanged.

Each proposed unit declares processing status and may supply:

- **Entities:** local keys and exact mention quotes. Identity is local to the recall document
  and occurrence. Equal words in a source and recall do not imply the same entity.
- **Contexts:** kind (`reported`, `believed`, `intended`), parent, attribution cue and content
  scope. Scope is not evidence and is retained in the proposal, not disguised as `Context.evidence`.
- **Events:** explicit context, content support, verbatim predicate span and typed participant
  roles. Role evidence must be the referenced local mention within the event.
- **Details:** typed kind, exact quoted support and an existing event key. Content is derived
  from that quote. Address is `unresolved`; no binding or fidelity is manufactured.
- **Unresolved claims:** kind, evidence and reason. These remain visible outside the accepted
  graph and force a search-limited inventory rather than a completed one.

All evidence is checked against the independently prepared text and owning unit. Temporary
keys must be unique; every reference must resolve. Each unit is admitted atomically. A bad
claim rejects its unit without erasing other valid units. Omitted units are not-attempted;
failed and completed-zero-detail units remain different. Duplicate/foreign unit numbers,
invalid envelopes, duplicate JSON keys and nonfinite values refuse the whole response.
Raw replies are recorded before semantic parsing, including malformed replies.

## Context and semantic limits

Declared scopes must be strictly nested or disjoint, with matching parent references.
An event must fit entirely within its deepest declared context. Assigning it to narrated
world, or to an outer ancestor, refuses. Crossing a scope boundary also refuses; the resolver
does not guess how to split it. This bounded profile supports contiguous spans and unit-local
references. Discontinuous evidence, cross-unit coreference/contexts, normalized predicates,
relation extraction and inferred claims are deferred with explicit schema refusals.

The checks prove **evidence and structural consistency with the proposed scopes**, not
semantic entailment. A provider can omit or mislabel an attribution, choose a wrong event
kind, or assign a wrong role while citing real words. This slice does not detect every such
error and does not establish extraction accuracy. There are no calibrated scores or implied
human approval. Synthetic context tests use nonnarrative tokens to isolate the structural rule.

## Account compatibility

Extraction emits `storymodel.account/v2`. V2 expands entity origins to recall documents while
preserving existing source entities and IDs. The legacy field `Entity.source_document` means
origin document in v2. All entity evidence belongs to that origin. Recall-origin entities
cannot participate in another session's events or source events. Explicit source-entity use
in a recall event remains representable for compatibility, but this extractor cannot emit it.

V1 admission remains source-entity-only. Existing v1 accounts keep their serialized values
and identities. The existing Python projection and CLI accept both versions. Old v1-only
readers must reject v2; no compatibility with an unimplemented browser inspector is claimed.

## API and CLI

```python
from pathlib import Path
from storymodel.extraction import prepare_extraction, request_digest
from storymodel.extraction_workflow import ExtractionRecording, import_extraction, replay_extraction
from storymodel.analysis import project

request = prepare_extraction("session-1", Path("recall.txt").read_bytes())
# This JSON text must be an exported reply for this exact request.
recording = ExtractionRecording(
    request_sha256=request_digest(request),
    origin="local exported proposal",
    reply=Path("proposal.json").read_text(),
)
import_extraction(request, recording, Path("recording"))
result = replay_extraction(request, Path("recording"))
analysis = project(result.account)
```

Importing requires a new recording directory. Imported labels and checksums provide
provenance bookkeeping, not authentication of a model or author; independent file pins
are available on the Python request reader and replay import manifest.

```sh
uv run storymodel prepare-extraction recall.txt --recall-key session-1 \
  --allow-export --output request.json
uv run storymodel extraction-prompt request.json --output prompt.json
# Supply reply.json in ExtractionRecording form, bound to this request digest.
uv run storymodel import-extraction request.json reply.json recording
uv run storymodel replay-extraction request.json recording --output resolution.json
uv run storymodel replay-extraction request.json recording --account-only --output account.json
uv run storymodel analyze account.json --output analysis.json
```

`prepare-extraction` optionally takes `--source-account source.json`. Preparation and prompt
files are local working artifacts; result export requires the explicit grant. Existing
destinations refuse rather than overwrite. JSON schemas are in `schemas/extraction.json`.
