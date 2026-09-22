# Source and alignment API stability

This records the D1A/D1B source migration for library callers. The design decisions
are in [ADR 0007](adr/0007-film-source-representation.md). D1B qualification is
pending; this page describes the candidate API, not a release certification.

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
