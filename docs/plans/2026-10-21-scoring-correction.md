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


### Native-interval experiment results: reject minimum-rank fusion as a replacement

Native producer `be4a2c97e6415a04d79aa3e970c56fb171000cc8` completed all 3,060 requests in
96 local provider calls, with no failed, missing or TooLong outcomes. Inputs comprise 880 audio,
999 visual-caption and 1,000 human-text targets, 173 queries and eight invented contrasts. Audio
absence is missing channel material, not evidence of silence or absent story content. The frozen
1,000-target universe and all 24 development queries remain in every metric denominator.

An independent agent inspected 197 source-image strips and rehashed every strip without a mismatch.
The 250 pooled recall/target pairs were fixed as visual depiction, machine-transcript assertion and
combined interval support before reading human annotations or generated captions. Combined AV
source labels are eight supported, 100 partial, 141 unknown and one contradicted. Partial and
unknown outcomes are not counted as erroneous recollections. The contradiction concerns a positive
ASR statement with a conflicting classification, not missing depiction. Human-text contextual
judgments were then fixed separately, adding one fully supported pair and six partial pairs.
Aggregate arm counts were disclosed after the primary AV freeze; no target ranks were shown during
either judgment stage. The reviewer had prior scene exposure from the earlier experiment.

The primary table uses the SAME frozen AV-source reference for every ranking arm. This avoids
crediting human-text arms with a richer reference than the no-human-text arms receive.

| Retrieval input/policy | Supported first choice / 24 | Supported top-three / 24 |
|---|---:|---:|
| Original lexical caption control | 2 | 2 |
| Audio embeddings | 0 | 1 |
| Original-caption embeddings | 1 | 2 |
| Human-annotation embeddings | 3 | 5 |
| Audio + captions, minimum rank | 0 | 2 |
| All three, minimum rank | 0 | 3 |

Audio-plus-caption minimum rank loses both supported lexical first choices. Its top-three lists
have one supported gain (unit 42) and one loss (26), hence no net gain. Against caption embeddings,
it adds no supported top-three query and loses the supported first choice at unit 42. All-channel
minimum rank gains top-three queries 42 and 168 versus lexical captions but loses 26; it also loses
both supported first choices. Audio alone retrieves a valid target for unit 168 in its top three,
which the audio-plus-caption policy drops. Human embeddings recover supported top-three targets
for units 3, 26, 42, 74 and 168. None of these within-film observations is an independent accuracy
qualification, and no policy is tuned on the observed outcomes.

The lexical control has 12 unknown, eight partial, two supported first choices and two flat-vector
abstentions. Audio has 11 unknown, 12 partial and one contradicted first choice. Caption embeddings
have 11 unknown, 12 partial and one supported first choice. Human embeddings have eight unknown,
13 partial and three supported first choices. Both combined policies have 11 unknown and 13 partial
first choices. Each combined arm's top-three pool contains a contradicted candidate for one query.
The other 23 are not thereby declared correct. No inference about intrusion, source membership,
normalized mapping weights, temporal accuracy or population performance follows from these counts.

Using the richer all-source contextual reference SECONDARILY raises human-only supported top-three
from five to six; all other table entries remain unchanged. That extra case comes solely from an
annotation about an empty room. It cannot establish that audio/caption inputs supplied that fact.

After all source judgments were fixed, the reviewer checked generated caption plus ASR input
coverage for the eight AV-supported source pairs. Seven are only partially expressed by the actual
model inputs: sparse furnishing, pill identity/speckling, passing a seated bench man, or the relevant
observer/seeing relation is omitted. Some captions also invent a camera or liquid. Only the phone-
absent-from-case pair has complete model-input support, supplied by central ASR alone. Thus source
retrieval validity and encoded factual coverage are separate: a returned interval can be supported
by original frames even when its caption omitted the decisive fact. These eight are a selected
positive subset, not an overall caption-error estimate.

Known development unit 92 is outside the 24-unit denominator. Audio ranks the explicit dead-woman
statement at row 575 first; the audio-plus-caption and all-channel policies also retain it first.
That locates the relevant statement but does not prove the complete recalled floor/body event is
represented in one interval. Visual posture/location and spoken death state still require a
justified link across shots. Do not call this an independently validated direct-hit repair.

The seven invented contrast documents expose an embedding limitation. True paraphrase cosine is
0.559; wrong-person, sleeping, negated, earlier-reference and hypothetical descriptions score
0.762, 0.737, 0.823, 0.839 and 0.833 respectively. Unrelated text scores 0.050. These are one
synthetic characterization, not a benchmark accuracy estimate: cosine alone is not an entailment,
identity, state, tense or modality decision. No local support critic was executed or assumed.

An additional descriptive audit finds 654 distinct audio payloads among 880 audio targets, with
290 targets sharing some payload and a maximum group of 39. Identical repeated payloads have
byte-identical exported vectors; every sampled query's best audio score has one exact winner.
Therefore duplication does not explain these observed top-score ties (there are none). Shared
utterances remain one evidence source rather than multiple independent confirmations. No dedup
reranking or tolerance adjustment was performed after outcomes were read.

