# Case study 2: library slices for ADR 0032 and the ADR 0031 amendment

Status: proposed, 2026-11-17. Nothing here is scheduled. The slices follow two documents that
the owner has not yet ruled on:

- [ADR 0032](../adr/0032-recall-reference-vocabulary.md), the recall reference vocabulary;
- the proposed amendment at the end of
  [ADR 0031](../adr/0031-reference-measures-and-held-level-projection.md), on timing provenance
  and cell intake.

No bead exists for any slice yet. Each slice becomes one Mote bead when the owner accepts the
document it serves. Retiring the two `ExternalState` cases is not in this plan; see the
[retirement notes](2026-11-17-externalstate-retirement-notes.md).

## Conventions

- **Size.** Each slice is about a day's work and has one acceptance criterion: what one named
  suite must show. The sizes are estimates; nothing has been built.
- **Modules.** Each slice names every module it touches. CI compiles with fatal warnings
  (AGENTS.md, "Build and test"), so a new case of a matched type must reach every exhaustive
  match in the same slice.
- **Platforms.** `core`, `recall`, `align` and `codec` are portable, so their slices gate on JVM,
  JS and Native.
- **Data.** A slice that reads `data/` is **not a CI gate**. It is a recorded local run: inputs,
  command and counts are committed, and no text is.
- **Sibling.** `storyatlas4s` builds this repository from source. A slice that changes anything
  it consumes compiles and tests it before landing (AGENTS.md, "Sibling seam").
- **Paths.** `K` is `core/src/main/scala/storymodel4s/core/source.scala`. `R/`, `A/`, `C/`, `I/`
  and `P/` are `recall`, `align`, `codec`, `corpus/intake` and `pipeline` under their module's
  `src/main/scala/storymodel4s/`. Lines are in this branch's tree and were read, not run.

## Order

| Slice | Depends on |
|---|---|
| T1 provenance values | none |
| T2 evenness check | T1 |
| T2b calibration count (data) | T2 |
| T3 timing wire form | T1 |
| T4 intake declares provenance | T1 |
| T5 projection refusals | T3, T4, and the owner's answer on Sherlock |
| T6 cell rule | none |
| V1 `Programme` | none |
| V2 programme on the source | V1 |
| V3a programme-segment selection | V2 |
| V3b selection codecs | V3a |
| V4 `RecallReference` | V2 |
| V4b measures on it | V4 |
| V5 `RecallTalk` | V4 |
| V6 `RecallAccount` binding | V5 |
| V7 position and outside-stimulus | V6 |
| V8 relations | V6 |
| V9 address codec (owner slice) | V7, V8 |
| V10 ledger categories | V5 |
| V11 pointing row | T5, V6, V10 |
| V12 conditionings | V11 |
| V13 per-part extent | V3a, V4b |
| V14a harness-reply conversion | V7, V11 |
| V14b parity run (data) | V14a |

## Timing track: the ADR 0031 amendment

**T1. `OnsetProvenance` and `OnsetSegmentation` in `recall`.**

- Modules: `recall` only. `RecallTiming.checked` (`R/timing.scala:197`) keeps its signature and
  means undeclared; a second factory takes a declaration. No caller changes.
- Acceptance: an existing fixture timing's digest equals a literal pinned before the change; the
  same timing declared measured has a different digest; a segmentation built for another
  inventory or clock is refused by name; and an outside-package compile-refusal probe with a
  positive control shows `OnsetSegmentation` cannot be built without its factory.

**T2. The evenness check in `recall`.**

- Modules: `recall` only.
- Acceptance: the **misdeclared-timing suite**. Linearly interpolated synthetic onsets declared
  measured are refused with `UnexplainedEvenness`. The same onsets declared interpolated with
  one-word runs are refused. Declared with their true runs they are accepted. Irregular
  synthetic onsets declared measured are accepted, as the positive control. Removing the check
  fails the first two tests by name.

**T2b. Calibration count.** Reads `data/`; **not a CI gate**.

- Counts the unexplained-evenness share per session, under a measured declaration, for Sherlock's
  development participants and FilmFestival's development sessions. It never reads a sealed
  participant.
- Acceptance: the per-session counts are committed, and the threshold is frozen in `recall` or
  the comparison gains a declared tolerance, with the reason recorded in the amendment.

**T3. Timing wire form in `codec`.**

