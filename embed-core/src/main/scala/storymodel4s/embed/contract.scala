package storymodel4s.embed

import cats.{Applicative, Show}

import storymodel4s.core.{Checksum, OpaqueId, ProviderCall}
import storymodel4s.features.{Estimate, MalformedReason, MissingReason}

/** Caller-supplied request identity; unique within a batch (checked by [[EmbedBatch]]). */
object RequestId extends OpaqueId("RequestId")
type RequestId = RequestId.T

/** Raw text is only ever seen by local providers; sanitized text is the remote-safe form. The two
  * are different constructors so a transport cannot accept raw text by accident.
  */
enum EmbedPayload:
  case Raw(text: String, sensitivity: Sensitivity)
  case Sanitized(text: PseudonymizedText)

  def sensitivityOf: Sensitivity = this match
    case Raw(_, s)    => s
    case Sanitized(_) => Sensitivity.Sensitive

  /** Exact material the provider embeds; instruction and role are appended by the embedder so they
    * participate in cache identity.
    */
  private[embed] def materialText: String = this match
    case Raw(t, _)    => t
    case Sanitized(p) => p.text

final case class EmbedRequest(id: RequestId, payload: EmbedPayload, space: GeometryId)

/** A validated batch: unique ids, spaces known to the target embedder. */
final case class EmbedBatch private (requests: Vector[EmbedRequest]):
  def ids: Vector[RequestId] = requests.map(_.id)

object EmbedBatch:
  def validated(
      requests: Vector[EmbedRequest],
      knownSpaces: Set[GeometryId]
  ): Either[EmbedError, EmbedBatch] =
    val dup = requests.groupBy(_.id).collectFirst { case (id, rs) if rs.size > 1 => id }
    dup match
      case Some(id) => Left(EmbedError.DuplicateRequestId(id.value))
      case None     =>
        requests.find(r => !knownSpaces.contains(r.space)) match
          case Some(r) => Left(EmbedError.UnknownSpace(r.space.value))
          case None    => Right(EmbedBatch(requests))

/** Execution failure is distinct from valid absence (`Estimate.Missing`). Messages never contain
  * payload text.
  */
enum ExecutionFailure:
  case TooLong(tokens: Int, max: Int)
  case ProviderError(code: String, attempt: Int)
  case PolicyDenied(decision: PolicyDecision.Denied)
  case LocalOnly(decision: PolicyDecision.LocalOnly)
  case Transport(code: String)
  case Invalid(error: EmbedError)

  def render: String = this match
    case TooLong(t, m)       => s"too-long:$t>$m"
    case ProviderError(c, a) => s"provider:$c:attempt$a"
    case PolicyDenied(d)     => d.render
    case LocalOnly(d)        => d.render
    case Transport(c)        => s"transport:$c"
    case Invalid(e)          => s"invalid:${e.message}"

final case class EmbedOutcome(
    id: RequestId,
    space: GeometryId,
    value: Either[ExecutionFailure, Estimate[ValidatedVector]]
)

/** A typed audit record for any repair or rejection performed by the conforming wrapper.
  *
  * Why: result normalization must remain visible in receipts instead of silently changing provider
  * output or discarding otherwise valid sibling estimates.
  */
enum ResultDecision:
  case Reordered(id: RequestId, fromIndex: Int, toIndex: Int)
  case SpaceRejected(id: RequestId, expected: GeometryId, actual: GeometryId)
  case BatchRejected(error: EmbedError)

  private[embed] def render: String = this match
    case Reordered(id, from, to)             => s"reordered:${id.value}:$from:$to"
    case SpaceRejected(id, expected, actual) =>
      s"space-rejected:${id.value}:${expected.value}:${actual.value}"
    case BatchRejected(error) => s"batch-rejected:${error.message}"

/** What the cache did for one request. */
enum CacheDecision:
  case Hit(id: RequestId, key: MaterialDigest)
  case Miss(id: RequestId, key: MaterialDigest)
  case Bypassed(id: RequestId, reason: String)

/** Receipt of one `embed` attempt: zero or more provider calls plus every cache/policy decision. A
  * preflight denial or a full cache hit legitimately has no provider call at all.
  */
