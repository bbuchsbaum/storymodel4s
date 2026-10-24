# ADR 0027 — A recall-blind source identity graph for the Sherlock body scenes

**Status:** Proposed 2026-10-24 (AGENTS.md SD5). Two owner decisions are recorded under "Owner
decisions"; decision N, on names, is open.

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

What exists for Sherlock part 2 (the owner-declared presentation edition,
`docs/data/sherlock/timebase-repair.json`):

- **Annotation.** The Chen et al. 2017 table (`Sherlock_Segments_1000_NN_2017.tsv`, identity in
  `annotation-lineage.json`) has a `Name - All` column naming who appears in each segment. Run-2
  seconds map to part-2 playback seconds by `annotationToPlaybackCrosswalk`, which is the identity.
  Segments are whole seconds, one to ten seconds long.
- **Speech recognition.** `data/transcription/unified-v1/full/whisper_mlx/sherlock/` has word
  timings and decoder segments. Segment 11 of part 2 runs 119.71-204.14 s, so the word timings inside
  it are not credible.
- **Frame observations.** S3b (`bd-01M5MT2M0X9YZCD654BGAX087W`) measured sampled frame pairs as weak:
  at most `AppearanceMatch` contributory, and no "different" edge from posture or orientation.
- **No person tracker, face detector or track coverage**, so `ContinuousTrack` and
  `SimultaneousTracks` have no input.

The body case, in part-2 seconds:

| Row | Seconds | `Name - All` | Speech |
|---|---|---|---|
| 502 | 74-84 | Sherlock, John, Lestrade, Policewoman, 2 Policemen, Person in Coveralls | "her name's Jennifer Wilson" at 74.64-75.30 |
| 503 | 84-94 | Jennifer Wilson | none |
| 507 | 110-111 | Jennifer Wilson | none |
| 526 | 152-155 | Sherlock, Jennifer Wilson | inside the untrustworthy segment 11 |
| 575 | 249-251 | John | "There's a woman lying dead." at 250.38-250.96 |

## Decision

### 1. Scope and inputs

S3c builds the graph for part 2, rows 500-585 (67-279 s), the corpse-room scenes. Its inputs are a
closed list, each pinned by SHA-256 in the run manifest: the media part's byte identity, the
annotation TSV, the speech-recognition words and segments, and, for proposals, the language-model
recordings. The producer refuses any other input path, so no recall file can be read. The graph is
therefore recall-blind by construction, and the manifest proves which bytes it read.

### 2. Mentions

- **Visual.** One mention per person listed in `Name - All` for each row in scope, anchored in media
  time on the part-2 picture stream over the row's interval. A listed name is attached to its mention
  as a `ProperName`, exactly as written. A counted group ("2 Policemen") yields that many unnamed
  mentions. An entry the producer cannot parse withholds the row's presence claim (§3), with a
  receipt.
- **Dialogue.** One mention per referring expression in the recognized words: third-person pronouns,
  the person nouns `woman`, `man`, `girl`, `boy`, `lady`, `body` and `victim`, and every token
  sequence equal to a name in the in-scope `Name - All` vocabulary. Each decoder segment is one
  `Speech` context. Only the name tokens carry a `ProperName`; a pronoun or description never does.
- **Refused speech.** A decoder segment longer than 30 s, or a word whose recognition probability is
  below 0.5, yields no mention, with a receipt. Segment 11 is refused this way.

### 3. Presence

Each row in scope yields one `PresenceClaim` over its interval with basis `HumanReviewed`. Its
`SourceDerivationReceipt` cites the annotation's SHA-256, the row number and the column. This is
owner decision 1.

The claim certifies who appears anywhere in the row, not who is on screen at each instant. Deixis is
still sound within it: a row listing one person had nobody else in it at all. An utterance that
crosses a row boundary lies in no single claim's interval and is not certified, which fails closed.

### 4. Proposals, and who may make them

Every link reaches the resolver as a proposal, and every proposal and its outcome is a receipt row.

- **Deterministic `NamedReference`.** For each name, each later mention bearing it is proposed as
  the same person as the first, in time order. This includes visual-to-visual links between rows,
  which are only as good as the annotation (§7). Owner decision N decides whether these are admitted.
- **Language-model proposals only, for deixis and descriptions.** A pronoun can refer to someone
  absent, so "only one person present" is necessary for deixis and not sufficient. The resolver
  checks presence but cannot tell whether "he" is about the woman on screen. Deixis is therefore
  proposed only by the model, which reads the recognized words around the mention and the
  `Name - All` lists of the overlapping and adjacent rows, but not the scene descriptions. It
  returns, for each pronoun or description, either a deictic proposal to one visual mention, a set
  of compatible candidates, or "absent or unclear", each with a stated reason.
- **Mapping.** A deictic proposal becomes `IdentityLink.of(..., DeicticReference)`. Each compatible
  candidate becomes `IdentityHint(CompatibleDescription, Toward)`. The model never licenses a link,
  and a hint never resolves one (ADR 0024).
- **Calls.** The model is the dated `deepseek-v4.1-flash` pin used in the earlier assessment rounds,
  with fallback disabled and zero data retention. Calls are content-keyed and recorded so the run
  replays offline, under a USD 5 ceiling for the round, accounted as reported, accounted upper bound
  and attempted bound. This is owner decision 2.
