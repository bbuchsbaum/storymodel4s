package storymodel4s.corpus

import storymodel4s.core.{Checksum, ContentAddress, OpaqueId}

object ProfileId extends OpaqueId("ProfileId")
type ProfileId = ProfileId.T

/** How one source column is read.
  *
  * `indexOnly` marks a column that is populated past the content -- a row counter, a seconds index
  * -- so trimming ignores it. Measured: six Friends sheets are pre-filled to `SecondsOfRecall` 3000
  * with every other column blank, and trimming on that column keeps thousands of empty rows.
  */
final case class ColumnBinding(encoding: CellEncoding, indexOnly: Boolean = false)

/** How one sheet is read: where its header is, and what each named column means. */
final case class SheetBinding(headerRow: Int, columns: Map[String, ColumnBinding]):
  def indexColumns: Set[String] =
    columns.collect { case (name, b) if b.indexOnly => name }.toSet

/** A declared, versioned reading of one corpus: source column to encoding, per sheet.
  *
  * Its identity is a checksum over its canonical rendering, on `LexiconTable`'s precedent from ADR
  * 0011, whose identity is "the canonical rendering of its entries PLUS ITS NAME". So the declared
  * `ProfileId` and version ARE part of identity -- a version bump legitimately declares a different
  * reading even when the bindings happen to match -- while the file a profile is stored in is not,
  * because a profile under the data root has no committed path. That is what lets a receipt say
  * which reading produced it.
  *
  * Every field that changes a READING is in the rendering: artifact, sheet, header row, column
  * name, encoding (including the Excel day origin), and `indexOnly`. An omitted field would let two
  * different readings share one identity and make a receipt ambiguous; a test varies each in turn
  * and requires the checksum to move.
  *
  * A profile is DATA. For a source set whose admission is still `proposed`, it lives beside the
  * bytes under the git-ignored data root, never in this repository.
  */
final class CorpusProfile private (
    val id: ProfileId,
    val version: Int,
    val sheets: Map[(ArtifactId, String), SheetBinding],
    val identity: Checksum
):
  def binding(artifact: ArtifactId, sheet: String): Option[SheetBinding] =
    sheets.get((artifact, sheet))
  def artifacts: Set[ArtifactId] = sheets.keySet.map(_._1)
  override def equals(other: Any): Boolean = other match
    case that: CorpusProfile => identity == that.identity
    case _                   => false
  override def hashCode(): Int = identity.hashCode()
  override def toString: String = s"CorpusProfile(${id.value} v$version, ${identity.short()})"

object CorpusProfile:
  /** Refuses the configuration errors that would otherwise surface as data problems. */
  private[corpus] def of(
      id: ProfileId,
      version: Int,
      sheets: Map[(ArtifactId, String), SheetBinding]
  ): Either[ProfileRefusal, CorpusProfile] =
    if version < 1 then Left(ProfileRefusal.BadVersion(version))
    else if sheets.isEmpty then Left(ProfileRefusal.NoSheets(id))
    else
      sheets.collectFirst {
        case ((a, s), b) if b.headerRow < 1   => ProfileRefusal.BadHeaderRow(a, s, b.headerRow)
        case ((a, s), b) if b.columns.isEmpty => ProfileRefusal.NoColumns(a, s)
        case ((a, s), b) if b.columns.keys.exists(_.trim.isEmpty) =>
          ProfileRefusal.BlankColumnName(a, s)
      } match
        case Some(refusal) => Left(refusal)
        case None => Right(new CorpusProfile(id, version, sheets, render(id, version, sheets)))

  /** Canonical rendering: sorted, fully qualified, and covering every field that changes a read. An
    * encoding left out here would make two different readings share one identity.
    */
  private def render(
      id: ProfileId,
      version: Int,
      sheets: Map[(ArtifactId, String), SheetBinding]
  ): Checksum =
    val parts = sheets.toVector
      .sortBy((k, _) => (k._1.value, k._2))
      .flatMap { case ((artifact, sheet), binding) =>
        Vector(artifact.value, sheet, binding.headerRow.toString) ++
          binding.columns.toVector.sortBy(_._1).flatMap { (name, b) =>
            Vector(name, b.encoding.render, b.indexOnly.toString)
          }
      }
    ContentAddress.digest(Vector("corpus-profile", id.value, version.toString) ++ parts)

/** Why a profile could not be built. A configuration error, never a datum. */
enum ProfileRefusal:
  case BadVersion(version: Int)
  case NoSheets(id: ProfileId)
  case BadHeaderRow(artifact: ArtifactId, sheet: String, row: Int)
  case NoColumns(artifact: ArtifactId, sheet: String)
  case BlankColumnName(artifact: ArtifactId, sheet: String)

  def message: String = this match
    case BadVersion(v)         => s"profile version $v is not positive"
    case NoSheets(id)          => s"profile ${id.value} binds no sheets"
    case BadHeaderRow(a, s, r) => s"${a.value}!$s header row $r is not 1-based"
    case NoColumns(a, s)       => s"${a.value}!$s binds no columns"
    case BlankColumnName(a, s) => s"${a.value}!$s binds a blank column name"