final case class AttemptReceipt(
    providerCalls: Vector[ProviderCall],
    cacheDecisions: Vector[CacheDecision],
    policyDecisions: Vector[PolicyDecision],
    resultDecisions: Vector[ResultDecision],
    digest: Checksum
):
  private[embed] def addResultDecisions(decisions: Vector[ResultDecision]): AttemptReceipt =
    if decisions.isEmpty then this
    else
      AttemptReceipt.of(
        providerCalls,
        cacheDecisions,
        policyDecisions,
        resultDecisions ++ decisions
      )

object AttemptReceipt:
  def of(
      providerCalls: Vector[ProviderCall],
      cacheDecisions: Vector[CacheDecision],
      policyDecisions: Vector[PolicyDecision],
      resultDecisions: Vector[ResultDecision] = Vector.empty
  ): AttemptReceipt =
    val parts =
      providerCalls.map(c =>
        s"call:${c.provider}:${c.model}:${c.inputChecksum.hex}:${c.outputChecksum.hex}"
      ) ++
        cacheDecisions.map {
          case CacheDecision.Hit(id, k)      => s"hit:${id.value}:${k.render}"
          case CacheDecision.Miss(id, k)     => s"miss:${id.value}:${k.render}"
          case CacheDecision.Bypassed(id, r) => s"bypass:${id.value}:$r"
        } ++ policyDecisions.map(_.render) ++ resultDecisions.map(_.render)
    AttemptReceipt(
      providerCalls,
      cacheDecisions,
      policyDecisions,
      resultDecisions,
      Checksum.ofText(parts.mkString("\n"))
    )

final case class BatchResult(outcomes: Vector[EmbedOutcome], receipt: AttemptReceipt):
  /** Law L4: exactly one outcome per request id, in the batch's order, each in the space asked. */
  def conforms(batch: EmbedBatch): Either[EmbedError, BatchResult] =
    if outcomes.length != batch.requests.length then
      Left(
        EmbedError.InvalidResult(
          s"${outcomes.length} outcomes for ${batch.requests.length} requests"
        )
      )
    else
      val bad = outcomes.zip(batch.requests).indexWhere { case (o, r) =>
        o.id != r.id || o.space != r.space
      }
      if bad >= 0 then Left(EmbedError.InvalidResult(s"outcome $bad does not match its request"))
      else Right(this)

/** Static capabilities of a provider. */
final case class EmbedderInfo(
    provider: ProviderFingerprint,
    name: String,
    version: String,
    locality: Locality,
    privacyClass: PrivacyClass,
    maxTokens: Int,
    supportsInstructions: Boolean,
    tokenEmbeddings: Boolean,
    matryoshkaDims: Option[Vector[Int]]
)

/** The embedding contract (ADR 0001 §D3). Implementations must satisfy L4 (one outcome per request
  * id, in order; failures per item) — [[Embedder.conforming]] enforces it for any implementation.
  */
trait Embedder[F[_]]:
  def info: EmbedderInfo
  def spaces: Vector[EmbeddingSpace]
  def embed(batch: EmbedBatch): F[BatchResult]

  final def spaceIds: Set[GeometryId] = spaces.map(_.id).toSet
  final def space(id: GeometryId): Option[EmbeddingSpace] = spaces.find(_.id == id)

