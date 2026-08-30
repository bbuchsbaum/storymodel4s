package storymodel4s.interview.scoring

import storymodel4s.core.*
import storymodel4s.interview.scoring.{
  Conditional,
  ExclusionCause,
  PlacementGrain,
  PlacementResolution
}
import storymodel4s.features.{Coverage, Estimate, MissingReason, ScoreEstimate}
import storymodel4s.interview.*
import storymodel4s.recall.RecallUnitId
import storymodel4s.story.ModelStatus

/** Categories of the traditional Autobiographical Interview score sheet. */
enum AiCategory:
  case InternalEvent, InternalPlace, InternalTime, InternalPerceptual, InternalThoughtEmotion
  case ExternalEvent, ExternalSemantic, Repetition, Other

  def isInternal: Boolean = this match
    case InternalEvent | InternalPlace | InternalTime | InternalPerceptual |
        InternalThoughtEmotion =>
      true
    case _ => false

/** How the policy turns fractional expected counts into whole numbers when required. */
enum HardCountRule:
  case ExpectedRounded, ArgmaxAddress

/** How repetitions are scored: counted in their own column, or removed from the count entirely. */
enum RepetitionRule:
  case CountAsRepetition, Ignore

/** How phases are combined. */
enum PhaseHandling:
  case Separate, Pooled

/** A versioned projection from memory addresses and facets onto traditional AI categories.
  *
  * Why versioned: manual scoring conventions differ across labs and manual editions; the policy is
  * data so that the same artifact can be re-scored under another convention.
  */
final case class AiScoringPolicy(
    version: String,
    internalCategories: Set[DetailFacet],
    externalClasses: Set[EpisodeScope],
    repetitionRule: RepetitionRule,
    phaseHandling: PhaseHandling,
    hardCountRule: HardCountRule
)

object AiScoringPolicy:
  /** Levine-style defaults: all five facets internal; other/extended/repeated episodes external. */
  val Standard: AiScoringPolicy = AiScoringPolicy(
    "ai-standard-0.1",
    Set(
      DetailFacet.Event,
      DetailFacet.Place,
      DetailFacet.Time,
      DetailFacet.Perceptual,
      DetailFacet.ThoughtEmotion
    ),
    Set(EpisodeScope.OtherSpecific, EpisodeScope.Extended, EpisodeScope.RepeatedOrCategoric),
    RepetitionRule.CountAsRepetition,
    PhaseHandling.Separate,
    HardCountRule.ExpectedRounded
  )

/** A closed interval bound on an expected count. */
final case class Interval(low: Double, high: Double):
  def contains(x: Double): Boolean = x >= low - 1e-9 && x <= high + 1e-9

/** An expected (fractional) count with a bound.
  *
  * `calibrationModel` names the model whose probabilities the expectation was computed from. When
  * it is `None` the address masses were raw, uncalibrated scores and the value is a
  * `RawExpectation`: a count-shaped summary of the model's routing, not a calibrated estimate of
  * what a manual scorer would count (AGENTS.md contract 3).
  */
final case class ExpectedCount(point: Double, interval: Interval, calibrationModel: Option[String]):
  def isCalibrated: Boolean = calibrationModel.isDefined
  def label: String = calibrationModel.fold("RawExpectation")(m => s"Calibrated($m)")

object ExpectedCount:
  val zero: ExpectedCount = ExpectedCount(0.0, Interval(0.0, 0.0), None)
  def +(a: ExpectedCount, b: ExpectedCount): ExpectedCount =
    ExpectedCount(
      a.point + b.point,
      Interval(a.interval.low + b.interval.low, a.interval.high + b.interval.high),
      if a.calibrationModel == b.calibrationModel then a.calibrationModel else None
    )

/** Traditional score sheet: expected and hard counts per category, per phase and pooled.
  *
  * `coverage` says how many details carried an observed count mass: details whose mass is `Missing`
  * are excluded from every count rather than read as zero.
  */
