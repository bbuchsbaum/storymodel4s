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
  * The comparison is against the column's **normalized** value, not its literal. Measured: Friends
  * `WhichEvent` carries 60 string cells among 20,677 numeric ones and `Detail` carries 8, so a
  * literal comparison would make a condition hold on some rows and not others for no reason in the
  * data. (`RecallType` itself is uniformly float in this copy; the hazard is real but lives in the
  * coded columns.) Normalization is the declared encoding's canonical rendering, which the profile
  * supplies -- a contract the encoding slice owes this one.
  */
enum Applicability:
  case Always
  case WhenColumnEquals(column: String, code: String)

  /** Set membership, because single-code equality is not enough for real data.
    *
    * Measured on the Friends workbook: `WhichStoryline` is present on 20,723 of 20,735
    * `RecallType == 1` rows AND 1,047 of 1,093 `RecallType == 2` rows; `Detail` under 1 and 2;
    * `False memory?` under 1, 2 and 3. With only equality a profile author must declare
    * `WhichStoryline` either `Always` -- turning 1,198 non-recall rows into `Absent` and inflating
    * the missingness denominator, the bug this type exists to prevent -- or `== "1"`, which refuses
    * 1,052 rows of real data.
    */
  case WhenColumnIn(column: String, codes: Set[String])

  /** Named `conditionColumn` rather than `column`, because two cases already have a `column` field
    * and a same-named method on the enum would shadow them.
    */
  def conditionColumn: Option[String] = this match
    case Always                 => None
    case WhenColumnEquals(c, _) => Some(c)
    case WhenColumnIn(c, _)     => Some(c)

  /** Tri-state, because "the condition column is blank" is not "the condition is false".
    *
    * Measured: 4,751 non-empty Friends rows carry no `RecallType` at all. Treating those as
    * `DoesNotHold` labels a blank `WhichEvent` `NotApplicable` -- a positive claim that RecallType
    * is something other than 1, when in fact it is absent. 165 of those rows carry a transcript,
    * storyline or notes, i.e. they are unscored recall seconds: exactly the "indistinguishable from
    * an unscored row" case this type was introduced to prevent.
    */
  def evaluate(row: RowContext): ConditionOutcome = this match
    case Always                      => ConditionOutcome.Holds
    case WhenColumnEquals(col, code) =>
      row.valueOf(col) match
        case ColumnValue.Undeclared => ConditionOutcome.Undeclared
        case ColumnValue.Blank      => ConditionOutcome.Undetermined
        case ColumnValue.Value(v)   =>
          if v == code then ConditionOutcome.Holds else ConditionOutcome.DoesNotHold
    case WhenColumnIn(col, codes) =>
      row.valueOf(col) match
        case ColumnValue.Undeclared => ConditionOutcome.Undeclared
        case ColumnValue.Blank      => ConditionOutcome.Undetermined
        case ColumnValue.Value(v)   =>
          if codes.contains(v) then ConditionOutcome.Holds else ConditionOutcome.DoesNotHold

  def render: String = this match
    case Always                      => "always"
    case WhenColumnEquals(col, code) => s"$col == $code"
    case WhenColumnIn(col, codes)    => s"$col in {${codes.toVector.sorted.mkString(", ")}}"

/** What evaluating an [[Applicability]] against a row established. */
enum ConditionOutcome:
  case Holds, DoesNotHold

  /** The condition column exists but this row leaves it blank. */
  case Undetermined

  /** The profile never supplied the condition column: a reader/profile mismatch, not a datum. */
  case Undeclared

/** One column's value on one row, as the profile's declared encoding rendered it. */
enum ColumnValue:
  case Undeclared
  case Blank
  case Value(normalized: String)

/** The normalized values of one source row, by column, as the profile's declared encodings rendered
  * them. It exists so an applicability condition is evaluated by this module against declared
  * encodings, rather than by each reader against raw literals.
  */
final class RowContext private[corpus] (private val values: Map[String, String]):
  /** Tri-state: a column the profile never supplied is not the same as one this row leaves blank.
    * The first is a configuration error and must fail fast (ADR 0018 §8); the second is a datum.
    */
  def valueOf(column: String): ColumnValue =
    values.get(column) match
      case None                      => ColumnValue.Undeclared
      case Some(v) if v.trim.isEmpty => ColumnValue.Blank
      case Some(v)                   => ColumnValue.Value(v)

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

  /** The code book this outcome was decided against. Carried on EVERY status, so a per-book
    * coverage denominator (Friends em56 versus em52) is computable from the blank cases too.
    */
  def book: CodeBookId

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
final class Absent private[corpus] (val raw: Raw[String], val book: CodeBookId)
    extends Coded[Nothing]:
  def at: SourceCoordinate = raw.at
  override def equals(other: Any): Boolean = other match
    case that: Absent => raw == that.raw && book == that.book
    case _            => false
  override def hashCode(): Int = (raw, book).hashCode()
  override def toString: String = s"Absent@${raw.at}"

