# Recall-to-video study harness

Development-only iteration on the recall-to-video mapping, under the study plan
(`docs/plans/2026-09-02-recall-to-video-study-plan.md`).

Scientific interpretation and the current delivery sequence are governed by
[`docs/refactor/PLAN.md`](../../docs/refactor/PLAN.md). Earlier arm-selection
claims below are historical study notes, not measurement validation.

Inputs and outputs live under the local data root (`tools/data-root.sh`; layout in `data/README.md`),
one copy shared by every worktree; the study record is `data/study/recall-to-video/`.

- `partition.json` (in the study record) freezes the 11 development and 6 untouched-test
  participants, drawn by seed 20260902 before any arm was compared.
- `run-arm.sh ARM PARTITION` runs one configuration over one partition.
- `gold_scene.py` scores arms against the scene-level gold under its pre-registration.
- `within_scene.py` is the within-scene precision apparatus under
  `docs/plans/2026-09-03-within-scene-precision-preregistration.md`: `packet` writes the blind
  packet and sealed key, `extract` pulls answers out of a filled packet, `score` joins answers to any
  arm's report, and `diagnose` reports gold-free within-scene diagnostics.
- `voyage/build.py` assembles the Sherlock Recall Voyage page, a self-contained HTML instrument
  (`voyage/template.html` plus one embedded JSON document) showing every participant's recall units
  on the film clock with posterior mass, runner-up, the raw-emission ghost, the gold scene bands and
  a per-unit inspector. It reads the default, monotone and baseline arms from the study record and
  writes beside them; the page carries recall prose and stays outside Git.
- `score.py` reports the gold-free outcomes with a seeded participant bootstrap, and
  `score.py --compare` gives paired per-participant differences.
- `matched.py A DIR_A B DIR_B` repeats that comparison on the units whose anchor stayed at the leaf
  level in both arms, which is how an apparent ordering gain is told apart from a shift to coarser
  anchors.

## Explicit recall-clock intake

`recall_clock.py` is a standard-library Python intake oracle for the
[recall–encoding workflow](../../docs/plans/2026-09-22-recall-encoding-workflow.md).
It checks one CSV against the declared Sherlock lineage, preserves every data
record and selects the released OpenNeuro or Princeton word-onset column. The
clock is required. It does not run alignment or change the existing Scala mapper.

Run the original synthetic example from the repository root:

```sh
python3 tools/recall-study/recall_clock.py \
  --csv tools/recall-study/fixtures/recall-clock/words.csv \
  --lineage tools/recall-study/fixtures/recall-clock/lineage.json \
  --clock openneuro --expect-source recall-source-06 \
  --alias-id recall-alias-05 \
  --alias-map tools/recall-study/fixtures/recall-clock/aliases.json \
  --out /tmp/synthetic-openneuro-clock.json
```

The output's parent directory must exist; an existing output is refused. For an
admitted development CSV, set `RECALL_CSV` and `CLOCK_OUTPUT` to local paths and run:

```sh
python3 tools/recall-study/recall_clock.py \
  --csv "$RECALL_CSV" --clock openneuro --out "$CLOCK_OUTPUT"
```

The default manifest is `docs/data/sherlock/recall-lineage.json`, resolved relative
to the script. The study partition still governs access: this helper does not
authorize opening untouched-test transcripts. `--expect-source recall-source-NN`
adds an identity assertion; `--alias-id` and `--alias-map` optionally verify a
convenience alias together. Canonical source IDs, aliases and BIDS subject IDs are
different coordinates. No mapping is inferred from a filename or its number.

The versioned JSON contract is
`storymodel4s.sherlock.recall-clock-intake/v1`. It is an experimental intake
artifact for adapter validation, not the public mapping-result wire format.

| Field | Meaning |
|---|---|
| `identity` | Declared artifact set and canonical source, exact CSV digest/length, lineage digest and optional checked alias/map digest |
| `clock` | Explicit release column in seconds; `appliedOffset` is zero because the selected column is copied directly |
| `recordCoordinates` | One-based CSV data-record positions, excluding the header; these are not canonical word IDs |
| `records` | Every record's six numeric cells, typed missingness, text-presence flag and selected onset; no recall prose |
| `counts` | All records, empty text records, missing numeric cells and missing selected onsets |
| `offsetDiagnostics` | Exact rational minima/maxima of observed OpenNeuro-minus-Princeton differences, with paired/unpaired counts; no correction is applied |
| `capabilities` | Explicitly unavailable word offsets, scanner crosswalk and canonical word-inventory join |
| `producer` | CLI script digest, allowing a consumer to bind the implementation |

