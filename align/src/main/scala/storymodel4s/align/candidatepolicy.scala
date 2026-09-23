package storymodel4s.align

import storymodel4s.recall.{RecallUnit, RecallUnitId}

/** Why a strict candidate configuration or generation was refused.
  *
  * Why: strict reference candidates must never be produced from an unchecked budget or from a
  * non-finite score, and the refusal has to reach the caller as a value rather than vanish.
  */
enum CandidateRefusal:
  case NonPositivePerLevel(perLevel: Int)
  case NonPositiveBudget(budget: Int)
  case BudgetBelowPerLevel(budget: Int, perLevel: Int)
  case NonFiniteScore(unit: RecallUnitId, ref: SourceNodeRef)

  def message: String = this match
    case NonPositivePerLevel(k)    => s"perLevel must be positive, got $k"
    case NonPositiveBudget(n)      => s"tie budget must be positive, got $n"
    case BudgetBelowPerLevel(n, k) => s"tie budget $n is below perLevel $k"
    case NonFiniteScore(unit, ref) => s"non-finite semantic score for ${unit.value} at ${ref.key}"

/** The budget a caller asks for. It is an unchecked request; [[StrictCandidateConfig.of]] checks it
  * against `perLevel` and there is no default, so every strict profile states its choice.
  */
enum TieBudgetRequest:
  case Unbounded
  case AtMost(n: Int)

/** A checked tie budget: how many candidates a hierarchy level may nominate, over the union of all
  * channels, after tie completion.
  */
sealed trait TieBudget
object TieBudget:
  case object Unbounded extends TieBudget

  /** At most `n` candidates per level; only [[StrictCandidateConfig.of]] builds one. */
  final class AtMost private[align] (val n: Int) extends TieBudget:
    override def equals(that: Any): Boolean = that match
      case o: AtMost => n == o.n
      case _         => false
    override def hashCode: Int = n.hashCode
    override def toString: String = s"AtMost($n)"

/** How candidate generation resolves candidates whose scores tie at the per-level cut.
  *
  * Why: the historical cut breaks ties by source identifier, so which targets survive depends on
  * how a corpus numbered them (measured by `LocalEvidenceIsolationSuite`). A strict profile must
  * declare a policy that does not, and the policy is part of the evidence identity (ADR 0019,
  * "Candidate tie policy").
  */
sealed trait CandidateTiePolicy
object CandidateTiePolicy:

  /** The historical `sortBy((distance, ref.key)).take(perLevel)` cut, kept by name so frozen
    * presets reproduce. Identifier-dependent, hence never eligible for a reference profile.
    */
  case object HistoricalKeyOrder extends CandidateTiePolicy

  /** Keep every candidate whose score equals the k-th at the cut (exact finite equality), with
    * dense ranks, subject to a union-wide budget.
    */
  final class TieComplete private[align] (val budget: TieBudget) extends CandidateTiePolicy:
    override def equals(that: Any): Boolean = that match
      case o: TieComplete => budget == o.budget
      case _              => false
    override def hashCode: Int = budget.hashCode
    override def toString: String = s"TieComplete($budget)"

  def identifierDependent(policy: CandidateTiePolicy): Boolean = policy match
    case HistoricalKeyOrder => true
    case _: TieComplete     => false

  private[align] def render(policy: CandidateTiePolicy): Vector[String] = policy match
    case HistoricalKeyOrder => Vector("historical-key-order")
    case t: TieComplete     =>
      t.budget match
        case TieBudget.Unbounded => Vector("tie-complete", "unbounded")
        case a: TieBudget.AtMost => Vector("tie-complete", "at-most", a.n.toString)

/** A level whose union of candidates exceeded the budget after tie completion. The level's
  * candidates are withheld, and the overflow is carried to every consumer: a strict reference
  * refuses the unit rather than normalizing over the remaining levels. Only the generator builds
  * one, because `unionSize > budget` is a relation the fields must satisfy.
  */