final case class AiCompatibleScores(
    policy: AiScoringPolicy,
    expected: Map[AiCategory, ExpectedCount],
    hard: Map[AiCategory, Int],
    byPhase: Map[InterviewPhase, Map[AiCategory, ExpectedCount]],
    coverage: Coverage,
    /** How much of the account's detail mass we actually placed (protocol vehicle, ADR 0003).
      *
      * Distinct from `coverage`, which says whether a detail was OBSERVED. This says how much of
      * its placement was RESOLVED, and it is what keeps our own uncertainty out of the ratio.
      */
    resolution: PlacementResolution
):
  def expectedInternal: Double = expected.collect { case (c, e) if c.isInternal => e.point }.sum
  def expectedExternal: Double = expected.collect { case (c, e) if !c.isInternal => e.point }.sum

  /** The Autobiographical Interview's headline measure, carried with the resolution it rests on.
    *
    * Unresolved mass enters NEITHER the numerator nor the denominator. It used to enter the
    * denominator as an external detail, which depressed the ratio by exactly the amount of our own
    * uncertainty - and that uncertainty is largest for vaguer, more disorganised accounts, which
    * are produced by the very groups these studies compare. A model-uncertainty term correlated
    * with group membership and pushing the headline measure one direction can manufacture a group
    * difference that is not in the data.
    *
    * Returned as `Conditional` so the figure cannot be quoted without the resolution behind it.
    */
  def internalRatio: Conditional[Option[Double]] =
    val t = expectedInternal + expectedExternal
    Conditional(if t <= 0.0 then None else Some(expectedInternal / t), resolution)

/** Projection of assessments onto traditional scores.
  *
  * Expected count of category `c` is `Σ_i w_i · P(category_i = c)` where the category distribution
  * is the address distribution joined with the facet distribution under the policy. The interval
  * `[low, high]` is a posterior-mass bound: `low` sums `w_i · p` only over details whose modal
  * category is `c`; `high` sums `w_i` over every detail giving `c` any mass. Both contain the point
  * estimate by construction; neither is a calibrated credible interval.
  */