Decision: retain the existing production scorer and reject minimum-rank combination as its
replacement. The useful next mechanism is support-aware event construction and ranking: preserve
atomic facets with evidence, distinguish the same participant from nearby people, bind statements
to the event they refer to, and compose compatible cross-shot facts while retaining uncertainty.
A richer caption/fact extractor and any learned support critic need their own frozen candidate
comparison; neither is established by this experiment. Human text remains optional, and the
no-human-text route still needs annotation-independent segmentation before end-to-end qualification.
The parent accuracy issue remains OPEN.

Verification: Python 13 passed, zero failed/skipped. The fatal-warning scoped `embedBench/test`
gate on the native producer passed 181, failed zero and skipped one (182 total), followed by
`scalafmtCheckAll`. Later Python-only clock validation and tied/missing-channel pool checks pass;
regenerated embedding input and source evidence are byte-identical to the original frozen files.
Two compiled Python mutants each produce one named failure: inclusive/touching overlap (10
siblings pass in the then-11-test suite) and removed clock-scale guard (12 siblings pass in 13).
Both scripts were restored byte-for-byte. Independent tooling review PASS covers exact revision
`8a57524092fd4068bf430f8bd0dd5e7aa121e0ad`; final documentation/result-transcription review is
recorded in the local receipt. No public API or production behavior changed, no sibling consumes
this test-only object, and no source/participant text or media entered Git. No merge or push.

Reproduction/evidence: `.agent-work/audiovisual-20261021/experiment/` contains original input,
native outputs, all scores/ranks, frozen blind pool and AV labels, secondary human labels, caption
input checks, synthetic results and `support-summary.json`. The executable local analysis is
`.agent-work/audiovisual-20261021/summarize_support.py`; its hash and all label/rank/pool hashes are
bound in the summary. `verification-before-judgments.json`, both mutation receipts, native/gate
logs and the final review receipt remain at the parent artifact root.


### Owner clarification: mixed true/false recollections require claim-level assessment

The owner emphasized that false and partially false memories are central, not edge cases. The
full-unit supported/not-supported retrieval diagnostic above is deliberately strict but is not a
veridicality measure. It cannot answer how much of a compound recollection is correct, and its
partial/unknown labels must not be renamed false memory.

The required general separation is event correspondence versus claim-level veridicality. A recall
can refer to the right source event while misstating a participant, object, state or causal detail.
Preserve that event correspondence and separately assess each expressed claim, including polarity,
modality, alternatives and temporal/reference context. Contradicting one facet must not erase
supported facets or force the whole unit to a non-source destination. A contradictory facet can
still help distinguish the intended event from unrelated events; localization is not certification.

For each recall claim and candidate event, retain support, positive counterevidence and unresolved
status with actual source provenance and interval/reference scope. Lack of depiction, absent
captions, uncertain ASR, unresolved pronouns or a truncated sample is unresolved source evidence,
not contradiction. A character's report, suspicion or later retraction retains its context; it does
not silently establish a root-world fact. Disjunctions and uncertainty must not be strengthened into
assertions during claim extraction. Several compatible source intervals may jointly support an
event, while repeated descriptions of one utterance remain shared evidence.

Evaluate event retrieval and claim assessment separately. The next reference set needs correct,
partly contradictory, wholly contradictory and unresolvable recall variants about the SAME events,
plus plausible but unrelated events and legitimate reordered recall. Freeze researcher judgments
at the claim level without showing ranks, allow alternate event references, and retain unresolved
claims in denominators/coverage. Measure false contradiction and false support separately from
event correspondence, alongside partial factual coverage and the causes of unresolved assessment.
A lower complete-unit hit count is not itself evidence of worse memory or more intrusions.

No measured support/contradiction score becomes a calibrated probability, clinical inference or
participant trait without its own qualification. A learned local critic can propose claim links and
evidence quotations, but proposals and matching quotations are not a semantic guarantee. Test its
claim extraction, evidence ownership, contextual reasoning and verdicts independently before
allowing those outputs to affect veridicality. This is the next design constraint, not an executed
claim-level implementation or an accuracy result from the current embedding experiment.

### Executed first milestone: attributed claim assessment

Child `bd-01M5FGET6020C9B1BC2YCBKY52` implements an additive `ClaimAssessmentReceipt` in
`align`, under the owner-authorized amendment to ADR 0022. A receipt preserves the original
`MappingResult` and separately records a fixed question population, reviewed recall spans,
claim spans, source citations, chart references, assessor/procedure attribution, support,
counterevidence and unresolved reasons. Its checked constructor verifies ownership and complete
accounting. It does not certify semantic truth, successful extraction or assessor identity.

Each question has one derived status. Evidence supporting and contradicting a claim remains
separately inspectable; conflicts cannot cancel into a resolved verdict. Incomplete context can
retain supporting evidence while remaining unresolved. An unresolved participant can retain an
expressed action and its evidence. Reviewed nonexpression, failed extraction and unavailable
source evidence are different states. All declared questions stay in the population, including
unresolved cases. Repeating a citation does not multiply question credit. Counts are descriptive
accounting, not calibrated veridicality or participant accuracy.

