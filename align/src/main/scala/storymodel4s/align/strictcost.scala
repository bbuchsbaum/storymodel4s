package storymodel4s.align

import storymodel4s.features.{Coverage, Estimate, MissingReason}
import storymodel4s.proposition.{GateReading, GraphOrder, ProjectionRefusal, SemanticCompatibility}
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked

/** A measured comparison or projection that this strict scoring model cannot consume. */
enum StrictScoringRefusal:
  case Projection(reason: ProjectionRefusal)
  case AmbiguousGates(readings: Set[GateReading])
  case InvalidTable(detail: String)

  def message: String = this match
    case Projection(r)      => s"canonical content projection refused: $r"
    case AmbiguousGates(rs) =>
      s"single-facet model cannot consume ${rs.size} distinct measured gate readings"
    case InvalidTable(d) => s"content table: $d"

/** Closed content adapters prevent a strict scorer from reading coordinate-bearing originals. */
sealed trait StrictSemanticChannel:
  private[align] def score(
      unit: UnitContent[GraphOrder.Canonical],
      target: TargetContent[GraphOrder.Canonical]
  ): Estimate[Double]

  /** Adapter kind, not a claim that a remote provider ran or a digest of its configuration. */
  def kind: String

object StrictSemanticChannel:
  /** A deterministic lemma-set distance whose inputs contain no source coordinates. */
  case object Lexical extends StrictSemanticChannel:
    val kind: String = "lexical"
    private[align] def score(
        u: UnitContent[GraphOrder.Canonical],
        t: TargetContent[GraphOrder.Canonical]
    ): Estimate[Double] = ContentLexical.score(u, t)

  /** Records an unconfigured adapter without invoking or substituting another channel. */
  case object Unavailable extends StrictSemanticChannel:
    val kind: String = "unavailable"
    private[align] def score(
        u: UnitContent[GraphOrder.Canonical],
        t: TargetContent[GraphOrder.Canonical]
    ): Estimate[Double] = Estimate.missing(MissingReason.ChannelUnavailable)

  /** A frozen fixture adapter keyed only by canonical content, never by original identifiers.
    * Declared outcomes are fixture measurements, not evidence of provider execution.
    */
  final class ContentTable private (
      private val entries: Map[
        (UnitContent[GraphOrder.Canonical], TargetContent[GraphOrder.Canonical]),
        Estimate[Double]
      ],
      private val otherwise: Estimate[Double]
  ) extends StrictSemanticChannel:
    val kind: String = "content-table"
    private[align] def score(
        u: UnitContent[GraphOrder.Canonical],
        t: TargetContent[GraphOrder.Canonical]
    ): Estimate[Double] = entries.getOrElse((u, t), otherwise)

    override def equals(other: Any): Boolean = other match
      case t: ContentTable => entries == t.entries && otherwise == t.otherwise
      case _               => false
    override def hashCode: Int = (entries, otherwise).hashCode
    override def toString: String = s"ContentTable(${entries.size} content pairs)"

  object ContentTable:
    /** Refuse conflicting equal-content entries instead of choosing their insertion order. */
    def of(
        entries: Vector[
          (
              (UnitContent[GraphOrder.Canonical], TargetContent[GraphOrder.Canonical]),
              Estimate[Double]
          )
        ],
        otherwise: Estimate[Double] = Estimate.missing(MissingReason.ProviderAbstained)
    ): Either[StrictScoringRefusal, ContentTable] =
      def valid(e: Estimate[Double]): Boolean = e match
        case Estimate.Observed(d, _) => d.isFinite && d >= 0.0 && d <= 1.0
        // Only the library's unavailable adapter issues this reason.
        case Estimate.Missing(MissingReason.ChannelUnavailable) => false
        case _                                                  => true
      if !valid(otherwise) || entries.exists(p => !valid(p._2)) then
        Left(
          StrictScoringRefusal.InvalidTable(
            "distances must be finite in [0,1]; unavailable is not a table outcome"
          )
        )
      else if entries.groupMap(_._1)(_._2).values.exists(_.distinct.size > 1) then
        Left(StrictScoringRefusal.InvalidTable("equal content pairs carry conflicting outcomes"))
      else Right(new ContentTable(entries.toMap, otherwise))

    /** Project fixture coordinates once, retaining only content keys in the resulting adapter. */
    def projected(
        recall: RecallGraph[Checked],
        source: SourceView,
        entries: Vector[((RecallUnitId, SourceNodeRef), Estimate[Double])],
        otherwise: Estimate[Double] = Estimate.missing(MissingReason.ProviderAbstained)
    ): Either[StrictScoringRefusal, ContentTable] =
      val view = MappingBindingRender.snapshot(source)
      entries
        .foldLeft[Either[StrictScoringRefusal, Vector[
          (
              (UnitContent[GraphOrder.Canonical], TargetContent[GraphOrder.Canonical]),
              Estimate[Double]
          )
        ]]](Right(Vector.empty)) { case (acc, ((unit, ref), value)) =>
          acc.flatMap { done =>
            (recall.byId.get(unit), view.node(ref)) match
              case (Some(u), Some(n)) =>
                ContentProjection
                  .canonical(u, n, view)
                  .left
                  .map(StrictScoringRefusal.Projection.apply)
                  .map((uc, tc, _) => done :+ ((uc, tc) -> value))
              case _ =>
                Left(
                  StrictScoringRefusal.InvalidTable(
                    "fixture pair is outside the supplied recall/source"
                  )
                )
          }
        }
        .flatMap(of(_, otherwise))

