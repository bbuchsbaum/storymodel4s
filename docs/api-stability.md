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

The `hsmm/v3` wire remains text-only, with unchanged canonical text JSON. A future
support-bearing generic wire needs its own version and acceptance evidence; this
migration does not introduce one. Detached evidence components containing points
use `evidence-support/v2`, including the full bundle binding. Historical interval
components retain their v1 bytes. Neither component encoder is a film model or
HSMM codec; anchored component decoding remains refused.

The story model wire remains schema `0.7.0` for text. Text codec, rendering, and
text slicing consumers require `TextModel`; obtain that witness through checked
text construction or `StoryModel.asText`. General models cannot be paired with
an unrelated text model through `StorySourceView`.

## Qualification boundary

Frozen text JSON, per-backend WOG HSMM bytes, complete Sherlock row coordinates,
and the all-17 recall anchor projection are separate preservation courts. Passing
one does not establish the others. Native's previously recorded numerical
differences remain platform-labelled; this API change sets no new tolerance or
cross-platform byte-identity policy. The D1B evidence record will link the exact
qualified revisions and limitations when those courts finish.
