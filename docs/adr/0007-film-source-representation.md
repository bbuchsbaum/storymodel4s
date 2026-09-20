# ADR 0007 — Represent film through typed evidence and a presentation axis

**Status:** Accepted. Stage C portable contracts landed (C1, salvaged 2026-09-01); the first acquisition court is executed locally under the 2026-09-01 amendments below, with `Draft` authority throughout. The 2026-09-19 amendment records the approved D1A-types / D1A-film split and replaces the research prerequisite for engineering. Their implementation and the film proofs remain open.

**Date:** 2026-08-29

**Decider:** `claude-storymodel4s`

**Author:** `codex-storymodel-collab`

**Contributors:** `codex-storyatlas-root` (additive source design),
`codex-storymodel-new-engineer` (path-dependent axis), and `cursor-grok-storymodel4s`
(vector/proposal separation and tracking layer)

**Bead:** `bd-01M17WN3R44WSNNDN679CN04TZ`

## Decision summary

Film will enter the existing narrative pipeline as another evidence-bearing source, not as a
sibling narrative model and not as a subtitle-shaped imitation of text.

The source contract will gain four additive concepts:

1. a checksummed `SourceBundle` that names the immutable picture, audio, annotation, and text
   streams used in a build;
2. a nonempty `EvidenceSupport` whose anchors retain their coordinate kind and stream identity;
3. a path-dependent `PresentationAxis` that makes positions comparable only within the declared
   primary stream; and
4. a checked `NarrativeSourceAtlas` interface above `SurfaceAtlas`, so text keeps its current
   construction boundary while a film-annotation adapter can supply proposal units and typed
   support.

One immutable film edition has one physical playback coordinate: presentation timestamps (PTS).
Shot cuts, coded scene boundaries, dialogue turns, action intervals, and tracks are distinct typed
evidence layers on that coordinate. They are not separate axes merely because their provenance or
uncertainty differs. Different cuts, streams, clocks, or run-local timebases do have distinct axis
identities and require a recorded alignment before comparison.

The existing deterministic compiler remains the one semantic compiler. Its stage order,
resolution rules, `NarrativeCompilation`, `NarrativeGraph`, and downstream recall alignment do not
fork for film. The current concrete atlas annotations at
`document/src/main/scala/storymodel4s/document/compiler.scala:279`, `:297`, `:485`, and `:548`
move to the checked atlas interface. The later support migration is broader than those four type
annotations because the current evidence ledger and narrative nodes still store `SpanSet`; this
ADR does not conceal that work or authorize it now.

The released Sherlock annotations are the first source candidate. They can provide a temporal
atlas and proposal evidence without obtaining episode video, but they produce a deliberately
sparse source graph: ordering and situations are plausible, participants are proposals, and
relations, roles, stable situation identities, contexts, and dialogue propositions are absent.

## Context

The narrative theory is already source-medium independent. `NarrativeGraph`, contexts, hierarchy,
typed relations, recall states, unbalanced alignment, and recall signatures represent narrative
content rather than pixels or character offsets. The implemented evidence and presentation
contracts are intentionally text-first:

- `StoryModel` stores `StorySource` and `SurfaceAtlas` directly and exposes text-covering queries;
- explicit claim evidence is `Option[SpanSet]`;
- story nodes and `AlignmentSource.sourceSupport` carry `SpanSet`;
- `NodeSummary.support` is a `SpanSet`;
- `SourceView.textLength` supplies the denominator for relative discourse position;
- `StorySourceView` obtains lexical descriptors by slicing canonical text; and
- `SurfaceAtlas` decomposes one `StorySource` into text units.

This specialization is useful for text and unsafe as a film abstraction. Subtitles omit visible
action, gesture, editing, environmental sound, and conflict between dialogue and image. Treating
subtitles as the source would report recall against a transcript quotient while labelling the
result film recall.

The text-bound surface is wide as a locator and narrow as a denominator. A current source-only
recount on `main` finds `TextSpan` or `SpanSet` in 47 runtime source files across 14 runtime
modules (53 files when the `laws` module is included). By contrast, the only production division
by `textLength` is in `SourceView.relativeSpan`. The earlier board measurement was 48 files across
14 modules and 15 `textLength` references; concurrent source growth changed the count, not the
shape of the seam.

The one division is scientifically load-bearing. Character offset divided by canonical text
length is a reading-position proxy. PTS divided by runtime is a playback-clock position. Both are
finite numbers in `[0, 1]`, but they are not interchangeable estimands. A shared bare `Double`
would make an invalid comparison look valid.

The implementation history also demonstrates the missing-position hazard. Before `3b246ea`,
`SourceView.relativePosition` mapped missing support to `0.0`, while signature and population
consumers wrapped the result in `Some`. Commit `3b246ea` added honest `measuredPosition` and moved
the signature consumer to it. On current `main`, the lossy accessor remains available, population
still wraps it, and HSMM transition features consume it directly. “Unplaceable” can therefore
still become indistinguishable from “at the beginning” outside the repaired signature path. The
film contract must close the type-level seam rather than copy either accessor.

## Evidence from the Sherlock source audit

The [Sherlock annotation audit](../design/sherlock-source-representation-audit.md) inspected the
pinned annotation workbook, its transformation code, released derivatives, paper methods, and
licence records without fetching participant prose or episode video.

The workbook supplies:

- 1,000 ordered microsegments with run-local seconds and mostly complete TR bounds;
- 50 sparse scene markers supplied by a different coder;
- narrative descriptions;
- character presence, focus, and speaker labels;
- location, camera, music, visible-text, arousal, and valence fields; and
- enough timing information for a receipt-bearing two-run repair.

It does not supply semantic roles, typed relations, dialogue content, contexts, stable situation
identities, exact scene-to-microsegment containment, or a narrative hierarchy beyond a two-level
ordering scaffold. The released 30-state HMM is a separate `Diagnostic` derivation whose tuning
used recall outcomes; it is neither an independent source atlas nor a validation gold standard.

The annotation workbook is an observation of what a coder recorded and a derivation of the
episode. Owner decision `post-01M1CN0CHHMYSA93R7AZMPZQB2` makes the storage disposition
artifact-specific: actual episode video file contents remain local, external to Git, and
non-redistributable; annotations, recalls, aliases, transforms, receipts, graphs, features,
reports, model outputs, and other non-video derivatives may be committed and shared after their
applicable provenance and story-text admission checks. This project policy is neither a legal
adjudication nor a film-licence claim.

These findings determine the first film claim. Sherlock supports a source-side diagnostic against
someone else's event representation. It does not establish that storymodel4s has reconstructed the
episode or recovered the narrative graph a viewer formed.

## Decision

### 1. Keep one narrative model and add source contracts

`StoryModel` will not fork into text and film products. A film can mix picture, audio, dialogue,
human annotations, and machine proposals in support of one narrative claim. A model type parameter
for exactly one medium would make that ordinary case awkward or force one channel to masquerade as
the master source.

The additive boundary is a `SourceBundle`: a content-addressed manifest of immutable streams and
their relationships. It records at least:

- bundle and edition identity;
- source kind;
- each stream's kind, identity, checksum, extent semantics, and access/rights status;
- the one declared primary presentation stream;
- cross-stream alignment receipts and their input checksums; and
- the software, parameters, and version that produced every derived stream.

An alternate cut, resampled audio stream, repaired clock, or re-encoded annotation table is a new
stream identity. A compatible alignment may relate it to another stream, but equality of title,
runtime, row count, or normalized position never creates that relation.

The primary axis is legitimate only when it represents the audience-facing order of the pinned
source edition. Canonical character order is primary for written text. Playback PTS is primary for
film. Subtitle text, ASR text, frame number, acquisition TR, and story-world time are auxiliary
coordinates unless an explicit source contract says otherwise. A receipt records a projection; it
does not make an arbitrary choice scientifically legitimate.

### 2. Make the presentation axis path-dependent

