package storymodel4s.view

import cats.data.NonEmptyVector
import cats.syntax.all.*
import storymodel4s.acquire.ClaimFamily
import storymodel4s.core.*
import storymodel4s.document.{DerivationGapReason, NarrativeCandidateAddress}
import storymodel4s.story.*

/** The Atlas sibling of [[CodexFlow]] (ADR 0002 D2): placed geometric marks under a declared
  * [[ProjectionContract]]. This file implements the default projection, the **Discourse Atlas**,
  * whose only exact coordinate is source order. Nothing here infers: every narrative mark's address
  * resolves through `StoryModel.supporting`, every surface mark exactly matches the source atlas,
  * containment is by construction (a parent region is the hull of its children), visibility under a
  * reader horizon is the shared [[EvidenceVisibility]] closure, and the vertical axis is declared
  * as a *lane*, not a measurement.
  */
enum ProjectionKind:
  case DiscourseAtlas

/** Visual channels a contract may give meaning to. */
enum VisualChannel:
  case X, Y, SurfaceUnit, RegionExtent, LandmarkPosition, Thread, Portal, Route, Distance, Area

  /** The exact discontinuous scope of one context frame over the discourse axis. */
  case ContextBand

  /** A recorded absence: a derivation gap, an abstained sentence, an unsatisfied promotion law. */
  case Absence

  /** The non-colour channel that separates one epistemic state from another (ADR 0002 D9). */
  case Epistemic

/** Meanings permitted for a declared projection axis. */
enum AxisMeaning:
  /** Exact UTF-16 offset into the canonical text: the observational axis. */
  case DiscourseOffset

  /** A discrete lane chosen for legibility; vertical distance carries no meaning. */
  case ContextLane

/** Meaning of Euclidean distance in a projection. */
enum DistanceMeaning:
  case NoMeaning

/** Quantities that a projection may deliberately encode as geometric measure. */
enum MeasureMeaning:
  /** Reserved for projections whose 2-D area encodes a measure; the Discourse Atlas has none. */
  case SourceLength

/** One declared meaning of one channel. */
final case class ChannelMeaning(channel: VisualChannel, meaning: String)

/** Invariants a compiled scene is guaranteed (and tested) to satisfy (ADR 0002 §5). */
enum VisualInvariant:
  case DiscourseOrder // V-P1
  case ContainmentByHull // V-P2
  case LaneHasNoMetric // V-P4
  case EvidenceBacked // V-E1/V-E3
  case SelectionPreserved // V-L2
  case HorizonShared // D4 row 7: EvidenceVisibility is the only horizon
  case Deterministic // V-D1

  /** Every absence the bound draft records has exactly one mark; a partial model draws as partial.
    */
  case AbsenceIsMarked // D9, plan §2.3

  /** Every epistemic mark declares a non-colour channel, and no two states share one. */
  case EpistemicChannelIsNonColour // V-U5

/** What the geometry of a scene means. Declared data, never documentation (ADR 0002 §2). */
final case class ProjectionContract private (
    kind: ProjectionKind,
    x: AxisMeaning,
    y: AxisMeaning,
    distance: DistanceMeaning,
    area: Option[MeasureMeaning],
    legend: Vector[ChannelMeaning],
    invariants: Set[VisualInvariant]
)

object ProjectionContract:
  private[view] def of(
      kind: ProjectionKind,
      x: AxisMeaning,
      y: AxisMeaning,
      distance: DistanceMeaning,
      area: Option[MeasureMeaning],
      legend: Vector[ChannelMeaning],
      invariants: Set[VisualInvariant]
  ): ProjectionContract =
    new ProjectionContract(kind, x, y, distance, area, legend, invariants)

  val discourseAtlas: ProjectionContract = of(
    ProjectionKind.DiscourseAtlas,
    AxisMeaning.DiscourseOffset,
    AxisMeaning.ContextLane,
    DistanceMeaning.NoMeaning,
    area = None,
    Vector(
      ChannelMeaning(
        VisualChannel.X,
        "exact discourse offset (UTF-16 code units of the canonical text)"
      ),
      ChannelMeaning(
        VisualChannel.Y,
        "context lane (narrated world = 0); vertical distance has no meaning"
      ),
      ChannelMeaning(
        VisualChannel.SurfaceUnit,
        "exact half-open UTF-16 x span; no projection-y coordinate; any renderer-assigned " +
          "vertical placement or categorical subrow is layout-only outside ContextLane and " +
          "carries no vertical-position or distance meaning"
      ),
      ChannelMeaning(
        VisualChannel.RegionExtent,
        "half-open x-range = hull of the exact support of the segment's visible children; lanes = those children's lanes"
      ),
      ChannelMeaning(
        VisualChannel.LandmarkPosition,
        "x = start of the first supporting span; y = the situation's context lane"
      ),
      ChannelMeaning(
        VisualChannel.Thread,
        "connects an entity's participations in discourse order; asserts nothing between them"
      ),
      ChannelMeaning(
        VisualChannel.Portal,
        "a stored reference edge (RelationLayer.Reference active) whose endpoints are not discourse-adjacent"
      ),
      ChannelMeaning(
        VisualChannel.Route,
        "a stored relation edge of an active relation layer; carries the edge's epistemic status"
      ),
      ChannelMeaning(
        VisualChannel.ContextBand,
        "one x-range per span of the context frame's own exact support, on the frame's lane; " +
          "never a hull over the gaps between them, and never an inferred continuation"
      ),
      ChannelMeaning(
        VisualChannel.Absence,
        "a recorded absence — a derivation gap, an abstained sentence, or an unsatisfied " +
          "promotion law — placed on the exact spans it concerns and on no lane, since an " +
          "unresolved candidate has no established context"
      ),
      ChannelMeaning(
        VisualChannel.Epistemic,
        "the non-colour channel of an absence: hatch, fan, placeholder, or bracket; " +
          "assignment is total and injective over the epistemic states"
      ),
      ChannelMeaning(VisualChannel.Distance, "no semantic interpretation"),
      ChannelMeaning(VisualChannel.Area, "no semantic interpretation")
    ),
    Set(
      VisualInvariant.DiscourseOrder,
      VisualInvariant.ContainmentByHull,
      VisualInvariant.LaneHasNoMetric,
      VisualInvariant.EvidenceBacked,
      VisualInvariant.SelectionPreserved,
      VisualInvariant.HorizonShared,
      VisualInvariant.Deterministic,
      VisualInvariant.AbsenceIsMarked,
      VisualInvariant.EpistemicChannelIsNonColour
    )
  )

/** Narrative levels of semantic zoom (one axis of [[ZoomLevel]]). */
enum NarrativeLevel:
  case Story, Episode, Scene, Event

  /** Segment kinds whose regions are visible at this level. */
  def visibleSegments: Set[SegmentKind] = this match
    case Story   => Set(SegmentKind.Episode)
    case Episode => Set(SegmentKind.Episode, SegmentKind.Scene)
    case Scene   => Set(SegmentKind.Scene)
    case Event   => Set(SegmentKind.Scene)

  /** Whether this level exposes atomic event/state landmarks. */
  def showsSituations: Boolean = this match
    case Scene | Event => true
    case _             => false

/** Surface detail is the other zoom axis (ADR 0002 D14a); the Atlas compiles it into checked marks.
  */
enum SurfaceDetail:
  case Hidden, Sentences, Tokens

/** Unforgeable preflight report of the surface-detail levels an exact [[SurfaceAtlas]] supports.
  *
  * Why: its three observations form one derived capability proof, so a plain class prevents
  * `apply`, `copy`, or `fromProduct` from manufacturing a report that disagrees with the atlas.
  */
