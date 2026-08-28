package storymodel4s.acquire

import cats.data.NonEmptyVector
import storymodel4s.core.*

/** Declaration of one build stage: its identity, prerequisites, and output schema version. */
final case class StageSpec(id: StageId, dependsOn: Set[StageId], schemaVersion: String)

enum PlanError:
  case DuplicateStage(id: StageId)
  case UnknownDependency(stage: StageId, dependency: StageId)
  case Cycle(path: Vector[StageId])
  case MissingFingerprint(stage: StageId)

/** Pure stage-DAG utilities. The orchestrator owns which stages run and in what order; agents never
  * decide that (design record §91.1).
  */
object StageDag:
  /** Deterministic topological order: Kahn's algorithm with the ready set sorted by identifier. */
  def topologicalOrder(specs: Vector[StageSpec]): Either[PlanError, Vector[StageId]] =
    val ids = specs.map(_.id)
    ids.groupBy(identity).collectFirst { case (id, xs) if xs.size > 1 => id } match
      case Some(dup) => Left(PlanError.DuplicateStage(dup))
      case None      =>
        val known = ids.toSet
        val unknown = specs.iterator
          .flatMap(s => s.dependsOn.iterator.map(d => (s.id, d)))
          .find((_, d) => !known(d))
        unknown match
          case Some((s, d)) => Left(PlanError.UnknownDependency(s, d))
          case None         =>
            val deps = specs.map(s => s.id -> s.dependsOn).toMap
            @annotation.tailrec
            def go(
                remaining: Map[StageId, Set[StageId]],
                done: Vector[StageId]
            ): Either[PlanError, Vector[StageId]] =
              if remaining.isEmpty then Right(done)
              else
                val ready = remaining.collect { case (id, ds) if ds.isEmpty => id }.toVector.sorted
                if ready.isEmpty then Left(PlanError.Cycle(cyclePath(remaining)))
                else
                  val readySet = ready.toSet
                  val next = remaining.collect {
                    case (id, ds) if !readySet(id) => id -> (ds -- readySet)
                  }
                  go(next, done ++ ready)
            go(deps, Vector.empty)

  /** One cycle among the remaining stages, for the error message. */
  private def cyclePath(remaining: Map[StageId, Set[StageId]]): Vector[StageId] =
    val start = remaining.keys.toVector.sorted.head
    @annotation.tailrec
    def walk(at: StageId, path: Vector[StageId]): Vector[StageId] =
      val idx = path.indexOf(at)
      if idx >= 0 then path.drop(idx) :+ at
      else
        val nxt = remaining(at).toVector.sorted.headOption
        nxt match
          case Some(n) => walk(n, path :+ at)
          case None    => path :+ at
    walk(start, Vector.empty)

/** Resource policy as data: bounded concurrency, default timeouts, retries, per-stage budgets. */
final case class BudgetPolicy private (
    maxConcurrency: Int,
    defaultTimeoutMillis: Long,
    maxRetries: Int,
    perStage: Map[StageId, TaskBudget]
):
  def budgetFor(stage: StageId): TaskBudget =
    perStage.getOrElse(stage, TaskBudget.unsafe(None, defaultTimeoutMillis, maxRetries))

object BudgetPolicy:
  def of(
      maxConcurrency: Int,
      defaultTimeoutMillis: Long,
      maxRetries: Int,
      perStage: Map[StageId, TaskBudget] = Map.empty
  ): Either[DomainError, BudgetPolicy] =
    if maxConcurrency < 1 then
      Left(DomainError.InvariantViolation("budget/concurrency", "must be at least 1"))
    else if defaultTimeoutMillis <= 0L then
      Left(DomainError.InvariantViolation("budget/timeout", "must be positive"))
    else if maxRetries < 0 then
      Left(DomainError.InvariantViolation("budget/retries", "must be non-negative"))
    else Right(new BudgetPolicy(maxConcurrency, defaultTimeoutMillis, maxRetries, perStage))

