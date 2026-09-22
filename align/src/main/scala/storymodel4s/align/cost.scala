package storymodel4s.align

import cats.data.NonEmptySet
import scala.collection.immutable.{SortedMap, SortedSet}
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

/** Nonnegative finite weights over [[CostTerm]] whose aggregate is representable. Not a case class:
  * `fromProduct` would mint a negative, non-finite, or aggregate-overflowing weight vector that
  * [[CostWeights.of]] refuses.
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
  /** Weights must be finite and nonnegative, and their aggregate must be representable as a
    * `Double`. `chart` and `structural` weight the optional evidence-backed distances (ADR 0001 rev
    * 3 §D4b); they are inert whenever those terms are `Missing`.
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
    if !all.forall(w => w >= 0.0 && !w.isNaN && !w.isInfinite) then
      Left(AlignError.InvalidConfig("CostWeights", "weights must be finite and nonnegative"))
    else if !all.sum.isFinite then
      Left(
        AlignError.InvalidConfig(
          "CostWeights",
          "aggregate weight must be finite and representable"
        )
      )
    else
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

/** Why content-term support could not be established for a cell (`hsmm/v4`, ADR 0001 §D5).
  *
  * Both cases name a missing DENOMINATOR, not a low share. Before `hsmm/v4` each of them published
  * the numeric support `1.0` — "fully supported" — for a cell that had measured nothing it could be
  * supported by (bd-01M19956MFSG7076QE4J66T7E9).
  */
enum SupportUnestablishedReason:
  /** No content term could have been measured for this cell: the eligible population is empty. */
  case EmptyEligibility

  /** Eligible terms exist, but their configured weights sum to zero, so the share has no
    * denominator. Reachable with lawful weights: [[CostWeights.of]] admits the all-zero vector.
    */
  case ZeroEligibleWeight

/** Why content-term support is not the question a cost record answers. */
enum SupportNotApplicableReason:
  /** An external state is an alternative to source content, not a comparison with it. */
  case ExternalState

  /** The candidate reference is not a node of the view ([[Exclusion.Unreachable]]). */
  case Unreachable

/** The inspectable basis of one cell's support: which content terms were MEASURED, which were
  * ELIGIBLE (could have been measured for this cell), and the exact weight of each eligible term.
  *
  * A share is a claim about this basis and nothing else, so the basis travels with it: a consumer
  * can see that `0.955` means "3.2 of 3.35 eligible weight" rather than trusting a bare number.
  * Measured terms must be eligible (a term that was measured could have been), and sums run in
  * `CostTerm` order so the share is the same double on every platform.
  *
  * This is a PER-CELL accounting record. It does not prove which weights a cost model priced the
  * cell's `total` with: binding totals to an admitted cost-model invocation is
  * bd-01M1DA6NJXYT4NEA18745FM3KY.
  */
final class CellSupportBasis private (
    val measuredTerms: SortedSet[CostTerm],
    val eligibleTerms: SortedSet[CostTerm],
    val eligibleWeights: SortedMap[CostTerm, Double]
):
  /** Total eligible weight, summed in `CostTerm` order: the share's denominator. */
  def eligibleWeight: Double = eligibleTerms.toVector.map(eligibleWeights).sum

  /** Measured eligible weight, summed in `CostTerm` order: the share's numerator. */
  def measuredWeight: Double = measuredTerms.toVector.map(eligibleWeights).sum

  override def equals(other: Any): Boolean = other match
    case that: CellSupportBasis =>
      measuredTerms == that.measuredTerms && eligibleTerms == that.eligibleTerms &&
      eligibleWeights == that.eligibleWeights
    case _ => false
  override def hashCode: Int = (measuredTerms, eligibleTerms, eligibleWeights).hashCode
  override def toString: String =
    s"CellSupportBasis(measured=${measuredTerms.mkString("{", ",", "}")}, " +
      s"eligibleWeights=${eligibleWeights.mkString("{", ",", "}")})"

