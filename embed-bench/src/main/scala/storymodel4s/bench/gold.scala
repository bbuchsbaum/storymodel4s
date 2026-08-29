package storymodel4s.bench

import cats.data.NonEmptyVector

import storymodel4s.align.{ExternalState, Facet, SourceNodeRef, SourceView}
import storymodel4s.recall.{DiscourseFunction, RecallUnitId}

/** Adjudicated groundedness of a recall unit (protocol §3): where its content comes from.
  *
  * Why: the open-world metrics are scored per groundedness class and are never pooled with the
  * source-anchor metrics — an association is not a missed anchor.
  */
enum Groundedness:
  case Source, Association, Inference, Intrusion, Uninterpretable

/** One acceptable anchor with its declared hierarchy level (0 = situation). */
final case class GoldTarget(node: SourceNodeRef, level: Int)

/** The alignment gold for one recall unit — the `RecallUnitTarget` record of the adjudication
  * protocol §3, in align vocabulary.
  *
  * Why a NonEmptyVector of targets: a unit may have several acceptable anchors at several levels
  * (summary, blend, ambiguity survived adjudication); metrics score against the acceptable set and
  * report the primary separately. A unit with no source target carries its groundedness instead.
  */
final case class GoldUnit(
    unit: RecallUnitId,
    targets: Vector[GoldTarget],
    primary: Option[GoldTarget],
    facets: Set[Facet],
    groundedness: Groundedness,
    externalSubtype: Option[ExternalState],
    discourse: DiscourseFunction,
    blend: Boolean
):
  def isSourceAnchored: Boolean = primary.nonEmpty
  def isFaithful: Boolean = isSourceAnchored && facets.isEmpty
  def isDistorted: Boolean = isSourceAnchored && facets.nonEmpty
  def targetNodes: Set[SourceNodeRef] = targets.map(_.node).toSet

object GoldUnit:
  /** A faithful, source-anchored unit whose primary is the first target. */
  def anchored(
      unit: RecallUnitId,
      targets: NonEmptyVector[GoldTarget],
      discourse: DiscourseFunction = DiscourseFunction.EpisodicAssertion,
      facets: Set[Facet] = Set.empty,
      blend: Boolean = false
  ): GoldUnit =
    GoldUnit(
      unit,
      targets.toVector,
      Some(targets.head),
      facets,
      Groundedness.Source,
      None,
      discourse,
      blend
    )

  /** A unit whose content is not anchored in the source. */
  def external(
      unit: RecallUnitId,
      groundedness: Groundedness,
      subtype: Option[ExternalState],
      discourse: DiscourseFunction
  ): GoldUnit =
    GoldUnit(unit, Vector.empty, None, Set.empty, groundedness, subtype, discourse, blend = false)

/** The gold for a whole recall. `validated` checks that every target is a node of the view at its
  * declared level, so a gold file cannot silently name a node the bench never scores.
  *
  * Why a non-case class: `validated` is a real smart constructor, but a public case class still
  * exposes `fromProduct` / `copy`, which can mint a map `validated` would refuse.
  */
final class Gold private (val byUnit: Map[RecallUnitId, GoldUnit]):
  def apply(unit: RecallUnitId): Option[GoldUnit] = byUnit.get(unit)
  def size: Int = byUnit.size

  override def equals(other: Any): Boolean = other match
    case that: Gold => byUnit == that.byUnit
    case _          => false

  override def hashCode(): Int = byUnit.hashCode()

  override def toString: String =
    s"Gold(${byUnit.keys.toVector.sortBy(_.value).map(_.value).mkString(",")})"

object Gold:
  enum GoldError:
    case UnknownNode(unit: RecallUnitId, node: SourceNodeRef)
    case LevelMismatch(unit: RecallUnitId, node: SourceNodeRef, declared: Int, actual: Int)
    case PrimaryNotInTargets(unit: RecallUnitId)
    case DistortedWithoutAnchor(unit: RecallUnitId)

    def message: String = this match
      case UnknownNode(u, n) => s"unit ${u.value}: gold target ${n.key} is not a node of the view"
      case LevelMismatch(u, n, d, a) =>
        s"unit ${u.value}: target ${n.key} declared at level $d but the view has it at level $a"
      case PrimaryNotInTargets(u)    => s"unit ${u.value}: primary is not among the targets"
      case DistortedWithoutAnchor(u) => s"unit ${u.value}: facets without a source anchor"

  def validated(units: Vector[GoldUnit], view: SourceView): Either[GoldError, Gold] =
    val problems = units.iterator.flatMap { g =>
      val nodeErrors = g.targets.flatMap { t =>
        view.node(t.node) match
          case None                          => Some(GoldError.UnknownNode(g.unit, t.node))
          case Some(n) if n.level != t.level =>
            Some(GoldError.LevelMismatch(g.unit, t.node, t.level, n.level))
          case Some(_) => None
      }
      val primaryError =
        g.primary.filterNot(g.targets.contains).map(_ => GoldError.PrimaryNotInTargets(g.unit))
      val facetError =
        if g.facets.nonEmpty && g.primary.isEmpty then
          Some(GoldError.DistortedWithoutAnchor(g.unit))
        else None
      nodeErrors ++ primaryError ++ facetError
    }
    problems.nextOption() match
      case Some(e) => Left(e)
      case None    => Right(new Gold(units.iterator.map(g => g.unit -> g).toMap))
