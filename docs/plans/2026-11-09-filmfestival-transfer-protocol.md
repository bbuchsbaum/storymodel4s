# FilmFestival transfer evaluation: protocol (2026-11-09)

This fixes how the LLM mapper is evaluated on FilmFestival, before any model call on that corpus.
It implements section T of the
[categorical mapping plan](2026-11-09-categorical-mapping-and-transfer-plan.md). The owner agreed
the choices on 2026-11-09, with corrections from an external review.

**Status.** The decisions below are fixed. The prompt text, the packet and their hashes are added
in a freeze commit (see [Freeze](#freeze)). No model is called before that commit.

## What this evaluation is

> FilmFestival was previously used to develop and evaluate an embedding/HSMM mapper at
> film-identity level. This is the first evaluation of the frozen LLM mapper and its scene-level
> outputs on this corpus. Four participants contribute an additional session previously
> unprocessed by those methods. The evaluation tests transfer from Sherlock; it is not an
> untouched-corpus or new-participant confirmation.

Prior use, for the record: on 2026-09-04/05 the aligner (ONNX and lexical channels, HSMM) was run
on first recall sessions and scored for film identity only (about 67%). No scene-level score and
no LLM has been run. No partition was ever drawn. Annotation descriptions were visible in those
runs.

## Inputs

| Input | File under `data/filmfestival/` | SHA-256 |
|---|---|---|
| Reference labels | `derived/gold-scenematched.tsv` | `0433e14f…ae16bd0` |
| Film annotation (JL) | `derived/annotation-JL.tsv` | `f8b164c1…bc7485bc` |
| Coding scheme | `recall_scenematched/_scenematching_codingscheme.xlsx` | recorded at freeze |

The scorer refuses a reference file that does not have 3,226 utterances.

## Decisions

1. **Unit.** The original authors' utterance, one row of the reference file. The result is
   performance on author-provided utterances. How our own segmenter performs is a separate
   question.
2. **Sessions.** Both recall sessions are evaluated and reported separately.
   - The second session (four participants: sub-08, 10, 16, 18) is previously unprocessed recall
     from previously used participants. It is not held-out participants.
   - Both evaluations are frozen together. Each participant-session runs in its own model context,
     with no other session's transcript, predictions or corrections.
   - Nothing is changed between running the first session and the second.
3. **Primary output: the referent.** The coding scheme labels what an utterance "refers to", which
   is the mapper's referent judgement. The narrative judgement is a prespecified secondary output.
   The choice is not revisited after scores are seen. This differs from the Sherlock benchmark,
   whose reference was narrative coding.
4. **Answers the model may give,** for each of the two judgements:
   - one scene (a global scene id);
   - one film with no particular scene;
   - no source assignment.

   A scene answer's film is taken from the scene dictionary, never from the reply.
5. **Title scenes.** The coding scheme excludes title scenes and codes a title mention as
   film-only. A scene whose every annotation row is a title card is therefore not a valid scene
   answer. There are nine: 3, 27, 68, 81, 109, 137, 154, 165, 181. They stay in the packet as
   source information. Scene 42 holds a title row and a content row and stays valid.
   - That leaves 203 valid scene targets out of 212 film scenes.
   - The reference uses a title-only scene once, in the two-scene reference "68 70", which remains
     answerable through scene 70.
6. **Packet.** One packet holding all ten films, with global scene ids, given to every call. A
   per-film packet would give away the film. The lobby cartoon is not a target. Scene and row ids
   are the same in the packet, the replies, the scene dictionary and the scorer.
   - Before the freeze, the packet's size is checked against the model's context with the longest
     recall session. A call that would truncate is a protocol failure, not a result.
7. **Film descriptions.** Annotator JL. RC is a possible later robustness condition; identical
   scene numbering does not make its descriptions equivalent.
8. **Conditions.** This protocol covers the annotation-assisted condition: JL descriptions plus
   speech recognition.
   - Speech is supplied as a separate transcript per film, not attached to annotation rows. The
     annotation and media clocks differ by a few seconds, and a nearby utterance must not be
     presented as a quotation from a particular row.
   - A film with no speech (five are wordless) is marked as such. That is different from a failed
     transcription.
   - The automatic-input condition (speech plus frames, no annotations) is a separate experiment.
9. **Participants.** All 15 with reference labels.

## Population

Counted by `tools/filmfestival/ff_score.py population`, with the cartoon excluded from the film
counts:

| Session | All utterances | Scene-specific, ten films | Film-labelled, ten films |
|---|---:|---:|---:|
| First | 2,682 | 2,335 | 2,574 |
| Second | 544 | 471 | 528 |
| Combined | 3,226 | 2,806 | 3,102 |

Every utterance falls in exactly one class:

| Class | n | Meaning |
|---|---:|---|
| Single scene | 2,210 | one specific scene |
| Multi-scene, contiguous | 476 | an unbroken range of scenes |
| Multi-scene, non-contiguous | 120 | several scenes with a gap |
| Film, no scene | 296 | a film is named but no scene |
| Non-film | 117 | memory search, gave up, or off task |
| Cartoon | 7 | out of scope |

Every scene id in the reference exists in the dictionary and belongs to the film its utterance
names (checked on load; a mismatch refuses).

## Scoring

All rates are reported per session, as the mean of per-participant rates and pooled, with the
denominator. Uncertainty is computed by resampling participants, so one participant's utterances
stay together.

- **Scene agreement (primary).** Over scene-specific utterances. The one selected referent scene
  is correct when it belongs to the reference set. Alternatives earn nothing. A film-only answer,
  no assignment and a processing failure are all misses, and each is counted.
  - Single-scene, contiguous and non-contiguous utterances are reported separately. A hit on a
    multi-scene reference is a localization measure; it does not show that every referenced event
    was recovered.
- **Film identity.** Over film-labelled utterances. The predicted film, from a scene or a
  film-only answer, equals the reference film.
- **Film, no scene.** Counts of: the right film without a scene; a scene in the right film
  (invented precision); the wrong film; no assignment; failure.
- **Non-film utterances.** Counts of: assigned to one of the ten films; explicit no assignment;
  processing failure. A failure is never a correct non-assignment. Memory-search speech can occur
  inside a film's discussion, so these counts are described, not scored as right or wrong.
- **Cartoon.** Seven utterances, reported as counts only.
- **Chance.** For one uniform scene guess:
  - *given the correct film*: acceptable reference scenes divided by that film's valid scenes;
  - *end to end*: acceptable reference scenes divided by all 203 valid scenes.

  Both are averaged over the scored utterances, so they rise with multi-scene references.
- **Per film.** Scene agreement for each of the ten films. Two are excerpts of commercial
  features and eight are short films; this breakdown is descriptive. A film's obscurity does not
  show that the model has not seen it.
- **Session comparison.** Only within the four participants who have both sessions. Comparing all
  15 with those four would confound session with participant.
- **Narrative judgement.** Scored the same way, as the secondary output.

Processing failures are reported with their causes. An utterance with no valid answer after the
retry policy is a failure; it is never dropped.

## Execution

- **Model and calls.** The same model and call path as the Sherlock development runs: Claude Opus
  through the subscription CLI, with the API key removed from the environment. The CLI version and
  the reported model are recorded per call.
- **Chunks and retries.** As on Sherlock: 60 utterances per call, the whole session's utterances
  shown for context, up to three attempts per chunk with the fixed strict-JSON suffix.
- **Recording.** Every exchange is recorded by content hash before it is parsed, so the run can be
  replayed with no model call.
- **Order.** Participant-sessions run independently. No result is inspected before every
  participant-session of both sessions has finished, except to detect a protocol failure
  (truncation, a refused call).

## Freeze

One commit records all of the following, and the first model call cites it:

- the prompt text and its hash;
- the packet construction and the rendered packet's hash;
- the scene dictionary (216 scenes, 203 valid) and its hash;
- the scorer at its commit, reproducing the population table above;
- the retry policy;
- the context-size check.

The scorer's synthetic tests (`tools/filmfestival/test_ff_score.py`) cover film-only answers,
multi-scene references, cartoon exclusion, title-scene exclusion, explicit non-assignment and
processing failure.

## Known limits

- The same participants and annotation descriptions were used in earlier work.
- The release names no reference annotator, and the reliability of the recall coding between
  coders is unmeasured.
- Scenes are far finer here than in Sherlock (212 in about 45 minutes against 50 in 48), so the
  two agreement rates are not comparable; the chance baselines say how hard each is.
- Whether the local clips are the editions the participants saw is unverified. One clip is a
  time-stretched re-encode and one is 8 seconds short. This matters for the automatic-input
  condition and for attaching speech to rows, not for this condition.
- No recall or film text enters the repository.
