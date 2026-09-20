package storymodel4s.acquire

import cats.data.ValidatedNec
import cats.syntax.all.*
import storymodel4s.core.*

/** Identifier of one bounded unit of acquisition work handed to an agent. */
object TaskId extends OpaqueId("TaskId")
type TaskId = TaskId.T

/** The family of work a task packet asks for. Agents are specialised by kind; the orchestrator
  * never hands an agent a task outside its declared kinds.
  */
enum TaskKind:
  case LocalSemantics
  case FrameRoleCritique
  case AlignmentCritique
  case EntityCoreference
  case EventIdentity
  case ContextScope
  case TemporalStructure
  case CausalGoal
  case Hierarchy
  case GlobalConsistency
  case Custom(namespace: String, name: String)

/** A pointer into a pinned standards document (AMR guidelines section, PropBank frame, UMR relation
  * definition, project policy). Selected by code, never recalled from model weights.
  */
final case class StandardsRef(standard: String, version: String, section: String)

/** Resource limits for one task. Timeouts and retries are data so the orchestrator can budget. */
final class TaskBudget private (
    val maxTokens: Option[Long],
    val timeoutMillis: Long,
    val maxRetries: Int
):
  override def equals(other: Any): Boolean = other match
    case that: TaskBudget =>
      maxTokens == that.maxTokens &&
      timeoutMillis == that.timeoutMillis &&
      maxRetries == that.maxRetries
    case _ => false

  override def hashCode(): Int = (maxTokens, timeoutMillis, maxRetries).##

  override def toString: String =
    s"TaskBudget(maxTokens=$maxTokens, timeoutMillis=$timeoutMillis, maxRetries=$maxRetries)"

object TaskBudget:
  def of(
      maxTokens: Option[Long],
      timeoutMillis: Long,
      maxRetries: Int
  ): Either[DomainError, TaskBudget] =
    if timeoutMillis <= 0L then
      Left(DomainError.InvariantViolation("budget/timeout", "must be positive"))
    else if maxRetries < 0 then
      Left(DomainError.InvariantViolation("budget/retries", "must be non-negative"))
    else if maxTokens.exists(_ <= 0L) then
      Left(DomainError.InvariantViolation("budget/tokens", "must be positive when present"))
    else Right(new TaskBudget(maxTokens, timeoutMillis, maxRetries))

  def unsafe(maxTokens: Option[Long], timeoutMillis: Long, maxRetries: Int): TaskBudget =
    of(maxTokens, timeoutMillis, maxRetries)
      .fold(e => throw new IllegalArgumentException(e.message), identity)

/** Stable identifiers a packet may refer to. Agents receive these and must answer in terms of them;
  * they never see or invent character offsets.
  */
final case class TaskReferences(
    sentenceIds: Vector[SurfaceUnitId],
    tokenIds: Vector[SurfaceUnitId],
    nodeIds: Vector[String],
    upstreamClaims: Vector[ClaimId]
):
  /** Every referenced sentence and token must exist in the atlas with the matching kind. */
  def validateAgainst(atlas: SurfaceAtlas): ValidatedNec[DomainError, TaskReferences] =
    validateWith(
      unitExists = (id, kind) => atlas.byId.get(id).exists(_.kind == kind),
      nodeExists = _ => true,
      claimExists = _ => true
    )

  /** Parser unit references belong to the bound proposal surface, never to media anchor IDs. */
  def validateAgainst(atlas: NarrativeSourceAtlas): ValidatedNec[DomainError, TaskReferences] =
    atlas match
      case text: TextNarrativeAtlas => validateAgainst(text.atlas)
      case anchored: AnchoredNarrativeAtlas =>
        validateWith(
          unitExists = (id, kind) =>
            anchored.surface.exists(_.surface.byId.get(id).exists(_.kind == kind)),
          nodeExists = _ => true,
          claimExists = _ => true
        )

  /** Generic existence check for callers that also know the node and claim universes. */
  def validateWith(
      unitExists: (SurfaceUnitId, SurfaceUnitKind) => Boolean,
      nodeExists: String => Boolean,
      claimExists: ClaimId => Boolean
  ): ValidatedNec[DomainError, TaskReferences] =
    def missing(path: String, what: String) =
      DomainError.InvariantViolation(path, s"unknown reference $what")
    val sentences = sentenceIds.traverse_ { id =>
      if unitExists(id, SurfaceUnitKind.Sentence) then ().validNec
      else missing("task/references/sentences", id.value).invalidNec
    }
    val tokens = tokenIds.traverse_ { id =>
      if unitExists(id, SurfaceUnitKind.Token) then ().validNec
      else missing("task/references/tokens", id.value).invalidNec
    }
    val nodes = nodeIds.traverse_ { id =>
      if nodeExists(id) then ().validNec else missing("task/references/nodes", id).invalidNec
    }
    val claims = upstreamClaims.traverse_ { id =>
      if claimExists(id) then ().validNec
      else missing("task/references/claims", id.value).invalidNec
    }
    (sentences, tokens, nodes, claims).mapN((_, _, _, _) => this)

object TaskReferences:
  val empty: TaskReferences = TaskReferences(Vector.empty, Vector.empty, Vector.empty, Vector.empty)

/** One bounded task for one agent: typed input, the references it may use, the standards it was
  * given, the prompt package that governs it, and its budget.
  *
  * Why: agents do not exchange free-form essays; they receive exactly this and return typed
  * proposals (`AgentProposal`) or findings (`CriticFinding`).
  */
final case class TaskPacket[I](
    id: TaskId,
    kind: TaskKind,
    input: I,
    references: TaskReferences,
    standardsExcerpts: Vector[StandardsRef],
    promptPackage: PromptPackageRef,
    budget: TaskBudget
):
  def map[J](f: I => J): TaskPacket[J] = copy(input = f(input))
