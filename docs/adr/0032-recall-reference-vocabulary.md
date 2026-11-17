# ADR 0032 — Recall reference vocabulary: talk, topic frame, address and programme

**Status:** Proposed, 2026-11-17. Not binding until the owner decides it. No runtime type,
codec or producer change is made by this record.

**Decider:** the owner

**Tracker:** case study 2, `bd-01M7JRB11CHP7EPC35QQ12DT41`.

**Evidence:** the case-study-2 design and its revisions,
`docs/plans/2026-11-16-cs2-mapping-object.md`, and the draft pre-registration,
`docs/plans/2026-11-17-cs2-preregistration.md`, both on branch `claude/cs2-mapping-object` at
`a93763b46`. The harness is `tools/case-studies/recall-precision/cs2/` on the same branch. Every
number below is FilmFestival development data; the six sealed participants were not used. The
scoring rules were revised after seeing development results, so no number is a validation.

## Context

[ADR 0030](0030-detail-account-and-derived-analysis.md) fixes the detail account: a reference is
single, joint, broad, alternative, unresolved or a typed external destination, and fidelity is a
separate assessment. [ADR 0031](0031-reference-measures-and-held-level-projection.md) adds
measures on such a reference and an onset-point projection onto recall time.

Case study 2 built a probabilistic recall-to-film mapping for a corpus of ten short films seen in
one sequence. The harness had to represent talk that ADR 0030 has no words for:

- **Speech about a film without a moment in it:** naming a film, picking it out by its place in
  the viewing order, or remarking on the whole sequence.
- **Speech that does something other than describe:** evaluating a film, remembering or failing
  to remember, searching memory, closing one film and opening the next, ending the recall.
- **Material outside the stimulus:** two of the films are excerpts, so speech can point at the
  rest of the feature without leaving the work.
- **Blends and comparisons:** content from one film told as part of another, and two films set
  side by side.
- **A composition the library cannot address:** one work shown twice, and a film axis that spans
  scan runs.

The harness recorded its answers as declarations, not decisions. One of them, putting non-film
states inside the destination simplex, silently extends ADR 0031 §3. This record proposes the
vocabulary, so that a library slice does not inherit a harness choice by default.

**The library today:**

- `ProvisionalReference` (`align/referencemeasures.scala`) has single, joint, broad, alternative,
  external destination, unresolved and not-resolved meanings. It has no work, position or
  composition address and no talk type.
- `OnsetLevelProjection` has `NotApplicableReason.Discourse` and `NoSourceSupport` and the
  absences `NoSample`, `NotApplicableOnly` and `UncoveredOnly`.
- `ExternalState` (`align/matrix.scala`, ADR 0001) mixes open-world destinations
  (`Association`, `Intrusion`) with a talk type (`Commentary`), a fidelity-like grade
  (`SourceConsistentInference`) and an aligner outcome (`Unranked`).
- `ReferenceAnswers.Talk` (`pipeline/agentmapping`) is a producer wire token set: episodic,
  metamemory, commentary, fragment, repair.
- `TrackComposition` (`core/source.scala`) maps part axes into **one** edition axis through
  occurrence-labelled segments with disjoint targets. It has no work identity and no viewing
  order, and a work shown twice cannot be expressed, since both occurrences would target the same
  edition interval. This is read from the code (*permits*), not run.
- `RecallTimingIntake.readLexical` refuses a nonblank cell that holds more than one lexical word.
  `read` (`external-clock-exact-replay/v1`) treats each cell as one word, which is what
  `ReferenceParity` uses.

## Decision

### 1. Talk type and referential address are separate axes; topic frame is a third field

**Decision.** A recall reference carries three fields:

- **Talk type**: what the stretch of speech does. A closed enum plus `Custom(namespace, label)`:
  `Content`, `Evaluation` (commentary), `Metamemory`, `SearchingWithinWork`,
  `SearchingBetweenWorks`, `Closing`, `EndingRecall`, `OffTask`, `Fragment`.
- **Referential address**: what the words point at (§2). Only `Content` carries one.
- **Topic frame**: which work the discourse is about at this point. It is either framed, with
  self-reported weights over the programme's works (Σw ≤ 1, as in ADR 0031 §1), or unframed. A
  frame that was not produced is unavailable, never unframed.

