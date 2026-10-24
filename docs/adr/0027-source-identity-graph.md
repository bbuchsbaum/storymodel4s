# ADR 0027 — A recall-blind source identity graph for the Sherlock body scenes

**Status:** Accepted 2026-10-24 (AGENTS.md SD5). The owner chose the recommended answer to each
question under "Owner decisions". The cold review of `1e25a878f` found three blocking defects; they
and its eight follow-ups are corrected here. The re-review of `ecfc10f6a` approved with follow-ups,
which are folded in.

**Decider:** the owner

**Tracker:** `bd-01M5MT30T5GFWWM07V8Q566J0B` (S3c of the evidence-binding epic, under S3
`bd-01M5MR41A9HTQH1MHC57JK28DK`). S3d (`bd-01M5MT3DRFC5PY4BEBBVGVD168`) makes the assessor read the
graph built here. The track source this ADR does without is `bd-01M5PYJ7237CQAGMY0GC7ESZM1`.

## Context

ADR 0024 makes identity a licensed link between mentions, checked by `ReferentIdentity.resolve`
(`document/referent.scala`) against a `ReferentContext` of mentions, observed track coverage and
presence claims. It requires the source graph to be built once per film without recall text, lets a
language model only propose, and keeps a receipt for every accepted and refused proposal. ADR 0024
amendments 4 and 5 make different names a contributory hint and make deixis sufficient only where a
presence claim covers the utterance. Nothing yet feeds the resolver real film input.

What exists for Sherlock part 2 (media part b of the owner-declared presentation edition,
`docs/data/sherlock/timebase-repair.json`):

- **Annotation.** The Chen et al. 2017 table (`Sherlock_Segments_1000_NN_2017.tsv`, identity in
  `annotation-lineage.json`). For each segment, `Name - All` lists who appears and `Name - Speaking`
  names the speaker. Run-2 seconds map to part-b playback seconds by
  `annotationToPlaybackCrosswalk`, which is the identity. Segments are whole seconds, one to ten
  seconds long.
- **Speech recognition.**
  `data/transcription/unified-v1/full/whisper_mlx/sherlock/Sherlock_part2_imovie/` gives word
  timings, per-word probabilities and decoder segments. Its source SHA-256 equals part b's byte
  identity (`source-manifest.json`), and its clock is `source_media` seconds from 0 over 1546.913 s,
  the duration of the stream `media-part-b-audio-0`, which starts at 0.0 like the picture stream.
  Decoder segments 10 (78.2-119.71 s) and 11 (119.71-204.14 s) are 41 and 84 s long, so the word
  timings inside them are not credible.
- **Frame observations.** S3b (`bd-01M5MT2M0X9YZCD654BGAX087W`) measured sampled frame pairs as
  weak: at most `AppearanceMatch` contributory, and no "different" edge from posture or orientation.
- **No person tracker, face detector or track coverage**, so `ContinuousTrack` and
  `SimultaneousTracks` have no input.

The body case, in part-b seconds. Words are cited by their `words.tsv` index, not quoted (§6).

| Row | Seconds | `Name - All` | `Name - Speaking` | Speech |
|---|---|---|---|---|
| 502 | 74-84 | Sherlock, John, Lestrade, and four role-labelled people | Lestrade | words 102-103, the victim's name, at 74.64-75.30; word 102 has probability 0.37 |
| 503 | 84-94 | Jennifer Wilson | none | none |
| 507 | 110-111 | Jennifer Wilson | none | none |
| 526 | 152-155 | Sherlock, Jennifer Wilson | none | inside decoder segment 11 |
| 575 | 249-251 | John | John | words 274-278, an indefinite description of a dead woman, at 250.02-250.96 |

## Decision

### 1. Scope and inputs

S3c builds the graph for part b, rows 500-585 (67-279 s), the corpse-room scenes. Its inputs are a
closed list, each pinned by SHA-256 in the run manifest: the media part's byte identity and stream
facts, the annotation TSV, the speech-recognition words and segments, the role-label list (§2), and,
for proposals, the language-model recordings. The producer refuses any other input path. A court
(§7) shows that a recall path is refused and that deleting the guard fails a named test.

### 2. Mentions

