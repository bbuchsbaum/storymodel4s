# Native source observations and text recall

Account v8 extends conditional assessment evidence to exact native playback supports. It
preserves the existing source/recall graph, occurrence IDs, reference meanings and analysis
policies. V6/v7 remain native-graph formats with text-only assessment evidence; upgrading is
explicit and binds the original account snapshot and every document/media byte fingerprint.

```python
from storymodel import upgrade_assessment_account, project
from storymodel.direct_mapping import prepare_mapping, resolve_mapping
from storymodel.fidelity import prepare_fidelity, resolve_fidelity

account = upgrade_assessment_account(paired_native_source_and_text_recall)
request = prepare_mapping(account, recall_unit)
mapped = resolve_mapping(request, recorded_mapping_reply)
questions = prepare_fidelity(mapped.account, recall_unit)
assessed = resolve_fidelity(questions, recorded_fidelity_reply)
views = project(assessed.account)
```

The existing import/replay operations persist exact replies before parsing and bind the entire
request. CLI preparation accepts `--native-source` to request the explicit v8 upgrade. Subsequent
prompt/import/replay commands dispatch from the saved request. Export grants are checked before
recording access. Source accounts remain independent of recall.

## Information and evidence contract

Mapping request/result v3 and proposal v2 use `native-observation-direct-mapping/v1`.
Fidelity request/proposal/result v3 use `native-observation-conditional-fidelity/v1`.
Text v1 and contextual-text v2 profiles retain their separate information conditions.

The native prompt renders supplied source events, states, participants, contexts, circumstances
and media descriptors, alongside local recall text. These are admitted symbolic observations;
the prompt does not contain raw audiovisual bytes. It omits recall graph structure, neighbors,
global recall coordinates and previous judgments. No automatic cross-modal identity or holder
resolution is supplied. Reported, believed and intended content retains its epistemic placement.

A native proposal uses `playback_evidence: [{"situation": ..., "support": ...}]`. The cited
situation must belong to the source and its complete evidence must lie inside the hypothesis's
exact target union. The citation must also lie inside the situation's exact support. Gaps stay
gaps, points never acquire duration, and matching numbers on another edition/stream/clock do
not establish matching evidence. Text quotations retain their existing exact quotation checks.
The stored assessment contains canonical support unions; the recorded reply retains the original
situation citations and the request fixes the graph that was assessed.

Absent or pending assessments may be filled; completed/unavailable/refused assessments and
reference hypotheses remain unchanged. Rejected grades leave pending slots intact. The account
validator also checks all native assessment evidence against source targets, including evidence
attached to pending or unverifiable outcomes. External hypotheses cannot carry native citations.

The three-view analysis consumes native **source** units and text recall. Supported definite
row references can earn present coverage; pending evidence remains not-assessable; broad and
alternative references retain their existing uncertainty rules. This is row coverage, not
playback-duration coverage. Any recall-side media source is refused, including hidden media
alongside an otherwise textual selected recall unit.

## Connected example and compatibility

From `port/`, run `python examples/native_assessment_workflow.py data/native-assessment-example`.
It extends the existing playback construction example: one source, two independently recorded
text recalls, detail admission before pairing, mapping, fidelity and saved views. A valid native
citation earns coverage; a citation crossing an unsupported gap is rejected. Abstract controls
establish contract behavior, not extraction accuracy, calibrated uncertainty or scientific truth.

The declared v7 account schema is frozen. Current mixed rich-account envelope schemas expose
v8 as an additional account version; legacy-only envelopes and source quotation/proposal schemas
stay unchanged. Native capabilities do not silently enable text-only identity, inference or
measurement consumers. Native numerical inference, native recall details, holder-aware native
assessment, acquisition and full P2–P6 coverage remain owed.
