package storymodel4s.corpus

import storymodel4s.core.{DomainError, OpaqueId}

/** Identity of one artifact inside a corpus source set: a workbook, an archive, a delimited file. */
object ArtifactId extends OpaqueId("ArtifactId")
type ArtifactId = ArtifactId.T

/** Where a value physically came from, so that normalization stays auditable and reversible.
  *
  * Unforgeable by construction: a `final` **non-case** class has no `Mirror.Product`, so Scala 3
  * mints it no `fromProduct`, and a bare-private constructor suppresses the generated `apply` and
  * emits no `copy` (`docs/design/unforgeable-types.md`). It is also unobtainable outside this tree,
  * because only a reader that actually opened the cell can mint one.
  *
  * `row` is 1-based, as the source numbers it, and `column` is the source's own column name or
  * letter — never an index, which would not survive a column being inserted upstream. `container`
  * is a sheet name or archive entry, or empty for a flat file.
  */
final class SourceCoordinate private[corpus] (
    val artifact: ArtifactId,
    val container: String,
    val row: Int,
    val column: String
):
  override def equals(other: Any): Boolean = other match
    case that: SourceCoordinate =>
      artifact == that.artifact && container == that.container && row == that.row &&
      column == that.column
    case _ => false

  override def hashCode(): Int = (artifact, container, row, column).hashCode()

  /** Deliberately renders the address and never a value: a coordinate is a location, and a
    * `toString` that carried content would put source text into logs and receipts.
    */
  override def toString: String =
    val where = if container.isEmpty then "" else s"!$container"
    s"${artifact.value}$where:$row:$column"

object SourceCoordinate:
  /** Refuses a non-positive row: sources number from one, and a zero row means the caller lost the
    * header offset rather than that the cell sits before the first line.
    */
  private[corpus] def at(
      artifact: ArtifactId,
      container: String,
      row: Int,
      column: String
  ): Either[DomainError, SourceCoordinate] =
    if row < 1 then
      Left(DomainError.InvariantViolation("corpus/coordinate/row", s"row $row is not 1-based"))
    else if column.isEmpty then
      Left(DomainError.InvariantViolation("corpus/coordinate/column", "column name is empty"))
    else Right(new SourceCoordinate(artifact, container, row, column))

  given Ordering[SourceCoordinate] =
    Ordering.by(c => (c.artifact.value, c.container, c.row, c.column))

/** A canonical value that still knows where it came from and what the source literally said.
  *
  * The promise is the **joined** relation `value ↔ literal ↔ coordinate`: that this value is what
  * this literal decoded to, at this place. An earlier design made `Raw` a public case class on the
  * reasoning that `SourceCoordinate` was the protected payload; that was wrong, because every `Raw`
  * a reader emits hands out its coordinate, so `Raw(fabricated, genuine.at, "anything")` is one
  * line once any reader has run. The relation is a joined claim, so the carrier is closed too.
  */
final class Raw[+A] private[corpus] (val value: A, val at: SourceCoordinate, val literal: String):
  override def equals(other: Any): Boolean = other match
    case that: Raw[?] => value == that.value && at == that.at && literal == that.literal
    case _            => false

  override def hashCode(): Int = (value, at, literal).hashCode()

  /** Renders the address and the type of claim, never the literal or the value. */
  override def toString: String = s"Raw@$at"

object Raw:
  private[corpus] def of[A](value: A, at: SourceCoordinate, literal: String): Raw[A] =
    new Raw(value, at, literal)
