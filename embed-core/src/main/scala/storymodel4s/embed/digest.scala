package storymodel4s.embed

import cats.{Order, Show}

import storymodel4s.core.{OpaqueId, Sha256}

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

/** A keyed digest of sensitive material. Distinct from [[storymodel4s.core.Checksum]] on purpose: a
  * plain content hash of transcript text is dictionary-attackable; this one is not without the key.
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

  /** Cross-platform determinism vector (ADR 0001 D6): the same key and the PRODUCTION material
    * rendering ([[ReceiptRendering.material]] for a query-role request without instruction) must
    * produce this exact digest on JVM, Scala.js and Native, because receipts cross platforms.
    * Changing the rendering format is a receipt-format version bump, not a silent edit.
    */
  object Golden:
    val keyId: KeyId = KeyId.unsafe("golden-key-v1")
    val keyBytes: Array[Byte] = utf8("storymodel4s-golden-key-2026")
    val text: String = "the young man went to the river"
    val rendering: String = ReceiptRendering.material(Role.Query, None, text)
    val expectedHex: String = "1ad599f677d0bd96b3f0da58b3737545dfffb2d11d47744dece2423ff5d2ed40"

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