Commentary, metamemory, searching within a work and closing carry a topic frame and no address.
A referring expression inside an evaluation is its own `Content` reference over its own span; the
rest of the evaluation stays non-addressed. The topic frame never creates location mass: a
consumer that falls back on it, as the case-study scorer does, declares that as its own rule.

**Rejected:** one state enum for both, as pass 1 v1 used (content, work, memory search, gave up,
off task, fragment). Its `work` state meant both naming a film and evaluating one, and the cached
replies cannot separate them; the harness had to report two variants of one reading. Also
rejected: deriving the topic frame from the local address. A film is held as discourse topic
across many units, and a blend is exactly a reference whose address and frame differ.

**Types.**

- Talk type and topic frame are values; the lawful combination is not. Some individually lawful
  values combine falsely (an evaluation with a moment address, a framed search between works), so
  a `RecallTalk` that binds talk, frame and address is a `final` non-case class built by a checked
  factory (rule 8). The table of lawful combinations is part of this decision:

  | Talk type | Topic frame | Address |
  |---|---|---|
  | `Content` | framed or unframed | required (§2) |
  | `Evaluation`, `Metamemory`, `Closing` | framed or unframed | none |
  | `SearchingWithinWork` | framed | none |
  | `SearchingBetweenWorks`, `EndingRecall`, `OffTask` | unframed | none; a state (§3) |
  | `Fragment`, `Custom` | either | none |

- `Custom` talk carries no address and no state, so an unknown talk type fails closed.
- Refusals are typed values (rule 12): `AddressForbidden(talk)`, `AddressRequired`,
  `FrameRequired(talk)`, `FrameForbidden(talk)`, `UnknownWork`, and the weight refusals of
  `ProvisionalReference`.
- `ReferenceAnswers.Talk` stays a wire token set. Mapping it onto `RecallTalk` is a declared
  conversion in `pipeline`, never an identity.

### 2. Address classes

**Decision.** A `Content` reference has exactly one address class:

- **Moment**: an ADR 0030 reference over source units (single, joint, broad or alternative),
  unchanged.
- **Work**: the speech picks out a work without a moment. It is its own class and never a broad
  reference over the work's seconds.
- **Position**: the speech picks out a work by its place in an order. It records the anchor
  span, a typed ordinal, and a reading: `Viewing`, `RecallOrder` (the order in which the speaker's
  topic frames first name works) or `Custom`. Its resolution to a work is derived by a declared
  resolver from the programme and, for `RecallOrder`, the account's frames. A producer's stated
  resolution is kept as an observation and can be wrong, which is scored as such.
- **Composition**: the whole programme, not any one work.
- **In work, outside stimulus**: material of a work that was not shown. Lawful only for a work
  that the programme declares an excerpt. It is not external.
- **External**: an external association, typed `OtherSpecific`, `Personal`, `GeneralKnowledge` or
  `Custom`, as ADR 0030's internal/external classification names them.

External is both, on different axes, and the two never imply each other. As an address it is a
destination: the words point outside the programme. ADR 0030's `external` fidelity grade assesses
a claim, and it neither sets nor implies an external address. That is ADR 0030's own rule that
fidelity never implies address.

**Recaller inference is a fidelity grade, not an address.** An inference about a moment keeps the
moment's address, and ADR 0030 grades it `recaller-inference`. There is no inference address
class.

**Rejected:** a work-level reference as a broad reference over all of a work's seconds. One
mention of a film would become hundreds of seconds of coverage (case-study design, revision 2).
Also rejected: reusing `ExternalState` for addresses. It mixes a talk type, a fidelity grade and
an aligner outcome with destinations, so an address built from it could not be told apart from
an assessment (rule 7).

**Types.**

- `RecallAddress` is a sealed family with one member per class. `Work`, `Composition` and
  `External` are plain cases. `Position` and `InWorkOutsideStimulus` depend on the programme
  (§4), so they are `final` non-case classes built only by checked factories over it, and
  `Position`'s resolution is derived, never accepted from the caller. Every work an address names
  is checked against the programme when the `RecallTalk` is built.
- Refusals: `UnknownWork`, `NotAnExcerpt(work)`, `StimulusCoverageUnknown(work)` (fail closed),
  `PositionOutOfRange`, and `PositionUnresolved` as a typed outcome rather than a refusal, since
  an unresolved position is a legitimate result (rule 13).
- `ExternalState` and the ADR 0001 alignment matrix are unchanged.

### 3. Non-work states are typed destinations, and the destination simplex is declared

