# ADR 0032 — Recall reference vocabulary: talk, topic frame, address and programme

**Status:** Proposed, 2026-11-17 (revision 4). Not binding until the owner rules. It changes no
runtime type, codec or producer.

A cold review found revision 3 (`eedc2e35f`) not acceptable as written. Revision 4 narrows it:

- **Retiring two `ExternalState` cases is not decided here.** It waits for a later ADR, written
  once the talk-type field exists in the library. The alignment matrix and `HsmmResult` are
  unchanged by this record. What was learned is in the [retirement notes][notes].
- **Onset provenance and the cell rule moved** to a proposed [amendment][amend] at the end of
  ADR 0031, because both change its projection request.
- **The slices moved** to [the slice plan][slices].

[notes]: ../plans/2026-11-17-externalstate-retirement-notes.md
[slices]: ../plans/2026-11-17-cs2-library-slices.md
[amend]: 0031-reference-measures-and-held-level-projection.md

**Decider:** the owner. **Tracker:** case study 2, `bd-01M7JRB11CHP7EPC35QQ12DT41`.

**Evidence:** the case-study plan `docs/plans/2026-11-16-cs2-mapping-object.md` and the harness
`tools/case-studies/recall-precision/cs2/` (cited as `H/`), both at `a93763b46` on branch
`claude/cs2-mapping-object`. Its numbers are FilmFestival development data under rules revised
after seeing results, so none is a validation. Library code is cited by file and line in this
branch's tree, with `A/` for `align/src/main/scala/storymodel4s/align/` and `C/` for
`codec/src/main/scala/storymodel4s/codec/`. It was read, not run.

## Context

[ADR 0030](0030-detail-account-and-derived-analysis.md) fixes the detail account and
[ADR 0031](0031-reference-measures-and-held-level-projection.md) the measures on a reference.
Case study 2 mapped recall onto a sequence of short films, and it needed words that neither
has: speech that names a film, places it in the viewing order or remarks on the whole sequence;
speech that evaluates, remembers, searches memory, closes a film or ends the recall; material
outside the stimulus, since two films are excerpts (plan line 32); blends and comparisons of
films; and eleven works in one viewing order, one of them shown twice (plan lines 23-24).

The harness recorded its answers as declarations. One, putting non-film states inside the
simplex, extends ADR 0031 §3 without a decision line.

**The library today:**

- `ProvisionalReference` is single, joint, broad, alternative, external, unresolved or not
  resolved (`A/referencemeasures.scala:98-103`). Its external members are typed
  by `ExternalState` (`:72`, `:100`). It holds one source digest and one coordinate (`:21-22`),
  and it is provisional, with no wire format (`:11-13`).
- `ExternalState` is `Association`, `Commentary`, `SourceConsistentInference`, `Intrusion`,
  `Uninterpretable` or `Unranked` (`A/matrix.scala:13-14`).
- A `TrackComposition` has one source axis (`K:1704`, with `K` for
  `core/src/main/scala/storymodel4s/core/source.scala`), and
  `TemporalSupport.Selection.Occurrence` takes exactly one of its segments
  (`A/temporalsupport.scala:92-102`). No type holds a work, a viewing order or a showing.

## Terms (each used in one sense here)

- **Stretch:** one anchored span of recall with a talk type. Its type is `RecallTalk`.
- **Address:** what a `Content` stretch points at (§2), in ADR 0030's sense.
- **Showing:** one presentation slot of a work in a programme. The case-study plan says
  "occurrence". This record does not, because that word already names the label of one
  composition segment (`OccurrenceId`, `K:20-21`, `K:1655-1659`) and, in ADR 0031 §1, one
  occurrence of a repeated referent inside a reference.
- **Destination:** only the existing `Destination`, a mapping target or an external state
  (`A/mappingvocabulary.scala:7-9`). What a row of §3's distribution is over is a **cell**.
- **Film cell:** a cell that is a film unit at the declared grain, as `H/projections.py:106`
  defines it. Work-level and other cells are not film cells.

## Decision

### 1. Talk type, topic frame and reference are separate fields

**Decision.** A `RecallTalk` carries its exact UTF-16 support, its producer condition (ADR 0022)
and three fields. The fields constrain each other; they are not a free product.

- **Talk type:** what the stretch does. A closed enum plus `Custom(namespace, label)`: `Content`,
  `Evaluation` (commentary), `Metamemory`, `SearchingWithinWork`, `SearchingBetweenWorks`,
  `Closing`, `EndingRecall`, `OffTask`, `Fragment`.