- Modules: `codec` (`C/recalltiming.scala:16`, `:79-98`, `:110-122`). `C/mappingexchange.scala:161`
  and `C/temporalprojection.scala:416` take a `RecallTiming` and are checked for a second
  schema version.
- Acceptance: each provenance round-trips; a literal `recall-timing/v0.1` document decodes as
  undeclared with its recorded digest; an unrecognised provenance token is a decode error.

**T4. Intake declares provenance.**

- Modules: `corpus-intake` (`I/RecallTimingIntake.scala:46-65`) and its three callers in
  `pipeline`: `P/RecallTimingBuild.scala:156`, `P/agentmapping/ReferenceParity.scala:98` and
  `P/RecallVolumeExport.scala:240`.
- Acceptance: a synthetic table with an utterance column reads as interpolated with one run per
  utterance; and the build receipt's `timingDigest` (`P/RecallTimingBuild.scala:191`) equals a
  pinned literal for an undeclared job and differs for a declared one.

**T5. Projection refusals.** Blocked on the owner's answer about Sherlock's source.

- Modules: `align` (`A/onsetlevelprojection.scala:229-245`) and `pipeline`. Under the rule,
  `ReferenceParity`'s two requests (`P/agentmapping/ReferenceParity.scala:308-330`) are refused
  while Sherlock is undeclared, so this slice either passes Sherlock's declared provenance or
  records the refusal as the intended result. `ReferenceConversionSuite` in `pipeline` calls
  `ReferenceParity.timing` and `bins` and is rerun.
- Acceptance: one suite drives intake, a codec round trip and `Request.checked`. A bin that
  splits a run exactly at a run boundary is refused with `SplitsInterpolatedSegment`. Bins of
  whole runs are admitted. Undeclared timing, including a decoded v0.1 document, is refused with
  `OnsetProvenanceUndeclared`. Measured timing admits the splitting bins, as the positive
  control.

**T6. The cell rule.**

- Modules: `recall` (the token-to-cell layer) and `corpus-intake` (the recipe on `Result`,
  `I/RecallTimingIntake.scala:31-44`).
- Acceptance: on a synthetic table with a hyphenated cell and a clock-time cell, `read` gives one
  word per cell with undivided onsets; the token layer maps both tokens of a cell to it with a
  shared-timing tag; `readLexical` refuses the same bytes with `WordSpansMismatch`; and the two
  results carry different recipes.

## Vocabulary track: ADR 0032

**V1. `Programme` in `core`.**

- Modules: `core` only.
- Acceptance: a programme with one work shown twice and one showing over two parts constructs;
  a duplicate viewing position, an undeclared work, two segments of one showing on one part and
  overlapping showings on one part are each refused by name; and an outside-package probe with a
  positive control shows it cannot be built without its factory.

**V2. A programme on `SourceRepresentation`.**

- Modules: `align` (`A/mappingsource.scala:86-103`, `:198-203`) and `codec`, whose source wire
  form encodes the composition (`C/mappingsourcewire.scala:236-250`). `SourceRepresentation` is
  also used in `laws` and `pipeline`; a second factory is added instead of a parameter, so no
  existing call changes.
- Acceptance: the digest of an existing fixture source equals a literal pinned before the
  change. With a programme the digest differs. It changes again when only the viewing order
  changes, and again when only one work's coverage changes, as two separate assertions. A
  segment that is not on a primary axis of the source's media bundles is refused.

**V3a. The programme-segment selection.**

- Modules: `align` (`A/temporalsupport.scala:24-37`, `:72-102`, `:172`, and the matches at
  `A/temporalquery.scala:619` and `A/temporalprojection.scala:174`) and `codec`, where the new
  case must reach three encoders (`C/temporalsupport.scala:12`, `C/temporalquery.scala:24`,
  `C/temporalprojection.scala:18`) and three decoders (`C/temporalsupport.scala:80`,
  `C/temporalquery.scala:130`, `C/temporalprojection.scala:331`) for the build to pass.
- Acceptance: on a synthetic source with a two-part showing and a twice-shown work,
  `TemporalSupport.read` gives every unit the state that ADR 0032 §4 item 3 lists for it, and a
  unit anchored on two parts without a composition is still refused
  (`A/mappingsource.scala:314`).

**V3b. Selection codecs.**