- **Visual.** One mention per person listed in `Name - All` for each row in scope, anchored in media
  time on the stream `media-part-b-video-0` over the row's interval.
  - A character name is attached as a `ProperName`, exactly as written (owner decision N).
  - An entry on the role-label list (a profession or description such as a uniformed officer, or a
    counted group of them) yields unnamed mentions, as many as its count. The list is a reviewed
    input of the run; in scope it covers only row 502's four entries. Role labels are not names, so
    two officers in different rows are never joined by name.
  - An entry that is neither a listed character nor a listed role label withholds the row's presence
    claim (§3), with a receipt.
- **Dialogue.** One mention per referring expression in the recognized words, anchored in media time
  on `media-part-b-audio-0` over the expression's word timings:
  - third-person pronouns, including their contractions ("she's", "he's"), matched after the
    core's case folding with trailing punctuation removed;
  - the person nouns `woman`, `man`, `girl`, `boy`, `lady`, `body` and `victim`;
  - every token sequence equal to a character name in the in-scope `Name - All` vocabulary.
  
  Each decoder segment is one `Speech` context. Only a name mention carries a `ProperName`, and a
  pronoun or description never does.
- **Refused speech.** These guards apply before any proposal, each with a receipt:
  - A decoder segment longer than 30 s yields no mention, because its timings are not credible.
    Segments 10 and 11 are refused.
  - A pronoun or noun whose word probability is below 0.5 yields no mention.
  - A name mention is admitted on exact equality with the closed name vocabulary, whatever its word
    probabilities. It errs if a misrecognition lands exactly on another in-scope character's name,
    or if the recognizer hallucinates a name inside an accepted segment. *Disclosed:* this rule was
    written after seeing that word 102 has probability 0.37. Under a per-word guard the body's name
    link would be refused (owner decision N covers both readings).

### 3. Presence

Each row in scope yields one `PresenceClaim` over its interval, on the picture stream, with basis
`HumanReviewed`. Its `SourceDerivationReceipt` cites the annotation's SHA-256, the row number and
the column. This is owner decision 1.

