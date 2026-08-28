package storymodel4s.view

import cats.data.NonEmptyVector
import cats.syntax.all.*
import storymodel4s.core.*
import storymodel4s.story.*

/** The Atlas sibling of [[CodexFlow]] (ADR 0002 D2): placed geometric marks under a declared
  * [[ProjectionContract]]. This file implements the default projection, the **Discourse Atlas**,
  * whose only exact coordinate is source order. Nothing here infers: every mark's address resolves
  * through `StoryModel.supporting`, containment is by construction (a parent region is the hull of
  * its children), visibility under a reader horizon is the shared [[EvidenceVisibility]] closure,
  * and the vertical axis is declared as a *lane*, not a measurement.
  */
enum ProjectionKind:
  case DiscourseAtlas

/** Visual channels a contract may give meaning to. */
enum VisualChannel:
  case X, Y, RegionExtent, LandmarkPosition, Thread, Portal, Route, Distance, Area

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

/** What the geometry of a scene means. Declared data, never documentation (ADR 0002 §2). */
final case class ProjectionContract(
    kind: ProjectionKind,
    x: AxisMeaning,
    y: AxisMeaning,
    distance: DistanceMeaning,
    area: Option[MeasureMeaning],
    legend: Vector[ChannelMeaning],
    invariants: Set[VisualInvariant]
)

