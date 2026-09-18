package storymodel4s.corpus.intake

import storymodel4s.corpus.*

/** Opens a verified snapshot under a declared profile.
  *
  * `open` takes a [[Verified]] and reads ONLY from it. That is a promise the type cannot make on
  * its own -- this object sits inside the `storymodel4s.corpus.*` tree and nothing stops it calling
  * `Files.readAllBytes` -- so it is held by a falsifier instead: a store whose bytes match no file
  * on disk, whose rows must still come out.
  */
object CorpusReader:

  /** One data row under a profile: its cells by canonical column name, and the normalized row
    * context an [[Applicability]] is evaluated against.
    */
  final class OpenRow private[intake] (
      val number: Int,
      val cells: Map[String, Raw[String]],
      val context: RowContext
  ):
    def literal(column: String): Option[String] = cells.get(column).map(_.value)
    override def toString: String = s"OpenRow($number, ${cells.size} cells)"

  /** A sheet's rows, and the cell refusals gathered while reading them.
    *
    * Cell refusals ACCUMULATE; they do not abort the sheet. ADR 0018 §8: "accumulate cell-level
    * refusals per sheet under a declared cap; fail fast on structural refusals... A 27,777-row
    * workbook must not surrender one bad cell per run." An earlier version of this reader aborted
    * the whole read on the first bad cell, which is precisely the behaviour that rule was written
    * against -- on Friends you would learn about exactly one problem per run, across 23 sheets.
    *
    * A row with a refused cell is still returned, built from the cells that DID read. The refusal
    * says which cell failed and where; discarding the row would hide the rest of it.
    */
  final class OpenSheet private[intake] (
      val artifact: ArtifactId,
      val sheet: String,
      val rows: Vector[OpenRow],
      val refusals: Vector[CellRefusal],
      /** How many refusals were FOUND, which is not how many were kept.
        *
        * `refusals` stops at `RefusalCap`. A Boolean "truncated" flag said that the number was
        * larger without saying by how much, so a sheet with 101 bad cells and one with 27,777 read
        * identically -- and the difference between those two is the difference between a typo and a
        * misdeclared column. The count is the thing a reader needs, so it is the thing stored.
        */
      val refusalsSeen: Int
  ):
    def isClean: Boolean = refusals.isEmpty
    def refusalsTruncated: Boolean = refusalsSeen > refusals.size
    override def toString: String =
      s"OpenSheet(${artifact.value}!$sheet, ${rows.size} rows, $refusalsSeen refusals" +
        (if refusalsTruncated then s" (${refusals.size} kept)" else "") + ")"

  final class OpenCorpus private[intake] (
      val verified: Verified,
      val profile: CorpusProfile,
      val sheets: Map[(ArtifactId, String), OpenSheet]
  ):
    def sheet(a: ArtifactId, s: String): Option[OpenSheet] = sheets.get((a, s))

    /** Every sheet's refusals, each tagged with the sheet it came from.
      *
      * A caller that reports refusals one sheet at a time reports only the sheets it remembered to
      * ask about, and a sheet nobody asks about is a sheet whose failures never reach the receipt.
      * That is a property of the CALLER, so it cannot be fixed in `OpenSheet`; the corpus has to be
      * able to answer for all of its sheets at once.
      */
    def refusals: Vector[((ArtifactId, String), CellRefusal)] =
      sheets.toVector
        .sortBy((k, _) => (k._1.value, k._2))
        .flatMap((k, s) => s.refusals.map(k -> _))

    /** How many refusals the whole corpus found, across every sheet, before any cap. */
    def refusalsSeen: Int = sheets.values.map(_.refusalsSeen).sum

    def allClean: Boolean = sheets.values.forall(_.isClean)
    override def toString: String =
      s"OpenCorpus(${verified.manifest.corpus.value}, ${sheets.size} sheets, " +
        s"profile ${profile.identity.short()})"

  /** How many cell refusals one sheet reports before it stops collecting them. Bounded so that a
    * systematically misdeclared column cannot turn one read into 27,777 error objects.
    */
  val RefusalCap: Int = 100

  enum OpenRefusal:
    /** The profile binds a sheet in an artifact the snapshot never verified. */
    case UnverifiedArtifact(artifact: ArtifactId)
    case Unreadable(artifact: ArtifactId, sheet: String, reason: String)

    /** The profile names a column the sheet's header row does not carry. A profile/source mismatch,
      * and a fail-fast structural error rather than a per-row outcome.
      */
    case ColumnNotInHeader(artifact: ArtifactId, sheet: String, column: String)

    /** Two header cells carry the same bound name, so which column the profile means is ambiguous.
      *
      * Resolving it silently would pick whichever the header Map happened to yield -- not even
      * deterministically -- and a whole column could be read from the wrong place. Friends sheets
      * carry a pasted legend column, so duplicated header text is not hypothetical.
      */
    case DuplicateHeader(artifact: ArtifactId, sheet: String, column: String)
    case Cell(refusal: CellRefusal)

    def message: String = this match
      case UnverifiedArtifact(a)      => s"${a.value} was not verified in this snapshot"
      case Unreadable(a, s, r)        => s"${a.value}!$s: $r"
      case ColumnNotInHeader(a, s, c) => s"${a.value}!$s has no column '$c' in its header row"
      case DuplicateHeader(a, s, c)   =>
        s"${a.value}!$s has more than one column named '$c'; which one the profile means is ambiguous"
      case Cell(r) => r.message

  def open(verified: Verified, profile: CorpusProfile): Either[OpenRefusal, OpenCorpus] =
    val opened = profile.sheets.toVector.sortBy((k, _) => (k._1.value, k._2)).map {
      case ((artifact, sheet), binding) =>
        openSheet(verified, artifact, sheet, binding).map(s => (artifact, sheet) -> s)
    }
    opened.collectFirst { case Left(r) => r } match
      case Some(refusal) => Left(refusal)
      case None          =>
        Right(
          new OpenCorpus(verified, profile, opened.collect { case Right(kv) => kv }.toMap)
        )

  private def openSheet(
      verified: Verified,
      artifact: ArtifactId,
      sheet: String,
      binding: SheetBinding
  ): Either[OpenRefusal, OpenSheet] =
    verified.artifact(artifact) match
      case None     => Left(OpenRefusal.UnverifiedArtifact(artifact))
      case Some(va) =>
        Xlsx.rows(va.toArray, artifact, sheet) match
          case Left(r)    => Left(OpenRefusal.Unreadable(artifact, sheet, r.message))
          case Right(raw) =>
            for
              header <- raw
                .find(_.number == binding.headerRow)
                .toRight(
                  OpenRefusal.Unreadable(artifact, sheet, s"no row ${binding.headerRow}")
                )
              // header letter -> declared name, for the columns the profile binds
              bound = header.cells.toVector.collect {
                case (letter, cell) if binding.columns.contains(cell.value) => cell.value -> letter
              }
              _ <- bound
                .groupBy(_._1)
                .collectFirst { case (name, xs) if xs.sizeIs > 1 => name }
                .toLeft(())
                .left
                .map(name => OpenRefusal.DuplicateHeader(artifact, sheet, name))
              letters = bound.toMap
              _ <- binding.columns.keys
                .find(!letters.contains(_))
                .toRight(())
                .swap
                .left
                .map(c => OpenRefusal.ColumnNotInHeader(artifact, sheet, c))
              indexLetters = binding.indexColumns.flatMap(letters.get)
              body = Xlsx.trimTrailing(raw.filter(_.number > binding.headerRow), indexLetters)
              read = body.map(r => openRow(r, binding, letters))
              rows = read.map(_._1)
              found = read.flatMap(_._2)
            yield new OpenSheet(
              artifact,
              sheet,
              rows,
              found.take(RefusalCap),
              found.size
            )

  /** Reads one row, returning it alongside any cell refusals rather than instead of it. */
  private def openRow(
      row: Xlsx.RawRow,
      binding: SheetBinding,
      letters: Map[String, String]
  ): (OpenRow, Vector[CellRefusal]) =
    val cells = Map.newBuilder[String, Raw[String]]
    val normalized = Map.newBuilder[String, String]
    val refusals = Vector.newBuilder[CellRefusal]
    binding.columns.foreach { (name, col) =>
      letters.get(name).foreach { letter =>
        row.cells.get(letter) match
          // a column the profile binds but this row leaves out is BLANK, not undeclared
          case None      => normalized.addOne(name -> "")
          case Some(raw) =>
            cells.addOne(name -> raw)
            Cell.normalize(raw.at, name, raw.value, Some(col.encoding)) match
              case Left(r) =>
                refusals.addOne(r)
                // the cell is unreadable, so it contributes no normalized value -- and must not
                // contribute a blank one either, or a condition would read it as present-and-empty
                ()
              case Right(Some(norm)) => normalized.addOne(name -> norm)
              case Right(None)       => normalized.addOne(name -> "")
      }
    }
    (
      new OpenRow(row.number, cells.result(), RowContext.of(normalized.result())),
      refusals.result()
    )