- **Topic frame:** which work the discourse is about. It is *framed*, with self-reported weights
  over the programme's works (Σw ≤ 1; a sum above 1 is refused); *unframed*; or *unavailable*,
  with a processing outcome. Unavailable is never read as unframed.
- **Reference:** only `Content` has one, and it is always *addressed* (§2), *unresolved*
  (attempted, no referent determined) or *not resolved*, with a processing outcome.

A processing outcome is one of ADR 0030's reference statuses, as `ProvisionalReference.Outcome`
types them (`A/referencemeasures.scala:86-89`): not attempted, failed or search-limited. A unit
whose talk type was not produced has no `RecallTalk`; its span keeps the outcome and its words
count as uncovered (§6).

| Talk type | Topic frame | Reference |
|---|---|---|
| `Content` | any | required: addressed, unresolved or not resolved |
| `Evaluation`, `Metamemory`, `Closing` | any | none |
| `SearchingWithinWork` | framed or unavailable | none |
| `SearchingBetweenWorks`, `EndingRecall`, `OffTask` | unframed or unavailable | none; state (§3) |
| `Fragment`, `Custom` | any | none |

A referring expression inside an evaluation is its own `Content` stretch over its own span. The
topic frame never creates mass: a consumer that falls back on it declares that as its own rule.

**Decided with this section (owner, 2026-11-17):**

- an evaluation with no framed work is non-addressed talk, with no commentary cell;
- searching within a work gets no work-level mass in the library; a corpus whose gold codes it
  at film level maps that in its adapter's crosswalk;
- frame weights above 1 are refused, where the harness normalizes them (plan line 282).

**Non-addressed talk lives only in the reference account and its ledgers** (§6). The HSMM row
contract and the `ExternalState` columns are untouched. **A text-only source gets no
`RecallTalk`:** it has no parts (`A/mappingsource.scala:13`), so it can declare no programme
(§4), and the factory refuses with `ProgrammeRequired`.

**Rejected:** one enum for talk and address, as pass 1 v1 used (`H/ff_producer.py:295`). Its
`work` state meant both naming and evaluating a film, and the cached replies cannot separate
them. Also rejected: deriving the frame from the local address. A film is held as topic across
many units, and a blend is a reference whose address and frame differ.

**Types.** `RecallTalk` is a `final` non-case class built by a checked factory (rule 8): an
evaluation with an address, or a framed search between works, is individually lawful and jointly
false. Only a contradiction is refused, never a missing observation. `Custom` talk has no
reference and no state, so an unknown talk type fails closed. Refusals are values (rule 12):
`ReferenceForbidden`, `ReferenceRequired`, `FrameRequired`, `FrameForbidden`, `ProgrammeRequired`,
`ForeignSource` (§2) and the weight refusals. `ReferenceAnswers.Talk`
(`pipeline/src/main/scala/storymodel4s/pipeline/agentmapping/ReferenceAnswers.scala:77`) stays a
wire token set, mapped by a declared conversion.

### 2. Addresses

**Decision.** An address has ADR 0031 §1's form. It is *definite*, with one hypothesis and an
optional confidence, or *alternative*, with two or more distinct hypotheses and optional weights.
The weight, remainder, duplicate and one-member rules are unchanged
(`A/referencemeasures.scala:119-131`). This record adds the kinds a hypothesis may have:

- **Moment:** a single, joint or broad hypothesis over source units, as now.
- **Work:** the speech picks out a work without a moment. Never a broad reference over the
  work's seconds.
- **Position:** a work picked out by its place in an order. It records its anchor, a typed
  ordinal and a reading: `Viewing`, `RecallOrder` (the order in which the speaker's topic frames
  first name works) or `Custom`.
- **Composition:** the whole programme.
- **In work, outside stimulus:** material of a work that was not shown. Lawful only for a work
  the programme declares an excerpt. It is not external.
- **External:** an association outside the programme, typed by a new `ExternalKind`:
  `OtherSpecific`, `Personal`, `GeneralKnowledge` or `Custom`, ADR 0030's external categories.

**Alternatives may mix kinds.** ADR 0031 already lets one alternative hold located and external
members (`A/referencemeasures.scala:70-72`). The same now holds for every kind: doubt between a
scene and the film as a whole is an alternative of a moment and a work.