Observed numeric values remain decimal strings, with surrounding whitespace
removed. Missing cells remain missing, including a missing selected onset; zero
is an observed value. Finite negative onsets are preserved. TR numbers must be
integer-valued, but are **not verified zero-based BIDS volume indices**. Numeric
text is bounded to 128 coefficient digits, an absolute exponent of 128 and 512
characters; unsupported representations produce a located refusal, never a
rounded substitute. The limits are recorded in `numericTextLimits`.
JSON integers are limited to 128 decimal digits before conversion; decoder
failures remain structured and content-free.

The manifest establishes a declared byte identity and column interpretation.
Supplying a different manifest does not authenticate a new release. An OpenNeuro
column alone does not establish scan onset, dropped volumes, stimulus/run
boundaries, cartoon transitions or HRF treatment. Those belong to the later
verified scanner crosswalk. Onsets also do not establish word durations.

Successful stdout is a content-free summary with the output digest. Refusals use
exit 2 and an error code, optionally a record/column location; argument errors
use argparse's usage diagnostics. Output publication is complete-file and
exclusive. It never overwrites an earlier result.

```sh
python3 tools/recall-study/tests/test_recall_clock.py -v
python3 tools/recall-study/tests/mutate_recall_clock.py \
  --out /tmp/recall-clock-mutation-evidence
```

The mutation output directory must be new. The court runs restored controls
before and after 12 compiled mutants and requires each named assertion to fail.
The [fixture record](fixtures/recall-clock/README.md) explains the independent
expected values. These checks qualify intake behavior, not mapping accuracy or
temporal calibration.

## Canonical word-clock join

The next independent reader, `recall_word_clock.py`, joins those external clock
records to a pinned G1 word/unit inventory through an explicit exact replay.
It checks UTF-16 coordinates, digests and membership, and reports onset-only
timing with missingness and order diagnostics. See
[the runnable example and structured contract](WORD_CLOCK_JOIN.md).
Structural correspondence and recording identity remain separate claims.

## Frozen engineering baseline

`freeze_baseline.py` records one development participant twice using the current
local ONNX reconstruction path. It clears ambient study settings, refuses sealed
participants, verifies admitted input bytes, and requires exact report, posterior,
stage, voyage and inventory parity. An independent integer-coordinate oracle
checks every annotation row, including rows never selected by inference. It reads
no gold. Raw prose stays under the ignored data root; its manifest contains hashes,
identifiers, coordinates, configuration and command receipts.

```sh
python3 tools/recall-study/test_freeze_baseline.py -v
python3 tools/recall-study/mutate_baseline_checks.py
python3 tools/recall-study/freeze_baseline.py capture \
  --data-root "$STUDY_DATA_ROOT" --out "$STUDY_DATA_ROOT/study/recall-to-video/baseline-capture" \
  --participant NN03_ANT_202231_final
python3 tools/recall-study/freeze_baseline.py verify \
  "$STUDY_DATA_ROOT/study/recall-to-video/baseline-capture/manifest.json" \
  --data-root "$STUDY_DATA_ROOT"
```

Set `STUDY_DATA_ROOT` to the resolved data directory returned by `tools/data-root.sh`.
Commit code before capture; each command and output binds that revision. Run logs
are retained and hashed, but timing and output-path differences in logs are excluded
from byte parity. There is no tolerance for differences in the five artifacts.
Verification uses the original paths recorded in command receipts; it is a local
replay record, not a relocatable corpus package.

The admitted `fixtures/baseline-miniatures.json` supplies original synthetic text
and two-part annotation views with reversals, revisits, external material, unknown
timing, partial support and deliberately distinct argmax/decoded choices. These
are hand-authored contract cases. The Python tests exercise receipt integrity;
they do not run a mapper on these packets or establish behavioral recovery.
The baseline likewise makes no accuracy, calibration or reference-measurement claim.

## A no-op that is checked rather than intended

The lexical blend at weight 1.0 must reproduce the baseline report byte-for-byte, because at that
weight it ranks by the semantic distance itself and the re-ranking is the identity permutation. That
is run as a guard before the sweep. It is worth more than a unit test here: it exercises the whole
path, including table construction, abstention handling and the remapping, against a known answer.

## Cross-participant agreement is a gold-free diagnostic

