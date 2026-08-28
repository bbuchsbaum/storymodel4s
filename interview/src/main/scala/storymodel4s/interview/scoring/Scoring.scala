package storymodel4s.interview.scoring

import storymodel4s.core.*
import storymodel4s.features.{Coverage, Estimate, MissingReason, ScoreEstimate}
import storymodel4s.interview.*
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
    coverage: Coverage
):
  def expectedInternal: Double = expected.collect { case (c, e) if c.isInternal => e.point }.sum
  def expectedExternal: Double = expected.collect { case (c, e) if !c.isInternal => e.point }.sum
  def internalRatio: Option[Double] =
    val t = expectedInternal + expectedExternal
    if t <= 0.0 then None else Some(expectedInternal / t)

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
        case MemoryAddress.Unresolved   => Vector(AiCategory.Other -> pa)
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
      yield (w, d)
    }

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
    Coverage.unsafe(as.size, as.count(_.detail.observedMass.isDefined))

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
    AiCompatibleScores(policy, expected, hard, byPhase, massCoverage(model.assessments))

/** Evidence for phenomenological re-experiencing, reported as separate strands and never summed
  * (design record §59.3; AGENTS.md contract 7).
  *
  * What may enter: the participant's explicit reliving rating; whether each unique recall unit
  * carries first-person language; the per-unit set of source-monitoring statements. What may not:
  * detail or atom counts, specificity, target mass, internal AI totals, or any other quantity of
  * content. `firstPersonRate` is |units with the cue| / |units| (a unit is first-person iff any of
  * its assessments carries the cue). `sourceMonitoringUnitCounts` counts units per kind, never
  * details. Both rates carry `Coverage` over those units so a 1.0 over two units is not read like a
  * 1.0 over forty. Atomizing one utterance into many details must not change any strand.
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

/** The multidimensional autobiographical-memory profile of design record §65.2. Ratios whose
  * denominator is empty are `Missing`, never 0.
  */
final case class Profile(
    targetMass: Double,
    massCoverage: Coverage,
    episodicDensityPerWord: ScoreEstimate,
    episodicDensityPerSecond: ScoreEstimate,
    eventPurity: ScoreEstimate,
    spatiotemporalAnchoring: Double,
    perceptualProfile: Map[Modality, Double],
    mentalStateProfile: Map[MentalStateKind, Double],
    relationalIntegration: ScoreEstimate,
    fragmentation: ScoreEstimate,
    semanticization: ScoreEstimate,
    otherEventDrift: ScoreEstimate,
    redundancy: ScoreEstimate,
    probeGain: ScoreEstimate,
    phenomenology: PhenomenologyEvidence
)

