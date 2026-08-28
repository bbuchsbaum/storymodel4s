package storymodel4s.core

import java.nio.charset.StandardCharsets

import cats.{Hash, Order, Show}
import cats.data.NonEmptyVector

/** Typed address protocol (ADR 0002 §4, D8).
  *
  * Why: views, the CLI inspector, critic findings, and validator paths all need to name a narrative
  * object; a single closed reference type cannot live in `core` without naming upper-module types.
  * `core` therefore fixes only the *protocol*: a module discriminator, a kind, and a canonical
  * escaped key. Each module defines its own typed reference plus an [[Addressable]] instance, and
  * the closed coproduct is assembled where every module is visible (`view`, `codec`).
  *
  * Wire form: `tag/kind/part₁/part₂/…` where `tag` and `kind` match `[a-z][a-z0-9-]*` and each key
  * part is percent-escaped so that identifiers containing `/`, `%`, `:` or non-ASCII text
  * round-trip exactly. The rendering is total, canonical, and contains no whitespace.
  */
object ModuleTag:
  opaque type ModuleTag = String
  private val Re = "^[a-z][a-z0-9-]{0,31}$".r

  def from(raw: String): Either[DomainError, ModuleTag] =
    if Re.matches(raw) then Right(raw)
    else Left(DomainError.InvalidFormat("ModuleTag", raw, "expected [a-z][a-z0-9-]{0,31}"))
  def unsafe(raw: String): ModuleTag =
    from(raw).fold(e => throw new IllegalArgumentException(e.message), identity)

  extension (t: ModuleTag) def value: String = t
  given Show[ModuleTag] = Show.show(identity)
  given Order[ModuleTag] = Order[String]
  given Hash[ModuleTag] = Hash[String]
type ModuleTag = ModuleTag.ModuleTag

/** The kind of object within a module (`situation`, `tokens`, …); same lexical rule as a tag. */
object AddressKind:
  opaque type AddressKind = String
  private val Re = "^[a-z][a-z0-9-]{0,31}$".r

  def from(raw: String): Either[DomainError, AddressKind] =
    if Re.matches(raw) then Right(raw)
    else Left(DomainError.InvalidFormat("AddressKind", raw, "expected [a-z][a-z0-9-]{0,31}"))
  def unsafe(raw: String): AddressKind =
    from(raw).fold(e => throw new IllegalArgumentException(e.message), identity)

  extension (k: AddressKind) def value: String = k
  given Show[AddressKind] = Show.show(identity)
  given Order[AddressKind] = Order[String]
  given Hash[AddressKind] = Hash[String]
type AddressKind = AddressKind.AddressKind

/** Percent-escaping for key parts: unreserved characters pass through, everything else (including
  * the `/` separator and `%` itself) becomes `%XX` over UTF-8 bytes.
  */
object AddressEscape:
  private def unreserved(c: Char): Boolean =
    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
      c == '.' || c == '_' || c == '-' || c == ':'

  def encode(part: String): String =
    if part.forall(unreserved) then part
    else
      val sb = new StringBuilder
      part.getBytes(StandardCharsets.UTF_8).foreach { b =>
        val c = (b & 0xff).toChar
        if b >= 0 && unreserved(c) then sb.append(c)
        else
          sb.append('%')
          sb.append(Character.toUpperCase(Character.forDigit((b >>> 4) & 0xf, 16)))
          sb.append(Character.toUpperCase(Character.forDigit(b & 0xf, 16)))
      }
      sb.toString

  def decode(encoded: String): Either[DomainError, String] =
    def bad(reason: String) = Left(DomainError.InvalidFormat("AddressKey", encoded, reason))
    if !encoded.contains('%') then
      if encoded.forall(unreserved) then Right(encoded)
      else bad("unescaped reserved character")
    else
      val out = new java.io.ByteArrayOutputStream(encoded.length)
      var i = 0
      var err: Option[String] = None
      while err.isEmpty && i < encoded.length do
        val c = encoded.charAt(i)
        if c == '%' then
          if i + 2 >= encoded.length then err = Some("truncated escape")
          else
            val hi = Character.digit(encoded.charAt(i + 1), 16)
            val lo = Character.digit(encoded.charAt(i + 2), 16)
            if hi < 0 || lo < 0 then err = Some("malformed escape")
            else
              out.write((hi << 4) | lo)
              i += 3
        else if unreserved(c) then
          out.write(c.toInt)
          i += 1
        else err = Some("unescaped reserved character")
      err match
        case Some(r) => bad(r)
        case None    => Right(new String(out.toByteArray, StandardCharsets.UTF_8))