The recommended public shape is a stable axis value with an abstract coordinate kind:

```scala
trait PresentationAxis:
  type Kind
  def id: PresentationAxisId
  def sourceKind: SourceKind
  def extent: AxisExtent[Kind]
  def normalize(position: AxisCoordinate[Kind]): Either[AxisError, RelativePosition[Kind]]
  def normalize(interval: AxisInterval[Kind]): Either[AxisError, RelativeExtent[Kind]]

trait SourceView:
  val axis: PresentationAxis
  def primaryExtent(
      ref: SourceNodeRef
  ): Either[AxisProjectionError, Option[AxisInterval[axis.Kind]]]
```

The names are architectural, not implemented API. The required property is the path-dependent
relationship: operations on one view accept `RelativePosition[view.axis.Kind]`. A text-character
position and a film-PTS position have no unchecked common comparison even when both render as
`0.5`. Axis identity also participates in fingerprints and result proofs, so two different film
editions do not become comparable merely because both use PTS.

Comparison, distance, ordering, and normalization live on the axis. A normalized scalar may be
rendered for a chart, but the scalar alone is not a source position and cannot re-enter scientific
computation without its kind and axis identity.

`SourceView.textLength` becomes `axis.extent`. `relativeSpan` becomes a typed primary-axis
projection. Missing support remains `None` or a typed projection error through transition
features, signatures, population aggregates, and benchmarks. It never becomes `0.0` or
`Some(default)`.

### 3. Use one film PTS axis and distinct boundary claim types

Shot boundaries and coded scene boundaries share the PTS coordinate of one immutable film edition.
They do not share a semantic boundary type.

- `ShotBoundaryMeasurement` is one derivation family factored into separate typed claims for
  boundary existence/localization and transition morphology. Each morphology candidate carries
  only its lawful extent: a hard cut carries an `Instant(PTS)`, while a dissolve or fade carries
  an `Interval(start, end)`. A proposal may retain alternatives over those lawful dependent
  pairs; unresolved is a proposal state, not a morphology. A match cut is a separate visual-rhyme
  judgment that may coexist with any transition morphology.
- `CodedSceneBoundaryJudgment` records a coder's segmentation decision and its annotation receipt.
  It does not become a shot cut when the timestamps coincide.

The Sherlock discrepancy of up to three seconds shows that the claims and coding procedures
differ. It does not show that the timestamp coordinate differs. Spending two axes on one clock
would require an artificial cross-axis alignment for values already expressed in the same
timebase, while putting both claims in one boundary enum would erase their provenance. One axis
with typed evidence layers preserves both facts.

When records use different clocks—Sherlock's run-local seconds, TR coordinates, and a repaired
continuous clock, for example—each clock retains its own identity until a checked transformation
receipt relates it to film PTS. Forward-filling a marker or adding a run offset is a derivation,
not a coordinate cast.

### 4. Generalize evidence membership without inventing mixed-axis algebra

`EvidenceSupport` is a nonempty collection of typed anchors. Initial anchor families include:

- exact text support: stream identity plus `SpanSet`;
- media time: stream identity plus a nonempty set of half-open PTS intervals;
- shot identity: stream identity plus `ShotId` and its receipted interval;
- dialogue/audio support: text or audio stream identity plus its native interval; and
- track support: picture stream identity, `TrackId`, and the intervals in which it exists.

`SpanSet` remains the exact UTF-16 text implementation. Existing text-only constructors and
accessors remain available. A film interval set receives the same construction discipline:
nonempty, bounded, sorted, deduplicated, and half-open on one named axis.

There is no generic hull, overlap, contiguity, order, clipping, or coordinate arithmetic over mixed
support. Those operations first select one compatible axis. Selecting an auxiliary stream requires
a declared projection and, when coordinates differ, a checksummed alignment receipt. Missing
alignment is a typed refusal.

This distinction classifies current text-bound operations more precisely than a mechanical rename:

| Operation | Current text behavior | Film rule |
|---|---|---|
| Evidence membership/storage | `Option[SpanSet]` | May become heterogeneous `EvidenceSupport`. |
| Interval algebra/order/dedup | Implicit one text coordinate | Only within one compatible axis and stream. |
| Content extraction | Slice `canonicalText` | Requires a text anchor; media intervals do not yield text. |
| Epistemic-horizon clipping | `ReaderAt` over text offsets | Declares an axis and projection; no mixed-support horizon. |
| Geometry/navigation/identity | Often derived from span bounds | Declares a projection axis; normalized equality creates no identity. |
| Denominator/normalization | Character offset / text length | Axis-owned, kind-preserving normalization. |
| Missing position | Currently collapses to `0.0` | Remains typed missing or refuses. |

`TranscriptAtlas` is a useful contrast, not the film contract. It correctly keeps text as master
and requires optional audio order to agree with text order. A film bundle reverses that relation:
PTS is primary, dialogue text is aligned, and story-world order remains separate from both.

The medium-neutral contracts belong in `core`, beside `Evidence`, `SurfaceAtlas`, source identity,
and content hashing. They remain portable and may depend only on the dependencies already admitted
there. They contain no decoder, media container, HTTP client, model runtime, or provider API.
Film-specific ingestion and learned extraction stay in separately scoped JVM adapters; adding such
an adapter module requires its own bead and dependency review.

### 5. Add a checked atlas interface above `SurfaceAtlas`

`SurfaceAtlas` remains a final, private-constructor class. Its smart-construction laws—unique IDs,
bounded spans, coherent ordinals, non-overlap, and valid parentage—must not be weakened to admit a
new source kind.

A `NarrativeSourceAtlas` trait sits above it. It exposes only the checked operations the semantic
compiler needs: source/bundle identity, proposal-bearing units, unit lookup, typed support lookup,
and validation that a support anchor belongs to the declared bundle. `SurfaceAtlas` conforms to
the trait without becoming extensible. A future Sherlock adapter is separately smart-constructed
from the pinned table, timebase-repair receipt, and row/scene reconciliation record.

The eventual `StoryModel` envelope holds the checked source bundle and atlas interface rather than
claiming that every source has canonical text. Text models retain explicit checked access to their
`StorySource` and `SurfaceAtlas`; film models cannot call text slicing or covering operations
without selecting an aligned text stream. This envelope migration is source-breaking for callers
that read the current public fields directly, and it requires a named compatibility plan rather
than a cast or an empty text surrogate.

The current deterministic compiler names `SurfaceAtlas` concretely four times. Those annotations
move to the interface. The semantic stages do not change:

```text
checked source atlas + checked local charts + typed proposals + evidence + receipts
  -> NarrativeCompilation
  -> StoryModel[Validated]
  -> AlignmentSource
```

For film, an annotation description or generated caption is proposal-source text. Stage 1b turns
that text into a checked local chart; natural language is not already a `PropositionChart`.
Interval and track anchors remain evidence for the proposal. The deterministic resolver remains
the only component that creates accepted claims.

Changing the four atlas annotations does not by itself complete film support. `Evidence`,
`SourceSupport`, story nodes, `AlignmentSource`, and aligner summaries still contain `SpanSet`.
Their migration to `EvidenceSupport` or a typed primary-axis projection belongs to the later
contract slice and must preserve semantic compatibility for existing text evidence. The
important claim is that the compiler's scientific transformation and stage order do not fork,
not that its current signatures already accept film.

The later implementation ownership is therefore narrow but cross-module:

- `core` owns portable bundle, axis, support, coordinate, and atlas contracts;
- `acquire` carries typed support in resolver inputs without changing who may resolve a claim;
- `story` generalizes the model envelope and retains typed source support on nodes and at
  `AlignmentSource`;
- `document` generalizes the four atlas annotations and support validation while preserving the
  compiler stages;
- `align` consumes a typed primary projection and retains axis identity in result proofs; and
- `codec` and `laws` version the wire form and enforce cross-platform construction and refusal.

No HTTP, decoder, model runtime, or JVM media type crosses into a portable module.