The independently reviewed synthetic diagnostic in `ClaimAssessmentContrast` uses a deliberately
narrow, test-only comparison rule: exact canonical proposition, supplied event/entity identity,
one atomic root assertion and explicit polarity. Identical assertions support; opposite explicit
polarity on the same proposition supplies counterevidence. Other predicate/participant mismatch,
unknown identity, different event, embedded content and unmodeled alternatives abstain. Paraphrase
equivalence and correspondence are supplied, not extracted. The disjunction control checks
abstention on unmodeled content; the perhaps control deliberately withholds chart polarity and
does not qualify modality interpretation.

All 17 frozen expected outcomes match: three supported, two contradicted, eight unresolved, and
one each conflicted, reviewed-not-expressed, extraction-unresolved and reference-unresolved.
There are 15 expressed questions and five resolved questions. One compound recollection has a
supported clause and a contradicted clause assigned to the same recall unit and source event,
with distinct claim spans and unchanged event mapping. These results establish controlled
mechanism behavior, not an improvement on Sherlock or another corpus.

The local artifact `.agent-work/claim-assessment-20261021/controlled-report.json` contains the
complete synthetic mapping, question rows, citation support, chart owners/elements/provenance,
recall scopes, supplied correspondence and procedure attribution. `controlled-report.md` provides
a readable table. The checked-in diagnostic regenerates the JSON; this local report format is
not a new production interchange contract. No source-media or participant transcript entered Git.

The next acceptance boundary is automatic evidence construction. Freeze a source/recall pool and
independent claim judgments before comparing extraction/caption candidates. Compare audio-only,
visual-only, their combination, and optional human annotations against the same questions; keep
missing extraction and source evidence visible. Measure false support, false contradiction and
resolved coverage separately, then test cross-shot event/reference binding. Only after that
comparison succeeds should a candidate face held-out participants and movies. The parent accuracy
issue remains open; this milestone does not change the production scorer or captioner.

### Second milestone: automatic proposals over retained audiovisual evidence

Child `bd-01M5FKA73X997XYKW332666MVS` adds an offline question-conditioned critic experiment
and a test-only consumer of its proposals through the real `ClaimAssessmentReceipt`. The source
revision is `1d98aff7aaae29ff2b3fec83a543812b7ef8fe4d`. The experiment supplies focal claims and
fixed candidate events; original recall and media coordinates remain provenance. It does not
extract free-form recall, run production localization, change captions or admit root-world truth.

The primary population has 48 questions: one independently supplied focal claim for each of the
24 earlier recall units, and 24 authored supported/opposite/added-detail variants over all eight
earlier AV-supported pairs. Natural targets were selected from each existing pool by the minimum
SHA-256 of the fixed seed, ordinal and row, without consulting prior support labels. The eight
controlled anchors belong to five recall units; observations are dependent and same-film
exploration, not held-out participant or movie evidence. Eight known body questions are a separate
diagnostic. Audio, caption text and their exact union produce 144 primary and 24 diagnostic
executions. Human annotation is not an input arm in this milestone.

The independent reviewer froze two reference layers before inference: sampled original frames
plus time-qualified ASR assertions, and the exact text available to each arm. ASR assertions are
not a listened verification of the audio or independent world truth. All 34 selected image hashes
were verified. Common-source primary labels are 9 supported, 8 contradicted and 31 unresolved.
The input-layer references resolve only 2/48 audio, 2/48 caption and 4/48 combined questions, with
equal support/contradiction counts in each arm. Thus this set mainly tests abstention and
unsupported inference; the small resolved subset cannot establish broad positive accuracy.

The pinned local Qwen3-VL-4B model runs text-only with deterministic seed/temperature and explicit
input/output limits. Complete ASR segments retain decoder suspicion and central word timings;
timings appear only in audio arms. Exact quotations must belong to each question's allowed items.
Missing evidence, uncertain reference/context and processing failure stay distinct. Every question
remains in the denominator. The Scala consumer independently verifies input ownership, raw-to-
normalized quotation agreement and UTF-16 spans, then exports attributed proposals with unchanged
mapping and question-population digests across arms. Passing these checks proves provenance and
accounting, not entailment.

Pre-inference review repaired omitted word timings and an experiment-folder file-binding gap.
A later parent check found control labels embedded in model-facing case IDs. That partial run
was stopped at 105/168 and is inadmissible; only processing counters had been inspected before
repair. All attempts remain in the local audit trail. The corrected experiment uses full SHA-256
claim IDs and a four-field model-input whitelist, independently inspected across all 168 inputs.
Claims, evidence, prompt and reference judgments were unchanged. Only `experiment-04` supplies
admissible critic results; earlier attempts must not be pooled with it.

The pinned MiniLM control uses identical claim and evidence-item texts, without critic timing
metadata or any fitted threshold. Its byte-identical input allowed reuse after the ID repair.
Of 168 comparisons, 167 have observed vectors and one has no document text. In 5/24 controlled
polarity comparisons, the opposite wording has higher cosine than the supported wording. This
characterizes cosine ordering; it is not a numerical accuracy comparison with a semantic classifier.