object CellSupportBasis:
  private given Ordering[CostTerm] = Ordering.by(_.ordinal)

  /** The checked door, for wire evidence and custom producers: measured terms must be eligible, the
    * weights must cover exactly the eligible terms, and each weight must be finite and nonnegative
    * with a representable sum.
    */
  def of(
      measuredTerms: Set[CostTerm],
      eligibleTerms: Set[CostTerm],
      eligibleWeights: Map[CostTerm, Double]
  ): Either[AlignError, CellSupportBasis] =
    val malformed = (detail: String) => AlignError.MalformedRecord("CellSupportBasis", detail)
    val eligible = SortedSet.from(eligibleTerms)
    val weights = SortedMap.from(eligibleWeights)
    if !measuredTerms.subsetOf(eligibleTerms) then
      Left(malformed("every measured term must be eligible"))
    else if weights.keySet != eligible then
      Left(malformed("eligible weights must cover exactly the eligible terms"))
    else
      weights.collectFirst {
        case (term, w) if w.isNaN || w.isInfinite || w < 0.0 =>
          malformed(s"the eligible weight of $term must be finite and nonnegative")
      } match
        case Some(error) => Left(error)
        case None        =>
          val basis = new CellSupportBasis(SortedSet.from(measuredTerms), eligible, weights)
          // A finite sum in CostTerm order bounds every subset sum in that order (rounding is
          // monotone on nonnegative addends), so the numerator can never overflow either.
          if basis.eligibleWeight.isInfinite then
            Left(malformed("the eligible weight sum must be representable"))
          else Right(basis)

  /** The basis of a producer that priced with `weights` over its DECLARED eligible population.
    * [[CostWeights.of]] already guarantees finite weights with a representable aggregate.
    *
    * Eligibility is taken as declared, never widened. A measured term the producer did not declare
    * eligible is left out of the basis rather than added to it: widening would let an empty or
    * zero-weight declaration publish a share of `1.0` (the defect this carrier removes), and would
    * hide a producer that prices a term it calls ineligible — the present-but-not-eligible defect
    * that once multiplied segment costs DOWN. Left out, such a record fails the checked factory's
    * binding (measured = priced − imputed), so the rebuild laws expose it.
    */
  private[align] def fromWeights(
      measuredTerms: Set[CostTerm],
      eligibleTerms: Set[CostTerm],
      weights: CostWeights
  ): CellSupportBasis =
    val eligible = SortedSet.from(eligibleTerms)
    new CellSupportBasis(
      SortedSet.from(measuredTerms.filter(eligibleTerms)),
      eligible,
      SortedMap.from(eligible.iterator.map(term => term -> weights(term)))
    )

/** The support of one cost record: how much of the evidence its cost could rest on was measured.
  *
  * THREE STATES, AND ONLY ONE HAS A NUMBER. The trait deliberately exposes no share, so a consumer
  * cannot sort, average or regress support across records without first selecting
  * [[SupportAssessment.Assessed]] — an external state or a cell with no denominator cannot enter a
  * numeric comparison with one that measured five terms. Before `hsmm/v4`, one `Double` carried all
  * three meanings, and on the WOG golden the ten external cells published `1.0` with zero terms
  * while the real anchors published `0.955`: the intrusion looked better supported than the source
  * while having measured nothing (bd-01M19956MFSG7076QE4J66T7E9).
  *
  * Every value is derived — by [[SupportAssessment.derive]] from a [[CellSupportBasis]], or as one
  * of the two fixed inapplicable values. No caller and no wire artifact can supply a share or a
  * reason. The concrete classes are not case classes and their constructors are private, so no
  * `apply`, `copy`, `fromProduct` or `Mirror` can mint one.
  */
sealed trait SupportAssessment

