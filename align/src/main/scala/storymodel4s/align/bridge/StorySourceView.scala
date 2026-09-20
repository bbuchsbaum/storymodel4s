package storymodel4s.align.bridge

import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.proposition.ParticipantRole
import storymodel4s.recall.{Lexical, ModalityTag, PolarityTag, SketchRole}
import storymodel4s.story.{
  AlignmentSource,
  ContextKind,
  ModelStatus,
  Modality,
  NarrativeNodeId,
  Polarity,
  PropositionEvidenceSource,
  TextModel,
  RelationLayer as StoryLayer
}

/** Bridges the story-side `AlignmentSource` contract (design record §31.7) to the aligner's
  * `SourceView`.
  *
  * Why a bridge rather than one trait: `AlignmentSource` speaks narrative types (contexts,
  * predicates, typed roles) and must stay stable for scientific consumers; `SourceView` speaks the
  * reduced vocabulary the aligner adjudicates on (tags, lemmas, sparse layers). Keeping them apart
  * lets either side evolve without changing the other's meaning.
  *
  * Layer construction:
  *   - `DiscourseSuccession`: story succession among situations, plus succession among segments of
  *     the same level ordered by discourse position;
  *   - `WorldTime`: the Hasse cover (transitive reduction) of the story's strict-precedence edges
  *     restricted to situations in the `NarratedWorld` context, so an edge means "next", not
  *     "later" (review #11); the closure is available through `reachable`;
  *   - `Causal`, `Goal`, `StateChange`, `Reference`: the story matrices as given;
  *   - `EntityContinuity`: built per situation from the shared-entity index with a bounded fan-out
  *     per entity (`maxFanout` nearest situations in discourse), weighted by Jaccard overlap of
  *     participant sets, so hubs do not produce quadratic pair sets (review #12);
  *   - `Hierarchy`: parent → member from *primary* containment only, consistent with `ancestors`
  *     (review #13); auxiliary arcs are not exposed as a layer;
  *   - `Semantic`: empty unless injected.
  *
  * Lexical normalization is the story-agnostic `recall.Lexical` (review #6). Nodes without source
  * support are not fabricated at position 0: they are omitted and listed in `dropped`.
  */