### 6. Classify acquisition components by emitted value, not epistemic tier

Tool cost and precision influence scheduling. They do not determine epistemic status. Artifact
bytes are observations of those artifacts; a pre-existing human record is an observation of what
the annotator recorded and a derivation of the film. Every learned output is a derivation with a
receipt naming model, weights, version, parameters, and input identity.

Candidate acquisition components are classified by what they emit:

| Output contract | Example candidates | Admitted use |
|---|---|---|
| Intervals | shot-transition detectors | Boundary proposals with typed alternatives and receipts. |
| Intervals + text | ASR with word timestamps | Proposal-source text for Stage 1b; not dialogue truth. |
| Intervals + identity | diarization, tracking, re-identification | Speaker/track continuity proposals; not character identity or action. |
| Intervals + description | video captioning, dense event captioning, action localization | Situation/role proposal material with interval support. |
| Vectors | video representation models | Feature sidecars, retrieval, and comparison only. |

Tracking is not a similarity substrate. A track proposes interval plus identity continuity. Joining
it to a character name or an action is another explicit proposal with its own evidence and
receipt.

Embedding output is structurally unable to become a proposition. `Embedder.embed` returns a
`BatchResult` containing vectors and attempt receipts; the compiler consumes `EvidenceBundle` and
`PropositionChart[Checked]`. The forbidden conversion is
`ValidatedVector => PropositionChart`, including any latent decoder that returns a proposal. No
such lift will be introduced. Embedding-assisted retrieval remains lawful because retrieval
consumes vector types and nominates candidates without adjudicating them.

Something-Something V2 performance may be admission evidence for motion sensitivity; Kinetics
performance alone is not. Neither benchmark certifies narrative action. A candidate used for
action proposals also needs local counterfactual courts for opposite direction or temporal order
with identical objects, role binding, interval localization, and identity continuity across cuts.

No model named in the design discussion is selected by this ADR. Licence, weight availability,
gated-access terms, platform cost, and the local courts are separate admission gates. An owner must
accept any required access agreement; an agent may not do so on the owner's behalf.

### 7. Preserve claim status, extent, credence, and uncertainty separately

A detector does not assign its own `EpistemicStatus`. It emits typed alternatives, raw scores, and
a receipt. The compiler assigns the allowed status for that claim family under the same rule as
text proposals.

One detector derivation family contains multiple claims:

1. whether a boundary exists and where it is localized;
2. the transition morphology; and
3. only after acceptance, the shot adjacency induced by that boundary.

At proposal time, alternatives range over lawful dependent morphology-and-extent candidates.
`HardCut` has only an `Instant(PTS)` form; `Dissolve` and `Fade` have only interval forms. The
pairing is enforced when each candidate is constructed, so neither `HardCut(Interval(...))` nor
`Dissolve(Instant(...))` can enter an alternatives set. At acceptance, one resolved morphology
retains its required extent type. A resolver may accept that a boundary exists while retaining
alternatives for localization or morphology. Accepting a shot transition never entails a scene or
narrative-event boundary.

`MatchCut` is not a transition morphology. It is a content-similarity judgment across a
transition, with its own proposal, evidence, and receipt; a match dissolve must remain
representable. `NoBoundary` is not a morphology either. Detector coverage distinguishes:

1. `NotExamined`, when the detector supplied no coverage;
2. `ExaminedNoCandidate(policy, receipt)`, when it ran but no candidate survived the declared
   decision policy; and
3. a substantive negative-boundary proposal, which follows its own resolution path.

The second state is a derived search record about execution, not an observation that the artifact
contains no boundary. Its receipt binds the examined extent, model and weights, preprocessing,
selection policy, and calibration identity when any. It never constructs the third state.

Calibrated confidence, when available, belongs in `Credence` with its calibration model. Scores
from different detector heads are not probabilities and equal raw scores do not make them
comparable. Model and weights belong in the receipt. Unresolved alternatives remain alternatives;
a threshold does not turn uncertainty into an observation.

This rule also separates ASR confidence from dialogue content, diarization confidence from speaker
identity, and tracking confidence from entity identity.

### 8. Keep downstream narrative and recall semantics unchanged

Everything after a validated narrative graph retains its present meaning:

- situations, entities, contexts, relations, hierarchy, and story-world time;
- sparse local costs and graph-HSMM trajectory inference;
- omission, merge, split, reorder, revisit, elaboration, and explicit external states;
- recall signatures and population summaries; and
- the rule that embeddings are sidecars rather than identity or graph structure.

The aligner does need the typed primary-axis projection instead of `textLength`, and its result
proofs must retain axis identity. Its estimands and state model do not acquire film-specific
branches.

Recall remains text for the available film-recall corpora. It follows the existing recall
segmentation and proposition path. Film therefore uses Stage 1b twice: once for recall text and
once for annotation descriptions, ASR text, or generated event descriptions. Film does not bypass
the proposition-provider bottleneck.

### 9. Accept sparse source graphs and explicit refusal

A Sherlock build may emit situations and ordering while leaving most relations absent. It must not
manufacture relations from co-presence, treat a speaker label as an utterance proposition, promote
focus to salience, or infer a context from location.

The compilation retains:

- every proposal and non-accepted resolution;
- the exact annotation anchors and repair receipts;
- which source-side families were not represented;
- the sparse candidate set actually evaluated; and
- a validation report that distinguishes a useful partial diagnostic from a validated source
  model.

Alignment runs only when the existing validation boundary admits the source model. A partial
compilation remains publishable evidence; it is not coerced into a graph merely to make the
analysis run.

## Required laws and courts

The implementation slice must make these failures executable.

1. **Axis type:** text character and film PTS positions have no unchecked comparison, even when
   both render as the same normalized scalar.
2. **Axis identity:** positions from different source editions or streams refuse comparison until
   a matching alignment receipt is supplied.
3. **Fingerprint:** primary-axis kind, stream identity, extent semantics, and alignment receipts
   participate in source-view and result fingerprints.
4. **Missingness:** an absent primary projection remains missing through transition features,
   signatures, population aggregation, codecs, and benchmarks; it never becomes zero.
5. **Projection legitimacy:** mixed support never chooses order implicitly. The declared primary
   axis must be the audience-facing order of the pinned source edition.
6. **No mixed hull:** hull, overlap, contiguity, clipping, and ordering refuse anchors from
   incompatible axes.
7. **Boundary distinction:** a shot cut and a coded scene boundary at the same PTS remain distinct
   claims with distinct provenance.
8. **Transition extent (type-discharged):** a gradual transition cannot collapse to a point, and a
   hard cut cannot acquire an unmeasured duration, because unlawful morphology-and-extent pairs
   are not constructible.
9. **Transition rhyme (type and runtime):** visual similarity across a transition remains
   independent of its morphology; a match dissolve is representable and round-trips through the
   codec.
10. **Search coverage (runtime):** not examined, examined with no surviving candidate, and a
    substantive negative-boundary proposal remain distinguishable; no empty detector result can
    construct the negative claim.
11. **Calibration separation (runtime):** equal raw scores from hard-cut and dissolve heads do not
    imply equal probabilities; changing calibration cannot change transition identity.
12. **Independent proposals (runtime):** two models may disagree on boundary extent or morphology
    without overwriting each other's proposals.
13. **Boundary non-entailment (runtime):** accepting a shot transition never creates or accepts a
    coded scene boundary or narrative-event boundary.
14. **Clock repair:** raw run-local seconds cannot enter a continuous PTS computation without the
   declared repair receipt.
15. **Proposal boundary:** ASR and video captions cannot construct an accepted claim or checked
   chart without Stage 1b and deterministic resolution.
16. **Vector boundary:** no public or package-visible operation converts a validated vector into a
   proposition or typed claim proposal.
17. **Tracking boundary:** track continuity cannot become character identity or an action without
   a separately evidenced proposal.
