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
    discourseChronology: Option[Double],
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
    backwardMass: Option[StepMass],
    worldBackwardMass: Option[StepMass],
    perUnitLocalizability: Map[RecallUnitId, Double],
    perUnitFidelity: Map[RecallUnitId, FidelityReport],
    perUnitMode: Map[RecallUnitId, FidelityMode]
):
  /** External mass, with our own failure held apart from the participant's behaviour.
    *
    * Deliberately NOT a `Double`. The five attributed terms are claims about the person — they said
    * something associative, intrusive, commentarial, source-consistently inferred, or
    * uninterpretable. `unrankedMass` is a claim about US: the aligner could not rank the unit at
    * all (see `AlignState.External(Unranked)`). Summing the two into one number lets our inability
    * to align be quoted as evidence that the participant produced content outside the source — and
    * unranked mass is highest for vaguer, sparser recall, so the error runs one way and correlates
    * with exactly the participant properties a study compares.
    *
    * Returning a pair rather than a scalar is the point: a caller cannot quote the external mass
    * without carrying the caveat, because there is no method that hands back the sum.
    */
  def externalMass: ExternalMassReport =
    ExternalMassReport(
      attributed = associationMass + intrusionMass + commentaryMass +
        sourceConsistentInferenceMass + uninterpretableMass,
      unranked = unrankedMass
    )

/** A per-step route quantity together with the support it rests on.
  *
  * The mean is over EVERY step of the route, not only the steps that carried comparable mass:
  * dropping the others and renormalizing turns "one of three steps moved backward" into "the route
  * moved backward", which is the same manufactured certainty this file exists to remove. The
  * support says how many steps could be compared at all, so a reader can see that a per-step mass
  * of 1/3 rests on one step out of three.
  */
final case class StepMass(perStep: Double, comparableSteps: Int, totalSteps: Int):
  def render: String = f"$perStep%.4f (over $comparableSteps/$totalSteps comparable steps)"

/** External mass split into what the participant did and what we could not do.
  *
  * There is no `total`: re-adding the two halves is exactly the conflation this type exists to
  * prevent, and a caller that genuinely wants the sum must write it at the call site where a
  * reviewer can see it.
  */
final case class ExternalMassReport(attributed: Double, unranked: Double):
  /** Fraction of mass the aligner was able to rank at all — the coverage of `attributed`. */
  def rankedMass: Double = 1.0 - unranked

  def render: String =
    f"external(attributed)=$attributed%.4f unranked=$unranked%.4f ranked=${rankedMass}%.4f"

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

    /** Mean per-step backward mass over EVERY step, with the comparable-step support beside it.
      *
      * `None` only when the route has no steps at all — dividing by `max(1, 0)` used to report 0.0,
      * "this person never moved backwards", when we never saw a move to judge. A step that carried
      * no source-to-source mass is still a step of the route and stays in the denominator;
      * excluding it would renormalize the residue into a stronger claim than the evidence.
      */
    def backwardMean(pos: SourceNodeRef => Option[Double]): Option[StepMass] =
      if f.steps.isEmpty then None
      else
        val comparable = f.steps.count(st =>
          st.sourceMass(isBackward(pos)) > 0.0 || st.sourceMass(isForward(pos)) > 0.0
        )
        val mean = f.steps.map(_.sourceMass(isBackward(pos))).sum / f.steps.size
        Some(StepMass(mean, comparable, f.steps.size))
    val discoursePos: SourceNodeRef => Option[Double] = r => Some(view.relativePosition(r))
    val worldPos: Option[SourceNodeRef => Option[Double]] =
      view.worldOrder.map(o => r => o.get(r).map(_.toDouble))
    // `ordered` already abstains when no step carries directional mass; the previous
    // `.getOrElse(1.0)` threw that away and published PERFECT forward chronology for a recall we
    // could not place at all. The honest value is None.
    val discourse = ordered(discoursePos)
    val world = worldPos.flatMap(ordered)
    val backward = backwardMean(discoursePos)
    val worldBackward = worldPos.flatMap(backwardMean)

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

/** Why a scalar projection could not be produced. Both cases used to be silent zeros. */
enum ProjectionError:
  /** A weight names a component that does not exist — a typo used to delete a term. */
  case UnknownComponent(name: String)

  /** A weighted component has no measurement; the projection abstains rather than inventing one. */
  case MissingComponent(name: String)

  /** A weight set that cannot define a projection: empty, or not finite. */
  case InvalidWeights(detail: String)

  def message: String = this match
    case UnknownComponent(n) => s"projection weight names no such signature component: $n"
    case MissingComponent(n) => s"projection weights $n, which this signature did not measure"
    case InvalidWeights(d)   => s"projection weights are not a usable set: $d"

