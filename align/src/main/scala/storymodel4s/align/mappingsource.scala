package storymodel4s.align

import cats.data.{NonEmptySet, NonEmptyVector}
import cats.syntax.all.*
import scala.collection.immutable.SortedSet
import storymodel4s.core.*

sealed trait BundleEntry
object BundleEntry:
  final class Media private (val bundle: SourceBundle) extends BundleEntry
  object Media:
    private[BundleEntry] def of(bundle: SourceBundle): Media = new Media(bundle)
  final class TextSource private (val canonicalText: Checksum) extends BundleEntry
  object TextSource:
    private[BundleEntry] def of(checksum: Checksum): TextSource = new TextSource(checksum)
  def media(bundle: SourceBundle): Media = Media.of(bundle)
  def text(canonicalText: Checksum): TextSource = TextSource.of(canonicalText)

enum UnlocatedReason:
  case NoLocusInSource
enum SupportRelation:
  case EvidenceSupport
enum BundleRole:
  case Part(bundle: SourceBundleId)
  case Composition
enum PartOrder:
  case Before, Same, After, Incomparable
enum CoverageUnknownReason:
  case TargetUnlocated

/** Supplied physical support; inventory membership is checked by SourceRepresentation.of. */
sealed trait SourceSupportStatus
object SourceSupportStatus:
  final class Located private (val support: TypedSupport) extends SourceSupportStatus
  object Located:
    private[SourceSupportStatus] def of(support: TypedSupport): Located = new Located(support)
  final class Unlocated private (val reason: UnlocatedReason) extends SourceSupportStatus
  object Unlocated:
    private[SourceSupportStatus] def of(reason: UnlocatedReason): Unlocated = new Unlocated(reason)
  def located(support: TypedSupport): Located = Located.of(support)
  def unlocated(reason: UnlocatedReason): Unlocated = Unlocated.of(reason)

/** A declaration checked against complete part identities, streams and coordinate mappings. */
final class DeclaredComposition private (
    val composed: SourceBundle,
    val parts: NonEmptyVector[SourceBundle]
)
object DeclaredComposition:
  def of(
      composed: SourceBundle,
      parts: NonEmptyVector[SourceBundle]
  ): Either[MappingRefusal, DeclaredComposition] =
    val all = parts.toVector
    val mappings = composed.mappings.collect { case c: TrackComposition => c }
    val axes = all.map(_.primaryAxis.id)
    def within(interval: PlaybackInterval, axis: PresentationAxis): Boolean = axis.extent match
      case extent: AxisExtent.PlaybackTicks =>
        interval.axis == axis.id && interval.start >= extent.start && interval.endExclusive <= extent.endExclusive
      case _ => false
    val coverage = axes.distinct.size == axes.size && mappings.size == all.size &&
      mappings.map(_.relation.sourceAxis).sortBy(_.value) == axes.sortBy(_.value) &&
      mappings.forall(_.relation.targetAxis == composed.primaryAxis.id)
    val streamsMatch = all.flatMap(_.streams).forall(s => composed.stream(s.id).contains(s))
    val extentsMatch = mappings.forall { mapping =>
      all.find(_.primaryAxis.id == mapping.relation.sourceAxis).exists { part =>
        mapping.segments.toVector
          .forall(s => within(s.source, part.primaryAxis) && within(s.target, composed.primaryAxis))
      }
    }
    if !coverage || !streamsMatch || !extentsMatch then
      Left(
        MappingRefusal.InvalidComposition(
          "composition must bind exactly its parts once, preserve their streams and stay within declared axes"
        )
      )
    else Right(new DeclaredComposition(composed, parts))

type MappingTarget = SourceRepresentation.Target
type SupportCoverage = SourceRepresentation.Coverage
object SupportCoverage:
  export SourceRepresentation.Coverage.{Complete, Partial, Unknown}

/** Immutable source dictionary with explicit physical support and separately bound semantic scope.
  * A text checksum declares identity; the later workspace join checks it against actual text bytes.
  */
