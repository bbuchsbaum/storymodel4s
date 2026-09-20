package storymodel4s.align

import storymodel4s.core.{Credence, SegmentId, SituationId, SpanSet, TypedSupport}
import storymodel4s.features.{Coverage, Estimate, MissingReason, ScoreEstimate}
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

/** Whether a source view supplies propositional content for its nodes at all.
  *
  * Why this is a type and not an inference from `predicate == None`: that absence reads two ways —
  * *this node has no predicate*, or *this view does not describe predicates* — and
  * [[FidelityFacets]] scores the first reading. A view that supplies none therefore made every
  * recall unit naming an actor or an action `Wrong` rather than `Unspecified`, and, because
  * `context` has no absent value, scored `Context` `Correct` for every asserted unit at once. Both
  * are claims about recall derived from a silence in the source.
  *
  * `Undeclared` is the default for the same reason [[ImportanceWeight.unmeasured]] is: a view that
  * says nothing must abstain rather than assert. A view that does describe propositions says so,
  * and then `predicate = None` means what it appears to mean.
  *
  * This governs reporting only. It is not a gate: [[ModeGate]] admissibility is unchanged, because
  * an undeclared view already yields no contradictions and so already admits `Faithful` — the
  * defect was never that the gate refused too little here, but that the facets asserted too much.
  */
enum PropositionalScope:
  /** The view describes predicates, participants, and context; absences are the node's own. */
  case Declared

  /** The view does not describe them; no facet over them may be scored. */
  case Undeclared(reason: MissingReason)

  def declares: Boolean = this match
    case Declared      => true
    case Undeclared(_) => false

object PropositionalScope:
  /** No caller said what this view describes, so it is read as describing nothing. */
  val unstated: PropositionalScope = Undeclared(MissingReason.ProviderAbstained)

/** A measured node-salience weight, or an explicit reason why salience was not measured.
  *
  * Why: importance conditions a published estimand, so a non-finite or negative observation must be
  * refused before it enters a [[NodeSummary]]. Missing and observed zero remain distinct: the
  * former says nobody supplied a weight; the latter is a measured zero-salience result.
  */
final class ImportanceWeight private (val estimate: ScoreEstimate):
  /** The measured weight, if a lawful observation exists. */
  def toOption: Option[Double] = estimate.toOption

  override def equals(other: Any): Boolean = other match
    case that: ImportanceWeight => estimate == that.estimate
    case _                      => false

  override def hashCode(): Int = estimate.hashCode()
  override def toString: String = s"ImportanceWeight($estimate)"

object ImportanceWeight:
  /** Validate an existing estimate without changing its credence or missing reason. */
  def from(estimate: ScoreEstimate): Either[AlignError, ImportanceWeight] =
    estimate match
      case Estimate.Observed(value, _) if value.isNaN || value.isInfinite =>
        Left(AlignError.MalformedRecord("importanceWeight", "observed value is not finite"))
      case Estimate.Observed(value, _) if value < 0.0 =>
        Left(AlignError.MalformedRecord("importanceWeight", "observed value is negative"))
      case _ => Right(new ImportanceWeight(estimate))

  /** Construct a measured importance while preserving any recorded credence. */
  def observed(
      value: Double,
      credence: Option[Credence] = None
  ): Either[AlignError, ImportanceWeight] =
    from(Estimate.Observed(value, credence))

  /** Construct an explicit absence; missing importance is never silently converted to zero. */
  def missing(reason: MissingReason): ImportanceWeight =
    new ImportanceWeight(Estimate.missing(reason))

  /** The default says that nobody measured this node's importance. */
  val unmeasured: ImportanceWeight = missing(MissingReason.AllMissing)

  /** Validate a source literal and throw on an invalid value. */
  def unsafe(estimate: ScoreEstimate): ImportanceWeight =
    from(estimate).fold(error => throw new IllegalArgumentException(error.message), identity)