/** A declared scalar projection of the signature: explicit, versioned weights.
  *
  * Returns `Either` because both failure modes are real and were previously invisible: a weight
  * naming a component that does not exist silently dropped the term (a typo cost you a whole
  * dimension of the score), and a component with no measurement was substituted with 0.0, so "we
  * did not measure this" became "this scored worst" — or, under a negative weight, best.
  */
final class SignatureProjection private (
    val version: String,
    val weights: Map[String, Double]
):
  // Not a case class: a private constructor does not suppress the derived Mirror, whose public
  // fromProduct would rebuild a projection from unvalidated weights.
  override def equals(other: Any): Boolean = other match
    case that: SignatureProjection => version == that.version && weights == that.weights
    case _                         => false

  override def hashCode: Int = (version, weights).hashCode

  override def toString: String = s"SignatureProjection($version, ${weights.size} weights)"

  def apply(s: RecallSignature): Either[ProjectionError, Double] =
    val comps: Map[String, Option[Double]] = Map(
      "uniformCoverage" -> Some(s.uniformCoverage),
      "importanceWeightedCoverage" -> Some(s.importanceWeightedCoverage),
      "fidelity" -> s.fidelity,
      "specificity" -> s.specificity,
      "compression" -> Some(s.compression),
      "discourseChronology" -> s.discourseChronology,
      "worldChronology" -> s.worldChronology,
      "causalPreservation" -> s.causalPreservation,
      "semanticFlowCoherence" -> Some(s.semanticFlowCoherence),
      "associationMass" -> Some(s.associationMass),
      "intrusionMass" -> Some(s.intrusionMass),
      "commentaryMass" -> Some(s.commentaryMass),
      "sourceConsistentInferenceMass" -> Some(s.sourceConsistentInferenceMass),
      "uninterpretableMass" -> Some(s.uninterpretableMass),
      "unrankedMass" -> Some(s.unrankedMass),
      "distortedMass" -> Some(s.distortedMass),
      "backwardMass" -> s.backwardMass.map(_.perStep),
      "worldBackwardMass" -> s.worldBackwardMass.map(_.perStep)
    )
    val terms = weights.toVector.sortBy(_._1).map { case (k, w) =>
      comps.get(k) match
        case None          => Left(ProjectionError.UnknownComponent(k))
        case Some(None)    => Left(ProjectionError.MissingComponent(k))
        case Some(Some(v)) => Right(w * v)
    }
    terms.collectFirst { case Left(e) => e } match
      case Some(e) => Left(e)
      case None    => Right(terms.collect { case Right(v) => v }.sum)

object SignatureProjection:
  /** The known component names, so a weight set can be checked before it is ever applied. */
  val components: Set[String] = Set(
    "uniformCoverage",
    "importanceWeightedCoverage",
    "fidelity",
    "specificity",
    "compression",
    "discourseChronology",
    "worldChronology",
    "causalPreservation",
    "semanticFlowCoherence",
    "associationMass",
    "intrusionMass",
    "commentaryMass",
    "sourceConsistentInferenceMass",
    "uninterpretableMass",
    "unrankedMass",
    "distortedMass",
    "backwardMass",
    "worldBackwardMass"
  )

  /** Smart constructor: a projection with no weights, or a non-finite weight, is not a projection.
    *
    * Empty weights used to produce `Right(0.0)` — a scalar summary of a signature computed from
    * nothing — and a NaN or infinite weight produced a NaN or infinite score that would propagate
    * silently through any aggregate built on it.
    */
  def of(
      version: String,
      weights: Map[String, Double]
  ): Either[ProjectionError, SignatureProjection] =
    if version.trim.isEmpty then Left(ProjectionError.InvalidWeights("version must be non-empty"))
    else if weights.isEmpty then
      Left(ProjectionError.InvalidWeights("a projection needs at least one weighted component"))
    else
      weights.toVector.sortBy(_._1).collectFirst {
        case (k, w) if w.isNaN || w.isInfinite =>
          ProjectionError.InvalidWeights(s"weight for $k is not finite")
        case (k, _) if !components.contains(k) => ProjectionError.UnknownComponent(k)
      } match
        case Some(e) => Left(e)
        case None    => Right(new SignatureProjection(version, weights))