The admissible run completed all 168 executions in 893.11 seconds. The native consumer built
three receipts with all 56 questions and identical mapping/population digests. Primary results
against the input-layer references are:

| Arm | Questions | Correct resolved | Unsupported support | Unsupported contradiction | Unresolved | Processing failure |
|---|---:|---:|---:|---:|---:|---:|
| Audio | 48 | 1 | 0 | 0 | 38 | 9 |
| Caption | 48 | 2 | 2 | 2 | 30 | 12 |
| Audio + caption | 48 | 2 | 0 | 0 | 12 | 34 |

Unsupported resolution means deciding a claim whose input reference is unresolved; it does not
mean that the inferred fact is false in the movie. There are no opposite resolved-reference
verdicts in this run. The four unsupported primary caption decisions concern supported/opposite
variants at rows 67 and 313: generic object becomes pill, and generic vehicle/emergency lights
become police vehicle. They agree with the original-frame reference while exceeding the caption
text. Exact quote ownership did not guarantee entailment. No natural claim received a resolved
verdict, and no accepted unresolved proposal retained a support or counterevidence quotation.
Thus the model did not demonstrate useful partial-evidence retention.

Across primary and diagnostic populations, 65/168 outputs are processing failures, all rejected
proposals. The first validation failure is unresolved-reason shape in 63 and claim identity in 2;
64 outputs use reason objects rather than the required strings, including one identity failure.
These failures remain in denominators. Combined primary input fails protocol in 34/48 cases, so
this run cannot establish that adding audio helps semantic assessment. The five correct primary
input resolutions recover five of the eight resolvable arm/question comparisons; all nine accepted
primary resolutions agree with the common-source reference. Neither count establishes general
accuracy, given missing text evidence, failures and dependent authored controls.

In the separate eight-question body diagnostic, audio yields five unresolved and three failed
proposals; caption yields two supported, four unresolved and two failed; AV yields three unresolved
and five failed. Caption supports `d507-lying` and `d507-floor`, agreeing with sampled frames while
exceeding the caption's participant identification. At part-2 row 507 (110–111 s), the caption does
retain posture/clothing/floor, but its person linkage is not established. Row 526 (152–155 s)
retains a generic person on the ground while losing distinguishing detail. Row 503 (84–94 s)
remains a sampling limitation rather than a demonstrated caption omission. The scoped utterance-
content claim at row 575 (249–251 s; ASR segment 31 at 250.02–250.96 s) is recoverable in A/AV
reference input, but the critic leaves it unresolved in A and fails protocol in AV. Cross-shot
identity never resolves. This is not a repaired, evidence-bound direct hit for the complete event.

Decision: do not promote this critic into the production scorer. The next bounded intervention is
a separately tested serialization repair or constrained output protocol, preserving identity and
quotation checks and this run's original failure counts. Then compare original-frame assessment
with caption text on fixed claims/intervals/ASR to test recovery of omitted details. Any reprocessing
of these exposed outputs is a new post-hoc development condition, not a replacement for this
frozen result. Event correspondence, partial factual support and veridicality must remain separate.
Held-out participants and movies remain necessary for a claim of overall improvement. The parent
accuracy issue stays OPEN.

Verification: the fatal-warning scoped `embedBench/test` gate passed 195, failed zero, errors zero,
skipped one (196 total). The skipped existing neural-channel integration test is named in the gate
log. Python passes 14 tests. A compiled Python blinding mutant fails its one named regression with
13 siblings passing; a compiled Scala request-identity mutant fails its named assertion with eight
siblings passing, followed by exact source restoration and the green gate. All executed results are
local observations. No production API or behavior changed in this milestone, no sibling consumes
the test-only adapter, no corpus text/media entered Git, and no merge or push was performed.

Evidence is under `.agent-work/automatic-claims-20261021/`: `experiment-04/report.md` and
`summary.json`, frozen inputs/references, full raw model outputs, native `claim-receipts.json`,
embedding reuse proof, interrupted-attempt receipts, mutations and build logs. `analyze.py` checks
exact unique populations, request/input hashes and every valid Python/Scala status match; its hash
is bound into the summary. Independent review recomputed all arm counts and assessed resolved
errors against both reference layers. Final formatting, exact-revision review and Git state are
bound in `verification.json`. Nonblocking strict-JSON consumer hardening is tracked separately as
`bd-01M5FMPM0F5Y22EKGYZF05DRSD`.

### Third milestone: separate protocol, source information and assessor capability

Child `bd-01M5FQFMG4JSDVWZV6J6XN2WXS` retains the preceding experiment and adds explicitly
attributed development conditions. The same 56 questions, authored candidate assignments and
three input arms remain fixed. Here A means timestamped ASR, V means generated caption text,
and AV means their union. These conditions do not include human annotations, original frames
as assessor input, or a search of the complete film. Independent agent judgments of the original
frames and exact supplied text remain evaluation references, not information given to the critic.