object TraditionalScoring:
  /** The category distribution of one assessment, or `None` when the policy removes every
    * alternative (a pure repetition under `RepetitionRule.Ignore` is not counted at all).
    */
  def categoryDistribution(
      a: DetailAssessment,
      policy: AiScoringPolicy
  ): Option[Distribution[AiCategory]] =
    val pairs = a.address.toVector.flatMap { case (addr, pa) =>
      addr match
        case MemoryAddress.Episode(_, EpisodeScope.TargetSpecific) =>
          a.facets.toVector.map { case (f, pf) =>
            val cat = f match
              case DetailFacet.Event          => AiCategory.InternalEvent
              case DetailFacet.Place          => AiCategory.InternalPlace
              case DetailFacet.Time           => AiCategory.InternalTime
              case DetailFacet.Perceptual     => AiCategory.InternalPerceptual
              case DetailFacet.ThoughtEmotion => AiCategory.InternalThoughtEmotion
              case DetailFacet.Other          => AiCategory.Other
            val internal = policy.internalCategories.contains(f)
            (if internal then cat else AiCategory.Other) -> pa * pf
          }
        case MemoryAddress.Episode(_, scope) if policy.externalClasses.contains(scope) =>
          Vector(AiCategory.ExternalEvent -> pa)
        case MemoryAddress.Episode(_, _)        => Vector(AiCategory.Other -> pa)
        case MemoryAddress.PersonalKnowledge(_) => Vector(AiCategory.ExternalSemantic -> pa)
        case MemoryAddress.GeneralKnowledge     => Vector(AiCategory.ExternalSemantic -> pa)
        case MemoryAddress.Discourse(InterviewDiscourseFunction.Repetition(_)) =>
          policy.repetitionRule match
            case RepetitionRule.CountAsRepetition => Vector(AiCategory.Repetition -> pa)
            case RepetitionRule.Ignore            => Vector.empty
        case MemoryAddress.Discourse(_) => Vector(AiCategory.Other -> pa)
        // Unresolved is OUR failure to place the detail, not the participant editorializing.
        // In Levine et al. the external-other column is a positively identified category -
        // metacognitive statements, inferences - and mapping unplaced mass onto it turned
        // "we do not know where this belongs" into a scored external detail. It is now carried
        // as unresolved mass on the score sheet's PlacementResolution instead.
        case MemoryAddress.Unresolved => Vector.empty
    }
    Distribution.of(pairs).toOption

  /** Rows that contribute to counts: observed mass and a non-empty category distribution. */
  private def rows(
      as: Vector[DetailAssessment],
      policy: AiScoringPolicy
  ): Vector[(Double, Distribution[AiCategory])] =
    as.flatMap { a =>
      for
        w <- a.detail.observedMass
        d <- categoryDistribution(a, policy)
      // The category distribution is conditional on placement - it is normalized over the
      // categories that remain once unresolved and policy-excluded mass are removed. So the
      // detail's WEIGHT must be scaled by the fraction actually placed, or dropping those terms
      // would renormalize them away and inflate every remaining count: a detail half of whose
      // mass we could not place would contribute a whole detail's worth of categories.
      yield (w * placedFraction(a, policy), d)
    }

  /** Fraction of a detail's address mass that reaches a scored category. */
  private def placedFraction(a: DetailAssessment, policy: AiScoringPolicy): Double =
    val unplaced = a.address.toVector.collect {
      case (MemoryAddress.Unresolved, p) => p
      case (MemoryAddress.Discourse(InterviewDiscourseFunction.Repetition(_)), p)
          if policy.repetitionRule == RepetitionRule.Ignore =>
        p
    }.sum
    math.max(0.0, 1.0 - unplaced)

  private def calibrationOf(as: Vector[DetailAssessment]): Option[String] =
    val models = as.map(_.meta.credence.calibrationModel).distinct
    models match
      case Vector(Some(m)) => Some(m)
      case _               => None

  private def expectedCounts(
      as: Vector[DetailAssessment],
      policy: AiScoringPolicy
  ): Map[AiCategory, ExpectedCount] =
    val rs = rows(as, policy)
    val model = calibrationOf(as)
    AiCategory.values.toVector.map { c =>
      val point = rs.map { case (w, d) => w * d(c) }.sum
      val low = rs.collect { case (w, d) if d.mode == c => w * d(c) }.sum
      val high = rs.collect { case (w, d) if d(c) > 0.0 => w }.sum
      c -> ExpectedCount(point, Interval(low, high), model)
    }.toMap

  def massCoverage(as: Vector[DetailAssessment]): Coverage =
    Coverage.of(as.size, as.count(_.detail.observedMass.isDefined)).getOrElse(Coverage.empty)

  def score(
      model: InterviewModel[ModelStatus.Validated],
      policy: AiScoringPolicy = AiScoringPolicy.Standard
  ): AiCompatibleScores =
    val expected = expectedCounts(model.assessments, policy)
    val hard: Map[AiCategory, Int] = policy.hardCountRule match
      case HardCountRule.ExpectedRounded =>
        expected.view.mapValues(e => math.round(e.point).toInt).toMap
      case HardCountRule.ArgmaxAddress =>
        val modes = model.assessments.flatMap { a =>
          if a.detail.observedMass.isDefined then categoryDistribution(a, policy).map(_.mode)
          else None
        }
        AiCategory.values.toVector.map(c => c -> modes.count(_ == c)).toMap
    val phases = model.assessments.map(_.promptContext.phase).distinct
    val byPhase = policy.phaseHandling match
      case PhaseHandling.Pooled   => Map.empty[InterviewPhase, Map[AiCategory, ExpectedCount]]
      case PhaseHandling.Separate =>
        phases.map(p => p -> expectedCounts(model.assessmentsIn(p), policy)).toMap
    AiCompatibleScores(
      policy,
      expected,
      hard,
      byPhase,
      massCoverage(model.assessments),
      placementResolution(model.assessments, policy)
    )

  /** The three placement masses of a whole account, normalized over its observed detail mass.
    *
    * `resolved` is mass that reached a scored category, `unresolved` is mass the model declined to
    * place, and `excluded` is mass a POLICY removed - a repetition dropped under
    * `RepetitionRule.Ignore` is a scoring decision, not a model outcome, and folding it into either
    * of the other two would misattribute one for the other.
    */
  private[scoring] def placementResolution(
      as: Vector[DetailAssessment],
      policy: AiScoringPolicy
  ): PlacementResolution =
    var resolved = 0.0
    var unresolved = 0.0
    var excluded = 0.0
    as.foreach { a =>
      a.detail.observedMass.foreach { w =>
        a.address.toVector.foreach { case (addr, pa) =>
          val m = w * pa
          addr match
            case MemoryAddress.Unresolved => unresolved += m
            case MemoryAddress.Discourse(InterviewDiscourseFunction.Repetition(_))
                if policy.repetitionRule == RepetitionRule.Ignore =>
              excluded += m
            case _ => resolved += m
        }
      }
    }
    val total = resolved + unresolved + excluded
    if total <= 0.0 then PlacementResolution.complete(PlacementGrain.Phase)
    else
      // The excluded arm here is ALWAYS RepetitionPolicy: the only branch above that adds to it is
      // a Repetition address under RepetitionRule.Ignore. Attributing it is not bookkeeping - the
      // constructor REFUSES unattributed exclusion, so omitting this makes a live production path
      // throw. Found by codex-storymodel4s-scout; my own three-module gate missed it because no
      // test exercises the Ignore branch, which is a coverage gap worth its own bead rather than a
      // silent pass.
      PlacementResolution
        .of(
          PlacementGrain.Phase,
          resolved / total,
          unresolved / total,
          excluded / total,
          excludedBy =
            if excluded > 0.0 then Map(ExclusionCause.RepetitionPolicy -> excluded / total)
            else Map.empty
        )
        .fold(e => throw new IllegalStateException(e.message), identity)

