# ADR 0007 — Represent film through typed evidence and a presentation axis

**Status:** Proposed architecture; document-only Stage A

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
2. **Existing vertical gate:** complete the already-scoped real-transcript path through Stage 1b
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
