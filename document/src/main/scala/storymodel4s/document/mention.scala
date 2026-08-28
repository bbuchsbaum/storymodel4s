package storymodel4s.document

import cats.Order
import storymodel4s.core.*
import storymodel4s.proposition.{Checked, ConceptId, PropositionChart}

/** A node of one sentence's chart, addressed globally: `(sentence, concept)`.
  *
  * Why: document composition is a disjoint union (design record §44). Local charts stay intact and
  * local concept identities become global references; nothing is merged destructively.
  */
final case class ChartNodeRef(sentence: SurfaceUnitId, concept: ConceptId):
  def key: String = s"${sentence.value}#${concept.value}"

object ChartNodeRef:
  given Order[ChartNodeRef] = Order.by(_.key)
  given Ordering[ChartNodeRef] = Order[ChartNodeRef].toOrdering

/** The mention graph `M = ⊔ᵢ Aᵢ`: every sentence's checked chart, keyed by its surface unit.
  *
  * Construction is a disjoint union: adding a chart for a sentence already present is an error
  * rather than a merge. Union is associative and the empty graph is its identity (§42.5).
  */
final case class MentionGraph private (charts: Map[SurfaceUnitId, PropositionChart[Checked]]):
  def sentences: Vector[SurfaceUnitId] = charts.keys.toVector.sorted
  def chart(sentence: SurfaceUnitId): Option[PropositionChart[Checked]] = charts.get(sentence)
  def size: Int = charts.size
  def isEmpty: Boolean = charts.isEmpty

  /** Every chart node, ordered by sentence then concept. */
  def nodes: Vector[ChartNodeRef] =
    sentences.flatMap(s => charts(s).conceptIds.toVector.sorted.map(ChartNodeRef(s, _)))

  def contains(ref: ChartNodeRef): Boolean =
    charts.get(ref.sentence).exists(_.concepts.contains(ref.concept))

  def concept(ref: ChartNodeRef) =
    charts.get(ref.sentence).flatMap(_.concepts.get(ref.concept))

  /** Disjoint union; fails on a shared sentence. */
  def ++(other: MentionGraph): Either[DomainError, MentionGraph] =
    val shared = charts.keySet.intersect(other.charts.keySet)
    if shared.isEmpty then Right(MentionGraph(charts ++ other.charts))
    else
      Left(
        DomainError.DuplicateId(
          "MentionGraph",
          shared.toVector.sorted.map(_.value).mkString(",")
        )
      )

  def add(
      sentence: SurfaceUnitId,
      chart: PropositionChart[Checked]
  ): Either[DomainError, MentionGraph] =
    if charts.contains(sentence) then Left(DomainError.DuplicateId("MentionGraph", sentence.value))
    else Right(MentionGraph(charts.updated(sentence, chart)))

object MentionGraph:
  val empty: MentionGraph = MentionGraph(Map.empty)

  def of(
      charts: Iterable[(SurfaceUnitId, PropositionChart[Checked])]
  ): Either[DomainError, MentionGraph] =
    charts.foldLeft[Either[DomainError, MentionGraph]](Right(empty)) { case (acc, (s, c)) =>
      acc.flatMap(_.add(s, c))
    }