final class StorySourceView private (
    source: AlignmentSource,
    model: TextModel[?],
    semantic: Map[SourceNodeRef, Map[SourceNodeRef, Double]],
    maxFanout: Int,
    evidence: PropositionEvidenceSource
) extends SourceView:

  private val text: String = model.source.canonicalText

  private def toRef(n: NarrativeNodeId): SourceNodeRef = n match
    case NarrativeNodeId.Situation(id) => SourceNodeRef.Situation(id)
    case NarrativeNodeId.Segment(id)   => SourceNodeRef.Segment(id)

  private val allNarrative: Vector[NarrativeNodeId] = source.allNodes

  /** Nodes omitted from the view because they lack source support, with the reason. */
  val dropped: Vector[(NarrativeNodeId, String)] =
    allNarrative.collect {
      case n if source.sourceSupport(n).isEmpty => (n, "no source support")
    }
  private val all: Vector[NarrativeNodeId] =
    allNarrative.filter(n => source.sourceSupport(n).nonEmpty)

  /** Rank of each node among the nodes of its own level, by absolute discourse position. */
  private val levelRank: Map[NarrativeNodeId, Int] =
    all
      .groupBy(source.levelOf)
      .values
      .flatMap { ns =>
        ns.sortBy(n => (source.discoursePosition(n).getOrElse(Int.MaxValue), n.render)).zipWithIndex
      }
      .toMap

  private def summarize(n: NarrativeNodeId, support: SpanSet): NodeSummary =
    val participants = source.participantsOf(n).map { p =>
      val tokens = Lexical.words(p.label).filterNot(Lexical.stopwords.contains).toSet
      ParticipantSummary(StorySourceView.sketchRole(p.role), p.label, tokens + p.label)
    }
    // Multiword lemmas ("feel-sick") compare by their head so predicate identity is not defeated
    // by compounding; the full lemma still contributes to the lemma set.
    val fullPredicate = source.predicateOf(n).map(p => Lexical.lower(p.lemma))
    val predicate = fullPredicate.flatMap(_.split('-').headOption.filter(_.nonEmpty))
    val description = source.descriptionOf(n).getOrElse("")
    val supportText = support.spans.toVector.flatMap(sp => sp.slice(text).toOption)
    val lemmas =
      (supportText.flatMap(Lexical.stems) ++ Lexical.stems(description) ++
        predicate.toVector.map(Lexical.stem) ++
        fullPredicate.toVector.flatMap(fp => Lexical.stems(fp.replace('-', ' '))) ++
        participants.flatMap(p => Lexical.stems(p.label))).toSet
    val locations =
      participants.collect {
        case p if p.role == SketchRole.Location || p.role == SketchRole.Destination => p.label
      }
    NodeSummary(
      toRef(n),
      source.levelOf(n),
      source.parentOf(n).map(SourceNodeRef.Segment(_)),
      levelRank.getOrElse(n, 0),
      support,
      predicate,
      participants,
      source.contextOf(n).map(StorySourceView.contextTag).getOrElse(ContextTag.NarratedWorld),
      source.polarityOf(n).map(StorySourceView.polarityTag).getOrElse(PolarityTag.Unknown),
      source.modalityOf(n).map(StorySourceView.modalityTag).getOrElse(ModalityTag.Unknown),
      locations,
      lemmas,
      evidence = n match
        case NarrativeNodeId.Situation(_) => evidence.propositionEvidenceOf(n)
        case NarrativeNodeId.Segment(_)   => None // segments never get a fabricated chart
      ,
      // This view reads predicates, participants and contexts off a compiled narrative model, so
      // an absent predicate here is the node's own absence and the facets may be scored on it.
      propositional = PropositionalScope.Declared
    )

  val nodes: Vector[NodeSummary] =
    all.flatMap(n => source.sourceSupport(n).map(s => summarize(n, s)))
  private val index: Map[SourceNodeRef, NodeSummary] = nodes.iterator.map(n => n.ref -> n).toMap
  def node(ref: SourceNodeRef): Option[NodeSummary] = index.get(ref)
  val textLength: Int = text.length

  private val known: Set[NarrativeNodeId] = all.toSet

  private def sparse(
      rel: Map[(NarrativeNodeId, NarrativeNodeId), Double]
  ): Map[SourceNodeRef, Map[SourceNodeRef, Double]] =
    rel.toVector
      .filter { case ((a, b), w) => w > 0.0 && known.contains(a) && known.contains(b) }
      .groupBy { case ((a, _), _) => toRef(a) }
      .view
      .mapValues(_.map { case ((_, b), w) => toRef(b) -> w }.toMap)
      .toMap

  private val narratedWorldSituations: Set[NarrativeNodeId] =
    all.filter(n => source.contextOf(n).contains(ContextKind.NarratedWorld)).toSet

  /** Direct strict-precedence edges among narrated-world situations. */
  private lazy val worldDirect: Vector[(SourceNodeRef, SourceNodeRef)] =
    source
      .relationMatrix(StoryLayer.WorldTime)
      .collect {
        case ((a, b), w)
            if w > 0.0 && narratedWorldSituations.contains(a) &&
              narratedWorldSituations.contains(b) =>
          (toRef(a), toRef(b))
      }
      .toVector
      .distinct
      .sortBy((a, b) => (a.key, b.key))

  /** Transitive reduction: keep `a → b` only when no other path from `a` reaches `b`. */
  private lazy val worldTimeCover: Map[SourceNodeRef, Map[SourceNodeRef, Double]] =
    val out = worldDirect.groupMap(_._1)(_._2)
    val closure: Map[SourceNodeRef, Set[SourceNodeRef]] =
      out.keys.toVector.sorted.map { start =>
        val seen = scala.collection.mutable.LinkedHashSet.empty[SourceNodeRef]
        val queue = scala.collection.mutable.Queue(out.getOrElse(start, Vector.empty)*)
        while queue.nonEmpty do
          val n = queue.dequeue()
          if !seen.contains(n) then
            seen += n
            queue.enqueueAll(out.getOrElse(n, Vector.empty))
        start -> seen.toSet
      }.toMap
    val kept = worldDirect.filter { case (a, b) =>
      !out
        .getOrElse(a, Vector.empty)
        .exists(c => c != b && closure.getOrElse(c, Set.empty).contains(b))
    }
    kept.groupBy(_._1).view.mapValues(_.map(_._2 -> 1.0).toMap).toMap

  private lazy val discourseSuccession: Map[SourceNodeRef, Map[SourceNodeRef, Double]] =
    val situations = sparse(source.relationMatrix(StoryLayer.DiscourseSuccession))
    val segments = all
      .filter(n => source.levelOf(n) > 0)
      .groupBy(source.levelOf)
      .values
      .flatMap { ns =>
        val ordered = ns.sortBy(levelRank)
        ordered.zip(ordered.drop(1)).map((a, b) => toRef(a) -> Map(toRef(b) -> 1.0))
      }
      .toMap
    situations ++ segments

  /** Primary containment only: parent → member. */
  private lazy val hierarchy: Map[SourceNodeRef, Map[SourceNodeRef, Double]] =
    nodes
      .flatMap(n => n.parent.map(p => (p, n.ref)))
      .groupBy(_._1)
      .view
      .mapValues(_.map(_._2 -> 1.0).toMap)
      .toMap

  private lazy val causal = sparse(source.relationMatrix(StoryLayer.Causal))
  private lazy val goal = sparse(source.relationMatrix(StoryLayer.Goal))
  private lazy val stateChange = sparse(source.relationMatrix(StoryLayer.StateChange))
  private lazy val reference = sparse(source.relationMatrix(StoryLayer.Reference))

  /** Bounded-fan-out entity continuity among situations sharing a participant. */
  private lazy val entityContinuity: Map[SourceNodeRef, Map[SourceNodeRef, Double]] =
    val situations = all.filter(n => source.levelOf(n) == 0)
    val entitiesOf: Map[NarrativeNodeId, Set[EntityId]] =
      situations.map(n => n -> source.participantsOf(n).map(_.entity).toSet).toMap
    val byEntity: Map[EntityId, Vector[NarrativeNodeId]] =
      situations
        .flatMap(n => entitiesOf(n).toVector.map(e => (e, n)))
        .groupMap(_._1)(_._2)
        .view
        .mapValues(_.sortBy(n => (source.discoursePosition(n).getOrElse(Int.MaxValue), n.render)))
        .toMap
    val pairs = scala.collection.mutable.LinkedHashSet.empty[(NarrativeNodeId, NarrativeNodeId)]
    byEntity.toVector.sortBy(_._1.value).foreach { case (_, sits) =>
      sits.indices.foreach { i =>
        val a = sits(i)
        // nearest neighbours in discourse order on either side, up to maxFanout total
        val neighbours =
          (sits.slice(i + 1, i + 1 + maxFanout) ++ sits.slice(math.max(0, i - maxFanout), i))
            .sortBy(n =>
              math.abs(
                source.discoursePosition(n).getOrElse(0) -
                  source.discoursePosition(a).getOrElse(0)
              )
            )
            .take(maxFanout)
        neighbours.foreach { b =>
          pairs += ((a, b))
          pairs += ((b, a))
        }
      }
    }
    pairs.toVector
      .map { case (a, b) =>
        val ea = entitiesOf(a)
        val eb = entitiesOf(b)
        val w =
          if ea.union(eb).isEmpty then 0.0 else ea.intersect(eb).size.toDouble / ea.union(eb).size
        (toRef(a), toRef(b), w)
      }
      .filter(_._3 > 0.0)
      .groupBy(_._1)
      .view
      .mapValues(_.map(e => e._2 -> e._3).toMap)
      .toMap

  def adjacency(layer: RelationLayer): Map[SourceNodeRef, Map[SourceNodeRef, Double]] =
    layer match
      case RelationLayer.DiscourseSuccession => discourseSuccession
      case RelationLayer.WorldTime           => worldTimeCover
      case RelationLayer.Causal              => causal
      case RelationLayer.EntityContinuity    => entityContinuity
      case RelationLayer.Hierarchy           => hierarchy
      case RelationLayer.Semantic            => semantic
      case RelationLayer.Goal                => goal
      case RelationLayer.StateChange         => stateChange
      case RelationLayer.Reference           => reference

  /** A topological rank of narrated-world situations by strict precedence (Kahn's algorithm), ties
    * broken by discourse position; segments take the minimum rank of their leaves. `None` when the
    * precedence graph is cyclic or empty.
    */
  lazy val worldOrder: Option[Map[SourceNodeRef, Int]] =
    val situationRefs = narratedWorldSituations.toVector.map(toRef).sortBy(_.key)
    if situationRefs.isEmpty then None
    else
      val out = worldDirect.groupMap(_._1)(_._2)
      val indegree = scala.collection.mutable.Map(situationRefs.map(_ -> 0)*)
      worldDirect.foreach { case (_, b) => indegree(b) = indegree(b) + 1 }
      def position(r: SourceNodeRef) = index.get(r).map(_.discoursePosition).getOrElse(0)
      given Ordering[SourceNodeRef] =
        Ordering.by[SourceNodeRef, (Int, String)](r => (position(r), r.key)).reverse
      val ready = scala.collection.mutable.PriorityQueue.empty[SourceNodeRef]
      situationRefs.filter(indegree(_) == 0).foreach(ready.enqueue(_))
      val ranks = scala.collection.mutable.LinkedHashMap.empty[SourceNodeRef, Int]
      while ready.nonEmpty do
        val r = ready.dequeue()
        ranks(r) = ranks.size
        out.getOrElse(r, Vector.empty).foreach { b =>
          indegree(b) = indegree(b) - 1
          if indegree(b) == 0 then ready.enqueue(b)
        }
      if ranks.size != situationRefs.size then None
      else
        val segmentRanks = nodes.filter(!_.isLeaf).flatMap { n =>
          leavesUnder(n.ref).flatMap(ranks.get).minOption.map(n.ref -> _)
        }
        Some(ranks.toMap ++ segmentRanks)

