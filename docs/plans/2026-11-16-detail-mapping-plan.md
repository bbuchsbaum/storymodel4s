# Detail layer and full event-graph delivery (2026-11-16)

This revision amends the [working plan][preview] and the [roadmap](../ROADMAP.md). Its Mote
epic is `bd-01M7J5P1QW4C3ZRMQSGPNPQPFS`. The owner asked for it in chat on 2026-11-16, after a
review of how far the library is from supporting detailed scientific analysis of a film, a
recall and the mapping between them.

The owner's follow-up retains the full film and recall event graph as a delivery commitment.
The detail layer is its first usable foundation. Graph construction is required; improvements
from typed matching or structured decoding are separate experiments. This amendment supersedes
this plan's earlier conditional deferral of graph construction.

## Why the plan changes

The scene preview is close. Deliveries 1 to 3 are done, the ordinary-transcript entry point
has landed, and FilmFestival automatic input reaches 78.5% strict referent scene agreement.
A bundle, however, records only a scene, a film, cited rows, alternatives and a self-reported
confidence for each recall unit. That supports one measure, localization. The analyses the
library exists for need more:

- **Fidelity:** which recalled details are accurate, gist-correct with a wrong detail,
  inferred or invented.
- **Coverage and omission:** which film content each person recalled, and which they left out.
- **Participants:** who did what, linked to a stable cast, with speakers on dialogue.
- **Detail kinds:** event, perceptual, dialogue, mental state and external or semantic content.
- **Order:** an order measure that the mapper's use of surrounding recall does not contaminate.

The roadmap's route to these was E1 to E4: a compiled film event graph, a parsed recall event
graph, typed matching and structure-aware decoding, in that order. Two results argue for
another route:

1. Direct LLM mapping beat the structured pipeline without any event graph: 79.2% against
   61.0% participant-averaged scene-exact on Sherlock development (seen data), and 75.4%
   against 55.2% on the registered read of five new participants. Structure has not yet shown
   a measured benefit on this path.
2. A graded verifier (`tools/sherlock_mapper/verify_claims.py`) already judges recalled claims
   against film rows. On the owner's 50 calibration items it cut false contradictions from 19
   to 0, and its gist-detail calls on owner-supported claims held in 16 of 17, fully or partly
   ([mapper plan](2026-11-08-sherlock-mapper-plan.md)). The missing pieces it exposed were
   speaker labels and a severity field, not an event graph.

So detail structure is built by extending the answers and the source dictionary that already
travel through the common pipeline, one evaluated field set at a time. It then feeds the full
event graph through the construction slices below. A graph need not improve scene assignment
to be useful for inspecting events, participant roles, contexts and relations.

## What does not change

- The scene preview's deliveries, claim boundary and finish line stand. The preview ships
  without detail fields.
- Each capability is an input adapter or answer field set in the same bundle, read by the same
  readers and scored by the same scorer (uniform outputs across datasets and conditions).
- Rules 3, 7 and 13 bind every field. An LLM field is a proposal. A deterministic admission
  step checks its spans, citations and identifiers, and "unverifiable" and "not assessed" are
  explicit states, never a default value. A self-reported confidence is not a probability.
- Development on seen data is engineering. A claim of accuracy needs fresh items and a frozen
  protocol. Exits marked *fresh* below are such claims; the others are engineering exits.

## Core structure and derived analyses

The reusable account records recall detail kinds and exact spans, memory addresses and discourse
functions, source referents and evidence, and separate fidelity assessments. Analysis operates on
that account under a named, versioned policy. A derived label or count does not replace its inputs.

Internal/external is one such analysis, following the `interview` module. The policy declares the
target episode, detail facets, treatment of other episodes, discourse and repetition, and the
counting denominator. Its results retain links to the contributing details and report unresolved
and excluded material separately. Unresolved placement never becomes external detail by default.
An inaccurate target-episode detail can still be internal under that policy; a correct general
fact can be external. Internal/external, fidelity and phenomenology answer different questions.

