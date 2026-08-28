package storymodel4s.align

import storymodel4s.core.{SegmentId, SituationId, SpanSet}
import storymodel4s.recall.{ModalityTag, PolarityTag, SketchRole}

/** An alignable source node: an atomic situation or a composite segment (scene, episode, root). */
enum SourceNodeRef:
  case Situation(id: SituationId)
  case Segment(id: SegmentId)

  /** Stable string key used for deterministic ordering and sparse maps. */
  def key: String = this match
    case Situation(id) => s"sit:${id.value}"
    case Segment(id)   => s"seg:${id.value}"

object SourceNodeRef:
  given Ordering[SourceNodeRef] = Ordering.by(_.key)

/** Typed relation layers the aligner reads from a source. Adjacency is directed and sparse. */
enum RelationLayer:
  /** `a → b` when `b` is narrated immediately after `a` at the same hierarchy level. */
  case DiscourseSuccession

  /** `a → b` when `a` precedes `b` in story-world time (accepted, context-scoped). */
  case WorldTime

  /** `a → b` when `a` causes or enables `b`. */
  case Causal

  /** Symmetric-in-practice: nodes sharing a participant entity. */
  case EntityContinuity

  /** `parent → child` primary containment. */
  case Hierarchy

  /** Graded semantic neighbourhood (embedding or thematic), symmetric-in-practice. */
  case Semantic

/** Context kind of a source node, reduced to what alignment needs. */
enum ContextTag:
  case NarratedWorld, Speech, Belief, Desire, Intention, Hypothetical, Counterfactual, Memory,
    Imagination

/** A source participant as the aligner sees it: role plus every name it may be referred to by. */
final case class ParticipantSummary(role: SketchRole, label: String, aliases: Set[String]):
  def names: Set[String] = aliases.map(_.toLowerCase) + label.toLowerCase

/** Everything the aligner needs to know about one source node.
  *
  * Contract: `level` is 0 for atomic situations and increases toward the root; `parent` is the
  * primary-containment parent; `discoursePosition` is the rank of the node's first mention among
  * all nodes at its level (0-based); `support` is exact evidence in the source text; `lemmas` are
  * content lemmas of the supporting text plus the predicate and participant labels; `importance` is
  * an injected salience weight used only for importance-weighted coverage, never for matching
  * (INTEGRATION: `importance` becomes a `storymodel4s.features.ScoreEstimate` with missingness).
  */
final case class NodeSummary(
    ref: SourceNodeRef,
    level: Int,
    parent: Option[SourceNodeRef],
    discoursePosition: Int,
    support: SpanSet,
    predicate: Option[String],
    participants: Vector[ParticipantSummary],
    context: ContextTag,
    polarity: PolarityTag,
    modality: ModalityTag,
    locations: Vector[String],
    lemmas: Set[String],
    outcome: Option[String] = None,
    cause: Option[String] = None,
    importance: Double = 1.0
):
  def byRole(role: SketchRole): Option[ParticipantSummary] = participants.find(_.role == role)
  def agent: Option[ParticipantSummary] = byRole(SketchRole.Agent)
  def patient: Option[ParticipantSummary] =
    byRole(SketchRole.Patient).orElse(byRole(SketchRole.Theme))
  def allNames: Set[String] = participants.flatMap(_.names).toSet
  def isLeaf: Boolean = level == 0

/** Minimal read-only view of a source story for alignment. `story.AlignmentSource` is bridged to
  * this trait at integration; tests use [[InMemorySourceView]].
  *
  * Contracts:
  *   - `nodes` lists every alignable node exactly once, at every hierarchy level.
  *   - `adjacency(layer)` returns sparse directed weights; absent pairs are 0. Weights are in
  *     `[0, 1]` and `> 0` means the relation holds (graded for `Semantic`).
  *   - `worldOrder` gives a total or partial rank by story-world time when the source knows it.
  *   - `textLength` is the canonical text length, the denominator for discourse positions.
  */
