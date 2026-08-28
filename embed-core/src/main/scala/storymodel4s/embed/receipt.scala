package storymodel4s.embed

import cats.{Order, Show}

import storymodel4s.core.{Checksum, ProviderCall}

/** Whether a receipt identity is a plain content hash, an HMAC under a store-local key, or a
  * material-free hash produced because a required key was absent.
  *
  * Why a typed field: a reader must be able to tell a keyed identity from a plain one without
  * parsing `ProviderCall.params` (ADR 0001 D6, chief addition 2). `Withheld` exists so that the
  * receipt of a batch that failed closed for lack of a key is still a receipt — and is labelled as
  * such — rather than a silent plain fallback.
  */
enum DigestKind:
  case Plain
  case Keyed
  case Withheld

  def render: String = this match
    case Plain    => "plain"
    case Keyed    => "keyed"
    case Withheld => "withheld"

/** The canonical, escaped rendering of every receipt surface (ADR 0001 D6 canonical rendering).
  *
  * Fields are joined by `|`, records by `\n`; the four separator characters `\`, `|`, newline and
  * NUL are escaped as `\\`, `\|`, `\n`, `\0` so no field can forge a separator. The HMAC/hash input
  * of every identity is exactly one of these renderings — including the cross-platform golden
  * vector, which hashes a production [[ReceiptRendering.material]] rendering.
  */
object ReceiptRendering:
  val MaterialVersion = "material/v1"
  val PseudoVersion = "pseudo/v1"
  val ItemsVersion = "items/v1"
  val OutputsVersion = "outputs/v1"
  val AttemptVersion = "attempt/v1"

  def esc(s: String): String =
    val sb = new StringBuilder(s.length + 8)
    var i = 0
    while i < s.length do
      s.charAt(i) match
        case '\\'     => sb.append("\\\\")
        case '|'      => sb.append("\\|")
        case '\n'     => sb.append("\\n")
        case '\u0000' => sb.append("\\0")
        case c        => sb.append(c)
      i += 1
    sb.result()

  /** The exact material a provider embeds for a request: role, instruction digest and text. */
  def material(role: Role, instruction: Option[InstructionDigest], text: String): String =
    s"$MaterialVersion|role=${role.render}|instruction=${instruction.fold("-")(_.render)}|text=${esc(text)}"

  /** Identity input of a pseudonymized payload: policy, key id and sanitized text. */
  def pseudonymized(policyId: PrivacyPolicyId, keyId: KeyId, text: String): String =
    s"$PseudoVersion|policy=${esc(policyId.value)}|key=${esc(keyId.value)}|text=${esc(text)}"

  /** Per-item identities of one provider call (no material, only rendered digests). */
  def items(items: Vector[ItemDigest]): String =
    (ItemsVersion +: items.map(i => s"item|${esc(i.id.value)}|${i.sensitivity}|${i.digest.render}"))
      .mkString("\n")

/** The identity of some receipt surface (cache key, provider-call input/output, capability payload,
  * attempt receipt).
  *
  * Sealed with `private[embed]` constructors: outside this package a `Plain` identity of sensitive
  * material cannot be constructed at all — the only constructors are [[ReceiptDigest.of]] (Plain
  * iff `Sensitivity.Public`), [[ReceiptDigest.keyed]] / [[ReceiptDigest.keyedUnder]] (HMAC or a
  * typed `NoKey`), and the `private[embed]` [[ReceiptDigest.withheld]] used only by
  * [[AttemptReceipt]] for a material-free rendering. Rendered forms never contain material or key
  * bytes.
  */
sealed trait ReceiptDigest:
  def kind: DigestKind
  def keyIdOption: Option[KeyId]

  /** `plain:<hex>`, `hmac:<keyId>:<hex>` or `withheld:<keyId>:<hex>` — hex values are either hashes
    * of public material, key-gated HMACs, or hashes of material-free renderings.
    */
  def render: String