final class SurfaceDetailSupport private (
    val availableKinds: Set[SurfaceUnitKind],
    val supportedDetails: Set[SurfaceDetail],
    val unsupportedTokenIds: Vector[SurfaceUnitId]
):
  /** Whether compilation can answer this surface-detail request without silent degradation. */
  def supports(detail: SurfaceDetail): Boolean = supportedDetails.contains(detail)

  /** Refuse a requested surface-detail level the atlas cannot support structurally. */
  def require(detail: SurfaceDetail): Either[AtlasCompileError, Unit] =
    Either.cond(supports(detail), (), AtlasCompileError.UnsupportedSurfaceDetail(detail, this))

  override def equals(other: Any): Boolean = other match
    case that: SurfaceDetailSupport =>
      availableKinds == that.availableKinds && supportedDetails == that.supportedDetails &&
      unsupportedTokenIds == that.unsupportedTokenIds
    case _ => false

  override def hashCode(): Int =
    (availableKinds, supportedDetails, unsupportedTokenIds).hashCode

  override def toString: String =
    val kinds = availableKinds.toVector.map(_.toString).sorted.mkString(",")
    val details = supportedDetails.toVector.map(_.toString).sorted.mkString(",")
    val unsupported = unsupportedTokenIds.map(_.value).mkString(",")
    s"SurfaceDetailSupport(availableKinds=[$kinds], supportedDetails=[$details], unsupportedTokenIds=[$unsupported])"

object SurfaceDetailSupport:
  /** Inspect existing unit kinds and token ancestry without deriving any missing surface units. */
  def inspect(atlas: SurfaceAtlas): SurfaceDetailSupport =
    def reachesSentence(unit: storymodel4s.core.SurfaceUnit): Boolean =
      @scala.annotation.tailrec
      def climb(next: Option[SurfaceUnitId], seen: Set[SurfaceUnitId]): Boolean = next match
        case Some(id) if !seen.contains(id) =>
          atlas.byId.get(id) match
            case Some(parent) if parent.kind == SurfaceUnitKind.Sentence => true
            case Some(parent) => climb(parent.parent, seen + id)
            case None         => false
        case _ => false

      climb(unit.parent, Set(unit.id))

    val kinds = atlas.byKind.keySet
    val unsupportedTokens = atlas.tokens.filterNot(reachesSentence).map(_.id).sorted
    val sentenceLayerSupported =
      kinds.contains(SurfaceUnitKind.Sentence) && unsupportedTokens.isEmpty
    val details = Set(SurfaceDetail.Hidden) ++
      Option.when(sentenceLayerSupported)(SurfaceDetail.Sentences) ++
      Option.when(
        sentenceLayerSupported && kinds.contains(SurfaceUnitKind.Token)
      )(SurfaceDetail.Tokens)
    new SurfaceDetailSupport(kinds, details, unsupportedTokens)

/** Typed failures unique to Atlas compilation, preserving ordinary domain failures unchanged. */
enum AtlasCompileError:
  /** A pre-existing domain validation failed before a scene could be compiled. */
  case Domain(error: DomainError)

  /** The requested surface level is absent or lacks complete token-to-sentence ancestry. */
  case UnsupportedSurfaceDetail(requested: SurfaceDetail, support: SurfaceDetailSupport)

  def message: String = this match
    case Domain(error)                                => error.message
    case UnsupportedSurfaceDetail(requested, support) =>
      val kinds = support.availableKinds.toVector.map(_.toString).sorted.mkString(",")
      val details = support.supportedDetails.toVector.map(_.toString).sorted.mkString(",")
      val unsupported = support.unsupportedTokenIds.map(_.value).mkString(",")
      s"unsupported surface detail $requested: available kinds=[$kinds], supported details=[$details], tokens without sentence ancestors=[$unsupported]"

/** Independent narrative and surface-detail coordinates of semantic zoom. */
final case class ZoomLevel(narrative: NarrativeLevel, surface: SurfaceDetail)

/** Stable identity of one placed mark. One [[Address]] may yield several marks (ADR 0002 §6). */
object MarkId extends OpaqueId("MarkId")
type MarkId = MarkId.T

/** Stable semantic and mark identities kept separate across levels and renderers. */
final case class VisualIdentity private (address: Address, level: NarrativeLevel, mark: MarkId)

object VisualIdentity:
  /** Construct identity only inside the validated scene compiler. */
  private[view] def of(address: Address, level: NarrativeLevel, mark: MarkId): VisualIdentity =
    new VisualIdentity(address, level, mark)

/** Axis-aligned extent: half-open in x (`[x0, x1Exclusive)`, like [[TextSpan]]) and inclusive in
  * lanes. Checked construction: no negative bounds, `x0 <= x1Exclusive`, `lane0 <= lane1`.
  */
final case class Extent private (x0: Int, x1Exclusive: Int, lane0: Int, lane1: Int):
  def containsX(x: Int): Boolean = x >= x0 && x < x1Exclusive
  def contains(other: Extent): Boolean =
    other.x0 >= x0 && other.x1Exclusive <= x1Exclusive && other.lane0 >= lane0 &&
      other.lane1 <= lane1
  def hull(other: Extent): Extent =
    new Extent(
      math.min(x0, other.x0),
      math.max(x1Exclusive, other.x1Exclusive),
      math.min(lane0, other.lane0),
      math.max(lane1, other.lane1)
    )

object Extent:
  /** Construct an extent only when its half-open x bounds and inclusive lanes are ordered. */
  def of(x0: Int, x1Exclusive: Int, lane0: Int, lane1: Int): Either[DomainError, Extent] =
    if x0 < 0 || lane0 < 0 then
      Left(
        DomainError.InvalidFormat(
          "Extent",
          s"[$x0,$x1Exclusive) lanes $lane0..$lane1",
          "negative bound"
        )
      )
    else if x1Exclusive < x0 then
      Left(DomainError.InvalidFormat("Extent", s"[$x0,$x1Exclusive)", "x end precedes start"))
    else if lane1 < lane0 then
      Left(DomainError.InvalidFormat("Extent", s"lanes $lane0..$lane1", "lane end precedes start"))
    else Right(new Extent(x0, x1Exclusive, lane0, lane1))

/** One point of a route or thread: exact offset and layout-only lane. */
final case class Anchor private (x: Int, lane: Int)

object Anchor:
  /** Construct an anchor only at a nonnegative source offset and layout lane. */
  def of(x: Int, lane: Int): Either[DomainError, Anchor] =
    if x < 0 || lane < 0 then
      Left(
        DomainError.InvalidFormat(
          "Anchor",
          s"($x,$lane)",
          "source offset and lane must be nonnegative"
        )
      )
    else Right(new Anchor(x, lane))

/** Whether a landmark represents an event or a state without a Boolean sentinel. */
enum LandmarkKind:
  case Event, State

/** Closed content-address salt for each renderer-neutral mark family. */
private[view] enum AtlasMarkKind:
  case SurfaceUnit, Region, Landmark, Thread, Portal, Route, ContextBand, Gap, Abstention,
    UnsatisfiedLaw

  def salt: String = this match
    case SurfaceUnit    => "surface-unit"
    case Region         => "region"
    case Landmark       => "landmark"
    case Thread         => "thread"
    case Portal         => "portal"
    case Route          => "route"
    case ContextBand    => "context-band"
    case Gap            => "gap"
    case Abstention     => "abstention"
    case UnsatisfiedLaw => "unsatisfied-law"

