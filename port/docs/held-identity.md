# Identity from checked context holders

`resolve_attributed_identity(account, forms_json, holders_json)` consumes the existing
[context-holder result](attribution.md) to add a second identity pass. The same exact recordings
are used by the API, CLI and saved-result verifier. An optional `attribution=` argument accepts
a saved result only after full recomputation against those recordings.

```python
from storymodel import (
    resolve_attributed_identity, verify_attributed_identity, attributed_identity_roles,
)

result = resolve_attributed_identity(account, forms_json, holders_json)
verify_attributed_identity(account, forms_json, holders_json, result)
roles = attributed_identity_roles(account, forms_json, holders_json, result)
```

The original account, first-pass identity and holder results remain unchanged.
`result.attribution` retains all upstream decisions and receipts. Each final referent has
`base_referent`, its original identity-class ID, and complete `members`, including accepted
first-person occurrences. Final classes use the `attributed-text-referent` namespace;
all final mention references and candidate references address these final classes. The
holder records inside `result.attribution` continue to address the retained base classes.
No account-local entity or context ID changes.

## Rule and refusal behavior

Only valid, provided first-/second-person form annotations can reach this pass. Missing,
invalid or unsupported annotations preserve their original processing reasons. Placement
comes from the mention's actual event/state roles, never string position or nearest speaker.
All roles must name one context; competing placements stay `ambiguous-context`, even when
their holders agree. A mention with no placement stays `context-unknown`; a narrated-world
mention stays `outside-held-context`.

| Placed mention and holder | Final identity |
|---|---|
| First person, explicit singular, named holder | Attach to holder's class under `speech-holder/v1` |
| First person, plural, named holder | Open `speaker-group`; holder is a candidate |
| First person, unknown number, named holder | Open `speaker-number-unknown`; holder is a candidate |
| Second person, named holder | Open `needs-addressee`; holder is a candidate |
| Holder absent, unresolved or unprocessed | Open `holder-unresolved`; detailed cause remains upstream |
| Holder belongs to a different document | Open `holder-document-mismatch`; do not merge document-local classes |

An inner context uses its own holder; no implicit parent inheritance is performed. Holders
are resolved from the unchanged first pass: introducing mentions and resolved third-person
pronouns. Newly attached first-person mentions cannot become holder evidence in the same run.
Self-reference or a chain of first-person holders therefore does not bootstrap a new identity.
This preserves Scala's two-pass rule, rather than introducing an unqualified iterative method.

One deliberate difference from Scala `EntityIdentity.resolve`: unknown number is not treated
as singular. Scala's first-person non-plural branch accepts `Number.Unknown`; this Python
recipe requires positive singular evidence. A declared form is still an interpretation, not
automatic linguistic truth or a calibrated prediction.

`attributed_identity_roles` verifies the saved result and exposes final identity for every
event/state role while preserving its context, occurrence and evidence. API computation may
remain private on export-denied accounts; CLI producers enforce the original grant before
opening recordings. The verifier emits only an account-bound verification receipt.

## Executed workflow and retained gaps

The [attribution example](../examples/attribution_workflow.py) now saves both passes and final
role inspection, from the same abstract source-construction recording:

```sh
python examples/attribution_workflow.py data/held-example
cd data/held-example
storymodel resolve-attributed-identity account.json identity-annotations.json holder-proposals.json --output final.json
storymodel verify-attributed-identity account.json identity-annotations.json holder-proposals.json final.json
storymodel attributed-identity-roles account.json identity-annotations.json holder-proposals.json final.json --output roles.json
```

This does not infer addressees, plural speaker-group membership or unknown grammatical number.
Scala's richer number acquisition and third-person number compatibility remain owed, as do
additional context kinds, automatic holder candidate discovery, playback and attribution-aware
assessment. Existing first-pass identity APIs and schemas remain unchanged. The engineering
controls establish conditional behavior, not semantic accuracy or full Scala parity.