18. **Sparse honesty:** absent roles, relations, contexts, or dialogue remain gaps, not defaults.
19. **Action court:** identical-object clips with reversed direction or order, role swaps,
   localization foils, and across-cut identity breaks defeat an unqualified action provider.
20. **Text compatibility:** existing checked text construction, exact `SpanSet` behavior, and text
   alignment remain unchanged for text-only consumers.

## Migration sequence

This ADR fixes the direction; it does not authorize production changes.

1. **Stage A — this ADR:** record the source, support, axis, atlas, compiler, and acquisition
   contracts. No code.
2. **Existing vertical gate (superseded by the 2026-09-19 amendment):** complete the already-scoped real-transcript path through Stage 1b
   to one answered research question. Stages B through D remain blocked until that gate closes;
   generalizing an unvalidated compiler seam would multiply an unknown.
3. **Stage B — artifact-specific admission:** keep actual episode video file contents external,
   pin shareable media hashes and stream metadata, pin the exact annotation and recall artifacts,
   specify the two-run timebase repair, check the 17-source alias mapping, and apply ordinary
   provenance and story-text admission to any non-video artifact proposed for Git. There is no
   additional Sherlock-specific licence, consent, REB, participant-prose, or aggregate-only hold
   on those non-video artifacts.
4. **Stage C — portable contracts:** add smart-constructed `SourceBundle`, `EvidenceSupport`,
   `PresentationAxis`, `NarrativeSourceAtlas`, PTS intervals, and typed boundary layers. Preserve
   text constructors and add no decoder or model dependency.
5. **Stage D — narrow integration:** add the read-only Sherlock atlas adapter; migrate the four
   compiler atlas annotations, the model envelope, and the two public alignment seams. Heavy media
   libraries remain in JVM provider/adaptor modules.
6. **Stage E — film vertical proof:** compile one lawful film source slice and one real recall
   transcript through one declared research question. Only then admit broader film providers or
   film-specific visualization work.

Stages B through E require new beads, exact path reservations, and their own review and gate
policies. Model adapters still require their own licence and access dispositions; the superseded
Sherlock source-artifact hold does not transfer to them.

## Consequences

- Film builds on the existing compiler, narrative graph, and recall alignment rather than forking
  them.
- The source can cite text, picture, audio, annotations, and tracks together without collapsing
  their coordinates.
- The primary presentation axis is explicit, typed, and part of result identity.
- Shot and scene boundaries remain comparable in time while remaining different scientific
  claims.
