package storymodel4s.amr.graph

import cats.{Eq, Order, Show}
import storymodel4s.core.{DomainError, OpaqueId}

/** Identifier of a graph node (the PENMAN variable in decoded graphs; canonical labels after
  * `Canonical.form`).
  */
object NodeId extends OpaqueId("NodeId")
type NodeId = NodeId.T

/** A PropBank-style frame identifier such as `want-01` or `be-located-at-91`.
  *
  * Syntactic rule (documented because it is a heuristic): a frame is a head of at least two
  * lowercase letters, optionally hyphenated with further lowercase-letter segments, followed by
  * `-NN` with exactly two digits. Heads containing digits or single letters (`f-16`, `covid-19`
  * would still pass — see below) are not frames. Tokens that *look* like frames but are not in the
  * pinned lexicon (`covid-19`) are ambiguous by construction; only a lexicon can settle them, which
  * is why `SchemaChecker` reports `UnknownFrame` as a warning and nothing downstream may depend on
  * a lookup succeeding.
  */
object FrameId:
  opaque type FrameId = String
  private val Shape = "^[a-z]{2,}(-[a-z]+)*-[0-9]{2}$".r
  def from(raw: String): Either[DomainError, FrameId] =
    if Shape.matches(raw) then Right(raw)
    else Left(DomainError.InvalidFormat("FrameId", raw, "expected lemma-NN"))
  def unsafe(raw: String): FrameId =
    from(raw).fold(e => throw new IllegalArgumentException(e.message), identity)
  def looksLikeFrame(raw: String): Boolean = Shape.matches(raw)
  extension (f: FrameId)
    def value: String = f

    /** The lemma part (`want` for `want-01`). */
    def lemma: String = f.substring(0, f.lastIndexOf('-'))

    /** The sense number (`01` for `want-01`). */
    def sense: String = f.substring(f.lastIndexOf('-') + 1)
  given Show[FrameId] = Show.show(identity)
  given Order[FrameId] = Order[String]
  given Ordering[FrameId] = Ordering.String
type FrameId = FrameId.FrameId

/** A bare lexical concept such as `boy` or `ghost`. */
object Lemma:
  opaque type Lemma = String
  def from(raw: String): Either[DomainError, Lemma] =
    if raw.isEmpty || raw.exists(c => c.isWhitespace || "()/:~\"".contains(c)) then
      Left(DomainError.InvalidFormat("Lemma", raw, "empty or contains PENMAN delimiters"))
    else Right(raw)
  def unsafe(raw: String): Lemma =
    from(raw).fold(e => throw new IllegalArgumentException(e.message), identity)
  extension (l: Lemma) def value: String = l
  given Show[Lemma] = Show.show(identity)
  given Order[Lemma] = Order[String]
type Lemma = Lemma.Lemma

/** The name of a non-core role such as `location`, `polarity`, `consist`. */
object RoleName:
  opaque type RoleName = String
  private val Shape = "^[A-Za-z][A-Za-z0-9-]*$".r
  def from(raw: String): Either[DomainError, RoleName] =
    if Shape.matches(raw) then Right(raw)
    else Left(DomainError.InvalidFormat("RoleName", raw, "expected [A-Za-z][A-Za-z0-9-]*"))
  def unsafe(raw: String): RoleName =
    from(raw).fold(e => throw new IllegalArgumentException(e.message), identity)
  extension (r: RoleName) def value: String = r
  given Show[RoleName] = Show.show(identity)
  given Order[RoleName] = Order[String]
type RoleName = RoleName.RoleName

/** A core argument index. The syntactic bound is 0–9; semantic licensing is lexicon-checked. */
object ArgIndex:
  opaque type ArgIndex = Int
  def from(i: Int): Either[DomainError, ArgIndex] =
    if i >= 0 && i <= 9 then Right(i)
    else Left(DomainError.InvalidFormat("ArgIndex", i.toString, "expected 0..9"))
  def unsafe(i: Int): ArgIndex =
    from(i).fold(e => throw new IllegalArgumentException(e.message), identity)
  extension (a: ArgIndex) def value: Int = a
  given Order[ArgIndex] = Order[Int]
  given Ordering[ArgIndex] = Ordering.Int
type ArgIndex = ArgIndex.ArgIndex

/** A positive position index for `opN` / `sntN` roles. */
object PosIndex:
  opaque type PosIndex = Int
  def from(i: Int): Either[DomainError, PosIndex] =
    if i >= 1 then Right(i)
    else Left(DomainError.InvalidFormat("PosIndex", i.toString, "expected ≥ 1"))
  def unsafe(i: Int): PosIndex =
    from(i).fold(e => throw new IllegalArgumentException(e.message), identity)
  extension (p: PosIndex) def value: Int = p
  given Order[PosIndex] = Order[Int]
