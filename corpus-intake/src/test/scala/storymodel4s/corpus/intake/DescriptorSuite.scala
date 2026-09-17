package storymodel4s.corpus.intake

import java.io.ByteArrayOutputStream
import java.util.zip.{ZipEntry, ZipOutputStream}

import io.circe.Json
import storymodel4s.core.Checksum
import storymodel4s.corpus.*

import munit.FunSuite

class DescriptorSuite extends FunSuite:
  private val art = ArtifactId.unsafe("s.xlsx")

  private def wb: Array[Byte] =
    val bos = new ByteArrayOutputStream(); val zos = new ZipOutputStream(bos)
    def put(n: String, c: String): Unit =
      zos.putNextEntry(new ZipEntry(n)); zos.write(c.getBytes("UTF-8")); zos.closeEntry()
    put(
      "xl/workbook.xml",
      """<?xml version="1.0"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        | xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
        |<sheets><sheet name="N" sheetId="1" r:id="rId1"/></sheets></workbook>""".stripMargin
    )
    put(
      "xl/_rels/workbook.xml.rels",
      """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        |<Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>""".stripMargin
    )
    put(
      "xl/worksheets/sheet1.xml",
      """<?xml version="1.0"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
        |<sheetData><row r="1"><c r="A1"><v>Num</v></c><c r="B1"><v>Time</v></c></row>
        |<row r="2"><c r="A2"><v>1</v></c><c r="B2"><v>1.0</v></c></row>
        |<row r="3"><c r="A3"><v>2</v></c><c r="B3"><v>1.000439814814815</v></c></row>
        |</sheetData></worksheet>""".stripMargin
    )
    zos.close(); bos.toByteArray

  private final class MemStore(m: Map[ArtifactId, Array[Byte]]) extends ArtifactStore:
    def list: Vector[ArtifactId] = m.keys.toVector
    def bytes(id: ArtifactId): Either[IntakeRefusal, Array[Byte]] =
      m.get(id).toRight(IntakeRefusal.MissingArtifact(id))

  private def fixture =
    val bytes = wb
    val rec = ArtifactRecord(
      art,
      RelativeArtifactPath.from("d/s.xlsx").toOption.get,
      bytes.length.toLong,
      Checksum.ofBytes(bytes),
      "storyboard"
    )
    val m = SourceManifest
      .of(
        CorpusId.unsafe("synthetic"),
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
    val v = Verify
      .verify(m, new MemStore(Map(art -> bytes.clone())))
      .fold(f => fail(f.head.message), identity)
    val p = CorpusProfile
      .of(
        ProfileId.unsafe("p.v1"),
        1,
        Map(
          (art, "N") -> SheetBinding(
            1,
            Map(
              "Num" -> ColumnBinding(CellEncoding.IntegerOrWholeDecimalText),
              "Time" -> ColumnBinding(CellEncoding.ExcelSerialDays(1))
            )
          )
        )
      )
      .fold(r => fail(r.message), identity)
    val o = CorpusReader.open(v, p).fold(r => fail(r.message), identity)
    (v, p, o)

  test("capabilities are DERIVED from the reading, never declared") {
    val (_, p, o) = fixture
    val caps = Descriptor.capabilities(p, o).map(_.render)
    assert(caps.contains("stimulus-clock:excel-serial-days@1"), caps.toString)
    assert(caps.contains("stimulus-rows:N=2"), caps.toString)
    assert(caps.contains("ordinal:N.Num=2"), caps.toString)
  }

  test("no semantic capability is invented from a reading alone") {
    val (_, p, o) = fixture
    val caps = Descriptor.capabilities(p, o).map(_.render)
    // a reading cannot know which column is a thread label or which is gold
    assert(!caps.exists(_.contains("thread")), caps.toString)
    assert(!caps.exists(_.contains("gold")), caps.toString)
  }

  test("the descriptor carries identity and capabilities, and NO column value") {
    val (v, p, o) = fixture
    val j = Descriptor.json(v, p, o).noSpaces
    assert(j.contains(Descriptor.Schema))
    assert(j.contains(p.identity.hex))
    assert(j.contains("stimulus-clock:excel-serial-days@1"))
    // the workbook's only cell values are 1, 2 and two Excel serials; none may appear as content
    assert(!j.contains("1.000439814814815"), j)
  }

  test("the descriptor changes when the profile changes, because the reading changed") {
    val (v, p, o) = fixture
    val before = Descriptor.json(v, p, o).noSpaces
    val p2 = CorpusProfile
      .of(
        ProfileId.unsafe("p.v1"),
        2,
        Map(
          (art, "N") -> SheetBinding(
            1,
            Map(
              "Num" -> ColumnBinding(CellEncoding.IntegerText),
              "Time" -> ColumnBinding(CellEncoding.ExcelSerialDays(1))
            )
          )
        )
      )
      .fold(r => fail(r.message), identity)
    val o2 = CorpusReader.open(v, p2).fold(r => fail(r.message), identity)
    assertNotEquals(Descriptor.json(v, p2, o2).noSpaces, before)
  }