/** Evidence for phenomenological re-experiencing, reported as separate strands and never summed
  * (design record §59.3; AGENTS.md contract 7).
  *
  * What may enter: the participant's explicit reliving rating; whether each unique recall unit
  * carries first-person language; the per-unit set of source-monitoring statements. What may not:
  * detail or atom counts, specificity, target mass, internal AI totals, or any other quantity of
  * content. `firstPersonRate` is |units with the cue| / |assessed units| (a unit is first-person
  * iff any of its assessments carries the cue). Units without assessments are excluded from the
  * rate but counted in coverage (`eligible` = participant recall units, `observed` = assessed
  * units). `sourceMonitoringUnitCounts` counts units per kind, never details. Atomizing one
  * utterance into many details must not change any strand.
  */
final case class PhenomenologyEvidence(
    explicitRating: Option[ScoreEstimate],
    firstPersonRate: ScoreEstimate,
    firstPersonCoverage: Coverage,
    sourceMonitoringUnitCounts: Map[SourceMonitoring, Int],
    sourceMonitoringRate: ScoreEstimate,
    sourceMonitoringCoverage: Coverage
):
  /** Alias for [[sourceMonitoringUnitCounts]]: unit counts, not detail counts. */
  def sourceMonitoring: Map[SourceMonitoring, Int] = sourceMonitoringUnitCounts

/** A profile rate together with how much of its eligible support was observed.
  *
  * Why a pair: a 1.0 over two assessed units is not a 1.0 over forty participant units. Bead 9
  * (`ProfileStrand`) will wrap these with contributor ids.
  */
final case class CoveredScore(estimate: ScoreEstimate, coverage: Coverage):
  def toOption: Option[Double] = estimate.toOption
  def isObserved: Boolean = estimate.isObserved

/** The multidimensional autobiographical-memory profile of design record §65.2. Ratios whose
  * denominator is empty are `Missing`, never 0.
  *
  * `targetMass` is the raw atom-mass sum for the traditional sheet; it is not a measure of
  * remembering. Density, purity, probe gain, and the other rates are 1-per-unit or 1-per-situation
  * and must not be derived from `targetMass`.
  *
  * Unit membership (purity, semanticization, otherEventDrift, redundancy, target, probeGain): a
  * unit is in a class iff any of its assessments has mass ≥ 0.5 on that class. Residual mass below
  * 0.5 (including induction's 0.045 OtherSpecific leak when alternatives exist) does not classify
  * the unit.
  */
final case class Profile(
    /** Atom-mass sum for the traditional sheet; not a measure of remembering. */
    targetMass: Double,
    massCoverage: Coverage,
    /** UNIT grain: |target units| / participant words. Coverage eligible = participant units. */
    episodicDensityPerWord: CoveredScore,
    /** UNIT grain: |target units| / participant seconds. Coverage eligible = participant units. */
    episodicDensityPerSecond: CoveredScore,
    /** UNIT grain: target units / (target + other-specific units). Membership ≥ 0.5. */
    eventPurity: CoveredScore,
    /** SITUATION grain: unique Anchor / AtLocation / Movement situations. Not
      * TemporalFact.Relation.
      */
    spatiotemporalAnchoring: CoveredScore,
    /** SITUATION grain when the atom has situations; else the source unit. */
    perceptualProfile: Map[Modality, CoveredScore],
    /** UNIT grain: unique source units per kind (`MentalStateFact` has no situation). */
    mentalStateProfile: Map[MentalStateKind, CoveredScore],
    /** SITUATION grain: related situations / target situations. */
    relationalIntegration: CoveredScore,
    /** SITUATION graph: 1 − |largest component| / |target situations|; mental atoms add no node. */
    fragmentation: CoveredScore,
    /** UNIT grain: semantic units / assessed units (PK | GK | RepeatedOrCategoric; Levine maps
      * RepeatedOrCategoric to ExternalEvent, not ExternalSemantic). Membership ≥ 0.5.
      */
    semanticization: CoveredScore,
    /** UNIT grain: other-specific units / assessed units. Membership ≥ 0.5. */
    otherEventDrift: CoveredScore,
    /** UNIT grain: repetition units / assessed units. Membership ≥ 0.5. */
    redundancy: CoveredScore,
    /** UNIT grain: post-probe target units / (free + post target units). */
    probeGain: CoveredScore,
    phenomenology: PhenomenologyEvidence
)

