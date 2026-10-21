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

## Item 4: caption fidelity across cuts (frozen comparison)

Owner authorized proceeding with the fourth work item: improve caption accuracy across shot
changes while preserving genuine continuity and visible actions/states. The bounded first
experiment changes the description recipe only. It does not change the model, frame samples,
resolution, greedy decoding or the 96-token per-call limit. It does not use recall text.

Three arms are frozen in `tools/caption_fidelity.py`: the existing joint prompt, that prompt
with explicit evidence requirements for identity continuity, and one independent description
per sampled image. The last arm has three calls and up to three times the output budget per
interval; measure tokens and time rather than claiming a cost-matched comparison. Separate
sampled images are not detected shots, and separate descriptions alone do not recover identity
continuity or actions between images. Outputs remain raw worker proposals, not admitted source
claims or a replacement production caption package.

Selection before new outputs: the three previously inspected development rows 503, 507 and
526, plus every 42nd row from 493 through 955 (12 additional intervals). All use the existing
Part B samples. An independent agent records image-only reference observations before viewing
new caption variants, then judges anonymous arm outputs against those fixed observations. The
reviewer had previously seen the development error in row 507. Separate-image output structure
can reveal that arm even with anonymous labels, so this is not complete recipe blinding. This is
a bounded within-film diagnostic with agent judgments, not independent human or cross-film validation.
The receipt must distinguish the three known development cases from the additional cases.

Acceptance questions: does false cross-image identity decrease, does genuine continuity remain
described when visibly supported, and are salient visible actions/states/objects preserved?
Record factual additions unsupported by the frames, omissions, uncertainty, and token-limit
hits. Report per-case wins and regressions; do not pick an arm solely on row 507. Do not change
the production default merely because a prompt requests better behavior.

Local inputs, frame strips, frozen requests, outcomes, reference judgments and execution logs
live in `.agent-work/caption-fidelity-20261021/`; no media or derived captions are committed.
The harness refuses changed frozen inputs, cross-arm outcomes and incomplete extent coverage.
Run its synthetic binding checks with
`python3 -m unittest discover -s tools -p test_caption_fidelity.py`.


### Exploratory resolution control

After inspecting the frozen recipe comparison, a further control tests the same original joint
prompt with `maxPixels` raised from 65,536 to 262,144; all other recipe fields and the 15 input
intervals remain fixed. The original image grids are 12 by 20 patches (320 by 192 pixels),
although the decoded frames are 640 by 360. The small budget originated in the adapter's
preprocessing court and is not a validated film-quality choice. More visual detail is a general
alternative to increasingly restrictive identity instructions. Its effect remains empirical.

This is an exploratory follow-up on already inspected cases, not an additional independent
validation set. Its request, reference hash and acceptance questions were frozen before
inference in `.agent-work/caption-fidelity-20261021/resolution-control/freeze.json`. Compare
all 15 cases, preserve regressions and token costs, and judge against the same fixed references.

### Observed caption-fidelity results

All worker calls completed successfully. The initial three arms used identical effective grids
for all 45 sampled images and identical runtimes (Python 3.13.11, MLX 0.32.2, mlx-vlm 0.6.17).
The fresh baseline reproduced every one of the 15 retained captions byte-for-byte. The
resolution control used 640 by 352 inputs, with the same runtime and original prompt.

An independent agent judged 60 interval-level variants against 44 image-first reference facts.
Initial 45 judgments were fixed before the resolution follow-up was read; that follow-up was
condition-aware. Reference qualifications retain image indices, additional people within
recurring-subject cases, and uncertainty about hand ownership, small objects and medical state.
Concrete unsupported claims are counted separately from interpretive adjectives. Counts below
are intervals with an error, not numbers of erroneous claims. Complete coverage requires all
parts of a preselected compound fact; it is not an overall caption-accuracy score.

| Recipe | False merges | False splits | Complete / partial / absent facts | Other unsupported claims: intervals | Recurring subject retained |
|---|---:|---:|---:|---:|---:|
| Original joint, 320x192 | 3 | 0 | 5 / 31 / 8 | 6 / 15 | 13 / 14 |
| Identity-aware joint, 320x192 | 0 | 4 | 13 / 26 / 5 | 7 / 15 | 7 / 14 |
| Separate sampled images, 320x192 | 0 | 0 | 13 / 28 / 3 | 9 / 15 | 0 / 14 |
| Original joint, 640x352 | 1 | 0 | 9 / 26 / 9 | 6 / 15 | 14 / 14 |

The original body-case merge disappears under all alternatives. The identity-aware prompt
also repairs rows 787 and 829 but introduces false splits in rows 526, 871, 913 and 955.
Separate-image descriptions leave identity continuity unestablished rather than solving it;
their zero merge/split count must not be reported as perfect identity recognition.