/** Coordinates used by scoring, separate from physical source evidence. */
enum ScoringPosition:
  case CanonicalText(value: SpanSet)
  case LegacyAnnotationText(value: SpanSet)

  def spans: SpanSet = this match
    case CanonicalText(value)        => value
    case LegacyAnnotationText(value) => value

  def relativeTo(length: Int): Option[(Double, Double)] =
    if length > 0 then
      val span = spans.minSpan
      Some((span.start.toDouble / length, span.endExclusive.toDouble / length))
    else None

/** Everything the aligner needs to know about one source node.
  *
  * Contract: `level` is 0 for atomic situations and increases toward the root; `parent` is the
  * primary-containment parent; `discoursePosition` is the rank of the node's first mention among
  * all nodes at its level (0-based); `support` is exact typed physical evidence; `lemmas` are
  * content stems of the supporting text plus the predicate and participant labels; `importance` is
  * an injected salience weight used only for importance-weighted coverage, never for matching
  * (`Missing` importance excludes the node from importance-weighted coverage; it is never zero).
  *
  * Source compatibility: this is deliberately not a case class. Closing unchecked construction
  * removed `Product`, the generated `Mirror`/`fromProduct`, and generated `unapply` pattern
  * matching. Consumers should use the public reads and rebuild with `NodeSummary.apply` or the
  * explicit `copy`; both require a validated [[ImportanceWeight]].
  */
