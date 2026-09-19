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

## The primary outcome is cross-participant agreement

`agreement.py A DIR_A B DIR_B` is the judge of record. Seventeen people watched the same film, so
when two of them describe the same moment a correct mapping puts both descriptions in the same
place. Units are paired *across* participants by mutual-best IDF overlap of the recall text alone, so
the pairing is identical for every arm and no arm can change which comparisons it is scored on.

**Concentration and localizability are demoted to diagnostics and may no longer choose an arm.**
Both measure how peaked the posterior is, so any stronger prior improves them whether or not it is
right. Measured: as the ordering prior strengthens they rise monotonically and unanimously all the
way to scale 8, while agreement peaks at 1.5 and by scale 8 is *worse than doing nothing*. A model
can top both while getting further from the truth.

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
