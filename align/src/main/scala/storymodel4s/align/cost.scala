package storymodel4s.align

import cats.data.NonEmptySet
import storymodel4s.features.{Coverage, Estimate, MissingReason}
import storymodel4s.proposition.{ChartCompatibility, CompatibilityReport, PropositionEvidence}
import storymodel4s.recall.*

/** Additive terms of the local content cost `C_iv` (design record §7.1). `Distortion` is the
  * documented penalty of a [[FidelityMode.Distorted]] state — one unit of cost per contradicted
  * facet, scaled by the contradiction weight — kept separate from graded semantic cost so a
  * faithful anchor is preferred over a distorted one at equal content match.
  */
enum CostTerm:
  /** `d_sem`: graded dense-geometry distance (embedding or lexical fallback). */
  case Semantic

  /** `d_sketch`: predicate agreement of the shallow sketches — the M0 fallback, never labelled a
    * chart or structural distance.
    */
  case Propositional
  case Entity, Sensory, Granularity, Distortion

  /** `d_chart`: `ChartCompatibility` over checked proposition charts; `Missing` without evidence on
    * both sides.
    */
  case Chart

  /** `d_wl`: injected structural (graph-kernel) distance over charts; `Missing` unless a provider
    * (`embed-grakern`) is configured and evidence exists.
    */
  case Structural

/** Nonnegative finite weights over [[CostTerm]]. Not a case class: `fromProduct` would mint a
  * negative or non-finite weight that [[CostWeights.of]] refuses.
  */
final class CostWeights private (
    val semantic: Double,
    val propositional: Double,
    val entity: Double,
    val sensory: Double,
    val granularity: Double,
    val contradiction: Double,
    val chart: Double,
    val structural: Double
):
  def apply(term: CostTerm): Double = term match
    case CostTerm.Semantic      => semantic
    case CostTerm.Propositional => propositional
    case CostTerm.Entity        => entity
    case CostTerm.Sensory       => sensory
    case CostTerm.Granularity   => granularity
    case CostTerm.Distortion    => contradiction
    case CostTerm.Chart         => chart
    case CostTerm.Structural    => structural

  override def equals(other: Any): Boolean = other match
    case that: CostWeights =>
      semantic == that.semantic && propositional == that.propositional &&
      entity == that.entity && sensory == that.sensory &&
      granularity == that.granularity && contradiction == that.contradiction &&
      chart == that.chart && structural == that.structural
    case _ => false

  override def hashCode(): Int =
    (
      semantic,
      propositional,
      entity,
      sensory,
      granularity,
      contradiction,
      chart,
      structural
    ).hashCode

  override def toString: String =
    s"CostWeights(semantic=$semantic, propositional=$propositional, entity=$entity, " +
      s"sensory=$sensory, granularity=$granularity, contradiction=$contradiction, " +
      s"chart=$chart, structural=$structural)"

