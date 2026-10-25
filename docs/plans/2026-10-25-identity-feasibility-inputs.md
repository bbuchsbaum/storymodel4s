# Identity feasibility packet: inputs, clocks, scope cost and criteria (Claude half)

**Date:** 2026-10-25.
**Bead:** `bd-01M5SPTKQX5GQS9CM3E0KZT7XQ`, under epic `bd-01M5MR1KTXEP898CTN2KE3J219`.
**Plan:** the joint proposal recorded on the epic (Mote ops
`20261025T140608.260924Z-p10810-c0000-rca0d-hddbbcf` and
`20261025T140809.851661Z-p16321-c0000-rec89-h9ebf19`). The other half, population, inclusion rule,
reference burden and the binder and adapter specification, is Codex's
(`bd-01M5SPY13QSN0X6MEBR170EZCZ`).

**Scope of this document.** It contains counts, row and scene ids, hashes and rules, and no story
text, dialogue or recall text (rule 14d). Every measurement was read-only and offline, with no model
calls. The local artifacts it cites stay under `data/source-identity/feasibility/`.

## 1. Inputs and anchors

The records are those the connected assessor consumed in `connected-main-03/requests.json` (sha256
`e383392c…10bdc`):
- 429 distinct records: 159 `caption`, 159 `manual-annotation` and 111 `asr-segment`;
- 363 on `annotation-run-2` and 66 on `annotation-run-1`.

| Need (ADR 0028) | Finding | Status |
|---|---|---|
| Speech records carry recognizer word indices and per-word UTF-16 offsets (section 7) | They carry neither, but both **derive exactly**. In all 111 `asr-segment` records, the decoder segment's words appear in order as exact substrings of the record text, and nothing but whitespace is left over. Offsets are stored in `asr-record-offsets.json` (sha256 `2089bd97…0f64`, record ids, indices and offsets only) | Derivable; needs a deterministic step in record preparation |
| Record clock maps to the graph's edition (section 2) | `docs/data/sherlock/timebase-repair.json`, `annotationToPlaybackCrosswalk` `annotation-raw-to-part-playback-v1` (status established): run-1 maps to `media-part-a`, run-2 to `media-part-b`, both identity. All 111 ASR records' start and end equal their segment's `source_media` times, which confirms the mapping on this data | Established; the reader's part argument must be passed through this declared table, not inferred from names |
| Record times are whole ticks (section 2) | All 858 start and end values are whole 1/44100 s ticks, and none is reversed. They are serialized as JSON floats, and the reader refuses floats | The caller parses with `parse_float=Decimal` |
| Long decoder segments | 8 of the 111 ASR records lie in segments longer than 30 s, which ADR 0027 section 2 refuses | Those records' speech handles stay unbound |
| One modality per record (section 3) | A `manual-annotation` row carries both a description and a speaker, so under ADR 0028 it is a mixed record and is refused. That is 159 of the 429 records | **Open for the adapter specification (Codex half):** split each row into a picture part and a speech part for arm A, or leave it out |

## 2. The recall timing convention

**Established as the Princeton clock.**
- **Receipt:** `docs/plans/2026-09-02-gold-scene-preregistration.md` section 2. The
  `Recall_Scene_n50_Onsets` file (sha256 `68cc307c…3753`) gives TR indices at 1.5 s on the Princeton
  clock. This was fixed by last-offset against last-word agreement for 15 of 16 subjects. The
  section 3 table maps gold subject 3 to NN03.
- **My independent NN03 check:**
  - NN03's last coded offset is TR 713 (1069.5 s), which equals the Princeton maximum TR of 713. It
    is 1.1 s after the last Princeton word onset.
  - The OpenNeuro clock gives a maximum TR of 718 and a last word 6.4 s *after* the coded end, which
    is inconsistent.
  - The Princeton TR equals floor(onset / 1.5) + 1 for all 2,495 words.
- **Consequence:** items may be selected through the Princeton TR column. The watch-scene file
  restarts its numbering per part, so a global scene id is 27 plus the Part2 scene number. Watch TRs
  are local to each part, so the annotation's continuous TR minus 946 gives the part-b local TR.

## 3. Scope cost: the scenes NN03 recalls

These are counts from `scene-scope-cost.json` (sha256 `15221429…fa30`). NN03 recalls 32 scenes,
covering 702 annotation rows.

