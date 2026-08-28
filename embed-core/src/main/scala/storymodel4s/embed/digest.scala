package storymodel4s.embed

import cats.{Order, Show}

import storymodel4s.core.{Checksum, OpaqueId, Sha256}

/** Identifies a store-local secret key (rotation happens by issuing a new id). */
object KeyId extends OpaqueId("KeyId")
type KeyId = KeyId.T

/** Portable HMAC-SHA256 over core's pure SHA-256 (RFC 2104), so sensitive cache identities never
  * depend on `javax.crypto`.
  */
object Hmac:
  private val BlockSize = 64

  def sha256(key: Array[Byte], message: Array[Byte]): Array[Byte] =
    val k0 = if key.length > BlockSize then Sha256.digest(key) else key
    val k = java.util.Arrays.copyOf(k0, BlockSize)
    val ipad = new Array[Byte](BlockSize)
    val opad = new Array[Byte](BlockSize)
    var i = 0
    while i < BlockSize do
      ipad(i) = (k(i) ^ 0x36).toByte
      opad(i) = (k(i) ^ 0x5c).toByte
      i += 1
    val inner = Sha256.digest(concat(ipad, message))
    Sha256.digest(concat(opad, inner))

  def hex(key: Array[Byte], message: Array[Byte]): String = Sha256.hex(sha256(key, message))

  private def concat(a: Array[Byte], b: Array[Byte]): Array[Byte] =
    val out = new Array[Byte](a.length + b.length)
    System.arraycopy(a, 0, out, 0, a.length)
    System.arraycopy(b, 0, out, a.length, b.length)
    out

/** A keyed digest of sensitive material. Distinct from [[Checksum]] on purpose: a plain content
  * hash of transcript text is dictionary-attackable; this one is not without the key.
  */
final case class SensitiveDigest private (keyId: KeyId, hex: String):
  def render: String = s"hmac:${keyId.value}:$hex"

object SensitiveDigest:
  def compute(
      keyId: KeyId,
      key: Array[Byte],
      material: String
  ): Either[EmbedError, SensitiveDigest] =
    if key.isEmpty then Left(EmbedError.InvalidKey("empty key"))
    else Right(SensitiveDigest(keyId, Hmac.hex(key, utf8(material))))

  private[embed] def utf8(s: String): Array[Byte] = s.getBytes("UTF-8")

  /** Cross-platform determinism vector (ADR 0001 D6): the same key and canonical rendering must
    * produce this exact digest on JVM, Scala.js and Native, because receipts cross platforms.
    * Changing the rendering format is a receipt-format version bump, not a silent edit.
    */
  object Golden:
    val keyId: KeyId = KeyId.unsafe("golden-key-v1")
    val keyBytes: Array[Byte] = utf8("storymodel4s-golden-key-2026")
    val rendering: String =
      "receipt/v1|plain:0000|space:golden|role:Query|view:Surface|material:the young man went to the river"
    val expectedHex: String = "cd865a8c0bfaf84e5cb20c582504ff4c2b7e3e13e4215de889d3d661c0c5173c"

  given Show[SensitiveDigest] = Show.show(_.render)
  given Order[SensitiveDigest] = Order.by(_.render)

/** Supplies store-local keys by id. Implementations decide storage (memory, OS keychain, file); the
  * key never appears in receipts, sidecars or logs.
  */
trait SensitiveKeyProvider:
  def currentKeyId: KeyId
  def key(id: KeyId): Option[Array[Byte]]

object SensitiveKeyProvider:
  /** A provider with no keys at all: every non-public digest fails closed with a typed error. */
  val none: SensitiveKeyProvider =
    new SensitiveKeyProvider:
      def currentKeyId: KeyId = KeyId.unsafe("none")
      def key(k: KeyId): Option[Array[Byte]] = None

  /** Test/development provider with one in-memory key. Not for production stores. */
  def static(id: KeyId, bytes: Array[Byte]): SensitiveKeyProvider =
    new SensitiveKeyProvider:
      def currentKeyId: KeyId = id
      def key(k: KeyId): Option[Array[Byte]] =
        if k == id then Some(java.util.Arrays.copyOf(bytes, bytes.length)) else None

/** Identity of the exact material sent to a provider, chosen by sensitivity. Kept as the
  * `CacheDecision` vocabulary; new code should use [[ReceiptDigest]] (PHASE 2 (after P0-1): switch
  * `CacheDecision` to `ReceiptDigest` and retire this type).
  */
enum MaterialDigest:
  case Plain(checksum: Checksum)
  case Sensitive(digest: SensitiveDigest)

  def render: String = this match
    case Plain(c)     => s"plain:${c.hex}"
    case Sensitive(d) => d.render

object MaterialDigest:
  /** Digest the rendered provider material: plain SHA-256 for non-sensitive inputs, keyed HMAC for
    * sensitive ones. The material string is the exact bytes the provider would see (text plus
    * instruction plus role marker), so a changed instruction never hits a stale cache entry.
    */
  def of(
      sensitivity: Sensitivity,
      material: String,
      keys: SensitiveKeyProvider
  ): Either[EmbedError, MaterialDigest] =
    ReceiptDigest.of(sensitivity, material, keys).map(_.toMaterial)

  given Order[MaterialDigest] = Order.by(_.render)
