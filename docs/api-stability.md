# Source and alignment API stability

This records the D1A/D1B source migration for library callers. The design decisions
are in [ADR 0007](adr/0007-film-source-representation.md). D1B qualification is
pending; this page describes the candidate API, not a release certification.

## Owner-approved 1.0 stability boundary (2026-09-23)

**Owner approved on 2026-09-23; not an API freeze or a release certificate.**
The governing decision is `bd-01M35JGSMRABV1ANW341W76JWV`; the release gate is
`bd-01M2TAMJ95K9D7H248A8387YW4`. The classifications below describe the intended
1.0 contract. Unimplemented entries still require their existing delivery and
construction-audit gates. The owner confirmed stable mapping/source contracts and
their required `Estimate`/`HsmmResult` values, experimental inference and narrative
implementation APIs, and the linked dispositions below. The final symbol audit
and release gates remain required; this decision does not freeze unfinished APIs.

Stable means a supported public contract from 1.0.0 onward. Experimental means
usable and tested where qualified, but its Scala API can change in 1.x with a
migration note. Internal means implementation or test machinery, with no supported
consumer entry point. Availability and scientific validity are separate from all
three classifications. Experimental production paths still owe their correctness,
missingness, permission and evidence checks.

| Package or type family | Intended 1.0 status | Reason and boundary |
| --- | --- | --- |
| `core`: IDs, checksums, text spans, surface axes, source bundles, typed evidence/support, provenance and permission values used by the public mapping contracts | Stable | These identify observations and exact evidence; consumers must be able to join and inspect them without depending on an estimator. |
| `proposition`: `PropositionChart`, `PropositionEvidence` and their checked value vocabulary | Stable | Canonical local semantic evidence appears in source and recall values. Parser output quality remains a separate claim. |
| `recall`: `RecallUnit`, roles/modalities, `RecallInventory` and checked observation identity | Stable | One fixed inventory and explicit unit outcomes are the public accounting contract. |
| `recall`: automatic segmenter heuristics and tuning configuration | Experimental | A named/versioned segmentation profile identifies output; its implementation can evolve without changing old artifact identities. |
| `align`: `SourceView`, `NodeSummary`, source references, typed physical support, scoring positions and the value types in their public signatures | Stable | D1B's text/media distinction and exact support are part of the preserved public source commitment. |
| `features`: `Estimate`, `ScoreEstimate`, `Coverage`, missingness/reason values and other values exposed by the stable source/mapping signatures | Stable after the missingness repair | `SourceView` already exposes these types; their constructor and pattern-match shapes must be settled before freezing it. |
| `features`: `FeatureTrack`, planning/reducer APIs and feature derivation machinery not exposed by stable signatures | Experimental | Keep research feature construction extensible; artifact identity and declared reducer semantics remain exact. |
| `align`: checked G1 mapping records, candidate/outcome/measure/decision vocabulary, support assessments, bindings and stage receipts | Stable | These are the inspectable, complete-accounting consumer result and preserve unknown provenance and unavailable calibration. |
| Public mapping facade, named configuration/profile inputs and checked run envelope (implementation pending) | Stable target | Researchers need one supported invocation boundary; the existing facade/profile tickets must first implement and qualify it. No proposed spelling is frozen here. |
| Registered shared-evidence and channel receipt values exposed by the public facade (completion pending) | Stable target | Reference and reconstruction must consume the same admitted evidence. The current engine identity alone does not establish a complete execution receipt. |
| Declared candidate tie policy, budget and typed overflow/refusal receipt values exposed by that facade (implementation pending) | Stable target | Identifier-independent tie handling and explicit refusal belong to the public evidence contract; the final names and checked constructors still require implementation and audit. |
| `align`: `HsmmResult` and the checked result values required by `DerivationContext`/G1 result binding | Stable structural result | The current contextual mapping decoder exposes this dependency. Checked structural consistency does not certify a custom cost invocation or calibration. |
| `align`: `GraphHsmm`, current engine `LocalEvidence`/`LocalEvidenceId` construction, `CandidateGenerator`/`CandidateSet`, custom `LocalCostModel` and low-level inference/ablation configuration | Experimental | Research extension points remain available; promote exact public dependencies if the final stable facade exposes them. No universal invocation-authenticated or calibrated-HSMM claim follows. |
| `align`: `RecallSignature`, `WeightedCoverage`, `MassRatio`, `StepMass` and the organization/readout helpers | Experimental | Organization measurement is 1.1 scope; residual denominator, grain and constructor work must not be implied complete by the 1.0 mapping release. |
| `codec`: contextual mapping/workspace readers, checked exchange writer and their public error/context/result values | Stable | An independent consumer must be able to validate exact joins and refusals. Wire/schema compatibility is separately versioned below. |
| G2 mapping exchange tables and manifests; new source/StoryModel export formats | Stable versioned format target | Retain exact accounting, declared loss and input identities. Each format still needs its own schema, independent reader and acceptance evidence before release. |
| `story`: `StoryModel`, narrative ontology and source adapters; `document`: `NarrativeCompiler` and proposal payloads including `CausalProposal` | Experimental | The owner chose an experimental narrative compiler for 1.0 and a film compiler for 1.1. Real-text validation and honest abstention remain required. |
| `acquire`, `amr-interop`, `embed-core`, `embed-grakern`, parser/agent-provider implementation APIs | Experimental, except signature dependencies explicitly promoted by the audit | Provider runtimes and acquisition strategies can evolve; public mapping receipts must still name exact inputs, model/configuration and outcomes. |
| `media` and `corpus-intake` Scala adapters and worker protocols | Experimental | Their 1.0 ingestion commands owe the checked source-package contract, exact media receipts and offline missing-capability behavior; this does not freeze adapter internals. |
| `pipeline`: documented released commands and their versioned machine-readable receipts | Stable command target | The release-infrastructure ticket still owes installation without a source checkout and tested argument/exit/receipt behavior. |
| `pipeline`: orchestration helpers and implementation classes | Internal | Only documented public entry points carry the command contract. |
| `fixtures`, `laws` and `MappingMiniature` | Experimental test support | Published test support carries no scientific authority and is not the production source contract. |
| `embed-bench`, workers, test harnesses and repository tools | Internal | Reproduction tools are version-pinned evidence producers, not the researcher-facing library API. |
| Other public symbols not covered above | Experimental pending audit | Public visibility alone is not a stability declaration; the construction audit must resolve any symbol required by a stable contract. |