- `SurfaceAtlas` keeps its closed construction boundary.
- Text callers retain exact UTF-16 support and a text-workflow-compatible ordinary path.
  ("Source-compatible" is deliberately avoided here: in this repository `source` names the
  narrative source, and this migration IS Scala-source-breaking at `StoryModel`'s public fields.)
- The support migration is wider than four compiler annotations, but its semantic depth is
  concentrated in axis selection and missingness rather than in 47 independent normalizations.
- The Sherlock source is useful sooner, with a narrower claim: sparse temporal source alignment
  against a human-derived event representation.
- Film does not remove the Stage 1b bottleneck or the need for deterministic resolution.
- Model availability, licence, calibration, and benchmark admission remain unresolved until
  separately evidenced.

## Rejected alternatives

### Treat subtitles as the film source

Rejected because subtitles omit visible action and much of the perceived presentation. The result
would estimate recall of a text quotient while claiming recall of film.

### Add a sibling `FilmModel`

Rejected because narrative claims commonly mix picture, audio, and text support. Forking the model
would duplicate contexts, relations, hierarchy, validation, and alignment while making
cross-channel claims harder to express.

### Parameterize the entire `StoryModel` by one medium

Rejected because one film claim may cite several media kinds. The variability belongs in evidence
anchors and the selected presentation axis, not in one model-wide medium parameter.

### Normalize every source to a bare `[0, 1]` value

Rejected because character fraction and runtime fraction are distinct constructs, and different
film editions may share a numeric fraction without sharing an identity.

### Give shot and scene boundaries separate axes

Rejected for boundaries on the same edition's PTS. Their disagreement is epistemic and semantic,
not a coordinate-system mismatch. Distinct timebases or editions still receive distinct axes.

### Treat strong model output as observation

Rejected because confidence and epistemic status answer different questions. Every learned output
is a derivation with a receipt, regardless of cost or benchmark score.

### Decode embeddings into propositions

Rejected structurally. Vectors may retrieve or compare; they do not become narrative content.

## Implementation and documentation friction exposed

The ordinary film path is not yet explainable as a four-signature change. Current public types bind
source identity, exact evidence, lexical extraction, and presentation position to text at several
layers. The smallest coherent future API must therefore make three separations visible:

| Workflow | Current friction | Required improvement | Compatibility |
|---|---|---|---|
| Cite mixed source evidence | `Evidence.spans` admits text only. | Add nonempty typed `EvidenceSupport`; retain text constructors. | Source additions plus later codec version. |
| Hold the source on `StoryModel` | Public fields require `StorySource` and `SurfaceAtlas`. | Store checked bundle/atlas contracts; expose checked text-only access. | Source-breaking field migration plus codec version. |
| Place a node in presentation order | `textLength` and `Double` erase kind and missingness. | Axis-owned typed projection and result fingerprint. | Source-breaking align trait migration; semantic correction. |
| Compile film proposals | Compiler accepts concrete text atlas and span validation. | Checked atlas interface plus delegated support validation. | Four concrete atlas annotations plus support migration. |
| Reuse lexical alignment | `StorySourceView` slices canonical text directly. | Film bridge obtains descriptors from checked charts and receipted text streams. | Additive bridge; no change to cost semantics. |

Until those APIs exist and pass the courts above, documentation must describe film as approved
architecture and a measured Sherlock source opportunity, not as a supported library workflow.

## Amendments

### 2026-09-01 — `core.DerivationReceipt` renamed to `SourceDerivationReceipt`

The Stage C source contracts (salvaged in 12f15173) introduced `storymodel4s.core.DerivationReceipt`,
the checked receipt on `CallerRuntimePacketRecord` and the axis-repair records. The name collides
with the older `storymodel4s.document.DerivationReceipt` of ADR 0005: inside package
`storymodel4s.document`, a wildcard `import storymodel4s.core.*` outranks a package member defined in
another file, so `document` test sources resolved the core type and failed to compile on `main`.

Decision: the core type is `SourceDerivationReceipt`. Its identity computation and constructor
discipline are unchanged; only the name moves. The document compiler's `DerivationReceipt` keeps its
name because it predates the collision and ADR 0005 names it.

Rejected alternative: qualify the import in the document tests. That leaves two public types with
one simple name in sibling modules, which every future `import storymodel4s.core.*` inside `document`
would trip over again; the rule that names in `core` must not shadow names in modules that depend on
it is cheaper enforced at the source than remembered at each import.

### 2026-09-01 — `media`: the JVM media-acquisition adapter module

§4 reserves film ingestion for "separately scoped JVM adapters" and says adding one "requires its own
bead and dependency review". This amendment is that record, for bead `bd-01M1FDNN3T4SKZ5ZJN1XGXYNY7`
(first acquisition court, landing A; single-developer mode, so the author decides and records).

Decision: a JVM-only module `media` (`storymodel4s-media`, package `storymodel4s.media`), depending on
`core` and on circe for JSON. It is the only module permitted to spawn a process, and it does so in
exactly one place (`Subprocess`). No portable module depends on it. Its vocabulary:

- `ToolRealization` — name, `-version` line, and SHA-256 of the executable that ran; its identity
  enters every derivation receipt so a replay under a different binary is a different derivation.
- `FixtureManifest`, `DeclaredStream`, `DeclaredFrames`, `StreamDisposition` (`Decoded`,
  `PacketIndexedOnly`, `Unsupported`) — the exact-byte admitted-input manifest the ledger requires,
  carrying the generator's declared ground truth for project-authored media.
- `ProbeEnvelope` — a recorded invocation (tool, exact arguments, stdout digest); the "injected typed
  receipt" ordinary CI replays instead of executing a tool.
- `FfprobeJson`, `FfprobeOutput`, `RawStream`, `RawPacket`, `PacketFlags`, `Ffprobe` — the parser
  and the one admitted invocation (with `-protocol_whitelist file`). A literal `INT64_MIN`
  timestamp in the JSON is refused at parse; only an absent field is the typed missing.
- `MediaProbe`, `ProbedStream`, `ProbedPacket`, `PacketIndex` (with `PacketIndex.Entry`) — the
  adapter-side join of manifest, verified input, tool, arguments and output into
  `CallerRuntimePacketRecord`s under a `SourceDerivationReceipt`, plus an explicit packet-to-PTS
  table. Each `ProbedPacket` keeps core's `PacketTimeFields` and `MediaDuration` beside the draft
  record; missingness is read from those typed fields, never from the record's raw `Long`s.
  Authority is `Draft` and the type offers no promotion.
- `FixtureTier`, `DeclaredPicture`, `DeclaredAudio` — manifest declarations the join checks against
  the tool's report (pixel format, geometry, sample rate, channels). `DeclaredFrames` has a checked
  constructor only.

The join refuses, rather than repairs, on: input identity mismatch; an observed stream with no
declaration (the ledger forbids ignoring a stream); a declared stream the tool omits; codec, kind,
timebase, picture format or geometry, or audio parameters disagreeing with the declaration; a
present PTS before a present DTS (core's `PacketTimeFields` refusal, message preserved); a negative
duration. A missing PTS is a typed absence on the packet and refuses index construction; it is
never invented from a nominal rate. The index refuses a corrupt-flagged packet and an `Unsupported`
stream, does not present a discard-flagged packet, and refuses tick arithmetic that would overflow.
`StreamDisposition` is law, not label: only a `Decoded` stream may yield frames.

Rejected alternatives: (1) placing the adapter in `embed-bench`, which already runs the
recall-to-video pipeline — that module exists to score alignments against gold and drags in ONNX
and grakern, and an ingest adapter must not depend on either; (2) placing it in `acquire`, which is
portable and may not spawn a process (§5); (3) a portable pure-Scala demuxer — it would make the
project the owner of container semantics the ledger deliberately assigns to an exactly identified
external tool.

### 2026-09-01 — `media`: identified frames and boundary-localization proposals

Bead `bd-01M1FEN59CZCCR0SR3B6MZ1KYB` (first acquisition court, landing B) adds to `media`:

- `Ffmpeg`, `PictureGeometry`, `FrameSet`, `FramesEnvelope` — the one admitted decode (one picture
  stream to raw BGR24 with PTS passthrough, no scaling or cropping) and the join that binds the
  decoded bytes to the probe's `PacketIndex`. The join refuses unless the byte length is exactly one
  frame per indexed packet; a frame's PTS is looked up in that index and never computed from a
  nominal rate (§3.1).
- `ContentDetectorRecipe`, `DetectorRequest`, `DetectorOutcome`, `DetectorEnvelope`, `FlashFilterMode`
  — every detector parameter declared; an automatic kernel size must come back resolved in the
  outcome or the join refuses (ledger §5: no library default stands in for a recorded value). The
  worker is untrusted (§6 step 1); it echoes request identity, frame digest, count, geometry and
  recipe, and the join checks every echo (§6 step 2).
- `AppliedRecipe` — what the library installed, read back from the constructed detector's own
  state rather than echoed from the request; the join refuses unless it satisfies the recipe, and
  an automatic kernel size must come back as a positive resolved value. `FrameMetrics` — raw
  per-frame detector values. The outcome's claimed runtime must render to the worker
  `ToolRealization`'s version line or the join refuses; `WorkerRealization.observe` builds that
  realization by asking the interpreter for its versions and hashing the script, independently of
  the outcome, so the live courts compare two sources rather than the outcome with itself. The
  worker's interpreter and wheel bytes are not hashed, only its script.
- `BoundaryLocalizationProposal`, `BoundarySearch` and `BoundarySearchResult` — the typed
  candidate of §6's output table, row "shot, track, interval, identity, boundary": a
  boundary-existence and localization proposal on `BoundaryLayer.Shot`, at the instant of the
  first frame after a visual discontinuity, with the window between the two frames and the raw
  score. It carries no `ShotMorphology` and no extent claim, so it is not a `BoundaryClaim` and
  offers no method to one (law 8; ledger §5 forbidden authority); its `BoundaryId` binds the
  recipe. Empty detector output yields `BoundarySearchCoverage.ExaminedNoCandidate` on the
  examined extent and can never construct a negative claim (law 10). Authority is `Draft`.
  The axis is the picture stream's native presentation clock admitted as the edition playback
  axis under one recorded assumption, named in the receipt: that stream's edit list is the
  identity. The join checks the evidence it has (the tool's reported stream start equals the
  first presented PTS and no packet was discarded) and refuses when either signal says otherwise;
  an edit list the demuxer folds without either signal is not detected, since the `elst` itself is
  never read; a checked
  `TrackComposition` receipt, and any non-identity edit list, belong to the E0 court.

The worker itself lives at `media/worker/` as a uv-locked Python project pinned to the ledger's
`scenedetect-headless` 0.7.1 wheel digest; it never opens a container and never computes a
timestamp.

Rejected alternatives: (1) `scenedetect.detect()` / `open_video()`, which would let the
library own decoding, frame timing and a nominal frame rate (ledger §5 forbids exactly this); (2)
emitting `BoundaryClaim.shot(HardCut(instant))` directly, which would assert a morphology the
detector cannot establish; (3) a JVM-side reimplementation of the detector, which would make the
project the owner of an algorithm the ledger identifies by exact upstream commit.

### 2026-09-02 — `media`: timed visual descriptions as proposals (captioning lane)

Under the captioning admission record (`docs/design/vlm-captioning-admission-record.md`, ledger
§6.3; owner decisions of 2026-09-01 and 2026-09-02), `media` gains the G1 timed-language entry
for machine descriptions:

- `ModelPin` (repository, revision, and the digest of every file the worker reads: shards, weight
  index, config, tokenizer, chat template, preprocessor and generation configs; the worker refuses
  an unlisted weight file or an index naming one, and the join compares the echoed config digest and
  the verified file set), `CaptionRecipe` (fixed prompt by
  digest, pixel limits, token budget, seed; frames always supplied explicitly as an image sequence;
  greedy decoding only), `CaptionExtent` (a nonempty, strictly increasing list of frame ordinals),
  `CaptionRequest`, `CaptionOutcome`, `AppliedCaptionRecipe` (what the processor applied, read
  back), `CaptionResult`, `ModelEcho`, `CaptionEnvelope`.
- `CaptionProposal` — one described extent: the text, the frames shown, and the playback interval
  those frames span on the picture stream's axis, closed on the last frame's known duration from
  the packet index; plus the recipe and model identities. It is a `Proposal` with `Draft`
  authority (§6 output table, row "ASR text or visual description"): it says what a model wrote
  about these frames. The evidence that the declared pixel limits were applied is the processor's
  own image grid per frame, which the join checks against the limits (`caption/applied-grid`); the
  F0 recipe declares limits that differ from the model's defaults so that application is visible.
  The worker forces the offline switches on and reports them; the join refuses an outcome that
  did not run offline. It is not a `TimedSegment`, offers no conversion to one, and cannot enter
  the recall-to-video pipeline without a receipt-carrying adapter that does not yet exist.
- `CaptionSearch` / `CaptionSearchResult` — the join, which refuses when the request does not
  describe the frames, when the outcome answers another request, other frames, another recipe,
  another model or an unverified one, or another runtime than the worker it is joined under; when
  the applied recipe does not satisfy the declared one; when coverage is partial or a result does
  not answer its extent; or when an extent cannot be closed on the axis. It reuses the boundary
  court's identity-edit-list guard and names the same assumption in its receipt.
- `WorkerRealization.observeCaption` — the captioning worker observed independently of its
  outcome (interpreter versions plus script digest), as for the boundary worker.

The worker lives at `media/worker-vlm/` (uv-locked: `mlx-vlm` 0.6.17 and `mlx` 0.32.2, MIT) and
verifies the local model directory against the request's shard digests before loading it; it runs
offline. Rejected alternatives: (1) letting the model see a video clip and its own frame timing,
which would let the library own sampling and time (ledger §5 pattern; the adapter supplies frames
by ordinal instead); (2) emitting `TimedSegment`s directly, which would put a proposal where the
recall-to-video pipeline expects trusted source text; (3) a remote captioning API, rejected in the
admission record on rights and privacy grounds. 

### 2026-09-19 — D1A source-to-story contracts and the engineering gate

This amendment records the owner's 2026-09-18 rulings A, C, D and E in the
[approved D1A revision 4](../plans/2026-09-17-d1a-source-to-story-plan.md). It supersedes
conflicting earlier descriptions of the atlas, support migration and release sequencing in
this ADR. [Delivery PLAN](../refactor/PLAN.md) governs the current implementation order;
[S1](../refactor/goals/film-foundation-20260919.md) records these decisions before new public
vocabulary enters code.

**A — engineering precedes research claims.** The owner ruled: "we're developing software.
build first, then researchers ask questions." The answered-research-question requirement in
migration step 2 no longer blocks engineering stages B–D. Its engineering purpose survives:
generalizing the compiler must not silently change the existing text behavior. The completed
[S0 text parity court](../refactor/evidence/d1a-s0-text-parity-20260919/README.md), qualified at
`051f88f3fc94e72ed484b672937c351cafe3618e` against production baseline
`27ebdb71c33fcdccd97f109b46e30006f3fbcce8`, pins that behavior. Later slices retain those
values; compatibility evidence does not scientifically validate the text compiler. Research
comparisons remain separately gated in the delivery plan and do not determine whether ordinary
film engineering can proceed.

**C — split the work and preserve every approved slice.** D1A-types is S0 through S4c. It makes
the model, nodes, evidence and alignment source capable of carrying film support, without
compiling film. D1A-film subsequently populates the proposal surface and compiles a lawful film
source through the deterministic stages. D1B's public signatures follow D1A-types; hand-built
film models can exercise them. D1B's end-to-end proofs require D1A-film.

**D — one bundle and a separate proposal surface.** A model has exactly one `SourceBundle`.
Multiple film parts compose through checked `TrackComposition`; they do not become unrelated
bundles inside one model. The proposal surface is an annotation or caption derivation outside
that bundle, bound by checksum and derivation receipt. Existing film anchors therefore retain
their bundle identities when proposal text is added. The Sherlock adapter becomes a checked
`AnchoredNarrativeAtlas` construction over one composed bundle, carrying its admitted repair
receipts. Bundle composition and that adapter belong to the later film work.

**E — the library's end-to-end film route remains required for 1.0.** D1A-types, D1A-film and all
of D1B must demonstrate that a film source compiles through the API, recall aligns against it,
and results carry exact playback intervals. The full film-model wire format V1 and Sherlock
terminal E0 remain additive 1.x work under their own versions. The mapper plan's proposed P1
alternatives are **not adopted**: an annotation-assisted preview does not replace this route,
and a benchmark win does not gate film engineering. Generated captions' eligibility to license
`SurfaceExplicit` remains a distinct owner decision for the film phase plan; this amendment
does not assign that license.

#### Public vocabulary and construction boundaries

- `NarrativeSourceAtlas` becomes sealed, with final `TextNarrativeAtlas` and
  `AnchoredNarrativeAtlas` implementations. `surfaceAtlas` leaves the general trait.
  `AnchoredNarrativeAtlas.of` checks bundle membership, admits only an `EditionPlayback` primary
  axis in this slice, refuses duplicate proposal-unit IDs before map lookup, and refuses unit
  surfaces absent from, or duplicated in, the bound proposal surface.
- `BoundProposalSurface` derives a checksum over source identity/canonical bytes and canonical
  unit structure (ID, kind, span, ordinal, tagged parent), and an association identity binding
  that checksum, the canonical source checksum and the supplied `SourceDerivationReceipt.bindingIdentity`.
  Free-form fields are hashed before joining. The receipt records inputs, so this association
  is not independent output attestation. A proposal unit's optional `SurfaceUnitId` names a
  sentence of that one surface. The bound derivation never becomes canonical source text.
- `TypedSupport` is the core sum `Text(SpanSet)` or `Anchored(EvidenceSupport)`; acquire can
  consume it without depending on story. `Evidence.anchors: Option[EvidenceSupport]` defaults
  to absence; existing text evidence bytes retain their omitted-anchor form.
- `PrimaryProjection` is derived support on the model's primary axis:
  `TextSpans(axis, spans)` or `Playback(axis, intervals)`. No cached projection travels inside
  `TypedSupport`. `EvidenceSupport.intervalsOn(axis)` merges overlapping or abutting intervals
  on that axis canonically, independently of order and idempotently. Text span access remains
  per stream.
- `StoryText` is the checked canonical-text witness containing source, surface and stream.
  `TextModel[S]` binds that witness to a `StoryModel[S]`; neither has a public construction
  door. `StoryModel.asText` yields the witness exactly for `TextNarrativeAtlas`. Text validation,
  adjudication and compilation preserve it. Text slicing, covering, rendering, model codecs
  and other consumers named in the approved plan require `TextModel`.
- `hasSupport` replaces acquisition's `hasSpans` predicate, consuming the new typed support
  accessor on `EvidenceRef`. Existing wire-visible gap names `NoSpanEvidence` and
  `MissingSpanEvidence` stay unchanged, and the text verdict court must remain identical.

#### Canonical form, identity and projection

A `TextCharacter` primary axis requires `TypedSupport.Text`; the admitted non-text primary kind
`EditionPlayback` requires `TypedSupport.Anchored`. Other primary kinds are refused by the
anchored atlas in this slice. Text-primary models cannot carry evidence anchors; film evidence
cannot carry bare spans. Upstream-only evidence may carry neither. The canonical
form must be checked wherever evidence meets a bundle: the atlas constructor, compiler-input
construction (including inline evidence and ledger evidence), and model draft construction.
`StoryModel.draft` becomes fallible and its internal copy path uses the same checks. The full
envelope checks every node support and all evidence, including `BoundaryBelief.evidence`, for
canonical form and bundle membership; anchored node support must project to the primary axis.
Span extent remains a validator law, preserving drafts that carry text-bound violations.

Text identity remains the source's existing story ID (including an explicitly supplied ID)
and canonical checksum. Non-text identity is derived from the domain `story-anchored`, full
`SourceBundle.identity` and explicitly tagged bound-surface association identity or absence.
The story ID uses `ContentAddress.of`; the receipt source checksum uses the full untruncated
SHA-256 of the same identity input. `draft` checks both receipt fields against the atlas-derived
values for text and non-text models. A bundle ID alone is insufficient: two bundles can share
its truncated ID while differing in axis extent or timebase. The full bundle identity includes
all stream coordinate metadata, the primary-axis fingerprint, ordered authority tracks and
sorted `CheckedMapping.identity` values. Full mapping identity includes family, axes, exact
rational or occurrence/interval/pair payload and safe receipt binding identity. The additive
`SourceDerivationReceipt.bindingIdentity` hashes algorithm and parameters separately with ordered
input checksums under its own domain, preserving historical `receipt.identity`. A paired NUL-in-
algorithm versus NUL-in-parameters witness must distinguish the collision in the legacy join.
The courts distinguish
changed mapping parameters with unchanged relation IDs, changed secondary-stream coordinates,
changed primary extent/timebase, and changed proposal content or associated receipt.

Ordering uses projection start, end, then node ID. For text it must preserve the existing
`minSpan` ordering pinned by S0. Discourse order and position live on the model, which owns the
bundle; unbound graph-only versions become internal. Checked
`NarrativeGraph.discourseOrderOn(bundle)` shares the projection implementation for the compiler's
pre-model trajectory construction. `AlignmentSource` becomes sealed: the general
source exposes evidence and primary projection, while its text-specific construction retains
text `sourceSupport` for `StorySourceView`. D1A changes no alignment scoring behavior.

#### Wire compatibility and rejected alternatives

Component encoders remain total. Anchored values gain the self-versioned `evidence-support/v1`
sub-shape, retaining the complete payload for each anchor kind: text bundle/stream/spans;
media-time bundle/stream/axis/intervals; shot bundle/stream/shot ID/interval; and track
bundle/stream/track ID/intervals. Version 0.7.0 decoders refuse anchored sub-shapes with typed
errors, and each anchor case has an encode/typed-refusal court. The unversioned `JsonLines`
claims carrier can encode anchored evidence but cannot promise its round trip under those
decoders.

The model schema remains 0.7.0. At S4b model encoding requires `TextModel`, whose canonical form
contains no anchors. S2 first installs temporary anchor refusals in every existing text-model
construction path, including boundary evidence, and in text compiler-input inline/ledger joins.
The still-total model constructor throws `IllegalArgumentException` for this newly expressible
invalid input until S4a replaces the refusal with `Either`. Anchored component decoding is
explicitly refused. A guard-removal mutation must demonstrate the export boundary, so no
intermediate landed model can acquire the new anchored sub-shape. Its absence
is unambiguous and text bytes stay unchanged. This differs from the 0.6.0 → 0.7.0 transition,
which began writing a new field into model artifacts. Model, derivation and claims round-trip
laws are explicitly text-only; V1 must version a film artifact when it first writes one.

Rejected alternatives are recorded before implementation:

- A phantom medium parameter on the whole model cannot describe mixed evidence and is already
  rejected above. A second capability type parameter on every model occurrence adds pervasive
  API complexity; the atlas-minted text witness supplies the needed guarantee locally.
- Optional text fields permit a model to be paired with foreign text. The checked witness binds
  the source, surface and stream to that model instead.
- Making text `encode` return `Either` moves an impossible text-capability refusal into every
  caller. Requiring `TextModel` establishes that capability at construction.
- Putting proposal text inside the film bundle changes the bundle ID with every annotation
  set and requires rebasing existing film anchors. The separate bound surface preserves those
  anchors and contributes its checksum to model identity.
- Multiple independent bundles inside one model weaken the single-source join; checked
  composition supplies one bundle for multipart film. Composition is not implemented by this
  amendment.
- Hashing only `MappingRelation.id` is rejected: it binds family and endpoints, so distinct
  scales, offsets, composition segments or occurrence correspondences can share it. Full checked
  mapping content and receipts participate in non-text model identity.

The pre-S2 revision-4 cold review on 2026-09-19 required these construction and staging
corrections; [revision 5 of the plan](../plans/2026-09-17-d1a-source-to-story-plan.md) records the
complete stream-kind/anchor compatibility table, native-or-explicitly-mapped axis rule, exact
temporary refusals and witnesses. `DerivedClock` and `Custom` receive no implicit support
capability. A mapped anchor must lie wholly in the mapping image: composition preserves target
gaps and validates source segments against the selected stream; ClockRepair maps exact native
seconds (ticks times rational timebase) through its scale/offset with no rounding.
`AnnotationTimeline` stays on the existing annotation-preview path. Admitted
Sherlock row 13 is an instant; D1B must add an explicit point-capable projection before claiming
to carry that source inventory. It cannot substitute a fabricated positive interval.

This is a design and compatibility record. It adds no production capability, establishes no
new scientific validity, and does not claim executed CI or film compiler completion.

#### S3 acquisition boundary clarification — 19 September 2026

`SourceSupport` carries optional `TypedSupport` with a text constructor and derived
`spans`. `EvidenceRef.support` returns a singular support only for spans alone or anchors
alone; detached twin-form evidence and `ById` return `None`, while the raw text accessor
is retained. Selecting anchors first is rejected because it hides the second coordinate
form. Resolver support presence comes from the bundle or the winning proposals. This is
neither a bundle join nor a license for `SurfaceExplicit`; `ClaimMeta.spanLaw` stays on
text spans. The historical policy and gap names remain wire-compatible.

The shared text compiler-input validator refuses anchored `SourceSupport` until the
film compiler defines its input and canonical rendering. Accepting it while the current
renderer only fingerprints derived text spans would discard identity-relevant payload.
This temporary refusal requires explicit text/absent controls and a compiled deletion witness.


#### S4a checked text staging and projection meaning — 19 September 2026

S4a changes node support to `TypedSupport`, with text companion overloads and an optional
`TypedSupport.textSpans` accessor. A throwing generic span cast is rejected. The component
encoder writes Text as the existing span shape and Anchored as `evidence-support/v1`; the
text decoder explicitly refuses the latter. The model remains text-only in this slice.
`StoryModel.draft` and its internal copy return `Either[DomainError, StoryModel[...]]` and
check every node/circumstance support plus all ordinary and boundary evidence. They do not
move text extent laws into construction. Status-only promotion preserves the admitted value.

`PrimaryProjection.on(bundle, support)` is the checked projection entry point. S4a admits
only Text on a TextCharacter primary, preserving the original SpanSet and exact hull bounds;
other families/kinds return typed refusals, even for empty-graph ordering. Graph pre-model
ordering uses that projection. The joined model exposes discourse order/position and ordered
entity/context/within/covering queries from the same derived order. All unbound graph ordering
forms become internal; internal helpers may consume an already checked order. A private
constructor may retain that derived order, but it is never an independently supplied public
claim or an input cache on TypedSupport. Draft and copy derive it afresh from the resulting
graph and bundle; neither public factory nor internal copy accepts an order argument. Only
status-only promotion with the same immutable graph/bundle may reuse the cache. A changed-support
copy must reverse its order/ordered queries as expected, and a stale-cache mutant must fail that
witness. Bundle-changing copy witnesses join S4b. Text trajectory derivation is checked, and compiler
failures propagate through its typed error channel without dropping nodes or falling back.

At S4b the anchored primary projection selects the exact union of all intervals already on
primary. Other native/text anchors remain in the original evidence, unchanged. A direct
primary anchor makes this selected view total over admitted models; it does not convert every
anchor or certify all-channel temporal coverage. Native-only support cannot acquire primary
coordinates merely because a mapping exists. Implicit mapping and silently narrowing admission
to all-anchors-primary are rejected; complete mapped projection is a different future contract.

The fallible factory does not justify weak compile probes: fixture helpers still extract an
actual StoryModel, copy/Product/Mirror probes isolate their own boundaries, and every visibility
mutation gets a clean recompile. A copied Either would falsely hide copy visibility; Product
instead fails visibly. These are corrections to probe design, not relaxed acceptance.

#### S4b factory and text-validation refinement — 20 September 2026

`StoryModel.draft(atlas: NarrativeSourceAtlas, ...)` constructs the general envelope.
`StoryModel.draftText(surface: SurfaceAtlas, ...)` constructs and returns a checked
`TextModel`; its source is the surface's own source. An independent source argument is
removed rather than retaining a redundant pair. Separate names avoid Scala's conflicting
default-argument overloads. Both use the same canonical-form, membership, primary-projection,
receipt and order checks. Text extent remains a validator law.

`TextValidationOutcome(report, validated: Option[TextModel[Validated]])` is an additive
carrier beside the existing general `ValidationOutcome`. A generic or higher-kinded outcome
redesign is rejected because it adds migration without strengthening this witness. One
validation/report/policy implementation serves both routes. The general `validate` retains
its default policy; text overloads are `validate(text)` and `validate(text, policy)` without
duplicate defaults. Text validation and adjudication promote their own admitted model and
retain its unchanged atlas-derived witness. They never recover text with `flatMap(asText)`
or convert an invariant violation into an ordinary failed validation.

The text compiler, codec, materializer and view pipeline retain this capability in both
inputs and returned values, including the compiler-required-gap `None` branch. Promotion
joins compare underlying model values; separately allocated text wrappers have value
equality and hash semantics. This outcome carrier does not attest that an arbitrary report
belongs to an arbitrary draft; existing model/receipt joins remain mandatory.

`StoryText` and `TextModel` are final checked wrappers. TextModel explicitly forwards named
immutable reads and text queries; `source` and its compatibility `atlas` alias come from its
own text witness, while `model.atlas` remains the sealed general atlas. Wildcard forwarding,
implicit model conversions, public copy/status methods and helpers pairing foreign text
with a model are rejected. The existing public node carriers remain detached values;
the shared model join establishes their canonical form. Construction probes distinguish
those intentionally open carriers from the closed model/text witnesses.

Generic validation retains the existing consistency rules that inspect only graph, status and
context: retrospective duplicate occurrence, root Reported refusal and hypothesis subject.
Only the overlap-dependent duplicate-report branch and causal text cues require TextModel.
The aggregate text report and its ordering remain unchanged; losing the text witness cannot
silently remove a medium-independent rule or license a text-only one.

The hypothesis-subject rule remains shared, but a lawful film model currently cannot carry
a `SurfaceExplicit` subject: ClaimMeta requires bare text spans and the film join refuses
them. Its text court remains executable; the film court proves that construction refusal.
This limitation is not film-side execution of the rule and does not settle caption licensing.


#### S4c alignment-source capability refinement — 20 September 2026

`AlignmentSource` is sealed. Its existing validated and adjudicated factories retain their
status requirements and expose exact `evidenceOf(node): Option[TypedSupport]` and
`primaryOf(node): Option[PrimaryProjection]`. Existing nodes use the model's admitted support
and projection cache; a missing node returns None. This neither drops native evidence nor
maps additional anchors into primary coordinates.

Overloads on the corresponding TextModel return sealed `TextAlignmentSource`. It owns the
same model's StoryText and keeps `sourceSupport(node): Option[SpanSet]` for text consumers.
Both implementation classes are private within the companion; exposing a wildcard-status
implementation constructor would bypass the status boundary and is rejected.

`StorySourceView.apply` accepts only TextAlignmentSource, without an independent model
argument. Its text is derived from that source's own StoryText. The previous pair could
combine one model's supports and relations with another model's canonical text. A runtime
identity check on that redundant pair is rejected in favor of removing the pairing door.
Validated/adjudicated conveniences preserve their existing model inputs and build the
corresponding text source internally. Shared relation calculations, numerical feature
inputs and scoring are unchanged.

The prior description of the HSMM checksum as unused was overbroad: the separate JVM
resource suite already asserts it and runs in the full gate. The portable two-test suite
only checks canonical encoding and contextual round trips. Retain both, and compare the
candidate's exact output with each frozen pre-migration backend artifact on the recorded
runtime. Existing Native differences remain labelled; no universal cross-OS checksum or
new tolerance is adopted. Point support still belongs to the subsequent D1B slice.

#### D1B typed recall signatures and admitted points — 20 September 2026

This slice follows S4c's completed local acceptance at `146df652`. Its ordinary Mote
close was rejected by automatic approval review despite explicit owner authorization;
that administrative status does not reopen the passed substrate boundary.

Add `EvidenceAnchor.MediaPoint(bundle, stream, at: PlaybackInstant)`. Its axis is derived
from `at`; it obeys the same stream-kind table as MediaTime. Native and mapped image
membership is half-open: included start, excluded end, with composition gaps preserved.
Use existing exact PlaybackInstant construction, never an empty or invented-duration
interval. Add checked, final `PlaybackSupport` with one axis, a canonical interval union
and sorted/deduplicated points, requiring at least one member. Keep points explicit even
inside intervals. `EvidenceSupport.playbackOn` derives it; PrimaryProjection.Playback
carries it. Ordering uses exact min/max bounds; containment checks the full union plus
point membership. Explicit interval-only access remains named as such; the complete
projection must never use it to erase points. An interval-only hull is refused whenever selected support contains points.
Reject an interval-or-point sum because it loses mixed parent support.

`EvidenceSupport` retains the full checked bundle identity, and `TypedSupport.identity`
binds it plus canonical, tagged anchor payloads, including axis, tick, anchor family,
stream and text reference details. This is additive to historical bundle IDs. The detached
component encoder uses `evidence-support/v2` when points occur, leaving existing interval
components unchanged; the text model decoder continues to refuse anchored components.
This component shape is not an HSMM wire format or a film model codec.

`NodeSummary.support` and `CellCoordinates.sourceSupport` become TypedSupport. A required
`ScoringPosition` is separate: `CanonicalText(spans)` or `LegacyAnnotationText(spans)`.
These are feature coordinates, not physical support. The existing text constructor derives
the canonical-text feature; the typed constructor requires it explicitly. SourceView's
required `scoringLength` replaces the misleading general `textLength` name. Relative-span,
measured-position and transition Backward/LongJump arithmetic reads only this declared
feature and its denominator. Missing position remains None; no zero fallback is introduced.
The Sherlock adapter preserves its old joined-description offsets, group feature hulls,
and denominator solely as LegacyAnnotationText scoring inputs. Physical leaf and parent
support is checked media evidence, never that document or its hull.

For text nodes whose canonical scoring spans equal their support, preserve the exact v1
fingerprint token stream, including the historical textLength token. Otherwise use a
versioned fingerprint with separately tagged complete physical support and scoring feature.
Changing either must change identity. `HsmmResult.validated` derives and retains immutable
support for the view's entire node inventory, including unused candidates and point-only
parents; no caller-supplied support map enters the constructor. CellCoordinates reads that
retained support after the existing result/view/recall join. Existing proof/gate checks stay.

No generic non-text wire representation: hsmm/v3 JSON remains text-only and byte-identical;
every generic encoding door refuses a non-text result with a typed error; any v4 support-
bearing wire is deferred. Change both encode and toJson to checked Either results, remove
the generic Encoder[HsmmResult], and refuse non-text views at both contextual decoding
doors. Use HsmmCodecError.UnsupportedSupport for this refusal. A forged generic total
encoder or silent support omission is rejected. WOG bytes remain the exact text court,
separately from the new negative construction/encoding probes and compiled mutants.

The admitted Sherlock adapter builds one composed edition from its two immutable part
bundles in manifest presentation order. A checked TrackComposition records one explicit
occurrence per part; exact rational tick conversion must be integral and representable.
Retain both part-native and composed-primary anchors for every row. The composition uses
part duration/timebase metadata, not the notebook run offset. Bound the proposal atlas to
these checked supports; no generated caption receives SurfaceExplicit authority. Parent
support is the union of member anchors, preserving points and gaps. Existing report loci
remain part-native, so the frozen all-17 report comparison still tests the same coordinates.

Acceptance includes every admitted row (especially row 13 at native tick 112500), exact
point/interval/mixed support and projection, construction/Mirror and foreign-coordinate
refusals, complete result retention, fingerprint mutations, all v3 doors, frozen S0/WOG
bytes, all 17 participant output projection parity and the independent 1000-row coordinate
oracle. Preserve the preexisting backend differences and frozen expectations. Record the
public stability boundary in docs/api-stability.md; generic film wire/compiler licensing
and empirical claims remain outside this slice.

Full support binding is strict at every model/atlas/projection join: equal legacy IDs do
not excuse a changed bundle identity. A legitimate bundle edit explicitly reconstructs
EvidenceSupport.of(newBundle, oldSupport.anchors.toVector), validating every anchor and
retaining the new full identity. Discarding a successful revalidation while keeping the
old binding is rejected. Existing bundle-copy courts must perform this explicit rebuild;
a separate stale-same-ID control must refuse. This affects anchored joins only.