object ProjectionContract:
  val discourseAtlas: ProjectionContract = ProjectionContract(
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
      VisualInvariant.Deterministic
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

  def showsSituations: Boolean = this match
    case Scene | Event => true
    case _             => false

/** Surface detail is the other zoom axis (ADR 0002 P3); the Atlas records it, the app renders it.
  */
enum SurfaceDetail:
  case Hidden, Sentences, Tokens

/** Independent narrative and surface-detail coordinates of semantic zoom. */
final case class ZoomLevel(narrative: NarrativeLevel, surface: SurfaceDetail)

/** Stable identity of one placed mark. One [[Address]] may yield several marks (ADR 0002 §6). */
object MarkId extends OpaqueId("MarkId")
type MarkId = MarkId.T

/** Stable semantic and mark identities kept separate across levels and renderers. */
final case class VisualIdentity(address: Address, level: NarrativeLevel, mark: MarkId)

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
  def unsafe(x0: Int, x1Exclusive: Int, lane0: Int, lane1: Int): Extent =
    of(x0, x1Exclusive, lane0, lane1)
      .fold(e => throw new IllegalArgumentException(e.message), identity)

/** One point of a route or thread: exact offset and layout-only lane. */
final case class Anchor(x: Int, lane: Int)

/** Whether a landmark represents an event or a state without a Boolean sentinel. */
enum LandmarkKind:
  case Event, State

/** Closed renderer-neutral marks emitted by the first Discourse Atlas compiler. */
enum VisualPrimitive:
  case Region(identity: VisualIdentity, extent: Extent, label: String, parent: Option[Address])
  case Landmark(identity: VisualIdentity, at: Anchor, label: String, kind: LandmarkKind)
  case Thread(identity: VisualIdentity, label: String, points: Vector[Anchor])
  case Portal(identity: VisualIdentity, from: Anchor, to: Anchor, mode: NarrativeReference)
  case Route(
      identity: VisualIdentity,
      from: Anchor,
      to: Anchor,
      layer: RelationLayer,
      status: EpistemicStatus
  )

  def identity: VisualIdentity
  def address: Address = identity.address

/** Bounded policy for selecting entity-continuity threads. */
enum ThreadPolicy:
  /** Only entities in the shared selection get a thread. */
  case Selected

  /** Every entity with at least two visible participations, up to `max` by participation count. */
  case All(max: PositiveInt)

/** What the Atlas shows besides regions and landmarks. */
final case class AtlasSpec(zoom: ZoomLevel, threads: ThreadPolicy)

/** Where a selected or focused semantic address is represented in this projection. */
enum SelectionPlacement:
  /** The address has one or more ordinary marks at this level. */
  case OnMark(marks: NonEmptyVector[MarkId])

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
    marks: Vector[VisualPrimitive],
    navigation: SceneNavigation,
    selectionPlacements: Map[Address, SelectionPlacement],
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
final class AtlasCompiler(provenance: ViewProvenance):

  def compile(
      model: StoryModel[ModelStatus.Validated],
      state: CommonViewState,
      spec: AtlasSpec
  ): Either[DomainError, NarrativeScene] =
    for
      _ <- EvidenceVisibility.validateHorizon(model.source.canonicalText, state.horizon)
      _ <- validateProvenance(model, state, spec)
      ledger <- model.ledger
      scene <- build(model, ledger, state, spec)
    yield scene

  private def build(
      model: StoryModel[ModelStatus.Validated],
      ledger: ClaimLedger,
      state: CommonViewState,
      spec: AtlasSpec
  ): Either[DomainError, NarrativeScene] =
    val g = model.graph
    val h = model.hierarchy
    val level = spec.zoom.narrative
    val storyRef = Addressable[StoryRef]

    // D4 row 7: the shared evidence closure decides visibility; support is then clipped.
    val visibleClaims: Option[Set[ClaimId]] =
      EvidenceVisibility.visibleUnder(state.horizon, ledger)
    def claimVisible(meta: ClaimMeta): Boolean = visibleClaims.forall(_.contains(meta.id))
    def clipped(support: SpanSet): Option[SpanSet] =
      EvidenceVisibility.clipSupport(support, state.horizon)

    // Every structural edge that contributes to geometry must itself be visible. Under the
    // omniscient horizon `claimVisible` admits the complete validated hierarchy unchanged.
    val primaryContainment = h.primary.filter(edge => claimVisible(edge.meta))
    val childrenOf: Map[SegmentId, Vector[NarrativeMember]] =
      primaryContainment.groupMap(_.parent)(_.member)
    val primaryParent: Map[NarrativeMember, SegmentId] =
      primaryContainment.map(edge => edge.member -> edge.parent).toMap

    val situationSupport: Map[SituationId, SpanSet] =
      g.discourseOrder.flatMap { id =>
        val n = g.situations(id)
        if claimVisible(n.meta) then clipped(n.support).map(id -> _) else None
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
    def laneOf(s: SituationId): Int = lanes.getOrElse(g.situations(s).context, 0)
    def anchorOf(s: SituationId): Anchor = Anchor(situationSupport(s).minSpan.start, laneOf(s))

    // Situation extents (first visible span) and segment extents by construction: the hull of the
    // visible primary children, recursively.
    val situationExtent: Map[SituationId, Extent] =
      visibleSituations.map { id =>
        val sp = situationSupport(id).minSpan
        id -> Extent.unsafe(sp.start, sp.endExclusive, laneOf(id), laneOf(id))
      }.toMap
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

    def markId(address: Address, kind: String): MarkId =
      MarkId.unsafe(ContentAddress.of("mark", address.render, kind, level.toString))
    def identity(ref: StoryRef, kind: String): VisualIdentity =
      val a = storyRef.address(ref)
      VisualIdentity(a, level, markId(a, kind))

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
              identity(StoryRef.Segment(s.id), "region"),
              ext,
              s.summary.value,
              parent
            )
          }
        }

    val landmarks: Vector[VisualPrimitive] =
      if !level.showsSituations then Vector.empty
      else
        visibleSituations.map { id =>
          val n = g.situations(id)
          val kind = if n.isState then LandmarkKind.State else LandmarkKind.Event
          VisualPrimitive.Landmark(
            identity(StoryRef.Situation(id), "landmark"),
            anchorOf(id),
            n.description,
            kind
          )
        }

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
        val pts = participationsByEntity.getOrElse(e, Vector.empty).map(anchorOf)
        val entityVisible = g.entities.get(e).exists(n => claimVisible(n.meta))
        Option.when(pts.size >= 2 && entityVisible)(
          VisualPrimitive.Thread(
            identity(StoryRef.Entity(e), "thread"),
            g.entities(e).label.value,
            pts
          )
        )
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
          .filter(r => math.abs(visiblePosition(r.from) - visiblePosition(r.to)) > 1)
          .map(r =>
            VisualPrimitive.Portal(
              identity(StoryRef.Reference(r.from, r.mode, r.to), "portal"),
              anchorOf(r.from),
              anchorOf(r.to),
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
              .map(c =>
                VisualPrimitive.Route(
                  identity(StoryRef.Causal(c.cause, c.relation, c.effect), "route"),
                  anchorOf(c.cause),
                  anchorOf(c.effect),
                  RelationLayer.Causal,
                  c.meta.status
                )
              )
          else Vector.empty
        val goals =
          if state.relationLayers.contains(RelationLayer.Goal) then
            g.relations.goals
              .filter(e => endpointsVisible(e.from, e.to, e.meta))
              .map(e =>
                VisualPrimitive.Route(
                  identity(StoryRef.Goal(e.from, e.relation, e.to), "route"),
                  anchorOf(e.from),
                  anchorOf(e.to),
                  RelationLayer.Goal,
                  e.meta.status
                )
              )
          else Vector.empty
        causal ++ goals

    val marks = (regions ++ landmarks ++ threads ++ portals ++ routes).sortBy(_.identity.mark)

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
      def climb(m: NarrativeMember, seen: Set[NarrativeMember]): Option[Address] =
        primaryParent.get(m).flatMap { p =>
          val a = storyRef.address(StoryRef.Segment(p))
          if marked.contains(a) then Some(a)
          else if seen.contains(NarrativeMember.Segment(p)) then None
          else climb(NarrativeMember.Segment(p), seen + m)
        }
      start.flatMap(climb(_, Set.empty))
    val placements: Map[Address, SelectionPlacement] =
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
      marks,
      nav,
      placements,
      provenance
    )

  /** Provenance binds source, model build, and this exact view configuration. */
  private def validateProvenance(
      model: StoryModel[ModelStatus.Validated],
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
              provenance.basis == ViewBasis.ResearcherReviewedFixture =>
          Right(())
        case None =>
          Left(
            DomainError.InvariantViolation(
              "view/atlas/provenance/model-receipt",
              "model has no build receipt; only the researcher-reviewed fixture basis may omit one"
            )
          )

  /** V-E1/V-E3: every mark resolves to an object the model supports with exact spans. */
  private def checkEvidence(
      model: StoryModel[?],
      mark: VisualPrimitive
  ): Either[DomainError, Unit] =
    Addressable[StoryRef].parse(mark.address).flatMap(model.supporting) match
      case Some(_) => Right(())
      case None    =>
        Left(
          DomainError.InvariantViolation(
            s"view/atlas/marks/${mark.identity.mark.value}",
            s"${mark.address.render} has no evidence support"
          )
        )

object AtlasCompiler:
  def apply(provenance: ViewProvenance): AtlasCompiler = new AtlasCompiler(provenance)

  /** Content address of the exact view configuration, shared-state parts first. */
  def configurationChecksum(state: CommonViewState, spec: AtlasSpec): Checksum =
    val threads = spec.threads match
      case ThreadPolicy.Selected => "threads:selected"
      case ThreadPolicy.All(max) => s"threads:all:${max.value}"
    val parts = EvidenceVisibility.stateParts(state) ++ Vector(
      s"zoom:${spec.zoom.narrative}:${spec.zoom.surface}",
      threads,
      s"projection:${ProjectionKind.DiscourseAtlas}"
    )
    ContentAddress.digest(parts)

/** Deterministic plain-text twin of a scene (ADR 0002 V-D2). */
object AtlasTextualTwin:
  def render(scene: NarrativeScene): String =
    val out = new StringBuilder
    val p = scene.provenance
    out.append("Narrative Atlas — ").append(scene.contract.kind.toString).append('\n')
    out.append("Zoom: ").append(scene.zoom.narrative).append(" / ").append(scene.zoom.surface)
    out.append('\n')
    out.append("Basis: ").append(p.basis.label).append('\n')
    out.append("Source checksum: ").append(p.sourceChecksum.hex).append('\n')
    out.append("Model receipt checksum: ")
    out.append(p.modelReceiptChecksum.fold("not available")(_.hex)).append('\n')
    out.append("Compiler: ").append(p.compilerVersion).append('\n')
    out.append("Configuration: ").append(p.configChecksum.hex).append('\n')
    out.append("Shared state\n")
    EvidenceVisibility
      .stateParts(scene.state)
      .foreach(part => out.append("  ").append(part).append('\n'))
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
      case VisualPrimitive.Region(id, e, label, parent) =>
        out.append(
          s"  region ${id.mark.value} ${id.address.render} x=[${e.x0},${e.x1Exclusive}) lanes=[${e.lane0},${e.lane1}] parent=${parent.fold("-")(_.render)} \"$label\"\n"
        )
      case VisualPrimitive.Landmark(id, a, label, kind) =>
        out.append(
          s"  landmark ${id.mark.value} ${id.address.render} x=${a.x} lane=${a.lane} $kind \"$label\"\n"
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
