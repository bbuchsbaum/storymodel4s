package storymodel4s.corpus.intake

import java.nio.file.{Files, Path}
import io.circe.{ACursor, Json}
import io.circe.parser.parse
import munit.FunSuite
import storymodel4s.core.*

class TimebaseRepairSuite extends FunSuite:
  private def path: Path =
    val local = Path.of("docs/data/sherlock/timebase-repair.json")
    if Files.exists(local) then local else Path.of("../docs/data/sherlock/timebase-repair.json")
  private def source: String = Files.readString(path)
  private def record: TimebaseRepair.Record =
    TimebaseRepair.parse(source).fold(e => fail(e.message), identity)
  private def bundles(rec: TimebaseRepair.Record): Map[String, SourceBundle] =
    Vector(rec.manifest.partA, rec.manifest.partB).map { p =>
      p.partId -> SherlockAnnotations.partBundle(p).fold(e => fail(e.message), identity)
    }.toMap
  private def cursor(json: Json, path: String): ACursor =
    path.split('/').foldLeft[ACursor](json.hcursor) { (c, key) =>
      key.toIntOption.fold(c.downField(key))(c.downN)
    }
  private def changed(path: String, value: Json): String =
    val original = parse(source).toOption.get
    val at = cursor(original, path)
    assert(at.focus.nonEmpty, s"mutation path exists: $path")
    assertNotEquals(at.focus.get, value, s"mutation changes $path")
    val result = at.withFocus(_ => value).top.get
    assertEquals(cursor(result, path).focus, Some(value), s"mutation applied at $path")
    result.noSpaces

  test("the committed record builds two checked actual-axis repairs with complete receipts") {
    val rec = record
    val actual = bundles(rec)
    val repairs = TimebaseRepair.clockRepairs(rec, actual).fold(e => fail(e.message), identity)
    assertEquals(rec.inputRows, 1000)
    assertEquals(rec.run1EndRow, Some(482))
    assertEquals(rec.manifest.partA.durationTicks, 3565500L)
    assertEquals(rec.manifest.partB.durationTicks, 3887000L)
    assertEquals(rec.runs.map(_.uncoveredTailTicks), Vector(500L, 27000L))
    assertEquals(rec.checksum, Checksum.ofBytes(Files.readAllBytes(path)))
    assertEquals(rec.notebookProvenanceStatus, "declared-unverified")
    assertNotEquals(repairs.head.relation.sourceAxis, repairs(1).relation.sourceAxis)
    assertNotEquals(repairs.head.relation.targetAxis, repairs(1).relation.targetAxis)
    repairs.zip(rec.runs).foreach { (repair, run) =>
      assertEquals(repair.relation.targetAxis, actual(run.partId).primaryAxis.id)
      assertNotEquals(repair.relation.targetAxis.value, run.axisId)
      assertEquals(repair.receipt.algorithm, rec.crosswalkId)
      assert(repair.receipt.parameters.contains(run.formula))
      assert(repair.receipt.parameters.contains("declared-unverified"))
      assertEquals(repair.receipt.inputChecksums.head, rec.checksum)
      assertEquals(repair.offset, ExactRational.Zero)
      assertEquals(
        ClockRepair
          .projectRunLocalSeconds(
            ExactRational.integer(10),
            run.sourceAxis,
            actual(run.partId).primaryAxis.id,
            repair
          )
          .toOption,
        Some(ExactRational.integer(25000))
      )
    }
  }

  // Each path is mutated structurally, and the helper proves the mutation actually applied.
  test("malformed numeric pairs and duplicate JSON keys refuse before interpretation") {
    for (path, value) <- Vector(
        "annotationToPlaybackCrosswalk/runs/0/annotationRows" -> "1-junk-482",
        "annotationToPlaybackCrosswalk/runs/0/annotationRows" -> "1-482-",
        "presentationEditionIdentity/parts/0/video/frameRate" -> "25/junk/1",
        "presentationEditionIdentity/parts/0/video/frameRate" -> "25/1/"
      )
    do assert(TimebaseRepair.parse(changed(path, Json.fromString(value))).isLeft, value)
    val root = parse(source).toOption.get.noSpaces
    val marker = "\"formula\":\"" + TimebaseRepair.ImplementedFormula + "\""
    assert(root.contains(marker))
    val duplicate = root.replace(marker, "\"formula\":\"unsupported\"," + marker)
    assertNotEquals(duplicate, root)
    assert(TimebaseRepair.parse(root).isRight)
    assert(TimebaseRepair.parse(duplicate).isLeft)
  }

  private val invalidStrings = Vector(
    "schema" -> "foreign",
    "presentationEditionIdentity/authority" -> "frame-exact-certification",
    "presentationEditionIdentity/parts/0/primaryStream" -> "audio",
    "presentationEditionIdentity/parts/0/video/timeBase" -> "1/1000",
    "presentationEditionIdentity/parts/0/video/frameRate" -> "24/1",
    "annotationToPlaybackCrosswalk/editionReceipt" -> "foreign-edition",
    "annotationToPlaybackCrosswalk/status" -> "proposed",
    "annotationToPlaybackCrosswalk/certifies" -> "narrative-semantics",
    "annotationToPlaybackCrosswalk/doesNotCertify" -> "nothing",
    "annotationToPlaybackCrosswalk/explicitlyNotUsed" -> "annotation-run-local-seconds",
    "annotationToPlaybackCrosswalk/sourceCoordinate" -> "topic-notebook-repaired-seconds",
    "annotationToPlaybackCrosswalk/whyNotRepaired" -> "",
    "annotationToPlaybackCrosswalk/tailHandling" -> "trimmed",
    "annotationToPlaybackCrosswalk/runs/0/mapping" -> "affine",
    "annotationToPlaybackCrosswalk/runs/1/formula" -> "playbackTicks = rawAnnotationSeconds + 1419",
    "annotationToPlaybackCrosswalk/runs/0/partId" -> "media-part-b",
    "annotationToPlaybackCrosswalk/runs/0/axisId" -> "media-part-b-playback-ticks",
    "annotationToPlaybackCrosswalk/runs/1/runId" -> "run-1",
    "annotationToPlaybackCrosswalk/runs/1/sourceCoordinate" -> "scanner-tr",
    "annotationToPlaybackCrosswalk/runs/1/annotationRows" -> "482-1000",
    "annotationToPlaybackCrosswalk/runs/0/annotationRows" -> "1-999999999999999999999",
    "coordinateSystems/6/id" -> "media-part-b-playback-ticks",
    "coordinateSystems/6/partId" -> "media-part-b",
    "coordinateSystems/7/editionReceipt" -> "foreign",
    "coordinateSystems/0/parts/0/partId" -> "media-part-b",
    "repair/runAssignment/0/sourceRows" -> "1-481",
    "repair/formula/repairedStartSeconds" -> "rawStartSeconds",
    "repair/boundaryChecks/0/runId" -> "run-2",
    "repair/serializationReceipt/recordSeparator" -> "CRLF",
    "coordinateSystems/3/derivationReceipt" -> "foreign",
    "missingnessAndTails/tailHandling" -> "imputed"
  )
  invalidStrings.foreach { (path, value) =>
    test(s"unsupported or crossed declaration refuses: $path") {
      assert(TimebaseRepair.parse(changed(path, Json.fromString(value))).isLeft, path)
    }
  }
  private val invalidNumbers = Vector(
    "schemaVersion" -> 1L,
    "presentationEditionIdentity/parts/0/presentationOrdinal" -> 2L,
    "presentationEditionIdentity/parts/0/video/durationTicks" -> 3565501L,
    "presentationEditionIdentity/parts/1/video/ticksPerSecond" -> 1000L,
    "presentationEditionIdentity/parts/0/video/startPts" -> 1L,
    "presentationEditionIdentity/parts/0/video/frameCount" -> Long.MaxValue,
    "coordinateSystems/6/durationTicks" -> 42L,
    "coordinateSystems/6/ticksPerSecond" -> 2501L,
    "coordinateSystems/6/ticksPerFrame" -> 101L,
    "coordinateSystems/0/parts/0/endSeconds" -> 9999L,
    "annotationToPlaybackCrosswalk/runs/0/annotationStartSeconds" -> Long.MaxValue,
    "annotationToPlaybackCrosswalk/runs/0/playbackStartTicks" -> 1L,
    "annotationToPlaybackCrosswalk/runs/1/annotationEndSeconds" -> Long.MaxValue,
    "annotationToPlaybackCrosswalk/runs/1/playbackEndTicks" -> Long.MaxValue,
    "annotationToPlaybackCrosswalk/runs/0/uncoveredTailTicks" -> Long.MaxValue,
    "annotationToPlaybackCrosswalk/runs/1/uncoveredTailSeconds" -> 11L,
    "annotationToPlaybackCrosswalk/runs/0/playbackEndFrame" -> 35649L,
    "annotationToPlaybackCrosswalk/exactness/nonIntegerValues" -> 1L,
    "repair/inputRows" -> 999L,
    "repair/retainedRows" -> 999L,
    "repair/serializationReceipt/recordCount" -> 999L,
    "repair/offsetSeconds/run-2" -> 1426L,
    "coordinateSystems/1/runs/0/ordinaryEndSeconds" -> 1420L,
    "coordinateSystems/1/runs/0/rawEndIncludingScanBreakSeconds" -> 1419L,
    "coordinateSystems/3/rowCount" -> 999L,
    "coordinateSystems/3/endSeconds" -> 3000L,
    "coordinateSystems/2/maximumTr" -> 2L,
    "coordinateSystems/2/minimumTr" -> 1975L,
    "repair/boundaryChecks/0/sourceRow" -> 481L,
    "repair/boundaryChecks/1/repairedStartSeconds" -> 1420L,
    "missingnessAndTails/partA/rawAnnotationToMediaTailSeconds" -> 7L,
    "missingnessAndTails/partB/annotationEndSeconds" -> 1545L,
    "missingnessAndTails/scanBreakRows/1/startSeconds" -> 1421L
  )
  invalidNumbers.foreach { (path, value) =>
    test(s"inconsistent numeric declaration refuses: $path") {
      assert(TimebaseRepair.parse(changed(path, Json.fromLong(value))).isLeft, path)
    }
  }

  test("malformed, missing, duplicate and unsupported semantic collections refuse") {
    val changes = Vector(
      "presentationEditionIdentity/parts" -> Json.arr(),
      "presentationEditionIdentity/parts/0/sha256" -> Json.Null,
      "scientificRestrictions" -> Json.arr(),
      "scientificRestrictions/editionIdentityEstablishes" -> Json.arr(
        Json.fromString("recall-to-playback-alignment")
      ),
      "repair/dropSourceRows" -> Json.arr(Json.fromInt(481), Json.fromInt(481)),
      "coordinateSystems/2/missingBoundsImputed" -> Json.True,
      "annotationToPlaybackCrosswalk/runs" -> Json.arr(),
      "sourceDerivation/sha256" -> Json.fromString("not a hash")
    )
    changes.foreach { (path, value) =>
      assert(TimebaseRepair.parse(changed(path, value)).isLeft, path)
    }
    assert(TimebaseRepair.parse("not JSON").isLeft)
    assert(TimebaseRepair.load(Path.of("deliberately-absent-repair-record.json")).isLeft)
  }

  test("changing the single media admission pin changes identity and refuses the old bundle") {
    val old = record
    val changedRecord = TimebaseRepair
      .parse(
        changed(
          "presentationEditionIdentity/parts/0/sha256",
          Json.fromString(Checksum.ofText("different admitted bytes").hex)
        )
      )
      .toOption
      .get
    assertNotEquals(changedRecord.manifest.partA.sha256, old.manifest.partA.sha256)
    assertNotEquals(changedRecord.checksum, old.checksum)
    assert(TimebaseRepair.clockRepairs(changedRecord, bundles(old)).isLeft)
    assert(TimebaseRepair.clockRepairs(changedRecord, bundles(changedRecord)).isRight)
  }

  test("bundle identity extent timebase and population are checked against the selected record") {
    val rec = record
    val actual = bundles(rec)
    val a = rec.manifest.partA
    val b = actual(rec.manifest.partB.partId)
    assert(TimebaseRepair.clockRepairs(rec, actual.updated(a.partId, b)).isLeft)
    assert(TimebaseRepair.clockRepairs(rec, actual - a.partId).isLeft)
    assert(TimebaseRepair.clockRepairs(rec, actual.updated("extra", b)).isLeft)
    for bad <- Vector(a.copy(durationTicks = a.durationTicks + 1), a.copy(ticksPerSecond = 1000)) do
      val bundle = SherlockAnnotations.partBundle(bad).toOption.get
      assert(TimebaseRepair.clockRepairs(rec, actual.updated(a.partId, bundle)).isLeft)
  }

  test("record digest owns the parsed bytes and changes when explanation changes") {
    val bytes = source.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    val rec = TimebaseRepair.parse(bytes).toOption.get
    bytes(0) = 0
    assertEquals(rec.checksum, record.checksum)
    val altered = TimebaseRepair
      .parse(
        changed(
          "annotationToPlaybackCrosswalk/whyNotRepaired",
          Json.fromString("A revised explanation with the same implemented mapping.")
        )
      )
      .toOption
      .get
    assertNotEquals(altered.checksum, rec.checksum)
    assertNotEquals(
      TimebaseRepair.clockRepairs(altered, bundles(altered)).toOption.get.head.receipt,
      TimebaseRepair.clockRepairs(rec, bundles(rec)).toOption.get.head.receipt
    )
  }