These derived categories may be exported and inspected, with their policy and input provenance.
Another scoring policy can reuse the same core account without another extraction or mapping
call. The ADR defines the projection and its relationship to the existing interview scoring API.

## Measurement and projection contract

Added 2026-11-16 after the owner's discussion of imaging analyses; bead
`bd-01M7JCDMCX9H2BK6X17PRN3GHQ`. DL1 to DL6 and the graph slices feed one contract instead of
each producing its own export. The aim: given a film and a person's recall, represent what they
expressed, what source content it concerns, how specifically and faithfully it represents that
content, and what remains uncertain. Keep these at their original granularity, and offer explicit
projections onto words, passages, events and measured time, so that researchers can build
different behavioural and neural analyses. The contract does not choose an analysis, a temporal
unit or a list of measures.

Each capability contributes versioned annotations, assessments or measurements with explicit
support, provenance, missingness and uncertainty semantics. Shared derivations produce analysis
views without changing the original evidence or repeating model extraction. The chain is:
evidence and annotations; measurements at their native support; explicit aggregation and
temporal projection; analysis-ready exports.

The contract connects and extends existing code. It is not a new module or result format.

- `features` has a word-level measurement interface whose unmeasurable tokens are typed missing
  or ineligible, declared scalar, vector, categorical and distribution spaces, tracks with
  provenance and coverage, and window reducers.
- `align` has a vocabulary for what a mapping quantity means (`MeasureKind`).
- The [imaging guide](../guide/imaging.md) documents recall-onset export on the scan's sampling
  grid for one admitted Sherlock development participant. That is not coverage of the cohort or
  of FilmFestival.

### What a measurement attaches to

| Attaches to | Examples |
|---|---|
| Source content | Visual and auditory properties, characters, actions, dialogue, relations |
| Recall content | Perceptual language, expressed details, semantic content, hedging, repetition |
| Recall-source correspondence | Referents, alternatives, fidelity of particular details, support |
| Relations across recall units | Revisit, transition, reordering, elaboration, compression |
| Participant or cohort summaries | Source coverage, repeated mention, fidelity distributions |

The families share addressing, provenance, missingness and export machinery. They need not share
an estimator. "Was this source unit recalled" is a correspondence between a source and one
person's recall, not a property of the film.

### Reference

A recall unit or atom refers to source units. A source unit records its kind and resolution. It
may be a scene or an annotation row now and an event later; the contract does not wait for the
event graph. A reference has a declared meaning, not only a member list:

- **Joint:** several identifiable source units, each referred to.
- **Broad:** an episode or interval that contains source units, without separate recall of each.
- **Alternative:** competing hypotheses, each of which may itself be single, joint or broad.
- **Unresolved:** no supported hypothesis.

Supported hypotheses are stored sparsely, with optional weights. Possible sets are not
enumerated. Three consequences follow:

- A broad reference is not evidence that each unit inside it was recalled. DL4 reports it under
  its own coverage definition.
- Breadth of reference, uncertainty between hypotheses and the resolution of the source
  annotation are three separate quantities. The span of film a unit points to is not, by
  itself, temporal acuity.
- A normalized vector over single source units is a derived view. It can express alternatives
  among single units; it cannot express a joint or broad reference, and is offered only where
  its meaning holds.

### Uncertainty quantities

Each stored quantity records the proposition or outcome it concerns, its candidate universe and
conditioning assumptions, its method and provenance, and its calibration where one exists. A
calibrated probability of the correct scene says nothing about whether the recalled details are
accurate.

Quantities are named with the existing `MeasureKind` vocabulary: raw score, normalized score
mass, transport mass, model posterior and calibrated probability. A frequency across repeated
model calls is a further meaning that the ADR must add. It depends on the model, the prompt, the
sampling procedure and the rule for treating two answers as equivalent. It shows instability, not
calibration: a model can be consistently wrong. A self-reported confidence stays a raw score.