/** A canonical, nonempty sequence of key parts. Parts may be any string (including empty); the
  * rendered form is unambiguous because parts are escaped before being joined with `/`.
  */
object AddressKey:
  opaque type AddressKey = NonEmptyVector[String]

  def of(parts: NonEmptyVector[String]): AddressKey = parts
  def of(head: String, tail: String*): AddressKey = NonEmptyVector(head, tail.toVector)
  def fromVector(parts: Vector[String]): Either[DomainError, AddressKey] =
    NonEmptyVector
      .fromVector(parts)
      .toRight(DomainError.InvalidFormat("AddressKey", "", "no parts"))

  def parse(rendered: String): Either[DomainError, AddressKey] =
    val pieces = rendered.split("/", -1).toVector
    pieces
      .foldLeft[Either[DomainError, Vector[String]]](Right(Vector.empty)) { (acc, p) =>
        for
          v <- acc
          d <- AddressEscape.decode(p)
        yield v :+ d
      }
      .flatMap(fromVector)

  extension (k: AddressKey)
    def parts: NonEmptyVector[String] = k
    def render: String = k.toVector.map(AddressEscape.encode).mkString("/")

  given Show[AddressKey] = Show.show(_.render)
  given Order[AddressKey] = Order.by(_.render)
  given Hash[AddressKey] = Hash.by(_.render)
type AddressKey = AddressKey.AddressKey

/** A universal, serializable address of a narrative object: module tag, kind, canonical key. */
final case class Address(tag: ModuleTag, kind: AddressKind, key: AddressKey):
  def render: String = s"${tag.value}/${kind.value}/${key.render}"
  override def toString: String = render

object Address:
  def parse(rendered: String): Either[DomainError, Address] =
    val i = rendered.indexOf('/')
    val j = if i < 0 then -1 else rendered.indexOf('/', i + 1)
    if i < 0 || j < 0 then
      Left(DomainError.InvalidFormat("Address", rendered, "expected tag/kind/key"))
    else
      for
        tag <- ModuleTag.from(rendered.substring(0, i))
        kind <- AddressKind.from(rendered.substring(i + 1, j))
        key <- AddressKey.parse(rendered.substring(j + 1))
      yield Address(tag, kind, key)

  given Show[Address] = Show.show(_.render)
  given Order[Address] = Order.by(_.render)
  given Ordering[Address] = Order[Address].toOrdering
  given Hash[Address] = Hash.by(_.render)

/** A module's typed reference space, embedded into and recovered from [[Address]].
  *
  * Laws (checked in `laws`): `parse(address(a)) == Some(a)`; `address(a).tag == tag`;
  * `addr.tag != tag ⇒ parse(addr) == None`; `address` is injective.
  */
trait Addressable[A]:
  def tag: ModuleTag
  def address(a: A): Address
  def parse(addr: Address): Option[A]

object Addressable:
  def apply[A](using ev: Addressable[A]): Addressable[A] = ev

  extension [A](a: A)(using ev: Addressable[A]) def address: Address = ev.address(a)

  /** Helper for instance authors: refuse foreign tags before dispatching on kind. */
  def guarded[A](tag: ModuleTag)(f: (AddressKind, AddressKey) => Option[A]): Address => Option[A] =
    addr => if addr.tag == tag then f(addr.kind, addr.key) else None