final class SourceRepresentation private (
    val bundles: NonEmptyVector[BundleEntry],
    val composition: Option[DeclaredComposition],
    val targets: Vector[MappingTarget],
    val viewFingerprint: ViewFingerprint,
    val scopeDigest: Checksum
):
  def target(ref: SourceNodeRef): Option[MappingTarget] = targets.find(_.ref == ref)
  def digest: Checksum = MappingRender.digest(
    Vector(
      "source-representation/v1",
      viewFingerprint.checksum.hex,
      scopeDigest.hex,
      MappingRender.sequence(bundles.toVector.map(MappingSourceRender.bundleEntry).sorted),
      MappingRender.optional(
        composition.map(c =>
          MappingRender.sequence(
            Vector(
              MappingSourceRender.bundle(c.composed),
              MappingRender.sequence(c.parts.toVector.map(MappingSourceRender.bundle).sorted)
            )
          )
        )
      ),
      MappingRender.sequence(targets.sortBy(_.ref.key).map(MappingSourceRender.target))
    )
  )

  /** Relative part order exists only when every declared occurrence establishes that order. */
  def order(a: PresentationAxisId, b: PresentationAxisId): PartOrder =
    val known = bundles.toVector.collect { case m: BundleEntry.Media =>
      m.bundle.primaryAxis.id
    }.toSet ++ composition.map(_.composed.primaryAxis.id)
    if !known.contains(a) || !known.contains(b) then PartOrder.Incomparable
    else if a == b then PartOrder.Same
    else
      def segments(axis: PresentationAxisId): Vector[PlaybackInterval] =
        composition.toVector.flatMap(
          _.composed.mappings
            .collect {
              case c: TrackComposition if c.relation.sourceAxis == axis =>
                c.segments.toVector.map(_.target)
            }
            .flatten
        )
      val left = segments(a)
      val right = segments(b)
      if left.isEmpty || right.isEmpty then PartOrder.Incomparable
      else if left.map(_.endExclusive).max <= right.map(_.start).min then PartOrder.Before
      else if right.map(_.endExclusive).max <= left.map(_.start).min then PartOrder.After
      else PartOrder.Incomparable