/** Outcome of a validation gate. Combination keeps the worst status and all messages. */
enum GateStatus:
  case Passed
  case PassedWithWarnings(warnings: NonEmptyVector[String])
  case Blocked(reasons: NonEmptyVector[String], warnings: Vector[String])

  def isBlocked: Boolean = this match
    case Blocked(_, _) => true
    case _             => false

  def allWarnings: Vector[String] = this match
    case Passed                 => Vector.empty
    case PassedWithWarnings(ws) => ws.toVector
    case Blocked(_, ws)         => ws

  def allReasons: Vector[String] = this match
    case Blocked(rs, _) => rs.toVector
    case _              => Vector.empty

  def combine(other: GateStatus): GateStatus =
    GateStatus.of(allReasons ++ other.allReasons, allWarnings ++ other.allWarnings)

object GateStatus:
  def of(reasons: Vector[String], warnings: Vector[String]): GateStatus =
    NonEmptyVector.fromVector(reasons) match
      case Some(rs) => Blocked(rs, warnings)
      case None     =>
        NonEmptyVector.fromVector(warnings) match
          case Some(ws) => PassedWithWarnings(ws)
          case None     => Passed

  def all(statuses: Iterable[GateStatus]): GateStatus =
    statuses.foldLeft[GateStatus](Passed)(_.combine(_))

  given cats.Monoid[GateStatus] with
    def empty: GateStatus = Passed
    def combine(a: GateStatus, b: GateStatus): GateStatus = a.combine(b)

/** Build-wide configuration relevant to cache keys. */
final case class BuildConfig(
    configHash: Checksum,
    providerFingerprints: Map[StageId, Fingerprint],
    defaultFingerprint: Fingerprint,
    stageLocal: Map[StageId, StageLocalConfig] = Map.empty
):
  def fingerprintFor(stage: StageId): Fingerprint =
    providerFingerprints.getOrElse(stage, defaultFingerprint)

  /** Prompt packages, provider parameters, and seed of one stage; empty when undeclared. */
  def localFor(stage: StageId): StageLocalConfig =
    stageLocal.getOrElse(stage, StageLocalConfig.empty)

final case class PlannedStage(spec: StageSpec, key: StageCacheKey, invalidated: Boolean)

/** An ordered plan with cache keys. A stage's key depends on its dependencies' keys, so any
  * upstream change invalidates everything downstream without explicit propagation.
  */
final case class BuildPlan(stages: Vector[PlannedStage]):
  def order: Vector[StageId] = stages.map(_.spec.id)
  def invalidated: Vector[StageId] = stages.filter(_.invalidated).map(_.spec.id)
  def cached: Vector[StageId] = stages.filterNot(_.invalidated).map(_.spec.id)
  def keyOf(stage: StageId): Option[StageCacheKey] =
    stages.find(_.spec.id == stage).map(_.key)

object BuildPlan:
  /** Plan `specs` against `previous` cache keys. Roots hash the source input; others hash their
    * dependencies' keys. A stage is invalidated when its key differs from the previous build's.
    */
  def from(
      specs: Vector[StageSpec],
      config: BuildConfig,
      inputChecksum: Checksum,
      previous: Map[StageId, StageCacheKey]
  ): Either[PlanError, BuildPlan] =
    StageDag.topologicalOrder(specs).map { order =>
      val byId = specs.map(s => s.id -> s).toMap
      val (planned, _) =
        order.foldLeft((Vector.empty[PlannedStage], Map.empty[StageId, StageCacheKey])) {
          case ((acc, keys), id) =>
            val spec = byId(id)
            val input =
              if spec.dependsOn.isEmpty then inputChecksum
              else
                ContentAddress.digest(
                  spec.dependsOn.toVector.sorted.map(d => s"${d.value}=${keys(d).checksum.hex}")
                )
            val key = StageCacheKey.of(
              input,
              spec.schemaVersion,
              config.configHash,
              config.fingerprintFor(id),
              config.localFor(id)
            )
            val invalidated = !previous.get(id).contains(key)
            (acc :+ PlannedStage(spec, key, invalidated), keys.updated(id, key))
        }
      BuildPlan(planned)
    }
