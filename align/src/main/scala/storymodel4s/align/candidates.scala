package storymodel4s.align

import storymodel4s.recall.{RecallUnit, RecallUnitId}

/** One nomination of a source node as a candidate for a unit, with its provenance: which channel
  * proposed it, at what rank within that channel, with what raw score (never a probability), in
  * which feature space, under which receipt. Provenance is retained so ablations can drop channels
  * and so no score ever enters the alignment as mass (law L2).
  */
final case class Nomination(
    ref: SourceNodeRef,
    channel: String,
    rank: Int,
    rawScore: Option[Double],
    space: Option[String],
    receipt: Option[String]
)

/** The candidate set of one unit. `abstained` records that no ranking evidence existed at all:
  * every semantic provider abstained on every node and no lexical/entity hit fired. Such a unit
  * goes to `ExternalState.Unranked`, never to `Intrusion` (review #2).
  *
  * `ranked` is the deduplicated, deterministically ordered anchor list the aligner consumes; the
  * order carries no information into inference (states are a set), only `nominations` do.
  */
final case class CandidateSet(nominations: Vector[Nomination], abstained: Boolean):
  lazy val ranked: Vector[SourceNodeRef] = nominations.map(_.ref).distinct.sorted
  def isEmpty: Boolean = ranked.isEmpty
  def size: Int = ranked.size

  /** Channels that nominated `ref`. */
  def channelsOf(ref: SourceNodeRef): Set[String] =
    nominations.filter(_.ref == ref).map(_.channel).toSet

  def channels: Set[String] = nominations.map(_.channel).toSet

  /** Drop every nomination from `channel` (ablation); abstention is recomputed. */
  def without(channel: String): CandidateSet =
    val kept = nominations.filterNot(_.channel == channel)
    CandidateSet(kept, abstained = abstained || (kept.isEmpty && nominations.nonEmpty))

object CandidateSet:
  val unranked: CandidateSet = CandidateSet(Vector.empty, abstained = true)

  /** Plain anchors with no provenance (channel `unspecified`). */
  def of(refs: Vector[SourceNodeRef]): CandidateSet =
    CandidateSet(
      refs.distinct.sorted.zipWithIndex.map { (r, i) =>
        Nomination(r, Channels.unspecified, i, None, None, None)
      },
      abstained = false
    )

  /** Rank-only fusion of several channels' candidate sets (reciprocal-rank fusion). The fused rank
    * orders `nominations` for diagnostics only; the RRF score is neither stored nor exposed and
    * cannot enter a cost or a mass (law L2). All nominations are preserved with their own channel,
    * rank, and raw score.
    */
  def fuse(sets: Vector[CandidateSet], k: Int = 60): CandidateSet =
    val all = sets.flatMap(_.nominations)
    if all.isEmpty then CandidateSet(Vector.empty, abstained = sets.forall(_.abstained))
    else
      val rrf: Map[SourceNodeRef, Double] = all
        .groupBy(_.ref)
        .view
        .mapValues(ns => ns.map(n => 1.0 / (k + 1 + n.rank)).sum)
        .toMap
      val ordered = all.sortBy(n => (-rrf(n.ref), n.ref.key, n.channel, n.rank))
      CandidateSet(ordered, abstained = false)

object Channels:
  val unspecified = "unspecified"
  val lexical = "lexical"
  def semantic(space: Option[String]): String = space.fold("semantic")(s => s"semantic:$s")

/** Sparse candidate sets: which source nodes each unit may be aligned to. */
final case class Candidates(byUnit: Map[RecallUnitId, CandidateSet]):
  def apply(unit: RecallUnitId): Vector[SourceNodeRef] =
    byUnit.get(unit).map(_.ranked).getOrElse(Vector.empty)
  def set(unit: RecallUnitId): CandidateSet = byUnit.getOrElse(unit, CandidateSet.unranked)
  def abstained(unit: RecallUnitId): Boolean = set(unit).abstained
  def union: Vector[SourceNodeRef] = byUnit.values.flatMap(_.ranked).toVector.distinct.sorted
  def totalSize: Int = byUnit.values.map(_.size).sum

  /** Ablation: drop one channel everywhere. */
  def without(channel: String): Candidates =
    Candidates(byUnit.view.mapValues(_.without(channel)).toMap)

object Candidates:
  /** Build from plain per-unit vectors (never abstained). */
  def of(byUnit: Map[RecallUnitId, Vector[SourceNodeRef]]): Candidates =
    Candidates(byUnit.view.mapValues(CandidateSet.of).toMap)

/** Stage 1: recall-oriented candidate generation. Top-`perLevel` nodes by semantic distance at
  * every hierarchy level (channel `semantic[:space]`, rank within level, raw score = distance),
  * unioned with lexical/entity-overlap hits (channel `lexical`) so rare exact details survive. No
  * dense `M × K` allocation: only the kept candidates are materialized.
  */
final case class CandidateGenerator(
    semantic: SemanticDistance,
    perLevel: Int = 3,
    lexicalOverlap: Boolean = true,
    space: Option[String] = None
):
  def generate(units: Vector[RecallUnit], view: SourceView): Candidates =
    Candidates(units.iterator.map(u => u.id -> forUnit(u, view)).toMap)

  def forUnit(unit: RecallUnit, view: SourceView): CandidateSet =
    var anyRanked = false
    val channel = Channels.semantic(space)
    val dense = view.byLevel.toVector.sortBy(_._1).flatMap { case (level, nodes) =>
      val scored = nodes.flatMap(n => semantic(unit, n).toOption.map(d => (n.ref, d)))
      if scored.nonEmpty then anyRanked = true
      scored
        .sortBy { case (r, d) => (d, r.key) }
        .take(perLevel)
        .zipWithIndex
        .map { case ((r, d), i) =>
          Nomination(r, channel, i, Some(d), space, Some(s"level:$level"))
        }
    }
    val lexical =
      if !lexicalOverlap then Vector.empty
      else
        val lemmas = unit.proposition.lemmas
        val names = Names.tokens(unit.proposition.participants.flatMap(_.names).toSet)
        view.nodes
          .collect {
            case n if lemmas.nonEmpty && lemmas.exists(n.lemmas.contains)               => n.ref
            case n if names.nonEmpty && names.exists(Names.tokens(n.allNames).contains) => n.ref
          }
          .distinct
          .sorted
          .zipWithIndex
          .map { (r, i) => Nomination(r, Channels.lexical, i, None, None, None) }
    val all = dense ++ lexical
    if all.isEmpty && !anyRanked then CandidateSet.unranked
    else CandidateSet(all, abstained = false)