object ProfileScoring:
  /** Shared unit-membership threshold: a unit is in a class iff any assessment has mass ≥ this. */
  private val MembershipThreshold = 0.5

  private enum GrainKey:
    case Situation(id: SituationId)
    case Unit(id: RecallUnitId)

  private def weighted(a: DetailAssessment, share: Double): Double =
    a.detail.observedMass.map(_ * share).getOrElse(0.0)

  private def ratio(num: Double, den: Double): ScoreEstimate =
    if !num.isFinite || !den.isFinite || den <= 0.0 then Estimate.missing(MissingReason.Excluded)
    else Estimate.observed(num / den)

  private def coverageOf(eligible: Int, observed: Int): Coverage =
    Coverage.of(eligible, observed).getOrElse(Coverage.empty)

  private def covered(est: ScoreEstimate, eligible: Int, observed: Int): CoveredScore =
    CoveredScore(est, coverageOf(eligible, observed))

  /** Probe gain (§65.6): |target units after a probe| / |free + post target units|.
    *
    * A unit contributes 1 if any of its assessments in that phase is target-specific (mass ≥ 0.5).
    * Re-atomizing a phase must not change the ratio. When neither phase has a target unit the ratio
    * is `Missing(Excluded)`, never 0 or 1.
    */
  def probeGain(assessments: Vector[DetailAssessment], participantUnits: Int): CoveredScore =
    val byUnit = assessments.groupBy(_.detail.sourceUnit).values
    def hasTarget(phasePred: InterviewPhase => Boolean): Int =
      byUnit.count(as =>
        as.exists(a => phasePred(a.promptContext.phase) && a.targetMass >= MembershipThreshold)
      )
    val free = hasTarget(_ == InterviewPhase.FreeRecall)
    val post = hasTarget(_ != InterviewPhase.FreeRecall)
    covered(ratio(post.toDouble, (free + post).toDouble), participantUnits, free + post)

  def probeGain(model: InterviewModel[ModelStatus.Validated]): CoveredScore =
    probeGain(model.assessments, model.recall.ordered.size)

  /** Phenomenology strands from the assessments and the participant's explicit ratings.
    *
    * Assessments are collapsed by `Detail.sourceUnit`. A unit is first-person iff any of its
    * assessments carries the cue. Rates are over assessed units; `participantUnits` is the model's
    * participant recall-unit count (`RecallGraph.ordered.size`) and becomes coverage eligible.
    * Units without assessments lower coverage and leave both rates unchanged.
    */
  def phenomenology(
      assessments: Vector[DetailAssessment],
      ratings: Option[SubjectiveRatings],
      participantUnits: Int
  ): PhenomenologyEvidence =
    val rating = ratings.flatMap(_.reliving)
    val byUnit = assessments.groupBy(_.detail.sourceUnit)
    val observed = byUnit.size
    Coverage.of(participantUnits, observed) match
      case Left(_) =>
        PhenomenologyEvidence(
          rating,
          Estimate.missing(MissingReason.Excluded),
          Coverage.empty,
          Map.empty,
          Estimate.missing(MissingReason.Excluded),
          Coverage.empty
        )
      case Right(coverage) if observed == 0 =>
        PhenomenologyEvidence(
          rating,
          Estimate.missing(MissingReason.Excluded),
          coverage,
          Map.empty,
          Estimate.missing(MissingReason.Excluded),
          coverage
        )
      case Right(coverage) =>
        val units = byUnit.values.toVector
        def firstPerson(as: Vector[DetailAssessment]): Boolean =
          as.exists(_.experiential.firstPersonLanguage)
        def monitoring(as: Vector[DetailAssessment]): Set[SourceMonitoring] =
          as.flatMap { a =>
            a.sourceMonitoring.toVector ++ a.experiential.sourceMonitoringStatements
          }.toSet
        val sets = units.map(monitoring)
        PhenomenologyEvidence(
          rating,
          Estimate.observed(units.count(firstPerson).toDouble / observed),
          coverage,
          sets.flatten.groupMapReduce(identity)(_ => 1)(_ + _),
          Estimate.observed(sets.count(_.nonEmpty).toDouble / observed),
          coverage
        )

  private def components[A](nodes: Vector[A], edges: Vector[(A, A)]): Vector[Set[A]] =
    val adj = edges.flatMap { case (a, b) => Vector(a -> b, b -> a) }.groupMap(_._1)(_._2)
    var seen = Set.empty[A]
    nodes.flatMap { n =>
      if seen.contains(n) then None
      else
        var comp = Set(n)
        var frontier = List(n)
        while frontier.nonEmpty do
          val x = frontier.head
          frontier = frontier.tail
          adj.getOrElse(x, Vector.empty).foreach { y =>
            if !comp.contains(y) then
              comp += y
              frontier = y :: frontier
          }
        seen ++= comp
        Some(comp)
    }

  private def unitGroups(
      assessments: Vector[DetailAssessment]
  ): Vector[Vector[DetailAssessment]] =
    assessments.groupBy(_.detail.sourceUnit).values.toVector

  private def unitClassified(as: Vector[DetailAssessment])(
      pred: MemoryAddress => Boolean
  ): Boolean =
    as.exists(_.massAt(pred) >= MembershipThreshold)

  private def isTargetUnit(as: Vector[DetailAssessment]): Boolean =
    unitClassified(as)(_.isTargetSpecific)

  /** Typed grain: situations when the atom has them, otherwise the source unit. Never a shared
    * string space of SituationId and RecallUnitId values.
    */
  private def grainKeys(a: DetailAssessment): Set[GrainKey] =
    val sits = a.detail.atom.situations
    if sits.nonEmpty then sits.map(GrainKey.Situation.apply)
    else Set(GrainKey.Unit(a.detail.sourceUnit))

  private def anchoringSituations(atom: DetailAtom): Set[SituationId] =
    atom match
      case DetailAtom.TemporalFact(TemporalClaim.Anchor(s, _))    => Set(s)
      case DetailAtom.SpatialFact(SpatialClaim.AtLocation(s, _))  => Set(s)
      case DetailAtom.SpatialFact(SpatialClaim.Movement(s, _, _)) => Set(s)
      case _                                                      => Set.empty

  private def relationEdges(atom: DetailAtom): Vector[(SituationId, SituationId)] =
    atom match
      case DetailAtom.RelationalFact(NarrativeRelationRef.Causal(c, e))      => Vector(c -> e)
      case DetailAtom.RelationalFact(NarrativeRelationRef.Temporal(f, _, t)) => Vector(f -> t)
      case DetailAtom.RelationalFact(NarrativeRelationRef.Elaborates(p, c))  => Vector(p -> c)
      case DetailAtom.TemporalFact(TemporalClaim.Relation(f, _, t))          => Vector(f -> t)
      case _                                                                 => Vector.empty

  /** Isolated-assessment profile: unit and situation grains, 1 per unit / situation.
    *
    * `participantUnits` is coverage-eligible for the unit rates. `words` and `seconds` are the
    * density denominators (transcript measures, not atom counts).
    */
  def profile(
      assessments: Vector[DetailAssessment],
      participantUnits: Int,
      words: Double,
      seconds: Double,
      ratings: Option[SubjectiveRatings]
  ): Profile =
    val units = unitGroups(assessments)
    val assessed = units.size
    val targetUnits = units.count(isTargetUnit)
    val otherUnits = units.count(
      unitClassified(_) {
        case MemoryAddress.Episode(_, EpisodeScope.OtherSpecific) => true
        case _                                                    => false
      }
    )
    val semanticUnits = units.count(
      unitClassified(_) {
        case MemoryAddress.PersonalKnowledge(_) | MemoryAddress.GeneralKnowledge => true
        case MemoryAddress.Episode(_, EpisodeScope.RepeatedOrCategoric)          => true
        case _                                                                   => false
      }
    )
    val repetitionUnits = units.count(
      unitClassified(_) {
        case MemoryAddress.Discourse(InterviewDiscourseFunction.Repetition(_)) => true
        case _                                                                 => false
      }
    )
    val targetAs = assessments.filter(_.targetMass >= MembershipThreshold)
    val targetSits = targetAs.flatMap(_.detail.atom.situations).toSet
    val anchoringSits = targetAs.flatMap(a => anchoringSituations(a.detail.atom)).toSet
    val sitEligible = math.max(targetSits.size, anchoringSits.size)
    val perceptualCounts = targetAs
      .flatMap { a =>
        a.detail.atom match
          case DetailAtom.PerceptualFact(_, m, _) => grainKeys(a).map(k => (m, k))
          case _                                  => Set.empty
      }
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.toSet)
      .toMap
    val perceptualEligible = perceptualCounts.values.flatten.toSet.size
    val perceptual = perceptualCounts.view.mapValues { ks =>
      val n = ks.size
      covered(
        if n == 0 then Estimate.missing(MissingReason.Excluded) else Estimate.observed(n.toDouble),
        math.max(perceptualEligible, n),
        n
      )
    }.toMap
    val mentalCounts = targetAs
      .flatMap { a =>
        a.detail.atom match
          case DetailAtom.MentalStateFact(_, s) => Some(s.kind -> a.detail.sourceUnit)
          case _                                => None
      }
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.toSet)
      .toMap
    val mental = mentalCounts.view.mapValues { us =>
      val n = us.size
      covered(
        if n == 0 then Estimate.missing(MissingReason.Excluded) else Estimate.observed(n.toDouble),
        math.max(targetUnits, n),
        n
      )
    }.toMap
    val related = targetAs
      .flatMap(a => relationEdges(a.detail.atom))
      .flatMap { case (a, b) =>
        Vector(a, b)
      }
      .toSet
    val nodes = targetSits.toVector
    val edges = targetAs.flatMap(a => relationEdges(a.detail.atom))
    val fragmentationEst: ScoreEstimate =
      if nodes.isEmpty then Estimate.missing(MissingReason.Excluded)
      else
        val largest = components(nodes, edges).map(_.size).maxOption.getOrElse(0)
        Estimate.observed(1.0 - largest.toDouble / nodes.size)
    val targetMass = assessments.map(a => weighted(a, a.targetMass)).sum
    def unitRatio(num: Double, den: Double): ScoreEstimate =
      if assessed == 0 then Estimate.missing(MissingReason.Excluded) else ratio(num, den)
    val unitCov = coverageOf(participantUnits, assessed)
    def unitCovered(est: ScoreEstimate): CoveredScore = CoveredScore(est, unitCov)
    val anchoringEst =
      if anchoringSits.isEmpty then Estimate.missing(MissingReason.Excluded)
      else Estimate.observed(anchoringSits.size.toDouble)
    Profile(
      targetMass,
      TraditionalScoring.massCoverage(assessments),
      unitCovered(unitRatio(targetUnits.toDouble, words)),
      unitCovered(unitRatio(targetUnits.toDouble, seconds)),
      unitCovered(unitRatio(targetUnits.toDouble, (targetUnits + otherUnits).toDouble)),
      covered(anchoringEst, sitEligible, anchoringSits.size),
      perceptual,
      mental,
      covered(ratio(related.size.toDouble, targetSits.size.toDouble), sitEligible, related.size),
      covered(fragmentationEst, sitEligible, nodes.size),
      unitCovered(ratio(semanticUnits.toDouble, assessed.toDouble)),
      unitCovered(ratio(otherUnits.toDouble, assessed.toDouble)),
      unitCovered(ratio(repetitionUnits.toDouble, assessed.toDouble)),
      probeGain(assessments, participantUnits),
      phenomenology(assessments, ratings, participantUnits)
    )

  def profile(model: InterviewModel[ModelStatus.Validated]): Profile =
    val words = model.source.transcript.participantTokens.count(u =>
      model.source.transcript.atlas.text(u).exists(_.isLetterOrDigit)
    )
    val seconds = model.source.transcript
      .turnsByRole(SpeakerRole.Participant)
      .flatMap(_.audio)
      .map(_.durationMillis)
      .sum / 1000.0
    profile(
      model.assessments,
      model.recall.ordered.size,
      words.toDouble,
      seconds,
      model.source.ratings
    )