trait SourceView:
  def nodes: Vector[NodeSummary]
  def node(ref: SourceNodeRef): Option[NodeSummary]
  def adjacency(layer: RelationLayer): Map[SourceNodeRef, Map[SourceNodeRef, Double]]
  def worldOrder: Option[Map[SourceNodeRef, Int]]
  def textLength: Int

  lazy val maxLevel: Int = nodes.map(_.level).maxOption.getOrElse(0)
  lazy val leaves: Vector[NodeSummary] = nodes.filter(_.isLeaf)
  lazy val byLevel: Map[Int, Vector[NodeSummary]] = nodes.groupBy(_.level)

  def weight(layer: RelationLayer, a: SourceNodeRef, b: SourceNodeRef): Double =
    adjacency(layer).getOrElse(a, Map.empty).getOrElse(b, 0.0)

  def hasEdge(layer: RelationLayer, a: SourceNodeRef, b: SourceNodeRef): Boolean =
    weight(layer, a, b) > 0.0

  def ancestors(ref: SourceNodeRef): Vector[SourceNodeRef] =
    node(ref).flatMap(_.parent) match
      case Some(p) => p +: ancestors(p)
      case None    => Vector.empty

  def isAncestor(ancestor: SourceNodeRef, of: SourceNodeRef): Boolean =
    ancestors(of).contains(ancestor)

  lazy val childrenIndex: Map[SourceNodeRef, Vector[SourceNodeRef]] =
    nodes.flatMap(n => n.parent.map(p => p -> n.ref)).groupMap(_._1)(_._2)

  def descendants(ref: SourceNodeRef): Vector[SourceNodeRef] =
    childrenIndex.getOrElse(ref, Vector.empty).flatMap(c => c +: descendants(c))

  /** Leaves under `ref` (the node itself when it is a leaf). */
  def leavesUnder(ref: SourceNodeRef): Vector[SourceNodeRef] =
    if node(ref).exists(_.isLeaf) then Vector(ref)
    else descendants(ref).filter(d => node(d).exists(_.isLeaf))

  /** Transitive reachability in a layer (BFS over positive-weight edges). */
  def reachable(layer: RelationLayer, from: SourceNodeRef, to: SourceNodeRef): Boolean =
    val adj = adjacency(layer)
    val seen = scala.collection.mutable.HashSet.empty[SourceNodeRef]
    val queue = scala.collection.mutable.Queue(from)
    var found = false
    while queue.nonEmpty && !found do
      val cur = queue.dequeue()
      adj.getOrElse(cur, Map.empty).foreach { case (nxt, w) =>
        if w > 0.0 && !seen.contains(nxt) then
          if nxt == to then found = true
          seen += nxt
          queue.enqueue(nxt)
      }
    found

  /** Discourse position of a node normalized to `[0, 1]` by support midpoint. */
  def relativePosition(ref: SourceNodeRef): Double =
    node(ref) match
      case Some(n) if textLength > 0 =>
        val s = n.support.minSpan
        ((s.start + s.endExclusive) / 2.0) / textLength.toDouble
      case _ => 0.0

/** Simple in-memory `SourceView`. Hierarchy adjacency is derived from parents unless supplied. */
final case class InMemorySourceView(
    nodes: Vector[NodeSummary],
    edges: Map[RelationLayer, Vector[(SourceNodeRef, SourceNodeRef, Double)]],
    worldOrder: Option[Map[SourceNodeRef, Int]],
    textLength: Int
) extends SourceView:
  private lazy val index: Map[SourceNodeRef, NodeSummary] =
    nodes.iterator.map(n => n.ref -> n).toMap

  def node(ref: SourceNodeRef): Option[NodeSummary] = index.get(ref)

  private lazy val adjacencies: Map[RelationLayer, Map[SourceNodeRef, Map[SourceNodeRef, Double]]] =
    RelationLayer.values.toVector.map { layer =>
      val given_ = edges.getOrElse(layer, Vector.empty)
      val derived =
        if layer == RelationLayer.Hierarchy && given_.isEmpty then
          nodes.flatMap(n => n.parent.map(p => (p, n.ref, 1.0)))
        else given_
      layer -> derived.groupBy(_._1).view.mapValues(_.map(e => e._2 -> e._3).toMap).toMap
    }.toMap

  def adjacency(layer: RelationLayer): Map[SourceNodeRef, Map[SourceNodeRef, Double]] =
    adjacencies.getOrElse(layer, Map.empty)
