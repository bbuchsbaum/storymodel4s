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

## Executed development diagnostics

Producer `c323c4976c670ce3167f6dd890cfdba12da91aa3` ran six fresh local publications: the
historical and corrected policies for annotations (A), captions (V, blend 1.0), and their
fixed blend (AV, 0.5). Configuration and input hashes were frozen before execution. All six
completed successfully, with 173 units and a declared cut of 1,000 detail targets. These use
the existing lexical channels; they are not a replay of the original ONNX/BM25 producer.
No captions were regenerated. The existing package still uses manually supplied intervals.

Within each representation arm, both policies exported identical normalized source-support
sets. Exported source ranks were identical for the matched available units: 151 in A, 120
in V, and 160 in AV. Another 22, 53, and 13 units respectively had no normalized source
support under either policy; these are unavailable comparisons, not ranking agreements.
Ranks use a `1e-12` tie tolerance. The complete nomination sets were not inspected. Thus
this experiment does **not** demonstrate a source-ranking improvement from the new policy.
It does exercise the corrected measured basis and conditional export on the real inputs.

| Representation | Units with normalized source weights | u92 top source row | u92 row 503 rank | u93 top source row |
|---|---:|---:|---:|---:|
| A | 151 / 173 | 575 | 4 | 596 |
| V | 120 / 173 | 526 | absent from normalized support | 804 |
| AV | 160 / 173 | 575 | 4 | 594 |

These coverage counts are not accuracy scores. Row 575 is dialogue about the same dead woman,
so it is not an established wrong-event match; it also does not localize the depicted reveal.
In V, row 507 is absent from u92 exported normalized support, while rows 508 and 591
rank 17 and 12 among the exported normalized source targets. The top V interval, row 526,
has an annotation about inspecting an umbrella, but subsequent inspection of its recorded
frames establishes that it also depicts the body. It must not be classified as a wrong
depicted-content match from the manual annotation alone. The initial reveal's caption does
not describe a full body; its three recorded samples do not show a full body either.
These development observations do not qualify overall accuracy. Symmetric lexical similarity
does not establish event support, and normalization does not repair acquisition or matching.

The separate retained-score counterfactual reproduced the original local masses for all 173
units with maximum absolute error below `5.6e-16`. On its retained detail candidates, u92
orders row 575 first and row 503 second after removing the incompatible penalties. Its
distances already contain the historical mixed-grain reranker's effects; this is not a fresh
producer result and must not be substituted for the table above.

Local receipts are under `.agent-work/scoring-20261021/`: `fresh-diagnostic-freeze.json`,
`fresh-diagnostic-controls-freeze.json`, `fresh-diagnostic-03.log`, `runs/`, and
`fresh-diagnostic-audit.json`. The first two diagnostic attempts failed at invocation and
output-directory setup respectively; their logs are retained. Reproduce the scalar audit with
`python3 tools/audit-source-reference-diagnostic.py PATH/TO/runs`. It verifies publication
hashes and mass sums, retains every exported normalized source target for u92/u93, and
reports source-rank agreement among matched available units separately from unavailable pairs. Participant text stays in the existing local artifacts.

The next scientific acceptance step is the independent evaluation above, including separate
labels for event support and depicted-interval support. Caption acquisition and semantic
retrieval need their own measured improvements; a blanket visual boost is not supported by
these results. The parent issue remains open for that acceptance.

## Recorded-frame audit and next accuracy intervention

Subsequent agent inspection decoded the actual `bgr24` samples supplied to the existing caption
worker, rather than inferring frame content from annotations. The raw-frame SHA-256 was checked
against `frames-envelope.json`: `a434a84c01276f33a4d637142eb3471ba8a0e76a1b0d776fc47073b7c1d39373`.
The local receipt is `.agent-work/scoring-20261021/frame-audit/inspection.json`; extracted images
remain outside Git. This is a three-interval development audit, not blinded independent labels
or inspection of every frame in each interval.

| Source row | Selected sample ordinals | Observed evidence and consequence |
|---|---|---|
| 503 | 60, 61, 62 | Doorway/people, wall/hanging object, and a hand close-up. No full body in these samples. A description omission cannot be assigned to the model without checking its supplied images. |
| 507 | 72, 73, 74 | Observer in a dark jacket, then a different person face-down in a pink coat. The caption captures posture but incorrectly identifies them as the same person across the cut. |
| 526 | 129, 130, 131 | Sample 130 includes the pink-coated person lying on the floor alongside the examiner and umbrella. The top V result is a plausible valid depiction; the umbrella-focused annotation is not a negative label. |

The governing matching rule is to credit supported recalled actions or states at any interval
that depicts them, with separate labels for dialogue or other contextual support. Initial
appearance and any valid depiction are different localization questions. A visible prone person
alone does not establish death; death can be supported by additional source context with its
provenance retained. Missing descriptions are unknown evidence, not contradictions.

The next bounded experiment is the existing `AllMiniLmL6V2` ONNX annotation channel against
the lexical baseline, with the corrected profile and fixed 1,000-detail source cut. Score the
complete cut for the same units, retaining raw distances and candidate provenance, before
applying a retrieval budget. This isolates semantic ordering from candidate truncation. The
fresh A/V/AV diagnostics above used lexical Jaccard, so they did not test this alternative.
Current ONNX registration does not fuse captions: semantic V/AV comparisons require an explicit,
versioned rendering/join extension and must not be claimed by merely changing a profile name.

If semantic retrieval finds supported intervals but ranks them poorly, test a pairwise support
reranker against the same frozen candidate pool. Distinguishing action, state, participant and
context must be checked; generic scene similarity is insufficient. Supported, contradicted and
unknown claims remain separate, and a relevance score is not a calibrated correctness
probability. If evidence is absent from the sampled images, test recall-blind shot coverage;
if present but misdescribed, test per-shot descriptions and identity continuity separately.
The row 507 error specifically motivates a cross-cut identity check, not a death-word rule.

Evaluate these interventions separately against blinded, multi-positive reference judgments,
with candidate recall, supported top-1/top-5, ranking, depiction localization, coverage and
paired regressions reported under the frozen protocol above. Existing Sherlock cases are
development diagnostics. Neither the frame correction nor a successful example establishes
general improvement; the next model comparison and independent acceptance remain unexecuted.
