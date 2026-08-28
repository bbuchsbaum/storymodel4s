package storymodel4s.embed

import cats.{Applicative, Show}

import storymodel4s.core.{Checksum, OpaqueId, ProviderCall}
import storymodel4s.features.Estimate

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
    digest: Checksum
)

object AttemptReceipt:
  def of(
      providerCalls: Vector[ProviderCall],
      cacheDecisions: Vector[CacheDecision],
      policyDecisions: Vector[PolicyDecision]
  ): AttemptReceipt =
    val parts =
      providerCalls.map(c =>
        s"call:${c.provider}:${c.model}:${c.inputChecksum.hex}:${c.outputChecksum.hex}"
      ) ++
        cacheDecisions.map {
          case CacheDecision.Hit(id, k)      => s"hit:${id.value}:${k.render}"
          case CacheDecision.Miss(id, k)     => s"miss:${id.value}:${k.render}"
          case CacheDecision.Bypassed(id, r) => s"bypass:${id.value}:$r"
        } ++ policyDecisions.map(_.render)
    AttemptReceipt(
      providerCalls,
      cacheDecisions,
      policyDecisions,
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
  /** Wrap any embedder so that a non-conforming result (wrong count/order/space) becomes a typed
    * per-batch failure instead of a silent mismatch.
    */
  def conforming[F[_]: Applicative](underlying: Embedder[F]): Embedder[F] =
    new Embedder[F]:
      def info: EmbedderInfo = underlying.info
      def spaces: Vector[EmbeddingSpace] = underlying.spaces
      def embed(batch: EmbedBatch): F[BatchResult] =
        Applicative[F].map(underlying.embed(batch)) { r =>
          r.conforms(batch) match
            case Right(ok) => ok
            case Left(e)   =>
              BatchResult(
                batch.requests.map(q =>
                  EmbedOutcome(q.id, q.space, Left(ExecutionFailure.Invalid(e)))
                ),
                AttemptReceipt.of(Vector.empty, Vector.empty, Vector.empty)
              )
        }

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