**Decision.** Three talk types, which are always unframed, have a **non-work state** as their
destination: `SearchingBetweenWorks`, `EndingRecall` and `OffTask`. The state is derived from the
talk type by the `RecallTalk` factory and cannot be asserted separately; `Custom` talk has none.
A non-work state is not an address: the speech points at no material.

**The simplex is a declared choice.** A `LocationDistribution` row is a simplex over a declared
destination set. The default set is film units at a declared grain, the other address classes
(work, position, composition, in work outside stimulus, external), the held-out states, the
non-work states, and the known unassigned share. A sampled row sums to 1.

This is new: ADR 0031 did not decide it, and the harness adopted it without a decision line. It
is reconciled with ADR 0031 §3 as follows:

- **They are different estimands.** ADR 0031 §3 projects a level of a reference measure such as
  extent. A non-work state has no film support and so no level; there it is not applicable, with
  a new typed reason, `NotApplicableReason.NonWorkState`. A `LocationDistribution` asks where the
  speech points, and a non-work state is a legitimate answer to that question.
- **Neither projection borrows the other's rule.** A location row never reports "only not
  applicable" for a bin of non-work states, and a level projection never gives a non-work state a
  number.
- **Conditioning is explicit.** A distribution over film-addressed destinations only is derived by
  conditioning on that subset. It reports the excluded mass beside every row and never
  renormalizes silently.

**Evidence, with its limit.** The first orthogonal-talk build treated the three states as
non-addressed talk. The FilmFestival film term then fell from a skill of 0.145 to 0.053 against
the duration baseline, and the 83 non-film gold rows rose to 5.33 bits. The states were restored
as destinations. This was a correction made after seeing the result, on development data.

**Rejected:** non-work states as a typed absence outside the simplex, which is ADR 0031 §3's rule
carried over. The film-or-not question would then have no forecast at all, and a non-work bin
could not be told apart from a bin of fragments.

**Types.** `NonWorkState` is an enum plus `Custom`. The destination set is a value carried by the
distribution, so two distributions over different sets are different declarations. The simplex
row is a `final` class built only by the projection.

### 4. The address of a composition: the programme

**Decision.** A new portable `Programme` describes a multi-work composition. Film material is
addressed by **(work, occurrence, part, seconds)**:

- A **work** is a film's identity (`WorkId`), with a declared stimulus coverage: `Complete`,
  `Excerpt` or `Unknown`.
- An **occurrence** is one presentation slot of one work. It has a unique viewing position. A work
  shown twice has two occurrences.
- A **part** is one playback axis, such as a scan run. A part may hold several occurrences, and an
  occurrence may span parts, with one segment per part.
- **Seconds** are on the part's own axis. No global timeline is constructed.

**A constituent spanning parts** keeps its support per part and is never joined onto one axis.
Where an occurrence spans parts, its **cross-part film axis** is that occurrence's own
`TrackComposition` onto the work's edition axis, selected as `TemporalSupport.Selection.Occurrence`.
Each occurrence has its own composition, so two occurrences of one work never compete for the
same edition interval.

**Rejected:**

- **A global concatenated timeline.** It invents a clock that no viewer or scanner had, and it
  puts two films on one axis, the failure that ADR 0025 guards against.
- **One source per work with no programme.** It loses viewing order, so position addresses cannot
  be resolved, and it cannot hold a span that crosses works.
- **Relaxing `TrackComposition`'s disjoint-target rule.** That rule protects edition
  correspondence within one work.

**Types.**

- `Programme` is a `final` non-case class in `core`, beside `TrackComposition`, because `align`
  does not depend on `corpus`. It is validated as a whole:
  - positions are unique;
  - each occurrence names a declared work and has segments on distinct parts;
  - each segment lies within its part.
- A source unit's work is derived from its occurrence, never asserted.
- Refusals are `DomainError` values, as in `core/source.scala`.
- Corpus adapters declare their programme; Sherlock is one work with one occurrence over two
  parts.

### 5. Relations between references

**Decision.** Two relations join references in one account. Neither is a member of a reference,
and neither enters a distribution.

- **Comparison** joins two distinct references, usually work-level, with a kind: `Similarity`,
  `Contrast` or `Custom`. It is neither joint nor alternative.
- **Attribution** records a blend or confusion: the framed work, which is what the speaker says
  the content belongs to, and a content reference, whose address lies in another work. Location
  mass follows the content reference's address. The framed work stays in the topic frame. Source
  attribution is its own quantity (rule 7).

