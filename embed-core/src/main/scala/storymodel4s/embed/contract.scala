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
  def itemSensitivity: Vector[(RequestId, Sensitivity)] =
    requests.map(r => r.id -> r.payload.sensitivityOf)

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
  * payload text. `PolicyDenied` carries the recorded decision — a plain denial or a missing key.
  */
enum ExecutionFailure:
  case TooLong(tokens: Int, max: Int)
  case ProviderError(code: String, attempt: Int)
  case PolicyDenied(decision: PolicyDecision)
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

/** What the cache did for one request. Keys are typed [[ReceiptDigest]]s, so the digest kind is
  * visible without parsing a rendering; a request denied for lack of a key is recorded as such.
  */
enum CacheDecision:
  case Hit(id: RequestId, key: ReceiptDigest)
  case Miss(id: RequestId, key: ReceiptDigest)
  case Denied(id: RequestId, decision: PolicyDecision)
  case Bypassed(id: RequestId, reason: String)

  private[embed] def render: String = this match
    case Hit(id, k)    => s"cache|hit|${ReceiptRendering.esc(id.value)}|${k.render}"
    case Miss(id, k)   => s"cache|miss|${ReceiptRendering.esc(id.value)}|${k.render}"
    case Denied(id, d) =>
      s"cache|denied|${ReceiptRendering.esc(id.value)}|${ReceiptRendering.esc(d.render)}"
    case Bypassed(id, r) =>
      s"cache|bypass|${ReceiptRendering.esc(id.value)}|${ReceiptRendering.esc(r)}"

/** Receipt of one `embed` attempt: zero or more provider calls plus every cache/policy/result
  * decision and the sensitivity of every item. A preflight denial or a full cache hit legitimately
  * has no provider call at all.
  *
  * Identity discipline (ADR 0001 D6): `digest` is computed from the canonical rendering of the
  * CONSTRUCTED receipt — every decision vector, including `resultDecisions`, and the item
  * sensitivities — so no defaulted argument can escape it. It is `Plain` when every item is
  * `Public`, `Keyed` under the store key when any item is not, and `Withheld` only when the key was
  * absent and every non-public item carries a recorded `PolicyDecision.KeyUnavailable`. There is no
  * other case: a non-public batch without a key and without recorded denials cannot be receipted.
  */
final class AttemptReceipt private (
    val providerCalls: Vector[ProviderCall],
    val embeddingReceipts: Vector[EmbeddingReceipt],
    val cacheDecisions: Vector[CacheDecision],
    val policyDecisions: Vector[PolicyDecision],
    val resultDecisions: Vector[ResultDecision],
    val itemSensitivity: Vector[(RequestId, Sensitivity)],
    val digest: ReceiptDigest,
    private val keys: SensitiveKeyProvider
):
  def kind: DigestKind = digest.kind

  private def parts = (
    providerCalls,
    embeddingReceipts,
    cacheDecisions,
    policyDecisions,
    resultDecisions,
    itemSensitivity,
    digest
  )

  override def equals(o: Any): Boolean = o match
    case that: AttemptReceipt => this.parts == that.parts
    case _                    => false
  override def hashCode: Int = parts.hashCode
  override def toString: String =
    s"AttemptReceipt(calls=${providerCalls.size}, cache=${cacheDecisions.size}, policy=${policyDecisions.size}, result=${resultDecisions.size}, items=${itemSensitivity.size}, digest=${digest.render})"

  /** Re-receipt with additional result decisions; the digest is recomputed over the new rendering.
    * The key provider that minted this receipt is reused, so recomputation cannot change the kind
    * unless the key has since vanished — in which case the receipt is withheld and says so.
    */
  private[embed] def addResultDecisions(decisions: Vector[ResultDecision]): AttemptReceipt =
    if decisions.isEmpty then this
    else
      val next = resultDecisions ++ decisions
      AttemptReceipt
        .of(
          providerCalls,
          embeddingReceipts,
          cacheDecisions,
          policyDecisions,
          next,
          itemSensitivity,
          keys
        )
        .getOrElse(
          AttemptReceipt.failClosed(
            providerCalls,
            embeddingReceipts,
            cacheDecisions,
            policyDecisions,
            next,
            itemSensitivity,
            keys.currentKeyId
          )
        )