/** What evidence a context band is drawn from (ADR 0002 D4, row 6).
  *
  * D4 requires bands to distinguish exact scope evidence, contextual membership and inferred
  * continuation. Only the first is compiled today: the frame's own `support` spans, one x-range
  * apiece. The other two need claims the model does not yet make, and a band that quietly stood in
  * for them would draw a continuous speech frame across text nobody attributed to a speaker.
  */
enum ContextBandBasis:
  /** Every x-range is one span of the frame's own recorded support. */
  case ExactScopeEvidence

/** Closed renderer-neutral marks emitted by the first Discourse Atlas compiler. */
enum VisualPrimitive:
  case SurfaceUnit(
      identity: VisualIdentity,
      span: TextSpan,
      kind: SurfaceUnitKind,
      unitOrdinal: Int,
      parent: Option[Address]
  )
  case Region(identity: VisualIdentity, extent: Extent, label: String, parent: Option[Address])

  /** `context` is the situation's own frame, so a renderer can band without reopening the model.
    * Its lane is `at.lane`, which is layout; the frame identity is the claim.
    */
  case Landmark(
      identity: VisualIdentity,
      at: Anchor,
      label: String,
      kind: LandmarkKind,
      context: ContextId
  )
  case Thread(identity: VisualIdentity, label: String, points: Vector[Anchor])
  case Portal(identity: VisualIdentity, from: Anchor, to: Anchor, mode: NarrativeReference)
  case Route(
      identity: VisualIdentity,
      from: Anchor,
      to: Anchor,
      layer: RelationLayer,
      status: EpistemicStatus
  )

  /** One context frame drawn over the discourse axis: one extent per support span, never a hull. */
  case ContextBand(
      identity: VisualIdentity,
      kind: ContextKind,
      lane: Int,
      extents: NonEmptyVector[Extent],
      parent: Option[Address],
      basis: ContextBandBasis
  )

  /** A derivation the compiler attempted and could not make, kept as a mark rather than a hole. */
  case Gap(
      identity: VisualIdentity,
      family: ClaimFamily,
      target: NarrativeCandidateAddress,
      reason: DerivationGapReason,
      state: UncertaintyState,
      placement: EpistemicPlacement
  )

  /** A sentence that produced no situation root at all, with the provider's own stated reason. */
  case Abstention(
      identity: VisualIdentity,
      unit: SurfaceUnitId,
      reason: SentenceAbstention,
      placement: EpistemicPlacement
  )

  /** One promotion law this model does not satisfy, carrying the validator's own violation. */
  case UnsatisfiedLaw(
      identity: VisualIdentity,
      violation: Violation,
      placement: EpistemicPlacement
  )

  def identity: VisualIdentity
  def address: Address = identity.address

  /** The exact material an epistemic mark concerns; `None` for every ordinary mark. */
  def epistemicPlacement: Option[EpistemicPlacement] = this match
    case Gap(_, _, _, _, _, where)   => Some(where)
    case Abstention(_, _, _, where)  => Some(where)
    case UnsatisfiedLaw(_, _, where) => Some(where)
    case _                           => None

  /** The D9 uncertainty state a mark carries; `None` when the mark states no uncertainty.
    *
    * An unsatisfied promotion law is deliberately not a D9 state: D9 classifies uncertainty about a
    * value, and a violated law is a structural defect of the build. It still carries a non-colour
    * channel, through [[epistemicChannel]].
    */
  def uncertainty: Option[UncertaintyState] = this match
    case Gap(_, _, _, _, state, _)  => Some(state)
    case Abstention(_, _, _, _)     => Some(UncertaintyState.Missing)
    case _                          => None

  /** The non-colour channel this mark is drawn in; `None` for every ordinary mark (V-U5). */
  def epistemicChannel: Option[EpistemicChannel] = this match
    case UnsatisfiedLaw(_, _, _) => Some(EpistemicChannel.Bracket)
    case other                   => other.uncertainty.map(_.channel)

/** Bounded policy for selecting entity-continuity threads. */
enum ThreadPolicy:
  /** Only entities in the shared selection get a thread. */
  case Selected

  /** Every entity with at least two visible participations, up to `max` by participation count. */
  case All(max: PositiveInt)

/** What the Atlas shows besides regions and landmarks. */
final case class AtlasSpec private (
    zoom: ZoomLevel,
    threads: ThreadPolicy,
    featureScale: FeatureScale
)

object AtlasSpec:
  /** Construct a Discourse Atlas specification from already checked closed policies. */
  def apply(
      zoom: ZoomLevel,
      threads: ThreadPolicy,
      featureScale: FeatureScale = FeatureScale.Default
  ): AtlasSpec = new AtlasSpec(zoom, threads, featureScale)

/** Value-free feature plan awaiting checked numeric sidecar materialization downstream. */
final case class AtlasFeatureLayer private[view] (
    scale: FeatureScale,
    state: FeatureChannelState,
    observations: Vector[FeatureObservationPlacement]
)

object AtlasFeatureLayer:
  private[view] def compiled(
      scale: FeatureScale,
      planned: PlannedFeatureLayer
  ): AtlasFeatureLayer =
    new AtlasFeatureLayer(scale, planned.state, planned.observations)

/** Where a selected or focused semantic address is represented in one compiled view.
  *
  * `Mark` is the renderer-neutral identity used by that view: [[MarkId]] in an Atlas and
  * [[AnnotationId]] in a Codex.
  */
enum SelectionPlacement[+Mark]:
  /** The address has one or more ordinary marks in this view. */
  case OnMark(marks: NonEmptyVector[Mark])

  /** The address is preserved through its nearest visible primary ancestor. */
  case ViaAncestor(ancestor: Address)

  /** The address remains selected but this projection has no honest visual anchor for it. */
  case OffProjection

/** Placed marks under a contract, preserving shared state even when an address is off-projection.
  */
final case class NarrativeScene private[view] (
    contract: ProjectionContract,
    zoom: ZoomLevel,
    state: CommonViewState,
    featureLayer: AtlasFeatureLayer,
    marks: Vector[VisualPrimitive],
    navigation: SceneNavigation,
    selectionPlacements: Map[Address, SelectionPlacement[MarkId]],
    provenance: ViewProvenance
):
  def textualTwin: String = AtlasTextualTwin.render(this)

/** Bidirectional lookup between semantic addresses and the marks representing them. */
final case class SceneNavigation private (
    byAddress: Map[Address, Vector[MarkId]],
    addressOf: Map[MarkId, Address]
):
  def marksFor(address: Address): Vector[MarkId] = byAddress.getOrElse(address, Vector.empty)

object SceneNavigation:
  /** Build a bidirectional navigation index, rejecting duplicate renderer identities. */
  def from(marks: Vector[VisualPrimitive]): Either[DomainError, SceneNavigation] =
    val ids = marks.map(_.identity.mark)
    ids.groupBy(identity).collectFirst { case (id, xs) if xs.size > 1 => id } match
      case Some(dup) => Left(DomainError.DuplicateId("MarkId", dup.value))
      case None      =>
        Right(
          new SceneNavigation(
            marks.groupMap(_.address)(_.identity.mark).view.mapValues(_.sorted).toMap,
            marks.map(m => m.identity.mark -> m.address).toMap
          )
        )

