package storymodel4s.embed

import cats.{Order, Show}

import storymodel4s.core.{DomainError, OpaqueId, TextSpan}

/** How sensitive an input is. Autobiographical transcripts are `Sensitive` by construction. */
enum Sensitivity:
  case Public
  case Internal
  case Sensitive

object Sensitivity:
  given Order[Sensitivity] = Order.by {
    case Sensitivity.Public    => 0
    case Sensitivity.Internal  => 1
    case Sensitivity.Sensitive => 2
  }

/** Where a provider runs. `Remote` providers never receive `EmbedPayload.Raw` sensitive text. */
enum Locality:
  case Local
  case Remote

/** The most sensitive input a provider is permitted to see as raw text. */
enum PrivacyClass:
  case PublicOnly
  case InternalOk
  case SensitiveOk

  def admits(s: Sensitivity): Boolean = (this, s) match
    case (SensitiveOk, _)                   => true
    case (InternalOk, Sensitivity.Public)   => true
    case (InternalOk, Sensitivity.Internal) => true
    case (PublicOnly, Sensitivity.Public)   => true
    case _                                  => false

object PrivacyPolicyId extends OpaqueId("PrivacyPolicyId")
type PrivacyPolicyId = PrivacyPolicyId.T

/** Text carrying an explained pseudonymization transformation under a named policy and key.
  *
  * This value carries no re-identification key; the key is a separately held `ReidentificationKey`
  * in the interview module. Construction is private so unchecked text cannot be laundered into
  * [[EmbedPayload.Sanitized]] by choosing that enum case. Residual sensitivity is a trust boundary:
  * it rests with the caller of [[PseudonymizedText.checked]], normally the `Pseudonymizer`.
  */
final class PseudonymizedText private (
    val policyId: PrivacyPolicyId,
    val keyId: KeyId,
    val text: String,
    val offsets: Vector[(TextSpan, TextSpan)],
    /** Keyed identity of this payload under its pseudonymization key (ADR 0001 D6): an HMAC over
      * the canonical `pseudo/v1` rendering, minted by [[PseudonymizedText.checked]] and never a
      * plain hash of the sanitized text. Always `DigestKind.Keyed` under `keyId`.
      */
    val digest: ReceiptDigest
):
  override def equals(other: Any): Boolean = other match
    case that: PseudonymizedText =>
      policyId == that.policyId && keyId == that.keyId && text == that.text &&
      offsets == that.offsets && digest == that.digest
    case _ => false

  override def hashCode: Int = (policyId, keyId, text, offsets, digest).##

  override def toString: String =
    s"PseudonymizedText(<redacted>, length=${text.length}, replacements=${offsets.size})"