object AttemptReceipt:
  /** Canonical rendering of the receipt's content (never material). */
  private[embed] def rendering(
      providerCalls: Vector[ProviderCall],
      cacheDecisions: Vector[CacheDecision],
      policyDecisions: Vector[PolicyDecision],
      resultDecisions: Vector[ResultDecision],
      itemSensitivity: Vector[(RequestId, Sensitivity)]
  ): String =
    val e = ReceiptRendering.esc
    val lines =
      Vector(ReceiptRendering.AttemptVersion) ++
        providerCalls.map(c =>
          s"call|${e(c.provider)}|${e(c.model)}|${e(c.version)}|${c.inputChecksum.hex}|${c.outputChecksum.hex}"
        ) ++
        cacheDecisions.map(_.render) ++
        policyDecisions.map(d => s"policy|${e(d.render)}") ++
        resultDecisions.map(d => s"result|${e(d.render)}") ++
        itemSensitivity.map { case (id, s) => s"item|${e(id.value)}|$s" }
    lines.mkString("\n")

  /** Build a receipt. `Left(NoKey)` only when a non-public item is present, no key is available,
    * and that item is NOT covered by a recorded `KeyUnavailable` decision — i.e. the caller tried
    * to receipt sensitive work without either keying or denying it.
    */
  def of(
      providerCalls: Vector[ProviderCall],
      embeddingReceipts: Vector[EmbeddingReceipt],
      cacheDecisions: Vector[CacheDecision],
      policyDecisions: Vector[PolicyDecision],
      resultDecisions: Vector[ResultDecision],
      itemSensitivity: Vector[(RequestId, Sensitivity)],
      keys: SensitiveKeyProvider
  ): Either[EmbedError, AttemptReceipt] =
    val text =
      rendering(providerCalls, cacheDecisions, policyDecisions, resultDecisions, itemSensitivity)
    val nonPublic = itemSensitivity.collect {
      case (id, s) if !ReceiptDigest.plainAdmissible.contains(s) => id
    }
    val digest: Either[EmbedError, ReceiptDigest] =
      if nonPublic.isEmpty then Right(ReceiptDigest.plainPublic(text))
      else
        ReceiptDigest.keyed(text, keys).left.flatMap { err =>
          val denied = policyDecisions.collect { case PolicyDecision.KeyUnavailable(Some(id), _) =>
            id
          }.toSet
          if nonPublic.forall(denied.contains) then
            Right(ReceiptDigest.withheld(keys.currentKeyId, text))
          else Left(err)
        }
    digest.map(d =>
      new AttemptReceipt(
        providerCalls,
        embeddingReceipts,
        cacheDecisions,
        policyDecisions,
        resultDecisions,
        itemSensitivity,
        d,
        keys
      )
    )

  /** A receipt for a batch with no non-public items (or no items at all): always `Plain`, needs no
    * key. Used by providers that only ever see public material and by tests.
    */
  def public(
      providerCalls: Vector[ProviderCall],
      cacheDecisions: Vector[CacheDecision],
      policyDecisions: Vector[PolicyDecision],
      resultDecisions: Vector[ResultDecision] = Vector.empty
  ): AttemptReceipt =
    val text =
      rendering(providerCalls, cacheDecisions, policyDecisions, resultDecisions, Vector.empty)
    new AttemptReceipt(
      providerCalls,
      Vector.empty,
      cacheDecisions,
      policyDecisions,
      resultDecisions,
      Vector.empty,
      ReceiptDigest.plainPublic(text),
      SensitiveKeyProvider.none
    )

  /** The receipt of a batch that failed closed for lack of key `missing`: every non-public item is
    * recorded as `KeyUnavailable` and the identity is `Withheld` over a material-free rendering.
    */
  private[embed] def failClosed(
      providerCalls: Vector[ProviderCall],
      embeddingReceipts: Vector[EmbeddingReceipt],
      cacheDecisions: Vector[CacheDecision],
      policyDecisions: Vector[PolicyDecision],
      resultDecisions: Vector[ResultDecision],
      itemSensitivity: Vector[(RequestId, Sensitivity)],
      missing: KeyId
  ): AttemptReceipt =
    val already = policyDecisions.collect { case PolicyDecision.KeyUnavailable(Some(id), _) =>
      id
    }.toSet
    val denials = itemSensitivity.collect {
      case (id, s) if !ReceiptDigest.plainAdmissible.contains(s) && !already.contains(id) =>
        PolicyDecision.KeyUnavailable(Some(id), missing)
    }
    val policy = policyDecisions ++ denials
    val text = rendering(providerCalls, cacheDecisions, policy, resultDecisions, itemSensitivity)
    new AttemptReceipt(
      providerCalls,
      embeddingReceipts,
      cacheDecisions,
      policy,
      resultDecisions,
      itemSensitivity,
      ReceiptDigest.withheld(missing, text),
      SensitiveKeyProvider.none
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