The protocol replay accepts only exact singleton `{"reason": "known_enum"}` objects as wire
equivalents of the required reason strings. Original raw outputs and processing failures remain
recorded. Unknown reasons, extra keys, wrong claim identities and invalid quotations still fail.
This recovers 63 original responses, all unresolved: 166/168 become valid and two remain rejected.
It repairs serialization, with no added semantic resolutions. The explicit post-hoc condition is
not a replacement for the original 65-failure result.

The source intervention uses a question-independent frame-local description prompt over the
same 34 intervals and three sampled frames per interval, with the same Qwen3-VL-4B model and
image resolution. Its 512-token output budget is larger than the earlier caption recipe; this is
not a compute-matched comparison. All 34 descriptions complete without truncation or empty output.
The first attempt refused an incompatible frame-bank name before inference; that receipt is retained.
The final input references were independently reviewed and frozen before the new critic run.

The new descriptions remove the false cross-image person linkage at row 507, but retain only a
generic prone person at rows 507/526. They add a tentative small-object interpretation at row 67,
lose speckles at row 82, and incorrectly add a wheelchair at row 139. These are mixed caption
changes, not a demonstrated fidelity improvement. The exact-input reference labels gain no fully
resolvable questions. The retained samples and ASR do not independently establish the cross-shot
person link needed to join the body depiction to the later death assertion. This is a limit of
these supplied inputs, not a claim that wider footage cannot establish the link.

The new Qwen critic executes 112 V/AV requests; unchanged A responses are reused. The real Scala
consumer produces three receipts of 56 questions each. After the same protocol replay, 163/168
responses are valid and five fail: two wrong claim identities and three invalid quotations.
The latter are absent from, or not uniquely locatable in, the cited input. Across the 48 primary
questions per arm, exact-input references permit two A, two V and four AV resolutions:

| Qwen input condition | Correct resolutions A / V / AV | Unsupported resolutions A / V / AV | Processing failures A / V / AV |
|---|---|---|---|
| Original captions, protocol replay | 1 / 2 / 2 | 0 / 4 / 0 | 0 / 1 / 1 |
| Frame-local captions, protocol replay | 1 / 2 / 0 | 0 / 0 / 0 | 0 / 2 / 3 |

Reduced unsupported resolutions coexist with lost correct AV contradictions. One becomes a
quotation failure; the other abstains despite unchanged decisive audio. All natural claims remain
unresolved, and no accepted unresolved response retains partial supporting or counterevidence
links. All eight body diagnostics remain unresolved in the new condition, including the directly
available death-assertion content. Therefore richer descriptions alone have not repaired assessment.

The encoder control compares pinned MiniLM and `BAAI/bge-small-en-v1.5` on the exact same claim
and evidence texts, with their respective masked-mean/CLS pooling and token limits. No query
instruction or threshold is fitted. MiniLM Python vectors reproduce the existing Scala output
over all 154 records, with maximum component difference `2.43e-7` against a frozen `1e-5` tolerance.
The alternative encoder shows no benefit on the four controlled polarity comparisons resolvable
from input text: both encoders have zero inverted orderings with the original captions and one
with the longer captions. Both lose the same AV ordering despite unchanged decisive ASR. The full
24-comparison inversion counts are 5/10 for original MiniLM/BGE and 7/12 for new MiniLM/BGE, but
most of those facts are absent from the supplied text; those counts are descriptive cosine
ordering, not entailment error rates. New-caption MiniLM also refuses five oversized inputs;
coverage is 162/168 versus 167/168 for the other conditions, with one empty document in all four.
No retrieval stage is changed or evaluated by this fixed-pair experiment.

Local evidence is under `.agent-work/scoring-factorial-20261021/`: `plan-freeze.json`,
`baseline-v2/`, `atomic-02/`, `atomic-critic/`, `atomic-v2/`, the independently reviewed
`reference-atomic-input-02.json` and `caption-fidelity-02.json`, four encoder freezes/results,
`comparison-summary.json`, and execution logs. Earlier provisional caption references are retained
but superseded and unused by the critic. The analysis verifies exact text-to-vector bindings and
fixed questions/candidate assignments; source changes legitimately change mapping/population
digests. A compiled negative witness swaps two valid document IDs without changing populations
and fails the named text-binding assertion, while the real inputs pass all 96 encoder contrasts.

#### Stronger-assessor comparison: DeepSeek V4.1 Flash

At the owner's request, the same semantic prompt and exact evidence inputs were sent to
OpenRouter model `deepseek/deepseek-v4.1-flash-1017` (canonical dated slug
`deepseek/deepseek-v4.1-flash-20261017`). The frozen route permits only Fireworks, with zero-data
retention, data collection denied and no fallback. The recipe uses low reasoning, temperature
zero, a 4,096-token completion budget, four concurrent requests and one attempt per input.
This is an assessor-package comparison: model, provider, reasoning and token budgets differ
from Qwen. It does not isolate parameter count or establish repeat-run stability.

All 280 unique requests completed: 168 original-text requests and 112 new-caption V/AV requests;
the 56 original A results are reused in the second condition. Reported cost for every request
totals USD 0.354744252, below the frozen conservative USD 1.7146338 bound and USD 3 budget.
Two successful synthetic endpoint checks separately report USD 0.0003828. Neither reference
labels nor original frames, manual annotations, complete recall transcripts or participant
identifiers are model inputs. Exact request/body hashes and provider/model identity are retained.