Higher resolution repairs the merges in rows 507 and 787 without adding a judged merge or
split; row 829 still merges two people. In the 12 additional systematically selected intervals,
identity-error counts are 2 for the baseline, 3 for the identity-aware prompt, and 1 for higher
resolution. Those same intervals were already inspected before choosing the resolution control,
so this is exploratory replication across cases, not a held-out result. Higher resolution
increases complete fact coverage in five intervals and reduces it in two (829 and 913).
Its other unsupported-claim flags improve in rows 577 and 871 and regress in 503 and 526;
aggregate unsupported-claim incidence is unchanged. The new omissions and remaining false
descriptions stay in the assessment, not hidden by the identity improvement.

Measured single-run times: original joint 53.96 seconds, identity-aware 64.92 seconds,
separate images 82.72 seconds, higher resolution 54.28 seconds. Generated token totals are
767, 1,242, 1,589 and 841 respectively. Only the identity-aware arm hits the 96-token cap:
three intervals (493, 619, 955). These timings are observations, not a repeated performance
benchmark. Per-image calls have a larger aggregate output budget.

The supported next recipe candidate keeps the original prompt and changes only
`captionRecipe.maxPixels` from `65536` to `262144`, with `minPixels=16384` and
`maxNewTokens=96` retained. A complete local recipe is
`.agent-work/caption-fidelity-20261021/resolution-control/recipe.json`. Existing acquisition
configuration already accepts this field and derives a new recipe identity; no production
API change is needed. This is a candidate for a fresh independent comparison, not a qualified
default replacement or a regenerated full-movie caption package. More detailed input improved
identity fidelity in this pilot, while residual factual errors prevent an overall accuracy claim.

Reproduce the judgment counts with `tools/caption_fidelity.py summarize`, supplying
`--assessment assessment.tsv --references reference-observations.json
--key combined-arm-key.json --output NEW_OUTPUT.json`, resolved under the local artifact root.
`verified-assessment-summary.json` binds the three inputs by SHA-256. Per-case adverse
judgments are in `assessment.tsv`; `assessment-summary.json` also retains paired wins/losses.
Initial experiment producer: `c57311efa0ef5fb76962328e96fb79e9d999452c`; later changes add
assessment counting and tests without altering those recorded worker calls. Eleven synthetic
tests pass. Removing the request-identity guard yields one named failure with seven siblings
passing in the then-eight-test suite. No captions, video or participant text entered Git.

## Fresh interval and actual-recall confirmation (frozen before inference)

The owner authorized testing the higher-resolution candidate on fresh intervals and downstream
recall retrieval. Select 16 temporal strata in each part and choose the minimum SHA-256 of
the fixed seed, stratum and extent identifier, excluding the 15 previously inspected frame
intervals. Independently choose 24 of the 173 recall units by a different seed coordinate,
excluding the known development units 92 and 93. Do not choose queries by matching, baseline
success or anticipated benefit. All selected units remain in the denominator, including
uninformative and unsupported queries. New frame inspections may share familiar scenes;
row 591's old caption had been discussed previously. This is not a participant or film holdout.

The local protocol, exact interval/query selection and input hashes are frozen in
`.agent-work/caption-confirm-20261021/protocol-freeze.json`. An independent agent records
image-only facts and qualifications before either new arm runs. Both arms use the original
prompt, the same pinned model, exact samples and 96-token output limit; only `maxPixels`
differs (65,536 versus 262,144). Judge paired captions with anonymous condition labels.

For downstream retrieval, the intervention replaces only the selected 32 captions in the
otherwise fixed full gallery. It is explicitly a hybrid diagnostic, not a homogeneous new
caption package. `CaptionResolutionProbe` calls the production `CaptionScoring` function on
the checked retained recall graph and bound source view. It retains all 1,000 detail targets,
missing caption values, and all 173 units. It checks each fresh low-resolution caption against
the retained baseline prices. This full-cut pricing diagnostic does not execute nomination,
normalization or final chronological decoding; their outputs must not be inferred from it.

The primary retrieval comparison is caption-only lexical distance. Stable source-key order
breaks exact ties, and tie sizes remain visible. A wholly flat price vector is non-discriminating,
not missing evidence; it produces an abstention for this diagnostic and remains in the sample
denominator. The fixed 0.5 annotation/caption blend is a separate descriptive rank comparison.
For the prespecified 24-unit sample, pool both primary arms' top three distinct intervals,
randomize presentation and judge recall/source pairs without captions, ranks or arm labels.
Credit all supported intervals, including repeated depictions. Separate sampled-frame depiction,
contextual support from the original annotation, contradiction and unknown full-interval support.
Three samples without a recalled event do not establish its absence throughout the interval.