/** Compiles the Discourse Atlas. Pure and deterministic in `(model, state, spec)`. */
final class AtlasCompiler private (provenance: ViewProvenance):

  /** Compile a validated story into an evidence-backed scene under the exact supplied receipt. */
  def compile(
      model: StoryModel[ModelStatus.Validated],
      state: CommonViewState,
      spec: AtlasSpec
  ): Either[AtlasCompileError, NarrativeScene] =
    if provenance.basis == ViewBasis.DraftBuild then
      Left(
        AtlasCompileError.Domain(
          DomainError.InvariantViolation(
            "view/atlas/provenance/draft-basis",
            "a validated model may not be compiled under a draft-build receipt; " +
              "use compileDraft, whose receipt names the promotion state it renders"
          )
        )
      )
    else run(model, None, state, spec)

  /** Compile a draft together with the exact evidence of its own incompleteness.
    *
    * Why this exists rather than a rule change that would let the machine-built model validate:
    * making a model promote in order to satisfy a renderer moves the falsehood from the picture
    * into the artifact. A researcher needs to see a partial model *and* see exactly where it is
    * partial, which is what the [[VisualPrimitive.Gap]], [[VisualPrimitive.Abstention]] and
    * [[VisualPrimitive.UnsatisfiedLaw]] marks are for. The scene cannot be mistaken for a validated
    * one: its receipt carries [[ViewBasis.DraftBuild]] and a [[DraftPromotion]] derived from this
    * exact bundle, and [[compile]] refuses that receipt.
    */
  def compileDraft(
      draft: DraftModel,
      state: CommonViewState,
      spec: AtlasSpec
  ): Either[AtlasCompileError, NarrativeScene] =
    if provenance.basis != ViewBasis.DraftBuild then
      Left(
        AtlasCompileError.Domain(
          DomainError.InvariantViolation(
            "view/atlas/provenance/draft-basis",
            s"a draft scene requires a ${ViewBasis.DraftBuild.label} receipt, " +
              s"and this one declares ${provenance.basis.label}"
          )
        )
      )
    else if !provenance.draft.contains(draft.promotion) then
      Left(
        AtlasCompileError.Domain(
          DomainError.InvariantViolation(
            "view/atlas/provenance/draft-promotion",
            s"receipt promotion ${provenance.draft.fold("none")(_.label)} " +
              s"does not describe this draft (${draft.promotion.label})"
          )
        )
      )
    else run(draft.model, Some(draft), state, spec)

  private def run(
      model: StoryModel[?],
      draft: Option[DraftModel],
      state: CommonViewState,
      spec: AtlasSpec
  ): Either[AtlasCompileError, NarrativeScene] =
    def domain[A](result: Either[DomainError, A]): Either[AtlasCompileError, A] =
      result.leftMap(AtlasCompileError.Domain.apply)
    val surfaceSupport = SurfaceDetailSupport.inspect(model.atlas)
    for
      _ <- domain(EvidenceVisibility.validateHorizon(model.source.canonicalText, state.horizon))
      _ <- domain(validateProvenance(model, state, spec))
      _ <- surfaceSupport.require(spec.zoom.surface)
      feature <- domain(
        FeaturePlanner.plan(
          model,
          state.feature,
          spec.featureScale,
          state.horizon,
          provenance
        )
      )
      ledger <- domain(model.ledger)
      scene <- domain(
        build(
          model,
          draft,
          ledger,
          state,
          spec,
          AtlasFeatureLayer.compiled(spec.featureScale, feature)
        )
      )
    yield scene

  private def build(
      model: StoryModel[?],
      draft: Option[DraftModel],
      ledger: ClaimLedger,
      state: CommonViewState,
      spec: AtlasSpec,
      featureLayer: AtlasFeatureLayer
  ): Either[DomainError, NarrativeScene] =
    val g = model.graph
    val h = model.hierarchy
    val level = spec.zoom.narrative
    val storyRef = Addressable[StoryRef]
    val coreRef = Addressable[CoreRef]

    // D4 row 7: the shared evidence closure decides visibility; support is then clipped.
    val visibleClaims: Option[Set[ClaimId]] =
      EvidenceVisibility.visibleUnder(state.horizon, ledger)
    def claimVisible(meta: ClaimMeta): Boolean = visibleClaims.forall(_.contains(meta.id))
    def clipped(support: SpanSet): Option[SpanSet] =
      EvidenceVisibility.clipSupport(support, state.horizon)
    def surfaceUnitVisible(unit: storymodel4s.core.SurfaceUnit): Boolean = state.horizon match
      case EpistemicHorizon.Omniscient       => true
      case EpistemicHorizon.ReaderAt(offset) => unit.span.endExclusive <= offset

    // Every structural edge that contributes to geometry must itself be visible. Under the
    // omniscient horizon `claimVisible` admits the complete validated hierarchy unchanged.
    val primaryContainment = h.primary.filter(edge => claimVisible(edge.meta))
    val childrenOf: Map[SegmentId, Vector[NarrativeMember]] =
      primaryContainment.groupMap(_.parent)(_.member)
    val primaryParent: Map[NarrativeMember, SegmentId] =
      primaryContainment.map(edge => edge.member -> edge.parent).toMap

    val situationSupport: Map[SituationId, SpanSet] =
      g.discourseOrder.flatMap { id =>
        g.situations.get(id).flatMap { node =>
          if claimVisible(node.meta) then clipped(node.support).map(id -> _) else None
        }
      }.toMap
    val visibleSituations: Vector[SituationId] = g.discourseOrder.filter(situationSupport.contains)
    val visibleSet = visibleSituations.toSet

    // Lanes: only horizon-visible contexts may affect geometry. The narrated-world root is lane 0
    // when visible; every other visible context gets a lane by sorted id.
    val lanes: Map[ContextId, Int] =
      val visibleContexts =
        g.contexts.valuesIterator.filter(c => claimVisible(c.meta)).map(_.id).toSet
      val root = g.rootContext.filter(visibleContexts.contains).toVector
      val rest = visibleContexts.toVector.filterNot(root.contains).sorted
      (root ++ rest).zipWithIndex.toMap

    val checkedGeometry: Either[
      DomainError,
      Vector[(SituationId, Anchor, Extent)]
    ] = visibleSituations.traverse { id =>
      for
        support <- situationSupport
          .get(id)
          .toRight(
            DomainError.InvariantViolation(
              "view/atlas/geometry/support",
              s"visible situation ${id.value} has no clipped support"
            )
          )
        node <- g.situations
          .get(id)
          .toRight(
            DomainError.InvariantViolation(
              "view/atlas/geometry/situation",
              s"discourse situation ${id.value} is absent from the graph"
            )
          )
        lane = lanes.getOrElse(node.context, 0)
        span = support.minSpan
        anchor <- Anchor.of(span.start, lane)
        extent <- Extent.of(span.start, span.endExclusive, lane, lane)
      yield (id, anchor, extent)
    }

    val geometry = checkedGeometry match
      case Right(value) => value
      case Left(error)  => return Left(error)
    val anchorBySituation = geometry.map((id, anchor, _) => id -> anchor).toMap
    val situationExtent = geometry.map((id, _, extent) => id -> extent).toMap

    // Segment extents are by construction the hull of their visible primary children.
    val segmentExtent: Map[SegmentId, Extent] =
      val memo = scala.collection.mutable.Map.empty[SegmentId, Option[Extent]]
      def extent(seg: SegmentId, seen: Set[SegmentId]): Option[Extent] =
        memo.getOrElseUpdate(
          seg,
          if seen.contains(seg) then None
          else
            childrenOf
              .getOrElse(seg, Vector.empty)
              .flatMap {
                case NarrativeMember.Situation(s) => situationExtent.get(s)
                case NarrativeMember.Segment(c)   => extent(c, seen + seg)
              }
              .reduceOption((left, right) => left.hull(right))
        )
      g.segments.keys.toVector.sorted.flatMap(s => extent(s, Set.empty).map(s -> _)).toMap

    def markId(address: Address, kind: AtlasMarkKind): MarkId =
      MarkId.unsafe(ContentAddress.of("mark", address.render, kind.salt, level.toString))
    // Several absences can share one anchor (a context assignment and a participant coverage at
    // the same chart node), so an epistemic mark's identity carries a content key of its own.
    def epistemicIdentity(address: Address, kind: AtlasMarkKind, key: String): VisualIdentity =
      VisualIdentity.of(
        address,
        level,
        MarkId.unsafe(ContentAddress.of("mark", address.render, kind.salt, level.toString, key))
      )
    def identity(ref: StoryRef, kind: AtlasMarkKind): VisualIdentity =
      val a = storyRef.address(ref)
      VisualIdentity.of(a, level, markId(a, kind))
    def surfaceIdentity(id: SurfaceUnitId): VisualIdentity =
      val a = coreRef.address(CoreRef.SurfaceUnit(id))
      VisualIdentity.of(a, level, markId(a, AtlasMarkKind.SurfaceUnit))

    val regions: Vector[VisualPrimitive] =
      g.segments.values.toVector
        .filter(s => level.visibleSegments.contains(s.kind) && claimVisible(s.summary.meta))
        .sortBy(_.id)
        .flatMap { s =>
          segmentExtent.get(s.id).map { ext =>
            val parent = primaryParent
              .get(NarrativeMember.Segment(s.id))
              .filter(p => g.segments.get(p).exists(ps => level.visibleSegments.contains(ps.kind)))
              .filter(segmentExtent.contains)
              .map(p => storyRef.address(StoryRef.Segment(p)))
            VisualPrimitive.Region(
              identity(StoryRef.Segment(s.id), AtlasMarkKind.Region),
              ext,
              s.summary.value,
              parent
            )
          }
        }

    val landmarks: Vector[VisualPrimitive] =
      if !level.showsSituations then Vector.empty
      else
        geometry.flatMap { (id, anchor, _) =>
          g.situations.get(id).map { node =>
            val kind = if node.isState then LandmarkKind.State else LandmarkKind.Event
            VisualPrimitive.Landmark(
              identity(StoryRef.Situation(id), AtlasMarkKind.Landmark),
              anchor,
              node.description,
              kind,
              node.context
            )
          }
        }

    // D4 row 6: one extent per span of the frame's own support. A hull would draw a speech frame
    // continuously across the narration between its parts, which is the claim the model refuses.
    val contextBands: Either[DomainError, Vector[VisualPrimitive]] =
      g.contexts.keys.toVector.sorted
        .flatMap(id => g.contexts.get(id))
        .filter(frame => claimVisible(frame.meta))
        .traverse { frame =>
          val lane = lanes.getOrElse(frame.id, 0)
          clipped(frame.support) match
            case None          => Right(Vector.empty[VisualPrimitive])
            case Some(support) =>
              support.refs.toVector
                .traverse(ref => Extent.of(ref.span.start, ref.span.endExclusive, lane, lane))
                .map(extents =>
                  NonEmptyVector
                    .fromVector(extents)
                    .map(nev =>
                      VisualPrimitive.ContextBand(
                        identity(StoryRef.Context(frame.id), AtlasMarkKind.ContextBand),
                        frame.kind,
                        lane,
                        nev,
                        frame.parent
                          .filter(parent => lanes.contains(parent))
                          .map(parent => storyRef.address(StoryRef.Context(parent))),
                        ContextBandBasis.ExactScopeEvidence
                      )
                    )
                    .toVector
                )
        }
        .map(_.flatten)

    val visibleMemberOf: Map[EntityId, Vector[EntityId]] =
      g.relations.entityRelations
        .filter(edge => edge.relation == EntityRelation.MemberOf && claimVisible(edge.meta))
        .groupMap(_.from)(_.to)
    def visibleGroupsOf(entity: EntityId): Set[EntityId] =
      var seen = Set.empty[EntityId]
      var frontier = visibleMemberOf.getOrElse(entity, Vector.empty).toSet
      while frontier.nonEmpty do
        seen ++= frontier
        frontier = frontier.flatMap(group => visibleMemberOf.getOrElse(group, Vector.empty)) -- seen
      seen
    def visibleParticipations(entity: EntityId): Vector[SituationId] =
      val participantEntities = visibleGroupsOf(entity) + entity
      g.relations.participants
        .filter(edge =>
          participantEntities.contains(edge.entity) && visibleSet.contains(edge.situation) &&
            claimVisible(edge.meta)
        )
        .map(_.situation)
        .distinct
        .sortBy(id => g.discoursePosition.getOrElse(id, Int.MaxValue))
    val participationsByEntity: Map[EntityId, Vector[SituationId]] =
      g.entities.keysIterator
        .map(entity => entity -> visibleParticipations(entity))
        .filter(_._2.nonEmpty)
        .toMap

    val threads: Vector[VisualPrimitive] =
      val candidates: Vector[EntityId] = spec.threads match
        case ThreadPolicy.Selected =>
          state.selection.toVector
            .flatMap(a => storyRef.parse(a))
            .collect { case StoryRef.Entity(e) => e }
            .sorted
        case ThreadPolicy.All(max) =>
          participationsByEntity.toVector
            .map((e, sits) => e -> sits.size)
            .filter(_._2 >= 2)
            .sortBy((e, n) => (-n, e))
            .take(max.value)
            .map(_._1)
      candidates.flatMap { e =>
        val points =
          participationsByEntity.getOrElse(e, Vector.empty).flatMap(anchorBySituation.get)
        g.entities.get(e).flatMap { entity =>
          Option.when(points.size >= 2 && claimVisible(entity.meta))(
            VisualPrimitive.Thread(
              identity(StoryRef.Entity(e), AtlasMarkKind.Thread),
              entity.label.value,
              points
            )
          )
        }
      }

    val edgesVisible = level.showsSituations
    def endpointsVisible(a: SituationId, b: SituationId, meta: ClaimMeta): Boolean =
      visibleSet.contains(a) && visibleSet.contains(b) && claimVisible(meta)
    val portals: Vector[VisualPrimitive] =
      if !edgesVisible || !state.relationLayers.contains(RelationLayer.Reference) then Vector.empty
      else
        val visiblePosition = visibleSituations.zipWithIndex.toMap
        g.relations.references
          .filter(r => endpointsVisible(r.from, r.to, r.meta))
          .flatMap(r =>
            for
              fromPosition <- visiblePosition.get(r.from)
              toPosition <- visiblePosition.get(r.to)
              if math.abs(fromPosition - toPosition) > 1
              from <- anchorBySituation.get(r.from)
              to <- anchorBySituation.get(r.to)
            yield VisualPrimitive.Portal(
              identity(StoryRef.Reference(r.from, r.mode, r.to), AtlasMarkKind.Portal),
              from,
              to,
              r.mode
            )
          )
    val routes: Vector[VisualPrimitive] =
      if !edgesVisible then Vector.empty
      else
        val causal =
          if state.relationLayers.contains(RelationLayer.Causal) then
            g.relations.causal
              .filter(c => endpointsVisible(c.cause, c.effect, c.meta))
              .flatMap(c =>
                (anchorBySituation.get(c.cause), anchorBySituation.get(c.effect)).mapN {
                  (from, to) =>
                    VisualPrimitive.Route(
                      identity(
                        StoryRef.Causal(c.cause, c.relation, c.effect),
                        AtlasMarkKind.Route
                      ),
                      from,
                      to,
                      RelationLayer.Causal,
                      c.meta.status
                    )
                }
              )
          else Vector.empty
        val goals =
          if state.relationLayers.contains(RelationLayer.Goal) then
            g.relations.goals
              .filter(e => endpointsVisible(e.from, e.to, e.meta))
              .flatMap(e =>
                (anchorBySituation.get(e.from), anchorBySituation.get(e.to)).mapN { (from, to) =>
                  VisualPrimitive.Route(
                    identity(StoryRef.Goal(e.from, e.relation, e.to), AtlasMarkKind.Route),
                    from,
                    to,
                    RelationLayer.Goal,
                    e.meta.status
                  )
                }
              )
          else Vector.empty
        causal ++ goals

    val surfaceUnits: Vector[storymodel4s.core.SurfaceUnit] = spec.zoom.surface match
      case SurfaceDetail.Hidden    => Vector.empty
      case SurfaceDetail.Sentences => model.atlas.sentences
      case SurfaceDetail.Tokens    => model.atlas.sentences ++ model.atlas.tokens
    val surfaceMarks: Vector[VisualPrimitive] =
      surfaceUnits.filter(surfaceUnitVisible).map { unit =>
        VisualPrimitive.SurfaceUnit(
          surfaceIdentity(unit.id),
          unit.span,
          unit.kind,
          unit.ordinal,
          unit.parent.map(id => coreRef.address(CoreRef.SurfaceUnit(id)))
        )
      }

    val bands = contextBands match
      case Right(value) => value
      case Left(error)  => return Left(error)

    // Absence marks. Every one is placed on exact surface material or says in its own type why it
    // has no discourse position; none of them claims a lane, because a candidate whose context was
    // never resolved has no context to be drawn in.
    def surfaceSpans(units: Vector[SurfaceUnitId]): EpistemicPlacement =
      val distinct = units.distinct.sorted
      if distinct.isEmpty then EpistemicPlacement.NoDiscoursePosition(NoPositionReason.WholeWork)
      else
        distinct.find(unit => model.atlas.byId.get(unit).isEmpty) match
          case Some(absent) =>
            EpistemicPlacement.NoDiscoursePosition(NoPositionReason.UnitAbsentFromAtlas(absent))
          case None =>
            val refs =
              distinct.flatMap(unit => model.atlas.byId.get(unit).map(u => SpanRef(Some(u.id), u.span)))
            SpanSet
              .of(refs)
              .flatMap(clipped)
              .fold(EpistemicPlacement.NoDiscoursePosition(NoPositionReason.BeyondHorizon))(
                EpistemicPlacement.AtSpans.apply
              )

    val gapMarks: Vector[VisualPrimitive] = draft.toVector.flatMap(_.gaps).map { gap =>
      val at = GapTarget.address(gap.target, model.source.id)
      VisualPrimitive.Gap(
        epistemicIdentity(at, AtlasMarkKind.Gap, GapTarget.markKey(gap)),
        gap.family,
        gap.target,
        gap.reason,
        UncertaintyState.of(gap.reason),
        surfaceSpans(GapTarget.chartNodes(gap.target).map(_.sentence))
      )
    }

    val abstentionMarks: Vector[VisualPrimitive] =
      draft.toVector.flatMap(_.abstentions).map { (unit, reason) =>
        val at = coreRef.address(CoreRef.SurfaceUnit(unit))
        VisualPrimitive.Abstention(
          epistemicIdentity(at, AtlasMarkKind.Abstention, reason.render),
          unit,
          reason,
          surfaceSpans(Vector(unit))
        )
      }

    // The occurrence index, not the position in the vector: a validator may state one violation
    // twice, and keying on the global position would move every other law mark's identity when an
    // unrelated violation appears or goes away.
    val lawMarks: Vector[VisualPrimitive] =
      val seen = scala.collection.mutable.Map.empty[Violation, Int]
      draft.toVector.flatMap(_.violations).map { violation =>
        val occurrence = seen.getOrElse(violation, 0)
        seen.update(violation, occurrence + 1)
        val subject = violation.address
        val placement = subject match
          case None    => EpistemicPlacement.NoDiscoursePosition(NoPositionReason.WholeWork)
          case Some(a) =>
            storyRef.parse(a).flatMap(model.supporting) match
              case None =>
                EpistemicPlacement.NoDiscoursePosition(NoPositionReason.SubjectCitesNoSpans(a))
              case Some(support) =>
                clipped(support).fold(
                  EpistemicPlacement.NoDiscoursePosition(NoPositionReason.BeyondHorizon)
                )(EpistemicPlacement.AtSpans.apply)
        val at = subject.getOrElse(coreRef.address(CoreRef.Story(model.source.id)))
        val key = s"${violation.law}|${violation.severity}|${violation.path}|" +
          s"${violation.reason}|${subject.fold("-")(_.render)}|$occurrence"
        VisualPrimitive.UnsatisfiedLaw(
          epistemicIdentity(at, AtlasMarkKind.UnsatisfiedLaw, key),
          violation,
          placement
        )
      }

    val marks =
      (surfaceMarks ++ regions ++ landmarks ++ bands ++ threads ++ portals ++ routes ++
        gapMarks ++ abstentionMarks ++ lawMarks)
        .sortBy(_.identity.mark)

    // V-L2: selection and focus identity survive every projection. A missing ordinary mark uses a
    // visible primary ancestor when one exists and otherwise remains explicitly off-projection.
    val marksByAddress: Map[Address, Vector[MarkId]] =
      marks.groupMap(_.address)(_.identity.mark).view.mapValues(_.sorted).toMap
    val marked = marksByAddress.keySet
    def objectVisible(ref: StoryRef): Boolean = ref match
      case StoryRef.Situation(id) => situationSupport.contains(id)
      case StoryRef.Segment(id)   =>
        g.segments
          .get(id)
          .exists(segment =>
            claimVisible(segment.summary.meta) && clipped(segment.support).nonEmpty
          )
      case _ => false
    def visibleAncestor(ref: StoryRef): Option[Address] =
      val start: Option[NarrativeMember] = ref match
        case StoryRef.Situation(s) if objectVisible(ref) => Some(NarrativeMember.Situation(s))
        case StoryRef.Segment(s) if objectVisible(ref)   => Some(NarrativeMember.Segment(s))
        case _                                           => None
      def segmentVisible(id: SegmentId): Boolean =
        g.segments
          .get(id)
          .exists(segment =>
            claimVisible(segment.summary.meta) && clipped(segment.support).nonEmpty
          )
      start
        .flatMap(member =>
          VisibleAncestorChain.from(member, primaryParent, segmentVisible).find(marked.contains)
        )
    def visibleSurfaceAncestor(id: SurfaceUnitId): Option[Address] =
      @scala.annotation.tailrec
      def climb(next: Option[SurfaceUnitId], seen: Set[SurfaceUnitId]): Option[Address] =
        next match
          case Some(parentId) if !seen.contains(parentId) =>
            model.atlas.byId.get(parentId).filter(surfaceUnitVisible) match
              case Some(parent) =>
                val address = coreRef.address(CoreRef.SurfaceUnit(parent.id))
                if marked.contains(address) then Some(address)
                else climb(parent.parent, seen + parentId)
              case None => None
          case _ => None

      model.atlas.byId
        .get(id)
        .filter(surfaceUnitVisible)
        .flatMap(unit => climb(unit.parent, Set(unit.id)))
    val placements: Map[Address, SelectionPlacement[MarkId]] =
      (state.selection ++ state.focus).toVector
        .sortBy(_.render)
        .map { address =>
          val placement = NonEmptyVector
            .fromVector(marksByAddress.getOrElse(address, Vector.empty))
            .map(SelectionPlacement.OnMark.apply)
            .orElse(
              storyRef
                .parse(address)
                .flatMap(visibleAncestor)
                .map(SelectionPlacement.ViaAncestor.apply)
            )
            .orElse(
              coreRef
                .parse(address)
                .collect { case CoreRef.SurfaceUnit(id) => id }
                .flatMap(visibleSurfaceAncestor)
                .map(SelectionPlacement.ViaAncestor.apply)
            )
            .getOrElse(SelectionPlacement.OffProjection)
          address -> placement
        }
        .toMap

    for
      _ <- marks.traverse_(m => checkEvidence(model, m))
      nav <- SceneNavigation.from(marks)
    yield new NarrativeScene(
      ProjectionContract.discourseAtlas,
      spec.zoom,
      state,
      featureLayer,
      marks,
      nav,
      placements,
      provenance
    )

  /** Provenance binds source, model build, and this exact view configuration. */
  private def validateProvenance(
      model: StoryModel[?],
      state: CommonViewState,
      spec: AtlasSpec
  ): Either[DomainError, Unit] =
    val expectedConfig = AtlasCompiler.configurationChecksum(state, spec)
    if model.source.canonicalChecksum != provenance.sourceChecksum then
      Left(
        DomainError.InvariantViolation(
          "view/atlas/provenance/source-checksum",
          s"${provenance.sourceChecksum.hex} does not match ${model.source.canonicalChecksum.hex}"
        )
      )
    else if provenance.configChecksum != expectedConfig then
      Left(
        DomainError.InvariantViolation(
          "view/atlas/provenance/config-checksum",
          s"${provenance.configChecksum.hex} does not match ${expectedConfig.hex}"
        )
      )
    else
      model.receipt match
        case Some(receipt) if provenance.modelReceiptChecksum.contains(receipt.contentChecksum) =>
          Right(())
        case Some(receipt) =>
          Left(
            DomainError.InvariantViolation(
              "view/atlas/provenance/model-receipt",
              s"expected model receipt ${receipt.contentChecksum.hex}"
            )
          )
        case None
            if provenance.modelReceiptChecksum.isEmpty &&
              (provenance.basis == ViewBasis.ResearcherReviewedFixture ||
                provenance.basis == ViewBasis.DraftBuild) =>
          Right(())
        case None =>
          Left(
            DomainError.InvariantViolation(
              "view/atlas/provenance/model-receipt",
              "model has no build receipt; only the researcher-reviewed fixture and draft-build " +
                "bases may omit one"
            )
          )

  /** V-E1/V-E3: every mark resolves to exact narrative evidence or an exact surface unit.
    *
    * The law extends unchanged to the absence marks: an absence that names words must name words
    * the model actually records. A gap or an abstention may only sit on the exact span of a surface
    * unit the atlas contains, and an unsatisfied law may only sit on spans its subject's own claim
    * cites. An absence with no discourse position claims no text and so cannot lie about any.
    */
  private def checkEvidence(
      model: StoryModel[?],
      mark: VisualPrimitive
  ): Either[DomainError, Unit] =
    def onExactUnits(placement: EpistemicPlacement): Boolean = placement match
      case EpistemicPlacement.NoDiscoursePosition(_) => true
      case EpistemicPlacement.AtSpans(spans)         =>
        spans.refs.toVector.forall(ref =>
          ref.unit.flatMap(model.atlas.byId.get).exists(_.span == ref.span)
        )
    val supported = mark match
      case VisualPrimitive.SurfaceUnit(_, span, kind, unitOrdinal, parent) =>
        Addressable[CoreRef].parse(mark.address) match
          case Some(CoreRef.SurfaceUnit(id)) =>
            model.atlas.byId.get(id).exists { unit =>
              unit.span == span && unit.kind == kind && unit.ordinal == unitOrdinal &&
              unit.parent.map(parentId =>
                Addressable[CoreRef].address(CoreRef.SurfaceUnit(parentId))
              ) == parent
            }
          case _ => false
      case VisualPrimitive.Gap(_, _, _, _, _, placement)  => onExactUnits(placement)
      case VisualPrimitive.Abstention(_, _, _, placement) => onExactUnits(placement)
      case VisualPrimitive.UnsatisfiedLaw(_, violation, placement) =>
        placement match
          case EpistemicPlacement.NoDiscoursePosition(_) => true
          case EpistemicPlacement.AtSpans(spans)         =>
            violation.address
              .flatMap(Addressable[StoryRef].parse)
              .flatMap(model.supporting)
              .exists(support => spans.refs.toVector.forall(support.refs.toVector.contains))
      case _ => Addressable[StoryRef].parse(mark.address).flatMap(model.supporting).nonEmpty
    if supported then Right(())
    else
      Left(
        DomainError.InvariantViolation(
          s"view/atlas/marks/${mark.identity.mark.value}",
          s"${mark.address.render} has no exact evidence support"
        )
      )

