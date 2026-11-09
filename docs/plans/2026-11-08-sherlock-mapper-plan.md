# A Sherlock mapper that works (2026-11-08)

Owner instruction, 2026-11-08: "make a plan and pursue it — the end goal that our mapping works
for Sherlock, works really good. Do the engineering, math and science to find a working
approach. Use LLMs if needed. Use the tools: captions, transcripts, all of it."

This plan executes milestones E1-E4 of the [ROADMAP](../ROADMAP.md) for Sherlock. Mote epic:
`bd-01M6Y7MWZGP29TNDJ90SG36A45`. Research harness: `tools/sherlock_mapper/`. Outputs live under
`data/study/recall-to-video/llm-mapper/`, which is ignored.

## Measurement

- **Population.** The 11 development participants, of whom 10 have gold (1,499 units). The six
  untouched participants are refused by the harness (`common.guard`) until a single registered
  read.
- **Gold.** The frozen `development-20260923/support.json`, which holds Chen et al.'s
  human scene coding.
- **Metrics.** Scene-exact and within-one-scene accuracy, participant-averaged (primary) and
  pooled. Where a method has stages, report them separately: candidate recall, local top-1 and
  final.
- **Scorer check.** The harness scorer reproduces the registered baseline: 60.95% / 79.41%
  participant-averaged, 65.24% pooled.
- **Ceremony.** Development experiments are engineering under the 2026-11-08 ruling. They need
  configuration, outputs and a score.json under the data root, plus a study-log entry for
  anything that changes direction.

## The experiment ladder

| Step | Approach | Question it answers |
|---|---|---|
| A0 | Existing graph-HSMM (A arm) | Baseline: 61.0% exact, 33.7% local top-1 |
| A1 | Context-rich LLM: the whole film outline (annotations, Whisper dialogue, scene captions) plus the participant's whole recall in one context; map target units in chunks | How far does a strong reader with all information get? This is the baseline to beat (owner amendment 2026-10-25). |
| A2 | Ablations of A1: no dialogue, no captions, smaller models, chunk size | Which information channels and how much model capacity carry the result |
| A3 | LLM scene distributions as HSMM emissions | Do content strength and the order prior add? |
| A4 | Film event layer, compiled once per film and recall-blind, and recall event parsing; typed retrieval and matching; HSMM decoding | Can a reusable structured representation match or beat A1 at lower per-participant cost, with inspectable evidence? |
| A5 | Within-scene localization, at row and second level, for the winner | Beyond scene-exact: the within-scene machine lane exists (66.1% for A0) |

## Validity threats and their checks

- **The LLM has prior knowledge of Sherlock.** Strong A1 results on Sherlock may overstate
  results on unfamiliar films. Two checks:
  - run on FilmFestival (obscure short films) when its references are usable;
  - run an A1 variant with names masked in the film outline only.
- **The unit segmentation is the existing segmenter's.** Gold is per TR, joined to units by the
  frozen unit timing. Mapping is held to that granularity.
- **Development overfitting.** Prompts and policies are tuned on development participants. The
  untouched read happens once, after the method is frozen.

## Path to the library

The winning approach becomes a mapping producer under ADR 0022. It has record/replay for LLM
calls (as `provider-agent` already does for parsers), proposals admitted deterministically, and
outputs in the existing mapping record. Event-layer artifacts go through the film compiler
stages (film plan, P1 children of `bd-01M2T32SGVJXR2RT3RTS2DCN9P`).

## Targets

The ROADMAP exits, on development:

- candidate miss below 10%;
- local top-1 at 55% or above;
- final scene-exact at 75% or above.

After that, one untouched read.

## Log

Results are appended below as they land.

### 2026-11-08: first results (development, 10 gold participants, 1,499 units)

| Run | Scene-exact (participant avg) | Within one | Pooled exact | Span-aware exact |
|---|---:|---:|---:|---:|
| A0 baseline graph-HSMM | 60.95 | 79.41 | 65.24 | 65.38 |
| A1 Opus, full outline | 78.15 | 90.56 | 80.05 | 84.21 |
| A2 without Whisper dialogue | 78.26 | 90.30 | 80.05 | |
| A2 without scene captions | 78.22 | 90.52 | 80.05 | |
| A2 Sonnet | 78.27 | 90.94 | 80.05 | |
| A1v2 Opus, narrative and referent scenes | **79.20** | 90.69 | **81.39** | **85.28** |
| A3 v2 with order-prior Viterbi (fixed / LOPO) | 78.68 / 78.31 | | | |

