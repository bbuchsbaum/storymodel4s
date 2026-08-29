package storymodel4s.embed

import cats.{Applicative, Show}

import storymodel4s.core.{Checksum, OpaqueId, ProviderCall}
import storymodel4s.features.{CanonicalDouble, Estimate, MalformedReason, MissingReason}

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
  case PolicyDenied(decision: PolicyDecision.Denied | PolicyDecision.KeyUnavailable)
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
  case ReceiptRejected(error: EmbedError.ReceiptKeyMismatch | EmbedError.AuthorityMismatch)
  case BatchRejected(error: EmbedError)

  private[embed] def render: String = this match
    case Reordered(id, from, to)             => s"reordered:${id.value}:$from:$to"
    case SpaceRejected(id, expected, actual) =>
      s"space-rejected:${id.value}:${expected.value}:${actual.value}"
    case ReceiptRejected(error) => s"receipt-rejected:${error.message}"
    case BatchRejected(error)   => s"batch-rejected:${error.message}"

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
    val digest: ReceiptDigest
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

  /** Ids of the items whose identity is not plain-admissible (the ones a withheld receipt denies).
    */
  private def nonPublicIds: Set[RequestId] =
    itemSensitivity.collect {
      case (id, s) if !ReceiptDigest.plainAdmissible.contains(s) => id
    }.toSet

  /** A withheld receipt describes a batch that could not be keyed: no vector of a non-public item
    * may leave with it. Every such outcome becomes `PolicyDenied(KeyUnavailable)`; a non-withheld
    * receipt returns the outcomes unchanged. Shared by the cache wrapper and the conforming wrapper
    * so both paths withhold identically.
    */
  private[embed] def enforceWithholding(outcomes: Vector[EmbedOutcome]): Vector[EmbedOutcome] =
    digest match
      case ReceiptDigest.Withheld(missing, _) =>
        val denied = nonPublicIds
        outcomes.map { o =>
          if denied.contains(o.id) && o.value.isRight then
            o.copy(value =
              Left(
                ExecutionFailure.PolicyDenied(
                  new PolicyDecision.KeyUnavailable(Some(o.id), missing)
                )
              )
            )
          else o
        }
      case _ => outcomes

  /** Re-receipt with additional result decisions under the batch-scoped authority. The receipt
    * stores only its final digest; a keyed amendment must therefore be supplied the immutable
    * snapshot captured before work. It can neither change authority nor erase a call.
    */
  private[embed] def addResultDecisions(
      decisions: Vector[ResultDecision],
      snapshot: Option[SensitiveKeySnapshot]
  ): Either[EmbedError, AttemptReceipt] =
    if decisions.isEmpty then Right(this)
    else AttemptReceipt.amend(this, decisions, snapshot)

