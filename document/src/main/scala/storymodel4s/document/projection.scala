package storymodel4s.document

import cats.data.NonEmptySet
import storymodel4s.core.*
import storymodel4s.core.NarrativeKind.{EntityK, SituationK}
import storymodel4s.story.{NarrativeGraph, Polarity}

/** How chart nodes give rise to a narrative node (design record §46). */
enum ProjectionMode:
  case DirectMention, EventRealization, StateRealization, ProspectiveDescription,
    RetrospectiveDescription, Summary, Inferred

/** Evidence-backed projection from one or more chart nodes to a canonical narrative node. Many
  * chart nodes may project to one canonical node; a chart node may take part in several
  * projections, but in at most one `DirectMention` (validated by [[ProjectionIndex]]).
  */
final case class Projection[K <: NarrativeKind](
    sources: NonEmptySet[ChartNodeRef],
    target: CanonicalId[K],
    mode: ProjectionMode,
    meta: ClaimMeta
)

/** A canonical situation's truth-status inside one context: the narrative-side counterpart of an
  * embedded proposition (§47). Reported content lives under its speech context; the same content
  * may carry a separate, differently-statused assertion in the narrated world.
  */
final case class ContextualAssertion(
    situation: SituationId,
    context: ContextId,
    polarity: Polarity,
    status: EpistemicStatus
)

enum ProjectionViolation:
  case UnknownSituation(target: CanonicalId[SituationK], path: String)
  case UnknownEntity(target: CanonicalId[EntityK], path: String)
  case MultipleDirectMentions(source: ChartNodeRef, targets: Vector[String])
  case SourceNotInMentionGraph(source: ChartNodeRef, path: String)

/** Indexes over projections: chart node → projections and canonical → chart nodes. */
final case class ProjectionIndex(
    situations: Vector[Projection[SituationK]],
    entities: Vector[Projection[EntityK]]
):
  lazy val byChartNode: Map[ChartNodeRef, Vector[(String, ProjectionMode)]] =
    (situations.flatMap(p => p.sources.toSortedSet.toVector.map(_ -> (p.target.value, p.mode))) ++
      entities.flatMap(p => p.sources.toSortedSet.toVector.map(_ -> (p.target.value, p.mode))))
      .groupMap(_._1)(_._2)

  lazy val situationSources: Map[CanonicalId[SituationK], Vector[ChartNodeRef]] =
    situations.groupMap(_.target)(_.sources.toSortedSet.toVector).view.mapValues(_.flatten).toMap

  lazy val entitySources: Map[CanonicalId[EntityK], Vector[ChartNodeRef]] =
    entities.groupMap(_.target)(_.sources.toSortedSet.toVector).view.mapValues(_.flatten).toMap

  /** Chart nodes that project nowhere: expected for grammatical abstractions, reported so callers
    * can decide whether an unmapped node is a finding.
    */
  def unmapped(graph: MentionGraph): Vector[ChartNodeRef] =
    graph.nodes.filterNot(byChartNode.contains)

  def validate(graph: NarrativeGraph, mentions: Option[MentionGraph]): Vector[ProjectionViolation] =
    val unknownSit = situations.zipWithIndex.collect {
      case (p, i) if !graph.situations.contains(SituationId.unsafe(p.target.value)) =>
        ProjectionViolation.UnknownSituation(p.target, s"situations/$i")
    }
    val unknownEnt = entities.zipWithIndex.collect {
      case (p, i) if !graph.entities.contains(EntityId.unsafe(p.target.value)) =>
        ProjectionViolation.UnknownEntity(p.target, s"entities/$i")
    }
    val multiDirect = byChartNode.toVector.sortBy(_._1.key).collect {
      case (src, ts) if ts.count(_._2 == ProjectionMode.DirectMention) > 1 =>
        ProjectionViolation.MultipleDirectMentions(
          src,
          ts.filter(_._2 == ProjectionMode.DirectMention).map(_._1).sorted
        )
    }
    val missingSources = mentions.toVector.flatMap { mg =>
      byChartNode.keys.toVector.sorted.collect {
        case src if !mg.contains(src) =>
          ProjectionViolation.SourceNotInMentionGraph(src, src.key)
      }
    }
    unknownSit ++ unknownEnt ++ multiDirect ++ missingSources

object ProjectionIndex:
  val empty: ProjectionIndex = ProjectionIndex(Vector.empty, Vector.empty)
