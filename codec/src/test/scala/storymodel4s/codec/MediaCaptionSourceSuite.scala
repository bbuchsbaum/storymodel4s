package storymodel4s.codec

import io.circe.Json
import munit.FunSuite
import storymodel4s.core.*

/** Authored sparse multipart clocks distinguish imported declarations from observed execution. */
class MediaCaptionSourceSuite extends FunSuite:
  private def right[A](value: Either[?, A]): A = value.fold(e => fail(e.toString), identity)
  private def str(s: String): Json = Json.fromString(s)
  private def hash(s: String): Checksum = Checksum.ofText(s)
  private def change(j: Json, key: String, value: Json): Json = j.mapObject(_.add(key, value))
  private def part(
      name: String,
      start: Long,
      denominator: Int,
      count: Int,
      last: Int,
      indices: Vector[Int]
  ): Json =
    val model = hash("model")
    val recipe = hash("recipe")
    val worker = hash("worker")
    val frameId = hash(s"frames-$name")
    val frameSha = hash(s"pixels-$name")
    val parameters =
      s"worker ${worker.hex} model ${model.hex} recipe ${recipe.hex} request $name edit-list:identity-assumed"
    val receipt = right(
      SourceDerivationReceipt.of("vlm-caption-proposals/v3", parameters, Vector(frameId, frameSha))
    )
    val pts = Vector(start, start + 40L)
    Json.obj(
      "occurrence" -> str(name),
      "edition" -> str(name),
      "input" -> str(hash(name).hex),
      "start" -> str(start.toString),
      "end" -> str((start + 50L).toString),
      "numerator" -> str("1"),
      "denominator" -> str(denominator.toString),
      "presentedCount" -> Json.fromInt(count),
      "samples" -> Json.fromValues(Vector(0, last).zip(pts).map { (ordinal, at) =>
        Json.obj(
          "ordinal" -> Json.fromInt(ordinal),
          "pts" -> str(at.toString),
          "duration" -> str("10")
        )
      }),
      "framesIdentity" -> str(frameId.hex),
      "framesChecksum" -> str(frameSha.hex),
      "receipt" -> Json.obj(
        "algorithm" -> str(receipt.algorithm),
        "parameters" -> str(parameters),
        "inputs" -> Json.fromValues(receipt.inputChecksums.map(c => str(c.hex))),
        "identity" -> str(receipt.identity.hex)
      ),
      "model" -> str(model.hex),
      "recipe" -> str(recipe.hex),
      "worker" -> str(worker.hex),
      "request" -> str(name),
      "proposals" -> Json.arr(
        Json.obj(
          "id" -> str("cross-part"),
          "text" -> str("red"),
          "samples" -> Json.fromValues(indices.map(Json.fromInt)),
          "start" -> str(pts(indices.head).toString),
          "end" -> str((pts(indices.last) + 10L).toString)
        )
      )
    )
  private def document: Json = Json.obj(
    "schemaVersion" -> str(MediaCaptionSourceCodec.Schema),
    "packageManifest" -> str(hash("manifest").hex),
    "packageIdentity" -> str(hash("package").hex),
    "edition" -> str("synthetic-parts"),
    "parts" -> Json.arr(part("a", 0L, 10, 5, 4, Vector(0)), part("b", 100L, 20, 6, 4, Vector(0, 1)))
  )
  private def first(j: Json)(f: Json => Json): Json =
    val rows = j.hcursor.get[Vector[Json]]("parts").toOption.get
    change(j, "parts", Json.fromValues(f(rows.head) +: rows.tail))

  test("native film clocks, Draft provenance and exact sparse coverage survive import"):
    val imported = right(MediaCaptionSourceCodec.decode(document.noSpaces))
    assertEquals(imported.authority, ObservationAuthority.Draft)
    assertEquals(imported.view.parts.values.map(_.sourceKind).toSet, Set(SourceKind.FilmEdition))
    assertEquals(
      imported.view.parts("a").primaryAxis.timebase,
      Some(right(RationalTimebase.of(1, 10)))
    )
    assertEquals(
      imported.view.parts("b").primaryAxis.timebase,
      Some(right(RationalTimebase.of(1, 20)))
    )
    assertEquals(imported.sampledOrdinals("a"), Some(Vector(0, 4)))
    assertEquals(imported.examinedOrdinals("a"), Some(Vector(0)))
    assertEquals(imported.unexaminedOrdinalRanges("a"), Some(Vector(1 until 5)))
    assertEquals(imported.unexaminedOrdinalRanges("b"), Some(Vector(1 until 4, 5 until 6)))
    assertEquals(imported.evidence.map(_.captions.head.support.start), Vector(0L, 100L))
    assertEquals(imported.evidence.map(_.captions.head.support.endExclusive), Vector(10L, 150L))
    assertEquals(right(MediaCaptionSourceCodec.decode(imported.artifact)), imported)
    println("MEDIA_CAPTION_SOURCE_FIXTURE\t" + imported.artifact)

  test("changing a caption hull without changing its sampled timestamps refuses"):
    val bad = first(document) { p =>
      val caption = p.hcursor.get[Vector[Json]]("proposals").toOption.get.head
      change(p, "proposals", Json.arr(change(caption, "end", str("50"))))
    }
    assert(
      MediaCaptionSourceCodec
        .decode(bad.noSpaces)
        .left
        .toOption
        .exists(_.contains("sampled timestamps"))
    )

  test("a caption cannot name a sample missing from its own part"):
    val bad = first(document) { p =>
      val caption = p.hcursor.get[Vector[Json]]("proposals").toOption.get.head
      change(p, "proposals", Json.arr(change(caption, "samples", Json.arr(Json.fromInt(2)))))
    }
    assert(MediaCaptionSourceCodec.decode(bad.noSpaces).isLeft)

  test("caption recipe and frame provenance cannot be replaced independently"):
    assert(
      MediaCaptionSourceCodec
        .decode(first(document)(p => change(p, "model", str(hash("foreign").hex))).noSpaces)
        .isLeft
    )
    assert(
      MediaCaptionSourceCodec
        .decode(
          first(document)(p => change(p, "framesIdentity", str(hash("foreign").hex))).noSpaces
        )
        .isLeft
    )

  test("unknown end duration remains unavailable rather than becoming a point or a hull"):
    val bad = first(document) { p =>
      val samples = p.hcursor.get[Vector[Json]]("samples").toOption.get
      change(
        p,
        "samples",
        Json.fromValues(change(samples.head, "duration", Json.Null) +: samples.tail)
      )
    }
    assert(MediaCaptionSourceCodec.decode(bad.noSpaces).isLeft)

  test("repeated media occurrences require composition rather than invented edition identity"):
    val rows = document.hcursor.get[Vector[Json]]("parts").toOption.get
    val duplicate = change(rows.head, "occurrence", str("repeat"))
    val bad = change(document, "parts", Json.arr(rows.head, duplicate))
    assert(
      MediaCaptionSourceCodec.decode(bad.noSpaces).left.toOption.exists(_.contains("composition"))
    )

  test("an unknown origin schema or duplicate JSON key refuses"):
    assert(
      MediaCaptionSourceCodec
        .decode(change(document, "schemaVersion", str("verified-video/v1")).noSpaces)
        .isLeft
    )
    assert(
      MediaCaptionSourceCodec.decode(document.noSpaces.replaceFirst("\\{", "{\"parts\":[],")).isLeft
    )

  test("unknown unexamined sample duration survives canonical round trip"):
    val value = first(document) { original =>
      val p = change(original, "presentedCount", Json.fromInt(6))
      val samples = p.hcursor.get[Vector[Json]]("samples").toOption.get
      change(
        p,
        "samples",
        Json.fromValues(Vector(samples.head, change(samples(1), "duration", Json.Null)))
      )
    }
    val imported = right(MediaCaptionSourceCodec.decode(value.noSpaces))
    assertEquals(right(MediaCaptionSourceCodec.decode(imported.artifact)), imported)
    assert(imported.artifact.contains("\"duration\":null"))

  test("caption request order cannot reverse native source order"):
    val original = document.hcursor.get[Vector[Json]]("parts").toOption.get.head
    val early = original.hcursor.get[Vector[Json]]("proposals").toOption.get.head
    val late = early.mapObject(
      _.add("id", str("late"))
        .add("text", str("late"))
        .add("samples", Json.arr(Json.fromInt(1)))
        .add("start", str("40"))
        .add("end", str("50"))
    )
    val reverse = change(original, "proposals", Json.arr(late, early))
    val forward = change(original, "proposals", Json.arr(early, late))
    val reversed =
      right(MediaCaptionSourceCodec.decode(change(document, "parts", Json.arr(reverse)).noSpaces))
    val ordered =
      right(MediaCaptionSourceCodec.decode(change(document, "parts", Json.arr(forward)).noSpaces))
    assertEquals(reversed.view.segments.map(_.text), Vector("red", "late"))
    assertEquals(reversed.evidence.map(_.captions.head.support.start), Vector(0L, 40L))
    assertEquals(reversed.view.segments, ordered.view.segments)
    val leaves = reversed.view.nodes.filter(_.level == 0).sortBy(_.discoursePosition)
    assertEquals(
      reversed.view.adjacency(storymodel4s.align.RelationLayer.DiscourseSuccession),
      Map(leaves(0).ref -> Map(leaves(1).ref -> 1.0))
    )

  test("sampled endpoint ordinals must agree with the declared full-part axis"):
    val wrongStart = first(document)(p => change(p, "start", str("-1")))
    val wrongEnd = first(document)(p => change(p, "end", str("51")))
    assert(
      MediaCaptionSourceCodec
        .decode(wrongStart.noSpaces)
        .left
        .toOption
        .exists(_.contains("endpoint"))
    )
    assert(
      MediaCaptionSourceCodec.decode(wrongEnd.noSpaces).left.toOption.exists(_.contains("endpoint"))
    )

  test("an entirely unexamined part retains its clock and all missing coverage"):
    val value = first(document)(p => change(p, "proposals", Json.arr()))
    val imported = right(MediaCaptionSourceCodec.decode(value.noSpaces))
    assertEquals(imported.examinedOrdinals("a"), Some(Vector.empty))
    assertEquals(imported.unexaminedOrdinalRanges("a"), Some(Vector(0 until 5)))
    assertEquals(imported.view.parts.size, 2)
    assertEquals(imported.evidence.size, 1)
