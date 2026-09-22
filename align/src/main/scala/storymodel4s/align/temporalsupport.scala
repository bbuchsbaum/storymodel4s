package storymodel4s.align

import cats.syntax.all.*
import storymodel4s.core.*

/** A checked selection of supplied geometry. It does not allocate assignment mass in time. */
final class TemporalSupport private (
    val sourceDigest: Checksum,
    val target: SourceNodeRef,
    val coordinate: TemporalSupport.Coordinate,
    val nodes: Vector[TemporalSupport.Node]
):
  def selected: TemporalSupport.Node = nodes.find(_.ref == target).get

  /** Transitive physical-locus accounting, independent of the legacy immediate-child field. */
  def unlocatedDescendants: Vector[SourceNodeRef] =
    nodes.filter(n => n.ref != target && n.state == TemporalSupport.State.Unlocated).map(_.ref)
  def unavailableDescendants: Vector[SourceNodeRef] =
    nodes.filter(n => n.ref != target && n.included.isEmpty).map(_.ref)

object TemporalSupport:
  val Version: String = "supplied-temporal-support/v1"

  enum Selection:
    /** Select this declared part's primary axis; native evidence is never assigned an occurrence.
      */
    case Part(bundle: Checksum)

    /** Select one admitted occurrence on the composition's primary axis. No native projection. */
    case Occurrence(mapping: Checksum, occurrence: OccurrenceId)

  final class Coordinate private[TemporalSupport] (
      val selection: Selection,
      val bundle: Checksum,
      val axis: PresentationAxis,
      val occurrence: Option[CompositionSegment]
  )

  enum State:
    case Available, Unlocated, TextOnly, NoSupportOnAxis, OutsideOccurrence

  /** Included and excluded retain exact unions/points. Missing support is never an empty union. */
  final class Node private[TemporalSupport] (
      val ref: SourceNodeRef,
      val suppliedIdentity: Option[Checksum],
      val state: State,
      val included: Option[PlaybackSupport],
      val excluded: Option[PlaybackSupport]
  )

  enum Refusal:
    case UnknownTarget, UnknownPart, CompositionRequired, UnknownOccurrence, AmbiguousOccurrence
    case CyclicHierarchy, ForeignSupport, InvalidSupport, OccurrenceEvidenceMismatch

  def read(
      source: SourceRepresentation,
      target: SourceNodeRef,
      selection: Selection
  ): Either[Refusal, TemporalSupport] =
    for
      _ <- source.target(target).toRight(Refusal.UnknownTarget)
      _ <- acyclic(source)
      coordinate <- select(source, selection)
      descendants = closure(source, target)
      rows <- source.targets
        .filter(t => descendants.contains(t.ref))
        .sortBy(_.ref.key)
        .traverse(t => locate(source, t, coordinate))
    yield new TemporalSupport(source.digest, target, coordinate, rows)

  /** Validate a coordinate selection independently of a target's availability. */
  def select(
      source: SourceRepresentation,
      selection: Selection
  ): Either[Refusal, Coordinate] =
    selection match
      case Selection.Part(identity) =>
        source.bundles.toVector.collect {
          case media: BundleEntry.Media if media.bundle.identity == identity => media.bundle
        } match
          case Vector(part) =>
            Right(new Coordinate(selection, part.identity, part.primaryAxis, None))
          case _ => Left(Refusal.UnknownPart)
      case Selection.Occurrence(mappingIdentity, occurrenceId) =>
        source.composition.toRight(Refusal.CompositionRequired).flatMap { composition =>
          val candidates = composition.composed.mappings
            .collect {
              case mapping: TrackComposition if mapping.identity == mappingIdentity => mapping
            }
            .flatMap(_.segments.toVector.filter(_.occurrence == occurrenceId))
          candidates match
            case Vector(segment) =>
              Right(
                new Coordinate(
                  selection,
                  composition.composed.identity,
                  composition.composed.primaryAxis,
                  Some(segment)
                )
              )
            case Vector() => Left(Refusal.UnknownOccurrence)
            case _        => Left(Refusal.AmbiguousOccurrence)
        }

  // Iterative ancestry walk refuses cycles before deriving transitive missingness; no stack limit.
  private def acyclic(source: SourceRepresentation): Either[Refusal, Unit] =
    val parents = source.targets.map(t => t.ref -> t.parent).toMap
    val complete = scala.collection.mutable.HashSet.empty[SourceNodeRef]
    var cycle = false
    source.targets.foreach { target =>
      val path = scala.collection.mutable.HashSet.empty[SourceNodeRef]
      var cursor = Option(target.ref)
      while cursor.nonEmpty && !cycle && !complete.contains(cursor.get) do
        val ref = cursor.get
        if !path.add(ref) then cycle = true else cursor = parents(ref)
      complete ++= path
    }
    Either.cond(!cycle, (), Refusal.CyclicHierarchy)

  private def closure(source: SourceRepresentation, target: SourceNodeRef): Set[SourceNodeRef] =
    val children = source.targets.groupBy(_.parent).view.mapValues(_.map(_.ref)).toMap
    var found = Set(target)
    var pending = List(target)
    while pending.nonEmpty do
      val next = children.getOrElse(Some(pending.head), Vector.empty).filterNot(found.contains)
      found ++= next
      pending = next.toList ::: pending.tail
    found

  private def locate(
      source: SourceRepresentation,
      target: MappingTarget,
      coordinate: Coordinate
  ): Either[Refusal, Node] =
    def unavailable(state: State, identity: Option[Checksum]): Node =
      new Node(target.ref, identity, state, None, None)
    target.sourceSupport match
      case _: SourceSupportStatus.Unlocated     => Right(unavailable(State.Unlocated, None))
      case located: SourceSupportStatus.Located =>
        val identity = Some(located.support.identity)
        located.support match
          case TypedSupport.Text(_)            => Right(unavailable(State.TextOnly, identity))
          case TypedSupport.Anchored(evidence) =>
            val bundles = source.bundles.toVector.collect { case m: BundleEntry.Media =>
              m.bundle
            } ++
              source.composition.toVector.map(_.composed)
            for
              owner <- bundles
                .find(_.identity == evidence.bundleIdentity)
                .toRight(Refusal.ForeignSupport)
              _ <- evidence.checkedOn(owner).left.map(_ => Refusal.InvalidSupport)
              _ <- occurrenceEvidence(owner, evidence, coordinate)
              node <-
                if !evidence.anchors.toVector.exists(_.axisId.contains(coordinate.axis.id)) then
                  Right(unavailable(State.NoSupportOnAxis, identity))
                else
                  evidence
                    .playbackOn(coordinate.axis.id)
                    .left
                    .map(_ => Refusal.InvalidSupport)
                    .flatMap { supplied =>
                      coordinate.occurrence match
                        case None =>
                          Right(
                            new Node(target.ref, identity, State.Available, Some(supplied), None)
                          )
                        case Some(segment) =>
                          split(supplied, coordinate.axis, segment.target).map {
                            (inside, outside) =>
                              new Node(
                                target.ref,
                                identity,
                                if inside.nonEmpty then State.Available
                                else State.OutsideOccurrence,
                                inside,
                                outside
                              )
                          }
                    }
            yield node

  /** Axis equality alone cannot establish occurrence provenance when part mappings overlap. */
  private def occurrenceEvidence(
      owner: SourceBundle,
      evidence: EvidenceSupport,
      coordinate: Coordinate
  ): Either[Refusal, Unit] =
    (coordinate.selection, coordinate.occurrence) match
      case (Selection.Occurrence(mapping, _), Some(segment)) =>
        def intersects(interval: PlaybackInterval): Boolean =
          interval.axis == segment.target.axis && interval.start < segment.target.endExclusive && segment.target.start < interval.endExclusive
        def contributes(anchor: EvidenceAnchor): Boolean = anchor match
          case EvidenceAnchor.MediaTime(_, _, _, set) => set.intervals.toVector.exists(intersects)
          case EvidenceAnchor.MediaPoint(_, _, at)    =>
            at.axis == segment.target.axis && segment.target.contains(at.at)
          case EvidenceAnchor.Shot(_, _, _, interval) => intersects(interval)
          case EvidenceAnchor.Track(_, _, _, set)     => set.intervals.toVector.exists(intersects)
          case _: EvidenceAnchor.Text                 => false
        val valid = evidence.anchors.toVector.filter(contributes).forall { anchor =>
          owner.stream(anchor.anchorStream).exists { stream =>
            stream.nativeAxis == segment.source.axis &&
            owner.mappings.collect {
              case m: TrackComposition
                  if m.relation.sourceAxis == stream.nativeAxis && m.relation.targetAxis == segment.target.axis =>
                m.identity
            }.distinct == Vector(mapping)
          }
        }
        Either.cond(valid, (), Refusal.OccurrenceEvidenceMismatch)
      case _ => Right(())

  /** Partition only supplied target-axis geometry; never rescale native coordinates or add mass. */
  private def split(
      support: PlaybackSupport,
      axis: PresentationAxis,
      window: PlaybackInterval
  ): Either[Refusal, (Option[PlaybackSupport], Option[PlaybackSupport])] =
    val inside = support.intervals.flatMap { i =>
      val start = i.start.max(window.start)
      val end = i.endExclusive.min(window.endExclusive)
      Option.when(start < end)((start, end))
    }
    val outside = support.intervals.flatMap { i =>
      Vector(
        Option.when(i.start < window.start)((i.start, i.endExclusive.min(window.start))),
        Option.when(i.endExclusive > window.endExclusive)(
          (i.start.max(window.endExclusive), i.endExclusive)
        )
      ).flatten
    }
    def union(
        bounds: Vector[(Long, Long)],
        points: Vector[PlaybackInstant]
    ): Either[Refusal, Option[PlaybackSupport]] =
      if bounds.isEmpty && points.isEmpty then Right(None)
      else
        for
          intervals <- bounds.traverse((a, b) =>
            PlaybackInterval.on(axis, a, b).left.map(_ => Refusal.InvalidSupport)
          )
          value <- PlaybackSupport
            .of(axis.id, intervals, points)
            .left
            .map(_ => Refusal.InvalidSupport)
        yield Some(value)
    for
      included <- union(inside, support.points.filter(p => window.contains(p.at)))
      excluded <- union(outside, support.points.filterNot(p => window.contains(p.at)))
    yield (included, excluded)
