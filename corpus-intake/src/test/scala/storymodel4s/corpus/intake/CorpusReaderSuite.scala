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

  /** Two profiles that READ DIFFERENTLY must not share an identity.
    *
    * The field-completeness test varies each field in turn, which catches omissions but not
    * COLLISIONS. These are the two adversarial shapes: a separator that appears inside a value it
    * is meant to separate, in both the encoding rendering and the digest itself.
    */
  test("a Custom encoding cannot collide by moving the colon") {
    def p(enc: CellEncoding) =
      CorpusProfile
        .of(
          ProfileId.unsafe("c.v1"),
          1,
          Map((art, "Narr") -> SheetBinding(1, Map("X" -> ColumnBinding(enc))))
        )
        .fold(r => fail(r.message), identity)
    // Custom("a:b","c") and Custom("a","b:c") both render "a:b:c" if the colon is a bare separator
    val one = p(CellEncoding.Custom("a:b", "c"))
    val two = p(CellEncoding.Custom("a", "b:c"))
    assertNotEquals(one.identity, two.identity, "two different encodings share an identity")
  }

  test("a separator inside a column name cannot forge an identity") {
    val nul = 0.toChar
    def p(cols: Map[String, ColumnBinding]) =
      CorpusProfile
        .of(ProfileId.unsafe("n.v1"), 1, Map((art, "Narr") -> SheetBinding(1, cols)))
        .fold(r => fail(r.message), identity)
    // ContentAddress.digest joins parts with NUL. Each PlainText column contributes exactly
    // ["text", name, "false"], so a single column named `a\0false\0text\0b` would join to the
    // same byte sequence as the two columns `a` and `b` -- UNLESS every part is length-prefixed.
    // Forged against the current field order deliberately: a collision test that does not match
    // the real structure passes for the wrong reason, which a mutant caught here.
    val forged = p(Map(s"a${nul}false${nul}text${nul}b" -> ColumnBinding(CellEncoding.PlainText)))
    val genuine = p(
      Map(
        "a" -> ColumnBinding(CellEncoding.PlainText),
        "b" -> ColumnBinding(CellEncoding.PlainText)
      )
    )
    assertNotEquals(forged.identity, genuine.identity, "a NUL in a name forged an identity")
  }

  /** A third collision shape, probed after the first two were fixed: can a SHEET name absorb the
    * field that follows it?
    *
    * The per-sheet parts are artifact, sheet, headerRow. A sheet named `S\u00001` would, without
    * framing, join identically to sheet `S` with header row 1. Length prefixing makes every part
    * self-delimiting, so it cannot -- but the first two collisions were also "obviously fine" until
    * probed, so this one is pinned rather than reasoned about.
    */
  test("a sheet name cannot absorb the header row that follows it") {
    val nul = 0.toChar
    def p(sheet: String, header: Int) =
      CorpusProfile
        .of(
          ProfileId.unsafe("s.v1"),
          1,
          Map(
            (art, sheet) -> SheetBinding(header, Map("Z" -> ColumnBinding(CellEncoding.PlainText)))
          )
        )
        .fold(r => fail(r.message), identity)
    assertNotEquals(p("Narr", 1).identity, p(s"Narr${nul}1", 1).identity)
    // The artifact id cannot carry the separator AT ALL: `IdRules.check` refuses whitespace and
    // control characters, so that attack is closed one layer earlier, by the id type rather than by
    // the digest. Worth asserting, because a defence in a different layer is one a later change to
    // THIS layer will not notice it is relying on.
    assert(ArtifactId.from(s"a.xlsx${nul}b").isLeft)
    assert(ArtifactId.from("a.xlsx b").isLeft)
    // sheet names and column names are NOT ids and get no such check, which is exactly why the
    // digest has to frame them itself
    assert(ProfileId.from(s"p${nul}v1").isLeft)
  }

  /** The fourth collision shape, and the first that SUCCEEDED.
    *
    * The first three probes each asked whether one part could absorb the next, and length-prefixing
    * answered no every time. That made every PART self-delimiting and left the part VECTOR flat and
    * count-free -- and `encodingParts` emits one, two, or THREE elements depending on the encoding.
    * A variable-arity group in a flat vector means a column group can straddle a sheet boundary:
    * the reader of the vector cannot tell where one sheet's columns stop.
    *
    * Concretely, both profiles below rendered to `35a327fc3baa...`:
    *
    *   - ONE sheet `storyboard.xlsx!S` at header row 1, reading three columns; and
    *   - TWO sheets, `storyboard.xlsx!S` at header row 1 and `custom!ns0` at header row 7.
    *
    * These read DIFFERENT ARTIFACTS. A receipt citing that identity names neither reading, which is
    * the one job the identity exists to do. Framing parts was necessary and not sufficient; the
    * structure has to be unambiguous too, so each column and each sheet now digests to a single
    * fixed-width part of its parent.
    */
  test("a variable-arity encoding cannot straddle a sheet boundary") {
    val custom = ArtifactId.unsafe("custom")
    // the forge depends on sheet ORDER: the shared prefix only lines up when the one-sheet
    // profile's artifact sorts before `custom`, so this probe does not reuse `art`
    val aa = ArtifactId.unsafe("a.xlsx")
    def prof(sheets: Map[(ArtifactId, String), SheetBinding]) =
      CorpusProfile
        .of(ProfileId.unsafe("p.v1"), 1, sheets)
        .fold(r => fail(r.message), identity)

    val oneSheet = prof(
      Map(
        (aa, "S") -> SheetBinding(
          1,
          Map(
            "aa" -> ColumnBinding(CellEncoding.PlainText),
            "custom" -> ColumnBinding(CellEncoding.Custom("ns0", "7")),
            "zz" -> ColumnBinding(CellEncoding.PlainText)
          )
        )
      )
    )
    val twoSheets = prof(
      Map(
        (aa, "S") -> SheetBinding(1, Map("aa" -> ColumnBinding(CellEncoding.PlainText))),
        (custom, "ns0") -> SheetBinding(
          7,
          Map("zz" -> ColumnBinding(CellEncoding.Custom("false", "text")))
        )
      )
    )

    assertEquals(oneSheet.sheets.size, 1)
    assertEquals(twoSheets.sheets.size, 2)
    assertEquals(
      twoSheets.sheets.keys.map(_._1.value).toSet,
      Set("a.xlsx", "custom"),
      "the forgery is only interesting if the two profiles read different artifacts"
    )
    assertNotEquals(
      oneSheet.identity,
      twoSheets.identity,
      "one sheet's columns were read as a second sheet: the identity names neither reading"
    )
  }

  /** A receipt must answer for every sheet it opened, not for the one it happened to read.
    *
    * The Friends receipt reported `NarrComb` alone while the profile bound two sheets, so a run
    * that could not read one MoreEMs cell still printed `cell refusals : none`. The per-sheet
    * accumulator was working perfectly; nothing asked it. Both directions are pinned: the corpus
    * reports a dirty sheet the caller never named, and stays quiet when every sheet is clean.
    */
  test("a refusal on a sheet the caller never asks about still reaches the receipt") {
    val other = ArtifactId.unsafe("more-ems.xlsx")
    val v = verifiedOf(payload)
    def sh(a: ArtifactId, name: String, refusals: Vector[CellRefusal], seen: Int) =
      new CorpusReader.OpenSheet(a, name, Vector.empty, refusals, seen)
    val coord = SourceCoordinate.at(other, "MoreEMs", 9, "Ghost").toOption.get
    val bad = CellRefusal.NoDeclaredEncoding(coord, "Ghost")
    val corpus = new CorpusReader.OpenCorpus(
      v,
      profile,
      Map(
        (art, "Narr") -> sh(art, "Narr", Vector.empty, 0),
        (other, "MoreEMs") -> sh(other, "MoreEMs", Vector(bad), 1)
      )
    )
    assertEquals(corpus.allClean, false)
    assertEquals(corpus.refusalsSeen, 1)
    assertEquals(corpus.refusals.map(_._1), Vector((other, "MoreEMs")))
    val line = FriendsIntake.corpusRefusalLine(corpus)
    assert(line.contains("MoreEMs"), s"the unasked-for sheet is missing from the receipt: $line")
    assert(line.contains("more-ems.xlsx!MoreEMs=1"), line)

    val clean = new CorpusReader.OpenCorpus(
      v,
      profile,
      Map((art, "Narr") -> sh(art, "Narr", Vector.empty, 0))
    )
    assert(clean.allClean)
    assertEquals(FriendsIntake.corpusRefusalLine(clean), "none across 1 sheet(s)")
  }

  /** A capped refusal list must still say how many there were.
    *
    * `refusalsTruncated` was a Boolean, so 101 bad cells and 27,777 bad cells printed the same --
    * and that difference is the difference between a typo and a misdeclared column.
    */
  test("the refusal count survives the cap that the refusal LIST does not") {
    val one = CellRefusal.NoDeclaredEncoding(
      SourceCoordinate.at(art, "Narr", 1, "Ghost").toOption.get,
      "Ghost"
    )
    val capped = new CorpusReader.OpenSheet(
      art,
      "Narr",
      Vector.empty,
      Vector.fill(CorpusReader.RefusalCap)(one),
      27777
    )
    assertEquals(capped.refusals.size, CorpusReader.RefusalCap)
    assertEquals(capped.refusalsSeen, 27777)
    assert(capped.refusalsTruncated)
    assert(capped.toString.contains("27777"), capped.toString)
    val exact = new CorpusReader.OpenSheet(art, "Narr", Vector.empty, Vector(one), 1)
    assertEquals(exact.refusalsTruncated, false)
  }

  /** The INVARIANT, not one more instance of it.
    *
    * Four hand-built forgeries have now been tried against this digest and the fourth succeeded.
    * That is a poor record for reasoning, and the reason is that each probe pins one shape: while
    * mutation-testing the fix, merely reordering the parts within a column defeated the fourth
    * forgery without making the structure any less ambiguous. A test that a reordering satisfies is
    * testing the instance, not the property.
    *
    * So this enumerates a space built out of exactly the pieces the collisions were made from --
    * one sheet versus two, columns whose NAMES are the encoding tags (`custom`, `text`), `Custom`
    * whose namespace and label are other fields' values (`ns0`, `false`, `7`), and the arity-1,
    * arity-2 and arity-3 encodings interleaved -- and requires every distinct profile in it to have
    * a distinct identity. The property is injectivity; the forgeries were only ever evidence that
    * it did not hold.
    */
  test("distinct profiles have distinct identities across the whole collision family") {
    val a1 = ArtifactId.unsafe("a.xlsx")
    val a2 = ArtifactId.unsafe("custom")
    val encs = Vector(
      CellEncoding.PlainText,
      CellEncoding.IntegerText,
      CellEncoding.ExcelSerialDays(0),
      CellEncoding.ExcelSerialDays(1),
      CellEncoding.Custom("ns0", "7"),
      CellEncoding.Custom("false", "text"),
      CellEncoding.Custom("text", "false"),
      // a Custom whose fields are the STRUCTURAL tags, so an encoding can pose as a group opener
      CellEncoding.Custom("sheet", "1"),
      CellEncoding.Custom("column", "false"),
      CellEncoding.Custom("a.xlsx", "S")
    )
    // Names drawn from the tag vocabulary AND from the structural tags, so a name can masquerade
    // as an encoding tag or as the opener of a column or sheet group. Leaving `sheet` and `column`
    // out of this vector was what let a flat-but-tagged rendering survive this test: the tags were
    // never attacked with the one input that attacks them.
    val names = Vector("aa", "custom", "text", "zz", "false", "sheet", "column", "1", "a.xlsx")

    def cols(spec: Vector[(String, CellEncoding, Boolean)]) =
      spec.map((n, e, i) => n -> ColumnBinding(e, indexOnly = i)).toMap

    val singles =
      for
        n <- names
        e <- encs
        i <- Vector(false, true)
      yield Map((a1, "S") -> SheetBinding(1, cols(Vector((n, e, i)))))

    val triples =
      for e <- encs
      yield Map(
        (a1, "S") -> SheetBinding(
          1,
          cols(
            Vector(
              ("aa", CellEncoding.PlainText, false),
              ("custom", e, false),
              ("zz", CellEncoding.PlainText, false)
            )
          )
        )
      )

    val pairs =
      for
        e <- encs
        h <- Vector(1, 7)
      yield Map(
        (a1, "S") -> SheetBinding(1, cols(Vector(("aa", CellEncoding.PlainText, false)))),
        (a2, "ns0") -> SheetBinding(h, cols(Vector(("zz", e, false))))
      )

    val space = (singles ++ triples ++ pairs).distinct
    val profiles = space.map(s =>
      s -> CorpusProfile
        .of(ProfileId.unsafe("p.v1"), 1, s)
        .fold(r => fail(r.message), identity)
        .identity
    )
    val byDigest = profiles.groupBy(_._2).filter(_._2.sizeIs > 1)
    assertEquals(
      byDigest,
      Map.empty[Checksum, Vector[(Map[(ArtifactId, String), SheetBinding], Checksum)]],
      s"${byDigest.size} digest(s) are shared by profiles that read differently"
    )
    assertEquals(profiles.map(_._2).distinct.size, space.size)
    assert(space.sizeIs > 150, s"the family is too small to be evidence: ${space.size}")
  }

  /** A SURVIVING mutant, recorded rather than hidden.
    *
    * `render` nests -- each column digests to one part of its sheet, each sheet to one part of the
    * profile -- and it also TAGS each group with a literal `column` or `sheet`. Mutating the
    * nesting away while keeping the tags survives both the forgery above and the whole family, and
    * that is not a gap in the family. With the tags present, a group's arity is a function of its
    * tag literal (`custom` implies three parts, `excel-serial-days` two, anything else one), so the
    * flat form is parseable without ambiguity, and no input built from today's `CellEncoding` cases
    * can collide under it. The mutation is genuinely benign TODAY.
    *
    * The nesting is kept anyway, and the reason is the one thing the tags depend on: that arity can
    * be recovered from the tag. A new `CellEncoding` case whose `render` collided with a tag, or a
    * future variable-arity case, breaks that silently and returns this digest to the state the
    * fourth forgery found it in. Nesting does not depend on the property at all -- every vector
    * reaching `digest` is fixed-width by construction -- so it stays correct through a change that
    * nobody thinks to re-probe. Following `VerifySuite`'s precedent: benign is not the same as
    * intended, and an unpinned decision drifts.
    */
  test("the digest survives an encoding whose tag collides with a structural tag") {
    val a1 = ArtifactId.unsafe("a.xlsx")
    // `Custom("column", ...)` renders its tag as `custom`, but its NAMESPACE is `column` -- the
    // nearest thing the current vocabulary has to an encoding that opens a group. Under nesting it
    // cannot matter, which is the point: the assertion is about the structure, not the vocabulary.
    def p(cols: Map[String, ColumnBinding]) =
      CorpusProfile
        .of(ProfileId.unsafe("p.v1"), 1, Map((a1, "S") -> SheetBinding(1, cols)))
        .fold(r => fail(r.message), identity)
        .identity
    val x = p(Map("k" -> ColumnBinding(CellEncoding.Custom("column", "k"))))
    val y = p(
      Map(
        "k" -> ColumnBinding(CellEncoding.PlainText),
        "column" -> ColumnBinding(CellEncoding.Custom("k", "text"))
      )
    )
    assertNotEquals(x, y)
  }
