package storymodel4s.align

import storymodel4s.features.Estimate
import storymodel4s.proposition.GraphOrder

/** One lemma-set distance for strict content scoring and the historical lexical provider. */
private[align] object ContentLexical:
  def overlaps[O <: GraphOrder](u: UnitContent[O], t: TargetContent[O]): Boolean =
    val names = Names.tokens(u.participants.flatMap(_.names).toSet)
    (u.lemmas.nonEmpty && u.lemmas.exists(t.lemmas.contains)) ||
    (names.nonEmpty && names.exists(Names.tokens(t.participants.flatMap(_.names).toSet).contains))

  def score[O <: GraphOrder](u: UnitContent[O], t: TargetContent[O]): Estimate[Double] =
    val a = u.lemmas
    val b = t.lemmas
    Estimate.observed(
      if a.isEmpty && b.isEmpty then 1.0
      else 1.0 - a.intersect(b).size.toDouble / a.union(b).size.toDouble
    )