"Span-aware" counts a prediction as correct if it equals any gold scene coded during the unit's
own span. The primary metric takes gold at the unit onset only; 142 units span more than one gold
scene.

Findings:

- **Content now carries the mapping.** Every participant improves; the weakest goes from 43% to
  71%.
- **The order prior adds nothing on top of the LLM.** The LLM already reads recall order.
- **Dialogue and captions add nothing on Sherlock.** The human annotation rows already quote the
  dialogue and describe the shots. They will matter for unannotated films.
- **Opus and Sonnet are interchangeable** (94.5% identical predictions).
  - Four runs tie at exactly 1,200 correct units. This is coincidence: their predictions differ
    on 24-82 units.
- **Coding convention explains part of the residual.** When the narrative and referent scene
  differ (113 units), gold agrees with the narrative scene 75 times and the referent scene 10
  times. Chen's coders labelled narrative position. Both quantities are now emitted.
- **The NN03 u92 case is fixed.** The recalled body now maps to scene 30, rows 507
  and 503, which are the right frames. u93 (the collar) picks scene 30 (row 529), with scene 33
  as a 0.4 alternative; gold says 33.

### 2026-11-08: automatic evidence, prior knowledge, and frame-level localization

| Run | Film evidence | Scene-exact | Within one | Span-aware |
|---|---|---:|---:|---:|
| A6 transcript-only (Sonnet) | Whisper transcript only: no human rows, no human scene boundaries | 72.94 | 88.42 | 77.32 |
| A6 transcript and frame captions (Sonnet) | Whisper plus Claude-vision captions of 746 frames sampled every 4 s, recall-blind | 74.97 | 89.57 | 80.32 |
| A6 no-film probe (Sonnet) | none; the model's own knowledge of the episode | 11.47 | 31.25 | 12.99 |

- **An automatic transcript alone already beats the old pipeline,** which had full human
  annotation (61%). The no-film probe shows the evidence does the work, not memorised plot.
  - The probe's prompt names where part a ends, a slight leak of structure in the conservative
    direction.
  - Plot familiarity may still help the model interpret dialogue. An unfamiliar film
    (FilmFestival) remains the decisive generalization test.
- **Frame-level localization** was scored against the 2026-09-03 within-scene packet, which has
  LLM-adjudicated row ranges (the machine lane; diagnostic, not human).
  - v2's first-listed row falls in range for 88.3% of 179 units (90.2% on stratum A); any of its
    three rows, 91.1%.
  - The A0 pipeline's registered machine-lane hit was 66.1%.
  - Mapper and adjudicators are both LLMs, so a shared bias is possible. The owner's human lane on
    this packet would settle it.

### 2026-11-08: v3, structured symbolic mapping (pilot on NN03 and NN04)

v3 emits, per unit:

- the narrative and referent scene, with alternatives;
- a discourse function;
- typed claims (who, resolved against the 46-name cast inventory from the annotation; what;
  where), each with supporting rows, a relation (depicts, dialogue, summary or inference) and a
  fidelity judgement.

Pilot results, 242 units and 390 claims:

- fidelity: 369 supported, 10 contradicted, 11 unclear;
- only 2 unresolved person mentions;
- scene-exact 76.69 on these two participants.

Three flagged contradictions were checked against the annotation text, and all three are real
recall distortions:

- the wrong country in Sherlock's first question to John (r202);
- splash marks on the left leg, where the film has the right leg (r624);
- the waiting man's accessory, which in the film is an umbrella (r734).

Localization and veridicality are now separate outputs.

### 2026-11-08: v3 on all development participants

| Run | Scene-exact | Within one | Pooled | Span-aware |
|---|---:|---:|---:|---:|
| v3, Opus, structured | 78.99 | 90.76 | 81.05 | 85.23 |

The structure costs no accuracy.

Coverage:

- **Claims.** 2,709 claims over 1,744 units: 2,478 supported, 130 contradicted, 17 not in the
  film, 84 unclear.
- **Relations.** 1,309 dialogue, 1,106 depicts, 193 inference, 101 summary.
- **Function.** 1,618 narration, 69 commentary, 26 metamemory, 31 other.
- **Identity.** 13 person mentions unresolved.

Contradiction precision on a random 8-claim sample is about 6/8 genuine distortions. Examples:

- Anderson recalled as the lead detective; he is forensics, and Lestrade is the inspector.
- The minister's aides recalled as her friends.
- Mike taking John to the morgue; the film has the lab.
- John's arch-enemy remark attributed to the wrong person.

Two are doubtful. One marks the not-a-suicide conclusion as contradicted, although the film later
supports it.
The other has a claim attached to the wrong unit. Fidelity labels need a human-checked sample
before scientific use.

### 2026-11-08: owner blind check of v3 fidelity, and a graded verifier

The owner rated 50 v3 claims blind: 30 the model called contradicted, 10 not in the film, 10
supported (seed 11; answers kept under the data root). The sampled estimate above was wrong.

| v3 label | Owner: supports | contradicts | misread | can't tell | not in film |
|---|---:|---:|---:|---:|---:|
| contradicted (30) | 19 | 6 | 3 | 1 | 1 |
| not in film (10) | 2 | 4 | 1 | 3 | 0 |
| supported (10) | 9 | 0 | 1 | 0 | 0 |

Contradiction precision is 6/30, and the owner's notes call most of those six partial. The
recurring case is a recognisable event with a wrong detail; the three-way label has no place
for it, and neither did the form.

`verify_claims.py` re-judges each claim in a second pass, blind to the extractor's label, on a
graded scale: supported, gist-detail (naming the wrong detail), contradicted (the gist itself is
false), inference, unverifiable, invented, misread.

| Owner \ verifier | supported | gist-detail | contradicted | inference | other |
|---|---:|---:|---:|---:|---:|
| supports (30) | 9 | 17 | 0 | 2 | 2 |
| contradicts (10) | 1 | 8 | 0 | 1 | 0 |
| misread (5) | 1 | 1 | 1 | 1 | 1 |
| can't tell (4) | 0 | 3 | 0 | 1 | 0 |
| not in film (1) | 0 | 1 | 0 | 0 | 0 |

- False contradictions fall from 19 to 0: no claim the owner supported is called contradicted.
- Eight of the owner's ten contradicts become gist-detail, which matches the owner's notes.
- The open question is the 17 owner-supported claims the verifier calls gist-detail. For 6 the
  owner's note names the same detail; the other 11 need a short follow-up check of the named
  detail alone.
- The 50 items were used to design the scale, so they are calibration, not a held-out test.
- Scene mapping is unaffected; these labels never fed it.

### 2026-11-08: owner check of the verifier's named details

The owner checked the 11 owner-supported claims (no note) that the verifier called gist-detail,
judging only the named detail: 7 right, 3 partly right or too trivial, 1 wrong.

- With the 6 cases where the owner's own note named the same detail, the verifier's gist-detail
  calls on owner-supported claims hold in 16 of 17, fully or partly. The graded labels can be
  used; the three-way v3 label should not be.
- The one wrong call is a speaker attribution: the verifier gave a line to the wrong officer.
  The packet's dialogue has no speaker labels (the transcript is not diarized, and the
  annotation's "Name - Speaking" column is not rendered). Rendering it is a packet change, so it
  needs a new packet version and a new recording, not a replay.
- The three partial calls are minor wordings and one visual detail. A severity field (minor or
  material) on gist-detail would separate them.

### 2026-11-09: Haiku 5.5 against Opus 5.5 (development scout)

The v2 mapper was rerun on the 11 development participants with `claude-haiku-5-5`, same prompts,
packet and chunking (run `scout-haiku55-v2`, 34 calls). The Opus figures are the recorded
`a1v2-opus` run. Both are scored by the frozen scorer on the 10 participants with gold (1,499
units).

| | Opus 5.5 | Haiku 5.5 |
|---|---:|---:|
| Narrative agreement, participant average | 79.20 | 78.23 |
| Pooled | 81.39 | 80.39 |
| Within one scene, participant average | 90.69 | 90.52 |
| Units omitted or null, of 1,744 | 0 | 0 |

- Haiku is higher for 4 participants, lower for 5 and tied for 1 (−6.0 to +3.5 points). The
  paired mean difference is −1.0 point, 95% interval about −3.3 to +1.4 over ten participants.
- The models give the same narrative scene on 92.6% of the 1,744 units, and a different referent
  scene on 107 of the 1,669 units where both give one.
- Every Haiku call parsed on its first attempt and reported `claude-haiku-5-5`.

This is development data on which the prompt was tuned with Opus, so it says the cheaper model
is a plausible second arm, not that the two are equivalent elsewhere.