Both derived conditions use the same narrow protocol replay as Qwen and the unchanged real Scala
consumer. Original-caption responses are 155/168 valid before replay and 168/168 after it.
New-caption responses, including reused audio, are 154/168 before replay and 165/168 after;
the three remaining failures have invalid quotations. All failures stay in the denominator.
Primary exact-input results, with the same 2/2/4 resolvable questions per A/V/AV arm, are:

| DeepSeek input condition | Correct resolutions A / V / AV | Unsupported resolutions A / V / AV | Processing failures A / V / AV |
|---|---|---|---|
| Original captions, protocol replay | 2 / 1 / 3 | 0 / 1 / 0 | 0 / 0 / 0 |
| Frame-local captions, protocol replay | 2 / 1 / 3 | 0 / 0 / 0 | 0 / 1 / 2 |

No model gives an opposite verdict on an input-resolved reference, and no Conflicted verdict is
produced. DeepSeek's remaining unsupported original-V contradiction concerns a police-car claim
when the caption supplies only an unspecified vehicle with emergency lights. Missing participant
or object identity still prevents a definite contradiction. No natural claim becomes fully resolved.
The primary results favor DeepSeek's audio handling while retaining a visual over-abstention:
Qwen resolves both available V questions, whereas DeepSeek resolves only one in each condition.

In the separate body diagnostic, DeepSeek recovers the death-assertion question in A and AV for
both source conditions, citing the time-qualified ASR segment within the selected interval.
Qwen leaves it unresolved even after protocol replay. This is recovery of an assertion in ASR,
not listened audio verification or proof of death. DeepSeek retains posture/floor evidence and,
in one new-caption AV proposal, separately cites depiction and assertion while leaving their
cross-shot identity unresolved. It therefore improves evidence use without completing the
evidence-bound match for the entire recalled event.

DeepSeek retains links in 21 original-caption and 17 new-caption unresolved proposals, compared
with Qwen's one and zero respectively. These counts include diagnostic cases across V/AV arms;
they are not independent cases or counts of verified partial claims. Independent inspection finds
useful posture/floor and tentative-object evidence, but also generic overlap, unresolved people,
the original caption's false cross-image linkage, and a reported inference that cannot establish
a person's present state. Literal quote ownership is necessary and insufficient for semantic support.

Decision: use the stronger assessor for the next development comparison; do not promote either
caption rewrite or assessor directly into production. The next evidence condition should combine
captions and timestamped ASR with optional manual annotations and relevant surrounding event
context, retaining source identity, temporal scope and disagreement. Manual annotations are an
additional fallible source, not a prerequisite or automatic truth override. Compare automatic-only
and annotation-augmented conditions separately. Explicitly test cross-shot person/event links,
partial false memories, wrong-event neighbors and unsupported contradiction. Any full-event
acceptance still requires those links and independent held-out evaluation; the parent accuracy
issue remains open. The current experiments establish neither improved retrieval nor overall
accuracy across participants or films.

Additional evidence under the same local root: `deepseek-01/` (freeze, exact model metadata,
raw responses and costs), `deepseek-old-v2/`, `deepseek-atomic-v2/`, `assessor-comparison.json`,
`prepare_remote.py`, `analyze_assessors.py` and consumer logs. The independent reviewer reproduced
the counts and inspected resolved and linked unresolved proposals against both reference layers.
Python checks pass 26 tests, fail zero and skip zero; two earlier compiled mutants each fail their
named regression with siblings passing. A malformed-response regression retains a failed request
and a successful sibling. The unchanged Scala consumer builds all four three-arm conditions with
56 questions per arm; its focused suite passes nine, fails zero and skips zero. These are local
observations. Final revision, format checks, evidence hashes and independent review are recorded
in the local `verification.json`. Production behavior, `main` and remote publication are unchanged.

#### Context and optional-annotation comparison

The next fixed-candidate experiment preserves the original 56 questions (48 primary plus eight
body diagnostics) and every focal interval and word coordinate. Three new AV conditions add
focal manual annotations, automatic evidence within same-part ±90-second windows, or both
context and annotations. The original automatic AV condition is reused. Context records retain
their own timestamps and source identities; they never enlarge the focal target. Selection uses
positive temporal overlap, not claim wording or reference labels. Complete records are retained,
with optional manual annotations explicitly treated as fallible assertions. One zero-duration
source description is refused without removing a focal row or unique ASR segment. The retained
1,000-row source cut and its segmentation are still human-derived.

An independent agent read the selected evidence and froze all 168 condition-specific judgments
before inference. These assess support in supplied source assertions, not verified footage truth.
The context-plus-annotation references contain an explicit body/examination/death-statement
identity chain; the automatic-only records do not establish that chain. One other contextual
statement becomes supportable outside its focal interval; it cannot count as recovered focal
localization. The previous common-reference layer has narrower coverage and is not complete
negative gold for these expanded inputs.

