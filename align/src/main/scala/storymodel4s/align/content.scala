package storymodel4s.align

import storymodel4s.proposition.{GraphOrder, ProjectionRefusal, SemanticGraph, SemanticProjection}
import storymodel4s.recall.*

/** One participant as a content scorer may see it: role, whether it was specified, and every name
  * it goes by. No entity identifier and no position.
  *
  * Why a plain case class: it is only content. All field combinations are lawful, and a scorer that
  * builds one learns nothing it was not given (the rule 8 Cartesian-product test).
  */
final case class ParticipantContent(role: SketchRole, specified: Boolean, names: Set[String])

object ParticipantContent:
  /** Content order: by role, then by sorted names. Never text or storage order. */
  given Ordering[ParticipantContent] =
    Ordering.by(p => (p.role.ordinal, p.names.toVector.sorted.mkString("\u0000"), p.specified))

/** The recall side of a local scoring question, projected under order policy `O`.
  *
  * Why it exists: a [[RecallUnit]] carries its id, ordinal, span, text, grounding and cue spans,
  * all of which can encode position. This carries only what the deterministic scorers read (ADR
  * 0019, S2): discourse function, the sketch's content fields, and the chart as a
  * [[SemanticGraph]]. `graph` keeps a missing chart (`None`) distinct from an empty one.
  *
  * Under `Canonical` participants are in content order. Under `Source` they keep the sketch's
  * order, for the historical shims only.
  */
final class UnitContent[O <: GraphOrder] private[align] (
    val function: DiscourseFunction,
    val predicate: Option[String],
    val participants: Vector[ParticipantContent],
    val polarity: PolarityTag,
    val modality: ModalityTag,
    val outcome: Option[String],
    val sensoryTerms: Vector[String],
    val lemmas: Set[String],
    val graph: Option[SemanticGraph[O]]
):
  def byRole(role: SketchRole): Option[ParticipantContent] = participants.find(_.role == role)

  private def parts: Product =
    (
      function,
      predicate,
      participants,
      polarity,
      modality,
      outcome,
      sensoryTerms,
      lemmas,
      graph
    )

  override def equals(that: Any): Boolean = that match
    case o: UnitContent[?] => parts == o.parts
    case _                 => false
  override def hashCode: Int = parts.hashCode
  override def toString: String = s"UnitContent($function, ${predicate.getOrElse("-")})"

/** The source side of a local scoring question: one node's content, projected under `O`.
  *
  * Why it exists: a [[NodeSummary]] carries its reference, parent, discourse and scoring positions,
  * support, importance and provenance. This carries level, the content fields the scorers read, the
  * chart as a [[SemanticGraph]], and the node's leaves as [[Members]], and nothing else.
  */
final class TargetContent[O <: GraphOrder] private[align] (
    val level: Int,
    val predicate: Option[String],
    val participants: Vector[ParticipantContent],
    val context: ContextTag,
    val polarity: PolarityTag,
    val modality: ModalityTag,
    val outcome: Option[String],
    val lemmas: Set[String],
    val hasEvidence: Boolean,
    val graph: Option[SemanticGraph[O]],
    val members: Members[O]
):
  def isLeaf: Boolean = level == 0
  def byRole(role: SketchRole): Option[ParticipantContent] = participants.find(_.role == role)

  private def parts: Product =
    (
      level,
      predicate,
      participants,
      context,
      polarity,
      modality,
      outcome,
      lemmas,
      hasEvidence,
      graph,
      members
    )

  override def equals(that: Any): Boolean = that match
    case o: TargetContent[?] => parts == o.parts
    case _                   => false
  override def hashCode: Int = parts.hashCode
  override def toString: String = s"TargetContent(level $level, ${predicate.getOrElse("-")})"

/** The leaves under a target, which a scorer may use only through reductions.
  *
  * Why no iteration: the historical member order is the source's reference order, and incidental
  * order must never reach a strict scorer (ADR 0019 S2, "Containment"). An unordered collection is
  * not the guarantee. The reductions are: each is a function of the members' multiset. Under
  * `Source`, the two historical orders (reference order for structural reduction, storage order for
  * the mode gate) are kept privately, so the historical shims reproduce their output bit for bit.
  */
final class Members[O <: GraphOrder] private[align] (
    private[align] val structural: Vector[TargetContent[O]],
    private[align] val leaves: Vector[TargetContent[O]]
):
  /** How many leaves there are. */
  def count: Int = structural.size

  /** How many leaves carry a chart. */
  def withEvidence: Int = structural.count(_.hasEvidence)

  def exists(p: TargetContent[O] => Boolean): Boolean = structural.exists(p)

  /** The least value of `f` over the leaves where it is defined. */
  def minOf(f: TargetContent[O] => Option[Double]): Option[Double] =
    structural.flatMap(f).minOption

  // Equality is over the multiset, so it holds regardless of either private order.
  private def bag: Map[TargetContent[O], Int] = structural.groupMapReduce(identity)(_ => 1)(_ + _)

  override def equals(that: Any): Boolean = that match
    case o: Members[?] => bag == o.asInstanceOf[Members[O]].bag
    case _             => false
  override def hashCode: Int = bag.hashCode
  override def toString: String = s"Members($count)"