Alternatives are retained whether or not any quantity is attached to them. The toolkit applies
no confidence threshold silently. Selecting, weighting or excluding by a quantity is a named
analysis policy that records what it kept and dropped, since such a filter can favour
distinctive, detailed recall.

### Constructs kept apart

Expressed content, source fidelity and subjective experience are separate (rule 7). A passage
can hold many perceptual details that are inaccurate, or describe an event accurately in plain
language, and neither shows how vividly the person remembered it. A track is named for what it
measures. A score from sensorimotor word norms is visual-language strength, not visual imagery.
Measures of different constructs are validated separately; none is a baseline that the others
must beat.

### Aggregation

Every derived value declares its rule: a count of distinct items, a count of mentions, a sum, a
mean of observed values, or a rate. Counts, durations and denominators are preserved, and rates
are optional derived values. Nothing is normalized by word count automatically; the amount of
detail and its density are different quantities, and controlling for speech production is an
analysis decision. One atom that spans five words is one detail. A clause-level vector copied to
its words is not five word-level estimates. A reference to an original estimate neither
allocates nor multiplies it, as in the onset export's non-additive references.

### Temporal projection

Film playback, recall speech and each scan's acquisition clock are linked explicitly. A link
that is not established stays unavailable, as the physical film-to-scanner transform is now. A
word timestamp marks speech, not the onset of the memory process that preceded it.

Two capabilities are distinct. An onset-only projection needs word or unit onsets and no
duration. A duration-supported projection needs a measured interval and an allocation rule; the
current export declares that allocation unavailable. A projection declares its temporal support,
allocation rule, units and any conservation rule. Distributing an amount and holding an attribute
are different rules: one detail spread over five seconds is a density that integrates to one,
while a rating of four over the same interval stays at four. Haemodynamic convolution, lagged or
finite-impulse-response features and boxcars are downstream recipes applied to a declared
projection. They are not part of a feature.

### Exit and qualification

- **Engineering exit:** from the same saved source, recall and mapping artifacts, produce:
  1. a recall-indexed feature table, with a recall-time matrix where timing and projection
     assumptions permit;
  2. a sparse recall-source correspondence table that preserves joint references, broad
     references, alternative hypotheses and unresolved outcomes;
  3. a source-indexed participant summary with explicit coverage definitions, denominators and
     assessment status.

  Every output identifies its inputs and derivation policy. Unsupported measurements remain
  explicitly unavailable.
- **Controls:** independently calculated synthetic cases must tell apart "A and B" from
  "A or B"; a broad summary from explicit recall of each constituent; one atom referenced by
  several words from several atoms; onset-only from duration-supported timing; and an
  amount-conserving from a level-holding allocation. Aggregation must neither duplicate evidence
  nor manufacture precision.
- **Completeness check:** reinstatement, subsequent-memory and recall-period encoding analyses
  are three worked examples that must be constructible from the three outputs. They test the
  contract and do not determine its representation.

Engineering acceptance establishes that representations and projections preserve their declared
meaning. Scientific qualification is specific to a measure, its intended interpretation and its
domain. Measures included in the planned Sherlock evaluation share its frozen protocol and
protected read, so their definitions, extraction methods, scoring rules and claims are fixed
before it. A measure developed afterwards needs its own validation source, or stays explicitly
exploratory.

## Capabilities

Each implementation bead has one engineering acceptance artifact. Its scientific qualification
is a separate criterion in the joint evaluation, not a prerequisite for building its consumers.
This avoids a cycle in which DL3 waits for DL1's fresh result while the shared read waits for
DL3 to be built. A field set stays experimental until its relevant scientific criterion is met.
Thresholds below are proposals for the owner to fix before the frozen protocol, not results.

### DL1. Recall detail inventory