object SupportAssessment:
  /** A share in `[0, 1]` of eligible weight that was measured, over a NONEMPTY eligible population
    * with positive eligible weight. Exactly `1.0` when every eligible term was measured (and able
    * to round to `1.0` when an unmeasured term's weight is below the sum's precision: the basis,
    * not the share, says what was measured).
    */
  final class Assessed private (
      val share: Double,
      val basis: CellSupportBasis,
      /** Nonempty by type: an empty population is [[Unestablished]], never assessed. */
      val eligibleTerms: NonEmptySet[CostTerm]
  ) extends SupportAssessment:
    def measuredTerms: SortedSet[CostTerm] = basis.measuredTerms
    def eligibleWeights: SortedMap[CostTerm, Double] = basis.eligibleWeights

    override def equals(other: Any): Boolean = other match
      case that: Assessed => share == that.share && basis == that.basis
      case _              => false
    override def hashCode: Int = (share, basis).hashCode
    override def toString: String = s"Assessed($share, $basis)"

  object Assessed:
    private[SupportAssessment] def derived(
        share: Double,
        basis: CellSupportBasis,
        eligibleTerms: NonEmptySet[CostTerm]
    ): Assessed = new Assessed(share, basis, eligibleTerms)

  /** No share exists: the basis establishes no denominator. The basis stays inspectable. */
  final class Unestablished private (
      val reason: SupportUnestablishedReason,
      val basis: CellSupportBasis
  ) extends SupportAssessment:
    override def equals(other: Any): Boolean = other match
      case that: Unestablished => reason == that.reason && basis == that.basis
      case _                   => false
    override def hashCode: Int = (reason, basis).hashCode
    override def toString: String = s"Unestablished($reason, $basis)"

  object Unestablished:
    private[SupportAssessment] def derived(
        reason: SupportUnestablishedReason,
        basis: CellSupportBasis
    ): Unestablished = new Unestablished(reason, basis)

  /** Content-term support is not the question this record answers. It carries no basis. */
  final class NotApplicable private (val reason: SupportNotApplicableReason)
      extends SupportAssessment:
    override def equals(other: Any): Boolean = other match
      case that: NotApplicable => reason == that.reason
      case _                   => false
    override def hashCode: Int = reason.hashCode
    override def toString: String = s"NotApplicable($reason)"

  object NotApplicable:
    private[SupportAssessment] def of(reason: SupportNotApplicableReason): NotApplicable =
      new NotApplicable(reason)

  /** The support of every external-state record. */
  val externalState: NotApplicable = NotApplicable.of(SupportNotApplicableReason.ExternalState)

  /** The support of [[CostBreakdown.unreachable]]. */
  val unreachable: NotApplicable = NotApplicable.of(SupportNotApplicableReason.Unreachable)

  /** The one derivation of a share or a reason. An empty eligible population and a zero eligible
    * weight are [[Unestablished]] — they have no denominator, so they cannot claim to be fully
    * supported. Otherwise the share is measured over eligible weight, both summed in `CostTerm`
    * order; it is exactly `1.0` when the measured population is the eligible one.
    */
  def derive(basis: CellSupportBasis): Assessed | Unestablished =
    NonEmptySet.fromSet(basis.eligibleTerms) match
      case None => Unestablished.derived(SupportUnestablishedReason.EmptyEligibility, basis)
      case Some(eligible) =>
        val denominator = basis.eligibleWeight
        if !(denominator > 0.0) then
          Unestablished.derived(SupportUnestablishedReason.ZeroEligibleWeight, basis)
        else if basis.measuredTerms == basis.eligibleTerms then
          Assessed.derived(1.0, basis, eligible)
        else Assessed.derived(math.min(1.0, basis.measuredWeight / denominator), basis, eligible)

  /** Rebuild support from wire evidence through the checked [[CellSupportBasis.of]]. */
  def fromEvidence(
      measuredTerms: Set[CostTerm],
      eligibleTerms: Set[CostTerm],
      eligibleWeights: Map[CostTerm, Double]
  ): Either[AlignError, Assessed | Unestablished] =
    CellSupportBasis.of(measuredTerms, eligibleTerms, eligibleWeights).map(derive)

  /** The basis a support value rests on, when it rests on one. */
  def basisOf(support: SupportAssessment): Option[CellSupportBasis] = support match
    case assessed: Assessed           => Some(assessed.basis)
    case unestablished: Unestablished => Some(unestablished.basis)
    case _: NotApplicable             => None

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

  /** Nothing weighted was measured for this cell: its support is assessed at exactly `0`, so the
    * cost rests on no evidence at all. (A cell whose ELIGIBLE weight is zero has no share at all —
    * [[SupportAssessment.Unestablished]] — and is still priced as before `hsmm/v4`; that pricing is
    * a separate estimand question, not a support claim.)
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
final class StructuralMemberEstimate private[align] (
    val member: SourceNodeRef,
    val estimate: Estimate[Double]
):
  // NOT A CASE CLASS. A case class with a private constructor still derives Mirror.ProductOf, whose
  // public fromProduct rebuilds it field by field outside `align` and past AlignWire.memberEstimate
  // - which is the only thing that refuses a NON-FINITE observed estimate. Demonstrated forgeable
  // at a0cf33d; the remedy is confirmed against PlacementResolution at dad96e6.
  override def equals(other: Any): Boolean = other match
    case that: StructuralMemberEstimate => member == that.member && estimate == that.estimate
    case _                              => false
  override def hashCode: Int = (member, estimate).hashCode
  override def toString: String = s"StructuralMemberEstimate(${member.key}, $estimate)"

/** One charted member rejected before reduction by the same contradictions as the ModeGate.
  *
  * Why: incompatible charts must be auditable without becoming candidates for a flattering distance
  * or changing the reducer's estimand.
  */
