package storymodel4s.align

import storymodel4s.recall.{RecallUnit, RecallUnitId}

/** The candidate set of one unit. `abstained` records that no ranking evidence existed at all:
  * every semantic provider abstained on every node and no lexical/entity hit fired. Such a unit
  * goes to `ExternalState.Unranked`, never to `Intrusion` (review #2).
  */
final case class CandidateSet(ranked: Vector[SourceNodeRef], abstained: Boolean):
  def isEmpty: Boolean = ranked.isEmpty
  def size: Int = ranked.size

object CandidateSet:
  val unranked: CandidateSet = CandidateSet(Vector.empty, abstained = true)
  def of(refs: Vector[SourceNodeRef]): CandidateSet = CandidateSet(refs, abstained = false)

/** Sparse candidate sets: which source nodes each unit may be aligned to. */
final case class Candidates(byUnit: Map[RecallUnitId, CandidateSet]):
  def apply(unit: RecallUnitId): Vector[SourceNodeRef] =
    byUnit.get(unit).map(_.ranked).getOrElse(Vector.empty)
  def set(unit: RecallUnitId): CandidateSet = byUnit.getOrElse(unit, CandidateSet.unranked)
  def abstained(unit: RecallUnitId): Boolean = set(unit).abstained
  def union: Vector[SourceNodeRef] = byUnit.values.flatMap(_.ranked).toVector.distinct.sorted
  def totalSize: Int = byUnit.values.map(_.size).sum

object Candidates:
  /** Build from plain per-unit vectors (never abstained). */
  def of(byUnit: Map[RecallUnitId, Vector[SourceNodeRef]]): Candidates =
    Candidates(byUnit.view.mapValues(CandidateSet.of).toMap)

/** Stage 1: recall-oriented candidate generation. Top-`perLevel` nodes by semantic distance at
  * every hierarchy level, unioned with lexical/entity-overlap hits so rare exact details survive.
  * No dense `M × K` allocation: only the kept candidates are materialized.
  */
final case class CandidateGenerator(
    semantic: SemanticDistance,
    perLevel: Int = 3,
    lexicalOverlap: Boolean = true
):
  def generate(units: Vector[RecallUnit], view: SourceView): Candidates =
    Candidates(units.iterator.map(u => u.id -> forUnit(u, view)).toMap)

  def forUnit(unit: RecallUnit, view: SourceView): CandidateSet =
    var anyRanked = false
    val dense = view.byLevel.toVector.sortBy(_._1).flatMap { case (_, nodes) =>
      val scored = nodes.flatMap(n => semantic(unit, n).toOption.map(d => (n.ref, d)))
      if scored.nonEmpty then anyRanked = true
      scored.sortBy { case (r, d) => (d, r.key) }.take(perLevel).map(_._1)
    }
    val lexical =
      if !lexicalOverlap then Vector.empty
      else
        val lemmas = unit.proposition.lemmas
        val names = Names.tokens(unit.proposition.participants.flatMap(_.names).toSet)
        view.nodes.collect {
          case n if lemmas.nonEmpty && lemmas.exists(n.lemmas.contains)               => n.ref
          case n if names.nonEmpty && names.exists(Names.tokens(n.allNames).contains) => n.ref
        }
    val all = (dense ++ lexical).distinct.sorted
    if all.isEmpty && !anyRanked then CandidateSet.unranked else CandidateSet.of(all)
