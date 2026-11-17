# Recorded unit-only mapping

`prepare_mapping` and `replay_mapping` add a graph-free route from anchored recall details to
source rows or scenes. The same checked `Reference`, `Binding` and `Assessment` types feed
`project`'s recall, correspondence and source views. No provider call is made by this workflow.

```python
from pathlib import Path
from storymodel import prepare_mapping, mapping_prompt, import_mapping, replay_mapping, project
from storymodel.direct_workflow import MappingRecording
from storymodel.direct_mapping import mapping_request_digest

request = prepare_mapping(account, recall_unit_id, interpretation="referent")
prompt = mapping_prompt(request)
# Obtain a response separately; echo packet_sha256 from the rendered prompt.
recording = MappingRecording(
    request_sha256=mapping_request_digest(request), origin="recorded assessor/version",
    reply=raw_response,
)
import_mapping(request, recording, Path("new-recording"))
result = replay_mapping(request, Path("new-recording"))
views = project(result.account)
```

Run the complete abstract-token example with:

```sh
uv run python examples/direct_mapping.py data/direct-example
uv run storymodel replay-mapping data/direct-example/request.json data/direct-example/recording
uv run storymodel analyze data/direct-example/account.json
```

The destination must not exist. The example saves the request, exact prompt, imported raw reply,
result, account and three views. Its hand-supplied outcomes test engineering behavior; they do
not establish mapping accuracy.

The CLI also provides `prepare-mapping ACCOUNT UNIT --interpretation narrative|referent`,
`mapping-prompt REQUEST`, and `import-mapping REQUEST RECORDING_JSON RECORDING_DIRECTORY`.
`--account-only` on replay refuses unsuccessful admission so receipts are not silently discarded.
All exporting commands check the account's producer grant before reading additional artifacts
or creating outputs. Pure computational functions remain usable on accounts denied export.

## Input condition and evidence

The private request binds the complete account snapshot and the chosen unit. The provider prompt
contains only source documents/row and scene targets, the recall unit's verbatim text, and local
detail aliases with unit-relative UTF-16 quotes. It omits recall document identity, global offsets,
unit numbers, neighboring recall, time, normalized detail content, address, prior bindings and
private request hashes. Source order remains available. Source graph extraction is not required.

The packet digest is supplied for the response to echo; the resolver checks it. The imported
recording additionally binds the private request digest, so a recording cannot be replayed on a
different occurrence even when two prompts happen to be identical. Import stores raw text before
parsing. Offline replay checks both the recording and content-keyed cache. These checks establish
consistency; a self-contained digest and origin string do not authenticate the assessor.

Tests move an identical unit across neighboring text, including a non-BMP prefix, and permute
account collections: prompt bytes stay identical while private occurrence IDs change. This is an
input-condition audit, not evidence that an external model has no priors, nor a claim that detail
selection itself was performed independently of neighboring recall. Only contiguous recall units
are supported in this profile; discontinuous detail and source evidence remains exact.

## Decisions and refusal

- Narrative and referent interpretations occupy separate binding slots. Existing slots remain
  unchanged and are excluded from subsequent requests; there is no implicit reassessment.
- Single, joint and broad references are different meanings. Alternatives retain multiple
  hypotheses; explicit external destinations are never inferred from failed localization.
- Each supplied grade names one hypothesis. Missing grades become pending for that hypothesis.
  Exact source quotes must lie within its target support union; neither a gap nor another
  alternative's evidence is accepted. Evidence identity does not prove the claimed entailment.
- Supported, contradicted, gist-detail error, recaller inference, invented, unverifiable and
  misread remain distinct. Gist-detail errors require severity. Source fidelity cannot be assigned
  to an external hypothesis; the external grade requires an external destination. Misread retains
  the detail, and no grade changes its address or the extraction inventory.
- Every requested detail receives a receipt. Missing work becomes not-attempted; invalid work is
  rejected with a failed binding. A malformed/foreign envelope or duplicate/foreign local key
  refuses the whole packet. A bad detail cannot install a partial binding or poison a valid sibling.

## Scope and design decision

Provisional decision for P3.1: reuse the checked account reference/fidelity vocabulary with local
proposal aliases and the existing recorded executor. Rejected alternatives: exposing the whole
recall and global indices as Scala `AgentPrompts` does, requiring a full event graph for direct
mapping, translating provider keys into trusted identities, or adding a second execution engine.
This profile deliberately changes the prompt condition and is versioned separately; it does not
claim byte compatibility with Scala's full-recall mapper.

This is the first P3 route, not full P3 parity. Event/episode targets, semantic channels, richer
source evidence, batch orchestration and reassessment remain owed. Scala's fixed-question claim
assessment receipt also preserves reviewed-not-expressed, extraction/reference unresolved and
simultaneous support/counterevidence (conflicted); this simpler graded path does not replace it.
Neither quoted evidence nor successful admission establishes scientific accuracy. The full P2
graph, P4 numerical work, P5 studies and P6 inspector remain part of the port commitment.