object StorySourceView:
  /** Default bound on entity-continuity fan-out per situation per entity. */
  val DefaultMaxFanout: Int = 8

  def apply(
      source: AlignmentSource,
      model: TextModel[ModelStatus.Validated],
      semantic: Map[SourceNodeRef, Map[SourceNodeRef, Double]] = Map.empty,
      maxFanout: Int = DefaultMaxFanout,
      evidence: PropositionEvidenceSource = PropositionEvidenceSource.none
  ): StorySourceView =
    new StorySourceView(source, model, semantic, math.max(1, maxFanout), evidence)

  def validated(
      model: TextModel[ModelStatus.Validated],
      evidence: PropositionEvidenceSource = PropositionEvidenceSource.none
  ): StorySourceView =
    new StorySourceView(AlignmentSource(model.model), model, Map.empty, DefaultMaxFanout, evidence)

  def adjudicated(
      model: TextModel[ModelStatus.Adjudicated],
      evidence: PropositionEvidenceSource = PropositionEvidenceSource.none
  ): StorySourceView =
    new StorySourceView(
      AlignmentSource.adjudicated(model.model),
      model,
      Map.empty,
      DefaultMaxFanout,
      evidence
    )

  def sketchRole(role: ParticipantRole): SketchRole = role match
    case ParticipantRole.Agent         => SketchRole.Agent
    case ParticipantRole.Patient       => SketchRole.Patient
    case ParticipantRole.Theme         => SketchRole.Theme
    case ParticipantRole.Experiencer   => SketchRole.Experiencer
    case ParticipantRole.Location      => SketchRole.Location
    case ParticipantRole.Destination   => SketchRole.Destination
    case ParticipantRole.Source        => SketchRole.Source
    case ParticipantRole.Instrument    => SketchRole.Instrument
    case ParticipantRole.Time          => SketchRole.Time
    case ParticipantRole.Stimulus      => SketchRole.Other("stimulus")
    case ParticipantRole.Beneficiary   => SketchRole.Beneficiary
    case ParticipantRole.Manner        => SketchRole.Other("manner")
    case ParticipantRole.Cause         => SketchRole.Other("cause")
    case ParticipantRole.Result        => SketchRole.Other("result")
    case ParticipantRole.Custom(ns, l) => SketchRole.Other(s"$ns:$l")

  def contextTag(kind: ContextKind): ContextTag = kind match
    case ContextKind.NarratedWorld  => ContextTag.NarratedWorld
    case ContextKind.Speech(_)      => ContextTag.Speech
    case ContextKind.Belief(_)      => ContextTag.Belief
    case ContextKind.Desire(_)      => ContextTag.Desire
    case ContextKind.Intention(_)   => ContextTag.Intention
    case ContextKind.Hypothetical   => ContextTag.Hypothetical
    case ContextKind.Counterfactual => ContextTag.Counterfactual
    case ContextKind.Memory(_)      => ContextTag.Memory
    case ContextKind.Imagination(_) => ContextTag.Imagination

  def polarityTag(p: Polarity): PolarityTag = p match
    case Polarity.Positive => PolarityTag.Positive
    case Polarity.Negative => PolarityTag.Negative
    case Polarity.Unknown  => PolarityTag.Unknown

  def modalityTag(m: Modality): ModalityTag = m match
    case Modality.Asserted       => ModalityTag.Asserted
    case Modality.Necessary      => ModalityTag.Asserted
    case Modality.Possible       => ModalityTag.Possible
    case Modality.Probable       => ModalityTag.Possible
    case Modality.Intended       => ModalityTag.Intended
    case Modality.Desired        => ModalityTag.Desired
    case Modality.Counterfactual => ModalityTag.Counterfactual
    case Modality.Reported       => ModalityTag.Reported
    case Modality.Unknown        => ModalityTag.Unknown

  /** Story-agnostic content stems of a text (kept for callers that used the old `words`). */
  def words(s: String): Vector[String] = Lexical.stems(s)