The model, Fireworks-only route, prompt, temperature, low reasoning setting and 4,096-token
completion budget remain unchanged. Primary results are:

| AV evidence | Input-resolvable / 48 | Correct resolutions / 48 | Unsupported or opposite resolutions | Processing failures / 48 |
|---|---|---|---|---|
| Focal automatic, reused | 4 | 3 | 0 | 0 |
| Focal plus annotations | 9 | 5 | 0 | 0 |
| Broader automatic context | 4 | 3 | 0 | 2 |

The two additional correct resolutions concern opposite formulations of one police-car event,
not two independent events. No natural primary claim is fully resolved. The frozen police-car
positive reference is sensitive to an unexplicit marked-vehicle adjective; its unresolved result
is not an unambiguous semantic error. Agent references and dependent controls cannot establish
held-out accuracy.

In the separate body diagnostics, focal annotations let the assessor resolve the prone woman's
posture. It still leaves the floor-specific claim unresolved despite its joined caption/annotation
support. The death assertion remains correctly resolved, as in the automatic baseline; cross-shot
identity remains unresolved. Broader automatic context adds no diagnostic resolution and incurs
one completion-length failure. The full context-plus-annotation outcome is **inconclusive**:
49 of 56 requests received HTTP 429, one exhausted the completion budget, and six valid results
were unresolved. Every body diagnostic failed at transport, so this condition says nothing about
whether the assessor can use the available annotation identity chain.

A separately frozen full-population recovery preserved the original results and exact input
order, used serial execution with at least five seconds between starts, and stopped on its first
429. It attempted three requests: one valid unresolved result, one length failure and one 429;
the other 53 are explicitly unattempted. No outcomes are stitched across runs. Operational losses
are reported separately from semantic regressions. The recovery also leaves all body diagnostics
unmeasured. Further remote execution requires renewed route availability or a separately qualified
route; this experiment is not grounds to promote the assessor into production.

Eight wholly invented controls all pass: explicit versus absent identity links, distinct people,
focal versus outside-focal depiction, conflicting sources, a partly supported compound memory,
and explicit negation. The compound case retains partial support while remaining unresolved.
Nevertheless, independent inspection finds incorrect partial evidence polarity: compatible color
and speckling descriptions are treated as opposing evidence. Other partial links confuse an
ambiguous transition with flashing lights, lack participant binding, or cite later posture for a
focal claim. Exact quotation ownership and an unresolved final status do not make these links
semantically sound. The next semantic change should assess each claim component and require
actual incompatibility about the same entity/event before assigning counterevidence; supported
components, missing bindings and false additions must remain distinguishable.

There were 179 actual calls, with 129 priced responses reporting USD 0.754598976. Assigning all
50 unpriced HTTP failures their full request bounds gives a post-run accounted upper bound of
USD 2.705561976; unattempted records cost zero. The recovery's prospective cumulative bound was
USD 4.821619908, within the authorized USD 5 cap. These are different quantities, not alternative
estimates of one measured bill. Both original and recovery failures remain in their populations.

Evidence lives under `.agent-work/context-evidence-20261021/`: frozen inputs and references,
original and recovery remote artifacts, separately normalized conditions, native receipts,
`comparison.json`, `comparison-recovery.json`, `report.md`, and `verification.json`. Python checks
pass 31, fail zero and skip zero. A compiled same-part-guard mutant fails the named scope test
with four siblings passing. The unchanged Scala consumer accepts all four new condition artifacts,
each with three receipts and 56 questions per arm; unchanged A/V controls are explicitly reused.
Independent review verified reference ownership, outcomes, costs and partial-link limitations.
This is local experiment evidence; the parent accuracy issue remains open and no production
scorer or remote publication is changed by this slice.

#### Component and frame-observation experiment (2026-10-22)

The four authorized changes are implemented as experimental Python tools: fixed component
assessment, explicit entity/event/context joins, positive incompatibility requirements, and
question-independent per-frame observations with separately attributed sampled associations.
An independently checked inventory retains all56 original parents and108 components. It does
not establish automatic recall decomposition. Local proposition evidence and attribution to the
recalled referent are distinct fields. Correct details can remain inspectable when another
detail is unknown; incompatible attributes are not automatically same-component conflict.

After Fireworks capacity failures, a separately frozen Together route used the same dated
DeepSeek model with no fallback and the existing privacy/price restrictions. A same-route old
whole-claim control was included. Component requests allow8192 output tokens versus4096 for
the old protocol, so this is a protocol/compute comparison. All176 executions were attempted:
166 valid,7 unfinished/nontext responses and3 invalid proposals. Failed requests remain in
their original populations; no successes are stitched across attempts.

| Component condition | Parents | Components | Input-resolvable | Correct resolutions | Wrong resolutions | Failed parents |
|---|---:|---:|---:|---:|---:|---:|
| Automatic focal, primary |48|87|20|13|0|1|
| Focal plus annotations, primary |48|87|37|7|1|4|
| Automatic focal, body diagnostics |8|21|8|6|0|0|
| Focal plus annotations, body diagnostics |8|21|14|11|0|0|
| Context plus annotations, body diagnostics |8|21|21|8|0|4|

