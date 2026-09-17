package storymodel4s.corpus.intake

import java.io.ByteArrayOutputStream
import java.util.zip.{ZipEntry, ZipOutputStream}

import io.circe.Json
import storymodel4s.core.Checksum
import storymodel4s.corpus.*

import munit.FunSuite

class CorpusReaderSuite extends FunSuite:
  private val art = ArtifactId.unsafe("storyboard.xlsx")
  private val corpusId = CorpusId.unsafe("synthetic")

  private def wb(body: String): Array[Byte] =
    val bos = new ByteArrayOutputStream(); val zos = new ZipOutputStream(bos)
    def put(n: String, c: String): Unit =
      zos.putNextEntry(new ZipEntry(n)); zos.write(c.getBytes("UTF-8")); zos.closeEntry()
    put(
      "xl/workbook.xml",
      """<?xml version="1.0"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        | xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
        |<sheets><sheet name="Narr" sheetId="1" r:id="rId1"/></sheets></workbook>""".stripMargin
    )
    put(
      "xl/_rels/workbook.xml.rels",
      """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        |<Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>""".stripMargin
    )
    put(
      "xl/worksheets/sheet1.xml",
      s"""<?xml version="1.0"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
         |<sheetData>$body</sheetData></worksheet>""".stripMargin
    )
    zos.close(); bos.toByteArray

  private def row(n: Int, cs: (String, String)*): String =
    s"""<row r="$n">${cs.map((r, v) => s"""<c r="$r"><v>$v</v></c>""").mkString}</row>"""

  /** A header plus three events, with an index column populated past the content. */
  private val payload = wb(
    row(1, "A1" -> "EventModelNum", "B1" -> "Time", "C1" -> "RecallType") +
      row(2, "A2" -> "1", "B2" -> "1.0", "C2" -> "1") +
      row(3, "A3" -> "2", "B3" -> "1.000439814814815", "C3" -> "1.0") +
      row(4, "A4" -> "3")
  )

  private final class MemStore(m: Map[ArtifactId, Array[Byte]]) extends ArtifactStore:
    def list: Vector[ArtifactId] = m.keys.toVector
    def bytes(id: ArtifactId): Either[IntakeRefusal, Array[Byte]] =
      m.get(id).toRight(IntakeRefusal.MissingArtifact(id))

  private def verifiedOf(bytes: Array[Byte]) =
    val rec = ArtifactRecord(
      art,
      RelativeArtifactPath.from("s/storyboard.xlsx").toOption.get,
      bytes.length.toLong,
      Checksum.ofBytes(bytes),
      "storyboard"
    )
    val m = SourceManifest
      .of(
        corpusId,
        SourceManifest.Schema,
        SourceManifest.SchemaVersion,
        Vector(rec),
        Vector.empty,
        AdmissionStatus(AdmissionState.Proposed, false, Vector.empty),
        ContentPolicy(false, false, Vector.empty),
        Vector.empty,
        Map.empty[String, Json]
      )
      .fold(f => fail(f.message), identity)
    Verify
      .verify(m, new MemStore(Map(art -> bytes.clone())))
      .fold(f => fail(f.head.message), identity)

  private val binding = SheetBinding(
    headerRow = 1,
    columns = Map(
      "EventModelNum" -> ColumnBinding(CellEncoding.IntegerText, indexOnly = true),
      "Time" -> ColumnBinding(CellEncoding.ExcelSerialDays),
      "RecallType" -> ColumnBinding(CellEncoding.IntegerOrWholeDecimalText)
    )
  )
  private def profile =
    CorpusProfile
      .of(ProfileId.unsafe("synthetic.v1"), 1, Map((art, "Narr") -> binding))
      .fold(r => fail(r.message), identity)

  test("open reads a verified snapshot under a profile") {
    val oc = CorpusReader.open(verifiedOf(payload), profile).fold(r => fail(r.message), identity)
    val rows = oc.sheet(art, "Narr").get.rows
    // row 4 is index-only and trimmed away
    assertEquals(rows.map(_.number), Vector(2, 3))
    assertEquals(rows.head.literal("Time"), Some("1.0"))
  }

  /** THE FALSIFIER for "open reads only what was verified". These bytes exist in no file anywhere;
    * if open reached the filesystem it could not produce these rows.
    */
  test("open reads ONLY the verified bytes, never the filesystem") {
    val invented = wb(
      row(1, "A1" -> "EventModelNum", "B1" -> "Time", "C1" -> "RecallType") +
        row(2, "A2" -> "99", "B2" -> "1.0", "C2" -> "1")
    )
    val oc = CorpusReader.open(verifiedOf(invented), profile).fold(r => fail(r.message), identity)
    assertEquals(oc.sheet(art, "Narr").get.rows.head.literal("EventModelNum"), Some("99"))
  }

  test("the row context carries NORMALIZED values, which is what a condition compares") {
    val oc = CorpusReader.open(verifiedOf(payload), profile).fold(r => fail(r.message), identity)
    val rows = oc.sheet(art, "Narr").get.rows
    // '1' and 1.0 are one code once normalized -- the whole point of the declared encoding
    assertEquals(rows(0).context.valueOf("RecallType"), ColumnValue.Value("1"))
    assertEquals(rows(1).context.valueOf("RecallType"), ColumnValue.Value("1"))
    // and an Excel serial normalizes to seconds, not to a number near one
    assertEquals(rows(1).context.valueOf("Time"), ColumnValue.Value("38"))
  }

  test("a condition over the opened rows now agrees across both representations") {
    val oc = CorpusReader.open(verifiedOf(payload), profile).fold(r => fail(r.message), identity)
    val veridical = Applicability.WhenColumnEquals("RecallType", "1")
    oc.sheet(art, "Narr").get.rows.foreach { r =>
      assertEquals(veridical.evaluate(r.context), ConditionOutcome.Holds, r.toString)
    }
  }

  test("a profile naming a column the header lacks fails fast") {
    val bad = SheetBinding(1, Map("NoSuchColumn" -> ColumnBinding(CellEncoding.PlainText)))
    val p = CorpusProfile
      .of(ProfileId.unsafe("bad.v1"), 1, Map((art, "Narr") -> bad))
      .fold(r => fail(r.message), identity)
    CorpusReader.open(verifiedOf(payload), p) match
      case Left(CorpusReader.OpenRefusal.ColumnNotInHeader(_, _, c)) =>
        assertEquals(c, "NoSuchColumn")
      case other => fail(s"expected ColumnNotInHeader, got $other")
  }

  test("a profile binding an artifact the snapshot never verified is refused") {
    val other = ArtifactId.unsafe("never-verified.xlsx")
    val p = CorpusProfile
      .of(ProfileId.unsafe("x.v1"), 1, Map((other, "Narr") -> binding))
      .fold(r => fail(r.message), identity)
    assertEquals(
      CorpusReader.open(verifiedOf(payload), p),
      Left(CorpusReader.OpenRefusal.UnverifiedArtifact(other))
    )
  }

  test("a profile's identity changes when any encoding changes, and not otherwise") {
    val a = profile
    val same = CorpusProfile
      .of(ProfileId.unsafe("synthetic.v1"), 1, Map((art, "Narr") -> binding))
      .fold(r => fail(r.message), identity)
    assertEquals(a.identity, same.identity)
    val changed = binding.copy(columns =
      binding.columns.updated("Time", ColumnBinding(CellEncoding.MinuteDotSecond))
    )
    val b = CorpusProfile
      .of(ProfileId.unsafe("synthetic.v1"), 1, Map((art, "Narr") -> changed))
      .fold(r => fail(r.message), identity)
    assertNotEquals(a.identity, b.identity)
    // and when only indexOnly moves, because that changes which rows are read
    val trimmed = binding.copy(columns =
      binding.columns.updated("EventModelNum", ColumnBinding(CellEncoding.IntegerText, false))
    )
    val c = CorpusProfile
      .of(ProfileId.unsafe("synthetic.v1"), 1, Map((art, "Narr") -> trimmed))
      .fold(r => fail(r.message), identity)
    assertNotEquals(a.identity, c.identity)
  }

  test("a profile with no sheets, a bad header row, or a blank column name is refused") {
    assert(CorpusProfile.of(ProfileId.unsafe("p"), 1, Map.empty).isLeft)
    assert(CorpusProfile.of(ProfileId.unsafe("p"), 0, Map((art, "Narr") -> binding)).isLeft)
    assert(
      CorpusProfile
        .of(ProfileId.unsafe("p"), 1, Map((art, "Narr") -> SheetBinding(0, binding.columns)))
        .isLeft
    )
    assert(
      CorpusProfile
        .of(
          ProfileId.unsafe("p"),
          1,
          Map((art, "Narr") -> SheetBinding(1, Map(" " -> ColumnBinding(CellEncoding.PlainText))))
        )
        .isLeft
    )
  }