Report supported top-1/top-3 counts, unknowns, abstentions, unchanged rankings and paired
regressions. Pool judgments do not establish exhaustive relevant-interval recall or full-gallery
MRR. Report caption facts, false identities, other unsupported claims and costs separately.
Sparse replacement may have little effect: retain that result without selecting more favorable
queries afterward. The experiment cannot establish the effect of regenerating all captions.


### Fresh confirmation results and decision

The four worker runs completed successfully on the frozen 32 intervals. Every fresh low-resolution
caption exactly reproduced its retained baseline. Anonymous paired judgments against 88 fixed
reference facts gave:

| Quantity | Original resolution | Higher resolution |
|---|---:|---:|
| Complete / partial / absent facts | 7 / 66 / 15 | 7 / 67 / 14 |
| False merges / false splits | 0 / 0 | 0 / 0 |
| Intervals with unsupported identity | 1 / 32 | 0 / 32 |
| Intervals with other unsupported claims | 14 / 32 | 15 / 32 |
| Recurring subject retained | 27 / 29 | 28 / 29 |

The probe priced all 173 units against all 1,000 detail targets, preserving the one missing
caption. It measured 172,827 available baseline pairs and verified 5,536 fresh-baseline prices.
Replacing only 32 captions changed some prices for 122 units, the first-ranked interval for four,
and the top-three list for 15. These are rank changes, not accuracy improvements.

The independent judge inspected all 63 distinct original source-image strips and original
annotation contexts for the 73 pooled recall/source pairs. Across pairs, combined support was
supported for four, partial for 24, and unknown for 45; none was judged contradicted. Insufficient
samples or annotation context do not establish an incorrect recollection. Both flat queries
remain in the fixed 24-unit denominator.

| Primary caption-only diagnostic | Original resolution | Higher resolution |
|---|---:|---:|
| Supported first choice | 2 / 24 | 2 / 24 |
| At least one supported top-three interval | 2 / 24 | 3 / 24 |
| First choice: partial / unknown | 8 / 12 | 8 / 12 |
| Flat-vector abstentions | 2 / 24 | 2 / 24 |

The single supported top-three gain is unit 3; there are no paired supported-top-one gains or
losses and no supported-top-three losses. This sparse intervention gives no persuasive evidence
for making higher resolution the default. The broader caption improvement from the exploratory
pilot did not repeat. Keep the original production recipe while testing complementary source
channels. Neither this small sample nor its sparse intervention estimates the effect of a fully
regenerated film, a new participant or a new film. Agent judgments are not researcher gold.

Local evidence is under `.agent-work/caption-confirm-20261021/`: `caption-summary.json`,
`caption-paired.json`, `retrieval/support-judgments.tsv`, `retrieval/analyze_support.py`, and
`retrieval/support-summary.json`. The original anonymous pool and source evidence remain intact.
The support summary binds the judgment, pool, ranking and analysis script hashes. No source or
participant text entered Git.

The production-function probe is a test-only diagnostic, not an admitted mixed-recipe package.
Its ownership guard checks frozen case identities, exact requests, outcome and verification
receipts, recipe differences, source ordinals and exact low/high texts. Swapping high captions
between lawful rows must fail. Removing that guard compiles, then fails the named swapped-caption
test with five siblings passing. The source was restored byte-for-byte.

On exact producer `38d70609b7b5bb58c07332ddaa553864ac88f82c`, the fatal-warning scoped
`embedBench/test` gate passed 181 tests, failed zero and skipped one (182 total). The final
`CaptionResolutionProbe` replay produced a byte-identical score table, and `scalafmtCheckAll`
passed last. The Python harness has 17 passing tests. These are local observations; they do not
qualify caption accuracy, normalized mapping weights or the original accuracy issue as resolved.

### Audio evidence and the next general scoring comparison

Live inspection found complete Whisper transcripts for both Sherlock media parts in
`data/transcription/unified-v1/full/whisper_mlx/sherlock/`, with native-media word and decoder
segment times. The deterministic export check reconstructed all 441 exported files across 110
records (95 complete, 15 failed) byte-for-byte. This checks artifact integrity, not recognition
or timestamp accuracy. Full-film Qwen/Parakeet alternatives must not be inferred from excerpt
trials. The retained Qwen review records meaning-changing disagreements and lacks word timing.

The second-part Whisper decoder segment at 250.02--250.96 seconds explicitly supplies the
woman's death and lying state. It overlaps annotation row 575 at 249--251 seconds, under the
existing declared part-clock correspondence. The body is independently described by visual
captions at nearby intervals. Thus available audio provides a concrete complementary fact,
without requiring the captioner to infer medical state from posture. We have read the machine
transcript and annotation, not independently listened to and adjudicated that audio interval.