**External hypotheses do not use `ExternalState`.** Revision 3 called the moment reference
"unchanged" while rejecting `ExternalState` for addresses, which contradicted itself. The recall
reference is a new checked type, `RecallReference`, with `ProvisionalReference`'s rules and
these six kinds. `ProvisionalReference` stays as it is for the landed ADR 0031 path, and nothing
converts a `RecallReference` into an `ExternalState`. `ReferenceMeasures` reads a
`RecallReference` directly. Extent is unavailable, with a typed reason per kind, for every
hypothesis that is not a moment, as it is for an external member today
(`A/referencemeasures.scala:455`).

**An address is bound to its source.** A moment cites source units, and the programme is declared
on the source and is part of its digest (§4). One binding therefore covers both:

- every `RecallTalk` records the digest of the `SourceRepresentation` it was validated against;
- a `RecallAccount` holds the stretches of one recall under one source digest and one producer
  condition, and refuses any other (`ForeignSource`). Projections, relations and resolvers take
  an account, never a loose address;
- a position's resolution is never stored. The account derives it, from the viewing order or
  from its own earlier frames, as `Resolved(work)` or `Unresolved(reason)`. A producer's stated
  resolution is kept as an observation;
- decoding re-validates against the source the caller supplies.

**External is an address and, separately, a grade.** ADR 0030's `external` fidelity grade assesses
a claim and neither sets nor implies an external address. **Recaller inference is a grade only:**
an inference about a moment keeps the moment's address.

**Rejected:** a work as a broad reference over its seconds, because one mention would become
hundreds of seconds of coverage. `ExternalState` for addresses, because it mixes a talk type, a
grade and an aligner outcome with associations (rule 7). Alternatives of one kind only, because
gist-versus-detail doubt would be inexpressible. Checking only that a work belongs to the
programme in hand (revision 2), because a work can be an excerpt in one programme and complete
in another.

**Types.** `RecallReference` and `RecallAccount` are `final` non-case classes with checked
factories. A hypothesis is a sealed family; `InWorkOutsideStimulus` is a `final` non-case class
built only by a factory over a programme, and the rest are plain cases. Refusals: `UnknownWork`,
`NotAnExcerpt`, `StimulusCoverageUnknown` (fail closed), `ForeignSource`, and ADR 0031's weight
and member refusals. An unresolved position is a typed result, not a refusal (rule 13).

### 3. Non-work states and the pointing distribution

**Decision.** `SearchingBetweenWorks`, `EndingRecall` and `OffTask` each have a **non-work
state**, derived from the talk type by the `RecallTalk` factory and never asserted. A state is
not an address: the speech points at no material.

**The projection is the `PointingDistribution`.** The harness calls it `LocationDistribution`;
revision 3's `DestinationDistribution` clashed with `Destination`. A row is over cells of three
kinds:

- **material:** a film cell, a work, a position (resolved or not), the composition, in work
  outside stimulus, external, or a held-out joint or cross-work broad hypothesis;
- **state:** a non-work state;
- **unassigned:** a reference's own known remainder, and nothing else.

**Exactly which distribution.** For each bin on the recall clock:

1. **Samples.** As in ADR 0031 §3, a covered word with an onset in the bin samples the stretch
   that covers it, under the declared nesting. A sample is a `Content` reference or a state
   stretch. Non-addressed talk and uncovered words are never samples.
2. **Weighting.** The row is the mean over distinct samples, each counted once
   (`H/projections.py:24`). A work reference of weight 1 over five cells and a state over one
   cell give 1/2 and 1/2, not 5/6 and 1/6.
3. **Mass.** A state puts 1 on its cell; a talk type has no weight. A reference puts its supplied
   weights, as written, on its hypotheses' cells, and its known remainder on unassigned.
4. **Allocation.** How a moment's mass is spread is a rule the request declares and the result
   names. One is decided, `cs2-allocation/v1`: a single hypothesis is uniform over its units; a
   joint one goes to a held-out cell keyed by work (`H/projections.py:71`); a broad one sits at
   the smallest grain that contains it, and is held out across works (`H/projections.py:124`).
5. **Sum.** A present row sums to 1 within the row tolerance.

**When assignment mass is unavailable.** A reference may have no weights, weights on some members
or an unknown remainder (`A/referencemeasures.scala:277`), and an unweighted definite reference
is `NoWeights` (`:437`). A reference may also be unresolved or not resolved.

- A bin's unconditional row is present only when every sample has known mass. Otherwise its
  value is the absence `AssignmentUnavailable`, listing each such sample and its reason.
- A row over the samples with known mass is a declared conditioning. It reports the number
  excluded, and with none left it is the absence `NoKnownSample`.
