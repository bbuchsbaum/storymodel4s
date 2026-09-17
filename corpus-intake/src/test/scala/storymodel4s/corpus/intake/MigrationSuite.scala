package storymodel4s.corpus.intake

import java.nio.file.Files

import storymodel4s.core.Checksum
import storymodel4s.corpus.*

import munit.FunSuite

/** Synthetic only. The real Friends run is a main, not a test: its bytes live in the git-ignored
  * data root and a test needing them would be red in CI and on any machine without them.
  */
class MigrationSuite extends FunSuite:
  private val payload = "storyboard".getBytes("UTF-8")
  private val sha = Checksum.ofBytes(payload).hex

  private def v1(schema: String, extra: String = "") =
    s"""{
       |  "schema": "$schema",
       |  "schemaVersion": 1,
       |  "admissionStatus": {"state":"proposed","courtOpened":false,"blocking":["owner decision"]},
       |  "contentPolicy": {
       |    "participantRecallProsePresent": true,
       |    "stimulusTranscriptPresent": true,
       |    "externalOnlyArtifactClasses": ["participant-recall-prose"]
       |  },
       |  "nonClaims": ["admission-court-opened"],
       |  "artifacts": [
       |    {"path":"a.xlsx","byteLength":${payload.length},"sha256":"$sha","role":"storyboard"}
       |    $extra
       |  ]
       |}""".stripMargin

  test("a v1 artifacts-array manifest lifts to the thin v2 shape") {
    val m = LegacyManifest
      .liftArtifactsArray(CorpusId.unsafe("friends"), v1("storymodel4s.friends.source-manifest"))
      .fold(r => fail(r.message), identity)
    assertEquals(m.schema, SourceManifest.Schema)
    assertEquals(m.schemaVersion, 2)
    assertEquals(m.artifacts.size, 1)
    // the artifact id is its path, which is the identity every other staged record uses
    assertEquals(m.artifacts.head.id, ArtifactId.unsafe("a.xlsx"))
    assertEquals(m.admission.state, AdmissionState.Proposed)
    assertEquals(m.admission.blocking, Vector("owner decision"))
    assert(m.contentPolicy.participantProsePresent)
    // the lift records where it came from rather than erasing it
    assertEquals(
      m.extensions.get("migratedFrom").flatMap(_.asString),
      Some("storymodel4s.friends.source-manifest")
    )
    // and it carries no records, because v1 had no such concept
    assertEquals(m.records, Vector.empty)
  }

  test("a manifest with no artifacts array is refused, naming the schema that lacked it") {
    // this is the Sherlock shape: its inventory is mediaParts / boundNonVideoRecords
    val sherlockish = """{"schema":"storymodel4s.sherlock.source-manifest","mediaParts":[]}"""
    LegacyManifest.liftArtifactsArray(CorpusId.unsafe("sherlock"), sherlockish) match
      case Left(LegacyManifest.MigrationRefusal.NoArtifacts(s)) =>
        assertEquals(s, "storymodel4s.sherlock.source-manifest")
      case other => fail(s"expected NoArtifacts, got $other")
  }

  test("a malformed artifact is refused by index, not skipped") {
    val bad = v1("s", extra = ",{\"path\":\"b.xlsx\",\"byteLength\":1}")
    LegacyManifest.liftArtifactsArray(CorpusId.unsafe("c"), bad) match
      case Left(LegacyManifest.MigrationRefusal.BadArtifact(i, _)) => assertEquals(i, 1)
      case other => fail(s"expected BadArtifact(1), got $other")
  }

  test("an unsafe path in a legacy manifest is refused") {
    val escaping = v1("s").replace("\"path\":\"a.xlsx\"", "\"path\":\"../escape.xlsx\"")
    assert(LegacyManifest.liftArtifactsArray(CorpusId.unsafe("c"), escaping).isLeft)
  }

  test(
    "a FileStore lists what is on disk, which is what makes the undeclared direction checkable"
  ) {
    val dir = Files.createTempDirectory("corpus-store")
    try
      Files.write(dir.resolve("declared.xlsx"), payload)
      Files.write(dir.resolve("unaccounted.xlsx"), "extra".getBytes("UTF-8"))
      val store = FileStore.at(dir).fold(r => fail(r.message), identity)
      assertEquals(store.list.map(_.value).sorted, Vector("declared.xlsx", "unaccounted.xlsx"))
      assertEquals(
        store.bytes(ArtifactId.unsafe("declared.xlsx")).map(_.toVector),
        Right(payload.toVector)
      )
      assertEquals(
        store.bytes(ArtifactId.unsafe("absent.xlsx")),
        Left(IntakeRefusal.MissingArtifact(ArtifactId.unsafe("absent.xlsx")))
      )
    finally
      Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
  }

  test("a FileStore refuses a missing root and a path that escapes it, never throwing") {
    assertEquals(
      FileStore.at(java.nio.file.Path.of("/definitely/not/here")),
      Left(IntakeRefusal.RootUnavailable(RootIssue.Missing))
    )
    val dir = Files.createTempDirectory("corpus-escape")
    try
      val store = FileStore.at(dir).fold(r => fail(r.message), identity)
      val escaping = ArtifactId.unsafe("../../etc/passwd")
      assert(store.bytes(escaping).isLeft)
    finally Files.deleteIfExists(dir)
  }

  test("the Friends storyboard profile is well formed and declares every column's encoding") {
    val p = FriendsIntake.profile.fold(r => fail(r.message), identity)
    assertEquals(p.artifacts.size, 1)
    val b = FriendsIntake.storyboardBinding
    assertEquals(b.headerRow, 1)
    // the two time columns are Excel serials, not decimals -- the measured hazard
    assertEquals(b.columns("Time").encoding, CellEncoding.ExcelSerialDays)
    assertEquals(b.columns("TimeOrig").encoding, CellEncoding.ExcelSerialDays)
    assert(b.columns.values.forall(_.encoding != CellEncoding.Custom("", "")))
  }