Each recall unit is split into detail atoms. An atom has an exact span in canonical recall
coordinates, a kind and a memory address. The kinds extend the `interview` module's
`DetailAtom` cases (event, participant, attribute, temporal, spatial, perceptual, mental state,
relational) with reported speech. The address separates film-episode content from the
recaller's own knowledge, and discourse functions (commentary, inference, repair, repetition)
follow `InterviewDiscourseFunction`. Internal and external totals are a scoring-policy
projection of kind, address and discourse function, as `interview` requires, never a stored
kind. A span-anchored atom is a recall-side proposal; it is not yet a `DetailAtom`, which
refers to resolved situations and entities. The ADR records this split.
Internal/external labels and counts are produced by the derived analysis above, not by adding
two more atom kinds or treating failed placement as external content.

- Admission: every span lies inside its unit, atoms do not overlap, and the share of each
  unit's text left uncovered is reported.
- **Engineering exit:** one replayable inventory artifact preserves exact spans, kinds,
  addresses and discourse functions, with named controls for foreign spans and unresolved
  placement, and two policy projections that conserve resolved, unresolved and excluded mass.
- **Scientific qualification (fresh, joint evaluation):** atom-level agreement with blind human detail coding reaches at least 80%
  of human-human agreement on the same units, with both reported.

### DL2. Source cast and row record

This is a slim first step of E1. Each film gets a cast with stable identifiers, drawn from the
annotations, the speech transcript and the frames. Each annotation or automatic row is tagged
with its participants, its action and, for dialogue, its speaker. It subsumes the
speaker-labelled evidence milestone (M3).

- Admission: every tag cites row text, a speech span or a frame. A cast member is never
  asserted without a citation. Identity links follow ADRs 0024 and 0027.
- **Engineering exit:** an owner spot-check of three scenes accepts the record, with a
  coverage report for Sherlock and one FilmFestival film beside it (the share of rows with an
  identified participant and of dialogue with a speaker).

### DL3. Detail binding with graded fidelity

Each DL1 atom is bound to the source rows that support it, or to none, and each participant
mention is bound to a DL2 cast identifier or marked unresolved. A binding carries the reference
meaning defined in the contract above (joint, broad, alternative or unresolved), so a summary of
an episode is not stored as a citation of each row in it. The fidelity grade has these values:

- supported;
- gist-correct with a detail error, with a severity;
- contradicted;
- recaller inference;
- external (the recaller's own knowledge or commentary, not film content);
- invented (presented as film content, absent from the source);
- unverifiable from the record;

- misread (the recall was misunderstood by the judge);
- not assessed (no judgement was attempted).

External content and invented film content are separate grades, as in the verifier, so they
are never conflated (rule 7). The grade ports the graded verifier into the common pipeline as a
new reply protocol. Scene assignment is not recomputed from it.
The `external` content disposition does not replace the atom's memory address or discourse
function, and is not the internal/external scoring projection. The ADR keeps source relevance,
fidelity and assessment availability distinguishable. Absence from an incomplete annotation
record is insufficient evidence for `invented`; it remains unverifiable from that record.

- **Engineering exit:** one live/recorded synthetic bundle and offline replay preserve every
  assessed or unassessed atom, its row/cast bindings and independent assessment states; invalid
  citations, invented-from-missing-evidence and cross-context promotions have refusal controls.
- **Scientific qualification (fresh, joint evaluation):** on items coded blind, precision of the contradicted and gist-detail grades
  is at least 0.8 each, with precision and recall of every grade reported. The owner's 50 seen
  items calibrate the rubric and are not a test.

### DL4. Coverage and omission

This is derived from DL3, not asked of a model. For each source row or scene it records which
participants recalled it, with which grades. Coverage is reported per grade, so a row cited
only by contradicted or unverifiable atoms is not counted as recalled. A row reached only
through a broad reference is reported under a separate coverage definition, not as recalled in
its own right. A row with no binding is "not evidenced as recalled", which is a claim about the
record, not proof of an omission; the report names it that way.

- **Engineering exit:** one independently calculated readout fixture matches the derived
  per-grade coverage and denominator, including unbound, contradicted and unverifiable cases,
  without a model call; no unbound row is labelled a proven omission.
- **Scientific qualification (fresh, joint evaluation):** row-level coverage agrees with human coverage coding on a sample at a
  kappa of at least 0.6, with the denominator stated.

### DL5. Order-independent condition

A separate condition maps each prepared unit alone against the fixed source record. Its input
excludes neighbouring recall, recall-position cues and other units' assignments; its mapping
and post-processing use no recall-order prior. Unresolved references remain unresolved instead
of being repaired with surrounding recall. A local-window condition may be a comparison, but
does not qualify as order-independent.

With the prepared unit inventory and source fixed, a permutation check must preserve each
unit's request content and deterministic replay result after joining by stable unit identity.
Agreement with full-context and human scene labels is reported as localization evidence. The
proposed order readout is evaluated directly against known-order controls and human reference,
including reordered recalls; scene agreement alone does not establish recovery of recall order.
Source presentation order and story-world chronology remain separate. Chronology stays unsupported
without an explicit story-world coordinate interpretation and its own recovery evidence.

- **Engineering exit:** a recorded claim-boundary decision on whether order and chronology
  remain "not supported" or move to "experimental", based on the input-policy audit,
  permutation check and direct readout-recovery evidence above. A negative or inconclusive
  result is a valid decision; accuracy claims still require the frozen evaluation protocol.

### DL6. Who-did-what in recurring scenes

This was M4. It becomes the first hard test of DL2 and DL3 together, on a bounded set of
recurring scenes where scene labels cannot make the distinction.

- **Engineering exit:** one replayable paired-condition artifact distinguishes actor, role,
  action and occurrence in recurring scenes, with a declared scene-only comparison and an
  independently checked scorer. Its construction is ready before the joint fresh read.
- **Scientific qualification (fresh, joint evaluation):** the share of participant-action pairs judged correct exceeds the
  scene-only baseline, with an interval on the difference that excludes zero.

## Full event-graph delivery

"Full" means that the declared representation contract works end to end: source and recall
events/states, participants and roles, places and attributes, contexts and speech, occurrence
identity, hierarchy, temporal, causal and referential relations, with evidence and recall-to-source
bindings. It does not mean that every fact in a film is known. Every required family must have a
working producer/admission path and a positive and refusing witness; an entirely unimplemented
family cannot pass by returning only unknown. Individual unsupported facts remain explicit gaps.

Build the source account once, independently of recall. Preserve exact edition/native support,
the difference between observation and interpretation, and the current common bundle and CLI.
The following are construction slices over a bounded paired account; full-film integration is
the existing terminal E0 task. Use the existing `story`, `document`, `recall`, `codec` and `view`
contracts, not another graph builder or result format. Public additions require the relevant ADR.

| Slice | Depends on | One engineering acceptance artifact |
|---|---|---|
| Producer profile | Detail vocabulary ADR; NMP field-gap decisions | A pinned synthetic proposal packet passes the public record/replay and resolver path; forged authority and unsupported fields are refused. |
| Existing film compiler F1-F6 | Existing F0-F6 dependency chain | The existing F6 admitted-input artifact demonstrates actual film compilation, exact support and retained gaps, with text compatibility. |
| Detail-to-event bridge | Producer profile, F5, DL3 | A paired account preserves several details about one event, a summary spanning events and a non-episodic detail, with original IDs and support. |
| Anchored contexts and speech | Bridge | A context artifact distinguishes depicted, reported, believed/intended and unresolved content; a licensed speech promotion is a separate overlay. |
| Hierarchy and reference | Anchored contexts | A relation artifact preserves containment, occurrence identity and scoped references, with refusal controls for foreign endpoints and invalid containment. |
| Temporal relations | Anchored contexts | A temporal artifact distinguishes presentation order, world chronology, unknown order and interval relations; unsupported world-time assertions refuse or gap. |
| Causal relations | Hierarchy/reference and temporal slices | A causal artifact distinguishes joint antecedents, separate causes and unresolved hypotheses; chronology alone never licenses causation. |
| Film exchange (existing V1) | Existing D1B proof plus the relation slices | A canonical round trip retains source/recall events, contexts, evidence, gaps, bindings, overlays and exact coordinates; an independent reader checks it. |
| Graph walkthrough (existing terminal E0) | Existing V1/D1B chain, DL4, DL6 readiness | One documented command produces the common bundle and graph tables from pinned film inputs and recall; independently checked readouts retain policies and missingness. |

F0's [recorded choice A](../refactor/evidence/film-f0-claim-status-20260921/README.md) is settled:
generated captions do not license film `SurfaceExplicit`. The
[F0-F6 specification](2026-09-20-film-compiler-phase-plan.md) is infrastructure, not the full
graph programme. Its first compiler deliberately retains Held gaps; the anchored-context slice
adds checked Held construction. The source-identity export under ADRs 0027/0028 has no situations, so it does not
substitute for film-model exchange. ADR 0026's speech promotions remain overlays, never ordinary
root-world situations.

V1 and terminal E0 retain their existing probabilistic-profile P/F, remainder, receipt and
text-compatibility obligations. Categorical detail bundles must never fabricate those measures
or be used to close the probabilistic courts. The graph path extends the common artifact with
explicit profiles; it does not require a win from the E3/E4 research algorithms. StoryAtlas must
compile and test against any changed surface it consumes before that implementation lands.

The graph engineering finish requires all required families, round-trip exchange, DL6 readiness
and the executed walkthrough. It does not await a favorable accuracy result. The joint evaluation
then determines qualified uses; a failed or inconclusive criterion withholds that claim.

### Tracker ownership

Reuse the unified-path epic `bd-01M6Y7MWZGP29TNDJ90SG36A45`, the detail epic above, and the film
delivery container `bd-01M35MK5N6F0EWJE2EYT3D8Y9M`. The new bounded slices are:

| Slice | Bead |
|---|---|
| Producer profile | `bd-01M7JAZQ7KCN5Z7PD221V1MHRK` |
| Detail-to-event bridge | `bd-01M7JB0Q8VB28C7ZBHA3353VK6` |
| Anchored contexts and speech | `bd-01M7JB1F7B8FG4Z4730WM6SR5H` |
| Hierarchy and reference | `bd-01M7JB28QJWC1DMZQ19XK1G3TP` |
| Temporal relations | `bd-01M7JB314J12NG7TJK8XVHNW2Q` |
| Causal relations | `bd-01M7JB3S0W55SXKSC1GJC827VN` |

Film compiler parent: `bd-01M2T32SGVJXR2RT3RTS2DCN9P`; film exchange V1:
`bd-01M1CQQDCWN2RTT1Z8XNNB7VMY`; terminal E0: `bd-01M1CQSWJ538PAEME49200YARR`.
The identity/evidence-binding and claim-assessment programmes supply reviewed components to
DL2/DL3 and the bridge. Reconcile each candidate's exact code and tests before adoption; retain
older methods as frozen comparisons only where they answer a named question. Do not build a
parallel identity or assessment product, or close their existing acceptance by this plan alone.

## Human reference

One coding effort serves the preview, DL1/DL3/DL4/DL6 and claimed graph/readout accuracy. Delivery
5's rubric includes detail atoms, fidelity, coverage, participant/action roles and graph-family
evidence from the start. Coders do not see model answers; disagreements are retained before
adjudication. The coding sheet starts from source/recall inputs without model predictions. The
budget follows a timing pilot on seen or throwaway material; rebuild the workbench only if needed.
Before freezing, specify atom matching, per-grade sample support, denominators, exclusions and
uncertainty calculations. An undefined or inadequately supported criterion is inconclusive,
not a passing score. No proposed numerical threshold is ratified by adopting this delivery plan.

Fresh items need participants or films not yet read. FilmFestival's participants have all been
used, and Sherlock has only the untouched remainder, which E5 also needs. That remainder is read
once: the fresh DL criteria and E5's untouched read are one pre-registered read, frozen after
every method included in it is built and selected, including DL6 and any graph/readout being
evaluated. Do not expose results to implementation work between these outcomes. Any earlier
preview read or later-tuned method needs disjoint fresh material for a new confirmatory claim.
The same holds for measures derived under the contract: one that is not defined and fixed before
the freeze is exploratory until it has its own validation source.
FilmFestival already used in development remains transfer evidence. Validation data and the
coding budget still require explicit approval; this plan does not authorize collection or access.

Reuse human-study parent `bd-01M2TA9KKVFWPQ74HN90P0SM68` and its existing protocol, material and
execution lane: `bd-01M50KBFT26DRB77VFHPRHYNA2`, `bd-01M50KC744836VNJ8F8A4AHNWH`,
`bd-01M50KD0NHVPCEHGZND9H18M6E`. Preserve their approval and exposure gates. The synthetic
organization-recovery test is separate and cannot establish human accuracy. A completed report
may be negative or inconclusive; each measure retains its own qualified, failed or unsupported
state, denominator, exclusions and uncertainty. Coder agreement is a reference, not a universal
ceiling. No field becomes scientifically qualified just because its implementation ticket closes.

## Order

1. Finish preview delivery 4 (`bd-01M7H69QWE0NKSNB8XWW6MC02H`).
2. Write the joint coding rubric and run the timing pilot. This does not wait for delivery 4;
   it runs alongside it and delivery 5.
3. Resolve the detail vocabulary/producer profile and build DL2 and DL1 in parallel. The cast
   record is recall-blind. Existing F1-F6 construction can proceed alongside these capabilities.
   The same ADR settles the contract's reference meanings, quantity names and projection
   declarations. The contract's synthetic controls need no DL artifact; its three outputs are
   wired to DL1, DL3 and DL4 artifacts as each becomes ready.
4. DL3 after DL1 and DL2 engineering readiness; DL4 after DL3. Build the event bridge after F5,
   the producer profile and DL3; then anchored contexts, hierarchy/reference and temporal
   production, and causal production.
5. DL5 can run at any point after delivery 4. It needs no new fields.
6. DL6 after DL3 and the event bridge. Extend V1 and terminal E0 after their existing proof
   dependencies and the graph construction slices; freeze the joint evaluation only after
   every included implementation is ready. Its results never gate those implementations.
7. E1/E2 graph delivery is required. E3 typed-matching and E4 structured-decoding improvements
   are experimental consumers; later tuning requires separate confirmatory data.

## Analyses each step supports

| After | Available analysis, scientifically qualified only by its corresponding evidence |
|---|---|
| Preview | Which scene a unit narrates or refers to; scene-level recall coverage, described only |
| DL1 | Policy-derived internal/external detail counts, with unresolved and excluded mass reported |
| DL2 | Participant and speaker structure of the film |
| DL3 | Recall fidelity: accurate, gist-preserved, distorted, inferred and invented detail |
| DL4 | Evidenced row coverage and absence of such evidence; comparisons within the qualified scope |
| DL5 | Recall order and chronology, if its exit supports them |
| DL6 | Who did what in recall, against the film's cast |
| Contract | Feature, correspondence and source-summary views of the same artifacts |

## Open decisions

1. An ADR for the new public vocabulary (detail kinds, fidelity grades, cast identifiers),
   naming the rejected alternative of a full event graph first. It also covers the contract's
   reference meanings, the added quantity meaning for sampling frequency and the projection
   declaration, naming the rejected alternative of one probability vector over single source
   units. The owner decides ADRs.
2. Validation data for fresh items: the untouched Sherlock remainder, a new FilmFestival-style
   corpus, or a new collection.
3. Who codes, the budget, and whether the owner's within-scene lane
   (`bd-01M2TA9ZK64GTG3526RMNWNHCT`) folds into the joint rubric.
4. The bounded selection of semantic gaps for the producer profile and the thresholds/sample
   sizes for graph-family qualification. The owner decides new public vocabulary; this plan
   adopts a delivery sequence, not an unreviewed schema or a particular accuracy claim.

[preview]: 2026-11-09-categorical-mapping-and-transfer-plan.md#revision-2026-11-10-the-scientific-preview