- Modules: `codec` only.
- Acceptance: each of the three codecs round-trips the new selection, and pinned `Part` and
  `Occurrence` documents still decode and re-encode byte for byte.

**V4. `RecallReference` in `align`.**

- Modules: `align` only.
- Acceptance: one table-driven suite constructs lawful mixed-kind alternatives, and reproduces by
  name each refusal that `ProvisionalReference` has for weights and members
  (`A/referencemeasures.scala:119-131`).

**V4b. Measures on a `RecallReference`.**

- Modules: `align` only.
- Acceptance: alternatives present, member count and unassigned share agree with
  `ProvisionalReference` on references both can express, and extent is the typed unavailable
  reason for each hypothesis that is not a moment.

**V5. `RecallTalk` in `align`.**

- Modules: `align` only.
- Acceptance: one table-driven suite constructs every lawful cell of ADR 0032 §1's table,
  including an unavailable frame and unresolved and not-resolved content, and refuses every
  unlawful cell with its named reason; a text-only source is refused with `ProgrammeRequired`;
  and an outside-package probe with a positive control shows it cannot be built without its
  factory.

**V6. `RecallAccount` binding.**

- Modules: `align` only.
- Acceptance: three sources are built that differ in nothing, in viewing order only, and in one
  work's coverage only. A stretch validated under the first is accepted by an account over the
  first, and refused with `ForeignSource` by an account over each of the others, in two separate
  assertions.

**V7. Position and outside-stimulus addresses.**

- Modules: `align` only.
- Acceptance: known-answer tests resolve viewing and recall-order positions on a synthetic
  programme, with an ordinal beyond the programme as an unresolved result; and an
  outside-stimulus address is refused for a complete work and for a work of unknown coverage.

**V8. `Comparison` and `Attribution`.**

- Modules: `align` only.
- Acceptance: a same-work attribution and a reference from another account are refused by name,
  and a compile-refusal probe with a positive control shows neither is accepted as an
  alternative member.

**V9. The address codec.** This is the **owner slice** for the wire form of `RecallReference`,
`RecallTalk` and `RecallAccount`: it decides the schema name and version, and no other slice
writes them.

- Modules: `codec` only.
- Acceptance: an account round-trips with canonical re-encoding equality; decoding under a
  source with another digest is refused; and a document that carries a position resolution is
  refused as an unknown field.

**V10. Ledger categories.**

- Modules: `align` (`A/onsetlevelprojection.scala:290-296`). `pipeline` prints ledger counts
  (`P/agentmapping/ReferenceParity.scala:354-404`) and keeps compiling, since fields are added.
- Acceptance: an independent per-word oracle agrees with every category count under both
  nesting policies, on fixtures with content nested in non-addressed talk, missing timing,
  uncovered member words and uncovered unassigned words.

**V11. The pointing row.**

- Modules: `align` only.
- Acceptance: one suite gives the exact row for a work reference of explicit weight 1 over five
  cells with a state over one cell (1/2 and 1/2); for a state-only bin; and for a mixed bin. An
  unweighted definite reference gives `AssignmentUnavailable` with `NoWeights`, and a
  partial-weight alternative gives it with `PartialWeights`. One non-work input gives a level
  absence and a present state row. The row-sum law passes on JVM, JS and Native.

**V12. Conditionings.**

- Modules: `align` only.
- Acceptance: conditioning on known samples reports the excluded count and gives `NoKnownSample`
  when none is left; conditioning on film cells reports the excluded mass and gives `NoFilmMass`
  when there is none.

**V13. Per-part extent.**

- Modules: `align` only.
- Acceptance: a constituent with units on both parts of a showing reports two per-part entries
  with exact seconds through a `ShowingReference`, while ADR 0031's extent on either single part
  stays unavailable and lists the missing units.

**V14a. Conversion of harness replies.**

- Modules: `pipeline` (`agentmapping`).
- Acceptance: on synthetic replies, every harness talk token and state converts to the
  `RecallTalk` that a table in the suite states, and a reply with frame weights above 1 is
  refused by name.

**V14b. Parity run.** Reads `data/`; **not a CI gate**.

- Runs the conversion on the develop five's recorded replies, with no model call and no sealed
  participant.
- Acceptance: the counts by talk type and state equal the harness's for each session; the rows
  behind each known deviation are listed by id with counts equal to the harness's own; and the
  number of bins left without an unconditional row is reported, which answers ADR 0032's third
  open question.