object PseudonymizedText:
  /** Invariant path reported when the pseudonymization key `keyId` is unavailable in `keys`. */
  val KeyPath: String = "pseudonymization/key"

  /** Check that an offset map is a complete, code-point-safe account of pseudonymization, and mint
    * the payload's keyed identity under `keyId` from `keys`.
    *
    * Each pair maps a nonempty source span to its replacement span in `text`. Text outside mapped
    * spans must be unchanged, so callers cannot hide an unexplained transformation or present raw
    * text with an empty/fictitious map. Validation errors report only positions and invariant
    * names, never source or destination text. A missing key fails closed with
    * `InvariantViolation(KeyPath, …)`: no `PseudonymizedText` exists without a keyed digest.
    */
  def checked(
      policyId: PrivacyPolicyId,
      keyId: KeyId,
      sourceText: String,
      text: String,
      offsets: Vector[(TextSpan, TextSpan)],
      keys: SensitiveKeyProvider
  ): Either[DomainError, PseudonymizedText] =
    for
      _ <- validateUtf16(sourceText, "source")
      _ <- validateUtf16(text, "destination")
      _ <- validateMap(sourceText, text, offsets)
      digest <- ReceiptDigest
        .keyedUnder(keyId, ReceiptRendering.pseudonymized(policyId, keyId, text), keys)
        .left
        .map(e => DomainError.InvariantViolation(KeyPath, e.message))
    yield new PseudonymizedText(policyId, keyId, text, offsets, digest)

  private def validateMap(
      sourceText: String,
      destinationText: String,
      offsets: Vector[(TextSpan, TextSpan)]
  ): Either[DomainError, Unit] =
    if sourceText.isEmpty && destinationText.isEmpty && offsets.isEmpty then Right(())
    else if sourceText == destinationText then
      Left(
        DomainError.InvariantViolation(
          "pseudonymizedText/text",
          "pseudonymization must change nonempty source text"
        )
      )
    else if offsets.isEmpty then
      Left(
        DomainError.InvariantViolation(
          "pseudonymizedText/offsets",
          "a nonempty transformation requires at least one replacement"
        )
      )
    else
      var sourceCursor = 0
      var destinationCursor = 0
      var index = 0
      var problem: Option[DomainError] = None

      while index < offsets.size && problem.isEmpty do
        val (source, destination) = offsets(index)
        val path = s"pseudonymizedText/offsets/$index"
        problem = validateSpan(sourceText, source, s"$path/source", allowEmpty = false)
          .orElse(
            validateSpan(destinationText, destination, s"$path/destination", allowEmpty = true)
          )
          .orElse(
            Option.when(source.start < sourceCursor)(
              DomainError.InvariantViolation(
                s"$path/source",
                "source replacements must be ordered and nonoverlapping"
              )
            )
          )
          .orElse(
            Option.when(destination.start < destinationCursor)(
              DomainError.InvariantViolation(
                s"$path/destination",
                "destination replacements must be ordered and nonoverlapping"
              )
            )
          )
          .orElse(
            Option.when(
              sourceText.substring(sourceCursor, source.start) !=
                destinationText.substring(destinationCursor, destination.start)
            )(
              DomainError.InvariantViolation(
                path,
                "text outside replacement spans must be unchanged"
              )
            )
          )
          .orElse {
            val sourceSurface = sourceText.substring(source.start, source.endExclusive)
            val replacement = destinationText.substring(destination.start, destination.endExclusive)
            Option.when(replacement.contains(sourceSurface))(
              DomainError.InvariantViolation(
                path,
                "a replacement must not contain its source span text"
              )
            )
          }
        sourceCursor = source.endExclusive
        destinationCursor = destination.endExclusive
        index += 1

      problem
        .orElse(
          Option.when(
            sourceText.substring(sourceCursor) != destinationText.substring(destinationCursor)
          )(
            DomainError.InvariantViolation(
              "pseudonymizedText/offsets",
              "text outside replacement spans must be unchanged"
            )
          )
        )
        .toLeft(())

  private def validateSpan(
      text: String,
      span: TextSpan,
      path: String,
      allowEmpty: Boolean
  ): Option[DomainError] =
    if !allowEmpty && span.isEmpty then
      Some(DomainError.InvalidSpan(span.start, span.endExclusive, s"$path must be nonempty"))
    else if span.endExclusive > text.length then
      Some(
        DomainError.InvalidSpan(
          span.start,
          span.endExclusive,
          s"$path exceeds text length ${text.length}"
        )
      )
    else if !isCodePointBoundary(text, span.start) then
      Some(
        DomainError.InvalidSpan(
          span.start,
          span.endExclusive,
          s"$path starts inside a UTF-16 surrogate pair"
        )
      )
    else if !isCodePointBoundary(text, span.endExclusive) then
      Some(
        DomainError.InvalidSpan(
          span.start,
          span.endExclusive,
          s"$path ends inside a UTF-16 surrogate pair"
        )
      )
    else None

  private def validateUtf16(text: String, path: String): Either[DomainError, Unit] =
    var index = 0
    var problem: Option[DomainError] = None
    while index < text.length && problem.isEmpty do
      val char = text.charAt(index)
      if Character.isHighSurrogate(char) then
        if index + 1 < text.length && Character.isLowSurrogate(text.charAt(index + 1)) then
          index += 2
        else
          problem = Some(
            DomainError.InvalidSpan(index, index + 1, s"$path contains an unpaired high surrogate")
          )
      else if Character.isLowSurrogate(char) then
        problem = Some(
          DomainError.InvalidSpan(index, index + 1, s"$path contains an unpaired low surrogate")
        )
      else index += 1
    problem.toLeft(())

  private def isCodePointBoundary(text: String, offset: Int): Boolean =
    offset >= 0 && offset <= text.length &&
      (offset == 0 || offset == text.length ||
        !(Character.isHighSurrogate(text.charAt(offset - 1)) &&
          Character.isLowSurrogate(text.charAt(offset))))

