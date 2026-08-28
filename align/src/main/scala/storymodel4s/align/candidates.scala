package storymodel4s.align

import storymodel4s.recall.{RecallUnit, RecallUnitId}

/** Sparse candidate sets: which source nodes each unit may be aligned to. */
final case class Candidates(byUnit: Map[RecallUnitId, Vector[SourceNodeRef]]):
  def apply(unit: RecallUnitId): Vector[SourceNodeRef] = byUnit.getOrElse(unit, Vector.empty)
  def union: Vector[SourceNodeRef] = byUnit.values.flatten.toVector.distinct.sorted
  def totalSize: Int = byUnit.values.map(_.size).sum

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

  def forUnit(unit: RecallUnit, view: SourceView): Vector[SourceNodeRef] =
    val dense = view.byLevel.toVector.flatMap { case (_, nodes) =>
      nodes
        .flatMap(n => semantic(unit, n).toOption.map(d => (n.ref, d)))
        .sortBy { case (r, d) => (d, r.key) }
        .take(perLevel)
        .map(_._1)
    }
    val lexical =
      if !lexicalOverlap then Vector.empty
      else
        val lemmas = unit.proposition.lemmas
        val names = unit.proposition.participants.flatMap(_.names).toSet
        view.nodes.collect {
          case n if lemmas.nonEmpty && lemmas.exists(n.lemmas.contains) => n.ref
          case n if names.nonEmpty && names.exists(n.allNames.contains) => n.ref
        }
    (dense ++ lexical).distinct.sorted