**Vocabulary review.** The 32 scenes hold 38 distinct `Name - All` entries, and 30 of them are not
in the reviewed list of ADR 0027. Each is a one-time classification: character, or role label with a
count. The whole film has 46.

**Refused speech.** 390.75 s of speech lies in decoder segments longer than 30 s. Nine scenes have
no admitted source candidate mention at all: 2, 3, 4, 10, 11, 30, 41, 42 and 44. Scene 30 is the
body-scene core.

**Producer runs** are deterministic, take seconds per part, and make no model calls. Their cost is
the vocabulary review plus a human-reviewed presence basis for each row, which ADR 0027 decision 1
already grants.

**Recall side.** Over these scenes NN03's recall has 2,455 words, of which 256 are pronoun or
person-noun tokens. That is the raw pool from which Codex's inclusion rule draws decisions. These
are tokens, not decisions: many co-refer, and some refer to no person.

In the table, "admitted source candidates" are recognizer tokens outside refused segments. For
pronouns and nouns they also need probability of at least 0.5. Names are matched against that
scene's annotation names. It is an estimate of source-side coverage, not a mention count from the
producer.

| Scene | Part | Rows | New vocab. entries | Refused long segments (s) | Admitted source candidates (pronoun / noun / name) | NN03 recall words | NN03 pronoun / noun tokens |
|---|---|---|---|---|---|---|---|
| 2 | a | 8-18 | 2 | 1 (13) | 0 / 0 / 0 | 38 | 0 / 2 |
| 3 | a | 19-23 | 0 | 1 (31) | 0 / 0 / 0 | 23 | 2 / 0 |
| 4 | a | 24-36 | 1 | 1 (45.68) | 0 / 0 / 0 | 30 | 5 / 0 |
| 5 | a | 37-44 | 1 | 0 (0) | 0 / 0 / 1 | 70 | 12 / 1 |
| 8 | a | 64-69 | 2 | 0 (0) | 0 / 1 / 0 | 125 | 8 / 2 |
| 9 | a | 69-72 | 6 | 0 (0) | 6 / 1 / 0 | 34 | 5 / 1 |
| 10 | a | 73-80 | 2 | 1 (12.87) | 0 / 0 / 0 | 44 | 0 / 0 |
| 11 | a | 81-86 | 2 | 1 (17) | 0 / 0 / 0 | 43 | 2 / 1 |
| 13 | a | 97-137 | 8 | 0 (0) | 4 / 1 / 4 | 158 | 7 / 0 |
| 14 | a | 138-164 | 1 | 0 (0) | 0 / 0 / 6 | 85 | 7 / 2 |
| 16 | a | 187-251 | 2 | 1 (4.29) | 8 / 1 / 5 | 146 | 19 / 0 |
| 19 | a | 263-297 | 2 | 1 (23.28) | 3 / 0 / 4 | 79 | 5 / 2 |
| 22 | a | 314-338 | 1 | 0 (0) | 2 / 0 / 2 | 100 | 8 / 1 |
| 24 | a | 350-371 | 2 | 0 (0) | 0 / 0 / 2 | 91 | 15 / 0 |
| 29 | b | 490-504 | 2 | 1 (24.8) | 3 / 0 / 2 | 70 | 2 / 1 |
| 30 | b | 505-545 | 0 | 2 (90.00) | 0 / 0 / 0 | 53 | 2 / 1 |
| 33 | b | 590-631 | 1 | 0 (0) | 41 / 2 / 0 | 237 | 24 / 0 |
| 34 | b | 632-654 | 4 | 1 (12.09) | 15 / 0 / 1 | 146 | 20 / 1 |
| 35 | b | 658-686 | 2 | 2 (27.20) | 13 / 1 / 1 | 51 | 5 / 1 |
| 36 | b | 687-718 | 5 | 1 (58.46) | 0 / 0 / 5 | 104 | 10 / 0 |
| 37 | b | 719-733 | 2 | 0 (0) | 0 / 0 / 3 | 28 | 1 / 1 |
| 38 | b | 734-784 | 1 | 0 (0) | 12 / 1 / 8 | 109 | 10 / 5 |
| 40 | b | 804-833 | 2 | 0 (0) | 2 / 0 / 4 | 83 | 8 / 1 |
| 41 | b | 834-840 | 1 | 1 (5.35) | 0 / 0 / 0 | 15 | 0 / 1 |
| 42 | b | 841-844 | 0 | 1 (14) | 0 / 0 / 0 | 31 | 6 / 0 |
| 43 | b | 845-858 | 2 | 1 (11.73) | 1 / 0 / 0 | 27 | 3 / 2 |
| 44 | b | 858-869 | 0 | 0 (0) | 0 / 0 / 0 | 71 | 7 / 1 |
| 45 | b | 871-898 | 0 | 0 (0) | 8 / 0 / 0 | 45 | 8 / 0 |
| 46 | b | 899-911 | 0 | 0 (0) | 3 / 1 / 0 | 91 | 12 / 2 |
| 47 | b | 911-934 | 0 | 0 (0) | 1 / 2 / 0 | 102 | 5 / 3 |
| 48 | b | 935-961 | 0 | 0 (0) | 10 / 1 / 0 | 80 | 5 / 1 |
| 49 | b | 963-981 | 0 | 0 (0) | 20 / 2 / 0 | 46 | 0 / 0 |

