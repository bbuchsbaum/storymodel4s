package storymodel4s.align

import storymodel4s.core.{SegmentId, SituationId, SpanSet}
import storymodel4s.features.{Coverage, Estimate, ScoreEstimate, MissingReason}
import storymodel4s.proposition.PropositionEvidence
import storymodel4s.recall.{Lexical, ModalityTag, PolarityTag, SketchRole}

/** The proposition evidence available under a source node (ADR 0001 rev 3 §D4b). An atomic
  * situation contributes its own chart (at most one); a segment contributes the multiset of its
  * members' charts. A segment never receives a fabricated chart of its own.
  */
final case class SegmentEvidence(members: Vector[PropositionEvidence]):
  def isEmpty: Boolean = members.isEmpty
  def nonEmpty: Boolean = members.nonEmpty

object SegmentEvidence:
  val empty: SegmentEvidence = SegmentEvidence(Vector.empty)

/** How much of a candidate's structural surface is backed by charts: for a leaf `members = 1`; for
  * a segment `members` is the number of leaves under it and `membersWithEvidence` how many of them
  * carry a chart. Chart-based distances on a segment are computed over the covered leaves only and
  * reported with this coverage, never as if the segment were fully charted.
  */
final case class StructuralCoverage(level: Int, membersWithEvidence: Int, members: Int):
  def coverage: Coverage = Coverage.unsafe(members, membersWithEvidence)
  def fraction: Double =
    if members <= 0 then 0.0 else membersWithEvidence.toDouble / members.toDouble
  def isComplete: Boolean = members > 0 && membersWithEvidence == members
  def isEmpty: Boolean = membersWithEvidence == 0

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

  /** `a → b` when `b` is an immediate successor of `a` in narrated-world time: the Hasse cover
    * (transitive reduction) of accepted strict precedence, so "successor" means *next*, not merely
    * *later* (review #11). Reachability gives the closure.
    */
  case WorldTime

  /** `a → b` when `a` causes or enables `b`. */
  case Causal

  /** Symmetric-in-practice: nodes sharing a participant entity. */
  case EntityContinuity

  /** `parent → child` primary containment. */
  case Hierarchy

  /** Graded semantic neighbourhood (embedding or thematic), symmetric-in-practice. */
  case Semantic

  /** `a → b` for goal relations (motivates, intended-to-achieve, …). */
  case Goal

  /** `event → state` for state initiation/termination/maintenance. */
  case StateChange

  /** `mention-event → referred-event` for narrative reference (prospective, retrospective, …). */
  case Reference

/** Context kind of a source node, reduced to what alignment needs. */
enum ContextTag:
  case NarratedWorld, Speech, Belief, Desire, Intention, Hypothetical, Counterfactual, Memory,
    Imagination

/** A source participant as the aligner sees it: role plus every name it may be referred to by. */
final case class ParticipantSummary(role: SketchRole, label: String, aliases: Set[String]):
  def names: Set[String] = aliases.map(Lexical.lower) + Lexical.lower(label)

/** Everything the aligner needs to know about one source node.
  *
  * Contract: `level` is 0 for atomic situations and increases toward the root; `parent` is the
  * primary-containment parent; `discoursePosition` is the rank of the node's first mention among
  * all nodes at its level (0-based); `support` is exact evidence in the source text; `lemmas` are
  * content stems of the supporting text plus the predicate and participant labels; `importance` is
  * an injected salience weight used only for importance-weighted coverage, never for matching
  * (`Missing` importance excludes the node from importance-weighted coverage; it is never zero).
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
    /** Injected salience. Defaults to `Missing`: nobody measured this node's importance, and
      * `observed(1.0)` asserted MAXIMAL salience for every node at once - a claim, not a default.
      * With it Missing, importance-weighted coverage abstains until a caller supplies importances,
      * instead of silently duplicating uniform coverage under a second name.
      */
    importance: ScoreEstimate = Estimate.missing(MissingReason.AllMissing),
    evidence: Option[PropositionEvidence] = None
):
  def hasEvidence: Boolean = evidence.nonEmpty
  def byRole(role: SketchRole): Option[ParticipantSummary] = participants.find(_.role == role)
  def agent: Option[ParticipantSummary] = byRole(SketchRole.Agent)

  /** The patient-like participant: patient, else theme, else recipient/addressee. */
  def patient: Option[ParticipantSummary] =
    byRole(SketchRole.Patient)
      .orElse(byRole(SketchRole.Theme))
      .orElse(byRole(SketchRole.Beneficiary))
  def allNames: Set[String] = participants.flatMap(_.names).toSet
  def isLeaf: Boolean = level == 0

