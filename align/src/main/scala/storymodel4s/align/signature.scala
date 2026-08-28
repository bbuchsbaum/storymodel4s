package storymodel4s.align

import storymodel4s.recall.{RecallGraph, RecallUnitId}

/** The decomposed recall signature `m_s` (design record §12). Every component stays separately
  * accessible; a scalar is only ever produced by a declared [[SignatureProjection]].
  *
  *   - coverage counts anchored mass in *either* mode: a distorted anchor is recall of the event.
  *   - `distortedMass` / `distortedMassByFacet`: mean per-unit mass on distorted states, overall
  *     and per contradicted facet — reported separately from omission (uncovered leaves) and from
  *     intrusion (external mass), because they are different phenomena (design record §9).
  *   - `fidelity` and `perUnitFidelity`: facets assessed on the MAP `(anchor, mode)`; the facets of
  *     a distorted mode are wrong by construction.
  *   - `specificity`: mean localizability over units with source mass (K = source node count).
  *   - `backwardMass` / `worldBackwardMass`: mean per-step anchor→anchor mass on moves that go
  *     backward in discourse / story-world order, excluding moves to an ancestor (§12.3).
  *   - `causalPreservation`: fraction of source causal edges among recalled leaves whose endpoints
  *     are covered by two *distinct* recall units linked by a recall causal edge, both mapped at or
  *     below `causalLevelThreshold` (§12.4).
  *   - external masses: mean per-unit mass on each explicit external state.
  */
final case class RecallSignature(
    uniformCoverage: Double,
    importanceWeightedCoverage: Double,
    fidelity: Option[Double],
    specificity: Option[Double],
    compression: Double,
    discourseChronology: Double,
    worldChronology: Option[Double],
    causalPreservation: Option[Double],
    semanticFlowCoherence: Double,
    associationMass: Double,
    intrusionMass: Double,
    commentaryMass: Double,
    sourceConsistentInferenceMass: Double,
    uninterpretableMass: Double,
    unrankedMass: Double,
    distortedMass: Double,
    distortedMassByFacet: Map[Facet, Double],
    backwardMass: Double,
    worldBackwardMass: Option[Double],
    perUnitLocalizability: Map[RecallUnitId, Double],
    perUnitFidelity: Map[RecallUnitId, FidelityReport],
    perUnitMode: Map[RecallUnitId, FidelityMode]
):
  def externalMass: Double =
    associationMass + intrusionMass + commentaryMass + sourceConsistentInferenceMass +
      uninterpretableMass + unrankedMass