final class StructuralMemberExclusion private[align] (
    val member: SourceNodeRef,
    val contradictions: Set[Contradiction]
):
  // Sealed for the same reason: AlignWire.memberExclusion refuses an exclusion naming NO
  // contradiction, and fromProduct walked past it. An exclusion with an empty contradiction set is
  // a member recorded as rejected for no reason - absence presented as a judgement.
  override def equals(other: Any): Boolean = other match
    case that: StructuralMemberExclusion =>
      member == that.member && contradictions == that.contradictions
    case _ => false
  override def hashCode: Int = (member, contradictions).hashCode
  override def toString: String = s"StructuralMemberExclusion(${member.key}, $contradictions)"

/** Audit receipt for a segment-level structural reduction.
  *
  * `sourceChartCoverage` counts charts over all source leaves. `observedEstimateCoverage` counts
  * observed estimates over compatible chart members only. They are deliberately separate: provider
  * abstention is not missing source evidence. When the recall unit has no chart, membership cannot
  * be assessed: both member vectors are empty and observed-estimate coverage is `0/0`, while source
  * chart coverage remains available.
  */
final class StructuralReductionReceipt private[align] (
    val reducer: StructuralReducer,
    val members: Vector[StructuralMemberEstimate],
    val excludedMembers: Vector[StructuralMemberExclusion],
    val sourceChartCoverage: StructuralCoverage,
    val observedEstimateCoverage: Coverage
):
  // The receipt is the audit trail for a structural reduction, so a forged one is a receipt
  // attesting a reduction that never happened - members it never compared, coverage it never
  // measured. AlignWire.reductionReceipt checks the coverage relation; fromProduct did not.
  override def equals(other: Any): Boolean = other match
    case that: StructuralReductionReceipt =>
      reducer == that.reducer && members == that.members &&
      excludedMembers == that.excludedMembers &&
      sourceChartCoverage == that.sourceChartCoverage &&
      observedEstimateCoverage == that.observedEstimateCoverage
    case _ => false
  override def hashCode: Int =
    (reducer, members, excludedMembers, sourceChartCoverage, observedEstimateCoverage).hashCode
  override def toString: String =
    s"StructuralReductionReceipt($reducer, ${members.size} members, " +
      s"${excludedMembers.size} excluded, $sourceChartCoverage, $observedEstimateCoverage)"

/** A structural estimate paired with the complete receipt for its membership and reduction.
  *
  * Why: a scalar cost alone cannot reveal provider abstention, incompatible members, coverage, or
  * which member won the declared reducer.
  */
final class StructuralReduction private[align] (
    val estimate: Estimate[Double],
    val receipt: StructuralReductionReceipt
):
  // Pairs a scalar with the receipt it was reduced from. Forging it lets the two DISAGREE - an
  // estimate that its own receipt does not produce - which is the cheapest possible way to publish
  // a number with an audit trail that does not support it.
  override def equals(other: Any): Boolean = other match
    case that: StructuralReduction => estimate == that.estimate && receipt == that.receipt
    case _                         => false
  override def hashCode: Int = (estimate, receipt).hashCode
  override def toString: String = s"StructuralReduction($estimate, $receipt)"

/** The cost of one admissible `(anchor, mode)` state (or external state) for one unit.
  *
  * `missingTerms` names terms that were `Missing` and therefore contributed nothing. Chart and
  * Structural are missing without charts; Sensory is missing when the unit lists no sensory terms.
  * `sourceChartCoverage` is chart availability over source members only; observed estimate coverage
  * and member-level outcomes live in the corresponding `reductions` receipt (ADR 0001 rev 3 §D4b).
  *
  * NOT A CASE CLASS, and the constructor is bare `private`. Until `hsmm/v4` this was a case class
  * with a `private[align]` constructor, so `summon[Mirror.ProductOf[CostBreakdown]].fromProduct`
  * rebuilt it field by field outside `align`, and any `storymodel4s.align.*` subpackage could call
  * the constructor, `apply` or `copy` directly — every door that bypassed
  * [[AlignWire.costBreakdown]] (bd-01M17ZNXY6AS1CMBQJRH3JMNVX). The remaining doors are:
  * [[AlignWire.costBreakdown]] (checked), [[CostBreakdown.unreachable]] (a constant), and the
  * `align`-internal producers in the companion, each of which DERIVES support from the record it
  * builds rather than accepting one.
  */