### Signature closure before the freeze

The pre-freeze audit `bd-01M19N0W937KVZCK78F6X57X1D` must enumerate the actual stable
symbols and their public argument, return, parent and constructor types. A type
reachable through those signatures cannot remain experimental under a stable
promise: promote and audit it, or revise the boundary before the owner confirms
the final freeze. This is a release blocker, not permission to publish a mixed
stable/experimental signature silently.

A concrete current dependency is `align/source.scala`: source values expose
`Estimate`, `Coverage`, `MissingReason` and proposition/recall values. Consequently
marking all of `features` experimental would leave the `SourceView` promise
incomplete. The narrower stable estimate family above is deliberate; it does not
freeze every feature builder or reducer. A second current dependency is
`codec.DerivationContext.result: HsmmResult`; the structural result type is therefore
included in the stable closure while the inference engine remains experimental.

The construction audit must follow that result closure into `StructuralCoverage`
(through `CostBreakdown.sourceChartCoverage`) and `TransitionFlow`/`FlowStep`
(through `HsmmResult.flow`). Their public case-class constructors can currently
create values that violate the coverage-count or finite/nonnegative-mass
invariants checked by enclosing records. Alongside the existing `Admissibility`
repair, settle these construction boundaries before freezing the result family;
enclosing validation does not make each independently constructible value checked.

### Approved linked decisions

| Issue | Approved disposition | Release consequence |
| --- | --- | --- |
| `bd-01M17GC475EPDVW5Z4D776AYH0` (typed narrative licensing basis) | P3; proposal payloads remain experimental. Preserve the conservative `Hypothesized` floor. | No new pre-freeze licensing API is required. This does not waive the real-text/abstention court. |
| `bd-01M17AGFAVE90MA9QS50V0FTZD` (signature support grain) | P3 together with experimental `RecallSignature`. | Remove its direct edge to the 1.0 release gate and amend that gate's corresponding acceptance text. |
| `bd-01M16E05TWX28QKJF2GFY67ZZV` (`RecallSignature` migration) | P3; keep the unresolved denominator/constructor work explicit. | No stable organization-readout claim in 1.0; new public mapping records must not inherit these ambiguities. |
| `bd-01M16DBEH9PKER423BZ47ZKBMV` (`Estimate` eligibility) | Keep P2 and make the repair an explicit pre-freeze prerequisite. | Add the missing typed eligibility state and its laws before freezing the stable estimate family; do not turn ineligible into missing or zero. |
| `bd-01M1DA6NJXYT4NEA18745FM3KY` (general HSMM invocation authentication) | Keep P2 as a separately scoped authority/claim gate. | Remove only its direct edge to the 1.0 release gate; universal authenticated/calibrated HSMM totals are not advertised. Strict reference still requires its bounded executed-producer receipts and rejects unknown provenance. |

