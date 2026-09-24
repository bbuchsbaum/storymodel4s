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
    // The provider's own answer is kept, not collapsed to a Double, so that "abstained" survives to
    // the receipt. The PRICE is unchanged - `missingSemantic` is still substituted, and the M0
    // decision at cost.scala:705 stands - but the substitution is now recorded rather than silent.
    val dSem = clamp(semantic.toOption.getOrElse(missingSemantic))
    val semImputed: Map[CostTerm, MissingReason] = semantic match
      case Estimate.Missing(reason) => Map(CostTerm.Semantic -> reason)
      case _                        => Map.empty
    val dProp = (u.predicate, t.predicate) match
      case (Some(a), Some(b)) => if a == b then 0.0 else 1.0
      case _                  => 0.5
    // Role-aware: a name found in the same role earns full credit, in another role half credit.
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
    // Preferred abstraction level: summaries want a scene, thematic/evaluative remarks want the
    // global level, predicate-bearing assertions want a leaf, predicate-less ones a scene.
    val preferred = u.function match
      case DiscourseFunction.Summary                                    => 1
      case DiscourseFunction.Association | DiscourseFunction.Evaluation => grain.maxLevel
      case _ if u.predicate.isEmpty && u.participants.isEmpty           => 1
      case _                                                            => 0
    val dGran = math.min(1.0, 0.5 * math.abs(t.level - preferred))
    // The distortion term: `distortionPenalty` per contradicted facet, weighted by
    // `contradiction`. It separates a distorted anchor from a faithful one at equal content
    // match and must stay below the external floor so a well-matched distorted anchor is
    // preferred to intrusion (design record §9). Provisional until W4 calibration.
    val dDist = distortionPenalty * mode.facetSet.size.toDouble
    val always = Vector(
      CostTerm.Semantic -> dSem,
      CostTerm.Propositional -> dProp,
      CostTerm.Entity -> dEnt,
      CostTerm.Granularity -> dGran,
      CostTerm.Distortion -> dDist
    )
    // Optional evidence-backed terms: present only when charts exist on both sides (and, for
    // `d_wl`, a provider answered). Absent terms are inert and recorded, never substituted.
    val optional =
      Vector(CostTerm.Chart -> chart, CostTerm.Structural -> structural, CostTerm.Sensory -> dSens)
    val present = optional.collect { case (term, Estimate.Observed(v, _)) => term -> clamp(v) }
    val missing = optional.collect { case (term, Estimate.Missing(_)) => term }.toSet
    // ELIGIBILITY IS PER-CELL, not per-view. A chartless node in a mixed view could never have been
    // chart-compared, so treating it as a missed measurement would invent a dimension that cell
    // cannot have — the same error as scaling over all terms, which collapsed every row to External.
    // Chart eligibility must be asked of the function that PRODUCES the term. `dChart` comes from
    // ChartDistance.reduction, which measures over `view.structuralMembers(node.ref)` — the leaves.
    // It does NOT come from ChartDistance.report, whose `(unit, node)` signature genuinely does
    // need a chart on each side. Testing `node.evidence` here applied report's contract to
    // reduction's term: identical on a leaf, wrong on a segment, because StorySourceView gives a
    // segment no chart of its own ("segments never get a fabricated chart") while its leaves carry
    // the charts actually compared. Chart then came out present-but-not-eligible, wPresent exceeded
    // wEligible, and the blend multiplied the cost DOWN while the old numeric support's clamp
    // reported the over-unity ratio as full support. `leavesUnder` returns the node itself for a leaf, so this
    // predicate is a strict generalization and no leaf cell moves.
    //
    // Structural has the SAME defect mirrored, found by scout on this candidate. It is produced by
    // ChartDistance.structuralReduction, which reads the same structuralMembers population, but its
    // eligibility asked only whether a provider was configured and the unit had evidence — never
    // whether the source had a chart to compare against. On a chartless cell the term is absent
    // either way, yet CONFIGURATION ALONE moved (support, total) from (0.9552, 1.37045) to
    // (0.8312, 1.575): a 15% cost inflation for a measurement that cell could never have had,
    // biasing chartless cells toward External. That is exactly the error the paragraph above warns
    // about, in the term I did not check. Both now ask the population the measurement reads.
    // (Since S2a-2 that population is read here as `t.members`, which the Source projection fills
    // from `view.structuralMembers(node.ref)`.)
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
