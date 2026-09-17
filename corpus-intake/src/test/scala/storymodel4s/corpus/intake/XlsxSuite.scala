package storymodel4s.corpus.intake

import java.io.ByteArrayOutputStream
import java.util.zip.{ZipEntry, ZipOutputStream}

import storymodel4s.corpus.*

import munit.FunSuite

/** Every workbook here is SYNTHETIC. No corpus bytes enter Git (design contract rule 14(d)); each
  * fixture reproduces a hazard measured on real bytes without carrying any of them.
  */
class XlsxSuite extends FunSuite:
  private val art = ArtifactId.unsafe("synthetic.xlsx")

  /** Builds a minimal but real xlsx: workbook, relationships, shared strings, and sheets. */
  private def workbook(
      sheets: Vector[(String, String)],
      shared: Vector[String] = Vector.empty,
      dimension: Option[String] = None
  ): Array[Byte] =
    val bos = new ByteArrayOutputStream()
    val zos = new ZipOutputStream(bos)
    def put(name: String, content: String): Unit =
      zos.putNextEntry(new ZipEntry(name))
      zos.write(content.getBytes("UTF-8"))
      zos.closeEntry()

    val sheetTags = sheets.zipWithIndex
      .map((s, i) => s"""<sheet name="${s._1}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
      .mkString
    put(
      "xl/workbook.xml",
      s"""<?xml version="1.0"?><workbook
         | xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
         | xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
         |<sheets>$sheetTags</sheets></workbook>""".stripMargin
    )
    val relTags = sheets.zipWithIndex
      .map((_, i) => s"""<Relationship Id="rId${i + 1}" Target="worksheets/sheet${i + 1}.xml"/>""")
      .mkString
    put(
      "xl/_rels/workbook.xml.rels",
      s"""<?xml version="1.0"?><Relationships
         | xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
         |$relTags</Relationships>""".stripMargin
    )
    if shared.nonEmpty then
      val si = shared.map(s => s"<si><t>$s</t></si>").mkString
      put(
        "xl/sharedStrings.xml",
        s"""<?xml version="1.0"?><sst
           | xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
           | count="${shared.size}">$si</sst>""".stripMargin
      )
    sheets.zipWithIndex.foreach { case ((_, body), i) =>
      val dim = dimension.map(d => s"""<dimension ref="$d"/>""").getOrElse("")
      put(
        s"xl/worksheets/sheet${i + 1}.xml",
        s"""<?xml version="1.0"?><worksheet
           | xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
           |$dim<sheetData>$body</sheetData></worksheet>""".stripMargin
      )
    }
    zos.close()
    bos.toByteArray

  private def row(n: Int, cells: (String, String)*): String =
    val cs = cells.map((ref, v) => s"""<c r="$ref"><v>$v</v></c>""").mkString
    s"""<row r="$n">$cs</row>"""

  test("a workbook's sheets are listed in workbook order") {
    val wb = workbook(Vector("Alpha" -> row(1, "A1" -> "1"), "Beta" -> row(1, "A1" -> "2")))
    assertEquals(Xlsx.sheets(wb), Right(Vector("Alpha", "Beta")))
  }

  test("rows carry their number and their column letters, with a coordinate on every cell") {
    val body = row(1, "A1" -> "Episode", "B1" -> "Time") + row(2, "A2" -> "1", "B2" -> "0")
    val wb = workbook(Vector("S" -> body))
    val rows = Xlsx.rows(wb, art, "S").fold(r => fail(r.message), identity)
    assertEquals(rows.size, 2)
    assertEquals(rows(1).number, 2)
    assertEquals(rows(1).literal("A"), Some("1"))
    assertEquals(rows(1).cells("B").at.column, "B")
    assertEquals(rows(1).cells("B").at.container, "S")
  }

  test("the declared dimension is NOT trusted") {
    // measured hazard: friendsSRMStoryBoard declares A1:O58 and holds 14 data rows; an intake
    // record that believed the declaration claimed 57 events from the wrong episode.
    val body = row(1, "A1" -> "h") + row(2, "A2" -> "1") + row(3, "A3" -> "2")
    val wb = workbook(Vector("S" -> body), dimension = Some("A1:O58"))
    val rows = Xlsx.rows(wb, art, "S").fold(r => fail(r.message), identity)
    assertEquals(rows.size, 3)
    assertEquals(rows.map(_.number), Vector(1, 2, 3))
  }

  test("style-only rows with no valued cells are not rows") {
    val body = row(1, "A1" -> "h") + row(2, "A2" -> "1") + """<row r="3"><c r="A3"/></row>"""
    val wb = workbook(Vector("S" -> body))
    val rows = Xlsx.rows(wb, art, "S").fold(r => fail(r.message), identity)
    assertEquals(rows.map(_.number), Vector(1, 2))
  }

  test("a header with no data rows is a NAMED outcome, not an empty success") {
    // measured hazard: all 57 Friends event-segmentation CSVs are 63 bytes, header only.
    val wb = workbook(Vector("S" -> row(1, "A1" -> "participant", "B1" -> "session")))
    assertEquals(Xlsx.rows(wb, art, "S"), Left(Xlsx.XlsxRefusal.NoDataRows("S")))
    val empty = workbook(Vector("S" -> ""))
    assertEquals(Xlsx.rows(empty, art, "S"), Left(Xlsx.XlsxRefusal.NoDataRows("S")))
  }

  test("trailing padding is trimmed on content, not on the index column") {
    // measured hazard: six Friends sheets are pre-filled down to SecondsOfRecall 3000 with every
    // other column blank. Trimming on the index keeps thousands of empty rows.
    val body = row(1, "A1" -> "SecondsOfRecall", "B1" -> "Transcript") +
      row(2, "A2" -> "0", "B2" -> "text") +
      row(3, "A3" -> "1", "B3" -> "more") +
      row(4, "A4" -> "2") + row(5, "A5" -> "3") + row(6, "A6" -> "4")
    val wb = workbook(Vector("S" -> body))
    val rows = Xlsx.rows(wb, art, "S").fold(r => fail(r.message), identity)
    assertEquals(rows.size, 6)
    val trimmed = Xlsx.trimTrailing(rows, indexColumns = Set("A"))
    assertEquals(trimmed.map(_.number), Vector(1, 2, 3))
    // and trimming on the wrong column keeps the padding, which is the bug being prevented
    assertEquals(Xlsx.trimTrailing(rows, indexColumns = Set("B")).size, 6)
  }

  test("shared strings resolve, so a text column is not read as an index") {
    val body = row(1, "A1" -> "0") + """<row r="2"><c r="A2" t="s"><v>1</v></c></row>"""
    val wb = workbook(Vector("S" -> body), shared = Vector("Central Perk", "Monica's apartment"))
    val rows = Xlsx.rows(wb, art, "S").fold(r => fail(r.message), identity)
    assertEquals(rows(1).literal("A"), Some("Monica's apartment"))
  }

  test("an unknown sheet names what is available") {
    val wb = workbook(Vector("Alpha" -> (row(1, "A1" -> "1") + row(2, "A2" -> "2"))))
    Xlsx.rows(wb, art, "Missing") match
      case Left(Xlsx.XlsxRefusal.NoSuchSheet(s, avail)) =>
        assertEquals(s, "Missing")
        assertEquals(avail, Vector("Alpha"))
      case other => fail(s"expected NoSuchSheet, got $other")
  }

  test("a non-zip artifact is refused rather than throwing") {
    assertEquals(Xlsx.sheets("not a workbook".getBytes("UTF-8")), Left(Xlsx.XlsxRefusal.NotAZip))
  }

  test("a workbook is untrusted input: an external entity must not be resolved") {
    val evil =
      """<?xml version="1.0"?>
        |<!DOCTYPE t [<!ENTITY x SYSTEM "file:///etc/passwd">]>
        |<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
        |<sheetData><row r="1"><c r="A1"><v>&x;</v></c></row>
        |<row r="2"><c r="A2"><v>ok</v></c></row></sheetData></worksheet>""".stripMargin
    val bos = new ByteArrayOutputStream()
    val zos = new ZipOutputStream(bos)
    def put(n: String, c: String): Unit =
      zos.putNextEntry(new ZipEntry(n)); zos.write(c.getBytes("UTF-8")); zos.closeEntry()
    put(
      "xl/workbook.xml",
      """<?xml version="1.0"?><workbook
        | xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        | xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
        |<sheets><sheet name="S" sheetId="1" r:id="rId1"/></sheets></workbook>""".stripMargin
    )
    put(
      "xl/_rels/workbook.xml.rels",
      """<?xml version="1.0"?><Relationships
        | xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        |<Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>""".stripMargin
    )
    put("xl/worksheets/sheet1.xml", evil)
    zos.close()
    val result = Xlsx.rows(bos.toByteArray, art, "S")
    // Either the parser refuses the DTD outright, or it yields rows with no file content in them.
    result match
      case Left(_: Xlsx.XlsxRefusal.MalformedXml) => ()
      case Right(rows)                            =>
        assert(!rows.exists(_.cells.values.exists(_.value.contains("root:"))))
      case other => fail(s"unexpected: $other")
  }
