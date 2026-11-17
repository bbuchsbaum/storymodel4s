# Context-aware conditional fidelity

The `context-evidence-conditional-fidelity/v2` profile connects checked context holders and
attributed identity to the existing graded fidelity workflow. Reference, graph content and
previous completed/unavailable/refused assessments stay unchanged. The ordinary unit-only v1
profile, schemas and recorded replies remain available with their original input condition.

```python
from storymodel import ContextEvidence, prepare_fidelity, fidelity_prompt, resolve_fidelity

request = prepare_fidelity(
    account,
    recall_unit_id,
    context_evidence=ContextEvidence(
        identity_annotations=exact_identity_json,
        holder_proposals=exact_holder_json,
    ),
)
prompt = fidelity_prompt(request)
result = resolve_fidelity(request, raw_reply)
```

Both exact recordings must belong to the request's complete account snapshot. Attribution and
holder-dependent identity are recomputed from those recordings at public consumption boundaries.
A supplied derived identity object cannot replace them. Use the same `FidelityRecording`,
`import_fidelity`, `replay_fidelity` and `verify_fidelity` functions for offline execution.
The v2 request, prompt, result and recording profile identify the expanded evidence condition.

## What this profile exposes

It retains the original full source text/catalog and each question's fixed reference hypothesis.
For each hypothesis, it adds source situations whose complete evidence lies inside that
hypothesis's target-support union. Partial overlap is insufficient. On recall, only the detail's
explicit `event` link selects a situation; overlapping spans do not manufacture placement.
A missing link is `no-recorded-situation`, never an inferred narrated-world context.

For these situations, it includes context ancestors, roles, holder candidates and their mention
identity decisions. The selected recall situation or attribution evidence may extend outside
its recall unit. This is explicitly **not the unit-only information condition**. Identity is
computed from the account's document-local mention inventory, even when some witnesses are
outside the visible projection.

The prompt uses per-question local aliases and excerpt-local UTF-16 coordinates. It withholds
unrelated recall situations, other recall documents, global recall offsets, session identity,
previous grades and raw annotation explanations. It does not expand to every member of a
referent class. A resolved upstream identity with withheld class members is marked unavailable
for a required dependency in this projection, rather than silently treating hidden witnesses
as visible evidence. Source and recall aliases do not establish cross-document identity or
inequality.

Context and holder excerpts outside the hypothesis's targets are background evidence. They
cannot replace exact hypothesis-owned source citations for a substantive fidelity grade.
Reported, believed, intended and other non-root content retains its context chain; holderless
frames remain distinct from unresolved holder-bearing frames. The existing second-pass identity
rules still prevent an unresolved inner holder from borrowing an outer holder, and preserve
plural, unknown-number and addressee uncertainty.

## What each answer declares

A `storymodel.fidelity-proposal/v2` answer has the usual grade/status/evidence fields plus:

```json
{
  "context_accounting": {
    "considered_contexts": ["c0", "c1"],
    "required_holders": ["c1"],
    "required_mentions": ["m0"],
    "reason": "This judgment depends on the attributed speaker and this mention's identity."
  }
}
```

Use the aliases actually supplied for that question. Every supplied context must be considered
exactly once. Required holders must identify holder-bearing contexts; required mentions must
be visible mentions. Duplicate or foreign aliases reject that question without changing its slot.

When a required holder or mention identity is unresolved or has withheld witnesses, only a
noncompleted status or `completed/unverifiable` may be admitted. No grade is manufactured from
the gap. A context-independent judgment may use empty dependency lists with an explicit reason,
subject to the same source-quote and grade checks as v1. The reason must explain relevance or
why unresolved dependencies are unnecessary.

This is attributed dependency accounting. The validator does not prove that a claimed
independence is semantically correct, authenticate a reviewer, or certify entailment or grading
accuracy. It rejects contradictions between the declared dependencies and the proposed grade.
Accepted accounting survives in the v2 result, keyed by local question alias; the original raw
reply remains available for rejected or missing work. Fixed-population claim assessment remains
a separate contract and is not converted into graded fidelity.

## Connected example

From `port/`, run `python examples/context_fidelity_workflow.py data/new-context-fidelity-run`
with a new destination. One source is reused across two independently constructed recalls.
Recorded controls admit a named-holder judgment, reject a judgment requiring an unresolved
holder, and admit a declared context-independent judgment under the same unresolved input.
The example saves exact inputs, recordings, prompts, all three full receipts, separate accounts
and the three analysis views. Scripted outcomes establish admission behavior, not grading accuracy.

## CLI and limits

```sh
storymodel prepare-fidelity account.json UNIT \
  --identity-annotations identity.json --holder-proposals holders.json --output request.json
storymodel fidelity-prompt request.json --output prompt.json
storymodel import-fidelity request.json recording.json new-recording-directory
storymodel replay-fidelity request.json new-recording-directory --output result.json
```

Supply both contextual recording options or neither. Export permission is checked before reading
these extra files. Without the options, preparation uses unchanged v1. Replay remains input-bound
and offline; `--account-only` refuses to discard unsuccessful outcomes.

Native-media source mapping/assessment, general neighboring-recall profiles, cross-target grade
citations, cross-document identity linking and explicit reassessment history remain owed.
A useful context-aware text workflow does not close those capabilities or establish full parity.