/** Minimal read-only view of a source story for alignment. `story.AlignmentSource` is bridged to
  * this trait by `align.bridge.StorySourceView`; tests use [[InMemorySourceView]].
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

  /** Number of alignable source nodes: the fixed `K` of localizability. */
  lazy val sourceNodeCount: Int = nodes.size

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

  /** Leaves under `ref` (the node itself when it is a leaf); memoized. */
  def leavesUnder(ref: SourceNodeRef): Vector[SourceNodeRef] =
    leavesIndex.getOrElse(ref, Vector.empty)

  /** Atomic members considered by structural reducers, in canonical reference order.
    *
    * Why: segment reduction receipts must retain the identity of every member they considered,
    * rather than exposing only an anonymous multiset of charts.
    */
  def structuralMembers(ref: SourceNodeRef): Vector[NodeSummary] =
    leavesUnder(ref).flatMap(node).sortBy(_.ref)

  /** The proposition evidence under `ref`: the node's own chart for a leaf, the multiset of member
    * charts for a segment (in canonical reference order). Never fabricates a segment chart.
    */
  def segmentEvidence(ref: SourceNodeRef): SegmentEvidence =
    SegmentEvidence(structuralMembers(ref).flatMap(_.evidence))

  /** Source-chart coverage of `ref`, independent of whether a distance provider answered.
    *
    * Why: chart availability and provider-observed coverage are distinct quantities; the latter is
    * recorded by each structural reduction receipt.
    */
  def structuralCoverage(ref: SourceNodeRef): StructuralCoverage =
    node(ref) match
      case Some(n) if n.isLeaf => StructuralCoverage(0, if n.hasEvidence then 1 else 0, 1)
      case Some(n)             =>
        val leaves = structuralMembers(ref)
        StructuralCoverage(n.level, leaves.count(_.hasEvidence), leaves.size)
      case None => StructuralCoverage(0, 0, 0)

  private lazy val leavesIndex: Map[SourceNodeRef, Vector[SourceNodeRef]] =
    nodes.map { n =>
      n.ref -> (if n.isLeaf then Vector(n.ref)
                else descendants(n.ref).filter(d => node(d).exists(_.isLeaf)))
    }.toMap

  /** Reachability closure per layer, computed lazily once per view (sorted, deterministic). */
  private val closures =
    scala.collection.mutable.Map.empty[RelationLayer, Map[SourceNodeRef, Set[SourceNodeRef]]]

  def reachabilityIndex(layer: RelationLayer): Map[SourceNodeRef, Set[SourceNodeRef]] =
    closures.synchronized {
      closures.getOrElseUpdate(
        layer, {
          val adj = adjacency(layer)
          adj.keys.toVector.sorted.map { start =>
            val seen = scala.collection.mutable.LinkedHashSet.empty[SourceNodeRef]
            val queue = scala.collection.mutable.Queue.empty[SourceNodeRef]
            adj.getOrElse(start, Map.empty).toVector.sortBy(_._1.key).foreach { case (n, w) =>
              if w > 0.0 then queue.enqueue(n)
            }
            while queue.nonEmpty do
              val cur = queue.dequeue()
              if !seen.contains(cur) then
                seen += cur
                adj.getOrElse(cur, Map.empty).toVector.sortBy(_._1.key).foreach { case (n, w) =>
                  if w > 0.0 && !seen.contains(n) then queue.enqueue(n)
                }
            start -> seen.toSet
          }.toMap
        }
      )
    }

  /** Transitive reachability in a layer. */
  def reachable(layer: RelationLayer, from: SourceNodeRef, to: SourceNodeRef): Boolean =
    reachabilityIndex(layer).getOrElse(from, Set.empty).contains(to)

  /** Relative discourse span of a node: `[start, end]` of its support hull in `[0, 1]`. */
  def relativeSpan(ref: SourceNodeRef): Option[(Double, Double)] =
    node(ref) match
      case Some(n) if textLength > 0 =>
        val s = n.support.minSpan
        Some((s.start.toDouble / textLength, s.endExclusive.toDouble / textLength))
      case _ => None

  /** Discourse position of a node normalized to `[0, 1]` by support midpoint. */
  def relativePosition(ref: SourceNodeRef): Double =
    relativeSpan(ref).map((a, b) => (a + b) / 2.0).getOrElse(0.0)

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