object CostWeights:
  /** Weights must be finite and nonnegative. `chart` and `structural` weight the optional
    * evidence-backed distances (ADR 0001 rev 3 §D4b); they are inert whenever those terms are
    * `Missing`.
    */
  def of(
      semantic: Double,
      propositional: Double,
      entity: Double,
      sensory: Double,
      granularity: Double,
      contradiction: Double,
      chart: Double = 0.5,
      structural: Double = 0.5
  ): Either[AlignError, CostWeights] =
    val all =
      Vector(
        semantic,
        propositional,
        entity,
        sensory,
        granularity,
        contradiction,
        chart,
        structural
      )
    if all.forall(w => w >= 0.0 && !w.isNaN && !w.isInfinite) then
      Right(
        new CostWeights(
          semantic,
          propositional,
          entity,
          sensory,
          granularity,
          contradiction,
          chart,
          structural
        )
      )
    else Left(AlignError.InvalidConfig("CostWeights", "weights must be finite and nonnegative"))

  def unsafe(
      semantic: Double,
      propositional: Double,
      entity: Double,
      sensory: Double,
      granularity: Double,
      contradiction: Double,
      chart: Double = 0.5,
      structural: Double = 0.5
  ): CostWeights =
    of(semantic, propositional, entity, sensory, granularity, contradiction, chart, structural)
      .fold(
        e => throw new IllegalArgumentException(e.message),
        identity
      )

  val default: CostWeights = unsafe(1.0, 0.5, 0.4, 0.15, 0.3, 1.0)

  /** Embedding-only weights for the baseline ablation: no structure. */
  val semanticOnly: CostWeights = unsafe(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

/** Structural incompatibilities that embeddings cannot see. Every contradiction is a *fidelity
  * facet* of the anchor: it makes `(anchor, Faithful)` inadmissible and `(anchor,
  * Distorted(facets))` admissible (ADR 0001 rev 3 §D5). None excludes the anchor itself.
  */
enum Contradiction:
  case RoleReversal, PolarityConflict, ContextConflict, ModalityConflict, OutcomeConflict

  def facet: Facet = this match
    case RoleReversal     => Facet.RoleReversal
    case PolarityConflict => Facet.Polarity
    case ContextConflict  => Facet.Context
    case ModalityConflict => Facet.Modality
    case OutcomeConflict  => Facet.Outcome

/** Why a candidate is not a source state for a unit. Only bookkeeping reasons remain: a recall
  * judgement never removes an anchor.
  */
enum Exclusion:
  /** The candidate reference is not a node of the view. */
  case Unreachable

  /** Every term that could have been measured for this cell carries zero weight, so the cost rests
    * on no evidence at all.
    *
    * DISTINCT FROM Unreachable, and the distinction is the point: Unreachable is a claim about the
    * REFERENCE - that node is not in the view. This is a claim about the EVIDENCE - the node is
    * there and we have no weighted basis to judge it. Collapsing them would be the same lie the
    * support carrier exists to prevent.
    *
    * It must be an exclusion rather than a price. With no weighted terms the blend contributes
    * nothing and the cost falls to the function prior alone, which sits BELOW the external floor -
    * so a cell that measured nothing would become the cheapest anchor available. That is absence
    * scored as a perfect match, at the level of a whole cell.
    */
  case Unassessable

/** The declared estimand for reducing compatible structural member estimates.
  *
  * Why: reducer choice changes the scientific question and therefore belongs in every receipt.
  */
enum StructuralReducer:
  /** The closest compatible member represents a segment's structural match. */
  case Minimum

  private[align] def reduce(values: Vector[Double]): Option[Double] = this match
    case Minimum => values.minOption

/** One compatible source member and the estimate produced for it.
  *
  * Why: an aggregate structural cost must remain traceable to the exact source members and provider
  * outcomes from which it was reduced.
  */
final case class StructuralMemberEstimate private[align] (
    member: SourceNodeRef,
    estimate: Estimate[Double]
)

/** One charted member rejected before reduction by the same contradictions as the ModeGate.
  *
  * Why: incompatible charts must be auditable without becoming candidates for a flattering distance
  * or changing the reducer's estimand.
  */
final case class StructuralMemberExclusion private[align] (
    member: SourceNodeRef,
    contradictions: Set[Contradiction]
)

/** Audit receipt for a segment-level structural reduction.
  *
  * `sourceChartCoverage` counts charts over all source leaves. `observedEstimateCoverage` counts
  * observed estimates over compatible chart members only. They are deliberately separate: provider
  * abstention is not missing source evidence. When the recall unit has no chart, membership cannot
  * be assessed: both member vectors are empty and observed-estimate coverage is `0/0`, while source
  * chart coverage remains available.
  */
final case class StructuralReductionReceipt private[align] (
    reducer: StructuralReducer,
    members: Vector[StructuralMemberEstimate],
    excludedMembers: Vector[StructuralMemberExclusion],
    sourceChartCoverage: StructuralCoverage,
    observedEstimateCoverage: Coverage
)

/** A structural estimate paired with the complete receipt for its membership and reduction.
  *
  * Why: a scalar cost alone cannot reveal provider abstention, incompatible members, coverage, or
  * which member won the declared reducer.
  */
final case class StructuralReduction private[align] (
    estimate: Estimate[Double],
    receipt: StructuralReductionReceipt
)

/** The cost of one admissible `(anchor, mode)` state (or external state) for one unit.
  *
  * `missingTerms` names terms that were `Missing` and therefore contributed nothing. Chart and
  * Structural are missing without charts; Sensory is missing when the unit lists no sensory terms.
  * `sourceChartCoverage` is chart availability over source members only; observed estimate coverage
  * and member-level outcomes live in the corresponding `reductions` receipt (ADR 0001 rev 3 §D4b).
  */
final case class CostBreakdown private[align] (
    terms: Map[CostTerm, Double],
    mode: Option[FidelityMode],
    exclusion: Option[Exclusion],
    total: Double,
    missingTerms: Set[CostTerm] = Set.empty,
    sourceChartCoverage: Option[StructuralCoverage] = None,
    reductions: Map[CostTerm, StructuralReductionReceipt] = Map.empty,
    /** Share of the ELIGIBLE term weight actually measured for this cell.
      *
      * `total` is scaled up to eligible support, which assumes the unmeasured eligible terms behave
      * like the measured ones. This names that assumption: 1.0 means nothing was assumed, and a
      * lower value says how much of the cost is extrapolation. A consumer comparing costs across
      * cells with different support is comparing claims of different strength, and this is what
      * lets it notice — or refuse.
      */
    supportWeight: Double = 1.0
):
  def term(t: CostTerm): Double = terms.getOrElse(t, 0.0)
  def has(t: CostTerm): Boolean = terms.contains(t)

  /** The member-level audit receipt for an optional structural term, when it was evaluated. */
  def reduction(t: CostTerm): Option[StructuralReductionReceipt] = reductions.get(t)

  /** Not a source state for this unit (bookkeeping only). */
  def excluded: Boolean = exclusion.nonEmpty

  def isDistorted: Boolean = mode.exists(!_.isFaithful)

  /** Facets contradicted by this state's mode. */
  def facets: Set[Facet] = mode.map(_.facetSet).getOrElse(Set.empty)

  /** Reported content asserted as fact (or vice versa): the `Context` facet. */
  def contextMismatch: Boolean = facets.contains(Facet.Context)

object CostBreakdown:
  def unreachable: CostBreakdown =
    CostBreakdown(Map.empty, None, Some(Exclusion.Unreachable), Double.MaxValue / 4)

  /** A cell with no weighted evidence: excluded, and priced beyond reach so that any consumer which
    * ignores the exclusion still cannot prefer it. Support is 0 because nothing was measured — the
    * honest value, and the one the [0, 1] guard exists to admit.
    */
  def unassessable: CostBreakdown =
    CostBreakdown(
      Map.empty,
      None,
      Some(Exclusion.Unassessable),
      Double.MaxValue / 4,
      supportWeight = 0.0
    )

/** Graded semantic distance in `[0, 1]` between a recall unit and a source node, or `Missing` when
  * the provider abstains (no embedding for the unit, out-of-domain text, budget exceeded). This is
  * the embedding hook: production supplies cosine distance over feature views; tests supply tables.
  * Missing is never coerced to zero: the cost model substitutes a declared neutral value and
  * candidate generation skips the dense ranking for that pair.
  */
trait SemanticDistance:
  def apply(unit: RecallUnit, node: NodeSummary): Estimate[Double]

  /** The distance, or `default` when the provider abstained. */
  def orElse(unit: RecallUnit, node: NodeSummary, default: Double): Double =
    apply(unit, node).toOption.getOrElse(default)

object SemanticDistance:
  /** Jaccard distance over content lemmas: a dependency-free fallback that never abstains. */
  val lexicalJaccard: SemanticDistance = SemanticDistance.of { (unit, node) =>
    val a = unit.proposition.lemmas
    val b = node.lemmas
    if a.isEmpty && b.isEmpty then 1.0
    else 1.0 - a.intersect(b).size.toDouble / a.union(b).size.toDouble
  }

  /** Table-driven distance with a default for unlisted pairs (simulates embedding output). */
  def fromTable(
      table: Map[(RecallUnitId, SourceNodeRef), Double],
      default: Double = 0.9
  ): SemanticDistance =
    SemanticDistance.of((unit, node) => table.getOrElse((unit.id, node.ref), default))

  /** Table-driven distance that abstains on unlisted pairs. */
  def fromTableOrAbstain(table: Map[(RecallUnitId, SourceNodeRef), Double]): SemanticDistance =
    (unit, node) =>
      table.get((unit.id, node.ref)) match
        case Some(d) => Estimate.observed(d)
        case None    => Estimate.missing(MissingReason.ProviderAbstained)

  /** A provider that always abstains. */
  val abstaining: SemanticDistance =
    (_, _) => Estimate.missing(MissingReason.ProviderAbstained)

  /** Lift a total distance function. */
  def of(f: (RecallUnit, NodeSummary) => Double): SemanticDistance =
    (u, n) => Estimate.observed(f(u, n))

  def apply(f: (RecallUnit, NodeSummary) => Estimate[Double]): SemanticDistance = (u, n) => f(u, n)

private[align] object Names:
  /** Token-level overlap: "young man" and "man" co-refer for the purpose of role checks. Labels are
    * compared after lowercasing and stopword removal.
    */
  def tokens(names: Set[String]): Set[String] =
    names.flatMap(n => Lexical.words(n).filterNot(Lexical.stopwords.contains))
  def overlap(a: Set[String], b: Set[String]): Boolean =
    a.exists(b.contains) || tokens(a).exists(tokens(b).contains)

/** `d_wl`: an injected structural distance over proposition charts (the grakern channel of ADR 0001
  * rev 3 §D4b/§D4c). It sees only evidence, never sketches or text, and abstains with `Missing`
  * whenever either side lacks a chart. The default provider abstains always; the `embed-grakern`
  * adapter supplies WL-kernel distances later.
  */
trait StructuralDistance:
  def apply(unit: PropositionEvidence, node: PropositionEvidence): Estimate[Double]

object StructuralDistance:
  /** No structural provider configured: `d_wl` is `Missing` everywhere. */
  val missing: StructuralDistance =
    (_, _) => Estimate.missing(MissingReason.ProviderAbstained)

  def of(f: (PropositionEvidence, PropositionEvidence) => Double): StructuralDistance =
    (a, b) => Estimate.observed(f(a, b))

  def apply(f: (PropositionEvidence, PropositionEvidence) => Estimate[Double]): StructuralDistance =
    (a, b) => f(a, b)

/** `d_chart`: graded chart compatibility turned into a distance, plus the gates the charts carry.
  * Segment membership is established before reduction by the same contradiction detector as the
  * ModeGate. The minimum is taken over observed compatible-member estimates only; missingness is
  * reported in the receipt and never imputed into the cost.
  */
object ChartDistance:
  private val Reducer: StructuralReducer = StructuralReducer.Minimum

  /** Distance between two charts in `[0, 1]`: `1 − structuralScore`. */
  def between(a: PropositionEvidence, b: PropositionEvidence): Double =
    1.0 - ChartCompatibility.compare(a.chart, b.chart).structuralScore

  /** The report the charts give, when both sides carry evidence. */
  def report(unit: RecallUnit, node: NodeSummary): Option[CompatibilityReport] =
    for
      u <- unit.evidence
      n <- node.evidence
    yield ChartCompatibility.compare(u.chart, n.chart)

  /** `d_chart` for a unit against a node of any level.
    *
    * This convenience projection preserves the previous result shape; [[reduction]] exposes its
    * member identities, exclusions, coverage, and reducer.
    */
  def apply(unit: RecallUnit, node: NodeSummary, view: SourceView): Estimate[Double] =
    reduction(unit, node, view).estimate

  /** Full member-level reduction for deterministic chart compatibility.
    *
    * Why: chart availability, compatibility membership, and the resulting scalar are separate
    * claims and must remain independently auditable.
    */
  def reduction(unit: RecallUnit, node: NodeSummary, view: SourceView): StructuralReduction =
    reduce(unit, node, view)((u, member) => Estimate.observed(between(u, member)))

  /** Structural distance projected to its scalar estimate for compatibility with cost callers.
    */
  def structural(
      distance: StructuralDistance,
      unit: RecallUnit,
      node: NodeSummary,
      view: SourceView
  ): Estimate[Double] =
    structuralReduction(distance, unit, node, view).estimate

  /** Full member-level reduction for an injected structural-distance provider.
    *
    * Why: provider abstention changes estimate coverage, not source-chart coverage or the observed
    * reducer value.
    */
  def structuralReduction(
      distance: StructuralDistance,
      unit: RecallUnit,
      node: NodeSummary,
      view: SourceView
  ): StructuralReduction =
    reduce(unit, node, view)(distance.apply)

  private def reduce(
      unit: RecallUnit,
      node: NodeSummary,
      view: SourceView
  )(
      estimate: (PropositionEvidence, PropositionEvidence) => Estimate[Double]
  ): StructuralReduction =
    val sourceCoverage = view.structuralCoverage(node.ref)
    unit.evidence match
      case None =>
        result(
          Estimate.missing(MissingReason.ProviderAbstained),
          Vector.empty,
          Vector.empty,
          sourceCoverage
        )
      case Some(unitEvidence) =>
        val charted = view
          .structuralMembers(node.ref)
          .flatMap(member => member.evidence.map(evidence => member -> evidence))
        val classified = charted.map { case (member, evidence) =>
          val contradictions = ContradictionDetector.detect(unit, member).toSet
          if contradictions.isEmpty then
            Left(
              StructuralMemberEstimate(
                member.ref,
                finite(estimate(unitEvidence, evidence))
              )
            )
          else Right(StructuralMemberExclusion(member.ref, contradictions))
        }
        val members = classified.collect { case Left(member) => member }
        val excluded = classified.collect { case Right(member) => member }
        val observed = members.flatMap(_.estimate.toOption)
        val aggregate =
          if charted.isEmpty || members.isEmpty then Estimate.missing(MissingReason.Excluded)
          else
            Reducer
              .reduce(observed)
              .fold[Estimate[Double]](Estimate.missing(MissingReason.ProviderAbstained))(
                Estimate.observed
              )
        result(aggregate, members, excluded, sourceCoverage)

  private def finite(estimate: Estimate[Double]): Estimate[Double] = estimate match
    case Estimate.Observed(value, credence) => Estimate.score(value, credence)
    case missing @ Estimate.Missing(_)      => missing

  private def result(
      estimate: Estimate[Double],
      members: Vector[StructuralMemberEstimate],
      excluded: Vector[StructuralMemberExclusion],
      sourceCoverage: StructuralCoverage
  ): StructuralReduction =
    val observedEstimateCoverage =
      Coverage.unsafe(members.size, members.count(_.estimate.isObserved))
    StructuralReduction(
      estimate,
      StructuralReductionReceipt(
        Reducer,
        members,
        excluded,
        sourceCoverage,
        observedEstimateCoverage
      )
    )

/** Detects structural contradictions between a recall unit and a source node. When both sides carry
  * checked charts, the chart report decides role reversal, polarity and embedding (context)
  * conflict — chart evidence takes precedence over the sketch heuristics for those three facets —
  * while modality and outcome conflicts still come from the sketch. Rules are conservative: each
  * fires only when the compared slots are both specified.
  */
object ContradictionDetector:
  /** Facets a chart report decides. */
  private val ChartFacets: Set[Contradiction] =
    Set(Contradiction.RoleReversal, Contradiction.PolarityConflict, Contradiction.ContextConflict)

  /** Contradictions carried by a chart report. */
  def fromReport(report: CompatibilityReport): Vector[Contradiction] =
    Vector(
      Option.when(report.roleReversal)(Contradiction.RoleReversal),
      Option.when(report.polarityConflict)(Contradiction.PolarityConflict),
      Option.when(report.embeddingConflict)(Contradiction.ContextConflict)
    ).flatten

  /** Evidence-aware detection for a unit: chart facets from the charts when both exist, sketch
    * facets otherwise; modality/outcome always from the sketch.
    */
  def detect(unit: RecallUnit, node: NodeSummary): Vector[Contradiction] =
    ChartDistance.report(unit, node) match
      case Some(report) =>
        val fromSketch = detect(unit.proposition, node).filterNot(ChartFacets.contains)
        (fromReport(report) ++ fromSketch).distinct
      case None => detect(unit.proposition, node)

  def detect(sketch: PropositionSketch, node: NodeSummary): Vector[Contradiction] =
    val predicateMatch = sketch.predicate.exists(p => node.predicate.exists(_ == p))
    val out = Vector.newBuilder[Contradiction]

    // Role reversal: the recalled patient is the source agent (and the recalled agent is not),
    // or the recalled agent is the source patient (and the recalled patient is not).
    val sAgent = sketch.agent
    val sPatient = sketch.patient
    val nAgent = node.agent
    val nPatient = node.patient
    val patientIsNodeAgent = (sPatient, nAgent) match
      case (Some(p), Some(a)) => Names.overlap(p.names, a.names)
      case _                  => false
    val agentIsNodeAgent = (sAgent, nAgent) match
      case (Some(x), Some(a)) => Names.overlap(x.names, a.names)
      case _                  => false
    val agentIsNodePatient = (sAgent, nPatient) match
      case (Some(x), Some(p)) => Names.overlap(x.names, p.names)
      case _                  => false
    val patientIsNodePatient = (sPatient, nPatient) match
      case (Some(x), Some(p)) => Names.overlap(x.names, p.names)
      case _                  => false
    val reversed =
      (patientIsNodeAgent && sAgent.nonEmpty && !agentIsNodeAgent) ||
        (agentIsNodePatient && sPatient.nonEmpty && !patientIsNodePatient)
    val bothInverted = patientIsNodeAgent && agentIsNodePatient
    if bothInverted || (reversed && predicateMatch) then out += Contradiction.RoleReversal

    if predicateMatch && sketch.polarity != PolarityTag.Unknown &&
      node.polarity != PolarityTag.Unknown && sketch.polarity != node.polarity
    then out += Contradiction.PolarityConflict

    if predicateMatch && sketch.modality == ModalityTag.Asserted &&
      node.context != ContextTag.NarratedWorld
    then out += Contradiction.ContextConflict

    val unrealized = Set(ModalityTag.Intended, ModalityTag.Desired, ModalityTag.Counterfactual)
    val modalityConflict =
      (sketch.modality == ModalityTag.Asserted && unrealized.contains(node.modality)) ||
        (unrealized.contains(sketch.modality) && node.modality == ModalityTag.Asserted)
    if predicateMatch && modalityConflict then out += Contradiction.ModalityConflict

    (sketch.outcome, node.outcome) match
      case (Some(a), Some(b)) if predicateMatch && Lexical.lower(a) != Lexical.lower(b) =>
        out += Contradiction.OutcomeConflict
      case _ => ()

    out.result()

  /** Whether the sketch engages the node structurally at all (shares its predicate or contradicts
    * it); used to decide which leaves count when a segment inherits contradictions.
    */
  def engages(sketch: PropositionSketch, node: NodeSummary): Boolean =
    sketch.predicate.exists(p => node.predicate.contains(p)) || detect(sketch, node).nonEmpty

  /** Evidence-aware engagement: charts with any matched predicate engage; otherwise the sketch
    * rule.
    */
  def engages(unit: RecallUnit, node: NodeSummary): Boolean =
    ChartDistance.report(unit, node) match
      case Some(r) => r.matchedPredicates.nonEmpty || detect(unit, node).nonEmpty
      case None    => engages(unit.proposition, node)

/** Which `(anchor, mode)` pairs a unit may occupy on a candidate node. Decided by [[ModeGate]]
  * before any graded cost is evaluated; `faithful` is false exactly when a contradiction was
  * detected, in which case `distortion` names the contradicted facets and the distorted state is
  * the only admissible mode on that anchor.
  */
final case class Admissibility private[align] (
    contradictions: Vector[Contradiction],
    faithful: Boolean,
    distortion: Option[NonEmptySet[Facet]]
):
  /** The admissible modes on this anchor, in a deterministic order. */
  def modes: Vector[FidelityMode] =
    (if faithful then Vector(FidelityMode.Faithful) else Vector.empty) ++
      distortion.toVector.map(FidelityMode.Distorted(_))

  def facets: Set[Facet] = distortion.map(_.toSortedSet.toSet).getOrElse(Set.empty)

  /** The faithful mode was refused. */
  def gated: Boolean = !faithful

object Admissibility:
  /** Construction is `private[align]`: a record originates only in [[ModeGate]] (or the ungated
    * ablation path), never in a caller — so an admissibility map handed to [[HsmmResult.validated]]
    * cannot be fabricated, and is in any case re-derived there.
    */
  private[align] val faithfulOnly: Admissibility =
    Admissibility(Vector.empty, faithful = true, None)

  private[align] def of(contradictions: Vector[Contradiction]): Admissibility =
    val distinct = contradictions.distinct
    if distinct.isEmpty then faithfulOnly
    else
      Admissibility(
        distinct,
        faithful = false,
        NonEmptySet.fromSet(
          scala.collection.immutable.SortedSet.from(distinct.map(_.facet))
        )
      )

/** The mode gate (ADR 0001 rev 3 §D5, law L1): decides, per unit and candidate anchor, whether the
  * faithful mode is admissible and which distorted mode replaces it. It is a typed prepass owned by
  * [[GraphHsmm]]; no [[LocalCostModel]] can widen it, and no distance, weight, temperature,
  * candidate channel, or refinement pass can reintroduce a refused mode.
  *
  * A segment refuses the faithful mode only when *every* leaf the sketch engages (same predicate or
  * contradicted) is contradicted; its distorted facets are the union over those leaves. Leaves the
  * sketch does not engage do not vote either way, so a scene stays a valid gist target while one
  * compatible leaf exists.
  */
object ModeGate:
  def assess(unit: RecallUnit, node: NodeSummary, view: SourceView): Admissibility =
    if node.isLeaf then Admissibility.of(ContradictionDetector.detect(unit, node))
    else
      val engaged = view
        .leavesUnder(node.ref)
        .flatMap(view.node)
        .filter(ContradictionDetector.engages(unit, _))
      val reports = engaged.map(ContradictionDetector.detect(unit, _))
      if engaged.nonEmpty && reports.forall(_.nonEmpty) then Admissibility.of(reports.flatten)
      else Admissibility.faithfulOnly

/** Prior cost added to every *source* candidate according to the unit's discourse function: an
  * association or a task comment is presumptively external, an episodic assertion is not.
  */
final case class FunctionPrior(costs: Map[DiscourseFunction, Double]):
  def apply(f: DiscourseFunction): Double = costs.getOrElse(f, 0.0)

object FunctionPrior:
  val default: FunctionPrior = FunctionPrior(
    Map(
      DiscourseFunction.EpisodicAssertion -> 0.0,
      DiscourseFunction.Summary -> 0.0,
      DiscourseFunction.Inference -> 0.1,
      DiscourseFunction.SourceMonitoring -> 0.3,
      DiscourseFunction.Association -> 0.5,
      DiscourseFunction.Evaluation -> 0.5,
      DiscourseFunction.TaskCommentary -> 0.6,
      DiscourseFunction.Uninterpretable -> 0.6
    )
  )
  val none: FunctionPrior = FunctionPrior(Map.empty)

/** Local cost model: graded content cost for an *admissible* `(anchor, mode)` state and floor cost
  * for external states. Implementations never see an inadmissible pair (law L3) and cannot refuse
  * or admit modes: admissibility is [[ModeGate]]'s alone.
  */
trait LocalCostModel:
  /** Cost of aligning `unit` to `node` in `mode`; `view` lets segment nodes consult their leaves.
    */
  def cost(unit: RecallUnit, node: NodeSummary, mode: FidelityMode, view: SourceView): CostBreakdown

  /** Cost of the external floor: a source candidate must beat this to be preferred. */
  def externalFloor: Double

  /** Cost of assigning `unit` to external state `state`; the state that is natural for the unit's
    * discourse function costs exactly the floor, others cost more.
    */
  def externalCost(unit: RecallUnit, state: ExternalState): Double

object ExternalStates:
  /** The external destination presupposed by a discourse function. */
  def natural(f: DiscourseFunction): ExternalState = f match
    case DiscourseFunction.Association       => ExternalState.Association
    case DiscourseFunction.Evaluation        => ExternalState.Commentary
    case DiscourseFunction.TaskCommentary    => ExternalState.Commentary
    case DiscourseFunction.SourceMonitoring  => ExternalState.Commentary
    case DiscourseFunction.Inference         => ExternalState.SourceConsistentInference
    case DiscourseFunction.Uninterpretable   => ExternalState.Uninterpretable
    case DiscourseFunction.EpisodicAssertion => ExternalState.Intrusion
    case DiscourseFunction.Summary           => ExternalState.Intrusion

/** The default local cost model. Terms are composed lexicographically after the mode gate: only
  * admissible pairs reach here, and only *present* terms enter the total. The optional
  * evidence-backed terms `d_chart` and `d_wl` are `Missing` without charts (or without a structural
  * provider) and then contribute nothing — they are never imputed from `d_sketch` or `d_sem`.
  * `d_sens` is `Missing` when the unit lists no sensory terms: that is no observation, not a
  * perfect match (a `0.0` would invent agreement). A `Missing` term is recorded in
  * `CostBreakdown.missingTerms`. The dense term `d_sem` keeps its M0 behaviour: an abstaining
  * provider is replaced by the declared neutral `missingSemantic` (a constant, not another term),
  * because unranked units are already routed to `Unranked` upstream.
  */

/** The local cost blend, extracted so the arithmetic is addressable.
  *
  * This number decides which alignment wins, and every assertion that compares one computed total
  * to another survives a systematic change to it: both sides move together. So it is pinned by
  * literal expected values in `CostSuite`, not by recomputation through this same function.
  */
object DefaultLocalCostModel:

  /** Weighted sum over the terms actually present, in `CostTerm` enum order, plus the unit's
    * discourse-function prior. Absent terms contribute nothing — they are not zero-valued terms,
    * they are terms the model could not compute, and the distinction is recorded separately in
    * `CostBreakdown.missingTerms`.
    */
  private[align] def blend(
      terms: Map[CostTerm, Double],
      weights: CostWeights,
      functionPrior: Double,
      eligible: Set[CostTerm] = Set.empty
  ): Double =
    val present = CostTerm.values.toVector.flatMap(t => terms.get(t).map(weights(t) * _)).sum
    functionPrior + present * scaleToEligible(terms.keySet, eligible, weights)

  /** `W_eligible / W_present` — scales a partially measured cost up to the support it COULD have
    * had.
    *
    * Scaling to ELIGIBLE weight, not to all terms, is the whole point. A term that could never have
    * been measured for this cell — `d_chart` where neither side carries a chart — is not a
    * measurement we failed to make, and inflating as though it were invents a dimension the data
    * cannot have. Measured on WOG: eligible-aware gives 1.0000 for fully measured cells and 1.0469
    * for cells missing only Sensory; scaling over all terms gives 1.2985 and 1.3594, and collapses
    * every row to External.
    *
    * IT IS STILL AN IMPUTATION: it assumes the unmeasured eligible terms behave like the measured
    * ones. The difference from imputing per term is that this one is named in
    * [[CostBreakdown.supportWeight]] and can be refused. The blend does NOT refuse on its own — the
    * aligner must produce a posterior — so the refusal is the consumer's to make.
    */
  private[align] def scaleToEligible(
      present: Set[CostTerm],
      eligible: Set[CostTerm],
      weights: CostWeights
  ): Double =
    // An empty eligible set means the caller did not declare eligibility, and the safe reading is
    // "everything present was everything possible" - factor 1, today's behaviour. Defaulting to ALL
    // terms would silently scale an unaware caller to a support it never claimed, which is the
    // failure that collapsed every row when I scaled over all terms.
    if eligible.isEmpty then 1.0
    else
      val wPresent = present.toVector.map(weights(_)).sum
      val wEligible = eligible.toVector.map(weights(_)).sum
      if !(wPresent > 0.0) || !(wEligible > 0.0) then 1.0 else wEligible / wPresent

  /** Share of the ELIGIBLE term weight that was actually measured; 1.0 when nothing was assumed. */
  private[align] def supportOf(
      present: Set[CostTerm],
      eligible: Set[CostTerm],
      weights: CostWeights
  ): Double =
    if eligible.isEmpty then 1.0
    else
      val wEligible = eligible.toVector.map(weights(_)).sum
      if !(wEligible > 0.0) then 1.0
      else math.min(1.0, present.toVector.map(weights(_)).sum / wEligible)

final case class DefaultLocalCostModel(
    weights: CostWeights = CostWeights.default,
    semantic: SemanticDistance = SemanticDistance.lexicalJaccard,
    functionPrior: FunctionPrior = FunctionPrior.default,
    externalFloor: Double = 1.0,
    externalMismatch: Double = 0.5,
    missingSemantic: Double = 0.5,
    distortionPenalty: Double = 0.3,
    structural: StructuralDistance = StructuralDistance.missing
) extends LocalCostModel:

  def externalCost(unit: RecallUnit, state: ExternalState): Double =
    if state == ExternalState.Unranked then externalFloor
    else if ExternalStates.natural(unit.function) == state then externalFloor
    else externalFloor + externalMismatch

  def cost(
      unit: RecallUnit,
      node: NodeSummary,
      mode: FidelityMode,
      view: SourceView
  ): CostBreakdown =
    val sketch = unit.proposition
    val dSem = clamp(semantic.orElse(unit, node, missingSemantic))
    val dProp = (sketch.predicate, node.predicate) match
      case (Some(a), Some(b)) => if a == b then 0.0 else 1.0
      case _                  => 0.5
    val specified = sketch.participants.filter(_.specified)
    // Role-aware: a name found in the same role earns full credit, in another role half credit.
    val dEnt =
      if specified.isEmpty then 0.5
      else
        val credit = specified.map { p =>
          if node.byRole(p.role).exists(n => Names.overlap(p.names, n.names)) then 1.0
          else if Names.overlap(p.names, node.allNames) then 0.5
          else 0.0
        }.sum
        1.0 - credit / specified.size.toDouble
    val dSens =
      if sketch.sensoryTerms.isEmpty then Estimate.missing(MissingReason.AllMissing)
      else
        val hits = sketch.sensoryTerms.count(t => node.lemmas.contains(Lexical.stem(t)))
        Estimate.observed(1.0 - hits.toDouble / sketch.sensoryTerms.size.toDouble)
    // Preferred abstraction level: summaries want a scene, thematic/evaluative remarks want the
    // global level, predicate-bearing assertions want a leaf, predicate-less ones a scene.
    val preferred = unit.function match
      case DiscourseFunction.Summary                                    => 1
      case DiscourseFunction.Association | DiscourseFunction.Evaluation => view.maxLevel
      case _ if sketch.predicate.isEmpty && sketch.participants.isEmpty => 1
      case _                                                            => 0
    val dGran = math.min(1.0, 0.5 * math.abs(node.level - preferred))
    // The distortion term: `distortionPenalty` per contradicted facet, weighted by
    // `contradiction`. It separates a distorted anchor from a faithful one at equal content
    // match and must stay below the external floor so a well-matched distorted anchor is
    // preferred to intrusion (design record §9). Provisional until W4 calibration.
    val dDist = distortionPenalty * mode.facetSet.size.toDouble
    // Optional evidence-backed terms: present only when charts exist on both sides (and, for
    // `d_wl`, a provider answered). Absent terms are inert and recorded, never substituted.
    val chartReduction = ChartDistance.reduction(unit, node, view)
    val structuralReduction = ChartDistance.structuralReduction(structural, unit, node, view)
    val dChart = chartReduction.estimate
    val dWl = structuralReduction.estimate
    val always = Vector(
      CostTerm.Semantic -> dSem,
      CostTerm.Propositional -> dProp,
      CostTerm.Entity -> dEnt,
      CostTerm.Granularity -> dGran,
      CostTerm.Distortion -> dDist
    )
    val optional =
      Vector(CostTerm.Chart -> dChart, CostTerm.Structural -> dWl, CostTerm.Sensory -> dSens)
    val present = optional.collect { case (t, Estimate.Observed(v, _)) => t -> clamp(v) }
    val missing = optional.collect { case (t, Estimate.Missing(_)) => t }.toSet
    val terms = (always ++ present).toMap
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
    // wEligible, and the blend multiplied the cost DOWN while supportOf's clamp reported the
    // over-unity ratio as full support. `leavesUnder` returns the node itself for a leaf, so this
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
    val chartedMembers = view.structuralMembers(node.ref).exists(_.hasEvidence)
    val chartEligible = unit.evidence.nonEmpty && chartedMembers
    val structuralEligible =
      structural != StructuralDistance.missing && unit.evidence.nonEmpty && chartedMembers
    val eligible = always.map(_._1).toSet ++
      Set(CostTerm.Sensory) ++
      Option.when(chartEligible)(CostTerm.Chart) ++
      Option.when(structuralEligible)(CostTerm.Structural)
    val support = DefaultLocalCostModel.supportOf(terms.keySet, eligible, weights)
    // ZERO SUPPORT IS AN EXCLUSION, NOT A PRICE. With no weighted evidence the blend contributes
    // nothing and the cost falls to the function prior, which is below the external floor - so the
    // cell that measured nothing would win. Excluding it drops the state from the space entirely
    // (hsmm.scala builds states from `!b.excluded`), which says the true thing: we have no basis to
    // rank this anchor, rather than a very good one.
    if support <= 0.0 then CostBreakdown.unassessable
    else
      val weighted =
        DefaultLocalCostModel.blend(terms, weights, functionPrior(unit.function), eligible)
      CostBreakdown(
        terms,
        Some(mode),
        None,
        weighted,
        missing,
        Some(view.structuralCoverage(node.ref)),
        Map(
          CostTerm.Chart -> chartReduction.receipt,
          CostTerm.Structural -> structuralReduction.receipt
        ),
        support
      )

  private def clamp(x: Double): Double =
    if x.isNaN then 1.0 else math.max(0.0, math.min(1.0, x))
