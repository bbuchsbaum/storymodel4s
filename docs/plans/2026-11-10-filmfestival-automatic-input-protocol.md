# FilmFestival automatic-input condition: protocol (2026-11-10)

This adds a second input condition to the
[FilmFestival transfer protocol](2026-11-09-filmfestival-transfer-protocol.md). Everything that
protocol fixes stays fixed unless this page changes it: the utterances, the two sessions, the
reference and its classes, the referent as primary judgement, the three kinds of answer, the two
mapper arms, independent participant-sessions, and the reporting. The owner agreed the choices
below on 2026-11-09.

**Status.** Draft. No frame, transcript or recall from this corpus has been sent to a model for
this condition. Values under [Freeze](#freeze) are filled in by two freeze commits.

## Why

The annotation-assisted condition gave the model scene descriptions written by a person. Its
result ([results](2026-11-09-filmfestival-transfer-results.md)) says what the mapper can do with
that help. This condition removes it: the film is represented only by what can be derived from
the video. The comparison between the two, with the same mapper on the same utterances, says what
the human descriptions were worth.

## One system, two inputs

The owner's requirement is that outputs be as uniform as possible across datasets and conditions.
This condition therefore changes the input and nothing downstream.

- **Same prediction format.** A reply is converted into the predictions the scorer already takes:
  a scene, a film without a scene, no assignment, or a failure. The time the model gave is kept
  beside it.
- **Same scorer and report.** `tools/filmfestival/ff_score.py` scores every condition and writes
  one report layout. What this condition needs is added in a general form (below), and the
  annotation-assisted run is re-scored into the same layout.
- **Same run script.** `ff_map.py` runs every condition. The condition is named in the frozen
  manifest, as the model already is; it is never chosen on the command line.

Where uniformity is not possible, this page says so.

A check on the design: re-scoring the landed annotation-assisted run with the extended scorer
reproduces every published figure exactly, and its near agreement equals the within-one-scene
figures already reported (Opus 95.9% and 94.5%).

## The film record

For each of the ten films, in presentation order with its film number and title:

- **Speech:** the non-suspect segments of the unified Whisper transcript, each with its start time.
  A film with no speech says so.
- **Frames:** one frame every 2 seconds from the start of the clip, each with a one-sentence
  description written by a vision model. Frames are chosen by their timestamp in the source, and
  extraction refuses unless each is within 0.05 seconds of its intended time. (ffmpeg's
  frame-rate filter was found to give frames about a second late.)
- Lines are in time order. Times are minutes:seconds from the start of that film's clip.
- There are no scene numbers, no row numbers and no human-written descriptions.

Why every 2 seconds: the shortest annotated scenes last 3 to 4 seconds, so every scene gets at
least one frame. Sherlock used 4 seconds for scenes averaging about 15. Frames are sampled by
time, not by shot: scene boundaries are often not hard cuts, and two films have almost none.

### Frame descriptions

- **Model:** `claude-sonnet-5-5`, the model that described the Sherlock frames. The description
  model is part of the input, not an arm.
- **Instruction:** the Sherlock instruction unchanged: one factual sentence of at most 30 words
  per frame; people by appearance, not identity; setting; readable on-screen text.
- **Blind:** the describing calls contain frames and the instruction only. No recall, no
  annotation, no film title.
- Frames are 512 pixels wide. Each frame file is recorded by hash, and the descriptions are
  hashed as one file.
- Frames are shown to the describing model under neutral names, a batch at a time. Nothing it
  can see names or numbers a film.
- A frame with no usable description after three attempts is listed as undescribed in the record
  and counted. The run refuses if more than 2% of a film's frames are undescribed.

## Answers

For each judgement the model gives one of:

- a film and a time in it (minutes:seconds);
- a film without a time;
- nothing.

These are the three kinds of the parent protocol, with a time where that protocol has a scene.
A time written any other way than minutes:seconds, or a time with no film, is a failure for
that judgement with its reason. Reply items carry no alternatives and no row citations in this
condition, because the record has no rows; that is a stated difference from the parent.

## From a time to a scene

The reference is scene-level, so a time is converted to a scene after the run, by a fixed rule
that uses the annotation's scene boundaries. The model never sees those boundaries.

- **Clock.** Clip time and annotation time are related film by film by
  `clip = a × (annotation − C0) + b`, where `C0` is the start of the film's first content row.
  The constants were measured from content before any model call: quoted dialogue matched to the
  transcript, and detected cuts matched to annotated row starts. They are in
  [Freeze](#freeze).
  - Nine films have `a = 1` and an offset under one second. Catch Me If You Can has
    `a = 0.996`.
  - Estimated uncertainty is 0.5 to 1.5 seconds. The Shoe is the least certain (1.5 seconds);
    its offset rests on visual checks, because cut matching alone was ambiguous.
- **Scene intervals.** A scene starts at its earliest row start and ends at the next scene's
  start. A film's first scene runs from 0, and its last scene ends at the end of the clip. Rows
  are not assumed to be in order. A time on a boundary belongs to the scene that starts there.
- **Strict scene.** The scene whose interval contains the time.
- **Near scenes.** Every valid scene whose interval lies within 2 seconds of the time,
  inclusive.
- **A time past the end of the clip** is an answer, not a processing failure. It is never a
  scene hit, it keeps its film for film identity, and it is counted on its own. A time up to 2
  seconds past the end is still near the last scene.
- **Title cards.** No clip contains a title card, so a time can never select one. The
  Boyfriend's first scene holds its title row and a content row, and is a valid target from 0.

Why 2 seconds: it covers the largest clock uncertainty plus the annotation's one-second
resolution. Three seconds would take in whole neighbouring scenes.

## Scoring

The parent protocol's scoring applies. Two things are added, both for every condition.

- **Strict scene agreement.** As before: the selected scene is in the reference set.
- **Near scene agreement.** The selected scene, or any near scene, is in the reference set.
  - For a time answer, near scenes are those within 2 seconds, as defined above.
  - For a scene answer (the annotation-assisted condition), near scenes are the valid scenes
    immediately before and after it in the same film.
  - These two definitions are not the same quantity. They are the nearest equivalents: each asks
    whether the answer is right to within the reference's boundary precision. Reports name which
    one applies.

**Primary measure for this condition: near scene agreement of the referent, primary arm.**
Between 22% and 40% of film time lies within 2 seconds of a scene boundary, so strict agreement
would partly measure the clock. Strict agreement is always reported beside it.

Comparisons fixed in advance:

| Comparison | Reads |
|---|---|
| Automatic against annotation-assisted, same arm | near against near, and strict against strict |
| Frames plus speech against no film record | how much is prior knowledge, per film |
| Haiku against Opus, within participant | as in the parent protocol |

The baselines of `ff_baselines.py` are reported as before, labelled as not prespecified in the
parent protocol but fixed here.

## Arms

| Arm | Film record | Mappers |
|---|---|---|
| Automatic | frames plus speech | `claude-opus-5-5` (primary), `claude-haiku-5-5` |
| No film record | film numbers, titles and durations only | `claude-opus-5-5`, `claude-haiku-5-5` |

- The no-record arm measures what the mappers already know. Two films are excerpts of
  commercial features and eight are short films, so it is reported per film.
- A speech-only arm is not run: five films have no speech.

## Execution

As in the parent protocol: 60 utterances per call with the whole session shown, up to three
attempts per chunk, every reply recorded by content hash, each call checked against its arm's
model, both sessions frozen together and run without inspecting results in between.

## Freeze

Two freezes, because the film record depends on the frame descriptions.

1. **Description freeze.** Frame extraction, the frame list with hashes, the description
   instruction and model, and the description tool. Reviewed independently, then the frames are
   described.
2. **Mapping freeze.** The film record with its hash, the prompt, the clock constants and scene
   intervals, the time-to-scene rule, the scorer, and one manifest per arm and model. Reviewed
   independently, then the mapping runs.

No result is scored until every arm has finished.

| Item | Value |
|---|---|
| Frames | to be recorded at the description freeze |
| Film record | to be recorded at the mapping freeze |
| Clock constants | to be recorded at the mapping freeze |

## Known limits

- The clock was fitted to the annotation, which is what scoring uses. Whether the local clips are
  the editions shown in the scanner is not verified.
- Frame descriptions are model output. A wrong description is inherited by the mapper. This
  experiment counts undescribed frames and does not grade descriptions.
- The limits of the parent protocol apply: previously used participants, a reference whose
  reliability between coders is unmeasured, and authors' utterances as the unit.
