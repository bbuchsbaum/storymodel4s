package storymodel4s.embed

import cats.{Order, Show}

import storymodel4s.core.{Checksum, ProviderCall}

/** Whether a receipt identity is a plain content hash or an HMAC under a store-local key.
  *
  * Why a typed field: a reader must be able to tell a keyed identity from a plain one without
  * parsing `ProviderCall.params` (ADR 0001 D6, chief addition 2).
  */
enum DigestKind:
  case Plain
  case Keyed

  def render: String = this match
    case Plain => "plain"
    case Keyed => "keyed"

/** The identity of some receipt surface (cache key, provider-call input/output, capability payload,
  * attempt receipt). `Plain` is admissible ONLY for `Sensitivity.Public` material; every other
  * sensitivity yields a `Keyed` identity or a typed error. Rendered forms never contain material or
  * key bytes.
  */
enum ReceiptDigest:
  case Plain(checksum: Checksum)
  case Keyed(digest: SensitiveDigest, keyId: KeyId)

  def kind: DigestKind = this match
    case Plain(_)    => DigestKind.Plain
    case Keyed(_, _) => DigestKind.Keyed

  def keyIdOption: Option[KeyId] = this match
    case Plain(_)    => None
    case Keyed(_, k) => Some(k)

  /** `plain:<hex>` or `hmac:<keyId>:<hex>` — the HMAC hex is key-gated, the key id is not secret.
    */
  def render: String = this match
    case Plain(c)    => s"plain:${c.hex}"
    case Keyed(d, _) => d.render

  /** Bridge to the pre-existing `MaterialDigest` vocabulary used by `CacheDecision`
    * (contract.scala, not editable in this slice). PHASE 2 (after P0-1): switch `CacheDecision` to
    * `ReceiptDigest` and delete this bridge.
    */
  def toMaterial: MaterialDigest = this match
    case Plain(c)    => MaterialDigest.Plain(c)
    case Keyed(d, _) => MaterialDigest.Sensitive(d)

object ReceiptDigest:
  /** Sensitivities whose receipt identity may be a plain content hash. Exactly `Public`. */
  val plainAdmissible: Set[Sensitivity] = Set(Sensitivity.Public)

  /** Digest `material` for its sensitivity. `Internal` and `Sensitive` are keyed under the
    * provider's current key; a missing key is a typed error — never a plain fallback.
    */
  def of(
      sensitivity: Sensitivity,
      material: String,
      keys: SensitiveKeyProvider
  ): Either[EmbedError, ReceiptDigest] =
    if plainAdmissible.contains(sensitivity) then Right(Plain(Checksum.ofText(material)))
    else keyed(material, keys)

  /** Keyed identity regardless of sensitivity (used for batch-level identities once any item in the
    * batch is non-public).
    */
  def keyed(material: String, keys: SensitiveKeyProvider): Either[EmbedError, ReceiptDigest] =
    val id = keys.currentKeyId
    keys.key(id) match
      case None    => Left(EmbedError.InvalidKey(s"no key for ${id.value}"))
      case Some(k) => SensitiveDigest.compute(id, k, material).map(d => Keyed(d, d.keyId))

  def fromMaterial(m: MaterialDigest): ReceiptDigest = m match
    case MaterialDigest.Plain(c)     => Plain(c)
    case MaterialDigest.Sensitive(d) => Keyed(d, d.keyId)

  given Order[ReceiptDigest] = Order.by(_.render)
  given Show[ReceiptDigest] = Show.show(_.render)

/** The embed-level authority on how a provider call identified its material.
  *
  * Why: `core.ProviderCall` carries plain `Checksum` fields shared by every provider in the
  * library. For non-public material those fields hold `Checksum.ofText(keyedDigest.render)` — a
  * hash of an HMAC output, still key-gated — and `kind` states that fact in a typed way so an
  * auditor never has to infer it from `params`.
  */
final case class EmbeddingReceipt private (
    call: ProviderCall,
    kind: DigestKind,
    items: Vector[(RequestId, ReceiptDigest)],
    outputs: ReceiptDigest
):
  /** True iff no rendered identity in this receipt is a plain hash of non-public material. */
  def isKeyConsistent: Boolean =
    kind match
      case DigestKind.Plain =>
        items.forall(_._2.kind == DigestKind.Plain) && outputs.kind == DigestKind.Plain
      case DigestKind.Keyed => outputs.kind == DigestKind.Keyed

object EmbeddingReceipt:
  val KindParam = "digest-kind"
  val KeyParam = "digest-key-id"

  /** Build the receipt for one provider call. `base` supplies provider/model/version/params; its
    * checksums are overwritten from the item digests and `outputs` so the identity discipline is
    * decided here, not by each provider. The receipt is `Keyed` iff any item or the outputs are
    * keyed; in that case `outputs` must be keyed too (a plain hash of vectors computed from
    * sensitive text is itself a dictionary-attack surface).
    */
  def of(
      base: ProviderCall,
      items: Vector[(RequestId, ReceiptDigest)],
      outputs: ReceiptDigest
  ): Either[EmbedError, EmbeddingReceipt] =
    val anyKeyed = items.exists(_._2.kind == DigestKind.Keyed) || outputs.kind == DigestKind.Keyed
    val kind = if anyKeyed then DigestKind.Keyed else DigestKind.Plain
    if anyKeyed && outputs.kind != DigestKind.Keyed then
      Left(EmbedError.InvalidResult("keyed items require keyed outputs"))
    else
      val inputIdentity =
        Checksum.ofText(items.map { case (id, d) => s"${id.value}=${d.render}" }.mkString("\n"))
      val outputIdentity = Checksum.ofText(outputs.render)
      val keyParams = outputs.keyIdOption.map(k => KeyParam -> k.value).toMap
      val call = base.copy(
        inputChecksum = inputIdentity,
        outputChecksum = outputIdentity,
        params = base.params ++ Map(KindParam -> kind.render) ++ keyParams
      )
      Right(EmbeddingReceipt(call, kind, items, outputs))