- **Conditioning on film cells** divides by the mass on film cells and reports the excluded
  mass. With no film mass it is the absence `NoFilmMass`.
- How many case-study bins the strict unconditional row would leave without a value has not
  been counted.

**Reconciled with ADR 0031 §3.** The two are different estimands. ADR 0031 projects a level of a
measure; a state has no film support, so there it is not applicable, with a new reason
`NonWorkState`. The pointing row asks what the speech points at, or which state replaces
pointing. Neither borrows the other's rule: the same non-work input gives a level absence and a
present state row.

**Rejected:** states as an absence outside the simplex, which leaves the film-or-not question
with no forecast. Treating them as non-addressed talk dropped the FilmFestival film term's skill
from 0.145 to 0.053 (plan line 248); restoring them was a correction made after seeing that
development result. Also rejected: dropping samples without mass, as the harness does for a
content unit with no pass-2 answer (`H/ff_producer.py:974`), which changes the denominator
silently.

**Types.** `NonWorkState` is an enum plus `Custom`. The cell set and allocation rule are values
on the distribution. The row is a `final` class built only by the projection.

### 4. The programme

**Decision.** A portable `Programme` records what was shown. Film material is addressed by
**(work, showing, part, seconds)**:

- a **work** is a film's identity (`WorkId`), with coverage `Complete`, `Excerpt` or `Unknown`;
- a **showing** is one presentation of one work, with a unique viewing position; a work shown
  twice has two;
- a **part** is the primary axis of one media bundle of the source, such as a scan run;
- a **segment** is the `PlaybackInterval` a showing occupies on one part, one per part it spans;
- **seconds** are on the part's own axis. No global timeline is built.

**What changes in the library:**

1. **`SourceRepresentation` may declare one programme.** Its factory takes a view, bundles, a
   composition and physical support today (`A/mappingsource.scala:198-203`). It checks that each
   segment lies on the primary axis of one of its media bundles. A declared programme enters the
   source digest; a source without one keeps today's digest, which the slice pins by value.
2. **`TemporalSupport.Selection` gains `ProgrammeSegment(programme, showing, part)`.** Its
   coordinate is one axis, the part's own, with a **window**. `Coordinate.occurrence` is a
   `CompositionSegment` (`A/temporalsupport.scala:36`), whose two intervals must lie on different
   axes (`K:1673-1674`), so it cannot hold a same-axis window. `Coordinate` gains an optional
   window interval, and `occurrence` stays empty for a programme segment.
3. **Each unit gets a state already in the enum** (`A/temporalsupport.scala:39-40`): `Available`
   inside the window, with any part outside it excluded as `split` does now (`:172`);
   `OutsideOccurrence` when wholly outside; `NoSupportOnAxis` on another part; `Unlocated` and
   `TextOnly` as now.
4. **A programme does not let one unit span parts.** `SourceRepresentation.of` refuses a unit
   anchored on two part axes without a composition (`CrossPartWithoutComposition`,
   `A/mappingsource.scala:314`), and that stays. A programme places showings on parts and
   relates no axis to another. Only showings, and constituents of several units, span parts.
5. **A multi-part showing is read one `TemporalSupport` per segment.** A measure across its
   segments needs a new input, a `ShowingReference` built from the source with one reading per
   segment, because a reference holds one coordinate and only a digest of its source
   (`A/referencemeasures.scala:21-22`). ADR 0031's single-axis extent is unchanged.
6. **`codec` matches `Selection` exhaustively in three places** (`C/temporalsupport.scala:12`,
   `C/temporalquery.scala:24`, `C/temporalprojection.scala:18`), and `align` in two
   (`A/temporalquery.scala:619`, `A/temporalprojection.scala:174`). All gain the case in the same
   change, since CI compiles with fatal warnings.

**No cross-part film axis is built.** Sherlock's composed axis, with one composition per part
(`corpus-intake/src/main/scala/storymodel4s/corpus/intake/SherlockSourceAtlas.scala:78-87`),
stays readable one segment at a time through the existing selection.

**Rejected:** a global concatenated timeline for every corpus, which invents a clock nobody had
and puts two films on one axis, the failure ADR 0025 guards against. One source per work with no
programme, which loses viewing order and cannot hold a span that crosses works. A
`TrackComposition` per showing (revision 2), which has one source axis and a one-segment
selection. `Programme` in `corpus` with `align` depending on it, which would make alignment
depend on intake; it lives in `core` (owner, 2026-11-17).

