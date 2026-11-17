# A separate graded fidelity pass

`prepare_fidelity`, `import_fidelity` and `replay_fidelity` assess an already mapped detail
under each explicit reference hypothesis. They fill absent or pending grades without remapping
reference, recoding the detail or overwriting completed assessments. Both legacy and rich
accounts use the same implementation.

```python
from pathlib import Path
from storymodel import (
    FidelityRecording, prepare_fidelity, fidelity_prompt,
    import_fidelity, replay_fidelity, project,
)
from storymodel.fidelity import fidelity_request_digest

request = prepare_fidelity(account, recall_unit_id)
prompt = fidelity_prompt(request)
# Obtain a raw reply separately using the prompt's schema and packet digest.
recording = FidelityRecording(
    request_sha256=fidelity_request_digest(request),
    origin="recorded assessor/version",
    reply=raw_reply,
)
import_fidelity(request, recording, Path("new-recording"))
result = replay_fidelity(request, Path("new-recording"))
views = project(result.account)
```

The pure computation is `resolve_fidelity(request, raw_reply)`; `verify_fidelity` recomputes
the complete result from those original inputs. A parsed result is not proof of admission.

Run the abstract two-recall integration control:

```sh
uv run python examples/fidelity_workflow.py data/fidelity-example
uv run storymodel replay-fidelity data/fidelity-example/recall-a/request.json \
  data/fidelity-example/recall-a/recording
```

The example retains one rich source, two prior mappings and their references, then imports
scripted supported/contradicted grades. Coverage changes under the existing supported-only
policy while details and reference stay unchanged. The scripted assignments test the update
and analysis path; they are not a semantic discrimination test or a measured assessor result.
The recurring-scene correspondence demonstration remains a further delivery.

## What the assessor sees

The `unit-only-conditional-fidelity/v1` prompt includes the complete source text and source
row/scene catalog, one recall unit's verbatim text, and the pending questions. Each question
contains the extracted claim content, kind, exact unit-relative UTF-16 quotes and the fixed
single/joint/broad/external reference hypothesis. Unlike the direct localizer, this assessor
must see the extracted claim to distinguish an extraction misread from inaccurate recall.

It excludes previous grades and their reasons, recall document/session identity, global
recall offsets, neighboring recall, time, address and private request digests. Source order
remains visible. Local aliases identify the detail/interpretation/hypothesis slot; the provider
does not compute internal IDs. The complete private request binds the original account and
therefore the otherwise hidden identities and previous decisions.

This differs deliberately from `tools/sherlock_mapper/verify_claims.py`, which shows neighboring
recall and can cite any film row. We preserve its useful two-step judgment and graded vocabulary,
while using the port's explicit hypothesis-conditioned evidence and recorded execution. Full
cross-target source citations and a declared neighbor-aware assessment profile remain owed;
this first profile does not claim complete equivalence to that study script.

## Admission and preservation

The reply must echo the prompt's packet digest. A foreign/duplicate local question, malformed
JSON or foreign envelope refuses the packet. Each otherwise identifiable question is resolved
independently: an invalid grade is rejected, a missing answer is not-attempted, and a lawful
answer is admitted. Every selected question receives a receipt. Rejected/missing answers leave
the original slot unchanged; they never turn reference into failed or external.

Only absent/pending slots are selected. Existing completed, unavailable and refused assessments
are neither exposed nor overwritten. Narrative and referent interpretations are separate slots.
Alternative hypotheses each retain their own grade; supported evidence under one alternative
does not settle reference or earn definite source coverage. No new hypothesis is manufactured
for unresolved/failed reference. Explicit reassessment requires a future versioned operation.

Direct mapping and this pass share one conditional-assessment validator. Exact source quotes
must match their source document and lie within the hypothesis's target-support union, including
gaps and UTF-16 boundaries. A joint hypothesis can cite its declared targets; a different
alternative's target is not implicitly available. External hypotheses accept only external,
unverifiable or misread grades and cannot cite source evidence without a source target.

All eight existing grades remain distinct. Supported, contradicted, gist-detail-error and
invented require source evidence; gist-detail-error requires severity. Pending, unavailable and
refused carry reasons without a substantive grade. Reason, evidence ownership and shape are
checked; entailment, source adequacy, reviewer identity and grading accuracy are attributed,
not certified. The prompt explicitly warns that annotation absence does not prove invention.

Fixed-population [claim assessment](claim-assessment.md) is a separate contract. Its statuses
and counts are not automatically converted to fidelity grades or supported coverage.

## Recorded execution and CLI

Import stores raw text before parsing, using the existing content-keyed `RecordedRun`. Replay
checks the request, imported reply and cache, then admits grades offline. It never calls a
provider, silently retries an uncertain dispatch, or overwrites an existing recording directory.
Optional external hashes pin request/import bytes; internal hashes establish consistency,
not authentication.

Commands are `prepare-fidelity ACCOUNT UNIT`, `fidelity-prompt REQUEST`,
`import-fidelity REQUEST RECORDING_JSON NEW_DIRECTORY`, and
`replay-fidelity REQUEST DIRECTORY`. Preparation accepts `--interpretation narrative|referent`;
replay accepts `--account-only`, which refuses to discard rejected/not-attempted outcomes.
The normal result retains every outcome. All CLI exports and file workflows check producer
grants before reading additional replies or creating output. Pure functions remain usable
locally on accounts denied export.

The new request/reply/result/recording envelopes are `/v1`; their account field retains its
existing schema. Direct-mapping schemas, prompt bytes, recipes and result behavior remain
unchanged. The old `direct_mapping.SourceQuote` and `FidelityProposal` imports remain available
as aliases to the shared definitions.
