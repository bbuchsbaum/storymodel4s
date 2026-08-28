package storymodel4s.interview

import storymodel4s.core.*
import storymodel4s.features.ScoreEstimate
import storymodel4s.recall.DiscourseFunction

/** How a detail relates to an episode (design record §63.1). */
enum EpisodeScope:
  case TargetSpecific, OtherSpecific, Extended, RepeatedOrCategoric

enum PersonalKnowledgeKind:
  case AutobiographicalFact, SelfKnowledge, RelationshipKnowledge, LifePeriodKnowledge,
    HabitOrRoutine

/** Discourse destinations of the Autobiographical Interview: not memory content at all.
  *
  * `Association` (thematic/associative comment, design record §6.1) and `Inference` (content the
  * participant derives rather than reports remembering) extend the §63.1 list so that every
  * recall-side [[storymodel4s.recall.DiscourseFunction]] has an explicit destination; nothing is
  * ever routed to an episode by default (see [[MemoryAddress.discourseOf]]).
  */
enum InterviewDiscourseFunction:
  case Metacognitive, Editorial, Evaluation, ConversationalRepair, TaskCommentary, Association,
    Inference
  case Repetition(of: DetailId)

/** Where a detail is addressed in memory. Traditional "internal/external" is a projection of this
  * vocabulary by a scoring policy, never a primitive.
  */
enum MemoryAddress:
  case Episode(id: EpisodeId, scope: EpisodeScope)
  case PersonalKnowledge(kind: PersonalKnowledgeKind)
  case GeneralKnowledge
  case Discourse(function: InterviewDiscourseFunction)
  case Unresolved

  def isTargetSpecific: Boolean = this match
    case Episode(_, EpisodeScope.TargetSpecific) => true
    case _                                       => false

object MemoryAddress:
  /** Total mapping from a recall unit's discourse function to a non-episodic destination.
    *
    * `None` means the function carries episodic content whose episode must be induced
    * (`EpisodicAssertion`, `Summary`); every other function has an explicit non-episodic home.
    * `Uninterpretable` speech is `Unresolved`, not discourse and not an episode.
    */
  def discourseOf(f: DiscourseFunction): Option[MemoryAddress] = f match
    case DiscourseFunction.EpisodicAssertion => None
    case DiscourseFunction.Summary           => None
    case DiscourseFunction.Inference   => Some(Discourse(InterviewDiscourseFunction.Inference))
    case DiscourseFunction.Association =>
      Some(Discourse(InterviewDiscourseFunction.Association))
    case DiscourseFunction.Evaluation => Some(Discourse(InterviewDiscourseFunction.Evaluation))
    case DiscourseFunction.SourceMonitoring =>
      Some(Discourse(InterviewDiscourseFunction.Metacognitive))
    case DiscourseFunction.TaskCommentary =>
      Some(Discourse(InterviewDiscourseFunction.TaskCommentary))
    case DiscourseFunction.Uninterpretable => Some(Unresolved)

/** Traditional facet of an internal detail. */
enum DetailFacet:
  case Event, Place, Time, Perceptual, ThoughtEmotion, Other

/** A sparse, normalized probability mass over a finite set of alternatives.
  *
  * Why: every assessment axis is a distribution, not a label; hard labels are produced only by a
  * declared policy at scoring time.
  */
final case class Distribution[A] private (weights: Map[A, Double]):
  def apply(a: A): Double = weights.getOrElse(a, 0.0)
  def support: Set[A] = weights.keySet
  def mass(pred: A => Boolean): Double = weights.iterator.collect {
    case (a, w) if pred(a) => w
  }.sum

  /** Shannon entropy in nats. */
  def entropy: Double =
    -weights.values.iterator.filter(_ > 0.0).map(p => p * math.log(p)).sum

  /** Alternative with the largest mass; ties broken by rendered name for determinism. */
  def mode: A =
    given Ordering[(Double, String)] =
      Ordering.Tuple2(using Ordering.Double.TotalOrdering, Ordering.String.reverse)
    weights.toVector.maxBy { case (a, w) => (w, a.toString) }._1

  def map[B](f: A => B): Distribution[B] =
    Distribution(weights.groupMapReduce { case (a, _) => f(a) } { case (_, w) => w }(_ + _))

  /** Drop alternatives failing `pred` and renormalize; `None` when nothing is left. */
  def filter(pred: A => Boolean): Option[Distribution[A]] =
    Distribution.of(weights.filter { case (a, _) => pred(a) }).toOption

  def toVector: Vector[(A, Double)] = weights.toVector.sortBy { case (a, w) => (-w, a.toString) }