The forgeable `Admissibility` repair, platform-labelled goldens and Native numeric
governance remain release requirements. This decision removes no demonstrated
scientific or construction defect merely because a type becomes experimental.
It narrows which API shapes must be frozen for the declared 1.0 product.

### Compatibility and rejected alternatives

The first `1.0.0` release establishes the binary baseline. The publishing ticket
`bd-01M2TAKTB61XNXV4Y6A9J3CB0C` must enable MiMa against that baseline for later
1.0.x/1.x releases and prove that a breaking stable-symbol change is caught.
Experimental/internal exclusions must be explicit, scoped and reviewed; a broad
filter cannot hide a stable API break. Before 1.0.0 there is no claimed binary
compatibility baseline. Cross-platform and source/wire compatibility still need
their own checks; MiMa alone establishes none of them.

Existing `mapping-record/v0.1`, workspace and exchange schema tags retain their
current meanings. A changed required field or interpretation needs a new schema
and explicit migration/refusal behavior. A stable Scala boundary does not silently
upgrade an old artifact, confer export permission, or turn raw/model-conditional
values into calibrated probabilities.

Rejected: freezing every public package at once (would pull deferred compiler and
organization API work back into 1.0); marking all features experimental while
promising stable `SourceView` (leaks unstable signature types); making every
inference engine/custom scorer stable (confuses the public mapping contract with
research extension points); and using stability labels to assert algorithmic
validity, calibration or a green release gate.

## Physical support and scoring position

`NodeSummary.support` and `CellCoordinates.sourceSupport` are `TypedSupport`.
Match `Text(spans)` or `Anchored(evidence)` explicitly. `textSpans` returns an
`Option[SpanSet]`; media evidence does not have character offsets.

The existing `NodeSummary.apply` text constructor retains its `SpanSet` argument
and derives `Some(ScoringPosition.CanonicalText(spans))`. The general
`NodeSummary.typed` constructor requires both physical support and an explicit
`Option[ScoringPosition]`. Use `None` when no scoring coordinate was measured.
`LegacyAnnotationText` names the historical joined-description feature used by
the Sherlock adapter; it is separate from that adapter's checked media support.

`SourceView.scoringLength` replaces `textLength`. `relativeSpan` and
`measuredPosition` read the scoring feature and its denominator. Both preserve
absence. The total `relativePosition` accessor has been removed because its
zero fallback conflated missing position with a measured position of zero.
Transition features omit position-dependent terms when the feature is absent.

Canonical text views retain the previous fingerprint token stream and arithmetic.
Other views use a versioned fingerprint that separately binds complete physical
support, feature kind and coordinates, absence, and the denominator. A fingerprint
does not certify the scientific interpretation of a scoring feature.

## Point and interval support

`EvidenceAnchor.MediaPoint` carries a checked `PlaybackInstant`. It has no
invented duration. `EvidenceSupport.playbackOn` returns checked `PlaybackSupport`,
which retains a canonical interval union and explicit points, including points
inside intervals. `PrimaryProjection.Playback` now carries this complete value.
Consumers must use its `intervals` and `points`; interval-only accessors are
deliberately narrower, and `hullOn` refuses selected point evidence.

`EvidenceSupport` retains the full identity of the bundle used for construction.
A changed bundle requires an explicit checked rebuild with
`EvidenceSupport.of(newBundle, oldSupport.anchors.toVector)`. Equal legacy bundle
IDs do not authorize carrying support across changed coordinate metadata.

`SherlockSourceAtlas.of` adapts admitted `SherlockAnnotations.Atlas` values to one
checked composed edition. Row and scene units retain part-native and composed
primary coordinates. Scene support preserves interval gaps and point-only
observations. This adapter does not establish caption licensing or supply a
film-to-story compiler.

## Results and wire formats

`HsmmResult.validated` is the construction boundary. It derives retained support
for the complete view inventory, including candidates unused by inference.
`HsmmResult` and `PlaybackSupport` have private constructors and no public
`Product` or `Mirror` construction path.

`HsmmResultCodec.encode` now returns `Either[HsmmCodecError, String]`, and `toJson`
returns `Either[HsmmCodecError, Json]`. Handle their errors explicitly. The generic
Circe `Encoder[HsmmResult]` is removed. Both encoding methods and both contextual
decoding methods refuse non-text support or noncanonical scoring features with
`UnsupportedSupport`.

