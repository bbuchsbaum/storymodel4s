package storymodel4s.corpus.intake

import java.nio.charset.StandardCharsets

import munit.FunSuite
import storymodel4s.corpus.intake.SherlockAnnotations.*
import storymodel4s.core.*

/** Courts for the Sherlock annotation adapter, on a synthetic six-row fixture shaped like the real
  * table (two runs, a scene opening each run, a zero-duration row, a scan-break-style row with
  * blank TRs). The real 1000-row table never enters the repository (fixture policy 14(d)); the
  * runner verifies it live against the pinned manifest hash.
  */
class SherlockAnnotationsSuite extends FunSuite:

  private val header =
    "Segment Number\tStart Time (s) \tEnd Time (s) \tStart TR\tEnd TR\tScene Segments\t" +
      "Scene Details - A Level \tSpace-In/Outdoor\tName - All\tName - Focus\tName - Speaking\t" +
      "Location\tCamera Angle\tMusic Presence \tWords on Screen "

  /** Six rows: run 1 = rows 1-4 on part A, run 2 = rows 5-6 on part B. Row 3 is zero-duration; row
    * 4 has blank TRs (scan-break shape).
    */
  private val fixtureRows = Vector(
    "1\t0\t10\t1\t7\t1. Opening\tA man walks in the rain.\tOutdoor\tMan\tMan\t\tStreet\tLong\tNo\t",
    "2\t10\t20\t8\t14\t\tThe man finds a red door and knocks twice.\tOutdoor\tMan\t\tMan\tStreet\tMedium\tNo\t",
    "3\t20\t20\t14\t14\t\tSmash cut to black.\tIndoor\t\t\t\t\tClose\tNo\t",
    "4\t20\t30\t\t\t\tBlack screen while the projector is switched.\tIndoor\t\t\t\t\tLong\tNo\t",
    "5\t0\t5\t21\t24\t2. Cartoon\tPeople in costumes parade and sing.\tIndoor\tSingers\tSingers\tSingers\tCartoon World\tLong\tYes\t",
    "6\t5\t12\t25\t29\t\tPopcorn pops in a glass machine.\tIndoor\tSinger\t\tSinger\tCartoon World\tMedium\tYes\t"
  )

  private def bytesOf(rows: Vector[String]): Array[Byte] =
    (header +: rows).mkString("\n").getBytes(StandardCharsets.UTF_8)

  private def recordFor(bytes: Array[Byte]): TimebaseRepair.Record =
    val stream = getClass.getResourceAsStream("/sherlock-synthetic-repair.json")
    val json =
      try new String(stream.readAllBytes(), StandardCharsets.UTF_8)
      finally stream.close()
    val changed = json.replace("0" * 64, Checksum.ofBytes(bytes).hex)
    TimebaseRepair.parse(changed).fold(e => fail(e.message), identity)

  private def parseFixture(rows: Vector[String] = fixtureRows): Either[DomainError, Atlas] =
    val bytes = bytesOf(rows)
    SherlockAnnotations.parse(bytes, recordFor(bytes))

  private def parsed: Atlas =
    parseFixture().fold(e => fail(s"fixture must parse: ${e.message}"), identity)

  test("the observed scanner extent must equal the declaration even when boundaries fit") {
    val bytes = bytesOf(fixtureRows)
    val original = recordFor(bytes).document
    val changed = original.hcursor
      .downField("coordinateSystems")
      .downN(2)
      .downField("maximumTr")
      .withFocus(_ => io.circe.Json.fromInt(30))
      .top
      .get
    assertEquals(
      changed.hcursor.downField("coordinateSystems").downN(2).get[Int]("maximumTr"),
      Right(30)
    )
    val record = TimebaseRepair.parse(changed.noSpaces).fold(e => fail(e.message), identity)
    assert(SherlockAnnotations.parse(bytes, record).isLeft)
    assert(parseFixture().isRight)
  }

  test("the synthetic table parses with both runs, scenes, and typed missing TRs") {
    val atlas = parsed
    assertEquals(atlas.rows.size, 6)
    assertEquals(
      atlas.scenes.map(s => (s.ordinal, s.firstRow, s.lastRow)),
      Vector((1, 1, 4), (2, 5, 6))
    )
    assertEquals(atlas.runOf(4), RunId.Run1)
    assertEquals(atlas.runOf(5), RunId.Run2)
    val scanBreak = atlas.rows(3)
    assertEquals(scanBreak.startTr, None)
    assertEquals(scanBreak.endTr, None)
    assertEquals(atlas.rows(1).namesAll, Vector("Man"))
    assertEquals(atlas.rows(4).location, Some("Cartoon World"))
  }

  test("the crosswalk is the identity seconds-to-ticks mapping onto the run's own part") {
    val atlas = parsed
    atlas.mediaByRow(1) match
      case MediaLocus.Extent(part, iv) =>
        assertEquals(part, "media-part-a")
        assertEquals(iv.start, 0L)
        assertEquals(iv.endExclusive, 25000L)
      case other => fail(s"row 1 must map to an extent, got $other")
    // Run 2 restarts at tick 0 on part B; its seconds are never offset onto a concatenated clock.
    atlas.mediaByRow(5) match
      case MediaLocus.Extent(part, iv) =>
        assertEquals(part, "media-part-b")
        assertEquals(iv.start, 0L)
        assertEquals(iv.endExclusive, 12500L)
      case other => fail(s"row 5 must map to an extent, got $other")
    // The last run-2 row may end flush with the part extent.
    assertEquals(atlas.mediaByRow(6).endTick, 30000L)
  }

  test("a zero-duration row becomes a playback instant, never a fabricated interval") {
    parsed.mediaByRow(3) match
      case MediaLocus.Instant(part, at) =>
        assertEquals(part, "media-part-a")
        assertEquals(at.at, 50000L)
      case other => fail(s"row 3 must map to an instant, got $other")
  }

  test("each part bundle carries its own playback axis with the pinned extent") {
    val atlas = parsed
    assertEquals(atlas.partABundle.primaryAxis.id == atlas.partBBundle.primaryAxis.id, false)
    val extents = Vector(atlas.partABundle, atlas.partBBundle).map(_.primaryAxis.extent)
    assertEquals(
      extents.collect { case pt: storymodel4s.core.AxisExtent.PlaybackTicks => pt.endExclusive },
      Vector(76000L, 30000L)
    )
  }

  test("foreign bytes refuse before any parsing") {
    val bytes = bytesOf(fixtureRows)
    val foreign = recordFor("something else entirely".getBytes(StandardCharsets.UTF_8))
    SherlockAnnotations.parse(bytes, foreign) match
      case Left(DomainError.InvariantViolation(path, _)) =>
        assertEquals(path, "sherlock/annotation/identity")
      case other => fail(s"expected an identity refusal, got $other")
  }

  test("a row-count mismatch refuses") {
    parseFixture(fixtureRows.dropRight(1)) match
      case Left(DomainError.InvariantViolation(path, reason)) =>
        assertEquals(path, "sherlock/annotation/rows")
        assert(reason.contains("expected 6"), reason)
      case other => fail(s"expected a row-count refusal, got $other")
  }

  test("a numbering break refuses") {
    val broken = fixtureRows.updated(2, fixtureRows(2).replaceFirst("^3\t", "7\t"))
    parseFixture(broken) match
      case Left(DomainError.InvariantViolation(path, reason)) =>
        assertEquals(path, "sherlock/row/3")
        assert(reason.contains("numbering breaks"), reason)
      case other => fail(s"expected a numbering refusal, got $other")
  }

  test("non-integer seconds refuse: the crosswalk claims integer bounds") {
    val broken = fixtureRows.updated(1, fixtureRows(1).replaceFirst("\t10\t20\t", "\t10.5\t20\t"))
    parseFixture(broken) match
      case Left(DomainError.InvariantViolation(path, reason)) =>
        assertEquals(path, "sherlock/row/2")
        assert(reason.contains("not an integer second"), reason)
      case other => fail(s"expected an integer refusal, got $other")
  }

  test("reversed bounds refuse") {
    val broken = fixtureRows.updated(1, fixtureRows(1).replaceFirst("\t10\t20\t", "\t20\t10\t"))
    parseFixture(broken) match
      case Left(DomainError.InvariantViolation(path, reason)) =>
        assertEquals(path, "sherlock/row/2")
        assert(reason.contains("reversed"), reason)
      case other => fail(s"expected a reversed-bounds refusal, got $other")
  }

  test("a coordinate escaping the part's playback extent refuses instead of stretching") {
    val broken = fixtureRows.updated(5, fixtureRows(5).replaceFirst("\t5\t12\t", "\t5\t13\t"))
    parseFixture(broken) match
      case Left(e)  => assert(e.message.contains("escapes"), e.message)
      case Right(_) => fail("a 13 s bound on a 12 s part must refuse")
  }

  test("an empty description refuses") {
    val broken = fixtureRows.updated(
      2,
      "3\t20\t20\t14\t14\t\t\tIndoor\t\t\t\t\tClose\tNo\t"
    )
    parseFixture(broken) match
      case Left(DomainError.InvariantViolation(path, reason)) =>
        assertEquals(path, "sherlock/row/3")
        assert(reason.contains("description"), reason)
      case other => fail(s"expected a description refusal, got $other")
  }

  test("a table whose first row opens no scene refuses") {
    val broken = fixtureRows.updated(0, fixtureRows(0).replaceFirst("\t1\\. Opening\t", "\t\t"))
    parseFixture(broken) match
      case Left(DomainError.InvariantViolation(path, _)) =>
        assertEquals(path, "sherlock/scenes")
      case other => fail(s"expected a scene refusal, got $other")
  }

  test("omitting the record refuses; every accepted row carries the executed repair") {
    assert(SherlockAnnotations.parse(bytesOf(fixtureRows)).isLeft)
    val atlas = parsed
    assertEquals(atlas.repairByRow.keySet, atlas.mediaByRow.keySet)
    assertEquals(atlas.repairByRow(1), atlas.repairsByRun("run-1"))
    assertEquals(atlas.repairByRow(5), atlas.repairsByRun("run-2"))
    assert(
      atlas.repairByRow.values.forall(
        _.receipt.inputChecksums.contains(atlas.repairRecord.checksum)
      )
    )
  }

  test("the actual projection seam refuses crossed run and target axes") {
    val atlas = parsed
    val run1 = atlas.repairRecord.runs.head
    val run2 = atlas.repairRecord.runs(1)
    val one = ExactRational.integer(1L)
    assertEquals(
      SherlockAnnotations
        .project(one, run1.sourceAxis, atlas.partABundle, atlas.repairsByRun("run-1"))
        .toOption,
      Some(2500L)
    )
    assert(
      SherlockAnnotations
        .project(one, run2.sourceAxis, atlas.partABundle, atlas.repairsByRun("run-1"))
        .isLeft
    )
    assert(
      SherlockAnnotations
        .project(one, run1.sourceAxis, atlas.partBBundle, atlas.repairsByRun("run-1"))
        .isLeft
    )
    assert(
      ClockRepair
        .projectRunLocalSeconds(one, run1.sourceAxis, atlas.partABundle.primaryAxis.id)
        .isLeft
    )
    assert(
      SherlockAnnotations
        .locusFor(atlas.rows.head, run1, atlas.partABundle, atlas.repairsByRun("run-1"))
        .isRight
    )
    assert(
      SherlockAnnotations
        .locusFor(atlas.rows.head, run1, atlas.partABundle, atlas.repairsByRun("run-2"))
        .isLeft
    )
  }

  test("the projection seam refuses fractional ticks and exact arithmetic overflow") {
    val atlas = parsed
    val run = atlas.repairRecord.runs.head
    val repair = atlas.repairsByRun("run-1")
    val fraction = ExactRational.of(1L, 3L).toOption.get
    assert(SherlockAnnotations.project(fraction, run.sourceAxis, atlas.partABundle, repair).isLeft)
    assert(
      SherlockAnnotations
        .project(ExactRational.integer(Long.MaxValue), run.sourceAxis, atlas.partABundle, repair)
        .isLeft
    )
  }

  test("oversized integer annotation seconds refuse without throwing") {
    val broken = fixtureRows.updated(
      0,
      fixtureRows.head.replace("\t0\t10\t", "\t99999999999999999999999\t10\t")
    )
    assert(parseFixture(broken).isLeft)
  }

  test("composed source atlas retains exact native and primary row and scene support") {
    val input = parsed
    val model = SherlockSourceAtlas.of(input).fold(e => fail(e.message), identity)
    val primary = model.atlas.bundle.primaryAxis
    assertEquals(primary.extent.asInstanceOf[AxisExtent.PlaybackTicks].endExclusive, 106000L)
    assertEquals(model.rows.keySet, (1 to 6).toSet)
    assertEquals(model.atlas.units.size, 8)
    val point = model.rows(3).support
    assertEquals(
      point.anchors.toVector.collect { case EvidenceAnchor.MediaPoint(_, _, at) =>
        (at.axis, at.at)
      },
      Vector((input.partABundle.primaryAxis.id, 50000L), (primary.id, 50000L))
    )
    val secondRun = model.rows(5).support
    assertEquals(
      secondRun.intervalsOn(input.partBBundle.primaryAxis.id).toOption.get.intervals.head.start,
      0L
    )
    assertEquals(secondRun.intervalsOn(primary.id).toOption.get.intervals.head.start, 76000L)
    val scene = model.scenes(1).support.playbackOn(primary.id).toOption.get
    assertEquals(scene.intervals.map(i => (i.start, i.endExclusive)), Vector((0L, 75000L)))
    assertEquals(scene.points.map(_.at), Vector(50000L))
    assert(model.atlas.units.forall(_.support.checkedOn(model.atlas.bundle).isRight))
  }

  test("a point-only scene remains a point-only checked proposal unit") {
    def label(row: String, name: String): String =
      row.split("\t", -1).updated(5, name).mkString("\t")
    val rows = fixtureRows
      .updated(2, label(fixtureRows(2), "2. Instant"))
      .updated(3, label(fixtureRows(3), "3. Switch"))
    val input = parseFixture(rows).fold(e => fail(e.message), identity)
    val model = SherlockSourceAtlas.of(input).fold(e => fail(e.message), identity)
    val pointScene =
      model.scenes(2).support.playbackOn(model.atlas.bundle.primaryAxis.id).toOption.get
    assertEquals(pointScene.intervals, Vector.empty)
    assertEquals(pointScene.points.map(_.at), Vector(50000L))
  }

  test("scene union preserves the genuine gap between part extents") {
    val rows = fixtureRows.updated(4, fixtureRows(4).split("\t", -1).updated(5, "").mkString("\t"))
    val input = parseFixture(rows).fold(e => fail(e.message), identity)
    val model = SherlockSourceAtlas.of(input).fold(e => fail(e.message), identity)
    val support = model.scenes(1).support.playbackOn(model.atlas.bundle.primaryAxis.id).toOption.get
    assertEquals(support.intervals.map(i => (i.start, i.endExclusive)),
      Vector((0L, 75000L), (76000L, 106000L)))
    assertEquals(support.points.map(_.at), Vector(50000L))
  }

  test("composition receipt binds the exact admitted record annotation and ordered part identities") {
    val input = parsed
    val model = SherlockSourceAtlas.of(input).fold(e => fail(e.message), identity)
    val expected = Vector(input.repairRecord.checksum, input.manifest.annotationSha256,
      input.partABundle.identity, input.partBBundle.identity)
    model.compositions.values.foreach { mapping =>
      assertEquals(mapping.receipt.algorithm, "sherlock/presentation-composition/v1")
      assertEquals(mapping.receipt.inputChecksums, expected)
    }
  }

  test("different admitted part rates use exact LCM ticks for both native and primary support") {
    val bytes = bytesOf(fixtureRows)
    def playback(json: io.circe.Json): io.circe.Json = json.mapObject(_
      .add("timeBase", io.circe.Json.fromString("1/1000"))
      .add("ticksPerSecond", io.circe.Json.fromLong(1000L))
      .add("ticksPerFrame", io.circe.Json.fromLong(40L))
      .add("durationTicks", io.circe.Json.fromLong(12000L)))
    val changed = recordFor(bytes).document.hcursor
      .downField("coordinateSystems").downN(7).withFocus(playback).top.get.hcursor
      .downField("presentationEditionIdentity").downField("parts").downN(1)
      .downField("video").withFocus(playback).top.get.hcursor
      .downField("annotationToPlaybackCrosswalk").downField("runs").downN(1)
      .downField("playbackEndTicks").withFocus(_ => io.circe.Json.fromLong(12000L)).top.get
    val record = TimebaseRepair.parse(changed.noSpaces).fold(e => fail(e.message), identity)
    val input = SherlockAnnotations.parse(bytes, record).fold(e => fail(e.message), identity)
    val model = SherlockSourceAtlas.of(input).fold(e => fail(e.message), identity)
    val axis = model.atlas.bundle.primaryAxis
    assertEquals(axis.timebase, Some(RationalTimebase.of(1L, 5000L).toOption.get))
    assertEquals(axis.extent.asInstanceOf[AxisExtent.PlaybackTicks].endExclusive, 212000L)
    val a = model.rows(1).support
    assertEquals(a.intervalsOn(input.partABundle.primaryAxis.id).toOption.get.intervals.head.endExclusive, 25000L)
    assertEquals(a.intervalsOn(axis.id).toOption.get.intervals.head.endExclusive, 50000L)
    assertEquals(model.rows(3).support.playbackOn(axis.id).toOption.get.points.map(_.at), Vector(100000L))
    val b = model.rows(6).support
    val native = b.intervalsOn(input.partBBundle.primaryAxis.id).toOption.get.intervals.head
    val primary = b.intervalsOn(axis.id).toOption.get.intervals.head
    assertEquals((native.start, native.endExclusive), (5000L, 12000L))
    assertEquals((primary.start, primary.endExclusive), (177000L, 212000L))
  }