**What this means for scene selection (input to the inclusion rule):**
- Scenes with zero admitted source mentions can be in the item set only with arm A, or with B after
  an ASR repair. Under arm B their speech-side failures would be input failures, not binder
  failures.
- The body scene (29-30) contributes few NN03 tokens. That supports keeping it as the named witness
  and regression case, not the main population.
- Scenes 33, 34, 16, 38 and 46 have the most NN03 pronoun tokens together with admitted source
  candidates.

## 4. Frozen criteria (draft, for joint agreement before any arm runs)

These restate the joint proposal as checkable rules. Item counts are left as `N` until Codex's
population count exists.

**Unit.** A *natural attribution decision* is one recall person-reference in NN03, a pronoun,
description or name, together with the claim role it fills. It is judged by a qualified human as one
of: resolvable to a source person, resolvable to "no person in the film", or not resolvable
(Unknown). Correlated tokens of one decision count once.

**Split, frozen before implementation or selection:**
- Natural decisions are split by scene into a **discovery** set and an untouched **confirmation**
  set.
- The split is fixed by a seeded, recorded procedure before any arm output exists. Whole scenes go
  to one side, so co-referring tokens cannot leak across.
- Designed diagnostics and hard controls are a separate third set.

**Arms:**
- A, B and D share one binder.
- C bypasses graph acquisition and binding. It shares the admitted evidence budget, the output
  fields, the source and target checks, the references and the evaluation.

**Two denominators, reported for every arm and set:**
1. *Utility rate*: correct attributions over human-resolvable natural decisions.
2. *Full accounting* over **all** frozen natural decisions: counts of correct, wrong, unresolved and
   input-failed, and answer coverage, meaning the share with any non-unresolved answer.

Unknown references stay visible and are never forced into true or false. Unresolved is never a
participant failure.

**Engineering GO gate, for a candidate and again for its confirmation run:**
- At least 15 human-resolvable natural decisions in the set. Fewer means **insufficient sample**,
  never GO.
- Utility rate of at least 80%.
- Zero observed false attributions or contradictions.
- Every discriminating control passed: wrong person, off-screen person, time, state, context and
  relational description.
- The same error gate applies to C and to any repair.

**Named witness.** The body and identity diagnostic, with its paired negatives:
- If human review finds it resolvable, the problem counts as solved only when it is resolved,
  whatever the aggregate shows.
- If review finds insufficient evidence, the result is an explicit ambiguity finding.
- Reused body cases are regression evidence only.

**Per-item failure stage.** Each item records one: input missing, candidate absent, binding,
inference, or attribution.

**Decision rules, unchanged from the joint proposal:**
- One targeted seam repair is allowed after a failed discovery run. A failed confirmation stops or
  reframes the work.
- If the entity is absent, the first repair is discourse coreference.
- C winning selects a follow-up direction; it is not production adoption.

**Proposed amendment, not yet agreed with Codex: a pseudonymized control for C, and for the
labellers.**
- The model and the agent labellers may know this widely seen episode, so C could win on memorized
  plot rather than on the evidence given.
- Run C, and draft labelling, under one consistent renaming of the characters as well as the
  original names. Map the answers back before scoring.
- Report both conditions. A gap between them measures dependence on prior knowledge.
- This costs one extra condition for C and changes no criterion above.

**Arm C** needs owner authorization for its spend, and the human reference reviewer needs to be
named. Both are owner decisions.