object AttemptReceipt:
  private val AttemptVersion = "attempt/v2"

  private enum DigestAuthority:
    case Plain
    case Keyed(snapshot: SensitiveKeySnapshot)
    case Withheld(missing: KeyId)

    def digest(rendering: String): ReceiptDigest = this match
      case Plain             => ReceiptDigest.plainPublic(rendering)
      case Keyed(snapshot)   => ReceiptDigest.Keyed(SensitiveDigest.compute(snapshot, rendering))
      case Withheld(missing) => ReceiptDigest.withheld(missing, rendering)

  private def build(
      providerCalls: Vector[ProviderCall],
      embeddingReceipts: Vector[EmbeddingReceipt],
      cacheDecisions: Vector[CacheDecision],
      policyDecisions: Vector[PolicyDecision],
      resultDecisions: Vector[ResultDecision],
      itemSensitivity: Vector[(RequestId, Sensitivity)],
      authority: DigestAuthority
  ): AttemptReceipt =
    val material = rendering(
      providerCalls,
      embeddingReceipts,
      cacheDecisions,
      policyDecisions,
      resultDecisions,
      itemSensitivity
    )
    new AttemptReceipt(
      providerCalls,
      embeddingReceipts,
      cacheDecisions,
      policyDecisions,
      resultDecisions,
      itemSensitivity,
      authority.digest(material)
    )

  private def amend(
      receipt: AttemptReceipt,
      decisions: Vector[ResultDecision],
      snapshot: Option[SensitiveKeySnapshot]
  ): Either[EmbedError, AttemptReceipt] =
    val authority = receipt.digest match
      case ReceiptDigest.Plain(_) =>
        snapshot match
          case None    => Right(DigestAuthority.Plain)
          case Some(_) => Left(EmbedError.InvalidResult("plain receipt supplied a keyed authority"))
      case ReceiptDigest.Keyed(stored) =>
        snapshot match
          case Some(supplied) if supplied.keyId == stored.keyId =>
            Right(DigestAuthority.Keyed(supplied))
          case Some(supplied) =>
            Left(EmbedError.AuthorityMismatch(stored.keyId, supplied.keyId))
          case None => Left(EmbedError.NoKey(stored.keyId.value))
      case ReceiptDigest.Withheld(missing, _) =>
        snapshot match
          case None           => Right(DigestAuthority.Withheld(missing))
          case Some(supplied) =>
            Left(EmbedError.AuthorityMismatch(missing, supplied.keyId))
    authority.map(a =>
      build(
        receipt.providerCalls,
        receipt.embeddingReceipts,
        receipt.cacheDecisions,
        receipt.policyDecisions,
        receipt.resultDecisions ++ decisions,
        receipt.itemSensitivity,
        a
      )
    )

  private def record(tag: String, fields: Vector[String]): String =
    (tag +: fields.map(ReceiptRendering.esc)).mkString("|")

  private def optionFields(value: Option[String]): Vector[String] = value match
    case Some(v) => Vector("some", v)
    case None    => Vector("none", "")

  private def providerCallLines(index: Int, call: ProviderCall): Vector[String] =
    val fields =
      Vector(index.toString, call.provider, call.model, call.version) ++
        optionFields(call.promptTemplateVersion) ++
        Vector(
          call.inputChecksum.hex,
          call.outputChecksum.hex
        ) ++
        optionFields(call.seed.map(_.toString)) ++
        Vector(call.cached.toString, call.params.size.toString)
    val params = call.params.toVector.sortBy { case (key, value) => (key, value) }.map {
      case (key, value) => record("call-param", Vector(index.toString, key, value))
    }
    record("call", fields) +: params

  private def embeddingReceiptLines(
      index: Int,
      receipt: EmbeddingReceipt
  ): Vector[String] =
    val header = record(
      "embedding",
      Vector(
        index.toString,
        receipt.kind.render,
        receipt.items.size.toString,
        receipt.outputs.render
      )
    )
    header +: receipt.items.zipWithIndex.map { case (item, itemIndex) =>
      record(
        "embedding-item",
        Vector(
          index.toString,
          itemIndex.toString,
          item.id.value,
          item.sensitivity.toString,
          item.digest.render
        )
      )
    }

  private def policyFields(decision: PolicyDecision): Vector[String] = decision match
    case PolicyDecision.Allowed(policyId, capability) =>
      Vector(
        "allowed",
        policyId.value,
        capability.provider.render,
        capability.model,
        capability.purpose,
        capability.policyId.value,
        capability.expiresAtEpochMillis.toString,
        capability.budgetTokens.toString,
        capability.detectorIdentity.detectorId.value,
        capability.detectorIdentity.configurationDigest.render,
        capability.payloadDigest.render
      )
    case PolicyDecision.LocalOnly(policyId, reason) =>
      Vector("local-only") ++ optionFields(policyId.map(_.value)) :+ reason
    case PolicyDecision.Denied(policyId, reason) =>
      Vector("denied") ++ optionFields(policyId.map(_.value)) :+ reason
    case PolicyDecision.KeyUnavailable(id, keyId) =>
      Vector("key-unavailable") ++ optionFields(id.map(_.value)) :+ keyId.value

  private def policyDigests(decision: PolicyDecision): Vector[ReceiptDigest.Keyed] =
    decision match
      case PolicyDecision.Allowed(_, capability) =>
        Vector(capability.detectorIdentity.configurationDigest, capability.payloadDigest)
      case _ => Vector.empty

  private def cacheDecisionLine(index: Int, decision: CacheDecision): String = decision match
    case CacheDecision.Hit(id, key) =>
      record("cache", Vector(index.toString, "hit", id.value, key.render))
    case CacheDecision.Miss(id, key) =>
      record("cache", Vector(index.toString, "miss", id.value, key.render))
    case CacheDecision.Denied(id, policy) =>
      record("cache", Vector(index.toString, "denied", id.value) ++ policyFields(policy))
    case CacheDecision.Bypassed(id, reason) =>
      record("cache", Vector(index.toString, "bypassed", id.value, reason))

  private def errorFields(error: EmbedError): Vector[String] = error match
    case EmbedError.DimensionMismatch(expected, actual) =>
      Vector("dimension-mismatch", expected.toString, actual.toString)
    case EmbedError.NonFiniteValue(index)         => Vector("non-finite", index.toString)
    case EmbedError.NotNormalized(norm, expected) =>
      Vector("not-normalized", CanonicalDouble.render(norm), expected.render)
    case EmbedError.InvalidDimension(value)       => Vector("invalid-dimension", value.toString)
    case EmbedError.InvalidRequestId(raw, reason) => Vector("invalid-request-id", raw, reason)
    case EmbedError.DuplicateRequestId(id)        => Vector("duplicate-request-id", id)
    case EmbedError.UnknownSpace(id)              => Vector("unknown-space", id)
    case EmbedError.IncompatibleSpaces(query, document, reason) =>
      Vector("incompatible-spaces", query, document, reason)
    case EmbedError.InvalidDistance(value) =>
      Vector("invalid-distance", CanonicalDouble.render(value))
    case EmbedError.InvalidRecipe(reason)               => Vector("invalid-recipe", reason)
    case EmbedError.InvalidResult(reason)               => Vector("invalid-result", reason)
    case EmbedError.InvalidKey(reason)                  => Vector("invalid-key", reason)
    case EmbedError.ReceiptKeyMismatch(expected, found) =>
      Vector("receipt-key-mismatch", expected.value, found.value)
    case EmbedError.AuthorityMismatch(stored, supplied) =>
      Vector("authority-mismatch", stored.value, supplied.value)
    case EmbedError.NoKey(keyId) => Vector("no-key", keyId)

  private def resultDecisionLine(index: Int, decision: ResultDecision): String = decision match
    case ResultDecision.Reordered(id, from, to) =>
      record("result", Vector(index.toString, "reordered", id.value, from.toString, to.toString))
    case ResultDecision.SpaceRejected(id, expected, actual) =>
      record(
        "result",
        Vector(index.toString, "space-rejected", id.value, expected.value, actual.value)
      )
    case ResultDecision.ReceiptRejected(error) =>
      record("result", Vector(index.toString, "receipt-rejected") ++ errorFields(error))
    case ResultDecision.BatchRejected(error) =>
      record("result", Vector(index.toString, "batch-rejected") ++ errorFields(error))

  /** Canonical attempt/v2 rendering of every value field (never material). */
  private[embed] def rendering(
      providerCalls: Vector[ProviderCall],
      embeddingReceipts: Vector[EmbeddingReceipt],
      cacheDecisions: Vector[CacheDecision],
      policyDecisions: Vector[PolicyDecision],
      resultDecisions: Vector[ResultDecision],
      itemSensitivity: Vector[(RequestId, Sensitivity)]
  ): String =
    val lines =
      Vector(AttemptVersion) ++
        providerCalls.zipWithIndex.flatMap { case (call, index) =>
          providerCallLines(index, call)
        } ++
        embeddingReceipts.zipWithIndex.flatMap { case (receipt, index) =>
          embeddingReceiptLines(index, receipt)
        } ++
        cacheDecisions.zipWithIndex.map { case (decision, index) =>
          cacheDecisionLine(index, decision)
        } ++
        policyDecisions.zipWithIndex.map { case (decision, index) =>
          record("policy", Vector(index.toString) ++ policyFields(decision))
        } ++
        resultDecisions.zipWithIndex.map { case (decision, index) =>
          resultDecisionLine(index, decision)
        } ++
        itemSensitivity.zipWithIndex.map { case ((id, sensitivity), index) =>
          record("item", Vector(index.toString, id.value, sensitivity.toString))
        }
    lines.mkString("\n")

  private def validateProviderReceipts(
      providerCalls: Vector[ProviderCall],
      embeddingReceipts: Vector[EmbeddingReceipt],
      cacheDecisions: Vector[CacheDecision],
      policyDecisions: Vector[PolicyDecision],
      resultDecisions: Vector[ResultDecision],
      itemSensitivity: Vector[(RequestId, Sensitivity)]
  ): Either[EmbedError, Unit] =
    val declaredIds = itemSensitivity.map(_._1)
    val nonPublicIds = itemSensitivity.collect {
      case (id, sensitivity) if !ReceiptDigest.plainAdmissible.contains(sensitivity) => id
    }.toSet
    val embeddedItems = embeddingReceipts.flatMap(_.items)
    val embeddedIds = embeddedItems.map(_.id)
    val keyedCacheIdentity = cacheDecisions.exists {
      case CacheDecision.Hit(_, digest)  => digest.kind == DigestKind.Keyed
      case CacheDecision.Miss(_, digest) => digest.kind == DigestKind.Keyed
      case _                             => false
    }
    val publicCarriesKeyedEvidence =
      nonPublicIds.isEmpty && (embeddingReceipts.exists(_.kind == DigestKind.Keyed) ||
        keyedCacheIdentity || policyDecisions.exists(policyDigests(_).nonEmpty))
    if declaredIds.distinct.size != declaredIds.size then
      Left(EmbedError.InvalidResult("duplicate item sensitivity evidence"))
    else if embeddedIds.distinct.size != embeddedIds.size then
      Left(EmbedError.InvalidResult("duplicate embedded item identity"))
    else if nonPublicIds.nonEmpty && embeddingReceipts.exists(_.items.isEmpty) then
      Left(EmbedError.InvalidResult("non-public batch cannot contain an empty-item receipt"))
    else if publicCarriesKeyedEvidence then
      Left(EmbedError.InvalidResult("public batch cannot contain keyed receipt evidence"))
    else if embeddingReceipts.exists(receipt => !receipt.isKeyConsistent) then
      Left(EmbedError.InvalidResult("inconsistent embedding receipt identity"))
    else if providerCalls != embeddingReceipts.map(_.call) then
      Left(
        EmbedError.InvalidResult(
          "provider calls must exactly match embedding receipt calls"
        )
      )
    else
      val declared = itemSensitivity.toMap
      embeddedItems.find(item => declared.get(item.id) != Some(item.sensitivity)) match
        case Some(_) =>
          Left(
            EmbedError.InvalidResult(
              "embedded item sensitivity must match the batch evidence"
            )
          )
        case None =>
          val keyedItems = embeddedItems.collect {
            case item if item.digest.kind == DigestKind.Keyed => item.id
          }.toSet
          val cacheCovered = cacheDecisions.collect {
            case CacheDecision.Hit(id, key) if key.kind == DigestKind.Keyed => id
            case CacheDecision.Denied(id, _)                                => id
          }.toSet
          val policyCovered = policyDecisions.collect {
            case PolicyDecision.KeyUnavailable(Some(id), _) => id
          }.toSet
          val batchRejected = resultDecisions.exists {
            case ResultDecision.BatchRejected(_) => true
            case _                               => false
          }
          val uncovered = nonPublicIds -- keyedItems -- cacheCovered -- policyCovered
          if uncovered.nonEmpty && !batchRejected then
            Left(
              EmbedError.InvalidResult(
                s"non-public items lack keyed or denial evidence: ${uncovered.toVector.map(_.value).sorted.mkString(",")}"
              )
            )
          else Right(())

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
    val nonPublic = itemSensitivity.collect {
      case (id, s) if !ReceiptDigest.plainAdmissible.contains(s) => id
    }
    if nonPublic.isEmpty then
      validateProviderReceipts(
        providerCalls,
        embeddingReceipts,
        cacheDecisions,
        policyDecisions,
        resultDecisions,
        itemSensitivity
      ).map(_ =>
        build(
          providerCalls,
          embeddingReceipts,
          cacheDecisions,
          policyDecisions,
          resultDecisions,
          itemSensitivity,
          DigestAuthority.Plain
        )
      )
    else
      val keyId = keys.currentKeyId
      SensitiveKeySnapshot.capture(keyId, keys) match
        case Right(snapshot) =>
          ofSnapshot(
            providerCalls,
            embeddingReceipts,
            cacheDecisions,
            policyDecisions,
            resultDecisions,
            itemSensitivity,
            snapshot
          )
        case Left(error @ EmbedError.NoKey(_)) =>
          validateProviderReceipts(
            providerCalls,
            embeddingReceipts,
            cacheDecisions,
            policyDecisions,
            resultDecisions,
            itemSensitivity
          ).flatMap { _ =>
            val denied = policyDecisions.collect {
              case PolicyDecision.KeyUnavailable(Some(id), _) => id
            }.toSet
            if !nonPublic.forall(denied.contains) then Left(error)
            else if providerCalls.nonEmpty || embeddingReceipts.nonEmpty then
              Left(EmbedError.InvalidResult("withheld receipt cannot describe provider calls"))
            else
              Right(
                build(
                  providerCalls,
                  embeddingReceipts,
                  cacheDecisions,
                  policyDecisions,
                  resultDecisions,
                  itemSensitivity,
                  DigestAuthority.Withheld(keyId)
                )
              )
          }
        case Left(error) => Left(error)

  /** Build under the one immutable key snapshot captured before non-public delegation. */
  private[embed] def ofSnapshot(
      providerCalls: Vector[ProviderCall],
      embeddingReceipts: Vector[EmbeddingReceipt],
      cacheDecisions: Vector[CacheDecision],
      policyDecisions: Vector[PolicyDecision],
      resultDecisions: Vector[ResultDecision],
      itemSensitivity: Vector[(RequestId, Sensitivity)],
      snapshot: SensitiveKeySnapshot
  ): Either[EmbedError, AttemptReceipt] =
    val hasNonPublic = itemSensitivity.exists { case (_, sensitivity) =>
      !ReceiptDigest.plainAdmissible.contains(sensitivity)
    }
    if !hasNonPublic then Left(EmbedError.InvalidResult("keyed receipt requires a non-public item"))
    else
      validateProviderReceipts(
        providerCalls,
        embeddingReceipts,
        cacheDecisions,
        policyDecisions,
        resultDecisions,
        itemSensitivity
      ).flatMap { _ =>
        val receiptDigests =
          embeddingReceipts.flatMap(receipt => receipt.outputs +: receipt.items.map(_.digest))
        val cacheDigests = cacheDecisions.flatMap {
          case CacheDecision.Hit(_, digest)    => Vector(digest)
          case CacheDecision.Miss(_, digest)   => Vector(digest)
          case CacheDecision.Denied(_, policy) => policyDigests(policy)
          case CacheDecision.Bypassed(_, _)    => Vector.empty
        }
        val keyedDigests = (receiptDigests ++ cacheDigests ++ policyDecisions.flatMap(
          policyDigests
        )).collect { case ReceiptDigest.Keyed(digest) => digest }
        val missingKeys =
          policyDecisions.collect { case PolicyDecision.KeyUnavailable(_, keyId) => keyId } ++
            cacheDecisions.collect {
              case CacheDecision.Denied(_, PolicyDecision.KeyUnavailable(_, keyId)) => keyId
            }
        keyedDigests.find(_.keyId != snapshot.keyId) match
          case Some(digest) =>
            Left(EmbedError.ReceiptKeyMismatch(snapshot.keyId, digest.keyId))
          case None =>
            missingKeys.find(_ != snapshot.keyId) match
              case Some(keyId) => Left(EmbedError.ReceiptKeyMismatch(snapshot.keyId, keyId))
              case None        =>
                Right(
                  build(
                    providerCalls,
                    embeddingReceipts,
                    cacheDecisions,
                    policyDecisions,
                    resultDecisions,
                    itemSensitivity,
                    DigestAuthority.Keyed(snapshot)
                  )
                )
      }

  /** Reject an invalid provider result under the trusted batch authority, preserving only calls. */
  private[embed] def rejectProviderResult(
      providerCalls: Vector[ProviderCall],
      cacheDecisions: Vector[CacheDecision],
      policyDecisions: Vector[PolicyDecision],
      itemSensitivity: Vector[(RequestId, Sensitivity)],
      error: EmbedError,
      snapshot: Option[SensitiveKeySnapshot]
  ): AttemptReceipt =
    val receiptRejected = error match
      case mismatch @ EmbedError.ReceiptKeyMismatch(_, _) =>
        Vector(ResultDecision.ReceiptRejected(mismatch))
      case mismatch @ EmbedError.AuthorityMismatch(_, _) =>
        Vector(ResultDecision.ReceiptRejected(mismatch))
      case _ => Vector.empty
    val authority = snapshot.fold[DigestAuthority](DigestAuthority.Plain)(DigestAuthority.Keyed(_))
    build(
      providerCalls,
      Vector.empty,
      cacheDecisions,
      policyDecisions,
      receiptRejected :+ ResultDecision.BatchRejected(error),
      itemSensitivity,
      authority
    )

  /** The receipt of an attempt that touched nothing: no items, no calls, no decisions. */
  private[embed] val empty: AttemptReceipt =
    build(
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      DigestAuthority.Plain
    )

  /** A `Plain` receipt for a batch whose items are ALL public (or empty): needs no key. It takes
    * the item sensitivities as evidence and refuses a non-public item — a keyed result can never be
    * re-receipted as plain (chief re-review, required (2)). `private[embed]`: providers outside the
    * package go through [[AttemptReceipt.of]].
    */
  private[embed] def public(
      providerCalls: Vector[ProviderCall],
      cacheDecisions: Vector[CacheDecision],
      policyDecisions: Vector[PolicyDecision],
      resultDecisions: Vector[ResultDecision],
      itemSensitivity: Vector[(RequestId, Sensitivity)]
  ): Either[EmbedError, AttemptReceipt] =
    validateProviderReceipts(
      providerCalls,
      Vector.empty,
      cacheDecisions,
      policyDecisions,
      resultDecisions,
      itemSensitivity
    ).flatMap { _ =>
      itemSensitivity.collectFirst {
        case (id, s) if !ReceiptDigest.plainAdmissible.contains(s) => id
      } match
        case Some(id) =>
          Left(
            EmbedError.InvalidResult(
              s"plain receipt refused: item ${id.value} is not public"
            )
          )
        case None =>
          Right(
            build(
              providerCalls,
              Vector.empty,
              cacheDecisions,
              policyDecisions,
              resultDecisions,
              itemSensitivity,
              DigestAuthority.Plain
            )
          )
    }

  /** The receipt of a batch that failed closed for lack of key `missing`: every non-public item is
    * recorded as `KeyUnavailable` and the identity is `Withheld` over a material-free rendering.
    */
  private[embed] def failClosed(
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
    build(
      Vector.empty,
      Vector.empty,
      cacheDecisions,
      policy,
      resultDecisions,
      itemSensitivity,
      DigestAuthority.Withheld(missing)
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

object BatchResult:
  /** Deny a keyless non-public provider batch before any call is made. */
  private[embed] def keyUnavailable(batch: EmbedBatch, keyId: KeyId): BatchResult =
    keyUnavailable(batch, keyId, cacheAttempt = false)

  /** Deny a keyless cache batch before lookup or delegation, recording cache denials. */
  private[embed] def cacheKeyUnavailable(batch: EmbedBatch, keyId: KeyId): BatchResult =
    keyUnavailable(batch, keyId, cacheAttempt = true)

  private def keyUnavailable(
      batch: EmbedBatch,
      keyId: KeyId,
      cacheAttempt: Boolean
  ): BatchResult =
    val denials = batch.requests.map { request =>
      new PolicyDecision.KeyUnavailable(Some(request.id), keyId)
    }
    val outcomes = batch.requests.zip(denials).map { case (request, denial) =>
      EmbedOutcome(request.id, request.space, Left(ExecutionFailure.PolicyDenied(denial)))
    }
    val cacheDecisions =
      if cacheAttempt then
        batch.requests.zip(denials).map { case (request, denial) =>
          CacheDecision.Denied(request.id, denial)
        }
      else Vector.empty
    BatchResult(
      outcomes,
      AttemptReceipt.failClosed(
        cacheDecisions,
        denials.map(d => d: PolicyDecision),
        Vector.empty,
        batch.itemSensitivity,
        keyId
      )
    )

  /** Reject a corrupt batch key before provider work while preserving the typed key failure. */
  private[embed] def invalidKey(
      batch: EmbedBatch,
      keyId: KeyId,
      error: EmbedError.InvalidKey
  ): BatchResult =
    val denials = batch.requests.map { request =>
      new PolicyDecision.KeyUnavailable(Some(request.id), keyId)
    }
    val outcomes = batch.requests.map { request =>
      EmbedOutcome(request.id, request.space, Left(ExecutionFailure.Invalid(error)))
    }
    BatchResult(
      outcomes,
      AttemptReceipt.failClosed(
        Vector.empty,
        denials.map(d => d: PolicyDecision),
        Vector(ResultDecision.BatchRejected(error)),
        batch.itemSensitivity,
        keyId
      )
    )

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
    * because their vectors cannot be associated safely. For a non-public batch, `keys` is captured
    * once before delegation and every escaping receipt is reconstructed under that authority.
    */
  def conforming[F[_]: Applicative](
      underlying: Embedder[F],
      keys: SensitiveKeyProvider = SensitiveKeyProvider.none
  ): Embedder[F] =
    new Embedder[F]:
      def info: EmbedderInfo = underlying.info
      def spaces: Vector[EmbeddingSpace] = underlying.spaces
      def embed(batch: EmbedBatch): F[BatchResult] =
        val nonPublic = batch.itemSensitivity.exists { case (_, sensitivity) =>
          !ReceiptDigest.plainAdmissible.contains(sensitivity)
        }
        if !nonPublic then Applicative[F].map(underlying.embed(batch))(normalize(batch, _, None))
        else
          val keyId = keys.currentKeyId
          SensitiveKeySnapshot.capture(keyId, keys) match
            case Right(snapshot) =>
              Applicative[F].map(underlying.embed(batch))(normalize(batch, _, Some(snapshot)))
            case Left(error @ EmbedError.InvalidKey(_)) =>
              Applicative[F].pure(BatchResult.invalidKey(batch, keyId, error))
            case Left(_) => Applicative[F].pure(BatchResult.keyUnavailable(batch, keyId))

  private def normalize(
      batch: EmbedBatch,
      result: BatchResult,
      snapshot: Option[SensitiveKeySnapshot]
  ): BatchResult =
    trustedReceipt(batch, result.receipt, snapshot) match
      case Left(error)    => rejectUntrusted(batch, result, error, snapshot)
      case Right(receipt) =>
        val trusted = result.copy(receipt = receipt)
        val expectedIds = batch.ids.toSet
        val grouped = trusted.outcomes.zipWithIndex.groupMap(_._1.id)(identity)
        val actualIds = grouped.keySet
        val duplicateIds = grouped.collect {
          case (id, values) if values.size > 1 => id
        }.toVector
        val missingIds = expectedIds.diff(actualIds).toVector
        val extraIds = actualIds.diff(expectedIds).toVector

        associationError(
          trusted.outcomes.size,
          batch.requests.size,
          duplicateIds,
          missingIds,
          extraIds
        ).fold(normalizeBijection(batch, trusted, grouped, snapshot))(
          rejectTrusted(batch, trusted, _, snapshot)
        )

  private def trustedReceipt(
      batch: EmbedBatch,
      receipt: AttemptReceipt,
      snapshot: Option[SensitiveKeySnapshot]
  ): Either[EmbedError, AttemptReceipt] =
    snapshot match
      case Some(authority) =>
        receipt.digest match
          case ReceiptDigest.Keyed(digest) if digest.keyId != authority.keyId =>
            Left(EmbedError.ReceiptKeyMismatch(authority.keyId, digest.keyId))
          case ReceiptDigest.Keyed(_) =>
            AttemptReceipt
              .ofSnapshot(
                receipt.providerCalls,
                receipt.embeddingReceipts,
                receipt.cacheDecisions,
                receipt.policyDecisions,
                receipt.resultDecisions,
                batch.itemSensitivity,
                authority
              )
              .flatMap { rebuilt =>
                if rebuilt.digest == receipt.digest then Right(rebuilt)
                else
                  Left(EmbedError.InvalidResult("attempt receipt digest does not match its body"))
              }
          case _ =>
            Left(EmbedError.InvalidResult("non-public batch returned a non-keyed receipt"))
      case None =>
        receipt.digest match
          case ReceiptDigest.Plain(_) =>
            AttemptReceipt.of(
              receipt.providerCalls,
              receipt.embeddingReceipts,
              receipt.cacheDecisions,
              receipt.policyDecisions,
              receipt.resultDecisions,
              batch.itemSensitivity,
              SensitiveKeyProvider.none
            )
          case _ => Left(EmbedError.InvalidResult("public batch returned a non-plain receipt"))

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
      grouped: Map[RequestId, Vector[(EmbedOutcome, Int)]],
      snapshot: Option[SensitiveKeySnapshot]
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
      rejectTrusted(
        batch,
        result,
        EmbedError.InvalidResult("outcomes did not form a complete request-id bijection"),
        snapshot
      )
    else
      val decisions = normalized.flatMap(_._2)
      result.receipt.addResultDecisions(decisions, snapshot) match
        case Right(receipt) =>
          BatchResult(receipt.enforceWithholding(normalized.map(_._1)), receipt)
        case Left(error) => rejectUntrusted(batch, result, error, snapshot)

  private def rejectTrusted(
      batch: EmbedBatch,
      result: BatchResult,
      error: EmbedError,
      snapshot: Option[SensitiveKeySnapshot]
  ): BatchResult =
    result.receipt
      .addResultDecisions(Vector(ResultDecision.BatchRejected(error)), snapshot)
      .fold(
        amendmentError => rejectUntrusted(batch, result, amendmentError, snapshot),
        receipt =>
          BatchResult(
            batch.requests.map(request =>
              EmbedOutcome(request.id, request.space, Left(ExecutionFailure.Invalid(error)))
            ),
            receipt
          )
      )

  private def rejectUntrusted(
      batch: EmbedBatch,
      result: BatchResult,
      error: EmbedError,
      snapshot: Option[SensitiveKeySnapshot]
  ): BatchResult =
    val receipt = AttemptReceipt.rejectProviderResult(
      result.receipt.providerCalls,
      result.receipt.cacheDecisions,
      result.receipt.policyDecisions,
      batch.itemSensitivity,
      error,
      snapshot
    )
    BatchResult(
      batch.requests.map(request =>
        EmbedOutcome(request.id, request.space, Left(ExecutionFailure.Invalid(error)))
      ),
      receipt
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