`agreement.py A DIR_A B DIR_B` compares arms on cross-participant agreement. Seventeen people
watched the same film, so when two of them describe the same moment a correct mapping puts both
descriptions in the same place. Units are paired *across* participants by mutual-best IDF overlap
of the recall text alone, so the pairing is identical for every arm and no arm can change which
comparisons it is scored on.

The study once used agreement as its gold-free primary outcome; it chose the ordering-prior scale.
It is not an accuracy measure. A constant anchor attains a zero gap (the script prints that
control), and in the navigation-ladder readout agreement favoured the content and hierarchy rungs
(median gap 69.5 s against 92.0 s) while their scene-accuracy difference from the full rung spanned
zero (+0.55 points, CI [-1.28, +2.55]; [study log](../../docs/plans/2026-09-02-recall-to-video-study-log.md#navigation-ladder-readout-2026-09-05)).
Scene localization against gold is scored by `gold_scene.py` under [SCORING.md](SCORING.md).

**Concentration and localizability are demoted to diagnostics and may no longer choose an arm.**
Both measure how peaked the posterior is, so any stronger prior improves them whether or not it is
right. Measured: as the ordering prior strengthens they rise monotonically and unanimously all the
way to scale 8, while agreement peaks at 1.5 and by scale 8 is *worse than doing nothing*. A model
can top both while agreement worsens.

**Use the paired bootstrap and the signed-rank, not a sign test.** About 40% of anchors are unchanged
between any two arms, so the median of per-pair differences is 0 by construction and a sign test
throws away magnitude. Using one produced a false null that stood until it was replaced.

## Which levers may be judged by which outcome

This is the discipline that keeps the iteration honest, and it is not symmetric.

**Sequential coherence (Kendall tau) may not judge a change to the transition model.** The HSMM
already carries a sequential prior (`TransitionModel.default`: `DiscourseSuccessor` +1.5,
`Backward` -0.5, `LongJump` -1.0). Strengthening the long-jump penalty would raise tau mechanically,
because tau *is* sequentiality. Tuning a sequential prior to maximise a sequentiality metric measures
nothing. Transition-model work is therefore deferred until adjudicated gold exists to judge it by
retrieval accuracy instead.

**Source-text changes may be judged by tau and by concentration.** Making a segment's embedded text
more discriminative does not mechanically force the anchors into temporal order, so a rise in tau is
evidence rather than an artefact. Candidate-set size is unchanged, so concentration is comparable.

**Candidate-breadth changes may be judged by tau but not by concentration.** Concentration is
posterior mass on the top state; admitting more states spreads mass mechanically. Comparing
concentration across different `perLevel` values compares arithmetic, not quality.

**Any arm that changes how attractive a scene node is must pass the granularity check.** A scene
node and its own leaves compete for the same posterior mass. Make the scene node richer and units
migrate onto it; a scene anchor carries a coarser, temporally smoother time, so Kendall tau rises
for free. `matched.py` re-scores on the units anchored at the leaf level in *both* arms and reports
how the anchor mix moved. This is not a formality: it removed more than half of the scene-caption
arm's headline ordering gain, and it left the lexical blend's gain larger than the headline.

**Every arm reports source mass, and no arm is judged by it alone.** Richer source text absorbs more
recall without necessarily localising it better; that is the verbosity confound, and source mass is
the quantity it moves first.

## Arms

| Label | Change | Judged by |
|---|---|---|
| `baseline` | Coder description alone; `perLevel=8`, lexical overlap off | reference |
| `enriched` | `STORYMODEL4S_SOURCE_TEXT=enriched`: location and characters before the description | tau, concentration |
| `lexical` | `STORYMODEL4S_CANDIDATES_LEXICAL_OVERLAP=true` | tau |
| `perlevel-N` | `STORYMODEL4S_CANDIDATES_PER_LEVEL=N` | tau |
| `caption-*` | machine visual descriptions from the `media` court | tau, concentration, granularity check |
| `digest-scene` | `STORYMODEL4S_SOURCE_TEXT=digest-scene`: the scene's own coder descriptions, sampled and truncated to match a caption | control for `caption-scene` |
| `blend0NN` | `STORYMODEL4S_LEXICAL_BLEND=0.NN`: BM25 re-ranking of the semantic channel | tau, concentration, granularity check |
| `blend*-lemmas` | the above with `STORYMODEL4S_LEXICAL_FIELDS=lemmas` | tau, concentration, granularity check |

Development chooses everything. The untouched set is unsealed once, chooses nothing, and any
outcome that changes a frozen choice contaminates it.
