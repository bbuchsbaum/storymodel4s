package storymodel4s.core

import java.nio.charset.StandardCharsets

/** Pure-Scala SHA-256.
  *
  * Why: content addressing must produce identical digests on JVM, Scala.js, and Native, so the core
  * cannot depend on `java.security`.
  */
object Sha256:
  private val K: Array[Int] = Array(
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
  )

  /** Digest of `message` as 32 bytes. */
  def digest(message: Array[Byte]): Array[Byte] =
    val h = Array(0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c,
      0x1f83d9ab, 0x5be0cd19)
    val bitLen = message.length.toLong * 8L
    val padLen = ((message.length + 8) / 64 + 1) * 64
    val padded = new Array[Byte](padLen)
    System.arraycopy(message, 0, padded, 0, message.length)
    padded(message.length) = 0x80.toByte
    var i = 0
    while i < 8 do
      padded(padLen - 1 - i) = ((bitLen >>> (8 * i)) & 0xff).toByte
      i += 1
    val w = new Array[Int](64)
    var block = 0
    while block < padLen do
      var t = 0
      while t < 16 do
        val o = block + t * 4
        w(t) = ((padded(o) & 0xff) << 24) | ((padded(o + 1) & 0xff) << 16) |
          ((padded(o + 2) & 0xff) << 8) | (padded(o + 3) & 0xff)
        t += 1
      while t < 64 do
        val s0 = Integer.rotateRight(w(t - 15), 7) ^ Integer.rotateRight(w(t - 15), 18) ^
          (w(t - 15) >>> 3)
        val s1 = Integer.rotateRight(w(t - 2), 17) ^ Integer.rotateRight(w(t - 2), 19) ^
          (w(t - 2) >>> 10)
        w(t) = w(t - 16) + s0 + w(t - 7) + s1
        t += 1
      var a = h(0); var b = h(1); var c = h(2); var d = h(3)
      var e = h(4); var f = h(5); var g = h(6); var hh = h(7)
      t = 0
      while t < 64 do
        val bigS1 =
          Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11) ^ Integer.rotateRight(e, 25)
        val ch = (e & f) ^ (~e & g)
        val t1 = hh + bigS1 + ch + K(t) + w(t)
        val bigS0 =
          Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13) ^ Integer.rotateRight(a, 22)
        val maj = (a & b) ^ (a & c) ^ (b & c)
        val t2 = bigS0 + maj
        hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
        t += 1
      h(0) += a; h(1) += b; h(2) += c; h(3) += d
      h(4) += e; h(5) += f; h(6) += g; h(7) += hh
      block += 64
    val out = new Array[Byte](32)
    i = 0
    while i < 8 do
      out(i * 4) = (h(i) >>> 24).toByte
      out(i * 4 + 1) = (h(i) >>> 16).toByte
      out(i * 4 + 2) = (h(i) >>> 8).toByte
      out(i * 4 + 3) = h(i).toByte
      i += 1
    out

  def hex(bytes: Array[Byte]): String =
    val sb = new StringBuilder(bytes.length * 2)
    bytes.foreach { b =>
      sb.append(Character.forDigit((b >>> 4) & 0xf, 16))
      sb.append(Character.forDigit(b & 0xf, 16))
    }
    sb.toString

  def hexDigest(message: Array[Byte]): String = hex(digest(message))
  def hexDigest(message: String): String =
    hexDigest(message.getBytes(StandardCharsets.UTF_8))

/** A lowercase hexadecimal SHA-256 digest. */
object Checksum:
  opaque type Checksum = String
  private val HexRe = "^[0-9a-f]{64}$".r

  def from(hex: String): Either[DomainError, Checksum] =
    if HexRe.matches(hex) then Right(hex)
    else Left(DomainError.InvalidFormat("Checksum", hex, "expected 64 lowercase hex digits"))
  def unsafe(hex: String): Checksum =
    from(hex).fold(e => throw new IllegalArgumentException(e.message), identity)
  def ofBytes(bytes: Array[Byte]): Checksum = Sha256.hexDigest(bytes)
  def ofText(text: String): Checksum = Sha256.hexDigest(text)
  extension (c: Checksum)
    def hex: String = c
    def short(n: Int = 12): String = c.take(n)
  given cats.Show[Checksum] = cats.Show.show(identity)
  given cats.Order[Checksum] = cats.Order[String]
  given cats.Hash[Checksum] = cats.Hash[String]
type Checksum = Checksum.Checksum

/** Deterministic, content-addressed identifiers of the form `<kind>:<12 hex>`.
  *
  * Why: repeated builds of the same interpretation must yield the same identifiers so that build
  * outputs are diffable and stable across machines and platforms.
  */
object ContentAddress:
  /** NUL separator so that `("a","b")` cannot collide with `("ab")`. */
  private val Sep: String = 0.toChar.toString

  def digest(parts: Iterable[String]): Checksum = Checksum.ofText(parts.mkString(Sep))

  def of(kind: String, parts: String*): String =
    s"$kind:${Checksum.ofText((kind +: parts).mkString(Sep)).short()}"

/** Helpers building the identifier kinds that the narrative layers content-address. */
object DeterministicId:
  /** Mention IDs are addressed by story, kind, and the exact span they anchor. */
  def forMention(story: StoryId, kind: String, span: TextSpan): String =
    ContentAddress.of(s"m-$kind", story.value, span.start.toString, span.endExclusive.toString)

  /** Canonical IDs are addressed by the sorted set of member mention IDs, not by a UUID. */
  def forCanonical(story: StoryId, kind: String, memberIds: Iterable[String]): String =
    ContentAddress.of(s"c-$kind", (story.value +: memberIds.toVector.sorted)*)
