package storymodel4s.acquire

import cats.Order
import storymodel4s.core.DomainError

/** Independent critic specialisations. Each critic receives the candidate and exact evidence, not
  * another agent's reasoning.
  */
enum CriticFamily:
  case SyntaxGraphLaw
  case FrameRole
  case SourceEntailment
  case PolarityModalityContext
  case DocumentIdentity
  case TemporalConsistency
  case CausalOverreach
  case HierarchyCoherence

/** How much a finding weighs on resolution. `Blocking` findings prevent acceptance. */
enum Severity:
  case Info, Warning, Blocking

object Severity:
  given Order[Severity] = Order.by(_.ordinal)
  given Ordering[Severity] = Order[Severity].toOrdering

/** Closed vocabulary of typed evidence codes a critic may emit (design record §86, §95).
  *
  * Why closed: findings must be aggregable, benchmarkable, and translatable into review cards
  * without parsing prose.
  */
enum FindingCode(val severity: Severity):
  // syntax / graph laws
  case MalformedStructure extends FindingCode(Severity.Blocking)
  case DanglingReference extends FindingCode(Severity.Blocking)
  case DuplicateTriple extends FindingCode(Severity.Warning)
  // frame / role
  case UnknownFrame extends FindingCode(Severity.Warning)
  case ArgumentNotLicensed extends FindingCode(Severity.Warning)
  case WrongParticipant extends FindingCode(Severity.Blocking)
  case RoleReversalSuspected extends FindingCode(Severity.Blocking)
  // source entailment
  case MissingConcept extends FindingCode(Severity.Warning)
  case HallucinatedConcept extends FindingCode(Severity.Blocking)
  case SpanInadequate extends FindingCode(Severity.Warning)
  case SpanMismatch extends FindingCode(Severity.Blocking)
  // polarity / modality / context
  case ReportTreatedAsFact extends FindingCode(Severity.Blocking)
  case PolarityMisattached extends FindingCode(Severity.Blocking)
  case EmbeddingMisplaced extends FindingCode(Severity.Blocking)
  case ModalityMisattached extends FindingCode(Severity.Warning)
  case IntendedTreatedAsRealized extends FindingCode(Severity.Blocking)
  // document identity
  case DuplicateOccurrence extends FindingCode(Severity.Blocking)
  case OverMerge extends FindingCode(Severity.Blocking)
  case UnderMerge extends FindingCode(Severity.Warning)
  // temporal
  case TemporalCycle extends FindingCode(Severity.Blocking)
  case OrderContradiction extends FindingCode(Severity.Warning)
  // causal
  case UnsupportedCause extends FindingCode(Severity.Blocking)
  case PrecedenceAsCause extends FindingCode(Severity.Blocking)
  // hierarchy
  case EmptySegment extends FindingCode(Severity.Warning)
  case IncoherentBoundary extends FindingCode(Severity.Info)
  // generic
  case AlternativeAnalysis extends FindingCode(Severity.Info)
  case PrefersSourceSupportedCandidate extends FindingCode(Severity.Info)
  case PrefersFoil extends FindingCode(Severity.Blocking)

  def isBlocking: Boolean = severity == Severity.Blocking

/** What a finding is about. */
enum TargetKind:
  case Node, Edge, Claim, Sentence, Token, Chart

final case class TargetRef(kind: TargetKind, id: String)

/** Optional explanatory text attached to a critic finding.
  *
  * Why: an empty present note is observationally identical to no note but previously produced a
  * distinct structural value, so only nonblank text may inhabit the present branch.
  */
object FindingNote:
  opaque type FindingNote = String

  /** Validates a note without trimming or otherwise rewriting admitted text. */
  def from(raw: String): Either[DomainError, FindingNote] =
    if raw.exists(char => !char.isWhitespace) then Right(raw)
    else Left(DomainError.InvalidFormat("FindingNote", raw, "must not be blank"))

  /** Validating constructor for literals and trusted fixtures; throws when `raw` is blank. */
  def unsafe(raw: String): FindingNote =
    from(raw).fold(error => throw new IllegalArgumentException(error.message), identity)

  extension (note: FindingNote) def value: String = note

type FindingNote = FindingNote.FindingNote

/** A critic's typed observation. Findings are data: they never mutate a candidate, and the resolver
  * decides what they mean.
  *
  * Why a non-case class: generated `fromProduct` accepts an untyped `Product` and can bypass the
  * opaque note boundary. The explicit `apply` and `copy` require a validated [[FindingNote]].
  */
final class CriticFinding private (
    val taskId: TaskId,
    val family: CriticFamily,
    val code: FindingCode,
    val targets: Vector[TargetRef],
    val evidence: Vector[EvidenceRef],
    val rawScore: Option[RawScore],
    val note: Option[FindingNote]
):
  def severity: Severity = code.severity
  def isBlocking: Boolean = code.isBlocking

  /** Rebuilds a finding without exposing an unchecked note field. */
  def copy(
      taskId: TaskId = taskId,
      family: CriticFamily = family,
      code: FindingCode = code,
      targets: Vector[TargetRef] = targets,
      evidence: Vector[EvidenceRef] = evidence,
      rawScore: Option[RawScore] = rawScore,
      note: Option[FindingNote] = note
  ): CriticFinding =
    CriticFinding(taskId, family, code, targets, evidence, rawScore, note)

  override def equals(other: Any): Boolean = other match
    case that: CriticFinding =>
      taskId == that.taskId &&
      family == that.family &&
      code == that.code &&
      targets == that.targets &&
      evidence == that.evidence &&
      rawScore == that.rawScore &&
      note == that.note
    case _ => false

  override def hashCode(): Int =
    (taskId, family, code, targets, evidence, rawScore, note).hashCode

  override def toString: String =
    s"CriticFinding($taskId,$family,$code,$targets,$evidence,$rawScore,$note)"

object CriticFinding:
  /** Constructs a finding whose optional note has already passed its lexical boundary. */
  def apply(
      taskId: TaskId,
      family: CriticFamily,
      code: FindingCode,
      targets: Vector[TargetRef],
      evidence: Vector[EvidenceRef],
      rawScore: Option[RawScore],
      note: Option[FindingNote]
  ): CriticFinding =
    new CriticFinding(taskId, family, code, targets, evidence, rawScore, note)

  /** Findings whose code is `Blocking` regardless of confidence. */
  def blocking(findings: Iterable[CriticFinding]): Vector[CriticFinding] =
    findings.toVector.filter(_.isBlocking)

  /** Findings that actually block resolution under a confidence threshold: a `Blocking` code blocks
    * when its raw score is at least `threshold`, or when the critic reported no score (an unscored
    * blocking finding is taken at face value — the conservative reading). Blocking codes below the
    * threshold are retained as data but do not block (review finding #36).
    */
  def blocking(findings: Iterable[CriticFinding], threshold: Double): Vector[CriticFinding] =
    findings.toVector.filter(f => f.isBlocking && f.rawScore.forall(_.value >= threshold))
  def maxSeverity(findings: Iterable[CriticFinding]): Option[Severity] =
    findings.map(_.severity).maxOption(using Severity.given_Ordering_Severity)