object ProfileScoring:
  private def weighted(a: DetailAssessment, share: Double): Double =
    a.detail.observedMass.map(_ * share).getOrElse(0.0)

  private def ratio(num: Double, den: Double): ScoreEstimate =
    if den <= 0.0 then Estimate.missing(MissingReason.Excluded) else Estimate.observed(num / den)

  /** Probe gain (§65.6): new target-specific mass produced after probing, over free plus new mass.
    * "New" excludes repetitions (they are addressed to `Discourse(Repetition)`). When neither free
    * recall nor post-probe material carries target mass the ratio is undefined and reported as
    * `Missing(Excluded)`, never as 0 or 1.
    */
  def probeGain(model: InterviewModel[ModelStatus.Validated]): ScoreEstimate =
    val free = model.assessments
      .filter(_.promptContext.phase == InterviewPhase.FreeRecall)
      .map(a => weighted(a, a.targetMass))
      .sum
    val post = model.assessments
      .filter(_.promptContext.phase != InterviewPhase.FreeRecall)
      .map(a => weighted(a, a.targetMass))
      .sum
    ratio(post, free + post)

  /** Phenomenology strands from the assessments and the participant's explicit ratings.
    *
    * Assessments are collapsed by `Detail.sourceUnit`. A unit is first-person iff any of its
    * assessments carries the cue. Source-monitoring is the union of `sourceMonitoring` and
    * `experiential.sourceMonitoringStatements` on that unit, counted once per unit.
    */
  def phenomenology(
      assessments: Vector[DetailAssessment],
      ratings: Option[SubjectiveRatings]
  ): PhenomenologyEvidence =
    val rating = ratings.flatMap(_.reliving)
    val byUnit = assessments.groupBy(_.detail.sourceUnit)
    val n = byUnit.size
    val coverage = Coverage.unsafe(n, n)
    if n == 0 then
      PhenomenologyEvidence(
        rating,
        Estimate.missing(MissingReason.Excluded),
        coverage,
        Map.empty,
        Estimate.missing(MissingReason.Excluded),
        coverage
      )
    else
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
        Estimate.observed(units.count(firstPerson).toDouble / n),
        coverage,
        sets.flatten.groupMapReduce(identity)(_ => 1)(_ + _),
        Estimate.observed(sets.count(_.nonEmpty).toDouble / n),
        coverage
      )

  private def components(
      nodes: Vector[DetailId],
      edges: Vector[(DetailId, DetailId)]
  ): Vector[Set[DetailId]] =
    val adj = edges.flatMap { case (a, b) => Vector(a -> b, b -> a) }.groupMap(_._1)(_._2)
    var seen = Set.empty[DetailId]
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

  def profile(model: InterviewModel[ModelStatus.Validated]): Profile =
    val as = model.assessments
    val targetMass = as.map(a => weighted(a, a.targetMass)).sum
    val otherSpecific = as
      .map(a =>
        weighted(
          a,
          a.massAt {
            case MemoryAddress.Episode(_, EpisodeScope.OtherSpecific) => true
            case _                                                    => false
          }
        )
      )
      .sum
    val semantic = as
      .map(a =>
        weighted(
          a,
          a.massAt {
            case MemoryAddress.PersonalKnowledge(_) | MemoryAddress.GeneralKnowledge => true
            case MemoryAddress.Episode(_, EpisodeScope.RepeatedOrCategoric)          => true
            case _                                                                   => false
          }
        )
      )
      .sum
    val repetition = as
      .map(a =>
        weighted(
          a,
          a.massAt {
            case MemoryAddress.Discourse(InterviewDiscourseFunction.Repetition(_)) => true
            case _                                                                 => false
          }
        )
      )
      .sum
    val total = as.flatMap(_.detail.observedMass).sum
    val words = model.source.transcript.participantTokens.count(u =>
      model.source.transcript.atlas.text(u).exists(_.isLetterOrDigit)
    )
    val seconds = model.source.transcript
      .turnsByRole(SpeakerRole.Participant)
      .flatMap(_.audio)
      .map(_.durationMillis)
      .sum / 1000.0

    val targetAs = as.filter(_.targetMass >= 0.5)
    val anchoring = targetAs.count { a =>
      a.detail.atom match
        case DetailAtom.TemporalFact(_) | DetailAtom.SpatialFact(_) => true
        case _                                                      => false
    }.toDouble
    val perceptual = targetAs
      .flatMap { a =>
        a.detail.atom match
          case DetailAtom.PerceptualFact(_, m, _) => Some(m -> weighted(a, a.targetMass))
          case _                                  => None
      }
      .groupMapReduce(_._1)(_._2)(_ + _)
    val mental = targetAs
      .flatMap { a =>
        a.detail.atom match
          case DetailAtom.MentalStateFact(_, s) => Some(s.kind -> weighted(a, a.targetMass))
          case _                                => None
      }
      .groupMapReduce(_._1)(_._2)(_ + _)

    // Integration: target mass carried by atoms that participate in an explicit relation.
    val related: Set[SituationId] = targetAs.flatMap { a =>
      a.detail.atom match
        case DetailAtom.RelationalFact(r) =>
          r match
            case NarrativeRelationRef.Causal(c, e)      => Vector(c, e)
            case NarrativeRelationRef.Temporal(f, _, t) => Vector(f, t)
            case NarrativeRelationRef.Elaborates(p, c)  => Vector(p, c)
        case DetailAtom.TemporalFact(TemporalClaim.Relation(f, _, t)) => Vector(f, t)
        case _                                                        => Vector.empty
    }.toSet
    val integratedMass = targetAs
      .filter(a => a.detail.atom.situations.exists(related.contains))
      .map(a => weighted(a, a.targetMass))
      .sum
    val integration: ScoreEstimate =
      ratio(math.min(integratedMass, targetMass), targetMass)

    // Fragmentation: 1 - |largest component| / |target atoms|, atoms linked when they share a
    // situation or are joined by a relational atom.
    val nodes = targetAs.map(_.detail.id)
    val bySit: Map[SituationId, Vector[DetailId]] =
      targetAs.flatMap(a => a.detail.atom.situations.map(_ -> a.detail.id)).groupMap(_._1)(_._2)
    val edges =
      bySit.values.toVector.flatMap(ds => ds.sliding(2).collect { case Vector(a, b) => (a, b) })
    val fragmentation: ScoreEstimate =
      if nodes.isEmpty then Estimate.missing(MissingReason.Excluded)
      else
        val largest = components(nodes, edges).map(_.size).maxOption.getOrElse(0)
        Estimate.observed(1.0 - largest.toDouble / nodes.size)

    Profile(
      targetMass,
      TraditionalScoring.massCoverage(as),
      ratio(targetMass, words.toDouble),
      ratio(targetMass, seconds),
      ratio(targetMass, targetMass + otherSpecific),
      anchoring,
      perceptual,
      mental,
      integration,
      fragmentation,
      ratio(semantic, total),
      ratio(otherSpecific, total),
      ratio(repetition, total),
      probeGain(model),
      phenomenology(as, model.source.ratings)
    )
