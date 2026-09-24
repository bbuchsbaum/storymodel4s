package storymodel4s.align

import storymodel4s.proposition.{GraphOrder, ProjectionRefusal, SemanticGraph, SemanticProjection}
import storymodel4s.recall.*

/** One participant as a content scorer may see it: role, whether it was specified, and every name
  * it goes by. No entity identifier and no position.
  *
  * Why not a case class: the names are normalized on first read, not at projection. The historical
  * shims project every leaf of a segment for every unit, and most rules read only an agent's and a
  * patient's names; computing all of them eagerly made Scala Native 3.7 times slower on the voyage
  * court. Equality is over role, specification and names.
  */
final class ParticipantContent private[align] (
    val role: SketchRole,
    val specified: Boolean,
    namesOf: => Set[String]
):
  lazy val names: Set[String] = namesOf

  override def equals(that: Any): Boolean = that match
    case o: ParticipantContent => role == o.role && specified == o.specified && names == o.names
    case _                     => false
  override def hashCode: Int = (role, specified, names).hashCode
  override def toString: String = s"ParticipantContent($role, $specified, $names)"

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
    participantsOf: => Vector[ParticipantContent],
    val polarity: PolarityTag,
    val modality: ModalityTag,
    val outcome: Option[String],
    val sensoryTerms: Vector[String],
    val lemmas: Set[String],
    graphOf: => Option[SemanticGraph[O]]
):
  /** The chart as a graph; projected on first read, since several scorers never read it. */
  lazy val graph: Option[SemanticGraph[O]] = graphOf

  /** Built on first read: several scorers never look at participants. */
  lazy val participants: Vector[ParticipantContent] = participantsOf

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
    participantsOf: => Vector[ParticipantContent],
    val context: ContextTag,
    val polarity: PolarityTag,
    val modality: ModalityTag,
    val outcome: Option[String],
    val lemmas: Set[String],
    val hasEvidence: Boolean,
    graphOf: => Option[SemanticGraph[O]],
    membersOf: => Members[O]
):
  /** The chart as a graph; projected on first read, since several scorers never read it. */
  lazy val graph: Option[SemanticGraph[O]] = graphOf

  /** Built on first read: several scorers never look at participants. */
  lazy val participants: Vector[ParticipantContent] = participantsOf

  /** Built on first read: only segment reductions and eligibility read members. */
  lazy val members: Members[O] = membersOf

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
    // Members are built only if a scorer reads them; each leaf is then projected once and placed in
    // both historical orders.
    def members = {
      val leaves = view.leavesUnder(node.ref).flatMap(view.node)
      val content = leaves.map(n => n.ref -> sourceNode(n)).toMap
      new Members[GraphOrder.Source](
        view.structuralMembers(node.ref).map(n => content(n.ref)),
        leaves.map(n => content(n.ref))
      )
    }
    (sourceUnit(unit), sourceNode(node, members), grain(view))

  /** Source-ordered unit content, for the historical per-pair shims. */
  private[align] def sourceUnit(unit: RecallUnit): UnitContent[GraphOrder.Source] =
    unitOf(unit, sorted = false, unit.evidence.map(ev => SemanticProjection.sourceOrder(ev.chart)))

  /** Source-ordered node content without members, for the per-pair shims that never read them. */
  private[align] def sourceNode(node: NodeSummary): TargetContent[GraphOrder.Source] =
    sourceNode(node, Members.empty.asInstanceOf[Members[GraphOrder.Source]])

  private def sourceNode(
      node: NodeSummary,
      members: => Members[GraphOrder.Source]
  ): TargetContent[GraphOrder.Source] =
    targetOf(
      node,
      sorted = false,
      node.evidence.map(ev => SemanticProjection.sourceOrder(ev.chart)),
      members
    )

  private def grain(view: SourceView): ContentGrain = new ContentGrain(view.maxLevel)

  private type Graph[O <: GraphOrder] =
    Option[storymodel4s.proposition.PropositionEvidence] => Either[
      ProjectionRefusal,
      Option[SemanticGraph[O]]
    ]

  private def order(ps: Vector[ParticipantContent], sorted: Boolean) =
    if sorted then ps.sorted else ps

  private def unitOf[O <: GraphOrder](
      unit: RecallUnit,
      sorted: Boolean,
      g: => Option[SemanticGraph[O]]
  ): UnitContent[O] =
    val s = unit.proposition
    new UnitContent[O](
      unit.function,
      s.predicate,
      order(s.participants.map(p => new ParticipantContent(p.role, p.specified, p.names)), sorted),
      s.polarity,
      s.modality,
      s.outcome,
      s.sensoryTerms,
      s.lemmas,
      g
    )

  private def targetOf[O <: GraphOrder](
      n: NodeSummary,
      sorted: Boolean,
      g: => Option[SemanticGraph[O]],
      members: => Members[O]
  ): TargetContent[O] =
    new TargetContent[O](
      n.level,
      n.predicate,
      order(n.participants.map(p => new ParticipantContent(p.role, true, p.names)), sorted),
      n.context,
      n.polarity,
      n.modality,
      n.outcome,
      n.lemmas,
      n.hasEvidence,
      g,
      members
    )

  private def projectUnit[O <: GraphOrder](
      unit: RecallUnit,
      sorted: Boolean,
      graph: Graph[O]
  ): Either[ProjectionRefusal, UnitContent[O]] =
    graph(unit.evidence).map(unitOf(unit, sorted, _))

  private def projectTarget[O <: GraphOrder](
      node: NodeSummary,
      view: SourceView,
      sorted: Boolean,
      graph: Graph[O]
  ): Either[ProjectionRefusal, TargetContent[O]] =
    val none = Members.empty.asInstanceOf[Members[O]]
    def leaf(n: NodeSummary, members: Members[O]) =
      graph(n.evidence).map(targetOf(n, sorted, _, members))
    def traverse(ns: Vector[NodeSummary]) =
      ns.foldLeft[Either[ProjectionRefusal, Vector[TargetContent[O]]]](Right(Vector.empty)) {
        (acc, n) => acc.flatMap(v => leaf(n, none).map(v :+ _))
      }
    for
      structural <- traverse(view.structuralMembers(node.ref))
      leaves <- traverse(view.leavesUnder(node.ref).flatMap(view.node))
      target <- leaf(node, new Members[O](structural, leaves))
    yield target