/** The column does not apply on this row, by a declared condition. Distinct from missingness. */
final class NotApplicable private[corpus] (
    val raw: Raw[String],
    val condition: Applicability,
    val book: CodeBookId
) extends Coded[Nothing]:
  def at: SourceCoordinate = raw.at
  override def equals(other: Any): Boolean = other match
    case that: NotApplicable =>
      raw == that.raw && condition == that.condition && book == that.book
    case _ => false
  override def hashCode(): Int = (raw, condition, book).hashCode()
  override def toString: String = s"NotApplicable@${raw.at}(${condition.render})"

/** The condition column is blank on this row, so whether the column applies is UNKNOWN.
  *
  * Neither `Absent` (which asserts the column applied and the source was silent) nor
  * `NotApplicable` (which asserts the column did not apply). Measured: 4,751 Friends rows carry no
  * RecallType, 165 of them with a transcript, storyline or notes. Collapsing those into either
  * neighbour states something the data does not support.
  */
final class Undetermined private[corpus] (
    val raw: Raw[String],
    val condition: Applicability,
    val book: CodeBookId
) extends Coded[Nothing]:
  def at: SourceCoordinate = raw.at
  override def equals(other: Any): Boolean = other match
    case that: Undetermined =>
      raw == that.raw && condition == that.condition && book == that.book
    case _ => false
  override def hashCode(): Int = (raw, condition, book).hashCode()
  override def toString: String = s"Undetermined@${raw.at}(${condition.render})"

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

  /** The profile never supplied the condition column. A configuration error, not a datum, so it
    * fails fast rather than becoming a per-row outcome (ADR 0018 §8).
    */
  case ConditionColumnUndeclared(at: SourceCoordinate, column: String)

  /** A value is present on a row whose condition could not be evaluated at all. */
  case UndeterminedValuePresent(
      at: SourceCoordinate,
      literal: String,
      condition: Applicability
  )

  def message: String = this match
    case InapplicableValuePresent(at, literal, condition) =>
      s"$at carries '$literal' but the column applies only when ${condition.render}"
    case ConditionColumnUndeclared(at, column) =>
      s"$at: the profile declares no column '$column' to evaluate the condition against"
    case UndeterminedValuePresent(at, literal, condition) =>
      s"$at carries '$literal' but ${condition.render} could not be evaluated on this row"

object Coded:
  /** Decode one cell. The only way to obtain any [[Coded]] status.
    *
    * Takes ONE cell. An earlier signature took a decoded `Raw[K]` and a literal `Raw[String]`
    * independently and never checked they came from the same place, so a caller anywhere in the
    * corpus tree could assemble a `Known` whose joined claim spanned two cells -- the defect class
    * this carrier exists to prevent, one door inward. Now every outcome is built from `cell`, and
    * `decoded` is a plain value rather than a second provenance.
    */
  private[corpus] def decode[K, V](
      book: CodeBook[K, V],
      cell: Raw[String],
      decoded: Option[K],
      row: RowContext
  ): Either[CodeRefusal, Coded[V]] =
    book.applicability.evaluate(row) match
      case ConditionOutcome.Undeclared =>
        Left(
          CodeRefusal.ConditionColumnUndeclared(
            cell.at,
            book.applicability.conditionColumn.getOrElse("<none>")
          )
        )
      case ConditionOutcome.Undetermined =>
        decoded match
          case Some(_) =>
            Left(
              CodeRefusal.UndeterminedValuePresent(cell.at, cell.literal, book.applicability)
            )
          case None => Right(new Undetermined(cell, book.applicability, book.id))
      case ConditionOutcome.DoesNotHold =>
        decoded match
          case Some(_) =>
            Left(
              CodeRefusal.InapplicableValuePresent(cell.at, cell.literal, book.applicability)
            )
          case None => Right(new NotApplicable(cell, book.applicability, book.id))
      case ConditionOutcome.Holds =>
        decoded match
          case None       => Right(new Absent(cell, book.id))
          case Some(code) =>
            book.get(code) match
              case Some(v) => Right(new Known(Raw.of(v, cell.at, cell.literal), book.id))
              case None    => Right(new Unmapped(cell, book.id))
