package storymodel4s.corpus

import storymodel4s.core.{DomainError, OpaqueId}

/** Identity of a published code book: the scoring legend an annotation was coded against. */
object CodeBookId extends OpaqueId("CodeBookId")
type CodeBookId = CodeBookId.T

/** Where a code book is published, so a decoded value can be traced to the definition it used. */
final case class Citation(source: String, locator: String):
  override def toString: String = s"$source ($locator)"

/** When a column carries a value at all.
  *
  * Friends' `WhichEvent` is populated **if and only if** `RecallType == 1`, so its event gold is a
  * veridical-recall gold and its blanks are not missingness. Without this type that arrives as
  * `None` and is indistinguishable from an unscored row, which would silently inflate every
  * coverage denominator computed over it.
  *
  * The comparison is against the column's **normalized** value, not its literal: the same code
  * arrives as `'1'` and `1.0` in one Friends column, so comparing literals would make the condition
  * hold on some rows and not others for no reason in the data. Normalization is the declared
  * encoding's canonical rendering, which the profile supplies.
  */
enum Applicability:
  case Always
  case WhenColumnEquals(column: String, code: String)

  def holdsIn(row: RowContext): Boolean = this match
    case Always                      => true
    case WhenColumnEquals(col, code) => row.normalized(col).contains(code)

  def render: String = this match
    case Always                      => "always"
    case WhenColumnEquals(col, code) => s"$col == $code"

/** The normalized values of one source row, by column, as the profile's declared encodings rendered
  * them. It exists so an applicability condition is evaluated by this module against declared
  * encodings, rather than by each reader against raw literals.
  */
final class RowContext private[corpus] (private val values: Map[String, String]):
  def normalized(column: String): Option[String] = values.get(column)
  def columns: Set[String] = values.keySet
  override def toString: String = s"RowContext(${values.size} columns)"

object RowContext:
  private[corpus] def of(values: Map[String, String]): RowContext = new RowContext(values)

/** A published mapping from a decoded code to the meaning its legend gives it.
  *
  * Keyed by the **decoded** code, never by the raw literal: keying by literal would re-import the
  * `'1'` versus `1.0` split that the declared-encoding step exists to remove, and would make one
  * code two entries.
  */
final class CodeBook[K, V] private[corpus] (
    val id: CodeBookId,
    val citation: Citation,
    val entries: Map[K, V],
    val applicability: Applicability
):
  def get(code: K): Option[V] = entries.get(code)
  def size: Int = entries.size
  override def toString: String = s"CodeBook(${id.value}, ${entries.size} entries)"

object CodeBook:
  /** Refuses an empty book: a code book with no entries maps nothing and would silently turn every
    * value into `Unmapped`, which reads as a source problem rather than a configuration one.
    */
  private[corpus] def of[K, V](
      id: CodeBookId,
      citation: Citation,
      entries: Map[K, V],
      applicability: Applicability
  ): Either[DomainError, CodeBook[K, V]] =
    if entries.isEmpty then
      Left(DomainError.InvariantViolation("corpus/codebook", s"${id.value} has no entries"))
    else Right(new CodeBook(id, citation, entries, applicability))

/** The outcome of decoding one cell against a code book.
  *
  * **Deliberately not an `enum`.** Scala 3 gives every enum case a case class, so `enum Coded`
  * would make `Known(someRaw)` constructible by any consumer holding a `Raw` — fabricating a decode
  * that never happened: genuine coordinate, invented interpretation status. The status *is* a claim
  * — that this literal decoded to this value under this code book — and it is exactly the claim a
  * forged carrier would lie about. An earlier revision of ADR 0018 shipped the enum on a reviewer's
  * judgement that a forgeable status was harmless because it "carries no relation beyond `Raw`'s";
  * a later probe disproved that, which is why these are final non-case classes.
  */
sealed trait Coded[+A]:
  def at: SourceCoordinate

