package storymodel4s.acquire

import cats.Functor
import cats.data.NonEmptyVector
import storymodel4s.core.*

/** An uncalibrated score reported by a provider. Deliberately a distinct type from
  * [[storymodel4s.core.Probability]]: no code path can pass a raw score where a probability is
  * required.
  */
object RawScore:
  opaque type RawScore = Double
  def from(v: Double): Either[DomainError, RawScore] =
    if v.isNaN || v.isInfinite then
      Left(DomainError.InvalidFormat("RawScore", v.toString, "non-finite"))
    else Right(v)
  def unsafe(v: Double): RawScore =
    from(v).fold(e => throw new IllegalArgumentException(e.message), identity)
  extension (s: RawScore) def value: Double = s
  given cats.Order[RawScore] = cats.Order[Double]
  given Ordering[RawScore] = Ordering.Double.TotalOrdering
  given cats.Show[RawScore] = cats.Show.show(_.toString)
type RawScore = RawScore.RawScore

/** What an agent did with its task. */
enum ProposalDisposition:
  /** The agent's preferred analysis. */
  case Proposed

  /** A second analysis the agent considers viable but not preferred. */
  case Alternative

  /** The agent declined to answer (insufficient evidence, out of scope). */
  case Abstained

  /** The agent judged the task premise unsupported by the source. */
  case Unsupported

/** Evidence attached to a proposal: either an identifier already in the ledger or an inline
  * [[storymodel4s.core.Evidence]] record.
  */
enum EvidenceRef:
  case ById(id: EvidenceId)
  case Inline(evidence: Evidence)

  def evidenceId: EvidenceId = this match
    case ById(id)  => id
    case Inline(e) => e.id
  def spans: Option[SpanSet] = this match
    case ById(_)   => None
    case Inline(e) => e.spans

/** What a proposal conflicts with, as reported by the agent that noticed it. */
enum ConflictTarget:
  case Claim(id: ClaimId)
  case Task(id: TaskId)
  case Node(ref: String)

final case class ConflictRef(target: ConflictTarget, reason: String)

/** Receipt of the provider call that produced a proposal or finding. */
final case class AgentCallReceipt(
    call: ProviderCall,
    promptPackage: PromptPackageRef,
    taskId: TaskId
)

/** The only thing an agent may return: an optional typed value, its disposition, evidence, an
  * uncalibrated score, known conflicts, and a receipt.
  *
  * Invariants (enforced by construction):
  *   - `Proposed`/`Alternative` carry a value and at least one evidence reference;
  *   - `Abstained`/`Unsupported` carry no value;
  *   - the score is a [[RawScore]], never a probability.
  *
  * No agent can construct a `Resolved`, mark a score calibrated, or write into a model; only the
  * deterministic resolver does that.
  */
final case class AgentProposal[A] private (
    taskId: TaskId,
    value: Option[A],
    disposition: ProposalDisposition,
    evidence: Vector[EvidenceRef],
    rawScore: Option[RawScore],
    conflicts: Vector[ConflictRef],
    receipt: AgentCallReceipt
):
  def isSubstantive: Boolean = value.isDefined
  def map[B](f: A => B): AgentProposal[B] = copy(value = value.map(f))

object AgentProposal:
  import ProposalDisposition.*

  def proposed[A](
      taskId: TaskId,
      value: A,
      evidence: NonEmptyVector[EvidenceRef],
      rawScore: Option[RawScore],
      conflicts: Vector[ConflictRef],
      receipt: AgentCallReceipt
  ): AgentProposal[A] =
    new AgentProposal(
      taskId,
      Some(value),
      Proposed,
      evidence.toVector,
      rawScore,
      conflicts,
      receipt
    )

  def alternative[A](
      taskId: TaskId,
      value: A,
      evidence: NonEmptyVector[EvidenceRef],
      rawScore: Option[RawScore],
      conflicts: Vector[ConflictRef],
      receipt: AgentCallReceipt
  ): AgentProposal[A] =
    new AgentProposal(
      taskId,
      Some(value),
      Alternative,
      evidence.toVector,
      rawScore,
      conflicts,
      receipt
    )

  def abstained[A](taskId: TaskId, receipt: AgentCallReceipt): AgentProposal[A] =
    new AgentProposal(taskId, None, Abstained, Vector.empty, None, Vector.empty, receipt)

  def unsupported[A](
      taskId: TaskId,
      evidence: Vector[EvidenceRef],
      receipt: AgentCallReceipt
  ): AgentProposal[A] =
    new AgentProposal(taskId, None, Unsupported, evidence, None, Vector.empty, receipt)

  /** Validated construction from decoded provider output. */
  def from[A](
      taskId: TaskId,
      value: Option[A],
      disposition: ProposalDisposition,
      evidence: Vector[EvidenceRef],
      rawScore: Option[RawScore],
      conflicts: Vector[ConflictRef],
      receipt: AgentCallReceipt
  ): Either[DomainError, AgentProposal[A]] =
    val path = s"proposal/${taskId.value}"
    (disposition, value) match
      case (Proposed | Alternative, None) =>
        Left(DomainError.InvariantViolation(path, s"$disposition requires a value"))
      case (Proposed | Alternative, Some(_)) if evidence.isEmpty =>
        Left(DomainError.InvariantViolation(path, s"$disposition requires evidence"))
      case (Abstained | Unsupported, Some(_)) =>
        Left(DomainError.InvariantViolation(path, s"$disposition must not carry a value"))
      case _ =>
        Right(new AgentProposal(taskId, value, disposition, evidence, rawScore, conflicts, receipt))

  given Functor[AgentProposal] with
    def map[A, B](fa: AgentProposal[A])(f: A => B): AgentProposal[B] = fa.map(f)
