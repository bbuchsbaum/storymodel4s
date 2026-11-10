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
- **Blind to what we know, not to the picture.** A describing call contains frames and the
  instruction only: no recall, no annotation, and no title, number or name added by us. Frames
  are shown under neutral names, a batch at a time. Some frames show a film's title or credits
  on screen, and the instruction asks for readable text, so a description can contain a title.
  That harms nothing here, because the mapper is shown the titles.
- **Confined and audited.** The describing model can read files. Each call is confined to its
  batch's directory, and the files it actually read are recorded. A call that read anything
  else, or used any other tool, is refused and not recorded. A description of a file the model
  did not open is not accepted.
- **What counts as a description.** At least three words and at most 400 characters, and not a
  refusal or an error message (a fixed list of phrases). The 30-word limit in the instruction is
  not enforced. Before the mapping freeze the descriptions are audited by length and repetition.
- Frames are 512 pixels wide. Each frame file is recorded by hash, and the descriptions are
  hashed as one file.
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
  transcript, and detected cuts matched to annotated row starts. An independent review checked
  them by a different method and put the offset error at no more than half a second in every
  film. They are in [Freeze](#freeze).
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

The parent protocol's scoring applies. Every condition's report now has the same three columns,
each with its own chance level:

| Column | A hit when the reference set contains | Conditions |
|---|---|---|
| Strict | the selected scene | all |
| Within one scene | the selected scene, or the valid scene next to it in the same film | all |
| Within 2 seconds | a scene within 2 seconds of the time | time answers only |

- For a time answer the selected scene is the strict scene. A time past the end of the clip has
  no selected scene.
- **Within one scene is the same quantity in every condition,** and is the companion measure
  for comparing conditions. Chance for a uniform guess in the correct film is 15.7% for a scene
  answer and 16.6% for a time answer.
- **Within 2 seconds describes tolerance to the clock** and to where in a moment the model
  places its time. It is not compared with the annotation-assisted condition: its chance level
  is 8.4%, about half that of within one scene, so the two are not alike.
- Chance for a strict hit is 6.1% for a scene answer and 6.9% for a time answer, given the
  film; 0.6% and 0.7% end to end. Each report carries its own exact chance figures.

**Primary measure: strict referent scene agreement, primary arm, per session, as the mean of
per-participant rates with its interval; the pooled rate is reported beside it.** This is the
parent protocol's primary measure, so the two conditions are read on the same number. The clock
was checked by two independent methods to within half a second, so strict agreement is not
dominated by clock error. *This choice is the author's recommendation and awaits the owner's
confirmation; an earlier draft made within 2 seconds primary.*

Comparisons fixed in advance:

| Comparison | Reads |
|---|---|
| Automatic against annotation-assisted, same model | strict, and within one scene |
| Frames plus speech against no film record | strict, per film |
| Haiku against Opus, within participant | as in the parent protocol |

A report records the model, the condition and the manifest it was run under, and the scorer
refuses reply files that do not match the manifest.

The baselines of `ff_baselines.py` are reported as before.

## Arms

| Arm | Film record | Mappers |
|---|---|---|
| Automatic | frames plus speech | `claude-opus-5-5` (primary), `claude-haiku-5-5` |
| No film record | film numbers, titles and durations only | `claude-opus-5-5`, `claude-haiku-5-5` |

- The no-record arm shows what the mappers can do without a film record. That is prior knowledge
  of the films plus whatever the recall itself gives away: it sees the whole session and each
  film's length, so it can place an utterance by its position in the retelling. Two films are
  excerpts of commercial features and eight are short films, so it is reported per film.
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
- About 5% of speech lines have stretched timings in the transcript (one checked line is listed
  about ten seconds early). The parent condition did not attach speech to rows; here speech
  times are something the model can anchor an answer on.
- The prompt is about two and a half times longer than the parent's, and replies cite no rows.
- The limits of the parent protocol apply: previously used participants, a reference whose
  reliability between coders is unmeasured, and authors' utterances as the unit.