- **Not used.** Frame observations and captions. S3b showed sampled pairs to be weak, and captions
  from the same frames are not independent corroboration (ADR 0024). Track-level input waits for
  `bd-01M5PYJ7237CQAGMY0GC7ESZM1`.

### 5. The resolver run

A JVM command in `pipeline`, `sourceIdentity build INPUTS OUTPUT`, reads the producer's
`source-identity-inputs/v1` document. It rebuilds the part-2 `SourceBundle` from the edition facts,
constructs every mention, presence claim and link through the public checked factories, records each
refusal with its `ReferentError`, and calls `ReferentIdentity.resolve`. Its output,
`source-identity-graph/v1`, holds:

- the identity classes;
- for each dialogue mention, its relation to every visual class (`Same`, `Distinct`, `Open` or
  `Unknown`), with the contributory hints recorded between them (`hintsBetween`). `Open` means a
  licensed distinctness reopened a class; hinted candidates alone leave a relation `Unknown`;
- every proposal with its outcome and reason;
- the run manifest and the SHA-256 of every input.

The output is written only through a temporary directory and a move, as `videoSource` does. Producing
`pipeline` vocabulary is in scope here. A stable codec for S3d is S3d's decision.

### 6. Where the text lives

Inputs, recordings and outputs contain recognized words and character names, so they stay under the
local data root and never enter Git. Code and tests in Git use synthetic fixtures only (design
rule 14d).

### 7. Measurement

Identity precision is reported per basis class against a frozen label set: one label per proposed
dialogue-to-visual pair, written before the run by a fresh-context agent from the recognized words and
frames, without the graph. The labels are fixed by SHA-256. They are agent judgments, as in S3b, and
are reported as such. The owner may replace them with human labels. Precision carries a Wilson 95%
interval.

`NamedReference` links between visual mentions derive from the same annotation that would label them,
so their precision is not measured here. It is reported as "annotation-derived, unmeasured".

### 8. What the body case should give

If decision N admits names, the depicted woman of rows 503 and 507 is `Same` as the spoken
"Jennifer Wilson" at 74.64 s, by `NamedReference`. "A woman lying dead" at 250.38 s has nobody
matching on screen: row 575 lists only John. Deixis refuses it. With no track source there is no
licensed distinctness either, so the honest result is `Unknown`, with a `CompatibleDescription` hint
toward Jennifer Wilson if the model proposes her. Text coreference, which could join it to the
earlier name, is deferred in ADR 0024.

The bead's acceptance asks for the link to be "either licensed with a cited basis or Open with
candidates". In the resolver's terms, `Open` is a class reopened by a licensed distinctness, which
this graph cannot produce. This ADR reads the acceptance as "unlicensed, with its candidates
recorded": `Unknown` with hints. That reading is part of what the owner accepts.

## Owner decisions

1. **Presence (decided 2026-10-24).** `Name - All` counts as human-reviewed presence for the rows in
   scope. Recorded caveat: it names characters, so an unnamed extra may be missing, and its rows are
   coarse.
2. **Spend (decided 2026-10-24).** A USD 5 ceiling for the round on the dated DeepSeek pin, with
   recording and replay.
3. **Tracker (decided 2026-10-24).** Deferred, not dropped: `bd-01M5PYJ7237CQAGMY0GC7ESZM1`.
- **N. Names (open).** May the `Name - All` names be attached to visual mentions, so that
  `NamedReference` joins a spoken name to the person annotated on screen, and joins one annotated
  person across rows? *Recommended: yes.* The annotation is human-reviewed and recall-blind, and it
  is the only cross-shot evidence until a tracker exists. The cost: the graph's visual identities are
  then the annotation's, so the annotation can no longer serve as independent gold for them (§7).
  *If no:* visual mentions carry no names, identity across shots is `Unknown`, and the body link is
  `Open` at best.

## Consequences

- The resolver gets its first real film input, and S3d has a graph to consume.
- The body link's outcome follows from licences that can be audited, not from a per-question
  judgment.
- Every accepted and refused proposal is inspectable, and the run replays offline from its
  recordings.

### Known limits

- Presence is row-granular, and segment boundaries are whole seconds.
- Names are compared after the core's lowercase and whitespace folding only. "Doctor Watson" does not
  join "John", which fails safe (ADR 0024 amendment 4). An alias table is not in S3c.
- A pronoun inside a refused decoder segment gets no mention, so the graph misses it.
- Deixis rests on the model's proposal that the pronoun refers to someone present. The resolver
  checks only the presence half, so per-basis precision (§7) is the safeguard.
- The scope is 86 rows of one part.

## Rejected alternatives

- **Building tracks first.** Deferred by owner decision 3. It is the only route to cross-shot
  identity that does not depend on the annotation.
- **Letting the model license links.** That breaks rule 13 and ADR 0024: the model proposes, and the
  resolver decides.
- **Deterministic deixis for every pronoun said while one person is present.** Pronouns refer to
  absent people, as at 277.86 s, "her own vomit", while only John is listed. That would license
  confident wrong identities.
- **Using frame observations or captions as identity evidence.** S3b measured them as weak, and
  captions from the same frames are not independent.
- **Feeding the model the annotation's scene descriptions.** They often state the answer ("Lestrade
  explains: Her name's Jennifer Wilson"), which would make the proposals a restatement of the
  annotation, and they would contaminate any later label set drawn from them.
- **Accepting long decoder segments.** An 85-second segment's word timings would put an utterance in
  the wrong row, and presence would then certify the wrong people.