**Types.** `Programme` is a `final` non-case class in `core`, with a digest derived from its
content (rule 10). Its factory checks unique positions, declared works, a showing's segments on
distinct parts, and no overlap between showings on one part. A unit's showing, and so its
work, is derived from the segment that contains its support, never asserted. Refusals are
`DomainError` values.

### 5. Relations between references

**Decision.** Two relations join references of one account. Neither is a member of a reference
or enters a distribution.

- **Comparison** joins two distinct references with a kind: `Similarity`, `Contrast` or `Custom`.
- **Attribution** records a blend: the framed work, and a content reference whose works all
  differ from it. Mass follows the content reference; the framed work stays in the topic frame.

**Attribution is never an alternative.** An alternative is doubt about which material is meant.
An attribution is a claim about two things at once. **Rejected:** an alternative of the framed
work and the content source, which would score a confident confusion as calibrated doubt and
hide the source-monitoring error.

**Types.** Both are built by checked factories over a `RecallAccount`. Attribution refuses with
`SameWork` when a work of the content reference, derived through the programme, is the framed
work. Neither is accepted as an alternative member; an outside-package compile-refusal probe
with a positive control proves it.

### 6. Uncovered words and non-addressed talk are ledger counts

**Decision.** Uncovered words, `Fragment` talk and every other non-addressed talk type are counts
in a per-bin ledger, by talk type, and never cells. None is zero mass and none becomes
unassigned mass. This applies the measurement contract: unavailable is never zero.

- **Categories.** ADR 0031's ledger counts covered words once
  (`A/onsetlevelprojection.scala:290-296`). They now split into *content* (any `Content`
  reference, resolved or not), *state* and *non-addressed*. With the two uncovered counts they
  partition the words with an onset.
- **One category per word.** Under innermost nesting it is that of the word's innermost stretch.
  Under *all*, a word covered by several categories counts once, as content before state before
  non-addressed, and the number of such words is reported.
- **Absences of a pointing row:** `NoSample` (no word has an onset in the bin),
  `AssignmentUnavailable`, `OnlyNonAddressed` and `OnlyUncovered`. When several hold,
  `AssignmentUnavailable` wins, then `OnlyNonAddressed`. The two conditionings add
  `NoKnownSample` and `NoFilmMass` (§3).

**Rejected:** a zero-mass fragment or uncovered cell. It cannot be told apart from an assessed
zero, and it lets abstention pass as a cell.

**Types.** The counts are added to `OnsetLevelProjection.Ledger`. The pointing row's absences are
their own enum, not new cases of the level projection's `Absence` (`:314-323`).

## Findings recorded with this decision

**Pass 1 v2 put this vocabulary into a prompt and scored worse** (`H/ff_producer.py:327`; plan
lines 386-394). On the develop five, under a rule recorded before the calls, v1 with the prefix
layout scored 1.013, 0.088 and 0.140 mean bits on the scene, work and film terms. Pass 1 v2 with
the same layout scored 1.105, 0.109 and 0.179: worse on all three, each beyond its noise band
(+0.092 against 0.026, +0.022 against 0.011, +0.039 against 0.005). It was dropped. So the
vocabulary is decided as a representation, apart from what any producer is asked.

**The frozen harness differs from this record in three ways**, the known parity deviations:
it normalizes frame weights above 1; it drops a content unit with no pass-2 answer from the
denominator; and its work, position and composition references are single-member classes that
never sit in an alternative (`H/account.py:129-166`).

## Open questions for the owner

1. **Names.** `PointingDistribution`, "cell" and "showing" are proposals.
2. **Mixed-kind alternatives** (§2) extend ADR 0031 §1's member set. The owner should confirm.
3. **The strict unconditional row** (§3). Its cost in bins is not counted.

## Deferred

- **Retiring `ExternalState.Commentary` and `SourceConsistentInference`.** A later ADR, written
  after the talk-type field lands; see the retirement notes.
- **Fragment subtypes**, until a consumer needs them.
- **Questions and revisits.** A revisit is derivable from successive addresses and frames.
- **The switch-cue list**, a producer and segmenter concern.
- **The gold crosswalk**, which belongs to each corpus adapter.
- **Weighted talk types, a whole-showing selection on a composed axis, and a word-weighted
  row**, each until a consumer needs it.

## Out of scope

`CoverageMeasure` and the correspondence table; scoring, fallbacks and baselines; producer
prompts; fidelity grades (ADR 0030); and any scientific claim.
