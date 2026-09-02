# Recall-to-video study harness

Development-only iteration on the recall-to-video mapping, under the study plan
(`docs/plans/2026-09-02-recall-to-video-study-plan.md`).

- `partition.json` (in the ignored `tmp/study/`) freezes the 11 development and 6 untouched-test
  participants, drawn by seed 20260902 before any arm was compared.
- `run-arm.sh ARM PARTITION` runs one configuration over one partition.
- `score.py` reports the gold-free outcomes with a seeded participant bootstrap, and
  `score.py --compare` gives paired per-participant differences.
- `matched.py A DIR_A B DIR_B` repeats that comparison on the units whose anchor stayed at the leaf
  level in both arms, which is how an apparent ordering gain is told apart from a shift to coarser
  anchors.

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
