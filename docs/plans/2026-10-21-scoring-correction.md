# General source-localization scoring correction

Issue: `bd-01M5EA1JDF877X4RZPJY6EFJDJ`. Design frozen before implementation and new comparisons,
2026-10-21. Historical baseline producer: `99344ea01ffd82613b85337d6dd23a49b809f0c7`.
Implementation base: `56d750408f3ca5ad7274c289d30d94dcb7134051`.

## Mechanism and bounded decision

A missing proposition sketch does not establish a preference for coarse scenes. An undeclared
source participant inventory does not establish a participant mismatch. A fixed external floor
cannot establish intrusion from a sum containing such penalties. The retained NN03 case exposes
these mechanisms; it is a development case and cannot establish improved general accuracy.

The first qualified correction uses one measured semantic channel, at a declared single target
level, without function, granularity, entity, propositional or distortion penalties. Missing
semantic observations are unavailable, never substituted with 0.5 or zero. Disabled terms are
not measured support. Normalize once per nominated admitted source target; multiple fidelity
modes do not multiply its prior. Equal target-mode prices are required. Source membership is
unestablished. Keep this conditioning in the measure, identities and exchange. Refuse consumers
that cannot preserve it. Historical policies and outputs remain available unchanged.

Rejected: remove missing terms from an arbitrary weighted sum. That rewards sparse descriptions:
semantic 0.4 with an observed entity mismatch 0.4 costs 0.8, whereas missing entity evidence
would cost 0.4. Also rejected: phrase rules, retuning a granularity constant for NN03, and calling
symmetric graph similarity directional entailment. Richer event-support scoring needs a separate
validated contract.

## Engineering falsifiers (before tuning)

- Semantic-only cost equals its observed distance exactly; disabled placeholders cannot increase
  support or change the cost. Empty sketches, undeclared structure and coarse neighbors cannot
  change a selected detail's price.
- Missing/ineligible semantic observations yield no priced fallback or normalized mass.
- External prices do not affect source-conditional weights; fidelity-mode multiplicity does not
  change target prior. Unequal mode prices and heterogeneous measured bases are refused.
- Historical reference behavior remains unchanged. Conditional scope survives export/import,
  is bound into identity, and inconsistent metadata/rows are rejected.
- Retained NN03 diagnostic reuses immutable scalar exports in place. Report all source ranks,
  candidate inclusion and adverse cases; do not require a selected interval to win. Row 575
  is dialogue referring to the same event, not an established negative example.

## Empirical evaluation protocol (unexecuted)

Compare frozen historical scoring, the corrected fixed-basis profile, and a semantic-only detail
control. Representation arms: annotations only, AI visual descriptions only, and their existing
fixed blend at 0.5. Caption acquisition is recall-blind and reused across arms. Freeze model,
caption receipts, source cut, segmentation, candidate budget, temperature and all inputs before
running. Use identical eligible targets and units for the primary paired comparison. Compare
full-cut scoring where affordable to isolate retrieval from scoring. No parameters are selected
using the reported examples.

Reference judgments are made without model ranks and admit multiple supporting intervals,
uncertainty and genuine source-external recalls. Separately label depicted content and dialogue
about the event. Sample direct hits, paraphrases, close wrong events, repeated events, genuine
non-source and out-of-order recall. Split by participant and movie where feasible. Existing
Sherlock development cases and previously unsealed holdouts are not fresh validation.

Report candidate recall, target ranking (MRR and supported top-1/top-5), supported temporal
localization, abstentions/coverage, and false intrusion separately. The conditional producer makes
no intrusion prediction: do not count that as a measured improvement in intrusion discrimination.
Report paired wins/losses and participant-cluster uncertainty; stratify missing visual evidence,
source structure, repeated events and recall order. Primary acceptance requires a positive paired
ranking improvement with uncertainty reported and no material regression in agreed strata; do
not assert this acceptance without adequate independent judgments. Decide material-regression
margins with the study owner before independent labels are opened.

In the visual arm, distinguish: content outside sampled frames, generated-description omission,
wrong generated description, retrieval miss, and ranking miss despite supporting description.
The current captions describe a face-down person in a pink coat for some body shots, but do not
explicitly establish death or gender; omission is not contradiction. Manual coordinates used to
sample these captions are not evidence that automatic segmentation has been qualified.

## Completion claims

Synthetic invariants establish an engineering correction. Retained-case arithmetic establishes
its diagnosed effect on those retained candidate scores only. Independent accuracy improvement,
fully automatic acquisition, and event entailment remain separate acceptance claims.

## Running the corrected producer

Use the ordinary mapping configuration with `"mode":"reference-only"` and one of:
`mapping-lexical-source-reference/v1`, `mapping-onnx-source-reference/v1`, or
`mapping-caption-source-reference/v1`. Keep the existing ONNX artifact settings or verified
caption-package input appropriate to that channel. Example lexical settings:

```json
{"profile":"mapping-lexical-source-reference/v1","mode":"reference-only","temperature":"1.0","perLevel":20,"tieBudget":200,"requested":["Mapping"],"networkRequests":0,"retries":0}
```

The new profiles require reference-only execution. Existing `mapping-*-pair/v1` settings retain
their historical behavior. Normalized values declare `unit/nominated-admitted-source-targets`;
source membership is unestablished, even when one candidate receives all the score mass.
Temporal allocation and organization summaries currently refuse this new measure because their result
types do not retain conditioning. Existing historical readouts remain supported. Incompatible paired
settings are rejected before provider execution.