final class TieOverflow private[align] (val level: Int, val unionSize: Int, val budget: Int):
  override def equals(that: Any): Boolean = that match
    case o: TieOverflow => level == o.level && unionSize == o.unionSize && budget == o.budget
    case _              => false
  override def hashCode: Int = (level, unionSize, budget).hashCode
  override def toString: String = s"TieOverflow(level=$level, union=$unionSize, budget=$budget)"

/** A level where every scored semantic candidate received the same finite score. This records what
  * was measured, the scored population included: the semantic channel did not distinguish these
  * candidates. It does not say the level is uninformative, because lexical hits may still
  * distinguish them.
  */
final class UniformSemanticScores private[align] (val level: Int, val scoredCount: Int):
  override def equals(that: Any): Boolean = that match
    case o: UniformSemanticScores => level == o.level && scoredCount == o.scoredCount
    case _                        => false
  override def hashCode: Int = (level, scoredCount).hashCode
  override def toString: String = s"UniformSemanticScores(level=$level, scored=$scoredCount)"

/** Checked configuration for strict candidate generation. */
final class StrictCandidateConfig private (
    val perLevel: Int,
    val policy: CandidateTiePolicy.TieComplete,
    val lexicalOverlap: Boolean,
    val space: Option[String]
):
  override def toString: String =
    s"StrictCandidateConfig(perLevel=$perLevel, $policy, lexical=$lexicalOverlap, space=$space)"

object StrictCandidateConfig:
  def of(
      perLevel: Int,
      budget: TieBudgetRequest,
      lexicalOverlap: Boolean,
      space: Option[String]
  ): Either[CandidateRefusal, StrictCandidateConfig] =
    if perLevel > 0 then
      val checked: Either[CandidateRefusal, TieBudget] = budget match
        case TieBudgetRequest.Unbounded => Right(TieBudget.Unbounded)
        case TieBudgetRequest.AtMost(n) =>
          if !(n > 0) then Left(CandidateRefusal.NonPositiveBudget(n))
          else if n >= perLevel then Right(new TieBudget.AtMost(n))
          else Left(CandidateRefusal.BudgetBelowPerLevel(n, perLevel))
      checked.map { b =>
        new StrictCandidateConfig(
          perLevel,
          new CandidateTiePolicy.TieComplete(b),
          lexicalOverlap,
          space
        )
      }
    else Left(CandidateRefusal.NonPositivePerLevel(perLevel))

/** One unit's strict candidates: the nominations, plus what the policy withheld or observed. */
final class StrictCandidateSet private[align] (
    val set: CandidateSet,
    val overflow: Vector[TieOverflow],
    val uniformSemantic: Vector[UniformSemanticScores]
):
  override def equals(that: Any): Boolean = that match
    case o: StrictCandidateSet =>
      set == o.set && overflow == o.overflow && uniformSemantic == o.uniformSemantic
    case _ => false
  override def hashCode: Int = (set, overflow, uniformSemantic).hashCode
  override def toString: String =
    s"StrictCandidateSet(${set.size} anchors, overflow=${overflow.size}, " +
      s"uniform=${uniformSemantic.size})"

/** Strict candidates for a set of units, with the policy that produced them. It deliberately has no
  * `without` or `fuse`, so the policy cannot be lost by recombining sets. Use [[candidates]] to
  * feed inference.
  */
final class StrictCandidates private[align] (
    val policy: CandidateTiePolicy,
    val byUnit: Map[RecallUnitId, StrictCandidateSet]
):
  def set(unit: RecallUnitId): StrictCandidateSet =
    byUnit.getOrElse(
      unit,
      new StrictCandidateSet(CandidateSet.unranked, Vector.empty, Vector.empty)
    )

  /** The plain nominations for inference. The overflow and uniform-level records stay on this value
    * and on the [[LocalEvidence]] built from it.
    */
  def candidates: Candidates = Candidates(byUnit.view.mapValues(_.set).toMap)

  override def toString: String = s"StrictCandidates($policy, units=${byUnit.size})"