object Distribution:
  /** Normalize nonnegative weights; fails when they are all zero or any is negative/non-finite. */
  def of[A](pairs: Iterable[(A, Double)]): Either[DomainError, Distribution[A]] =
    val merged = pairs.groupMapReduce(_._1)(_._2)(_ + _)
    if merged.values.exists(w => w < 0.0 || w.isNaN || w.isInfinite) then
      Left(DomainError.InvalidFormat("Distribution", merged.toString, "negative or non-finite"))
    else
      val total = merged.values.sum
      if total <= 0.0 then
        Left(DomainError.InvalidFormat("Distribution", merged.toString, "zero total mass"))
      else Right(new Distribution(merged.view.mapValues(_ / total).toMap))

  /** For tests and literals only; library code uses [[of]] or [[point]]. */
  def unsafe[A](pairs: (A, Double)*): Distribution[A] =
    of(pairs).fold(e => throw new IllegalArgumentException(e.message), identity)

  def point[A](a: A): Distribution[A] = new Distribution(Map(a -> 1.0))

/** Where the participant says a detail comes from. Vocabulary from design record §75.14. */
enum SourceMonitoring:
  case DirectMemory, Inference, Hearsay, Photo, Diary, FamilyStory, Rehearsed, Unknown

/** Evidence bearing on phenomenological re-experiencing. Content quantity is deliberately absent:
  * re-experiencing must never be inferred from the number of internal details (§59.3).
  */
final case class ExperientialEvidence(
    explicitRating: Option[ScoreEstimate],
    firstPersonLanguage: Boolean,
    sourceMonitoringStatements: Vector[SourceMonitoring]
)

object ExperientialEvidence:
  val none: ExperientialEvidence = ExperientialEvidence(None, false, Vector.empty)

/** The protocol context a detail was produced in. */
final case class PromptContext(phase: InterviewPhase, afterProbe: Option[PromptId])

/** The factorized assessment of one detail (design record §63).
  *
  * The four axes — `address` (target membership), `specificity`, `experiential` (re-experiencing)
  * and any veridicality judgment (absent here: no independent source) — are independent fields by
  * construction; no constructor derives one from another.
  */
final case class DetailAssessment(
    detail: Detail,
    address: Distribution[MemoryAddress],
    facets: Distribution[DetailFacet],
    specificity: ScoreEstimate,
    experiential: ExperientialEvidence,
    epistemicStatus: EpistemicStatus,
    promptContext: PromptContext,
    sourceMonitoring: Option[SourceMonitoring],
    meta: ClaimMeta
):
  /** α_i: probability the detail belongs to the nominated target episode. */
  def targetMass: Double = address.mass(_.isTargetSpecific)
  def massAt(pred: MemoryAddress => Boolean): Double = address.mass(pred)

object DetailAssessment:
  /** Facet distribution implied by an atom's kind; a policy may override. */
  def defaultFacets(atom: DetailAtom): Distribution[DetailFacet] = atom match
    case DetailAtom.EventOccurrence(_)                     => Distribution.point(DetailFacet.Event)
    case DetailAtom.ParticipantFact(_, _, _)               => Distribution.point(DetailFacet.Event)
    case DetailAtom.AttributeFact(AtomTarget.Entity(_), _) =>
      Distribution
        .of(Vector(DetailFacet.Place -> 0.5, DetailFacet.Perceptual -> 0.5))
        .getOrElse(Distribution.point(DetailFacet.Other))
    case DetailAtom.AttributeFact(_, _)     => Distribution.point(DetailFacet.Other)
    case DetailAtom.TemporalFact(_)         => Distribution.point(DetailFacet.Time)
    case DetailAtom.SpatialFact(_)          => Distribution.point(DetailFacet.Place)
    case DetailAtom.PerceptualFact(_, _, _) => Distribution.point(DetailFacet.Perceptual)
    case DetailAtom.MentalStateFact(_, _)   => Distribution.point(DetailFacet.ThoughtEmotion)
    case DetailAtom.RelationalFact(NarrativeRelationRef.Temporal(_, _, _)) =>
      Distribution.point(DetailFacet.Time)
    case DetailAtom.RelationalFact(_) => Distribution.point(DetailFacet.Event)