object Members:
  private[align] val empty: Members[Nothing] = new Members(Vector.empty, Vector.empty)

/** Scale of the source view the scorer is working in: its deepest level. */
final class ContentGrain private[align] (val maxLevel: Int):
  override def equals(that: Any): Boolean = that match
    case o: ContentGrain => maxLevel == o.maxLevel
    case _               => false
  override def hashCode: Int = maxLevel.hashCode
  override def toString: String = s"ContentGrain($maxLevel)"

/** Projection from coordinate-bearing inputs to content, under an order policy.
  *
  * `canonical` is the strict path: content order throughout, and a refusal instead of an id-order
  * fallback when a chart cannot be labelled canonically. `source` is for the historical shims only
  * and reproduces storage and reference order exactly.
  */
object ContentProjection:

  def canonical(
      unit: RecallUnit,
      node: NodeSummary,
      view: SourceView
  ): Either[
    ProjectionRefusal,
    (UnitContent[GraphOrder.Canonical], TargetContent[GraphOrder.Canonical], ContentGrain)
  ] =
    val canon = (e: Option[storymodel4s.proposition.PropositionEvidence]) =>
      e match
        case None     => Right(None)
        case Some(ev) => SemanticProjection.canonical(ev.chart).map(Some(_))
    for
      u <- projectUnit(unit, sorted = true, canon)
      t <- projectTarget(node, view, sorted = true, canon)
    yield (u, t, grain(view))

  private[align] def source(
      unit: RecallUnit,
      node: NodeSummary,
      view: SourceView
  ): (UnitContent[GraphOrder.Source], TargetContent[GraphOrder.Source], ContentGrain) =
    val src = (e: Option[storymodel4s.proposition.PropositionEvidence]) =>
      Right(e.map(ev => SemanticProjection.sourceOrder(ev.chart)))
    val out =
      for
        u <- projectUnit[GraphOrder.Source](unit, sorted = false, src)
        t <- projectTarget[GraphOrder.Source](node, view, sorted = false, src)
      yield (u, t, grain(view))
    out.fold(r => throw new IllegalStateException(s"source projection cannot refuse: $r"), identity)

  private def grain(view: SourceView): ContentGrain = new ContentGrain(view.maxLevel)

  private type Graph[O <: GraphOrder] =
    Option[storymodel4s.proposition.PropositionEvidence] => Either[
      ProjectionRefusal,
      Option[SemanticGraph[O]]
    ]

  private def order(ps: Vector[ParticipantContent], sorted: Boolean) =
    if sorted then ps.sorted else ps

  private def projectUnit[O <: GraphOrder](
      unit: RecallUnit,
      sorted: Boolean,
      graph: Graph[O]
  ): Either[ProjectionRefusal, UnitContent[O]] =
    val s = unit.proposition
    graph(unit.evidence).map { g =>
      new UnitContent[O](
        unit.function,
        s.predicate,
        order(s.participants.map(p => ParticipantContent(p.role, p.specified, p.names)), sorted),
        s.polarity,
        s.modality,
        s.outcome,
        s.sensoryTerms,
        s.lemmas,
        g
      )
    }

  private def projectTarget[O <: GraphOrder](
      node: NodeSummary,
      view: SourceView,
      sorted: Boolean,
      graph: Graph[O]
  ): Either[ProjectionRefusal, TargetContent[O]] =
    def leaf(n: NodeSummary, members: Members[O]): Either[ProjectionRefusal, TargetContent[O]] =
      graph(n.evidence).map { g =>
        new TargetContent[O](
          n.level,
          n.predicate,
          order(
            n.participants.map(p => ParticipantContent(p.role, specified = true, p.names)),
            sorted
          ),
          n.context,
          n.polarity,
          n.modality,
          n.outcome,
          n.lemmas,
          n.hasEvidence,
          g,
          members
        )
      }
    val none = Members.empty.asInstanceOf[Members[O]]
    def traverse(ns: Vector[NodeSummary]) =
      ns.foldLeft[Either[ProjectionRefusal, Vector[TargetContent[O]]]](Right(Vector.empty)) {
        (acc, n) => acc.flatMap(v => leaf(n, none).map(v :+ _))
      }
    for
      structural <- traverse(view.structuralMembers(node.ref))
      leaves <- traverse(view.leavesUnder(node.ref).flatMap(view.node))
      target <- leaf(node, new Members[O](structural, leaves))
    yield target