final class CostBreakdown private (
    val terms: Map[CostTerm, Double],
    val mode: Option[FidelityMode],
    val exclusion: Option[Exclusion],
    val total: Double,
    val missingTerms: Set[CostTerm],
    val sourceChartCoverage: Option[StructuralCoverage],
    val reductions: Map[CostTerm, StructuralReductionReceipt],
    /** How much of the evidence this record's cost could rest on was measured.
      *
      * `total` is scaled up to eligible support, which assumes the unmeasured eligible terms behave
      * like the measured ones. [[SupportAssessment.Assessed]] names that assumption: a share below
      * `1.0` says how much of the cost is extrapolation. The share is exactly `1.0` when every
      * eligible term was measured, but it can also ROUND to `1.0` when the unmeasured eligible
      * weight is below the denominator's precision, so whether anything was assumed is answered by
      * the basis (measured versus eligible terms), not by the number. A consumer comparing costs
      * across cells with different support is comparing claims of different strength, and this is
      * what lets it notice — or refuse. A cell with no denominator is
      * [[SupportAssessment.Unestablished]], and an external or unreachable record is
      * [[SupportAssessment.NotApplicable]]: neither carries a number, so neither can be sorted
      * above a cell that measured something.
      */
    val support: SupportAssessment,
    /** Terms that were PRICED but not MEASURED, with the reason the provider gave.
      *
      * Distinct from [[missingTerms]], and the distinction is the point. A missing term is absent
      * from `terms` and contributes nothing to the total. An imputed term IS in `terms`, carries
      * its full weight into the price, and rests on a declared constant rather than an observation.
      * Conflating them would make the record internally false, because `terms` and `missingTerms`
      * are defined as disjoint and the wire enforces it.
      *
      * Before this existed, these two cells competing for the SAME ranked unit were identical in
      * every published field — one resting on evidence, one on a default:
      *
      * e4 semantic MEASURED 0.5 support 0.9552238805970149 missing {Chart, Structural, Sensory} e5
      * semantic ABSTAINED support 0.9552238805970149 missing {Chart, Structural, Sensory}
      *
      * The price is deliberately unchanged (the M0 decision at [[DefaultLocalCostModel]] stands and
      * is a separate bead); only the accounting moves, so `total` is byte-identical and the
      * assessed share excludes imputed weight from its numerator while keeping it eligible in the
      * denominator.
      */
    val imputedTerms: Map[CostTerm, MissingReason]
):
  def term(t: CostTerm): Double = terms.getOrElse(t, 0.0)
  def has(t: CostTerm): Boolean = terms.contains(t)

  /** Whether this term's scalar is a declared constant rather than an observation. */
  def imputed(t: CostTerm): Boolean = imputedTerms.contains(t)

  /** The member-level audit receipt for an optional structural term, when it was evaluated. */
  def reduction(t: CostTerm): Option[StructuralReductionReceipt] = reductions.get(t)

  /** Not a source state for this unit (bookkeeping only). */
  def excluded: Boolean = exclusion.nonEmpty

  def isDistorted: Boolean = mode.exists(!_.isFaithful)

  /** Facets contradicted by this state's mode. */
  def facets: Set[Facet] = mode.map(_.facetSet).getOrElse(Set.empty)

  /** Reported content asserted as fact (or vice versa): the `Context` facet. */
  def contextMismatch: Boolean = facets.contains(Facet.Context)

  private def parts =
    (
      terms,
      mode,
      exclusion,
      total,
      missingTerms,
      sourceChartCoverage,
      reductions,
      support,
      imputedTerms
    )

  override def equals(other: Any): Boolean = other match
    case that: CostBreakdown => parts == that.parts
    case _                   => false
  override def hashCode: Int = parts.hashCode
  override def toString: String =
    s"CostBreakdown(terms=$terms, mode=$mode, exclusion=$exclusion, total=$total, " +
      s"missingTerms=$missingTerms, sourceChartCoverage=$sourceChartCoverage, " +
      s"reductions=$reductions, support=$support, imputedTerms=$imputedTerms)"

