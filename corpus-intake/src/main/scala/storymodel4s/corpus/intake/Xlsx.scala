package storymodel4s.corpus.intake

import java.io.ByteArrayInputStream
import java.util.zip.{ZipEntry, ZipInputStream}
import javax.xml.stream.{XMLInputFactory, XMLStreamConstants, XMLStreamReader}

import scala.collection.mutable
import scala.util.control.NonFatal

import storymodel4s.corpus.*

/** Reads an `.xlsx` workbook with the JDK's own StAX parser: no new dependency (ADR 0018 §9).
  *
  * Three behaviours here are not conveniences, they are the reader's contract, and each answers a
  * hazard measured on real corpus bytes:
  *
  *   1. **The declared `<dimension>` is not trusted.** `friendsSRMStoryBoard` declares `A1:O58` and
  *      holds 14 data rows; the rest are style-only cells with no values. An intake record that
  *      believed the declaration claimed 57 events from an episode the workbook does not contain.
  *   2. **A sheet with a header and no data rows is a typed outcome, not a success.** All 57
  *      Friends event-segmentation CSVs are 63 bytes -- a BOM and a header -- and reading one as an
  *      empty success would report a participant who pressed nothing.
  *   3. **Trailing padding is trimmed on content, not on an index column.** Six Friends sheets are
  *      pre-filled down to `SecondsOfRecall` 3000 with every other column blank; trimming on the
  *      seconds column would keep thousands of empty rows.
  */