object Embedder:
  /** Wrap an embedder so that provably associated siblings survive a malformed item.
    *
    * A complete id bijection is normalized into request order. A wrong-space item becomes a typed
    * missing estimate and a receipt decision; missing, duplicate, or extra ids fail the whole batch
    * because their vectors cannot be associated safely.
    */
  def conforming[F[_]: Applicative](underlying: Embedder[F]): Embedder[F] =
    new Embedder[F]:
      def info: EmbedderInfo = underlying.info
      def spaces: Vector[EmbeddingSpace] = underlying.spaces
      def embed(batch: EmbedBatch): F[BatchResult] =
        Applicative[F].map(underlying.embed(batch))(normalize(batch, _))

  private def normalize(batch: EmbedBatch, result: BatchResult): BatchResult =
    val expectedIds = batch.ids.toSet
    val grouped = result.outcomes.zipWithIndex.groupMap(_._1.id)(identity)
    val actualIds = grouped.keySet
    val duplicateIds = grouped.collect { case (id, values) if values.size > 1 => id }.toVector
    val missingIds = expectedIds.diff(actualIds).toVector
    val extraIds = actualIds.diff(expectedIds).toVector

    associationError(result.outcomes.size, batch.requests.size, duplicateIds, missingIds, extraIds)
      .fold(normalizeBijection(batch, result, grouped))(failClosed(batch, result, _))

  private def associationError(
      actualSize: Int,
      expectedSize: Int,
      duplicateIds: Vector[RequestId],
      missingIds: Vector[RequestId],
      extraIds: Vector[RequestId]
  ): Option[EmbedError] =
    val details = Vector(
      Option.when(actualSize != expectedSize)(s"count=$actualSize expected=$expectedSize"),
      renderIds("duplicate", duplicateIds),
      renderIds("missing", missingIds),
      renderIds("extra", extraIds)
    ).flatten
    Option.when(details.nonEmpty)(EmbedError.InvalidResult(details.mkString("; ")))

  private def renderIds(label: String, ids: Vector[RequestId]): Option[String] =
    Option.when(ids.nonEmpty)(s"$label=${ids.map(_.value).sorted.mkString(",")}")

  private def normalizeBijection(
      batch: EmbedBatch,
      result: BatchResult,
      grouped: Map[RequestId, Vector[(EmbedOutcome, Int)]]
  ): BatchResult =
    val normalized = batch.requests.zipWithIndex.flatMap { case (request, targetIndex) =>
      grouped.get(request.id).flatMap(_.headOption).map { case (outcome, sourceIndex) =>
        val reorder =
          Option.when(sourceIndex != targetIndex)(
            ResultDecision.Reordered(request.id, sourceIndex, targetIndex)
          )
        if outcome.space == request.space then (outcome, reorder.toVector)
        else
          val missing = EmbedOutcome(
            request.id,
            request.space,
            Right(Estimate.missing(MissingReason.Malformed(MalformedReason.ProviderResult)))
          )
          val rejection =
            ResultDecision.SpaceRejected(request.id, request.space, outcome.space)
          (missing, reorder.toVector :+ rejection)
      }
    }
    if normalized.size != batch.requests.size then
      failClosed(
        batch,
        result,
        EmbedError.InvalidResult("outcomes did not form a complete request-id bijection")
      )
    else
      val decisions = normalized.flatMap(_._2)
      BatchResult(normalized.map(_._1), result.receipt.addResultDecisions(decisions))

  private def failClosed(
      batch: EmbedBatch,
      result: BatchResult,
      error: EmbedError
  ): BatchResult =
    BatchResult(
      batch.requests.map(request =>
        EmbedOutcome(request.id, request.space, Left(ExecutionFailure.Invalid(error)))
      ),
      result.receipt.addResultDecisions(Vector(ResultDecision.BatchRejected(error)))
    )

  /** Preflight one request against the provider's locality/privacy class. Raw sensitive text never
    * reaches a remote provider; the decision is recorded, not thrown.
    */
  def preflight(info: EmbedderInfo, request: EmbedRequest): Either[ExecutionFailure, Unit] =
    request.payload match
      case EmbedPayload.Raw(_, s) =>
        if info.locality == Locality.Remote && s == Sensitivity.Sensitive then
          Left(
            ExecutionFailure.LocalOnly(
              PolicyDecision.LocalOnly(None, "raw sensitive text to remote provider")
            )
          )
        else if !info.privacyClass.admits(s) then
          Left(
            ExecutionFailure.PolicyDenied(
              PolicyDecision.Denied(None, s"provider privacy class does not admit $s")
            )
          )
        else Right(())
      case EmbedPayload.Sanitized(_) => Right(())

  given Show[EmbedderInfo] =
    Show.show(i => s"${i.name}@${i.version}(${i.provider.render.take(12)})")
