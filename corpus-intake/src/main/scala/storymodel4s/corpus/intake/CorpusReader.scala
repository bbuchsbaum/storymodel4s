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

  final class OpenSheet private[intake] (
      val artifact: ArtifactId,
      val sheet: String,
      val rows: Vector[OpenRow]
  ):
    override def toString: String = s"OpenSheet(${artifact.value}!$sheet, ${rows.size} rows)"

  final class OpenCorpus private[intake] (
      val verified: Verified,
      val profile: CorpusProfile,
      val sheets: Map[(ArtifactId, String), OpenSheet]
  ):
    def sheet(a: ArtifactId, s: String): Option[OpenSheet] = sheets.get((a, s))
    override def toString: String =
      s"OpenCorpus(${verified.manifest.corpus.value}, ${sheets.size} sheets, " +
        s"profile ${profile.identity.short()})"

  enum OpenRefusal:
    /** The profile binds a sheet in an artifact the snapshot never verified. */
    case UnverifiedArtifact(artifact: ArtifactId)
    case Unreadable(artifact: ArtifactId, sheet: String, reason: String)

    /** The profile names a column the sheet's header row does not carry. A profile/source mismatch,
      * and a fail-fast structural error rather than a per-row outcome.
      */
    case ColumnNotInHeader(artifact: ArtifactId, sheet: String, column: String)
    case Cell(refusal: CellRefusal)

    def message: String = this match
      case UnverifiedArtifact(a)      => s"${a.value} was not verified in this snapshot"
      case Unreadable(a, s, r)        => s"${a.value}!$s: $r"
      case ColumnNotInHeader(a, s, c) => s"${a.value}!$s has no column '$c' in its header row"
      case Cell(r)                    => r.message

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
              letters = header.cells.collect {
                case (letter, cell) if binding.columns.contains(cell.value) => cell.value -> letter
              }
              _ <- binding.columns.keys
                .find(!letters.contains(_))
                .toRight(())
                .swap
                .left
                .map(c => OpenRefusal.ColumnNotInHeader(artifact, sheet, c))
              indexLetters = binding.indexColumns.flatMap(letters.get)
              body = Xlsx.trimTrailing(raw.filter(_.number > binding.headerRow), indexLetters)
              rows <- body.foldLeft[Either[OpenRefusal, Vector[OpenRow]]](Right(Vector.empty)) {
                (acc, r) => acc.flatMap(done => openRow(r, binding, letters).map(done :+ _))
              }
            yield new OpenSheet(artifact, sheet, rows)

  private def openRow(
      row: Xlsx.RawRow,
      binding: SheetBinding,
      letters: Map[String, String]
  ): Either[OpenRefusal, OpenRow] =
    val cells = Map.newBuilder[String, Raw[String]]
    val normalized = Map.newBuilder[String, String]
    var refusal: Option[OpenRefusal] = None
    binding.columns.foreach { (name, col) =>
      if refusal.isEmpty then
        letters.get(name).foreach { letter =>
          row.cells.get(letter) match
            case None      => ()
            case Some(raw) =>
              cells.addOne(name -> raw)
              Cell.normalize(raw.at, name, raw.value, Some(col.encoding)) match
                case Left(r)           => refusal = Some(OpenRefusal.Cell(r))
                case Right(Some(norm)) => normalized.addOne(name -> norm)
                case Right(None)       => normalized.addOne(name -> "")
        }
        // a column the profile binds but this row leaves out is BLANK, not undeclared
        if letters.contains(name) && !row.cells.contains(letters(name)) then
          normalized.addOne(name -> "")
    }
    refusal.toLeft(new OpenRow(row.number, cells.result(), RowContext.of(normalized.result())))