/** Outcome of a policy evaluation; recorded in receipts, never containing payload text. */
enum PolicyDecision:
  case Allowed(policyId: PrivacyPolicyId, capability: RemoteCapability)
  case LocalOnly(policyId: Option[PrivacyPolicyId], reason: String)
  case Denied(policyId: Option[PrivacyPolicyId], reason: String)

  /** A required store key was absent: the item (or, with `None`, the batch) failed closed. */
  case KeyUnavailable(id: Option[RequestId], keyId: KeyId)

  def render: String = this match
    case Allowed(p, c)         => s"allowed:${p.value}:${c.render}"
    case LocalOnly(p, r)       => s"local-only:${p.fold("-")(_.value)}:$r"
    case Denied(p, r)          => s"denied:${p.fold("-")(_.value)}:$r"
    case KeyUnavailable(id, k) => s"key-unavailable:${id.fold("-")(_.value)}:${k.value}"

/** Time is supplied by the caller (epoch millis) so the module stays clock-free and testable. */
final case class RemoteCapability private[embed] (
    provider: ProviderFingerprint,
    model: String,
    purpose: String,
    policyId: PrivacyPolicyId,
    expiresAtEpochMillis: Long,
    budgetTokens: Long,
    payloadDigest: ReceiptDigest
):
  def render: String =
    s"cap:${provider.render.take(12)}:$model:$purpose:${policyId.value}:$expiresAtEpochMillis:$budgetTokens:${payloadDigest.render.takeRight(12)}"

/** A remote policy: which providers/models/purposes may receive which sanitized payloads. */
final case class RemotePolicy(
    id: PrivacyPolicyId,
    allowedProviders: Set[ProviderFingerprint],
    allowedModels: Set[String],
    allowedPurposes: Set[String],
    maxBudgetTokens: Long,
    ttlMillis: Long
)

/** The only request type a remote transport accepts. Constructible solely through
  * [[RemotePolicy.evaluate]]; the constructor is private so no code path can hand raw text to a
  * remote provider.
  */
final case class AuthorizedRemoteRequest private[embed] (
    id: RequestId,
    space: GeometryId,
    payload: PseudonymizedText,
    capability: RemoteCapability
)

object RemotePolicy:
  /** Evaluate the request's sanitized payload against a policy for a provider/model/purpose.
    * Returns the authorized request bound to that exact payload and a capability, or a typed denial
    * (never an exception, never the payload text in the reason).
    */
  def evaluate(
      policy: RemotePolicy,
      request: EmbedRequest,
      provider: ProviderFingerprint,
      model: String,
      purpose: String,
      nowEpochMillis: Long,
      estimatedTokens: Long
  ): Either[PolicyDecision.Denied, AuthorizedRemoteRequest] =
    def deny(reason: String): Either[PolicyDecision.Denied, AuthorizedRemoteRequest] =
      Left(PolicyDecision.Denied(Some(policy.id), reason))
    request.payload match
      case EmbedPayload.Raw(_, _)          => deny("request payload is not pseudonymized")
      case EmbedPayload.Sanitized(payload) =>
        if payload.policyId != policy.id then deny("payload pseudonymized under a different policy")
        else if !policy.allowedProviders.contains(provider) then deny("provider not allowed")
        else if !policy.allowedModels.contains(model) then deny("model not allowed")
        else if !policy.allowedPurposes.contains(purpose) then deny("purpose not allowed")
        else if estimatedTokens < 0 || estimatedTokens > policy.maxBudgetTokens then
          deny("budget exceeded")
        else if policy.ttlMillis <= 0 then deny("policy has no validity window")
        else
          val cap = RemoteCapability(
            provider,
            model,
            purpose,
            policy.id,
            nowEpochMillis + policy.ttlMillis,
            estimatedTokens,
            payload.digest
          )
          Right(AuthorizedRemoteRequest(request.id, request.space, payload, cap))

  given Show[PolicyDecision] = Show.show(_.render)
