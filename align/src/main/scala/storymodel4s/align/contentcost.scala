package storymodel4s.align

import storymodel4s.features.{Estimate, MissingReason}
import storymodel4s.proposition.GraphOrder
import storymodel4s.recall.{DiscourseFunction, Lexical}

/** A content-only price calculation, with provider outcomes supplied as typed measurements. */
private[align] object ContentCostScoring:
  final case class Terms(
      values: Map[CostTerm, Double],
      missing: Set[CostTerm],
      semanticImputed: Map[CostTerm, MissingReason],
      eligible: Set[CostTerm]
  )

  def terms[O <: GraphOrder](
      u: UnitContent[O],
      t: TargetContent[O],
      grain: ContentGrain,
      mode: FidelityMode,
      semantic: Estimate[Double],
      chart: Estimate[Double],
      structural: Estimate[Double],
      structuralConfigured: Boolean,
      missingSemantic: Double,
      distortionPenalty: Double
  ): Terms =
    val dSem = clamp(semantic.toOption.getOrElse(missingSemantic))
    val semImputed: Map[CostTerm, MissingReason] = semantic match
      case Estimate.Missing(reason) => Map(CostTerm.Semantic -> reason)
      case _                        => Map.empty
    val dProp = (u.predicate, t.predicate) match
      case (Some(a), Some(b)) => if a == b then 0.0 else 1.0
      case _                  => 0.5
    val specified = u.participants.filter(_.specified)
    val allNames = t.participants.flatMap(_.names).toSet
    val dEnt =
      if specified.isEmpty then 0.5
      else
        val credit = specified.map { p =>
          if t.byRole(p.role).exists(n => Names.overlap(p.names, n.names)) then 1.0
          else if Names.overlap(p.names, allNames) then 0.5
          else 0.0
        }.sum
        1.0 - credit / specified.size.toDouble
    val dSens =
      if u.sensoryTerms.isEmpty then Estimate.missing(MissingReason.AllMissing)
      else
        val hits = u.sensoryTerms.count(s => t.lemmas.contains(Lexical.stem(s)))
        Estimate.observed(1.0 - hits.toDouble / u.sensoryTerms.size.toDouble)
    val preferred = u.function match
      case DiscourseFunction.Summary                                    => 1
      case DiscourseFunction.Association | DiscourseFunction.Evaluation => grain.maxLevel
      case _ if u.predicate.isEmpty && u.participants.isEmpty           => 1
      case _                                                            => 0
    val dGran = math.min(1.0, 0.5 * math.abs(t.level - preferred))
    val dDist = distortionPenalty * mode.facetSet.size.toDouble
    val always = Vector(
      CostTerm.Semantic -> dSem,
      CostTerm.Propositional -> dProp,
      CostTerm.Entity -> dEnt,
      CostTerm.Granularity -> dGran,
      CostTerm.Distortion -> dDist
    )
    val optional =
      Vector(CostTerm.Chart -> chart, CostTerm.Structural -> structural, CostTerm.Sensory -> dSens)
    val present = optional.collect { case (term, Estimate.Observed(v, _)) => term -> clamp(v) }
    val missing = optional.collect { case (term, Estimate.Missing(_)) => term }.toSet
    val chartedMembers = t.members.exists(_.hasEvidence)
    val chartEligible = u.graph.nonEmpty && chartedMembers
    val structuralEligible = structuralConfigured && chartEligible
    val eligible = always.map(_._1).toSet ++
      Set(CostTerm.Sensory) ++
      Option.when(chartEligible)(CostTerm.Chart) ++
      Option.when(structuralEligible)(CostTerm.Structural)
    Terms((always ++ present).toMap, missing, semImputed, eligible)

  private def clamp(x: Double): Double =
    if x.isNaN then 1.0 else math.max(0.0, math.min(1.0, x))