**Attribution is never an alternative.** An alternative is uncertainty about which material the
words mean. An attribution is a claim about two things at once: what was framed and where the
content came from. Splitting mass between them would turn a confusion into a hedge.

**Rejected:** an alternative of {framed work, content source}. That would score a confident
confusion as calibrated doubt, and it would hide the source-monitoring error that the relation
exists to record.

**Types.**

- Both relations are built by checked factories over the account, which check that every
  reference exists and the two references differ.
- Attribution additionally checks that the content's work, derived through the programme, differs
  from the framed work; otherwise it refuses with `SameWork`.
- A comparison's identity is derived from its references and kind, in either order.
- Neither type is accepted where an ADR 0031 alternative member is expected. An outside-package
  compile-refusal probe with a positive control proves this.

### 6. Multi-token transcript cells

**Decision.** A transcript cell is the timed unit. A nonblank cell that holds several lexical
tokens, such as a hyphenated word, a clock time or an escaped apostrophe, is **one word** of the
recall inventory, and its onset belongs to the cell:

- No cell onset is ever divided among, or interpolated across, its tokens.
- Lexical tokens are a derived layer over the cell. Each token maps to exactly one cell and
  shares that cell's timing observation, tagged as shared rather than measured.
- Every word count in a ledger or projection counts cells.
- A reference covers a cell when its UTF-16 support intersects the cell (ADR 0031 §3).

The adapter names its recipe. `external-clock-exact-replay/v1` is the cell rule.
`external-clock-single-lexical-word-replay/v1` stays strict and keeps refusing multi-token cells,
for corpora that declare one lexical word per cell.

**Rejected:**

- **Dividing an onset among a cell's tokens.** It manufactures timing precision that the source
  does not have (rule 7).
- **Refusing every multi-token transcript.** That would reject ordinary transcripts for an
  orthographic accident.

**Types.** The token-to-cell layer is a checked map from a `RecallInventory`. The recipe is an
explicit value in the intake result, and a recipe mismatch is a typed `Refusal`, as now.

### 7. Uncovered words, fragments and non-addressed talk are ledger counts

**Decision.** Words that no reference covers, `Fragment` talk, and every other non-addressed talk
type are **counts in a per-bin ledger**, by talk type, and never destinations. None is a
zero-mass entry in a distribution, and none becomes unassigned mass. The unassigned share is only
a reference's own known remainder (ADR 0031 §1). This applies the measurement contract:
unavailable is never zero.

In ADR 0031 §3's ledger, a non-addressed stretch is a not-applicable reference (`Discourse`), so
its words are covered. The ledger now splits covered words into addressed, state and
non-addressed words, the last by talk type. With the two uncovered counts they still partition
the words with an onset. The level projection's absences are unchanged.

A `LocationDistribution` bin with no addressed or state sample has no simplex. Its typed absence
is one of:

- **No sample**, as in ADR 0031;
- **Only non-addressed**;
- **Only uncovered**.

Only non-addressed takes precedence over only uncovered, and the counts keep the full picture.

**Rejected:** a zero-mass `fragment` or `uncovered` destination in the simplex. It cannot be told
apart from an assessed zero, and it would let abstention pass as a destination.

**Types.** The ledger fields are added to `OnsetLevelProjection.Ledger`. The location absence is
its own enum on `LocationDistribution`, not a new case of the level projection's `Absence`. Both
are produced only by their projection.

## Findings recorded with this decision

**Pass 1 v2 put this vocabulary into a prompt and scored worse.** Pass 1 v2 asked the producer
for separate talk, topic-frame and address fields per unit. The ablation on the develop five,
under a rule recorded before the calls, gave these mean bits (lower is better):

| Condition | Scene | Work | Film |
|---|---|---|---|
| v1 + per-call layout | 1.054 | 0.090 | 0.140 |
| v1 + prefix layout | 1.013 | 0.088 | 0.140 |
| v2 + prefix layout | 1.105 | 0.109 | 0.179 |

Against v1 + prefix, v2 was worse on all three terms, each beyond its noise band (scene +0.092
against 0.026, work +0.022 against 0.011, film +0.039 against 0.005). It was dropped, and the
pre-registered run uses v1. So the vocabulary is decided here as a representation, separately
from what any producer is asked. A producer may reach these fields through a declared conversion
of a simpler reply, as the frozen harness does.