object ReceiptDigest:
  final case class Plain private[embed] (checksum: Checksum) extends ReceiptDigest:
    def kind: DigestKind = DigestKind.Plain
    def keyIdOption: Option[KeyId] = None
    def render: String = s"plain:${checksum.hex}"

  final case class Keyed private[embed] (digest: SensitiveDigest) extends ReceiptDigest:
    def kind: DigestKind = DigestKind.Keyed
    def keyId: KeyId = digest.keyId
    def keyIdOption: Option[KeyId] = Some(digest.keyId)
    def render: String = digest.render

  /** A material-free identity recorded when a required key was absent; only [[AttemptReceipt]]
    * mints it, and only when every non-public item of the batch carries a
    * `PolicyDecision.KeyUnavailable`.
    */
  final case class Withheld private[embed] (missingKey: KeyId, checksum: Checksum)
      extends ReceiptDigest:
    def kind: DigestKind = DigestKind.Withheld
    def keyIdOption: Option[KeyId] = Some(missingKey)
    def render: String = s"withheld:${missingKey.value}:${checksum.hex}"

  /** Sensitivities whose receipt identity may be a plain content hash. Exactly `Public`. */
  val plainAdmissible: Set[Sensitivity] = Set(Sensitivity.Public)

  /** Digest `material` for its sensitivity. `Internal` and `Sensitive` are keyed under the
    * provider's current key; a missing key is a typed [[EmbedError.NoKey]] — never a plain
    * fallback.
    */
  def of(
      sensitivity: Sensitivity,
      material: String,
      keys: SensitiveKeyProvider
  ): Either[EmbedError, ReceiptDigest] =
    if plainAdmissible.contains(sensitivity) then Right(Plain(Checksum.ofText(material)))
    else keyed(material, keys)

  /** Keyed identity under the provider's current key, regardless of sensitivity. */
  def keyed(material: String, keys: SensitiveKeyProvider): Either[EmbedError, ReceiptDigest] =
    keyedUnder(keys.currentKeyId, material, keys)

  /** Keyed identity under a specific key id (e.g. the pseudonymization key named by a payload). */
  def keyedUnder(
      keyId: KeyId,
      material: String,
      keys: SensitiveKeyProvider
  ): Either[EmbedError, ReceiptDigest] =
    keys.key(keyId) match
      case None    => Left(EmbedError.NoKey(keyId.value))
      case Some(k) => SensitiveDigest.compute(keyId, k, material).map(Keyed(_))

  /** Plain identity of material that is public by construction (the caller asserts the sensitivity;
    * use [[of]] whenever a `Sensitivity` is in hand).
    */
  private[embed] def plainPublic(material: String): ReceiptDigest = Plain(Checksum.ofText(material))

  private[embed] def withheld(missingKey: KeyId, materialFreeRendering: String): ReceiptDigest =
    Withheld(missingKey, Checksum.ofText(materialFreeRendering))

  given Order[ReceiptDigest] = Order.by(_.render)
  given Show[ReceiptDigest] = Show.show(_.render)

/** One item's identity inside a provider call, with the sensitivity evidence that justifies its
  * digest kind. Constructed only through [[ItemDigest.of]], which refuses a `Plain` digest for
  * non-public material.
  */
final case class ItemDigest private[embed] (
    id: RequestId,
    sensitivity: Sensitivity,
    digest: ReceiptDigest
)

object ItemDigest:
  def of(
      id: RequestId,
      sensitivity: Sensitivity,
      digest: ReceiptDigest
  ): Either[EmbedError, ItemDigest] =
    digest.kind match
      case DigestKind.Plain if !ReceiptDigest.plainAdmissible.contains(sensitivity) =>
        Left(EmbedError.InvalidResult(s"plain identity for $sensitivity item ${id.value}"))
      case DigestKind.Withheld =>
        Left(EmbedError.InvalidResult(s"withheld identity is not an item identity (${id.value})"))
      case _ => Right(ItemDigest(id, sensitivity, digest))

/** The embed-level authority on how a provider call identified its material.
  *
  * Why: `core.ProviderCall` carries plain `Checksum` fields shared by every provider in the
  * library. For non-public material those fields hold `Checksum.ofText(<keyed renderings>)` — a
  * hash of HMAC outputs, still key-gated — and `kind` states that fact in a typed way so an auditor
  * never has to infer it from `params` (ADR 0001 D6).
  */
final case class EmbeddingReceipt private (
    call: ProviderCall,
    kind: DigestKind,
    items: Vector[ItemDigest],
    outputs: ReceiptDigest
):
  /** True iff no rendered identity in this receipt is a plain hash of non-public material. */
  def isKeyConsistent: Boolean =
    items.forall(i =>
      (i.digest.kind == DigestKind.Plain) == ReceiptDigest.plainAdmissible.contains(i.sensitivity)
    ) &&
      (kind match
        case DigestKind.Plain    => outputs.kind == DigestKind.Plain
        case DigestKind.Keyed    => outputs.kind == DigestKind.Keyed
        case DigestKind.Withheld => false)

object EmbeddingReceipt:
  val KindParam = "digest-kind"
  val KeyParam = "digest-key-id"

  /** Build the receipt for one provider call. `base` supplies provider/model/version/params; its
    * checksums are overwritten from the item digests and `outputs` so the identity discipline is
    * decided here, not by each provider. The receipt is `Keyed` iff any item or the outputs are
    * keyed; in that case `outputs` must be keyed too (a plain hash of vectors computed from
    * sensitive text is itself a dictionary-attack surface). A withheld identity never describes a
    * provider call (no call is made without a key).
    */
  def of(
      base: ProviderCall,
      items: Vector[ItemDigest],
      outputs: ReceiptDigest
  ): Either[EmbedError, EmbeddingReceipt] =
    val anyKeyed =
      items.exists(_.digest.kind == DigestKind.Keyed) || outputs.kind == DigestKind.Keyed
    val kind = if anyKeyed then DigestKind.Keyed else DigestKind.Plain
    if outputs.kind == DigestKind.Withheld || items.exists(_.digest.kind == DigestKind.Withheld)
    then Left(EmbedError.InvalidResult("withheld identities cannot describe a provider call"))
    else if anyKeyed && outputs.kind != DigestKind.Keyed then
      Left(EmbedError.InvalidResult("keyed items require keyed outputs"))
    else
      val inputIdentity = Checksum.ofText(ReceiptRendering.items(items))
      val outputIdentity = Checksum.ofText(outputs.render)
      val keyParams = outputs.keyIdOption.map(k => KeyParam -> k.value).toMap
      val call = base.copy(
        inputChecksum = inputIdentity,
        outputChecksum = outputIdentity,
        params = base.params ++ Map(KindParam -> kind.render) ++ keyParams
      )
      Right(EmbeddingReceipt(call, kind, items, outputs))