object AtlasCompiler:
  /** Bind Atlas compilation to a source, model-basis, compiler, and configuration receipt. */
  def apply(provenance: ViewProvenance): AtlasCompiler = new AtlasCompiler(provenance)

  /** Content address of the exact versioned Atlas compiler configuration. */
  def configurationChecksum(state: CommonViewState, spec: AtlasSpec): Checksum =
    Checksum.ofText(configurationRendering(state, spec))

  private val ConfigurationRenderingVersion = "atlas-compiler-config/v2"

  /** Versioned canonical rendering committed to by [[configurationChecksum]]. */
  private[view] def configurationRendering(state: CommonViewState, spec: AtlasSpec): String =
    ViewConfigurationRendering.render(configurationFields(state, spec))

  private def configurationFields(
      state: CommonViewState,
      spec: AtlasSpec
  ): Vector[(String, String)] =
    val threads = spec.threads match
      case ThreadPolicy.Selected => "selected"
      case ThreadPolicy.All(max) => s"all:${max.value}"
    val selections = ViewConfigurationRendering.selections(state)
    val relations = ViewConfigurationRendering.relations(state)
    Vector(
      "rendering" -> ConfigurationRenderingVersion,
      "horizon" -> ViewConfigurationRendering.horizonValue(state.horizon),
      "focus" -> state.focus.fold("none")(_.render),
      "feature" -> ViewConfigurationRendering.featureValue(state.feature),
      "scale" -> spec.featureScale.canonicalString,
      "selection.count" -> selections.size.toString
    ) ++ selections.zipWithIndex.map((value, index) => s"selection.$index" -> value) ++ Vector(
      "relation.count" -> relations.size.toString
    ) ++ relations.zipWithIndex.map((value, index) => s"relation.$index" -> value) ++ Vector(
      "zoom.narrative" -> narrativeLevelValue(spec.zoom.narrative),
      "zoom.surface" -> surfaceDetailValue(spec.zoom.surface),
      "threads" -> threads,
      "projection" -> "discourse-atlas"
    )

  private def narrativeLevelValue(level: NarrativeLevel): String = level match
    case NarrativeLevel.Story   => "story"
    case NarrativeLevel.Episode => "episode"
    case NarrativeLevel.Scene   => "scene"
    case NarrativeLevel.Event   => "event"

  private def surfaceDetailValue(detail: SurfaceDetail): String = detail match
    case SurfaceDetail.Hidden    => "hidden"
    case SurfaceDetail.Sentences => "sentences"
    case SurfaceDetail.Tokens    => "tokens"