**The harness already runs the decisions on development data.** The frozen configuration
(`talk: orthogonal`, `fallback: frame-film`) implements §1, §3 and §7, with these differences:

- frame weights summing above 1 are normalized there, whereas the library refuses them (§1);
- an unframed evaluation is non-addressed talk under pass 1 v1, but was a non-work commentary
  state in the dropped v2 converter. This record follows v1.

## Open questions for the owner

1. **Placement of `Programme`.** `core` is proposed because `align` does not depend on `corpus`.
   The alternative is an `align` → `corpus` dependency.
2. **Unframed evaluation.** This record makes it non-addressed talk, as pass 1 v1 does. The
   case-study gap list and the dropped v2 converter made it a non-work commentary state.
3. **Searching within a work.** Here it is non-addressed talk with a topic frame, although the
   FilmFestival gold codes film search at film level. Should the library ever give it work-level
   mass, or only a scorer's declared fallback?
4. **Frame weights above 1.** The library refuses them, while the frozen harness normalizes. Slice
   8 must either report the refused rows or adopt a declared normalization.
5. **`ExternalState`.** It is left unchanged here. Retiring its `Commentary` and
   `SourceConsistentInference` cases as destinations would be a separate ADR.
6. **Interpolated onsets.** FilmFestival's onsets are interpolated within utterances
   (`wordOnsetsAreMeasured: false`). How intake declares that is not decided here.

## Deferred

- **Fragment subtypes** (filler, false start, connective): one `Fragment` type until a consumer
  needs a distinction.
- **Questions and revisits.** A revisit is derivable from successive addresses and topic frames,
  so it gets no type of its own; questions wait for a corpus that codes them.
- **The switch-cue list** (the lexical cues that open and close a work): a producer and segmenter
  concern, not vocabulary.
- **The gold crosswalk.** Mapping a corpus's own codes onto this vocabulary belongs to that
  corpus's adapter, never to shared code.

## Library slices that would follow

Each is about a day's work with one acceptance criterion. Slices 2 to 4 need slice 1.

1. **`Programme` in `core`.** Acceptance: a synthetic programme holding one work shown twice and
   one occurrence over two parts constructs, and the units' works are derived from it.
2. **`RecallTalk` and `TopicFrame` in `align`.** Acceptance: every cell of the §1 table that is
   unlawful is refused with its named reason, in one table-driven test.
3. **`RecallAddress` classes beside `ProvisionalReference`.** Acceptance: the position resolver
   passes known-answer tests for the viewing and recall-order readings on a synthetic programme,
   including an unresolved position as a typed outcome.
4. **`Comparison` and `Attribution`.** Acceptance: a same-work attribution is refused, and a
   compile-refusal probe with a positive control shows neither is accepted as an alternative
   member.
5. **Ledger counts by talk type in `OnsetLevelProjection`.** Acceptance: a property test shows
   that addressed, state, non-addressed and uncovered words partition the words with an onset in
   every bin.
6. **`LocationDistribution` with a declared destination set.** Acceptance: a discipline law that
   every sampled row sums to 1 within tolerance, and that conditioning on film destinations
   reports the excluded mass, passes on JVM, JS and Native.
7. **The cell rule in recall intake.** Acceptance: a synthetic fixture with a hyphenated cell and
   a clock-time cell reads under the cell recipe as one word per cell with undivided onsets, and
   the lexical recipe still refuses it.
8. **Conversion of the frozen harness replies in `pipeline/agentmapping`.** Acceptance: on the
   develop five's recorded replies, with no model call, the library account reproduces the
   harness ledger counts by talk type and state.

## Rejected alternatives (summary)

- One enum for talk and address (§1).
- A topic frame derived from the local address (§1).
- Work-level references as broad references over seconds (§2).
- `ExternalState` as the address vocabulary (§2).
- Non-work states as a typed absence outside the simplex (§3).
- A global timeline, a programme-less source per work, or a relaxed `TrackComposition` (§4).
- Attribution as an alternative (§5).
- Divided onsets, or refusing multi-token transcripts (§6).
- Zero-mass destinations for uncovered or non-addressed words (§7).

## Out of scope

- `CoverageMeasure` and the correspondence table (case-study design, projections 2 and 3).
- Scoring, fallbacks and baselines, which stay in research tooling.
- Producer prompts and wire compatibility.
- Fidelity grades, ADR 0030.
- Any scientific claim. FilmFestival's numbers here are development data.