type PosIndex = PosIndex.PosIndex

/** A node concept. Frames are optional: a partial chart may carry only a lemma. */
enum Concept:
  case Frame(id: FrameId)
  case Lexical(lemma: Lemma)
  case Special(name: String)

  def render: String = this match
    case Frame(id)     => id.value
    case Lexical(l)    => l.value
    case Special(name) => name

  /** The lemma-level identity shared by `want-01` and `want-02`. */
  def lemmaKey: String = this match
    case Frame(id)     => id.lemma
    case Lexical(l)    => l.value
    case Special(name) => name

object Concept:
  private val SpecialNames =
    Set("amr-unknown", "amr-choice", "amr-empty", "multi-sentence", "truth-value", "name")

  def parse(text: String): Either[DomainError, Concept] =
    if FrameId.looksLikeFrame(text) then FrameId.from(text).map(Frame(_))
    else if SpecialNames.contains(text) || text.endsWith("-entity") || text.endsWith("-quantity")
    then Right(Special(text))
    else Lemma.from(text).map(Lexical(_))

  def unsafe(text: String): Concept =
    parse(text).fold(e => throw new IllegalArgumentException(e.message), identity)

  given Eq[Concept] = Eq.fromUniversalEquals

  /** Consistent with `==`: the constructor tag participates, so `Lexical("want-01")` and
    * `Frame(want-01)` (equal renderings) are not `Order`-equal.
    */
  given Order[Concept] = Order.by {
    case Frame(id)     => (0, id.value)
    case Lexical(l)    => (1, l.value)
    case Special(name) => (2, name)
  }

/** A literal target value. */
enum AmrLiteral:
  case Text(value: String)
  case Number(raw: String)
  case Symbol(value: String)

  def render: String = this match
    case Text(v)   => "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    case Number(r) => r
    case Symbol(v) => v

  def numeric: Option[BigDecimal] = this match
    case Number(r) => scala.util.Try(BigDecimal(r)).toOption
    case _         => None

object AmrLiteral:
  /** Canonical spelling of a number literal: plain (never scientific) notation with trailing zeros
    * stripped, so `1e3`, `1000`, and `1000.0` are one literal in the graph. The PENMAN tree keeps
    * the surface spelling; the graph does not.
    */
  def canonicalNumber(raw: String): Option[String] =
    scala.util.Try(BigDecimal(raw)).toOption.map { bd =>
      val stripped = bd.bigDecimal.stripTrailingZeros
      if stripped.signum == 0 then "0" else stripped.toPlainString
    }

  /** Consistent with `==`: `Number("5")` and `Symbol("5")` render alike but differ. */
  given Order[AmrLiteral] = Order.by {
    case Text(v)   => (0, v)
    case Number(r) => (1, r)
    case Symbol(v) => (2, v)
  }

/** Semantic role of an edge, in canonical (direct) direction. */
enum Role:
  case Arg(index: ArgIndex)
  case Standard(name: RoleName)
  case Operand(index: PosIndex)
  case Sentence(index: PosIndex)
  case Extension(namespace: String, name: String)

  def render: String = this match
    case Arg(i)           => s"ARG${i.value}"
    case Standard(n)      => n.value
    case Operand(i)       => s"op${i.value}"
    case Sentence(i)      => s"snt${i.value}"
    case Extension(ns, n) => s"$ns.$n"

object Role:
  private val ArgShape = "^ARG([0-9])$".r
  private val OpShape = "^op([1-9][0-9]*)$".r
  private val SntShape = "^snt([1-9][0-9]*)$".r
  private val ExtShape = "^([A-Za-z][A-Za-z0-9_-]*)\\.([A-Za-z][A-Za-z0-9_-]*)$".r

  /** Parse a direct role spelling (no `-of`). */
  def parse(text: String): Either[DomainError, Role] = text match
    case ArgShape(i)     => ArgIndex.from(i.toInt).map(Arg(_))
    case OpShape(i)      => PosIndex.from(i.toInt).map(Operand(_))
    case SntShape(i)     => PosIndex.from(i.toInt).map(Sentence(_))
    case ExtShape(ns, n) => Right(Extension(ns, n))
    case other           => RoleName.from(other).map(Standard(_))

  def unsafe(text: String): Role =
    parse(text).fold(e => throw new IllegalArgumentException(e.message), identity)

  val polarity: Role = Standard(RoleName.unsafe("polarity"))
  def arg(i: Int): Role = Arg(ArgIndex.unsafe(i))
  def standard(name: String): Role = Standard(RoleName.unsafe(name))

  given Order[Role] = Order.by(_.render)
  given Ordering[Role] = Order[Role].toOrdering