/** Deterministic plain-text twin of a scene (ADR 0002 V-D2). */
object AtlasTextualTwin:
  /** Render a deterministic, total screen-reader and snapshot twin of a scene. */
  def render(scene: NarrativeScene): String =
    val out = new StringBuilder
    val p = scene.provenance
    out.append("Narrative Atlas — ").append(scene.contract.kind.toString).append('\n')
    out.append("Zoom: ").append(scene.zoom.narrative).append(" / ").append(scene.zoom.surface)
    out.append('\n')
    out.append("Basis: ").append(p.basis.label).append('\n')
    p.draft.foreach { promotion =>
      out.append("Draft promotion\n")
      out
        .append("  promotable: ")
        .append(promotion.promoted)
        .append("; derivation gaps: ")
        .append(promotion.gapCount)
        .append("; violations: ")
        .append(promotion.violationCount)
        .append('\n')
      if promotion.unsatisfiedLaws.isEmpty then out.append("  unsatisfied laws: (none)\n")
      else
        out.append("  unsatisfied laws\n")
        promotion.unsatisfiedLaws.foreach(law =>
          out
            .append("  - ")
            .append(law.law)
            .append(' ')
            .append(law.severity)
            .append(" x")
            .append(law.count.value)
            .append('\n')
        )
    }
    out.append("Source checksum: ").append(p.sourceChecksum.hex).append('\n')
    out.append("Model receipt checksum: ")
    out.append(p.modelReceiptChecksum.fold("not available")(_.hex)).append('\n')
    out.append("Compiler: ").append(p.compilerVersion).append('\n')
    out.append("Configuration: ").append(p.configChecksum.hex).append('\n')
    out.append("Shared state\n")
    EvidenceVisibility
      .stateParts(scene.state)
      .foreach(part => out.append("  ").append(part).append('\n'))
    val feature = scene.featureLayer
    out.append("Feature layer\n")
    out
      .append("  scale: ")
      .append(feature.scale.label)
      .append(" [")
      .append(feature.scale.canonicalString)
      .append("]\n")
    out
      .append("  selection: ")
      .append(feature.state.selectedFeature.fold("none")(_.canonicalString))
      .append('\n')
    out
      .append("  resolved space: ")
      .append(feature.state.resolvedSpaceId.fold("none")(_.value))
      .append('\n')
    out.append("  state: ").append(feature.state.canonicalString).append('\n')
    out.append("  observations: ").append(feature.observations.size).append('\n')
    feature.observations.foreach { observation =>
      val extents = observation.xExtents.toVector
        .map(span => s"[${span.start},${span.endExclusive})")
        .mkString(",")
      val upstream = observation.audit.upstream.map(_.render).mkString(",")
      out
        .append("  - ")
        .append(observation.address.render)
        .append(" x=")
        .append(extents)
        .append(" audit=")
        .append(upstream)
        .append('\n')
    }
    out.append("Contract\n")
    out.append(
      s"  x: ${scene.contract.x}; y: ${scene.contract.y}; distance: ${scene.contract.distance}; area: ${scene.contract.area.fold("none")(_.toString)}\n"
    )
    scene.contract.legend.foreach(l =>
      out.append("  ").append(l.channel).append(": ").append(l.meaning).append('\n')
    )
    out.append("  invariants: ")
    out.append(scene.contract.invariants.toVector.map(_.toString).sorted.mkString(", "))
    out.append('\n')
    out.append("Marks\n")
    if scene.marks.isEmpty then out.append("  (none)\n")
    scene.marks.foreach {
      case VisualPrimitive.SurfaceUnit(id, span, kind, unitOrdinal, parent) =>
        out.append(
          s"  surface-unit ${id.mark.value} ${id.address.render} kind=$kind span=[${span.start},${span.endExclusive}) ordinal=$unitOrdinal parent=${parent.fold("-")(_.render)}\n"
        )
      case VisualPrimitive.Region(id, e, label, parent) =>
        out.append(
          s"  region ${id.mark.value} ${id.address.render} x=[${e.x0},${e.x1Exclusive}) lanes=[${e.lane0},${e.lane1}] parent=${parent.fold("-")(_.render)} \"$label\"\n"
        )
      case VisualPrimitive.Landmark(id, a, label, kind, context) =>
        out.append(
          s"  landmark ${id.mark.value} ${id.address.render} x=${a.x} lane=${a.lane} $kind " +
            s"context=${context.value} \"$label\"\n"
        )
      case VisualPrimitive.ContextBand(id, kind, lane, extents, parent, basis) =>
        val ranges = extents.toVector.map(e => s"[${e.x0},${e.x1Exclusive})").mkString(",")
        out.append(
          s"  context-band ${id.mark.value} ${id.address.render} lane=$lane x=$ranges " +
            s"parent=${parent.fold("-")(_.render)} basis=$basis ${kind.label}\n"
        )
      case VisualPrimitive.Gap(id, family, target, reason, state, placement) =>
        out.append(
          s"  gap ${id.mark.value} ${id.address.render} family=${GapTarget.familyName(family)} " +
            s"target=${target.render} reason=${reason.render} state=$state " +
            s"channel=${state.channel} at=${placement.render}\n"
        )
      case VisualPrimitive.Abstention(id, unit, reason, placement) =>
        out.append(
          s"  abstention ${id.mark.value} ${id.address.render} unit=${unit.value} " +
            s"reason=${reason.render} state=${UncertaintyState.Missing} " +
            s"channel=${EpistemicChannel.OpenHatch} at=${placement.render}\n"
        )
      case VisualPrimitive.UnsatisfiedLaw(id, violation, placement) =>
        out.append(
          s"  unsatisfied-law ${id.mark.value} ${id.address.render} law=${violation.law} " +
            s"severity=${violation.severity} path=${violation.path} " +
            s"reason=${violation.reason} subject=${violation.address.fold("-")(_.render)} " +
            s"channel=${EpistemicChannel.Bracket} at=${placement.render}\n"
        )
      case VisualPrimitive.Thread(id, label, pts) =>
        out.append(
          s"  thread ${id.mark.value} ${id.address.render} \"$label\" ${pts.map(p => s"(${p.x},${p.lane})").mkString(" ")}\n"
        )
      case VisualPrimitive.Portal(id, f, t, mode) =>
        out.append(
          s"  portal ${id.mark.value} ${id.address.render} $mode (${f.x},${f.lane})->(${t.x},${t.lane})\n"
        )
      case VisualPrimitive.Route(id, f, t, layer, status) =>
        out.append(
          s"  route ${id.mark.value} ${id.address.render} $layer $status (${f.x},${f.lane})->(${t.x},${t.lane})\n"
        )
    }
    if scene.selectionPlacements.nonEmpty then
      out.append("Selection placements\n")
      scene.selectionPlacements.toVector
        .sortBy(_._1.render)
        .foreach { (address, placement) =>
          val rendered = placement match
            case SelectionPlacement.OnMark(marks) =>
              s"on-mark(${marks.toVector.map(_.value).mkString(",")})"
            case SelectionPlacement.ViaAncestor(ancestor) =>
              s"via-ancestor(${ancestor.render})"
            case SelectionPlacement.OffProjection => "off-projection"
          out.append(s"  ${address.render} -> $rendered\n")
        }
    out.result()