/** Strict candidate generation: semantic candidates per hierarchy level, cut tie-complete with
  * dense ranks, unioned with lexical hits and bounded by the union-wide budget.
  */
final class StrictCandidateGenerator(
    val semantic: SemanticDistance,
    val config: StrictCandidateConfig
):

  def generate(
      units: Vector[RecallUnit],
      view: SourceView
  ): Either[CandidateRefusal, StrictCandidates] =
    units
      .foldLeft[Either[CandidateRefusal, Vector[(RecallUnitId, StrictCandidateSet)]]](
        Right(Vector.empty)
      ) { (acc, u) => acc.flatMap(done => forUnit(u, view).map(s => done :+ (u.id -> s))) }
      .map(pairs => new StrictCandidates(config.policy, pairs.toMap))

  def forUnit(unit: RecallUnit, view: SourceView): Either[CandidateRefusal, StrictCandidateSet] =
    val channel = Channels.semantic(config.space)
    val lexicalRefs: Set[SourceNodeRef] =
      if config.lexicalOverlap then CandidateGenerator.lexicalHits(unit, view).toSet else Set.empty
    val levels = view.byLevel.toVector.sortBy(_._1)
    val scoredByLevel: Either[CandidateRefusal, Vector[(Int, Vector[(SourceNodeRef, Double)])]] =
      levels.foldLeft[Either[CandidateRefusal, Vector[(Int, Vector[(SourceNodeRef, Double)])]]](
        Right(Vector.empty)
      ) { case (acc, (level, nodes)) =>
        acc.flatMap { done =>
          val scored = nodes.flatMap(n => semantic(unit, n).toOption.map(d => (n.ref, d)))
          scored.find((_, d) => !d.isFinite) match
            case Some((ref, _)) => Left(CandidateRefusal.NonFiniteScore(unit.id, ref))
            case None           => Right(done :+ (level -> scored))
        }
      }
    scoredByLevel.map { byLevel =>
      val anyRanked = byLevel.exists(_._2.nonEmpty)
      val perLevel = byLevel.map { (level, scored) =>
        val sorted = scored.sortBy(_._2)
        val kept =
          if sorted.size <= config.perLevel then sorted
          else
            val cutoff = sorted(config.perLevel - 1)._2
            sorted.filter((_, d) => d <= cutoff)
        val distinct = kept.map(_._2).distinct.sorted
        val semanticNoms = kept.map { (r, d) =>
          Nomination(r, channel, distinct.indexOf(d), Some(d), config.space, Some(s"level:$level"))
        }
        val levelNodes = view.byLevel.getOrElse(level, Vector.empty).map(_.ref).toSet
        val lexicalNoms = lexicalRefs
          .intersect(levelNodes)
          .toVector
          .sorted
          .map(r => Nomination(r, Channels.lexical, 0, None, None, None))
        val union = (semanticNoms.map(_.ref) ++ lexicalNoms.map(_.ref)).distinct.size
        val overflow = config.policy.budget match
          case a: TieBudget.AtMost if union > a.n => Some(new TieOverflow(level, union, a.n))
          case _                                  => None
        val uniform =
          if scored.size >= 2 && scored.forall(_._2 == scored.head._2) then
            Some(new UniformSemanticScores(level, scored.size))
          else None
        (if overflow.isEmpty then semanticNoms ++ lexicalNoms else Vector.empty, overflow, uniform)
      }
      val nominations = perLevel.flatMap(_._1)
      val set =
        if nominations.isEmpty && !anyRanked && lexicalRefs.isEmpty then CandidateSet.unranked
        else CandidateSet(nominations, abstained = false)
      new StrictCandidateSet(set, perLevel.flatMap(_._2), perLevel.flatMap(_._3))
    }