object CostBreakdown:
  /** The price of a record no consumer may prefer, even one that ignores its exclusion. */
  private val OutOfReach: Double = Double.MaxValue / 4

  /** A candidate reference that is not a node of the view: excluded, with no measurement. */
  val unreachable: CostBreakdown =
    new CostBreakdown(
      Map.empty,
      None,
      Some(Exclusion.Unreachable),
      OutOfReach,
      Set.empty,
      None,
      Map.empty,
      SupportAssessment.unreachable,
      Map.empty
    )

  /** The checked door behind [[AlignWire.costBreakdown]]: the constructor is reached only when
    * every record, support and coherence check passes.
    */
  private[align] def checked(
      terms: Map[CostTerm, Double],
      mode: Option[FidelityMode],
      exclusion: Option[Exclusion],
      total: Double,
      missingTerms: Set[CostTerm],
      sourceChartCoverage: Option[StructuralCoverage],
      reductions: Map[CostTerm, StructuralReductionReceipt],
      support: SupportAssessment,
      imputedTerms: Map[CostTerm, MissingReason]
  ): Either[AlignError, CostBreakdown] =
    AlignWire
      .costBreakdownError(
        terms,
        mode,
        exclusion,
        total,
        missingTerms,
        sourceChartCoverage,
        reductions,
        support,
        imputedTerms
      )
      .toLeft(
        new CostBreakdown(
          terms,
          mode,
          exclusion,
          total,
          missingTerms,
          sourceChartCoverage,
          reductions,
          support,
          imputedTerms
        )
      )

  /** An external-state record priced at `total`. Its support is not applicable: an external state
    * is an alternative to source content, so there is nothing for it to have measured. Before
    * `hsmm/v4` it published support `1.0` with zero terms.
    */
  private[align] def external(total: Double): CostBreakdown =
    new CostBreakdown(
      Map.empty,
      None,
      None,
      total,
      Set.empty,
      None,
      Map.empty,
      SupportAssessment.externalState,
      Map.empty
    )

  /** A source record whose support is DERIVED from the record itself: measured terms are the priced
    * terms that were not imputed, over the DECLARED `eligible` population with its exact weights
    * (see [[CellSupportBasis.fromWeights]]: eligibility is never widened to what was measured).
    *
    * ZERO ASSESSED SUPPORT IS AN EXCLUSION, NOT A PRICE. With no weighted evidence the blend
    * contributes nothing and the cost falls to the function prior, which is below the external
    * floor — so a cell that measured nothing would win. Such a cell is [[Exclusion.Unassessable]]:
    * it keeps its basis and its zero share as the audit trail, carries no terms, and `total` is
    * never evaluated for it. Unestablished support is NOT excluded here, deliberately: the cost it
    * is priced at is today's price, and `hsmm/v4` changes only how support is published.
    */
  private[align] def derived(
      terms: Map[CostTerm, Double],
      mode: FidelityMode,
      missingTerms: Set[CostTerm],
      sourceChartCoverage: Option[StructuralCoverage],
      reductions: Map[CostTerm, StructuralReductionReceipt],
      imputedTerms: Map[CostTerm, MissingReason],
      eligible: Set[CostTerm],
      weights: CostWeights,
      total: => Double
  ): CostBreakdown =
    val basis =
      CellSupportBasis.fromWeights(terms.keySet -- imputedTerms.keySet, eligible, weights)
    SupportAssessment.derive(basis) match
      case assessed: SupportAssessment.Assessed if assessed.share <= 0.0 =>
        new CostBreakdown(
          Map.empty,
          None,
          Some(Exclusion.Unassessable),
          OutOfReach,
          Set.empty,
          None,
          Map.empty,
          assessed,
          Map.empty
        )
      case support =>
        new CostBreakdown(
          terms,
          Some(mode),
          None,
          total,
          missingTerms,
          sourceChartCoverage,
          reductions,
          support,
          imputedTerms
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
  * or admit modes: admissibility is [[ModeGate]]'s alone. A model outside `align` builds its
  * records through the checked [[AlignWire.costBreakdown]], with support rebuilt through
  * [[SupportAssessment.fromEvidence]]; it cannot supply a share.
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
    functionPrior + scaleToEligible(present, terms.keySet, eligible, weights)

  /** Scales a partially measured weighted cost up to the support it COULD have had.
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
    * [[CostBreakdown.support]] and can be refused. The blend does NOT refuse on its own — the
    * aligner must produce a posterior — so the refusal is the consumer's to make.
    *
    * THE FACTOR-1 BRANCHES ARE PRICES, NOT SUPPORT CLAIMS. An undeclared (empty) eligible set and a
    * zero eligible weight both leave the cost unscaled, exactly as before `hsmm/v4`; what changed
    * is that neither may be PUBLISHED as full support. [[SupportAssessment.derive]] makes both
    * [[SupportAssessment.Unestablished]]. This function moves no number
    * (bd-01M19956MFSG7076QE4J66T7E9 is a representation change); whether a zero-weight cell should
    * be priced at all is a separate estimand decision.
    */
  private[align] def scaleToEligible(
      presentCost: Double,
      present: Set[CostTerm],
      eligible: Set[CostTerm],
      weights: CostWeights
  ): Double =
    // An empty eligible set means the caller did not declare eligibility, and the safe reading is
    // "everything present was everything possible" - factor 1, today's behaviour. Defaulting to ALL
    // terms would silently scale an unaware caller to a support it never claimed, which is the
    // failure that collapsed every row when I scaled over all terms. It is a PRICE only: support
    // for an empty eligible set is Unestablished, never "fully supported".
    if eligible.isEmpty || present == eligible then presentCost
    else
      val wPresent = present.toVector.map(weights(_)).sum
      val wEligible = eligible.toVector.map(weights(_)).sum
      if !(wPresent > 0.0) || !(wEligible > 0.0) then presentCost
      else
        val minimumPresentForFiniteRatio = wEligible / Double.MaxValue
        if wPresent >= minimumPresentForFiniteRatio then presentCost * (wEligible / wPresent)
        else (presentCost / wPresent) * wEligible

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
    // The provider's own answer is kept, not collapsed to a Double, so that "abstained" survives to
    // the receipt. The PRICE is unchanged - `missingSemantic` is still substituted, and the M0
    // decision at cost.scala:705 stands - but the substitution is now recorded rather than silent.
    val semEstimate = semantic(unit, node)
    val dSem = clamp(semEstimate.toOption.getOrElse(missingSemantic))
    val semImputed: Map[CostTerm, MissingReason] = semEstimate match
      case Estimate.Missing(reason) => Map(CostTerm.Semantic -> reason)
      case _                        => Map.empty
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
    val chartedMembers = view.structuralMembers(node.ref).exists(_.hasEvidence)
    val chartEligible = unit.evidence.nonEmpty && chartedMembers
    val structuralEligible =
      structural != StructuralDistance.missing && unit.evidence.nonEmpty && chartedMembers
    val eligible = always.map(_._1).toSet ++
      Set(CostTerm.Sensory) ++
      Option.when(chartEligible)(CostTerm.Chart) ++
      Option.when(structuralEligible)(CostTerm.Structural)
    // SUPPORT COUNTS MEASURED WEIGHT, NOT PRICED WEIGHT. An imputed term stays in `terms` (it is
    // priced) and stays in `eligible` (the comparison was possible), so it leaves the numerator
    // only. That is exactly the gap the two-cell court measures: support must differ between a
    // measured and an imputed cell by semanticWeight / eligibleWeight, and nothing else may move.
    // The factory derives the measured population from `terms -- semImputed` itself, so the
    // support basis cannot disagree with the record it describes.
    //
    // ZERO ASSESSED SUPPORT IS AN EXCLUSION, NOT A PRICE (see CostBreakdown.derived). Excluding it
    // drops the state from the space entirely (hsmm.scala builds states from `!b.excluded`), which
    // says the true thing: we have no basis to rank this anchor, rather than a very good one. The
    // total is by-name and is not computed for such a cell.
    CostBreakdown.derived(
      terms,
      mode,
      missing,
      Some(view.structuralCoverage(node.ref)),
      Map(
        CostTerm.Chart -> chartReduction.receipt,
        CostTerm.Structural -> structuralReduction.receipt
      ),
      semImputed,
      eligible,
      weights,
      DefaultLocalCostModel.blend(terms, weights, functionPrior(unit.function), eligible)
    )

  private def clamp(x: Double): Double =
    if x.isNaN then 1.0 else math.max(0.0, math.min(1.0, x))
