# Case study 2: the probabilistic recall-to-movie mapping

Status: design draft, 2026-11-16, for critique before any model call. Mote bead
`bd-01M7JRB11CHP7EPC35QQ12DT41`. Scope (owner): the mapping only. No fMRI, no reinstatement.
Generality (owner): designed for Sherlock and FilmFestival from the start, nothing
corpus-specific in shared code.

## What the object is for

An analysis-ready account of where in the film material each moment of recall points, with
its uncertainty, at the recall clock's native grain. Analyses of order, coverage, accuracy or
anything else are consumers; none is built in. The object keeps the account and declares every
projection, as the [measurement contract][contract] requires.

[contract]: 2026-11-16-detail-mapping-plan.md#measurement-and-projection-contract

## Coordinates: film material is a composition, not a timeline

Case study 1's parity run (milestone 0) found the library has no axis that spans parts of a
composition. Sherlock's two scan runs are two parts of one film; FilmFestival's two runs hold
twelve films (ten shorts and two cartoon intros). A joined "global" timeline is a display
convenience and makes no playback claim.

The object therefore addresses film material as **(work, part, seconds)**:

- **Work** is a film: Sherlock has one, FilmFestival twelve. A work is the identity a recall
  first has to get right ("which film").
- **Part** is a contiguous presentation segment on one playback axis (a scan run, or a work's
  span inside a run). A work may occupy several parts; a part may hold several works.
- **Seconds** are on that part's own axis.

Each corpus adapter declares its works, parts and source units (annotation rows) with their
playback support. No adapter invents a global axis.

## The account, at native grain

One row per **reference** (case study 1's anchored reference: word-span anchor, form, weighted
hypotheses over source units), recorded as produced and converted by the landed library path
(`ReferenceConversion`, `ReferenceMeasures`):

- recall side: participant, session, unit id, anchor span, talk type, hedge;
- meaning: single, joint, broad, or alternative of these, or an external destination;
- hypotheses: weight as written, and per constituent the work, part and seconds intervals;
- shares: unassigned (known remainder), external, not applicable;
- measures from `ReferenceMeasures`: alternatives present, member count, extent per
  constituent, support grain and floor flag;
- producer condition (content-local or whole-recall) and its rendering identity.

## Projections, each declared

1. **Recall-time × film-time matrix.** Per participant, bins on the recall clock (onset-point
   projection, distinct references per bin) by film units at a declared film grain (source
   unit, scene, work). Each row carries mass and, beside it, the unassigned, external,
   not-applicable and no-sample states, never folded into the mass. Declared policies:
   - **Alternatives:** a mixture by the weights as written (self-reports; no calibration).
   - **Broad:** either spread uniformly over the referenced span, or held out as its own
     state. Both are produced and labelled, because they are different claims.
   - **Joint:** each moment carries its own row; no mass is split between moments by default.
   - **Repeated:** occurrences are listed; the matrix spreads over them only under a named
     policy.
   - **Within a hypothesis:** uniform over its support seconds, labelled as an allocation
     assumption, not a measurement.
2. **Work-level marginal.** The same matrix summed to works: "which film is this moment
   about", with the same side states. It is the coarsest question and the one FilmFestival
   adds.
3. **Correspondence table.** The sparse reference × hypothesis × interval table itself, for
   any consumer that wants the account rather than a matrix.

## Inspectability

Every row of every projection traces to its references, and each reference to its recorded
reply, prompt digest and unit. Confidence (the self-reported weights), coverage (covered vs
uncovered words) and, where a reference coding exists, agreement with it, are reported beside
the mass, never multiplied into it.

## One yardstick for both corpora

Reported per corpus with the same columns, participant-weighted with participant-bootstrap
intervals:

- work identity agreement (FilmFestival; trivially 1 for Sherlock, reported as such);
- scene agreement on covered words, and over all coded words, with coverage beside it;
- log loss of the human-coded unit under the mixture;
- calibration of the self-reported top weight;
- within-scene localization on dialogue-quote anchors (Sherlock: Whisper dialogue;
  FilmFestival: subtitles);
- reliability across two independent producer samples.

## Expected gaps (what the case study is for)

1. A library source model for a multi-work composition with per-part axes, and how a
   constituent spanning parts of one work is measured (sum of per-part measures under a
   declared reducer, or unavailable).
2. A declared word-cell rule in recall intake, since transcript cells can hold several tokens.
3. An allocation inside a hypothesis's support ("uniform over seconds") as a named library
   allocation, distinct from the level-holding projection.
4. A work-level reference meaning: a recall that names a film without a moment ("the one with
   the shoe") is broad at work grain.
5. A FilmFestival adapter in the harness, with the producer prompt over a twelve-film outline.

## Order of work

1. Critique this definition (linguistics, ML; visualization once there is output).
2. FilmFestival adapter in the harness; Sherlock already has one.
3. Mapping-object builder in the harness over the landed library conversion, for Sherlock
   first (no model calls: case study 1's recorded answers).
4. Content-local producer run on FilmFestival (about 90 to 100 calls), two samples.
5. Yardstick on both corpora; progress page; critique round.
6. Library gaps from the run, through an ADR where vocabulary is new.