/** `core`'s own references: surface units, token ranges, spans, claims, evidence, the story. */
enum CoreRef:
  case Story(id: StoryId)
  case SurfaceUnit(id: SurfaceUnitId)
  case Tokens(range: TokenRange)
  case Spans(spans: SpanSet)
  case Claim(id: ClaimId)
  case Evidence(id: EvidenceId)

object CoreRef:
  val Tag: ModuleTag = ModuleTag.unsafe("core")

  private object Kinds:
    val story = AddressKind.unsafe("story")
    val unit = AddressKind.unsafe("surface-unit")
    val tokens = AddressKind.unsafe("tokens")
    val spans = AddressKind.unsafe("spans")
    val claim = AddressKind.unsafe("claim")
    val evidence = AddressKind.unsafe("evidence")

  private def encodeRef(r: SpanRef): String =
    val base = s"${r.span.start}-${r.span.endExclusive}"
    r.unit.fold(base)(u => s"$base@${u.value}")

  private def decodeRef(s: String): Option[SpanRef] =
    val (bounds, unit) = s.indexOf('@') match
      case -1 => (s, None)
      case i  => (s.substring(0, i), Some(s.substring(i + 1)))
    bounds.split("-", -1) match
      case Array(a, b) =>
        for
          start <- a.toIntOption
          end <- b.toIntOption
          span <- TextSpan.of(start, end).toOption
          u <- unit match
            case None    => Some(None)
            case Some(v) => SurfaceUnitId.from(v).toOption.map(Some(_))
        yield SpanRef(u, span)
      case _ => None

  given Addressable[CoreRef] with
    val tag: ModuleTag = Tag

    def address(a: CoreRef): Address = a match
      case CoreRef.Story(id)       => Address(Tag, Kinds.story, AddressKey.of(id.value))
      case CoreRef.SurfaceUnit(id) => Address(Tag, Kinds.unit, AddressKey.of(id.value))
      case CoreRef.Tokens(r)       =>
        Address(
          Tag,
          Kinds.tokens,
          AddressKey.of(r.start.value.toString, r.endExclusive.value.toString)
        )
      case CoreRef.Spans(s) =>
        Address(Tag, Kinds.spans, AddressKey.of(s.refs.map(encodeRef)))
      case CoreRef.Claim(id)    => Address(Tag, Kinds.claim, AddressKey.of(id.value))
      case CoreRef.Evidence(id) => Address(Tag, Kinds.evidence, AddressKey.of(id.value))

    def parse(addr: Address): Option[CoreRef] = Addressable.guarded[CoreRef](Tag) { (kind, key) =>
      val parts = key.parts
      def single[I](mk: String => Either[DomainError, I]): Option[I] =
        if parts.length == 1 then mk(parts.head).toOption else None
      kind match
        case Kinds.story    => single(StoryId.from).map(CoreRef.Story.apply)
        case Kinds.unit     => single(SurfaceUnitId.from).map(CoreRef.SurfaceUnit.apply)
        case Kinds.claim    => single(ClaimId.from).map(CoreRef.Claim.apply)
        case Kinds.evidence => single(EvidenceId.from).map(CoreRef.Evidence.apply)
        case Kinds.tokens   =>
          parts.toVector match
            case Vector(a, b) =>
              for
                s <- a.toIntOption
                e <- b.toIntOption
                r <- TokenRange.of(s, e).toOption
              yield CoreRef.Tokens(r)
            case _ => None
        case Kinds.spans =>
          parts.toVector
            .foldLeft[Option[Vector[SpanRef]]](Some(Vector.empty)) { (acc, p) =>
              for
                v <- acc
                r <- decodeRef(p)
              yield v :+ r
            }
            .flatMap(SpanSet.of)
            .map(CoreRef.Spans.apply)
        case _ => None
    }(addr)
