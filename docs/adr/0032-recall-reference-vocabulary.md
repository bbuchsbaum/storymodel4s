# ADR 0032 — Recall reference vocabulary: talk, topic frame, address and programme

**Status:** Proposed, 2026-11-17 (revision 3). Not binding until the owner rules. No runtime
type, codec or producer change is made by this record.

- **Revision 1** (`ffd38e022`) was the first draft.
- **Revision 2** (`0993a2413`) folded in the owner's resolutions of its six open questions.
- **Revision 3** answers a Codex council review of `0993a2413`. Three reviewers (security, tests,
  maintainability) each returned "acceptable with listed changes". The correctness reviewer and
  the synthesis produced no output, so there is no merged verdict. The reviewers read source and
  ran nothing; each finding was checked against the code before it was accepted. The findings
  and what changed are in [Review response](#review-response).

**Decider:** the owner

**Tracker:** case study 2, `bd-01M7JRB11CHP7EPC35QQ12DT41`.

**Evidence:** the case-study-2 design and its revisions,
`docs/plans/2026-11-16-cs2-mapping-object.md`, and the draft pre-registration,
`docs/plans/2026-11-17-cs2-preregistration.md`, both on branch `claude/cs2-mapping-object` at
`a93763b46`. The harness is `tools/case-studies/recall-precision/cs2/` on the same branch. Every
number below is FilmFestival development data; the six sealed participants were not used. The
scoring rules were revised after seeing development results, so no number is a validation.
Statements about library code are read from the code (*permits*), not run.

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
- **A composition the library cannot address:** several works in one viewing order, one of them
  shown twice, and a presentation that spans scan runs.

The harness recorded its answers as declarations, not decisions. One of them, putting non-film
states inside the destination simplex, silently extends ADR 0031 §3. This record proposes the
vocabulary, so that a library slice does not inherit a harness choice by default.

**The library today:**

- `ProvisionalReference` (`align/referencemeasures.scala`) has single, joint, broad, alternative,
  external destination, unresolved and not-resolved meanings. Weights are optional, and the
  remainder may be declared unknown. It has no work, position or composition address and no
  talk type.
- `OnsetLevelProjection` has `NotApplicableReason.Discourse` and `NoSourceSupport` and the
  absences `NoSample`, `NotApplicableOnly` and `UncoveredOnly`. Its request takes a
  `RecallTiming`.
- `RecallTiming` (`recall/timing.scala`) records a clock, a correspondence digest and a
  recording link. Each observation has a `Basis`, `SourceReported` or `Estimated`. Nothing says
  whether onsets were measured or interpolated.
- `ExternalState` (`align/matrix.scala`, ADR 0001) mixes open-world destinations
  (`Association`, `Intrusion`) with a talk type (`Commentary`), a fidelity-like grade
  (`SourceConsistentInference`) and an aligner outcome (`Unranked`). `HsmmResult` requires one row
  per recall unit, in recall order, each summing to 1.
- `TrackComposition` (`core/source.scala`) maps **one** source axis into a composed bundle's
  primary axis through occurrence-labelled segments. `TemporalSupport.Selection.Occurrence`
  selects exactly one such segment. Sherlock declares one composition per part. FilmFestival's
  bench mapping has one placeholder axis per scan run and no composition. There is no work
  identity, no viewing order, and no selection that reads one presentation across two parts.
- `RecallTimingIntake.readLexical` refuses a nonblank cell that holds more than one lexical word.
  `read` (`external-clock-exact-replay/v1`) treats each cell as one word, which is what
  `ReferenceParity` uses.

## Decision

### 1. Talk type and referential address are separate axes; topic frame is a third field

**Decision.** A `RecallTalk` is one anchored stretch of recall. It carries its exact UTF-16
support, its producer condition (ADR 0022) and three separately represented fields. The fields
constrain each other; they are not a free product.

- **Talk type**: what the stretch does. A closed enum plus `Custom(namespace, label)`:
  `Content`, `Evaluation` (commentary), `Metamemory`, `SearchingWithinWork`,
  `SearchingBetweenWorks`, `Closing`, `EndingRecall`, `OffTask`, `Fragment`.
- **Topic frame**: which work the discourse is about at this point. One of:
  - *framed*, with self-reported weights over the programme's works (Σw ≤ 1, as in ADR 0031 §1);
  - *unframed*: the producer says no work is in frame;
  - *unavailable*, with a processing outcome: the frame was not produced. Unavailable is never
    read as unframed.
- **Reference**: what the words point at. Only `Content` has one, and it always has one of:
  - *addressed*, with an address (§2);
  - *unresolved*: a reference was attempted and no referent was determined;
  - *not resolved*, with a processing outcome.

A **processing outcome** is one of ADR 0030's reference statuses, as `ProvisionalReference.Outcome`
already types them: not attempted, failed (with its `ProcessingFailure`, which covers a refused
or invalid reply) and search-limited. So content talk whose referent could not be determined
keeps its talk type and gets no fabricated address, and it never becomes a `Fragment`.

A unit whose talk type itself was not produced has no `RecallTalk`. It is recorded as a
processing outcome over its span, and its words count as uncovered (§7).

Commentary, metamemory, searching within a work and closing carry a topic frame and no reference.
A referring expression inside an evaluation is its own `Content` stretch over its own span; the
rest of the evaluation stays non-addressed. The topic frame never creates destination mass: a
consumer that falls back on it, as the case-study scorer does, declares that as its own rule.

Three consequences were decided with this section (owner, 2026-11-17):

- **An evaluation with no framed work is non-addressed talk**, as in the frozen harness. No
  commentary destination is invented for it.
- **Searching within a work gets no work-level mass in the library.** Only a scorer's declared
  fallback may assign it mass. A corpus whose gold codes such search at film level, as
  FilmFestival's does, maps it in that corpus adapter's crosswalk.
- **Frame weights summing above 1 are refused**, with the weight refusals of
  `ProvisionalReference`. The frozen harness normalizes them; that is a known parity deviation
  for slice 10, not a library rule.

**Rejected:** one state enum for both, as pass 1 v1 used (content, work, memory search, gave up,
off task, fragment). Its `work` state meant both naming a film and evaluating one, and the cached
replies cannot separate them; the harness had to report two variants of one reading. Also
rejected: deriving the topic frame from the local address. A film is held as discourse topic
across many units, and a blend is exactly a reference whose address and frame differ. Also
rejected: a non-work commentary destination for an unframed evaluation, as the dropped v2
converter used. Commentary is a talk type, and a destination for it would put what the speaker
does on the same axis as where the speech points.

**Types.**

- `RecallTalk` is a `final` non-case class built by a checked factory (rule 8), because some
  individually lawful values combine falsely: an evaluation with a moment address, a framed
  search between works. Only a contradiction is unlawful. A missing observation never is.
- The table of lawful combinations below is part of this decision.
- `Custom` talk carries no reference and no state, so an unknown talk type fails closed.
- Refusals are typed values (rule 12): `ReferenceForbidden(talk)`, `ReferenceRequired`,
  `FrameRequired(talk)` for an unframed search within a work, `FrameForbidden(talk)` for a framed
  state, `UnknownWork`, `ForeignProgramme` (§2), and the weight refusals of
  `ProvisionalReference`.
- `ReferenceAnswers.Talk` stays a wire token set. Mapping it onto `RecallTalk` is a declared
  conversion in `pipeline`, never an identity.

Lawful combinations ("any" means framed, unframed or unavailable):

| Talk type | Topic frame | Reference |
|---|---|---|
| `Content` | any | required: addressed, unresolved or not resolved |
| `Evaluation`, `Metamemory`, `Closing` | any | none |
| `SearchingWithinWork` | framed or unavailable | none |
| `SearchingBetweenWorks`, `EndingRecall`, `OffTask` | unframed or unavailable | none; state (§3) |
| `Fragment`, `Custom` | any | none |

### 2. Address classes, bound to their programme

**Decision.** An addressed `Content` reference has exactly one address class:

- **Moment**: an ADR 0030 reference over source units (single, joint, broad or alternative),
  unchanged.
- **Work**: the speech picks out a work without a moment. It is its own class and never a broad
  reference over the work's seconds.
- **Position**: the speech picks out a work by its place in an order. It records the anchor
  span, a typed ordinal, and a reading: `Viewing`, `RecallOrder` (the order in which the speaker's
  topic frames first name works) or `Custom`.
- **Composition**: the whole programme, not any one work.
- **In work, outside stimulus**: material of a work that was not shown. Lawful only for a work
  that the programme declares an excerpt. It is not external.
- **External**: an external association, typed `OtherSpecific`, `Personal`, `GeneralKnowledge` or
  `Custom`, as ADR 0030's internal/external classification names them.

Work, position, composition and external addresses may carry one supplied confidence weight in
(0, 1], like a definite reference in ADR 0031 §1.

**An address stays bound to the programme that validated it.** Membership of a work is not
enough: a work can be an excerpt in one programme and complete in another, and two programmes
can hold the same works in different viewing orders.

- Every `RecallTalk` records the digest of the `Programme` (§4) it was validated against.
- A `RecallAccount` holds the stretches of one recall under one programme and one producer
  condition. It refuses a `RecallTalk` validated against any other programme
  (`ForeignProgramme`). Projections, relations and resolvers take an account, never a loose
  address.
- **A position's resolution is never stored.** The account derives it on demand: from the
  programme's viewing order for `Viewing`, and from its own earlier frames for `RecallOrder`. The
  result is `Resolved(work)` or `Unresolved(reason)`. There is nothing to replay against another
  programme or another account. A producer's stated resolution is kept as an observation and can
  be wrong, which is scored as such.
- Decoding re-validates against the programme the caller supplies and compares the recorded
  digest, as the mapping codecs already do for their context.

External is both an address and a grade, on different axes, and neither implies the other. As an
address it is a destination: the words point outside the programme. ADR 0030's `external`
fidelity grade assesses a claim, and it neither sets nor implies an external address. That is
ADR 0030's own rule that fidelity never implies address.

**Recaller inference is a fidelity grade, not an address.** An inference about a moment keeps the
moment's address, and ADR 0030 grades it `recaller-inference`. There is no inference address
class.

**Rejected:** a work-level reference as a broad reference over all of a work's seconds. One
mention of a film would become hundreds of seconds of coverage (case-study design, revision 2).
Also rejected: reusing `ExternalState` for addresses. It mixes a talk type, a fidelity grade and
an aligner outcome with destinations, so an address built from it could not be told apart from
an assessment (rule 7). Also rejected: checking only that an address's work belongs to the
programme in hand (revision 2). An outside-stimulus address built where a work is an excerpt
would pass under a programme where that work is complete.

**Types.**

- `RecallAddress` is a sealed family with one member per class. `Work`, `Position`,
  `Composition` and `External` are plain cases; a position stores nothing that depends on a
  programme. `InWorkOutsideStimulus` is a `final` non-case class built only by a checked factory
  over a programme.
- `RecallAccount` is a `final` non-case class. Its factory checks that every stretch lies in the
  transcript and names the account's programme.
- Refusals: `UnknownWork`, `NotAnExcerpt(work)`, `StimulusCoverageUnknown(work)` (fail closed)
  and `ForeignProgramme`. An unresolved position, such as an ordinal beyond the programme, is a
  typed result, not a refusal (rule 13).
- Two `ExternalState` cases are retired as destinations (§8).

### 3. Non-work states are typed destinations, and the destination distribution is declared

**Decision.** Three talk types have a **non-work state** as their destination:
`SearchingBetweenWorks`, `EndingRecall` and `OffTask`. The state is derived from the talk type by
the `RecallTalk` factory and cannot be asserted separately; `Custom` talk has none. A non-work
state is not an address: the speech points at no material.

**The projection is a destination distribution.** It is named `DestinationDistribution`; the
harness calls it `LocationDistribution`. A destination is a tagged union:

- a **referential destination**: film units at a declared grain, a work, a position (with its
  resolved work, or unresolved), the composition, in work outside stimulus, external, or a
  held-out joint or cross-work broad hypothesis;
- a **non-work state**;
- the **unassigned share**, which is only a reference's own known remainder.

So a row answers one question: what the speech in the bin is directed at, or which state the
speaker is in instead. "Location distribution" is kept for the row conditioned on film
destinations.

**Exactly which distribution.** For each bin on the recall clock:

1. **Samples.** As in ADR 0031 §3's onset-point projection, a covered word with an onset in the
   bin samples the stretch that covers it, under the declared nesting policy. A sample is an
   addressed, unresolved or not-resolved `Content` reference, or a state stretch. Non-addressed
   talk and uncovered words are never samples (§7).
2. **Weighting.** The row is the mean over **distinct samples**, each counted once however many
   of its words fall in the bin. A work reference over five cells and a state over one cell give
   1/2 and 1/2, not 5/6 and 1/6. A word-weighted row would be a different, separately declared
   reducer; none is decided here.
3. **A state's mass.** A state puts 1 on its state. It is derived from a talk type, and a talk
   type has no weight in this vocabulary.
4. **A reference's mass.** A reference places its supplied weights, as written, on its members'
   destinations. Its known remainder, 1 − Σw under exhaustive coverage, goes to the unassigned
   share, with ADR 0031's snap.
5. **Placement.** How a moment hypothesis's mass is placed is a rule the request declares, and
   the result names it. One rule is decided here, `cs2-placement/v1`, as the harness applies it:
   - a single hypothesis is spread uniformly over its source units at the declared grain, and
     labelled as an allocation;
   - a joint hypothesis never enters film units; its mass goes to a held-out joint state keyed
     by work;
   - a broad hypothesis sits at the smallest declared grain that wholly contains it, a scene or
     a scene set of one work, and is held out when it crosses works.
6. **Sum.** A present row sums to 1 within the bundle's row tolerance.
7. **Timing.** The request takes checked timing with its onset provenance and has the same
   refusals as the level projection (§9).

**When assignment mass is unavailable.** ADR 0031 admits a reference with no weights, with
weights on only some members, or with a remainder declared unknown. ADR 0030 admits unresolved
and not-resolved references. None of these has a known distribution over destinations.

- A bin's unconditional row is present only when every sample in it has known assignment mass.
  Otherwise the bin's value is the typed absence `AssignmentUnavailable`. It lists each such
  sample with its reason: `NoWeights`, `PartialWeights`, `RemainderUnknown`, `Unresolved` or
  `NotResolved`. Nothing is dropped, renormalized or given an invented remainder.
- A row over only the samples with known assignment is a **declared conditioning**. It reports
  the number of excluded samples beside the row. With no sample left it is a typed absence.
- An account without weights is served by ADR 0031's categorical share over destination classes,
  which needs no mass.

**Conditioning on film destinations** is equally explicit. It divides by the film mass and
reports the excluded mass beside the row. When the film mass is 0 the value is the typed absence
`NoFilmMass`, never a division.

**The simplex is a declared choice.** Non-work states are in the default destination set. This
is new: ADR 0031 did not decide it, and the harness adopted it without a decision line. It is
reconciled with ADR 0031 §3 as follows:

- **They are different estimands.** ADR 0031 §3 projects a level of a reference measure such as
  extent. A non-work state has no film support and so no level; there it is not applicable, with
  a new typed reason, `NotApplicableReason.NonWorkState`.
- **Neither projection borrows the other's rule.** A destination row never reports "only not
  applicable" for a bin of non-work states, and a level projection never gives a non-work state a
  number. The same non-work input gives a level absence and a present state row.

**Evidence, with its limit.** The first orthogonal-talk build treated the three states as
non-addressed talk. The FilmFestival film term then fell from a skill of 0.145 to 0.053 against
the duration baseline, and the 83 non-film gold rows rose to 5.33 bits. The states were restored
as destinations. This was a correction made after seeing the result, on development data.

**Rejected:** non-work states as a typed absence outside the simplex, which is ADR 0031 §3's rule
carried over. The film-or-not question would then have no forecast at all, and a non-work bin
could not be told apart from a bin of fragments. Also rejected: a row that silently drops samples
without assignment mass, as the harness does when a content unit has no pass-2 answer. That
changes the denominator without saying so.

**Types.** `NonWorkState` is an enum plus `Custom`. The destination set and the placement rule
are values carried by the distribution, so two distributions that differ in either are different
declarations. The row is a `final` class built only by the projection. Its absences are their own
enum (§7).

### 4. The address of a composition: the programme

**Decision.** A new portable `Programme` describes what was shown: the works, their occurrences
in viewing order, and where each occurrence sits on the parts. Film material is addressed by
**(work, occurrence, part, seconds)**:

- A **work** is a film's identity (`WorkId`), with a declared stimulus coverage: `Complete`,
  `Excerpt` or `Unknown`.
- An **occurrence** is one presentation slot of one work, with a unique viewing position. A work
  shown twice has two. Its id type is `ProgrammeOccurrenceId`. It is not core's `OccurrenceId`,
  which labels one segment of a `TrackComposition` (Sherlock has one per part) and keeps that
  meaning.
- A **part** is one playback axis: the primary axis of one media bundle of the source, such as a
  scan run.
- A **segment** is the stretch of one part that an occurrence occupies: a `PlaybackInterval` on
  that part's primary axis. An occurrence has one segment per part it spans.
- **Seconds** are on the part's own axis. No global timeline is constructed.

**The programme does not reuse `TrackComposition`.** Revision 2 said that a cross-part occurrence
is its own `TrackComposition`, selected as `TemporalSupport.Selection.Occurrence`. That cannot
work: a `TrackComposition` has a single source axis, and the selection takes exactly one of its
segments and refuses more as `AmbiguousOccurrence`. What changes instead:

1. **`SourceRepresentation` may declare one `Programme`.** `SourceRepresentation.of` checks that
   each segment's axis is the primary axis of one of its media bundles, the ownership check that
   ADR 0025 requires of a part role. A source without a programme keeps its digest.
2. **`TemporalSupport.Selection` gains one case**, `ProgrammeSegment(programme, occurrence,
   part)`. Its coordinate is still one axis, the part's primary axis, windowed to the segment.
   Support outside the window is `OutsideOccurrence`, as it is today for a composition segment.
   Support on another part is `NoSupportOnAxis`. The `Part` and `Occurrence` selections are
   unchanged.
3. **A multi-part occurrence is read as one `TemporalSupport` per segment**, in part order.
   Nothing is joined onto one axis.
4. **`ReferenceMeasures` gains a per-part extent** over a programme occurrence: for each
   constituent, one entry per segment, measuring the units located on that part. It is complete
   when every cited unit is available on exactly one segment. ADR 0031's single-axis extent is
   unchanged: on any one part it stays unavailable for a constituent that spans parts. A total
   across parts is a declared reducer that sums exact seconds and names itself.

**No cross-part film axis is constructed.** Where a corpus already declares a composed bundle, as
Sherlock does, its concatenated edition axis stays readable one composition segment at a time
through the existing `Occurrence` selection. A work-relative time, such as a producer outline
shows, is a rendering derived from segment offsets, not an axis.

**Rejected:**

- **A global concatenated timeline for every corpus.** It invents a clock that no viewer or
  scanner had, and it puts two films on one axis, the failure that ADR 0025 guards against.
- **One source per work with no programme.** It loses viewing order, so position addresses cannot
  be resolved, and it cannot hold a span that crosses works.
- **A `TrackComposition` per occurrence** (revision 2), for the reason above.
- **`Programme` in `corpus`, with `align` depending on `corpus`.** The alignment layer would then
  depend on the intake contract. `Programme` lives in `core` (owner, 2026-11-17), and corpus
  adapters build it.

**Types.**

- `Programme` is a `final` non-case class in `core`. Its identity is a digest derived from its
  content (rule 10). It is validated as a whole:
  - viewing positions are unique;
  - each occurrence names a declared work;
  - an occurrence's segments lie on distinct parts;
  - segments of different occurrences on one part do not overlap.
- A source unit's occurrence, and so its work, is derived from the segment that contains its
  support. It is never asserted. A unit that lies in no segment, or across two, has a typed
  outcome instead.
- Refusals are `DomainError` values, as in `core/source.scala`.
- Corpus adapters declare their programme; Sherlock is one work with one occurrence over two
  parts.

### 5. Relations between references

**Decision.** Two relations join references in one account. Neither is a member of a reference,
and neither enters a distribution.

- **Comparison** joins two distinct references, usually work-level, with a kind: `Similarity`,
  `Contrast` or `Custom`. It is neither joint nor alternative.
- **Attribution** records a blend or confusion: the framed work, which is what the speaker says
  the content belongs to, and a content reference, whose address lies in another work.
  Destination mass follows the content reference's address. The framed work stays in the topic
  frame. Source attribution is its own quantity (rule 7).

**Attribution is never an alternative.** An alternative is uncertainty about which material the
words mean. An attribution is a claim about two things at once: what was framed and where the
content came from. Splitting mass between them would turn a confusion into a hedge.

**Rejected:** an alternative of {framed work, content source}. That would score a confident
confusion as calibrated doubt, and it would hide the source-monitoring error that the relation
exists to record.

**Types.**

- Both relations are built by checked factories over a `RecallAccount`, which check that every
  reference belongs to it and the two references differ.
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

**Decision.** Words that no stretch covers, `Fragment` talk, and every other non-addressed talk
type are **counts in a per-bin ledger**, by talk type, and never destinations. None is a
zero-mass entry in a distribution, and none becomes unassigned mass. This applies the measurement
contract: unavailable is never zero.

In ADR 0031 §3's ledger, a non-addressed stretch is a not-applicable reference (`Discourse`), so
its words are covered. The ledger now splits covered words into three categories: **content**
(any `Content` reference, resolved or not), **state** and **non-addressed**, the last by talk
type. With the two uncovered counts they partition the words with an onset. The level
projection's absences are unchanged.

**Each word has one category.** Stretches nest: a referring expression sits inside an evaluation.

- Under *innermost* nesting, a word's category is that of its one innermost covering stretch.
  Ties are already refused.
- Under *all*, a word covered by stretches of different categories is counted once, as content
  before state before non-addressed. The number of such words is reported beside the counts.

A `DestinationDistribution` bin has no row when none of its words is a sample, or when a sample
lacks assignment mass. Its typed absence is one of:

- **No sample**: no word has an onset in the bin, as in ADR 0031;
- **Only non-addressed**: its covered words are all non-addressed talk;
- **Only uncovered**: no stretch covers any of its words;
- **Assignment unavailable** (§3).

Assignment unavailable takes precedence, then only non-addressed, then only uncovered. The
counts keep the full picture.

**Rejected:** a zero-mass `fragment` or `uncovered` destination in the simplex. It cannot be told
apart from an assessed zero, and it would let abstention pass as a destination.

**Types.** The ledger fields are added to `OnsetLevelProjection.Ledger`. The distribution's
absence is its own enum, not a new case of the level projection's `Absence`. Both are produced
only by their projection.

### 8. Retiring `ExternalState.Commentary` and `ExternalState.SourceConsistentInference`

**Decision** (owner, 2026-11-17). Both cases stop being alignment destinations:

- **Commentary** is a talk type (`Evaluation`, §1). It is non-addressed talk, not a place where
  recall mass goes.
- **Source-consistent inference** is ADR 0030's `recaller-inference` fidelity grade. The inferring
  unit keeps the address of the material it is about.

The other four cases stay, each for its own reason:

- **`Association`** is a real destination: the speech points outside the programme. It is the
  alignment-matrix counterpart of the external address (§2).
- **`Intrusion`** is the column for asserted episodic content that no source unit anchors. The
  matrix needs a place for that mass. It is not relabelled as ADR 0030's `invented` grade, which a
  later assessment assigns.
- **`Uninterpretable`** is a statement about the expression: its words cannot be read. Without it
  such mass would be forced onto the source or onto another state.
- **`Unranked`** is a statement about the aligner, not the participant. `RecallSignature` keeps it
  apart from attributed external mass, and that separation must stay.

**How a non-addressed unit passes through the alignment result.** `HsmmResult` requires one row
per recall unit, in recall order, each summing to 1, and its flow and path follow those rows.
That contract is kept. `AlignState` gains one member, `NonAddressed`: the unit is not addressed
to material. It is a column, so every row still has somewhere to put its mass. It is not an
`ExternalState`, so it is neither a destination nor external mass.

- `ExternalStates.natural` maps the `Evaluation`, `TaskCommentary` and `SourceMonitoring`
  discourse functions to `NonAddressed`. `Inference` gets no natural external state, like
  `NotAssessed`, so no external column is preferred for it and its mass goes where the evidence
  puts it, normally on the anchors of its material.
- The aligner's only talk evidence is the segmenter's cue-based `DiscourseFunction`, which does
  not separate non-addressed talk from the non-work states. So this one column covers both. The
  destination distribution of §3 is built from `RecallTalk` and keeps them apart.
- A unit that mixes commentary with content splits its row between `NonAddressed` and its
  anchors, as rows already split between source and external mass.
- `RecallSignature` replaces `commentaryMass` with `nonAddressedMass`, outside attributed
  external mass, which becomes association plus intrusion plus uninterpretable.
  `sourceConsistentInferenceMass` is removed. Neither old export key is reused. No unit leaves
  the matrix, so the signature's denominators are unchanged. `EstimandVersion` changes, so an old
  figure cannot be compared with a new one silently.
- The view's mapping matrix shows the non-addressed column apart from the external columns. The
  bench's `open-world:source-consistent-inference-mass` metric is retired, and its name is not
  reused; its replacement measures the inference paraphrase's mass on its target units.

**Legacy records stay valid records.** Every unit the aligner can rank is priced against every
external state except `Unranked` (`align/evidence.scala`), so existing HSMM results carry both
retired states. They must stay decodable and verifiable.

- **Vocabulary.** The two cases move from `ExternalState` to a new `RetiredExternalState`.
  `AlignState` and the mapping `Destination` each gain one legacy-only member that wraps it and
  renders its old key (`ext:Commentary`; key parts `external`, `Commentary`). Old bytes, addresses
  and digests therefore reproduce exactly.
- **Record-level API.** Each affected codec has two entry points:
  - `decode` reads current records. It refuses a document that holds a retired token with the
    typed error `RetiredVocabulary(tokens)`.
  - `decodeLegacy` returns a `LegacyRecord`. It refuses a document with no retired token
    (`NotLegacy`), so each document has exactly one decoder.
- **Validation contract.** `decodeLegacy` runs every check that `decode` runs on that format
  today, with the retired tokens admitted: duplicate keys, context and physical coordinates,
  reconstruction through the same validation, the recomputed record digest, and canonical
  re-encoding equality. A legacy record is validated historical data, not retained bytes.
- **What a `LegacyRecord` is.** A `final` non-case class that holds the original text, the
  verified digest, the retired tokens it contains, and read-only inspection of its rows. It
  exposes no current record, so no signature, view, projection or analysis API accepts it. Its
  only encoding is its original text. There is no conversion to a current record: no address is
  invented for an old inference unit, and old commentary mass is not moved to `NonAddressed`.
- **Mixed records.** A record that holds both current and retired destinations is legacy as a
  whole.
- **New results.** The current checked constructors refuse the legacy-only members, so a new
  result cannot contain one.

**Persisted forms that carry the retired cases:**

- `HsmmResultCodec` (`codec/align.scala`) encodes `AlignState.External` by case name.
- `MappingCodecs`, and every caller of `MappingJson.destination` (`ext:<case>`): mapping
  outcomes, surface workspaces and temporal inspection.
- **Persisted addresses, which revision 2 omitted.** `AlignState.keyParts` and `parseKeyParts`
  write and read the key parts `external`, `<case>`. `AlignRef` cell and transition addresses go
  through them, and `ViewRef.parse` delegates to `AlignRef`; `ViewRefSuite` round-trips
  `Commentary` this way. `VoyageCodecs` embeds such addresses in documents that must re-encode
  canonically.
- `view/mappingmatrix.scala` carries the external columns.

**Other users:** `align/cost.scala` and `align/signature.scala` (above); `embed-bench/wog.scala`,
which expects `SourceConsistentInference` for the War of the Ghosts inference paraphrase, and
`embed-bench/metrics.scala`; `ProvisionalReference`'s external members, which have no wire
format; tests in `codec`, `view`, `align`, `fixtures` and `embed-bench`; and an archived probe
under `docs/refactor/evidence/`, which is a record and is not edited. The sibling `storyatlas4s`
builds this repository from source and must compile and pass before each of these slices lands.

**Rejected:**

- **Keeping both cases as destinations.** A unit's mass could then mean where the speech points,
  what it does, or how it was graded, which rule 7 forbids.
- **Deleting the cases outright.** Every existing record would become undecodable.
- **Excluding non-addressed units from the alignment input.** It would turn a cue-based
  classification into a hard fact, where the aligner now infers it from evidence, and it would
  break the rule that rows are the recall's units in order.
- **Archival-only legacy decoding**, which keeps bytes without re-validating them. Every existing
  HSMM artifact would lose its digest check.
- **Rewriting legacy commentary mass onto `NonAddressed` on decode.** It changes canonical bytes
  and digests, and it is the silent mapping the owner ruled out.

**Types.** `RetiredExternalState` is an enum. `LegacyRecord` and the legacy-only members are
built only by the legacy decode path. `RetiredVocabulary` and `NotLegacy` are typed errors (rule
12).

### 9. Measured and interpolated word onsets

**Decision** (owner, 2026-11-17, made enforceable in revision 3). Onset provenance is part of
`RecallTiming`, the portable timing value that projections already take. A declaration on the
intake receipt alone would not reach them: `ReferenceParity.timing` keeps `result.timing` and
drops the intake result, and `RecallTiming`'s digest and codec carry no such field today.

**The value.** `RecallTiming.OnsetProvenance` is one of:

- **Measured**, citing its evidence: each onset was measured individually.
- **Interpolated**, with an `OnsetSegmentation`: onsets were placed inside measured segments. The
  segmentation is a checked partition of the inventory's words into contiguous runs, one per
  measured segment. For FilmFestival, whose receipt already records
  `wordOnsetsAreMeasured: false`, that is one run per author utterance. It is read from a
  declared column or a pinned artifact, never inferred from onset spacing.
- **Undeclared.**

A timing declared measured refuses an observation whose `Basis` is `Estimated`. A timing with
both kinds is declared interpolated, with one-word segments for its measured words.

**Persistence.** Provenance enters `RecallTiming.digest` and the wire form under a new schema
version. An undeclared timing keeps the `recall-timing/v0.1` preimage and form, so no existing
digest changes, and a decoded v0.1 document is undeclared.

**Enforcement.** `OnsetLevelProjection.Request.checked` and the destination distribution take the
same checked timing:

- **Measured:** any bins.
- **Interpolated:** no bin may split a segment. All of a segment's words that have an onset fall
  in one bin, or all fall outside every bin. Otherwise the request is refused with
  `SplitsInterpolatedSegment(segment)`. Bins made of whole segments are admitted.
- **Undeclared:** there is no segmentation to check bins against, so no bin grain can be shown
  safe. The request is refused at every grain, with `OnsetProvenanceUndeclared`. That is what
  "fails closed" means here. A caller-assumed segmentation is not accepted in its place: a caller
  could assume one word per segment, which is a measured claim without evidence.

**Consequences.**

- Sherlock's adapter records no value; how its onsets were measured is not recorded. Until it
  declares one, the Sherlock series at 1.5 s bins cannot be rebuilt on the new input. That is an
  [open question](#open-questions-for-the-owner).
- The case study's gold-blind 7 s windows split utterances. On FilmFestival the library would
  refuse them; they stay a harness sensitivity check.

**Rejected:** a declaration on the intake receipt only (revision 2), for the reason above. Also
rejected: inferring measurement from the values, which would make a class depend on a guess, and
a warning in place of a refusal, which does not stop sub-utterance timing from being quoted.

**Types.** `OnsetSegmentation` is a `final` non-case class in `recall`, built against one
inventory and one clock, with its own digest. Its refusals are `ForeignInventory`,
`ForeignClock`, `NonContiguous`, `Overlapping` and `Incomplete`. `RecallTiming.Refusal` gains
`EstimatedUnderMeasured(word)`. The two projection refusals are new `Refusal` cases. An
unrecognised provenance on the wire is a decode error, not undeclared.

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
(`talk: orthogonal`, `fallback: frame-film`) implements §1, §3 and §7, with these differences,
which are the known parity deviations for slice 10:

- frame weights summing above 1 are normalized there, whereas the library refuses them (§1);
- a content unit with no pass-2 answer is uncovered there and leaves its bin's denominator. In
  the library it is a not-resolved `Content` reference, and its bin's unconditional row is
  unavailable (§3);
- an unframed evaluation is non-addressed talk under pass 1 v1, but was a non-work commentary
  state in the dropped v2 converter. This record follows v1 (§1).

## Questions resolved by the owner (2026-11-17)

1. `Programme` lives in `core`, not in `corpus` (§4).
2. An evaluation with no framed work is non-addressed talk (§1).
3. Searching within a work gets no work-level mass in the library (§1).
4. Frame weights summing above 1 are refused; the harness deviation is recorded for slice 10
   (§1).
5. `ExternalState.Commentary` and `SourceConsistentInference` are retired as destinations (§8).
6. Timing declares measured or interpolated onsets, and projections enforce it (§9).

## Review response

The council reviewed `0993a2413`. Each finding below was checked against the code or the ADR
text it cites, and each held.

- **A programme-dependent address is not bound to its programme** (security). §2: the digest is
  recorded, `RecallAccount` refuses a foreign programme, and a position's resolution is derived
  and never stored.
- **A cross-part occurrence cannot reuse `TrackComposition` and the `Occurrence` selection** (all
  three). §4 is rewritten: programme segments, one new selection case and a per-part extent.
  Slice 2 reads a two-part occurrence through `TemporalSupport`.
- **Receipt-only onset provenance is not enforceable, and unknown has no segmentation** (all
  three). §9: provenance is on `RecallTiming`, in its digest and wire form, and undeclared timing
  is refused at every grain. Slice 1 tests the refusal.
- **The distribution is undefined, and unavailable assignment mass is not handled** (tests,
  maintainability). §3: samples, weighting, placement and sum are defined, with
  `AssignmentUnavailable`, explicit conditioning and `NoFilmMass`.
- **Unresolved content, processing outcomes and an unavailable frame are not expressible** (tests,
  maintainability). §1: the reference and the frame each carry availability, and the table is
  rewritten.
- **A persisted-address consumer is missing from the migration** (tests). §8 names `keyParts`,
  `AlignRef`, `ViewRef` and Voyage documents.
- **Non-addressed units cannot pass through `HsmmResult`** (maintainability). §8: the
  `AlignState.NonAddressed` column.
- **The deprecation outcome has no record-level API or validation contract** (security,
  maintainability). §8: `decode` and `decodeLegacy`, full validation, and `LegacyRecord`.
- **The slices are not independently landable, and their criteria do not prove their boundary**
  (all three). The slices are reordered with their dependencies, and each criterion is rewritten.
- **"Location" names a narrower quantity than the row holds** (maintainability). §3: renamed
  `DestinationDistribution`, a tagged union.
- **The partition property needs a per-word category and an oracle** (tests). §7 gives each word
  one category; slice 7 tests it against an oracle.
- **A green suite does not show the intended commentary and inference behaviour** (tests). Slice
  12 has discriminating tests with mutations.

**One claim of the first two revisions was wrong and is withdrawn.** They said a work shown
twice cannot be expressed by `TrackComposition` because both occurrences would target the same
edition interval. One source interval can in fact map to two disjoint target intervals
(`TemporalSupportSuite`, repeated occurrences). What the library lacks is work identity, viewing
order, and a reading of one presentation across parts.

**Recommendations not adopted, and why:**

- **A single aggregate selection across the parts of an occurrence** (maintainability). A
  coordinate stays one axis. The occurrence is read per segment, which keeps part-local
  coordinates and adds one selection case instead of a second kind of coordinate.
- **Alignment eligibility with a checked map back to the recall inventory** (maintainability).
  A `NonAddressed` column keeps the existing row contract and the aligner's soft inference; see
  the rejected alternatives of §8.
- **Recording legacy decoding as archival only**, one of two options the security reviewer
  offered. The other, validated historical data, is adopted (§8).
- **Binding a recall-order resolution to its account** (security). The resolution is recomputed
  and never stored, which the reviewer offered as the alternative.

## Open questions for the owner

1. **Sherlock's onset provenance.** Under §9 the landed Sherlock series cannot be rebuilt until
   its adapter declares how the onsets were measured, with evidence. The alternative is a typed
   "unverified" result that cannot be passed where measured timing is required. This record
   proposes the refusal.
2. **The `NonAddressed` alignment column** (§8) is this record's answer to the row contract. The
   alternative, excluding such units from alignment, is recorded as rejected. The owner should
   confirm.
3. **Names.** `DestinationDistribution` replaces the harness's `LocationDistribution`, and the
   programme's occurrence id is `ProgrammeOccurrenceId` to keep it apart from core's
   `OccurrenceId`. Both are proposals.
4. **A bin with one sample of unavailable assignment has no unconditional row** (§3). The harness
   instead drops such samples. The stricter rule is proposed, with the explicit conditioning as
   the route to the harness's quantity.

## Deferred

- **Fragment subtypes** (filler, false start, connective): one `Fragment` type until a consumer
  needs a distinction.
- **Questions and revisits.** A revisit is derivable from successive addresses and topic frames,
  so it gets no type of its own; questions wait for a corpus that codes them.
- **The switch-cue list** (the lexical cues that open and close a work): a producer and segmenter
  concern, not vocabulary.
- **The gold crosswalk.** Mapping a corpus's own codes onto this vocabulary belongs to that
  corpus's adapter, never to shared code.
- **Weighted talk types.** A producer's uncertainty about what a stretch does has no field yet.
- **A whole-occurrence selection on a composed axis**, until a consumer needs one hull across
  parts.
- **A word-weighted destination row**, until a consumer declares it.

## Library slices that would follow

Each slice has one acceptance criterion, stated as what one named suite must show, and lands on
`main` with only the slices it depends on. Most are about a day's work. Slices 1, 12 and 13
touch several modules and are likely more; they should be split by module when their beads are
written, keeping the criterion. Sizes are estimates.

| Slice | Depends on |
|---|---|
| 1 Onset provenance | the owner's answer on Sherlock |
| 2 Programme and segment selection | none |
| 3 Per-part extent | 2 |
| 4 `RecallTalk` | 2 |
| 5 `RecallAccount`, position, outside stimulus | 4 |
| 6 Relations | 5 |
| 7 Ledger categories | 4 |
| 8 `DestinationDistribution` | 1, 5, 7 |
| 9 Cell rule | none |
| 10 Harness-reply conversion | 5, 8 |
| 11 Frozen legacy artifacts | none |
| 12 `NonAddressed` column | 11 |
| 13 Legacy decode and removal | 12 |

1. **Onset provenance in `RecallTiming`** (`recall`, `codec`, `corpus-intake`, `align`).
   Acceptance: one suite drives intake, a codec round trip and `Request.checked`, and shows that
   measured timing admits a bin that splits an utterance; that interpolated timing admits
   whole-segment bins and refuses a splitting bin with `SplitsInterpolatedSegment`, at an exact
   segment boundary; that undeclared timing, including a decoded v0.1 document, is refused with
   `OnsetProvenanceUndeclared`; and that a segmentation built for another inventory or clock is
   refused. Removing the bin check fails the interpolated test by name.
2. **`Programme` and segment selection** (`core`, `align`). Acceptance: on a synthetic source
   with one occurrence over two parts and one work shown twice, `TemporalSupport.read` returns
   each segment's own units as available on that part's axis, and the other showing's units as
   `OutsideOccurrence` or `NoSupportOnAxis`; the existing `Part` and `Occurrence` selections
   return what they returned before; and an outside-package probe with a positive control shows
   that a `Programme` cannot be built without its factory.
3. **Per-part extent in `ReferenceMeasures`** (`align`). Acceptance: a constituent with units on
   both parts reports two per-part entries with exact seconds, while ADR 0031's extent on either
   single part stays unavailable and lists the missing units.
4. **`RecallTalk`** with talk type, topic frame, reference outcome and the moment, work,
   composition and external addresses (`align`). Acceptance: one table-driven suite constructs
   every lawful cell of the §1 table, including an unavailable frame and unresolved and
   not-resolved `Content`, and refuses every unlawful cell with its named reason; an
   outside-package probe with a positive control shows it cannot be built without its factory.
5. **`RecallAccount`, position and outside-stimulus addresses** (`align`). Acceptance:
   known-answer tests resolve viewing and recall-order positions on a synthetic programme,
   including an unresolved one; and an address replayed under a second programme that holds the
   same works, in another viewing order and with a complete work where the first had an excerpt,
   is refused as `ForeignProgramme`, with the first programme as the positive control.
6. **`Comparison` and `Attribution`** (`align`). Acceptance: a same-work attribution and a
   reference from another account are refused, and a compile-refusal probe with a positive
   control shows that neither relation is accepted as an alternative member.
7. **Ledger categories in `OnsetLevelProjection`** (`align`). Acceptance: an independent per-word
   oracle agrees with every category count under both nesting policies, on fixtures with
   content nested in non-addressed talk, missing timing, uncovered member words and uncovered
   unassigned words.
8. **`DestinationDistribution`** (`align`). Acceptance: one suite gives the exact expected value
   for a five-cell work reference with a one-cell state (1/2 and 1/2); a partial-weight
   alternative (`AssignmentUnavailable`, then the declared conditioning with one excluded
   sample); a state-only bin; a mixed bin; film conditioning with no film mass (`NoFilmMass`);
   and one non-work input that gives a level absence and a present state row. The row-sum law
   passes on JVM, JS and Native.
9. **The cell rule in recall intake** (`recall`, `corpus-intake`). Acceptance: a synthetic
   fixture with a hyphenated cell and a clock-time cell reads under the cell recipe as one word
   per cell with undivided onsets and a shared-timing tag on each token, and the lexical recipe
   refuses the same bytes with `WordSpansMismatch`.
10. **Conversion of the frozen harness replies** (`pipeline/agentmapping`). Acceptance: on the
    develop five's recorded replies, with no model call, the library account's counts by talk
    type and state equal the harness's for each session, and the rows behind each known
    deviation are listed by id with counts equal to the harness's own. The replies stay under
    `data/`; only counts are committed.
11. **Frozen legacy artifacts** (tests only). Acceptance: literal pre-retirement documents, an
    HSMM result, a mapping record, a Voyage document and cell and transition addresses, each
    holding both retired tokens, are committed with their checksums and decode under today's
    decoders.
12. **The `NonAddressed` column** (`align`, `view`, `embed-bench`, `fixtures`). Acceptance:
    synthetic tests show that an evaluation unit's non-source mass is on `NonAddressed` and on no
    external column, that an inference unit's mass is on its target anchors, and that attributed
    external mass excludes both `NonAddressed` and `Unranked`; each test fails by name when its
    guard is mutated. After this slice no producer emits a retired state.
13. **Legacy decode and removal of the two cases** (`align`, `codec`, `view`). Acceptance: the
    documents of slice 11 decode through `decodeLegacy` with verified digests and byte-identical
    text; `decode` refuses them with `RetiredVocabulary`; `decodeLegacy` refuses each when its
    digest, context or keys are corrupted; a current record round-trips as the positive control;
    and an outside-package probe with a positive control shows that no signature or view API
    accepts a `LegacyRecord`.

## Rejected alternatives (summary)

- One enum for talk and address; a topic frame derived from the local address; a commentary
  destination for an unframed evaluation (§1).
- Work-level references as broad references over seconds; `ExternalState` as the address
  vocabulary; a membership-only programme check (§2).
- Non-work states as a typed absence outside the simplex; silently dropping samples without
  assignment mass (§3).
- A global timeline; a programme-less source per work; a `TrackComposition` per occurrence;
  `Programme` in `corpus` (§4).
- Attribution as an alternative (§5).
- Divided onsets, or refusing multi-token transcripts (§6).
- Zero-mass destinations for uncovered or non-addressed words (§7).
- Keeping or deleting the two retired cases; excluding non-addressed units from alignment;
  archival-only or rewriting legacy decoding (§8).
- Receipt-only onset provenance; inferring it from the values; a warning instead of a refusal
  (§9).

## Out of scope

- `CoverageMeasure` and the correspondence table (case-study design, projections 2 and 3).
- A programme for a text-only source, which has no parts.
- Scoring, fallbacks and baselines, which stay in research tooling.
- Producer prompts and wire compatibility.
- Fidelity grades, ADR 0030.
- Any scientific claim. FilmFestival's numbers here are development data.