object Xlsx:

  /** One source row, keyed by the sheet's own column letters. Header mapping is a later step, and a
    * profile's job: column letters are what the file has, column names are what a profile declares.
    */
  final class RawRow private[intake] (val number: Int, val cells: Map[String, Raw[String]]):
    def literal(column: String): Option[String] = cells.get(column).map(_.value)
    def isBlank: Boolean = cells.values.forall(_.value.trim.isEmpty)
    def blankExcept(columns: Set[String]): Boolean =
      cells.forall((k, v) => columns.contains(k) || v.value.trim.isEmpty)
    override def toString: String = s"RawRow($number, ${cells.size} cells)"

  /** Why a workbook could not be read. Distinct from [[IntakeRefusal]], which is about bytes. */
  enum XlsxRefusal:
    case NotAZip
    case MissingPart(part: String)
    case MalformedXml(part: String, reason: String)
    case NoSuchSheet(sheet: String, available: Vector[String])

    /** The sheet parsed and carries a header but no data rows. */
    case NoDataRows(sheet: String)

    def message: String = this match
      case NotAZip               => "artifact is not a zip container"
      case MissingPart(p)        => s"workbook has no $p"
      case MalformedXml(p, r)    => s"$p is malformed: $r"
      case NoSuchSheet(s, avail) => s"no sheet '$s'; available: ${avail.mkString(", ")}"
      case NoDataRows(s)         => s"sheet '$s' has a header and no data rows"

  private def factory: XMLInputFactory =
    val f = XMLInputFactory.newInstance()
    // A workbook is untrusted input. Without these, a crafted sheet can read local files or
    // expand entities until the reader dies.
    f.setProperty(XMLInputFactory.SUPPORT_DTD, java.lang.Boolean.FALSE)
    f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, java.lang.Boolean.FALSE)
    f

  private def entries(bytes: Array[Byte]): Either[XlsxRefusal, Map[String, Array[Byte]]] =
    try
      val zin = new ZipInputStream(new ByteArrayInputStream(bytes))
      val out = mutable.Map.empty[String, Array[Byte]]
      var e: ZipEntry = zin.getNextEntry()
      if e == null then Left(XlsxRefusal.NotAZip)
      else
        while e != null do
          if !e.isDirectory then out.update(e.getName, zin.readAllBytes())
          zin.closeEntry()
          e = zin.getNextEntry()
        Right(out.toMap)
    catch case NonFatal(_) => Left(XlsxRefusal.NotAZip)

  private def read[A](part: String, bytes: Array[Byte])(
      f: XMLStreamReader => A
  ): Either[XlsxRefusal, A] =
    try
      val r = factory.createXMLStreamReader(new ByteArrayInputStream(bytes))
      try Right(f(r))
      finally r.close()
    catch case NonFatal(ex) => Left(XlsxRefusal.MalformedXml(part, String.valueOf(ex.getMessage)))

  private def sharedStrings(parts: Map[String, Array[Byte]]): Either[XlsxRefusal, Vector[String]] =
    parts.get("xl/sharedStrings.xml") match
      case None    => Right(Vector.empty)
      case Some(b) =>
        read("xl/sharedStrings.xml", b) { r =>
          val out = Vector.newBuilder[String]
          val sb = new StringBuilder
          var inSi = false
          while r.hasNext do
            r.next() match
              case XMLStreamConstants.START_ELEMENT =>
                r.getLocalName match
                  case "si" => inSi = true; sb.clear()
                  case _    => ()
              case XMLStreamConstants.CHARACTERS  => if inSi then sb.append(r.getText)
              case XMLStreamConstants.END_ELEMENT =>
                if r.getLocalName == "si" then
                  out += sb.toString
                  inSi = false
              case _ => ()
          out.result()
        }

  /** Sheet name to zip entry, resolved through the workbook's relationship table. */
  private def sheetParts(
      parts: Map[String, Array[Byte]]
  ): Either[XlsxRefusal, Vector[(String, String)]] =
    for
      wb <- parts.get("xl/workbook.xml").toRight(XlsxRefusal.MissingPart("xl/workbook.xml"))
      rels <- parts
        .get("xl/_rels/workbook.xml.rels")
        .toRight(XlsxRefusal.MissingPart("xl/_rels/workbook.xml.rels"))
      idToTarget <- read("xl/_rels/workbook.xml.rels", rels) { r =>
        val out = mutable.Map.empty[String, String]
        while r.hasNext do
          if r.next() == XMLStreamConstants.START_ELEMENT && r.getLocalName == "Relationship" then
            val id = Option(r.getAttributeValue(null, "Id"))
            val target = Option(r.getAttributeValue(null, "Target"))
            (id, target) match
              case (Some(i), Some(t)) => out.update(i, t)
              case _                  => ()
        out.toMap
      }
      named <- read("xl/workbook.xml", wb) { r =>
        val out = Vector.newBuilder[(String, String)]
        while r.hasNext do
          if r.next() == XMLStreamConstants.START_ELEMENT && r.getLocalName == "sheet" then
            val name = Option(r.getAttributeValue(null, "name"))
            val rid = (0 until r.getAttributeCount)
              .find(i => r.getAttributeLocalName(i) == "id")
              .map(r.getAttributeValue)
            (name, rid) match
              case (Some(n), Some(i)) => out += (n -> i)
              case _                  => ()
        out.result()
      }
    yield named.flatMap { (name, rid) =>
      idToTarget.get(rid).map { t =>
        val normalized =
          if t.startsWith("/") then t.drop(1) else if t.startsWith("xl/") then t else s"xl/$t"
        name -> normalized
      }
    }

  /** The workbook's sheet names, in workbook order. */
  def sheets(bytes: Array[Byte]): Either[XlsxRefusal, Vector[String]] =
    entries(bytes).flatMap(sheetParts).map(_.map(_._1))

  private val colOf = "^([A-Z]+)".r

  /** Every row of one sheet, with no dimension trusted and no padding trimmed.
    *
    * Returns [[XlsxRefusal.NoDataRows]] when the sheet carries a header row and nothing else, so
    * that an empty payload is a named outcome rather than an empty success.
    */
  def rows(
      bytes: Array[Byte],
      artifact: ArtifactId,
      sheet: String
  ): Either[XlsxRefusal, Vector[RawRow]] =
    for
      parts <- entries(bytes)
      named <- sheetParts(parts)
      entry <- named
        .find(_._1 == sheet)
        .map(_._2)
        .toRight(XlsxRefusal.NoSuchSheet(sheet, named.map(_._1)))
      payload <- parts.get(entry).toRight(XlsxRefusal.MissingPart(entry))
      shared <- sharedStrings(parts)
      parsed <- read(entry, payload)(r => parseSheet(r, shared, artifact, sheet))
      result <-
        if parsed.isEmpty then Left(XlsxRefusal.NoDataRows(sheet))
        else if parsed.sizeIs == 1 then Left(XlsxRefusal.NoDataRows(sheet))
        else Right(parsed)
    yield result

  private def parseSheet(
      r: XMLStreamReader,
      shared: Vector[String],
      artifact: ArtifactId,
      sheet: String
  ): Vector[RawRow] =
    val rows = Vector.newBuilder[RawRow]
    var rowNumber = 0
    var cells = Map.newBuilder[String, Raw[String]]
    var cellRef = ""
    var cellType: String | Null = null
    var inValue = false
    var inInline = false
    val text = new StringBuilder

    def emitCell(): Unit =
      if cellRef.nonEmpty then
        val raw = cellType match
          case "s" =>
            text.toString.trim.toIntOption.flatMap(shared.lift).getOrElse("")
          case _ => text.toString
        if raw.nonEmpty then
          colOf.findFirstIn(cellRef).foreach { col =>
            SourceCoordinate
              .at(artifact, sheet, rowNumber, col)
              .foreach(c => cells.addOne(col -> Raw.of(raw, c, raw)))
          }
      cellRef = ""
      cellType = null
      text.clear()

    while r.hasNext do
      r.next() match
        case XMLStreamConstants.START_ELEMENT =>
          r.getLocalName match
            case "row" =>
              rowNumber = Option(r.getAttributeValue(null, "r"))
                .flatMap(_.toIntOption)
                .getOrElse(rowNumber + 1)
              cells = Map.newBuilder[String, Raw[String]]
            case "c" =>
              cellRef = Option(r.getAttributeValue(null, "r")).getOrElse("")
              cellType = r.getAttributeValue(null, "t")
              text.clear()
            case "v"  => inValue = true
            case "is" => inInline = true
            case _    => ()
        case XMLStreamConstants.CHARACTERS =>
          if inValue || inInline then text.append(r.getText)
        case XMLStreamConstants.END_ELEMENT =>
          r.getLocalName match
            case "v"   => inValue = false
            case "is"  => inInline = false
            case "c"   => emitCell()
            case "row" =>
              val built = cells.result()
              // A row with no valued cells is a style-only row: the `friendsSRMStoryBoard` case,
              // where the declared dimension runs to row 58 and the values stop at 15.
              if built.nonEmpty then rows += new RawRow(rowNumber, built)
            case _ => ()
        case _ => ()
    rows.result()

  /** Drops trailing rows that are blank outside `indexColumns`.
    *
    * Six Friends sheets are pre-filled down to `SecondsOfRecall` 3000, so the index column is
    * populated far past the content. Trimming on it keeps thousands of empty rows; trimming on
    * everything else is what gives the measured extent.
    */
  def trimTrailing(rows: Vector[RawRow], indexColumns: Set[String]): Vector[RawRow] =
    rows.lastIndexWhere(r => !r.blankExcept(indexColumns)) match
      case -1 => Vector.empty
      case i  => rows.take(i + 1)
