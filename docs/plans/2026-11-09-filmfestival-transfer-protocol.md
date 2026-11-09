# FilmFestival transfer evaluation: protocol (2026-11-09)

This fixes how the LLM mapper is evaluated on FilmFestival, before any model call on that corpus.
It implements section T of the
[categorical mapping plan](2026-11-09-categorical-mapping-and-transfer-plan.md). The owner agreed
the choices on 2026-11-09, with corrections from an external review.

**Status.** The decisions below are fixed, and the prompt, packet and scorer are frozen at the
values under [Freeze](#freeze). No model has been called on this corpus.

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
| Coding scheme | `recall_scenematched/_scenematching_codingscheme.xlsx` | `6aa3e021…624ca20c` |
| Raw scene codes | the 19 workbooks in `recall_scenematched/`, columns A and B | in the manifest |

The scorer refuses a reference file that does not have 3,226 utterances. The raw scene codes are
read from the workbooks by `tools/filmfestival/ff_codes.py`, because the derived file writes two
different codes as an empty scene list (see [Population](#population)).

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

| Session | All utterances | Scene-specific | Film-labelled | Film reference |
|---|---:|---:|---:|---:|
| First | 2,682 | 2,335 | 2,574 | 2,543 |
| Second | 544 | 471 | 528 | 515 |
| Combined | 3,226 | 2,806 | 3,102 | 3,058 |

"Film-labelled" is every utterance carrying one of the ten film codes. "Film reference" leaves
out memory search within a film (below), which refers to no film content.

Every utterance falls in exactly one class:

| Class | n | Meaning |
|---|---:|---|
| Single scene | 2,210 | one specific scene |
| Multi-scene, contiguous | 476 | an unbroken range of scenes |
| Multi-scene, non-contiguous | 120 | several scenes with a gap |
| Film only | 252 | the scheme's film-only code: a title mention, a remark on the whole film |
| Memory search within a film | 44 | a film code with scene −1, which the scheme defines as memory search |
| Non-film | 117 | memory search, gave up, or off task |
| Cartoon | 7 | out of scope |

The derived reference file cannot tell the last two classes apart, so they are separated by the
raw workbook codes. The prompt tells the model to give no assignment for memory search, so the 44
are near-certain film-identity misses by instruction. They are therefore reported on their own,
and film identity is reported both with and without them.

Every scene id in the reference exists in the dictionary and belongs to the film its utterance
names (checked on load; a mismatch refuses). One reference, "68 70", counts as non-contiguous
only because scene 68 is a title card.

## Scoring

Every rate, including each stratum and each film, is reported per session three ways: pooled,
per participant, and as the mean of per-participant rates with a 95% interval from resampling
participants, so one participant's utterances stay together. The second session's interval
resamples four participants and is reported with that caveat.

- **Scene agreement (primary).** Over scene-specific utterances. The one selected referent scene
  is correct when it belongs to the reference set. Alternatives earn nothing. A film-only answer,
  a scene that is not a valid target, no assignment and a processing failure are all misses, and
  each is counted.
- **A scene answer that is not a valid target** (a title card, the cartoon) is an answer, not a
  processing failure. It is never a scene hit. It keeps its dictionary film for film identity.
  - Single-scene, contiguous and non-contiguous utterances are reported separately. A hit on a
    multi-scene reference is a localization measure; it does not show that every referenced event
    was recovered.
- **Film identity.** The predicted film, from a scene or a film-only answer, equals the reference
  film. Reported over film-reference utterances and over all film-labelled utterances.
- **Film only, and memory search within a film.** For each, counts of: the right film without a
  scene; a scene in the right film (invented precision); a title card of the right film; the
  wrong film; no assignment; failure.
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
  15 with those four would confound session with participant. Even within the four, the two
  sessions cover mostly different films (one participant's first session is two films), so a
  session difference is also a film difference. It is described, not interpreted.
- **Narrative judgement.** Scored the same way, as the secondary output.

Processing failures are reported with counts by cause. An utterance with no valid answer after
the retry policy is a failure; it is never dropped. The scorer refuses unless every
participant-session has exactly one reply file.

## Execution

- **Model and calls.** The same model and call path as the Sherlock development runs. Those runs
  asked for the alias `opus`, and every one of their 260 recorded calls reported
  `claude-opus-5-5`. This run names `claude-opus-5-5` exactly, so the alias cannot move.
  - A call whose reply reports any other model, or more than one, stops the run and is not
    recorded.
  - Calls go through the subscription CLI with the API key removed. The run refuses to start if
    a variable that would route calls elsewhere is set.
  - The CLI version and the reported model are recorded with every call and in every reply file.
- **Chunks and retries.** As on Sherlock: 60 utterances per call, the whole session's utterances
  shown for context, up to three attempts per chunk with the fixed strict-JSON suffix.
- **Recording.** Every exchange is recorded by content hash before it is parsed, so the run can be
  replayed with no model call.
- **Order.** Participant-sessions run independently. No result is inspected before every
  participant-session of both sessions has finished, except to detect a protocol failure
  (truncation, a refused call).

## Freeze

Frozen values, produced by `tools/filmfestival/ff_map.py manifest` with no model call. The
manifest is [manifest.json](../refactor/evidence/filmfestival-transfer-20261109/manifest.json);
`ff_map.py run` refuses unless what it renders equals that manifest.

| Item | Value |
|---|---|
| System prompt (`ff_packet.SYSTEM`) | SHA-256 `d45e1754…90904498` |
| Rendered packet (16,693 words) | SHA-256 `c5dc1b13…fcfac5ec5` |
| Scene dictionary | 216 scenes, 203 valid; SHA-256 `39ef679b…43f560f1` |
| Reference, annotation, raw codes | SHA-256 of each, in the manifest |
| Participant-sessions | 19 (15 first, 4 second) |
| Utterances | 3,226 |
| Calls, before retries | 63, each prompt hashed in the manifest |
| Longest prompt | 121,435 characters; a prompt over 400,000 refuses the run |
| Model, chunk, attempts | `claude-opus-5-5`, 60, 3 |
| CLI flags and retry suffix | in the manifest |

The run compares what it renders with the tracked manifest, not with a copy beside its output.

The prompt is the Sherlock v2 prompt with three changes the corpus requires:

- it describes ten short films recalled in any order, with scene numbers that run across films;
- each judgement may be a scene, a film without a scene, or nothing;
- title cards are marked and cannot be chosen.

The packet lists, for each film, its scenes and rows with times from the start of the film, then
that film's speech as a separate list. It has no locations, people or scene summaries, because
the annotation has none.

Differences from the Sherlock harness, fixed here:

- a reply item for a unit outside the chunk it was asked about is dropped, and the unit counts
  as omitted;
- a reply item with a missing key or a wrong type is a failure for that judgement;
- a reply that cannot be parsed in any way is retried, then its chunk fails; it cannot stop the
  run;
- a unit answered twice in one session keeps the last answer, and the repeat is counted in the
  reply file.

An independent review of the first frozen draft (02f88d89a) found four defects before any model
call: a title-card answer was scored as a processing failure; memory search within a film was
mixed into the film-only class; one reply shape could stop the run for good; and the CLI version
and reported model were not recorded or checked. All four are fixed here, with tests.

The scorer's synthetic tests (`tools/filmfestival/test_ff_score.py`) cover film-only answers,
multi-scene references, cartoon exclusion, title-scene exclusion, explicit non-assignment and
processing failure. `test_ff_map.py` covers the packet, the reply parser and the run logic with a
stand-in model.

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
