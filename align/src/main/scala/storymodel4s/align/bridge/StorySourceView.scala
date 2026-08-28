package storymodel4s.align.bridge

import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.proposition.ParticipantRole
import storymodel4s.recall.{ModalityTag, PolarityTag, SketchRole}
import storymodel4s.story.{
  AlignmentSource,
  ContextKind,
  ModelStatus,
  Modality,
  NarrativeNodeId,
  Polarity,
  StoryModel,
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
  *   - `WorldTime`: transitive closure of the story's strict-precedence edges restricted to
  *     situations in the `NarratedWorld` context (reported or believed orderings never become
  *     narrated-world time);
  *   - `Causal`, `EntityContinuity`: the story matrices as given;
  *   - `Hierarchy`: parent → member, from primary containment;
  *   - `Semantic`: empty unless injected.
  */
final class StorySourceView(
    source: AlignmentSource,
    model: StoryModel[?],
    semantic: Map[SourceNodeRef, Map[SourceNodeRef, Double]] = Map.empty
) extends SourceView:

  private val text: String = model.source.canonicalText

  private def toRef(n: NarrativeNodeId): SourceNodeRef = n match
    case NarrativeNodeId.Situation(id) => SourceNodeRef.Situation(id)
    case NarrativeNodeId.Segment(id)   => SourceNodeRef.Segment(id)

  private val all: Vector[NarrativeNodeId] = source.allNodes

  /** Rank of each node among the nodes of its own level, by absolute discourse position. */
  private val levelRank: Map[NarrativeNodeId, Int] =
    all
      .groupBy(source.levelOf)
      .values
      .flatMap { ns =>
        ns.sortBy(n => (source.discoursePosition(n).getOrElse(Int.MaxValue), n.render)).zipWithIndex
      }
      .toMap

  private def summarize(n: NarrativeNodeId): NodeSummary =
    val support = source.sourceSupport(n).getOrElse(SpanSet.one(TextSpan.unsafe(0, 0)))
    val participants = source.participantsOf(n).map { p =>
      ParticipantSummary(StorySourceView.sketchRole(p.role), p.label, Set(p.label))
    }
    // Multiword lemmas ("feel-sick") compare by their head so predicate identity is not defeated
    // by compounding; the full lemma still contributes to the lemma set.
    val fullPredicate = source.predicateOf(n).map(_.lemma.toLowerCase)
    val predicate = fullPredicate.flatMap(_.split('-').headOption.filter(_.nonEmpty))
    val description = source.descriptionOf(n).getOrElse("")
    val supportWords =
      support.spans.toVector.flatMap(sp => StorySourceView.words(sp.slice(text).getOrElse("")))
    val lemmas =
      (supportWords ++ StorySourceView.words(description) ++ predicate.toVector ++
        fullPredicate.toVector.flatMap(StorySourceView.words) ++
        participants.flatMap(p => StorySourceView.words(p.label))).toSet
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
      lemmas
    )

  val nodes: Vector[NodeSummary] = all.map(summarize)
  private val index: Map[SourceNodeRef, NodeSummary] = nodes.iterator.map(n => n.ref -> n).toMap
  def node(ref: SourceNodeRef): Option[NodeSummary] = index.get(ref)
  val textLength: Int = text.length

  private def sparse(
      rel: Map[(NarrativeNodeId, NarrativeNodeId), Double]
  ): Map[SourceNodeRef, Map[SourceNodeRef, Double]] =
    rel.toVector
      .filter { case (_, w) => w > 0.0 }
      .groupBy { case ((a, _), _) => toRef(a) }
      .view
      .mapValues(_.map { case ((_, b), w) => toRef(b) -> w }.toMap)
      .toMap

  private val narratedWorldSituations: Set[NarrativeNodeId] =
    all.filter(n => source.contextOf(n).contains(ContextKind.NarratedWorld)).toSet

  private lazy val worldTimeClosure: Map[SourceNodeRef, Map[SourceNodeRef, Double]] =
    val direct = source
      .relationMatrix(StoryLayer.WorldTime)
      .filter { case ((a, b), w) =>
        w > 0.0 && narratedWorldSituations.contains(a) && narratedWorldSituations.contains(b)
      }
      .keys
      .toVector
      .groupMap(_._1)(_._2)
    val closure = narratedWorldSituations.toVector.map { start =>
      val seen = scala.collection.mutable.LinkedHashSet.empty[NarrativeNodeId]
      val queue = scala.collection.mutable.Queue(direct.getOrElse(start, Vector.empty)*)
      while queue.nonEmpty do
        val n = queue.dequeue()
        if !seen.contains(n) && n != start then
          seen += n
          queue.enqueueAll(direct.getOrElse(n, Vector.empty))
      toRef(start) -> seen.toVector.map(n => toRef(n) -> 1.0).toMap
    }
    closure.filter(_._2.nonEmpty).toMap

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

  private lazy val hierarchy: Map[SourceNodeRef, Map[SourceNodeRef, Double]] =
    source.hierarchyMembership.toVector
      .groupBy { case ((_, parent), _) => toRef(parent) }
      .view
      .mapValues(_.map { case ((member, _), w) => toRef(member) -> w }.toMap)
      .toMap

  private lazy val causal = sparse(source.relationMatrix(StoryLayer.Causal))
  private lazy val entityContinuity = sparse(source.relationMatrix(StoryLayer.EntityContinuity))

  def adjacency(layer: RelationLayer): Map[SourceNodeRef, Map[SourceNodeRef, Double]] =
    layer match
      case RelationLayer.DiscourseSuccession => discourseSuccession
      case RelationLayer.WorldTime           => worldTimeClosure
      case RelationLayer.Causal              => causal
      case RelationLayer.EntityContinuity    => entityContinuity
      case RelationLayer.Hierarchy           => hierarchy
      case RelationLayer.Semantic            => semantic

  /** A topological rank of narrated-world situations by strict precedence (Kahn's algorithm), ties
    * broken by discourse position; segments take the minimum rank of their leaves. `None` when the
    * closure is cyclic or empty.
    */
  lazy val worldOrder: Option[Map[SourceNodeRef, Int]] =
    val situationRefs = narratedWorldSituations.toVector.map(toRef).sortBy(_.key)
    if situationRefs.isEmpty then None
    else
      val direct = source
        .relationMatrix(StoryLayer.WorldTime)
        .collect {
          case ((a, b), w)
              if w > 0.0 && narratedWorldSituations.contains(a) &&
                narratedWorldSituations.contains(b) =>
            toRef(a) -> toRef(b)
        }
        .toVector
      val out = direct.groupMap(_._1)(_._2)
      val indegree = scala.collection.mutable.Map(situationRefs.map(_ -> 0)*)
      direct.foreach { case (_, b) => indegree(b) = indegree(b) + 1 }
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
  def apply(
      source: AlignmentSource,
      model: StoryModel[ModelStatus.Validated],
      semantic: Map[SourceNodeRef, Map[SourceNodeRef, Double]] = Map.empty
  ): StorySourceView = new StorySourceView(source, model, semantic)

  def validated(model: StoryModel[ModelStatus.Validated]): StorySourceView =
    new StorySourceView(AlignmentSource(model), model)

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
    case ParticipantRole.Beneficiary   => SketchRole.Other("beneficiary")
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

  private val stopwords: Set[String] = Set(
    "the",
    "a",
    "an",
    "and",
    "or",
    "of",
    "to",
    "in",
    "on",
    "at",
    "it",
    "is",
    "was",
    "were",
    "be",
    "been",
    "he",
    "she",
    "they",
    "them",
    "him",
    "her",
    "his",
    "their",
    "we",
    "i",
    "you",
    "that",
    "this",
    "there",
    "then",
    "now",
    "when",
    "while",
    "with",
    "for",
    "from",
    "up",
    "down",
    "into",
    "one",
    "not",
    "did",
    "do",
    "had",
    "has",
    "have",
    "by",
    "as",
    "so",
    "but",
    "no",
    "us",
    "me",
    "my",
    "who",
    "what",
    "which",
    "are",
    "am",
    "its",
    "our",
    "your",
    "those",
    "these",
    "all",
    "very"
  )

  /** Lower-cased alphabetic words minus a small stopword list, crudely lemmatized with the same
    * suffix rules the recall segmenter baseline uses, so lexical overlap compares like with like.
    */
  def words(s: String): Vector[String] =
    s.toLowerCase
      .split("[^a-z\u00e0-\u00ff]+") // explicit ranges: Scala.js lacks \\p{L}
      .toVector
      .filter(w => w.nonEmpty && !stopwords.contains(w))
      .map(lemma)

  private val irregular: Map[String, String] = Map(
    "went" -> "go",
    "goes" -> "go",
    "going" -> "go",
    "gone" -> "go",
    "came" -> "come",
    "coming" -> "come",
    "heard" -> "hear",
    "saw" -> "see",
    "seen" -> "see",
    "said" -> "say",
    "told" -> "tell",
    "thought" -> "think",
    "hid" -> "hide",
    "hidden" -> "hide",
    "shot" -> "shoot",
    "hit" -> "hit",
    "felt" -> "feel",
    "fought" -> "fight",
    "began" -> "begin",
    "made" -> "make",
    "fell" -> "fall",
    "was" -> "be",
    "were" -> "be",
    "died" -> "die",
    "dead" -> "die",
    "killed" -> "kill",
    "carried" -> "carry",
    "lifted" -> "lift",
    "got" -> "get",
    "returned" -> "return",
    "arrived" -> "arrive",
    "landed" -> "land",
    "spoke" -> "speak",
    "left" -> "leave",
    "cried" -> "cry",
    "canoes" -> "canoe",
    "men" -> "man",
    "people" -> "people",
    "paddling" -> "paddle",
    "paddles" -> "paddle",
    "misty" -> "fog",
    "foggy" -> "fog",
    "still" -> "calm",
    "ghosts" -> "ghost",
    "strangers" -> "stranger",
    "warriors" -> "warrior",
    "arrows" -> "arrow",
    "relatives" -> "relative",
    "family" -> "relative",
    "boat" -> "canoe",
    "fight" -> "fight",
    "battle" -> "fight",
    "war" -> "war",
    "raid" -> "war",
    "guys" -> "man",
    "fellows" -> "fellow",
    "sick" -> "sick",
    "ill" -> "sick",
    "tears" -> "cry",
    "lit" -> "make",
    "wound" -> "shoot",
    "back" -> "return",
    "night" -> "night"
  )

  private def lemma(word: String): String =
    irregular.get(word) match
      case Some(l) => l
      case None    =>
        if word.endsWith("ies") && word.length > 4 then word.dropRight(3) + "y"
        else if word.endsWith("ing") && word.length > 5 then word.dropRight(3)
        else if word.endsWith("ed") && word.length > 4 then word.dropRight(2)
        else if word.length > 4 && Vector("sses", "shes", "ches", "xes", "zes").exists(
            word.endsWith
          )
        then word.dropRight(2)
        else if word.endsWith("s") && !word.endsWith("ss") && word.length > 3 then word.dropRight(1)
        else word