/** The gate algorithm a result actually re-derived; it does not certify scoring provenance. */
enum GateSemantics:
  case Historical, CanonicalContent

object GateSemantics:
  private[align] def assess(
      semantics: GateSemantics,
      unit: RecallUnit,
      node: NodeSummary,
      view: SourceView
  ): Either[AlignError, Admissibility] = semantics match
    case GateSemantics.Historical       => Right(ModeGate.assess(unit, node, view))
    case GateSemantics.CanonicalContent => StrictScoring.gate(unit, node, view)

/** Compact outcomes retain why a level produced no observed semantic nominations. */
final class SemanticOutcomeSummary private (
    val level: Int,
    val observed: Int,
    val ineligible: Int,
    val missing: Map[MissingReason, Int]
):
  def count: Int = observed + ineligible + missing.values.sum
  override def equals(other: Any): Boolean = other match
    case s: SemanticOutcomeSummary =>
      level == s.level && observed == s.observed && ineligible == s.ineligible && missing == s.missing
    case _ => false
  override def hashCode: Int = (level, observed, ineligible, missing).hashCode
  override def toString: String =
    s"SemanticOutcomeSummary(level=$level, observed=$observed, ineligible=$ineligible, missing=$missing)"

object SemanticOutcomeSummary:
  private[align] def counted(
      level: Int,
      outcomes: Vector[Estimate[Double]]
  ): SemanticOutcomeSummary =
    new SemanticOutcomeSummary(
      level,
      outcomes.count(_.isObserved),
      outcomes.count(!_.isEligible),
      outcomes.collect { case Estimate.Missing(r) => r }.groupMapReduce(identity)(_ => 1)(_ + _)
    )

/** Checked strict prices accept controlled content channels and no arbitrary scoring callback. */
final class StrictCostModel private (
    val semantic: StrictSemanticChannel,
    val weights: CostWeights,
    val functionPrior: FunctionPrior,
    val externalFloor: Double,
    val externalMismatch: Double,
    val missingSemantic: Double,
    val distortionPenalty: Double
):
  private[align] def externalCost(unit: RecallUnit, state: ExternalState): Double =
    if state == ExternalState.Unranked || ExternalStates.natural(unit.function) == state then
      externalFloor
    else externalFloor + externalMismatch

  private[align] def cost(
      unit: RecallUnit,
      node: NodeSummary,
      mode: FidelityMode,
      view: SourceView
  ): Either[AlignError, CostBreakdown] =
    StrictScoring.project(unit, node, view).flatMap { (u, t, grain) =>
      val semanticEstimate = semantic.score(u, t)
      for
        chart <- StrictScoring.reduction(unit, node, view, u, t, chart = true)
        structural <- StrictScoring.reduction(unit, node, view, u, t, chart = false)
        price = ContentCostScoring.terms(
          u,
          t,
          grain,
          mode,
          semanticEstimate,
          chart.estimate,
          structural.estimate,
          structuralConfigured = false,
          missingSemantic,
          distortionPenalty
        )
        result <- CostBreakdown.derived(
          price.values,
          mode,
          price.missing,
          Some(view.structuralCoverage(node.ref)),
          Map(CostTerm.Chart -> chart.receipt, CostTerm.Structural -> structural.receipt),
          price.semanticImputed,
          price.eligible,
          weights,
          DefaultLocalCostModel
            .blend(price.values, weights, functionPrior(u.function), price.eligible),
          Map(CostTerm.Semantic -> semanticEstimate, CostTerm.Chart -> chart.estimate)
        )
      yield result
    }

  override def toString: String = s"StrictCostModel(${semantic.kind}, $weights)"

