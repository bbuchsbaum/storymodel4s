package storymodel4s.interview.scoring

import storymodel4s.core.*
import storymodel4s.features.{Estimate, MissingReason, ScoreEstimate}
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

/** How repetitions are scored. */
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

/** An expected (fractional) count with a bound. */
final case class ExpectedCount(point: Double, interval: Interval)

object ExpectedCount:
  val zero: ExpectedCount = ExpectedCount(0.0, Interval(0.0, 0.0))
  def +(a: ExpectedCount, b: ExpectedCount): ExpectedCount =
    ExpectedCount(
      a.point + b.point,
      Interval(a.interval.low + b.interval.low, a.interval.high + b.interval.high)
    )

/** Traditional score sheet: expected and hard counts per category, per phase and pooled. */
final case class AiCompatibleScores(
    policy: AiScoringPolicy,
    expected: Map[AiCategory, ExpectedCount],
    hard: Map[AiCategory, Int],
    byPhase: Map[InterviewPhase, Map[AiCategory, ExpectedCount]]
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
  def categoryDistribution(
      a: DetailAssessment,
      policy: AiScoringPolicy
  ): Distribution[AiCategory] =
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
    Distribution.of(pairs).getOrElse(Distribution.point(AiCategory.Other))

  private def expectedCounts(
      as: Vector[DetailAssessment],
      policy: AiScoringPolicy
  ): Map[AiCategory, ExpectedCount] =
    val rows = as.map(a => (a.detail.mass, categoryDistribution(a, policy)))
    AiCategory.values.toVector.map { c =>
      val point = rows.map { case (w, d) => w * d(c) }.sum
      val low = rows.collect { case (w, d) if d.mode == c => w * d(c) }.sum
      val high = rows.collect { case (w, d) if d(c) > 0.0 => w }.sum
      c -> ExpectedCount(point, Interval(low, high))
    }.toMap

  def score(
      model: InterviewModel[ModelStatus.Validated],
      policy: AiScoringPolicy = AiScoringPolicy.Standard
  ): AiCompatibleScores =
    val expected = expectedCounts(model.assessments, policy)
    val hard: Map[AiCategory, Int] = policy.hardCountRule match
      case HardCountRule.ExpectedRounded =>
        expected.view.mapValues(e => math.round(e.point).toInt).toMap
      case HardCountRule.ArgmaxAddress =>
        val modes = model.assessments.map(a => categoryDistribution(a, policy).mode)
        AiCategory.values.toVector.map(c => c -> modes.count(_ == c)).toMap
    val phases = model.assessments.map(_.promptContext.phase).distinct
    val byPhase = policy.phaseHandling match
      case PhaseHandling.Pooled   => Map.empty[InterviewPhase, Map[AiCategory, ExpectedCount]]
      case PhaseHandling.Separate =>
        phases.map(p => p -> expectedCounts(model.assessmentsIn(p), policy)).toMap
    AiCompatibleScores(policy, expected, hard, byPhase)

/** The multidimensional autobiographical-memory profile of design record §65.2. */
final case class Profile(
    targetMass: Double,
    episodicDensityPerWord: ScoreEstimate,
    episodicDensityPerSecond: ScoreEstimate,
    eventPurity: ScoreEstimate,
    spatiotemporalAnchoring: Double,
    perceptualProfile: Map[Modality, Double],
    mentalStateProfile: Map[MentalStateKind, Double],
    relationalIntegration: ScoreEstimate,
    fragmentation: ScoreEstimate,
    semanticization: Double,
    otherEventDrift: Double,
    redundancy: Double,
    probeGain: ScoreEstimate,
    phenomenologicalEvidence: Double,
    sourceMonitoring: Map[SourceMonitoring, Int]
)

object ProfileScoring:
  /** Probe gain (§65.6): new target-specific mass produced after probing, over free plus new mass.
    * "New" excludes repetitions (they are addressed to `Discourse(Repetition)`). When neither free
    * recall nor post-probe material carries target mass the ratio is undefined and reported as
    * `Missing(Excluded)`, never as 0 or 1.
    */
  def probeGain(model: InterviewModel[ModelStatus.Validated]): ScoreEstimate =
    val free = model.assessments
      .filter(_.promptContext.phase == InterviewPhase.FreeRecall)
      .map(a => a.detail.mass * a.targetMass)
      .sum
    val post = model.assessments
      .filter(_.promptContext.phase != InterviewPhase.FreeRecall)
      .map(a => a.detail.mass * a.targetMass)
      .sum
    if free + post <= 0.0 then Estimate.missing(MissingReason.Excluded)
    else Estimate.observed(post / (free + post))

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
    val targetMass = as.map(a => a.detail.mass * a.targetMass).sum
    val otherSpecific = as
      .map(a =>
        a.detail.mass * a.massAt {
          case MemoryAddress.Episode(_, EpisodeScope.OtherSpecific) => true
          case _                                                    => false
        }
      )
      .sum
    val semantic = as
      .map(a =>
        a.detail.mass * a.massAt {
          case MemoryAddress.PersonalKnowledge(_) | MemoryAddress.GeneralKnowledge => true
          case MemoryAddress.Episode(_, EpisodeScope.RepeatedOrCategoric)          => true
          case _                                                                   => false
        }
      )
      .sum
    val repetition = as
      .map(a =>
        a.detail.mass * a.massAt {
          case MemoryAddress.Discourse(InterviewDiscourseFunction.Repetition(_)) => true
          case _                                                                 => false
        }
      )
      .sum
    val total = as.map(_.detail.mass).sum
    val words = model.source.transcript.participantTokens.count(u =>
      model.source.transcript.atlas.text(u).exists(_.isLetterOrDigit)
    )
    val seconds = model.source.transcript
      .turnsByRole(SpeakerRole.Participant)
      .flatMap(_.audio)
      .map(_.durationMillis)
      .sum / 1000.0
    val densityWord: ScoreEstimate =
      if words == 0 then Estimate.missing(MissingReason.Excluded)
      else Estimate.observed(targetMass / words)
    val densitySec: ScoreEstimate =
      if seconds <= 0.0 then Estimate.missing(MissingReason.Excluded)
      else Estimate.observed(targetMass / seconds)
    val purity: ScoreEstimate =
      if targetMass + otherSpecific <= 0.0 then Estimate.missing(MissingReason.Excluded)
      else Estimate.observed(targetMass / (targetMass + otherSpecific))

    val targetAs = as.filter(_.targetMass >= 0.5)
    val anchoring = targetAs.count { a =>
      a.detail.atom match
        case DetailAtom.TemporalFact(_) | DetailAtom.SpatialFact(_) => true
        case _                                                      => false
    }.toDouble
    val perceptual = targetAs
      .collect { case a =>
        a.detail.atom match
          case DetailAtom.PerceptualFact(_, m, _) => Some(m -> a.detail.mass * a.targetMass)
          case _                                  => None
      }
      .flatten
      .groupMapReduce(_._1)(_._2)(_ + _)
    val mental = targetAs
      .flatMap { a =>
        a.detail.atom match
          case DetailAtom.MentalStateFact(_, s) => Some(s.kind -> a.detail.mass * a.targetMass)
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
      .map(a => a.detail.mass * a.targetMass)
      .sum
    val integration: ScoreEstimate =
      if targetMass <= 0.0 then Estimate.missing(MissingReason.Excluded)
      else Estimate.observed(math.min(1.0, integratedMass / targetMass))

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

    val phenomenology = as.count(a => a.experiential.firstPersonLanguage).toDouble +
      model.source.ratings.flatMap(_.reliving).flatMap(_.toOption).getOrElse(0.0)
    val monitoring = as.flatMap(_.sourceMonitoring).groupMapReduce(identity)(_ => 1)(_ + _)

    Profile(
      targetMass,
      densityWord,
      densitySec,
      purity,
      anchoring,
      perceptual,
      mental,
      integration,
      fragmentation,
      if total <= 0.0 then 0.0 else semantic / total,
      if total <= 0.0 then 0.0 else otherSpecific / total,
      if total <= 0.0 then 0.0 else repetition / total,
      probeGain(model),
      phenomenology,
      monitoring
    )