The HSMM wire remains text-only (now `hsmm/v4`; see below). A future
source-support-bearing generic wire needs its own version and acceptance evidence;
this migration does not introduce one. Detached evidence components containing points
use `evidence-support/v2`, including the full bundle binding. Historical interval
components retain their v1 bytes. Neither component encoder is a film model or
HSMM codec; anchored component decoding remains refused.

The story model wire remains schema `0.7.0` for text. Text codec, rendering, and
text slicing consumers require `TextModel`; obtain that witness through checked
text construction or `StoryModel.asText`. General models cannot be paired with
an unrelated text model through `StorySourceView`.

## Cost support (`hsmm/v4`)

`CostBreakdown.supportWeight: Double` is removed. `CostBreakdown.support` is a
`SupportAssessment`: `Assessed` (a `share` in `[0, 1]` over a nonempty eligible
population with positive eligible weight), `Unestablished` (`EmptyEligibility` or
`ZeroEligibleWeight`: no denominator, no number), or `NotApplicable`
(`ExternalState` for external cells, `Unreachable` for `CostBreakdown.unreachable`).
Only `Assessed` has a `share`; the trait has none, so select the case before
comparing support. `Assessed` and `Unestablished` expose their `CellSupportBasis`
(measured terms, eligible terms, exact eligible weights). Shares and reasons are
derived: obtain support from `SupportAssessment.fromEvidence` (checked) or
`SupportAssessment.derive`, or the fixed `externalState` / `unreachable` values.
Numbers did not move: every total is unchanged, and on the WOG goldens every
assessed share is bit-identical to its old `supportWeight` (shares are now summed
in `CostTerm` order). What changed is that an
external cell no longer publishes `1.0`, and neither does a cell with an empty
eligible set or zero eligible weight.

`CostBreakdown` is no longer a case class. It has explicit accessors (`terms`,
`mode`, `exclusion`, `total`, `missingTerms`, `sourceChartCoverage`, `reductions`,
`support`, `imputedTerms`), structural equality and a `toString`, and no `apply`,
`copy`, `unapply`, `Product` or `Mirror`. Build records through
`AlignWire.costBreakdown`, whose eighth argument is now `support: SupportAssessment`
and which refuses support that does not describe its record. The public no-argument
`CostBreakdown.unassessable` is removed (an unassessable record now carries the
basis it rests on); `CostBreakdown.unreachable` is a `val`.

`HsmmResultCodec` writes and reads `hsmm/v4` only. An `hsmm/v3` artifact is refused
with `HsmmCodecError.Wire(CodecError.UnsupportedSchema("hsmm/v3", Vector("hsmm/v4")))`.
Unknown fields in the artifact, its cost entries and its support are now wire
errors rather than silently ignored.
There is no converter: a v3 `supportWeight` of `1.0` cannot say which support
state applied, so a v3 artifact is re-derived from its inputs, not upgraded.

## Additive mapping-record API

G1 adds `RecallInventory` in `recall`, checked mapping/source/measure/decision records in
`align`, and contextual `MappingCodecs` in `codec`. It adds no module or dependency and changes
no existing story/HSMM schema or inference arithmetic. `MappingMiniature` in `laws` is
experimental public test support with authored alternatives, not measured model output.

`MappingResult.checked` requires one ordered outcome for every inventory unit and one coherent
source, target universe, policy set and stage ledger. Result-derived values share one
`DerivationBinding`: recall checksum, presence-sensitive recall supplement, inventory digest,
view fingerprint, scope digest and actual result digest. Binding does not certify execution.
The historical adapter preserves unknown stage provenance and unavailable calibration.

Use `SourceRepresentation.of` for multipart preview support. Equal legacy bundle IDs cannot
hide different full coordinate identities. Targets retain exact checked physical coordinates
and explicit unlocated/partial status; this type grants no permission to reveal source text.
Ordering independent parts requires a checked declared composition.

The authority-bearing components have no unchecked `apply`, `copy`, `Product` or `Mirror` route.
`StageProvenance.Derived`, `CalibratedProbability` and calibrated decisions have no construction
door in this version. Mapping schema `mapping-record/v0.1` refuses their tags rather than
promoting caller declarations. Decode requires expected inventory/source and, for bound values,
the original checked recall, view and HSMM result; it re-derives values and compares exact records.
This wire supports checked physical mapping support without widening the text-only HSMM wire.

## Qualification boundary

Frozen text JSON, per-backend WOG HSMM bytes, complete Sherlock row coordinates,
and the all-17 recall anchor projection are separate preservation courts. Passing
one does not establish the others. Native's previously recorded numerical
differences remain platform-labelled; this API change sets no new tolerance or
cross-platform byte-identity policy. The D1B evidence record will link the exact
qualified revisions and limitations when those courts finish.