None of the24 natural primary claims gains a correctly resolved component; the primary gains
come from authored controls. Counts above measure components against independently reviewed
input assertions, not fully correct memories or verified footage. Separate joint whole-claim
judgments were frozen before semantic outcomes were inspected. On the48 primary parents,
component all-supported has0 correct and1 unsupported positive resolution; the same-route old
whole protocol has2 correct and0 unsupported positives. The protocol does not qualify for
production promotion or a claimed improvement over the earlier five whole-claim resolutions.

The focal annotation condition does resolve the floor-specific body claim that the old whole
protocol leaves unresolved. However, independent review identifies three general failures:
negative claims can bypass the incompatibility guard by entering on the support side; unknown
modifiers contaminate referent assignment and suppress correct local details; and requiring
every role to be quoted in every source record rejects complementary annotation/ASR evidence.
The next bounded revision should test polarity-equivalent evidence requirements, separate
referent assignment from modifiers, and use explicit connected assignments across complementary
records. Different-people and wrong-event falsifiers must remain. Larger output budgets alone
do not repair these semantic problems.

Automatic acquisition used the pinned local Qwen visual model without recall, ASR or manual
annotation in its prompts. The full34-row run yielded95/102 valid observations and43/68 original
valid sampled associations. Four associations are subsequently refused for definite-different
decisions with an insufficient basis. Continuous visual tracking is unavailable in this probe:
sampled-pair same/different/unknown/empty proposals cannot establish intervening continuity.
Raw outputs and refusals are preserved separately.

Independent pixel review covers only12 body frames. It finds the floor/body detail, but also
orientation errors and an incorrect different-person association for the same body. A separate
adaptive higher-pixel run restores some usable observations but moves an orientation error and
retains the bad association; it establishes no consistent visual improvement. Those adaptive
outputs are never substituted into the complete low-pixel condition.

The final automatic-only assessor comparison preserves ASR and has no manual annotations:
observations alone credit2/7 input-resolvable components with2/8 parent processing failures;
observations plus sampled associations credit6/7 with1/8 failure. Each retains21 components
and has zero hard reference-status errors. No valid evidence link cites an association item,
so this difference is not evidence of successful tracking. One reference-status success has a
weak chosen quote despite support elsewhere in its complete source record. The new inputs
permit lying/floor credit, but gender, death attribution and cross-shot identity remain unresolved.

Targeted Python checks pass32, fail0, skip0 (component16, frame11, remote5). Compiled mutations
exercise quote ownership, source coverage, compatible-property rejection, join attribution and
both continuous-track refusal paths, with named failing witnesses and sibling controls. Exact
independent code/outcome review and final-reader replay are retained. There are no Scala/API
changes or new native consumer receipts; this is a tools-only local gate, not hosted CI.

This round attempted223 remote calls. The221 priced responses report USD0.986319396; assigning
the2 unpriced HTTP failures their full bounds gives USD1.009014996 accounted upper cost. The
conservative attempted-request bound is USD2.6004756, within the authorized USD5. Unattempted
records cost zero. Raw data remain ignored under `.agent-work/facet-assessment-20261022/`, with
frozen inputs, references, original outputs, comparison scripts, cost accounting, `report.md`
and `verification.json`. The branch remains local and the parent accuracy issue stays open.

#### Connected evidence and symmetric polarity experiment (2026-10-23)

The successor separates three questions: which source participant a recall argument refers to,
which predicates each source supports, and whether the recall affirms or denies the resulting
proposition. Neutral argument labels replace modifier-bearing role descriptions at the assessor
boundary. Typed, cited correspondences connect source entities, situations, contexts and utterance
occurrences. Complementary records may cover different roles; their union must cover the component,
and repeated assignments must be connected. Person identity alone does not establish a shared time
or utterance. These checks establish ownership and permitted composition; the model's semantic
judgments and correspondence proposals remain fallible.

The assessor sees a canonical complete proposition without the recall's outer polarity. Only
after it proposes affirmation or denial does the reader map those findings back to the recall.
Positive incompatibility requirements therefore apply equally to contradicting a positive recall
and supporting its negative counterpart. Compound and embedded scope remains intact, and unknown
normalization retains proposals without resolving them. All56 parents and108 components remain
fixed; the additional metadata was independently reviewed without source evidence or answer labels.

The first12 synthetic checks yielded9 exact outcomes, with one invalid proposal and two scope/join
abstentions. A prompt-only clarification of literal enum names, evidentiary context and required
cross-component connections then yielded12/12 exact outcomes over10 distinct model inputs. These
invented controls contain explicit uncertainty cues and demonstrate protocol use, not realistic
accuracy. Both trials are retained. The initial4096-token real-data run was interrupted after17
completed responses:9 valid and8 unfinished. One additional possible serial request is charged its
full bound;102 remaining requests are explicitly unattempted. The complete120-request comparison
was restarted at8192 output tokens, matching the previous component baseline, with unchanged
requests, prompt and judgments. Earlier successes are not substituted into this full run.