object RecallSignature:

  def compute(
      result: HsmmResult,
      recall: RecallGraph,
      view: SourceView,
      causalLevelThreshold: Int = 1
  ): RecallSignature =
    val p = result.posterior
    val f = result.flow
    val leaves = view.leaves.map(_.ref)
    val visitation = leafVisitation(p, view)
    val uniform = if leaves.isEmpty then 0.0 else leaves.map(visitation).sum / leaves.size
    // Leaves whose importance is Missing are excluded from the weighted sum (never counted as 0).
    val importance = view.leaves.flatMap(n => n.importance.toOption.map(w => (n.ref, w)))
    val wsum = importance.map(_._2).sum
    val weighted =
      if wsum <= 0 then uniform else importance.map { case (r, w) => w * visitation(r) }.sum / wsum

    val anchored: Vector[(RecallUnitId, FidelityReport, FidelityMode)] = recall.ordered.flatMap {
      u =>
        for
          row <- p.row(u.id)
          ref <- row.mapSource
          mode <- row.mapMode
          node <- view.node(ref)
          if row.sourceMass > row.externalMass
        yield (u.id, FidelityFacets.assess(u.proposition, node, mode), mode)
    }
    val facets = anchored.map(a => a._1 -> a._2).toMap
    val modes = anchored.map(a => a._1 -> a._3).toMap
    val fid = facets.values.flatMap(_.fidelity).toVector
    val fidelity = if fid.isEmpty then None else Some(fid.sum / fid.size)

    val k = view.sourceNodeCount
    val loc = p.rows.flatMap(r => r.localizability(k).map(r.unit -> _)).toMap
    val specificity = if loc.isEmpty then None else Some(loc.values.toVector.sorted.sum / loc.size)

    val maxLevel = math.max(1, view.maxLevel)
    val levelMass = p.rows.map { r =>
      val src = r.sourceMass
      if src <= 0 then 0.0
      else (0 to view.maxLevel).map(l => l * r.massAtLevel(view, l)).sum / src / maxLevel
    }
    val compression = if levelMass.isEmpty then 0.0 else levelMass.sum / levelMass.size

    def isBackward(pos: SourceNodeRef => Option[Double])(a: SourceNodeRef, b: SourceNodeRef) =
      a != b && !view.isAncestor(b, a) && ((pos(a), pos(b)) match
        case (Some(x), Some(y)) => y < x
        case _                  => false)
    def isForward(pos: SourceNodeRef => Option[Double])(a: SourceNodeRef, b: SourceNodeRef) =
      a != b && !view.isAncestor(b, a) && !view.isAncestor(a, b) && ((pos(a), pos(b)) match
        case (Some(x), Some(y)) => y > x
        case _                  => false)
    def ordered(pos: SourceNodeRef => Option[Double]): Option[Double] =
      val fw = f.steps.map(_.sourceMass(isForward(pos))).sum
      val bw = f.steps.map(_.sourceMass(isBackward(pos))).sum
      if fw + bw <= 0 then None else Some(fw / (fw + bw))
    def backwardMean(pos: SourceNodeRef => Option[Double]): Double =
      f.steps.map(_.sourceMass(isBackward(pos))).sum / math.max(1, f.steps.size)
    val discoursePos: SourceNodeRef => Option[Double] = r => Some(view.relativePosition(r))
    val worldPos: Option[SourceNodeRef => Option[Double]] =
      view.worldOrder.map(o => r => o.get(r).map(_.toDouble))
    val discourse = ordered(discoursePos).getOrElse(1.0)
    val world = worldPos.flatMap(ordered)
    val backward = backwardMean(discoursePos)
    val worldBackward = worldPos.map(backwardMean)

    val causalEdges = view.adjacency(RelationLayer.Causal).toVector.sortBy(_._1.key).flatMap {
      case (a, m) => m.toVector.sortBy(_._1.key).collect { case (b, w) if w > 0 => (a, b) }
    }
    val recalledCausal = causalEdges.filter { case (a, b) =>
      visitation.getOrElse(a, 0.0) > 0.5 && visitation.getOrElse(b, 0.0) > 0.5
    }
    val unitMap: Map[RecallUnitId, SourceNodeRef] = p.rows.flatMap { r =>
      r.mapSource
        .filter(_ => r.sourceMass > r.externalMass)
        .filter(ref => view.node(ref).exists(_.level <= causalLevelThreshold))
        .map(r.unit -> _)
    }.toMap
    def covers(ref: SourceNodeRef, leaf: SourceNodeRef): Boolean =
      ref == leaf || view.leavesUnder(ref).contains(leaf)
    val preserved = recalledCausal.count { case (a, b) =>
      recall.relations.causal.exists { e =>
        e.cause != e.effect && {
          (unitMap.get(e.cause), unitMap.get(e.effect)) match
            case (Some(ca), Some(ef)) => ca != ef && covers(ca, a) && covers(ef, b)
            case _                    => false
        }
      }
    }
    val causal =
      if recalledCausal.isEmpty then None else Some(preserved.toDouble / recalledCausal.size)

    val coherence = f.steps.map { s =>
      val total = s.sourceToSourceMass
      if total <= 0 then 1.0
      else
        s.sourceMass((a, b) =>
          a == b || view.hasEdge(RelationLayer.DiscourseSuccession, a, b) ||
            view.hasEdge(RelationLayer.Causal, a, b) || view.hasEdge(RelationLayer.Causal, b, a) ||
            view.isAncestor(a, b) || view.isAncestor(b, a) ||
            view.weight(RelationLayer.Semantic, a, b) > 0 || view.weight(
              RelationLayer.Semantic,
              b,
              a
            ) > 0
        ) / total
    }
    val semanticFlow = if coherence.isEmpty then 1.0 else coherence.sum / coherence.size

    val n = math.max(1, p.rows.size)
    def extMean(state: ExternalState): Double =
      if p.rows.isEmpty then 0.0 else p.rows.map(_.externalMass(state)).sum / n
    val distorted = if p.rows.isEmpty then 0.0 else p.rows.map(_.distortedMass).sum / n
    val byFacet = Facet.values.toVector
      .map(fc => fc -> (if p.rows.isEmpty then 0.0 else p.rows.map(_.distortedMass(fc)).sum / n))
      .filter(_._2 > 0.0)
      .toMap

    RecallSignature(
      uniform,
      weighted,
      fidelity,
      specificity,
      compression,
      discourse,
      world,
      causal,
      semanticFlow,
      extMean(ExternalState.Association),
      extMean(ExternalState.Intrusion),
      extMean(ExternalState.Commentary),
      extMean(ExternalState.SourceConsistentInference),
      extMean(ExternalState.Uninterpretable),
      extMean(ExternalState.Unranked),
      distorted,
      byFacet,
      backward,
      worldBackward,
      loc,
      facets,
      modes
    )

  /** Visitation per leaf, counting mass placed on ancestors as spread over their leaves; anchors of
    * either mode count.
    */
  def leafVisitation(p: AlignmentMatrix, view: SourceView): Map[SourceNodeRef, Double] =
    val acc = scala.collection.mutable.Map.empty[SourceNodeRef, Double].withDefaultValue(0.0)
    p.rows.foreach { row =>
      row.anchorMass.toVector.sortBy(_._1.key).foreach { case (ref, m) =>
        if m > 0 then
          val ls = view.leavesUnder(ref)
          if ls.nonEmpty then ls.foreach(l => acc.update(l, acc(l) + m / ls.size))
      }
    }
    view.leaves.map(n => n.ref -> (1.0 - math.exp(-acc(n.ref)))).toMap