/** The literal decoded, and the code book had a meaning for it. */
final class Known[+A] private[corpus] (val raw: Raw[A], val book: CodeBookId) extends Coded[A]:
  def at: SourceCoordinate = raw.at
  override def equals(other: Any): Boolean = other match
    case that: Known[?] => raw == that.raw && book == that.book
    case _              => false
  override def hashCode(): Int = (raw, book).hashCode()
  override def toString: String = s"Known@${raw.at}"

/** The cell carried a value the code book does not define. Never silently collapsed to absence. */
final class Unmapped private[corpus] (val raw: Raw[String], val book: CodeBookId)
    extends Coded[Nothing]:
  def at: SourceCoordinate = raw.at
  override def equals(other: Any): Boolean = other match
    case that: Unmapped => raw == that.raw && book == that.book
    case _              => false
  override def hashCode(): Int = (raw, book).hashCode()
  override def toString: String = s"Unmapped@${raw.at}"

/** The column applies on this row and the source wrote nothing. Genuine missingness.
  *
  * Distinct from [[Unmapped]], which is "the source wrote something this code book does not
  * define". Collapsing the two would conflate a silent source with an unrecognised one, which is
  * the conflation this whole type exists to prevent: a coverage denominator computed over them
  * would be wrong in opposite directions depending on which way the collapse went.
  */
final class Absent private[corpus] (val raw: Raw[String]) extends Coded[Nothing]:
  def at: SourceCoordinate = raw.at
  override def equals(other: Any): Boolean = other match
    case that: Absent => raw == that.raw
    case _            => false
  override def hashCode(): Int = raw.hashCode()
  override def toString: String = s"Absent@${raw.at}"

/** The column does not apply on this row, by a declared condition. Distinct from missingness. */
final class NotApplicable private[corpus] (val raw: Raw[String], val condition: Applicability)
    extends Coded[Nothing]:
  def at: SourceCoordinate = raw.at
  override def equals(other: Any): Boolean = other match
    case that: NotApplicable => raw == that.raw && condition == that.condition
    case _                   => false
  override def hashCode(): Int = (raw, condition).hashCode()
  override def toString: String = s"NotApplicable@${raw.at}(${condition.render})"

/** Why a cell could not be decoded even as `Unmapped` or `NotApplicable`. */
enum CodeRefusal:
  /** A value is present where the code book's own condition says the column does not apply.
    *
    * Without this case an "if and only if" is only an "if": Friends' `WhichEvent` would be allowed
    * to carry an event on a `RecallType == 4` row, and the gold's own definition would go unchecked
    * against the data it describes. Measured: exactly two such rows exist in the Friends workbook
    * (s2, `SecondsOfRecall` 384-385), so this refusal fires on real data and is not hypothetical.
    */
  case InapplicableValuePresent(at: SourceCoordinate, literal: String, condition: Applicability)

  def message: String = this match
    case InapplicableValuePresent(at, literal, condition) =>
      s"$at carries '$literal' but the column applies only when ${condition.render}"

object Coded:
  /** Decode one cell. The only way to obtain a `Known`, `Unmapped` or `NotApplicable`.
    *
    * `decoded` is the cell's value under the profile's declared encoding, or `None` when the cell
    * is blank. `literal` is what the source wrote, carried on every outcome so that any decision
    * this function makes can be traced back to the bytes that caused it.
    */
  private[corpus] def decode[K, V](
      book: CodeBook[K, V],
      decoded: Option[Raw[K]],
      literal: Raw[String],
      row: RowContext
  ): Either[CodeRefusal, Coded[V]] =
    val applies = book.applicability.holdsIn(row)
    (applies, decoded) match
      case (true, Some(code)) =>
        book.get(code.value) match
          case Some(v) => Right(new Known(Raw.of(v, code.at, code.literal), book.id))
          case None    => Right(new Unmapped(literal, book.id))
      case (true, None) =>
        Right(new Absent(literal))
      case (false, None) =>
        Right(new NotApplicable(literal, book.applicability))
      case (false, Some(code)) =>
        Left(
          CodeRefusal.InapplicableValuePresent(code.at, code.literal, book.applicability)
        )