The claim certifies who appears anywhere in the row, not who is on screen at each instant. Deixis
treats a row listing one person as having nobody else in it. That is an **accepted risk, not a
fact**: an unnamed extra may be missing (decision 1's caveat), and an off-screen speaker may be
unlisted.

Two rules narrow the risk:
- **Boundary margin.** A dialogue mention counts as inside a row only if its extent lies at least
  0.5 s inside the row's interval at both ends. Recognizer word timing drifts by about that much.
  Otherwise the producer does not propose deixis for it. Only the producer enforces this: a mention
  inside a row but within 0.5 s of its edge is still covered by that row's claim, so the margin has
  its own court (§7). A 1-second row can never hold an utterance with that margin, so speech in the
  1-second rows (507, 512 and others) is never deictic, which fails closed.
- **Uncertified mentions.** A mention outside every certified row cannot be deictic.

### 4. Proposals, and who may make them

Every link reaches the resolver as a proposal, and every proposal and its outcome is a receipt row.

- **Deterministic `NamedReference`** (only under owner decision N). For each name, each later
  mention bearing it is proposed as the same person as the first, in time order.
- **Deixis and descriptions come only from the model.** A pronoun can refer to someone absent, so
  "only one person present" is necessary and not sufficient. For each admitted pronoun or noun, the
  model reads the recognized words of its decoder segment and the `Name - All` and `Name - Speaking`
  entries of the overlapping and adjacent rows, but not the scene descriptions. It returns a deictic
  proposal to one visual mention, a set of compatible candidates, or "absent or unclear", each with
  a reason.
- **Producer guards on deixis, applied before the resolver,** each refusal with a receipt:
  - Only a third-person pronoun may be deictic. A description noun (woman, body, victim, and the
    rest) yields hints only.
  - A deictic proposal whose target is the speaking row's `Name - Speaking` person is refused. A
    third-person reference is almost never to the speaker.
  - A deictic proposal is refused when the row's `Name - Speaking` is blank, since the unlisted
    speaker may be the one person listed. It is also refused when the field names more than one
    person and the target is among them.
  - A mention outside every certified row (§3) yields no deictic proposal.
- **Mapping.**
  - A surviving deictic proposal becomes `IdentityLink.of(..., DeicticReference)`.
  - Each compatible candidate becomes `IdentityHint(CompatibleDescription, Toward)`.
  - The model never licenses a link, and a hint never resolves one (ADR 0024).
- **Calls.**
  - The model is the dated `deepseek-v4.1-flash` pin from the earlier assessment rounds, with
    fallback disabled and zero data retention.
  - Calls are content-keyed and recorded so the run replays offline.
  - The round has a USD 5 ceiling, accounted as reported, accounted upper bound and attempted bound
    (owner decision 2).
  - The model has prior knowledge of a widely seen episode. That is an uncontrolled input, so its
    proposals are not purely evidence-derived, and the guards and per-basis precision (§7) are what
    contain it.
- **Not used.** Frame observations and captions. S3b showed sampled pairs to be weak, and captions
  from the same frames are not independent corroboration. Track-level input waits for
  `bd-01M5PYJ7237CQAGMY0GC7ESZM1`.

### 5. The resolver run

A JVM command in `pipeline`, `sourceIdentity build INPUTS OUTPUT`, reads the producer's
`source-identity-inputs/v1` document. It:
- rebuilds part b's `SourceBundle` from the edition facts, with its picture and audio streams;
- constructs every mention, presence claim and link through the public checked factories;
- records each refusal with its `ReferentError`;
- calls `ReferentIdentity.resolve`.

Its output, `source-identity-graph/v1`, holds:
- the identity classes;
- for each dialogue mention, its `ReferentRelation` to every visual class, with the contributory
  hints between them (`hintsBetween`). `Open` means a licensed distinctness reopened a class, which
  this graph cannot produce without tracks. Hinted candidates alone leave a relation `Unknown`;
- every proposal, its producer-guard outcome, and its resolver outcome and reason;
- the run manifest and the SHA-256 of every input.

The output is written through a temporary directory and a move, as `videoSource` does. A stable
codec for S3d is S3d's decision.

### 6. Where the text lives

Inputs, recordings, outputs and labels contain recognized words and character names. They stay under
the local data root and never enter Git. Code and tests in Git use synthetic fixtures only. This ADR
cites dialogue by word index and annotation by row (design rule 14d). The character names in the
table are annotation identifiers, not dialogue or description text. They would be the first Sherlock
character names in Git documentation, which owner decision T settles.

### 7. Measurement and courts

- **Labels.**
  - Before any model call, the producer fixes a candidate universe deterministically: every admitted
    pronoun or noun mention, paired with every visual mention in its overlapping and adjacent rows.
  - A fresh-context agent labels each pair (same person, different, can't tell) from the recognized
    words and frames, blind to proposals and outputs.
  - The label file is frozen by SHA-256. The labels are agent judgments, as in S3b, and are reported
    as such. The owner may replace them with human labels.
- **Report.** For each basis class:
  - accepted, refused and `Unknown` counts against the universe;
  - precision, with N and a Wilson 95% interval. With a handful of pairs the interval is wide, and
    the report says so.
- **Annotation-derived links.** Every `NamedReference` link is annotation-derived, spoken-to-visual
  ones included: a labeller can only put a name to a face through the annotation or the dialogue.
  They are reported as "annotation-derived, unmeasured".
- **Courts (SD1).** Each needs a mutant that fails a named test while its siblings pass:
  - the recall-path refusal;
  - the segment-length and probability guards;
  - the role-label rule;
  - the boundary margin;
  - the speaker guard;
  - the description-noun guard.

### 8. What the body case should give

Derived from the rules as written:

- **Under decision N (yes):** the visual mentions of Jennifer Wilson, in every row in scope that
  lists her, are `Same`, by `NamedReference`. The spoken name, words 102-103 in row 502, is `Same`
  as them by name. That link needs no presence, so row 502's crowd does not matter. Under N (no),
  every one of these is `Unknown`.
- **The description at row 575** (words 274-278) is a noun, so it yields hints only, never deixis.
  The speaker guard would also refuse a deictic proposal to John, the only person listed. Its
  relation to Jennifer Wilson is `Unknown`, with a `CompatibleDescription` hint if the model
  proposes her.
- **A pronoun in a one-person row about someone absent,** such as word 153 in row 552, where the
  only person listed is the speaker: the speaker guard refuses a deictic proposal, and the relation
  stays `Unknown`.

## Owner decisions

1. **Presence (decided 2026-10-24).** `Name - All` counts as human-reviewed presence for the rows in
   scope. Recorded caveat: it names characters, so an unnamed extra may be missing, and its rows are
   coarse.
2. **Spend (decided 2026-10-24).** A USD 5 ceiling for the round on the dated DeepSeek pin, with
   recording and replay.
3. **Tracker (decided 2026-10-24).** Deferred, not dropped: `bd-01M5PYJ7237CQAGMY0GC7ESZM1`.

**N. Names (decided 2026-10-24: yes).** May the `Name - All` character names be attached to visual
mentions, and may a spoken name be admitted on exact equality with that vocabulary despite low word
probability (§2)?
- *Recommended: yes.* The annotation is human-reviewed and recall-blind, and it is the only
  cross-shot evidence until a tracker exists. Exact equality with a closed list of in-scope full
  names is a stronger lexical check than a word probability.
- *Cost:* the graph's visual identities become the annotation's, so the annotation can no longer be
  independent gold for them (§7). The low-probability admission is a rule chosen after seeing the
  case.
- *If no:* visual mentions carry no names, identity across shots is `Unknown`, and the body link is
  `Unknown`.

**A. The acceptance reading (decided 2026-10-24: `Unknown` with hints).** The bead asks for the body
link to be "either licensed with a cited basis or Open with candidates". In the resolver's terms,
`Open` is a class reopened by a licensed distinctness, which this graph cannot produce.
- *Recommended:* read it as "unlicensed, with its candidates recorded", which is `Unknown` with
  hints, and amend the bead.
- *Alternative:* keep `Open` literal, which makes the bead wait for the tracker.

**T. Character names in this ADR (decided 2026-10-24: yes).** May the annotation's character names
appear in this document, as identifiers?
- *Recommended: yes.* They are identifiers, not excerpts, and the ADR cannot state the body case
  without them.
- *If no:* the table uses row numbers and opaque person labels.

## Consequences

- The resolver gets its first real film input, and S3d has a graph to consume.
- The body link's outcome follows from licences that can be audited, not from a per-question
  judgment.
- Every accepted and refused proposal is inspectable, and the run replays offline from its
  recordings.

### Known limits

- Presence is row-granular, and segment boundaries are whole seconds.
- Names are compared after the core's case and whitespace folding only. A title plus surname does
  not join a first name, which fails safe (ADR 0024 amendment 4). An alias table is not in S3c.
- Mentions in refused decoder segments, including segments 10 and 11, are missing from the graph.
- Deixis rests on the model's proposal that a pronoun refers to someone present. The resolver checks
  only presence, and the producer only the speaker and the noun class. An off-screen referent in a
  one-person row where the listed person is not speaking is a residual error class, measured by §7.
- The scope is 86 rows of one part.

## Rejected alternatives

- **Building tracks first.** Deferred by owner decision 3. It is the only route to cross-shot
  identity that does not depend on the annotation.
- **Letting the model license links.** That breaks rule 13 and ADR 0024: the model proposes, and the
  resolver decides.
- **Deterministic deixis for every pronoun said while one person is present.** Pronouns refer to
  absent people, as at word 153 in row 552. That would license confident wrong identities.
- **A purely deterministic, speaker-aware deixis rule with no model.** It would propose deixis for
  every third-person pronoun in a one-person, non-speaker row, including ones about someone
  off-screen. The model's judgment of whether the pronoun refers to the person shown is the step it
  lacks.
- **Deixis from description nouns.** "A woman", said while only a man is on screen, would be linked
  to him.
- **Using frame observations or captions as identity evidence.** S3b measured them as weak, and
  captions from the same frames are not independent.
- **Feeding the model the annotation's scene descriptions.** They often state the answer, which
  would make the proposals a restatement of the annotation, and they would contaminate the label
  set.
- **Accepting long decoder segments.** An 84-second segment's word timings would put an utterance in
  the wrong row, and presence would then certify the wrong people.
- **A per-word probability guard on names.** It refuses the body's name link on one low-probability
  token, although exact equality with the closed name list already rules out nearly every
  misrecognition. It is kept as the outcome if decision N is "no".