/** A declared scalar projection of the signature: explicit, versioned weights. */
final case class SignatureProjection(version: String, weights: Map[String, Double]):
  def apply(s: RecallSignature): Double =
    val comps: Map[String, Double] = Map(
      "uniformCoverage" -> s.uniformCoverage,
      "importanceWeightedCoverage" -> s.importanceWeightedCoverage,
      "fidelity" -> s.fidelity.getOrElse(0.0),
      "specificity" -> s.specificity.getOrElse(0.0),
      "compression" -> s.compression,
      "discourseChronology" -> s.discourseChronology,
      "worldChronology" -> s.worldChronology.getOrElse(0.0),
      "causalPreservation" -> s.causalPreservation.getOrElse(0.0),
      "semanticFlowCoherence" -> s.semanticFlowCoherence,
      "associationMass" -> s.associationMass,
      "intrusionMass" -> s.intrusionMass,
      "commentaryMass" -> s.commentaryMass,
      "sourceConsistentInferenceMass" -> s.sourceConsistentInferenceMass,
      "uninterpretableMass" -> s.uninterpretableMass,
      "unrankedMass" -> s.unrankedMass,
      "distortedMass" -> s.distortedMass,
      "backwardMass" -> s.backwardMass,
      "worldBackwardMass" -> s.worldBackwardMass.getOrElse(0.0)
    )
    weights.toVector.sortBy(_._1).map { case (k, w) => w * comps.getOrElse(k, 0.0) }.sum