enum Orientation:
  case Direct
  case Inverse
  def flip: Orientation = this match
    case Direct  => Inverse
    case Inverse => Direct

/** A role as written on a surface edge: base role plus `-of` orientation. */
final case class SurfaceRole(base: Role, orientation: Orientation):
  def render: String = orientation match
    case Orientation.Direct  => base.render
    case Orientation.Inverse => base.render + "-of"
  def invert: SurfaceRole = copy(orientation = orientation.flip)
  def isInverse: Boolean = orientation == Orientation.Inverse

object SurfaceRole:
  def direct(role: Role): SurfaceRole = SurfaceRole(role, Orientation.Direct)

  /** Roles whose primary spelling ends in `-of` (their inverse is `-of-of`): exactly the AMR
    * guideline roles `consist-of`, `prep-on-behalf-of`, and `prep-out-of`, pinned to Penman's AMR
    * model. Any other `X-of` — including `prep-of` and `prep-in-of` — is the inverse of `X`; the
    * spelling is ambiguous in principle (`prep-in-of` could name a multiword preposition), and the
    * pinned model resolves it as an inverse so that `parse(render(r)) == r` holds for every role.
    */
  val PrimaryOfRoles: Set[String] = Set("consist-of", "prep-on-behalf-of", "prep-out-of")

  /** True when `text` is a primary (direct) role despite ending in `-of`. */
  def isPrimaryOf(text: String): Boolean = PrimaryOfRoles.contains(text)

  /** Parse a role spelling, recognising the `-of` inverse suffix. */
  def parse(text: String): Either[DomainError, SurfaceRole] =
    if isPrimaryOf(text) then RoleName.from(text).map(n => direct(Role.Standard(n)))
    else if text.endsWith("-of") && text.length > 3 then
      Role.parse(text.dropRight(3)).map(SurfaceRole(_, Orientation.Inverse))
    else Role.parse(text).map(SurfaceRole(_, Orientation.Direct))

  def unsafe(text: String): SurfaceRole =
    parse(text).fold(e => throw new IllegalArgumentException(e.message), identity)

  given Order[SurfaceRole] = Order.by(_.render)

/** An edge target: another node or a literal. */
enum AmrValue:
  case Node(id: NodeId)
  case Literal(value: AmrLiteral)

  def render: String = this match
    case Node(id)   => id.value
    case Literal(l) => l.render

  def nodeId: Option[NodeId] = this match
    case Node(id) => Some(id)
    case _        => None

object AmrValue:
  given Order[AmrValue] = Order.by {
    case Node(id)   => (0, id.value)
    case Literal(l) => (1, l.render)
  }

/** A directed, role-labelled edge. Literal targets are never sources. */
final case class Edge(source: NodeId, role: SurfaceRole, target: AmrValue):
  /** The edge as a canonical triple: for inverse roles, endpoints are swapped. Only defined when
    * the target is a node.
    */
  def canonical: Option[(NodeId, Role, AmrValue)] = (role.orientation, target) match
    case (Orientation.Direct, t)                 => Some((source, role.base, t))
    case (Orientation.Inverse, AmrValue.Node(t)) => Some((t, role.base, AmrValue.Node(source)))
    case (Orientation.Inverse, _)                => None

  def render: String = s"${source.value} :${role.render} ${target.render}"

object Edge:
  given Order[Edge] = Order.by(e => (e.source.value, e.role.render, e.target))
  given Ordering[Edge] = Order[Edge].toOrdering

  def apply(source: String, role: String, target: String): Edge =
    Edge(NodeId.unsafe(source), SurfaceRole.unsafe(role), AmrValue.Node(NodeId.unsafe(target)))
  def lit(source: String, role: String, literal: AmrLiteral): Edge =
    Edge(NodeId.unsafe(source), SurfaceRole.unsafe(role), AmrValue.Literal(literal))

/** Phantom check state of a graph. */
sealed trait CheckState
object CheckState:
  sealed trait Unchecked extends CheckState
  sealed trait Checked extends CheckState

/** Phantom role form of a graph: as written (may contain `-of`) or canonical (direct only). */
sealed trait RoleForm
object RoleForm:
  sealed trait SurfaceRoles extends RoleForm
  sealed trait CanonicalRoles extends RoleForm
