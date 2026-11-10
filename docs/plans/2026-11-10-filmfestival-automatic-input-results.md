# FilmFestival automatic-input condition: results (2026-11-10)

Run under the [automatic-input protocol](2026-11-10-filmfestival-automatic-input-protocol.md):
description freeze 3c6b4442d, mapping freeze 8515bdb6a. Score and comparison files are in
[the evidence directory](../refactor/evidence/filmfestival-auto-20261110/). The parent condition
is reported in [the transfer results](2026-11-09-filmfestival-transfer-results.md), whose
statement of prior use of this corpus applies here too.

An independent check recomputed every figure with its own scorer and its own time-to-scene
conversion, replayed all 76 reply files from the recorded raw replies, confirmed that no call
preceded its freeze, and found nothing from the annotation or the reference in the automatic
record. Its post hoc analyses are reported below and labelled.

## Execution

- Four runs: automatic and no film record, each with `claude-opus-5-5` and `claude-haiku-5-5`.
  Each has 19 participant-sessions, 3,226 utterances and 63 calls, every chunk accepted on its
  first attempt and every call reporting its arm's model.
- Frames: 1,359, all described by `claude-sonnet-5-5`, none undescribed.
- Processing failures: none in either automatic run. The Haiku no-record run omitted 2
  utterances and gave 3 times past the end of a clip.

## Primary result: strict referent scene agreement, Opus 5.5

Participant averages with 95% intervals; pooled counts beside them.

| Condition | First session (15) | Second session (4) |
|---|---|---|
| Annotation-assisted | 86.4% (83.8 to 89.1) | 84.8% (81.1 to 89.4) |
| **Automatic** | **78.5% (75.2 to 81.3); 1,853 of 2,335** | **78.9% (75.3 to 82.0); 375 of 471** |
| No film record | 36.9% (32.4 to 41.5) | 33.0% (24.4 to 42.4) |

Second-session intervals resample four participants.

Companion columns, Opus 5.5, participant averages by session:

| Condition | Within one scene | Within 2 seconds |
|---|---|---|
| Annotation-assisted | 95.6%, 93.9% | not defined |
| Automatic | 94.1%, 94.7% | 84.7%, 87.6% |
| No film record | 72.8%, 67.1% | 44.2%, 39.7% |

Film identity is 99.9% and 99.8% of film-reference utterances in the automatic run. No answer
named the wrong film.

## Automatic against annotation-assisted

Within participant, Opus 5.5, first session:

- strict: 8.0 points lower (10.0 to 6.2), lower for all 15 participants;
- within one scene: 1.5 points lower (2.6 to 0.5).

In the second session the differences are −5.9 and +0.7, on four participants. The narrative
judgement gives the same picture (−7.4 strict, −1.0 within one scene, first session).

Haiku 5.5 loses more: 10.3 points strict in the first session (12.8 to 8.0) and 13.5 in the
second; 2.6 and 2.3 within one scene.

### What the loss is (post hoc)

The independent check analysed the 578 strict misses of the Opus automatic run.

- 95% are a time in the right film; the rest are film-only answers.
- 73% fall in the neighbouring scene, evenly before and after the reference.
- The median miss is 3.7 seconds from the reference scene; a third are within 2 seconds.
- **It is not the clock.** The best constant shift per film, fitted on the scored data, would
  raise strict agreement by 1.7 points; cross-validated across participants, by 0.9. Clock error
  explains at most 1 to 2 of the 8 points.
- **It is short scenes.** Single-scene references, both sessions:

  | Reference scene length | Automatic | Annotation-assisted |
  |---|---:|---:|
  | under 6 seconds | 41.1% | 75.9% |
  | 6 to 10 seconds | 67.2% | 80.5% |
  | 10 to 20 seconds | 79.6% | 84.3% |
  | 20 seconds and over | 84.7% | 88.6% |

- 87% of answers sit on a 2-second frame time, so the record's sampling sets the precision.
- Speech gives no visible advantage: the five wordless films score 80.8% and the five with
  speech 77.4% (first session). The wordless films rest on frame descriptions alone.

