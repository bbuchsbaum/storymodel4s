# FilmFestival transfer evaluation: results (2026-11-09)

Run under the [protocol](2026-11-09-filmfestival-transfer-protocol.md), frozen at c5a6d154d; the
arm comparison was committed at 4c54af643 before any reply existed. Score and comparison files
are in [the evidence directory](../refactor/evidence/filmfestival-transfer-20261109/).

> FilmFestival was previously used to develop and evaluate an embedding/HSMM mapper at
> film-identity level. This is the first evaluation of the frozen LLM mapper and its scene-level
> outputs on this corpus. Four participants contribute an additional session previously
> unprocessed by those methods. The evaluation tests transfer from Sherlock; it is not an
> untouched-corpus or new-participant confirmation.

## Execution

- Both arms completed: 19 participant-sessions and 3,226 utterances each, 63 calls each, every
  chunk accepted on its first attempt, no retries.
- Every call reported exactly its arm's model (`claude-opus-5-5`, `claude-haiku-5-5`), CLI
  2.1.321.
- No processing failure in either arm: every utterance has a valid answer for both judgements.
- The Haiku arm returned 15 reply items for utterances outside the chunk asked about; they were
  dropped by rule. No utterance was left unanswered as a result.

## Primary result: referent scene agreement, Opus 5.5

Scene-specific utterances of the ten films. The one selected referent scene must be in the
reference set.

| Session | Participants | Participant average | 95% interval | Pooled |
|---|---:|---:|---|---|
| First | 15 | 86.4% | 83.8 to 89.1 | 86.9% (2,029 of 2,335) |
| Second | 4 | 84.8% | 81.1 to 89.4 | 85.4% (402 of 471) |

The second session's interval resamples four participants.

- **Chance** for one uniform scene guess: 6.2% and 5.6% given the correct film; 0.6% end to end.
- **By stratum (pooled):**

  | Stratum | First | Second |
  |---|---:|---:|
  | Single scene | 85.0% (of 1,810) | 83.0% (of 400) |
  | Multi-scene, contiguous | 95.9% (of 414) | 98.4% (of 62) |
  | Multi-scene, non-contiguous | 84.7% (of 111) | 100% (of 9) |

  A hit on a multi-scene reference is a localization measure, not recovery of every event.
- **Participants** range from 76.5% to 95.8% in the first session.
- **Misses** on scene-specific utterances were a wrong scene 280 times and a film-only answer 26
  times in the first session (65 and 4 in the second). There were no title-card answers.

### By film (first session, pooled)

| Film | Agreement | n | | Film | Agreement | n |
|---|---:|---:|---|---|---:|---:|
| 1 Catch Me If You Can | 90.0% | 261 | | 6 The Rock | 85.2% | 244 |
| 2 The Record | 88.9% | 190 | | 7 The Prisoner | 82.6% | 190 |
| 3 The Boyfriend | 85.2% | 440 | | 8 The Black Hole | 92.4% | 118 |
| 4 The Shoe | 90.3% | 134 | | 9 Post-it Love | 89.3% | 84 |
| 5 Keith Reynolds | 85.8% | 360 | | 10 Bus Stop | 86.3% | 314 |

The range is 82.6% to 92.4%. The two commercial excerpts (films 1 and 7) show no consistent
advantage: film 1 is among the highest and film 7 is the lowest. The five wordless films (2, 4,
6, 8, 9) are not lower than the others. Second-session
per-film counts are small (19 to 139) and are in the score file.

## Film identity, Opus 5.5

| Session | Film reference | All film-labelled |
|---|---|---|
| First | 99.9% (of 2,543) | 99.1% (of 2,574) |
| Second | 99.8% (of 515) | 98.1% (of 528) |

The gap between the columns is memory search within a film, for which the prompt asks for no
assignment.

- **Film-only references** (208 and 44): the model gave the right film without a scene 186 and
  34 times, and a scene in the right film 19 and 9 times (invented precision). It never gave the
  wrong film.
- **Memory search within a film** (31 and 13): no assignment 19 and 9 times; the film without a
  scene 12 and 4 times.
- **Non-film utterances** (101 and 16): no assignment 91 and 16 times; assigned to a film 10 and
  0 times. No failures.
- **Cartoon** (7): no assignment all 7 times.

## Secondary output: narrative judgement, Opus 5.5

| Session | Participant average | 95% interval | Pooled |
|---|---:|---|---|
| First | 86.3% | 83.7 to 88.8 | 86.8% (2,026 of 2,335) |
| Second | 86.5% | 83.6 to 90.9 | 86.6% (408 of 471) |

On this corpus the two judgements score alike. Referent was fixed as primary before the run.

## Secondary arm: Haiku 5.5

| Judgement, session | Opus 5.5 | Haiku 5.5 | Haiku minus Opus, within participant |
|---|---:|---:|---|
| Referent, first | 86.4% | 84.6% | −1.8 points (−3.3 to −0.5); lower for 10 of 15 |
| Referent, second | 84.8% | 83.6% | −1.2 points (−2.1 to +0.1); lower for 3 of 4 |
| Narrative, first | 86.3% | 85.3% | −1.0 points (−2.4 to +0.4); lower for 9 of 15 |
| Narrative, second | 86.5% | 85.4% | −1.1 points (−3.3 to +1.6); lower for 3 of 4 |

Rates are participant averages.

- Haiku is lower on the primary measure by about two points in the first session, with an
  interval that excludes zero. Film identity is the same (100% of film-reference utterances).
- The arms gave the identical referent answer on 2,386 of 2,682 utterances in the first session
  (89.0%) and 475 of 544 in the second (87.3%). Where both gave a scene, it was the same scene
  on 2,092 of 2,319 and 421 of 472.
- Haiku invents scene precision more often: on film-only references it gave a scene 44 and 14
  times, against Opus's 19 and 9. On memory search within a film it gave a scene 10 times and 1,
  against none for Opus.

## Session comparison

Within the four participants who have both sessions, Opus referent agreement is 87.0% in the
first session (659 utterances) and 84.8% in the second (471). The two sessions cover mostly
different films, so this is described, not interpreted.

## What this does and does not show

- The mapper built on Sherlock transfers to a second corpus with a different structure: ten
  films recalled in free order, 203 fine scene targets, and a packet with descriptions and
  speech only. Scene agreement is far above both chance baselines for every film and every
  participant.
- It is not a confirmation on unseen data. The participants and annotations were used before,
  for a different method, at film-identity level.
- The film descriptions were written by an annotator and given to the model. The
  automatic-input condition, with no annotations, has not been run.
- The reference coding has no measured reliability between coders, so the ceiling is unknown.
- Agreement here is not comparable with the Sherlock figure: the reference, the unit and the
  grain all differ.
- Self-reported alternatives, confidence and cited rows were recorded and are not analysed here.
