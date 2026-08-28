package storymodel4s.align

import storymodel4s.recall.{RecallGraph, RecallUnitId}

/** The decomposed recall signature `m_s` (design record §12). Every component stays separately
  * accessible; a scalar is only ever produced by a declared [[SignatureProjection]].
  */
final case class RecallSignature(
    uniformCoverage: Double,
    importanceWeightedCoverage: Double,
    fidelity: Option[Double],
    specificity: Double,
    compression: Double,
    discourseChronology: Double,
    worldChronology: Option[Double],
    causalPreservation: Option[Double],
    semanticFlowCoherence: Double,
    associationMass: Double,
    intrusionMass: Double,
    commentaryMass: Double,
    backwardMass: Double,
    perUnitLocalizability: Map[RecallUnitId, Double],
    perUnitFidelity: Map[RecallUnitId, FidelityReport]
)

object RecallSignature:

  def compute(
      result: HsmmResult,
      recall: RecallGraph,
      view: SourceView
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

    val facets: Map[RecallUnitId, FidelityReport] = recall.ordered.flatMap { u =>
      for
        row <- p.row(u.id)
        ref <- row.mapSource
        node <- view.node(ref)
        if row.sourceMass > row.externalMass
      yield u.id -> FidelityFacets.assess(u.proposition, node)
    }.toMap
    val fid = facets.values.flatMap(_.fidelity).toVector
    val fidelity = if fid.isEmpty then None else Some(fid.sum / fid.size)

    val loc = p.rows.map(r => r.unit -> r.localizability).toMap
    val specificity = if loc.isEmpty then 0.0 else loc.values.sum / loc.size

    val maxLevel = math.max(1, view.maxLevel)
    val levelMass = p.rows.map { r =>
      val src = r.sourceMass
      if src <= 0 then 0.0
      else (0 to view.maxLevel).map(l => l * r.massAtLevel(view, l)).sum / src / maxLevel
    }
    val compression = if levelMass.isEmpty then 0.0 else levelMass.sum / levelMass.size

    def ordered(pos: SourceNodeRef => Option[Double]): Option[Double] =
      val moves = f.steps.map { s =>
        val forward = s.sourceMass((a, b) =>
          a != b && (pos(a), pos(b)).match
            case (Some(x), Some(y)) => y > x
            case _                  => false
        )
        val backward = s.sourceMass((a, b) =>
          a != b && (pos(a), pos(b)).match
            case (Some(x), Some(y)) => y < x
            case _                  => false
        )
        (forward, backward)
      }
      val fw = moves.map(_._1).sum
      val bw = moves.map(_._2).sum
      if fw + bw <= 0 then None else Some(fw / (fw + bw))
    val discourse = ordered(r => Some(view.relativePosition(r))).getOrElse(1.0)
    val world = view.worldOrder.flatMap(o => ordered(r => o.get(r).map(_.toDouble)))
    val backward = f.steps.map { s =>
      s.sourceMass((a, b) => a != b && view.relativePosition(b) < view.relativePosition(a))
    }.sum / math.max(1, f.steps.size)

    val causalEdges = view.adjacency(RelationLayer.Causal).toVector.flatMap { case (a, m) =>
      m.collect { case (b, w) if w > 0 => (a, b) }
    }
    val recalledCausal = causalEdges.filter { case (a, b) =>
      visitation.getOrElse(a, 0.0) > 0.5 && visitation.getOrElse(b, 0.0) > 0.5
    }
    val unitMap: Map[RecallUnitId, SourceNodeRef] = p.rows.flatMap { r =>
      r.mapSource.filter(_ => r.sourceMass > r.externalMass).map(r.unit -> _)
    }.toMap
    def covers(ref: SourceNodeRef, leaf: SourceNodeRef): Boolean =
      ref == leaf || view.leavesUnder(ref).contains(leaf)
    val preserved = recalledCausal.count { case (a, b) =>
      recall.relations.causal.exists(e =>
        unitMap.get(e.cause).exists(covers(_, a)) && unitMap.get(e.effect).exists(covers(_, b))
      )
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

    def extMean(state: ExternalState): Double =
      if p.rows.isEmpty then 0.0 else p.rows.map(_.externalMass(state)).sum / p.rows.size

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
      backward,
      loc,
      facets
    )

  /** Visitation per leaf, counting mass placed on ancestors as spread over their leaves. */
  def leafVisitation(p: AlignmentMatrix, view: SourceView): Map[SourceNodeRef, Double] =
    val acc = scala.collection.mutable.Map.empty[SourceNodeRef, Double].withDefaultValue(0.0)
    p.rows.foreach { row =>
      row.mass.foreach {
        case (AlignState.Source(ref), m) if m > 0 =>
          val ls = view.leavesUnder(ref)
          if ls.nonEmpty then ls.foreach(l => acc.update(l, acc(l) + m / ls.size))
        case _ => ()
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
      "specificity" -> s.specificity,
      "compression" -> s.compression,
      "discourseChronology" -> s.discourseChronology,
      "worldChronology" -> s.worldChronology.getOrElse(0.0),
      "causalPreservation" -> s.causalPreservation.getOrElse(0.0),
      "semanticFlowCoherence" -> s.semanticFlowCoherence,
      "associationMass" -> s.associationMass,
      "intrusionMass" -> s.intrusionMass,
      "commentaryMass" -> s.commentaryMass,
      "backwardMass" -> s.backwardMass
    )
    weights.iterator.map { case (k, w) => w * comps.getOrElse(k, 0.0) }.sum