final class NodeSummary private (
    val ref: SourceNodeRef,
    val level: Int,
    val parent: Option[SourceNodeRef],
    val discoursePosition: Int,
    val support: TypedSupport,
    val scoringPosition: Option[ScoringPosition],
    val predicate: Option[String],
    val participants: Vector[ParticipantSummary],
    val context: ContextTag,
    val polarity: PolarityTag,
    val modality: ModalityTag,
    val locations: Vector[String],
    val lemmas: Set[String],
    val outcome: Option[String],
    val cause: Option[String],
    /** Injected salience. Defaults to `Missing`: nobody measured this node's importance, and
      * `observed(1.0)` asserted MAXIMAL salience for every node at once - a claim, not a default.
      * With it Missing, importance-weighted coverage abstains until a caller supplies importances,
      * instead of silently duplicating uniform coverage under a second name.
      */
    val importance: ImportanceWeight,
    val evidence: Option[PropositionEvidence],
    /** What this node's view says it describes. Defaults to [[PropositionalScope.unstated]] so a
      * caller that supplies no propositional content scores no facets over it.
      */
    val propositional: PropositionalScope
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

  /** Rebuild this node while preserving the validated importance boundary. */
  def copy(
      ref: SourceNodeRef = ref,
      level: Int = level,
      parent: Option[SourceNodeRef] = parent,
      discoursePosition: Int = discoursePosition,
      support: TypedSupport = support,
      scoringPosition: Option[ScoringPosition] = scoringPosition,
      predicate: Option[String] = predicate,
      participants: Vector[ParticipantSummary] = participants,
      context: ContextTag = context,
      polarity: PolarityTag = polarity,
      modality: ModalityTag = modality,
      locations: Vector[String] = locations,
      lemmas: Set[String] = lemmas,
      outcome: Option[String] = outcome,
      cause: Option[String] = cause,
      importance: ImportanceWeight = importance,
      evidence: Option[PropositionEvidence] = evidence,
      propositional: PropositionalScope = propositional
  ): NodeSummary =
    new NodeSummary(
      ref,
      level,
      parent,
      discoursePosition,
      support,
      scoringPosition,
      predicate,
      participants,
      context,
      polarity,
      modality,
      locations,
      lemmas,
      outcome,
      cause,
      importance,
      evidence,
      propositional
    )

  private def fields =
    (
      ref,
      level,
      parent,
      discoursePosition,
      support,
      scoringPosition,
      predicate,
      participants,
      context,
      polarity,
      modality,
      locations,
      lemmas,
      outcome,
      cause,
      importance,
      evidence,
      propositional
    )

  override def equals(other: Any): Boolean = other match
    case that: NodeSummary => fields == that.fields
    case _                 => false

  override def hashCode(): Int = fields.hashCode()
  override def toString: String = s"NodeSummary$fields"

object NodeSummary:
  /** Construct a source node whose importance has already passed [[ImportanceWeight]]. */
  def apply(
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
      importance: ImportanceWeight = ImportanceWeight.unmeasured,
      evidence: Option[PropositionEvidence] = None,
      propositional: PropositionalScope = PropositionalScope.unstated
  ): NodeSummary =
    typed(
      ref,
      level,
      parent,
      discoursePosition,
      TypedSupport.Text(support),
      Some(ScoringPosition.CanonicalText(support)),
      predicate,
      participants,
      context,
      polarity,
      modality,
      locations,
      lemmas,
      outcome,
      cause,
      importance,
      evidence,
      propositional
    )

  def typed(
      ref: SourceNodeRef,
      level: Int,
      parent: Option[SourceNodeRef],
      discoursePosition: Int,
      support: TypedSupport,
      scoringPosition: Option[ScoringPosition],
      predicate: Option[String],
      participants: Vector[ParticipantSummary],
      context: ContextTag,
      polarity: PolarityTag,
      modality: ModalityTag,
      locations: Vector[String],
      lemmas: Set[String],
      outcome: Option[String] = None,
      cause: Option[String] = None,
      importance: ImportanceWeight = ImportanceWeight.unmeasured,
      evidence: Option[PropositionEvidence] = None,
      propositional: PropositionalScope = PropositionalScope.unstated
  ): NodeSummary =
    new NodeSummary(
      ref,
      level,
      parent,
      discoursePosition,
      support,
      scoringPosition,
      predicate,
      participants,
      context,
      polarity,
      modality,
      locations,
      lemmas,
      outcome,
      cause,
      importance,
      evidence,
      propositional
    )

/** Minimal read-only view of a source story for alignment. `story.AlignmentSource` is bridged to
  * this trait by `align.bridge.StorySourceView`; tests use [[InMemorySourceView]].
  *
  * Contracts:
  *   - `nodes` lists every alignable node exactly once, at every hierarchy level.
  *   - `adjacency(layer)` returns sparse directed weights; absent pairs are 0. Weights are in
  *     `[0, 1]` and `> 0` means the relation holds (graded for `Semantic`).
  *   - `worldOrder` gives a total or partial rank by story-world time when the source knows it.
  *   - `scoringLength` is the declared feature-coordinate denominator, not a media extent.
  * Every exposed ref must resolve through node. A missing node or scoring feature remains None
  * through measuredPosition; transition features and metrics retain that absence.
  */
trait SourceView:
  def nodes: Vector[NodeSummary]
  def node(ref: SourceNodeRef): Option[NodeSummary]
  def adjacency(layer: RelationLayer): Map[SourceNodeRef, Map[SourceNodeRef, Double]]
  def worldOrder: Option[Map[SourceNodeRef, Int]]
  def scoringLength: Int

  /** Only this exact subset has the historical text-only HSMM v3 representation. */
  final def textWireCompatible: Boolean = nodes.forall { node =>
    (node.support, node.scoringPosition) match
      case (TypedSupport.Text(spans), Some(ScoringPosition.CanonicalText(position))) =>
        spans == position
      case _ => false
  }

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

  /** Relative span of the declared scoring feature; missing input remains absent. */
  def relativeSpan(ref: SourceNodeRef): Option[(Double, Double)] =
    node(ref).flatMap(_.scoringPosition).flatMap(_.relativeTo(scoringLength))

  /** Midpoint of the declared scoring feature, never substituted with a physical coordinate. */
  def measuredPosition(ref: SourceNodeRef): Option[Double] =
    relativeSpan(ref).map((a, b) => (a + b) / 2.0)

/** Simple in-memory `SourceView`. Hierarchy adjacency is derived from parents unless supplied. */
final case class InMemorySourceView(
    nodes: Vector[NodeSummary],
    edges: Map[RelationLayer, Vector[(SourceNodeRef, SourceNodeRef, Double)]],
    worldOrder: Option[Map[SourceNodeRef, Int]],
    scoringLength: Int
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