The current caption profile uses stem-set Jaccard distances over annotations and captions.
Its A/V comparison means annotation/visual-caption, not audio/visual. `MappingPipeline.batchOnnx`
explicitly refuses caption fusion; the existing full ASR exports are not a registered scoring
channel in this route. Consequently the current experiment does not test audio-transcript
embeddings or three-source fusion.

The next comparison should retrieve candidates from each available channel and score evidence
about the same event, preserving part, interval, source identity and provenance. Embeddings can
find paraphrases; participant roles, action/state, negation and reported-world context must then
constrain support. A useful fact from one channel must not require redundant agreement from all
channels. Human annotations are optional. Annotation dialogue and ASR derived from the same
utterance must not count as independent confirmations. A later discussion of an earlier event
must retain its reference to that event, rather than becoming a present visual depiction.

Compare annotation-only where available, audio-only, visual-only, audio-plus-visual, and all
available channels, including an explicit no-human-annotation arm. Keep target universe, query
sample and source timings fixed; preselect any window sizes and avoid tuning on the known body
case. Reuse the current 24 units as development evidence only. Reserve fresh participants and
films for acceptance, judge pooled candidates blind, and report supported top-one/top-three,
wrong-person or wrong-event matches, unknowns, abstention coverage and paired regressions.
Calibrate any channel combination on separate judgments; arbitrary averaging of embedding
distances does not establish a better scorer.

Required controls include paraphrased posture/death matches, a standing observer beside a prone
person, a sleeping rather than deceased person, negated death statements, later reference to an
earlier death, hypothetical future bodies, wrong-person matches, repeated valid depictions,
ASR alternatives and timestamp offsets across cuts. Preserve missing evidence separately from
contradiction. This is the next scoped implementation/evaluation proposal, not an executed
fusion result; the parent accuracy acceptance remains open.


### Authorized native-interval audio/caption retrieval experiment

Outcome: determine whether retained ASR adds supported candidates beyond caption-only lexical
and semantic retrieval. The independent formulation review selected native-interval channel-union
retrieval v1; no blocking finding. Scope is a test-only diagnostic using the pinned MiniLM ONNX
adapter and existing local data. Production scoring, public types and dependencies stay unchanged.

Freeze the original 1,000 detail intervals and original captions. For each interval use complete
ASR decoder segments with positive overlap, in time order, without padding. Preserve central word
and complete segment times separately: words outside the interval provide context, not direct
interval ownership. The instantaneous interval remains in the gallery without invented duration.
Audio and visual media hashes must agree. No concatenation across channels is used for embeddings.

Embed the three channels separately with the existing 256-token limit. Refusals, missing channels
and flat score vectors abstain. Each channel uses cosine competition ranks; exact similarity ties
share rank and use a frozen query/target hash for presentation. Audio-plus-visual and all-channel
arms use the minimum available within-channel rank, breaking ties with the same frozen hash.
Duplicating agreement adds no vote. This is an explicit retrieval baseline, not entailment,
calibrated fusion or a probability of source membership.

Use the existing fixed 24 recall units as development evidence; retain units 92/93 as separate known
case diagnostics. Pool the three single-channel top-three lists plus the original lexical caption
control. The fused top-three candidates must be contained in this pool. An independent agent judges
original images and retained ASR without seeing ranks, scores, generated captions or human text,
then freezes those judgments before reading human annotations. Keep visual depiction, machine
transcript assertion, interval versus context support, identity uncertainty and temporal reference
separate. Machine ASR support does not establish recognition accuracy without listening. Facts from
human text cannot establish that the no-human-text inputs supplied them. Original source samples
can reveal caption omissions, which must be distinguished from support actually in model inputs.

Report paired supported top-one/top-three gains and losses, unknown/partial/contradicted outcomes,
abstention coverage and channel availability with all 24 units in denominators. These pooled labels
cannot establish exhaustive relevant-target recall or full-gallery MRR. The no-human-text arms still
use human-derived segmentation and are not end-to-end annotation-free. Invented cyclist contrasts
characterize paraphrase retrieval, wrong-person, sleeping, negated, earlier-reference, hypothetical
and unrelated statements. Embedding similarities on these contrasts must not be reported as
successful entailment decisions. A local text-support critic is deferred until candidate findings
show whether audio adds useful support; it is not silently assumed in this retrieval baseline.

Local freeze: `.agent-work/audiovisual-20261021/experiment/freeze.json`, binding source, transcript,
recall, sample, lexical control, script and 3,060 submitted requests. Primary implementation paths
are `tools/event_fusion_probe.py`, its synthetic tests, and test-only `OnnxTextProbe.scala`.
Acceptance checks: Python tests, scoped fatal-warning `embedBench/test`, final formatting, a
compiled guard mutation with named failure, exact-revision independent review and blind support
judgments. The next action after those observations is to retain, reject or extend this baseline
on evidence; scientific acceptance of the parent accuracy issue remains separate.