### By film (Opus 5.5, first session, strict, pooled)

| Film | n | Annotation | Automatic | No record |
|---|---:|---:|---:|---:|
| 1 Catch Me If You Can | 261 | 90.0% | 79.7% | 40.2% |
| 2 The Record | 190 | 88.9% | 81.6% | 48.9% |
| 3 The Boyfriend | 440 | 85.2% | 79.8% | 43.4% |
| 4 The Shoe | 134 | 90.3% | 71.6% | 38.8% |
| 5 Keith Reynolds | 360 | 85.8% | 82.5% | 37.5% |
| 6 The Rock | 244 | 85.2% | 83.6% | 27.5% |
| 7 The Prisoner | 190 | 82.6% | 79.5% | 27.4% |
| 8 The Black Hole | 118 | 92.4% | 86.4% | 55.1% |
| 9 Post-it Love | 84 | 89.3% | 86.9% | 50.0% |
| 10 Bus Stop | 314 | 86.3% | 68.8% | 29.9% |

Within one scene, every film is above 90% in the automatic run.

Bus Stop is lowest. It has the most scenes (35, median 9 seconds); its speech stops at 4:41,
after which strict agreement is 52% against 75% before; and its last scene's interval covers
only credits and black frames, so all ten utterances coded to it miss. That last point is a
boundary artefact worth about 0.3 points overall.

## The no-record arm measures the order of recall

Given only titles and film lengths, Opus 5.5 scores 37% strict and 73% within one scene. A
uniform guess in the right film scores 7% and 17%. The independent check tested why (post hoc).

- **A positional rule does nearly as well.** Spacing a film's utterances evenly along its
  length, in the order spoken, scores 33.0% strict and 66.0% within one scene (first session;
  27.5% and 60.2% in the second). A version that uses no reference labels scores 32.7% and
  63.3%.
- Opus no-record is 3.9 points above that rule on strict agreement (0.0 to 7.7) and 6.8 above
  within one scene. Its times correlate 0.94 with the rule's.
- Haiku no-record is 8.3 points below the rule.
- **No sign of prior knowledge.** The two commercial excerpts score 34.8% against 35.9% for the
  rule on those films.

So the floor for scene agreement on this corpus is about 33% strict and 66% within one scene,
not chance. Read against it:

| | Strict | Within one scene |
|---|---|---|
| Positional rule | 33.0% | 66.0% |
| Opus 5.5, no record | 36.9% | 72.8% |
| Opus 5.5, automatic | 78.5% | 94.1% |
| Opus 5.5, annotation-assisted | 86.4% | 95.6% |

The automatic run is 45.5 points above the positional rule on strict agreement (41.8 to 49.5),
for all 15 participants. **Within one scene discriminates weakly:** two-thirds of it is available
from serial order alone.

The positional rule and the miss analysis come from the independent check's scripts, which are
not in the repository. Adding the rule to `ff_baselines.py` is a follow-up.

## Haiku 5.5 against Opus 5.5

Within participant, strict, first session and second:

| Condition | Haiku minus Opus |
|---|---|
| Annotation-assisted | −1.8 points, −1.2 |
| Automatic | −4.2 (−6.2 to −1.4), −8.9 |
| No film record | −12.2, −14.9 |

The cheaper model's gap grows as the input gets harder.

## What this does and does not show

- With no human description of the films, the mapper locates recall in the right film almost
  always and in the right or neighbouring scene 94% of the time. It loses about 8 points of
  exact scene agreement, concentrated in scenes shorter than 10 seconds.
- A large part of any scene-agreement figure on ordered recall comes from serial order. Scores
  should be read against a positional rule, and labels from this mapper are not an independent
  measure of recall order.
- The limits of the parent results apply: previously used participants, a reference of
  unmeasured reliability, authors' utterances as the unit, and a referent measure that is not
  comparable with the Sherlock figures.
- Frame descriptions are model output and were not graded.
- Whether denser frame sampling would recover the short scenes was not tested.