object SourceRepresentation:
  sealed trait Coverage
  object Coverage:
    final class Complete private () extends Coverage
    object Complete:
      private[Coverage] def derived(): Complete = new Complete()
    final class Partial private (val missing: NonEmptySet[SourceNodeRef]) extends Coverage
    object Partial:
      private[Coverage] def derived(missing: NonEmptySet[SourceNodeRef]): Partial = new Partial(
        missing
      )
    final class Unknown private (val reason: CoverageUnknownReason) extends Coverage
    object Unknown:
      private[Coverage] def derived(): Unknown = new Unknown(CoverageUnknownReason.TargetUnlocated)
    private[SourceRepresentation] def derive(
        ref: SourceNodeRef,
        nodes: Vector[NodeSummary],
        physical: Map[SourceNodeRef, SourceSupportStatus]
    ): Coverage =
      val children = nodes.filter(_.parent.contains(ref)).map(_.ref)
      val missing = SortedSet.from(
        children.filter(r => physical(r).isInstanceOf[SourceSupportStatus.Unlocated])
      )
      if children.nonEmpty then
        NonEmptySet.fromSet(missing).fold[Coverage](Complete.derived())(Partial.derived)
      else if physical(ref).isInstanceOf[SourceSupportStatus.Located] then Complete.derived()
      else Unknown.derived()

  final class Target private (
      val ref: SourceNodeRef,
      val level: Int,
      val parent: Option[SourceNodeRef],
      val sourceSupport: SourceSupportStatus,
      val axisMembership: Map[PresentationAxisId, BundleRole],
      val supportCoverage: SupportCoverage,
      val propositional: PropositionalScope
  )
  object Target:
    private[SourceRepresentation] def derived(
        node: NodeSummary,
        physical: SourceSupportStatus,
        axes: Map[PresentationAxisId, BundleRole],
        coverage: SupportCoverage
    ): Target =
      new Target(node.ref, node.level, node.parent, physical, axes, coverage, node.propositional)

  def of(
      view: SourceView,
      bundles: NonEmptyVector[BundleEntry],
      composition: Option[DeclaredComposition],
      physical: Map[SourceNodeRef, SourceSupportStatus]
  ): Either[MappingRefusal, SourceRepresentation] =
    val nodes = view.nodes
    val refs = nodes.map(_.ref)
    val parts = bundles.toVector.collect { case m: BundleEntry.Media => m.bundle }
    val texts = bundles.toVector.collect { case t: BundleEntry.TextSource => t.canonicalText }
    val axisRoles = parts.map(b =>
      b.primaryAxis.id -> BundleRole.Part(b.id)
    ) ++ composition.toVector.map(c => c.composed.primaryAxis.id -> BundleRole.Composition)
    val duplicateRefs =
      refs.groupBy(identity).collect { case (ref, xs) if xs.size > 1 => ref }.toVector.sorted
    val ambiguousBundleIds = parts
      .groupBy(_.id)
      .collect {
        case (id, entries) if entries.map(_.identity).distinct.size > 1 => id
      }
      .toVector
      .sortBy(_.value)
    val duplicateAxes = axisRoles
      .groupBy(_._1)
      .collect { case (axis, xs) if xs.size > 1 => axis }
      .toVector
      .sortBy(_.value)
    val structural: Either[MappingRefusal, Unit] =
      if duplicateRefs.nonEmpty then Left(MappingRefusal.DuplicateTarget(duplicateRefs.head))
      else if physical.keySet != refs.toSet then Left(MappingRefusal.PhysicalInventoryMismatch)
      else if nodes.exists(n => view.node(n.ref) != Some(n) || n.level < 0) then
        Left(
          MappingRefusal
            .InvalidValue("source.nodes", "lookup must agree with nonnegative-level node inventory")
        )
      else if nodes.exists(n => n.parent.exists(p => !refs.contains(p))) then
        Left(MappingRefusal.UnknownTarget(nodes.flatMap(_.parent).find(p => !refs.contains(p)).get))
      else if ambiguousBundleIds.nonEmpty then
        Left(MappingRefusal.AmbiguousBundle(ambiguousBundleIds.head))
      else if duplicateAxes.nonEmpty then Left(MappingRefusal.AmbiguousAxis(duplicateAxes.head))
      else if texts.distinct.size != texts.size then Left(MappingRefusal.InvalidTextSource)
      else Right(())
    val checkedComposition = composition.traverse_ { c =>
      NonEmptyVector.fromVector(parts) match
        case None =>
          Left(MappingRefusal.InvalidComposition("composition requires listed media parts"))
        case Some(ps)
            if ps.toVector
              .map(_.identity)
              .sortBy(_.hex) != c.parts.toVector.map(_.identity).sortBy(_.hex) =>
          Left(
            MappingRefusal.InvalidComposition(
              "composition parts differ from listed full identities"
            )
          )
        case Some(ps) => DeclaredComposition.of(c.composed, ps).map(_ => ())
    }
    def membership(node: NodeSummary): Either[MappingRefusal, Map[PresentationAxisId, BundleRole]] =
      physical(node.ref) match
        case _: SourceSupportStatus.Unlocated     => Right(Map.empty)
        case located: SourceSupportStatus.Located =>
          located.support match
            case TypedSupport.Text(_) =>
              if texts.size == 1 then Right(Map.empty) else Left(MappingRefusal.InvalidTextSource)
            case TypedSupport.Anchored(support) =>
              val available = parts ++ composition.toVector.map(_.composed)
              available.filter(_.identity == support.bundleIdentity) match
                case Vector(bundle) =>
                  support
                    .checkedOn(bundle)
                    .left
                    .map(e => MappingRefusal.InvalidValue("source.support", e.message))
                    .flatMap { _ =>
                      support.anchors.toVector
                        .flatMap(_.axisId)
                        .distinct
                        .traverse { axis =>
                          axisRoles.filter(_._1 == axis) match
                            case Vector((_, role)) => Right(axis -> role)
                            case Vector()          => Left(MappingRefusal.ForeignAxis(axis))
                            case _                 => Left(MappingRefusal.AmbiguousAxis(axis))
                        }
                        .flatMap { entries =>
                          val nativeParts = entries.collect { case (axis, BundleRole.Part(_)) =>
                            axis
                          }.distinct
                          if nativeParts.size > 1 && composition.isEmpty then
                            Left(MappingRefusal.CrossPartWithoutComposition(node.ref))
                          else Right(entries.toMap)
                        }
                    }
                case _ => Left(MappingRefusal.ForeignBundle(support.bundleIdentity))
    for
      _ <- structural
      _ <- checkedComposition
      targets <- nodes.sortBy(_.ref.key).traverse { node =>
        membership(node).map(axes =>
          Target.derived(node, physical(node.ref), axes, Coverage.derive(node.ref, nodes, physical))
        )
      }
    yield new SourceRepresentation(
      bundles,
      composition,
      targets,
      ViewFingerprint.of(view),
      MappingSourceRender.scope(nodes)
    )
