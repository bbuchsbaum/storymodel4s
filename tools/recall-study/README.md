# Recall-to-video study harness

Development-only iteration on the recall-to-video mapping, under the study plan
(`docs/plans/2026-09-02-recall-to-video-study-plan.md`).

- `partition.json` (in the ignored `tmp/study/`) freezes the 11 development and 6 untouched-test
  participants, drawn by seed 20260902 before any arm was compared.
- `run-arm.sh ARM PARTITION` runs one configuration over one partition.
- `score.py` reports the gold-free outcomes with a seeded participant bootstrap, and
  `score.py --compare` gives paired per-participant differences.

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
| `caption-*` | machine visual descriptions from the `media` court | tau, concentration |

Development chooses everything. The untouched set is unsealed once, chooses nothing, and any
outcome that changes a frozen choice contaminates it.
