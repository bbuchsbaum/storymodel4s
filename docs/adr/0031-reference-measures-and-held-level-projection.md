# ADR 0031 — Reference measures and recall-time projection

**Status:** Accepted — owner decision, 2026-11-16 (revision 4 with the section 1 and 3 decisions
recorded after review of the implementing slices). Binding for its decided sections; the
items under [Deferred](#deferred) remain undecided.

Revision 4 narrows the scope after discussing the case-study evidence with the owner:

- Alternative entropy is not published, because its magnitude did not reproduce.
- The calibration artifact is deferred until a frozen read can validate it.
- The held-interval projection is specified but not built, because no admitted recall has word
  intervals.

The deferred designs are kept in [Deferred](#deferred) so that they need not be rediscovered.

Three cold reviews shaped revisions 1–3:

- **Revision 1** (`d1755d9cb`) had four blocking defects:
  - a false entropy law;
  - an ill-defined extent;
  - a calibration that did not fit `Credence`;
  - an aggregation rule that broke the onset-only and five-words-are-one-detail rules.
- **Revision 2** (`1d53c4803`) had two more:
  - exclusive bin states that cannot hold, since bins mix states and onset-only timing cannot
    show "no speech";
  - an undefined calibrated entropy.

Revision 3 addresses all six and the follow-ups:

- the law's domain;
- extent per constituent and on the selected axis;
- the new calibrated-event vocabulary;
- layer and nesting declarations;
- coverage that never exceeds 1;
- a content-addressed artifact.

**Tracker:** case study 1, `bd-01M7JGB0JDZ5N4TB6J6YFF8GW7`, under the contract bead
`bd-01M7JCDMCX9H2BK6X17PRN3GHQ`.

**Evidence:** [case study 1](../plans/2026-11-16-recall-specificity-case-study.md). It covers
Sherlock development participants only. Every number below is development data, fitted with one
participant left out at a time. None is validated.

## Context

[ADR 0030](0030-detail-account-and-derived-analysis.md) fixes the detail account and its
reference meanings. It leaves four decisions open: type placement, a response-frequency measure
kind, held-level allocation, and producer wire compatibility.

This ADR decides two of them: held-level allocation, as an onset-point projection, and the
placement of reference measures. It also confirms that self-reported weights remain observations.

It does **not** decide:

- the response-frequency measure kind;
- producer wire compatibility;
- a calibration artifact (deferred).

Case study 1 built a recall-time series of how precisely spoken recall points into the film.
Its evidence:

- **One entropy over film seconds conflates three quantities:** breadth, uncertainty between
  hypotheses, and the annotation's grain. Separated, breadth and the competing-hypotheses flag
  correlate at ρ −0.04.
- **Breadth for single-moment references sits at the annotation's row grain in 23% of cases.**
- **Self-reported LLM weights, after a fitted tempering, give a top-scene probability with
  ECE 0.07.** This is development data, for one proposition (below).
- **Uncertainty between alternatives reproduces as presence across samples (κ 0.69), not as
  magnitude (ICC 0.01).**
- **A regressor holds a level over time.** The existing `TemporalQuery` allocations all
  distribute or bound an amount.

## Decision

### 1. Measures on a reference

These are portable functions in `align`. Their input is one ADR 0030 reference at a declared
layer (unit or atom) and interpretation (narrative or referent). They never renormalize any
supplied mass.

**Weights and remainder.** A producer declares whether its weights are exhaustive over the
places it considered. Σw < 1 is then a known unassigned share ("elsewhere"). Without that
declaration the remainder is unknown, stays unavailable (ADR 0022) and feeds no calibration
parameter.

**Members.** Only an *alternative* reference has members. It must have at least two distinct
members, each a single, joint or broad hypothesis or a typed external destination. A one-member
alternative is refused, because it is that member's own meaning. Members with identical meaning
and support are refused as duplicates.

Member weights are optional, and may be supplied for some members and not others.

- A supplied weight must be finite and strictly positive, and Σw ≤ 1 within the bundle's shape
  tolerance.
- Raw weights are kept exactly as supplied.
- When only some members carry weights, every weight-free measure is still reported, and the
  unassigned share is unavailable(partial weights).
- A single, joint or broad reference may likewise carry one supplied confidence weight in
  (0, 1]. Its remainder follows the declared coverage, like an alternative's.

The remainder is reported as the unassigned share and never redistributed. A reported share
always lies in [0, 1]. When |1 − Σw| is within the tolerance, the share is exactly 0, a declared
snap, so a rounding residual is never published as a signed share.

**Alternatives present** (Boolean) is true exactly for an alternative reference; external
members count. It is false for single, joint and broad references. It is unavailable for
unresolved references and processing outcomes.

**Member count** is the number of members of an alternative reference. It is 1 for single,
joint and broad references and for a bare external destination. That is a singleton
convention: it does not mean that a definite reference contains alternative members. It is
unavailable for unresolved references and processing outcomes.
Alternative entropy is deferred (see [Deferred](#deferred)). Across two independent samples its
magnitude did not reproduce (ICC 0.01 on the published, tempered weights where either sample
is positive), while presence reproduced at κ 0.69.

**Extent** is measured in seconds on a presentation axis that the caller declares, as for a
temporal query: a `TemporalSupport.Selection`, either a `Part` or one `Occurrence` of a
composition. Each `TemporalSupport.Refusal` and unavailability on that axis maps to a typed
unavailable extent that carries its reason. That covers unlocated support, no support on the
axis, and support outside the occurrence.

- A member's extent is a vector with one entry per constituent:
  - per occurrence the reference itself declares for a repeated member (ADR 0030 occurrences,
    measured on the selected axis; not the composition `Occurrence` selection);
  - per constituent moment for a joint member;
  - a single entry for a single or broad member.
- Each entry is the measure of the union of that constituent's interval support. Overlaps count
  once and gaps are not counted; the hull is reported separately. Point-only parts are counted
  as points, not measured.
- A member with only points has extent unavailable(point support).
- `TextOnly` or missing support gives unavailable(no temporal support).
- An external member has unavailable(external). Unresolved references and processing outcomes
  have their typed outcome.
- **Occurrence count** is a separate field. Any scalar across entries or members (for example a
  weight-averaged extent) is a declared reducer that names its weights.

**Support grain** is the duration range (min, max) of the source units bounding a constituent's
support, measured on each unit's own supplied support. The entry also reports how many units
bound it.

An entry is flagged *at annotation floor* when its extent does not exceed that maximum. The
flag describes the annotation's resolution, not psychological precision: a constituent
supported by one source unit is always at floor.

When a constituent's support is only partly available on the selected axis, the whole entry is
unavailable and lists every missing unit. A within-occurrence intersection could be measured
exactly, but only as a separately named quantity, never as the constituent's extent.

**Laws:**

- Alternatives present and member count are invariant to member order.
- Each entry's measure, hull and point count are invariant to splitting an interval and to
  duplicated overlapping intervals. Grain and the floor flag describe the bounding units, so
  they change when one unit becomes two.
- A reported unassigned share lies in [0, 1], and the share is invariant to member order.
- Extent ignores other members.
- Every unavailable case is a typed value, never a number (rules 6 and 7).

### 2. Self-reported weights

A producer's weights remain a self-reported observation (ADRs 0022 and 0030). They are recorded
with the reference and never relabelled as a probability.

`CalibratedProbability` stays reserved. The case study's raw self-reports gave a top-scene ECE of
0.085 against 0.07 after fitting, on development data for one proposition. That does not justify
opening the reserved constructor. The artifact design is kept in [Deferred](#deferred) for when a
frozen read supplies validation evidence.

### 3. Projecting reference measures onto recall time

There are two projections, as separate types, not new cases of `TemporalQuery.Allocation`. The
amount and level families are therefore distinct at compile time (rules 2 and 8).

A word timestamp marks speech, not the onset of the memory process (contract). Neither
projection applies a lag or a haemodynamic model.

**Declarations.** A request declares:

- the reference layer and interpretation;
- the presentation-axis selection, as in §1;
- the source grain, as the contract requires;
- a nesting policy, either *innermost* or *all*, for words covered by more than one reference
  at that layer. A broad frame over a specific nucleus is the common case. Under *all*, each
  reference is a distinct sample: it counts once in the onset-point reducer and contributes its
  own held time to the held-interval denominator;
- the bins;
- the reducer.

A reference covers the inventory words that its exact UTF-16 support (ADR 0030) intersects. No
proportional index is used.

**Onset-point projection.** This needs `OnsetOnly` or `Interval` timing.

- Each covered word with an onset is a point sample of its reference at that onset.
- A bin's value reduces over the **distinct references** sampled in the bin, each counted once
  however many of its words fall there.
- A bin with no onset sample has *no sample*. Coverage between onsets is never inferred, so
  "no sample" is not "no speech".
- A reference's identity within a layer is its **normalized support**: spans merged, empty
  spans dropped, unit tags ignored. Two samples with the same normalized support are refused as
  duplicates, so one reference can never count twice because its anchor was written in two
  ways.
- Under *innermost*, a word covered by two references of equal shortest support is refused as
  ambiguous rather than resolved by an arbitrary order. The check is conservative: it also
  covers words without timing or outside every bin.
- An onset outside every declared bin is reported once, in the projection's ledger.
- Numeric levels are finite doubles. Not-applicable levels are typed and never reach a reducer.
- The mean sums the exact binary values of its levels and divides once, under a declared
  decimal context (DECIMAL128, half-even), before rounding to a double. That can differ from
  rounding the exact quotient directly, in rare cases, but it is the same on every platform.
- A categorical share reports zero for a category absent from a bin that sampled references,
  and is unavailable only when the bin sampled none. Categories need lawful equality, so
  floating-point categories are refused.
- A reference whose support covers no word is reported once as wordless, never also as
  unsampled.

**Held-interval projection** (specified here, built when a dataset with word intervals is
admitted). This needs `Interval` timing.

- A reference's value holds over its covered words' intervals.
- A bin's value is the time-weighted mean over held time in the bin. Overlapping intervals that
  hold different values each contribute their own time to the denominator.
- *Coverage* is the measure of the union of held intervals within the bin divided by the bin
  length, so it never exceeds 1. Summed held time is reported separately.
- If any covered word has only an onset, the request is refused. The caller may instead declare
  that such words are excluded, in which case their count is reported per bin.

**Per-bin ledger.** Every bin reports counts, not a single exclusive state:

- applicable references sampled;
- not-applicable references (discourse, or no film support);
- covered words, uncovered words of a unit, and uncovered words outside any unit. These three
  partition the bin's words with an onset.

The value is present only when at least one applicable reference is sampled. Otherwise it is
typed: *no sample*, *only not applicable*, or *only uncovered*. Not-applicable takes precedence
when both are present, and the counts keep the full picture. Words whose timing is `Missing` cannot
be placed in any bin; the projection reports them once, in its own ledger. Not-applicable
references never enter a numeric reducer.

Categorical attributes such as talk type use a declared categorical reducer (share per
category, or presence), never a mean of codes.


### 4. The producer condition (an application of ADR 0022)

ADR 0022 already requires the rendering identity to record what a producer saw: context, order
and exposed ids. The case study's leak (ids that encoded recall position) is an instance of
that requirement.

The only new rule is a view rule. A content-local result (*k* preceding units, order hidden,
opaque ids) and a whole-recall result are different estimands, and no view merges them into
one series.

### Placement

- Reference measures and the application of calibration artifacts go in `align`, beside
  `TemporalQuery` and `TemporalExposure`.
- Both projections go in `align`. Their output becomes a `features` track only after `features`
  gains a time-bin target, which is a separate decision. `features` keeps depending only on
  `core`.
- ADR 0030's account types keep their own placement decision.

## Deferred

These are designs this ADR does not decide. They are kept so that they need not be
rediscovered.

**Alternative entropy.**

- U = H(w / Σw) in bits, over an alternative reference whose every member carries a supplied
  weight.
- It is conditional on assignment, with the unassigned share reported beside it.
- It is unavailable without weights, and it is 0 by meaning for single, joint and broad
  references.
- **Law:** on its defined domain, U = 0 exactly when the reference is not an alternative.
- There is no calibrated U.

**Calibration artifact** (revision 3's section 2, as reviewed):


The producer's weights remain a self-reported observation (ADRs 0022 and 0030). They are never
relabelled, and nothing in this section changes them.

A **calibration artifact** is a recorded record stored with the exchange that uses it, so a
saved bundle reproduces its numbers without refitting. Its identity is derived from its content
by the checked constructor (rule 10). A caller-asserted `CalibrationArtifactId` is not accepted
for these artifacts.

The artifact maps the self-report vector of one reference to a `CalibratedProbability` for one
named `CalibratedEvent`, and to nothing else. The current code has only
`CalibratedEvent.ChosenDecisionCorrect` and keeps `CalibratedProbability` reserved without a
constructor. **New vocabulary:** this ADR adds one event case, and it opens a constructor for
artifacts of that event only:

- **`TopSceneMatchesReferenceCoding(coding)`.** "The scene of the most heavily weighted
  hypothesis equals the reference coding's scene at the reference's onset."
  - **Domain:** a reference whose every hypothesis is a single hypothesis, with supplied weights,
    whose interval support lies within exactly one scene on the declared axis. That is a single
    reference with a supplied confidence weight, or an alternative of such singles, where an
    external member is its own outcome.
  - **Outside the domain:** joint and broad meanings, members with point-only, text-only or
    multi-scene support, and unweighted references are unavailable(outside event domain).
    Nothing is distributed over scenes, so no single probability stands for a joint, broad or
    mixed reference (ADR 0030's rejected alternative).
  - **Onset:** the onset of the reference's first covered word that has one. Without such a
    word the event is unavailable(no onset).
  - **Ties** make the event unavailable(tie).
  - **Reference coding:** identified by checksum.
  - **Canonical rendering**, for digests: `top-scene-matches-reference-coding:<checksum>`.
  - `DecisionCalibration.Calibrated` refuses every event except `ChosenDecisionCorrect`, so a
    top-scene probability can never pass as decision calibration. An outside-package refusal
    probe with a positive control proves this (rule 8).

The artifact records:

- the event;
- the candidate universe: the annotated scenes plus external;
- the conditioning: the producer condition (§4), source edition, reference coding and fitting
  population;
- the parameters: member tempering, a scale on the unassigned share, and a uniform floor over
  the declared universe, labelled as floor mass;
- the validation evidence: ECE for this event and its population. Development-fitted artifacts
  say so.

No other quantity is calibrated by the artifact, including the mass on non-top scenes and U.

Laws:

- Application is deterministic: identical inputs and artifact give identical output.
- The output names its artifact.
- The artifact refuses a reference produced under a condition other than its own.

Fitting stays in research tooling. Repeated-sample agreement remains response stability under
ADR 0022.


## Rejected alternatives

- **One entropy, or effective width, over film seconds per moment.** It conflates breadth,
  uncertainty and annotation grain (case study iterations 0–1).
- **A calibration expressed through `Credence`.** It is per claim and cannot represent a joint
  map with a floor over a declared universe.
- **Opening `CalibratedProbability` now.** The fitted gain over raw self-reports is small
  (ECE 0.085 against 0.07), it is development-only and it covers one proposition. Until a frozen
  read validates it, regressors use recorded self-reports, which a saved bundle reproduces
  without refitting.
- **Publishing alternative entropy now.** Its magnitude did not reproduce across samples
  (ICC 0.01 where either sample is positive); the presence flag did (κ 0.69).
- **Extent defined from `SupportOnly` query bounds.** Those bound a region's mass. They do not
  measure an occurrence's support, though an implementation may compute extent with them.
- **Counting words rather than references in a bin.** One reference spanning five words is one
  value (contract), not five observations.
- **One aggregation rule for onset-only and interval timing.** It would give onset-only words a
  duration they do not have.
- **Holding one value per recall unit.** Units mix a broad frame with a specific nucleus.

## Out of scope

- Fidelity, internal/external and coverage: ADR 0030.
- Specificity as distinguishing-detail content. ADR 0030's content kinds cover it. Case study 1
  iteration 5 found it nearly unrelated to breadth (ρ 0.10) and not predictive of localization.
- The response-frequency measure kind.
- Producer wire compatibility.
- A `features` time-bin target.
- Scientific validation of any measure. That belongs in ADR 0030's joint coding rubric with
  human coders, and then a frozen read.