object StrictCostModel:
  def of(
      semantic: StrictSemanticChannel,
      weights: CostWeights = CostWeights.default,
      functionPrior: FunctionPrior = FunctionPrior.default,
      externalFloor: Double = 1.0,
      externalMismatch: Double = 0.5,
      missingSemantic: Double = 0.5,
      distortionPenalty: Double = 0.3
  ): Either[AlignError, StrictCostModel] =
    val finiteNonnegative =
      Vector(externalFloor, externalMismatch, distortionPenalty) ++ functionPrior.costs.values
    if finiteNonnegative.exists(d =>
        !d.isFinite || d < 0.0
      ) || !(externalFloor + externalMismatch).isFinite
    then Left(AlignError.InvalidConfig("strict cost", "costs must be finite and nonnegative"))
    else if !missingSemantic.isFinite || missingSemantic < 0.0 || missingSemantic > 1.0 then
      Left(
        AlignError.InvalidConfig("strict cost", "missing semantic price must be finite in [0,1]")
      )
    else
      Right(
        new StrictCostModel(
          semantic,
          weights,
          functionPrior,
          externalFloor,
          externalMismatch,
          missingSemantic,
          distortionPenalty
        )
      )

/** Canonical evaluation and reference re-keying are owned by the library, never by adapters. */
private[align] object StrictScoring:
  def project(unit: RecallUnit, node: NodeSummary, view: SourceView) =
    ContentProjection
      .canonical(unit, node, view)
      .left
      .map(r => AlignError.StrictScoring(unit.id, node.ref, StrictScoringRefusal.Projection(r)))

  def gate(
      unit: RecallUnit,
      node: NodeSummary,
      view: SourceView
  ): Either[AlignError, Admissibility] =
    project(unit, node, view).flatMap { (u, t, _) =>
      ContentScoring
        .modeGate(u, t)
        .left
        .map(rs =>
          AlignError.StrictScoring(unit.id, node.ref, StrictScoringRefusal.AmbiguousGates(rs))
        )
    }

  def reduction(
      unit: RecallUnit,
      node: NodeSummary,
      view: SourceView,
      u: UnitContent[GraphOrder.Canonical],
      t: TargetContent[GraphOrder.Canonical],
      chart: Boolean
  ): Either[AlignError, StructuralReduction] =
    val keyed = view.structuralMembers(node.ref).map(_.ref).zip(t.members.structural)
    ContentScoring
      .reduceMembers(
        u,
        keyed,
        (_, member) =>
          if !chart then Estimate.missing(MissingReason.ChannelUnavailable)
          else
            (for
              a <- u.graph
              b <- member.graph
            yield Estimate.observed(1.0 - SemanticCompatibility.compare(a, b).structuralScore))
              .getOrElse(Estimate.missing(MissingReason.ProviderAbstained))
      )
      .left
      .map(rs =>
        AlignError.StrictScoring(unit.id, node.ref, StrictScoringRefusal.AmbiguousGates(rs))
      )
      .map { reduced =>
        val members =
          reduced.scored.map((ref, e) => StructuralMemberEstimate(ref, e)).sortBy(_.member.key)
        val excluded =
          reduced.excluded.map((ref, cs) => StructuralMemberExclusion(ref, cs)).sortBy(_.member.key)
        new StructuralReduction(
          reduced.estimate,
          new StructuralReductionReceipt(
            ContentScoring.Reducer,
            members,
            excluded,
            view.structuralCoverage(node.ref),
            Coverage
              .unsafe(members.count(_.estimate.isEligible), members.count(_.estimate.isObserved))
          )
        )
      }
