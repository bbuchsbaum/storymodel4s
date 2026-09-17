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
      "Time" -> ColumnBinding(CellEncoding.ExcelSerialDays(1)),
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

  /** ADR 0018 §8: cell refusals accumulate per sheet under a cap; structural ones fail fast.
    *
    * An earlier reader aborted the whole sheet on the first bad cell -- so on Friends, with 23
    * sheets and 27,777 content rows, you would learn about exactly one problem per run. That is the
    * behaviour the rule was written against.
    */
  test("a bad cell does not abort the sheet -- refusals ACCUMULATE") {
    val bad = wb(
      row(1, "A1" -> "EventModelNum", "B1" -> "Time", "C1" -> "RecallType") +
        row(2, "A2" -> "1", "B2" -> "1.0", "C2" -> "1") +
        row(3, "A3" -> "2", "B3" -> "not-a-serial", "C3" -> "1") +
        row(4, "A4" -> "3", "B4" -> "also-bad", "C4" -> "1") +
        row(5, "A5" -> "4", "B5" -> "1.000439814814815", "C5" -> "1")
    )
    val oc = CorpusReader.open(verifiedOf(bad), profile).fold(r => fail(r.message), identity)
    val sheet = oc.sheet(art, "Narr").get
    // every row is still returned, including the two with an unreadable cell
    assertEquals(sheet.rows.map(_.number), Vector(2, 3, 4, 5))
    // and BOTH failures are reported, not just the first
    assertEquals(sheet.refusals.size, 2)
    assert(!sheet.isClean)
    assert(!sheet.refusalsTruncated)
    // the readable columns of a bad row still read
    val badRow = sheet.rows.find(_.number == 3).get
    assertEquals(badRow.context.valueOf("RecallType"), ColumnValue.Value("1"))
    // and the unreadable cell is ABSENT, not blank -- a condition must not read it as
    // present-and-empty, which would be a claim the data does not support
    assertEquals(badRow.context.valueOf("Time"), ColumnValue.Undeclared)
  }

  test("a clean sheet reports no refusals") {
    val oc = CorpusReader.open(verifiedOf(payload), profile).fold(r => fail(r.message), identity)
    assert(oc.sheet(art, "Narr").get.isClean)
  }

  test("a STRUCTURAL problem still fails fast, rather than accumulating") {
    // a column the header lacks is a profile/source mismatch, not a datum
    val badProfile = CorpusProfile
      .of(
        ProfileId.unsafe("bad.v1"),
        1,
        Map((art, "Narr") -> SheetBinding(1, Map("Nope" -> ColumnBinding(CellEncoding.PlainText))))
      )
      .fold(r => fail(r.message), identity)
    assert(CorpusReader.open(verifiedOf(payload), badProfile).isLeft)
  }

  /** Two header cells carrying the SAME name: which column wins, and does the reader know?
    *
    * `letters` is built by collecting into a Map keyed by the column NAME, so a duplicate silently
    * collapses and the survivor is whichever the Map iteration happens to yield. Friends sheets
    * carry a pasted legend column (`Recall types`, present in 18 of 23 sheets), so duplicated
    * header text is not hypothetical.
    */
  test("a duplicated header name is REFUSED, not silently resolved to one of them") {
    val dup = wb(
      row(1, "A1" -> "EventModelNum", "B1" -> "Time", "C1" -> "Time") +
        row(2, "A2" -> "1", "B2" -> "1.0", "C2" -> "1.5") +
        row(3, "A3" -> "2", "B3" -> "1.000439814814815", "C3" -> "1.9")
    )
    val p = CorpusProfile
      .of(
        ProfileId.unsafe("dup.v1"),
        1,
        Map(
          (art, "Narr") -> SheetBinding(
            1,
            Map(
              "EventModelNum" -> ColumnBinding(CellEncoding.IntegerText),
              "Time" -> ColumnBinding(CellEncoding.ExcelSerialDays(1))
            )
          )
        )
      )
      .fold(r => fail(r.message), identity)
    CorpusReader.open(verifiedOf(dup), p) match
      case Left(CorpusReader.OpenRefusal.DuplicateHeader(_, _, name)) => assertEquals(name, "Time")
      case other => fail(s"expected DuplicateHeader, got $other")
  }

  /** The asymmetry question: a MISSPELLED column is refused, an OMITTED one is silent.
    *
    * That asymmetry is deliberate and this test pins it. A profile that names a column the header
    * lacks has made a mistake -- the two disagree about the source. A profile that simply does not
    * bind a column has made a CHOICE: reading a subset of a 21-column storyboard is the normal
    * case, not an error. Making omission an error would force every profile to enumerate columns it
    * does not use, and a profile that must list what it ignores is a profile nobody will keep
    * correct.
    */
  test("an unbound column is ignored by choice, while a misspelled one is refused") {
    // the workbook has EventModelNum, Time and RecallType; this profile binds only two
    val subset = CorpusProfile
      .of(
        ProfileId.unsafe("subset.v1"),
        1,
        Map(
          (art, "Narr") -> SheetBinding(
            1,
            Map(
              "EventModelNum" -> ColumnBinding(CellEncoding.IntegerText),
              "Time" -> ColumnBinding(CellEncoding.ExcelSerialDays(1))
            )
          )
        )
      )
      .fold(r => fail(r.message), identity)
    val oc = CorpusReader.open(verifiedOf(payload), subset).fold(r => fail(r.message), identity)
    val first = oc.sheet(art, "Narr").get.rows.head
    // the unbound column is absent from the row context, not blank -- it was never asked for
    assertEquals(first.context.valueOf("RecallType"), ColumnValue.Undeclared)
    assertEquals(first.literal("RecallType"), None)
    assertEquals(first.context.valueOf("Time"), ColumnValue.Value("0"))

    // whereas a column the header LACKS is a disagreement about the source, and refuses
    val misspelled = CorpusProfile
      .of(
        ProfileId.unsafe("typo.v1"),
        1,
        Map(
          (art, "Narr") -> SheetBinding(
            1,
            Map("EventModelNumm" -> ColumnBinding(CellEncoding.IntegerText))
          )
        )
      )
      .fold(r => fail(r.message), identity)
    assert(CorpusReader.open(verifiedOf(payload), misspelled).isLeft)
  }

  /** No field that changes a READING may be omitted from the profile's identity.
    *
    * The identity is what a receipt cites to say which reading produced it, so an omitted field
    * means two different readings share one identity and a receipt becomes ambiguous. This varies
    * every field in turn and requires the checksum to move.
    *
    * `id` and `version` ARE included, following ADR 0011's LexiconTable precedent -- identity is
    * "the canonical rendering of its entries PLUS ITS NAME". The declared name is part of identity;
    * the file a profile happens to be stored in is not, because a profile under the data root has
    * no committed path.
    */
  test("every field that changes a reading changes the identity") {
    val base = profile
    def idOf(
        pid: String = "synthetic.v1",
        v: Int = 1,
        artifact: ArtifactId = art,
        sheet: String = "Narr",
        b: SheetBinding = binding
    ) =
      CorpusProfile
        .of(ProfileId.unsafe(pid), v, Map((artifact, sheet) -> b))
        .fold(r => fail(r.message), identity)
        .identity

    assertEquals(idOf(), base.identity, "the same inputs must give the same identity")

    val varied = Map(
      "declared id" -> idOf(pid = "other.v1"),
      "version" -> idOf(v = 2),
      "artifact" -> idOf(artifact = ArtifactId.unsafe("other.xlsx")),
      "sheet name" -> idOf(sheet = "Other"),
      "header row" -> idOf(b = binding.copy(headerRow = 2)),
      "column name" -> idOf(b =
        binding.copy(columns =
          binding.columns.removed("Time") + ("Moment" -> binding.columns("Time"))
        )
      ),
      "encoding" -> idOf(b =
        binding.copy(columns =
          binding.columns.updated("Time", ColumnBinding(CellEncoding.MinuteDotSecond))
        )
      ),
      "excel day origin" -> idOf(b =
        binding.copy(columns =
          binding.columns.updated("Time", ColumnBinding(CellEncoding.ExcelSerialDays(0)))
        )
      ),
      "indexOnly" -> idOf(b =
        binding.copy(columns =
          binding.columns
            .updated("EventModelNum", ColumnBinding(CellEncoding.IntegerText, indexOnly = false))
        )
      ),
      "a dropped column" -> idOf(b = binding.copy(columns = binding.columns.removed("RecallType")))
    )
    varied.foreach { (field, id) =>
      assertNotEquals(id, base.identity, s"changing the $field did not change the identity")
    }
    // and no two variations collide with each other
    assertEquals(varied.values.toVector.distinct.size, varied.size, "two variations collided")
  }
